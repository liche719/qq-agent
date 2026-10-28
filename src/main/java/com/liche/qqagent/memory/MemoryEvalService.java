package com.liche.qqagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 记忆评估（2026-10-26）。
 *
 * <p><b>为什么要有</b>：改了提示词或记忆策略之后，**没有任何可重复的办法判断变好还是变差**——
 * 以前只能靠"上线后碰运气"。这里用一组固定用例，给出一个可以前后对比的分数。
 *
 * <p>用例在 {@code src/main/resources/eval/memory-cases.json}，分两类：
 * <ul>
 *   <li>{@code extract}：喂一段合成对话 → 期望抽出某些事实（subject/object 关键片段），
 *       且**不该**把情绪、一次性的事抽成事实；</li>
 *   <li>{@code recall}：给一句 query → 期望召回结果里包含某个值。</li>
 * </ul>
 *
 * <p><b>只在本地/隔离用户上跑</b>：所有读写都限定在调用方给的 {@code eval-*} 用户下，
 * 跑完按 user_id 清干净。生产环境没有把 eval 接口暴露给面板（见 AdminEvalController 的开关）。
 */
@Service
public class MemoryEvalService {

    private static final Logger log = LoggerFactory.getLogger(MemoryEvalService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER_KIND = "eval";

    private final MemoryExtractor extractor;
    private final ConversationMemoryService conversationMemory;
    private final MemoryFactService factService;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public MemoryEvalService(MemoryExtractor extractor,
                             ConversationMemoryService conversationMemory,
                             MemoryFactService factService,
                             JdbcTemplate jdbc,
                             @org.springframework.beans.factory.annotation.Value("${agent.eval-enabled:false}")
                             boolean enabled) {
        this.extractor = extractor;
        this.conversationMemory = conversationMemory;
        this.factService = factService;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 跑全部用例，返回逐条结果与总分 */
    public Map<String, Object> run(String userId) {
        return run(userId, false);
    }

    public Map<String, Object> run(String userId, boolean keepData) {
        if (!enabled) {
            return Map.of("ok", false, "error", "评估开关关着（agent.eval-enabled=false）");
        }
        if (userId == null || !userId.startsWith(OWNER_KIND + "-")) {
            // 防手滑：只允许 eval- 前缀的用户，绝不碰真实用户
            return Map.of("ok", false, "error", "userId 必须以 eval- 开头");
        }
        List<JsonNode> cases = loadCases();
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        for (JsonNode node : cases) {
            String type = node.path("type").asText("extract");
            Map<String, Object> one = "recall".equals(type)
                    ? runRecallCase(userId, node) : runExtractCase(userId, node);
            if (Boolean.TRUE.equals(one.get("pass"))) {
                passed++;
            }
            results.add(one);
        }
        if (!keepData) {
            cleanup(userId);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", true);
        report.put("user", userId);
        report.put("total", cases.size());
        report.put("passed", passed);
        report.put("score", cases.isEmpty() ? "0%" : (passed * 100 / cases.size()) + "%");
        report.put("results", results);
        log.info("记忆评估完成 user={} {}/{}", userId, passed, cases.size());
        return report;
    }

    private Map<String, Object> runExtractCase(String userId, JsonNode node) {
        String id = node.path("id").asText("");
        // 每个用例一个独立用户：用例之间不互相污染（否则提取会把上一段的对话也读进去）
        String caseUser = userId + "-" + id;
        List<String> turns = new ArrayList<>();
        for (JsonNode turn : node.path("conversation")) {
            turns.add(turn.asText(""));
        }
        int index = 0;
        for (String text : turns) {
            // 必须给合成消息一个 id：真实链路里模型靠 [sourceMessageIds=...] 标注证据，
            // 评估这里给空的话，抽出来的事实永远"无证据"，验不出证据链（2026-10-26 踩过）
            conversationMemory.record(caseUser, "user", "eval-" + id + "-" + index, text,
                    List.of("eval-msg-" + id + "-" + index), List.of(), null);
            index++;
        }
        boolean ran = extractor.extract(caseUser);
        try {
            Thread.sleep(200);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        List<Map<String, Object>> facts = factRows(caseUser);
        List<String> missing = new ArrayList<>();
        for (JsonNode expected : node.path("expectFacts")) {
            String subject = expected.path("subject").asText("").trim();
            String object = expected.path("object").asText("").trim();
            List<String> objectAny = new ArrayList<>();
            for (JsonNode alt : expected.path("objectAny")) {
                String value = alt.asText("").trim();
                if (!value.isBlank()) {
                    objectAny.add(value);
                }
            }
            // 空字符串 = 这条不约束（早期版本把空当"必须匹配空"，导致所有只写 object 的用例必挂）
            boolean hit = facts.stream().anyMatch(row -> {
                boolean subjectOk = subject.isBlank() || contains(row.get("subject"), subject);
                boolean objectOk = object.isBlank()
                        ? objectAny.isEmpty() || objectAny.stream().anyMatch(alt -> contains(row.get("object"), alt))
                        : contains(row.get("object"), object);
                return subjectOk && objectOk;
            });
            if (!hit) {
                List<String> wanted = object.isBlank() ? objectAny : List.of(object);
                hit = !wanted.isEmpty() && memoryRows(caseUser).stream()
                        .anyMatch(content -> wanted.stream().anyMatch(w -> contains(content, w)));
            }
            if (!hit) {
                missing.add((subject.isBlank() ? "*" : subject) + "="
                        + (object.isBlank() ? objectAny.toString() : object));
            }
        }
        List<String> forbidden = new ArrayList<>();
        for (JsonNode bad : node.path("forbidFacts")) {
            String keyword = bad.asText("");
            boolean hit = facts.stream().anyMatch(row -> contains(row.get("content"), keyword));
            if (hit) {
                forbidden.add(keyword);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("type", "extract");
        result.put("extractionRan", ran);
        result.put("facts", facts.size());
        result.put("pass", missing.isEmpty() && forbidden.isEmpty() && ran);
        if (!missing.isEmpty()) {
            result.put("missing", missing);
        }
        if (!forbidden.isEmpty()) {
            result.put("forbidden", forbidden);
        }
        return result;
    }

    private Map<String, Object> runRecallCase(String userId, JsonNode node) {
        String id = node.path("id").asText("");
        String query = node.path("query").asText("");
        // 2026-10-28：支持"多个可接受写法"（expectContainsAny）。原因：同一句"三点"可能被抽成
        // "15:00"或"下午三点"，测评不该因为渲染格式不同就判失败——那是噪声，不是检索变差。
        String expect = node.path("expectContains").asText("");
        List<String> expectAny = new ArrayList<>();
        for (JsonNode alt : node.path("expectContainsAny")) {
            String value = alt.asText("").trim();
            if (!value.isBlank()) {
                expectAny.add(value);
            }
        }
        String fromCase = node.path("fromCase").asText("");
        String caseUser = fromCase.isBlank() ? userId : userId + "-" + fromCase;
        List<MemoryFact> hits = factService.recallFacts(caseUser, query, 8, 0d);
        String rendered = factService.renderCards(hits, 1200);
        // 画像类（昵称、手机号）不在事实层，走 memory 表——模型平时是靠核心记忆注入拿到的
        if (!contains(rendered, expect)) {
            String fromMemory = String.join(" | ", memoryRows(caseUser));
            if (contains(fromMemory, expect)) {
                rendered = rendered + " | " + fromMemory;
            }
        }
        // 2026-10-28：新增"排序断言"（expectTopContains）。
        // 原来只看整段渲染里"有没有"，命中在第 8 条也算过——这样测不出检索排序的好坏，
        // 也就没法评估 BM25+RRF 这类"改排序"的手段。它要求**第一条命中**就必须包含该值。
        String expectTop = node.path("expectTopContains").asText("");
        boolean topOk = true;
        if (!expectTop.isBlank()) {
            String top = hits.isEmpty() ? "" : factService.renderCards(List.of(hits.get(0)), 400);
            topOk = contains(top, expectTop);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("type", "recall");
        result.put("query", query);
        result.put("hits", hits.size());
        if (!expectTop.isBlank()) {
            result.put("top", hits.isEmpty() ? "" : factService.renderCards(List.of(hits.get(0)), 120));
        }
        // expectContains 留空 = 这条不约束"整段里有没有"，只约束排序（expectTopContains）
        boolean containsOk;
        if (!expectAny.isEmpty()) {
            containsOk = false;
            for (String value : expectAny) {
                if (rendered.contains(value)) {
                    containsOk = true;
                    break;
                }
            }
        } else {
            containsOk = expect == null || expect.isBlank() || contains(rendered, expect);
        }
        result.put("pass", containsOk && topOk);
        if (!contains(rendered, expect)) {
            result.put("expected", expect);
            result.put("got", rendered.length() > 300 ? rendered.substring(0, 300) : rendered);
        }
        return result;
    }

    /** 画像/任务类记忆（memory 表）：昵称、手机号这类不进事实层 */
    private List<String> memoryRows(String userId) {
        List<String> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select content from memory where user_id = ? and (status is null or status = 'ACTIVE')", userId)) {
            Object content = row.get("content");
            if (content != null) {
                rows.add(String.valueOf(content));
            }
        }
        return rows;
    }

    private List<Map<String, Object>> factRows(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> raw = jdbc.queryForList(
                "select subject, predicate, object, content from memory_fact where user_id = ? and status = 'ACTIVE'",
                userId);
        for (Map<String, Object> row : raw) {
            Map<String, Object> item = new LinkedHashMap<>();
            row.forEach((key, value) -> item.put(key, value == null ? "" : String.valueOf(value)));
            rows.add(item);
        }
        return rows;
    }

    /** 跑完清场：只删这个 eval 用户的行，别的用户一条都不碰 */
    private void cleanup(String userId) {
        for (String table : List.of("memory_fact", "memory", "conversation_memory",
                "memory_change_log", "memory_extraction_run", "task_reflection")) {
            try {
                jdbc.update("delete from " + table + " where user_id = ? or user_id like ?",
                        userId, userId + "-%");
            } catch (RuntimeException exception) {
                log.warn("清理评估数据失败 table={}: {}", table, exception.getMessage());
            }
        }
    }

    private List<JsonNode> loadCases() {
        List<JsonNode> cases = new ArrayList<>();
        try (InputStream in = new ClassPathResource("eval/memory-cases.json").getInputStream()) {
            JsonNode root = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            root.path("cases").forEach(cases::add);
        } catch (Exception exception) {
            log.warn("读取评估用例失败：{}", exception.getMessage());
        }
        return cases;
    }

    private static boolean contains(Object haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) {
            return false;
        }
        return String.valueOf(haystack).toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
