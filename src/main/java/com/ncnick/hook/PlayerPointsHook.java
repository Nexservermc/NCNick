package com.ncnick.hook;

import com.ncnick.NCNick;
import com.ncnick.util.Debug;
import java.util.UUID;
import org.bukkit.Bukkit;

/**
 * PlayerPoints 点券钩子。
 *
 * <p>通过反射调用 PlayerPoints API，避免未安装时类加载失败。
 */
public class PlayerPointsHook {

    private final NCNick plugin;
    private Object api;
    private boolean enabled = false;
    /** 未接入的原因，用于在启动摘要里给出准确提示 */
    private String unavailableReason;

    public PlayerPointsHook(NCNick plugin) {
        this.plugin = plugin;
    }

    /**
     * 尝试接入 PlayerPoints。
     *
     * @return 是否成功
     */
    public boolean setup() {
        enabled = false;
        api = null;
        unavailableReason = null;

        if (Bukkit.getPluginManager().getPlugin("PlayerPoints") == null) {
            unavailableReason = "未安装 PlayerPoints";
            Debug.log(Debug.GENERAL, "未检测到 PlayerPoints 插件");
            return false;
        }

        try {
            Class<?> ppClass = Class.forName("org.black_ixx.playerpoints.PlayerPoints");
            Object instance = ppClass.getMethod("getInstance").invoke(null);
            if (instance == null) {
                unavailableReason = "PlayerPoints 尚未初始化";
                return false;
            }
            Object apiInstance = ppClass.getMethod("getAPI").invoke(instance);
            if (apiInstance == null) {
                unavailableReason = "PlayerPoints API 为空";
                return false;
            }
            api = apiInstance;
            enabled = true;
            Debug.log(Debug.GENERAL, "已接入 PlayerPoints");
            return true;
        } catch (Throwable t) {
            unavailableReason = "接入异常: " + t.getMessage();
            Debug.error(Debug.GENERAL, "接入 PlayerPoints 失败", t);
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled && api != null;
    }

    /**
     * 未接入点券的原因（用于启动摘要给出准确提示）。
     *
     * @return 原因描述；已成功接入时返回 null
     */
    public String getUnavailableReason() {
        return isEnabled() ? null : unavailableReason;
    }

    /**
     * 查询玩家点券余额。
     *
     * @return 余额；不可用时返回 0
     */
    public int getPoints(UUID uuid) {
        if (!isEnabled() || uuid == null) {
            return 0;
        }
        try {
            java.lang.reflect.Method m = api.getClass().getMethod("look", UUID.class);
            Object r = m.invoke(api, uuid);
            return r instanceof Number ? ((Number) r).intValue() : 0;
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "查询点券失败", t);
            return 0;
        }
    }

    /**
     * 扣除点券。
     *
     * @return 是否成功
     */
    public boolean take(UUID uuid, int amount) {
        if (!isEnabled() || uuid == null || amount <= 0) {
            return false;
        }
        try {
            java.lang.reflect.Method m = api.getClass()
                    .getMethod("take", UUID.class, int.class);
            Object r = m.invoke(api, uuid, amount);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "扣除点券失败", t);
            return false;
        }
    }

    /**
     * 返还点券（用于失败回滚）。
     */
    public boolean give(UUID uuid, int amount) {
        if (!isEnabled() || uuid == null || amount <= 0) {
            return false;
        }
        try {
            java.lang.reflect.Method m = api.getClass()
                    .getMethod("give", UUID.class, int.class);
            Object r = m.invoke(api, uuid, amount);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "返还点券失败", t);
            return false;
        }
    }
}
