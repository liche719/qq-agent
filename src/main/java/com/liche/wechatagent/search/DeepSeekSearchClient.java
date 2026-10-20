package com.liche.wechatagent.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 官方搜索接口（{@code POST /v1/tools/search}）——**2026-10-20 起是搜索主路径**，SearXNG 降为兜底。
 *
 * <p><b>为什么需要它（2026-10-15）</b>：自建的 SearXNG 在大陆机房 IP 下只有六个引擎能用，而且
 * **完全不认 {@code site:} 之类的搜索语法**（坑 68 就是栽在这上面：搜 {@code site:bilibili.com} 返回一堆无关结果）。
 * DeepSeek 2026-10 上线了自研搜索引擎的原始 JSON 接口，返回带 {@code rerank_score}/`authority`/发布时间，
 * 还支持 {@code site:} / {@code lang:} / {@code after:} —— 正好补上这个短板。
 *
 * <p><b>为什么只做兜底</b>：它**按次收费**（5 元 / 1000 次成功请求，失败与无结果不计费），而 SearXNG 是自建免费的。
 * 平时走 SearXNG，只有"搜不到"或"搜出来明显不相关"时才花钱兜一刀。
 *
 * <p><b>它不能替代的东西</b>：实测（2026-10-15）用标题搜 B站视频时，它**找不回** B站自己接口那条逐字命中的结果，
 * 所以 {@code readBilibiliVideo} 那条路继续走 B站搜索接口，跟这里无关。
 *
 * <p><b>失败一律吞掉</b>：兜底是尽力而为，它的任何错误都不能把一次正常搜索变成报错。
 *
 * <p>文档：<a href="https://api-docs.deepseek.com/zh-cn/guides/search/">网页搜索与抓取</a>。
 */
