package com.liche.wechatagent.channel;

import com.liche.wechatagent.user.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 主动消息投递（提醒、定时任务、主动关怀共用）。
 *
 * <p>规则：**优先用用户最近一次说话时记录的通道**——猜通道可能把私聊内容发到别的平台。
 * 但记录可能过期（例如排障时用模拟器发过消息，`last_channel` 被写成 simulator，
 * 之后生产环境下所有主动消息都会静默失败）。所以加一条保守兜底：
 * **当记录的通道不存在时，仅当"可用且支持主动消息的通道"恰好只有一个**才改用它；
 * 有多个可用通道时仍旧不猜、记 WARN 放弃投递。
 */
public final class ProactiveDelivery {

    private static final Logger log = LoggerFactory.getLogger(ProactiveDelivery.class);

    private ProactiveDelivery() {
    }

    public static boolean send(List<WeChatChannel> channels, UserProfile profile, String userId, String text) {
        if (channels == null || channels.isEmpty()) {
            log.warn("主动消息未发送：没有可用通道 user={}", userId);
            return false;
        }
        String recorded = profile == null ? null : profile.getLastChannel();
        String botId = profile == null ? null : profile.getLastBotId();

        WeChatChannel target = null;
        if (recorded != null && !recorded.isBlank()) {
            target = channels.stream()
                    .filter(channel -> recorded.equals(channel.channel()))
                    .filter(channel -> channel.supportsProactiveCare(userId))
                    .findFirst().orElse(null);
        }
        if (target == null) {
            List<WeChatChannel> usable = channels.stream()
                    .filter(channel -> channel.supportsProactiveCare(userId))
                    .filter(channel -> !"simulator".equals(channel.channel()))
                    .toList();
            if (usable.size() != 1) {
                log.warn("主动消息投递通道不可用且无法唯一确定 user={} recorded={} 可用通道={}",
                        userId, recorded, usable.stream().map(WeChatChannel::channel).toList());
                return false;
            }
            target = usable.get(0);
            log.info("记录的通道 {} 不可用，改用唯一可用通道 {} user={}", recorded, target.channel(), userId);
        }
        try {
            if (target.hasReliableSendStatus()) {
                return target.sendTextResultFrom(botId, userId, text);
            }
            target.sendTextFrom(botId, userId, text);
            return true;
        } catch (RuntimeException exception) {
            log.warn("主动消息发送异常 user={} channel={}: {}", userId, target.channel(), exception.toString());
            return false;
        }
    }
}
