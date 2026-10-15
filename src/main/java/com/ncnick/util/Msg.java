package com.ncnick.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;

/**
 * 文本与颜色工具类。
 *
 * <p>设计要点：
 * <ul>
 *   <li>不依赖任何 1.9+ API，通过反射探测十六进制颜色支持情况，保证 1.8 可运行</li>
 *   <li>提供纯色与渐变色字符串生成，供昵称颜色功能使用</li>
 *   <li>提供颜色代码剥离能力，供"全服唯一"比较使用</li>
 * </ul>
 */
public final class Msg {

    /** #RRGGBB 形式 */
    private static final Pattern HEX_PATTERN = Pattern.compile("#([0-9a-fA-F]{6})");
    /** &#RRGGBB 形式 */
    private static final Pattern AMP_HEX_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");
    /** &a-&f / &0-&9 / &k-&o / &r 形式 */
    private static final Pattern AMP_CODE_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    /** §x§R§R§G§G§B§B 形式（已转换的十六进制） */
    private static final Pattern SECTION_HEX_PATTERN =
            Pattern.compile("\u00A7x(\u00A7[0-9a-fA-F]){6}");
    /** §a 形式 */
    private static final Pattern SECTION_CODE_PATTERN = Pattern.compile("\u00A7([0-9a-fk-orA-FK-OR])");

    /** 服务端是否支持 1.16+ 十六进制颜色 */
    private static final boolean HEX_SUPPORTED;

    /** 传统 16 色的 RGB 值，用于旧版本降级时的最近色匹配 */
    private static final LegacyColor[] LEGACY_COLORS = new LegacyColor[]{
            new LegacyColor('0', 0, 0, 0),
            new LegacyColor('1', 0, 0, 170),
            new LegacyColor('2', 0, 170, 0),
            new LegacyColor('3', 0, 170, 170),
            new LegacyColor('4', 170, 0, 0),
            new LegacyColor('5', 170, 0, 170),
            new LegacyColor('6', 255, 170, 0),
            new LegacyColor('7', 170, 170, 170),
            new LegacyColor('8', 85, 85, 85),
            new LegacyColor('9', 85, 85, 255),
            new LegacyColor('a', 85, 255, 85),
            new LegacyColor('b', 85, 255, 255),
            new LegacyColor('c', 255, 85, 85),
            new LegacyColor('d', 255, 85, 255),
            new LegacyColor('e', 255, 255, 85),
            new LegacyColor('f', 255, 255, 255)
    };

    private Msg() {
        // 工具类禁止实例化
    }

    static {
        boolean supported = false;
        try {
            String version = org.bukkit.Bukkit.getBukkitVersion();
            // 形如 "1.20.4-R0.1-SNAPSHOT" 或 "1.8.8-R0.1-SNAPSHOT"
            int dash = version.indexOf('-');
            String numPart = dash > 0 ? version.substring(0, dash) : version;
            String[] bits = numPart.split("\\.");
            if (bits.length >= 2) {
                int major = Integer.parseInt(bits[0]);
                int minor = Integer.parseInt(bits[1]);
                // 1.16 及以上支持 §x§r§r§g§g§b§b
                supported = major > 1 || (major == 1 && minor >= 16);
            }
        } catch (Throwable ignored) {
            // 解析失败时保守认为不支持，走传统颜色降级路径
        }
        HEX_SUPPORTED = supported;
    }

    /** 服务端是否支持十六进制颜色。 */
    public static boolean isHexSupported() {
        return HEX_SUPPORTED;
    }

    // ==========================================================
    //  基础颜色转换
    // ==========================================================

    /**
     * 把 & 颜色代码与 #RRGGBB / &#RRGGBB 转换为 Minecraft 可识别的颜色字符串。
     *
     * @param text 原始文本，可为 null
     * @return 转换后的文本；输入为 null 时返回空串
     */
    public static String color(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 先处理 &#RRGGBB（必须在 & 传统代码之前，否则 & 会被提前消费）
        String out = replaceAmpHex(text);
        // 再处理 #RRGGBB（裸井号形式）
        out = replaceBareHex(out);
        // 最后处理 &a 等传统代码
        return ChatColor.translateAlternateColorCodes('&', out);
    }

    /** 去除所有颜色代码（& 与 § 两种形式都处理）。 */
    public static String stripColor(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = SECTION_HEX_PATTERN.matcher(text).replaceAll("");
        out = AMP_HEX_PATTERN.matcher(out).replaceAll("");
        out = HEX_PATTERN.matcher(out).replaceAll("");
        out = SECTION_CODE_PATTERN.matcher(out).replaceAll("");
        out = AMP_CODE_PATTERN.matcher(out).replaceAll("");
        return out;
    }

    /** 文本中是否含任意颜色代码。 */
    public static boolean containsColor(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return SECTION_HEX_PATTERN.matcher(text).find()
                || AMP_HEX_PATTERN.matcher(text).find()
                || HEX_PATTERN.matcher(text).find()
                || SECTION_CODE_PATTERN.matcher(text).find()
                || AMP_CODE_PATTERN.matcher(text).find();
    }

