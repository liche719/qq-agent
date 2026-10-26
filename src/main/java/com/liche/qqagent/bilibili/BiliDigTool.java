package com.liche.qqagent.bilibili;

import com.liche.qqagent.search.DeepSeekSearchClient;
import com.liche.qqagent.search.SearxngClient;
import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolExecutionClass;
import com.liche.qqagent.tool.ToolExecutionPolicy;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 「带着一个念头，去 B 站找相关视频」（2026-10-20 加）。
 *
 * <p>为什么需要它：用户举的那条链路是"刷到视频 → 有想法 → 研究 → **再刷特定的视频深入了解**"。
 * 而 {@code browseBilibili} 只能给热门/关注/稍后再看——那是"随便看看"，不是"带着问题去找"。
 *
 * <p><b>为什么不用 B 站自己的搜索接口</b>：{@code /x/web-interface/wbi/search/type} 要 wbi 签名
 * （昨天实测几个 feed 接口不需要，但搜索需要）。我们已经有 DeepSeek 搜索，它支持 {@code site:}，
 * 用它加 {@code site:bilibili.com} 就够定位视频，**省下 100 行签名代码和一处风控面**。
 *
 * <p>它只给"候选清单"：标题 + 链接 + 一句摘要。要知道视频讲了什么，还得拿链接给
 * {@code readBilibiliVideo} 读字幕或抽帧。
 */
@Component
public class BiliDigTool implements com.liche.qqagent.tool.AgentToolProvider {

    private static final int MAX_RESULTS = 6;

    private final DeepSeekSearchClient deepSeek;

    public BiliDigTool(DeepSeekSearchClient deepSeek) {
        this.deepSeek = deepSeek;
    }

    @Tool(value = "带着一个具体问题去 B 站找视频（不是随便刷热门）。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public ToolBusinessResult digBilibili(String query, Integer days) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            return ToolBusinessResult.failure("没给关键词，不知道要找什么。");
        }
        if (!deepSeek.enabled()) {
            return ToolBusinessResult.failure("现在没有能用的网页搜索（DeepSeek 搜索没配），这轮定向找不了。");
        }
        LocalDate after = days == null || days <= 0 ? null : LocalDate.now().minusDays(days);
        List<SearxngClient.SearchHit> hits;
        try {
            hits = deepSeek.search(keyword + " site:bilibili.com", after);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("定向找 B 站视频失败：" + exception.getMessage(), exception);
        }
        List<SearxngClient.SearchHit> videos = hits.stream()
                .filter(hit -> hit.url() != null && hit.url().contains("bilibili.com/video/"))
                .limit(MAX_RESULTS)
                .toList();
        if (videos.isEmpty()) {
            return ToolBusinessResult.failure("按「" + keyword + "」没找到 B 站的视频，换个说法再试，或者这轮就算了。");
        }
        StringBuilder out = new StringBuilder("【带着问题找的 B 站视频】").append(videos.size()).append(" 条：\n");
        int index = 1;
        for (SearxngClient.SearchHit hit : videos) {
            out.append(index++).append(". ").append(nullSafe(hit.title())).append('\n')
                    .append("   ").append(hit.url()).append('\n');
            String summary = nullSafe(hit.content());
            if (!summary.isEmpty()) {
                out.append("   摘要：").append(summary.length() > 120 ? summary.substring(0, 119) + "…" : summary)
                        .append('\n');
            }
        }
        out.append("\n（要看某条讲了什么，把它的链接给 readBilibiliVideo。）");
        return ToolBusinessResult.success(out.toString());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
