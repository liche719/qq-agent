package com.liche.qqagent.self;

import com.liche.qqagent.tool.ToolVisibilityRule;
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
     * 自主模块的平台级归属（2026-10-20）：**整套 self* 只属于机主那一个号**。
     *
     * <p>用户的原话："只有我自己的主账号的那个 QQ 号对应的笨鱼才有自主模块，其他别人的账号都没有"。
     * 判据是 {@code selfCore.isOwner(userId)} —— 陌生人在下面第一个分支里就被整组藏掉，
     * 连工具描述都不会进上下文。
     */
    private static final String SELF_TOOL_PREFIX = "self";

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
        // 它自己的时间里要能调全部 self* 工具
        if (ownTime) {
            return Set.of();
        }
        Set<String> hidden = new LinkedHashSet<>();
        for (ToolSpecification spec : all) {
            if (spec == null || spec.name() == null) {
                continue;
            }
            String name = spec.name();
            boolean hide = false;

            // 2026-10-20 用户明确：「只有我自己的主账号那个 QQ 号对应的笨鱼才有自主模块，
            // 其他别人的账号都没有」。所以**非机主连 self* 的 schema 都不下发**——
            // 不是"看得见但被拒"，是那一轮的工具集里压根没有这几项。
            if (!owner) {
                if (name.startsWith("self")) {
                    hide = true;
                }
            } else {
                // 机主在**普通对话**里：只藏"它自己的时间"那组（开方向、写念头、私下说话），
                // 但 selfRead/selfRecall/selfNote 这些允许用——那是他在看它自己的记录。
                for (String prefix : OWN_TIME_TOOL_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        hide = true;
                        break;
                    }
                }
            }
            if (hide) {
                hidden.add(name);
            }
        }
        return hidden;
    }
}
