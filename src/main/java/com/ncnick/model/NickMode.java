package com.ncnick.model;

/**
 * 昵称来源模式。
 */
public enum NickMode {

    /** 通过权限免费设置 */
    PERMISSION("permission", "权限"),
    /** 通过金币/点券购买 */
    PURCHASE("purchase", "购买"),
    /** 已被移除（用于跨服同步广播删除事件） */
    REMOVED("removed", "已移除");

    private final String id;
    private final String display;

    NickMode(String id, String display) {
        this.id = id;
        this.display = display;
    }

    /** 存储用的标识符。 */
    public String getId() {
        return id;
    }

    /** 中文显示名。 */
    public String getDisplay() {
        return display;
    }

    /**
     * 解析存储中的模式字符串，无法识别时回退为 PURCHASE。
     *
     * @param raw 原始字符串，可为 null
     */
    public static NickMode parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return PURCHASE;
        }
        String s = raw.trim();
        for (NickMode m : values()) {
            if (m.id.equalsIgnoreCase(s) || m.name().equalsIgnoreCase(s)) {
                return m;
            }
        }
        return PURCHASE;
    }
}
