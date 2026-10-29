package cn.wildfirechat.bridge.multiport;


import cn.wildfirechat.bridge.jpa.Domain;
import cn.wildfirechat.bridge.jpa.DomainRepository;
import cn.wildfirechat.bridge.security.CachedBodyHttpServletRequest;
import cn.wildfirechat.bridge.security.NonceCache;
import cn.wildfirechat.bridge.utilis.SignatureUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 按端口和规范化后的路径把请求分成三类：
 *
 * <ul>
 *   <li>内部端口：只允许 /internal/**，无签名（由防火墙/绑定地址保证只对 IM 服务开放）</li>
 *   <li>管理端口：只允许 /admin/** 与静态资源，由 Shiro 做登录/权限校验</li>
 *   <li>外部端口：只允许 /api/**，除 /api/hello 外必须通过 HMAC-SHA256 签名</li>
 * </ul>
 *
 * 安全判断必须使用容器规范化后的 servletPath，不能使用原始 getRequestURI()，
 * 否则 //api、/x/../api、/x/%2e%2e/api 等路径会绕过认证。
 */
public class InternalEndpointsFilter implements Filter {
    private static final Logger LOG = LoggerFactory.getLogger(InternalEndpointsFilter.class);

    public static final String AUTHENTICATED_DOMAIN_ATTRIBUTE = "bridge.authenticatedDomainId";

    private static final int MAX_DOMAIN_ID_LENGTH = 64;
    private static final int MAX_NONCE_LENGTH = 128;
    private static final int MAX_SIGN_LENGTH = 128;
    private static final int MAX_TIMESTAMP_LENGTH = 20;

    private final int internalPort;
    private final String internalPathPrefix;

    private final int adminPort;
    private final String adminPathPrefix;

    private final DomainRepository domainRepository;
    private final NonceCache nonceCache;
    private final long maxRequestBodyBytes;
    private final long signatureMaxSkewMillis;

