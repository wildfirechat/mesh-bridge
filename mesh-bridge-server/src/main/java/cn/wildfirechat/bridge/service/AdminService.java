package cn.wildfirechat.bridge.service;

import cn.wildfirechat.bridge.jpa.User;
import cn.wildfirechat.bridge.jpa.UserRepository;
import cn.wildfirechat.common.ErrorCode;
import cn.wildfirechat.bridge.jpa.Domain;
import cn.wildfirechat.bridge.jpa.DomainRepository;
import cn.wildfirechat.bridge.utilis.AdminResult;
import cn.wildfirechat.pojos.InputOutputDomainInfo;
import cn.wildfirechat.pojos.InputOutputUserInfo;
import cn.wildfirechat.pojos.OutputApplicationUserInfo;
import cn.wildfirechat.sdk.MeshAdmin;
import cn.wildfirechat.sdk.UserAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.util.TextUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.*;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static cn.wildfirechat.bridge.utilis.AdminResult.AdminCode.*;


@Service
public class AdminService {
    private static final Logger LOG = LoggerFactory.getLogger(AdminService.class);

    @Autowired
    DomainRepository domainRepository;


    @Autowired
    private UserRepository userRepository;

    public AdminResult login(HttpServletResponse httpResponse, String account, String password) {
        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken(account, password);

        try {
            subject.login(token);
        } catch (UnknownAccountException uae) {
            return AdminResult.error(ERROR_NOT_EXIST);
        } catch (IncorrectCredentialsException ice) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        } catch (LockedAccountException lae) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        } catch (ExcessiveAttemptsException eae) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        } catch (AuthenticationException ae) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        }

        if (subject.isAuthenticated()) {
            long timeout = subject.getSession().getTimeout();
        } else {
            token.clear();
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        }

        Object sessionId = subject.getSession().getId();
        httpResponse.setHeader("authToken", sessionId.toString());

        return AdminResult.ok();
    }

    public AdminResult updatePassword(String oldPassword, String newPassword) {
        Subject subject = SecurityUtils.getSubject();
        if (!subject.isAuthenticated()) {
            return AdminResult.error(ERROR_NOT_LOGIN);
        }
        String account = (String) subject.getPrincipal();
        Optional<User> optionalUser = userRepository.findByAccount(account);
        if (!optionalUser.isPresent()) {
            return AdminResult.error(ERROR_NOT_EXIST);
        }

        User user = optionalUser.get();
        String md5 = new Base64().encodeToString(DigestUtils.getDigest("MD5").digest((oldPassword + user.getSalt()).getBytes(StandardCharsets.UTF_8)));
        if (!md5.equals(user.getPasswordMd5())) {
            return AdminResult.error(ERROR_PASSWORD_INCORRECT);
        }

        String newMd5 = new Base64().encodeToString(DigestUtils.getDigest("MD5").digest((newPassword + user.getSalt()).getBytes(StandardCharsets.UTF_8)));
        user.setPasswordMd5(newMd5);
        userRepository.save(user);

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


    public Object createDomain(Domain domain) throws Exception {
        if(!TextUtils.isEmpty(domain.domainId)) {
            Optional<Domain> optionalDomain = domainRepository.findById(domain.domainId);
            if (optionalDomain.isPresent()) {
                return AdminResult.error(ERROR_ALREADY_EXIST);
            }
        }
        domain.mySecret = UUID.randomUUID().toString();
        domainRepository.save(domain);
        InputOutputDomainInfo domainInfo = new InputOutputDomainInfo();
        domainInfo.domainId = domain.domainId;
        domainInfo.name = domain.name;
        domainInfo.desc = domain.detailInfo;
        domainInfo.email = domain.email;
        domainInfo.tel = domain.tel;
        domainInfo.address = domain.address;
        domainInfo.extra = domain.extra;
        IMResult<Void> imResult =  MeshAdmin.createDomain(domainInfo);
        if(imResult.getErrorCode() != ErrorCode.ERROR_CODE_SUCCESS) {
            throw new Exception("Fail create domain in im server");
        }
        return AdminResult.ok();
    }

    public Object updateDomain(Domain domain) throws Exception {
        if(TextUtils.isEmpty(domain.domainId)) {
            return AdminResult.error(ERROR_MISS_PARAMETER);
        }
        domainRepository.save(domain);
        InputOutputDomainInfo domainInfo = new InputOutputDomainInfo();
        domainInfo.domainId = domain.domainId;
        domainInfo.name = domain.name;
        domainInfo.desc = domain.detailInfo;
        domainInfo.email = domain.email;
        domainInfo.tel = domain.tel;
        domainInfo.address = domain.address;
        domainInfo.extra = domain.extra;
        IMResult<Void> imResult =  MeshAdmin.createDomain(domainInfo);
        if(imResult.getErrorCode() != ErrorCode.ERROR_CODE_SUCCESS) {
            throw new Exception("Fail create domain in im server");
        }
        return AdminResult.ok();
    }

    public Object deleteDomain(String domainId) throws Exception {
        domainRepository.deleteById(domainId);
        IMResult<Void> imResult = MeshAdmin.deleteDomain(domainId);
        if(imResult.getErrorCode() != ErrorCode.ERROR_CODE_SUCCESS) {
            throw new Exception("delete domain from im server failure!");
        }
        return AdminResult.ok();
    }

    public Object getDomain(String domainId) {
        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        return optionalDomain.map(AdminResult::ok).orElseGet(() -> AdminResult.error(ERROR_NOT_EXIST));
    }

    public Object listDomain() {
        Iterable<Domain> iterable = domainRepository.findAll();
        List<Domain> list = new ArrayList<>();
        for (Domain domain : iterable) {
            list.add(domain);
        }
        return AdminResult.ok(list);
    }
}
