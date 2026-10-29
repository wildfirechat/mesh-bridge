package cn.wildfirechat.bridge.lifecycle;

import cn.wildfirechat.bridge.utilis.HttpUtils;
import cn.wildfirechat.sdk.AdminConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class StartupEventListener implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(StartupEventListener.class);

    @Value("${im.admin_url}")
    String adminUrl;

    @Value("${im.admin_secret}")
    String adminSecret;

    @Value("${bridge.http_response_max_bytes:1048576}")
    long httpResponseMaxBytes;

    @Value("${bridge.allow_http_domains:false}")
    boolean allowHttpDomains;

    @PostConstruct
    void init() {
        AdminConfig.initAdmin(adminUrl, adminSecret);
        HttpUtils.setResponseMaxBytes(httpResponseMaxBytes);
        HttpUtils.setAllowHttpDomains(allowHttpDomains);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        LOG.info("bridge application is ready");
    }
}
