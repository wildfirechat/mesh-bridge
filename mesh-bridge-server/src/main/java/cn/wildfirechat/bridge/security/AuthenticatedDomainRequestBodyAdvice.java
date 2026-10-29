package cn.wildfirechat.bridge.security;

import cn.wildfirechat.bridge.multiport.InternalEndpointsFilter;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Type;

/**
 * 外部接口的请求体里通常也带一个 domainId 字段。该字段不可信，
 * 这里统一用过滤器认证出来的 x-domain-id 覆盖它，避免“头是 A 域、体里写 B 域”的越权。
 */
@ControllerAdvice
public class AuthenticatedDomainRequestBodyAdvice extends RequestBodyAdviceAdapter {

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (!(inputMessage instanceof org.springframework.http.server.ServletServerHttpRequest)) {
            return body;
        }
        HttpServletRequest request = ((org.springframework.http.server.ServletServerHttpRequest) inputMessage).getServletRequest();
        String path = request.getServletPath();
        if (path == null || !path.startsWith("/api/")) {
            return body;
        }

        Object authenticated = request.getAttribute(InternalEndpointsFilter.AUTHENTICATED_DOMAIN_ATTRIBUTE);
        if (!(authenticated instanceof String) || !StringUtils.hasText((String) authenticated)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }

        if (body == null) {
            return null;
        }
        try {
            Field field = findField(body.getClass(), "domainId");
            if (field == null) {
                return body;
            }
            field.setAccessible(true);
            if (field.getType() == String.class) {
                field.set(body, authenticated);
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request body");
        }
        return body;
    }

    private Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
