package com.ncnick.util;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 权限节点集中定义。
 *
 * <h3>设计原则</h3>
 * <ol>
 *   <li><b>全部集中在这里</b>：避免散落在各处导致拼写错误或遗漏，
 *       也方便服主在一个文件里看全所有节点。</li>
 *   <li><b>分层清晰</b>：用点号分层（{@code ncnick.color.gradient}），
 *       在 LuckPerms / PermissionsEx 里能按前缀批量授权，
 *       例如 {@code /lp group vip permission set ncnick.color.* true}。</li>
 *   <li><b>父节点蕴含子节点</b>：通过 Bukkit 的 {@code children} 机制
 *       （见 plugin.yml）实现，授予 {@code ncnick.admin} 即获得其下全部。</li>
 *   <li><b>提供通配符判断</b>：{@link #has(CommandSender, String)}
 *       会自动把 {@code ncnick.*} / {@code ncnick.color.*} 视为已授权，
 *       兼容那些不支持 Bukkit children 的权限插件。</li>
 * </ol>
 *
 * <h3>节点总览</h3>
 * <pre>
 * ncnick.use                      基础使用（打开界面、设置昵称）
 * ├─ ncnick.nickname.set          设置/修改自己的昵称
 * ├─ ncnick.nickname.remove       删除自己的昵称
 * ├─ ncnick.color.use             使用颜色功能（入口）
 * │  ├─ ncnick.color.solid        纯色
 * │  ├─ ncnick.color.gradient     渐变
 * │  └─ ncnick.color.hex          十六进制真彩色（1.16+）
 * ├─ ncnick.free                  免费设置（跳过付费）
 * └─ ncnick.bypass.*              各类限制的豁免
 *    ├─ ncnick.bypass.length      跳过长度限制
 *    ├─ ncnick.bypass.charset     跳过字符集限制
 *    ├─ ncnick.bypass.unique      跳过全服唯一检查
 *    ├─ ncnick.bypass.blocked     跳过屏蔽词检查
 *    └─ ncnick.bypass.cooldown    跳过冷却
 *
 * ncnick.admin                    管理员总权限
 * ├─ ncnick.admin.gui             打开管理界面
 * ├─ ncnick.admin.set             设置他人昵称（可用颜色代码）
 * ├─ ncnick.admin.color           设置他人颜色
 * ├─ ncnick.admin.reset           重置他人颜色
 * ├─ ncnick.admin.remove          删除他人昵称
 * ├─ ncnick.admin.info            查看他人信息
 * ├─ ncnick.admin.list            列出所有玩家
 * ├─ ncnick.admin.search          搜索玩家
 * ├─ ncnick.admin.driver          驱动管理
 * ├─ ncnick.admin.debug           调试开关
 * ├─ ncnick.admin.reload          重载配置
 * └─ ncnick.admin.bypass          管理员绕过一切校验
 * </pre>
 */
public final class Permissions {

    private Permissions() {
        // 常量类禁止实例化
    }

    // ==========================================================
    //  根节点
    // ==========================================================

    /** 通配符：拥有它等同于拥有全部节点 */
    public static final String WILDCARD = "ncnick.*";

    /** 基础使用权限（打开界面、设置昵称的入口） */
    public static final String USE = "ncnick.use";

    // ==========================================================
    //  玩家 · 昵称
    // ==========================================================

    /** 设置 / 修改自己的昵称 */
    public static final String NICKNAME_SET = "ncnick.nickname.set";

    /** 删除自己的昵称 */
    public static final String NICKNAME_REMOVE = "ncnick.nickname.remove";

    // ==========================================================
    //  玩家 · 颜色
    // ==========================================================

    /** 使用颜色功能的入口权限 */
    public static final String COLOR_USE = "ncnick.color.use";

    /** 纯色 */
    public static final String COLOR_SOLID = "ncnick.color.solid";

    /** 渐变 */
    public static final String COLOR_GRADIENT = "ncnick.color.gradient";

    /** 十六进制真彩色（需服务端 1.16+） */
    public static final String COLOR_HEX = "ncnick.color.hex";

    // ==========================================================
    //  玩家 · 免费与豁免
    // ==========================================================

    /** 免费设置昵称与颜色（跳过付费） */
    public static final String FREE = "ncnick.free";

    /** 跳过长度限制 */
    public static final String BYPASS_LENGTH = "ncnick.bypass.length";

    /** 跳过字符集限制 */
    public static final String BYPASS_CHARSET = "ncnick.bypass.charset";

    /** 跳过全服唯一检查 */
    public static final String BYPASS_UNIQUE = "ncnick.bypass.unique";

    /** 跳过屏蔽词检查 */
    public static final String BYPASS_BLOCKED = "ncnick.bypass.blocked";

    /** 跳过冷却 */
    public static final String BYPASS_COOLDOWN = "ncnick.bypass.cooldown";

    /** 豁免通配符 */
    public static final String BYPASS_ALL = "ncnick.bypass.*";

    // ==========================================================
    //  管理员
    // ==========================================================

    /** 管理员总权限 */
    public static final String ADMIN = "ncnick.admin";

    /** 打开管理界面 */
    public static final String ADMIN_GUI = "ncnick.admin.gui";

    /** 设置他人昵称（可用颜色代码） */
    public static final String ADMIN_SET = "ncnick.admin.set";

    /** 设置他人昵称颜色 */
    public static final String ADMIN_COLOR = "ncnick.admin.color";

    /** 重置他人昵称颜色 */
    public static final String ADMIN_RESET = "ncnick.admin.reset";

    /** 删除他人昵称 */
    public static final String ADMIN_REMOVE = "ncnick.admin.remove";

    /** 查看他人昵称信息 */
    public static final String ADMIN_INFO = "ncnick.admin.info";

    /** 列出所有已设置昵称的玩家 */
    public static final String ADMIN_LIST = "ncnick.admin.list";

    /** 搜索玩家 */
    public static final String ADMIN_SEARCH = "ncnick.admin.search";

    /** 驱动管理（查看状态、强制下载） */
    public static final String ADMIN_DRIVER = "ncnick.admin.driver";

    /** 调试开关 */
    public static final String ADMIN_DEBUG = "ncnick.admin.debug";

    /** 重载配置 */
    public static final String ADMIN_RELOAD = "ncnick.admin.reload";

    /** 管理员绕过一切校验（长度、字符集、唯一、屏蔽词） */
    public static final String ADMIN_BYPASS = "ncnick.admin.bypass";

    // ==========================================================
    //  判断工具
    // ==========================================================

    /**
     * 判断发送者是否拥有指定权限。
     *
     * <p>除直接查询外，还会依次检查逐级通配符，例如查询
     * {@code ncnick.color.gradient} 时也会认可：
     * <ul>
     *   <li>{@code ncnick.color.*}</li>
     *   <li>{@code ncnick.*}</li>
     * </ul>
     * 这样即使权限插件不支持 Bukkit 的 children 继承，
     * 服主用通配符授权也能生效。
     *
     * @param sender 命令发送者 / 玩家，可为 null
     * @param node   权限节点
     * @return 是否有权限
     */
    public static boolean has(CommandSender sender, String node) {
        if (sender == null || node == null || node.isEmpty()) {
            return false;
        }
        // 控制台视为拥有全部权限
        if (!(sender instanceof Player)) {
            return true;
        }
        if (sender.hasPermission(node)) {
            return true;
        }
        // 逐级向上检查通配符
        String wildcard = node;
        int dot;
        while ((dot = wildcard.lastIndexOf('.')) > 0) {
            wildcard = wildcard.substring(0, dot);
            if (sender.hasPermission(wildcard + ".*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断玩家是否为管理员（拥有 {@link #ADMIN} 或其通配符）。
     *
     * @param sender 发送者
     */
    public static boolean isAdmin(CommandSender sender) {
        return has(sender, ADMIN);
    }

    /**
     * 判断玩家是否可免费设置（拥有 {@link #FREE} 或管理员权限）。
     *
     * @param sender 发送者
     */
    public static boolean canFree(CommandSender sender) {
        return has(sender, FREE) || has(sender, ADMIN);
    }

    /**
     * 判断玩家是否可绕过指定校验。
     *
     * @param sender 发送者
     * @param node   具体的 bypass 节点
     */
    public static boolean canBypass(CommandSender sender, String node) {
        return has(sender, node) || has(sender, ADMIN_BYPASS);
    }
}
