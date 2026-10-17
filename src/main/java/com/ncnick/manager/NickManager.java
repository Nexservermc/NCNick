package com.ncnick.manager;

import com.ncnick.ConfigManager;
import com.ncnick.NCNick;
import com.ncnick.config.LangManager;
import com.ncnick.model.ColorPreset;
import com.ncnick.model.NickData;
import com.ncnick.model.NickMode;
import com.ncnick.model.PayType;
import com.ncnick.util.CharsetFilter;
import com.ncnick.util.Debug;
import com.ncnick.util.Permissions;
import com.ncnick.util.Msg;
import com.ncnick.util.NicknameNormalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 昵称管理器 —— 插件核心业务逻辑。
 *
 * <p>职责：
 * <ul>
 *   <li>昵称缓存管理</li>
 *   <li>昵称校验（字符集、长度、唯一性、敏感词）</li>
 *   <li>显示名计算与应用</li>
 *   <li>购买与扣费</li>
 * </ul>
 */
public class NickManager {

    private final NCNick plugin;
    /** 在线玩家的昵称缓存：UUID -> 数据 */
    private final Map<UUID, NickData> cache = new ConcurrentHashMap<UUID, NickData>();

    /**
     * 正在进行中的异步写库任务数。
     *
     * <h3>为什么需要</h3>
     * 写库是异步的（不卡主线程）。插件卸载时
     * {@code onDisable} 会 {@code cancelTasks()}，此时**已排队但还没开跑**
     * 的异步任务会被直接取消 —— 那次保存就永远不会发生，
     * 玩家刚设的昵称凭空消失。
     *
     * <p>因此在卸载流程里先等这个计数归零，再取消任务、关闭连接。
     */
    private final java.util.concurrent.atomic.AtomicInteger pendingWrites =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * 权限判定缓存：UUID -> 是否持有昵称权限。
     *
     * <p>为何需要：{@code isActive()} 会在每次显示计算时被调用，
     * 而 PlaceholderAPI 变量可能被 TAB 插件每 tick 渲染多次，
     * 每次都查权限树会造成明显开销。这里缓存结果，
     * 由 {@link com.ncnick.task.PermissionCheckTask} 周期性刷新。
     */
    private final Map<UUID, Boolean> permCache = new ConcurrentHashMap<UUID, Boolean>();

    public NickManager(NCNick plugin) {
        this.plugin = plugin;
    }

    /**
     * 操作完成回调。
     *
     * <p>回调在主线程执行。{@code success} 用于区分成功与失败，
     * 便于界面决定是否保留当前选择状态（失败时应保留，让玩家改选支付方式）。
     */
    public interface Callback {
        /**
         * @param success 操作是否成功
         */
        void done(boolean success);
    }

    /**
     * 把 Runnable 适配成 Callback（仅关心完成，不区分成败）。
     */
    private static Callback adapt(final Runnable runnable) {
        if (runnable == null) {
            return null;
        }
        return new Callback() {
            @Override
            public void done(boolean success) {
                runnable.run();
            }
        };
    }

    // ==========================================================
    //  缓存
    // ==========================================================

    public NickData get(UUID uuid) {
        return uuid == null ? null : cache.get(uuid);
    }

    public void put(UUID uuid, NickData data) {
        if (uuid == null) {
            return;
        }
        if (data == null) {
            cache.remove(uuid);
        } else {
            cache.put(uuid, data);
        }
        Debug.log(Debug.CACHE, "缓存更新: {} -> {}", uuid, data);
    }

    public void removeCache(UUID uuid) {
        if (uuid != null) {
            cache.remove(uuid);
        }
    }

    /** 清空缓存（reload 时使用）。 */
    public void clearCache() {
        cache.clear();
        Debug.log(Debug.CACHE, "缓存已清空");
    }

    /**
     * 等待所有进行中的异步写库完成。
     *
     * <p>插件卸载时必须先调用本方法，**再**取消任务和关闭连接，
     * 否则排队中的保存会被丢弃。详见 {@link #pendingWrites}。
     *
     * @param timeoutMs 最长等待毫秒数
     * @return true 表示已全部写完；false 表示超时仍有未完成
     */
    public boolean awaitPendingWrites(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (pendingWrites.get() > 0) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 登记一次异步写库开始。 */
    private void beginWrite() {
        pendingWrites.incrementAndGet();
    }

    /** 登记一次异步写库结束（必须在 finally 里调用）。 */
    private void endWrite() {
        pendingWrites.decrementAndGet();
    }

    /** 当前缓存条目数。 */
    public int cacheSize() {
        return cache.size();
    }

    /**
     * 从存储加载玩家数据到缓存。
     * 必须在异步线程调用。
     */
    public NickData loadFromStorage(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        NickData data = plugin.getStorage().load(uuid);
        if (data != null) {
            cache.put(uuid, data);
        }
        return data;
    }

    // ==========================================================
    //  有效性判断
    // ==========================================================

    /**
     * 判断玩家的昵称当前是否应当生效。
     *
     * <p>购买模式始终生效；权限模式需要玩家仍持有权限。
     */
    public boolean isActive(Player player, NickData data) {
        if (player == null || data == null || !data.hasNickname()) {
            return false;
        }
        if (data.getMode() == NickMode.PURCHASE) {
            return true;
        }
        if (data.getMode() != NickMode.PERMISSION) {
            // REMOVED 等状态不生效
            return false;
        }
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isRevokeOnPermissionLost()) {
            return true;
        }
        return hasNickPermission(player);
    }

