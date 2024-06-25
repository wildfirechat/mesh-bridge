package cn.wildfirechat.bridge.multiport;

import cn.wildfirechat.bridge.jpa.DomainRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${server.internalPort}")
    private int internalPort;

    @Value("${server.internalPathPrefix}")
    private String internalPathPrefix;

    @Value("${server.adminPort}")
    private int adminPort;

    @Value("${server.adminPathPrefix}")
    private String adminPathPrefix;

    @Autowired
    DomainRepository domainRepository;

    @Bean
    public FilterRegistrationBean<InternalEndpointsFilter> trustedEndpointsFilter() {
        return new FilterRegistrationBean<>(new InternalEndpointsFilter(internalPort, internalPathPrefix, adminPort, adminPathPrefix, domainRepository));
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("/index.html");
    }
}