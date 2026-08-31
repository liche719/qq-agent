package com.liche.wechatagent;

import com.liche.wechatagent.config.ManagementAccessProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(ManagementAccessProperties.class)
public class WechatAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(WechatAgentApplication.class, args);
    }
}
