package com.liche.wechatagent.user;

import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.log.UserLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 用户档案服务：新用户首次对话自动写入默认基础人设 */
@Service
public class UserService {

    public static final String DEFAULT_PERSONA =
            "你是用户的专属长期智能助手，说话自然口语化，像真人一样沟通，拒绝生硬机械的机器人话术。"
                    + "你会自动记住用户的重要信息和目标，帮用户设置提醒、搜索资料。"
                    + "用户可以随时用 /set-prompt 指令重新设定你的身份和性格。";

    private final UserProfileRepository userProfileRepository;
    private final UserLogService userLogService;

    public UserService(UserProfileRepository userProfileRepository, UserLogService userLogService) {
        this.userProfileRepository = userProfileRepository;
        this.userLogService = userLogService;
    }

    public UserProfile getOrCreate(String userId) {
        return userProfileRepository.findById(userId)
                .orElseGet(() -> userProfileRepository.save(new UserProfile(userId, DEFAULT_PERSONA)));
    }

    public UserProfile get(String userId) {
        return userProfileRepository.findById(userId)
                .orElseThrow(() -> new BizException("用户不存在: " + userId));
    }

    /** /set-prompt：更新当前用户专属人设（写操作日志留痕） */
    public void updatePersona(String userId, String newPersona) {
        if (newPersona == null || newPersona.isBlank()) {
            throw new BizException("人设内容不能为空");
        }
        if (newPersona.length() > 2000) {
            throw new BizException("人设内容太长了，控制在 2000 字以内吧");
        }
        UserProfile profile = get(userId);
        profile.setPersona(newPersona.trim());
        profile.setUpdatedAt(java.time.LocalDateTime.now());
        userProfileRepository.save(profile);
        userLogService.record(userId, "SET_PROMPT", "人设已更新: " + newPersona.trim());
    }

    public boolean isMemoryEnabled(String userId) {
        return !Boolean.FALSE.equals(get(userId).getMemoryEnabled());
    }

    public void setMemoryEnabled(String userId, boolean enabled) {
        UserProfile profile = get(userId);
        profile.setMemoryEnabled(enabled);
        profile.setUpdatedAt(java.time.LocalDateTime.now());
        userProfileRepository.save(profile);
        userLogService.record(userId, enabled ? "MEMORY_ENABLED" : "MEMORY_DISABLED", "自动记忆已" + (enabled ? "开启" : "关闭"));
    }

    public void touchDelivery(String userId, String botId, String channel) {
        UserProfile profile = get(userId);
        profile.setLastBotId(botId);
        profile.setLastChannel(channel);
        profile.setLastSeenAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        userProfileRepository.save(profile);
    }

    public UserProfile configureProactiveCare(String userId, boolean enabled, String cadence,
                                              LocalDateTime nextCareAt) {
        UserProfile profile = get(userId);
        profile.setProactiveCareEnabled(enabled);
        if (cadence != null) profile.setProactiveCareCadence(cadence);
        profile.setNextCareAt(enabled ? nextCareAt : null);
        profile.setUpdatedAt(LocalDateTime.now());
        userProfileRepository.save(profile);
        userLogService.record(userId, enabled ? "PROACTIVE_CARE_ENABLED" : "PROACTIVE_CARE_DISABLED",
                enabled ? "cadence=" + profile.getProactiveCareCadence() : "disabled");
        return profile;
    }
}
