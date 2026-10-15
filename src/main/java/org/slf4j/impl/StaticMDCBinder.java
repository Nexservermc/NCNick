package org.slf4j.impl;

import java.util.Map;
import org.slf4j.spi.MDCAdapter;

/**
 * SLF4J 的 MDC 绑定（空实现）。
 *
 * <p>与 {@link StaticLoggerBinder} 配套：SLF4J 初始化时若找不到
 * {@code org.slf4j.impl.StaticMDCBinder}，在使用 MDC 相关 API 时会再报一次
 * 类似的提示。本插件（以及 HikariCP）并不使用 MDC，
 * 因此提供一个空实现把这条路彻底封死即可。
 *
 * <p>同样会被 shade 一并重定位到 {@code com.ncnick.libs.slf4j.impl}。
 */
public class StaticMDCBinder {

    /** SLF4J 通过它拿到单例。 */
    public static final StaticMDCBinder SINGLETON = new StaticMDCBinder();

    private StaticMDCBinder() {
    }

    /** 兼容旧版 SLF4J 的实例方法形式。 */
    public static StaticMDCBinder getSingleton() {
        return SINGLETON;
    }

    /** 返回空实现的 MDC 适配器。 */
    public MDCAdapter getMDCA() {
        return new NopMDCAdapter();
    }

    /** 返回适配器的类名（供日志输出）。 */
    public String getMDCAdapterClassStr() {
        return NopMDCAdapter.class.getName();
    }

    /**
     * 空实现的 MDC 适配器。
     *
     * <p>所有操作都是无副作用的：写入丢弃、读取返回 null。
     * 这样即便第三方库调用了 MDC，也不会抛异常或泄漏状态。
     */
    public static class NopMDCAdapter implements MDCAdapter {

        @Override
        public void put(String key, String val) {
            // 丢弃
        }

        @Override
        public String get(String key) {
            return null;
        }

        @Override
        public void remove(String key) {
            // 丢弃
        }

        @Override
        public void clear() {
            // 丢弃
        }

        @Override
        public Map<String, String> getCopyOfContextMap() {
            return null;
        }

        @Override
        public void setContextMap(Map<String, String> contextMap) {
            // 丢弃
        }
    }
}
