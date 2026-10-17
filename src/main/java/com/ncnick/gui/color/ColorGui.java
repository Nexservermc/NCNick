package com.ncnick.gui.color;

import com.ncnick.NCNick;
import com.ncnick.gui.GuiManager;
import com.ncnick.gui.GuiUtil;
import com.ncnick.model.ColorPreset;
import com.ncnick.model.NickData;
import com.ncnick.model.PayType;
import com.ncnick.util.Msg;
import com.ncnick.util.VersionUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 颜色设置 GUI（第 6 条）。
 *
 * <h3>三层流程</h3>
 * <ol>
 *   <li><b>第一层</b>：选择"纯色"或"渐变"</li>
 *   <li><b>第二层</b>：
 *       <ul>
 *         <li>纯色 → 展示全部 16 种染料颜色，选一个即可</li>
 *         <li>渐变 → 先选起始色，再选结束色（两次选择）</li>
 *       </ul>
 *   </li>
 *   <li><b>第三层</b>：预览效果，确认后用金币或点券购买</li>
 * </ol>
 */
public class ColorGui {

    /** 第一层界面行数 */
    private static final int MAIN_ROWS = 3;
    /** 选色界面行数 */
    private static final int PICK_ROWS = 4;
    /** 预览界面行数 */
    private static final int PREVIEW_ROWS = 3;

    private static final int SLOT_SOLID = 11;
    private static final int SLOT_GRADIENT = 15;

    /** 每个玩家的选色会话状态 */
    private final Map<UUID, ColorSession> sessions = new ConcurrentHashMap<UUID, ColorSession>();

    private final NCNick plugin;

    public ColorGui(NCNick plugin) {
        this.plugin = plugin;
    }

    /**
     * 选色会话状态。
     *
     * <p><b>这是选色状态的唯一来源。</b>
     * 早期版本把 {@code gradient} 与 {@code pickingTo} 同时存在
     * GuiManager 的独立 Map 里，而 {@code onPickerClick} 内部切换
     * "选起始色 / 选结束色" 阶段时只更新了本会话、没有同步那份 Map，
     * 导致点击事件读到的 {@code pickingTo} 永远是初始值 ——
     * 表现为"选完起始色后，再点颜色仍然是在选起始色"。
     * 现已删除那份重复状态，统一从本会话读取。
     */
    public static final class ColorSession {
        /** 是否选择了渐变模式 */
        boolean gradient = false;
        /**
         * 渐变模式下当前所处阶段：
         * {@code false} = 正在选起始色，{@code true} = 正在选结束色。
         *
         * <p>必须与会话一起更新，不能另存一份。
         */
        boolean pickingTo = false;
        /** 起始色 ID */
        String fromId;
        /** 结束色 ID */
        String toId;

        /** 当前是否已选完整 */
        boolean isComplete() {
            if (!gradient) {
                return fromId != null;
            }
            return fromId != null && toId != null;
        }

        // ---- 对外只读访问器 ----
        // GuiManager 位于 com.ncnick.gui 包，与本类不同包，
        // 因此需要公开访问器读取阶段状态。

        /** 是否为渐变模式。 */
        public boolean isGradient() {
            return gradient;
        }

        /** 渐变模式下是否正在选结束色（false = 正在选起始色）。 */
        public boolean isPickingTo() {
            return pickingTo;
        }

        /** 转成 ColorPreset */
        ColorPreset toPreset() {
            ColorPalette.Color from = ColorPalette.byId(fromId);
            if (from == null) {
                return ColorPreset.none();
            }
            if (!gradient) {
                return ColorPreset.solid(from.getHex());
            }
            ColorPalette.Color to = ColorPalette.byId(toId);
            if (to == null) {
                return ColorPreset.solid(from.getHex());
            }
            return ColorPreset.gradient(from.getHex(), to.getHex());
        }
    }

    /** 获取或创建会话。 */
    public ColorSession session(UUID uuid) {
        ColorSession s = sessions.get(uuid);
        if (s == null) {
            s = new ColorSession();
            sessions.put(uuid, s);
        }
        return s;
    }

    /** 移除会话。 */
    public void clearSession(UUID uuid) {
        if (uuid != null) {
            sessions.remove(uuid);
        }
    }

