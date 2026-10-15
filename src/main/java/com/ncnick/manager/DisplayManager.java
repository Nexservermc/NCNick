package com.ncnick.manager;

import com.ncnick.ConfigManager;
import com.ncnick.NCNick;
import com.ncnick.model.NickData;
import com.ncnick.util.Debug;
import com.ncnick.util.Msg;
import com.ncnick.util.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * 显示管理器。
 *
 * <p>负责把昵称应用到三个显示位置：
 * <ol>
 *   <li>TAB 玩家列表（{@code player.setPlayerListName}）</li>
 *   <li>头顶名字（记分板队伍前缀/后缀）</li>
 *   <li>聊天栏（由 ChatListener 处理）</li>
 * </ol>
 *
 * <p>若实验性功能接管了某个位置，这里会跳过对应处理，避免两个模块互相覆盖。
 */
public class DisplayManager {

    /** 头顶前缀/后缀的长度上限（1.8-1.12 为 16，1.13+ 为 64） */
    private static final int LEGACY_PREFIX_LIMIT = 16;
    private static final int MODERN_PREFIX_LIMIT = 64;

    /** 队伍名前缀，用于识别本插件创建的对象 */
    private static final String TEAM_PREFIX = "ncn_";

    private final NCNick plugin;

    public DisplayManager(NCNick plugin) {
        this.plugin = plugin;
    }

    /**
     * 应用所有显示位置。
     */
    public void applyAll(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        applyTab(player);
        applyNametag(player);
        // 聊天栏通过事件监听实时处理，无需在此应用
    }

    // ==========================================================
    //  TAB 列表
    // ==========================================================