    /**
     * 查询玩家是否持有昵称权限（带缓存）。
     *
     * <p>缓存由 {@link #refreshPermissionCache(Player)} 与
     * {@link com.ncnick.task.PermissionCheckTask} 负责刷新。
     */
    public boolean hasNickPermission(Player player) {
        if (player == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        Boolean cached = permCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        boolean has = player.hasPermission(plugin.getConfigManager().getPermissionNode());
        permCache.put(uuid, has);
        return has;
    }

    /**
     * 强制刷新某玩家的权限缓存。
     *
     * @return 刷新后的权限状态
     */
    public boolean refreshPermissionCache(Player player) {
        if (player == null) {
            return false;
        }
        boolean has = player.hasPermission(plugin.getConfigManager().getPermissionNode());
        permCache.put(player.getUniqueId(), has);
        return has;
    }

    /** 清除权限缓存（玩家退出或 reload 时）。 */
    public void clearPermissionCache(UUID uuid) {
        if (uuid != null) {
            permCache.remove(uuid);
        }
    }

    /** 清空全部权限缓存。 */
    public void clearAllPermissionCache() {
        permCache.clear();
    }

    /** 获取玩家当前生效的昵称（带颜色），无则返回 null。 */
    public String getActiveNickname(Player player) {
        if (player == null) {
            return null;
        }
        NickData d = cache.get(player.getUniqueId());
        if (d != null && isActive(player, d)) {
            return d.getColoredNickname();
        }
        return null;
    }

    /** 获取玩家当前生效的原始昵称（不含颜色），无则返回 null。 */
    public String getActiveRawNickname(Player player) {
        if (player == null) {
            return null;
        }
        NickData d = cache.get(player.getUniqueId());
        if (d != null && isActive(player, d)) {
            return d.getNickname();
        }
        return null;
    }

    // ==========================================================
    //  显示格式计算（第 2 条：占位符）
    // ==========================================================

    /**
     * 应用显示格式模板。
     *
     * <p>支持占位符：
     * <ul>
     *   <li>{@code {name}} —— 玩家真实 ID</li>
     *   <li>{@code {nickname}} —— 带颜色的昵称</li>
     *   <li>{@code {prefix}} —— 自定义占位符字符串</li>
     * </ul>
     *
     * @param template 格式模板
     * @param name     玩家真实 ID
     * @param nickname 昵称（可含颜色），无昵称时传 null
     * @return 填充后的字符串（尚未颜色转换）
     */
    public String format(String template, String name, String nickname) {
        if (template == null || template.isEmpty()) {
            return name == null ? "" : name;
        }
        ConfigManager cfg = plugin.getConfigManager();
        String prefix = cfg.getCustomPrefix();

        Map<String, String> vars = new HashMap<String, String>();
        vars.put("name", name == null ? "" : name);
        vars.put("nickname", nickname == null ? "" : nickname);
        vars.put("prefix", nickname == null && !cfg.isPrefixWhenNoNickname() ? "" : prefix);
        return Msg.fill(template, vars);
    }

    /**
     * 计算玩家的 TAB 显示名。
     *
     * <p>若模板渲染结果为空（例如 tab-format 只写了 {@code {nickname}}
     * 而玩家没有昵称），回退到真实 ID —— 名字槽不能是空白。
     */
    public String buildTabName(Player player) {
        String nickname = getActiveNickname(player);
        String out = format(plugin.getConfigManager().getTabFormat(),
                player.getName(), nickname);
        return out.isEmpty() ? player.getName() : out;
    }

    /**
     * 计算玩家的头顶显示名（前缀 + 后缀，受 16 字符限制）。
     *
     * <p>注意：原版头顶 = 队伍前缀 + 实体名 + 队伍后缀，
     * 中间那个实体名无法通过 Bukkit API 移除。
     * 因此模板里**不应该**再写 {@code {name}}，否则会变成
     * "Steve" + "Steve#小明" 两个名字。
     */
    public String buildNametagName(Player player) {
        String nickname = getActiveNickname(player);
        String out = format(plugin.getConfigManager().getNametagFormat(),
                player.getName(), nickname);
        return out.isEmpty() ? player.getName() : out;
    }

    /**
     * 计算聊天栏显示名。
     */
    public String buildChatName(Player player) {
        String nickname = getActiveNickname(player);
        String out = format(plugin.getConfigManager().getChatFormat(),
                player.getName(), nickname);
        return out.isEmpty() ? player.getName() : out;
    }

    // ==========================================================
    //  校验（第 3 条：可配置可用字符）
    // ==========================================================

    /**
     * 校验玩家输入的昵称。
     *
     * <p>校验流程：
     * <ol>
     *   <li>去掉首尾空白，检查非空</li>
     *   <li>拒绝颜色代码（玩家必须走颜色 GUI）</li>
     *   <li>用字符集白名单校验每个字符</li>
     *   <li>检查长度（按码点计）</li>
     *   <li>检查是否与真实 ID 相同</li>
     *   <li>检查敏感词</li>
     * </ol>
     *
     * <p>注意：唯一性检查需要查库，不在此方法内做。
     *
     * @param player 玩家（用于消息与权限判定）
     * @param raw    原始输入
     * @return 校验结果
     */
    public ValidateResult validate(Player player, String raw) {
        // 玩家路径：禁止颜色代码（需求 15）
        return validate(player, raw, false);
    }

    /**
     * 校验昵称（可指定是否允许颜色代码）。
     *
     * <p>需求 15 的边界：
     * <ul>
     *   <li>玩家通过 GUI / 命令设置时 {@code allowColorCodes=false}，
     *       昵称中不得出现颜色代码，颜色只能通过颜色 GUI 设置</li>
     *   <li>管理员通过指令设置时 {@code allowColorCodes=true}，可直接写颜色代码</li>
     * </ul>
     *
     * <p>无论哪条路径，字符集、长度、敏感词校验一律生效——
     * 管理员也不能写入非法字符或超长昵称。
     *
     * @param player          玩家（用于消息与权限判定），可为 null（离线管理场景）
     * @param raw             原始输入
     * @param allowColorCodes 是否允许颜色代码
     * @return 校验结果
     */
    public ValidateResult validate(Player player, String raw, boolean allowColorCodes) {
        ConfigManager cfg = plugin.getConfigManager();

        if (raw == null) {
            raw = "";
        }

        // 输入界面占位提示
        String placeholder = cfg.getGuiInputPlaceholder();
        if (placeholder != null && raw.trim().equals(placeholder.trim())) {
            return ValidateResult.fail("validate.input-placeholder-error");
        }

        String input = raw.trim();
        if (input.isEmpty()) {
            return ValidateResult.fail("validate.empty");
        }

        // ---- 颜色代码限制（需求 15） ----
        // 玩家路径一律拒绝；管理员路径放行
        if (!allowColorCodes && cfg.isBlockColorCodes() && Msg.containsColor(input)) {
            Debug.log(Debug.VALIDATE, "拒绝含颜色代码的输入: {}", input);
            return ValidateResult.fail("validate.contains-color-code");
        }

        // ---- 剥离颜色后取纯文本（后续校验针对可见字符） ----
        String visible = Msg.stripColor(input);

        // ---- 移除不可见的伪装字符 ----
        String sanitized = NicknameNormalizer.sanitizeForDisplay(visible);
        if (sanitized.isEmpty()) {
            return ValidateResult.fail("validate.empty");
        }

        // ---- 字符集白名单校验 ----
        // 拥有 ncnick.bypass.charset 的玩家跳过（管理员默认拥有）
        CharsetFilter filter = cfg.getCharsetFilter();
        CharsetFilter.Result charsetResult = filter.validate(sanitized);
        boolean bypassCharset = player != null
                && Permissions.canBypass(player, Permissions.BYPASS_CHARSET);
        if (!charsetResult.isValid() && !bypassCharset) {
            Debug.log(Debug.VALIDATE, "字符集校验失败: 非法字符={}",
                    charsetResult.getInvalidChars());
            return ValidateResult.fail("validate.invalid-char",
                    LangManager.vars("chars", charsetResult.getInvalidChars()));
        }

        // ---- 长度校验（按码点） ----
        // 拥有 ncnick.bypass.length 的玩家跳过
        int len = charsetResult.getVisibleLength();
        int min = cfg.getMinLength();
        int max = cfg.getMaxLength();
        boolean bypassLength = player != null
                && Permissions.canBypass(player, Permissions.BYPASS_LENGTH);
        if (!bypassLength && (len < min || len > max)) {
            return ValidateResult.fail("validate.invalid-length",
                    LangManager.vars("min", String.valueOf(min),
                            "max", String.valueOf(max),
                            "length", String.valueOf(len)));
        }

        // ---- 与真实 ID 比较 ----
        // player 可能为 null（离线管理场景），此时跳过该检查
        if (cfg.isBlockSameAsRealname()
                && player != null
                && sanitized.equalsIgnoreCase(player.getName())) {
            return ValidateResult.fail("validate.same-as-realname");
        }

        // ---- 敏感词 ----
        // 拥有 ncnick.bypass.blocked 的玩家跳过
        boolean bypassBlocked = player != null
                && Permissions.canBypass(player, Permissions.BYPASS_BLOCKED);
        List<String> blocked = cfg.getBlockedWords();
        if (blocked != null && !bypassBlocked) {
            String lower = sanitized.toLowerCase();
            for (String word : blocked) {
                if (word != null && !word.isEmpty()
                        && lower.contains(word.toLowerCase())) {
                    return ValidateResult.fail("validate.blocked-word",
                            LangManager.vars("word", word));
                }
            }
        }

        Debug.log(Debug.VALIDATE, "校验通过: {} (长度 {})", sanitized, len);
        return ValidateResult.ok();
    }

    /**
     * 校验昵称唯一性（需要查库，必须在异步线程调用）。
     *
     * @param player 玩家
     * @param raw    原始昵称
     * @return 校验结果
     */
    public ValidateResult validateUnique(Player player, String raw) {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isUniqueEnabled()) {
            return ValidateResult.ok();
        }

        // 拥有 ncnick.bypass.unique 的玩家跳过唯一性检查（管理员默认拥有）
        if (player != null
                && Permissions.canBypass(player, Permissions.BYPASS_UNIQUE)) {
            Debug.log(Debug.VALIDATE, "玩家 {} 拥有唯一性豁免，跳过检查", player.getName());
            return ValidateResult.ok();
        }

        String visible = Msg.stripColor(raw == null ? "" : raw.trim());
        String normalized = NicknameNormalizer.normalize(
                visible,
                cfg.isUniqueIgnoreColors(),
                cfg.isUniqueIgnoreCase(),
                cfg.isUniqueIgnoreSpaces());

        if (normalized.isEmpty()) {
            return ValidateResult.ok();
        }

        boolean taken = plugin.getStorage().isNicknameTaken(
                normalized, player.getUniqueId());
        if (taken) {
            Debug.log(Debug.VALIDATE, "昵称重复: {} -> {}", raw, normalized);
            return ValidateResult.fail("validate.duplicate",
                    LangManager.vars("nickname", visible));
        }
        return ValidateResult.ok();
    }