    /** 清空所有会话。 */
    public void clearAll() {
        sessions.clear();
    }

    // ==========================================================
    //  第一层：选择纯色 / 渐变
    // ==========================================================

    /**
     * 打开第一层界面。
     */
    public void openMain(Player player) {
        plugin.getGuiManager().setView(player.getUniqueId(), GuiManager.View.COLOR_MAIN, player);
        ColorSession s = session(player.getUniqueId());
        // 重置选择状态
        s.gradient = false;
        s.fromId = null;
        s.toId = null;

        Inventory inv = GuiUtil.create(
                plugin.getLang().text("color.title-main"), MAIN_ROWS);

        // ---- 纯色 ----
        List<String> solidLore = new ArrayList<String>();
        solidLore.add("&7为昵称设置单一颜色");
        solidLore.add("&7可选 &e" + ColorPalette.count() + " &7种染料颜色");
        solidLore.add("");
        solidLore.add("&7价格：&e" + priceText(false, player));
        solidLore.add("");
        solidLore.add("&a点击选择纯色");
        GuiUtil.set(inv, SLOT_SOLID, GuiUtil.item(
                colorBlockMaterial("red"),
                "&a纯色", solidLore));

        // ---- 渐变 ----
        List<String> gradLore = new ArrayList<String>();
        gradLore.add("&7为昵称设置双色渐变");
        gradLore.add("&7从起始色平滑过渡到结束色");
        gradLore.add("");
        gradLore.add("&7价格：&e" + priceText(true, player));
        gradLore.add("");
        gradLore.add("&d点击选择渐变");
        GuiUtil.set(inv, SLOT_GRADIENT, GuiUtil.item(
                colorBlockMaterial("purple"),
                "&d渐变", gradLore));

        // 底部导航：与选色层/预览层/管理界面保持一致的槽位约定
        //   返回 = 最后一行最左  (size - 9)
        //   关闭 = 最后一行最右  (size - 1)
        int size = inv.getSize();
        GuiUtil.set(inv, size - 9, GuiUtil.item(
                GuiUtil.backMaterial(), "&e返回", null));
        GuiUtil.set(inv, size - 1, GuiUtil.item(
                GuiUtil.closeMaterial(), "&c关闭", null));

        // ---- 边框 ----
        // 放在按钮之后：border 只填空槽位，不会覆盖返回/关闭按钮
        GuiUtil.border(inv);

        if (plugin.getConfigManager().isGuiFillEmpty()) {
            GuiUtil.fillEmpty(inv, 0, inv.getSize() - 1);
        }

        GuiUtil.open(player, inv);
    }

    // ==========================================================
    //  第二层：选色
    // ==========================================================

