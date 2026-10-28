package com.liche.qqagent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具参数校验（2026-10-28）。
 *
 * <p>它存在的理由：LLM 给错参数是**正常事件**，不是 bug。校验的意义不是"拦住"，是
 * **给一句模型能照做的话**——"缺少必填参数：query（可用参数：query、count）"
 * 比 NullPointerException 有用得多。
 */
class ToolArgumentValidatorTest {

    private static ToolSpecification spec() {
        return ToolSpecification.builder()
                .name("searchWeb")
                .description("搜索")
                .parameters(JsonObjectSchema.builder()
                        .addProperty("query", JsonStringSchema.builder().description("关键词").build())
                        .addProperty("count", JsonIntegerSchema.builder().description("条数").build())
                        .addProperty("fullText", JsonBooleanSchema.builder().description("全文").build())
                        .required("query")
                        .build())
                .build();
    }

    @Test
    void validArgumentsPass() {
        assertNull(ToolArgumentValidator.validate(spec(), "{\"query\":\"广州天气\"}"));
        assertNull(ToolArgumentValidator.validate(spec(), "{\"query\":\"x\",\"count\":6}"));
        // 模型常把数字写成字符串，能转就让它转，不算错
        assertNull(ToolArgumentValidator.validate(spec(), "{\"query\":\"x\",\"count\":\"6\"}"));
    }

    @Test
    void missingRequiredIsReportedWithAvailableNames() {
        String error = ToolArgumentValidator.validate(spec(), "{}");
        assertNotNull(error);
        assertTrue(error.contains("缺少必填参数：query"), error);
        assertTrue(error.contains("可用参数"), error);
    }

    @Test
    void typoInParameterNameIsCalledOut() {
        String error = ToolArgumentValidator.validate(spec(), "{\"qury\":\"广州天气\"}");
        assertNotNull(error);
        assertTrue(error.contains("你给的多余参数：qury"), "参数名写错时要直接指出来：" + error);
    }

    @Test
    void wrongTypeIsReported() {
        String error = ToolArgumentValidator.validate(spec(), "{\"query\":{\"a\":1}}");
        assertNotNull(error);
        assertTrue(error.contains("应该是字符串"), error);

        String badNumber = ToolArgumentValidator.validate(spec(), "{\"query\":\"x\",\"count\":\"很多\"}");
        assertNotNull(badNumber);
        assertTrue(badNumber.contains("应该是数字"), badNumber);
    }

    @Test
    void brokenJsonAndNonObjectAreReported() {
        assertTrue(ToolArgumentValidator.validate(spec(), "{query: x}").contains("不是合法 JSON"));
        assertTrue(ToolArgumentValidator.validate(spec(), "[1,2]").contains("JSON 对象"));
        // 空串按 {} 处理（模型偶尔给空）
        assertTrue(ToolArgumentValidator.validate(spec(), "").contains("缺少必填参数"));
    }
}
