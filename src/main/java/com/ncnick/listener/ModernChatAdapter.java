package com.ncnick.listener;

import com.ncnick.NCNick;
import com.ncnick.util.Debug;
import com.ncnick.util.Msg;
import java.lang.reflect.Method;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;

/**
 * 1.19+ Paper 聊天事件适配器。
 *
 * <h3>为什么需要这个类</h3>
 * 1.19 起 Paper 改用 {@code io.papermc.paper.event.player.AsyncChatEvent}，
 * 旧的 {@code AsyncPlayerChatEvent} 在很多 1.19+ 服务端上不再派发，
 * 导致聊天栏昵称失效。
 *
 * <h3>为什么用反射</h3>
 * 本插件编译目标是 1.8.8 API，该 API 中不存在 AsyncChatEvent 类，
 * 无法直接引用（会编译失败 / 在旧服务端抛 NoClassDefFoundError）。
 * 因此全部通过反射访问，只在检测到 1.19+ 时才注册。
 *
 * <h3>实现方式</h3>
 * 1.19+ 的聊天渲染通过 {@code ChatRenderer} 完成。我们给事件
 * 设置一个自定义 renderer，在渲染时把玩家名替换为昵称，
 * 从而保留其他插件（前缀、后缀等）的改动，避免冲突。
 */
public final class ModernChatAdapter {

    private final NCNick plugin;
    private boolean registered = false;

    public ModernChatAdapter(NCNick plugin) {
        this.plugin = plugin;
    }

