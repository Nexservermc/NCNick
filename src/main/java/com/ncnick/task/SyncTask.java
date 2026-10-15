package com.ncnick.task;

import com.ncnick.NCNick;
import com.ncnick.model.NickData;
import com.ncnick.model.SyncEntry;
import com.ncnick.util.Debug;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 跨服同步任务（仅 MySQL 模式）。
 *
 * <p>定期轮询 sync 表，把其他服务器上的昵称变更同步到本服。
 */
public class SyncTask extends BukkitRunnable {

    /** 单次拉取上限 */
    private static final int BATCH_LIMIT = 200;

    private final NCNick plugin;
    private volatile long lastId = 0L;
    private volatile boolean running = false;

    public SyncTask(NCNick plugin) {
        this.plugin = plugin;
    }

    /**
     * 启动任务：先初始化游标到当前最大值，再做周期轮询。
     */
    public void start() {
        // 初始化游标（异步，避免阻塞启动）
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    lastId = plugin.getStorage().getMaxSyncId();
                    Debug.log(Debug.SQL, "同步游标初始化: {}", lastId);
                } catch (Throwable t) {
                    Debug.error(Debug.SQL, "初始化同步游标失败", t);
                }
            }
        });

        int interval = plugin.getConfigManager().getSyncInterval();
        // 延迟 5 秒启动，避免与服务端启动竞争
        runTaskTimerAsynchronously(plugin, 100L, interval * 20L);
    }

    @Override
    public void run() {
        // 防止上一轮未结束时重入
        if (running) {
            return;
        }
        running = true;
        try {
            syncOnce();
        } catch (Throwable t) {
            Debug.error(Debug.SQL, "跨服同步失败", t);
        } finally {
            running = false;
        }
    }

    /**
     * 执行一次同步。
     */
    private void syncOnce() {
        if (!plugin.getStorage().isReady()) {
            return;
        }

        List<SyncEntry> entries = plugin.getStorage().pollSync(lastId, BATCH_LIMIT);
        if (entries.isEmpty()) {
            return;
        }

        for (final SyncEntry entry : entries) {
            lastId = Math.max(lastId, entry.getId());
            applyEntry(entry);
        }
        Debug.log(Debug.SQL, "同步了 {} 条变更，游标 -> {}", entries.size(), lastId);
    }

    /**
     * 应用一条同步记录。
     */
    private void applyEntry(final SyncEntry entry) {
        if (entry.getUuid() == null) {
            return;
        }
        if (entry.isRemoval()) {
            plugin.getNickManager().removeCache(entry.getUuid());
        } else {
            plugin.getNickManager().put(entry.getUuid(), entry.toNickData());
        }

        // 切回主线程刷新显示
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                Player target = Bukkit.getPlayer(entry.getUuid());
                if (target != null && target.isOnline()) {
                    plugin.getNickManager().apply(target);
                }
            }
        });
    }

    /** 当前游标（供调试）。 */
    public long getLastId() {
        return lastId;
    }

    /** 重新初始化游标（reload 后调用）。 */
    public void resetCursor() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    lastId = plugin.getStorage().getMaxSyncId();
                } catch (Throwable t) {
                    Debug.error(Debug.SQL, "重置同步游标失败", t);
                }
            }
        });
    }
}
