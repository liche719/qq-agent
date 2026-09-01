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
public class SearchTool {

    private static final Logger log = LoggerFactory.getLogger(SearchTool.class);

    private final SearxngClient searxngClient;
    private final ToolStatusService statusService;
    private final int timeoutSeconds;
    private final int maxResults;
    private final ZoneId timeZone;

    public SearchTool(SearxngClient searxngClient,
                      ToolStatusService statusService,
                      @Value("${searxng.timeout-seconds:15}") int timeoutSeconds,
                      @Value("${searxng.max-results:5}") int maxResults,
                      @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this.searxngClient = searxngClient;
        this.statusService = statusService;
        this.timeoutSeconds = Math.max(1, Math.min(120, timeoutSeconds));
        this.maxResults = Math.max(1, Math.min(10, maxResults));
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

    private String search(String query, String timeRange, boolean latest) {

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

        StringBuilder sb = new StringBuilder();
        if (latest) {
            sb.append("【最新事实检索】检索时间：")
                    .append(LocalDate.now(timeZone))
                    .append("（时区：").append(timeZone).append("）；结果范围：近一年。\n")
                    .append("注意：搜索摘要可能过时、截断或来自聚合站。不得仅凭摘要、旧版本规律或爆料给出‘当前’结论；应优先读取官方或原始发布者页面。若无法验证，应明确说无法确认。\n\n");
        }
        sb.append("搜索到以下资料（最多返回 ").append(deduped.size()).append(" 条）：\n");
        int i = 1;
        for (SearxngClient.SearchHit h : deduped) {
            sb.append(i++).append(". ").append(blankTo(h.title(), "（无标题）")).append("\n")
                    .append(h.url()).append(sourceDescription(h.url())).append("\n")
                    .append(h.publishedDate() == null || h.publishedDate().isBlank()
                            ? "发布时间：未提供\n" : "发布时间：" + h.publishedDate().trim() + "\n")
                    .append(truncate(blankTo(h.content(), "（无摘要）"), 150)).append("\n\n");
        }
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
