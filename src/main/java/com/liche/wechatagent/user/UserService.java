package com.liche.wechatagent.user;

import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.config.AgentPolicyProperties;
import com.liche.wechatagent.log.UserLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/** 用户档案服务：新用户首次对话自动写入默认基础人设 */
@Service
public class UserService {

    public static final String DEFAULT_PERSONA = AgentPolicyProperties.DEFAULT_PERSONA;

    private final UserProfileRepository userProfileRepository;
    private final UserLogService userLogService;
    private final String defaultPersona;
    private final int maxPersonaChars;

    @org.springframework.beans.factory.annotation.Autowired
    public UserService(UserProfileRepository userProfileRepository,
                       UserLogService userLogService,
                       AgentPolicyProperties policyProperties) {
        this.userProfileRepository = userProfileRepository;
        this.userLogService = userLogService;
        AgentPolicyProperties policies = policyProperties == null ? new AgentPolicyProperties() : policyProperties;
        this.defaultPersona = policies.getDefaultPersona() == null || policies.getDefaultPersona().isBlank()
                ? DEFAULT_PERSONA : policies.getDefaultPersona().trim();
        int configuredMax = policies.getMaxPersonaChars();
        this.maxPersonaChars = configuredMax < 128 || configuredMax > 10_000 ? 2_000 : configuredMax;
    }

    public UserService(UserProfileRepository userProfileRepository, UserLogService userLogService) {
        this(userProfileRepository, userLogService, new AgentPolicyProperties());
    }

    public UserProfile getOrCreate(String userId) {
        return userProfileRepository.findById(userId)
                .orElseGet(() -> userProfileRepository.save(new UserProfile(userId, defaultPersona)));
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
        if (newPersona.length() > maxPersonaChars) {
            throw new BizException("人设内容太长了，控制在 " + maxPersonaChars + " 字以内吧");
        }
        UserProfile profile = get(userId);
        profile.setPersona(newPersona.trim());
        profile.setUpdatedAt(java.time.LocalDateTime.now());
        userProfileRepository.save(profile);
        userLogService.record(userId, "SET_PROMPT", Map.of("personaLength", newPersona.trim().length()));
    }

    /** /陪练：设置或清除陪练模式（interview，null 或空表示关闭）；人设本身不动。 */
    public void setCoachMode(String userId, String coachMode) {
        UserProfile profile = get(userId);
        String normalized = coachMode == null || coachMode.isBlank()
                ? null : coachMode.trim().toLowerCase(java.util.Locale.ROOT);
        profile.setCoachMode(normalized);
        profile.setUpdatedAt(java.time.LocalDateTime.now());
        userProfileRepository.save(profile);
        userLogService.record(userId, normalized == null ? "COACH_MODE_OFF" : "COACH_MODE_ON",
                Map.of("mode", normalized == null ? "" : normalized));
    }

    /** 当前陪练模式；null 表示未开启 */
    public String coachMode(String userId) {
        return get(userId).getCoachMode();
    }

    /** 当前面试练习 session；null 表示没有进行中的练习 */
    public String coachSessionId(String userId) {
        return get(userId).getCoachSessionId();
    }

    /** 用户说的目标岗位 */
    public String coachRole(String userId) {
        return get(userId).getCoachRole();
    }

    /** 开始一次面试陪练：进入模式、生成新 session、记下岗位（岗位留空时沿用上次） */
    public void startInterviewSession(String userId, String role) {
        UserProfile profile = get(userId);
        profile.setCoachMode("interview");
        profile.setCoachSessionId(java.util.UUID.randomUUID().toString());
        if (role != null && !role.isBlank()) {
            profile.setCoachRole(role.strip());
        }
        profile.setUpdatedAt(java.time.LocalDateTime.now());
        userProfileRepository.save(profile);
        userLogService.record(userId, "INTERVIEW_START",
                Map.of("role", profile.getCoachRole() == null ? "" : profile.getCoachRole()));
    }

    /** 结束面试陪练：退出模式并清掉 session（岗位保留，下次复盘还能显示） */
    public void endInterviewSession(String userId) {
        UserProfile profile = get(userId);
        boolean wasPracticing = profile.getCoachMode() != null || profile.getCoachSessionId() != null;
        profile.setCoachMode(null);
        profile.setCoachSessionId(null);
        profile.setUpdatedAt(java.time.LocalDateTime.now());
        userProfileRepository.save(profile);
        if (wasPracticing) {
            userLogService.record(userId, "INTERVIEW_END");
        }
    }

    public boolean isMemoryEnabled(String userId) {
        return !Boolean.FALSE.equals(get(userId).getMemoryEnabled());
    }

    public void setMemoryEnabled(String userId, boolean enabled) {
        UserProfile profile = get(userId);
        profile.setMemoryEnabled(enabled);
        profile.setUpdatedAt(java.time.LocalDateTime.now());
        userProfileRepository.save(profile);
        userLogService.record(userId, enabled ? "MEMORY_ENABLED" : "MEMORY_DISABLED");
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
                Map.of("enabled", enabled));
        return profile;
    }
}
