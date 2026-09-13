package com.liche.wechatagent.search;

import com.liche.wechatagent.tool.ToolStatusService;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
import com.liche.wechatagent.tool.ToolExecutionClass;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 工具1：全网搜索工具。
 * - 对接 SearX-NG；结果去重降噪，每条截取核心摘要，最多返回 Top10
 * - 请求超时 15 秒（配置化），超时判定为失败进入重试
 * - 调用前主动推送状态提示「我正在搜索相关资料…」
 */
@Component
public class SearchTool implements com.liche.wechatagent.tool.AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(SearchTool.class);

    private final SearxngClient searxngClient;
    private final WebPageTool webPageTool;
    private final ToolStatusService statusService;
    private final int timeoutSeconds;
    private final int maxResults;
    private final int deepReadCount;
    private final int deepReadChars;
    private final ZoneId timeZone;

    /** 每个来源后面附一行带此标记的"标题 + 链接"，供 AgentLoop 在回复结尾统一生成"参考来源"。 */
    public static final String SOURCE_MARK = "🔗 ";

    public SearchTool(SearxngClient searxngClient,
                      WebPageTool webPageTool,
                      ToolStatusService statusService,
                      @Value("${searxng.timeout-seconds:15}") int timeoutSeconds,
                      @Value("${searxng.max-results:10}") int maxResults,
                      @Value("${searxng.deep-read-count:3}") int deepReadCount,
                      @Value("${searxng.deep-read-chars:1200}") int deepReadChars,
                      @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this.searxngClient = searxngClient;
        this.webPageTool = webPageTool;
        this.statusService = statusService;
        this.timeoutSeconds = Math.max(1, Math.min(120, timeoutSeconds));
        this.maxResults = Math.max(1, Math.min(10, maxResults));
        this.deepReadCount = Math.max(0, Math.min(5, deepReadCount));
        this.deepReadChars = Math.max(300, Math.min(6000, deepReadChars));
        this.timeZone = parseZone(timeZoneId);
    }

    @Tool(value = "搜索互联网获取资料，返回按部署配置限制数量的最相关结果。需要查询实时信息、新闻、未知资料时使用。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public String searchWeb(String query) {
        statusService.push("我正在搜索相关资料…");
        return search(requireQuery(query), null, false);
    }

    @Tool(value = "核对当前、最新、今天、正在使用的版本、价格、活动、新闻等会随时间变化的事实。会限定近一年结果并带入当前年月。拿到结果后，必须阅读可靠来源的原文再给出明确结论。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public String searchLatestWeb(String query) {
        statusService.push("我正在核对最新资料…");
        String requestedQuery = requireQuery(query);
        LocalDate today = LocalDate.now(timeZone);
        String datedQuery = requestedQuery + " " + today.getYear() + "年" + today.getMonthValue() + "月";
        return search(datedQuery, "year", true);
    }

    @Tool(value = "高可靠核验搜索：用于政策、法规、考试、医疗、价格、版本等不能仅凭单个摘要判断的事实。返回最多十条候选来源，并明确要求读取原文、比较至少两个独立发布者、检查日期和适用范围；证据不足时必须说明无法确认。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public String searchVerifiedWeb(String query) {
        statusService.push("我正在交叉核对多个来源…");
        String requestedQuery = requireQuery(query);
        LocalDate today = LocalDate.now(timeZone);
        return search(requestedQuery + " " + today.getYear() + "年" + today.getMonthValue() + "月",
                "year", true, true);
    }

    private String search(String query, String timeRange, boolean latest) {
        return search(query, timeRange, latest, false);
    }

    private String search(String query, String timeRange, boolean latest, boolean verified) {

        List<SearxngClient.SearchHit> hits;
        try {
            hits = searxngClient.search(query, timeoutSeconds, timeRange);
        } catch (Exception e) {
            log.warn("搜索失败 query={}", query, e);
            throw new IllegalStateException("搜索服务暂时不可用，没能找到相关资料", e);
        }

        // 去重降噪：URL 去重 + 标题归一化去重，截取核心摘要，最多 Top N
        List<SearxngClient.SearchHit> deduped = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        for (SearxngClient.SearchHit h : hits) {
            if (h.url() == null || h.url().isBlank()) {
                continue;
            }
            if (!seenUrls.add(h.url())) {
                continue;
            }
            String normTitle = normalize(h.title());
            if (normTitle != null && !seenTitles.add(normTitle)) {
                continue;
            }
            deduped.add(h);
            if (deduped.size() >= maxResults) {
                break;
            }
        }

        if (deduped.isEmpty()) {
            return "没有搜到相关资料，换个说法试试？";
        }

        Set<String> independentHosts = new java.util.LinkedHashSet<>();
        for (SearxngClient.SearchHit hit : deduped) {
            String host = hostOf(hit.url());
            if (host != null) {
                independentHosts.add(registrableHost(host));
            }
        }

        StringBuilder sb = new StringBuilder();
        if (latest) {
            sb.append("【最新事实检索】检索时间：")
                    .append(LocalDate.now(timeZone))
                    .append("（时区：").append(timeZone).append("）；结果范围：近一年。\n")
                    .append("注意：搜索摘要可能过时、截断或来自聚合站。不得仅凭摘要、旧版本规律或爆料给出‘当前’结论；应优先读取官方或原始发布者页面。若无法验证，应明确说无法确认。\n\n");
        }
        if (verified) {
            sb.append("【核验边界】当前仅得到搜索候选和摘要，不代表事实已核实。请读取排名靠前的原文，至少比较 ")
                    .append(Math.min(3, independentHosts.size()))
                    .append(" 个不同发布者，核对发布日期、适用地区/人群、版本和原文措辞；来源互相转载时只能算一个独立来源。若来源冲突或不足，不得给出确定结论。\n")
                    .append("独立发布者域名数（粗略去重）：").append(independentHosts.size()).append("\n\n");
        }
        sb.append("搜索到以下资料（最多返回 ").append(deduped.size()).append(" 条）：\n");
        int i = 1;
        for (SearxngClient.SearchHit h : deduped) {
            int index = i++;
            sb.append(index).append(". ").append(blankTo(h.title(), "（无标题）")).append("\n")
                    .append(h.url()).append(sourceDescription(h.url())).append("\n")
                    .append(h.publishedDate() == null || h.publishedDate().isBlank()
                            ? "发布时间：未提供\n" : "发布时间：" + h.publishedDate().trim() + "\n")
                    .append(truncate(blankTo(h.content(), "（无摘要）"), 150)).append("\n")
                    // 程序据此生成回复结尾的"参考来源"，不要删掉这一行
                    .append(SOURCE_MARK).append(index).append(". ")
                    .append(blankTo(h.title(), "（无标题）")).append(" — ").append(h.url()).append("\n\n");
        }

        // 深入读原文：只有摘要容易被聚合站带偏，抓前几条正文后回答才有依据
        int deep = Math.min(deepReadCount, deduped.size());
        if (deep > 0 && webPageTool != null) {
            StringBuilder deepSection = new StringBuilder();
            int read = 0;
            for (int index = 0; index < deep; index++) {
                SearxngClient.SearchHit hit = deduped.get(index);
                String text = webPageTool.fetchTextQuietly(hit.url(), deepReadChars);
                if (text == null || text.isBlank()) {
                    continue;
                }
                read++;
                deepSection.append("[").append(index + 1).append("] ")
                        .append(blankTo(hit.title(), "（无标题）")).append("\n")
                        .append(hit.url()).append("\n")
                        .append(text).append("\n\n");
            }
            if (read > 0) {
                sb.append("【原文摘录】（已抓取排名靠前的 ").append(read)
                        .append(" 条正文，内容有截断，仅供核对事实）\n")
                        .append(deepSection).append("\n");
            }
        }

        sb.append("【回答要求】优先依据上面的原文摘录作答；引用某条资料时在句末用 [编号] 标注（例如 [1]）；")
                .append("参考来源列表由程序在回复结尾统一附加，正文不必自己再列一遍。");
        return sb.toString().trim();
    }

    /**
     * Return observable source metadata only.  A hostname alone is not proof that
     * a page is official, authoritative, or safe; those judgements belong to the
     * agent after it reads the page and compares the publisher and evidence.
     */
    private String sourceDescription(String url) {
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (Exception ignored) {
            return "\n来源域名：未知（请核验原始发布者）";
        }
        return host == null || host.isBlank()
                ? "\n来源域名：未知（请核验原始发布者）"
                : "\n来源域名：" + host + "（域名本身不代表权威性，请核验原始发布者并交叉验证）";
    }

    private String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String registrableHost(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        String[] labels = normalized.split("\\.");
        return labels.length < 2 ? normalized : labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private String normalize(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        return title.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String blankTo(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s.trim();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        return query.strip();
    }

    private ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
