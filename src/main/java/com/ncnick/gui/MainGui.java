package com.ncnick.gui;

import com.ncnick.NCNick;
import com.ncnick.config.LangManager;
import com.ncnick.model.NickData;
import com.ncnick.model.PayType;
import com.ncnick.util.Msg;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * 昵称主界面。
 *
 * <p>提供：设置昵称、设置颜色、删除昵称、查看信息。
 */
public class MainGui {

    /** 界面行数（3 行 = 27 格：上边框 + 内容行 + 下边框） */
    private static final int ROWS = 3;
    /**
     * 槽位定义。
     *
     * <p>排布（第 1 行为内容行，两侧留边框）：
     * <pre>
     *   第 0 行  █ █ █ █ █ █ █ █ █
     *   第 1 行  █ ① ░ ② ░ ③ ░ ④ █
     *   第 2 行  █ █ █ █ █ █ █ █ █
     * </pre>
     * ①=10 ②=12 ③=14 ④=16，中心为 13，左右成对对称。
     */
    private static final int SLOT_SET_NICKNAME = 10;
    private static final int SLOT_COLOR = 12;
    private static final int SLOT_INFO = 14;
    private static final int SLOT_REMOVE = 16;

    /**
     * 玩家自助确认界面的行数（3 行）。
     *
     * <p>公开给 {@code GuiManager} 使用，避免两处各写一个数字导致不一致。
     */
    public static final int CONFIRM_ROWS = 3;
    /** 确认键（偏左，中轴 13 左侧） */
    public static final int SLOT_CONFIRM_YES = 11;
    /** 取消键（偏右，与确认键关于中轴 13 对称） */
    public static final int SLOT_CONFIRM_NO = 15;

    private final NCNick plugin;

    public MainGui(NCNick plugin) {
        this.plugin = plugin;
    }

    /**
     * 打开主界面。
     */
    public void open(Player player) {
        LangManager lang = plugin.getLang();
        plugin.getGuiManager().setView(player.getUniqueId(), GuiManager.View.MAIN, player);

        org.bukkit.inventory.Inventory inv =
                GuiUtil.create(plugin.getConfigManager().getGuiMainTitle(), ROWS);

        NickData data = plugin.getNickManager().get(player.getUniqueId());
        boolean hasNick = data != null && plugin.getNickManager().isActive(player, data);

        // ---- 设置昵称 ----
        List<String> nickLore = new ArrayList<String>();
        nickLore.add("&7为你的账号设置一个中文昵称");
        nickLore.add("&7当前昵称：&r" + (hasNick ? data.getColoredNickname() : "&7无"));
        nickLore.add("");
        nickLore.add("&7限制：&e" + plugin.getConfigManager().getMinLength()
                + "-" + plugin.getConfigManager().getMaxLength() + " &7个字符");
        nickLore.add("&7全服唯一，不可与他人重复");
        nickLore.add("");
        nickLore.add("&e点击设置昵称");
        GuiUtil.set(inv, SLOT_SET_NICKNAME, GuiUtil.item(
                GuiUtil.material("NAME_TAG", "NAME_TAG"),
                "&a设置昵称", nickLore));

        // ---- 设置颜色 ----
        List<String> colorLore = new ArrayList<String>();
        colorLore.add("&7为你的昵称设置纯色或渐变颜色");
        if (hasNick) {
            String colorDesc = data.hasColor()
                    ? data.getColor().describeColored()
                    : "&7默认（无颜色）";
            colorLore.add("&7当前颜色：" + colorDesc);
        } else {
            colorLore.add("&c请先设置昵称");
        }
        colorLore.add("");
        colorLore.add("&e点击打开颜色界面");
        GuiUtil.set(inv, SLOT_COLOR, GuiUtil.item(
                GuiUtil.material("RED_DYE", "INK_SACK"),
                "&d昵称颜色", colorLore));

        // ---- 玩家信息 ----
        List<String> infoLore = new ArrayList<String>();
        infoLore.add("&7真实 ID：&f" + player.getName());
        infoLore.add("&7昵称：&r" + (hasNick ? data.getColoredNickname() : "&7未设置"));
        if (hasNick) {
            infoLore.add("&7颜色：&r" + (data.hasColor()
                    ? data.getColor().describeColored() : "&7默认"));
            infoLore.add("&7来源：&f" + data.getMode().getDisplay());
            infoLore.add("&7更新时间：&f" + formatTime(data.getUpdatedAt()));
        }
        infoLore.add("");
        infoLore.add("&7支付方式：&f" + describePayTypes());
        GuiUtil.set(inv, SLOT_INFO, GuiUtil.item(
                GuiUtil.material("BOOK", "BOOK"),
                "&e我的信息", infoLore));

        // ---- 删除昵称 ----
        List<String> removeLore = new ArrayList<String>();
        if (hasNick) {
            removeLore.add("&7清除你当前的昵称");
            removeLore.add("&c此操作不可撤销");
        } else {
            removeLore.add("&7你还没有设置昵称");
        }
        removeLore.add("");
        if (hasNick) {
            removeLore.add("&c点击清除昵称");
        }
        GuiUtil.set(inv, SLOT_REMOVE, GuiUtil.item(
                GuiUtil.material("BARRIER", "BARRIER"),
                (hasNick ? "&c清除昵称" : "&7清除昵称"), removeLore));

        // ---- 边框 ----
        // 无论 fill-empty 开关如何，边框都要画；
        // border 内部只填空槽位，不会覆盖上面已放置的按钮。
        GuiUtil.border(inv);

        // ---- 填充 ----
        if (plugin.getConfigManager().isGuiFillEmpty()) {
            GuiUtil.fillEmpty(inv, 0, inv.getSize() - 1);
        }

        GuiUtil.open(player, inv);
    }

