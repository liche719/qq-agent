package com.liche.wechatagent.search;

import com.liche.wechatagent.tool.ToolStatusService;
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
 * - 对接 SearX-NG；结果去重降噪，每条截取核心摘要，最多返回 Top5
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

    public SearchTool(SearxngClient searxngClient,
                      ToolStatusService statusService,
                      @Value("${searxng.timeout-seconds:15}") int timeoutSeconds,
                      @Value("${searxng.max-results:5}") int maxResults) {
        this.searxngClient = searxngClient;
        this.statusService = statusService;
        this.timeoutSeconds = timeoutSeconds;
        this.maxResults = maxResults;
    }

    @Tool(value = "搜索互联网获取最新资料，返回最相关的Top5条结果。需要查询实时信息、新闻、未知资料时使用。")
    public String searchWeb(String query) {
        statusService.push("我正在搜索相关资料…");

        return search(query, null, false);
    }

    @Tool(value = "核对当前、最新、今天、正在使用的版本、价格、活动、新闻等会随时间变化的事实。会限定近一年结果并带入当前年月。拿到结果后，必须阅读可靠来源的原文再给出明确结论。")
    public String searchLatestWeb(String query) {
        statusService.push("我正在核对最新资料…");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String datedQuery = query + " " + today.getYear() + "年" + today.getMonthValue() + "月";
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
                    .append(LocalDate.now(ZoneId.of("Asia/Shanghai")))
                    .append("（北京时间）；结果范围：近一年。\n")
                    .append("注意：搜索摘要可能过时、截断或来自聚合站。不得仅凭摘要、旧版本规律或爆料给出‘当前’结论；应优先读取官方或原始发布者页面。若无法验证，应明确说无法确认。\n\n");
        }
        sb.append("搜索到以下资料：\n");
        int i = 1;
        for (SearxngClient.SearchHit h : deduped) {
            sb.append(i++).append(". ").append(blankTo(h.title(), "（无标题）")).append("\n")
                    .append(h.url()).append(sourceWarning(h.url())).append("\n")
                    .append(h.publishedDate() == null || h.publishedDate().isBlank()
                            ? "发布时间：未提供\n" : "发布时间：" + h.publishedDate().trim() + "\n")
                    .append(truncate(blankTo(h.content(), "（无摘要）"), 150)).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String sourceWarning(String url) {
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (Exception ignored) {
            return "\n来源风险：未知";
        }
        if (host == null) return "\n来源风险：未知";
        String normalized = host.toLowerCase();
        if (normalized.endsWith(".mihoyo.com") || normalized.endsWith(".hoyoverse.com")
                || normalized.endsWith(".gov.cn") || normalized.endsWith(".edu.cn")) {
            return "\n来源：官方或机构域名";
        }
        if (normalized.equals("ai.so.com") || normalized.endsWith(".so.com")
                || normalized.contains("baidu.com") || normalized.contains("sogou.com")) {
            return "\n来源风险：搜索聚合页，不可作为事实依据";
        }
        return "\n来源：第三方，需交叉验证";
    }

    private String normalize(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        return title.replaceAll("\s+", "").toLowerCase();
    }

    private String blankTo(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s.trim();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
