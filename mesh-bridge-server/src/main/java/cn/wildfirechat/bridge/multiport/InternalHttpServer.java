package cn.wildfirechat.bridge.multiport;


import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class InternalHttpServer {

    @Value("${server.internalPort}")
    private int internalPort;

    @Value("${server.adminPort}")
    private int adminPort;

    @Bean
    public ServletWebServerFactory servletContainer() {
        Connector internalConnector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        internalConnector.setScheme("http");
        internalConnector.setPort(internalPort);

        Connector adminConnector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        adminConnector.setScheme("http");
        adminConnector.setPort(adminPort);

        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
        tomcat.addAdditionalTomcatConnectors(internalConnector, adminConnector);
        return tomcat;
    }
}
