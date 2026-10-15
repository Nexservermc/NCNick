package com.ncnick.command;

import com.ncnick.NCNick;
import com.ncnick.config.LangManager;
import com.ncnick.driver.DriverArtifact;
import com.ncnick.driver.DriverDownloader;
import com.ncnick.gui.admin.AdminGui;
import com.ncnick.manager.NickManager;
import com.ncnick.manager.ValidateResult;
import com.ncnick.model.ColorPreset;
import com.ncnick.model.NickData;
import com.ncnick.util.Debug;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * 管理员命令 /nca（第 7 条：更细致的 OP 管理指令）。
 *
 * <p>子命令：
 * <ul>
 *   <li>{@code gui} —— 打开管理界面</li>
 *   <li>{@code set <玩家> <昵称>} —— 设置昵称</li>
 *   <li>{@code color <玩家> <颜色>} —— 设置颜色（支持颜色代码）</li>
 *   <li>{@code resetcolor <玩家>} —— 重置颜色</li>
 *   <li>{@code clear <玩家>} —— 清除昵称</li>
 *   <li>{@code info <玩家>} —— 查看详情</li>
 *   <li>{@code list [页码]} —— 列出所有昵称</li>
 *   <li>{@code driver [status|download]} —— 驱动管理</li>
 *   <li>{@code debug [on|off]} —— 调试开关</li>
 *   <li>{@code reload} —— 重载配置</li>
 * </ul>
 */
public class AdminCommand implements TabExecutor {

    private static final int LIST_PAGE_SIZE = 10;

    /** 驱动下载锁，防止并发下载同一驱动 */
    private final Object driverLock = new Object();
    private volatile boolean downloading = false;

    private final NCNick plugin;

    public AdminCommand(NCNick plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("ncnick.admin")) {
            sender.sendMessage(plugin.getLang().msg("msg.no-permission"));
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player) {
                plugin.getGuiManager().openAdmin((Player) sender);
            } else {
                sendHelp(sender);
            }
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "gui":
            case "open":
            case "menu":
                handleGui(sender);
                return true;

            case "set":
                handleSet(sender, args);
                return true;

            case "color":
                handleColor(sender, args);
                return true;

            case "resetcolor":
            case "reset":
                handleResetColor(sender, args);
                return true;

            case "clear":
            case "remove":
            case "delete":
                handleClear(sender, args);
                return true;

            case "info":
                handleInfo(sender, args);
                return true;

            case "list":
                handleList(sender, args);
                return true;

            case "driver":
                handleDriver(sender, args);
                return true;

            case "debug":
                handleDebug(sender, args);
                return true;

            case "reload":
                handleReload(sender);
                return true;

