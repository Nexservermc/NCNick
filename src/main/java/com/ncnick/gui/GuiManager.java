package com.ncnick.gui;

import com.ncnick.NCNick;
import com.ncnick.config.LangManager;
import com.ncnick.gui.admin.AdminGui;
import com.ncnick.gui.color.ColorGui;
import com.ncnick.model.PayType;
import com.ncnick.util.Debug;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * GUI 总管理器。
 *
 * <p>负责：
 * <ul>
 *   <li>分发界面打开请求</li>
 *   <li>记录每个玩家当前所处的界面，供点击事件路由</li>
 *   <li>管理聊天输入状态（等待玩家在聊天栏输入）</li>
 * </ul>
 */
public class GuiManager {

    /** 管理员权限节点（与 plugin.yml 保持一致） */
    public static final String ADMIN_PERMISSION = "ncnick.admin";

    /** 界面类型 */
    public enum View {
        /** 昵称主界面 */
        MAIN,
        /** 颜色第一层 */
        COLOR_MAIN,
        /** 颜色选色层 */
        COLOR_PICKER,
        /** 颜色预览层 */
        COLOR_PREVIEW,
        /** 支付方式选择层 */
        PAY_CHOOSE,
        /**
         * 玩家自助的二次确认层（如"清除我自己的昵称"）。
         *
         * <p><b>必须与 {@link #ADMIN_CONFIRM} 分开</b>：
         * 管理视图有 {@code ncnick.admin} 权限门禁，
         * 若玩家自助流程复用管理确认层，普通玩家点"确认"会被权限门禁拦下
         * （表现为按钮无反应 + 被踢出界面）。
         */
        PLAYER_CONFIRM,
        /** 管理主界面 */
        ADMIN_MAIN,
        /** 管理-玩家详情 */
        ADMIN_PLAYER,
        /** 管理-确认操作 */
        ADMIN_CONFIRM,
        /** 无 */
        NONE
    }

    /** 聊天输入类型 */
    public enum InputType {
        /** 输入昵称 */
        NICKNAME,
        /** 管理：搜索玩家 */
        ADMIN_SEARCH,
        /** 管理：设置玩家昵称 */
        ADMIN_SET_NAME,
        /** 管理：设置玩家颜色 */
        ADMIN_SET_COLOR
    }

    /** 支付选择界面槽位 */
    private static final int SLOT_PAY_COINS = 11;
    private static final int SLOT_PAY_POINTS = 13;
    private static final int SLOT_PAY_FREE = 15;
    private static final int SLOT_PAY_CANCEL = 22;

    /** 待支付确认的昵称：UUID -> 昵称 */
    private final Map<UUID, String> pendingNicknames = new ConcurrentHashMap<UUID, String>();

    /** 聊天输入会话 */
    public static final class InputSession {
        public final InputType type;
        /** 关联的目标玩家（管理操作用），可为 null */
        public final UUID targetUuid;
        /** 返回的界面 */
        public final View returnView;
        public final long created;

        InputSession(InputType type, UUID targetUuid, View returnView) {
            this.type = type;
            this.targetUuid = targetUuid;
            this.returnView = returnView;
            this.created = System.currentTimeMillis();
        }

        /** 是否已过期（60 秒） */
        public boolean isExpired() {
            return System.currentTimeMillis() - created > 60000L;
        }
    }

    /**
     * 管理员界面的服务端上下文。
     *
     * <p>安全关键：管理员操作的目标玩家必须由**服务端**记录，
     * 绝不能从界面标题/物品名等客户端可见文本反推——
     * 那会让玩家通过伪造标题来越权操作他人数据。
     */
    public static final class AdminContext {
        /** 当前列表页码（0 基） */
        public int page;
        /** 目标玩家名 */
        public String targetName;
        /** 目标玩家 UUID（权威标识） */
        public UUID targetUuid;
        /** 待确认操作的返回视图 */
        public View returnView = View.ADMIN_MAIN;

        /** 清空目标信息。 */
        public void clearTarget() {
            targetName = null;
            targetUuid = null;
        }
    }

