package com.ncnick.hook;

import com.ncnick.NCNick;
import com.ncnick.model.NickData;
import com.ncnick.util.Debug;
import com.ncnick.util.Msg;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
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
                    // 昵称（含颜色，**带占位符前缀**，如 "#小明"）。
                    //
                    // 【为什么带前缀】昵称是纯文字，若不与正文区分开，
                    // 读者无法判断"小明"是人名还是普通词语。
                    // 前缀（默认 #）就是为此存在的。
                    //
                    // 【无昵称时回退真实 ID】空串会让聊天里的名字位置变空白
                    // （"<  > 你好"），因此无昵称时返回真实 ID。
                    //
                    // 需要**不带前缀**的纯昵称，请用 %ncnick_nickname_plain%
                    // 需要**严格空**语义，请用 %ncnick_nickname_empty%
                    return active
                            ? Msg.color(plugin.getConfigManager().getCustomPrefix()
                                    + data.getColoredNickname())
                            : safeName(player);

                case "nickname_plain":
                case "nickname-plain":
                    // 纯昵称，不带占位符前缀（如 "小明"）
                    return active ? data.getColoredNickname() : safeName(player);

                case "nickname_empty":
                case "nickname-strict":
                    // 严格语义：无昵称时返回空串（供需要判断的场景使用）
                    return active ? data.getColoredNickname() : "";

                case "raw":
                case "rawnickname":
                    // 昵称纯文本（无颜色），同样带前缀
                    return active
                            ? plugin.getConfigManager().getCustomPrefix()
                                    + Msg.stripColor(data.getNickname())
                            : safeName(player);

                case "raw_plain":
                case "raw-plain":
                    // 纯文本、无前缀
                    return active ? Msg.stripColor(data.getNickname()) : safeName(player);

                case "raw_empty":
                case "raw-strict":
                    return active ? Msg.stripColor(data.getNickname()) : "";

                case "display":
                    // 昵称部分（前缀 + 昵称），**不含真实 ID**。
                    //
                    // 【为什么不含真实 ID】display 的用途是"替换名字槽"，
                    // 而名字槽（TAB、聊天 <%1$s>、计分板）本身已在别处
                    // 提供真实 ID。若这里再拼一次，就会出现两个名字：
                    //     Steve Steve#小明
                    // 因此 display 只输出 "#小明"，放哪儿都只有一个名字。
                    //
                    // 需要"真实ID+昵称"请看 %ncnick_fullname%
                    return active
                            ? Msg.color(plugin.getConfigManager().getCustomPrefix()
                                    + data.getColoredNickname())
                            : safeName(player);

                case "fullname":
                case "full_display":
                    // 完整显示名：真实 ID + 前缀 + 昵称（如 Steve#小明）
                    //
                    // ⚠️ 只在**不会重复真实 ID** 的位置使用，
                    //    例如独立的信息牌，而不是聊天/TAB 的名字槽。
                    return active
                            ? Msg.color(player.getName()
                                    + plugin.getConfigManager().getCustomPrefix()
                                    + data.getColoredNickname())
                            : safeName(player);

                case "display_empty":
                case "display-strict":
                case "nickname_prefix_empty":
                    // 前缀 + 昵称；**无昵称时返回空串**。
                    //
                    //   有昵称 → "#小明"
                    //   无昵称 → ""
                    //
                    // 与 %ncnick_display% 的唯一区别是无昵称时的行为：
                    // display 回退真实 ID（名字槽不能空），
                    // display_empty 返回空（用于"没昵称就整行不显示"）。
                    //
                    // 与 %ncnick_nickname_empty% 的区别：那个**不带**前缀
                    // 返回 "小明"，这个带前缀返回 "#小明"。
                    return active
                            ? Msg.color(plugin.getConfigManager().getCustomPrefix()
                                    + data.getColoredNickname())
                            : "";

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

    /**
     * 取玩家真实 ID，任何情况下都不返回 null。
     *
     * <p>占位符回退用：聊天、计分板等场景下"空字符串"通常不是想要的结果。
     */
    private static String safeName(OfflinePlayer player) {
        if (player == null) {
            return "";
        }
        String name = player.getName();
        return name == null ? "" : name;
    }
}
