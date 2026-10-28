package com.liche.qqagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具参数校验（2026-10-28）。**执行前**按工具的 JSON Schema 检查参数。
 *
 * <p><b>为什么必须有</b>：LLM 输出错参数是**预期内的正常事件**，不是程序 bug。
 * 原来的行为是"反序列化 + 按 Java 类型强转"：缺参数给 {@code null}、把数字硬转成字符串、
 * 参数名写错当成没给——于是错误往往在工具内部炸成 NPE，回传给模型的是一句
 * {@code NullPointerException}，它只能瞎猜着改。
 *
 * <p>现在把校验提到执行前，失败信息是**人能读懂、模型能照做**的：
 * <pre>缺少必填参数：query（可用参数：query、count）</pre>
 *
 * <p><b>刻意只做两条</b>：必填缺失、明显类型不符。不做 enum、范围、嵌套对象校验——
 * 那些要么用不到，要么容易误杀（模型偶尔把 {@code count} 写成 {@code "6"}，能转就让它转）。
 * 目的是"给出可修正的提示"，不是"当一个严格的 API 网关"。
 */
final class ToolArgumentValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolArgumentValidator() {
    }

    /**
     * @return 校验通过返回 null；否则返回给模型看的错误说明
     */
    static String validate(ToolSpecification spec, String argumentsJson) {
        if (spec == null) {
            return null;
        }
        String raw = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        JsonNode args;
        try {
            args = JSON.readTree(raw);
        } catch (Exception exception) {
            return "参数不是合法 JSON，请只返回一个 JSON 对象（例如 {\"query\": \"…\"}）";
        }
        if (args == null || args.isNull()) {
            args = JSON.createObjectNode();
        }
        if (!args.isObject()) {
            return "参数必须是一个 JSON 对象（例如 {\"query\": \"…\"}），收到的是 " + args.getNodeType();
        }

        JsonSchemaElement parameters = spec.parameters();
        if (!(parameters instanceof JsonObjectSchema object)) {
            return null;
        }
        Map<String, JsonSchemaElement> properties = object.properties() == null ? Map.of() : object.properties();
        Set<String> available = new LinkedHashSet<>(properties.keySet());

        // ① 必填缺失
        List<String> missing = new ArrayList<>();
        for (String name : object.required() == null ? List.<String>of() : object.required()) {
            JsonNode value = args.get(name);
            if (value == null || value.isNull()) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            StringBuilder message = new StringBuilder("缺少必填参数：").append(String.join("、", missing));
            if (!available.isEmpty()) {
                message.append("（可用参数：").append(String.join("、", available)).append("）");
            }
            // 参数名写错时，把"你给的"和"可用的"并列，模型基本一次就能改对
            Set<String> unknown = new LinkedHashSet<>();
            args.fieldNames().forEachRemaining(name -> {
                if (!properties.containsKey(name)) {
                    unknown.add(name);
                }
            });
            if (!unknown.isEmpty()) {
                message.append("；你给的多余参数：").append(String.join("、", unknown));
            }
            return message.toString();
        }

        // ② 明显类型不符（只判"给了对象/数组但期望标量"和"给了字符但期望数字/布尔"）
        for (Map.Entry<String, JsonSchemaElement> entry : properties.entrySet()) {
            JsonNode value = args.get(entry.getKey());
            if (value == null || value.isNull()) {
                continue;
            }
            String problem = typeProblem(entry.getKey(), entry.getValue(), value);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String typeProblem(String name, JsonSchemaElement schema, JsonNode value) {
        if (schema instanceof JsonStringSchema) {
            if (value.isObject() || value.isArray()) {
                return "参数 " + name + " 应该是字符串，收到的是" + nodeLabel(value);
            }
            return null;
        }
        if (schema instanceof JsonIntegerSchema || schema instanceof JsonNumberSchema) {
            if (value.isObject() || value.isArray()) {
                return "参数 " + name + " 应该是数字，收到的是" + nodeLabel(value);
            }
            if (value.isTextual() && !value.asText().trim().matches("-?\\d+(\\.\\d+)?")) {
                return "参数 " + name + " 应该是数字，收到的是文本「" + abbreviate(value.asText()) + "」";
            }
            return null;
        }
        if (schema instanceof JsonBooleanSchema) {
            if (value.isObject() || value.isArray()) {
                return "参数 " + name + " 应该是 true/false，收到的是" + nodeLabel(value);
            }
            if (value.isTextual()) {
                String text = value.asText().trim().toLowerCase(java.util.Locale.ROOT);
                if (!text.equals("true") && !text.equals("false")) {
                    return "参数 " + name + " 应该是 true/false，收到的是文本「" + abbreviate(value.asText()) + "」";
                }
            }
            return null;
        }
        if (schema instanceof JsonArraySchema) {
            if (!value.isArray() && !value.isTextual()) {
                return "参数 " + name + " 应该是数组，收到的是" + nodeLabel(value);
            }
            return null;
        }
        return null; // 嵌套对象等其它情况不校验
    }

    private static String nodeLabel(JsonNode value) {
        if (value.isObject()) {
            return "对象";
        }
        if (value.isArray()) {
            return "数组";
        }
        return value.getNodeType().name();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 20 ? text : text.substring(0, 20) + "…";
    }
}
