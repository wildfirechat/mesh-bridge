package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.jpa.Domain;
import cn.wildfirechat.bridge.jpa.DomainRepository;
import cn.wildfirechat.bridge.jpa.User;
import cn.wildfirechat.bridge.jpa.UserRepository;
import cn.wildfirechat.bridge.pojo.DomainRequest;
import cn.wildfirechat.bridge.pojo.DomainView;
import cn.wildfirechat.bridge.shiro.DBSessionDao;
import cn.wildfirechat.bridge.utilis.AdminResult;
import cn.wildfirechat.bridge.utilis.PasswordHasher;
import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.pojos.InputOutputDomainInfo;
import cn.wildfirechat.pojos.mesh.PojoDomainPingResponse;
import cn.wildfirechat.sdk.MeshAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static cn.wildfirechat.bridge.utilis.AdminResult.AdminCode.*;

@Service
public class AdminService {
    private static final Logger LOG = LoggerFactory.getLogger(AdminService.class);

    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long LOGIN_WINDOW_MILLIS = 5 * 60 * 1000L;

    @Autowired
    DomainRepository domainRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DBSessionDao dbSessionDao;

    @Value("${bridge.allow_http_domains:false}")
    private boolean allowHttpDomains;

    private final Map<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();

    public AdminResult login(HttpServletResponse httpResponse, String account, String password, String remoteAddr) {
        if (!StringUtils.hasText(account) || !StringUtils.hasText(password)) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        }

        String attemptKey = account.toLowerCase() + "|" + (remoteAddr == null ? "" : remoteAddr);
        if (isLocked(attemptKey)) {
            LOG.warn("admin login locked, account={}, ip={}", account, remoteAddr);
            return AdminResult.error(ERROR_FAILURE_TOO_MUCH_TIMES);
        }

        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(account, password);

        try {
            subject.login(token);
        } catch (AuthenticationException ae) {
            recordFailure(attemptKey);
            LOG.warn("admin login failed, account={}, ip={}", account, remoteAddr);
            token.clear();
            // 不区分用户不存在/密码错误，避免账户枚举
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        }

        if (!subject.isAuthenticated()) {
            recordFailure(attemptKey);
            token.clear();
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        }

        loginAttempts.remove(attemptKey);

        Optional<User> optionalUser = userRepository.findByAccount(account);
        if (optionalUser.isPresent() && !PasswordHasher.isModern(optionalUser.get().getPasswordMd5())) {
            User user = optionalUser.get();
            user.setPasswordMd5(PasswordHasher.hash(password));
            user.setSalt("");
            userRepository.save(user);
            LOG.info("upgraded legacy password hash for account={}", account);
        }