    /** 是否已成功注册。 */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * 尝试注册 1.19+ 聊天监听器。
     *
     * @return 是否注册成功
     */
    public boolean register() {
        registered = false;

        Class<?> eventClass;
        try {
            eventClass = Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
        } catch (Throwable t) {
            // 非 Paper 1.19+，属正常情况
            Debug.log(Debug.GENERAL, "未找到 AsyncChatEvent，跳过 1.19+ 聊天适配");
            return false;
        }

        try {
            // 用 registerEvent 手工注册，避免引用编译期不存在的类
            EventExecutor executor = new EventExecutor() {
                @Override
                public void execute(org.bukkit.event.Listener listener, Event event) {
                    handle(event);
                }
            };

            org.bukkit.event.Listener dummy = new org.bukkit.event.Listener() {
                // 仅作为事件持有者
            };

            org.bukkit.plugin.PluginManager pm = plugin.getServer().getPluginManager();
            pm.registerEvent(
                    (Class<? extends Event>) eventClass,
                    dummy,
                    EventPriority.LOWEST,
                    executor,
                    plugin,
                    true);

            registered = true;
            // 只在调试模式输出。正常启动时不必告知"用了哪个事件类"，
            // 聊天昵称能正常显示就说明适配成功。
            Debug.log(Debug.GENERAL, "已适配 1.19+ 聊天事件（AsyncChatEvent）");
            return true;
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "注册 1.19+ 聊天监听失败", t);
            return false;
        }
    }

    /**
     * 处理聊天事件：为事件设置自定义渲染器以替换玩家名。
     *
     * <p>全程反射，任何一步失败都静默回退，绝不影响正常聊天。
     */
    private void handle(Event event) {
        try {
            // 取发信玩家：AsyncChatEvent#getPlayer()
            Method getPlayer = event.getClass().getMethod("getPlayer");
            Object playerObj = getPlayer.invoke(event);
            if (!(playerObj instanceof Player)) {
                return;
            }
            Player player = (Player) playerObj;

            String nickname = plugin.getNickManager().getActiveNickname(player);
            if (nickname == null || nickname.isEmpty()) {
                // 无昵称：不干预
                return;
            }

            String displayName;
            if (plugin.getExperimentalManager().isChatHijacked()) {
                displayName = plugin.getExperimentalManager()
                        .buildExpChatName(player.getName(), nickname);
            } else {
                displayName = plugin.getNickManager().buildChatName(player);
            }
            if (displayName == null || displayName.isEmpty()) {
                return;
            }

            final String colored = Msg.color(displayName);

            // 构造 ChatRenderer：把渲染结果中的玩家名替换为昵称
            if (!applyRenderer(event, player, colored)) {
                Debug.log(Debug.PACKET, "无法为聊天事件设置渲染器，已跳过替换");
            }
        } catch (Throwable t) {
            // 聊天路径绝不能抛异常
            Debug.error(Debug.GENERAL, "处理 1.19+ 聊天事件失败", t);
        }
    }

    /**
     * 用反射构造并设置 ChatRenderer。
     *
     * <p><b>关键 API 事实</b>（已对照 paper-api 1.21.11 用 javap 核实）：
     * <ul>
     *   <li>设置渲染器的方法名是 {@code renderer(ChatRenderer)}，
     *       遵循 Adventure 命名约定，<b>不是</b> {@code setRenderer}。</li>
     *   <li>{@code ChatRenderer.render} 的签名是
     *       {@code Component render(Player, Component, Component, Audience)}，
     *       共 <b>4</b> 个参数（第 4 个是受众）。
     *       参数位置：0=发言玩家，1=原始显示名，2=消息内容，3=受众。</li>
     *   <li>返回值必须是<b>完整的聊天行</b>，只返回显示名会导致消息内容丢失。</li>
     * </ul>
     *
     * @return 是否设置成功
     */
    private boolean applyRenderer(Event event, Player player, String coloredName) {
        try {
            // ---- 1. 找到设置渲染器的方法 ----
            // 优先 Adventure 风格的 renderer(...)，兼容可能存在的 setRenderer(...)
            Method setRenderer = findRendererSetter(event.getClass());
            if (setRenderer == null) {
                Debug.log(Debug.PACKET,
                        "未找到 renderer 设置方法，跳过 1.19+ 聊天替换");
                return false;
            }

            Class<?> rendererClass = setRenderer.getParameterTypes()[0];
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");

            // 用 legacy 方式把 & 颜色串转成 Component
            final Object nameComponent = legacyToComponent(coloredName, componentClass);
            if (nameComponent == null) {
                return false;
            }

            // 空格分隔组件（昵称与消息之间）
            final Object spaceComponent = legacyToComponent(" ", componentClass);

            // 反射拿到 Component.append(Component)，用于拼接完整聊天行
            final Method appendMethod = findAppendMethod(componentClass, componentClass);

            // 用动态代理实现 ChatRenderer 接口
            Object renderer = java.lang.reflect.Proxy.newProxyInstance(
                    rendererClass.getClassLoader(),
                    new Class<?>[]{rendererClass},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();

                            // Paper 的实际签名是 4 参：
                            //   render(Player source, Component sourceDisplayName,
                            //          Component message, Audience viewer)
                            // 因此要求 >= 4，避免参数错位。
                            if ("render".equals(name) && args != null && args.length >= 4) {
                                Object message = args[2];
                                try {
                                    if (appendMethod != null && message != null) {
                                        // 昵称 + 空格 + 消息
                                        Object line = appendMethod.invoke(
                                                nameComponent, spaceComponent);
                                        if (line != null) {
                                            Object full = appendMethod.invoke(line, message);
                                            if (full != null) {
                                                return full;
                                            }
                                        }
                                        return line;
                                    }
                                } catch (Throwable t) {
                                    Debug.error(Debug.GENERAL, "拼接聊天行失败", t);
                                }
                                // 拼接失败时保守返回原始消息，绝不丢失玩家发言
                                return message != null ? message : nameComponent;
                            }

                            if ("toString".equals(name)) {
                                return "NCNickChatRenderer";
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(name)) {
                                return args != null && args.length == 1 && args[0] == proxy;
                            }
                            return null;
                        }
                    });

            setRenderer.invoke(event, renderer);
            Debug.log(Debug.PACKET, "已为 {} 设置聊天渲染器（方法: {}）",
                    player.getName(), setRenderer.getName());
            return true;
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "构造聊天渲染器失败", t);
            return false;
        }
    }

    /**
     * 查找设置聊天渲染器的方法。
     *
     * <p>Paper 的 {@code AbstractChatEvent} 使用 {@code renderer(ChatRenderer)}
     * 作为 setter（Adventure 命名约定）。为兼容性也尝试 {@code setRenderer}。
     *
     * @param eventClass 事件类
     * @return 匹配的方法，找不到返回 null
     */
    private static Method findRendererSetter(Class<?> eventClass) {
        Class<?> chatRendererClass = null;
        try {
            chatRendererClass = Class.forName("io.papermc.paper.chat.ChatRenderer");
        } catch (Throwable ignored) {
            // 类不存在时退化为按名称+参数个数匹配
        }

        Method fallback = null;
        for (Method m : eventClass.getMethods()) {
            String n = m.getName();
            if (m.getParameterCount() != 1) {
                continue;
            }
            boolean nameOk = "renderer".equals(n) || "setRenderer".equals(n);
            if (!nameOk) {
                continue;
            }
            // 参数类型必须是 ChatRenderer
            if (chatRendererClass != null
                    && !m.getParameterTypes()[0].equals(chatRendererClass)) {
                continue;
            }
            // 首选 renderer(...)
            if ("renderer".equals(n)) {
                return m;
            }
            if (fallback == null) {
                fallback = m;
            }
        }
        return fallback;
    }

    /**
     * 查找 {@code Component.append(Component)} 方法。
     *
     * <p>Adventure 提供 3 个单参数重载：{@code append(Component)}、
     * {@code append(ComponentLike)}、{@code append(ComponentBuilder)}。
     * 由于 {@code Component} 同时是 {@code ComponentLike} 的子类型，
     * 朴素的 "isAssignableFrom" 判断会同时匹配前两个，
     * 结果取决于 JDK 返回 {@code getMethods()} 的顺序（不可依赖）。
     *
     * <p>因此这里做**确定性选择**：
     * <ol>
     *   <li>优先参数类型恰好等于 {@code Component} 的重载</li>
     *   <li>否则退而选择参数类型的父接口（如 {@code ComponentLike}）</li>
     *   <li>显式排除 {@code ComponentBuilder}（语义不同，会消费 builder）</li>
     * </ol>
     *
     * @param componentClass 声明方法的类
     * @param wantParam      期望的参数类型（通常为 Component）
     * @return 匹配的方法；找不到返回 null
     */
    private static Method findAppendMethod(Class<?> componentClass, Class<?> wantParam) {
        Method exact = null;
        Method loose = null;
        for (Method m : componentClass.getMethods()) {
            if (!"append".equals(m.getName()) || m.getParameterCount() != 1) {
                continue;
            }
            Class<?> p = m.getParameterTypes()[0];

            // 精确匹配：参数类型就是 Component
            if (p.equals(wantParam)) {
                exact = m;
                break;
            }
            // 宽松匹配：参数是 Component 的父接口（如 ComponentLike）
            // 排除 ComponentBuilder（它不接收 Component）
            if (loose == null && p.isAssignableFrom(wantParam)
                    && !"ComponentBuilder".equals(p.getSimpleName())) {
                loose = m;
            }
        }
        return exact != null ? exact : loose;
    }

    /**
     * 把 legacy 颜色串转成 Adventure Component。
     *
     * <p><b>两个必须注意的实测要点</b>：
     * <ol>
     *   <li><b>必须在公开接口上取方法</b>：{@code legacySection()} 返回的实现类
     *       {@code LegacyComponentSerializerImpl} 是<b>包级私有</b>的。
     *       若用 {@code serializer.getClass().getMethod(...)} 取方法，
     *       解析到的声明类非 public，{@code invoke} 会抛
     *       {@code IllegalAccessException}。必须从
     *       {@code LegacyComponentSerializer} 接口上取。</li>
     *   <li><b>必须用 legacySection() 而非 legacyAmpersand()</b>：
     *       {@link Msg#color(String)} 产出的是 {@code §} 码，
     *       而 {@code legacyAmpersand()} 只认 {@code &}，喂给它 {@code §}
     *       会原样保留、丢失颜色。</li>
     * </ol>
     *
     * @param legacyText 已由 {@link Msg#color(String)} 转换过的文本
     * @param componentClass Component 类（用于兜底）
     * @return Component 实例；全部失败返回 null
     */
    private Object legacyToComponent(String legacyText, Class<?> componentClass) {
        if (legacyText == null) {
            legacyText = "";
        }
        try {
            Class<?> serializerClass = Class.forName(
                    "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");

            // 从**公开接口**取方法，避免 IllegalAccessException
            Method deserialize = serializerClass.getMethod("deserialize", String.class);

            // Msg.color() 产出 § 码，因此用 legacySection()
            Method factory = serializerClass.getMethod("legacySection");
            Object serializer = factory.invoke(null);
            if (serializer != null) {
                Object result = deserialize.invoke(serializer, legacyText);
                if (result != null) {
                    return result;
                }
            }
        } catch (Throwable t) {
            Debug.log(Debug.PACKET, "legacySection 序列化失败，尝试兜底: {}", t.getMessage());
        }

        // 兜底 1：文本本身就含 & 码时用 legacyAmpersand()
        if (legacyText.indexOf('&') >= 0 && legacyText.indexOf('\u00A7') < 0) {
            try {
                Class<?> serializerClass = Class.forName(
                        "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
                Method deserialize = serializerClass.getMethod("deserialize", String.class);
                Object serializer = serializerClass.getMethod("legacyAmpersand").invoke(null);
                if (serializer != null) {
                    Object result = deserialize.invoke(serializer, legacyText);
                    if (result != null) {
                        return result;
                    }
                }
            } catch (Throwable ignored) {
                // 继续兜底
            }
        }

        // 兜底 2：Component.text(String) —— 会丢颜色，但至少能显示文字
        try {
            Method text = componentClass.getMethod("text", String.class);
            return text.invoke(null, Msg.stripColor(legacyText));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
