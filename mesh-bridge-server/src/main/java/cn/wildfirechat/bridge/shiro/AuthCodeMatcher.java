package cn.wildfirechat.bridge.shiro;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.credential.CredentialsMatcher;
import org.springframework.stereotype.Service;

@Service
public class AuthCodeMatcher implements CredentialsMatcher {

    @Override
    public boolean doCredentialsMatch(AuthenticationToken token, AuthenticationInfo info) {
        // AuthCodeRealm 已从 SecurityManager 移除。保留 fail-closed，避免未来误启用时无条件通过。
        return false;
    }
}