    // ==========================================================
    //  渐变与纯色
    // ==========================================================

    /**
     * 生成纯色字符串。
     *
     * @param hex   形如 "FF5555" 或 "#FF5555"
     * @param text  要着色的文本
     * @return 着色后的字符串
     */
    public static String solid(String hex, String text) {
        return hexToColor(hex) + (text == null ? "" : text);
    }

    /**
     * 生成双色渐变字符串，逐字符插值。
     *
     * <p>旧版本（&lt;1.16）不支持十六进制，会自动按最近色降级。
     *
     * @param fromHex 起始色，形如 "FF5555"
     * @param toHex   结束色，形如 "00AAFF"
     * @param text    要着色的文本
     * @return 渐变着色后的字符串
     */
    public static String gradient(String fromHex, String toHex, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int[] from = parseRgb(fromHex);
        int[] to = parseRgb(toHex);
        if (from == null || to == null) {
            return text;
        }

        // 首尾去空白后的可见字符才参与渐变，避免前后空格影响观感
        char[] chars = text.toCharArray();
        int[] visibleIdx = visibleIndexes(chars);
        int visibleCount = visibleIdx.length;
        if (visibleCount == 0) {
            return new String(chars);
        }
        // 预建布尔表，把可见性判断从 O(n) 降为 O(1)
        boolean[] visible = new boolean[chars.length];
        for (int idx : visibleIdx) {
            visible[idx] = true;
        }

        StringBuilder sb = new StringBuilder(chars.length * 3);
        int step = 0;
        for (int i = 0; i < chars.length; i++) {
            if (!visible[i]) {
                sb.append(chars[i]);
                continue;
            }
            double ratio = visibleCount == 1 ? 0.0D : (double) step / (double) (visibleCount - 1);
            int r = (int) Math.round(from[0] + (to[0] - from[0]) * ratio);
            int g = (int) Math.round(from[1] + (to[1] - from[1]) * ratio);
            int b = (int) Math.round(from[2] + (to[2] - from[2]) * ratio);
            sb.append(hexToColor(String.format("%02X%02X%02X", r, g, b))).append(chars[i]);
            step++;
        }
        return sb.toString();
    }

