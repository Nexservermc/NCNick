package com.ncnick.command;

import com.ncnick.NCNick;
import com.ncnick.config.LangManager;
import com.ncnick.model.NickData;
import com.ncnick.util.Debug;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * 玩家命令 /nc。
 *
 * <p>子命令：打开主界面、设置昵称、设置颜色、删除、查看信息、帮助。
 */
public class PlayerCommand implements TabExecutor {

    private final NCNick plugin;

    public PlayerCommand(NCNick plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            // 无参数：玩家打开界面，控制台显示帮助
            if (sender instanceof Player) {
                plugin.getGuiManager().openMain((Player) sender);
            } else {
                sendHelp(sender, label);
            }
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "open":
            case "gui":
            case "menu":
                handleOpen(sender);
                return true;

            case "set":
            case "create":
                handleSet(sender, args);
                return true;

            case "color":
            case "colour":
                handleColor(sender);
                return true;

            case "remove":
            case "clear":
            case "delete":
                handleRemove(sender);
                return true;

            case "info":
            case "me":
                handleInfo(sender);
                return true;

            case "help":
            default:
                sendHelp(sender, label);
                return true;
        }
    }

    // ==========================================================
    //  子命令实现
    // ==========================================================

    private void handleOpen(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        if (!hasPerm(player, "ncnick.use")) {
            return;
        }
        plugin.getGuiManager().openMain(player);
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        if (!hasPerm(player, "ncnick.use")) {
            return;
        }

        if (args.length < 2) {
            // 无参数时打开输入界面
            plugin.getGuiManager().openNicknameInput(player);
            return;
        }

        String nickname = join(args, 1);
        plugin.getNickManager().setNickname(player, nickname, null, null);
    }

    private void handleColor(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        if (!hasPerm(player, "ncnick.use")) {
            return;
        }
        NickData data = plugin.getNickManager().get(player.getUniqueId());
        if (data == null || !data.hasNickname()) {
            player.sendMessage(plugin.getLang().msg("validate.not-set"));
            return;
        }
        plugin.getGuiManager().openColorMain(player);
    }

    private void handleRemove(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        if (!hasPerm(player, "ncnick.remove")) {
            return;
        }
        plugin.getNickManager().removeOwn(player);
    }

    private void handleInfo(CommandSender sender) {
        if (!requirePlayer(sender)) {
            return;
        }
        Player player = (Player) sender;
        showInfo(player, player.getName());
    }

    // ==========================================================
    //  信息展示
    // ==========================================================

    /**
     * 展示玩家昵称信息。
     */
    public void showInfo(CommandSender receiver, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        final LangManager lang = plugin.getLang();

        if (target == null) {
            // 离线：异步查库
            final CommandSender r = receiver;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
                @Override
                public void run() {
                    final NickData data = plugin.getStorage().loadByName(targetName);
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            if (data == null) {
                                r.sendMessage(lang.msg("validate.target-not-set",
                                        LangManager.vars("player", targetName)));
                            } else {
                                printInfo(r, targetName, data);
                            }
                        }
                    });
                }
            });
            return;
        }

        NickData data = plugin.getNickManager().get(target.getUniqueId());
        if (data == null) {
            receiver.sendMessage(lang.msg("validate.target-not-set",
                    LangManager.vars("player", targetName)));
            return;
        }
        printInfo(receiver, target.getName(), data);
    }

    private void printInfo(CommandSender receiver, String playerName, NickData data) {
        LangManager lang = plugin.getLang();
        receiver.sendMessage(lang.text("admin.info-header",
                LangManager.vars("player", playerName)));
        receiver.sendMessage(lang.text("admin.info-name",
                LangManager.vars("name", data.getName())));
        receiver.sendMessage(lang.text("admin.info-uuid",
                LangManager.vars("uuid", String.valueOf(data.getUuid()))));
        receiver.sendMessage(lang.text("admin.info-nickname",
                LangManager.vars("nickname",
                        data.hasNickname() ? Msg_Colored(data) : "无")));
        receiver.sendMessage(lang.text("admin.info-color",
                LangManager.vars("color", data.hasColor()
                        ? data.getColor().describeColored() : "默认")));
        receiver.sendMessage(lang.text("admin.info-mode",
                LangManager.vars("mode", data.getMode().getDisplay())));
        receiver.sendMessage(lang.text("admin.info-updated",
                LangManager.vars("time", formatTime(data.getUpdatedAt()))));
        receiver.sendMessage(lang.text("admin.info-footer"));
    }

    private static String Msg_Colored(NickData data) {
        return data.getColoredNickname();
    }

    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "未知";
        }
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return fmt.format(new java.util.Date(millis));
    }

    // ==========================================================
    //  帮助
    // ==========================================================

    private void sendHelp(CommandSender sender, String label) {
        LangManager lang = plugin.getLang();
        sender.sendMessage(lang.text("help.header"));
        sender.sendMessage(lang.text("help.entry-open"));
        sender.sendMessage(lang.text("help.entry-set"));
        sender.sendMessage(lang.text("help.entry-color"));
        sender.sendMessage(lang.text("help.entry-remove"));
        sender.sendMessage(lang.text("help.entry-info"));
        sender.sendMessage(lang.text("help.entry-help"));
        sender.sendMessage(lang.text("help.footer"));

        // 管理员额外提示 /nca 管理入口
        if (com.ncnick.util.Permissions.isAdmin(sender)) {
            sender.sendMessage(lang.text("help.admin-header"));
            sender.sendMessage(lang.text("help.admin-gui"));
            sender.sendMessage(lang.text("help.footer"));
        }
    }

    // ==========================================================
    //  补齐
    // ==========================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<String>();

        if (args.length == 1) {
            if (com.ncnick.util.Permissions.has(sender, com.ncnick.util.Permissions.USE)) {
                out.add("open");
                out.add("set");
                out.add("color");
                out.add("info");
            }
            if (com.ncnick.util.Permissions.has(sender, com.ncnick.util.Permissions.NICKNAME_REMOVE)) {
                out.add("remove");
            }
            out.add("help");
            return filter(out, args[0]);
        }

        if (args.length == 2 && "set".equalsIgnoreCase(args[0])) {
            // 昵称无法补齐
            return Collections.emptyList();
        }

        return Collections.emptyList();
    }

    // ==========================================================
    //  工具
    // ==========================================================

    private boolean requirePlayer(CommandSender sender) {
        if (sender instanceof Player) {
            return true;
        }
        sender.sendMessage(plugin.getLang().msg("msg.not-player"));
        return false;
    }

    private boolean hasPerm(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || com.ncnick.util.Permissions.isAdmin(sender)) {
            return true;
        }
        sender.sendMessage(plugin.getLang().msg("msg.no-permission"));
        return false;
    }

    static List<String> filter(List<String> list, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return list;
        }
        String p = prefix.toLowerCase();
        List<String> out = new ArrayList<String>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(p)) {
                out.add(s);
            }
        }
        return out;
    }

    static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
