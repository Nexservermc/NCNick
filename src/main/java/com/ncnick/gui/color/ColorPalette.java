package com.ncnick.gui.color;

import java.util.ArrayList;
import java.util.List;

/**
 * 颜色调色板（第 6 条：所有染料对应可用颜色）。
 *
 * <p>提供 Minecraft 全部 16 种染料颜色，并给出每种颜色在
 * 不同版本下对应的染料物品材质名。
 */
public final class ColorPalette {

    private ColorPalette() {
        // 工具类禁止实例化
    }

    /**
     * 单个颜色定义。
     */
    public static final class Color {
        /** 颜色 ID（用于持久化与识别） */
        private final String id;
        /** 十六进制值 RRGGBB */
        private final String hex;
        /** 中文显示名 */
        private final String displayName;
        /** 1.13+ 染料材质名 */
        private final String modernDye;
        /** 1.8-1.12 染料材质名（都是 INK_SACK + 数据值，这里用羊毛代替展示） */
        private final String legacyWool;

        Color(String id, String hex, String displayName, String modernDye, String legacyWool) {
            this.id = id;
            this.hex = hex;
            this.displayName = displayName;
            this.modernDye = modernDye;
            this.legacyWool = legacyWool;
        }

        public String getId() {
            return id;
        }

        public String getHex() {
            return hex;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getModernDye() {
            return modernDye;
        }

        public String getLegacyWool() {
            return legacyWool;
        }

        @Override
        public String toString() {
            return id + "(" + hex + ")";
        }
    }

    /** 全部 16 种染料颜色，顺序按游戏内排列 */
    private static final List<Color> COLORS = new ArrayList<Color>();

    static {
        // 羊毛在 1.13 前后都有，用羊毛作为颜色方块展示最稳妥
        COLORS.add(new Color("white",        "FFFFFF", "白色",   "WHITE_DYE",       "WOOL"));
        COLORS.add(new Color("light_gray",   "AAAAAA", "淡灰色", "LIGHT_GRAY_DYE",  "WOOL"));
        COLORS.add(new Color("gray",         "555555", "灰色",   "GRAY_DYE",        "WOOL"));
        COLORS.add(new Color("black",        "000000", "黑色",   "BLACK_DYE",       "WOOL"));
        COLORS.add(new Color("brown",        "8B5A2B", "棕色",   "BROWN_DYE",       "WOOL"));
        COLORS.add(new Color("red",          "FF5555", "红色",   "RED_DYE",         "WOOL"));
        COLORS.add(new Color("orange",       "FFAA00", "橙色",   "ORANGE_DYE",      "WOOL"));
        COLORS.add(new Color("yellow",       "FFFF55", "黄色",   "YELLOW_DYE",      "WOOL"));
        COLORS.add(new Color("lime",         "55FF55", "黄绿色", "LIME_DYE",        "WOOL"));
        COLORS.add(new Color("green",        "00AA00", "绿色",   "GREEN_DYE",       "WOOL"));
        COLORS.add(new Color("cyan",         "55FFFF", "青色",   "CYAN_DYE",        "WOOL"));
        COLORS.add(new Color("light_blue",   "55AAFF", "淡蓝色", "LIGHT_BLUE_DYE",  "WOOL"));
        COLORS.add(new Color("blue",         "5555FF", "蓝色",   "BLUE_DYE",        "WOOL"));
        COLORS.add(new Color("purple",       "AA00AA", "紫色",   "PURPLE_DYE",      "WOOL"));
        COLORS.add(new Color("magenta",      "FF55FF", "品红色", "MAGENTA_DYE",     "WOOL"));
        COLORS.add(new Color("pink",         "FFAACC", "粉红色", "PINK_DYE",        "WOOL"));
    }

    /** 获取全部颜色。 */
    public static List<Color> all() {
        return new ArrayList<Color>(COLORS);
    }

    /** 颜色总数。 */
    public static int count() {
        return COLORS.size();
    }

    /**
     * 按 ID 查找颜色。
     *
     * @param id 颜色 ID，如 "red"
     * @return 颜色；找不到返回 null
     */
    public static Color byId(String id) {
        if (id == null) {
            return null;
        }
        for (Color c : COLORS) {
            if (c.getId().equalsIgnoreCase(id)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 按十六进制值查找最接近的颜色。
     */
    public static Color byHex(String hex) {
        if (hex == null) {
            return null;
        }
        String clean = hex.startsWith("#") ? hex.substring(1) : hex;
        for (Color c : COLORS) {
            if (c.getHex().equalsIgnoreCase(clean)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 获取指定索引的颜色（用于分页）。
     *
     * @param index 索引
     * @return 颜色；越界返回 null
     */
    public static Color at(int index) {
        if (index < 0 || index >= COLORS.size()) {
            return null;
        }
        return COLORS.get(index);
    }

    /**
     * 生成一个用于展示的颜色方块材质名（1.13+）。
     *
     * <p>使用带颜色前缀的羊毛，视觉上更接近实际颜色。
     */
    public static String modernBlockName(Color color) {
        if (color == null) {
            return "WHITE_WOOL";
        }
        return color.getId().toUpperCase() + "_WOOL";
    }

    /**
     * 生成 1.8 版本可用的羊毛材质与数据值。
     *
     * @return 长度为 2 的数组 {材质名, 数据值字符串}；无效时返回 null
     */
    public static String[] legacyWool(Color color) {
        if (color == null) {
            return null;
        }
        int damage = legacyDamage(color.getId());
        if (damage < 0) {
            return null;
        }
        return new String[]{"WOOL", String.valueOf(damage)};
    }

    /**
     * 1.8 羊毛数据值映射。
     */
    private static int legacyDamage(String id) {
        if ("white".equals(id)) return 0;
        if ("orange".equals(id)) return 1;
        if ("magenta".equals(id)) return 2;
        if ("light_blue".equals(id)) return 3;
        if ("yellow".equals(id)) return 4;
        if ("lime".equals(id)) return 5;
        if ("pink".equals(id)) return 6;
        if ("gray".equals(id)) return 7;
        if ("light_gray".equals(id)) return 8;
        if ("cyan".equals(id)) return 9;
        if ("purple".equals(id)) return 10;
        if ("blue".equals(id)) return 11;
        if ("brown".equals(id)) return 12;
        if ("green".equals(id)) return 13;
        if ("red".equals(id)) return 14;
        if ("black".equals(id)) return 15;
        return -1;
    }

    /**
     * 生成预览用的色块文本（用 Unicode 方块字符）。
     *
     * @param color 颜色
     * @param width 色块数量
     */
    public static String swatch(Color color, int width) {
        if (color == null || width <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(com.ncnick.util.Msg.hexToColor(color.getHex()));
        for (int i = 0; i < width; i++) {
            sb.append('\u2588');
        }
        return sb.toString();
    }

    /**
     * 生成渐变预览文本。
     *
     * @param from  起始色
     * @param to    结束色
     * @param width 色块数量
     */
    public static String gradientSwatch(Color from, Color to, int width) {
        if (from == null || to == null || width <= 0) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < width; i++) {
            text.append('\u2588');
        }
        return com.ncnick.util.Msg.gradient(from.getHex(), to.getHex(), text.toString());
    }
}