    /**
     * 生成多段渐变（用于未来扩展，例如三色渐变）。
     *
     * @param hexes 颜色数组，至少两个元素
     * @param text  要着色的文本
     */
    public static String multiGradient(String[] hexes, String text) {
        if (hexes == null || hexes.length < 2 || text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        char[] chars = text.toCharArray();
        int[] visibleIdx = visibleIndexes(chars);
        int visibleCount = visibleIdx.length;
        if (visibleCount == 0) {
            return new String(chars);
        }

        int segments = hexes.length - 1;
        boolean[] visible = new boolean[chars.length];
        for (int idx : visibleIdx) {
            visible[idx] = true;
        }

        StringBuilder sb = new StringBuilder(chars.length * 3);
        int step = 0;
        for (int i = 0; i < chars.length; i++) {
            if (!visible[i]) {
                sb.append(chars[i]);
                continue;
            }
            double global = visibleCount == 1 ? 0.0D : (double) step / (double) (visibleCount - 1);
            double scaled = global * segments;
            int seg = Math.min((int) scaled, segments - 1);
            double local = scaled - seg;

            int[] a = parseRgb(hexes[seg]);
            int[] b = parseRgb(hexes[seg + 1]);
            if (a == null || b == null) {
                sb.append(chars[i]);
                step++;
                continue;
            }
            int r = (int) Math.round(a[0] + (b[0] - a[0]) * local);
            int g = (int) Math.round(a[1] + (b[1] - a[1]) * local);
            int bl = (int) Math.round(a[2] + (b[2] - a[2]) * local);
            sb.append(hexToColor(String.format("%02X%02X%02X", r, g, bl))).append(chars[i]);
            step++;
        }
        return sb.toString();
    }

    /**
     * 把十六进制颜色转成 Minecraft 颜色前缀。
     * 1.16+ 返回 §x§r§r§g§g§b§b，旧版本返回最近的 §a 传统色。
     *
     * @param hex 形如 "FF5555" 或 "#FF5555"，也接受空值
     */
    public static String hexToColor(String hex) {
        int[] rgb = parseRgb(hex);
        if (rgb == null) {
            return "";
        }
        if (!HEX_SUPPORTED) {
            return "\u00A7" + nearestLegacy(rgb[0], rgb[1], rgb[2]);
        }
        String s = String.format("%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
        StringBuilder sb = new StringBuilder("\u00A7x");
        for (int i = 0; i < s.length(); i++) {
            sb.append('\u00A7').append(s.charAt(i));
        }
        return sb.toString();
    }

    /**
     * 解析十六进制颜色为 RGB 三元组。
     *
     * @param hex 支持 "RRGGBB"、"#RRGGBB"、"&#RRGGBB"
     * @return 长度为 3 的数组，解析失败返回 null
     */
    public static int[] parseRgb(String hex) {
        if (hex == null) {
            return null;
        }
        String s = hex.trim();
        if (s.startsWith("&#")) {
            s = s.substring(2);
        } else if (s.startsWith("#")) {
            s = s.substring(1);
        }
        // 允许 "0x" 前缀
        if (s.length() > 2 && (s.startsWith("0x") || s.startsWith("0X"))) {
            s = s.substring(2);
        }
        if (s.length() != 6) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(s.substring(0, 2), 16),
                    Integer.parseInt(s.substring(2, 4), 16),
                    Integer.parseInt(s.substring(4, 6), 16)
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 把 RGB 转成 RRGGBB 大写字符串。 */
    public static String toHex(int r, int g, int b) {
        return String.format("%02X%02X%02X", clamp(r), clamp(g), clamp(b));
    }

    /** 计算出与给定 RGB 最接近的传统颜色代码字符。 */
    public static char nearestLegacy(int r, int g, int b) {
        char best = 'f';
        int bestDist = Integer.MAX_VALUE;
        for (LegacyColor lc : LEGACY_COLORS) {
            int dr = r - lc.r;
            int dg = g - lc.g;
            int db = b - lc.b;
            // 加权欧氏距离，更贴近人眼感知
            int dist = dr * dr * 30 + dg * dg * 59 + db * db * 11;
            if (dist < bestDist) {
                bestDist = dist;
                best = lc.code;
            }
        }
        return best;
    }

    // ==========================================================
    //  占位符替换
    // ==========================================================

    /**
     * 用给定键值对替换模板中的 {key} 占位符。
     *
     * <p><b>单次扫描实现</b>：从左到右扫描模板，遇到占位符就替换，
     * 且<b>不再回头扫描已替换进去的内容</b>。这样有两个好处：
     * <ol>
     *   <li>结果与 Map 的迭代顺序无关（HashMap 顺序不稳定）</li>
     *   <li>替换值中若含 {@code {xxx}} 字面量，不会被二次展开，
     *       避免玩家昵称里写 {@code {prefix}} 造成意外注入</li>
     * </ol>
     *
     * @param template 模板，可为 null
     * @param values   变量表；<b>值为 null 时按空串处理</b>
     *                 （避免玩家看到字面量 {@code {price}} 之类的占位符）
     * @return 替换结果；模板为 null 时返回空串
     */
    public static String fill(String template, Map<String, String> values) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (values == null || values.isEmpty()) {
            return template;
        }

        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        int len = template.length();
        while (i < len) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }

            // 找到匹配的右花括号
            int close = template.indexOf('}', i + 1);
            if (close < 0) {
                // 没有闭合，剩余部分原样输出
                out.append(template, i, len);
                break;
            }

            String key = template.substring(i + 1, close);
            if (values.containsKey(key)) {
                // 已知占位符：null 按空串处理；值不再被扫描（防二次展开）
                String value = values.get(key);
                if (value != null) {
                    out.append(value);
                }
            } else {
                // 未知占位符：原样保留，便于排查配置
                out.append(template, i, close + 1);
            }
            i = close + 1;
        }
        return out.toString();
    }

    /**
     * 截断字符串到指定长度，避免超过 Minecraft 的 16/32 字符显示限制。
     * 注意：颜色代码会被完整保留，不会被截成半个。
     *
     * @param text 原文本
     * @param max  最大可见字符数
     */
    public static String truncateVisible(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        int visible = 0;
        int i = 0;
        while (i < text.length()) {
            // 已达到可见字符上限：立即停止。
            // 注意：上限判断必须放在 § 分支**之前**——否则到达上限后
            // 仍会继续吞掉后续颜色码，导致结果原始长度超出预期
            // （这正是 1.8-1.12 上 setSuffix 抛 IAE 的放大器）。
            if (visible >= max) {
                break;
            }
            char c = text.charAt(i);
            // 跳过 § 颜色序列（§x§r§g§b 等），保证不截出半个序列
            if (c == '\u00A7' && i + 1 < text.length()) {
                sb.append(c).append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            sb.append(c);
            visible++;
            i++;
        }
        return sb.toString();
    }

    /** 计算不含颜色代码的可见长度。 */
    public static int visibleLength(String text) {
        return stripColor(text).length();
    }

    // ==========================================================
    //  内部工具
    // ==========================================================

    private static String replaceAmpHex(String text) {
        Matcher m = AMP_HEX_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(hexToColor(m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceBareHex(String text) {
        Matcher m = HEX_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(hexToColor(m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int[] visibleIndexes(char[] chars) {
        List<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < chars.length; i++) {
            if (!Character.isWhitespace(chars[i])) {
                list.add(i);
            }
        }
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static boolean isVisibleAt(int[] visibleIdx, int index) {
        for (int v : visibleIdx) {
            if (v == index) {
                return true;
            }
        }
        return false;
    }

    /** 传统 16 色定义。 */
    private static final class LegacyColor {
        final char code;
        final int r;
        final int g;
        final int b;

        LegacyColor(char code, int r, int g, int b) {
            this.code = code;
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }
}
