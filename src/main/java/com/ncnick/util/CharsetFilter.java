package com.ncnick.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 字符集白名单校验器（第 3 条：可配置可用字符功能）。
 *
 * <p>配置里用 Unicode 区间（如 {@code \\u4E00-\\u9FFF}）描述允许的字符。
 * 本类负责把这些区间编译成高效的码点区间表，并提供校验与"非法字符"提取能力。
 *
 * <p>关键点：中文扩展 B 区（U+20000 以上）在 Java 中是代理对，
 * 必须按码点遍历，不能按 char 遍历，否则会被误判。
 */
public final class CharsetFilter {

    /** 已编译的允许区间，按起始码点升序排列，支持二分查找 */
    private final List<int[]> allowedRanges;
    /** 额外允许的字符（码点集合） */
    private final Set<Integer> extraAllowed;
    /** 硬性禁止的字符（码点集合） */
    private final Set<Integer> forbidden;

    private CharsetFilter(List<int[]> allowedRanges, Set<Integer> extraAllowed, Set<Integer> forbidden) {
        this.allowedRanges = allowedRanges;
        this.extraAllowed = extraAllowed;
        this.forbidden = forbidden;
    }

    /**
     * 校验结果。
     */
    public static final class Result {
        private final boolean valid;
        private final String invalidChars;
        private final int visibleLength;

        Result(boolean valid, String invalidChars, int visibleLength) {
            this.valid = valid;
            this.invalidChars = invalidChars;
            this.visibleLength = visibleLength;
        }

        /** 是否全部字符都合法。 */
        public boolean isValid() {
            return valid;
        }

        /** 不合法的字符集合（去重后拼接），合法时为空串。 */
        public String getInvalidChars() {
            return invalidChars;
        }

        /** 可见字符数量（已按码点计数）。 */
        public int getVisibleLength() {
            return visibleLength;
        }
    }

    /**
     * 校验一段文本。
     *
     * <p>注意：调用方应传入已剥离颜色代码的纯文本。
     *
     * @param text 待校验文本
     * @return 校验结果
     */
    public Result validate(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(false, "", 0);
        }

        StringBuilder invalid = new StringBuilder();
        Set<Integer> seenInvalid = new LinkedHashSet<Integer>();
        int visibleCount = 0;

        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            i += charCount;

