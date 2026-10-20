package com.liche.wechatagent.self;

import com.liche.wechatagent.tool.ToolVisibilityRule;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「它自己的方向」那组工具**只在该作用域里下发**（2026-09-15 从 {@code ToolSetTrimmer} 搬过来）。
 *
 * <p>原来这条规则硬编码在宿主的工具裁剪器里，裁剪器还得 `import SelfCoreService` 才知道
 * "现在是不是它自己的作用域"——工具层依赖业务模块，方向是反的。现在由模块自己声明：
 * 裁剪器只问一句"有哪些要藏"，不用认识自主模块。
 *
 * <p>机主对话里它用不上「开一个自己的方向」，没必要每轮都为这几段 schema 付 prompt token，
 * 更没必要给它一个在聊天里乱开方向的入口。
 */
@Component
@ConditionalOnProperty(name = "memory.self-enabled", havingValue = "true", matchIfMissing = true)
public class SelfToolVisibilityRule implements ToolVisibilityRule {

    /**
     * 「它自己的时间」里那组工具的前缀。
     *
     * <p>注意 `selfWantToSay` **不在 `selfQuest` 前缀下**——它是"它自己的表达"，同样只该在
     * 它自己的时间里出现（否则它就一边陪你聊天一边写自己的日记）。实测漏过一次：
     * 只匹配 `selfQuest` 时它被留在机主对话的工具集里。
     */
    private static final List<String> OWN_TIME_TOOL_PREFIXES = List.of("selfQuest", "selfWantToSay", "selfSeed");

    /**
     * 工作区那组工具（2026-10-20）：**只有机主能用，别人一个都看不到**。
     *
     * <p>用户的原话："只有我的 QQ 主账号的笨鱼才有工作区，别的人都没有"。
     * 所以这里不是"藏起来不给看"，而是**每个人问到的工具集本来就不一样**——
     * 陌生人既看不到、也调不到，工作区里不会有别人的东西。
     */
    private static final List<String> OWNER_ONLY_TOOL_PREFIXES = List.of("selfWork");

    private final SelfCoreService selfCore;

    public SelfToolVisibilityRule(SelfCoreService selfCore) {
        this.selfCore = selfCore;
    }

    @Override
    public String name() {
        return "自主模块：它自己的方向那组工具只在它自己的作用域里下发";
    }

    @Override
    public Set<String> hiddenFor(String userId, List<ToolSpecification> all) {
        if (all == null || all.isEmpty()) {
            return Set.of();
        }
        boolean ownTime = userId != null && SelfCoreService.SELF_SCOPE.equals(userId.trim());
        boolean owner = selfCore.isOwner(userId);
        // 它自己的时间里要能调「自己的时间」那组工具
        if (ownTime) {
            return Set.of();
        }
        Set<String> hidden = new LinkedHashSet<>();
        for (ToolSpecification spec : all) {
            if (spec == null || spec.name() == null) {
                continue;
            }
            boolean hide = false;
            for (String prefix : OWN_TIME_TOOL_PREFIXES) {
                if (spec.name().startsWith(prefix)) {
                    hide = true;
                    break;
                }
            }
            // 工作区：只有机主能拿到（陌生人连 schema 都不出现在上下文里）
            if (!hide && !owner) {
                for (String prefix : OWNER_ONLY_TOOL_PREFIXES) {
                    if (spec.name().startsWith(prefix)) {
                        hide = true;
                        break;
                    }
                }
            }
            if (hide) {
                hidden.add(spec.name());
            }
        }
        return hidden;
    }
}