    /** 玩家当前界面 */
    private final Map<UUID, View> views = new ConcurrentHashMap<UUID, View>();
    /**
     * 我们实际打开的界面实例：UUID -> Inventory。
     *
     * <p><b>为什么需要它</b>：Bukkit 在「打开新界面」时会先对旧界面触发
     * {@code InventoryCloseEvent}。若在关闭事件里无条件清空视图状态，
     * 那么"主界面 → 颜色界面"这类切换会把**刚设置好的新视图**清掉，
     * 导致界面开着但 {@code view == NONE}：
     * <ul>
     *   <li>点击事件不再被取消 → <b>玩家可以把界面里的物品拿出来</b></li>
     *   <li>点击不再路由 → 按钮无反应</li>
     * </ul>
     * 记录实例后，关闭事件只在"关掉的确实是我们当前这个界面"时才清理状态，
     * 切换过程中的关闭事件会被正确忽略。
     */
    private final Map<UUID, Inventory> openInventories =
            new ConcurrentHashMap<UUID, Inventory>();
    /** 管理员界面的服务端上下文 */
    private final Map<UUID, AdminContext> adminContexts =
            new ConcurrentHashMap<UUID, AdminContext>();
    // 说明：早期版本在此另存了 pickerGradient / pickerPickingTo 两份选色阶段状态。
    // 但 ColorGui.onPickerClick 内部切换「选起始色 → 选结束色」时只更新了自己的会话，
    // 没有同步这两份 Map，导致点击事件读到的 pickingTo 永远是最初的 false ——
    // 表现为「选完起始色后，再点颜色还是在选起始色」。
    // 现已删除重复状态，统一从 ColorGui 的 ColorSession 读取（单一来源）。
    /** 玩家聊天输入会话 */
    private final Map<UUID, InputSession> inputs = new ConcurrentHashMap<UUID, InputSession>();

    private final NCNick plugin;
    private final MainGui mainGui;
    private final ColorGui colorGui;
    private final AdminGui adminGui;

    public GuiManager(NCNick plugin) {
        this.plugin = plugin;
        this.mainGui = new MainGui(plugin);
        this.colorGui = new ColorGui(plugin);
        this.adminGui = new AdminGui(plugin);
    }

    public MainGui getMainGui() {
        return mainGui;
    }

    public ColorGui getColorGui() {
        return colorGui;
    }

    public AdminGui getAdminGui() {
        return adminGui;
    }

    // ==========================================================
    //  界面状态
    // ==========================================================

    /**
     * 清除玩家的界面状态（不涉及权限校验）。
     *
     * <p>清除操作永远是安全的，因此单独提供，
     * 避免调用方为了清状态而必须持有 Player 对象。
     *
     * @param uuid 玩家 UUID
     */
    public void clearView(UUID uuid) {
        if (uuid == null) {
            return;
        }
        views.remove(uuid);
        openInventories.remove(uuid);
        adminContexts.remove(uuid);
    }

    /**
     * 设置玩家当前界面（带权限校验）。
     *
     * <p>安全关键：管理类视图（ADMIN_*）会先校验管理员权限，
     * 无权限时静默拒绝写入，防止玩家通过任何路径把自己置入管理视图。
     *
     * <p>注意：本方法刻意不提供省略 {@code player} 的重载——
     * 那会让调用方在设置管理视图时"忘记"传权限主体，
     * 从而留下绕过面。清除状态请用 {@link #clearView(UUID)}。
     *
     * @param uuid   玩家 UUID
     * @param view   视图；null 或 NONE 表示清除
     * @param player 玩家对象；管理视图下若为 null 或缺少权限则拒绝
     */
    public void setView(UUID uuid, View view, Player player) {
        if (uuid == null) {
            return;
        }
        if (view == null || view == View.NONE) {
            clearView(uuid);
            return;
        }

        // 管理视图必须持有管理员权限
        if (isAdminView(view)) {
            if (player == null || !player.hasPermission(ADMIN_PERMISSION)) {
                Debug.log(Debug.GENERAL, "拒绝为非管理员设置管理视图: {}", uuid);
                views.remove(uuid);
                adminContexts.remove(uuid);
                return;
            }
        }

        views.put(uuid, view);
    }

