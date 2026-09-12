package com.liche.wechatagent.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 陪练模式预设（英语陪练 / 面试陪练）。
 *
 * <p>设计要点：**不改用户的人设**，而是给系统提示词追加一段"本次对话的额外要求"。
 * 因此退出陪练只需要清掉 {@code UserProfile.coachMode}，用户原来的说话风格原样保留。
 */
public final class CoachPresets {

    public static final String ENGLISH = "english";
    public static final String INTERVIEW = "interview";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    private static final Map<String, String> DIRECTIVES = new LinkedHashMap<>();

    static {
        LABELS.put(ENGLISH, "英语陪练");
        LABELS.put(INTERVIEW, "面试陪练");

        DIRECTIVES.put(ENGLISH, """
                【当前处于「英语陪练」模式，以下要求优先于一般聊天习惯】
                1. 用英语和用户对话；难度按对方实际水平浮动（默认 B1~B2），对方明显吃力就放慢、缩短句子并给出中文提示。
                2. 每一轮都先自然回应对方说的内容（1~3 句），再把对话往前推：提一个问题或给一个可继续的话头。
                3. 纠错要克制：只挑 1~3 处最影响表达的（语法、选词、时态、地道度），格式用「你说：… → 更自然：…（中文一句解释）」，其余错误先放过。
                4. 不要一次给出长篇讲解或单词表；除非对方明确要语法讲解，否则保持陪练节奏。
                5. 用户用中文提问时，用中文解释，但练习部分继续用英语。
                6. 结尾可以用一行极短的提示（例如 🔤 今天练到：past tense），不要写总结段落。""");

        DIRECTIVES.put(INTERVIEW, """
                【当前处于「面试陪练」模式，以下要求优先于一般聊天习惯】
                1. 你扮演面试官：一次只问一个问题，问完就停下等对方回答，不要连续抛多个问题。
                2. 对方回答后先做简短追问（挖细节、问数字、问取舍），模拟真实面试节奏；对方答得含糊就继续追问，直到具体。
                3. 每 3~5 轮给一次结构化反馈，格式：「✅ 亮点：…／⚠️ 风险：…／💡 改法：…」，并给 1~5 星评分。
                4. 默认按用户投递的岗位提问；用户没说过岗位就先问清楚（岗位、年限、方向），之后再把问题贴近该岗位。
                5. 不要替对方写答案：可以示范"更好的说法"，但要对方自己再讲一遍。
                6. 保持简短，单条回复尽量不超过 300 字。""");
    }

    private CoachPresets() {
    }

    /** 把用户输入解析成模式标识：english / interview，无法识别返回 null。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        if (value.contains("english") || value.contains("英语") || value.contains("英文")) {
            return ENGLISH;
        }
        if (value.contains("interview") || value.contains("面试")) {
            return INTERVIEW;
        }
        return null;
    }

    /** 是否是"关闭陪练"的输入。 */
    public static boolean isOff(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        return value.contains("off") || value.contains("end") || value.contains("stop")
                || value.contains("结束") || value.contains("停止") || value.contains("退出")
                || value.contains("关闭") || value.contains("不练") || value.contains("取消");
    }

    /** 模式的中文名；未知返回 null。 */
    public static String label(String mode) {
        return mode == null ? null : LABELS.get(mode.toLowerCase(Locale.ROOT));
    }

    /** 把陪练模式的要求拼到人设后面；未开启时原样返回人设。 */
    public static String withMode(String persona, String coachMode) {
        String directive = directive(coachMode);
        if (directive == null) {
            return persona;
        }
        String base = persona == null ? "" : persona.strip();
        return base.isEmpty() ? directive : base + "\n\n" + directive;
    }

    /** 模式对应的提示词片段；未开启或未知模式返回 null。 */
    public static String directive(String coachMode) {
        return coachMode == null ? null : DIRECTIVES.get(coachMode.toLowerCase(Locale.ROOT));
    }
}
