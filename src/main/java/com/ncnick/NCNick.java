package com.ncnick;

import com.ncnick.command.AdminCommand;
import com.ncnick.command.PlayerCommand;
import com.ncnick.config.LangManager;
import com.ncnick.driver.DriverManagerImpl;
import com.ncnick.experimental.ExperimentalManager;
import com.ncnick.gui.GuiManager;
import com.ncnick.hook.PlaceholderHook;
import com.ncnick.hook.PlayerPointsHook;
import com.ncnick.hook.VaultHook;
import com.ncnick.listener.ChatListener;
import com.ncnick.listener.PlayerListener;
import com.ncnick.manager.DisplayManager;
import com.ncnick.manager.NickManager;
import com.ncnick.storage.MySQLStorage;
import com.ncnick.storage.SQLiteStorage;
import com.ncnick.storage.Storage;
import com.ncnick.task.PermissionCheckTask;
import com.ncnick.task.SyncTask;
import com.ncnick.util.Debug;
import com.ncnick.util.VersionUtil;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * NCNick 主类（第 9 条：简洁的控制台输出）。
 *
 * <p>作者：xiaotian_xt / Nice_Leo_
 */
public class NCNick extends JavaPlugin {

    private static NCNick instance;

    private ConfigManager configManager;
    private LangManager lang;
    private Storage storage;
    private DriverManagerImpl driverManager;
    private NickManager nickManager;
    private DisplayManager displayManager;
    private GuiManager guiManager;
    private ExperimentalManager experimentalManager;

    private VaultHook vaultHook;
    private PlayerPointsHook playerPointsHook;
    private PlaceholderHook placeholderHook;

    private SyncTask syncTask;
    private PermissionCheckTask permissionCheckTask;
    private com.ncnick.listener.ModernChatAdapter modernChatAdapter;

    private boolean storageReady = false;

    public static NCNick getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        long start = System.currentTimeMillis();

        // ---- 配置 ----
        saveDefaultConfig();
        configManager = new ConfigManager(this);

        // ---- 日志 ----
        Debug.setLogger(getLogger());
        com.ncnick.driver.DriverClassLoader.setLogger(getLogger());
        applyDebugConfig();

        // ---- 语言文件 ----
        lang = new LangManager(this);
        lang.load(getConfig().getString("language", "zh_CN"));

        // ---- 驱动管理（第 4、5 条） ----
        driverManager = new DriverManagerImpl(this);

        // ---- 存储 ----
        if (!initStorage()) {
            // 存储初始化失败：禁用插件，避免后续空指针
            getLogger().severe("存储初始化失败，插件将停用。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // ---- 经济钩子 ----
        vaultHook = new VaultHook(this);
        playerPointsHook = new PlayerPointsHook(this);

        // ---- 显示与业务 ----
        nickManager = new NickManager(this);
        displayManager = new DisplayManager(this);
        guiManager = new GuiManager(this);
        experimentalManager = new ExperimentalManager(this);

        // ---- 命令 ----
        registerCommands();

        // ---- 事件 ----
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ChatListener(this), this);

        // 1.19+ Paper 聊天事件适配（反射注册，仅在支持时生效）
        try {
            modernChatAdapter = new com.ncnick.listener.ModernChatAdapter(this);
            modernChatAdapter.register();
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "注册 1.19+ 聊天适配失败", t);
        }

