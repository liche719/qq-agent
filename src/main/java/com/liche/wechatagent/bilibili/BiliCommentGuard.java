package com.liche.wechatagent.bilibili;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * B 站评论的护栏（2026-10-20 加）。
 *
 * <p>用户明确说"不用草稿模式，直接发"，所以**安全全靠这里**——而且这些判断必须**从台账里算**，
 * 不能写在提示词里靠模型自觉：它有时候会忘了自己刚才发过。三道硬闸：
 * <ol>
 *   <li>每天最多 N 条；</li>
 *   <li>同一个视频只评一次（复评等于刷屏）；</li>
 *   <li>两条之间至少隔 M 分钟（防止一轮里连着发）。</li>
 * </ol>
 * 另加内容闸：**不放链接、不出现凭据/机器路径、不超长**——这是对用户主号的保护，不是形式主义。
 */
@Service
public class BiliCommentGuard {

    private static final Logger log = LoggerFactory.getLogger(BiliCommentGuard.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 评论里绝不能出现的东西（凭据、机器路径、内部标识） */
    private static final String[] FORBIDDEN = {
            "sessdata", "bili_jct", "csrf", "openid", "token", "密码", "passwd",
            "c:\\", "/opt/", "/home/", "/users/", "/root/", "/var/"
    };

    private final BiliCommentLogRepository logs;
    private final boolean enabled;
    private final int dailyLimit;
    private final int minIntervalMinutes;
    private final int maxChars;

    public BiliCommentGuard(BiliCommentLogRepository logs,
                            @Value("${bilibili.comment.enabled:true}") boolean enabled,
                            @Value("${bilibili.comment.daily-limit:5}") int dailyLimit,
                            @Value("${bilibili.comment.min-interval-minutes:10}") int minIntervalMinutes,
                            @Value("${bilibili.comment.max-chars:200}") int maxChars) {
        this.logs = logs;
        this.enabled = enabled;
        this.dailyLimit = Math.max(1, Math.min(30, dailyLimit));
        this.minIntervalMinutes = Math.max(0, Math.min(240, minIntervalMinutes));
        this.maxChars = Math.max(20, Math.min(1000, maxChars));
    }

    /**
     * 能不能发。
     *
     * @return 空 = 可以；有值 = 拒绝理由（直接给模型看，它会如实转述）
     */
    public Optional<String> reject(String bvid, String message) {
        return reject(bvid, message, null);
    }

    /**
     * 同上，但**区分顶级评论和楼中楼回复**（2026-10-20 加）。
     *
     * <p>为什么必须分：`rootRpid > 0` 是"在某个楼层里回复别人"，而"同一个视频只评一次"
     * 这条规则本来是为了防止**在别人视频底下刷多条独立评论**。如果连回复也拦，
     * 那它一旦在某个视频下评过，别人再怎么回它、它都只能干看着——这不是护栏，是哑巴。
     *
     * <p>所以：顶级评论照旧"一个视频一条"；回复只保留"每条回复只回一次"（由台账判重）
     * 加上每日总量与间隔，防刷屏的底线都还在。
     */
    public Optional<String> reject(String bvid, String message, Long rootRpid) {
        boolean isReply = rootRpid != null && rootRpid > 0;
        if (!enabled) {
            return Optional.of("评论功能被配置关掉了（bilibili.comment.enabled=false），只能看不能发。");
        }
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return Optional.of("评论内容是空的。");
        }
        if (text.length() > maxChars) {
            return Optional.of("评论太长（" + text.length() + " 字，上限 " + maxChars + "），压缩一下再说。");
        }
        if (text.contains("http://") || text.contains("https://")) {
            return Optional.of("评论里不放链接（容易被判广告）。");
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String bad : FORBIDDEN) {
            if (lower.contains(bad)) {
                return Optional.of("评论里出现了不该公开的东西（" + bad + "），换一段话。");
            }
        }

        long today = logs.countSucceededSince(LocalDate.now(ZONE).atStartOfDay());
        if (today >= dailyLimit) {
            return Optional.of("今天已经评论 " + today + " 条，到上限了（" + dailyLimit + " 条/天）。");
        }
        if (!isReply) {
            long sameVideo = logs.countSucceededForVideo(bvid == null ? "" : bvid.trim());
            if (sameVideo > 0) {
                return Optional.of("这个视频已经评过 " + sameVideo + " 次了，同一个视频只评一次。"
                        + "（想接着聊就用回复：把 rootRpid 填你那条评论的 rpid）");
            }
        }
        Optional<LocalDateTime> last = logs.lastSucceededAt();
        if (last.isPresent()) {
            long minutes = Duration.between(last.get(), LocalDateTime.now()).toMinutes();
            if (minutes < minIntervalMinutes) {
                return Optional.of("距离上一条评论才 " + minutes + " 分钟（至少隔 " + minIntervalMinutes + " 分钟）。");
            }
        }
        return Optional.empty();
    }

    /** 不管成没成都要记台账：失败原因本身也是排障证据 */
    public void record(String bvid, long aid, String title, String message,
                       Long rootRpid, Long parentRpid, BilibiliClient.CommentResult result) {
        BiliCommentLog entry = new BiliCommentLog();
        entry.setBvid(bvid == null ? "" : bvid.trim());
        entry.setAid(aid);
        entry.setVideoTitle(clip(title, 250));
        entry.setMessage(clip(message, 2000));
        entry.setRootRpid(rootRpid == null ? 0 : rootRpid);
        entry.setParentRpid(parentRpid == null ? 0 : parentRpid);
        entry.setResultCode(result == null ? -1 : result.code());
        entry.setResultMessage(clip(result == null ? "" : result.message(), 250));
        entry.setRpid(result == null ? 0 : result.rpid());
        entry.setCreatedAt(LocalDateTime.now());
        logs.save(entry);
        if (result != null && result.ok()) {
            log.info("B站评论已发出：bvid={} rpid={} 长度={}", entry.getBvid(), entry.getRpid(), entry.getMessage().length());
        } else {
            log.warn("B站评论失败：bvid={} code={} msg={}", entry.getBvid(),
                    result == null ? -1 : result.code(), result == null ? "" : result.message());
        }
    }

    /** 今天已经发了几条（面板/工具回话用） */
    public long todayCount() {
        return logs.countSucceededSince(LocalDate.now(ZONE).atStartOfDay());
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
