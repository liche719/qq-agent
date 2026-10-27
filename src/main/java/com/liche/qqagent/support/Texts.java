package com.liche.qqagent.support;

/**
 * 文本裁剪（2026-10-27，第 9 步：把散落在各处的 {@code clip} 收敛到这里）。
 *
 * <p>之前仓库里 **16 个文件各写了一个 `private static String clip(...)`**，共 10 种不同实现：
 * 有的超长时 `substring(0, max-1)+"…"`、有的 `substring(0, max)+"…"`、有的先压平空白、
 * 有的 null 返回 `null`。差异本身不算错，但"看起来都一样、行为各不同"最容易在排查时误判。
 *
 * <p>本类只提供三种**语义明确**的方法，迁移时按原行为逐文件选择，绝不"顺手统一"——
 * 那会改变日志长度、甚至改变落库内容。
 */
public final class Texts {

    private Texts() {
    }

    /**
     * 裁到 max 个字符，超长时用 `…` 占位（**总长正好 max**）。
     *
     * <p>对应原实现：`length <= max ? text : substring(0, max-1) + "…"`；null 视为空串。
     */
    public static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /**
     * 裁到 max 个字符后**再加** `…`（总长最多 max+1）。
     *
     * <p>对应原实现：`length <= max ? text : substring(0, max) + "…"`；null 视为空串。
     * 与 {@link #clip} 的区别只在超长时的总长度，迁移时别混用。
     */
    public static String clipWide(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0) {
            return "…";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /**
     * 先压平空白（连续空白→一个空格、去首尾）再裁，超长用 `…`（总长最多 max+1）。
     *
     * <p>对应原实现：`flat = text.replaceAll("\\s+", " ").trim(); flat.length() <= max ? flat : flat.substring(0, max) + "…"`。
     * 用于"给模型/日志看的单行摘要"——多行原文直接裁会把换行带进去。
     */
    public static String clipFlat(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        if (max <= 0) {
            return "…";
        }
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /**
     * 裁剪但**保留 null**（不把 null 变成空串）。
     *
     * <p>对应原实现：`null -> null`。调用方用 null 表示"没有内容"，与空串语义不同，
     * 所以单独一个方法，不能拿 {@link #clipWide} 顶替。
     */
    public static String clipOrNull(String text, int max) {
        if (text == null) {
            return null;
        }
        if (max <= 0) {
            return "…";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
