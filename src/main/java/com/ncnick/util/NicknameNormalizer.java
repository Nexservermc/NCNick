package com.ncnick.util;

/**
 * 昵称归一化工具（第 10 条：昵称全服唯一，不可重复）。
 *
 * <p>唯一性比较不能直接用原始字符串，因为：
 * <ul>
 *   <li>昵称中可能含颜色代码，"§a小明" 与 "§b小明" 应视为同一个昵称</li>
 *   <li>大小写差异（拉丁字母）通常应视为重复</li>
 *   <li>全角/半角、不可见字符会造成"看起来一样但不相等"的问题</li>
 * </ul>
 *
 * <p>归一化后的字符串存入数据库的 {@code normalized} 列并建唯一索引，
 * 由数据库层面兜底保证唯一性。
 */
public final class NicknameNormalizer {

    private NicknameNormalizer() {
        // 工具类禁止实例化
    }

    /**
     * 归一化昵称，用于唯一性比较。
     *
     * @param nickname         原始昵称（可含颜色代码）
     * @param stripColors      是否剥离颜色代码
     * @param lowerCase        是否转小写
     * @param stripSpaces      是否移除空白字符
     * @return 归一化结果；输入为空时返回空串
     */
    public static String normalize(String nickname, boolean stripColors,
                                   boolean lowerCase, boolean stripSpaces) {
        if (nickname == null || nickname.isEmpty()) {
            return "";
        }

        String s = nickname;

        if (stripColors) {
            s = Msg.stripColor(s);
        }

        // ---- Unicode 规范化（防同形昵称绕过唯一性） ----
        // NFKC 折叠兼容字符（全角、连字、上标等），NFC 折叠组合字符序列，
        // 使 "é"(U+00E9) 与 "e"+U+0301 这类视觉相同但码点不同的写法归一。
        try {
            s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);
            s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC);
        } catch (Throwable ignored) {
            // 规范化失败时继续用原串，不影响主流程
        }

        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cc = Character.charCount(cp);
            i += cc;

            // 跳过所有格式类不可见字符（零宽空格、双向控制符等）
            if (isInvisible(cp)) {
                continue;
            }

            // 跳过组合附加符号（Mn/Mc/Me），防止用组合字符伪装成别的昵称
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                continue;
            }

            // 统一空白处理
            if (Character.isWhitespace(cp) || cp == 0x3000) {
                if (stripSpaces) {
                    continue;
                }
                // 不剥离时统一成普通空格，避免制表符与空格不等
                sb.append(' ');
                continue;
            }

            // 全角转半角（ASCII 可见范围）
            int normalized = toHalfWidth(cp);

            if (lowerCase) {
                normalized = Character.toLowerCase(normalized);
            }

            sb.appendCodePoint(normalized);
        }

        String out = sb.toString();
        // 去掉首尾多余空格
        if (!stripSpaces) {
            out = trimSpaces(out);
        }
        return out;
    }

    /**
     * 使用默认策略归一化：剥离颜色 + 忽略大小写。
     */
    public static String normalize(String nickname) {
        return normalize(nickname, true, true, false);
    }

    /**
     * 判断两个昵称是否视为重复。
     */
    public static boolean isDuplicate(String a, String b, boolean stripColors,
                                      boolean ignoreCase, boolean ignoreSpaces) {
        return normalize(a, stripColors, ignoreCase, ignoreSpaces)
                .equals(normalize(b, stripColors, ignoreCase, ignoreSpaces));
    }

    /**
     * 生成用于展示的"安全昵称"：保留颜色，但去除不可见控制字符。
     * 防止玩家用零宽字符伪装成别人的昵称。
     */
    public static String sanitizeForDisplay(String nickname) {
        if (nickname == null || nickname.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(nickname.length());
        int i = 0;
        while (i < nickname.length()) {
            char c = nickname.charAt(i);
            // 保留颜色代码
            if (c == '\u00A7' && i + 1 < nickname.length()) {
                sb.append(c).append(nickname.charAt(i + 1));
                i += 2;
                continue;
            }
            int cp = nickname.codePointAt(i);
            int cc = Character.charCount(cp);
            i += cc;
            if (isInvisible(cp)) {
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /**
     * 是否为不可见/易混淆字符。
     *
     * <p>包括：零宽字符、双向控制符、组合附加符号之外的变体选择符。
     */
    private static boolean isInvisible(int cp) {
        // 零宽空格、零宽非连接符、零宽连接符、字节序标记
        if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) {
            return true;
        }
        // 双向控制符
        if (cp >= 0x202A && cp <= 0x202E) {
            return true;
        }
        if (cp >= 0x2066 && cp <= 0x2069) {
            return true;
        }
        // 软连字符
        if (cp == 0x00AD) {
            return true;
        }
        // 其他格式类控制字符
        int type = Character.getType(cp);
        return type == Character.FORMAT;
    }

    /**
     * 全角 ASCII 转半角（U+FF01 - U+FF5E → U+0021 - U+007E）。
     */
    private static int toHalfWidth(int cp) {
        if (cp >= 0xFF01 && cp <= 0xFF5E) {
            return cp - 0xFEE0;
        }
        // 全角空格
        if (cp == 0x3000) {
            return ' ';
        }
        return cp;
    }

    private static String trimSpaces(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == ' ') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(start, end);
    }
}
