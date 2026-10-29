package cn.wildfirechat.bridge.config;

import cn.wildfirechat.bridge.jpa.User;
import cn.wildfirechat.bridge.jpa.UserRepository;
import cn.wildfirechat.bridge.utilis.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 首次启动时创建管理员，密码由 BRIDGE_ADMIN_INIT_PASSWORD 注入；不再使用 data.sql 中的固定默认账户。
 */
@Component
public class AdminInitializer implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AdminInitializer.class);

    @Autowired
    private UserRepository userRepository;

    @Value("${bridge.admin_account:admin}")
    private String adminAccount;

    @Value("${bridge.admin_init_password:}")
    private String initPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        if (!StringUtils.hasText(initPassword) || initPassword.length() < 8) {
            throw new IllegalStateException("No admin user exists. Set BRIDGE_ADMIN_INIT_PASSWORD (at least 8 characters) for first startup.");
        }
        if (!StringUtils.hasText(adminAccount)) {
            throw new IllegalStateException("bridge.admin_account must not be empty");
        }

        User admin = new User();
        admin.setAccount(adminAccount);
        admin.setRole("admin");
        admin.setSalt("");
        admin.setPasswordMd5(PasswordHasher.hash(initPassword));
        userRepository.save(admin);
        LOG.warn("Created initial admin account '{}'. Please change its password and remove BRIDGE_ADMIN_INIT_PASSWORD.", adminAccount);
    }
}
