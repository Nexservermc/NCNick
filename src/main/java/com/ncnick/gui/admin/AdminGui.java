package com.ncnick.gui.admin;

import com.ncnick.NCNick;
import com.ncnick.gui.GuiManager;
import com.ncnick.gui.GuiUtil;
import com.ncnick.model.ColorPreset;
import com.ncnick.model.NickData;
import com.ncnick.util.Debug;
import com.ncnick.util.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * 管理员 GUI（第 7 条）。
 *
 * <p>提供在界面中管理各玩家昵称、颜色、删除等功能，
 * 无需记忆命令。
 *
 * <p>界面层级：
 * <ol>
 *   <li>玩家列表（分页）</li>
 *   <li>玩家详情（设置昵称 / 设置颜色 / 重置颜色 / 清除昵称）</li>
 *   <li>确认操作</li>
 * </ol>
 */
public class AdminGui {

    /** 列表界面行数 */
    private static final int LIST_ROWS = 6;
    /** 详情界面行数 */
    private static final int DETAIL_ROWS = 4;
    /** 确认界面行数 */
    private static final int CONFIRM_ROWS = 3;

    /** 列表每页数量（去掉边框后可用 28 个槽位） */
    private static final int PAGE_SIZE = 28;

    /** 详情界面槽位 */
    private static final int SLOT_SET_NAME = 10;
    private static final int SLOT_SET_COLOR = 12;
    private static final int SLOT_RESET_COLOR = 14;
    private static final int SLOT_CLEAR = 16;

    /** 确认界面槽位 */
    private static final int SLOT_CONFIRM_YES = 11;
    private static final int SLOT_CONFIRM_NO = 15;

    private final NCNick plugin;

    public AdminGui(NCNick plugin) {
        this.plugin = plugin;
    }

    // ==========================================================
    //  玩家列表（分页）
    // ==========================================================

