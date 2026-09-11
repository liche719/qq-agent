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
        return !path.startsWith("/api/clawbot") && !path.startsWith("/api/sim")
                && !path.startsWith("/api/agent/tasks") && !path.startsWith("/api/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setContentType("application/json;charset=UTF-8");
        boolean dashboard = request.getRequestURI().startsWith("/api/admin");
        if (dashboard && !isAllowedIp(request.getRemoteAddr())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("{\"message\":\"来源 IP 不允许访问管理后台\"}");
            return;
        }
        if (dashboard) {
            filterChain.doFilter(request, response);
            return;
        }
        String client = clientKey(request);
        FailureWindow existing = failures.get(client);
        if (existing != null && !existing.expired() && existing.count >= 5) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("{\"message\":\"管理接口暂时封禁，请稍后再试\"}");
            return;
        }
        if (hasValidKey(request) || (!dashboard && canUseLoopbackWithoutKey(request))) {
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
     * 失败封禁的计数键。直连来源是本机回环时（面板经 nginx 反代就是这种情形），
     * 用 X-Forwarded-For 的首个地址区分真实客户端，避免所有人的失败次数互相牵连、
     * 让攻击者靠故意输错把正常用户一起封禁。
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
        String first = forwarded.split(",")[0].trim();
        return first.isEmpty() ? remote : first;
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
