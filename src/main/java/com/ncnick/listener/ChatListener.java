package com.ncnick.listener;

import com.ncnick.NCNick;
import com.ncnick.util.Debug;
import com.ncnick.util.Msg;
import com.ncnick.util.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * 聊天显示监听器。
 *
 * <p>负责把聊天栏中的玩家名替换为自定义昵称。
 *
 * <p>实现说明：
 * <ul>
 *   <li>使用 {@code HIGH} 优先级，<b>较晚</b>地修改格式，确保能看到其他插件
 *       （TrChat、EssentialsX 等）处理后的结果。
 *       注意：Bukkit 的优先级顺序是
 *       {@code LOWEST → LOW → NORMAL → HIGH → HIGHEST → MONITOR}，
 *       即 <b>LOWEST 最先执行</b>。早期版本误用了 LOWEST，
 *       导致我们的改动会被后执行的插件覆盖。</li>
 *   <li>通过替换 {@code %1$s}（玩家名占位符）而非重写整个格式，
 *       从而保留聊天插件添加的前缀等信息</li>
 *   <li>最后对整个格式调用 {@code Msg.color}，把服务器或聊天插件
 *       遗留的裸 {@code &} 颜色代码一并转换，避免聊天栏显示字面 "&7" 之类的代码</li>
 *   <li>实验性聊天接管时，按实验性配置的模式生成显示名</li>
 * </ul>
 *
 * <p>注意：1.19+ 的 Paper 使用 AsyncChatEvent，但该类不在 1.8 的 API 中，
 * 无法直接引用。本类只监听 1.8-1.18 通用的 AsyncPlayerChatEvent；
 * 1.19+ 由 {@code ModernChatAdapter} 反射注册新事件处理。
 */
public class ChatListener implements Listener {

    private final NCNick plugin;

    public ChatListener(NCNick plugin) {
        this.plugin = plugin;
    }

    /**
     * 替换聊天格式中的玩家名，并转换格式里的颜色代码。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        try {
            String nickname = plugin.getNickManager().getActiveNickname(player);
            if (nickname == null || nickname.isEmpty()) {
                // 无昵称：不改动，保持原版
                return;
            }

            String displayName;
            if (plugin.getExperimentalManager().isChatHijacked()) {
                // 实验性模式：按配置决定显示模式
                displayName = plugin.getExperimentalManager()
                        .buildExpChatName(player.getName(), nickname);
            } else {
                // 常规模式：按 display.chat-format 生成
                displayName = plugin.getNickManager().buildChatName(player);
            }

            String colored = Msg.color(displayName);
            if (colored.isEmpty()) {
                return;
            }

            // 加粗处理
            if (plugin.getConfigManager().isBoldName()) {
                colored = "\u00A7l" + colored;
            }

            // 替换 %1$s 占位符（玩家名位置）
            String format = event.getFormat();
            String newFormat;
            if (format != null && format.contains("%1$s")) {
                newFormat = format.replace("%1$s", colored.replace("%", "%%"));
            } else if (format != null && format.contains("%s")) {
                newFormat = format.replaceFirst("%s", colored.replace("%", "%%"));
            } else {
                // 格式里没有占位符，退化为直接替换玩家名
                newFormat = colored + " \u00A7f%2$s";
            }

            // 关键：把**整个格式**的 & 颜色代码也转换掉。
            //
            // 原因：Bukkit 不会自动转换聊天格式里的 & 代码。
            // 服务器自身（spigot.yml）或聊天插件如果写了
            //   format: '&7[&aVIP&7] &f%1$s &7» &f%2$s'
            // 而不自己调用 translateAlternateColorCodes，
            // 玩家就会在聊天栏看到字面的 "&7" "&a"。
            //
            // 这一步是安全的：已经转换过的 § 代码不受影响，
            // 而不像颜色代码的裸 & （如 "Tom & Jerry"）也不会被误改
            // —— translateAlternateColorCodes 只处理合法的颜色字符。
            newFormat = Msg.color(newFormat);

            event.setFormat(newFormat);

            Debug.log(Debug.CACHE, "聊天格式已更新: {} -> {}",
                    player.getName(), newFormat);
        } catch (Throwable t) {
            // 聊天事件出错不能影响正常聊天
            Debug.error(Debug.GENERAL, "处理聊天显示失败: " + player.getName(), t);
        }
    }
}