        // ---- 经济与变量插件初始化（延迟一 tick，等依赖插件就绪） ----
        //
        // 注意：启动摘要在 setupHooks() 之后才打印。
        // 早期版本在这里就立刻打印摘要，而 setupHooks 要等到下一 tick，
        // 导致摘要里读到的是"钩子尚未初始化"的状态 ——
        // 即使服务器装了 Vault + 经济插件，也永远显示
        // "支付 仅权限模式（未装经济插件）"。
        final long startupCost = System.currentTimeMillis() - start;
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                setupHooks();
                // ---- 启动摘要（第 9 条：简洁输出）----
                // 必须放在钩子初始化之后，否则支付方式等信息是错的
                printStartupSummary(System.currentTimeMillis() - start);
                Debug.log(Debug.GENERAL, "钩子初始化 + 摘要输出耗时 {}ms", startupCost);
            }
        });

        // ---- 任务 ----
        startTasks();
    }

    @Override
    public void onDisable() {
        // 注销 PlaceholderAPI 扩展（避免残留注册状态）
        try {
            if (placeholderHook != null) {
                placeholderHook.unregister();
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        // 停止任务
        try {
            if (syncTask != null) {
                syncTask.cancel();
            }
            if (permissionCheckTask != null) {
                permissionCheckTask.cancel();
            }
            Bukkit.getScheduler().cancelTasks(this);
        } catch (Throwable ignored) {
            // 忽略
        }

        // 清理显示
        try {
            if (displayManager != null) {
                displayManager.cleanupAll();
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        // 清理 GUI 状态
        try {
            if (guiManager != null) {
                guiManager.shutdown();
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        // 关闭存储
        try {
            if (storage != null) {
                storage.close();
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        // 释放驱动文件句柄
        try {
            if (driverManager != null) {
                driverManager.close();
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        getLogger().info("NCNick 已卸载。");
    }

    // ==========================================================
    //  初始化
    // ==========================================================

    /**
     * 应用调试配置。
     */
    private void applyDebugConfig() {
        Debug.configure(
                configManager.isDebugEnabled(),
                configManager.isDebugLogSql(),
                configManager.isDebugLogCache(),
                configManager.isDebugLogPacket(),
                configManager.isDebugLogValidation());
    }

    /**
     * 初始化存储。
     *
     * <p>流程：
     * <ol>
     *   <li>预加载本地已有的驱动（不联网）</li>
     *   <li>若驱动缺失且允许自动下载，则异步下载</li>
     *   <li>创建存储实现并初始化</li>
     * </ol>
     *
     * @return 是否成功
     */
    private boolean initStorage() {
        String type = configManager.getStorageType();
        String driverKey = "mysql".equals(type) ? "mysql" : "sqlite";

        // 预加载本地驱动（不联网）
        driverManager.preload();

        // 确保所选存储的驱动可用
        boolean driverOk = ensureDriver(driverKey);

        if (!driverOk) {
            // MySQL 驱动拿不到时，若 SQLite 可用则回退，避免整个插件不可用
            if ("mysql".equals(type) && ensureDriver("sqlite")) {
                getLogger().warning("MySQL 驱动不可用，已回退到 SQLite 存储。");
                return createStorage("sqlite");
            }
            getLogger().severe("无法获取 " + driverKey + " 数据库驱动，插件将停用。");
            getLogger().severe("请手动下载驱动并放入: " + driverManager.libPath());
            return false;
        }

        return createStorage(type);
    }

    /**
     * 确保指定驱动可用（本地已有则直接用，否则按配置下载）。
     *
     * <p><b>判定标准是"本插件自己加载过"而非"JVM 里能看见"。</b>
     * 原因见 {@link com.ncnick.driver.DriverClassLoader#isSelfLoaded(String)}：
     * Paper 等服务端自带 MySQL 驱动，若按"能看见"判定，
     * {@code lib/} 空着也会被认为就绪，从而跳过下载。
     *
     * @param driverKey 驱动键名
     * @return 是否可用
     */
    private boolean ensureDriver(String driverKey) {
        if (isDriverSelfLoaded(driverKey)) {
            Debug.log(Debug.GENERAL, "驱动 {} 已由本插件加载，无需下载", driverKey);
            return true;
        }
        if (!configManager.isDriverAutoDownload()) {
            getLogger().warning("缺少 " + driverKey + " 驱动，且已关闭自动下载。");
            return false;
        }
        getLogger().info("正在下载 " + driverKey + " 数据库驱动……");
        boolean ok = driverManager.ensureAvailable(driverKey);
        if (ok) {
            getLogger().info(driverKey + " 驱动下载完成。");
        }
        return ok;
    }

    /**
     * 驱动是否已由本插件自己加载（来自 lib/ 目录）。
     */
    private boolean isDriverSelfLoaded(String driverKey) {
        com.ncnick.driver.DriverArtifact artifact =
                driverManager.getArtifact(driverKey);
        return artifact != null
                && driverManager.getClassLoader().isSelfLoaded(artifact.getMainClass());
    }

    /**
     * 创建并初始化指定类型的存储，失败时回退到 SQLite。
     *
     * @param type 存储类型（mysql / sqlite）
     * @return 是否成功
     */
    private boolean createStorage(String type) {
        Storage created = "mysql".equals(type)
                ? new MySQLStorage(this)
                : new SQLiteStorage(this);
        storage = created;

        try {
            created.init();
            storageReady = true;
            return true;
        } catch (Throwable t) {
            getLogger().severe("存储初始化失败: " + t.getMessage());
            Debug.error(Debug.SQL, "存储初始化异常", t);

            // 关键：先释放半初始化对象，避免连接池/文件句柄泄漏。
            // MySQLStorage.init() 会先创建 Hikari 连接池再建表，
            // 若建表失败，池子会随这个被丢弃的对象一直存活到进程结束。
            try {
                created.close();
            } catch (Throwable ignored) {
                // 忽略关闭异常
            }
            storage = null;

            // 只有 MySQL 才回退到 SQLite
            if (!"mysql".equals(type)) {
                return false;
            }
            if (!ensureDriver("sqlite")) {
                getLogger().severe("SQLite 驱动也不可用，无法回退。");
                return false;
            }
            getLogger().warning("尝试回退到 SQLite 存储……");
            try {
                Storage fallback = new SQLiteStorage(this);
                fallback.init();
                storage = fallback;
                storageReady = true;
                getLogger().info("已回退到 SQLite 存储。");
                return true;
            } catch (Throwable t2) {
                getLogger().severe("SQLite 回退也失败: " + t2.getMessage());
                storage = null;
                return false;
            }
        }
    }

    /**
     * 判断驱动当前是否可用于建立连接。
     *
     * <p>与 {@link #isDriverSelfLoaded(String)} 的区别：
     * 本方法会回退到插件类加载器，**服务端自带的驱动也算可用**。
     * 用于描述"实际能不能连"，不用于决定是否下载。
     */
    private boolean isDriverUsable(String driverKey) {
        com.ncnick.driver.DriverArtifact artifact =
                driverManager.getArtifact(driverKey);
        if (artifact == null) {
            return false;
        }
        return driverManager.getClassLoader().isAvailable(artifact.getMainClass());
    }

    /**
     * 描述当前存储驱动的来源与状态，用于启动摘要。
     *
     * <p>输出示例：
     * <pre>
     *   驱动    MySQL · 来自 lib/          （本插件自己下载的）
     *   驱动    MySQL · 来自服务端         （服务端自带，未落盘到 lib/）
     *   驱动    MySQL · 缺失               （不可用）
     * </pre>
     */
    private String describeDriver() {
        String type = storage == null ? configManager.getStorageType()
                : storage.getTypeName().toLowerCase();
        String key = type.startsWith("mysql") ? "mysql" : "sqlite";
        com.ncnick.driver.DriverArtifact artifact = driverManager.getArtifact(key);
        String label = "mysql".equals(key) ? "MySQL" : "SQLite";

        if (artifact == null) {
            return label + " · 未定义";
        }
        if (driverManager.getClassLoader().isSelfLoaded(artifact.getMainClass())) {
            return label + " · 来自 lib/";
        }
        if (driverManager.getClassLoader().isAvailable(artifact.getMainClass())) {
            // 能看见但不在 lib/ —— 服务端或其它插件提供的
            return label + " · 来自服务端（未下载到 lib/）";
        }
        return label + " · 缺失";
    }

    /**
     * 注册命令。
     */
    private void registerCommands() {
        PluginCommand nc = getCommand("nc");
        if (nc != null) {
            PlayerCommand executor = new PlayerCommand(this);
            nc.setExecutor(executor);
            nc.setTabCompleter(executor);
        } else {
            getLogger().warning("命令 nc 未在 plugin.yml 中定义");
        }

        PluginCommand nca = getCommand("nca");
        if (nca != null) {
            AdminCommand executor = new AdminCommand(this);
            nca.setExecutor(executor);
            nca.setTabCompleter(executor);
        } else {
            getLogger().warning("命令 nca 未在 plugin.yml 中定义");
        }
    }

    /**
     * 初始化经济与占位符钩子。
     */
    private void setupHooks() {
        if (vaultHook.setup()) {
            Debug.log(Debug.GENERAL, "Vault 已接入");
        }
        if (playerPointsHook.setup()) {
            Debug.log(Debug.GENERAL, "PlayerPoints 已接入");
        }

        placeholderHook = new PlaceholderHook(this);
        placeholderHook.register();

        // 钩子就绪后重新计算一次玩家的显示（可能影响价格提示）
        if (nickManager != null) {
            nickManager.reapplyAll();
        }
    }

    /**
     * 启动定时任务（首次启用时调用）。
     */
    private void startTasks() {
        restartTasks();
        // 实验性功能
        experimentalManager.setup();
    }

    /**
     * 重载后回显给管理员的状态摘要。
     *
     * <p>只列出**关键且易变**的信息，避免刷屏：
     * 支付方式、字符集、唯一性、实验性。
     * 复用 {@link #describePayments()} 等，保证与控制台口径一致。
     *
     * @return 可直接发送的多行文本
     */
    public String reloadStatusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("§7━━ §b重载结果 §7━━\n");
        sb.append("§7  支付    §f").append(describePayments()).append('\n');
        sb.append("§7  字符集  §f").append(describeCharset()).append('\n');
        sb.append("§7  唯一性  §f")
                .append(configManager.isUniqueEnabled() ? "开启" : "关闭").append('\n');
        sb.append("§7  实验性  §f")
                .append(configManager.isExperimentalEnabled()
                        ? experimentalManager.getActualMode() : "关闭").append('\n');
        sb.append("§7  调试    §f")
                .append(Debug.isEnabled() ? "开启" : "关闭");
        return sb.toString();
    }

    /**
     * 打印启动摘要（第 9 条：简洁）。
     *
     * <p>输出风格：<b>每个功能一行</b>，只列关键状态，不堆砌技术细节。
     * 刻意不输出"已注册变量""已适配聊天事件""替换方式"这类过程信息 ——
     * 它们对服主没有意义，只会淹没真正重要的内容。
     * 需要细节时开启 {@code debug.enabled} 即可。
     */
    private void printStartupSummary(long elapsedMs) {
        Logger log = getLogger();

        // 第一行：版本与耗时
        log.info("已启用 · " + VersionUtil.describe() + " · " + elapsedMs + "ms");

        // 以下每行一个功能
        log.info("  存储    " + storage.getTypeName());
        log.info("  驱动    " + describeDriver());
        log.info("  支付    " + describePayments());
        log.info("  字符集  " + describeCharset());
        log.info("  唯一性  " + (configManager.isUniqueEnabled() ? "开启" : "关闭"));

        if (configManager.isExperimentalEnabled()) {
            log.info("  实验性  " + experimentalManager.getActualMode());
        }
        if (Debug.isEnabled()) {
            log.info("  调试    开启（排查完请关闭）");
        }
    }

    /**
     * 描述可用的支付方式。
     *
     * <p>若金币/点券都不可用，会给出**具体原因**。
     * 早期版本无脑写"未装经济插件"，当服务器其实装了经济插件
     * 只是缺少 Vault 时，这个提示会把人带偏。
     */
    private String describePayments() {
        StringBuilder sb = new StringBuilder();
        if (vaultHook != null && vaultHook.isEnabled()) {
            sb.append("金币");
        }
        if (playerPointsHook != null && playerPointsHook.isEnabled()) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append("点券");
        }
        if (sb.length() > 0) {
            return sb.toString();
        }

        // 都不可用 —— 组装原因
        StringBuilder why = new StringBuilder();
        if (vaultHook != null && vaultHook.getUnavailableReason() != null) {
            why.append("金币: ").append(vaultHook.getUnavailableReason());
        }
        if (playerPointsHook != null && playerPointsHook.getUnavailableReason() != null) {
            if (why.length() > 0) {
                why.append("；");
            }
            why.append("点券: ").append(playerPointsHook.getUnavailableReason());
        }
        if (why.length() == 0) {
            why.append("未安装经济插件");
        }
        return "仅权限模式（" + why + "）";
    }

    /** 描述字符集状态。 */
    private String describeCharset() {
        int ranges = configManager.getCharsetFilter().rangeCount();
        if (ranges == 0) {
            // 这种情况 ConfigManager 已经报过严重告警，这里不重复
            return "未配置（玩家无法设置昵称）";
        }
        return ranges + " 个区间";
    }

    // ==========================================================
    //  重载（第 13 条）
    // ==========================================================

    /**
     * 完整重载插件配置与语言文件。
     *
     * <p>重载范围：
     * <ul>
     *   <li>config.yml 与派生配置</li>
     *   <li>语言文件</li>
     *   <li>调试开关</li>
     *   <li>实验性功能（重新探测并应用）</li>
     *   <li>所有在线玩家的显示</li>
     * </ul>
     *
     * <p>注意：存储层不重建（避免连接中断）。若存储类型改变，
     * 会给出需要重启的提示。
     */
    public void reloadPlugin() {
        long start = System.currentTimeMillis();

        String oldStorage = storage == null ? "" : storage.getTypeName();
        String oldType = configManager.getStorageType();

        // ---- 重载配置 ----
        reloadConfig();
        configManager.reload();
        applyDebugConfig();

        // ---- 重载语言 ----
        String locale = getConfig().getString("language", "zh_CN");
        lang.reload(locale);

        // ---- 清理界面与选色会话 ----
        guiManager.shutdown();

        // ---- 重新读取驱动坐标定义（driver.artifacts.* 改动可生效） ----
        try {
            driverManager.reloadArtifacts();
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "重载驱动定义失败", t);
        }

        // ---- 重新探测实验性功能 ----
        experimentalManager.setup();

        // ---- 重新探测经济 / 变量钩子 ----
        // 场景：服主装好 Vault 或经济插件后执行 /nca reload，
        // 期望不用重启就能生效。Vault 的经济服务在它自己启用时就已注册，
        // 这里只需重新向 ServicesManager 查询一次即可。
        // 早期版本没有这一步，导致"装了 Vault 但 reload 后仍显示未接入"。
        try {
            boolean vaultOk = vaultHook.setup();
            boolean ppOk = playerPointsHook.setup();
            if (vaultOk || ppOk) {
                getLogger().info("经济钩子已重新探测："
                        + describePayments());
            } else if (vaultHook.getUnavailableReason() != null
                    || playerPointsHook.getUnavailableReason() != null) {
                getLogger().info("经济钩子重新探测后仍不可用："
                        + describePayments());
            }
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "重新探测经济钩子失败", t);
        }

        // ---- 清空权限缓存，让新配置立即生效 ----
        nickManager.clearAllPermissionCache();

        // ---- 任务按新配置重启 ----
        restartTasks();

        // ---- 存储类型变更检测 ----
        String newType = configManager.getStorageType();
        boolean storageChanged = !newType.equalsIgnoreCase(oldType);
        if (storageChanged) {
            getLogger().warning("存储类型已由 " + oldType + " 变更为 " + newType
                    + "（当前运行 " + oldStorage + "），需重启服务器才能切换。");
        }

        // ---- 重新应用所有玩家显示 ----
        try {
            nickManager.reapplyAll();
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "重载后应用显示失败", t);
        }

        getLogger().info("配置已重载 (" + (System.currentTimeMillis() - start) + "ms)");
    }

    /**
     * 按当前配置重启定时任务。
     *
     * <p>reload 后间隔类配置需要重建任务才能生效。
     */
    private void restartTasks() {
        // 停掉旧任务
        try {
            if (syncTask != null) {
                syncTask.cancel();
                syncTask = null;
            }
            if (permissionCheckTask != null) {
                permissionCheckTask.cancel();
                permissionCheckTask = null;
            }
        } catch (Throwable ignored) {
            // 忽略取消失败
        }

        // 跨服同步（仅 MySQL）
        if (storage instanceof MySQLStorage) {
            syncTask = new SyncTask(this);
            syncTask.start();
        }

        // 权限检查（间隔可能已变）
        int interval = configManager.getPermissionCheckInterval();
        if (interval > 0) {
            permissionCheckTask = new PermissionCheckTask(this);
            long ticks = Math.max(20L, interval * 20L);
            permissionCheckTask.runTaskTimer(this, 200L, ticks);
        }
    }

    // ==========================================================
    //  Getter
    // ==========================================================

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LangManager getLang() {
        return lang;
    }

    public Storage getStorage() {
        return storage;
    }

    public boolean isStorageReady() {
        return storageReady;
    }

    public DriverManagerImpl getDriverManager() {
        return driverManager;
    }

    public NickManager getNickManager() {
        return nickManager;
    }

    public DisplayManager getDisplayManager() {
        return displayManager;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }

    public ExperimentalManager getExperimentalManager() {
        return experimentalManager;
    }

    public VaultHook getVaultHook() {
        return vaultHook;
    }

    public PlayerPointsHook getPlayerPointsHook() {
        return playerPointsHook;
    }
}
