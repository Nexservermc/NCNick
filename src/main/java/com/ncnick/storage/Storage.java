package com.ncnick.storage;

import com.ncnick.model.NickData;
import java.util.List;
import java.util.UUID;

/**
 * 存储层接口。
 *
 * <p>所有实现都必须使用 PreparedStatement，严禁字符串拼接 SQL。
 */
public interface Storage {

    /**
     * 初始化存储（建表、连接池等）。
     *
     * @throws Exception 初始化失败
     */
    void init() throws Exception;

    /**
     * 按 UUID 加载昵称数据。
     *
     * @return 数据，不存在返回 null
     */
    NickData load(UUID uuid);

    /**
     * 按玩家名加载昵称数据。
     *
     * @return 数据，不存在返回 null
     */
    NickData loadByName(String name);

    /**
     * 按昵称反查玩家（用于唯一性校验与管理员查询）。
     *
     * @param normalized 已归一化的昵称（调用方负责处理颜色/大小写）
     * @return 数据，不存在返回 null
     */
    NickData loadByNickname(String normalized);

    /**
     * 判断归一化后的昵称是否已被他人占用（全服唯一，第 10 条）。
     *
     * @param normalized 已归一化的昵称
     * @param exclude    排除的玩家 UUID（改自己的昵称时传自己），可为 null
     * @return 是否已被占用
     */
    boolean isNicknameTaken(String normalized, UUID exclude);

    /**
     * 保存（插入或更新）昵称数据。
     *
     * <p>返回值用于区分"真的写入了"与"因唯一约束冲突被拒绝"，
     * 调用方必须据此给用户真实反馈，避免出现"提示成功但实际没写"的假成功。
     *
     * @return true 表示写入成功；false 表示失败（含昵称重复）
     */
    boolean save(NickData data);

    /**
     * 删除指定玩家的昵称。
     */
    void remove(UUID uuid);

    /**
     * 更新昵称颜色。
     */
    void updateColor(UUID uuid, String colorSerialized);

    /**
     * 统计已设置昵称的玩家总数。
     */
    int countAll();

    /**
     * 分页列出所有昵称数据（按下发时间倒序）。
     *
     * @param offset 偏移
     * @param limit  数量上限
     */
    List<NickData> list(int offset, int limit);

    /**
     * 读取当前同步游标（仅 MySQL 使用，SQLite 返回 0）。
     */
    long getMaxSyncId();

    /**
     * 拉取游标之后的变更（仅 MySQL 使用，SQLite 返回空列表）。
     */
    List<com.ncnick.model.SyncEntry> pollSync(long afterId, int limit);

    /**
     * 判断存储是否可用（初始化成功）。
     */
    boolean isReady();

    /**
     * 关闭存储，释放资源。
     */
    void close();

    /** 存储类型名，用于日志。 */
    String getTypeName();

    /**
     * 数据实际存放位置，用于启动日志。
     *
     * <p>写成具体位置（文件绝对路径 / host:port/db）而不是只写
     * "SQLite"/"MySQL"，是因为**用错存储类型**是"数据看起来丢了"
     * 最常见的原因：日志里直接看到路径/主机，一眼就能发现不对。
     */
    String describeTarget();
}
