package com.liche.qqagent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具集裁剪（2026-10-28 补测）。
 *
 * <p>这段决定"这一轮把哪些工具下发给模型"，出错的表现很隐蔽：
 * 裁多了模型说"我没有这个能力"，裁少了每轮多烧 token。三条保守规则必须有测试。
 */
class ToolSetTrimmerTest {

    private static ToolSpecification spec(String name) {
        return ToolSpecification.builder().name(name).description("d").build();
    }

    private static List<ToolSpecification> all() {
        return List.of(spec("searchWeb"), spec("selfQuest"), spec("selfQuestNote"));
    }

    private static ToolVisibilityRule hideSelf() {
        return new ToolVisibilityRule() {
            @Override
            public String name() {
                return "self";
            }

            @Override
            public Set<String> hiddenFor(String userId, List<ToolSpecification> all) {
                return Set.of("selfQuest", "selfQuestNote");
            }
        };
    }

    @Test
    void hidesWhatRulesDeclare() {
        ToolSetTrimmer trimmer = new ToolSetTrimmer(List.of(hideSelf()), true);
        ToolSetTrimmer.TrimResult result = trimmer.trim(all(), "u1");

        assertEquals(List.of("searchWeb"), result.specifications().stream().map(ToolSpecification::name).toList());
        assertEquals(Set.of("selfQuest", "selfQuestNote"), result.hidden());
    }

    @Test
    void withoutUserIdKeepsEverything() {
        // 拿不到用户时宁可全给：少给会让功能静默失效，多给只是多花点 token
        ToolSetTrimmer trimmer = new ToolSetTrimmer(List.of(hideSelf()), true);
        ToolSetTrimmer.TrimResult result = trimmer.trim(all(), "  ");

        assertEquals(3, result.specifications().size());
        assertTrue(result.hidden().isEmpty());
    }

    @Test
    void disabledKeepsEverything() {
        ToolSetTrimmer trimmer = new ToolSetTrimmer(List.of(hideSelf()), false);
        assertEquals(3, trimmer.trim(all(), "u1").specifications().size());
    }

    @Test
    void brokenRuleIsSkippedNotFatal() {
        // 拔掉一个模块不该让整轮对话下不出去工具
        ToolVisibilityRule broken = new ToolVisibilityRule() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public Set<String> hiddenFor(String userId, List<ToolSpecification> all) {
                throw new IllegalStateException("boom");
            }
        };
        ToolSetTrimmer trimmer = new ToolSetTrimmer(List.of(broken, hideSelf()), true);
        ToolSetTrimmer.TrimResult result = trimmer.trim(all(), "u1");

        assertEquals(1, result.specifications().size(), "坏规则被跳过，好规则仍然生效");
    }

    @Test
    void emptyInputIsReturnedAsIs() {
        ToolSetTrimmer trimmer = new ToolSetTrimmer(List.of(hideSelf()), true);
        assertTrue(trimmer.trim(List.of(), "u1").specifications().isEmpty());
    }
}
