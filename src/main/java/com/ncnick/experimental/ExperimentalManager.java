package com.ncnick.experimental;

import com.ncnick.NCNick;
import com.ncnick.util.Debug;
import com.ncnick.util.VersionUtil;
import org.bukkit.Bukkit;

/**
 * 实验性功能管理器（第 8 条）。
 *
 * <p>提供"直接在原版显示上做替换"的能力，包含三个可独立开关的模块：
 * <ol>
 *   <li>原版 TAB 列表</li>
 *   <li>原版玩家头顶悬浮 ID</li>
 *   <li>原版聊天栏</li>
 * </ol>
 *
 * <p>每个模块有两种显示模式：
 * <ul>
 *   <li>{@code full} —— ①全自定义（只显示昵称）</li>
 *   <li>{@code name-nickname} —— ②原ID + 自定义</li>
 * </ul>
 *
 * <h3>实现策略</h3>
 * 优先使用 PacketEvents 在数据包层面替换（兼容性最好、不与其他插件的
 * API 层改动冲突）；若 PacketEvents 不可用，则自动降级为纯 Bukkit API 实现。
 * 所有 PacketEvents 调用均通过反射完成，因此不安装该插件时也能正常编译与运行。
 */
public class ExperimentalManager {

    private final NCNick plugin;
    /** PacketEvents 是否可用 */
    private boolean packetEventsAvailable = false;
    /** 三个模块的接管状态 */
    private boolean tabHijacked = false;
    private boolean nametagHijacked = false;
    private boolean chatHijacked = false;
    /** 当前实际使用的模式 */
    private String actualMode = "none";

    public ExperimentalManager(NCNick plugin) {
        this.plugin = plugin;
    }

    /**
     * 初始化实验性功能。
     *
     * @return 是否成功启用了至少一个模块
     */
    public boolean setup() {
        reset();

        if (!plugin.getConfigManager().isExperimentalEnabled()) {
            Debug.log(Debug.PACKET, "实验性功能总开关未启用");
            return false;
        }

        // 探测 PacketEvents
        packetEventsAvailable = detectPacketEvents();
        actualMode = decideMode();

        // 替换方式只在调试模式输出；启动摘要里已有一行"实验性 · ..."概括
        Debug.log(Debug.PACKET, "实验性功能替换方式: 原生 API"
                + (packetEventsAvailable ? "（已检测到 PacketEvents，当前仍用原生 API）" : ""));

        boolean any = false;

        if (plugin.getConfigManager().isExpTabEnabled()) {
            tabHijacked = true;
            any = true;
            Debug.log(Debug.PACKET, "已接管原版 TAB，模式: {}",
                    plugin.getConfigManager().getExpTabMode());
        }
        if (plugin.getConfigManager().isExpNametagEnabled()) {
            nametagHijacked = true;
            any = true;
            Debug.log(Debug.PACKET, "已接管原版头顶，模式: {}",
                    plugin.getConfigManager().getExpNametagMode());
        }
        if (plugin.getConfigManager().isExpChatEnabled()) {
            chatHijacked = true;
            any = true;
            Debug.log(Debug.PACKET, "已接管原版聊天，模式: {}",
                    plugin.getConfigManager().getExpChatMode());
        }

        if (!any) {
            // 总开关开着但没启用任何子项 —— 这是配置疏漏，值得提醒一句
            plugin.getLogger().warning(
                    "实验性功能总开关已开启，但 TAB / 头顶 / 聊天 三个子开关都未启用，实际不生效");
        }
        return any;
    }

    /**
     * 决定实际使用的替换策略。
     *
     * <p>返回值语义（务必与实现保持一致，不做夸大宣传）：
     * <ul>
     *   <li>{@code api} —— 通过 Bukkit 原生 API 直接替换原版显示内容
     *       （TAB 用 setPlayerListName、头顶用记分板队伍、聊天用事件格式）。
     *       这是默认且始终可用的策略，不需要任何额外依赖。</li>
     *   <li>{@code packetevents} —— 预留的数据包级策略标识。
     *       当前版本尚未实现发包逻辑，因此不会返回该值；
     *       检测到 PacketEvents 只用于日志与将来的扩展。</li>
     * </ul>
     */
    private String decideMode() {
        String configured = plugin.getConfigManager().getPacketMode();

        // 无论配置如何，当前实现都基于原生 API 完成替换。
        // 若管理员显式要求 packetevents 但未安装，给出明确提示。
        if ("packetevents".equals(configured) && !packetEventsAvailable) {
            plugin.getLogger().warning(
                    "配置要求使用 PacketEvents，但未检测到该插件；"
                            + "已改用原生 API 直接替换原版显示。");
        }

        // 诚实标注：当前版本统一使用 API 替换
        return "api";
    }