            // 换行等控制字符直接算非法
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                if (seenInvalid.add(cp)) {
                    invalid.append(describe(cp));
                }
                continue;
            }

            visibleCount++;

            if (forbidden.contains(cp)) {
                if (seenInvalid.add(cp)) {
                    invalid.append(describe(cp));
                }
                continue;
            }
            if (extraAllowed.contains(cp)) {
                continue;
            }
            if (!inAllowedRanges(cp)) {
                if (seenInvalid.add(cp)) {
                    invalid.append(describe(cp));
                }
            }
        }

        boolean ok = invalid.length() == 0;
        return new Result(ok, invalid.toString(), visibleCount);
    }

    /** 按码点统计可见字符数。 */
    public static int countVisible(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.codePointCount(0, text.length());
    }

    /**
     * 判断单个码点是否在允许区间内（二分查找）。
     */
    private boolean inAllowedRanges(int cp) {
        int lo = 0;
        int hi = allowedRanges.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int[] range = allowedRanges.get(mid);
            if (cp < range[0]) {
                hi = mid - 1;
            } else if (cp > range[1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** 允许区间数量，用于启动时自检。 */
    public int rangeCount() {
        return allowedRanges.size();
    }

    /** 是否允许任何字符（用于检测配置错误）。 */
    public boolean isEmpty() {
        return allowedRanges.isEmpty() && extraAllowed.isEmpty();
    }

    private static String describe(int cp) {
        if (cp >= 0x20 && cp < 0x7F) {
            return String.valueOf((char) cp);
        }
        try {
            return new String(Character.toChars(cp));
        } catch (Throwable ignored) {
            return String.format("\\u%04X", cp);
        }
    }

    // ==========================================================
    //  构建
    // ==========================================================

    /**
     * 构建器：从配置读出的区间字符串列表编译出校验器。
     */
    public static final class Builder {
        private final List<int[]> ranges = new ArrayList<int[]>();
        private final Set<Integer> extra = new LinkedHashSet<Integer>();
        private final Set<Integer> forbidden = new LinkedHashSet<Integer>();

        /**
         * 添加一条区间定义。
         *
         * <p>支持格式：
         * <ul>
         *   <li>{@code \\u4E00-\\u9FFF} —— 转义或未转义的 Unicode 区间</li>
         *   <li>{@code a-zA-Z} —— ASCII 区间</li>
         *   <li>{@code 0-9} —— 数字区间</li>
         *   <li>{@code \\u00B7} —— 单个码点</li>
         * </ul>
         *
         * @param spec 区间描述，可含多个用逗号分隔的段
         * @return this
         */
        public Builder addRangeSpec(String spec) {
            if (spec == null || spec.trim().isEmpty()) {
                return this;
            }
            // 支持一条配置里用逗号写多个区间
            String[] parts = spec.split(",");
            for (String part : parts) {
                addSingleRange(part.trim());
            }
            return this;
        }

        private void addSingleRange(String s) {
            if (s.isEmpty()) {
                return;
            }
            int dash = findSeparator(s);
            if (dash < 0) {
                // 单字符
                int cp = parseCodepoint(s);
                if (cp >= 0) {
                    ranges.add(new int[]{cp, cp});
                }
                return;
            }
            String left = s.substring(0, dash);
            String right = s.substring(dash + 1);
            int from = parseCodepoint(left);
            int to = parseCodepoint(right);
            if (from >= 0 && to >= 0 && from <= to) {
                ranges.add(new int[]{from, to});
            }
        }

        /**
         * 找到区间的分隔符位置。需要注意 "a-zA-Z" 这种一段里含多个区间的写法，
         * 本方法只处理第一段，由调用方按需拆分。
         */
        private int findSeparator(String s) {
            // 跳过可能的 \\u 前缀，避免把 "\\u4E00-\\u9FFF" 里的 '-' 找错
            for (int i = 1; i < s.length() - 1; i++) {
                if (s.charAt(i) == '-') {
                    // 排除 "a-zA-Z" 的中间位置：'-' 前后都得是合法端点
                    String l = s.substring(0, i);
                    String r = s.substring(i + 1);
                    // 右侧若还含 '-'，取到下一个 '-' 之前
                    int nextDash = r.indexOf('-');
                    if (nextDash > 0) {
                        r = r.substring(0, nextDash);
                    }
                    if (parseCodepoint(l) >= 0 && parseCodepoint(r) >= 0) {
                        return i;
                    }
                }
            }
            return -1;
        }

        /**
         * 解析一个码点端点。
         * 支持：{@code \\uXXXX}、{@code uXXXX}、{@code 0xXXXX}、单字符、ASCII 字符。
         *
         * @return 码点，失败返回 -1
         */
        private int parseCodepoint(String s) {
            if (s == null || s.isEmpty()) {
                return -1;
            }
            String t = s.trim();
            if (t.isEmpty()) {
                return -1;
            }

            // \\uXXXX 或 uXXXX
            if (t.length() >= 5 && (t.startsWith("\\u") || t.startsWith("\\U"))) {
                return hexToCp(t.substring(2));
            }
            if (t.length() == 5 && (t.charAt(0) == 'u' || t.charAt(0) == 'U')
                    && isHex(t.substring(1))) {
                return hexToCp(t.substring(1));
            }
            // 0xXXXX
            if (t.length() > 2 && (t.startsWith("0x") || t.startsWith("0X"))) {
                return hexToCp(t.substring(2));
            }
            // 纯十六进制且长度为 4/5/6，按码点解释（兼容用户直接写 4E00）
            if (t.length() >= 4 && t.length() <= 6 && isHex(t)) {
                return hexToCp(t);
            }
            // 单个字符（含代理对）
            if (t.codePointCount(0, t.length()) == 1) {
                return t.codePointAt(0);
            }
            return -1;
        }

        private int hexToCp(String hex) {
            try {
                int cp = Integer.parseInt(hex, 16);
                if (cp < 0 || cp > 0x10FFFF) {
                    return -1;
                }
                // 排除代理区，非法码点
                if (cp >= 0xD800 && cp <= 0xDFFF) {
                    return -1;
                }
                return cp;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private boolean isHex(String s) {
            if (s.isEmpty()) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                if (Character.digit(s.charAt(i), 16) < 0) {
                    return false;
                }
            }
            return true;
        }

        /** 添加额外允许的字符（按码点解析整串）。 */
        public Builder addExtraChars(String chars) {
            if (chars == null || chars.isEmpty()) {
                return this;
            }
            int i = 0;
            while (i < chars.length()) {
                int cp = chars.codePointAt(i);
                extra.add(cp);
                i += Character.charCount(cp);
            }
            return this;
        }

        /** 添加硬性禁止的字符。 */
        public Builder addForbiddenChars(String chars) {
            if (chars == null || chars.isEmpty()) {
                return this;
            }
            int i = 0;
            while (i < chars.length()) {
                int cp = chars.codePointAt(i);
                forbidden.add(cp);
                i += Character.charCount(cp);
            }
            return this;
        }

        /** 编译成不可变校验器，区间自动合并排序。 */
        public CharsetFilter build() {
            List<int[]> merged = mergeRanges(ranges);
            return new CharsetFilter(merged, extra, forbidden);
        }

        /**
         * 合并重叠或相邻的区间并排序，提升二分查找效率与正确性。
         */
        private List<int[]> mergeRanges(List<int[]> input) {
            List<int[]> sorted = new ArrayList<int[]>(input);
            sorted.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));

            List<int[]> out = new ArrayList<int[]>();
            for (int[] r : sorted) {
                if (out.isEmpty()) {
                    out.add(new int[]{r[0], r[1]});
                    continue;
                }
                int[] last = out.get(out.size() - 1);
                if (r[0] <= last[1] + 1) {
                    // 重叠或相邻，合并
                    last[1] = Math.max(last[1], r[1]);
                } else {
                    out.add(new int[]{r[0], r[1]});
                }
            }
            return out;
        }
    }
}