    /**
     * 打开玩家列表。
     *
     * @param page 页码，从 0 开始
     */
    public void openPlayerList(final Player admin, final int page) {
        // 权限校验：非管理员直接拒绝
        if (!plugin.getGuiManager().checkAdmin(admin)) {
            return;
        }
        plugin.getGuiManager().setView(admin.getUniqueId(),
                GuiManager.View.ADMIN_MAIN, admin);
        // 记录服务端上下文（页码与目标）
        GuiManager.AdminContext ctx = plugin.getGuiManager().adminContext(admin.getUniqueId());
        ctx.page = page;
        ctx.clearTarget();

        // 异步查库（避免阻塞主线程）
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final int total = plugin.getStorage().countAll();
                final List<NickData> list = plugin.getStorage()
                        .list(page * PAGE_SIZE, PAGE_SIZE);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!admin.isOnline()) {
                            return;
                        }
                        buildListInventory(admin, page, total, list);
                    }
                });
            }
        });
    }

    /**
     * 在主线程构建列表界面。
     */
    private void buildListInventory(Player admin, int page, int total, List<NickData> list) {
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
        if (page >= totalPages) {
            page = totalPages - 1;
        }

        Inventory inv = GuiUtil.create(
                plugin.getLang().text("admin-gui.title-main"), LIST_ROWS);

        // 填充玩家物品
        int slot = 0;
        for (NickData data : list) {
            if (slot >= PAGE_SIZE) {
                break;
            }
            int invSlot = mapListSlot(slot);
            slot++;

            String playerName = resolveName(data);
            List<String> lore = new ArrayList<String>();
            lore.add("&7昵称：&r" + (data.hasNickname()
                    ? data.getColoredNickname() : "&7无"));
            lore.add("&7颜色：&r" + (data.hasColor()
                    ? data.getColor().describeColored() : "&7默认"));
            lore.add("&7来源：&f" + data.getMode().getDisplay());
            lore.add("&7真实 ID：&f" + data.getName());
            lore.add("");
            lore.add("&e点击管理此玩家");

            // 使用玩家头颅（内部处理了 1.8-1.12 的 SKULL_ITEM 数据值与皮肤设置）
            org.bukkit.OfflinePlayer headOwner = resolveOfflinePlayer(data);
            GuiUtil.set(inv, invSlot, GuiUtil.headItem(
                    headOwner, "&f" + playerName, lore));
        }

        // 空提示
        if (list.isEmpty()) {
            List<String> emptyLore = new ArrayList<String>();
            emptyLore.add("&7当前还没有玩家设置昵称");
            GuiUtil.set(inv, 22, GuiUtil.item(
                    GuiUtil.infoMaterial(), "&7暂无数据", emptyLore));
        }

        // ---- 底部导航（对称排布）----
        //
        //  列:   0      1    2      3    4      5    6      7    8
        //      [上一页] ░ [刷新] ░ [信息] ░ [搜索] ░ [下一页]
        //
        // 以第 4 列（bottomRow + 4）为轴左右成对：0↔8、2↔6。
        // 关闭按钮放在右上角（槽位 8），不占用底部按钮位。
        int size = inv.getSize();
        int bottomRow = size - 9;

        if (page > 0) {
            GuiUtil.set(inv, bottomRow, GuiUtil.item(
                    GuiUtil.backMaterial(), "&e上一页",
                    listOf("&7当前第 &f" + (page + 1) + " &7页")));
        }

        // 页码信息（正中）
        List<String> pageLore = new ArrayList<String>();
        pageLore.add("&7共 &f" + total + " &7位玩家");
        pageLore.add("&7第 &f" + (page + 1) + " &7/ &f" + totalPages + " &7页");
        GuiUtil.set(inv, bottomRow + 4, GuiUtil.item(
                GuiUtil.infoMaterial(), "&e页面信息", pageLore));

        if (page < totalPages - 1) {
            GuiUtil.set(inv, bottomRow + 8, GuiUtil.item(
                    GuiUtil.material("ARROW", "ARROW"), "&e下一页",
                    listOf("&7当前第 &f" + (page + 2) + " &7页")));
        }

        // 刷新（轴左）
        GuiUtil.set(inv, bottomRow + 2, GuiUtil.item(
                GuiUtil.material("PAPER", "PAPER"), "&a刷新列表", null));

        // 搜索（轴右）
        GuiUtil.set(inv, bottomRow + 6, GuiUtil.item(
                GuiUtil.material("COMPASS", "COMPASS"), "&e搜索玩家",
                listOf("&7按玩家名搜索", "&e点击开始搜索")));

        // 关闭（右上角，位于边框上）
        GuiUtil.set(inv, 8, GuiUtil.item(
                GuiUtil.closeMaterial(), "&c关闭", null));

        if (plugin.getConfigManager().isGuiFillEmpty()) {
            for (int i = 0; i < size; i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, GuiUtil.filler());
                }
            }
        }

        // ---- 边框 ----
        GuiUtil.border(inv);
        GuiUtil.open(admin, inv);
    }

    /**
     * 把列表索引映射到界面槽位（跳过边框，4 行 × 7 列）。
     */
    private static int mapListSlot(int index) {
        int row = index / 7;
        int col = index % 7;
        return (row + 1) * 9 + (col + 1);
    }

    /**
     * 处理列表界面点击。
     *
     * @param page        当前页码（来自服务端上下文）
     * @param clickedName 被点击行的玩家名（null 表示点到了非玩家物品）
     * @return 是否已处理
     */
    public boolean onListClick(Player admin, int page, int slot, String clickedName) {
        int size = LIST_ROWS * 9;
        int bottomRow = size - 9;

        // 关闭（右上角）
        if (slot == 8) {
            admin.closeInventory();
            return true;
        }
        // 上一页（轴左端）
        if (slot == bottomRow && page > 0) {
            openPlayerList(admin, page - 1);
            return true;
        }
        // 下一页（轴右端）
        if (slot == bottomRow + 8) {
            openPlayerList(admin, page + 1);
            return true;
        }
        // 刷新（轴左）
        if (slot == bottomRow + 2) {
            openPlayerList(admin, page);
            return true;
        }
        // 搜索（轴右）
        if (slot == bottomRow + 6) {
            startSearch(admin);
            return true;
        }

        // 点击玩家物品
        if (clickedName != null && !clickedName.isEmpty()) {
            admin.closeInventory();
            final String name = clickedName;
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override
                public void run() {
                    openPlayerDetail(admin, name);
                }
            }, 2L);
            return true;
        }
        return true;
    }

    // ==========================================================
    //  玩家详情
    // ==========================================================

    /**
     * 打开玩家详情界面。
     */
    public void openPlayerDetail(final Player admin, final String targetName) {
        // 权限校验
        if (!plugin.getGuiManager().checkAdmin(admin)) {
            return;
        }
        plugin.getGuiManager().setView(admin.getUniqueId(),
                GuiManager.View.ADMIN_PLAYER, admin);
        // 先记录目标名，UUID 在异步解析后补写
        GuiManager.AdminContext ctx = plugin.getGuiManager().adminContext(admin.getUniqueId());
        ctx.targetName = targetName;
        ctx.targetUuid = null;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                // 先看在线玩家
                Player online = Bukkit.getPlayerExact(targetName);
                final NickData data;
                final UUID uuid;
                final String realName;
                if (online != null) {
                    uuid = online.getUniqueId();
                    realName = online.getName();
                    NickData cached = plugin.getNickManager().get(uuid);
                    data = cached != null ? cached : plugin.getStorage().load(uuid);
                } else {
                    NickData loaded = plugin.getStorage().loadByName(targetName);
                    data = loaded;
                    uuid = loaded == null ? null : loaded.getUuid();
                    realName = loaded == null ? targetName : loaded.getName();
                }

                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!admin.isOnline()) {
                            return;
                        }
                        // 把权威 UUID 写回服务端上下文，供点击路由使用
                        GuiManager.AdminContext c =
                                plugin.getGuiManager().adminContext(admin.getUniqueId());
                        c.targetName = realName;
                        c.targetUuid = uuid;
                        buildDetailInventory(admin, targetName, realName, uuid, data);
                    }
                });
            }
        });
    }

    private void buildDetailInventory(Player admin, String targetName, String realName,
                                      UUID uuid, NickData data) {
        String title = plugin.getLang().text("admin-gui.title-player")
                .replace("{player}", targetName);
        Inventory inv = GuiUtil.create(title, DETAIL_ROWS);

        boolean hasNick = data != null && data.hasNickname();
        String nickDisplay = hasNick ? data.getColoredNickname() : "&7无";
        String colorDisplay = (data != null && data.hasColor())
                ? data.getColor().describeColored() : "&7默认（无颜色）";

        // ---- 玩家信息 ----
        List<String> infoLore = new ArrayList<String>();
        infoLore.add("&7真实 ID：&f" + realName);
        infoLore.add("&7UUID：&f" + (uuid == null ? "未知" : uuid.toString()));
        infoLore.add("&7昵称：&r" + nickDisplay);
        infoLore.add("&7颜色：&r" + colorDisplay);
        infoLore.add("&7来源：&f" + (data == null ? "无" : data.getMode().getDisplay()));
        GuiUtil.set(inv, 4, GuiUtil.headItem(
                uuid == null ? null : Bukkit.getOfflinePlayer(uuid),
                "&f" + realName, infoLore));

        // ---- 设置昵称 ----
        GuiUtil.set(inv, SLOT_SET_NAME, GuiUtil.item(
                GuiUtil.material("NAME_TAG", "NAME_TAG"),
                plugin.getLang().text("admin-gui.item-set-name"),
                listOf("&7为玩家设置新的昵称",
                        "&7当前：&r" + nickDisplay,
                        "",
                        "&e点击后在聊天栏输入")));

        // ---- 设置颜色 ----
        GuiUtil.set(inv, SLOT_SET_COLOR, GuiUtil.item(
                GuiUtil.material("RED_DYE", "INK_SACK"),
                plugin.getLang().text("admin-gui.item-set-color"),
                listOf("&7为玩家设置昵称颜色",
                        "&7当前：&r" + colorDisplay,
                        "",
                        "&7支持 &f&a&7 / &f&c&7 / &f&#FF5555",
                        "&e点击后在聊天栏输入")));

        // ---- 重置颜色 ----
        GuiUtil.set(inv, SLOT_RESET_COLOR, GuiUtil.item(
                GuiUtil.material("MILK_BUCKET", "MILK_BUCKET"),
                plugin.getLang().text("admin-gui.item-reset-color"),
                listOf("&7移除该玩家的昵称颜色",
                        "",
                        "&e点击重置")));

        // ---- 清除昵称 ----
        GuiUtil.set(inv, SLOT_CLEAR, GuiUtil.item(
                GuiUtil.material("BARRIER", "BARRIER"),
                plugin.getLang().text("admin-gui.item-clear"),
                listOf("&7彻底删除该玩家的昵称数据",
                        "&c此操作不可撤销",
                        "",
                        "&c点击清除")));

        // ---- 底部 ----
        int size = inv.getSize();
        GuiUtil.set(inv, size - 9, GuiUtil.item(
                GuiUtil.backMaterial(), "&e返回列表", null));
        GuiUtil.set(inv, size - 1, GuiUtil.item(
                GuiUtil.closeMaterial(), "&c关闭", null));

        if (plugin.getConfigManager().isGuiFillEmpty()) {
            for (int i = 0; i < size; i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, GuiUtil.filler());
                }
            }
        }

        // ---- 边框 ----
        GuiUtil.border(inv);
        GuiUtil.open(admin, inv);
    }

    /**
     * 处理详情界面点击。
     */
    public boolean onDetailClick(Player admin, int slot, String targetName, UUID targetUuid) {
        int size = DETAIL_ROWS * 9;

        if (slot == size - 9) {
            openPlayerList(admin, 0);
            return true;
        }
        if (slot == size - 1) {
            admin.closeInventory();
            return true;
        }

        switch (slot) {
            case SLOT_SET_NAME:
                admin.closeInventory();
                plugin.getGuiManager().startInput(admin,
                        GuiManager.InputType.ADMIN_SET_NAME, targetUuid,
                        GuiManager.View.ADMIN_PLAYER);
                admin.sendMessage(plugin.getLang().prefix()
                        + plugin.getLang().text("admin-gui.prompt-nickname"));
                admin.sendMessage(plugin.getLang().prefix() + plugin.getLang().text("input.cancel-hint"));
                return true;

            case SLOT_SET_COLOR:
                admin.closeInventory();
                plugin.getGuiManager().startInput(admin,
                        GuiManager.InputType.ADMIN_SET_COLOR, targetUuid,
                        GuiManager.View.ADMIN_PLAYER);
                admin.sendMessage(plugin.getLang().prefix()
                        + plugin.getLang().text("admin-gui.prompt-color"));
                admin.sendMessage(plugin.getLang().prefix() + plugin.getLang().text("input.cancel-hint"));
                return true;

            case SLOT_RESET_COLOR:
                admin.closeInventory();
                doResetColor(admin, targetUuid, targetName);
                return true;

            case SLOT_CLEAR:
                admin.closeInventory();
                openConfirm(admin, targetUuid, targetName, GuiManager.View.ADMIN_MAIN);
                return true;

            default:
                return true;
        }
    }

    // ==========================================================
    //  确认界面
    // ==========================================================

    /**
     * 打开确认删除界面。
     */
    public void openConfirm(Player admin, final UUID targetUuid, final String targetName,
                            GuiManager.View returnView) {
        // 权限校验
        if (!plugin.getGuiManager().checkAdmin(admin)) {
            return;
        }
        plugin.getGuiManager().setView(admin.getUniqueId(),
                GuiManager.View.ADMIN_CONFIRM, admin);
        // 记录待确认目标（权威 UUID），供确认点击使用
        GuiManager.AdminContext ctx = plugin.getGuiManager().adminContext(admin.getUniqueId());
        ctx.targetUuid = targetUuid;
        ctx.targetName = targetName;
        ctx.returnView = returnView;
        Inventory inv = GuiUtil.create(
                plugin.getLang().text("admin-gui.title-confirm"), CONFIRM_ROWS);

        // 目标信息
        List<String> infoLore = new ArrayList<String>();
        infoLore.add("&7玩家：&f" + targetName);
        infoLore.add("");
        infoLore.add("&c将删除该玩家的昵称数据");
        GuiUtil.set(inv, 4, GuiUtil.headItem(
                targetUuid == null ? null : Bukkit.getOfflinePlayer(targetUuid),
                "&f" + targetName, infoLore));

        // 确认
        GuiUtil.set(inv, SLOT_CONFIRM_YES, GuiUtil.item(
                GuiUtil.material("LIME_WOOL", "WOOL"),
                plugin.getLang().text("admin-gui.confirm-yes"),
                listOf("&c确认执行此操作")));

        // 取消
        GuiUtil.set(inv, SLOT_CONFIRM_NO, GuiUtil.item(
                GuiUtil.material("RED_WOOL", "WOOL"),
                plugin.getLang().text("admin-gui.confirm-no"),
                listOf("&7返回上一页")));

        if (plugin.getConfigManager().isGuiFillEmpty()) {
            for (int i = 0; i < inv.getSize(); i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, GuiUtil.filler());
                }
            }
        }

        // ---- 边框 ----
        GuiUtil.border(inv);
        GuiUtil.open(admin, inv);
    }

    /**
     * 处理确认界面点击。
     *
     * <p>删除完成后<b>只关闭界面，不自动跳回列表</b>。
     * 早期实现会在 10 tick 后重新打开玩家列表，效果是"界面关了又自己弹回来"，
     * 而且强制回到第 1 页，管理员会失去原先的位置感。
     * 需要继续管理时重新输入 {@code /nca} 即可。
     *
     * @param admin      管理员
     * @param slot       点击槽位
     * @param targetUuid 目标玩家（来自服务端上下文）
     * @param targetName 目标玩家名（来自服务端上下文）
     * @return 是否已处理
     */
    public boolean onConfirmClick(Player admin, int slot, UUID targetUuid, String targetName) {
        if (slot == SLOT_CONFIRM_YES) {
            admin.closeInventory();
            plugin.getNickManager().adminRemove(targetUuid, targetName, true);
            admin.sendMessage(plugin.getLang().msg("remove.admin-success",
                    com.ncnick.config.LangManager.vars("player", targetName)));
            // 刻意不重新打开任何界面：
            // 早期实现会在 10 tick 后自动重开玩家列表，看起来像"关了又弹回来"，
            // 而且强制回到第 1 页。需要继续管理时重新输入 /nca 即可。
            return true;
        }

        if (slot == SLOT_CONFIRM_NO) {
            // 取消：回到进入确认页之前的那个界面，而不是硬跳回列表
            admin.closeInventory();
            backFromConfirm(admin);
            return true;
        }
        return true;
    }

    /**
     * 从确认页返回。
     *
     * <p>优先回到管理员进入确认页时所处的界面（存在
     * {@link GuiManager.AdminContext#returnView}），
     * 例如从玩家详情页点"删除"就退回详情页，保持操作连贯。
     * 若来源不可用则退回列表。
     */
    private void backFromConfirm(final Player admin) {
        GuiManager.AdminContext ctx = plugin.getGuiManager().adminContext(admin.getUniqueId());
        GuiManager.View returnView = ctx.returnView;
        ctx.returnView = GuiManager.View.ADMIN_MAIN;

        if (returnView == GuiManager.View.ADMIN_PLAYER
                && ctx.targetName != null && !ctx.targetName.isEmpty()) {
            // 从玩家详情页来的 → 退回详情页
            openPlayerDetail(admin, ctx.targetName);
            return;
        }
        // 其余情况退回列表，并保持原来的页码
        openPlayerList(admin, Math.max(0, ctx.page));
    }

    // ==========================================================
    //  聊天输入处理
    // ==========================================================

    /**
     * 处理搜索输入。
     */
    public void onSearchInput(final Player admin, final String keyword) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final NickData found;
                Player online = Bukkit.getPlayerExact(keyword);
                if (online != null) {
                    NickData cached = plugin.getNickManager().get(online.getUniqueId());
                    found = cached != null ? cached : plugin.getStorage().load(online.getUniqueId());
                } else {
                    found = plugin.getStorage().loadByName(keyword);
                }
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!admin.isOnline()) {
                            return;
                        }
                        if (found == null) {
                            admin.sendMessage(plugin.getLang().msg(
                                    "admin-gui.search-no-result"));
                            openPlayerList(admin, 0);
                        } else {
                            openPlayerDetail(admin, found.getName());
                        }
                    }
                });
            }
        });
    }

    /**
     * 处理设置昵称输入。
     */
    public void onSetNameInput(final Player admin, final UUID targetUuid, final String nickname) {
        if (targetUuid == null) {
            admin.sendMessage(plugin.getLang().msg("msg.player-not-found",
                    com.ncnick.config.LangManager.vars("player", "未知")));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                Player target = Bukkit.getPlayer(targetUuid);
                if (target == null) {
                    // 离线玩家：直接写库
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(targetUuid);
                    saveOfflineNickname(targetUuid,
                            offline.getName() == null ? "未知" : offline.getName(), nickname);
                    admin.sendMessage(plugin.getLang().msg("admin.set-success",
                            com.ncnick.config.LangManager.vars(
                                    "player", offline.getName() == null ? "未知" : offline.getName(),
                                    "nickname", nickname)));
                } else {
                    // 在线玩家：走完整校验（允许颜色代码），并如实反馈结果
                    plugin.getNickManager().adminSet(target, nickname,
                            new com.ncnick.manager.NickManager.AdminCallback() {
                                @Override
                                public void done(boolean success,
                                                 com.ncnick.manager.ValidateResult failure) {
                                    if (success) {
                                        admin.sendMessage(plugin.getLang()
                                                .msg("admin.set-success",
                                                        com.ncnick.config.LangManager.vars(
                                                                "player", target.getName(),
                                                                "nickname", nickname)));
                                    } else if (failure != null) {
                                        admin.sendMessage(plugin.getLang().msg(
                                                failure.getMessageKey(),
                                                failure.getParams()));
                                    } else {
                                        admin.sendMessage(plugin.getLang()
                                                .msg("purchase.payment-failed"));
                                    }
                                    if (admin.isOnline()) {
                                        openPlayerList(admin, 0);
                                    }
                                }
                            });
                    return;
                }
                openPlayerList(admin, 0);
            }
        });
    }

    /**
     * 离线玩家写库。
     */
    private void saveOfflineNickname(UUID uuid, String name, String nickname) {
        final NickData data = new NickData(uuid, name, nickname,
                null, com.ncnick.model.NickMode.PERMISSION, System.currentTimeMillis());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                plugin.getStorage().save(data);
            }
        });
    }

    /**
     * 处理设置颜色输入（管理员可直接用颜色代码）。
     */
    public void onSetColorInput(final Player admin, final UUID targetUuid, final String input) {
        if (targetUuid == null) {
            return;
        }
        // 解析颜色：支持 &#RRGGBB、&a、#RRGGBB
        ColorPreset preset = parseAdminColor(input);
        if (preset == null || !preset.isValid()) {
            admin.sendMessage(plugin.getLang().msg("admin-gui.invalid-color"));
            openPlayerList(admin, 0);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                NickData data = plugin.getStorage().load(targetUuid);
                final String name = data == null
                        ? String.valueOf(targetUuid) : data.getName();
                if (data != null) {
                    final NickData updated = data.withColor(preset, System.currentTimeMillis());
                    plugin.getStorage().save(updated);
                    // 更新在线缓存
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            Player target = Bukkit.getPlayer(targetUuid);
                            if (target != null) {
                                plugin.getNickManager().put(targetUuid, updated);
                                plugin.getNickManager().apply(target);
                            }
                            admin.sendMessage(plugin.getLang().msg("admin.set-color-success",
                                    com.ncnick.config.LangManager.vars(
                                            "player", name,
                                            "color", preset.describeColored())));
                            openPlayerList(admin, 0);
                        }
                    });
                } else {
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            admin.sendMessage(plugin.getLang().msg("validate.target-not-set",
                                    com.ncnick.config.LangManager.vars("player", name)));
                            openPlayerList(admin, 0);
                        }
                    });
                }
            }
        });
    }

    /**
     * 重置玩家颜色。
     */
    private void doResetColor(final Player admin, final UUID targetUuid, final String targetName) {
        if (targetUuid == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final NickData data = plugin.getStorage().load(targetUuid);
                if (data != null) {
                    final NickData updated = data.withColor(null, System.currentTimeMillis());
                    plugin.getStorage().save(updated);
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            Player target = Bukkit.getPlayer(targetUuid);
                            if (target != null) {
                                plugin.getNickManager().put(targetUuid, updated);
                                plugin.getNickManager().apply(target);
                            }
                            admin.sendMessage(plugin.getLang().msg("admin.reset-color-success",
                                    com.ncnick.config.LangManager.vars("player", targetName)));
                            openPlayerList(admin, 0);
                        }
                    });
                } else {
                    Bukkit.getScheduler().runTask(plugin, new Runnable() {
                        @Override
                        public void run() {
                            admin.sendMessage(plugin.getLang().msg("validate.target-not-set",
                                    com.ncnick.config.LangManager.vars("player", targetName)));
                            openPlayerList(admin, 0);
                        }
                    });
                }
            }
        });
    }

    /**
     * 开启搜索输入状态。
     */
    private void startSearch(Player admin) {
        admin.closeInventory();
        plugin.getGuiManager().startInput(admin,
                GuiManager.InputType.ADMIN_SEARCH, null, GuiManager.View.ADMIN_MAIN);
        admin.sendMessage(plugin.getLang().prefix()
                + plugin.getLang().text("admin-gui.search-prompt"));
        admin.sendMessage(plugin.getLang().prefix() + plugin.getLang().text("input.cancel-hint"));
    }

    // ==========================================================
    //  工具
    // ==========================================================

    /**
     * 解析管理员输入的颜色。
     *
     * <p>支持：#RRGGBB、&#RRGGBB、&a 等传统代码。
     */
    public static ColorPreset parseAdminColor(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        String s = input.trim();

        // 十六进制
        int[] rgb = Msg.parseRgb(s);
        if (rgb != null) {
            return ColorPreset.solid(rgb[0], rgb[1], rgb[2]);
        }

        // 传统代码 &a
        if (s.length() >= 2 && (s.charAt(0) == '&' || s.charAt(0) == '\u00A7')) {
            char code = Character.toLowerCase(s.charAt(1));
            String hex = legacyToHex(code);
            if (hex != null) {
                return ColorPreset.solid(hex);
            }
        }

        // 中文颜色名
        String byName = colorNameToHex(s);
        if (byName != null) {
            return ColorPreset.solid(byName);
        }

        return null;
    }

    /** 传统颜色代码转十六进制。 */
    private static String legacyToHex(char code) {
        switch (code) {
            case '0': return "000000";
            case '1': return "0000AA";
            case '2': return "00AA00";
            case '3': return "00AAAA";
            case '4': return "AA0000";
            case '5': return "AA00AA";
            case '6': return "FFAA00";
            case '7': return "AAAAAA";
            case '8': return "555555";
            case '9': return "5555FF";
            case 'a': return "55FF55";
            case 'b': return "55FFFF";
            case 'c': return "FF5555";
            case 'd': return "FF55FF";
            case 'e': return "FFFF55";
            case 'f': return "FFFFFF";
            default: return null;
        }
    }

    /** 中文颜色名转十六进制。 */
    private static String colorNameToHex(String name) {
        if ("红".equals(name) || "红色".equals(name)) return "FF5555";
        if ("橙".equals(name) || "橙色".equals(name)) return "FFAA00";
        if ("黄".equals(name) || "黄色".equals(name)) return "FFFF55";
        if ("绿".equals(name) || "绿色".equals(name)) return "55FF55";
        if ("青".equals(name) || "青色".equals(name)) return "55FFFF";
        if ("蓝".equals(name) || "蓝色".equals(name)) return "5555FF";
        if ("紫".equals(name) || "紫色".equals(name)) return "AA00AA";
        if ("粉".equals(name) || "粉色".equals(name)) return "FFAACC";
        if ("白".equals(name) || "白色".equals(name)) return "FFFFFF";
        if ("灰".equals(name) || "灰色".equals(name)) return "AAAAAA";
        if ("黑".equals(name) || "黑色".equals(name)) return "000000";
        return null;
    }

    /**
     * 由昵称数据解析离线玩家对象（用于显示头颅皮肤）。
     *
     * <p>用 UUID 而不是名字，避免同名或改名导致的皮肤错乱。
     *
     * @param data 昵称数据
     * @return 离线玩家；无法解析时返回 null（头颅将显示默认皮肤）
     */
    private static org.bukkit.OfflinePlayer resolveOfflinePlayer(NickData data) {
        if (data == null || data.getUuid() == null) {
            return null;
        }
        try {
            return Bukkit.getOfflinePlayer(data.getUuid());
        } catch (Throwable t) {
            Debug.log(Debug.GENERAL, "解析头颅皮肤主人失败: {}", t.getMessage());
            return null;
        }
    }

    /** 解析玩家显示名。 */
    private static String resolveName(NickData data) {
        if (data == null) {
            return "未知";
        }
        String name = data.getName();
        return (name == null || name.isEmpty()) ? String.valueOf(data.getUuid()) : name;
    }

    private static List<String> listOf(String... lines) {
        List<String> out = new ArrayList<String>();
        if (lines != null) {
            for (String l : lines) {
                out.add(l);
            }
        }
        return out;
    }
}
