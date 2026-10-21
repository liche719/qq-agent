package com.liche.wechatagent.controller;

import com.liche.wechatagent.self.AgentSelfNote;
import com.liche.wechatagent.self.SelfWorkspaceService;
import com.liche.wechatagent.self.AgentSelfNoteRepository;
import com.liche.wechatagent.self.AgentSeedRepository;
import com.liche.wechatagent.bilibili.BiliCommentLog;
import com.liche.wechatagent.bilibili.BiliCommentLogRepository;
import com.liche.wechatagent.bilibili.BiliViewLog;
import com.liche.wechatagent.bilibili.BiliViewLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private final AgentSelfNoteRepository events;
    private final AgentSeedRepository seeds;
    private final BiliCommentLogRepository comments;
    private final BiliViewLogRepository views;
    private final boolean selfEnabled;
    private final boolean researchEnabled;
    private final boolean bilibiliEnabled;
    private final int bilibiliDailyLimit;
    /** 工作区（面板要看它在里面写了什么）；没启用时为 null */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SelfWorkspaceService workspace;

    public AdminSelfActivityController(AgentSelfNoteRepository events,
                                       AgentSeedRepository seeds,
                                       BiliCommentLogRepository comments,
                                       BiliViewLogRepository views,
                                       @Value("${memory.self-enabled:false}") boolean selfEnabled,
                                       @Value("${memory.self-research-enabled:true}") boolean researchEnabled,
                                       @Value("${memory.self-bilibili-enabled:true}") boolean bilibiliEnabled,
                                       @Value("${memory.self-bilibili-daily-limit:1}") int bilibiliDailyLimit) {
        this.events = events;
        this.seeds = seeds;
        this.comments = comments;
        this.views = views;
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
        long biliToday = events.countByKindNotAndTopicAndCreatedAtAfter(AgentSelfNote.KIND_BLOCK, BILIBILI_TOPIC,
                LocalDate.now(ZONE).atStartOfDay());
        List<AgentSelfNote> recent = events.findByKindNotAndTopicAndCreatedAtAfterOrderByIdDesc(
                AgentSelfNote.KIND_BLOCK, BILIBILI_TOPIC,
                LocalDate.now(ZONE).minusDays(7).atStartOfDay(), PageRequest.of(0, 1));
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

    /**
     * 它**看过哪些视频**（2026-10-20）。
     *
     * <p>用户的原话：「我不干扰它，但是我得知道它做了什麼」。这张表是**工具埋点**写的，
     * 不靠它自述——所以即使它的总结含糊，这里看到的也是真的。
     */
    /** 它在工作区里写了什么（面板「它自己」页签用） */
    @GetMapping("/workspace")
    public Map<String, Object> workspace() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (workspace == null) {
            result.put("enabled", false);
            result.put("usage", "（工作区没启用）");
            result.put("rows", List.of());
            return result;
        }
        result.put("enabled", true);
        result.put("root", workspace.rootPath());
        result.put("usage", workspace.usage());
        result.put("maxBytes", workspace.maxTotalBytes());
        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SelfWorkspaceService.Entry entry : workspace.entries(300)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", entry.path());
            row.put("kind", entry.directory() ? "目录" : "文件");
            row.put("size", entry.directory() ? "—" : readable(entry.sizeBytes()));
            row.put("modified", entry.modified());
            rows.add(row);
        }
        result.put("rows", rows);
        return result;
    }

    private static String readable(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    /**
     * 读一个工作区文件的内容（2026-10-20 加，用户要"能在面板上点开看，就像在服务器上点开"）。
     *
     * <p>用 POST 而不是 GET：前端描述式面板传参走 body（{@code {"path":"notes/x.md"}}），
     * 复用同一套注入机制，不用为这里新写一套 query 拼接。
     *
     * <p>路径安全由 {@link SelfWorkspaceService#read(String)} 保证（出不了根目录）。
     */
    @PostMapping("/workspace/file")
    public Map<String, Object> workspaceFile(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String path = body == null ? "" : body.getOrDefault("path", "");
        if (workspace == null) {
            result.put("content", "（工作区没启用）");
            return result;
        }
        result.put("content", workspace.read(path));
        return result;
    }

    /**
     * 删掉工作区里的一个文件（2026-10-20 加，**写操作**）。
     *
     * <p>用户明确要求"面板只有我一个人用，可以适当多给点权限，但某些操作还是要二次确认"。
     * 二次确认在前端（面板的行内操作带 {@code confirm}），后端这里只做路径校验
     * （{@link SelfWorkspaceService#delete(String)} 保证出不了根目录）。
     *
     * <p>**这是真删**，没有回收站。所以前端那句话必须说清"删了就没了"。
     */
    @PostMapping("/workspace/delete")
    public Map<String, Object> workspaceDelete(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String path = body == null ? "" : body.getOrDefault("path", "");
        if (workspace == null) {
            result.put("message", "工作区没启用");
            return result;
        }
        if (path.isBlank()) {
            result.put("message", "没给路径");
            return result;
        }
        try {
            result.put("message", workspace.delete(path));
        } catch (RuntimeException exception) {
            result.put("message", "删不掉：" + exception.getMessage());
        }
        return result;
    }

    @GetMapping("/bilibili-views")
    public Map<String, Object> bilibiliViews() {
        List<Map<String, Object>> rows = views.findTop100ByOrderByIdDesc().stream().map(view -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", view.getCreatedAt() == null ? "" : view.getCreatedAt().toString().replace('T', ' '));
            row.put("title", "https://www.bilibili.com/video/" + view.getBvid());
            row.put("video", view.getTitle());
            row.put("outcome", view.getOutcome() + (view.getDetail().isBlank() ? "" : "（" + view.getDetail() + "）"));
            // 2026-10-20：把"它读完自己记的摘要"摊出来——这才是"它到底记住了什么"
            row.put("summary", view.getSummary().isBlank() ? "（这条没记内容）" : view.getSummary());
            return row;
        }).toList();
        return Map.of("rows", rows);
    }

    /**
     * 它**在 B 站说过什么**（2026-10-20）：评论原文 + 结果。
     *
     * <p>数据一直在 {@code bili_comment_log} 里，只是没接到面板上；用户要"知道它做了什么"，
     * 这是最该摊开看的一样——用的是他的账号在说话。
     */
    @GetMapping("/bilibili-comments")
    public Map<String, Object> bilibiliComments() {
        List<Map<String, Object>> rows = comments.findAll(
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"))
                .stream().limit(100).map(comment -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("at", comment.getCreatedAt() == null ? ""
                            : comment.getCreatedAt().toString().replace('T', ' '));
                    row.put("video", comment.getVideoTitle());
                    row.put("content", comment.getMessage());
                    row.put("result", comment.succeeded() ? "已发出" : ("被拒 " + comment.getResultCode()
                            + "：" + comment.getResultMessage()));
                    // 2026-10-21：链接要**直达评论**，别让人在视频页里翻几百条。
                    // H5 的评论页认 oid(+root)，比网页版 #reply<rpid> 锚点可靠。
                    if (comment.getBvid().isBlank()) {
                        row.put("link", "");
                    } else if (comment.getRootRpid() > 0) {
                        // 楼中楼：落到那一楼的回复列表
                        row.put("link", "https://www.bilibili.com/h5/comment/sub?oid=" + comment.getAid()
                                + "&root=" + comment.getRootRpid());
                    } else {
                        // 顶级评论：同样进评论页，不用在视频页里翻
                        row.put("link", "https://www.bilibili.com/h5/comment/sub?oid=" + comment.getAid());
                    }
                    // 评论号是**它自己那条**的 rpid，不是 root_rpid（那个是"回复谁"，
                    // 顶级评论时是 0，之前这一列因此一直显示 0）
                    row.put("rpid", comment.getRpid() > 0 ? String.valueOf(comment.getRpid()) : "—");
                    return row;
                }).toList();
        return Map.of("rows", rows);
    }
}

