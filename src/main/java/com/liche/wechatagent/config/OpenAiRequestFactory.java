package com.liche.wechatagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** 构建 OpenAI 兼容接口请求体（非流式/流式共用） */
final class OpenAiRequestFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private OpenAiRequestFactory() {
    }

    static ObjectNode buildPayload(String model, double temperature, ChatRequest request, boolean stream) {
        return buildPayload(model, temperature, request, stream, null, 0);
    }

    /**
     * @param extraBody 额外塞进请求体的字段（例如 {@code {"thinking":{"type":"disabled"}}}）；null 或非对象则不添加。
     *                  字段形状由配置提供（见 LlmScenarioSettings），上游换一种开关写法不用改代码。
     */
    static ObjectNode buildPayload(String model, double temperature, ChatRequest request, boolean stream,
                                   JsonNode extraBody, int maxTokens) {
        ObjectNode payload = OBJECT_MAPPER.createObjectNode();
        payload.put("model", model);
        payload.put("temperature", temperature);
        payload.put("stream", stream);
        if (maxTokens > 0) {
            payload.put("max_tokens", maxTokens);
        }
        if (extraBody != null && extraBody.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = extraBody.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                payload.set(field.getKey(), field.getValue());
            }
        }

        ArrayNode messages = payload.putArray("messages");
        for (ChatMessage m : request.messages()) {
            messages.add(toOpenAiMessage(m));
        }

        List<ToolSpecification> tools = request.toolSpecifications();
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = payload.putArray("tools");
            for (ToolSpecification ts : tools) {
                ObjectNode t = toolsNode.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", ts.name());
                if (ts.description() != null && !ts.description().isBlank()) {
                    fn.put("description", ts.description());
                }
                if (ts.parameters() != null) {
                    fn.set("parameters", toJsonNode(ts.parameters()));
                }
            }
            payload.put("tool_choice", "auto");
        }
        return payload;
    }

    static ObjectNode toOpenAiMessage(ChatMessage m) {
        ObjectNode n = OBJECT_MAPPER.createObjectNode();
        if (m instanceof SystemMessage sm) {
            n.put("role", "system");
            n.put("content", sm.text());
        } else if (m instanceof UserMessage um) {
            n.put("role", "user");
            List<dev.langchain4j.data.message.Content> umc = um.contents();
            boolean hasImage = umc.stream().anyMatch(c -> c instanceof dev.langchain4j.data.message.ImageContent);
            if (hasImage) {
                ArrayNode arr = n.putArray("content");
                for (dev.langchain4j.data.message.Content c : umc) {
                    if (c instanceof dev.langchain4j.data.message.TextContent tc) {
                        ObjectNode o = arr.addObject(); o.put("type", "text"); o.put("text", tc.text());
                    } else if (c instanceof dev.langchain4j.data.message.ImageContent ic) {
                        ObjectNode o = arr.addObject(); o.put("type", "image_url");
                        ObjectNode iu = o.putObject("image_url");
                        iu.put("url", ic.image().url() != null ? ic.image().url().toString() : ic.image().base64Data());
                    }
                }
            } else {
                n.put("content", um.singleText());
            }
        } else if (m instanceof AiMessage am) {
            n.put("role", "assistant");
            if (am.text() != null) {
                n.put("content", am.text());
            }
            List<ToolExecutionRequest> ters = am.toolExecutionRequests();
            if (ters != null && !ters.isEmpty()) {
                ArrayNode tcs = n.putArray("tool_calls");
                for (ToolExecutionRequest ter : ters) {
                    ObjectNode tc = tcs.addObject();
                    tc.put("id", ter.id());
                    tc.put("type", "function");
                    ObjectNode fn = tc.putObject("function");
                    fn.put("name", ter.name());
                    fn.put("arguments", ter.arguments());
                }
            }
        } else if (m instanceof ToolExecutionResultMessage term) {
            n.put("role", "tool");
            n.put("tool_call_id", term.id());
            n.put("content", term.text());
        } else {
            n.put("role", "user");
            n.put("content", m.toString());
        }
        return n;
    }

    static JsonNode toJsonNode(JsonSchemaElement el) {
        if (el == null) {
            return OBJECT_MAPPER.nullNode();
        }
        ObjectNode n = OBJECT_MAPPER.createObjectNode();
        if (el.description() != null) {
            n.put("description", el.description());
        }
        if (el instanceof JsonStringSchema) {
            n.put("type", "string");
        } else if (el instanceof JsonIntegerSchema) {
            n.put("type", "integer");
        } else if (el instanceof JsonNumberSchema) {
            n.put("type", "number");
        } else if (el instanceof JsonBooleanSchema) {
            n.put("type", "boolean");
        } else if (el instanceof JsonEnumSchema es) {
            n.put("type", "string");
            ArrayNode en = n.putArray("enum");
            es.enumValues().forEach(en::add);
        } else if (el instanceof JsonArraySchema as) {
            n.put("type", "array");
            n.set("items", toJsonNode(as.items()));
        } else if (el instanceof JsonObjectSchema os) {
            n.put("type", "object");
            ObjectNode props = n.putObject("properties");
            os.properties().forEach((k, v) -> props.set(k, toJsonNode(v)));
            List<String> required = os.required();
            if (required != null && !required.isEmpty()) {
                ArrayNode rn = n.putArray("required");
                required.forEach(rn::add);
            }
            Boolean additional = os.additionalProperties();
            if (additional != null) {
                n.put("additionalProperties", additional);
            }
        }
        return n;
    }
}
