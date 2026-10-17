package com.ncnick.storage;

import com.ncnick.NCNick;
import com.ncnick.model.ColorPreset;
import com.ncnick.model.NickData;
import com.ncnick.model.NickMode;
import com.ncnick.model.SyncEntry;
import com.ncnick.util.Debug;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * SQLite 存储实现（单服模式）。
 *
 * <h3>防注入设计</h3>
 * 与 MySQLStorage 一致：全部 PreparedStatement、表名白名单、归一化列唯一索引兜底。
 *
 * <h3>并发设计</h3>
 * SQLite 写操作是串行的，因此：
 * <ul>
 *   <li>开启 WAL 模式提升并发读性能</li>
 *   <li>所有写操作在同步块内串行化，避免 "database is locked"</li>
 *   <li>设置 busy_timeout 让锁等待而非立即失败</li>
 * </ul>
 */
public class SQLiteStorage implements Storage {

    private static final int MAX_NICKNAME_LEN = 64;
    private static final int MAX_NORMALIZED_LEN = 64;
    private static final int MAX_NAME_LEN = 16;

    private final NCNick plugin;
    private final File dbFile;
    /** 串行化写操作 */
    private final Object writeLock = new Object();
    private Connection connection;
    private volatile boolean ready = false;
    /** 本次启动前 db 文件是否不存在（即本次新建了空库） */
    private boolean fileCreated = false;

    public SQLiteStorage(NCNick plugin) {
        this.plugin = plugin;
        this.dbFile = new File(plugin.getDataFolder(),
                plugin.getConfigManager().getSqliteFile());
    }

    @Override
    public String getTypeName() {
        return "SQLite";
    }

    @Override
    public String describeTarget() {
        return dbFile.getAbsolutePath();
    }

    /**
     * 本次启动前数据库文件是否**不存在**（即刚建的空库）。
     *
     * <p>为空库时插件会打印警告：很可能是配置从 MySQL 误切到了 SQLite，
     * 此时玩家会以为"昵称数据全丢了"，其实数据还在 MySQL 里。
     */
    public boolean wasFileCreated() {
        return fileCreated;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void init() throws Exception {
        if (!isDriverLoaded()) {
            throw new IllegalStateException(
                    "SQLite 驱动未加载。插件会自动下载驱动到 plugins/NCNick/lib/，"
                            + "请检查网络或手动放入驱动后执行 /nca reload。");
        }

        File parent = dbFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建数据目录: " + parent.getAbsolutePath());
        }

        // 使用 SQLite JDBC 直连
        connection = openConnection();
        createTables();
        ready = true;
        Debug.log(Debug.SQL, "SQLite 已就绪: {}", dbFile.getAbsolutePath());
    }

    /**
     * 判断 SQLite 驱动是否已由本插件动态加载。
     *
     * <p><b>为什么不能用 {@code Class.forName("org.sqlite.JDBC")}</b>：
     * 驱动由 {@link com.ncnick.driver.DriverClassLoader} 创建的
     * {@code URLClassLoader} 加载，其 parent 是插件类加载器。
     * Java 的委派方向是「子 → 父」，因此<b>插件类加载器看不到子加载器中的类</b>。
     * 用 {@code Class.forName(name)}（走调用方=插件类加载器）会恒抛
     * ClassNotFoundException，导致 {@code init()} 误判"驱动未加载"并禁用插件。
     *
     * <p>正确做法：询问真正加载了驱动的那个类加载器。
     */
    private boolean isDriverLoaded() {
        try {
            return plugin.getDriverManager().getClassLoader()
                    .isAvailable("org.sqlite.JDBC");
        } catch (Throwable t) {
            Debug.error(Debug.SQL, "检测 SQLite 驱动可见性失败", t);
            return false;
        }
    }

    /**
     * 建立 SQLite 连接并设置优化参数。
     */
    private Connection openConnection() throws SQLException {
        // 记录"本次是否新建了数据库文件"——建表之前判断才有意义。
        // 新建的空库往往意味着用户从 MySQL 误切到了 SQLite。
        this.fileCreated = !dbFile.exists();
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Connection conn = DriverManager.getConnection(url);
        // 自动提交开启，写操作用显式事务包裹
        conn.setAutoCommit(true);

        Statement st = null;
        try {
            st = conn.createStatement();
            // WAL 模式：提升读写并发
            st.execute("PRAGMA journal_mode=WAL");
            // 平衡安全与性能
            st.execute("PRAGMA synchronous=NORMAL");
            // 锁等待 5 秒，避免瞬时并发直接失败
            st.execute("PRAGMA busy_timeout=5000");
            // 外键约束（当前无外键，保持一致性）
            st.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            Debug.error(Debug.SQL, "设置 SQLite PRAGMA 失败", e);
        } finally {
            closeQuietly(st);
        }
        return conn;
    }

