package com.liche.qqagent.support;

/**
 * 数值夹取（2026-10-28，去重：`bounded` 在 10 个文件里各写了一份）。
 *
 * <p>语义就一句：**越界就用默认值，而不是夹到边界**。这个区别很重要——
 * 配置写成 0 或 99999 通常意味着"写错了"，静默夹到 1 或 32 会让人以为配置生效了。
 *
 * <p>仓库里这 10 份实现**逐字相同**，所以可以安全收敛；只要有一份语义不同（比如夹到边界），
 * 就不能合并。
 */
public final class Numbers {

    private Numbers() {
    }

    public static int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    public static long bounded(long value, long minimum, long maximum, long fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }
}
