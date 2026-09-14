package com.liche.wechatagent.agent;

/**
 * 系统提示词里的一段「动态段落」。
 *
 * <p>存在的意义：核心（{@link AgentPromptBuilder}）只认这个类型，**不认识任何具体模块**。
 * 模块（例如自主模块）自己产生段落、声明顺序，核心按 {@code order} 拼进提示词。
 *
 * @param order 插入顺序；越小越靠前（用户记忆是 0，自留地这类"它自己那一侧"用负数放到前面）
 * @param title 段落标题，例如「【我自己那侧】」
 * @param charLimit 这一段自己的预算上限（0 = 没设），只用于面板显示「用了多少 / 上限多少」
 * @param body  段落正文；**为空时核心会跳过整段**（连标题都不出现）
 */
public record PromptSection(int order, String title, String body, int charLimit) {

    /** 没声明上限的段落：面板上显示「没设」（0 = 不设上限） */
    public PromptSection(int order, String title, String body) {
        this(order, title, body, 0);
    }

    public boolean isBlank() {
        return body == null || body.isBlank();
    }
}
