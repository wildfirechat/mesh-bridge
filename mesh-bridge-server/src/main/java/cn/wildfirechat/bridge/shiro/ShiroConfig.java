package cn.wildfirechat.bridge.shiro;


import org.apache.shiro.SecurityUtils;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class ShiroConfig {

    @Autowired
    DBSessionDao dbSessionDao;

    @Value("${bridge.session_timeout_minutes:60}")
    private long sessionTimeoutMinutes;

    @Bean(name = "shiroFilter")
    public ShiroFilterFactoryBean shiroFilter(SecurityManager securityManager) {
        ShiroFilterFactoryBean shiroFilterFactoryBean = new ShiroFilterFactoryBean();
        shiroFilterFactoryBean.setSecurityManager(securityManager);
        shiroFilterFactoryBean.setLoginUrl("/login");
        shiroFilterFactoryBean.setUnauthorizedUrl("/notRole");
        Map<String, String> filterChainDefinitionMap = new LinkedHashMap<>();

        filterChainDefinitionMap.put("/js/*", "anon");
        filterChainDefinitionMap.put("/fonts/*", "anon");
        filterChainDefinitionMap.put("/img/*", "anon");
        filterChainDefinitionMap.put("/css/*", "anon");
        filterChainDefinitionMap.put("/assets/*", "anon");
        filterChainDefinitionMap.put("/", "anon");

        filterChainDefinitionMap.put("/api/**", "anon");
        filterChainDefinitionMap.put("/internal/**", "anon");
        filterChainDefinitionMap.put("/admin/hello", "anon");
        filterChainDefinitionMap.put("/admin/login", "anon");

        // 已登录用户都可以查看自己的账号、改自己的密码、注销
        filterChainDefinitionMap.put("/admin/account", "login");
        filterChainDefinitionMap.put("/admin/update_pwd", "login");
        filterChainDefinitionMap.put("/admin/logout", "login");

        // 域管理属于管理员操作，user 角色不能执行
        filterChainDefinitionMap.put("/admin/**", "login,perms[user:admin]");

        shiroFilterFactoryBean.setFilterChainDefinitionMap(filterChainDefinitionMap);
        shiroFilterFactoryBean.getFilters().put("login", new JsonAuthLoginFilter());
        shiroFilterFactoryBean.getFilters().put("perms", new WfPermissionsAuthorizationFilter());
        return shiroFilterFactoryBean;
    }

    @Bean
    public SecurityManager securityManager() {
        DefaultWebSecurityManager defaultSecurityManager = new DefaultWebSecurityManager();
        defaultSecurityManager.setRealms(Collections.singletonList(passwordRealm));

        ShiroSessionManager sessionManager = new ShiroSessionManager();
        long timeout = Math.max(1, sessionTimeoutMinutes) * 60L * 1000L;
        sessionManager.setGlobalSessionTimeout(timeout);
        sessionManager.setSessionDAO(dbSessionDao);
        // 只接受 authToken 头，不使用 Cookie/URL 重写，避免 CSRF 和 session id 泄漏
        sessionManager.setSessionIdCookieEnabled(false);
        sessionManager.setSessionIdUrlRewritingEnabled(false);

        defaultSecurityManager.setSessionManager(sessionManager);
        SecurityUtils.setSecurityManager(defaultSecurityManager);
        return defaultSecurityManager;
    }

    @Autowired
    private PasswordRealm passwordRealm;
}
