package com.ncnick.model;

/**
 * 支付方式。
 */
public enum PayType {

    /** 金币（Vault / XConomy） */
    COINS("coins", "金币"),
    /** 点券（PlayerPoints） */
    POINTS("points", "点券"),
    /** 免费（权限） */
    FREE("free", "免费");

    private final String id;
    private final String display;

    PayType(String id, String display) {
        this.id = id;
        this.display = display;
    }

    public String getId() {
        return id;
    }

    public String getDisplay() {
        return display;
    }

    public static PayType parse(String raw) {
        if (raw != null) {
            for (PayType t : values()) {
                if (t.id.equalsIgnoreCase(raw) || t.name().equalsIgnoreCase(raw)) {
                    return t;
                }
            }
        }
        return COINS;
    }
}