        Object sessionId = subject.getSession().getId();
        httpResponse.setHeader("authToken", sessionId.toString());
        token.clear();
        LOG.info("admin login success, account={}, ip={}", account, remoteAddr);
        return AdminResult.ok();
    }

    public AdminResult logout() {
        Subject subject = SecurityUtils.getSubject();
        if (subject != null) {
            subject.logout();
        }
        return AdminResult.ok();
    }

    public AdminResult updatePassword(String oldPassword, String newPassword) {
        Subject subject = SecurityUtils.getSubject();
        if (!subject.isAuthenticated()) {
            return AdminResult.error(ERROR_NOT_LOGIN);
        }
        if (!StringUtils.hasText(oldPassword) || !StringUtils.hasText(newPassword) || newPassword.length() < 8) {
            return AdminResult.error(ERROR_MISS_PARAMETER);
        }
        String account = (String) subject.getPrincipal();
        Optional<User> optionalUser = userRepository.findByAccount(account);
        if (!optionalUser.isPresent()) {
            return AdminResult.error(ERROR_NOT_EXIST);
        }

        User user = optionalUser.get();
        if (!PasswordHasher.verify(oldPassword, user.getSalt(), user.getPasswordMd5())) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        }

        user.setPasswordMd5(PasswordHasher.hash(newPassword));
        user.setSalt("");
        userRepository.save(user);

        // 让该账号所有已存在的会话失效，防止旧 token 在改密后继续使用
        dbSessionDao.deleteByPrincipal(account);
        try {
            subject.logout();
        } catch (Exception e) {
            LOG.warn("logout current subject after password change failed", e);
        }
        LOG.info("admin password changed, account={}", account);
        return AdminResult.ok();
    }

    public String getUserId() {
        Subject subject = SecurityUtils.getSubject();
        if (subject.isAuthenticated()) {
            return (String) subject.getPrincipal();
        }
        return null;
    }

    public AdminResult getAccount() {
        Subject subject = SecurityUtils.getSubject();
        if (subject.isAuthenticated()) {
            return AdminResult.ok(subject.getPrincipal());
        }
        return AdminResult.error(ERROR_NOT_EXIST);
    }

    public Object createDomain(DomainRequest request) throws Exception {
        AdminResult validation = validateDomain(request);
        if (validation.getCode() != 0) {
            return validation;
        }
        Optional<Domain> optionalDomain = domainRepository.findById(request.domainId);
        if (optionalDomain.isPresent()) {
            return AdminResult.error(ERROR_ALREADY_EXIST);
        }

        Domain domain = new Domain();
        copyDomainFields(request, domain);
        domain.mySecret = UUID.randomUUID().toString();
        domainRepository.save(domain);

        InputOutputDomainInfo domainInfo = toDomainInfo(domain);
        IMResult<Void> imResult = MeshAdmin.createDomain(domainInfo);
        if (imResult.getErrorCode() != ErrorCode.ERROR_CODE_SUCCESS) {
            throw new Exception("Fail create domain in im server");
        }
        LOG.info("admin {} created domain {}", getUserId(), domain.domainId);
        return AdminResult.ok();
    }

    public Object updateDomain(DomainRequest request) throws Exception {
        AdminResult validation = validateDomain(request);
        if (validation.getCode() != 0) {
            return validation;
        }
        Optional<Domain> optionalDomain = domainRepository.findById(request.domainId);
        if (!optionalDomain.isPresent()) {
            return AdminResult.error(ERROR_NOT_EXIST);
        }

        Domain domain = optionalDomain.get();
        copyDomainFields(request, domain);
        // 空 secret 表示不修改，避免前端不回显对端密钥时被清空
        if (StringUtils.hasText(request.secret)) {
            domain.secret = request.secret;
        }
        // mySecret 不接受客户端输入，保持原值
        domainRepository.save(domain);

        InputOutputDomainInfo domainInfo = toDomainInfo(domain);
        IMResult<Void> imResult = MeshAdmin.createDomain(domainInfo);
        if (imResult.getErrorCode() != ErrorCode.ERROR_CODE_SUCCESS) {
            throw new Exception("Fail update domain in im server");
        }
        LOG.info("admin {} updated domain {}", getUserId(), domain.domainId);
        return AdminResult.ok();
    }

    public Object deleteDomain(String domainId) throws Exception {
        if (!StringUtils.hasText(domainId)) {
            return AdminResult.error(ERROR_MISS_PARAMETER);
        }
        domainRepository.deleteById(domainId);
        IMResult<Void> imResult = MeshAdmin.deleteDomain(domainId);
        if (imResult.getErrorCode() != ErrorCode.ERROR_CODE_SUCCESS) {
            throw new Exception("delete domain from im server failure!");
        }
        LOG.info("admin {} deleted domain {}", getUserId(), domainId);
        return AdminResult.ok();
    }

    public Object getDomain(String domainId) {
        if (!StringUtils.hasText(domainId)) {
            return AdminResult.error(ERROR_MISS_PARAMETER);
        }
        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if (optionalDomain.isPresent()) {
            LOG.info("admin {} viewed domain {} (including own secret)", getUserId(), domainId);
            return AdminResult.ok(DomainView.from(optionalDomain.get(), true));
        }
        return AdminResult.error(ERROR_NOT_EXIST);
    }

    public Object listDomain() {
        Iterable<Domain> iterable = domainRepository.findAll();
        List<DomainView> list = new ArrayList<>();
        for (Domain domain : iterable) {
            list.add(DomainView.from(domain, false));
        }
        return AdminResult.ok(list);
    }

    public Object pingDomain(String domainId) {
        if (!StringUtils.hasText(domainId)) {
            return AdminResult.error(ERROR_MISS_PARAMETER);
        }
        String result;
        try {
            IMResult<PojoDomainPingResponse> responseIMResult = MeshAdmin.pingDomain(domainId);
            if (responseIMResult.getErrorCode() == ErrorCode.ERROR_CODE_SUCCESS) {
                if (responseIMResult.result.success) {
                    result = "测试成功";
                } else {
                    result = "测试失败，错误信息：" + responseIMResult.getResult().errorMessage;
                }
            } else {
                result = "测试失败，错误码：" + responseIMResult.getErrorCode();
            }
        } catch (Exception e) {
            LOG.warn("ping domain {} failed", domainId, e);
            result = "请求到IM服务失败，请查看服务端日志";
        }
        return AdminResult.ok(result);
    }

    private AdminResult validateDomain(DomainRequest request) {
        if (request == null || !StringUtils.hasText(request.domainId)) {
            return AdminResult.error(ERROR_MISS_PARAMETER);
        }
        if (!request.domainId.matches("^[A-Za-z0-9._-]{1,64}$")) {
            return AdminResult.error(ERROR_MISS_PARAMETER);
        }
        if (tooLong(request.name, 64) || tooLong(request.detailInfo, 256) || tooLong(request.email, 64)
            || tooLong(request.tel, 64) || tooLong(request.address, 64) || tooLong(request.extra, 1024)
            || tooLong(request.secret, 64) || tooLong(request.url, 1024)) {
            return AdminResult.error(ERROR_MISS_PARAMETER);
        }
        if (StringUtils.hasText(request.url)) {
            try {
                URI uri = new URI(request.url);
                String scheme = uri.getScheme();
                if (scheme == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && (uri.getPath().contains("..") || uri.getPath().contains("//")))) {
                    return AdminResult.error(ERROR_MISS_PARAMETER);
                }
                scheme = scheme.toLowerCase();
                if (!"https".equals(scheme) && !(allowHttpDomains && "http".equals(scheme))) {
                    return AdminResult.error(ERROR_MISS_PARAMETER);
                }
            } catch (Exception e) {
                return AdminResult.error(ERROR_MISS_PARAMETER);
            }
        }
        return AdminResult.ok();
    }

    private boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private void copyDomainFields(DomainRequest request, Domain domain) {
        domain.domainId = request.domainId;
        domain.url = request.url;
        domain.detailInfo = request.detailInfo;
        domain.email = request.email;
        domain.tel = request.tel;
        domain.name = request.name;
        domain.address = request.address;
        domain.extra = request.extra;
        if (StringUtils.hasText(request.secret)) {
            domain.secret = request.secret;
        }
    }

    private InputOutputDomainInfo toDomainInfo(Domain domain) {
        InputOutputDomainInfo domainInfo = new InputOutputDomainInfo();
        domainInfo.domainId = domain.domainId;
        domainInfo.name = domain.name;
        domainInfo.desc = domain.detailInfo;
        domainInfo.email = domain.email;
        domainInfo.tel = domain.tel;
        domainInfo.address = domain.address;
        domainInfo.extra = domain.extra;
        return domainInfo;
    }

    private boolean isLocked(String key) {
        LoginAttempt attempt = loginAttempts.get(key);
        if (attempt == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (attempt.lockedUntil > now) {
            return true;
        }
        if (now - attempt.firstFailure > LOGIN_WINDOW_MILLIS) {
            loginAttempts.remove(key, attempt);
        }
        return false;
    }

    private void recordFailure(String key) {
        long now = System.currentTimeMillis();
        loginAttempts.compute(key, (k, old) -> {
            if (old == null || now - old.firstFailure > LOGIN_WINDOW_MILLIS) {
                old = new LoginAttempt();
                old.firstFailure = now;
            }
            old.failures++;
            if (old.failures >= MAX_LOGIN_FAILURES) {
                old.lockedUntil = now + LOGIN_WINDOW_MILLIS;
            }
            return old;
        });
        if (loginAttempts.size() > 10_000) {
            loginAttempts.entrySet().removeIf(entry -> entry.getValue().lockedUntil < System.currentTimeMillis()
                && System.currentTimeMillis() - entry.getValue().firstFailure > LOGIN_WINDOW_MILLIS);
        }
    }

    private static class LoginAttempt {
        int failures;
        long firstFailure;
        long lockedUntil;
    }
}
