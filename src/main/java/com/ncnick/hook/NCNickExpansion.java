package com.ncnick.hook;

import com.ncnick.NCNick;
import com.ncnick.model.NickData;
import com.ncnick.util.Debug;
import com.ncnick.util.Msg;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI 变量扩展。
 *
 * <h3>为什么必须用真实子类</h3>
 * {@link PlaceholderExpansion} 是<b>抽象类</b>而非接口。
 * 早期版本曾尝试用 {@code java.lang.reflect.Proxy} 代理它，
 * 但 {@code Proxy.newProxyInstance} 只支持接口，实测直接抛出：
 * <pre>
 *   IllegalArgumentException:
 *   me.clip.placeholderapi.expansion.PlaceholderExpansion is not an interface
 * </pre>
 * 导致 {@code register()} 静默失败、所有 {@code %ncnick_*%} 变量失效。
 * 因此这里改为真实继承。
 *
 * <h3>为什么这个类是独立的</h3>
 * PlaceholderAPI 在 pom 中是 {@code provided} 作用域 —— 编译期可见、运行时不打包。
 * 若把对本类父类的引用直接写在 {@link PlaceholderHook} 里，
 * 那么未安装 PAPI 的服务器在加载 {@code PlaceholderHook} 时就会
 * 抛 {@code NoClassDefFoundError}。
 * 因此把 PAPI 引用全部隔离在本类中，
 * 由 {@link PlaceholderHook} 在确认 PAPI 存在后才反射加载本类。
 *
 * <h3>提供的变量</h3>
 * <table border="1">
 *   <tr><th>变量</th><th>含义</th></tr>
 *   <tr><td>{@code %ncnick_nickname%}</td><td>昵称（含颜色）</td></tr>
 *   <tr><td>{@code %ncnick_raw%}</td><td>昵称（纯文本）</td></tr>
 *   <tr><td>{@code %ncnick_name%}</td><td>真实 ID</td></tr>
 *   <tr><td>{@code %ncnick_display%}</td><td>完整显示名</td></tr>
 *   <tr><td>{@code %ncnick_color%}</td><td>颜色十六进制</td></tr>
 *   <tr><td>{@code %ncnick_hasnick%}</td><td>是否已设置（true/false）</td></tr>
 *   <tr><td>{@code %ncnick_prefix%}</td><td>自定义占位符字符串</td></tr>
 * </table>
 */
public class NCNickExpansion extends PlaceholderExpansion {

    /** 扩展标识符 —— 决定变量前缀，即 {@code %ncnick_xxx%} */
    private static final String IDENTIFIER = "ncnick";

    private final NCNick plugin;

    public NCNickExpansion(NCNick plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public String getAuthor() {
        return "xiaotian_xt, Nice_Leo_";
    }

    @Override
    public String getVersion() {
        try {
            return plugin.getDescription().getVersion();
        } catch (Throwable t) {
            return "2.0.0";
        }
    }

    /**
     * 插件卸载/重载时保留注册状态。
     *
     * <p>返回 {@code true} 可避免 PAPI 在本插件重载时反复注销/注册，
     * 也让变量在 {@code /nca reload} 后立即继续可用。
     */
    @Override
    public boolean persist() {
        return true;
    }

    /**
     * 解析变量请求。
     *
     * <p>PAPI 传入的 {@code params} 已去掉 {@code ncnick_} 前缀
     * （例如 {@code %ncnick_nickname%} → {@code params = "nickname"}）。
     *
     * @param player 目标玩家（可能是离线玩家）
     * @param params 变量名（小写形式由本方法自行处理）
     * @return 变量值；返回 {@code null} 表示未处理该变量
     */
    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || params == null) {
            return null;
        }

        final String key = params.toLowerCase();
        try {
            // 与玩家无关的变量
            if ("prefix".equals(key)) {
                return plugin.getConfigManager().getCustomPrefix();
            }

            // 真实 ID：离线也能拿到
            if ("name".equals(key)) {
                String name = player.getName();
                return name == null ? "" : name;
            }

            // 以下都需要昵称数据
            NickData data = plugin.getNickManager().get(player.getUniqueId());
            boolean active = isActiveFor(player, data);

            switch (key) {
                case "nickname":
                    // 昵称（含颜色）
                    return active ? data.getColoredNickname() : "";

                case "raw":
                case "rawnickname":
                    // 昵称（纯文本，无颜色）
                    return active ? data.getNickname() : "";

                case "display":
                case "fullname":
                    // 完整显示名（如 Steve#小明）
                    if (!active) {
                        String n = player.getName();
                        return n == null ? "" : n;
                    }
                    Player online = Bukkit.getPlayer(player.getUniqueId());
                    if (online != null && online.isOnline()) {
                        return Msg.color(plugin.getNickManager().buildChatName(online));
                    }
                    // 离线玩家：用配置模板手工拼
                    return Msg.color(Msg.fill(
                            plugin.getConfigManager().getChatFormat(),
                            buildVars(player.getName(), data.getColoredNickname())));

                case "color":
                    return (active && data.hasColor())
                            ? "#" + data.getColor().getFrom() : "";

                case "hasnick":
                case "has":
                    return String.valueOf(active);

                default:
                    // 未识别的变量：返回 null 让 PAPI 走其他扩展
                    return null;
            }
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "解析 PAPI 变量失败: " + params, t);
            return "";
        }
    }

    /**
     * 判断昵称对该玩家是否生效。
     *
     * <p>在线玩家走完整判定（含权限检查）；离线玩家只判断数据本身是否可用，
     * 避免在异步/离线场景调用 Bukkit 的权限 API。
     */
    private boolean isActiveFor(OfflinePlayer player, NickData data) {
        if (data == null || !data.hasNickname()) {
            return false;
        }
        Player online = player.isOnline() && player instanceof Player
                ? (Player) player : null;
        if (online != null) {
            return plugin.getNickManager().isActive(online, data);
        }
        // 离线：只判断数据是否有效（不查权限）
        return true;
    }

    /** 构造填充变量表。 */
    private static java.util.Map<String, String> buildVars(String name, String nickname) {
        java.util.Map<String, String> m = new java.util.HashMap<String, String>();
        m.put("name", name == null ? "" : name);
        m.put("nickname", nickname == null ? "" : nickname);
        return m;
    }
}
