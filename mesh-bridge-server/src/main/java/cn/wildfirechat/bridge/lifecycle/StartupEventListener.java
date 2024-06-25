package cn.wildfirechat.bridge.lifecycle;

import cn.wildfirechat.sdk.AdminConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class StartupEventListener implements ApplicationListener<ApplicationReadyEvent> {

    @Value("${im.admin_url}")
    String adminUrl;

    @Value("${im.admin_secret}")
    String adminSecret;

    @PostConstruct
    void init() {
        AdminConfig.initAdmin(adminUrl, adminSecret);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // 在这里编写你的启动后逻辑
        System.out.println("应用程序已准备就绪，可以开始执行操作了！");
        // 例如，你可以访问数据库、启动后台线程、发送通知等。
    }
}