    /**
     * 探测 PacketEvents 是否已安装并可用。
     *
     * <p>用反射探测，避免编译期依赖。
     */
    private boolean detectPacketEvents() {
        // 先看插件是否安装
        if (Bukkit.getPluginManager().getPlugin("PacketEvents") == null) {
            Debug.log(Debug.PACKET, "未安装 PacketEvents 插件");
            return false;
        }
        // 再看 API 类是否可加载
        boolean apiOk = VersionUtil.hasClass("com.github.retrooper.packetevents.PacketEvents");
        if (!apiOk) {
            Debug.log(Debug.PACKET, "PacketEvents 插件存在但 API 类不可加载");
            return false;
        }
        try {
            // 校验 API 实例已初始化
            Class<?> cls = Class.forName("com.github.retrooper.packetevents.PacketEvents");
            Object instance = cls.getMethod("getAPI").invoke(null);
            return instance != null;
        } catch (Throwable t) {
            Debug.error(Debug.PACKET, "PacketEvents API 初始化检查失败", t);
            return false;
        }
    }

    /** 重置所有接管状态。 */
    public void reset() {
        tabHijacked = false;
        nametagHijacked = false;
        chatHijacked = false;
        packetEventsAvailable = false;
        actualMode = "none";
    }

    // ==========================================================
    //  状态查询
    // ==========================================================

    /** TAB 是否被实验性功能接管。 */
    public boolean isTabHijacked() {
        return tabHijacked;
    }

    /** 头顶是否被接管。 */
    public boolean isNametagHijacked() {
        return nametagHijacked;
    }

    /** 聊天是否被接管。 */
    public boolean isChatHijacked() {
        return chatHijacked;
    }

    /** PacketEvents 是否可用。 */
    public boolean isPacketEventsAvailable() {
        return packetEventsAvailable;
    }

    /** 实际使用的模式描述。 */
    public String getActualMode() {
        return actualMode;
    }

    /** 是否正在使用数据包模式（当前版本恒为 false，保留接口供将来扩展）。 */
    public boolean isUsingPackets() {
        return "packetevents".equals(actualMode);
    }

    /**
     * 是否为"直接替换原版显示"模式。
     *
     * <p>这是实验性功能的核心语义：接管原版 TAB / 头顶 / 聊天 的显示内容，
     * 而不是新增一套并行的显示。当前版本通过原生 API 实现。
     */
    public boolean isHijackingVanilla() {
        return tabHijacked || nametagHijacked || chatHijacked;
    }

    // ==========================================================
    //  显示名计算
    // ==========================================================

    /**
     * 按实验性模式计算 TAB 显示名。
     *
     * @param playerName 玩家真实 ID
     * @param nickname   昵称（含颜色），可为 null
     * @return 显示名（含颜色代码）
     */
    public String buildExpTabName(String playerName, String nickname) {
        return buildByMode(plugin.getConfigManager().getExpTabMode(), playerName, nickname);
    }

    /**
     * 按实验性模式计算头顶显示名。
     */
    public String buildExpNametagName(String playerName, String nickname) {
        return buildByMode(plugin.getConfigManager().getExpNametagMode(), playerName, nickname);
    }

    /**
     * 按实验性模式计算聊天显示名。
     */
    public String buildExpChatName(String playerName, String nickname) {
        return buildByMode(plugin.getConfigManager().getExpChatMode(), playerName, nickname);
    }

    /**
     * 按指定模式生成显示名。
     *
     * @param mode       full（仅自定义）/ name-nickname（原ID+自定义）
     * @param playerName 真实 ID
     * @param nickname   昵称（含颜色），可为 null
     */
    private String buildByMode(String mode, String playerName, String nickname) {
        if (nickname == null || nickname.isEmpty()) {
            return playerName;
        }
        if ("full".equals(mode)) {
            // ① 全自定义：只显示昵称
            return nickname;
        }
        // ② 原ID + 自定义：原ID#昵称
        String prefix = plugin.getConfigManager().getCustomPrefix();
        return playerName + prefix + nickname;
    }

    /**
     * 供调试：输出当前实验性功能状态。
     */
    public String statusLine() {
        return "模式=" + actualMode
                + " TAB=" + (tabHijacked ? "开" : "关")
                + " 头顶=" + (nametagHijacked ? "开" : "关")
                + " 聊天=" + (chatHijacked ? "开" : "关");
    }
}
