package com.liche.wechatagent.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 判断这一轮对话要不要用"省电档"（关掉深度思考）。
 *
 * <p>**为什么不用模型判断**：升/降档要在发请求之前决定，而模型只有看到问题之后才知道难不难；
 * 外部路由器要多一次 LLM 调用（QQ 首字延迟翻倍）。这里的信号都是程序能直接看到的，**纯规则、可单测**。
 *
 * <p>**为什么做得这么保守**：判错的后果是"这一轮答得差一点"，而且**不容易被发现**。
 * 所以只有同时满足下面全部条件才走省电档：
 * <ol>
 *   <li>没有附件/引用内容（有资料要读，必须认真对待）；</li>
 *   <li>很短（去空白后 ≤ {@value #MAX_FAST_CHARS} 字）；</li>
 *   <li>整句就是寒暄/确认（白名单匹配，或纯粹的"哈哈哈"）；</li>
 *   <li>不含任何"要做事情"的线索词（提醒/查/搜/记/任务/高数/冲刺……）；</li>
 *   <li>**不是"在回答上一轮的问题"**——上一轮机器人问了句什么、用户只回一个"好/行/可以"，
 *       这种"裸答应"很可能需要真的去做事（建提醒、改计划），所以一律回默认档。</li>
 * </ol>
 * 命中任一条件就回默认档——**方向永远是"宁可不省电"**。
 *
 * <p>省电档用独立场景 {@code dialog_fast} 记账，所以面板能直接看出"省电档占多少、效果如何"，
 * 不合适就把 {@code llm.dialog-fast.enabled} 关掉（一行配置回滚）。
 */
@Component
public class DialogModeDecider {

    /** 超过这个字数就别想着省电了（中文按字符数算足够） */
    private static final int MAX_FAST_CHARS = 12;

    /** 整句就是这些寒暄/确认词时才算省电档（宁可漏，不可错） */
    private static final Set<String> GREETINGS = Set.of(
            "在吗", "在不在", "在么", "在不在啊", "在吗在吗",
            "早", "早上好", "早安", "中午好", "下午好", "晚上好", "晚安", "睡了", "去睡了",
            "好的", "好滴", "好嘞", "好哒", "好吧", "好", "行", "可以", "没问题", "中",
            "嗯", "嗯嗯", "嗯呐", "哦", "哦哦", "噢", "知道了", "明白了", "懂了", "了解", "收到",
            "谢谢", "谢谢你", "谢了", "多谢", "感谢", "辛苦了", "辛苦啦",
            "哈哈", "哈哈哈", "哈哈哈哈", "笑死", "好的好的", "没事", "没事了", "没关系",
            "拜拜", "再见", "回聊", "先这样", "ok", "okk", "okay", "nice", "good",
            "好，谢谢", "好的谢谢", "谢谢啦", "3q", "thx");

    /** 纯粹的"哈哈哈/嘿嘿/嘻嘻"这类：长度不定，用模式匹配（不然"哈哈哈哈哈"会掉出白名单） */
    private static final Pattern PURE_LAUGHTER = Pattern.compile("^[哈嘻嘿呵]{2,}$");

    /** 这些是"裸答应"：如果上一轮机器人在问问题，用户这么回就意味着要真的去做事 */
    private static final Set<String> BARE_YES = Set.of(
            "好", "好的", "好吧", "行", "可以", "中", "嗯", "嗯嗯", "对", "是的", "要", "需要",
            "没问题", "ok", "okk", "okay", "好滴", "好嘞", "好哒");

    /** 命中任何一个就回默认档：这些词意味着"有事要做" */
    private static final List<String> ACTION_HINTS = List.of(
            "提醒", "记住", "记一下", "记个", "帮我", "帮忙", "帮", "查", "搜", "找一下", "看看", "看一下",
            "文件", "图片", "图", "课表", "资料", "文档", "pdf", "excel", "表格",
            "考研", "备考", "考试", "复习", "学习", "打卡", "错题", "进度", "里程碑", "科目", "数学", "英语",
            "政治", "408", "真题", "初试", "复试", "刷题", "背单词", "单词", "墨墨",
            "高数", "线代", "概率", "单词书", "网课", "二刷", "一轮", "二轮", "三轮", "冲刺", "真题卷", "套卷",
            "任务", "计划", "定时", "日程", "安排", "几点", "几号", "星期几", "什么时候", "多久",
            "怎么", "为什么", "能不能", "可不可以", "是不是", "多少", "几个", "哪个", "哪里",
            "改", "删", "加", "新建", "创建", "设置", "取消", "暂停", "开启", "打开", "关闭",
            "天气", "新闻", "股价", "汇率", "翻译", "总结", "分析", "对比", "推荐",
            "仔细", "认真", "好好想想", "深入", "推演", "核对", "检查", "验证");

    private final boolean enabled;

    public DialogModeDecider(@Value("${llm.dialog-fast.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public record Decision(boolean fast, String reason) {
    }

    /**
     * @param userText              用户这一条消息
     * @param hasAttachments        是否带图片/文档/引用内容
     * @param previousAssistantText 机器人上一轮说的话（可空），用来识别"裸答应"
     */
    public Decision decide(String userText, boolean hasAttachments, String previousAssistantText) {
        if (!enabled) {
            return new Decision(false, "省电档已关闭");
        }
        if (hasAttachments) {
            return new Decision(false, "带附件/引用，要读资料");
        }
        if (userText == null || userText.isBlank()) {
            return new Decision(false, "空消息");
        }
        String normalized = userText.trim().toLowerCase().replaceAll("\\s+", "");
        if (normalized.length() > MAX_FAST_CHARS) {
            return new Decision(false, "消息较长");
        }
        String stripped = normalized.replaceAll("[\\p{Punct}。，、！？~～…·\\s]", "");
        if (stripped.isEmpty()) {
            return new Decision(false, "只有标点/表情");
        }
        for (String hint : ACTION_HINTS) {
            if (normalized.contains(hint)) {
                return new Decision(false, "含线索词「" + hint + "」");
            }
        }
        boolean greeting = GREETINGS.contains(stripped) || GREETINGS.contains(normalized)
                || PURE_LAUGHTER.matcher(stripped).matches();
        if (!greeting) {
            return new Decision(false, "不在寒暄白名单里");
        }
        if (BARE_YES.contains(stripped) && askedSomething(previousAssistantText)) {
            return new Decision(false, "在回答上一轮的问题，可能要真去做事");
        }
        return new Decision(true, "寒暄/确认类短句");
    }

    /** 上一轮机器人是不是在问用户问题（看结尾一段里有没有问号） */
    private boolean askedSomething(String previousAssistantText) {
        if (previousAssistantText == null || previousAssistantText.isBlank()) {
            return false;
        }
        String tail = previousAssistantText.strip();
        int from = Math.max(0, tail.length() - 200);
        String window = tail.substring(from);
        return window.contains("？") || window.contains("?");
    }
}
