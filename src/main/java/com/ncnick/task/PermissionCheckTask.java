package com.ncnick.task;

import com.ncnick.NCNick;
import com.ncnick.model.NickData;
import com.ncnick.model.NickMode;
import com.ncnick.util.Debug;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 权限掉线检查任务。
 *
 * <p>权限模式下，玩家的昵称有效性依赖权限。当权限被移除后，
 * 需要定期检查并停止显示该昵称。
 */
public class PermissionCheckTask extends BukkitRunnable {

    private final NCNick plugin;

    public PermissionCheckTask(NCNick plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!plugin.getConfigManager().isPermissionModeEnabled()
                || !plugin.getConfigManager().isRevokeOnPermissionLost()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                // 强制刷新权限缓存，让 isActive() 能拿到最新权限状态
                boolean hasNow = plugin.getNickManager().refreshPermissionCache(player);

                NickData data = plugin.getNickManager().get(player.getUniqueId());
                if (data == null || data.getMode() != NickMode.PERMISSION) {
                    continue;
                }
                // 权限已失效则重新应用（会退化为原版显示）
                if (!hasNow) {
                    Debug.log(Debug.CACHE, "玩家 {} 权限已失效，恢复原版显示",
                            player.getName());
                    plugin.getNickManager().apply(player);
                }
            } catch (Throwable t) {
                Debug.error(Debug.GENERAL, "权限检查失败: " + player.getName(), t);
            }
        }
    }
}