    /** 是否为管理类视图。 */
    public static boolean isAdminView(View view) {
        return view == View.ADMIN_MAIN
                || view == View.ADMIN_PLAYER
                || view == View.ADMIN_CONFIRM;
    }

    /**
     * 获取管理员上下文（不存在时创建）。
     *
     * @param uuid 管理员 UUID
     */
    public AdminContext adminContext(UUID uuid) {
        AdminContext ctx = adminContexts.get(uuid);
        if (ctx == null) {
            ctx = new AdminContext();
            adminContexts.put(uuid, ctx);
        }
        return ctx;
    }

    /** 清除管理员上下文。 */
    public void clearAdminContext(UUID uuid) {
        if (uuid != null) {
            adminContexts.remove(uuid);
        }
    }

    /**
     * 校验玩家是否有权执行管理操作。
     *
     * <p>这是事件层的兜底防线：即使视图状态被以某种方式污染，
     * 点击路由也会在此被拦下。
     *
     * @param player 玩家
     * @return 是否有管理员权限
     */
    public boolean checkAdmin(Player player) {
        if (player == null) {
            return false;
        }
        if (player.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        // 拒绝并把玩家踢出管理视图
        Debug.log(Debug.GENERAL, "拦截非管理员的管理操作: {}", player.getName());
        clearView(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(plugin.getLang().msg("msg.no-permission"));
        return false;
    }

    public View getView(UUID uuid) {
        View v = views.get(uuid);
        return v == null ? View.NONE : v;
    }

    /**
     * 记录"即将打开"的界面实例（必须在 {@code player.openInventory()} **之前**调用）。
     *
     * <p>提前记录的原因见 {@link #openInventories} 的说明。
     *
     * @param uuid 玩家
     * @param inv  即将打开的界面
     */
    public void markOpened(UUID uuid, Inventory inv) {
        if (uuid == null || inv == null) {
            return;
        }
        openInventories.put(uuid, inv);
    }

    /** 取我们记录的当前界面实例（可能为 null）。 */
    public Inventory getOpenedInventory(UUID uuid) {
        return uuid == null ? null : openInventories.get(uuid);
    }

    /**
     * 玩家关闭界面时清理状态。
     *
     * <p>关键：只有"关闭的确实是本插件记录的那个界面"时才清理。
     * 若是因为打开新界面而触发的旧界面关闭事件，则忽略，保留新视图。
     *
     * @param uuid     玩家
     * @param closed   被关闭的界面（来自 InventoryCloseEvent）
     */
    public void onInventoryClose(UUID uuid, Inventory closed) {
        if (uuid == null) {
            return;
        }
        Inventory ours = openInventories.get(uuid);

        // 记录为空：可能是本插件之外打开的界面，或状态已被清理
        if (ours == null) {
            clearView(uuid);
            return;
        }

        // 关闭的不是我们记录的那个界面 → 说明正在切换到新界面，保留状态
        if (closed != null && !ours.equals(closed)) {
            Debug.log(Debug.CACHE, "忽略切换过程中的界面关闭事件: {}", uuid);
            return;
        }

        // 确实关闭了我们的界面 → 清理
        openInventories.remove(uuid);
        View v = getView(uuid);
        // 预览层/选色层关闭时保留选色会话，便于重新打开
        if (v != View.COLOR_PREVIEW && v != View.COLOR_PICKER) {
            colorGui.clearSession(uuid);
        }
        clearView(uuid);
    }

    /** 玩家退出时彻底清理。 */
    public void onQuit(UUID uuid) {
        if (uuid == null) {
            return;
        }
        views.remove(uuid);
        openInventories.remove(uuid);
        inputs.remove(uuid);
        colorGui.clearSession(uuid);
    }

    // ==========================================================
    //  打开界面
    // ==========================================================

    /** 打开昵称主界面。 */
    public void openMain(Player player) {
        safeRun(player, new Runnable() {
            @Override
            public void run() {
                mainGui.open(player);
            }
        });
    }

    /**
     * 打开昵称输入界面（告示牌/铁砧）。
     *
     * <p>1.8+ 通用做法是让玩家在聊天栏输入，
     * 因为告示牌 GUI 需要额外的库且在各版本表现不一。
     */
    public void openNicknameInput(Player player) {
        clearView(player.getUniqueId());
        player.closeInventory();

        InputSession session = new InputSession(InputType.NICKNAME, null, View.MAIN);
        inputs.put(player.getUniqueId(), session);

        // 引导文字全部来自语言文件（lang/*.yml 的 input 段）。
        // 早期版本把这些字符串硬编码在 Java 里，且没有经过 Msg.color()，
        // 玩家会看到字面的 "&e" "&7" 颜色代码而非颜色。
        LangManager lang = plugin.getLang();
        player.sendMessage(lang.prefix() + lang.text("input.nickname-prompt"));

        Map<String, String> vars = new HashMap<String, String>();
        vars.put("min", String.valueOf(plugin.getConfigManager().getMinLength()));
        vars.put("max", String.valueOf(plugin.getConfigManager().getMaxLength()));
        player.sendMessage(lang.prefix() + lang.text("input.nickname-requirements", vars));

        player.sendMessage(lang.prefix() + lang.text("input.cancel-hint"));
        player.sendMessage(lang.prefix() + lang.text("input.nickname-color-hint"));
    }

    /** 打开颜色第一层。 */
    public void openColorMain(Player player) {
        safeRun(player, new Runnable() {
            @Override
            public void run() {
                colorGui.openMain(player);
            }
        });
    }

    /** 打开选色层。 */
    public void openColorPicker(Player player, final boolean gradient, final boolean pickingTo) {
        safeRun(player, new Runnable() {
            @Override
            public void run() {
                colorGui.openPicker(player, gradient, pickingTo);
            }
        });
    }

    /**
     * 是否为渐变选色模式。
     *
     * <p>状态来源是 {@link com.ncnick.gui.color.ColorGui.ColorSession}，
     * 不在此另存副本 —— 详见字段声明处的说明。
     */
    public boolean isPickerGradient(UUID uuid) {
        return uuid != null && colorGui.session(uuid).isGradient();
    }

    /**
     * 渐变模式下是否正在选结束色（{@code false} = 正在选起始色）。
     *
     * <p>直接读会话，保证与界面当前实际阶段一致。
     */
    public boolean isPickerPickingTo(UUID uuid) {
        return uuid != null && colorGui.session(uuid).isPickingTo();
    }

    /** 打开颜色预览层。 */
    public void openColorPreview(Player player) {
        safeRun(player, new Runnable() {
            @Override
            public void run() {
                colorGui.openPreview(player);
            }
        });
    }

    // ==========================================================
    //  支付方式选择（细节要求：买中文名时可单独用点券/金币，
    //  同时给出两个选择，默认直接用金币）
    // ==========================================================

    /**
     * 打开支付方式选择界面。
     *
     * <p>玩家在聊天栏输入昵称并通过校验后进入此界面，
     * 可自行选择用金币或点券购买；两者都可用时并列展示。
     * 若玩家拥有权限，则额外提供"免费"选项。
     *
     * @param player   玩家
     * @param nickname 已通过校验的昵称
     */
    public void openPayChoose(Player player, String nickname) {
        pendingNicknames.put(player.getUniqueId(), nickname);
        setView(player.getUniqueId(), View.PAY_CHOOSE, player);

        com.ncnick.manager.NickManager nm = plugin.getNickManager();
        List<PayType> types = nm.availablePayTypes();
        boolean hasPermission = plugin.getConfigManager().isPermissionModeEnabled()
                && plugin.getNickManager().hasNickPermission(player);

        org.bukkit.inventory.Inventory inv = GuiUtil.create(
                plugin.getLang().text("pay.title"), 3);

        // ---- 免费（权限） ----
        if (hasPermission) {
            GuiUtil.set(inv, SLOT_PAY_FREE, GuiUtil.item(
                    GuiUtil.confirmMaterial(),
                    plugin.getLang().text("pay.item-free"),
                    plugin.getLang().list("pay.item-free-lore",
                            com.ncnick.config.LangManager.vars("permission",
                                    plugin.getConfigManager().getPermissionNode()))));
        }

        // ---- 金币（默认推荐） ----
        if (types.contains(PayType.COINS)) {
            double price = nm.calcPrice(
                    com.ncnick.manager.NickManager.ChargeKind.NICKNAME,
                    PayType.COINS, nickname, null);
            double balance = plugin.getVaultHook().getBalance(player);
            String action = balance >= price
                    ? plugin.getLang().text("pay.action-buy")
                    : plugin.getLang().text("pay.action-insufficient");
            GuiUtil.set(inv, SLOT_PAY_COINS, GuiUtil.item(
                    GuiUtil.material("GOLD_INGOT", "GOLD_INGOT"),
                    plugin.getLang().text("pay.item-coins"),
                    plugin.getLang().list("pay.item-coins-lore",
                            com.ncnick.config.LangManager.vars(
                                    "price", formatMoney(price),
                                    "balance", formatMoney(balance),
                                    "action", action))));
        }

        // ---- 点券 ----
        if (types.contains(PayType.POINTS)) {
            int price = (int) Math.round(nm.calcPrice(
                    com.ncnick.manager.NickManager.ChargeKind.NICKNAME,
                    PayType.POINTS, nickname, null));
            int balance = plugin.getPlayerPointsHook().getPoints(player.getUniqueId());
            String action = balance >= price
                    ? plugin.getLang().text("pay.action-buy")
                    : plugin.getLang().text("pay.action-insufficient");
            GuiUtil.set(inv, SLOT_PAY_POINTS, GuiUtil.item(
                    GuiUtil.material("EMERALD", "EMERALD"),
                    plugin.getLang().text("pay.item-points"),
                    plugin.getLang().list("pay.item-points-lore",
                            com.ncnick.config.LangManager.vars(
                                    "price", String.valueOf(price),
                                    "balance", String.valueOf(balance),
                                    "action", action))));
        }

        // ---- 预览信息 ----
        GuiUtil.set(inv, 4, GuiUtil.item(
                GuiUtil.infoMaterial(),
                plugin.getLang().text("pay.item-info",
                        com.ncnick.config.LangManager.vars("name", nickname)),
                plugin.getLang().list("pay.item-info-lore",
                        com.ncnick.config.LangManager.vars("nickname", nickname))));

        // ---- 取消 ----
        GuiUtil.set(inv, SLOT_PAY_CANCEL, GuiUtil.item(
                GuiUtil.cancelMaterial(), plugin.getLang().text("pay.item-cancel"), null));

        // ---- 边框 ----
        // 始终绘制，不受 fill-empty 开关影响
        GuiUtil.border(inv);

        if (plugin.getConfigManager().isGuiFillEmpty()) {
            GuiUtil.fillEmpty(inv, 0, inv.getSize() - 1);
        }

        GuiUtil.open(player, inv);
    }

    /**
     * 处理支付选择界面的点击。
     *
     * @return 是否已处理
     */
    public boolean onPayChooseClick(Player player, int slot) {
        String nickname = pendingNicknames.get(player.getUniqueId());
        if (nickname == null) {
            player.closeInventory();
            return true;
        }

        boolean hasPermission = plugin.getConfigManager().isPermissionModeEnabled()
                && plugin.getNickManager().hasNickPermission(player);
        List<PayType> types = plugin.getNickManager().availablePayTypes();

        final PayType chosen;
        if (hasPermission && slot == SLOT_PAY_FREE) {
            chosen = PayType.FREE;
        } else if (slot == SLOT_PAY_COINS && types.contains(PayType.COINS)) {
            chosen = PayType.COINS;
        } else if (slot == SLOT_PAY_POINTS && types.contains(PayType.POINTS)) {
            chosen = PayType.POINTS;
        } else if (slot == SLOT_PAY_CANCEL) {
            pendingNicknames.remove(player.getUniqueId());
            player.closeInventory();
            player.sendMessage(plugin.getLang().msg("purchase.cancelled"));
            return true;
        } else {
            return true;
        }

        player.closeInventory();
        pendingNicknames.remove(player.getUniqueId());

        plugin.getNickManager().setNickname(player, nickname, chosen, null);
        return true;
    }

    /** 清空待支付状态。 */
    public void clearPendingNickname(UUID uuid) {
        if (uuid != null) {
            pendingNicknames.remove(uuid);
        }
    }

    /** 金额格式化（整数省略小数）。 */
    private static String formatMoney(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount)) {
            return String.valueOf((long) amount);
        }
        return String.format("%.2f", amount);
    }

