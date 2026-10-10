package com.liche.wechatagent.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * QQ 结构化卡片（{@code message_type=3}）里的 {@code ark_data}。
 *
 * <p><b>为什么要解析它</b>：平台渲染给我们看的 {@code content} 只有「摘要 / source / title / preview」，
 * **跳转链接不在里面**；而官方文档写明 {@code ark_data.fields} 的常见键名包含 {@code jump_url}（跳转链接）。
 * 用户转发的 B站 / 小程序分享，只有拿到这个链接才谈得上"看内容"（2026-10-10 加）。
 *
 * <p>文档：<a href="https://bot.q.qq.com/wiki/develop/api-v2/autogen/event/c2c_message_create.html">单聊消息事件</a>。
 * 结构：
 * <pre>
 * ark_data: { ark_type, ark_name, prompt, fields: { title, desc, jump_url, preview, source, source_logo, tag... } }
 * </pre>
 * {@code ark_type} 取值：{@code miniapp}（小程序，B站转发就是它）/ {@code feed} / {@code video_share} / {@code tuwen} …
 *
 * <p><b>本类只做"提取 + 补链接"，不改 content 里已经有的平台渲染文本</b>——那些标题摘要平台已经给过了，
 * 重复一遍纯属浪费上下文（而且会挤掉真正有用的东西）。
 */
record QqArkCard(String arkType, String arkName, String title, String desc, String jumpUrl,
                 String previewUrl, List<String> fieldNames) {

    static final QqArkCard EMPTY = new QqArkCard("", "", "", "", "", "", List.of());

    static QqArkCard fromEvent(JsonNode event) {
        return fromNode(event == null ? null : event.path("ark_data"));
    }

    private static QqArkCard fromNode(JsonNode ark) {
        if (ark == null || ark.isMissingNode() || ark.isNull() || !ark.isObject()) {
            return EMPTY;
        }
        JsonNode fields = ark.path("fields");
        List<String> names = new ArrayList<>();
        if (fields.isObject()) {
            fields.fieldNames().forEachRemaining(names::add);
        }
        return new QqArkCard(
                text(ark, "ark_type"),
                text(ark, "ark_name"),
                first(fields, "title"),
                first(fields, "desc", "description", "summary"),
                // jump_url 是官方文档点名的键；后面几个是不同卡片类型的别名，按顺序碰运气
                first(fields, "jump_url", "jumpUrl", "qqdocurl", "url"),
                first(fields, "preview", "preview_url", "previewUrl"),
                List.copyOf(names));
    }

    boolean isEmpty() {
        return arkType.isBlank() && arkName.isBlank() && title.isBlank() && jumpUrl.isBlank();
    }

    /**
     * 把卡片信息补进这一轮的文本。
     *
     * <p>平台已经把标题/摘要渲染进 {@code content} 了，所以**正常情况下只补链接**；
     * 只有当 content 里什么都没有（卡片渲染缺失）时才退回完整渲染。
     */
    String applyTo(String existingContent) {
        if (isEmpty()) {
            return existingContent == null ? "" : existingContent;
        }
        String base = existingContent == null ? "" : existingContent;
        String body = base.isBlank() ? renderFull() : base;
        if (!jumpUrl.isBlank()) {
            return base.contains(jumpUrl) ? body : body + "\n链接：" + jumpUrl;
        }
        return body + NO_LINK_HINT;
    }

    /**
     * 卡片没带链接时的固定提示。
     *
     * <p>**实测（2026-10-10）**：B站转发过来的是 {@code ark_type=miniapp} 的小程序卡片，
     * 它的 {@code fields} 只有 {@code [preview, source, source_logo, title]}——官方文档里点名的
     * {@code jump_url} **并没有给**。所以"顺着链接去取字幕"对这类卡片走不通，只能靠标题去搜、
     * 或者让用户把链接发过来。这里把这件事**明确写进给模型的文本**，免得它对着一个不存在的链接瞎试，
     * 或者干脆凭标题编视频内容。
     */
    private static final String NO_LINK_HINT =
            "\n（这张卡片**没有携带链接**。上面那行 `title:` 就是它的完整标题——"
                    + "**把那个标题原样传给 readBilibiliVideo 就能读它的字幕**，不用再让他发链接；"
                    + "万一工具说搜不到标题一致的视频，再如实告诉他并要链接。**不要凭标题猜视频里讲了什么**。）";

    private String renderFull() {
        StringBuilder text = new StringBuilder("[卡片消息] ").append(arkName.isBlank() ? arkType : arkName);
        if (!title.isBlank()) {
            text.append("\n标题：").append(title);
        }
        if (!desc.isBlank() && !desc.equals(title)) {
            text.append("\n描述：").append(desc);
        }
        if (!jumpUrl.isBlank()) {
            text.append("\n链接：").append(jumpUrl);
        }
        return text.toString();
    }

    /** 封面图跟普通图片一样可以喂给视觉模型；只在这一轮有效，不会当成用户上传的图片存下来。 */
    List<String> withPreview(List<String> images) {
        if (previewUrl.isBlank()) {
            return images;
        }
        if (images == null || images.isEmpty()) {
            return List.of(previewUrl);
        }
        if (images.contains(previewUrl)) {
            return images;
        }
        List<String> merged = new ArrayList<>(images);
        merged.add(previewUrl);
        return List.copyOf(merged);
    }

    private static String text(JsonNode node, String name) {
        String value = node.path(name).asText("");
        return value == null ? "" : value.trim();
    }

    private static String first(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return "";
        }
        for (String name : names) {
            String value = node.path(name).asText("");
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
