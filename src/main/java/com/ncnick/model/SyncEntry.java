package com.ncnick.model;

import java.util.UUID;

/**
 * 跨服同步记录。
 *
 * <p>MySQL 模式下，每次昵称变更都往 sync 表追加一条记录，
 * 其他服务器轮询该表即可获知变更。
 */
public final class SyncEntry {

    private final long id;
    private final UUID uuid;
    private final String name;
    private final String nickname;
    private final ColorPreset color;
    private final NickMode mode;
    private final long updatedAt;

    public SyncEntry(long id, UUID uuid, String name, String nickname, ColorPreset color,
                     NickMode mode, long updatedAt) {
        this.id = id;
        this.uuid = uuid;
        this.name = name;
        this.nickname = nickname;
        this.color = color;
        this.mode = mode;
        this.updatedAt = updatedAt;
    }

    public long getId() {
        return id;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public String getNickname() {
        return nickname;
    }

    public ColorPreset getColor() {
        return color;
    }

    public NickMode getMode() {
        return mode;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /** 是否为删除事件。 */
    public boolean isRemoval() {
        return mode == NickMode.REMOVED || nickname == null || nickname.isEmpty();
    }

    /** 转换为 NickData。 */
    public NickData toNickData() {
        return new NickData(uuid, name, nickname, color, mode, updatedAt);
    }

    @Override
    public String toString() {
        return "SyncEntry{id=" + id + ", uuid=" + uuid + ", nickname='" + nickname
                + "', mode=" + mode + "}";
    }
}