    /** 打开管理主界面。 */
    public void openAdmin(Player player) {
        safeRun(player, new Runnable() {
            @Override
            public void run() {
                adminGui.openPlayerList(player, 0);
            }
        });
    }

    /** 打开管理-玩家详情。 */
    public void openAdminPlayer(Player admin, final String targetName) {
        safeRun(admin, new Runnable() {
            @Override
            public void run() {
                adminGui.openPlayerDetail(admin, targetName);
            }
        });
    }

    /**
     * 打开**玩家自助**的确认删除界面。
     *
     * <p>与管理员流程（{@code AdminGui.openConfirm}）的区别：这里用的是
     * {@link View#PLAYER_CONFIRM}，**不受 {@code ncnick.admin} 门禁**。
     *
     * <p>为什么必须分开：早期版本让玩家自助流程复用
     * {@code AdminGui.openConfirm}，那条路径会设置 {@link View#ADMIN_CONFIRM}
     * —— 属于管理类视图。而 {@code PlayerListener} 对所有管理类视图统一鉴权：
     * <pre>
     *   if (isAdminView(view) &amp;&amp; !checkAdmin(player)) return;
     * </pre>
     * 结果是普通玩家点「确认」被门禁拦下（按钮无反应 + 被踢出界面），
     * 而管理员会因 {@code returnView = MAIN} 走到兜底分支跳进管理界面。
     *
     * <p>这里删除的目标**固定为玩家自己**，不接受外部传入的 UUID，
     * 因此不存在越权删除他人的可能。
     *
     * @param player 玩家
     */
    public void openPlayerConfirmRemove(final Player player) {
        if (player == null) {
            return;
        }
        final UUID self = player.getUniqueId();
        final String selfName = player.getName();
        safeRun(player, new Runnable() {
            @Override
            public void run() {
                setView(self, View.PLAYER_CONFIRM, player);
                // 目标固定为自己（不信任任何外部输入）
                AdminContext ctx = adminContext(self);
                ctx.targetUuid = self;
                ctx.targetName = selfName;
                ctx.returnView = View.MAIN;

                Inventory inv = GuiUtil.create(
                        plugin.getLang().text("admin-gui.title-confirm"),
                        MainGui.CONFIRM_ROWS);

                List<String> infoLore = new ArrayList<String>();
                infoLore.add("&7玩家：&f" + selfName);
                infoLore.add("");
                infoLore.add("&c将清除你自己的昵称数据");
                GuiUtil.set(inv, 4, GuiUtil.headItem(player,
                        "&f" + selfName, infoLore));

                GuiUtil.set(inv, MainGui.SLOT_CONFIRM_YES, GuiUtil.item(
                        GuiUtil.material("LIME_WOOL", "WOOL"),
                        plugin.getLang().text("admin-gui.confirm-yes"),
                        java.util.Collections.singletonList("&c确认清除我的昵称")));

                GuiUtil.set(inv, MainGui.SLOT_CONFIRM_NO, GuiUtil.item(
                        GuiUtil.material("RED_WOOL", "WOOL"),
                        plugin.getLang().text("admin-gui.confirm-no"),
                        java.util.Collections.singletonList("&7取消并关闭")));

                GuiUtil.border(inv);
                if (plugin.getConfigManager().isGuiFillEmpty()) {
                    GuiUtil.fillEmpty(inv, 0, inv.getSize() - 1);
                }
                GuiUtil.open(player, inv);
            }
        });
    }

