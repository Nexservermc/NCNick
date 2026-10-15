package com.ncnick.hook;

import com.ncnick.NCNick;
import com.ncnick.util.Debug;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault 经济钩子（金币支付）。
 *
 * <p>通过反射访问 Vault API，使得未安装 Vault 时插件仍能正常加载
 * （不产生 NoClassDefFoundError）。
 */
public class VaultHook {

    /** Vault 经济接口的全限定名（公开接口，反射取方法必须以它为准） */
    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";

    private final NCNick plugin;
    private Object economy;
    /** 经济接口的 Class 对象，所有方法都从这里取（见下方说明） */
    private Class<?> economyInterface;
    private boolean enabled = false;
    /** 未接入的原因，用于在启动摘要里给出准确提示 */
    private String unavailableReason;

    public VaultHook(NCNick plugin) {
        this.plugin = plugin;
    }

    /**
     * 尝试接入 Vault。
     *
     * @return 是否成功
     */
    public boolean setup() {
        enabled = false;
        economy = null;
        economyInterface = null;
        unavailableReason = null;

        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            // 注意：装了经济插件 ≠ 装了 Vault。
            // Vault 是中间桥接插件，绝大多数经济插件需要它才能被第三方读取。
            unavailableReason = "未安装 Vault";
            Debug.log(Debug.GENERAL, "未检测到 Vault 插件");
            return false;
        }

        try {
            economyInterface = Class.forName(ECONOMY_CLASS);
        } catch (Throwable t) {
            unavailableReason = "Vault 版本不兼容";
            Debug.error(Debug.GENERAL, "加载 Vault Economy 接口失败", t);
            return false;
        }

        try {
            RegisteredServiceProvider<?> rsp =
                    Bukkit.getServicesManager().getRegistration(economyInterface);
            if (rsp == null) {
                // Vault 在，但没有经济插件注册服务
                unavailableReason = "Vault 已装但无经济插件注册服务";
                Debug.log(Debug.GENERAL, "Vault 未注册经济服务（可能未安装经济插件）");
                return false;
            }
            economy = rsp.getProvider();
            if (economy == null) {
                unavailableReason = "经济服务提供者为空";
                return false;
            }
            enabled = true;
            Debug.log(Debug.GENERAL, "已接入 Vault 经济: {}",
                    economy.getClass().getName());
            return true;
        } catch (Throwable t) {
            unavailableReason = "接入异常: " + t.getMessage();
            Debug.error(Debug.GENERAL, "接入 Vault 失败", t);
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled && economy != null;
    }

    /**
     * 未接入经济的原因（用于启动摘要给出准确提示）。
     *
     * @return 原因描述；已成功接入或无信息时返回 null
     */
    public String getUnavailableReason() {
        return isEnabled() ? null : unavailableReason;
    }

