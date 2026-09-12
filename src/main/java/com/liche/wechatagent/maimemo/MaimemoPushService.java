package com.liche.wechatagent.maimemo;

import com.liche.wechatagent.channel.qq.QqChannel;
import com.liche.wechatagent.config.AlertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日背单词进度推送。
 *
 * <p>到点（默认 21:30）给管理员本人的 QQ 发一条今日进度；每天只发一次（日期记在 {@code maimemo_setting}）。
 * 收件人沿用运维告警的 {@code alert.qq-openid}——也就是用户自己的 QQ。
 * 推送是"尽力而为"：QQ 官方机器人对主动消息有额度限制，失败只记日志，面板里的状态才是准的。
 */
@Service
public class MaimemoPushService {

    private static final Logger log = LoggerFactory.getLogger(MaimemoPushService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final MaimemoService maimemoService;
    private final AlertProperties alertProperties;
    private final ObjectProvider<QqChannel> qqChannel;
    private final ZoneId zone;

    public MaimemoPushService(MaimemoService maimemoService,
                              AlertProperties alertProperties,
                              ObjectProvider<QqChannel> qqChannel,
                              @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.maimemoService = maimemoService;
        this.alertProperties = alertProperties;
        this.qqChannel = qqChannel;
        this.zone = parseZone(timeZone);
    }

    @Scheduled(fixedDelayString = "${maimemo.push-scan-interval-ms:60000}",
            initialDelayString = "${maimemo.push-initial-delay-ms:60000}")
    public void scan() {
        try {
            LocalDateTime now = LocalDateTime.now(zone);
            if (!maimemoService.pushEnabled()) {
                return;
            }
            LocalTime target = maimemoService.pushTime();
            if (now.toLocalTime().isBefore(target)) {
                return;
            }
            if (now.toLocalDate().toString().equals(maimemoService.lastPushDate())) {
                return;
            }
            push();
        } catch (RuntimeException exception) {
            log.warn("背单词推送检查失败：{}", exception.toString());
        }
    }

    /** 面板上的「立即推送一次」：不受时间与"今天已发过"限制 */
    public Map<String, Object> pushNow() {
        return push();
    }

    private Map<String, Object> push() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> snapshot = maimemoService.refresh();
        String status = String.valueOf(snapshot.get("status"));
        if (MaimemoService.STATUS_NOT_CONFIGURED.equals(status) || MaimemoService.STATUS_DISABLED.equals(status)) {
            result.put("sent", false);
            result.put("message", "还没有可用的墨墨 Token，先在本页保存 Token 再推送。");
            return result;
        }
        if (MaimemoService.STATUS_ERROR.equals(status)) {
            result.put("sent", false);
            result.put("message", "读取墨墨数据失败：" + snapshot.get("message"));
            return result;
        }

        String text = MaimemoService.STATUS_UNAUTHORIZED.equals(status)
                ? "⚠️ 墨墨 Token 已失效，今天的背单词进度查不到了。\n去墨墨 App「开放 API」重新生成 Token，再粘贴到运维面板的「背单词」页保存即可。"
                : message(snapshot);
        boolean sent = deliver(text);
        LocalDate today = LocalDate.now(zone);
        // 只有真的发出去了才写「今天已推送」：否则额度受限时失败也记一次，
        // 当天 21:30 的定时推送就永远不会再尝试，用户当天收不到进度。
        if (sent) {
            maimemoService.markPushed(today);
        }
        result.put("sent", sent);
        result.put("text", text);
        result.put("message", sent
                ? "已推送到 QQ（" + today + "）"
                : "推送失败：可能是 QQ 主动消息额度限制，或告警收件人未配置。");
        return result;
    }

    private String message(Map<String, Object> snapshot) {
        @SuppressWarnings("unchecked")
        Map<String, Object> progress = (Map<String, Object>) snapshot.get("progress");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) snapshot.get("todayItems");
        int finished = number(progress.get("finished"));
        int total = number(progress.get("total"));
        int remaining = number(progress.get("remaining"));
        int percent = number(progress.get("percent"));

        StringBuilder sb = new StringBuilder("📖 墨墨今日进度（")
                .append(LocalDateTime.now(zone).format(STAMP)).append("）\n");
        if (total <= 0) {
            sb.append("今天没有安排学习任务。");
        } else {
            sb.append("已完成 ").append(finished).append("/").append(total)
                    .append("（").append(percent).append("%）");
            if (remaining > 0) {
                sb.append("，还剩 ").append(remaining).append(" 个");
            }
            sb.append("\n");
            if (progress.get("newCount") != null) {
                sb.append("新学 ").append(number(progress.get("newCount")))
                        .append(" · 复习 ").append(number(progress.get("reviewCount")))
                        .append(" · 约 ").append(progress.get("studyTimeText")).append("\n");
            }
            if (finished == 0) {
                sb.append("今天还没开始，趁现在背 20 个？");
            } else if (remaining > 0) {
                sb.append("睡前再过一遍就清空了。");
            } else {
                sb.append("今天已经清空啦，很稳 🎉");
            }
        }
        List<String> pending = items.stream()
                .filter(item -> !Boolean.TRUE.equals(item.get("finished")))
                .limit(5)
                .map(item -> String.valueOf(item.get("spelling")))
                .toList();
        if (remaining > 0 && !pending.isEmpty()) {
            sb.append("\n下一批：").append(String.join("、", pending));
        }
        return sb.toString();
    }

    private boolean deliver(String text) {
        String openid = alertProperties.getQqOpenid();
        if (openid == null || openid.isBlank()) {
            log.warn("背单词推送未发送：未配置 alert.qq-openid");
            return false;
        }
        QqChannel qq = qqChannel.getIfAvailable();
        if (qq == null) {
            log.warn("背单词推送未发送：QQ 通道未启用");
            return false;
        }
        try {
            boolean sent = qq.sendTextResultFrom(null, openid, text);
            if (sent) {
                log.info("已推送背单词进度：{}", text.replace('\n', ' '));
            } else {
                log.warn("背单词推送失败（可能是 QQ 主动消息额度限制）");
            }
            return sent;
        } catch (RuntimeException exception) {
            log.warn("背单词推送异常：{}", exception.toString());
            return false;
        }
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