    /**
     * 处理玩家自助确认界面的点击。
     *
     * <p>无论确认还是取消，**都只关闭界面、不做任何跳转**。
     *
     * @param player 玩家
     * @param slot   槽位
     * @return 是否已处理
     */
    public boolean onPlayerConfirmClick(Player player, int slot) {
        if (slot == MainGui.SLOT_CONFIRM_YES) {
            player.closeInventory();
            // 复用玩家自助删除逻辑：内部会校验是否已设置、
            // 异步落库、同步缓存并刷新头顶/TAB/聊天显示，最后提示成功。
            plugin.getNickManager().removeOwn(player);
            // 刻意不重新打开任何界面
            return true;
        }
        if (slot == MainGui.SLOT_CONFIRM_NO) {
            player.closeInventory();
            return true;
        }
        return true;
    }

    private void safeRun(Player player, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "打开界面失败: " + player.getName(), t);
            player.sendMessage(plugin.getLang().msg("msg.gui-error"));
        }
    }

    // ==========================================================
    //  聊天输入
    // ==========================================================

    /** 是否正在等待该玩家输入。 */
    public boolean isWaitingInput(UUID uuid) {
        InputSession s = inputs.get(uuid);
        if (s == null) {
            return false;
        }
        if (s.isExpired()) {
            inputs.remove(uuid);
            return false;
        }
        return true;
    }

    /** 获取输入会话。 */
    public InputSession getInput(UUID uuid) {
        return inputs.get(uuid);
    }

    /** 结束输入会话。 */
    public void endInput(UUID uuid) {
        if (uuid != null) {
            inputs.remove(uuid);
        }
    }

    /** 开启一个管理输入会话。 */
    public void startInput(Player player, InputType type, UUID targetUuid, View returnView) {
        inputs.put(player.getUniqueId(), new InputSession(type, targetUuid, returnView));
    }

    /** 处理聊天输入，返回 true 表示已消费该消息。 */
    public boolean handleChatInput(Player player, String message) {
        InputSession session = inputs.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        if (session.isExpired()) {
            inputs.remove(player.getUniqueId());
            return false;
        }

        // 取消
        if ("cancel".equalsIgnoreCase(message.trim())
                || "取消".equals(message.trim())) {
            inputs.remove(player.getUniqueId());
            player.sendMessage(plugin.getLang().msg("purchase.cancelled"));
            if (session.returnView == View.ADMIN_PLAYER) {
                // 回到管理界面需要目标名，这里简化处理
                openAdmin(player);
            } else if (session.returnView == View.MAIN) {
                openMain(player);
            }
            return true;
        }

        inputs.remove(player.getUniqueId());

        switch (session.type) {
            case NICKNAME: {
                // 先做本地校验，通过后进入支付方式选择界面
                // （细节要求：买中文名时可单独用点券/金币，同时给出两个选择）
                final String nickname = message.trim();
                com.ncnick.manager.ValidateResult check =
                        plugin.getNickManager().validate(player, nickname);
                if (!check.isOk()) {
                    player.sendMessage(plugin.getLang().msg(check.getMessageKey(),
                            check.getParams()));
                    // 校验失败：回到主界面
                    Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                        @Override
                        public void run() {
                            openMain(player);
                        }
                    }, 20L);
                    return true;
                }
                // 校验通过，打开支付选择界面
                openPayChoose(player, nickname);
                return true;
            }

            case ADMIN_SEARCH:
                adminGui.onSearchInput(player, message.trim());
                return true;

            case ADMIN_SET_NAME:
                adminGui.onSetNameInput(player, session.targetUuid, message.trim());
                return true;

            case ADMIN_SET_COLOR:
                adminGui.onSetColorInput(player, session.targetUuid, message.trim());
                return true;

            default:
                return true;
        }
    }

    // ==========================================================
    //  清理
    // ==========================================================

    /** 插件卸载时清理。 */
    public void shutdown() {
        views.clear();
        openInventories.clear();
        inputs.clear();
        colorGui.clearAll();
    }
}
