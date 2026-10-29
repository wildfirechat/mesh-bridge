package cn.wildfirechat.bridge.shiro;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 只对配置白名单内的 Origin 返回 CORS 头，不再反射任意 Origin，也不允许携带 Cookie。
 */
@Component
public class CorsFilter extends OncePerRequestFilter {

    @Value("${bridge.cors_allowed_origins:}")
    private String allowedOrigins;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (StringUtils.hasText(origin) && isAllowed(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, X-Requested-With, authToken");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.setHeader("Access-Control-Expose-Headers", "authToken");
            response.setHeader("Access-Control-Max-Age", "600");
        }
        filterChain.doFilter(request, response);
    }

    private boolean isAllowed(String origin) {
        if (!StringUtils.hasText(allowedOrigins)) {
            return false;
        }
        Set<String> allowed = new HashSet<>();
        Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .forEach(allowed::add);
        return allowed.contains(origin);
    }
}
