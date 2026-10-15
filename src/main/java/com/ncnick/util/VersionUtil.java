package com.ncnick.util;

import org.bukkit.Bukkit;

/**
 * 服务端版本探测与自适应工具。
 *
 * <p>单 jar 需要兼容 1.8 - 1.21.11，不同版本可用的 API 差异很大。
 * 这里集中管理版本判断与能力探测，避免散落在各处。
 */
public final class VersionUtil {

    /** 服务端主版本号，例如 1.8.8 为 8，1.20.4 为 20 */
    private static final int MINOR;
    /** 服务端次版本号，例如 1.20.4 为 4，没有则为 0 */
    private static final int PATCH;
    /** 原始版本字符串 */
    private static final String RAW;
    /** 是否为 Paper 或其衍生核心 */
    private static final boolean PAPER;
    /** 是否为 1.13+（扁平化 Material 命名） */
    private static final boolean FLATTENED;
    /** 是否为 1.16+（十六进制颜色） */
    private static final boolean HEX_COLOR;
    /** 是否为 1.19+（AsyncChatEvent 体系） */
    private static final boolean MODERN_CHAT;

    private VersionUtil() {
        // 工具类禁止实例化
    }

    static {
        String raw = "0.0.0";
        int minor = 0;
        int patch = 0;
        try {
            String v = Bukkit.getBukkitVersion();
            raw = v;
            int dash = v.indexOf('-');
            String numPart = dash > 0 ? v.substring(0, dash) : v;
            String[] bits = numPart.split("\\.");
            if (bits.length >= 2) {
                int major = Integer.parseInt(bits[0]);
                int sec = Integer.parseInt(bits[1]);
                // 兼容未来可能出现的 2.x 版本号格式
                if (major > 1) {
                    minor = major * 10 + sec;
                } else {
                    minor = sec;
                }
            }
            if (bits.length >= 3) {
                patch = parseIntSafe(bits[2]);
            }
        } catch (Throwable ignored) {
            // 解析失败保持默认值
        }
        MINOR = minor;
        PATCH = patch;
        RAW = raw;
        FLATTENED = MINOR >= 13;
        HEX_COLOR = MINOR >= 16;
        MODERN_CHAT = MINOR >= 19;
        PAPER = detectPaper();
    }

    private static boolean detectPaper() {
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            return true;
        } catch (Throwable ignored) {
            // 继续探测
        }
        try {
            Class.forName("io.papermc.paper.configuration.Configuration");
            return true;
        } catch (Throwable ignored) {
            // 继续探测
        }
        try {
            Bukkit.class.getMethod("getServer", (Class<?>[]) null);
            // Paper 才有静态 getServer()
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int parseIntSafe(String s) {
        try {
            // 处理 "4-R0" 这类带后缀的情况
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) {
                    sb.append(c);
                } else {
                    break;
                }
            }
            return sb.length() == 0 ? 0 : Integer.parseInt(sb.toString());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 原始版本字符串，例如 "1.20.4-R0.1-SNAPSHOT"。 */
    public static String raw() {
        return RAW;
    }

    /** 次版本号，例如 1.20.4 返回 20。 */
    public static int minor() {
        return MINOR;
    }

    /** 补丁版本号，例如 1.20.4 返回 4。 */
    public static int patch() {
        return PATCH;
    }

    /** 是否至少为指定次版本，例如 atLeast(13) 判断是否 1.13+。 */
    public static boolean atLeast(int minor) {
        return MINOR >= minor;
    }

    /** 是否低于指定次版本。 */
    public static boolean below(int minor) {
        return MINOR < minor;
    }

    /** 是否在 [from, to] 区间内（含端点）。 */
    public static boolean between(int from, int to) {
        return MINOR >= from && MINOR <= to;
    }

    /** 是否为 1.13+，Material 命名已扁平化。 */
    public static boolean isFlattened() {
        return FLATTENED;
    }

    /** 是否为 1.16+，支持十六进制颜色。 */
    public static boolean supportsHexColor() {
        return HEX_COLOR;
    }

    /** 是否为 1.19+，聊天事件体系变更。 */
    public static boolean isModernChat() {
        return MODERN_CHAT;
    }

    /** 是否为 Paper 或其衍生核心。 */
    public static boolean isPaper() {
        return PAPER;
    }

    /**
     * 安全地按名称获取 Material，兼容不同版本的材料改名。
     * 例如 1.8 是 GOLD_INGOT，1.13 之后仍是 GOLD_INGOT，但 SIGN 变成了 OAK_SIGN。
     *
     * @param names 候选名称，按优先级排列
     * @return 第一个存在的 Material，全部不存在时返回 null
     */
    public static Object material(String... names) {
        if (names == null) {
            return null;
        }
        for (String name : names) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            try {
                return Enum.valueOf(org.bukkit.Material.class, name);
            } catch (Throwable ignored) {
                // 尝试下一个
            }
        }
        return null;
    }

    /**
     * 判断类是否存在，用于可选依赖探测。
     *
     * @param className 全限定类名
     */
    public static boolean hasClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 判断插件是否已安装并启用。
     *
     * @param name 插件名
     */
    public static boolean hasPlugin(String name) {
        try {
            return Bukkit.getPluginManager().getPlugin(name) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 生成用于日志输出的版本描述。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(Bukkit.getVersion());
        sb.append(" (").append(PAPER ? "Paper" : "Spigot").append(")");
        return sb.toString();
    }
}
