package com.ncnick.model;

/**
 * 商品类型。
 *
 * <p>用于让**支付方式与价格按商品独立配置**：
 * 昵称可以只收金币，而颜色同时支持金币和点券。
 *
 * <p>对应 {@code economy.yml} 中的两段配置：
 * <pre>
 * purchase:
 *   nickname:
 *     currency: coins          ← 昵称只收金币
 *     price-coins: 10000
 *   color:
 *     currency: both           ← 颜色两种都收
 *     solid:
 *       price-coins: 5000
 *       price-points: 500
 * </pre>
 */
public enum ProductType {

    /** 设置 / 修改昵称 */
    NICKNAME("nickname", "昵称"),

    /** 设置昵称颜色（纯色与渐变共用一段 currency 配置） */
    COLOR("color", "颜色");

    private final String configKey;
    private final String display;

    ProductType(String configKey, String display) {
        this.configKey = configKey;
        this.display = display;
    }

    /** 在 economy.yml 中的配置键名。 */
    public String getConfigKey() {
        return configKey;
    }

    /** 中文显示名（用于消息）。 */
    public String getDisplay() {
        return display;
    }
}
