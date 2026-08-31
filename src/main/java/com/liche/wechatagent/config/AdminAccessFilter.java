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

@Component
public class AdminAccessFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Agent-Admin-Key";

    private final ManagementAccessProperties properties;

    public AdminAccessFilter(ManagementAccessProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/clawbot") && !path.startsWith("/api/sim");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (hasValidKey(request) || canUseLoopbackWithoutKey(request)) {
            filterChain.doFilter(request, response);
            return;
        }
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
                && isLoopback(request.getRemoteAddr());
    }

    private boolean isLoopback(String remoteAddress) {
        return "127.0.0.1".equals(remoteAddress) || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress);
    }
}