    /**
     * 打开选色界面。
     *
     * @param player   玩家
     * @param gradient 是否为渐变模式
     * @param pickingTo 渐变模式下 true 表示正在选结束色
     */
    public void openPicker(Player player, boolean gradient, boolean pickingTo) {
        plugin.getGuiManager().setView(player.getUniqueId(), GuiManager.View.COLOR_PICKER, player);
        ColorSession s = session(player.getUniqueId());
        // 阶段状态必须写回会话，否则点击事件读到的是过期值
        s.gradient = gradient;
        s.pickingTo = pickingTo;

        String title;
        if (!gradient) {
            title = plugin.getLang().text("color.title-solid");
        } else if (!pickingTo) {
            title = plugin.getLang().text("color.title-gradient-from");
        } else {
            title = plugin.getLang().text("color.title-gradient-to");
        }

        Inventory inv = GuiUtil.create(title, PICK_ROWS);
        List<ColorPalette.Color> colors = ColorPalette.all();

        // 可用颜色上限
        int limit = availableColorLimit();
        int shown = Math.min(colors.size(), limit);

        for (int i = 0; i < shown; i++) {
            ColorPalette.Color color = colors.get(i);
            List<String> lore = new ArrayList<String>();
            lore.add("&7颜色值：&f#" + color.getHex());

            if (gradient) {
                if (!pickingTo) {
                    lore.add("&7作为 &e起始色");
                    if (color.getId().equals(s.fromId)) {
                        lore.add("&a✔ 已选择");
                    }
                } else {
                    lore.add("&7起始色：&r" + swatchOf(s.fromId));
                    lore.add("&7作为 &e结束色");
                    if (color.getId().equals(s.toId)) {
                        lore.add("&a✔ 已选择");
                    }
                }
            } else {
                if (color.getId().equals(s.fromId)) {
                    lore.add("&a✔ 当前选择");
                }
            }
            lore.add("");
            lore.add("&7预览：" + colorPreview(color));
            lore.add("&e点击选择");

            GuiUtil.set(inv, i, GuiUtil.item(
                    colorBlockMaterial(color.getId()),
                    "&f" + color.getDisplayName(), lore));
        }

        // 底部导航
        int size = inv.getSize();
        int backSlot = size - 9;
        int previewSlot = size - 5;
        int cancelSlot = size - 1;

        GuiUtil.set(inv, backSlot, GuiUtil.item(
                GuiUtil.backMaterial(), "&e返回", null));

        // 渐变模式下，预览/继续按钮
        if (gradient) {
            List<String> pLore = new ArrayList<String>();
            if (!pickingTo) {
                pLore.add("&7请先选择一个起始色");
            } else {
                pLore.add("&7起始色：&r" + swatchOf(s.fromId));
                pLore.add("&7结束色：&r" + swatchOf(s.toId));
                pLore.add("");
                if (s.isComplete()) {
                    pLore.add("&a点击查看预览");
                } else {
                    pLore.add("&c请先选择一个结束色");
                }
            }
            GuiUtil.set(inv, previewSlot, GuiUtil.item(
                    GuiUtil.infoMaterial(), "&e查看预览", pLore));
        }

        GuiUtil.set(inv, cancelSlot, GuiUtil.item(
                GuiUtil.closeMaterial(), "&c关闭", null));

        if (plugin.getConfigManager().isGuiFillEmpty()) {
            // 只填充未使用的槽位
            for (int i = shown; i < size - 9; i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, GuiUtil.filler());
                }
            }
            for (int i = size - 8; i < size; i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, GuiUtil.filler());
                }
            }
        }

        // ---- 边框 ----
        GuiUtil.border(inv);
        GuiUtil.open(player, inv);
    }

    /**
     * 处理选色界面的点击。
     *
     * @return 是否已处理
     */
    public boolean onPickerClick(Player player, boolean gradient, boolean pickingTo, int slot) {
        ColorSession s = session(player.getUniqueId());
        int size = sizeOfPicker();
        int limit = availableColorLimit();
        int shown = Math.min(ColorPalette.count(), limit);

        // 颜色槽位
        if (slot >= 0 && slot < shown && slot < size - 9) {
            ColorPalette.Color color = ColorPalette.at(slot);
            if (color == null) {
                return true;
            }
            if (!gradient) {
                s.gradient = false;
                s.fromId = color.getId();
                s.toId = null;
                // 纯色：直接进入预览
                openPreview(player);
                return true;
            }
            if (!pickingTo) {
                s.fromId = color.getId();
                s.gradient = true;
                // 选完起始色，进入选结束色
                openPicker(player, true, true);
                return true;
            }
            s.toId = color.getId();
            s.gradient = true;
            openPreview(player);
            return true;
        }

        // 底部按钮
        if (slot == size - 9) {
            // 返回
            if (gradient && pickingTo) {
                openPicker(player, true, false);
            } else {
                openMain(player);
            }
            return true;
        }
        if (slot == size - 5 && gradient) {
            if (s.isComplete()) {
                openPreview(player);
            } else if (!pickingTo) {
                openPicker(player, true, true);
            } else {
                player.sendMessage(plugin.getLang().prefix()
                        + plugin.getLang().text("color.hint-pick"));
            }
            return true;
        }
        if (slot == size - 1) {
            player.closeInventory();
            return true;
        }
        return true;
    }

    // ==========================================================
    //  第三层：预览与购买
    // ==========================================================

    /**
     * 打开预览界面。
     */
    public void openPreview(Player player) {
        plugin.getGuiManager().setView(player.getUniqueId(), GuiManager.View.COLOR_PREVIEW, player);
        ColorSession s = session(player.getUniqueId());
        if (!s.isComplete()) {
            openMain(player);
            return;
        }

        ColorPreset preset = s.toPreset();
        NickData data = plugin.getNickManager().get(player.getUniqueId());
        String nickname = data == null ? player.getName() : data.getNickname();
        if (nickname == null || nickname.isEmpty()) {
            nickname = player.getName();
        }

        Inventory inv = GuiUtil.create(
                plugin.getLang().text("color.title-preview"), PREVIEW_ROWS);

        // ---- 预览物品 ----
        List<String> previewLore = new ArrayList<String>();
        previewLore.add("&7你的昵称将显示为：");
        previewLore.add("&f" + preset.apply(nickname));
        previewLore.add("");
        previewLore.add("&7颜色：&r" + preset.describeColored());
        previewLore.add("");
        previewLore.add("&7在聊天栏中的效果：");
        previewLore.add("&f<" + preset.apply(nickname) + ">&7 你好！");
        GuiUtil.set(inv, 13, GuiUtil.item(
                GuiUtil.material("PAPER", "PAPER"),
                "&e效果预览", previewLore));

        // ---- 购买按钮（每种支付方式一个） ----
        List<PayType> payTypes = plugin.getNickManager().availablePayTypes(com.ncnick.model.ProductType.COLOR);
        boolean hasPermission = plugin.getConfigManager().isPermissionModeEnabled()
                && player.hasPermission(plugin.getConfigManager().getPermissionNode());

        // 免费（权限）
        if (hasPermission) {
            List<String> freeLore = new ArrayList<String>();
            freeLore.add("&7你拥有权限 &e" + plugin.getConfigManager().getPermissionNode());
            freeLore.add("&a可以免费设置此颜色");
            GuiUtil.set(inv, 11, GuiUtil.item(
                    GuiUtil.confirmMaterial(), "&a免费应用", freeLore));
        }

        // 金币
        int slot = hasPermission ? 12 : 11;
        if (payTypes.contains(PayType.COINS)) {
            double price = plugin.getNickManager().calcPrice(
                    com.ncnick.manager.NickManager.ChargeKind.COLOR,
                    PayType.COINS, null, preset);
            double balance = plugin.getVaultHook().getBalance(player);
            List<String> lore = new ArrayList<String>();
            lore.add("&7价格：&e" + formatMoney(price) + " 金币");
            lore.add("&7余额：&e" + formatMoney(balance) + " 金币");
            lore.add("");
            lore.add(balance >= price ? "&a点击用金币购买" : "&c余额不足");
            GuiUtil.set(inv, slot, GuiUtil.item(
                    GuiUtil.material("GOLD_INGOT", "GOLD_INGOT"),
                    "&6用金币购买", lore));
            slot++;
        }

        // 点券
        if (payTypes.contains(PayType.POINTS)) {
            int price = (int) Math.round(plugin.getNickManager().calcPrice(
                    com.ncnick.manager.NickManager.ChargeKind.COLOR,
                    PayType.POINTS, null, preset));
            int balance = plugin.getPlayerPointsHook().getPoints(player.getUniqueId());
            List<String> lore = new ArrayList<String>();
            lore.add("&7价格：&e" + price + " 点券");
            lore.add("&7余额：&e" + balance + " 点券");
            lore.add("");
            lore.add(balance >= price ? "&a点击用点券购买" : "&c余额不足");
            GuiUtil.set(inv, slot, GuiUtil.item(
                    GuiUtil.material("EMERALD", "EMERALD"),
                    "&a用点券购买", lore));
        }

        // 底部
        int size = inv.getSize();
        GuiUtil.set(inv, size - 9, GuiUtil.item(
                GuiUtil.backMaterial(), "&e返回", null));
        GuiUtil.set(inv, size - 1, GuiUtil.item(
                GuiUtil.cancelMaterial(), "&c取消", null));

        if (plugin.getConfigManager().isGuiFillEmpty()) {
            for (int i = 0; i < size; i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, GuiUtil.filler());
                }
            }
        }

        // ---- 边框 ----
        GuiUtil.border(inv);
        GuiUtil.open(player, inv);
    }

    /**
     * 处理预览界面的点击。
     *
     * @return 是否已处理
     */
    public boolean onPreviewClick(Player player, int slot) {
        ColorSession s = session(player.getUniqueId());
        if (!s.isComplete()) {
            openMain(player);
            return true;
        }
        ColorPreset preset = s.toPreset();
        int size = sizeOfPreview();

        boolean hasPermission = plugin.getConfigManager().isPermissionModeEnabled()
                && player.hasPermission(plugin.getConfigManager().getPermissionNode());
        List<PayType> payTypes = plugin.getNickManager().availablePayTypes(com.ncnick.model.ProductType.COLOR);

        // 计算各按钮实际槽位
        int freeSlot = hasPermission ? 11 : -1;
        int coinSlot = -1;
        int pointSlot = -1;

        int cursor = hasPermission ? 12 : 11;
        if (payTypes.contains(PayType.COINS)) {
            coinSlot = cursor;
            cursor++;
        }
        if (payTypes.contains(PayType.POINTS)) {
            pointSlot = cursor;
        }

        final PayType chosen;
        if (slot == freeSlot) {
            chosen = PayType.FREE;
        } else if (slot == coinSlot) {
            chosen = PayType.COINS;
        } else if (slot == pointSlot) {
            chosen = PayType.POINTS;
        } else if (slot == size - 9) {
            // 返回
            openPicker(player, s.gradient, s.gradient);
            return true;
        } else if (slot == size - 1) {
            clearSession(player.getUniqueId());
            player.closeInventory();
            return true;
        } else {
            return true;
        }

        // 执行购买
        player.closeInventory();
        plugin.getNickManager().setColor(player, preset, chosen, new Runnable() {
            @Override
            public void run() {
                // 完成后清理会话状态
                clearSession(player.getUniqueId());
            }
        });
        return true;
    }

    // ==========================================================
    //  辅助
    // ==========================================================

    /** 生成指定颜色的方块材质。 */
    private Material colorBlockMaterial(String colorId) {
        String modern = ColorPalette.modernBlockName(ColorPalette.byId(colorId));
        Material m = GuiUtil.material(modern, "WOOL");
        if (m != null) {
            return m;
        }
        // 极端回退
        return GuiUtil.material("WHITE_WOOL", "WOOL");
    }

    /** 颜色预览文本。 */
    private static String colorPreview(ColorPalette.Color color) {
        return Msg.solid(color.getHex(), "████ " + color.getDisplayName());
    }

    /** 生成某个已选颜色的色块。 */
    private static String swatchOf(String colorId) {
        ColorPalette.Color c = ColorPalette.byId(colorId);
        if (c == null) {
            return "&7未选择";
        }
        return ColorPalette.swatch(c, 6) + " &f" + c.getDisplayName();
    }

    /** 价格文本。 */
    private String priceText(boolean gradient, Player player) {
        List<PayType> types = plugin.getNickManager().availablePayTypes(com.ncnick.model.ProductType.COLOR);
        if (types.isEmpty()) {
            return "不可购买";
        }
        StringBuilder sb = new StringBuilder();
        for (PayType t : types) {
            double price = plugin.getNickManager().calcPrice(
                    com.ncnick.manager.NickManager.ChargeKind.COLOR, t, null,
                    gradient ? ColorPreset.gradient("FF5555", "5555FF")
                             : ColorPreset.solid("FF5555"));
            if (sb.length() > 0) {
                sb.append(" &7/ ");
            }
            sb.append("&e").append(formatMoney(price)).append(" ")
                    .append(t.getDisplay());
        }
        return sb.toString();
    }

    /** 金额格式化。 */
    private static String formatMoney(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount)) {
            return String.valueOf((long) amount);
        }
        return String.format("%.2f", amount);
    }

    /** 可用颜色数量上限。 */
    private int availableColorLimit() {
        String cfg = plugin.getConfigManager().getAvailableColors();
        if (cfg == null || "all".equalsIgnoreCase(cfg)) {
            return ColorPalette.count();
        }
        try {
            int n = Integer.parseInt(cfg.trim());
            return Math.max(1, Math.min(ColorPalette.count(), n));
        } catch (NumberFormatException e) {
            return ColorPalette.count();
        }
    }

    private int sizeOfPicker() {
        return PICK_ROWS * 9;
    }

    private int sizeOfPreview() {
        return PREVIEW_ROWS * 9;
    }
}
