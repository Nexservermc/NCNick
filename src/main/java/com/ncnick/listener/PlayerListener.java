package com.ncnick.listener;

import com.ncnick.NCNick;
import com.ncnick.gui.GuiManager;
import com.ncnick.model.NickData;
import com.ncnick.util.Debug;
import com.ncnick.util.Permissions;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/**
 * 玩家与界面事件监听器。
 *
 * <p>处理：
 * <ul>
 *   <li>GUI 点击路由与防取出物品</li>
 *   <li>聊天输入捕获（用于昵称/搜索等输入）</li>
 *   <li>玩家进出的数据加载与清理</li>
 * </ul>
 */
public class PlayerListener implements Listener {

    private final NCNick plugin;

    public PlayerListener(NCNick plugin) {
        this.plugin = plugin;
    }

    // ==========================================================
    //  玩家进出
    // ==========================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 延迟加载，避免与其他插件的进服处理抢时序
        plugin.getNickManager().onJoin(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getNickManager().onQuit(player);
        plugin.getGuiManager().onQuit(player.getUniqueId());
    }

    // ==========================================================
    //  GUI 点击
    // ==========================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        UUID uuid = player.getUniqueId();

        GuiManager.View view = plugin.getGuiManager().getView(uuid);
        if (view == GuiManager.View.NONE) {
            return;
        }

        // 第二道保险：确认点击的确实是我们记录的那个界面。
        // 若界面被其他插件替换，则不拦截，避免影响他人。
        Inventory recorded = plugin.getGuiManager().getOpenedInventory(uuid);
        if (recorded != null
                && !recorded.equals(event.getView().getTopInventory())) {
            Debug.log(Debug.CACHE, "点击的界面与记录不符，跳过拦截");
            return;
        }

        // 阻止一切物品移动
        // （players 无法从这个界面拿走任何物品；所有点击只用于触发动作）
        event.setCancelled(true);
        // 兼容部分服务端：同时取消光标拾取，避免出现"影子物品"
        try {
            event.setResult(org.bukkit.event.Event.Result.DENY);
        } catch (Throwable ignored) {
            // 某些实现不支持设置 Result，忽略
        }

