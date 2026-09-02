package com.liche.wechatagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "management.access")
public class ManagementAccessProperties {

    private String adminApiKey = "";
    private boolean requireKey;
    private boolean allowLoopbackWithoutKey = true;
    private String allowedIps = "127.0.0.1,::1";

    public String getAdminApiKey() {
        return adminApiKey;
    }

    public void setAdminApiKey(String adminApiKey) {
        this.adminApiKey = adminApiKey == null ? "" : adminApiKey.trim();
    }

    public boolean isRequireKey() {
        return requireKey;
    }

    public void setRequireKey(boolean requireKey) {
        this.requireKey = requireKey;
    }

    public boolean isAllowLoopbackWithoutKey() {
        return allowLoopbackWithoutKey;
    }

    public void setAllowLoopbackWithoutKey(boolean allowLoopbackWithoutKey) {
        this.allowLoopbackWithoutKey = allowLoopbackWithoutKey;
    }

    public String getAllowedIps() { return allowedIps; }
    public void setAllowedIps(String allowedIps) { this.allowedIps = allowedIps == null ? "" : allowedIps.trim(); }
}
