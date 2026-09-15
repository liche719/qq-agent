package com.liche.wechatagent.self;

import com.liche.wechatagent.channel.ProactiveDelivery;
import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 它的**嘴**：把"它想跟机主说的话"真的送出去（三期领域②，2026-09-15 用户要求）。
 *
 * <p>三层分工，别混：
 * <ol>
 *   <li><b>说不说</b>是它的判断——它在自己的时间里用 {@code selfWantToSay} 记下来（{@link AgentQuestTool}）。</li>
 *   <li><b>什么时候说</b>是窗口——只在它自己那几个时间点（默认 7/13/20 点）顺带发，不另开定时。</li>
 *   <li><b>一天能说几条</b>是硬闸——{@code memory.self-speak-daily-limit}（默认 1），
 *       发出去才算数；拦住的不丢，标 {@code SUPPRESSED} 留在库里，面板上能看见"它想说但没说出来"。</li>
 * </ol>
 *
 * <p>投递走既有的 {@link ProactiveDelivery}（按用户最近一次说话的通道发，不猜通道），
 * 所以 QQ 那边的主动消息日额度账本照样生效——**它想说，也不等于能把你的额度吃穿**。
 *
 * <p><b>刻意不加 {@code @ConditionalOnProperty}</b>：面板（{@code AdminSelfController}）注入了它，
 * 给它加条件会让"关掉模块"变成"整个应用起不来"。模块开关由 {@link SelfService#isActive()} 兜底
 * （没开/没配归属人就一律不发）——这正是坑 64/65 里"行为层带条件、数据层不带"的同一件事。
 */
@Service
public class SelfSpeakService {

    private static final Logger log = LoggerFactory.getLogger(SelfSpeakService.class);

    /** 一次投递的结果（定时任务日志与面板用） */
    public record Outcome(boolean sent, String reason, Long utteranceId, String text) {

        static Outcome skipped(String reason) {
            return new Outcome(false, reason, null, null);
        }
    }

    private final SelfService selfService;
    private final UserService userService;
    private final List<WeChatChannel> channels;
    private final boolean enabled;
    private final int dailyLimit;

    public SelfSpeakService(SelfService selfService,
                            UserService userService,
                            List<WeChatChannel> channels,
                            @Value("${memory.self-speak-enabled:true}") boolean enabled,
                            @Value("${memory.self-speak-daily-limit:1}") int dailyLimit) {
        this.selfService = selfService;
        this.userService = userService;
        this.channels = channels;
        this.enabled = enabled;
        this.dailyLimit = Math.max(1, dailyLimit);
    }

    /**
     * 把它攒着的话发一条出去（没有就什么都不做，**不报错**）。
     *
     * <p>幂等性：一次只发一条、发出后立刻改状态，所以同一个窗口重复调用不会重复发。
     */
    public Outcome flush() {
        if (!enabled) {
            return Outcome.skipped("主动开口关着（memory.self-speak-enabled=false）");
        }
        if (!selfService.isActive()) {
            return Outcome.skipped(selfService.inactiveReason());
        }
        String owner = selfService.owner();
        if (owner == null) {
            return Outcome.skipped("未配置归属人");
        }
        long sentToday = selfService.countUtterancesSentToday();
        if (sentToday >= dailyLimit) {
            return Outcome.skipped("今天已经跟他说过 " + sentToday + " 条了（上限 " + dailyLimit + "）");
        }
        Optional<AgentSelfUtterance> pending = selfService.oldestPendingUtterance();
        if (pending.isEmpty()) {
            return Outcome.skipped("没有想说的话");
        }
        AgentSelfUtterance utterance = pending.get();
        String text = utterance.getContent();
        UserProfile profile = userService.get(owner);
        boolean delivered = ProactiveDelivery.send(channels, profile, owner, text);
        if (!delivered) {
            // 通道没送出去：**标 SUPPRESSED 但留着**——"它想说但没说出来"本身就是要观察的东西
            selfService.markUtteranceSuppressed(utterance);
            log.warn("它想说的话没送出去 #{}（通道不可用或额度不够）：{}", utterance.getId(), clip(text));
            return Outcome.skipped("通道没送出去");
        }
        selfService.markUtteranceSent(utterance);
        log.info("它主动说了一条 #{}：{}", utterance.getId(), clip(text));
        return new Outcome(true, null, utterance.getId(), text);
    }

    private String clip(String text) {
        return text == null ? "" : (text.length() <= 120 ? text : text.substring(0, 119) + "…");
    }
}
