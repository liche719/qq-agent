package com.liche.wechatagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 运维告警推送配置（只推给管理员本人的 QQ，默认关闭） */
@ConfigurationProperties(prefix = "alert")
public class AlertProperties {

    /** 是否启用告警推送 */
    private boolean enabled = false;
    /** 接收告警的 QQ openid（用户标识，不是 QQ 号） */
    private String qqOpenid = "";
    /** 同一个问题最长多久重复提醒一次 */
    private int repeatMinutes = 30;
    /** 检查间隔（毫秒） */
    private long checkIntervalMs = 60_000L;
    /** 磁盘可用空间低于该值时告警（默认 2 GB） */
    private long diskFreeMinBytes = 2L * 1024 * 1024 * 1024;
    /** 堆内存占用超过该百分比时告警 */
    private int heapUsedMaxPercent = 85;
    /** 启动后多久才开始检查（避免重启瞬间网关还没连上就误报） */
    private int startupGraceSeconds = 120;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getQqOpenid() {
        return qqOpenid;
    }

    public void setQqOpenid(String qqOpenid) {
        this.qqOpenid = qqOpenid == null ? "" : qqOpenid.trim();
    }

    public int getRepeatMinutes() {
        return repeatMinutes;
    }

    public void setRepeatMinutes(int repeatMinutes) {
        this.repeatMinutes = Math.max(1, repeatMinutes);
    }

    public long getCheckIntervalMs() {
        return checkIntervalMs;
    }

    public void setCheckIntervalMs(long checkIntervalMs) {
        this.checkIntervalMs = Math.max(10_000L, checkIntervalMs);
    }

    public long getDiskFreeMinBytes() {
        return diskFreeMinBytes;
    }

    public void setDiskFreeMinBytes(long diskFreeMinBytes) {
        this.diskFreeMinBytes = Math.max(0L, diskFreeMinBytes);
    }

    public int getHeapUsedMaxPercent() {
        return heapUsedMaxPercent;
    }

    public void setHeapUsedMaxPercent(int heapUsedMaxPercent) {
        this.heapUsedMaxPercent = Math.min(99, Math.max(1, heapUsedMaxPercent));
    }

    public int getStartupGraceSeconds() {
        return startupGraceSeconds;
    }

    public void setStartupGraceSeconds(int startupGraceSeconds) {
        this.startupGraceSeconds = Math.max(0, startupGraceSeconds);
    }
}
