package com.liche.wechatagent.controller;

import com.liche.wechatagent.self.AgentSelfEvent;
import com.liche.wechatagent.self.AgentSelfEventRepository;
import com.liche.wechatagent.self.AgentSeedRepository;
import com.liche.wechatagent.bilibili.BiliCommentLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面板「它自己」页签里的**「自主活动」**一节（2026-10-20 加）。
 *
 * <p>为什么要单开一个接口：活动是 2026-10-20 新抽出来的一层（{@code SelfActivity}），
 * 面板上原来只有"研究方向"，看不见"它今天刷没刷 B 站、刷完说了什么"。
 * 用户要的是"能一眼看出它在自己的时间里干了什么"，所以这里把每个活动最近一次的结果摊开。
 *
 * <p>全是只读；一个活动崩了不影响别的活动那一行。
 */
@RestController
@RequestMapping("/api/admin/self")
public class AdminSelfActivityController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String BILIBILI_TOPIC = "bilibili";

    private final AgentSelfEventRepository events;
    private final AgentSeedRepository seeds;
    private final BiliCommentLogRepository comments;
    private final boolean selfEnabled;
    private final boolean researchEnabled;
    private final boolean bilibiliEnabled;
    private final int bilibiliDailyLimit;

    public AdminSelfActivityController(AgentSelfEventRepository events,
                                       AgentSeedRepository seeds,
                                       BiliCommentLogRepository comments,
                                       @Value("${memory.self-enabled:false}") boolean selfEnabled,
                                       @Value("${memory.self-research-enabled:true}") boolean researchEnabled,
                                       @Value("${memory.self-bilibili-enabled:true}") boolean bilibiliEnabled,
                                       @Value("${memory.self-bilibili-daily-limit:1}") int bilibiliDailyLimit) {
        this.events = events;
        this.seeds = seeds;
        this.comments = comments;
        this.selfEnabled = selfEnabled;
        this.researchEnabled = researchEnabled;
        this.bilibiliEnabled = bilibiliEnabled;
        this.bilibiliDailyLimit = bilibiliDailyLimit;
    }

    /** 面板的表格直接渲染数组，所以这里返回 List 而不是包一层对象 */
    @GetMapping("/activities")
    public List<Map<String, Object>> activities() {
        long openSeeds = seeds.countByStatus("OPEN");
        long todayComments = comments.countSucceededSince(LocalDate.now(ZONE).atStartOfDay());
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(selfEnabled ? "总开关开着" : "总开关关着（什么都没在跑）", "自主活动",
                "念头 " + openSeeds + " 个没消化", "今天评论 " + todayComments + " 条", "—"));
        // 刷 B 站：次数来自 agent_self_event 的 topic 标记（和护栏用的是同一份数据）
        long biliToday = events.countByTopicAndCreatedAtAfter(BILIBILI_TOPIC, LocalDate.now(ZONE).atStartOfDay());
        List<AgentSelfEvent> recent = events.findByTopicAndCreatedAtAfterOrderByIdDesc(
                BILIBILI_TOPIC, LocalDate.now(ZONE).minusDays(7).atStartOfDay(), PageRequest.of(0, 1));
        rows.add(row(bilibiliEnabled ? "开着" : "关着", "刷会儿 B 站",
                biliToday + " / " + bilibiliDailyLimit,
                recent.isEmpty() ? "—" : recent.get(0).getCreatedAt().toString().replace('T', ' ').substring(0, 16),
                recent.isEmpty() ? "（还没跑过）" : recent.get(0).getContent()));
        // 研究：这一层没有次数统计（它的账在 agent_quest_run），这里如实标"见上面的方向/笔记"
        rows.add(row(researchEnabled ? "开着" : "关着", "研究我自己的方向", "见上面的方向与笔记", "—",
                "它自己的研究账在「它自己的方向」那一节"));

        return rows;
    }

    private Map<String, Object> row(String state, String activity, String today, String lastAt, String lastSummary) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("state", state);
        row.put("activity", activity);
        row.put("today", today);
        row.put("lastAt", lastAt);
        row.put("lastSummary", lastSummary);
        return row;
    }
}

