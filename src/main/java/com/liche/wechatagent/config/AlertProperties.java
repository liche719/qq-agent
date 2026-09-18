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
    /**
     * 记忆提取连续失败几次就告警（2026-09-18 加）。默认 2 —— 单次失败会自己重试，连续两次说明真出问题了
     * （最常见是"思考把 max_tokens 吃满 → 正文空 → JSON 解析失败"，面板里记 PARSE_FAILED）。
     * 0 = 不检查这条。
     */
    private int memoryFailureStreak = 2;
    /** 记忆提取**当天**累计花费超过多少元就告警（0 = 不检查）。默认 2 元；正常量级是 0.3~0.4 元/天 */
    private double memoryDailyCostYuan = 2.0d;

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

    public int getMemoryFailureStreak() {
        return memoryFailureStreak;
    }

    public void setMemoryFailureStreak(int memoryFailureStreak) {
        this.memoryFailureStreak = Math.max(0, memoryFailureStreak);
    }

    public double getMemoryDailyCostYuan() {
        return memoryDailyCostYuan;
    }

    public void setMemoryDailyCostYuan(double memoryDailyCostYuan) {
        this.memoryDailyCostYuan = Math.max(0d, memoryDailyCostYuan);
    }
}
