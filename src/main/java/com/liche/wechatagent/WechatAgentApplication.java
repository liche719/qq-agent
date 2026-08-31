package com.liche.wechatagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WechatAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(WechatAgentApplication.class, args);
    }
}