    // ==========================================================
    //  设置昵称
    // ==========================================================

    /**
     * 设置昵称（异步校验 + 同步应用）。
     *
     * @param player       玩家
     * @param rawNickname  原始昵称
     * @param payType      支付方式，null 表示自动选择（有权限则免费）
     * @param onComplete   完成回调（主线程执行），可为 null
     */
    public void setNickname(final Player player, final String rawNickname,
                            final PayType payType, final Runnable onComplete) {
        final Callback cb = adapt(onComplete);
        // 第一层：本地校验（无需查库，立即反馈）
        final ValidateResult local = validate(player, rawNickname);
        if (!local.isOk()) {
            sendValidateError(player, local);
            if (cb != null) {
                cb.done(false);
            }
            return;
        }

        final String nickname = Msg.stripColor(rawNickname.trim());

        // 第二层：异步查重
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final ValidateResult unique = validateUnique(player, nickname);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!unique.isOk()) {
                            sendValidateError(player, unique);
                            if (cb != null) {
                                cb.done(false);
                            }
                            return;
                        }
                        applyNickname(player, nickname, payType, cb);
                    }
                });
            }
        });
    }

    /**
     * 实际执行设置（主线程）。
     */
    private void applyNickname(Player player, String nickname, PayType payType,
                               Callback onComplete) {
        ConfigManager cfg = plugin.getConfigManager();
        LangManager lang = plugin.getLang();

        boolean hasPermission = cfg.isPermissionModeEnabled()
                && hasNickPermission(player);

        // 决定支付方式
        PayType effective;
        if (hasPermission && (payType == null || payType == PayType.FREE)) {
            effective = PayType.FREE;
        } else if (payType == null) {
            // 未指定时按配置自动选择（默认优先金币）
            effective = resolveDefaultPayType();
        } else {
            effective = payType;
        }

        // 检查模式是否开放
        if (effective == PayType.FREE && !cfg.isPermissionModeEnabled()) {
            // 权限模式未开放
            player.sendMessage(lang.msg("purchase.mode-disabled"));
            if (onComplete != null) {
                onComplete.done(false);
            }
            return;
        }
        if (effective != PayType.FREE && !cfg.isPurchaseEnabled()) {
            player.sendMessage(lang.msg("purchase.mode-disabled"));
            if (onComplete != null) {
                onComplete.done(false);
            }
            return;
        }

        // ---- 扣费 ----
        ChargeOutcome outcome = charge(player, effective, ChargeKind.NICKNAME,
                nickname, null);
        if (!outcome.ok) {
            player.sendMessage(outcome.message);
            if (onComplete != null) {
                onComplete.done(false);
            }
            return;
        }

        // ---- 写入 ----
        NickMode mode = effective == PayType.FREE ? NickMode.PERMISSION : NickMode.PURCHASE;
        NickData existing = cache.get(player.getUniqueId());
        ColorPreset color = existing == null ? null : existing.getColor();

        final NickData data = new NickData(player.getUniqueId(), player.getName(),
                nickname, color, mode, System.currentTimeMillis());

        // 关键：先落库，成功后才更新缓存与显示。
        // 这样可彻底避免"提示成功但实际未写入"的假成功（唯一约束冲突场景）。
        final UUID uuid = player.getUniqueId();
        final boolean freeMode = effective == PayType.FREE;
        final String chargeDesc = outcome.desc;

        beginWrite();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ok = plugin.getStorage().save(data);
                } finally {
                    endWrite();
                }
                final boolean saved = ok;
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            // 玩家已离线：若写库成功则仅更新缓存
                            if (saved) {
                                cache.put(uuid, data);
                            }
                            if (onComplete != null) {
                                onComplete.done(saved);
                            }
                            return;
                        }

                        if (!saved) {
                            // 写库失败（多为昵称被抢占）：提示玩家，不改动显示
                            player.sendMessage(lang.msg("validate.duplicate",
                                    LangManager.vars("nickname", nickname)));
                            if (onComplete != null) {
                                onComplete.done(false);
                            }
                            return;
                        }

                        // 写库成功：更新缓存并应用显示
                        cache.put(uuid, data);
                        apply(player);

                        // ---- 成功消息 ----
                        Map<String, String> vars = new HashMap<String, String>();
                        vars.put("display", Msg.color(buildChatName(player)));
                        vars.put("nickname", nickname);
                        StringBuilder sb = new StringBuilder();
                        sb.append(lang.prefix());
                        if (freeMode) {
                            sb.append(lang.text("purchase.free-success", vars));
                        } else {
                            sb.append(lang.text("purchase.set-success", vars));
                        }
                        if (chargeDesc != null && !chargeDesc.isEmpty()) {
                            sb.append("\n").append(lang.prefix())
                                    .append(lang.text("purchase.buy-success",
                                            LangManager.vars("price", chargeDesc)));
                        }
                        player.sendMessage(sb.toString());

                        if (onComplete != null) {
                            onComplete.done(true);
                        }
                    }
                });
            }
        });
    }

    /**
     * 按配置解析指定商品的默认支付方式（玩家未显式选择时）。
     *
     * <p>昵称与颜色各自独立：{@code economy.yml} 中
     * {@code purchase.nickname.currency} 与 {@code purchase.color.currency}
     * 可分别设为 coins / points / both。
     *
     * @param product 商品类型
     * @return 默认支付方式
     */
    public PayType resolveDefaultPayType(com.ncnick.model.ProductType product) {
        String currency = plugin.getConfigManager().getCurrency(product);
        if ("coins".equals(currency)) {
            return PayType.COINS;
        }
        if ("points".equals(currency)) {
            return PayType.POINTS;
        }
        // both：优先金币（默认直接用金币）
        if (plugin.getVaultHook().isEnabled()) {
            return PayType.COINS;
        }
        if (plugin.getPlayerPointsHook().isEnabled()) {
            return PayType.POINTS;
        }
        return PayType.COINS;
    }

    /** 向后兼容：不指定商品时按昵称处理。 */
    public PayType resolveDefaultPayType() {
        return resolveDefaultPayType(com.ncnick.model.ProductType.NICKNAME);
    }

    /**
     * 指定商品当前实际可用的支付方式（供 GUI 决定显示哪些按钮）。
     *
     * @param product 商品类型
     * @return 可用支付方式列表
     */
    public java.util.List<PayType> availablePayTypes(com.ncnick.model.ProductType product) {
        java.util.List<PayType> out = new java.util.ArrayList<PayType>();
        String currency = plugin.getConfigManager().getCurrency(product);
        boolean coinsOk = plugin.getVaultHook().isEnabled()
                && ("coins".equals(currency) || "both".equals(currency));
        boolean pointsOk = plugin.getPlayerPointsHook().isEnabled()
                && ("points".equals(currency) || "both".equals(currency));
        if (coinsOk) {
            out.add(PayType.COINS);
        }
        if (pointsOk) {
            out.add(PayType.POINTS);
        }
        return out;
    }

    /** 向后兼容：不指定商品时按昵称处理。 */
    public java.util.List<PayType> availablePayTypes() {
        return availablePayTypes(com.ncnick.model.ProductType.NICKNAME);
    }

    // ==========================================================
    //  设置颜色（第 6 条）
    // ==========================================================

    /**
     * 为玩家设置昵称颜色（带扣费）。
     *
     * @param player    玩家
     * @param preset    颜色
     * @param payType   支付方式
     * @param onComplete 完成回调
     */
    public void setColor(final Player player, final ColorPreset preset,
                         final PayType payType, final Runnable onComplete) {
        final Callback cb = adapt(onComplete);
        final ConfigManager cfg = plugin.getConfigManager();
        final LangManager lang = plugin.getLang();

        NickData data = cache.get(player.getUniqueId());
        if (data == null || !data.hasNickname()) {
            player.sendMessage(lang.msg("validate.not-set"));
            if (cb != null) {
                cb.done(false);
            }
            return;
        }

        // 颜色权限检查
        if (cfg.isColorPermissionRequired()
                && !player.hasPermission(cfg.getColorPermission())) {
            player.sendMessage(lang.msg("color.no-color-permission",
                    LangManager.vars("permission", cfg.getColorPermission())));
            if (cb != null) {
                cb.done(false);
            }
            return;
        }

        boolean hasPermission = cfg.isPermissionModeEnabled()
                && hasNickPermission(player);

        final PayType effective;
        if (hasPermission && (payType == null || payType == PayType.FREE)) {
            effective = PayType.FREE;
        } else if (payType == null) {
            // 颜色有自己的 currency 配置（purchase.color.currency）
            effective = resolveDefaultPayType(com.ncnick.model.ProductType.COLOR);
        } else {
            effective = payType;
        }

        if (effective != PayType.FREE && !cfg.isPurchaseEnabled()) {
            player.sendMessage(lang.msg("purchase.mode-disabled"));
            if (cb != null) {
                cb.done(false);
            }
            return;
        }

        // ---- 扣费 ----
        ChargeOutcome outcome = charge(player, effective, ChargeKind.COLOR,
                data.getNickname(), preset);
        if (!outcome.ok) {
            player.sendMessage(outcome.message);
            if (cb != null) {
                cb.done(false);
            }
            return;
        }

        // ---- 应用 ----
        // 同样先落库、成功后再更新缓存与显示，避免假成功
        final NickData updated = data.withColor(preset, System.currentTimeMillis());
        final UUID uuid = player.getUniqueId();
        final String colorDesc = preset == null ? "默认" : preset.describeColored();
        final String nickName = data.getNickname();
        final String chargeDesc = outcome.desc;

        beginWrite();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ok = plugin.getStorage().save(updated);
                } finally {
                    endWrite();
                }
                final boolean saved = ok;
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            if (saved) {
                                cache.put(uuid, updated);
                            }
                            if (cb != null) {
                                cb.done(saved);
                            }
                            return;
                        }

                        if (!saved) {
                            player.sendMessage(lang.msg("purchase.payment-failed"));
                            if (cb != null) {
                                cb.done(false);
                            }
                            return;
                        }

                        cache.put(uuid, updated);
                        apply(player);

                        // ---- 成功消息 ----
                        Map<String, String> vars = new HashMap<String, String>();
                        vars.put("color", colorDesc);
                        vars.put("nickname", nickName);
                        StringBuilder sb = new StringBuilder();
                        sb.append(lang.prefix())
                                .append(lang.text("purchase.color-buy-success", vars));
                        if (chargeDesc != null && !chargeDesc.isEmpty()) {
                            sb.append("\n").append(lang.prefix())
                                    .append(lang.text("purchase.buy-success",
                                            LangManager.vars("price", chargeDesc)));
                        }
                        player.sendMessage(sb.toString());

                        if (cb != null) {
                            cb.done(true);
                        }
                    }
                });
            }
        });
    }

    // ==========================================================
    //  扣费
    // ==========================================================

    /** 扣费项目类型 */
    public enum ChargeKind {
        /** 设置昵称 */
        NICKNAME,
        /** 设置颜色 */
        COLOR
    }

    /** 扣费结果 */
    public static final class ChargeOutcome {
        public final boolean ok;
        public final String message;
        public final String desc;
        public final PayType payType;
        public final double amount;

        ChargeOutcome(boolean ok, String message, String desc, PayType payType, double amount) {
            this.ok = ok;
            this.message = message;
            this.desc = desc;
            this.payType = payType;
            this.amount = amount;
        }
    }

    /**
     * 计算价格。
     *
     * @param kind      项目
     * @param payType   支付方式
     * @param nickname  昵称（用于计算超长加价），可为 null
     * @param preset    颜色（用于区分纯色/渐变价格），可为 null
     * @return 价格（金币为 double，点券为整数部分）
     */
    public double calcPrice(ChargeKind kind, PayType payType, String nickname,
                            ColorPreset preset) {
        ConfigManager cfg = plugin.getConfigManager();
        if (payType == PayType.FREE) {
            return 0.0D;
        }
        boolean coins = payType == PayType.COINS;

        if (kind == ChargeKind.COLOR) {
            boolean gradient = preset != null && preset.isGradient();
            if (coins) {
                return gradient ? cfg.getGradientPriceCoins() : cfg.getSolidPriceCoins();
            }
            return gradient ? cfg.getGradientPricePoints() : cfg.getSolidPricePoints();
        }

        // 昵称价格
        double base = coins ? cfg.getNicknamePriceCoins() : cfg.getNicknamePricePoints();

        // 超长加价
        if (cfg.isExtraFeeEnabled() && nickname != null) {
            int visible = Msg.visibleLength(nickname);
            int threshold = cfg.getExtraFeeThreshold();
            if (visible > threshold) {
                int extraChars = visible - threshold;
                double perChar = coins
                        ? cfg.getExtraFeePerCharCoins()
                        : cfg.getExtraFeePerCharPoints();
                base += extraCharCost(extraChars, perChar);
            }
        }
        return base;
    }

    private static double extraCharCost(int extraChars, double perChar) {
        return extraChars * perChar;
    }

    /**
     * 执行扣费。
     *
     * <p>策略：先检查余额，余额足够才扣款；扣款失败则返回错误。
     * 扣款成功后若后续步骤失败，调用方负责退款（当前设计无后续失败点）。
     */
    public ChargeOutcome charge(Player player, PayType payType, ChargeKind kind,
                                String nickname, ColorPreset preset) {
        LangManager lang = plugin.getLang();
        if (payType == PayType.FREE) {
            return new ChargeOutcome(true, null, null, PayType.FREE, 0.0D);
        }

        double price = calcPrice(kind, payType, nickname, preset);
        if (price <= 0) {
            // 价格为 0 视为免费
            return new ChargeOutcome(true, null, null, payType, 0.0D);
        }

        if (payType == PayType.COINS) {
            if (!plugin.getVaultHook().isEnabled()) {
                return new ChargeOutcome(false, lang.msg("purchase.no-economy"),
                        null, payType, price);
            }
            double balance = plugin.getVaultHook().getBalance(player);
            if (balance < price) {
                return new ChargeOutcome(false,
                        lang.msg("purchase.not-enough-coins",
                                LangManager.vars("price", formatAmount(price),
                                        "balance", formatAmount(balance))),
                        null, payType, price);
            }
            if (!plugin.getVaultHook().withdraw(player, price)) {
                return new ChargeOutcome(false, lang.msg("purchase.payment-failed"),
                        null, payType, price);
            }
            return new ChargeOutcome(true, null, formatAmount(price) + " 金币",
                    payType, price);
        }

        // 点券
        if (!plugin.getPlayerPointsHook().isEnabled()) {
            return new ChargeOutcome(false, lang.msg("purchase.no-economy"),
                    null, payType, price);
        }
        int points = (int) Math.round(price);
        int balance = plugin.getPlayerPointsHook().getPoints(player.getUniqueId());
        if (balance < points) {
            return new ChargeOutcome(false,
                    lang.msg("purchase.not-enough-points",
                            LangManager.vars("price", String.valueOf(points),
                                    "balance", String.valueOf(balance))),
                    null, payType, price);
        }
        if (!plugin.getPlayerPointsHook().take(player.getUniqueId(), points)) {
            return new ChargeOutcome(false, lang.msg("purchase.payment-failed"),
                    null, payType, price);
        }
        return new ChargeOutcome(true, null, points + " 点券", payType, price);
    }

    /** 金额格式化（整数省略小数）。 */
    private static String formatAmount(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount)) {
            return String.valueOf((long) amount);
        }
        return String.format("%.2f", amount);
    }

    // ==========================================================
    //  删除
    // ==========================================================

    /** 玩家自己删除昵称。 */
    public void removeOwn(final Player player) {
        final UUID uuid = player.getUniqueId();
        final NickData data = cache.get(uuid);
        if (data == null || !data.hasNickname()) {
            player.sendMessage(plugin.getLang().msg("validate.not-set"));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                plugin.getStorage().remove(uuid);
                cache.remove(uuid);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        apply(player);
                        player.sendMessage(plugin.getLang().msg("remove.success"));
                    }
                });
            }
        });
    }

    /**
     * 管理员删除指定玩家昵称。
     *
     * @param uuid   目标 UUID
     * @param name   目标名字（用于消息）
     * @param notify 是否通知目标玩家
     */
    public void adminRemove(final UUID uuid, final String name, final boolean notify) {
        if (uuid == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                plugin.getStorage().remove(uuid);
                cache.remove(uuid);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        Player target = Bukkit.getPlayer(uuid);
                        if (target != null) {
                            apply(target);
                            if (notify) {
                                target.sendMessage(plugin.getLang().msg("remove.success"));
                            }
                        }
                    }
                });
            }
        });
    }

    /**
     * 管理员设置昵称（需求 15：允许直接使用颜色代码）。
     *
     * <p>与玩家路径的差异：
     * <ul>
     *   <li><b>允许</b>昵称中含颜色代码（玩家路径禁止，必须走颜色 GUI）</li>
     *   <li>其余校验一律生效：字符集、长度、敏感词、全服唯一性</li>
     * </ul>
     *
     * <p>异步完成"校验 → 查重 → 写库"，通过 {@link AdminCallback}
     * 返回真实结果与失败原因，避免"提示成功但实际未写入"的假成功。
     *
     * @param target      目标玩家（在线）
     * @param rawNickname 原始昵称，可含颜色代码
     * @param callback    结果回调（主线程执行），可为 null
     */
    public void adminSet(final Player target, final String rawNickname,
                         final AdminCallback callback) {
        // ---- 第一层：本地校验（允许颜色代码） ----
        final ValidateResult check = validate(target, rawNickname, true);
        if (!check.isOk()) {
            if (callback != null) {
                callback.done(false, check);
            }
            return;
        }

        // 管理员路径保留颜色代码，不做 stripColor
        final String nickname = rawNickname.trim();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                // ---- 第二层：全服唯一性检查（管理员也不能制造重复昵称） ----
                final ValidateResult unique = validateUnique(target, nickname);

                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!unique.isOk()) {
                            if (callback != null) {
                                callback.done(false, unique);
                            }
                            return;
                        }

                        NickData existing = cache.get(target.getUniqueId());
                        ColorPreset color = existing == null ? null : existing.getColor();
                        final NickData data = new NickData(target.getUniqueId(),
                                target.getName(), nickname, color,
                                NickMode.PERMISSION, System.currentTimeMillis());

                        beginWrite();
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
                            @Override
                            public void run() {
                                boolean ok = false;
                                try {
                                    ok = plugin.getStorage().save(data);
                                } finally {
                                    endWrite();
                                }
                                final boolean saved = ok;
                                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                                    @Override
                                    public void run() {
                                        if (saved) {
                                            cache.put(target.getUniqueId(), data);
                                            if (target.isOnline()) {
                                                apply(target);
                                            }
                                            if (callback != null) {
                                                callback.done(true, null);
                                            }
                                        } else {
                                            // 落库失败（并发抢占同一昵称）
                                            if (callback != null) {
                                                callback.done(false, ValidateResult.fail(
                                                        "validate.duplicate",
                                                        LangManager.vars("nickname", nickname)));
                                            }
                                        }
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
    }

    /**
     * 管理员设置操作的回调。
     */
    public interface AdminCallback {
        /**
         * @param success 是否成功
         * @param failure 失败原因（成功时为 null），携带语言键与参数
         */
        void done(boolean success, ValidateResult failure);
    }

    // ==========================================================
    //  显示应用
    // ==========================================================

    /**
     * 把昵称应用到玩家的各个显示位置。
     *
     * <p>注意：若实验性功能接管了某个显示位置，这里会跳过以避免冲突。
     */
    public void apply(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            plugin.getDisplayManager().applyAll(player);
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "应用显示失败: " + player.getName(), t);
        }
    }

    /** 重新应用所有在线玩家。 */
    public void reapplyAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            apply(p);
        }
    }

    // ==========================================================
    //  玩家进出
    // ==========================================================

    /**
     * 玩家加入：异步加载数据后应用显示。
     */
    public void onJoin(final Player player) {
        final UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final NickData data = plugin.getStorage().load(uuid);
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (data != null) {
                            cache.put(uuid, data);
                        }
                        if (player.isOnline()) {
                            apply(player);
                        }
                    }
                });
            }
        });
    }

    /** 玩家退出：清理缓存与显示残留。 */
    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        cache.remove(uuid);
        permCache.remove(uuid);
        try {
            plugin.getDisplayManager().cleanup(player);
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "清理显示失败: " + player.getName(), t);
        }
    }

    // ==========================================================
    //  工具
    // ==========================================================

    /** 发送校验失败消息。 */
    private void sendValidateError(Player player, ValidateResult result) {
        String key = result.getMessageKey();
        if (key == null) {
            return;
        }
        player.sendMessage(plugin.getLang().msg(key, result.getParams()));
    }

    /** 打开昵称输入界面。 */
    public void openInputGui(Player player) {
        try {
            plugin.getGuiManager().openNicknameInput(player);
        } catch (Throwable t) {
            player.sendMessage(plugin.getLang().msg("msg.gui-error"));
            Debug.error(Debug.GENERAL, "打开输入界面失败", t);
        }
    }
}