    /**
     * 从**公开的 Economy 接口**上取方法。
     *
     * <h3>为什么不能用 economy.getClass().getMethod(...)</h3>
     * {@code economy} 是别的插件提供的实现实例（EssentialsX、CMI、XConomy…）。
     * 那些实现类往往不是 public，而 {@code Class.getMethod} 会返回一个
     * "声明类为非 public" 的 Method —— 调用时抛 {@code IllegalAccessException}。
     * 这与本项目此前在 {@code SkullMeta}、{@code LegacyComponentSerializer}
     * 上踩到的是同一类坑。
     *
     * <p>从公开接口取方法，则声明类是 public 的，反射调用始终合法。
     *
     * @param name   方法名
     * @param params 参数类型
     * @return 方法对象；不存在时返回 null
     */
    private java.lang.reflect.Method economyMethod(String name, Class<?>... params) {
        if (economyInterface == null) {
            return null;
        }
        try {
            return economyInterface.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 查询玩家余额。
     *
     * @return 余额；不可用时返回 0
     */
    public double getBalance(Player player) {
        if (!isEnabled() || player == null) {
            return 0.0D;
        }
        java.lang.reflect.Method m = economyMethod("getBalance", OfflinePlayer.class);
        if (m != null) {
            try {
                Object result = m.invoke(economy, player);
                return result instanceof Number ? ((Number) result).doubleValue() : 0.0D;
            } catch (Throwable t) {
                Debug.error(Debug.GENERAL, "查询余额失败（OfflinePlayer）", t);
            }
        }
        // 少数旧实现只提供 String 重载
        m = economyMethod("getBalance", String.class);
        if (m != null) {
            try {
                Object result = m.invoke(economy, player.getName());
                return result instanceof Number ? ((Number) result).doubleValue() : 0.0D;
            } catch (Throwable t) {
                Debug.error(Debug.GENERAL, "查询余额失败（String）", t);
            }
        }
        return 0.0D;
    }

    /**
     * 扣款。
     *
     * @param player 玩家
     * @param amount 金额
     * @return 是否成功
     */
    public boolean withdraw(Player player, double amount) {
        if (!isEnabled() || player == null || amount <= 0) {
            return false;
        }
        java.lang.reflect.Method m =
                economyMethod("withdrawPlayer", OfflinePlayer.class, double.class);
        if (m != null) {
            try {
                return isSuccess(m.invoke(economy, player, amount));
            } catch (Throwable t) {
                Debug.error(Debug.GENERAL, "扣款失败（OfflinePlayer）", t);
            }
        }
        m = economyMethod("withdrawPlayer", String.class, double.class);
        if (m != null) {
            try {
                return isSuccess(m.invoke(economy, player.getName(), amount));
            } catch (Throwable t) {
                Debug.error(Debug.GENERAL, "扣款失败（String）", t);
            }
        }
        return false;
    }

    /**
     * 退款（支付失败回滚时使用）。
     */
    public boolean deposit(Player player, double amount) {
        if (!isEnabled() || player == null || amount <= 0) {
            return false;
        }
        java.lang.reflect.Method m =
                economyMethod("depositPlayer", OfflinePlayer.class, double.class);
        if (m != null) {
            try {
                return isSuccess(m.invoke(economy, player, amount));
            } catch (Throwable t) {
                Debug.error(Debug.GENERAL, "退款失败（OfflinePlayer）", t);
            }
        }
        m = economyMethod("depositPlayer", String.class, double.class);
        if (m != null) {
            try {
                return isSuccess(m.invoke(economy, player.getName(), amount));
            } catch (Throwable t) {
                Debug.error(Debug.GENERAL, "退款失败（String）", t);
            }
        }
        return false;
    }

    /**
     * 解析 Vault 的 EconomyResponse，判断交易是否成功。
     *
     * <p>同样从公开类型取方法：{@code EconomyResponse} 是 Vault 的公开接口，
     * 但实现的匿名/内部类未必 public。
     */
    private boolean isSuccess(Object response) {
        if (response == null) {
            return false;
        }
        try {
            Class<?> responseInterface =
                    Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            java.lang.reflect.Method m =
                    responseInterface.getMethod("transactionSuccess");
            Object r = m.invoke(response);
            if (r instanceof Boolean) {
                return (Boolean) r;
            }
        } catch (Throwable ignored) {
            // 兼容没有该方法的情况
        }
        try {
            Class<?> responseInterface =
                    Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            java.lang.reflect.Field f = responseInterface.getField("type");
            Object type = f.get(response);
            return type != null && "SUCCESS".equals(String.valueOf(type));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 货币名称（用于消息显示），失败时返回"金币"。 */
    public String getCurrencyName() {
        if (!isEnabled()) {
            return "金币";
        }
        java.lang.reflect.Method m = economyMethod("currencyNamePlural");
        if (m != null) {
            try {
                Object r = m.invoke(economy);
                if (r != null && !String.valueOf(r).isEmpty()) {
                    return String.valueOf(r);
                }
            } catch (Throwable ignored) {
                // 使用默认名
            }
        }
        return "金币";
    }

    /** 格式化金额显示（整数省略小数）。 */
    public static String format(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount)) {
            return String.valueOf((long) amount);
        }
        return String.format("%.2f", amount);
    }
}
