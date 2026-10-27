package com.liche.qqagent.channel.qq;

import java.util.ArrayList;
import java.util.List;

/**
 * 数值/退避解析这类纯函数（2026-10-27 从 {@code QqChannel} 拆出，第 6 步）。
 *
 * <p>为什么值得单独放：`bounded` 在 channel 里被用了 18 次（各处配置项夹取范围），
 * `parseBackoff` 解析重连退避序列——它俩都是"边界处理"，恰恰是最容易写错、也最值得测的部分。
 */
final class QqNumbers {

    private QqNumbers() {
    }

    static long bounded(long value, long minimum, long maximum, long fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    static int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    /**
     * 解析重连退避序列（逗号分隔的毫秒）。
     *
     * <p>与拆之前一致：非法/空/全部越界时回落到默认序列 `2s, 5s, 10s, 30s`；
     * 单项只在 [250ms, 600000ms] 内才收。
     */
    static long[] parseBackoff(String value) {
        long[] defaults = {2_000L, 5_000L, 10_000L, 30_000L};
        if (value == null || value.isBlank()) {
            return defaults;
        }
        List<Long> values = new ArrayList<>();
        for (String part : value.split(",")) {
            try {
                long parsed = Long.parseLong(part.trim());
                if (parsed >= 250 && parsed <= 600_000) {
                    values.add(parsed);
                }
            } catch (NumberFormatException ignored) {
                // 单项写错就跳过，不影响其它项
            }
        }
        if (values.isEmpty()) {
            return defaults;
        }
        long[] result = new long[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index);
        }
        return result;
    }
}
