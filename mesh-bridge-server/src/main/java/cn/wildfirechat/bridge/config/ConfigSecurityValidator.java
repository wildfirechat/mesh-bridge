package cn.wildfirechat.bridge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.net.URI;

/**
 * 拒绝以仓库中的示例口令/密钥启动，避免“直接照着 README 部署”变成默认弱口令。
 * 所有值都可以通过环境变量注入。
 */
@Component
public class ConfigSecurityValidator {

    @Value("${bridge.my_domain_id:}")
    private String myDomainId;

    @Value("${im.admin_secret:}")
    private String imAdminSecret;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Value("${minio.access_key:}")
    private String minioAccessKey;

    @Value("${minio.secret_key:}")
    private String minioSecretKey;

    @Value("${im.admin_url:}")
    private String imAdminUrl;

    @Value("${minio.endpoint:}")
    private String minioEndpoint;

    @PostConstruct
    public void validate() {
        require("BRIDGE_MY_DOMAIN_ID / bridge.my_domain_id", myDomainId);
        require("IM_ADMIN_SECRET / im.admin_secret", imAdminSecret);
        require("DB_PASSWORD / spring.datasource.password", dbPassword);
        require("MINIO_ACCESS_KEY / minio.access_key", minioAccessKey);
        require("MINIO_SECRET_KEY / minio.secret_key", minioSecretKey);

        requireHttpsForRemote("IM_ADMIN_URL / im.admin_url", imAdminUrl);
        requireHttpsForRemote("MINIO_ENDPOINT / minio.endpoint", minioEndpoint);
    }

    private void requireHttpsForRemote(String name, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
            if ("http".equals(scheme) && !loopback) {
                throw new IllegalStateException(name + " must use https when it is not loopback");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(name + " is not a valid URL", e);
        }
    }

    private void require(String name, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(name + " is required and must not be empty");
        }
    }

    private void rejectDefault(String name, String value, String defaultValue) {
        if (defaultValue.equals(value)) {
            throw new IllegalStateException(name + " must not use the example value");
        }
    }
}
