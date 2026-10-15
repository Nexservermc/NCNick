package com.ncnick.model;

import java.util.UUID;

/**
 * 玩家昵称数据模型。
 *
 * <p>不可变对象，线程安全。昵称中可能包含颜色代码。
 */
public final class NickData {

    private final UUID uuid;
    private final String name;
    private final String nickname;
    /** 颜色定义，可能为 null（表示使用默认颜色） */
    private final ColorPreset color;
    private final NickMode mode;
    private final long updatedAt;

    public NickData(UUID uuid, String name, String nickname, NickMode mode, long updatedAt) {
        this(uuid, name, nickname, null, mode, updatedAt);
    }

    public NickData(UUID uuid, String name, String nickname, ColorPreset color,
                    NickMode mode, long updatedAt) {
        this.uuid = uuid;
        this.name = name == null ? "" : name;
        this.nickname = nickname == null ? "" : nickname;
        this.color = color;
        this.mode = mode == null ? NickMode.PURCHASE : mode;
        this.updatedAt = updatedAt;
    }

    public UUID getUuid() {
        return uuid;
    }

    /** 设置昵称时的真实 ID（可能已过期，仅作记录）。 */
    public String getName() {
        return name;
    }

    /** 纯文本昵称，不含颜色代码。 */
    public String getNickname() {
        return nickname;
    }

    /** 颜色定义，null 表示无自定义颜色。 */
    public ColorPreset getColor() {
        return color;
    }

    public NickMode getMode() {
        return mode;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /** 昵称是否有效（非空）。 */
    public boolean hasNickname() {
        return !nickname.isEmpty();
    }

    /** 是否设置了自定义颜色。 */
    public boolean hasColor() {
        return color != null && color.isValid();
    }

    /**
     * 生成最终用于显示的昵称（已应用颜色）。
     *
     * @return 带颜色的昵称字符串；无颜色时返回纯昵称
     */
    public String getColoredNickname() {
        if (nickname.isEmpty()) {
            return "";
        }
        if (color == null || !color.isValid()) {
            return nickname;
        }
        return color.apply(nickname);
    }

    /** 返回一个替换了颜色与更新时间的副本。 */
    public NickData withColor(ColorPreset newColor, long now) {
        return new NickData(uuid, name, nickname, newColor, mode, now);
    }

    /** 返回一个替换了昵称的副本（保留颜色）。 */
    public NickData withNickname(String newNickname, long now) {
        return new NickData(uuid, name, newNickname, color, mode, now);
    }

    /** 返回一个替换了模式与更新时间的副本。 */
    public NickData withMode(NickMode newMode, long now) {
        return new NickData(uuid, name, nickname, color, newMode, now);
    }

    @Override
    public String toString() {
        return "NickData{uuid=" + uuid + ", name='" + name + "', nickname='" + nickname
                + "', color=" + color + ", mode=" + mode + "}";
    }
}