    /**
     * 应用 TAB 列表显示名。
     *
     * <p>实验性 TAB 接管开启时，改用实验性模式（①全自定义 / ②原ID+自定义）
     * 计算显示名；否则使用常规的 display.tab-format 模板。
     */
    public void applyTab(Player player) {
        try {
            NickData data = plugin.getNickManager().get(player.getUniqueId());
            boolean active = data != null
                    && plugin.getNickManager().isActive(player, data);

            if (!active) {
                // 恢复为原始名字
                resetTab(player);
                return;
            }

            String tabName;
            if (plugin.getExperimentalManager().isTabHijacked()) {
                // 实验性接管：按实验性模式生成（仅替换原版显示内容）
                tabName = plugin.getExperimentalManager()
                        .buildExpTabName(player.getName(), data.getColoredNickname());
            } else {
                tabName = plugin.getNickManager().buildTabName(player);
            }

            String colored = Msg.color(tabName);

            // 1.8-1.12 的 setPlayerListName 按原始长度（含 § 码）校验 16 字符，
            // 渐变昵称每个字符都带颜色码，必须按原始长度再截一次。
            int limit = prefixLimit();
            colored = clampRaw(colored, limit);

            // 避免重复设置（减少数据包发送）
            if (colored.equals(player.getPlayerListName())) {
                return;
            }
            player.setPlayerListName(colored);
            Debug.log(Debug.CACHE, "TAB 已更新: {} -> {}", player.getName(), tabName);
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "设置 TAB 名字失败: " + player.getName(), t);
        }
    }

    /**
     * 恢复 TAB 为真实 ID。
     */
    private void resetTab(Player player) {
        try {
            String current = player.getPlayerListName();
            if (current != null && !current.equals(player.getName())) {
                player.setPlayerListName(player.getName());
                Debug.log(Debug.CACHE, "TAB 已恢复: {}", player.getName());
            }
        } catch (Throwable t) {
            // 某些服务端实现会抛出，忽略即可
            Debug.log(Debug.GENERAL, "恢复 TAB 失败: {}", t.getMessage());
        }
    }

    // ==========================================================
    //  头顶名字（记分板队伍）
    // ==========================================================

    /**
     * 应用头顶名字。
     *
     * <p>Minecraft 的头顶名字由"队伍前缀 + 玩家名 + 队伍后缀"组成。
     * 队伍前缀/后缀长度上限：1.8-1.12 为 16，1.13+ 为 64。
     *
     * <p>实验性头顶接管开启时，改用实验性模式计算显示内容：
     * <ul>
     *   <li>{@code full} —— ①全自定义：隐藏原版玩家名，只显示昵称</li>
     *   <li>{@code name-nickname} —— ②原ID+自定义：原ID 后追加 #昵称</li>
     * </ul>
     */
    public void applyNametag(Player player) {
        try {
            NickData data = plugin.getNickManager().get(player.getUniqueId());
            boolean active = data != null
                    && plugin.getNickManager().isActive(player, data);

            Scoreboard board = getScoreboard();
            if (board == null) {
                return;
            }

            String teamName = teamNameFor(player);
            Team team = board.getTeam(teamName);

            if (!active) {
                // 无昵称：移除队伍条目，恢复原版
                if (team != null) {
                    safeRemoveEntry(team, player.getName());
                    if (team.getEntries().isEmpty()) {
                        safeUnregister(team);
                    }
                }
                return;
            }

            if (team == null) {
                team = board.registerNewTeam(teamName);
            }

            // 计算头顶后缀。
            //
            // 重要限制：原版头顶名字由「队伍前缀 + 玩家实体名 + 队伍后缀」三段拼成，
            // 其中玩家实体名无法通过记分板 API 移除。因此：
            //   · name-nickname 模式 → 后缀为 "#昵称"，最终显示 "Steve#小明"（完全符合预期）
            //   · full 模式         → 后缀为 "昵称"，最终显示 "Steve小明"
            //     即原版 ID 仍会出现，无法做到"只显示昵称"。
            //     真正隐藏原版 ID 需要数据包层面的干预，本插件不依赖该能力。
            String suffix;
            boolean experimental = plugin.getExperimentalManager().isNametagHijacked();
            if (experimental && "full".equals(plugin.getConfigManager().getExpNametagMode())) {
                // 全自定义模式：直接把昵称作为后缀（原版 ID 无法移除，会前缀显示）
                suffix = Msg.color(data.getColoredNickname());
                warnNametagLimitationOnce();
            } else {
                // 原ID + 自定义，或常规模式：昵称作为后缀
                suffix = buildNametagSuffix(player);
            }

            // 双重截断：
            //   1) truncateVisible 限制可见字符数（保持观感）
            //   2) clampRaw       限制**原始长度**（含 § 码）
            // 1.8-1.12 的 setSuffix 按 String.length() 校验 16 字符，
            // 而渐变昵称每个字符都带一个颜色码，仅按可见长度截断会导致
            // 原始长度超限并抛 IllegalArgumentException。
            int limit = prefixLimit();
            suffix = clampRaw(Msg.truncateVisible(suffix, limit), limit);

            if (!suffix.equals(team.getSuffix())) {
                safeSetSuffix(team, suffix);
            }
            if (!team.hasEntry(player.getName())) {
                safeAddEntry(team, player.getName());
            }
            Debug.log(Debug.CACHE, "头顶已更新: {} -> {}", player.getName(), suffix);
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "设置头顶名字失败: " + player.getName(), t);
        }
    }

    /** 是否已就头顶限制告警过一次（避免刷屏）。 */
    private volatile boolean nametagLimitWarned = false;

    /**
     * 就"无法隐藏原版头顶 ID"这一平台限制给出一次告警。
     *
     * <p>诚实告知管理员：Bukkit 的记分板 API 无法移除玩家实体名，
     * 因此 full 模式在头顶位置只能做到"原ID + 昵称"。
     */
    private void warnNametagLimitationOnce() {
        if (nametagLimitWarned) {
            return;
        }
        nametagLimitWarned = true;
        plugin.getLogger().warning(
                "头顶显示限制：原版玩家 ID 无法通过记分板 API 隐藏，"
                        + "因此 experimental.nametag.mode=full 在头顶实际显示为"
                        + "「原ID+昵称」。如需真正只显示昵称，"
                        + "请使用 TAB 位置（experimental.tab）或安装支持数据包层的插件。");
    }

    /**
     * 按**原始长度**（含 {@code §} 颜色码）截断字符串。
     *
     * <p>为什么需要：1.8-1.12 的 {@code Team#setSuffix}、{@code setPrefix} 与
     * {@code Player#setPlayerListName} 是按 Java 字符串长度校验 16 字符上限的，
     * 颜色码也计入。仅按"可见字符"截断会让带渐变的昵称超出原始长度而抛
     * {@link IllegalArgumentException}，最终表现是头顶/TAB 昵称完全不显示。
     *
     * <p>截断时会避免切出半个颜色序列（不留下孤立的 {@code §}）。
     *
     * @param s     原字符串
     * @param limit 原始字符数上限
     * @return 截断后的字符串
     */
    private static String clampRaw(String s, int limit) {
        if (s == null || s.isEmpty() || s.length() <= limit) {
            return s == null ? "" : s;
        }
        String cut = s.substring(0, limit);
        // 若正好切在 § 上，退回一个字符，避免留下半个颜色序列
        if (cut.endsWith("\u00A7")) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }

    /**
     * 构造头顶后缀。
     *
     * <p>格式模板为 {name}{prefix}{nickname} 时，去掉 {name} 后
     * 剩下的部分（#昵称）作为后缀。
     */
    private String buildNametagSuffix(Player player) {
        ConfigManager cfg = plugin.getConfigManager();
        String template = cfg.getNametagFormat();

        // 关键：剥离模板中**所有** {name}，而不只是第一个。
        // 若模板写成 "{name}{name}{prefix}{nickname}"，只剥第一个会让
        // 剩余的 {name} 仍被替换成 ID，导致头顶重复显示 "SteveSteve#小明"。
        String rest = template.replace("{name}", "");

        String nickname = plugin.getNickManager().getActiveNickname(player);
        String prefix = cfg.getCustomPrefix();
        String filled = Msg.fill(rest, mapOf(
                "nickname", nickname == null ? "" : nickname,
                "prefix", nickname == null && !cfg.isPrefixWhenNoNickname() ? "" : prefix,
                // 后缀中不应再出现原版 ID：映射为空串，双保险
                "name", ""));
        return Msg.color(filled);
    }

    private static java.util.Map<String, String> mapOf(String k1, String v1,
                                                       String k2, String v2,
                                                       String k3, String v3) {
        java.util.Map<String, String> m = new java.util.HashMap<String, String>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return m;
    }

    /**
     * 获取主记分板。
     */
    private Scoreboard getScoreboard() {
        try {
            if (Bukkit.getScoreboardManager() == null) {
                return null;
            }
            return Bukkit.getScoreboardManager().getMainScoreboard();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 生成该玩家的队伍名（保证不与他人冲突）。 */
    private static String teamNameFor(Player player) {
        String uuid = player.getUniqueId().toString().replace("-", "");
        // 队伍名上限 16 字符
        String suffix = uuid.length() >= 12 ? uuid.substring(0, 12) : uuid;
        return TEAM_PREFIX + suffix;
    }

    /** 当前服务端允许的前缀长度。 */
    private static int prefixLimit() {
        return VersionUtil.atLeast(13) ? MODERN_PREFIX_LIMIT : LEGACY_PREFIX_LIMIT;
    }

    // ---- 安全包装：不同版本 API 行为有差异 ----

    private void safeSetSuffix(Team team, String suffix) {
        try {
            team.setSuffix(suffix);
        } catch (Throwable t) {
            // 超长时截断重试
            try {
                team.setSuffix(Msg.truncateVisible(suffix, 16));
            } catch (Throwable ignored) {
                Debug.log(Debug.GENERAL, "设置队伍后缀失败: {}", t.getMessage());
            }
        }
    }

    private void safeAddEntry(Team team, String entry) {
        try {
            team.addEntry(entry);
        } catch (Throwable t) {
            Debug.log(Debug.GENERAL, "添加队伍条目失败: {}", t.getMessage());
        }
    }

    private void safeRemoveEntry(Team team, String entry) {
        try {
            if (team.hasEntry(entry)) {
                team.removeEntry(entry);
            }
        } catch (Throwable t) {
            Debug.log(Debug.GENERAL, "移除队伍条目失败: {}", t.getMessage());
        }
    }

    private void safeUnregister(Team team) {
        try {
            team.unregister();
        } catch (Throwable t) {
            Debug.log(Debug.GENERAL, "注销队伍失败: {}", t.getMessage());
        }
    }

    // ==========================================================
    //  清理
    // ==========================================================

    /**
     * 玩家退出时清理头衔残留。
     */
    public void cleanup(Player player) {
        if (player == null) {
            return;
        }
        try {
            Scoreboard board = getScoreboard();
            if (board == null) {
                return;
            }
            Team team = board.getTeam(teamNameFor(player));
            if (team != null) {
                safeRemoveEntry(team, player.getName());
                if (team.getEntries().isEmpty()) {
                    safeUnregister(team);
                }
            }
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "清理玩家显示失败: " + player.getName(), t);
        }
    }

    /**
     * 插件卸载时清理所有本插件创建的队伍。
     */
    public void cleanupAll() {
        try {
            Scoreboard board = getScoreboard();
            if (board == null) {
                return;
            }
            for (Team team : board.getTeams()) {
                if (team.getName().startsWith(TEAM_PREFIX)) {
                    for (String entry : new java.util.ArrayList<String>(team.getEntries())) {
                        safeRemoveEntry(team, entry);
                    }
                    safeUnregister(team);
                }
            }
            Debug.log(Debug.GENERAL, "已清理所有记分板队伍");
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "清理记分板失败", t);
        }
    }

    /**
     * 全部玩家重新应用（reload 时使用）。
     */
    public void reapplyAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyAll(p);
        }
    }
}