@Component
public class DeepSeekSearchClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekSearchClient.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 20;
    /** 官方单次最多 50 条；我们最多也就展示 10 条，多要既慢又没意义 */
    private static final int MAX_COUNT = 10;
    /**
     * 没有 {@code site:} 限定时的相关性门槛：官方说 ≥1.5 算优质，但 1.5 太严会砍掉有用的；
     * 实测无意义查询的分都在 1 以下，1.0 能把"瞎搜回来的噪声"挡在外面（见 {@code parse} 的说明）。
     */
    private static final double MIN_RELEVANT_SCORE = 1.0d;

    private final String baseUrl;
    private final String apiKey;
    private final boolean enabled;
    private final int timeoutSeconds;
    private final int count;
    private final String language;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeepSeekSearchClient(@Value("${deepseek.search.base-url:https://api.deepseek.com}") String baseUrl,
                                @Value("${deepseek.search.api-key:}") String apiKey,
                                @Value("${llm.api-key:}") String llmApiKey,
                                @Value("${deepseek.search.enabled:true}") boolean enabled,
                                @Value("${deepseek.search.timeout-seconds:20}") int timeoutSeconds,
                                @Value("${deepseek.search.count:10}") int count,
                                @Value("${searxng.language:zh-CN}") String language) {
        this.baseUrl = trimSlash(baseUrl);
        String key = apiKey == null || apiKey.isBlank() ? llmApiKey : apiKey;
        this.apiKey = key == null ? "" : key.trim();
        // 没 key 就当没开：调用必然 401，不如省掉一次网络往返
        this.enabled = enabled && !this.apiKey.isEmpty();
        this.timeoutSeconds = Math.max(3, Math.min(60, timeoutSeconds));
        this.count = Math.max(1, Math.min(MAX_COUNT, count));
        this.language = language == null ? "" : language.trim();
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 搜一次。任何异常都返回空列表（调用方据此决定是否保留 SearXNG 的原始结果）。
     *
     * @param publishedAfter 只要这个日期之后发布的网页（近一年检索用）；null 表示不限制
     */
    public List<SearxngClient.SearchHit> search(String query, LocalDate publishedAfter) {
        if (!enabled || query == null || query.isBlank()) {
            return List.of();
        }
        String effectiveQuery = applyFilters(query.trim(), publishedAfter);
        long started = System.nanoTime();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", clampQuery(effectiveQuery));
            payload.put("count", count);
            // need_content=false：正文我们自己用 WebPageTool 抓（那边有白名单/去噪），这里只要摘要
            payload.put("need_content", false);

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(8));
            factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
            RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();

            String json = client.post()
                    .uri("/v1/tools/search")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            List<SearxngClient.SearchHit> hits = parse(json, effectiveQuery.contains("site:"));
            long millis = (System.nanoTime() - started) / 1_000_000L;
            if (hits.isEmpty()) {
                log.info("DeepSeek 搜索兜底：没有可用结果 query={} ms={}", effectiveQuery, millis);
            } else {
                // 这条日志也是**花钱的账**：每次成功请求 0.005 元，按条数没法反推次数，所以按次记一行
                log.info("DeepSeek 搜索：命中 {} 条 query={} ms={}（计费 0.005 元）",
                        hits.size(), effectiveQuery, millis);
            }
            return hits;
        } catch (Exception e) {
            long millis = (System.nanoTime() - started) / 1_000_000L;
            // 兜底失败只能是"更好用的没拿到"，绝不能影响 SearXNG 已有的结果
            log.warn("DeepSeek 搜索兜底失败（忽略）query={} ms={} error={}", effectiveQuery, millis, e.toString());
            return List.of();
        }
    }

    /**
     * 解析结果，并按 {@code rerank_score} 过滤掉明显不相关的。
     *
     * <p>**为什么必须过滤**（2026-10-15 实测）：随便敲一串无意义字符（{@code zzzqqqxx12345asdfkkk}），
     * 它照样返回 5 条结果、照样计费，内容跟查询毫无关系。兜底本来就是为了"总比没有强"，
     * 但把这种噪声喂给模型，等于给它一堆编造素材 —— 不如老老实实说"没搜到"。
     *
     * <p>官方口径是 **≥1.5 分为优质**；实测受限搜索（{@code site:}）时分数普遍偏低（0.6~0.9），
     * 而那时域名已经替我们做了相关性判断，所以**带 {@code site:} 时不按分数砍**，只按域名砍（在 SearchTool 里）。
     */
    private List<SearxngClient.SearchHit> parse(String json, boolean siteConstrained) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<SearxngClient.SearchHit> hits = new ArrayList<>();
            int dropped = 0;
            for (JsonNode r : root.path("results")) {
                String url = r.path("url").asText("");
                if (url.isBlank()) {
                    continue;
                }
                double score = r.path("rerank_score").asDouble(0d);
                if (!siteConstrained && score < MIN_RELEVANT_SCORE) {
                    dropped++;
                    continue;
                }
                // summary（600~800 字）比 snippet（100~200 字）更适合给模型读，优先取它
                String content = r.path("summary").asText("");
                if (content.isBlank()) {
                    content = r.path("snippet").asText("");
                }
                hits.add(new SearxngClient.SearchHit(
                        url,
                        r.path("title").asText(""),
                        content,
                        r.path("date").asText("")));
            }
            if (dropped > 0) {
                log.info("DeepSeek 搜索兜底：丢掉 {} 条低分结果（rerank_score < {}）", dropped, MIN_RELEVANT_SCORE);
            }
            return hits;
        } catch (Exception e) {
            throw new IllegalStateException("DeepSeek 搜索返回解析失败", e);
        }
    }

    /** 把我们的检索条件翻译成它支持的搜索语法（{@code after:} 限定发布时间） */
    private String applyFilters(String query, LocalDate publishedAfter) {
        StringBuilder sb = new StringBuilder(query);
        if (publishedAfter != null) {
            sb.append(" after:").append(publishedAfter);
        }
        if (!language.isBlank() && !query.contains("lang:")) {
            sb.append(" lang:").append(shortLanguage(language));
        }
        return sb.toString();
    }

    /** {@code zh-CN} → {@code zh}：官方只认语言码，不认地区后缀 */
    private String shortLanguage(String value) {
        int cut = value.indexOf('-');
        return cut > 0 ? value.substring(0, cut) : value;
    }

    private String clampQuery(String query) {
        // 官方限制 1~128 个 Unicode 字符，超了直接 400；截断而不是报错
        return query.length() <= 128 ? query : query.substring(0, 128);
    }

    private String trimSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
