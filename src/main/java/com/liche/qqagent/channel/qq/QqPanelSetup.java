package com.liche.qqagent.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 指令面板与快捷菜单的注册（2026-10-27 从 {@code QqChannel} 拆出，第 7 步）。
 *
 * <p>它在网关 READY 之后跑一次：先查"是否已建过"（用 {@link QqPanels#REMARK} 标记），没有再建。
 * 失败不阻塞消息收发，但要能被面板看到（错误计数）。
 *
 * <p>拆出来的好处：这段是**启动路径**上的 HTTP，出错只会打一行日志然后继续——
 * 放在 1800 行的 channel 里，出问题时很容易被忽略。现在它有独立日志前缀和自己的重试开关。
 */
final class QqPanelSetup {

    private static final Logger log = LoggerFactory.getLogger(QqPanelSetup.class);

    record Gateway(Runnable ensureToken, Supplier<String> accessToken, Supplier<String> apiBase,
                   Function<String, RestClient> client, Supplier<ObjectMapper> json,
                   Consumer<Exception> onApiError) {
    }

    private final Gateway gateway;
    private final boolean enabled;
    /** 只尝试一次：成功或彻底失败都不再重试，避免每次 READY 都打一遍面板接口 */
    private final AtomicBoolean configured = new AtomicBoolean();

    QqPanelSetup(Gateway gateway, boolean enabled) {
        this.gateway = gateway;
        this.enabled = enabled;
    }

    void setupPanel() {
        if (!enabled || !configured.compareAndSet(false, true)) {
            return;
        }
        try {
            gateway.ensureToken().run();
            String existing = gateway.client().apply(gateway.apiBase().get()).get()
                    .uri(uriBuilder -> uriBuilder.path("/v2/panels")
                            .queryParam("scope", "c2c")
                            .queryParam("limit", "50")
                            .build())
                    .header("Authorization", "QQBot " + gateway.accessToken().get())
                    .retrieve().body(String.class);
            if (existing != null && existing.contains(QqPanels.REMARK)) {
                log.info("QQ C2C command panel already exists");
                return;
            }
            Map<String, Object> payload = QqPanels.commandPanelPayload();
            gateway.client().apply(gateway.apiBase().get()).post().uri("/v2/panels")
                    .header("Authorization", "QQBot " + gateway.accessToken().get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(gateway.json().get().writeValueAsString(payload))
                    .retrieve().body(String.class);
            log.info("QQ C2C command panel configured");
        } catch (RestClientResponseException exception) {
            configured.set(false);
            gateway.onApiError().accept(exception);
            logHttpFailure("command panel", exception);
        } catch (Exception exception) {
            configured.set(false);
            gateway.onApiError().accept(exception);
            log.warn("QQ C2C command panel configuration failed: {}", exception.getMessage());
        }
    }

    void setupMenu() {
        if (!enabled) {
            return;
        }
        try {
            gateway.ensureToken().run();
            Map<String, Object> payload = QqPanels.customMenuPayload();
            gateway.client().apply(gateway.apiBase().get()).put().uri("/v2/menu")
                    .header("Authorization", "QQBot " + gateway.accessToken().get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(gateway.json().get().writeValueAsString(payload))
                    .retrieve().body(String.class);
            log.info("QQ C2C custom menu configured");
        } catch (RestClientResponseException exception) {
            gateway.onApiError().accept(exception);
            logHttpFailure("custom menu", exception);
        } catch (Exception exception) {
            gateway.onApiError().accept(exception);
            log.warn("QQ C2C custom menu configuration failed: {}", exception.getMessage());
        }
    }

    /** HTTP 层失败：把平台返回的 code/err_code/trace_id 打出来，否则只有一句"400"没法排查 */
    private void logHttpFailure(String what, RestClientResponseException exception) {
        try {
            JsonNode error = gateway.json().get().readTree(exception.getResponseBodyAsString());
            log.warn("QQ C2C {} configuration failed: httpStatus={} qqCode={} errCode={} traceId={} message={}",
                    what, exception.getStatusCode().value(), error.path("code").asInt(0),
                    error.path("err_code").asLong(0), error.path("trace_id").asText(""),
                    error.path("message").asText(""));
        } catch (Exception ignored) {
            log.warn("QQ C2C {} configuration failed: httpStatus={} body={}", what,
                    exception.getStatusCode().value(), exception.getResponseBodyAsString());
        }
    }
}
