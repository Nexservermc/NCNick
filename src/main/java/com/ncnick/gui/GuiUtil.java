package com.ncnick.gui;

import com.ncnick.NCNick;
import com.ncnick.util.Debug;
import com.ncnick.util.Msg;
import com.ncnick.util.VersionUtil;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * GUI 构建工具类。
 *
 * <p>封装材质兼容、物品构建、边界检查等重复逻辑，
 * 使各具体界面代码更简洁、更不易出错。
 */
public final class GuiUtil {

    private GuiUtil() {
        // 工具类禁止实例化
    }

    /**
     * 获取材质，自动处理 1.13 改名。
     *
     * @param modern 1.13+ 的名称
     * @param legacy 1.8-1.12 的名称
     * @return 材质；都不存在时返回 null
     */
    public static Material material(String modern, String legacy) {
        Object m = VersionUtil.material(modern, legacy);
        return m instanceof Material ? (Material) m : null;
    }

    /**
     * 常用的界面装饰材质（玻璃板）。
     */
    public static Material paneMaterial() {
        return material("GRAY_STAINED_GLASS_PANE", "STAINED_GLASS_PANE");
    }

    /** 通用"返回"按钮材质。 */
    public static Material backMaterial() {
        return material("ARROW", "ARROW");
    }

    /** 通用"关闭"按钮材质。 */
    public static Material closeMaterial() {
        return material("BARRIER", "BARRIER");
    }

    /** 通用"确认"按钮材质。 */
    public static Material confirmMaterial() {
        return material("LIME_DYE", "INK_SACK");
    }

    /** 通用"取消"按钮材质。 */
    public static Material cancelMaterial() {
        return material("RED_DYE", "INK_SACK");
    }

    /** 图标材质（信息展示）。 */
    public static Material infoMaterial() {
        return material("BOOK", "BOOK");
    }

    /** 玩家头颅材质（部分版本需要额外处理，失败时回退）。 */
    public static Material headMaterial() {
        Material m = material("PLAYER_HEAD", "SKULL_ITEM");
        return m != null ? m : material("NAME_TAG", "NAME_TAG");
    }

