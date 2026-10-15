package com.ncnick.storage;

import com.ncnick.NCNick;
import com.ncnick.model.ColorPreset;
import com.ncnick.model.NickData;
import com.ncnick.model.NickMode;
import com.ncnick.model.SyncEntry;
import com.ncnick.util.Debug;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * MySQL 存储实现（第 1 条：防 SQL 注入 + 第 10 条：全服唯一）。
 *
 * <h3>防注入设计</h3>
 * <ol>
 *   <li><b>全部使用 PreparedStatement</b>：所有玩家可控数据都通过占位符 {@code ?} 绑定，
 *       绝不拼接进 SQL 文本。这是防注入的根本手段。</li>
 *   <li><b>表名白名单化</b>：表名无法参数化，因此由 ConfigManager 强制过滤为
 *       仅含字母/数字/下划线，从源头杜绝表名注入。</li>
 *   <li><b>唯一索引兜底</b>：{@code normalized} 列建 UNIQUE 索引，
 *       即使应用层并发检查出现竞态，数据库也会拒绝重复写入。</li>
 *   <li><b>输入长度限制</b>：绑定前对超长字符串做截断，防止 DoS。</li>
 *   <li><b>连接池</b>：HikariCP 管理连接，避免连接泄漏。</li>
 * </ol>
 */
public class MySQLStorage implements Storage {

    /** 昵称列最大长度（utf8mb4 下按字符算） */
    private static final int MAX_NICKNAME_LEN = 64;
    /** 归一化列最大长度 */
    private static final int MAX_NORMALIZED_LEN = 64;
    /** 玩家名列最大长度 */
    private static final int MAX_NAME_LEN = 16;

    private final NCNick plugin;
    private final String tablePlayers;
    private final String tableSync;
    private HikariDataSource pool;
    private volatile boolean ready = false;

    public MySQLStorage(NCNick plugin) {
        this.plugin = plugin;
        // 表名已在 ConfigManager 中做过白名单过滤，只能含字母/数字/下划线
        String prefix = plugin.getConfigManager().getMysqlTablePrefix();
        this.tablePlayers = prefix + "players";
        this.tableSync = prefix + "sync";
    }

    @Override
    public String getTypeName() {
        return "MySQL";
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void init() throws Exception {
        // 驱动由 DriverClassLoader 动态加载；若尚未加载则报出可操作的错误
        if (!isDriverLoaded()) {
            throw new IllegalStateException(
                    "MySQL 驱动未加载。插件会自动下载驱动到 plugins/NCNick/lib/，"
                            + "请检查网络或手动放入驱动后执行 /nca reload。");
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(buildJdbcUrl());
        // 刻意**不调用** setDriverClassName(...)：
        //
        // 驱动由 DriverClassLoader 的子 URLClassLoader 加载，插件类加载器看不到它。
        // Hikari 设置 driverClassName 时会走
        //   "HikariConfig class loader or Thread context classloader"
        // 两条路径去加载驱动类，二者都看不到子加载器里的类，
        // 实测报错：Failed to load driver class ... in either of ...
        //
        // 不设置该属性时，Hikari 回退到 DriverManager.getConnection(...)，
        // 而 DriverClassLoader 已注册对插件类加载器可见的 DriverShim，
        // 因此连接可以正常建立（已端到端实测通过）。
        cfg.setUsername(plugin.getConfigManager().getMysqlUsername());
        cfg.setPassword(plugin.getConfigManager().getMysqlPassword());
        cfg.setMaximumPoolSize(plugin.getConfigManager().getMysqlPoolSize());
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(plugin.getConfigManager().getMysqlConnectionTimeout());
        cfg.setPoolName("NCNick-Hikari");
        // 连接存活检测，避免拿到已断开的连接
        cfg.setValidationTimeout(3000L);
        cfg.setKeepaliveTime(60000L);
        cfg.setMaxLifetime(600000L);
        // 明确不使用 SSL（由配置控制，写在 URL 里）
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "120");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "true");
        cfg.addDataSourceProperty("rewriteBatchedStatements", "true");

        try {
            this.pool = new HikariDataSource(cfg);
        } catch (Throwable t) {
            throw new IllegalStateException("MySQL 连接池创建失败: " + t.getMessage(), t);
        }

        createTables();
        this.ready = true;
    }