    /**
     * 处理点击事件。
     *
     * @return 是否已处理（true 表示取消原版行为）
     */
    public boolean onClick(Player player, int slot) {
        switch (slot) {
            case SLOT_SET_NICKNAME:
                plugin.getGuiManager().openNicknameInput(player);
                return true;
            case SLOT_COLOR:
                NickData data = plugin.getNickManager().get(player.getUniqueId());
                if (data == null || !data.hasNickname()) {
                    player.sendMessage(plugin.getLang().msg("validate.not-set"));
                    return true;
                }
                plugin.getGuiManager().openColorMain(player);
                return true;
            case SLOT_INFO:
                // 刷新界面以展示最新信息
                open(player);
                return true;
            case SLOT_REMOVE:
                NickData d = plugin.getNickManager().get(player.getUniqueId());
                if (d == null || !d.hasNickname()) {
                    player.sendMessage(plugin.getLang().msg("validate.not-set"));
                    return true;
                }
                // 走**玩家自助**确认流程（PLAYER_CONFIRM，不受管理员门禁）。
                // 早期版本复用 AdminGui 的确认界面，导致：
                //   · 普通玩家点"确认"被权限门禁拦下，根本删不掉
                //   · 管理员删完自己会被跳到管理界面
                plugin.getGuiManager().openPlayerConfirmRemove(player);
                return true;
            default:
                return false;
        }
    }

    /** 描述可用的支付方式。 */
    private String describePayTypes() {
        List<PayType> types = plugin.getNickManager().availablePayTypes();
        if (types.isEmpty()) {
            return "&7无（仅权限免费）";
        }
        StringBuilder sb = new StringBuilder();
        for (PayType t : types) {
            if (sb.length() > 0) {
                sb.append("&7 / ");
            }
            sb.append("&f").append(t.getDisplay());
        }
        return sb.toString();
    }

    /** 格式化时间。 */
    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "未知";
        }
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        return fmt.format(new java.util.Date(millis));
    }
}