    public InternalEndpointsFilter(int internalPort,
                                   String internalPathPrefix,
                                   int adminPort,
                                   String adminPathPrefix,
                                   DomainRepository domainRepository,
                                   NonceCache nonceCache,
                                   long maxRequestBodyBytes,
                                   long signatureMaxSkewSeconds) {
        this.internalPort = internalPort;
        this.internalPathPrefix = internalPathPrefix;
        this.adminPort = adminPort;
        this.adminPathPrefix = adminPathPrefix;
        this.domainRepository = domainRepository;
        this.nonceCache = nonceCache;
        this.maxRequestBodyBytes = maxRequestBodyBytes;
        this.signatureMaxSkewMillis = signatureMaxSkewSeconds * 1000L;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String rawPath = request.getRequestURI();
        String path = normalizedPath(request);
        if (isSuspicious(rawPath) || isSuspicious(path)) {
            reject(response, HttpStatus.BAD_REQUEST, "非法请求路径");
            return;
        }

        int localPort = request.getLocalPort();
        boolean isAdminPort = localPort == adminPort;
        boolean isInternalPort = localPort == internalPort;

        if (isAdminPort) {
            if (!isAdminPath(path)) {
                reject(response, HttpStatus.NOT_FOUND, "Not Found");
                return;
            }
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        if (isInternalPort) {
            if (!path.startsWith(internalPathPrefix)) {
                reject(response, HttpStatus.NOT_FOUND, "Not Found");
                return;
            }
            // 内部接口按设计不做应用层认证，仅允许内部端口访问；生产环境必须用防火墙/绑定地址隔离。
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        // 外部端口只允许 /api/**
        if (!path.startsWith("/api/")) {
            reject(response, HttpStatus.NOT_FOUND, "Not Found");
            return;
        }
        if ("/api/hello".equals(path)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        authenticateExternalRequest(request, response, path, filterChain);
    }

    private void authenticateExternalRequest(HttpServletRequest request,
                                             HttpServletResponse response,
                                             String path,
                                             FilterChain filterChain) throws IOException, ServletException {
        String nonce = request.getHeader("nonce");
        String timestamp = request.getHeader("timestamp");
        String sign = request.getHeader("sign");
        String domainId = request.getHeader("x-domain-id");

        if (!StringUtils.hasText(nonce) || !StringUtils.hasText(timestamp)
            || !StringUtils.hasText(sign) || !StringUtils.hasText(domainId)) {
            LOG.warn("external request missing auth headers, path={}, ip={}", path, request.getRemoteAddr());
            reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized");
            return;
        }
        if (nonce.length() > MAX_NONCE_LENGTH || sign.length() > MAX_SIGN_LENGTH
            || timestamp.length() > MAX_TIMESTAMP_LENGTH || domainId.length() > MAX_DOMAIN_ID_LENGTH) {
            reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized");
            return;
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized");
            return;
        }
        long now = System.currentTimeMillis();
        // 必须双向限制：既不能太旧，也不能在未来。否则把 timestamp 设成 Long.MAX_VALUE 就永不过期。
        if (ts <= 0 || Math.abs(now - ts) > signatureMaxSkewMillis) {
            LOG.warn("external request timestamp expired, domain={}, path={}, ip={}", domainId, path, request.getRemoteAddr());
            reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized");
            return;
        }

        Optional<Domain> optionalDomain = domainRepository.findById(domainId);
        if (!optionalDomain.isPresent()) {
            LOG.warn("external request unknown domain {}, path={}, ip={}", domainId, path, request.getRemoteAddr());
            reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized");
            return;
        }
        String secret = optionalDomain.get().mySecret;
        if (!StringUtils.hasText(secret)) {
            LOG.error("domain {} has empty mySecret, reject request", domainId);
            reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized");
            return;
        }

        String contentType = request.getContentType();
        String mediaType = request.getHeader("x-media-type");
        String fileExtension = request.getHeader("x-file-extension");

        long contentLength;
        String bodySha256;
        HttpServletRequest requestForChain = request;

        if ("/api/transfer_file".equals(path)) {
            // 文件流不缓存，避免大文件占内存；签名覆盖长度、类型和业务头，文件内容完整性由 TLS 保证。
            contentLength = request.getContentLengthLong();
            bodySha256 = "-";
        } else {
            byte[] body;
            try {
                body = readBody(request);
            } catch (IOException e) {
                LOG.warn("external request body too large or unreadable, domain={}, path={}, ip={}", domainId, path, request.getRemoteAddr());
                reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "Payload Too Large");
                return;
            }
            contentLength = body.length;
            bodySha256 = SignatureUtils.sha256Hex(body);
            requestForChain = new CachedBodyHttpServletRequest(request, body);
        }

        String canonical = SignatureUtils.canonical(
            request.getMethod(),
            path,
            request.getQueryString(),
            contentLength,
            bodySha256,
            contentType,
            mediaType,
            fileExtension,
            domainId,
            timestamp,
            nonce);
        String expectedSign = SignatureUtils.hmacSha256Hex(secret, canonical);
        if (!SignatureUtils.constantTimeEquals(expectedSign, sign)) {
            LOG.warn("external request sign mismatch, domain={}, path={}, ip={}", domainId, path, request.getRemoteAddr());
            reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized");
            return;
        }

        long nonceTtl = Math.max(signatureMaxSkewMillis * 2, 60_000L);
        if (!nonceCache.tryUse(domainId + "|" + nonce, now, nonceTtl)) {
            LOG.warn("external request replay detected, domain={}, path={}, ip={}", domainId, path, request.getRemoteAddr());
            reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized");
            return;
        }

        requestForChain.setAttribute(AUTHENTICATED_DOMAIN_ATTRIBUTE, domainId);
        filterChain.doFilter(requestForChain, response);
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxRequestBodyBytes) {
            throw new IOException("body too large");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(declaredLength > 0 ? (int) Math.min(declaredLength, 8192) : 256);
        byte[] buffer = new byte[8192];
        long total = 0;
        try (InputStream input = request.getInputStream()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxRequestBodyBytes) {
                    throw new IOException("body too large");
                }
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private String normalizedPath(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!StringUtils.hasText(path)) {
            path = request.getRequestURI();
        }
        String pathInfo = request.getPathInfo();
        if (StringUtils.hasText(pathInfo)) {
            path = path + pathInfo;
        }
        return path;
    }

    private boolean isAdminPath(String path) {
        return "/".equals(path)
            || "/index.html".equals(path)
            || "/favicon.ico".equals(path)
            || path.startsWith("/assets/")
            || path.startsWith("/js/")
            || path.startsWith("/css/")
            || path.startsWith("/fonts/")
            || path.startsWith("/img/")
            || path.startsWith(adminPathPrefix);
    }

    /**
     * 拒绝路径穿越、重复分隔符、矩阵参数、编码分隔符等所有可能让“原始路径”和
     * “容器规范化后的路径”不一致的写法。
     */
    private boolean isSuspicious(String path) {
        if (path == null) {
            return true;
        }
        String lower = path.toLowerCase();
        return path.indexOf('\0') >= 0
            || path.contains("..")
            || path.contains("//")
            || path.contains("/./")
            || path.endsWith("/.")
            || path.contains(";")
            || path.contains("\\")
            || lower.contains("%2e")
            || lower.contains("%2f")
            || lower.contains("%5c");
    }

    private void reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":" + status.value() + ",\"error\":true,\"errorMessage\":\"" + message + "\"}");
        response.getWriter().flush();
    }
}
