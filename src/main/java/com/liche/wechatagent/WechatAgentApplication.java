package com.liche.wechatagent;

import com.liche.wechatagent.config.AlertProperties;
import com.liche.wechatagent.config.ManagementAccessProperties;
import com.liche.wechatagent.config.AgentPolicyProperties;
import com.liche.wechatagent.config.MemoryPolicyProperties;
import com.liche.wechatagent.config.QqRuntimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({ManagementAccessProperties.class, AgentPolicyProperties.class,
        MemoryPolicyProperties.class, QqRuntimeProperties.class, AlertProperties.class})
public class WechatAgentApplication {

    public static void main(String[] args) {
        pinDefaultTimeZone();
        SpringApplication.run(WechatAgentApplication.class, args);
    }

    /**
     * 把 JVM 默认时区钉在应用时区上。
     *
     * <p>容器默认时区往往是 UTC，而 Quartz 的 Cron 表达式、Spring 的 {@code @Scheduled(cron)}、
     * 以及 JDBC 驱动对 {@code LocalDateTime} 的换算**都按 JVM 默认时区**来——一旦容器环境变化
     * （例如 compose 少了 TZ），"每天 8 点"会变成当地 16 点跑，落库时间还会整体偏 8 小时。
     * 这里在启动最开始就固定它，代码正确性不再依赖容器环境变量（compose 里的 TZ 仍保留，双保险）。
     */
    private static void pinDefaultTimeZone() {
        String configured = System.getenv().getOrDefault("APP_TIME_ZONE",
                System.getProperty("app.time-zone", "Asia/Shanghai"));
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(java.time.ZoneId.of(configured)));
        } catch (RuntimeException ignored) {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        }
    }
}
