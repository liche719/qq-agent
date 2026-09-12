package com.liche.wechatagent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AdminAccessFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Agent-Admin-Key";

    private final ManagementAccessProperties properties;
    private final ConcurrentHashMap<String, FailureWindow> failures = new ConcurrentHashMap<>();

    public AdminAccessFilter(ManagementAccessProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/sim")
                && !path.startsWith("/api/agent/tasks") && !path.startsWith("/api/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setContentType("application/json;charset=UTF-8");
        boolean dashboard = request.getRequestURI().startsWith("/api/admin");
        // 需要密钥时（生产/公网直连），密钥就是唯一凭据，不再要求来源 IP 在白名单内；
        // 本机模式（require-key=false）仍然只允许白名单来源，避免误暴露。
        if (dashboard && !properties.isRequireKey() && !isAllowedIp(request.getRemoteAddr())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("{\"message\":\"来源 IP 不允许访问管理后台\"}");
            return;
        }
        String client = clientKey(request);
        FailureWindow existing = failures.get(client);
        if (existing != null && !existing.expired() && existing.count >= 5) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("{\"message\":\"管理接口暂时封禁，请稍后再试\"}");
            return;
        }
        // 带正确密钥，或本机模式下回环地址免密钥（require-key=true 时回环不再免密钥，
        // 面板改由登录页拿到的口令通过 X-Agent-Admin-Key 头校验）。
        if (hasValidKey(request) || canUseLoopbackWithoutKey(request)) {
            failures.remove(client);
            filterChain.doFilter(request, response);
            return;
        }
        failures.entrySet().removeIf(entry -> entry.getValue().expired());
        if (failures.size() >= 1024 && !failures.containsKey(client)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("{\"message\":\"管理接口请求过多，请稍后再试\"}");
            return;
        }
        FailureWindow window = failures.compute(client, (address, previous) -> previous == null || previous.expired() ? new FailureWindow() : previous.next());
        if (window.count >= 5) { response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value()); response.getWriter().write("{\"message\":\"管理接口暂时封禁，请稍后再试\"}"); return; }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"管理接口需要管理员密钥\"}");
    }

    /**
     * 失败封禁的计数键（按真实来源 IP 分开计数，别人乱输不会连累你）。
     *
     * <p>直连来源是回环时说明前面有本机反向代理，取 X-Forwarded-For 的**最后一段**：
     * 代理会把自己看到的真实地址追加在末尾，前面几段是客户端可以随意伪造的，
     * 取第一段会让攻击者用假 IP 绕过封禁、甚至伪造他人 IP 去封禁别人。
     */
    private String clientKey(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isLoopback(remote)) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        String[] parts = forwarded.split(",");
        String last = parts[parts.length - 1].trim();
        return last.isEmpty() ? remote : last;
    }

    private boolean hasValidKey(HttpServletRequest request) {
        String expected = properties.getAdminApiKey();
        String provided = request.getHeader(API_KEY_HEADER);
        if (expected.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8));
    }

    private boolean canUseLoopbackWithoutKey(HttpServletRequest request) {
        return !properties.isRequireKey() && properties.isAllowLoopbackWithoutKey()
                && isLoopback(request.getRemoteAddr());
    }

    private boolean isAllowedIp(String address) {
        return Arrays.stream(properties.getAllowedIps().split(","))
                .map(String::trim).anyMatch(allowed -> allowed.equals(address)
                        || ("::1".equals(allowed) && "0:0:0:0:0:0:0:1".equals(address)));
    }

    private record FailureWindow(int count, long blockedUntil) {
        FailureWindow() {
            this(1, System.currentTimeMillis() + 600_000);
        }

        FailureWindow next() {
            return new FailureWindow(count + 1, count == 4 ? System.currentTimeMillis() + 600_000 : blockedUntil);
        }

        boolean expired() {
            return System.currentTimeMillis() >= blockedUntil;
        }
    }

    private boolean isLoopback(String remoteAddress) {
        return "127.0.0.1".equals(remoteAddress) || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress);
    }
}