    /**
     * 获取可用连接（断线自动重连）。
     */
    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed()) {
            synchronized (writeLock) {
                if (connection == null || connection.isClosed()) {
                    connection = openConnection();
                }
            }
        }
        return connection;
    }

    private void createTables() throws SQLException {
        Connection c = conn();
        Statement st = null;
        try {
            st = c.createStatement();
            // normalized 列建 UNIQUE 索引，数据库层保证唯一性
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ncnick_players ("
                    + "uuid TEXT NOT NULL PRIMARY KEY, "
                    + "name TEXT NOT NULL, "
                    + "nickname TEXT NOT NULL, "
                    + "normalized TEXT NOT NULL, "
                    + "color TEXT NULL, "
                    + "mode TEXT NOT NULL, "
                    + "updated_at INTEGER NOT NULL)");
            // 唯一索引单独创建，兼容已存在的旧表
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_ncnick_normalized "
                    + "ON ncnick_players (normalized)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ncnick_updated "
                    + "ON ncnick_players (updated_at)");

            // 兼容旧表：补充缺失列
            ensureColumn(c, "ncnick_players", "normalized", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(c, "ncnick_players", "color", "TEXT NULL");

            Debug.log(Debug.SQL, "SQLite 表结构已就绪");
        } finally {
            closeQuietly(st);
        }
    }

    /**
     * 检查列是否存在，不存在则添加（用于平滑升级旧版本数据表）。
     *
     * <p>安全关键：表名与列名无法参数化，因此做**标识符白名单校验**，
     * 只允许 {@code [A-Za-z_][A-Za-z0-9_]*}。这与 MySQLStorage 保持同一策略，
     * 属于防御纵深：即便将来有人把配置项传入本方法，也无法注入 SQL。
     */
    private void ensureColumn(Connection c, String table, String column, String definition) {
        // ---- 标识符白名单校验 ----
        if (!isSafeIdentifier(table)) {
            Debug.log(Debug.SQL, "拒绝非法 SQLite 表名: {}", table);
            return;
        }
        if (!isSafeIdentifier(column)) {
            Debug.log(Debug.SQL, "拒绝非法 SQLite 列名: {}", column);
            return;
        }
        if (definition == null || definition.contains(";") || definition.contains("--")
                || definition.contains("/*")) {
            Debug.log(Debug.SQL, "拒绝可疑的列定义: {}", definition);
            return;
        }

        Statement st = null;
        ResultSet rs = null;
        try {
            st = c.createStatement();
            // 表名已通过白名单校验，可安全拼接
            rs = st.executeQuery("PRAGMA table_info(" + table + ")");
            boolean found = false;
            while (rs.next()) {
                String name = rs.getString("name");
                if (column.equalsIgnoreCase(name)) {
                    found = true;
                    break;
                }
            }
            closeQuietly(rs);
            rs = null;
            closeQuietly(st);
            st = null;

            if (!found) {
                st = c.createStatement();
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " "
                        + definition);
                plugin.getLogger().info("已为 SQLite 表 " + table + " 添加列 " + column);
            }
        } catch (Throwable t) {
            Debug.error(Debug.SQL, "检查 SQLite 列失败: " + column, t);
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
        }
    }

    // ==========================================================
    //  查询
    // ==========================================================

    @Override
    public NickData load(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        String sql = "SELECT name, nickname, color, mode, updated_at FROM ncnick_players"
                + " WHERE uuid = ?";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn().prepareStatement(sql);
            ps.setString(1, uuid.toString());
            rs = ps.executeQuery();
            if (rs.next()) {
                return readRow(uuid, rs);
            }
            return null;
        } catch (SQLException e) {
            logSqlError("读取失败", sql, e);
            return null;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    @Override
    public NickData loadByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String sql = "SELECT uuid, nickname, color, mode, updated_at FROM ncnick_players"
                + " WHERE name = ? LIMIT 1";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn().prepareStatement(sql);
            ps.setString(1, truncate(name, MAX_NAME_LEN));
            rs = ps.executeQuery();
            if (rs.next()) {
                return readRow(parseUuid(rs.getString("uuid")), rs);
            }
            return null;
        } catch (SQLException e) {
            logSqlError("按名字读取失败", sql, e);
            return null;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    @Override
    public NickData loadByNickname(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        String sql = "SELECT uuid, name, nickname, color, mode, updated_at FROM ncnick_players"
                + " WHERE normalized = ? LIMIT 1";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn().prepareStatement(sql);
            ps.setString(1, truncate(normalized, MAX_NORMALIZED_LEN));
            rs = ps.executeQuery();
            if (rs.next()) {
                UUID uuid = parseUuid(rs.getString("uuid"));
                String name = rs.getString("name");
                String nickname = rs.getString("nickname");
                ColorPreset color = ColorPreset.deserialize(rs.getString("color"));
                NickMode mode = NickMode.parse(rs.getString("mode"));
                long updated = rs.getLong("updated_at");
                return new NickData(uuid, name, nickname, color, mode, updated);
            }
            return null;
        } catch (SQLException e) {
            logSqlError("按昵称读取失败", sql, e);
            return null;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    @Override
    public boolean isNicknameTaken(String normalized, UUID exclude) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        boolean hasExclude = exclude != null;
        String sql = hasExclude
                ? "SELECT 1 FROM ncnick_players WHERE normalized = ? AND uuid <> ? LIMIT 1"
                : "SELECT 1 FROM ncnick_players WHERE normalized = ? LIMIT 1";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn().prepareStatement(sql);
            ps.setString(1, truncate(normalized, MAX_NORMALIZED_LEN));
            if (hasExclude) {
                ps.setString(2, exclude.toString());
            }
            rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            logSqlError("查重失败", sql, e);
            // 保守策略：查询失败视为已占用
            return true;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    @Override
    public int countAll() {
        String sql = "SELECT COUNT(*) FROM ncnick_players";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn().prepareStatement(sql);
            rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            logSqlError("统计失败", sql, e);
            return 0;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    @Override
    public List<NickData> list(int offset, int limit) {
        List<NickData> out = new ArrayList<NickData>();
        if (limit <= 0) {
            return out;
        }
        String sql = "SELECT uuid, name, nickname, color, mode, updated_at FROM ncnick_players"
                + " ORDER BY updated_at DESC LIMIT ? OFFSET ?";
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn().prepareStatement(sql);
            ps.setInt(1, Math.min(limit, 500));
            ps.setInt(2, Math.max(0, offset));
            rs = ps.executeQuery();
            while (rs.next()) {
                NickData d = readRow(parseUuid(rs.getString("uuid")), rs);
                if (d != null) {
                    out.add(d);
                }
            }
        } catch (SQLException e) {
            logSqlError("分页查询失败", sql, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
        return out;
    }

    /** 单服模式无跨服同步。 */
    @Override
    public long getMaxSyncId() {
        return 0L;
    }

    /** 单服模式无跨服同步。 */
    @Override
    public List<SyncEntry> pollSync(long afterId, int limit) {
        return Collections.emptyList();
    }

    // ==========================================================
    //  写入
    // ==========================================================

    @Override
    public boolean save(NickData data) {
        if (data == null || data.getUuid() == null) {
            return false;
        }
        String normalized = com.ncnick.util.NicknameNormalizer.normalize(
                data.getNickname(),
                plugin.getConfigManager().isUniqueIgnoreColors(),
                plugin.getConfigManager().isUniqueIgnoreCase(),
                plugin.getConfigManager().isUniqueIgnoreSpaces());

        String sql = "INSERT INTO ncnick_players"
                + " (uuid, name, nickname, normalized, color, mode, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT(uuid) DO UPDATE SET"
                + " name = excluded.name,"
                + " nickname = excluded.nickname,"
                + " normalized = excluded.normalized,"
                + " color = excluded.color,"
                + " mode = excluded.mode,"
                + " updated_at = excluded.updated_at";

        synchronized (writeLock) {
            PreparedStatement ps = null;
            try {
                ps = conn().prepareStatement(sql);
                ps.setString(1, data.getUuid().toString());
                ps.setString(2, truncate(data.getName(), MAX_NAME_LEN));
                ps.setString(3, truncate(data.getNickname(), MAX_NICKNAME_LEN));
                ps.setString(4, truncate(normalized, MAX_NORMALIZED_LEN));
                ps.setString(5, data.getColor() == null ? null : data.getColor().serialize());
                ps.setString(6, data.getMode().getId());
                ps.setLong(7, data.getUpdatedAt());
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (isDuplicateKey(e)) {
                    // 唯一约束冲突：正常的业务冲突，调用方会提示玩家
                    Debug.log(Debug.SQL, "昵称 {} 触发唯一约束", data.getNickname());
                } else {
                    plugin.getLogger().severe("SQLite 保存失败 (" + data.getUuid() + "): "
                            + e.getMessage());
                    Debug.error(Debug.SQL, "SQLite 保存失败", e);
                }
                return false;
            } finally {
                closeQuietly(ps);
            }
        }
    }

    @Override
    public void updateColor(UUID uuid, String colorSerialized) {
        if (uuid == null) {
            return;
        }
        String sql = "UPDATE ncnick_players SET color = ?, updated_at = ? WHERE uuid = ?";
        synchronized (writeLock) {
            PreparedStatement ps = null;
            try {
                ps = conn().prepareStatement(sql);
                ps.setString(1, colorSerialized);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("SQLite 颜色更新失败 (" + uuid + "): " + e.getMessage());
                Debug.error(Debug.SQL, "SQLite 颜色更新失败", e);
            } finally {
                closeQuietly(ps);
            }
        }
    }

    @Override
    public void remove(UUID uuid) {
        if (uuid == null) {
            return;
        }
        String sql = "DELETE FROM ncnick_players WHERE uuid = ?";
        synchronized (writeLock) {
            PreparedStatement ps = null;
            try {
                ps = conn().prepareStatement(sql);
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("SQLite 删除失败 (" + uuid + "): " + e.getMessage());
                Debug.error(Debug.SQL, "SQLite 删除失败", e);
            } finally {
                closeQuietly(ps);
            }
        }
    }

    @Override
    public void close() {
        ready = false;
        synchronized (writeLock) {
            if (connection != null) {
                try {
                    // 关闭前做一次 WAL 检查点，确保数据落盘
                    Statement st = null;
                    try {
                        st = connection.createStatement();
                        st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    } catch (Throwable ignored) {
                        // 忽略检查点失败
                    } finally {
                        closeQuietly(st);
                    }
                    connection.close();
                } catch (Throwable ignored) {
                    // 忽略关闭异常
                }
                connection = null;
            }
        }
    }

    // ==========================================================
    //  工具
    // ==========================================================

    private NickData readRow(UUID uuid, ResultSet rs) throws SQLException {
        if (uuid == null) {
            return null;
        }
        String name = rs.getString("name");
        String nickname = rs.getString("nickname");
        String colorRaw = rs.getString("color");
        String modeRaw = rs.getString("mode");
        long updated = rs.getLong("updated_at");
        return new NickData(uuid, name, nickname,
                ColorPreset.deserialize(colorRaw), NickMode.parse(modeRaw), updated);
    }

    /**
     * 校验 SQL 标识符是否安全。
     *
     * <p>只允许 ASCII 字母、数字、下划线，且不能以数字开头，长度不超过 64。
     * 刻意拒绝非 ASCII 字母，避免同形字符绕过。
     */
    private static boolean isSafeIdentifier(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean ascii = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_';
            if (!ascii) {
                return false;
            }
        }
        char first = name.charAt(0);
        return (first >= 'a' && first <= 'z')
                || (first >= 'A' && first <= 'Z')
                || first == '_';
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean isDuplicateKey(SQLException e) {
        int code = e.getErrorCode();
        // SQLITE_CONSTRAINT = 19 / SQLITE_CONSTRAINT_UNIQUE = 2067
        if (code == 19 || code == 2067) {
            return true;
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("unique");
    }

    private void logSqlError(String action, String sql, SQLException e) {
        plugin.getLogger().warning("SQLite " + action + ": " + e.getMessage());
        Debug.log(Debug.SQL, "失败语句: {}", sql);
        Debug.error(Debug.SQL, action, e);
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }
}
