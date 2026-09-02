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
        if (hasValidKey(request) || canUseLoopbackWithoutKey(request)) {
            failures.remove(request.getRemoteAddr());
            filterChain.doFilter(request, response);
            return;
        }
        FailureWindow window = failures.compute(request.getRemoteAddr(), (k,v) -> v == null || v.expired() ? new FailureWindow() : v.next());
        if (window.count >= 5) { response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value()); response.getWriter().write("{\"message\":\"管理接口暂时封禁，请稍后再试\"}"); return; }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"管理接口需要管理员密钥\"}");
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
                && isAllowedIp(request.getRemoteAddr());
    }

    private boolean isAllowedIp(String address) {
        return Arrays.stream(properties.getAllowedIps().split(","))
                .map(String::trim).anyMatch(address::equals);
    }

    private record FailureWindow(int count, long blockedUntil) { FailureWindow() { this(1, System.currentTimeMillis()+600_000); } FailureWindow next(){ return new FailureWindow(count+1, blockedUntil); } boolean expired(){ return System.currentTimeMillis()>blockedUntil; } }

    private boolean isLoopback(String remoteAddress) {
        return "127.0.0.1".equals(remoteAddress) || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress);
    }
}
