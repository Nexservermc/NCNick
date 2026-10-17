package com.ncnick;

import com.ncnick.config.FileConfig;
import com.ncnick.util.CharsetFilter;
import com.ncnick.util.Debug;
import com.ncnick.util.Msg;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 配置管理器。
 *
 * <p>集中读取 config.yml 的所有配置项，提供强类型的取值方法。
 * 所有配置都在 {@link #reload()} 时重新解析，reload 后立即生效。
 */
public class ConfigManager {

    private final NCNick plugin;

    // ---- 拆分的配置文件 ----
    /** 昵称校验：字符集、长度、禁止项、唯一性 */
    private FileConfig validation;
    /** 金融：价格、支付方式 */
    private FileConfig economy;
    /** 显示格式：聊天/TAB/头顶/占位符 */
    private FileConfig display;
    /** 存储与驱动 */
    private FileConfig storage;
    /** 实验性功能 */
    private FileConfig experimental;
    /** 界面外观 */
    private FileConfig gui;
    /** 调试 */
    private FileConfig debug;

    // ---- 缓存解析结果，避免每次调用都重新解析 ----
    private CharsetFilter charsetFilter;
    private String configVersion;
    private long lastReload;
    /** 旧版存储配置是否已尝试迁移（只做一次，reload 不重复） */
    private boolean storageMigrated;

    public ConfigManager(NCNick plugin) {
        this.plugin = plugin;
        // 先创建各文件句柄，再解析派生配置
        this.validation = new FileConfig(plugin, "validation.yml");
        this.economy = new FileConfig(plugin, "economy.yml");
        this.display = new FileConfig(plugin, "display.yml");
        this.storage = new FileConfig(plugin, "storage.yml");
        this.experimental = new FileConfig(plugin, "experimental.yml");
        this.gui = new FileConfig(plugin, "gui.yml");
        this.debug = new FileConfig(plugin, "debug.yml");
        reload();
    }

    // ==========================================================
    //  配置文件句柄
    // ==========================================================

    /** 昵称校验配置（validation.yml）。 */
    public FileConfig val() {
        return validation;
    }

    /** 金融配置（economy.yml）。 */
    public FileConfig eco() {
        return economy;
    }

    /** 显示格式配置（display.yml）。 */
    public FileConfig disp() {
        return display;
    }

    /** 存储与驱动配置（storage.yml）。 */
    public FileConfig sto() {
        return storage;
    }

    /** 实验性功能配置（experimental.yml）。 */
    public FileConfig exp() {
        return experimental;
    }

    /** 界面外观配置（gui.yml）。 */
    public FileConfig gui() {
        return gui;
    }

    /** 调试配置（debug.yml）。 */
    public FileConfig dbg() {
        return debug;
    }

    /**
     * 重新解析所有派生配置。
     * 由 {@link NCNick#reloadPlugin()} 调用。
     *
     * <p>会重新读取全部拆分后的配置文件，
     * 因此新增的 yml 内容同样支持热重载。
     */
    public void reload() {
        // 重新加载所有拆分文件（磁盘可能已被外部修改）
        reloadFile(validation);
        reloadFile(economy);
        reloadFile(display);
        reloadFile(storage);
        reloadFile(experimental);
        reloadFile(gui);
        reloadFile(debug);

        buildCharsetFilter();
        this.configVersion = plugin.getConfig().getString("config-version", "2");
        this.lastReload = System.currentTimeMillis();

        // ⚠️ 必须在读取任何存储设置**之前**完成迁移
        migrateLegacyStorage();

        warnMisconfiguration();
    }

    /**
     * 把旧版（单 config.yml）里的 {@code storage:} 段迁移到 storage.yml。
     *
     * <h3>为什么必须做</h3>
     * 2.0.0 把配置拆成了多个文件。老用户的 {@code config.yml} 里是这样：
     * <pre>
     * storage:
     *   type: mysql
     *   mysql:
     *     host: 127.0.0.1
     * </pre>
     * 而新文件 {@code storage.yml} 的默认值是 <b>sqlite</b>。
     * 如果不迁移，插件会悄悄换成 SQLite，去读一个全新的空库，
     * 玩家看到的就是"昵称数据全没了"——而数据其实还在 MySQL 里。
     *
     * <h3>只在什么时候迁移</h3>
     * 仅当 {@code storage.yml} 是**本次启动新建**的。
     * 用户已经有这个文件时绝不覆盖，避免改坏他调好的参数。
     */
    private void migrateLegacyStorage() {
        if (storageMigrated) {
            return;
        }
        storageMigrated = true;

        try {
            if (storage == null || !storage.isCreatedNew()) {
                return;
            }
            // 旧配置段：config.yml 里的 storage:
            ConfigurationSection legacy =
                    plugin.getConfig().getConfigurationSection("storage");
            if (legacy == null) {
                return;
            }

            String type = legacy.getString("type", "").trim().toLowerCase();
            if (!"sqlite".equals(type) && !"mysql".equals(type)) {
                // 旧配置里没有可用的存储类型，保持默认
                return;
            }

            storage.setAndSave("storage-type", type);

            // SQLite 文件名
            String sqliteFile = legacy.getString("sqlite.file", "");
            if (sqliteFile != null && !sqliteFile.trim().isEmpty()) {
                storage.setAndSave("sqlite.file", sqliteFile.trim());
            }

            // MySQL 连接参数
            ConfigurationSection mysql = legacy.getConfigurationSection("mysql");
            if (mysql != null) {
                for (String key : new String[]{"host", "port", "database",
                        "username", "password", "table-prefix", "use-ssl",
                        "server-timezone", "pool-size", "connection-timeout"}) {
                    if (mysql.contains(key)) {
                        storage.setAndSave("mysql." + key, mysql.get(key));
                    }
                }
            }

            // 重新读取已迁移的值
            storage.load();

            plugin.getLogger().info("已从旧 config.yml 迁移存储设置 -> storage-type: "
                    + type);
            if ("mysql".equals(type)) {
                plugin.getLogger().info("继续使用 MySQL（"
                        + storage.getString("mysql.host", "?") + ":"
                        + storage.getInt("mysql.port", 3306) + "/"
                        + storage.getString("mysql.database", "?") + "）");
            }
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "迁移旧存储配置失败", t);
        }
    }

    /** 重新加载单个配置文件。 */
    private void reloadFile(FileConfig fc) {
        if (fc == null) {
            return;
        }
        try {
            fc.load();
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "重载 " + fc.getFileName() + " 失败", t);
        }
    }

    public long getLastReload() {
        return lastReload;
    }

    public String getConfigVersion() {
        return configVersion;
    }

    // ==========================================================
    //  一、字符集（第 3 条）
    // ==========================================================

    /**
     * 构建字符集过滤器。
     *
     * <p>遍历配置中所有字符类别的开关与区间定义，编译成高效的校验器。
     */
    private void buildCharsetFilter() {
        CharsetFilter.Builder builder = new CharsetFilter.Builder();
        ConfigurationSection cs = validation.getSection("charset");

        if (cs != null) {
            for (String category : cs.getKeys(false)) {
                // extra-chars 单独处理
                if ("extra-chars".equalsIgnoreCase(category)) {
                    continue;
                }
                ConfigurationSection cat = cs.getConfigurationSection(category);
                if (cat == null) {
                    continue;
                }
                if (!cat.getBoolean("enabled", false)) {
                    continue;
                }
                List<String> ranges = cat.getStringList("ranges");
                for (String range : ranges) {
                    builder.addRangeSpec(range);
                }
                Debug.log(Debug.VALIDATE, "已启用字符类别: {} ({} 条区间)",
                        category, ranges.size());
            }

            // 额外字符始终生效
            builder.addExtraChars(cs.getString("extra-chars", ""));
        } else {
            // 配置缺失时使用内置默认：中文简繁
            applyFallbackChinese(builder);
        }

        // 硬性禁止字符
        builder.addForbiddenChars(getForbiddenChars());

        charsetFilter = builder.build();

        Debug.log(Debug.VALIDATE, "字符集编译完成，共 {} 个允许区间", charsetFilter.rangeCount());
    }

    /** 配置缺失时的兜底字符集：中文简繁。 */
    private void applyFallbackChinese(CharsetFilter.Builder builder) {
        builder.addRangeSpec("\\u4E00-\\u9FFF");
        builder.addRangeSpec("\\u3400-\\u4DBF");
        builder.addRangeSpec("\\uF900-\\uFAFF");
        builder.addRangeSpec("\\u3000-\\u303F");
        builder.addRangeSpec("\\u20000-\\u2A6DF");
        plugin.getLogger().warning("未找到 validation.charset 配置，已使用内置中文默认字符集");
    }

    /** 获取编译好的字符集过滤器。 */
    public CharsetFilter getCharsetFilter() {
        if (charsetFilter == null) {
            buildCharsetFilter();
        }
        return charsetFilter;
    }

    /** 额外禁止的字符（字符串形式）。 */
    public String getForbiddenChars() {
        // 花括号必须在默认黑名单中：昵称若含 {}，会在消息占位符替换时被二次展开
        return val().getString("forbidden.chars", "&§<>\"'\\/|{}");
    }

    /** 是否禁止在昵称中使用颜色代码。 */
    public boolean isBlockColorCodes() {
        return val().getBoolean("forbidden.color-codes", true);
    }

    public int getMinLength() {
        return Math.max(1, val().getInt("length.min", 1));
    }

    public int getMaxLength() {
        int max = val().getInt("length.max", 10);
        // 防止配置错误导致上限比下限还小
        return Math.max(getMinLength(), max);
    }

    public boolean isBlockSameAsRealname() {
        return val().getBoolean("forbidden.same-as-realname", true);
    }

    /** 禁止词汇列表。 */
    public List<String> getBlockedWords() {
        List<String> list = val().getStringList("forbidden.blocked-words");
        return list == null ? new ArrayList<String>() : list;
    }

    // ---- 颜色权限 ----

    public boolean isColorPermissionRequired() {
        return val().getBoolean("forbidden.color.require-permission", false);
    }

    public String getColorPermission() {
        return val().getString("forbidden.color.permission", "ncnick.color");
    }

    /**
     * 玩家可见的颜色种类上限。
     *
     * @return "all" 或数字字符串
     */
    public String getAvailableColors() {
        Object v = val().get("forbidden.color.available-colors", "all");
        return v == null ? "all" : String.valueOf(v);
    }

    // ==========================================================
    //  二、唯一性（第 10 条）
    // ==========================================================

    public boolean isUniqueEnabled() {
        return val().getBoolean("uniqueness.enabled", true);
    }

    public boolean isUniqueIgnoreColors() {
        return val().getBoolean("uniqueness.ignore-color-codes", true);
    }

    public boolean isUniqueIgnoreCase() {
        return val().getBoolean("uniqueness.ignore-case", true);
    }

    public boolean isUniqueIgnoreSpaces() {
        return val().getBoolean("uniqueness.ignore-spaces", false);
    }

    // ==========================================================
    //  三、模式与价格
    // ==========================================================

    public boolean isPurchaseEnabled() {
        return eco().getBoolean("purchase.enabled", true);
    }

    public boolean isPermissionModeEnabled() {
        return eco().getBoolean("permission-mode.enabled", true);
    }

    public String getPermissionNode() {
        return eco().getString("permission-mode.permission", "ncnick.use");
    }

    public boolean isRevokeOnPermissionLost() {
        return eco().getBoolean("permission-mode.revoke-on-permission-lost", true);
    }

    /**
     * 指定商品的支付方式：{@code coins} / {@code points} / {@code both}。
     *
     * <p><b>昵称与颜色各自独立配置</b>：
     * <pre>
     * purchase:
     *   nickname:
     *     currency: coins     ← 昵称只收金币
     *   color:
     *     currency: both      ← 颜色两种都收
     * </pre>
     *
     * <p>早期版本只有一个全局 {@code purchase.currency}，
     * 昵称和颜色被迫共用，无法实现"昵称收金币、颜色收点券"这类组合。
     *
     * @param product 商品类型
     * @return 规范化后的取值，非法值回退为 both
     */
    public String getCurrency(com.ncnick.model.ProductType product) {
        String key = (product == null ? "nickname" : product.getConfigKey())
                + ".currency";
        return normalizeCurrency(eco().getString("purchase." + key, "both"));
    }

    /**
     * 向后兼容：不指定商品时返回昵称的支付方式。
     *
     * @deprecated 请改用 {@link #getCurrency(com.ncnick.model.ProductType)}，
     *             以便区分昵称与颜色。
     */
    @Deprecated
    public String getCurrency() {
        return getCurrency(com.ncnick.model.ProductType.NICKNAME);
    }

    /** 规范化支付方式取值。 */
    private static String normalizeCurrency(String c) {
        if (c == null) {
            return "both";
        }
        String s = c.trim().toLowerCase();
        if (!"coins".equals(s) && !"points".equals(s) && !"both".equals(s)) {
            return "both";
        }
        return s;
    }

    // ---- 昵称价格 ----

    public double getNicknamePriceCoins() {
        return eco().getDouble("purchase.nickname.price-coins", 10000.0D);
    }

    public int getNicknamePricePoints() {
        return eco().getInt("purchase.nickname.price-points", 1000);
    }

    // ---- 额外费用 ----

    public boolean isExtraFeeEnabled() {
        return eco().getBoolean("purchase.nickname.extra-fee.enabled", true);
    }

    public int getExtraFeeThreshold() {
        return eco().getInt("purchase.nickname.extra-fee.threshold", 5);
    }

    public double getExtraFeePerCharCoins() {
        return eco().getDouble("purchase.nickname.extra-fee.per-char-coins", 2000.0D);
    }

    public int getExtraFeePerCharPoints() {
        return eco().getInt("purchase.nickname.extra-fee.per-char-points", 200);
    }

    // ---- 颜色价格（第 6 条） ----

    /** 纯色 - 金币价 */
    public double getSolidPriceCoins() {
        return eco().getDouble("purchase.color.solid.price-coins", 5000.0D);
    }

    /** 纯色 - 点券价 */
    public int getSolidPricePoints() {
        return eco().getInt("purchase.color.solid.price-points", 500);
    }

    /** 渐变 - 金币价（默认比纯色贵） */
    public double getGradientPriceCoins() {
        return eco().getDouble("purchase.color.gradient.price-coins", 15000.0D);
    }

    /** 渐变 - 点券价（默认比纯色贵） */
    public int getGradientPricePoints() {
        return eco().getInt("purchase.color.gradient.price-points", 1500);
    }

    public boolean isChargeOnModify() {
        return eco().getBoolean("purchase.charge-on-modify", true);
    }

    // ==========================================================
    //  四、显示格式（第 2 条：占位符）
    // ==========================================================

    /** 自定义占位符字符串，如 "#"。 */
    public String getCustomPrefix() {
        String p = disp().getString("placeholder.custom-prefix", "#");
        return p == null ? "" : p;
    }

    public boolean isPrefixWhenNoNickname() {
        return disp().getBoolean("placeholder.show-when-no-nickname", false);
    }

    /** 聊天栏显示格式。 */
    public String getChatFormat() {
        return disp().getString("format.chat", "{prefix}{nickname}");
    }

    /** TAB 显示格式。 */
    public String getTabFormat() {
        // 默认只显示昵称，不重复真实 ID（模式①）。
        // 需要 Steve#小明 请改成 "{name}{prefix}{nickname}"。
        return disp().getString("format.tab", "{prefix}{nickname}");
    }

    /** 头顶显示格式。 */
    public String getNametagFormat() {
        // 默认不写 {name}：原版头顶本来就渲染实体名，
        // 再拼一次会变成 "SteveSteve#小明"。
        return disp().getString("format.nametag", "{prefix}{nickname}");
    }

    // ---- 聊天 ----

    public String getNameWrapper() {
        return disp().getString("options.name-wrapper", "<{name}>");
    }

    public boolean isBoldName() {
        return disp().getBoolean("options.bold-name", false);
    }

    // ==========================================================
    //  五、实验性功能（第 8 条）
    // ==========================================================

    public boolean isExperimentalEnabled() {
        return exp().getBoolean("enabled", false);
    }

    public boolean isExpTabEnabled() {
        return isExperimentalEnabled()
                && exp().getBoolean("tab.enabled", false);
    }

    public boolean isExpNametagEnabled() {
        return isExperimentalEnabled()
                && exp().getBoolean("nametag.enabled", false);
    }

    public boolean isExpChatEnabled() {
        return isExperimentalEnabled()
                && exp().getBoolean("chat.enabled", false);
    }

    /** TAB 显示模式：full（仅自定义）/ name-nickname（原ID+自定义） */
    public String getExpTabMode() {
        return normalizeMode(exp().getString("modes.tab", "name-nickname"));
    }

    public String getExpNametagMode() {
        return normalizeMode(exp().getString("modes.nametag", "name-nickname"));
    }

    public String getExpChatMode() {
        return normalizeMode(exp().getString("modes.chat", "name-nickname"));
    }

    // 说明：原设计的 experimental.nametag.hide-vanilla 选项已移除。
    // 因为原版头顶名字中的「玩家实体名」无法通过 Bukkit 记分板 API 移除，
    // 该开关无论如何设置都不会生效。留着一个无效开关只会误导管理员，
    // 因此改为在 config.yml 中以注释说明该平台限制。

    /** 数据包模式：auto / packetevents / none */
    public String getPacketMode() {
        String m = exp().getString("packet-mode", "auto");
        if (m == null) {
            return "auto";
        }
        String s = m.trim().toLowerCase();
        if (!"auto".equals(s) && !"packetevents".equals(s) && !"none".equals(s)) {
            return "auto";
        }
        return s;
    }

    public int getPacketPriority() {
        return exp().getInt("packet-priority", 100);
    }

    /**
     * 归一化显示模式。
     *
     * @return "full" 或 "name-nickname"
     */
    private String normalizeMode(String raw) {
        if (raw == null) {
            return "name-nickname";
        }
        String s = raw.trim().toLowerCase();
        // 兼容多种写法
        if ("full".equals(s) || "custom".equals(s) || "nickname-only".equals(s)
                || "1".equals(s) || "only".equals(s)) {
            return "full";
        }
        return "name-nickname";
    }

    // ==========================================================
    //  六、存储与驱动
    // ==========================================================

    /** 存储类型：sqlite / mysql */
    public String getStorageType() {
        String t = sto().getString("storage-type", "sqlite");
        if (t == null) {
            return "sqlite";
        }
        String s = t.trim().toLowerCase();
        if (!"sqlite".equals(s) && !"mysql".equals(s)) {
            return "sqlite";
        }
        return s;
    }

    /**
     * MySQL 不可用时是否自动改用 SQLite。
     *
     * <p><b>默认 false</b>，因为静默回退会凭空建一个空的 data.db，
     * 让玩家以为"昵称数据全丢了"（数据其实还在 MySQL 里），
     * 且回退期间的新改动会写进 SQLite，修好 MySQL 后又消失。
     * 宁可停用插件、把失败原因打清楚，也不要产生错误数据。
     */
    public boolean isFallbackToSqlite() {
        return sto().getBoolean("fallback-to-sqlite", false);
    }

    public boolean isMysqlEnabled() {        return "mysql".equals(getStorageType());
    }

    public String getMysqlHost() {
        return sto().getString("mysql.host", "127.0.0.1");
    }

    public int getMysqlPort() {
        int p = sto().getInt("mysql.port", 3306);
        // 端口合法性校验
        return (p < 1 || p > 65535) ? 3306 : p;
    }

    public String getMysqlDatabase() {
        return sto().getString("mysql.database", "minecraft");
    }

    public String getMysqlUsername() {
        return sto().getString("mysql.username", "root");
    }

    public String getMysqlPassword() {
        return sto().getString("mysql.password", "");
    }

    /**
     * 表名前缀。
     *
     * <p>安全关键：只保留字母、数字、下划线，防止 SQL 注入。
     * 这是防御纵深的一环——即便配置被恶意篡改，也无法注入 SQL。
     */
    public String getMysqlTablePrefix() {
        String raw = sto().getString("mysql.table-prefix", "ncnick_");
        if (raw == null || raw.isEmpty()) {
            return "ncnick_";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            }
        }
        String cleaned = sb.toString();
        if (cleaned.isEmpty()) {
            return "ncnick_";
        }
        // 防止前缀以数字开头导致 SQL 语法错误
        if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "t" + cleaned;
        }
        // 长度上限：超过时明确报错并回退，而非静默截断。
        // 静默截断会让 "aaa...x" 与 "aaa...y" 变成同一个前缀，两套配置共用一张表。
        if (cleaned.length() > 24) {
            plugin.getLogger().severe("表名前缀过长（超过 24 字符），"
                    + "已回退为默认值 ncnick_。请修改后执行 /nca reload。原值: " + raw);
            return "ncnick_";
        }
        if (!cleaned.equals(raw)) {
            plugin.getLogger().warning("表名前缀包含非法字符，已清理为: " + cleaned);
        }
        return cleaned;
    }

    public boolean isMysqlUseSsl() {
        return sto().getBoolean("mysql.use-ssl", false);
    }

    public String getMysqlTimeZone() {
        return sto().getString("mysql.server-timezone", "Asia/Shanghai");
    }

    public int getMysqlPoolSize() {
        int n = sto().getInt("mysql.pool-size", 6);
        return Math.max(1, Math.min(32, n));
    }

    public long getMysqlConnectionTimeout() {
        long t = sto().getLong("mysql.connection-timeout", 8000L);
        return Math.max(1000L, Math.min(60000L, t));
    }

    public int getSyncInterval() {
        int n = sto().getInt("mysql.sync-interval", 5);
        return Math.max(1, Math.min(600, n));
    }

    /**
     * SQLite 数据库文件名。
     *
     * <p>安全关键：必须防止路径穿越。攻击者若能把文件写到任意位置
     * （例如 {@code C:/Windows/Temp/x.db} 或 UNC 路径），配合其他漏洞可扩大影响。
     * 因此这里做严格白名单校验，并二次确认解析后的绝对路径仍在数据目录内。
     */
    public String getSqliteFile() {
        String f = sto().getString("sqlite.file", "data.db");
        if (f == null || f.trim().isEmpty()) {
            return "data.db";
        }

        String cleaned = f.trim().replace("\\", "/");

        // ---- 白名单：只允许 字母/数字/点/下划线/短横线，可含一层子目录 ----
        boolean patternOk = cleaned.matches("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");
        if (!patternOk
                || cleaned.contains("..")
                || cleaned.contains(":")
                || cleaned.startsWith("/")
                || cleaned.startsWith("~")
                || cleaned.endsWith("/")) {
            plugin.getLogger().warning("SQLite 文件名不合法，已回退为 data.db: " + f);
            return "data.db";
        }

        // ---- 二次确认：解析后的绝对路径必须仍在插件数据目录内 ----
        try {
            File base = plugin.getDataFolder().getAbsoluteFile();
            File resolved = new File(base, cleaned).getAbsoluteFile();
            String basePath = base.getPath();
            String resolvedPath = resolved.getPath();
            if (!resolvedPath.equals(basePath)
                    && !resolvedPath.startsWith(basePath + File.separator)) {
                plugin.getLogger().warning("SQLite 路径越界，已回退为 data.db: " + f);
                return "data.db";
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("SQLite 路径校验失败，已回退为 data.db: "
                    + t.getMessage());
            return "data.db";
        }

        return cleaned;
    }

    // ---- 驱动下载（第 4、5 条） ----

    public boolean isDriverAutoDownload() {
        return sto().getBoolean("driver.auto-download", true);
    }

    public boolean isDriverWarnRestart() {
        return sto().getBoolean("driver.warn-restart", true);
    }

    public int getDriverTimeout() {
        int t = sto().getInt("driver.timeout", 15000);
        return Math.max(3000, Math.min(120000, t));
    }

    public int getDriverRetries() {
        int r = sto().getInt("driver.retries", 2);
        return Math.max(1, Math.min(5, r));
    }

    public long getDriverMinFileSize() {
        return sto().getLong("driver.min-file-size", 10240L);
    }

    // ==========================================================
    //  七、性能
    // ==========================================================

    public int getPermissionCheckInterval() {
        return sto().getInt("performance.permission-check-interval", 60);
    }

    public int getCacheRefreshTicks() {
        int t = sto().getInt("performance.cache-refresh-ticks", 100);
        return Math.max(20, t);
    }

    public int getWriteQueueLimit() {
        int n = sto().getInt("performance.write-queue-limit", 500);
        return Math.max(50, n);
    }

    // ==========================================================
    //  八、调试（第 12 条）
    // ==========================================================

    public boolean isDebugEnabled() {
        return dbg().getBoolean("enabled", false);
    }

    public boolean isDebugLogSql() {
        return dbg().getBoolean("log-sql", false);
    }

    public boolean isDebugLogCache() {
        return dbg().getBoolean("log-cache", false);
    }

    public boolean isDebugLogPacket() {
        return dbg().getBoolean("log-packet", false);
    }

    public boolean isDebugLogValidation() {
        return dbg().getBoolean("log-validation", false);
    }

    // ==========================================================
    //  九、界面
    // ==========================================================

    public String getGuiMainTitle() {
        return gui().getString("titles.main", "&8昵称设置");
    }

    public String getGuiColorTitle() {
        return gui().getString("titles.color", "&8昵称颜色");
    }

    public boolean isGuiFillEmpty() {
        return gui().getBoolean("appearance.fill-empty", true);
    }

    public String getGuiInputTitle() {
        return gui().getString("titles.input", "&8输入昵称");
    }

    public String getGuiInputPlaceholder() {
        return gui().getString("input.placeholder", "<在此输入昵称>");
    }

    // ==========================================================
    //  自检
    // ==========================================================

    /**
     * 检查明显的配置错误并在控制台给出可操作的警告。
     */
    private void warnMisconfiguration() {
        // 字符集为空会让所有玩家都无法设置昵称
        CharsetFilter filter = getCharsetFilter();
        if (filter.isEmpty()) {
            plugin.getLogger().warning("字符集为空！所有玩家都无法设置昵称。");
            plugin.getLogger().warning("请在 config.yml 的 validation.charset 中"
                    + "至少启用一个字符类别（如 simplified-chinese）。");
        }

        // 价格设置检查
        if (isPurchaseEnabled() && "coins".equals(getCurrency())
                && getNicknamePriceCoins() <= 0) {
            plugin.getLogger().warning("昵称金币价格为 0，购买模式将始终成功。");
        }

        // 渐变应比纯色贵（用户明确要求）
        if (isPurchaseEnabled()
                && getGradientPriceCoins() < getSolidPriceCoins()) {
            plugin.getLogger().info("提示：渐变价格低于纯色价格，"
                    + "如需符合默认设计（渐变更贵）请调整 modes.purchase.color。");
        }

        // 实验性功能提示
        if (isExperimentalEnabled()) {
            plugin.getLogger().warning("实验性功能已启用（直接替换原版显示）。"
                    + "如与其他显示类插件冲突，请关闭 experimental.enabled。");
        }

        warnLegacyStorageIgnored();
    }

    /**
     * 提示"改了旧位置但不生效"的存储配置。
     *
     * <h3>真实场景</h3>
     * 2.0.0 把存储设置从 {@code config.yml} 的 {@code storage:} 段
     * 搬到了独立的 {@code storage.yml}（键名也从 {@code type} 变成
     * {@code storage-type}）。
     *
     * <p>升级后 {@code config.yml} 通常还是旧内容，用户很容易去那里改，
     * 改完发现"没生效"——因为插件已经只读 {@code storage.yml} 了。
     * 这条提示把"你改的地方已经不生效、应该改哪里"直接说出来。
     */
    private void warnLegacyStorageIgnored() {
        try {
            ConfigurationSection legacy =
                    plugin.getConfig().getConfigurationSection("storage");
            if (legacy == null) {
                return;
            }
            String legacyType = legacy.getString("type", "");
            if (legacyType == null) {
                return;
            }
            legacyType = legacyType.trim().toLowerCase();
            if (!"mysql".equals(legacyType) && !"sqlite".equals(legacyType)) {
                return;
            }
            String actual = getStorageType();
            if (legacyType.equals(actual)) {
                return;
            }
            plugin.getLogger().warning("config.yml 里的 storage.type: "
                    + legacyType + " 已不再生效（2.0.0 起改到 storage.yml）。");
            plugin.getLogger().warning("当前实际使用: " + actual
                    + "。请修改 storage.yml 的 storage-type，然后重启服务器。");
        } catch (Throwable ignored) {
            // 提示失败不影响启动
        }
    }

    /**
     * 生成配置摘要，供调试使用。
     */
    public Map<String, String> summary() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("storage", getStorageType());
        m.put("currency", getCurrency());
        m.put("min-max", getMinLength() + "-" + getMaxLength());
        m.put("charset-ranges", String.valueOf(getCharsetFilter().rangeCount()));
        m.put("unique", String.valueOf(isUniqueEnabled()));
        m.put("debug", String.valueOf(isDebugEnabled()));
        m.put("experimental", String.valueOf(isExperimentalEnabled()));
        return m;
    }

    /** 便捷：把文本送给颜色转换。 */
    public String colorize(String text) {
        return Msg.color(text);
    }
}
