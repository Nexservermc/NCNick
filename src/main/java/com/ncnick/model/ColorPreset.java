package com.ncnick.model;

import com.ncnick.util.Msg;

/**
 * 昵称颜色定义（第 6 条）。
 *
 * <p>支持两种类型：
 * <ul>
 *   <li>{@link Type#SOLID} —— 纯色，单一颜色</li>
 *   <li>{@link Type#GRADIENT} —— 渐变，从起始色过渡到结束色</li>
 * </ul>
 *
 * <p>不可变对象。序列化格式：
 * <pre>
 *   纯色   →  solid:FF5555
 *   渐变   →  gradient:FF5555-00AAFF
 *   无颜色 →  null 或空串
 * </pre>
 */
public final class ColorPreset {

    /** 颜色类型。 */
    public enum Type {
        SOLID("solid"),
        GRADIENT("gradient");

        private final String id;

        Type(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public static Type parse(String raw) {
            if (raw != null) {
                for (Type t : values()) {
                    if (t.id.equalsIgnoreCase(raw)) {
                        return t;
                    }
                }
            }
            return SOLID;
        }
    }

    private static final ColorPreset NONE = new ColorPreset(Type.SOLID, null, null);

    private final Type type;
    /** 起始色（纯色时即为唯一颜色），RRGGBB 大写，无颜色时为 null */
    private final String from;
    /** 结束色，仅渐变时有值 */
    private final String to;

    private ColorPreset(Type type, String from, String to) {
        this.type = type;
        this.from = from;
        this.to = to;
    }

    /** 表示"无颜色"的实例。 */
    public static ColorPreset none() {
        return NONE;
    }

    /**
     * 创建纯色。
     *
     * @param hex RRGGBB 或 #RRGGBB
     * @return 纯色实例；颜色非法时返回 none()
     */
    public static ColorPreset solid(String hex) {
        int[] rgb = Msg.parseRgb(hex);
        if (rgb == null) {
            return NONE;
        }
        return new ColorPreset(Type.SOLID, Msg.toHex(rgb[0], rgb[1], rgb[2]), null);
    }

    /**
     * 创建纯色（RGB 分量）。
     */
    public static ColorPreset solid(int r, int g, int b) {
        return new ColorPreset(Type.SOLID, Msg.toHex(r, g, b), null);
    }

    /**
     * 创建渐变。
     *
     * @param fromHex 起始色
     * @param toHex   结束色
     * @return 渐变实例；颜色非法时返回 none()
     */
    public static ColorPreset gradient(String fromHex, String toHex) {
        int[] a = Msg.parseRgb(fromHex);
        int[] b = Msg.parseRgb(toHex);
        if (a == null || b == null) {
            return NONE;
        }
        return new ColorPreset(Type.GRADIENT, Msg.toHex(a[0], a[1], a[2]), Msg.toHex(b[0], b[1], b[2]));
    }

    /**
     * 创建渐变（RGB 分量）。
     */
    public static ColorPreset gradient(int r1, int g1, int b1, int r2, int g2, int b2) {
        return new ColorPreset(Type.GRADIENT, Msg.toHex(r1, g1, b1), Msg.toHex(r2, g2, b2));
    }

    public Type getType() {
        return type;
    }

    /** 起始色，可能为 null。 */
    public String getFrom() {
        return from;
    }

    /** 结束色，仅渐变有效，可能为 null。 */
    public String getTo() {
        return to;
    }

    /** 是否为纯色。 */
    public boolean isSolid() {
        return type == Type.SOLID;
    }

    /** 是否为渐变。 */
    public boolean isGradient() {
        return type == Type.GRADIENT;
    }

    /** 是否为有效的颜色（有实际颜色值）。 */
    public boolean isValid() {
        return from != null;
    }

    /**
     * 把颜色应用到文本上。
     *
     * @param text 纯文本
     * @return 着色后的文本；无颜色时原样返回
     */
    public String apply(String text) {
        if (text == null || text.isEmpty() || !isValid()) {
            return text == null ? "" : text;
        }
        if (type == Type.SOLID) {
            return Msg.solid(from, text);
        }
        return Msg.gradient(from, to == null ? from : to, text);
    }

    /**
     * 生成用于预览的颜色示例（如 ███ 色块）。
     *
     * @param text 预览文字
     */
    public String preview(String text) {
        return apply(text);
    }

    /**
     * 序列化为存储字符串。
     *
     * @return "solid:RRGGBB" 或 "gradient:RRGGBB-RRGGBB"；无颜色时返回 null
     */
    public String serialize() {
        if (!isValid()) {
            return null;
        }
        if (type == Type.SOLID) {
            return "solid:" + from;
        }
        return "gradient:" + from + "-" + (to == null ? from : to);
    }

    /**
     * 从存储字符串反序列化。
     *
     * <p>为兼容旧数据，若字符串不含前缀冒号，按纯色处理。
     *
     * @param raw 存储字符串，可为 null
     * @return 颜色实例；无法解析时返回 none()
     */
    public static ColorPreset deserialize(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return NONE;
        }
        String s = raw.trim();

        // 兼容不带前缀的裸十六进制
        if (!s.contains(":")) {
            return solid(s);
        }

        int idx = s.indexOf(':');
        String typePart = s.substring(0, idx);
        String valuePart = s.substring(idx + 1);
        Type t = Type.parse(typePart);

        if (t == Type.GRADIENT) {
            int dash = valuePart.indexOf('-');
            if (dash > 0) {
                String a = valuePart.substring(0, dash);
                String b = valuePart.substring(dash + 1);
                ColorPreset g = gradient(a, b);
                if (g.isValid()) {
                    return g;
                }
            }
            // 渐变格式损坏时降级为纯色
            return solid(valuePart);
        }
        return solid(valuePart);
    }

    /**
     * 生成人类可读的颜色描述，用于 GUI 与消息。
     */
    public String describe() {
        if (!isValid()) {
            return "默认";
        }
        if (type == Type.SOLID) {
            return "#" + from;
        }
        return "#" + from + " → #" + (to == null ? from : to);
    }

    /**
     * 生成带颜色预览的描述文本。
     */
    public String describeColored() {
        if (!isValid()) {
            return "默认";
        }
        return apply(describe());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ColorPreset)) {
            return false;
        }
        ColorPreset other = (ColorPreset) o;
        if (type != other.type) {
            return false;
        }
        if (from == null ? other.from != null : !from.equals(other.from)) {
            return false;
        }
        return to == null ? other.to == null : to.equals(other.to);
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + (from == null ? 0 : from.hashCode());
        result = 31 * result + (to == null ? 0 : to.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "ColorPreset{" + serialize() + "}";
    }
}