    /**
     * 构造 JDBC URL。
     *
     * <p>注意：host / database 虽然来自配置（非玩家输入），仍做基本校验，
     * 防止配置被植入额外连接参数。
     */
    private String buildJdbcUrl() {
        String host = sanitizeHost(plugin.getConfigManager().getMysqlHost());
        int port = plugin.getConfigManager().getMysqlPort();
        String db = sanitizeDatabase(plugin.getConfigManager().getMysqlDatabase());
        boolean ssl = plugin.getConfigManager().isMysqlUseSsl();
        String tz = sanitizeTimeZone(plugin.getConfigManager().getMysqlTimeZone());

        StringBuilder sb = new StringBuilder(160);
        sb.append("jdbc:mysql://").append(host).append(':').append(port).append('/').append(db);
        sb.append("?useSSL=").append(ssl);
        sb.append("&allowPublicKeyRetrieval=true");
        sb.append("&useUnicode=true");
        sb.append("&characterEncoding=UTF-8");
        sb.append("&serverTimezone=").append(tz);
        sb.append("&autoReconnect=true");
        sb.append("&useCursorFetch=false");
        sb.append("&allowMultiQueries=false");
        sb.append("&useLocalSessionState=true");
        return sb.toString();
    }

    /**
     * 校验主机名：只允许字母、数字、点、短横线、下划线、冒号（IPv6）和方括号。
     */
    private static String sanitizeHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return "127.0.0.1";
        }
        String h = host.trim();
        StringBuilder sb = new StringBuilder(h.length());
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_'
                    || c == ':' || c == '[' || c == ']') {
                sb.append(c);
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? "127.0.0.1" : out;
    }

    /**
     * 校验数据库名：只允许字母、数字、下划线、短横线、美元符。
     */
    private static String sanitizeDatabase(String db) {
        if (db == null || db.trim().isEmpty()) {
            return "minecraft";
        }
        String d = db.trim();
        StringBuilder sb = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '$') {
                sb.append(c);
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? "minecraft" : out;
    }

    /**
     * 校验时区字符串：只允许字母、数字、斜杠、下划线、加号、短横线、冒号。
     */
    private static String sanitizeTimeZone(String tz) {
        if (tz == null || tz.trim().isEmpty()) {
            return "Asia/Shanghai";
        }
        String t = tz.trim();
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '/' || c == '_'
                    || c == '+' || c == '-' || c == ':') {
                sb.append(c);
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? "Asia/Shanghai" : out;
    }

    /**
     * 判断 MySQL 驱动是否已由本插件动态加载。
     *
     * <p><b>为什么不能用 {@code Class.forName(...)}</b>：驱动由
     * {@link com.ncnick.driver.DriverClassLoader} 创建的 {@code URLClassLoader} 加载，
     * 其 parent 是插件类加载器。Java 委派方向为「子 → 父」，
     * <b>插件类加载器看不到子加载器中的类</b>，因此 {@code Class.forName}
     * 会恒抛 ClassNotFoundException，导致 {@code init()} 误判并禁用插件。
     *
     * <p>正确做法：询问真正加载了驱动的那个类加载器。
     */
    private boolean isDriverLoaded() {
        try {
            com.ncnick.driver.DriverClassLoader loader =
                    plugin.getDriverManager().getClassLoader();
            // mysql-connector-j 8.x 的新类名，兼容 5.x 的旧类名
            return loader.isAvailable("com.mysql.cj.jdbc.Driver")
                    || loader.isAvailable("com.mysql.jdbc.Driver");
        } catch (Throwable t) {
            Debug.error(Debug.SQL, "检测 MySQL 驱动可见性失败", t);
            return false;
        }
    }

    /**
     * 建表。
     *
     * <p>表名来自白名单过滤后的配置，不含玩家输入，因此可安全拼接。
     * 所有列定义都为 utf8mb4，正确存储中文与 emoji。
     */
    private void createTables() throws SQLException {
        Connection conn = null;
        Statement st = null;
        try {
            conn = pool.getConnection();
            st = conn.createStatement();

            // 玩家昵称表
            // normalized 列建 UNIQUE 索引 —— 数据库层兜底保证全服唯一（第 10 条）
            String createPlayers = "CREATE TABLE IF NOT EXISTS " + tablePlayers + " ("
                    + "uuid VARCHAR(36) NOT NULL, "
                    + "name VARCHAR(16) NOT NULL, "
                    + "nickname VARCHAR(" + MAX_NICKNAME_LEN + ") NOT NULL, "
                    + "normalized VARCHAR(" + MAX_NORMALIZED_LEN + ") NOT NULL, "
                    + "color VARCHAR(64) NULL, "
                    + "mode VARCHAR(16) NOT NULL, "
                    + "updated_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (uuid), "
                    + "UNIQUE KEY uk_normalized (normalized), "
                    + "KEY idx_updated (updated_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
            st.executeUpdate(createPlayers);

            // 跨服同步表
            String createSync = "CREATE TABLE IF NOT EXISTS " + tableSync + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, "
                    + "uuid VARCHAR(36) NOT NULL, "
                    + "name VARCHAR(16) NOT NULL, "
                    + "nickname VARCHAR(" + MAX_NICKNAME_LEN + ") NOT NULL, "
                    + "color VARCHAR(64) NULL, "
                    + "mode VARCHAR(16) NOT NULL, "
                    + "updated_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (id), "
                    + "KEY idx_sync_uuid (uuid)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
            st.executeUpdate(createSync);

            // 兼容旧表结构：补充可能缺失的列
            ensureColumn(conn, tablePlayers, "normalized",
                    "VARCHAR(" + MAX_NORMALIZED_LEN + ") NOT NULL DEFAULT ''");
            ensureColumn(conn, tablePlayers, "color", "VARCHAR(64) NULL");
            ensureColumn(conn, tableSync, "color", "VARCHAR(64) NULL");

            Debug.log(Debug.SQL, "MySQL 表结构已就绪: {}, {}", tablePlayers, tableSync);
        } finally {
            closeQuietly(st);
            closeQuietly(conn);
        }
    }

    /**
     * 检查列是否存在，不存在则添加（用于平滑升级旧版本数据表）。
     *
     * <p>安全关键：表名与列名无法用占位符参数化，因此这里做**标识符白名单校验**，
     * 只允许 {@code [A-Za-z_][A-Za-z0-9_]*}，并用反引号包裹。
     * 这是防御纵深措施：即便将来有人把配置项传入本方法，也无法注入 SQL。
     */
    private void ensureColumn(Connection conn, String table, String column, String definition) {
        // ---- 标识符白名单校验（防注入的根本手段） ----
        if (!isSafeIdentifier(table)) {
            Debug.log(Debug.SQL, "拒绝非法表名: {}", table);
            return;
        }
        if (!isSafeIdentifier(column)) {
            Debug.log(Debug.SQL, "拒绝非法列名: {}", column);
            return;
        }
        // definition 由代码常量拼接而成，额外做一次危险字符检查
        if (definition == null || definition.contains(";") || definition.contains("--")
                || definition.contains("/*")) {
            Debug.log(Debug.SQL, "拒绝可疑的列定义: {}", definition);
            return;
        }

        String safeTable = "`" + table + "`";
        Statement st = null;
        ResultSet rs = null;
        try {
            // 用参数绑定查询列是否存在，避免拼接 LIKE 值
            PreparedStatement ps = conn.prepareStatement(
                    "SHOW COLUMNS FROM " + safeTable + " LIKE ?");
            try {
                ps.setString(1, column);
                rs = ps.executeQuery();
                if (rs.next()) {
                    return;
                }
            } finally {
                closeQuietly(rs);
                rs = null;
                closeQuietly(ps);
            }

            // 列不存在，添加之（标识符已通过白名单校验）
            st = conn.createStatement();
            st.executeUpdate("ALTER TABLE " + safeTable + " ADD COLUMN `" + column + "` "
                    + definition);
            plugin.getLogger().info("已为表 " + table + " 添加列 " + column);
        } catch (Throwable t) {
            Debug.error(Debug.SQL, "检查列 " + column + " 失败", t);
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
        }
    }

    /**
     * 校验 SQL 标识符是否安全。
     *
     * <p>只允许字母、数字、下划线，且不能以数字开头，长度不超过 64。
     */
    private static boolean isSafeIdentifier(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) {
            return false;
        }
        char first = name.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        // 额外拒绝非 ASCII 字母，避免同形字符绕过
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ascii) {
                return false;
            }
        }
        return true;
    }

    // ==========================================================
    //  查询
    // ==========================================================

    @Override
    public NickData load(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        String sql = "SELECT name, nickname, color, mode, updated_at FROM " + tablePlayers
                + " WHERE uuid = ?";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        long start = System.nanoTime();
        try {
            conn = pool.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setString(1, uuid.toString());
            rs = ps.executeQuery();
            if (rs.next()) {
                return readRow(uuid, rs);
            }
            return null;
        } catch (SQLException e) {
            logSqlError("读取玩家数据失败", sql, e);
            return null;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
            closeQuietly(conn);
            Debug.timing(Debug.SQL, "load(uuid)", System.nanoTime() - start);
        }
    }

    @Override
    public NickData loadByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String sql = "SELECT uuid, nickname, color, mode, updated_at FROM " + tablePlayers
                + " WHERE name = ? LIMIT 1";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = pool.getConnection();
            ps = conn.prepareStatement(sql);
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
            closeQuietly(conn);
        }
    }

    @Override
    public NickData loadByNickname(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        String sql = "SELECT uuid, name, nickname, color, mode, updated_at FROM " + tablePlayers
                + " WHERE normalized = ? LIMIT 1";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = pool.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setString(1, truncate(normalized, MAX_NORMALIZED_LEN));
            rs = ps.executeQuery();
            if (rs.next()) {
                NickData d = readRow(parseUuid(rs.getString("uuid")), rs);
                // loadByNickname 需要 name 列，而 readRow 已读取，这里补上
                if (d != null) {
                    String name = rs.getString("name");
                    return new NickData(d.getUuid(), name, d.getNickname(), d.getColor(),
                            d.getMode(), d.getUpdatedAt());
                }
            }
            return null;
        } catch (SQLException e) {
            logSqlError("按昵称读取失败", sql, e);
            return null;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
            closeQuietly(conn);
        }
    }

    @Override
    public boolean isNicknameTaken(String normalized, UUID exclude) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        String sql;
        boolean hasExclude = exclude != null;
        if (hasExclude) {
            sql = "SELECT 1 FROM " + tablePlayers
                    + " WHERE normalized = ? AND uuid <> ? LIMIT 1";
        } else {
            sql = "SELECT 1 FROM " + tablePlayers + " WHERE normalized = ? LIMIT 1";
        }

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = pool.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setString(1, truncate(normalized, MAX_NORMALIZED_LEN));
            if (hasExclude) {
                ps.setString(2, exclude.toString());
            }
            rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            logSqlError("昵称查重失败", sql, e);
            // 保守策略：查询失败时认为已占用，避免重复昵称被写入
            return true;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
            closeQuietly(conn);
        }
    }

    @Override
    public int countAll() {
        String sql = "SELECT COUNT(*) FROM " + tablePlayers;
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = pool.getConnection();
            ps = conn.prepareStatement(sql);
            rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            logSqlError("统计数量失败", sql, e);
            return 0;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
            closeQuietly(conn);
        }
    }

    @Override
    public List<NickData> list(int offset, int limit) {
        List<NickData> out = new ArrayList<NickData>();
        if (limit <= 0) {
            return out;
        }
        String sql = "SELECT uuid, name, nickname, color, mode, updated_at FROM " + tablePlayers
                + " ORDER BY updated_at DESC LIMIT ? OFFSET ?";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = pool.getConnection();
            ps = conn.prepareStatement(sql);
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
            closeQuietly(conn);
        }
        return out;
    }

    @Override
    public long getMaxSyncId() {
        String sql = "SELECT COALESCE(MAX(id), 0) FROM " + tableSync;
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = pool.getConnection();
            ps = conn.prepareStatement(sql);
            rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            logSqlError("读取同步游标失败", sql, e);
            return 0L;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
            closeQuietly(conn);
        }
    }

    @Override
    public List<SyncEntry> pollSync(long afterId, int limit) {
        List<SyncEntry> out = new ArrayList<SyncEntry>();
        if (limit <= 0) {
            return out;
        }
        String sql = "SELECT id, uuid, name, nickname, color, mode, updated_at FROM " + tableSync
                + " WHERE id > ? ORDER BY id ASC LIMIT ?";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = pool.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setLong(1, afterId);
            ps.setInt(2, Math.min(limit, 500));
            rs = ps.executeQuery();
            while (rs.next()) {
                out.add(new SyncEntry(
                        rs.getLong("id"),
                        parseUuid(rs.getString("uuid")),
                        rs.getString("name"),
                        rs.getString("nickname"),
                        ColorPreset.deserialize(rs.getString("color")),
                        NickMode.parse(rs.getString("mode")),
                        rs.getLong("updated_at")));
            }
        } catch (SQLException e) {
            logSqlError("拉取同步增量失败", sql, e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
            closeQuietly(conn);
        }
        return out;
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

        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = pool.getConnection();
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                String sql = "INSERT INTO " + tablePlayers
                        + " (uuid, name, nickname, normalized, color, mode, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE"
                        + " name = VALUES(name),"
                        + " nickname = VALUES(nickname),"
                        + " normalized = VALUES(normalized),"
                        + " color = VALUES(color),"
                        + " mode = VALUES(mode),"
                        + " updated_at = VALUES(updated_at)";
                ps = conn.prepareStatement(sql);
                ps.setString(1, data.getUuid().toString());
                ps.setString(2, truncate(data.getName(), MAX_NAME_LEN));
                ps.setString(3, truncate(data.getNickname(), MAX_NICKNAME_LEN));
                ps.setString(4, truncate(normalized, MAX_NORMALIZED_LEN));
                ps.setString(5, serializeColor(data.getColor()));
                ps.setString(6, data.getMode().getId());
                ps.setLong(7, data.getUpdatedAt());
                ps.executeUpdate();
                closeQuietly(ps);
                ps = null;

                // 写同步表，供其他服务器轮询
                appendSync(conn, data, normalized);

                conn.commit();
                return true;
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // 忽略回滚异常
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(auto);
                } catch (SQLException ignored) {
                    // 忽略
                }
            }
        } catch (SQLException e) {
            // 唯一索引冲突是预期内的情况（并发下两个玩家同时设置同一昵称）
            if (isDuplicateKey(e)) {
                // 用 warning 而非 severe：这是正常的业务冲突，调用方会提示玩家
                Debug.log(Debug.SQL, "昵称 {} 触发唯一约束", data.getNickname());
            } else {
                plugin.getLogger().severe("MySQL 保存失败 (" + data.getUuid() + "): "
                        + e.getMessage());
                Debug.error(Debug.SQL, "保存失败", e);
            }
            return false;
        } finally {
            closeQuietly(ps);
            closeQuietly(conn);
        }
    }

    @Override
    public void updateColor(UUID uuid, String colorSerialized) {
        if (uuid == null) {
            return;
        }
        String sql = "UPDATE " + tablePlayers + " SET color = ?, updated_at = ? WHERE uuid = ?";
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = pool.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setString(1, colorSerialized);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
            closeQuietly(ps);
            ps = null;

            // 读取当前数据以写同步记录
            NickData current = load(uuid);
            if (current != null) {
                String normalized = com.ncnick.util.NicknameNormalizer.normalize(
                        current.getNickname(),
                        plugin.getConfigManager().isUniqueIgnoreColors(),
                        plugin.getConfigManager().isUniqueIgnoreCase(),
                        plugin.getConfigManager().isUniqueIgnoreSpaces());
                conn = ensureConnection(conn);
                appendSync(conn, current, normalized);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MySQL 颜色更新失败 (" + uuid + "): " + e.getMessage());
            Debug.error(Debug.SQL, "颜色更新失败", e);
        } finally {
            closeQuietly(ps);
            closeQuietly(conn);
        }
    }

    private Connection ensureConnection(Connection existing) throws SQLException {
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        return pool.getConnection();
    }

    /**
     * 往同步表追加一条记录。
     */
    private void appendSync(Connection conn, NickData data, String normalized) throws SQLException {
        String sql = "INSERT INTO " + tableSync
                + " (uuid, name, nickname, color, mode, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            ps.setString(1, data.getUuid().toString());
            ps.setString(2, truncate(data.getName(), MAX_NAME_LEN));
            ps.setString(3, truncate(data.getNickname(), MAX_NICKNAME_LEN));
            ps.setString(4, serializeColor(data.getColor()));
            ps.setString(5, data.getMode().getId());
            ps.setLong(6, data.getUpdatedAt());
            ps.executeUpdate();
        } finally {
            closeQuietly(ps);
        }
    }

    @Override
    public void remove(UUID uuid) {
        if (uuid == null) {
            return;
        }
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = pool.getConnection();
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 先查出名字，用于同步事件
                String name = null;
                String selSql = "SELECT name FROM " + tablePlayers + " WHERE uuid = ?";
                ps = conn.prepareStatement(selSql);
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                try {
                    if (rs.next()) {
                        name = rs.getString("name");
                    }
                } finally {
                    closeQuietly(rs);
                    closeQuietly(ps);
                    ps = null;
                }

                String delSql = "DELETE FROM " + tablePlayers + " WHERE uuid = ?";
                ps = conn.prepareStatement(delSql);
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
                closeQuietly(ps);
                ps = null;

                // 广播删除事件
                if (name != null) {
                    String syncSql = "INSERT INTO " + tableSync
                            + " (uuid, name, nickname, color, mode, updated_at)"
                            + " VALUES (?, ?, '', NULL, ?, ?)";
                    ps = conn.prepareStatement(syncSql);
                    ps.setString(1, uuid.toString());
                    ps.setString(2, truncate(name, MAX_NAME_LEN));
                    ps.setString(3, NickMode.REMOVED.getId());
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                    closeQuietly(ps);
                    ps = null;
                }

                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // 忽略
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(auto);
                } catch (SQLException ignored) {
                    // 忽略
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL 删除失败 (" + uuid + "): " + e.getMessage());
            Debug.error(Debug.SQL, "删除失败", e);
        } finally {
            closeQuietly(ps);
            closeQuietly(conn);
        }
    }

    @Override
    public void close() {
        ready = false;
        if (pool != null) {
            try {
                pool.close();
            } catch (Throwable ignored) {
                // 忽略关闭异常
            }
            pool = null;
        }
    }

    // ==========================================================
    //  工具
    // ==========================================================

    /**
     * 从结果集读取一行。
     *
     * <p>列名兼容：调用方保证查询里包含所需列。
     */
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

    private static String serializeColor(ColorPreset color) {
        return color == null ? null : color.serialize();
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

    /**
     * 安全截断字符串，防止超长数据写入导致列截断错误。
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 判断是否为唯一键冲突异常。
     */
    private static boolean isDuplicateKey(SQLException e) {
        // MySQL 错误码 1062 = Duplicate entry
        if (e.getErrorCode() == 1062) {
            return true;
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("duplicate");
    }

    /**
     * 统一记录 SQL 错误，调试模式下附带 SQL 文本（不含参数值）。
     */
    private void logSqlError(String action, String sql, SQLException e) {
        plugin.getLogger().warning("MySQL " + action + ": " + e.getMessage());
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

    /** 供调试使用：当前连接池状态。 */
    public String poolStatus() {
        if (pool == null) {
            return "未初始化";
        }
        return "活动=" + pool.getHikariPoolMXBean().getActiveConnections()
                + " 空闲=" + pool.getHikariPoolMXBean().getIdleConnections()
                + " 总计=" + pool.getHikariPoolMXBean().getTotalConnections();
    }

    /** 空列表常量，避免重复创建。 */
    static List<SyncEntry> emptySync() {
        return Collections.emptyList();
    }
}