        Inventory top = event.getView().getTopInventory();
        // 只处理点击上半部分（插件界面）
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(top)) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) {
            return;
        }

        try {
            routeClick(player, view, slot, top);
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "处理界面点击失败", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        GuiManager.View view = plugin.getGuiManager().getView(player.getUniqueId());
        if (view == GuiManager.View.NONE) {
            return;
        }
        // 阻止在插件界面内拖拽物品
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        // 把"被关闭的界面实例"传下去：GuiManager 需要据此区分
        // 「玩家真的关了界面」与「因切换到新界面而触发的关闭」
        plugin.getGuiManager().onInventoryClose(player.getUniqueId(),
                event.getInventory());
    }

    /**
     * 把点击分发到对应界面处理器。
     *
     * <p>安全关键（两道防线）：
     * <ol>
     *   <li>所有 ADMIN_* 视图在进入前统一校验 {@code ncnick.admin} 权限，
     *       防止非管理员通过任何路径触发管理操作</li>
     *   <li>管理操作的目标玩家一律从服务端上下文
     *       （{@link GuiManager.AdminContext}）读取，
     *       <b>绝不</b>从界面标题或物品名等客户端可见文本反推</li>
     * </ol>
     */
    private void routeClick(Player player, GuiManager.View view, int slot, Inventory top) {
        GuiManager gui = plugin.getGuiManager();

        // ---- 防线 1：管理视图统一鉴权 ----
        if (GuiManager.isAdminView(view) && !gui.checkAdmin(player)) {
            return;
        }

        switch (view) {
            case MAIN:
                gui.getMainGui().onClick(player, slot);
                break;

            case COLOR_MAIN: {
                int size = top.getSize();
                if (slot == 11) {
                    // 纯色：需要 ncnick.color.solid
                    if (Permissions.has(player, Permissions.COLOR_SOLID)) {
                        gui.openColorPicker(player, false, false);
                    } else {
                        player.sendMessage(plugin.getLang().msg("msg.no-permission"));
                    }
                } else if (slot == 15) {
                    // 渐变：需要 ncnick.color.gradient
                    if (Permissions.has(player, Permissions.COLOR_GRADIENT)) {
                        gui.openColorPicker(player, true, false);
                    } else {
                        player.sendMessage(plugin.getLang().msg("msg.no-permission"));
                    }
                } else if (slot == size - 9) {
                    // 返回：最后一行最左（与 ColorGui 放置位置一致）
                    gui.openMain(player);
                } else if (slot == size - 1) {
                    // 关闭：最后一行最右
                    player.closeInventory();
                }
                break;
            }

            case COLOR_PICKER: {
                boolean gradient = gui.isPickerGradient(player.getUniqueId());
                boolean pickingTo = gui.isPickerPickingTo(player.getUniqueId());
                gui.getColorGui().onPickerClick(player, gradient, pickingTo, slot);
                break;
            }

            case COLOR_PREVIEW:
                gui.getColorGui().onPreviewClick(player, slot);
                break;

            case PAY_CHOOSE:
                gui.onPayChooseClick(player, slot);
                break;

            case PLAYER_CONFIRM:
                // 玩家自助确认（清除自己的昵称）。
                // 这是**玩家视图**，不经过管理权限门禁 ——
                // 普通玩家必须能删除自己的昵称。
                gui.onPlayerConfirmClick(player, slot);
                break;

            case ADMIN_MAIN: {
                // 页码取自服务端上下文；被点击的玩家名从物品读取后交给 AdminGui 解析
                GuiManager.AdminContext ctx = gui.adminContext(player.getUniqueId());
                String clickedName = findClickedPlayerName(top, slot);
                gui.getAdminGui().onListClick(player, ctx.page, slot, clickedName);
                break;
            }

            case ADMIN_PLAYER: {
                // ---- 防线 2：目标来自服务端上下文，而非界面标题 ----
                GuiManager.AdminContext ctx = gui.adminContext(player.getUniqueId());
                if (ctx.targetUuid == null) {
                    // 目标尚未解析完成，忽略本次点击
                    Debug.log(Debug.GENERAL, "管理上下文缺少目标 UUID，忽略点击");
                    return;
                }
                gui.getAdminGui().onDetailClick(player, slot, ctx.targetName, ctx.targetUuid);
                break;
            }

            case ADMIN_CONFIRM: {
                GuiManager.AdminContext ctx = gui.adminContext(player.getUniqueId());
                if (ctx.targetUuid == null) {
                    Debug.log(Debug.GENERAL, "确认上下文缺少目标 UUID，忽略点击");
                    return;
                }
                gui.getAdminGui().onConfirmClick(player, slot, ctx.targetUuid, ctx.targetName);
                break;
            }

            default:
                break;
        }
    }

    // ---- 界面上下文辅助 ----

    /**
     * 找出被点击的玩家物品对应的数据。
     *
     * <p>安全说明：这里仅用于**确定被点击的行**，真正的操作目标
     * 由 {@link com.ncnick.gui.GuiManager.AdminContext} 在服务端记录。
     * 物品显示名只作展示用途，不作为授权依据。
     *
     * @return 被点击行的玩家名；无法识别时返回 null
     */
    private String findClickedPlayerName(Inventory top, int slot) {
        org.bukkit.inventory.ItemStack item = top.getItem(slot);
        if (item == null || item.getItemMeta() == null) {
            return null;
        }
        String displayName = item.getItemMeta().getDisplayName();
        if (displayName == null) {
            return null;
        }
        String plain = com.ncnick.util.Msg.stripColor(displayName).trim();
        return plain.isEmpty() ? null : plain;
    }

    // ==========================================================
    //  聊天输入
    // ==========================================================

    /**
     * 捕获聊天输入。
     *
     * <p>使用 LOWEST 优先级并取消事件，确保输入不会外泄到公屏。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getGuiManager().isWaitingInput(player.getUniqueId())) {
            return;
        }
        // 取消原版聊天
        event.setCancelled(true);
        final String message = event.getMessage();

        // 已异步，直接处理（内部会切回主线程）
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                plugin.getGuiManager().handleChatInput(player, message);
            }
        });
    }
}