    /**
     * 构建**玩家头颅**物品（显示真实皮肤）。
     *
     * <h3>为什么不能直接 new ItemStack(headMaterial())</h3>
     * <ul>
     *   <li><b>1.8 - 1.12</b>：玩家头颅的材质是 {@code SKULL_ITEM}，
     *       但必须使用数据值 <b>3</b>（{@code SkullType.PLAYER}）。
     *       数据值 0 是**骷髅头**，1 是凋灵骷髅头，2 是僵尸头，4 是苦力怕头。
     *       用 {@code new ItemStack(SKULL_ITEM)} 会得到骷髅头 —— 这正是
     *       "玩家头颅显示不正常"的根因。</li>
     *   <li><b>1.13+</b>：材质改名为 {@code PLAYER_HEAD}，
     *       但若不设置 {@code SkullMeta} 的拥有者，会显示默认/缺失贴图。
     *       需要调用 {@code setOwningPlayer(...)} 才能显示真实皮肤。</li>
     * </ul>
     *
     * <p>{@code setOwningPlayer(OfflinePlayer)} 是 1.12 才加入的 API，
     * 1.8.8 的编译目标里不存在，因此全部通过反射调用：
     * 优先 {@code setOwningPlayer}，失败回退到 1.8 就有的 {@code setOwner(String)}。
     *
     * @param owner 头颅所属玩家（决定皮肤）；可为 null
     * @param name  显示名
     * @param lore  描述
     * @return 玩家头颅物品；无法构建时回退为普通物品
     */
    public static ItemStack headItem(OfflinePlayer owner, String name, List<String> lore) {
        Material headMat = material("PLAYER_HEAD", "SKULL_ITEM");
        if (headMat == null) {
            // 极端兜底：没有头颅材质时用告示牌
            return item(material("NAME_TAG", "NAME_TAG"), name, lore);
        }

        ItemStack stack;
        try {
            if (VersionUtil.atLeast(13)) {
                // 1.13+：PLAYER_HEAD，无需数据值
                stack = new ItemStack(headMat, 1);
            } else {
                // 1.8-1.12：必须指定数据值 3 才是玩家头颅
                stack = new ItemStack(headMat, 1, (short) 3);
            }
        } catch (Throwable t) {
            Debug.log(Debug.GENERAL, "创建头颅物品失败: {}", t.getMessage());
            stack = new ItemStack(headMat, 1);
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        // ---- 设置头颅主人（决定显示的皮肤）----
        if (owner != null) {
            applySkullOwner(meta, owner);
        }

        // ---- 显示名与描述 ----
        if (name != null) {
            meta.setDisplayName(Msg.color(name));
        }
        if (lore != null && !lore.isEmpty()) {
            List<String> colored = new ArrayList<String>(lore.size());
            for (String line : lore) {
                colored.add(Msg.color(line));
            }
            meta.setLore(colored);
        }

        applyMeta(stack, meta);
        return stack;
    }

    /**
     * 给头颅 Meta 设置拥有者（决定皮肤）。
     *
     * <p>全程反射，因为 {@code setOwningPlayer} 在 1.8.8 API 中不存在。
     * 必须在**公开的 SkullMeta 接口**上取方法，否则会因实现类非 public
     * 而抛 {@code IllegalAccessException}。
     */
    private static void applySkullOwner(ItemMeta meta, OfflinePlayer owner) {
        Class<?> skullMetaClass;
        try {
            skullMetaClass = Class.forName("org.bukkit.inventory.meta.SkullMeta");
        } catch (Throwable ignored) {
            return;
        }
        if (!skullMetaClass.isInstance(meta)) {
            return;
        }

        // 方案 A：1.12+ 的 setOwningPlayer(OfflinePlayer) —— 支持离线玩家皮肤
        try {
            java.lang.reflect.Method m = skullMetaClass
                    .getMethod("setOwningPlayer", OfflinePlayer.class);
            m.invoke(meta, owner);
            return;
        } catch (Throwable ignored) {
            // 继续尝试方案 B
        }

        // 方案 B：1.8-1.11 的 setOwner(String)
        try {
            java.lang.reflect.Method m = skullMetaClass
                    .getMethod("setOwner", String.class);
            String name = owner.getName();
            if (name != null && !name.isEmpty()) {
                m.invoke(meta, name);
            }
        } catch (Throwable t) {
            Debug.log(Debug.GENERAL, "设置头颅主人失败: {}", t.getMessage());
        }
    }

    /**
     * 构建一个显示物品。
     *
     * @param material 材质
     * @param name     显示名（支持 & 与 #RRGGBB）
     * @param lore      描述行
     * @param amount    数量
     */
    public static ItemStack item(Material material, String name, List<String> lore, int amount) {
        if (material == null) {
            material = Material.STONE;
        }
        ItemStack stack = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (name != null) {
            meta.setDisplayName(Msg.color(name));
        }
        if (lore != null && !lore.isEmpty()) {
            List<String> colored = new ArrayList<String>(lore.size());
            for (String line : lore) {
                colored.add(Msg.color(line));
            }
            meta.setLore(colored);
        }
        applyMeta(stack, meta);
        return stack;
    }

    /** 构建物品（单个）。 */
    public static ItemStack item(Material material, String name, List<String> lore) {
        return item(material, name, lore, 1);
    }

    /** 构建物品（无描述）。 */
    public static ItemStack item(Material material, String name) {
        return item(material, name, null, 1);
    }

    /**
     * 写回 ItemMeta。
     *
     * <p>1.8 的某些实现 setItemMeta 可能失败，这里做容错。
     */
    private static void applyMeta(ItemStack stack, ItemMeta meta) {
        try {
            stack.setItemMeta(meta);
        } catch (Throwable t) {
            Debug.log(Debug.GENERAL, "设置物品 Meta 失败: {}", t.getMessage());
        }
    }

    /**
     * 构建占位用玻璃板。
     */
    public static ItemStack filler() {
        return item(paneMaterial(), " ", null, 1);
    }

    /**
     * 为界面绘制**一圈边框**（灰色玻璃板）。
     *
     * <p>把最外圈填满后，界面自然形成"内容区"，
     * 视觉上更整齐，也避免物品贴着边缘显得杂乱。
     *
     * <p>边框仅占用最外圈槽位：
     * <pre>
     *   6 行界面（54 格）:
     *   第 0 行  █████████   ← 上边框
     *   第 1-4 行█.......█   ← 内容区 4×7 = 28 格
     *   第 5 行  █████████   ← 下边框（也可放操作按钮）
     * </pre>
     * 已有物品的槽位不会被覆盖，因此可以在画完边框后再放按钮。
     *
     * @param inv 目标界面
     */
    public static void border(Inventory inv) {
        if (inv == null) {
            return;
        }
        int size = inv.getSize();
        if (size < 9) {
            return;
        }
        int rows = size / 9;
        ItemStack pane = filler();

        // 上下两整行
        for (int i = 0; i < 9; i++) {
            setIfEmpty(inv, i, pane);
            setIfEmpty(inv, size - 9 + i, pane);
        }
        // 左右两列（跳过已在上下行里的角）
        for (int r = 1; r < rows - 1; r++) {
            setIfEmpty(inv, r * 9, pane);
            setIfEmpty(inv, r * 9 + 8, pane);
        }
    }

    /** 仅当槽位为空时放入物品（不覆盖已有内容）。 */
    private static void setIfEmpty(Inventory inv, int slot, ItemStack item) {
        if (slot < 0 || slot >= inv.getSize()) {
            return;
        }
        ItemStack cur = inv.getItem(slot);
        if (cur == null || cur.getType() == Material.AIR) {
            inv.setItem(slot, item);
        }
    }

    /**
     * 安全填充空白槽位。
     */
    public static void fillEmpty(Inventory inv, int from, int to) {
        ItemStack filler = filler();
        for (int i = from; i <= to && i < inv.getSize(); i++) {
            ItemStack cur = inv.getItem(i);
            if (cur == null || cur.getType() == Material.AIR) {
                inv.setItem(i, filler);
            }
        }
    }

    /**
     * 创建一个箱子界面。
     *
     * @param title 标题（支持颜色）
     * @param rows  行数（1-6）
     */
    public static Inventory create(String title, int rows) {
        int r = Math.max(1, Math.min(6, rows));
        String t = Msg.color(title);
        // 1.9+ 有 createInventory(owner, size, title)，1.8 只有 (owner, size, title)
        try {
            return Bukkit.createInventory(null, r * 9, t);
        } catch (Throwable e) {
            // 标题过长时截断重试
            return Bukkit.createInventory(null, r * 9, Msg.truncateVisible(t, 32));
        }
    }

    /**
     * 把物品放到指定槽位，越界自动忽略。
     */
    public static void set(Inventory inv, int slot, ItemStack item) {
        if (inv == null || item == null) {
            return;
        }
        if (slot >= 0 && slot < inv.getSize()) {
            inv.setItem(slot, item);
        }
    }

    /**
     * 打开界面，捕获异常避免崩溃。
     *
     * @return 是否成功打开
     */
    public static boolean open(Player player, Inventory inv) {
        if (player == null || inv == null) {
            return false;
        }
        // 关键：必须在 openInventory **之前**登记本次要打开的界面实例。
        //
        // Bukkit 在打开新界面时会先对旧界面触发 InventoryCloseEvent。
        // 若不做登记，GuiManager 无法区分"玩家真的关掉了界面"
        // 与"因为切换到新界面而触发的关闭"，会把刚设好的视图状态清掉，
        // 导致界面开着但 view==NONE —— 点击不取消（物品可被拿走）且按钮失效。
        try {
            NCNick plugin = NCNick.getInstance();
            if (plugin != null && plugin.getGuiManager() != null) {
                plugin.getGuiManager().markOpened(player.getUniqueId(), inv);
            }
        } catch (Throwable ignored) {
            // 登记失败不影响界面打开本身
        }

        try {
            player.openInventory(inv);
            return true;
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "打开界面失败", t);
            return false;
        }
    }

    /**
     * 生成等距的槽位列表（用于居中排列）。
     *
     * @param count 元素数量
     * @param size  容器大小
     */
    public static int[] centeredSlots(int count, int size) {
        int rows = size / 9;
        int[] out = new int[count];
        int perRow = 7;
        int startRow = Math.max(0, (rows - (int) Math.ceil(count / (double) perRow)) / 2);
        for (int i = 0; i < count; i++) {
            int row = startRow + i / perRow;
            int col = 1 + i % perRow;
            out[i] = row * 9 + col;
        }
        return out;
    }

    /**
     * 生成一个从中心向外扩散的槽位列表（用于大量颜色展示）。
     */
    public static List<Integer> innerSlots(int size) {
        List<Integer> out = new ArrayList<Integer>();
        int rows = size / 9;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < 9; c++) {
                // 跳过最外圈
                if (r == 0 || r == rows - 1 || c == 0 || c == 8) {
                    continue;
                }
                out.add(r * 9 + c);
            }
        }
        return out;
    }

    /**
     * 底部导航栏的槽位。
     */
    public static int bottomSlot(int size, int offsetFromRight) {
        return size - 1 - offsetFromRight;
    }
}
