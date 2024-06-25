package cn.wildfirechat.bridge.multiport;


import cn.wildfirechat.bridge.jpa.Domain;
import cn.wildfirechat.bridge.jpa.DomainRepository;
import org.apache.catalina.connector.RequestFacade;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import javax.servlet.*;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class InternalEndpointsFilter implements Filter {
    private static final Logger LOG= LoggerFactory.getLogger(InternalEndpointsFilter.class);
    private final int internalPort;
    private final String internalPathPrefix;

    private final int adminPort;
    private final String adminPathPrefix;
    private final DomainRepository domainRepository;

    private final String BAD_REQUEST = String.format("{\"code\":%d,\"error\":true,\"errorMessage\":\"%s\"}",
            HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase());

    private final String UNAUTHORIZED = String.format("{\"code\":%d,\"error\":true,\"errorMessage\":\"%s\"}",
            HttpStatus.UNAUTHORIZED.value(), HttpStatus.UNAUTHORIZED.getReasonPhrase());

    public InternalEndpointsFilter(int internalPort, String internalPathPrefix, int adminPort, String adminPathPrefix, DomainRepository domainRepository) {
        this.internalPort = internalPort;
        this.internalPathPrefix = internalPathPrefix;
        this.adminPort = adminPort;
        this.adminPathPrefix = adminPathPrefix;
        this.domainRepository = domainRepository;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        if (servletRequest.getLocalPort() != adminPort
            && servletRequest.getLocalPort() != internalPort
            && !((RequestFacade) servletRequest).getRequestURI().startsWith("/api")) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        boolean isInternalAPI = ((RequestFacade) servletRequest).getRequestURI().startsWith(internalPathPrefix);
        boolean isInternalPort = servletRequest.getLocalPort() == internalPort;

        boolean isAdminAPI = ((RequestFacade) servletRequest).getRequestURI().startsWith(adminPathPrefix);
        boolean isAdminPort = servletRequest.getLocalPort() == adminPort;
        if(isAdminPort && !isAdminAPI) {
            if(((RequestFacade) servletRequest).getRequestURI().equals("/") || ((RequestFacade) servletRequest).getRequestURI().equals("/index.html") || ((RequestFacade) servletRequest).getRequestURI().startsWith("/assets/")) {
                isAdminAPI = true;
            }
        }

        boolean isExternalAPI = !isInternalAPI && !isAdminAPI;
        boolean isExternalPort = !isInternalPort && !isAdminPort;

        boolean isExternalHello = isExternalAPI && "/api/hello".equals(((RequestFacade) servletRequest).getRequestURI());

        if((isInternalAPI && isInternalPort) || (isAdminAPI && isAdminPort) || (isExternalAPI && isExternalPort)) {
            if(isExternalAPI && !isExternalHello) {
                String nonce = ((RequestFacade) servletRequest).getHeader("nonce");
                if (!StringUtils.hasText(nonce)) {
                    nonce = ((RequestFacade) servletRequest).getHeader("Nonce");
                }
                String timestamp = ((RequestFacade) servletRequest).getHeader("timestamp");
                if (!StringUtils.hasText(timestamp)) {
                    timestamp = ((RequestFacade) servletRequest).getHeader("Timestamp");
                }
                String sign = ((RequestFacade) servletRequest).getHeader("sign");
                if (!StringUtils.hasText(sign)) {
                    sign = ((RequestFacade) servletRequest).getHeader("Sign");
                }

                String domainId = ((RequestFacade) servletRequest).getHeader("x-domain-id");
                if (!StringUtils.hasText(sign)) {
                    domainId = ((RequestFacade) servletRequest).getHeader("X-domain-id");
                }

                if (StringUtils.hasText(nonce) && StringUtils.hasText(timestamp) && StringUtils.hasText(sign) && StringUtils.hasText(domainId)) {
                    long ts = Long.parseLong(timestamp);
                    if(StringUtils.hasText(domainId)) {
                        if(ts > 0 && System.currentTimeMillis() - ts < 2 * 60 * 60 * 1000) {
                            Optional<Domain> optionalEntity = domainRepository.findById(domainId);
                            if(optionalEntity.isPresent()) {
                                String secret = optionalEntity.get().mySecret;
                                String str = nonce + "|" + secret + "|" + timestamp;
                                String localSign = DigestUtils.sha1Hex(str);
                                if(localSign.equals(sign)) {
                                    filterChain.doFilter(servletRequest, servletResponse);
                                    return;
                                } else {
                                    LOG.error("sign for domain {} is incorrect", domainId);
                                }
                            } else {
                                LOG.error("domain {} is not exist", domainId);
                            }
                        } else {
                            LOG.error("timestamp header miss or expired");
                        }
                    } else {
                        LOG.error("request miss x-domain-id header");
                    }
                } else {
                    LOG.error("request miss auth headers");
                }
                ((HttpServletResponse) servletResponse).setStatus(HttpStatus.UNAUTHORIZED.value());
                servletResponse.getOutputStream().write(UNAUTHORIZED.getBytes(StandardCharsets.UTF_8));
                servletResponse.getOutputStream().close();
            } else {
                filterChain.doFilter(servletRequest, servletResponse);
            }
        } else {
            ((HttpServletResponse) servletResponse).setStatus(HttpStatus.BAD_REQUEST.value());
            servletResponse.getOutputStream().write(BAD_REQUEST.getBytes(StandardCharsets.UTF_8));
            servletResponse.getOutputStream().close();
        }
    }
}