            case "help":
            default:
                sendHelp(sender);
                return true;
        }
    }

    // ==========================================================
    //  GUI
    // ==========================================================

    private void handleGui(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getLang().msg("msg.not-player"));
            return;
        }
        plugin.getGuiManager().openAdmin((Player) sender);
    }

    // ==========================================================
    //  set <玩家> <昵称>
    // ==========================================================

    private void handleSet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.getLang().text("help.admin-set"));
            return;
        }
        String targetName = args[1];
        String nickname = PlayerCommand.join(args, 2);

        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            // 在线玩家：走完整校验（允许颜色代码），并如实反馈结果
            final CommandSender r = sender;
            plugin.getNickManager().adminSet(online, nickname,
                    new NickManager.AdminCallback() {
                        @Override
                        public void done(boolean success, ValidateResult failure) {
                            if (success) {
                                r.sendMessage(plugin.getLang().msg("admin.set-success",
                                        LangManager.vars("player", online.getName(),
                                                "nickname", nickname)));
                            } else {
                                sendFailure(r, failure);
                            }
                        }
                    });
            return;
        }

        // 离线玩家
        final CommandSender r = sender;
        resolveOffline(targetName, new Callback() {
            @Override
            public void onFound(final String name, final java.util.UUID uuid) {
                final NickData data = new NickData(uuid, name, nickname,
                        null, com.ncnick.model.NickMode.PERMISSION, System.currentTimeMillis());
                Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
                    @Override
                    public void run() {
                        // 离线路径同样要做唯一性校验，避免管理员制造重复昵称
                        String normalized = com.ncnick.util.NicknameNormalizer.normalize(
                                nickname,
                                plugin.getConfigManager().isUniqueIgnoreColors(),
                                plugin.getConfigManager().isUniqueIgnoreCase(),
                                plugin.getConfigManager().isUniqueIgnoreSpaces());
                        final boolean taken = plugin.getStorage()
                                .isNicknameTaken(normalized, uuid);
                        final boolean saved;
                        if (taken) {
                            saved = false;
                        } else {
                            saved = plugin.getStorage().save(data);
                        }
                        Bukkit.getScheduler().runTask(plugin, new Runnable() {
                            @Override
                            public void run() {
                                if (taken) {
                                    r.sendMessage(plugin.getLang().msg("validate.duplicate",
                                            LangManager.vars("nickname", nickname)));
                                } else if (saved) {
                                    r.sendMessage(plugin.getLang().msg("admin.set-success",
                                            LangManager.vars("player", name,
                                                    "nickname", nickname)));
                                } else {
                                    r.sendMessage(plugin.getLang().msg("purchase.payment-failed"));
                                }
                            }
                        });
                    }
                });
            }

            @Override
            public void onNotFound() {
                r.sendMessage(plugin.getLang().msg("msg.player-not-found",
                        LangManager.vars("player", targetName)));
            }
        });
    }

    /**
     * 把校验失败原因转述给命令发送者。
     */
    private void sendFailure(CommandSender sender, ValidateResult failure) {
        if (failure == null) {
            sender.sendMessage(plugin.getLang().msg("purchase.payment-failed"));
            return;
        }
        String key = failure.getMessageKey();
        sender.sendMessage(plugin.getLang().msg(
                key == null ? "purchase.payment-failed" : key,
                failure.getParams()));
    }

    // ==========================================================
    //  color <玩家> <颜色>
    // ==========================================================

    private void handleColor(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.getLang().text("help.admin-color"));
            return;
        }
        String targetName = args[1];
        String colorInput = args[2];

        final ColorPreset preset = AdminGui.parseAdminColor(colorInput);
        if (preset == null || !preset.isValid()) {
            sender.sendMessage(plugin.getLang().msg("admin-gui.invalid-color"));
            return;
        }

        applyColor(sender, targetName, preset);
    }

    // ==========================================================
    //  resetcolor <玩家>
    // ==========================================================

    private void handleResetColor(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.getLang().text("help.admin-resetcolor"));
            return;
        }
        applyColor(sender, args[1], null);
    }

    /**
     * 应用颜色（null 表示重置）。
     */
    private void applyColor(final CommandSender sender, final String targetName,
                            final ColorPreset preset) {
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            updateColor(sender, online.getUniqueId(), online.getName(), preset);
            return;
        }
        resolveOffline(targetName, new Callback() {
            @Override
            public void onFound(String name, java.util.UUID uuid) {
                updateColor(sender, uuid, name, preset);
            }

            @Override
            public void onNotFound() {
                sender.sendMessage(plugin.getLang().msg("msg.player-not-found",
                        LangManager.vars("player", targetName)));
            }
        });
    }

    private void updateColor(final CommandSender sender, final java.util.UUID uuid,
                             final String name, final ColorPreset preset) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final NickData data = plugin.getStorage().load(uuid);
                if (data == null || !data.hasNickname()) {
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            sender.sendMessage(plugin.getLang().msg("validate.target-not-set",
                                    LangManager.vars("player", name)));
                        }
                    });
                    return;
                }
                final NickData updated = data.withColor(preset, System.currentTimeMillis());
                plugin.getStorage().save(updated);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        Player target = Bukkit.getPlayer(uuid);
                        if (target != null) {
                            plugin.getNickManager().put(uuid, updated);
                            plugin.getNickManager().apply(target);
                        }
                        if (preset == null) {
                            sender.sendMessage(plugin.getLang().msg("admin.reset-color-success",
                                    LangManager.vars("player", name)));
                        } else {
                            sender.sendMessage(plugin.getLang().msg("admin.set-color-success",
                                    LangManager.vars("player", name,
                                            "color", preset.describeColored())));
                        }
                    }
                });
            }
        });
    }

    // ==========================================================
    //  clear <玩家>
    // ==========================================================

    private void handleClear(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.getLang().text("help.admin-clear"));
            return;
        }
        String targetName = args[1];
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            doClear(sender, online.getUniqueId(), online.getName());
            return;
        }
        resolveOffline(targetName, new Callback() {
            @Override
            public void onFound(String name, java.util.UUID uuid) {
                doClear(sender, uuid, name);
            }

            @Override
            public void onNotFound() {
                sender.sendMessage(plugin.getLang().msg("msg.player-not-found",
                        LangManager.vars("player", targetName)));
            }
        });
    }

    private void doClear(final CommandSender sender, final java.util.UUID uuid,
                         final String name) {
        plugin.getNickManager().adminRemove(uuid, name, true);
        sender.sendMessage(plugin.getLang().msg("admin.clear-success",
                LangManager.vars("player", name)));
    }

    // ==========================================================
    //  info <玩家>
    // ==========================================================

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (sender instanceof Player) {
                new PlayerCommand(plugin).showInfo(sender, ((Player) sender).getName());
            } else {
                sender.sendMessage(plugin.getLang().text("help.admin-info"));
            }
            return;
        }
        new PlayerCommand(plugin).showInfo(sender, args[1]);
    }

    // ==========================================================
    //  list [页码]
    // ==========================================================

    private void handleList(final CommandSender sender, String[] args) {
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }
        final int p = page;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final int total = plugin.getStorage().countAll();
                final List<NickData> list = plugin.getStorage()
                        .list((p - 1) * LIST_PAGE_SIZE, LIST_PAGE_SIZE);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        int totalPages = Math.max(1,
                                (int) Math.ceil(total / (double) LIST_PAGE_SIZE));
                        sender.sendMessage(plugin.getLang().text("admin.info-header",
                                LangManager.vars("player",
                                        "第 " + p + "/" + totalPages + " 页，共 " + total + " 条")));
                        if (list.isEmpty()) {
                            sender.sendMessage(plugin.getLang().text("admin-gui.item-empty"));
                        } else {
                            for (NickData d : list) {
                                String nick = d.hasNickname()
                                        ? d.getColoredNickname() : "§7无";
                                sender.sendMessage("§7- §f" + d.getName() + " §7| " + nick
                                        + " §7| " + d.getMode().getDisplay());
                            }
                        }
                        sender.sendMessage(plugin.getLang().text("admin.info-footer"));
                    }
                });
            }
        });
    }

    // ==========================================================
    //  driver [status|download]
    // ==========================================================

    private void handleDriver(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase() : "status";

        if ("status".equals(action)) {
            showDriverStatus(sender);
            return;
        }

        if ("download".equals(action)) {
            forceDownload(sender, args.length >= 3 ? args[2] : null);
            return;
        }

        sender.sendMessage(plugin.getLang().text("help.admin-driver"));
    }

    private void showDriverStatus(CommandSender sender) {
        sender.sendMessage(plugin.getLang().text("driver.status-header"));
        for (Map.Entry<String, String> e : plugin.getDriverManager().status().entrySet()) {
            sender.sendMessage("§7- §f" + e.getKey() + "§7: §f" + e.getValue());
        }
        sender.sendMessage("§7目录: §f" + plugin.getDriverManager().libPath());
        sender.sendMessage(plugin.getLang().text("admin.info-footer"));
    }

    private void forceDownload(final CommandSender sender, String which) {
        if (downloading) {
            sender.sendMessage("§c已有下载任务在进行中，请稍候。");
            return;
        }

        final List<String> keys = new ArrayList<String>();
        if (which == null || which.isEmpty()) {
            keys.add("mysql");
            keys.add("sqlite");
        } else {
            String w = which.toLowerCase();
            if (!"mysql".equals(w) && !"sqlite".equals(w)) {
                sender.sendMessage("§c未知驱动: " + w + "（可用: mysql / sqlite）");
                return;
            }
            keys.add(w);
        }

        downloading = true;
        sender.sendMessage(plugin.getLang().text("admin.force-download-start",
                LangManager.vars("driver", String.join(", ", keys))));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    for (String key : keys) {
                        DriverArtifact artifact = plugin.getDriverManager().getArtifact(key);
                        if (artifact == null) {
                            continue;
                        }
                        DriverDownloader.Result r =
                                plugin.getDriverManager().forceDownload(key);
                        final String msg;
                        if (r != null && r.isSuccess()) {
                            msg = plugin.getLang().text("admin.force-download-ok",
                                    LangManager.vars("driver", artifact.getFileName(),
                                            "path", r.getFile().getAbsolutePath()));
                        } else {
                            msg = plugin.getLang().text("admin.force-download-fail",
                                    LangManager.vars("driver", artifact.getFileName(),
                                            "reason", r == null ? "未知错误" : r.getMessage()));
                        }
                        Bukkit.getScheduler().runTask(plugin, new Runnable() {
                            @Override
                            public void run() {
                                sender.sendMessage(msg);
                            }
                        });
                    }
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            sender.sendMessage(plugin.getLang()
                                    .text("admin.force-download-all-ok"));
                        }
                    });
                } finally {
                    downloading = false;
                }
            }
        });
    }

    // ==========================================================
    //  debug [on|off]
    // ==========================================================

    private void handleDebug(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            String v = args[1].toLowerCase();
            if ("on".equals(v) || "true".equals(v) || "1".equals(v)) {
                Debug.setEnabled(true);
            } else if ("off".equals(v) || "false".equals(v) || "0".equals(v)) {
                Debug.setEnabled(false);
            } else {
                sender.sendMessage(plugin.getLang().text("help.admin-debug"));
                return;
            }
        } else {
            // 切换
            Debug.setEnabled(!Debug.isEnabled());
        }

        sender.sendMessage(Debug.isEnabled()
                ? plugin.getLang().msg("admin.debug-on")
                : plugin.getLang().msg("admin.debug-off"));
        sender.sendMessage("§7注意：此开关仅在本次运行内有效，"
                + "持久化请修改 config.yml 的 debug.enabled 后重载。");
    }

    // ==========================================================
    //  reload
    // ==========================================================

    private void handleReload(CommandSender sender) {
        try {
            plugin.reloadPlugin();
            sender.sendMessage(plugin.getLang().msg("msg.reloaded"));

            // 回显关键状态：服主最关心"经济到底接上没有"，
            // 只回一句"已重载"会让人不确定有没有生效。
            // 这些行只在有信息量时输出，不刷屏。
            sender.sendMessage(plugin.reloadStatusText());
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "重载失败", t);
            sender.sendMessage(plugin.getLang().msg("msg.reload-failed",
                    LangManager.vars("error", String.valueOf(t.getMessage()))));
        }
    }

    // ==========================================================
    //  帮助
    // ==========================================================

    private void sendHelp(CommandSender sender) {
        LangManager lang = plugin.getLang();
        sender.sendMessage(lang.text("help.admin-header"));
        sender.sendMessage(lang.text("help.admin-gui"));
        sender.sendMessage(lang.text("help.admin-set"));
        sender.sendMessage(lang.text("help.admin-color"));
        sender.sendMessage(lang.text("help.admin-resetcolor"));
        sender.sendMessage(lang.text("help.admin-clear"));
        sender.sendMessage(lang.text("help.admin-info"));
        sender.sendMessage(lang.text("help.admin-list"));
        sender.sendMessage(lang.text("help.admin-driver"));
        sender.sendMessage(lang.text("help.admin-debug"));
        sender.sendMessage(lang.text("help.admin-reload"));
        sender.sendMessage(lang.text("help.footer"));
    }

    // ==========================================================
    //  补齐
    // ==========================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("ncnick.admin")) {
            return Collections.emptyList();
        }

        List<String> out = new ArrayList<String>();

        if (args.length == 1) {
            out.add("gui");
            out.add("set");
            out.add("color");
            out.add("resetcolor");
            out.add("clear");
            out.add("info");
            out.add("list");
            out.add("driver");
            out.add("debug");
            out.add("reload");
            out.add("help");
            return PlayerCommand.filter(out, args[0]);
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            if ("driver".equals(sub)) {
                out.add("status");
                out.add("download");
                return PlayerCommand.filter(out, args[1]);
            }
            if ("debug".equals(sub)) {
                out.add("on");
                out.add("off");
                return PlayerCommand.filter(out, args[1]);
            }
            // 玩家名补齐
            if ("set".equals(sub) || "color".equals(sub) || "resetcolor".equals(sub)
                    || "clear".equals(sub) || "info".equals(sub)) {
                return filterPlayers(args[1]);
            }
            return Collections.emptyList();
        }

        if (args.length == 3) {
            if ("driver".equals(sub) && "download".equalsIgnoreCase(args[1])) {
                out.add("mysql");
                out.add("sqlite");
                return PlayerCommand.filter(out, args[2]);
            }
            if ("color".equals(sub)) {
                // 常用颜色代码提示
                out.add("&a");
                out.add("&b");
                out.add("&c");
                out.add("&d");
                out.add("&e");
                out.add("&6");
                out.add("&#FF5555");
                out.add("&#55FF55");
                out.add("&#5555FF");
                return PlayerCommand.filter(out, args[2]);
            }
            if ("set".equals(sub)) {
                return Collections.emptyList();
            }
        }

        return Collections.emptyList();
    }

    /** 在线玩家名补齐。 */
    private List<String> filterPlayers(String prefix) {
        List<String> names = new ArrayList<String>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return PlayerCommand.filter(names, prefix);
    }

    // ==========================================================
    //  离线玩家解析
    // ==========================================================

    /** 回调接口 */
    private interface Callback {
        void onFound(String name, java.util.UUID uuid);

        void onNotFound();
    }

    /**
     * 解析离线玩家（异步）。
     */
    private void resolveOffline(final String name, final Callback callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                // 先查数据库（更可靠）
                final NickData byName = plugin.getStorage().loadByName(name);
                if (byName != null && byName.getUuid() != null) {
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            callback.onFound(byName.getName(), byName.getUuid());
                        }
                    });
                    return;
                }

                // 再查离线玩家缓存
                final OfflinePlayer offline;
                try {
                    @SuppressWarnings("deprecation")
                    OfflinePlayer op = Bukkit.getOfflinePlayer(name);
                    offline = op;
                } catch (Throwable t) {
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            callback.onNotFound();
                        }
                    });
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (offline == null || (!offline.hasPlayedBefore()
                                && offline.getName() == null)) {
                            callback.onNotFound();
                        } else {
                            callback.onFound(
                                    offline.getName() == null ? name : offline.getName(),
                                    offline.getUniqueId());
                        }
                    }
                });
            }
        });
    }
}
