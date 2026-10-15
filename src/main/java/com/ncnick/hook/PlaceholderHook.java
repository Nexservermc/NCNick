package com.ncnick.hook;

import com.ncnick.NCNick;
import com.ncnick.util.Debug;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;

/**
 * PlaceholderAPI 接入点。
 *
 * <h3>设计要点：为什么全部用反射</h3>
 * PlaceholderAPI 在 pom 中是 {@code provided} 作用域（编译期可见、运行时不打包），
 * 属于**软依赖**。本类刻意<b>不出现任何 PAPI 类型</b>：
 * <ul>
 *   <li>不继承、不实现 PAPI 的类</li>
 *   <li>不把 PAPI 类型写进字段或方法签名</li>
 *   <li>真正的实现在 {@link NCNickExpansion} 中，只在确认 PAPI 存在后才加载</li>
 * </ul>
 * 这样即使服务器**没装 PAPI**，本类也能正常加载，插件不会因
 * {@code NoClassDefFoundError} 而崩溃。
 *
 * <h3>历史缺陷</h3>
 * 早期版本用 {@code java.lang.reflect.Proxy} 动态代理 {@code PlaceholderExpansion}。
 * 但该类是<b>抽象类</b>而非接口，Proxy 只支持接口，实测直接抛：
 * <pre>
 *   IllegalArgumentException:
 *   me.clip.placeholderapi.expansion.PlaceholderExpansion is not an interface
 * </pre>
 * 结果是注册静默失败、所有 {@code %ncnick_*%} 变量失效。
 * 现已改为真实子类（见 {@link NCNickExpansion}）。
 */
public class PlaceholderHook {

    /** 扩展实现类的全限定名（含 PAPI 引用，需延迟加载） */
    private static final String EXPANSION_CLASS = "com.ncnick.hook.NCNickExpansion";

    private final NCNick plugin;
    /** 已注册的扩展实例（保持强引用，防止被 GC） */
    private Object expansion;
    private boolean registered = false;

    public PlaceholderHook(NCNick plugin) {
        this.plugin = plugin;
    }

    /**
     * 尝试注册扩展。
     *
     * @return 是否注册成功
     */
    public boolean register() {
        registered = false;
        expansion = null;

        // ---- 1. 检查 PAPI 是否安装 ----
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            Debug.log(Debug.GENERAL, "未检测到 PlaceholderAPI，跳过变量注册");
            return false;
        }

        // ---- 2. 延迟加载实现类 ----
        // 放在 try 里：若 PAPI 存在但版本不兼容，也只是注册失败而非崩溃
        final Class<?> expansionClass;
        try {
            expansionClass = Class.forName(EXPANSION_CLASS);
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "无法加载 PAPI 扩展类（可能 placeholderexpansion API 不兼容）", t);
            return false;
        }

        // ---- 3. 实例化 ----
        try {
            expansion = expansionClass
                    .getConstructor(NCNick.class)
                    .newInstance(plugin);
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "创建 PAPI 扩展实例失败", t);
            return false;
        }

        // ---- 4. 注册 ----
        try {
            Method register = expansionClass.getMethod("register");
            Object result = register.invoke(expansion);
            registered = result instanceof Boolean && (Boolean) result;

            if (registered) {
                // 只在调试模式下输出注册详情。
                // 正常情况下启动摘要里已能看出插件状态，
                // 逐个罗列变量名对服主没有价值，只会让控制台变吵。
                Debug.log(Debug.GENERAL,
                        "已注册 PlaceholderAPI 变量: %ncnick_nickname% / %ncnick_raw% / "
                                + "%ncnick_name% / %ncnick_display% / %ncnick_color% / "
                                + "%ncnick_hasnick% / %ncnick_prefix%");
            } else {
                plugin.getLogger().warning(
                        "PlaceholderAPI 扩展注册被拒绝（canRegister 返回 false），"
                                + "请检查 PAPI 版本是否为 2.10+");
            }
            return registered;
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "注册 PAPI 扩展失败", t);
            return false;
        }
    }

    /**
     * 注销扩展（插件卸载时调用）。
     */
    public void unregister() {
        if (expansion == null) {
            return;
        }
        try {
            Method unregister = expansion.getClass().getMethod("unregister");
            unregister.invoke(expansion);
        } catch (Throwable ignored) {
            // 忽略注销异常
        }
        expansion = null;
        registered = false;
    }

    /** 是否已成功注册。 */
    public boolean isRegistered() {
        return registered;
    }
}
