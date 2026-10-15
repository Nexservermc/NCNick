package com.ncnick;

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

    // ---- 缓存解析结果，避免每次调用都重新解析 ----
    private CharsetFilter charsetFilter;
    private String configVersion;
    private long lastReload;

    public ConfigManager(NCNick plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * 重新解析所有派生配置。
     * 由 {@link NCNick#reloadPlugin()} 调用。
     */
    public void reload() {
        buildCharsetFilter();
        this.configVersion = plugin.getConfig().getString("config-version", "1");
        this.lastReload = System.currentTimeMillis();
        warnMisconfiguration();
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
        ConfigurationSection cs = plugin.getConfig()
                .getConfigurationSection("validation.charset");

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
        return plugin.getConfig().getString("validation.forbidden-chars", "&§<>\"'\\/|{}");
    }

    /** 是否禁止在昵称中使用颜色代码。 */
    public boolean isBlockColorCodes() {
        return plugin.getConfig().getBoolean("validation.block-color-codes", true);
    }

    public int getMinLength() {
        return Math.max(1, plugin.getConfig().getInt("validation.min-length", 1));
    }

    public int getMaxLength() {
        int max = plugin.getConfig().getInt("validation.max-length", 10);
        // 防止配置错误导致上限比下限还小
        return Math.max(getMinLength(), max);
    }

    public boolean isBlockSameAsRealname() {
        return plugin.getConfig().getBoolean("validation.block-same-as-realname", true);
    }

    /** 禁止词汇列表。 */
    public List<String> getBlockedWords() {
        List<String> list = plugin.getConfig().getStringList("validation.blocked-words");
        return list == null ? new ArrayList<String>() : list;
    }

    // ---- 颜色权限 ----

    public boolean isColorPermissionRequired() {
        return plugin.getConfig().getBoolean("validation.color.require-permission", false);
    }

    public String getColorPermission() {
        return plugin.getConfig().getString("validation.color.permission", "ncnick.color");
    }

    /**
     * 玩家可见的颜色种类上限。
     *
     * @return "all" 或数字字符串
     */
    public String getAvailableColors() {
        Object v = plugin.getConfig().get("validation.color.available-colors", "all");
        return v == null ? "all" : String.valueOf(v);
    }

    // ==========================================================
    //  二、唯一性（第 10 条）
    // ==========================================================

    public boolean isUniqueEnabled() {
        return plugin.getConfig().getBoolean("uniqueness.enabled", true);
    }

    public boolean isUniqueIgnoreColors() {
        return plugin.getConfig().getBoolean("uniqueness.ignore-color-codes", true);
    }

    public boolean isUniqueIgnoreCase() {
        return plugin.getConfig().getBoolean("uniqueness.ignore-case", true);
    }

    public boolean isUniqueIgnoreSpaces() {
        return plugin.getConfig().getBoolean("uniqueness.ignore-spaces", false);
    }

    // ==========================================================
    //  三、模式与价格
    // ==========================================================

    public boolean isPurchaseEnabled() {
        return plugin.getConfig().getBoolean("modes.purchase.enabled", true);
    }

    public boolean isPermissionModeEnabled() {
        return plugin.getConfig().getBoolean("modes.permission.enabled", true);
    }

    public String getPermissionNode() {
        return plugin.getConfig().getString("modes.permission.permission", "ncnick.use");
    }

    public boolean isRevokeOnPermissionLost() {
        return plugin.getConfig().getBoolean("modes.permission.revoke-on-permission-lost", true);
    }

    /**
     * 支付方式：coins / points / both
     */
    public String getCurrency() {
        String c = plugin.getConfig().getString("modes.purchase.currency", "both");
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
        return plugin.getConfig().getDouble("modes.purchase.nickname.price-coins", 10000.0D);
    }

    public int getNicknamePricePoints() {
        return plugin.getConfig().getInt("modes.purchase.nickname.price-points", 1000);
    }

    // ---- 额外费用 ----

    public boolean isExtraFeeEnabled() {
        return plugin.getConfig()
                .getBoolean("modes.purchase.nickname.extra-fee.enabled", true);
    }

    public int getExtraFeeThreshold() {
        return plugin.getConfig()
                .getInt("modes.purchase.nickname.extra-fee.threshold", 5);
    }

    public double getExtraFeePerCharCoins() {
        return plugin.getConfig()
                .getDouble("modes.purchase.nickname.extra-fee.per-char-coins", 2000.0D);
    }

    public int getExtraFeePerCharPoints() {
        return plugin.getConfig()
                .getInt("modes.purchase.nickname.extra-fee.per-char-points", 200);
    }

    // ---- 颜色价格（第 6 条） ----

    /** 纯色 - 金币价 */
    public double getSolidPriceCoins() {
        return plugin.getConfig()
                .getDouble("modes.purchase.color.solid.price-coins", 5000.0D);
    }

    /** 纯色 - 点券价 */
    public int getSolidPricePoints() {
        return plugin.getConfig()
                .getInt("modes.purchase.color.solid.price-points", 500);
    }

    /** 渐变 - 金币价（默认比纯色贵） */
    public double getGradientPriceCoins() {
        return plugin.getConfig()
                .getDouble("modes.purchase.color.gradient.price-coins", 15000.0D);
    }

    /** 渐变 - 点券价（默认比纯色贵） */
    public int getGradientPricePoints() {
        return plugin.getConfig()
                .getInt("modes.purchase.color.gradient.price-points", 1500);
    }

    public boolean isChargeOnModify() {
        return plugin.getConfig().getBoolean("modes.purchase.charge-on-modify", true);
    }

    // ==========================================================
    //  四、显示格式（第 2 条：占位符）
    // ==========================================================

    /** 自定义占位符字符串，如 "#"。 */
    public String getCustomPrefix() {
        String p = plugin.getConfig().getString("display.custom-prefix", "#");
        return p == null ? "" : p;
    }

    public boolean isPrefixWhenNoNickname() {
        return plugin.getConfig().getBoolean("display.prefix-when-no-nickname", false);
    }

    /** 聊天栏显示格式。 */
    public String getChatFormat() {
        return plugin.getConfig().getString("display.chat-format", "{prefix}{nickname}");
    }

    /** TAB 显示格式。 */
    public String getTabFormat() {
        return plugin.getConfig().getString("display.tab-format", "{name}{prefix}{nickname}");
    }

    /** 头顶显示格式。 */
    public String getNametagFormat() {
        return plugin.getConfig().getString("display.nametag-format", "{name}{prefix}{nickname}");
    }

    public boolean isFallbackToVanilla() {
        return plugin.getConfig().getBoolean("display.fallback-to-vanilla", true);
    }

    // ---- 聊天 ----

    public String getNameWrapper() {
        return plugin.getConfig().getString("chat.name-wrapper", "<{name}>");
    }

    public boolean isBoldName() {
        return plugin.getConfig().getBoolean("chat.bold-name", false);
    }

    // ==========================================================
    //  五、实验性功能（第 8 条）
    // ==========================================================

    public boolean isExperimentalEnabled() {
        return plugin.getConfig().getBoolean("experimental.enabled", false);
    }

    public boolean isExpTabEnabled() {
        return isExperimentalEnabled()
                && plugin.getConfig().getBoolean("experimental.tab.enabled", false);
    }

    public boolean isExpNametagEnabled() {
        return isExperimentalEnabled()
                && plugin.getConfig().getBoolean("experimental.nametag.enabled", false);
    }

    public boolean isExpChatEnabled() {
        return isExperimentalEnabled()
                && plugin.getConfig().getBoolean("experimental.chat.enabled", false);
    }

    /** TAB 显示模式：full（仅自定义）/ name-nickname（原ID+自定义） */
    public String getExpTabMode() {
        return normalizeMode(plugin.getConfig()
                .getString("experimental.tab.mode", "name-nickname"));
    }

    public String getExpNametagMode() {
        return normalizeMode(plugin.getConfig()
                .getString("experimental.nametag.mode", "name-nickname"));
    }

    public String getExpChatMode() {
        return normalizeMode(plugin.getConfig()
                .getString("experimental.chat.mode", "name-nickname"));
    }

    // 说明：原设计的 experimental.nametag.hide-vanilla 选项已移除。
    // 因为原版头顶名字中的「玩家实体名」无法通过 Bukkit 记分板 API 移除，
    // 该开关无论如何设置都不会生效。留着一个无效开关只会误导管理员，
    // 因此改为在 config.yml 中以注释说明该平台限制。

    /** 数据包模式：auto / packetevents / none */
    public String getPacketMode() {
        String m = plugin.getConfig().getString("experimental.packet-mode", "auto");
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
        return plugin.getConfig().getInt("experimental.packet-priority", 100);
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
        String t = plugin.getConfig().getString("storage.type", "sqlite");
        if (t == null) {
            return "sqlite";
        }
        String s = t.trim().toLowerCase();
        if (!"sqlite".equals(s) && !"mysql".equals(s)) {
            return "sqlite";
        }
        return s;
    }

    public boolean isMysqlEnabled() {
        return "mysql".equals(getStorageType());
    }

    public String getMysqlHost() {
        return plugin.getConfig().getString("storage.mysql.host", "127.0.0.1");
    }

    public int getMysqlPort() {
        int p = plugin.getConfig().getInt("storage.mysql.port", 3306);
        // 端口合法性校验
        return (p < 1 || p > 65535) ? 3306 : p;
    }

    public String getMysqlDatabase() {
        return plugin.getConfig().getString("storage.mysql.database", "minecraft");
    }

    public String getMysqlUsername() {
        return plugin.getConfig().getString("storage.mysql.username", "root");
    }

    public String getMysqlPassword() {
        return plugin.getConfig().getString("storage.mysql.password", "");
    }

    /**
     * 表名前缀。
     *
     * <p>安全关键：只保留字母、数字、下划线，防止 SQL 注入。
     * 这是防御纵深的一环——即便配置被恶意篡改，也无法注入 SQL。
     */
    public String getMysqlTablePrefix() {
        String raw = plugin.getConfig().getString("storage.mysql.table-prefix", "ncnick_");
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
        return plugin.getConfig().getBoolean("storage.mysql.use-ssl", false);
    }

    public String getMysqlTimeZone() {
        return plugin.getConfig().getString("storage.mysql.server-timezone", "Asia/Shanghai");
    }

    public int getMysqlPoolSize() {
        int n = plugin.getConfig().getInt("storage.mysql.pool-size", 6);
        return Math.max(1, Math.min(32, n));
    }

    public long getMysqlConnectionTimeout() {
        long t = plugin.getConfig().getLong("storage.mysql.connection-timeout", 8000L);
        return Math.max(1000L, Math.min(60000L, t));
    }

    public int getSyncInterval() {
        int n = plugin.getConfig().getInt("storage.mysql.sync-interval", 5);
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
        String f = plugin.getConfig().getString("storage.sqlite.file", "data.db");
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
        return plugin.getConfig().getBoolean("driver.auto-download", true);
    }

    public boolean isDriverWarnRestart() {
        return plugin.getConfig().getBoolean("driver.warn-restart", true);
    }

    public int getDriverTimeout() {
        int t = plugin.getConfig().getInt("driver.timeout", 15000);
        return Math.max(3000, Math.min(120000, t));
    }

    public int getDriverRetries() {
        int r = plugin.getConfig().getInt("driver.retries", 2);
        return Math.max(1, Math.min(5, r));
    }

    public long getDriverMinFileSize() {
        return plugin.getConfig().getLong("driver.min-file-size", 10240L);
    }

    // ==========================================================
    //  七、性能
    // ==========================================================

    public int getPermissionCheckInterval() {
        return plugin.getConfig().getInt("performance.permission-check-interval", 60);
    }

    public int getCacheRefreshTicks() {
        int t = plugin.getConfig().getInt("performance.cache-refresh-ticks", 100);
        return Math.max(20, t);
    }

    public int getWriteQueueLimit() {
        int n = plugin.getConfig().getInt("performance.write-queue-limit", 500);
        return Math.max(50, n);
    }

    // ==========================================================
    //  八、调试（第 12 条）
    // ==========================================================

    public boolean isDebugEnabled() {
        return plugin.getConfig().getBoolean("debug.enabled", false);
    }

    public boolean isDebugLogSql() {
        return plugin.getConfig().getBoolean("debug.log-sql", false);
    }

    public boolean isDebugLogCache() {
        return plugin.getConfig().getBoolean("debug.log-cache", false);
    }

    public boolean isDebugLogPacket() {
        return plugin.getConfig().getBoolean("debug.log-packet", false);
    }

    public boolean isDebugLogValidation() {
        return plugin.getConfig().getBoolean("debug.log-validation", false);
    }

    // ==========================================================
    //  九、界面
    // ==========================================================

    public String getGuiMainTitle() {
        return plugin.getConfig().getString("gui.main-title", "&8昵称设置");
    }

    public String getGuiColorTitle() {
        return plugin.getConfig().getString("gui.color-title", "&8昵称颜色");
    }

    public boolean isGuiFillEmpty() {
        return plugin.getConfig().getBoolean("gui.fill-empty", true);
    }

    public String getGuiInputTitle() {
        return plugin.getConfig().getString("gui.input-title", "&8输入昵称");
    }

    public String getGuiInputPlaceholder() {
        return plugin.getConfig().getString("gui.input-placeholder", "<在此输入昵称>");
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
