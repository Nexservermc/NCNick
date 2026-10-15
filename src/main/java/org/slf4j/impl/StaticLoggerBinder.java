package org.slf4j.impl;

import java.util.logging.Level;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.spi.LoggerFactoryBinder;

/**
 * 把 SLF4J 日志转发到 {@code java.util.logging} 的绑定。
 *
 * <h3>为什么需要这个类</h3>
 * 本插件打包了 HikariCP，而 HikariCP 依赖 SLF4J。
 * 若类路径上只有 {@code slf4j-api} 而没有**绑定实现**，
 * SLF4J 在初始化时会往 stderr 打印两行：
 * <pre>
 *   SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
 *   SLF4J: Defaulting to no-operation (NOP) logger implementation
 * </pre>
 * Bukkit 会把这两行以 ERROR 级别归到插件名下，看起来像故障 ——
 * 服主实测反馈就是这个现象。它不影响功能，但会误导排查。
 *
 * <h3>为什么不用 slf4j-simple</h3>
 * {@code slf4j-simple} 直接写 stderr，会把驱动日志变成无归属的裸输出；
 * 而转发到 JUL 能让日志进入服务端日志系统，
 * **驱动内部的告警反而变得可见**（例如连接被拒、参数不识别），这更有价值。
 *
 * <h3>与 shade 重定位的配合</h3>
 * pom 中配置了 {@code org.slf4j → com.ncnick.libs.slf4j} 的重定位，
 * 本类会被一并搬到 {@code com.ncnick.libs.slf4j.impl}，
 * 从而与同样被搬过去的 slf4j-api 配套，避免与其它插件/服务端的
 * SLF4J 版本互相干扰。
 */
public class StaticLoggerBinder implements LoggerFactoryBinder {

    /**
     * SLF4J 会读这个常量来校验 api 与 binding 的版本是否匹配。
     * 值需与 pom 中的 slf4j-api 版本一致。
     */
    public static final String REQUESTED_API_VERSION = "1.7.36";

    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

    private static final String LOGGER_FACTORY_CLASS_STR =
            Jdk14LoggerFactory.class.getName();

    private final ILoggerFactory loggerFactory = new Jdk14LoggerFactory();

    private StaticLoggerBinder() {
    }

    /** SLF4J 通过它拿到单例。 */
    public static StaticLoggerBinder getSingleton() {
        return SINGLETON;
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public String getLoggerFactoryClassStr() {
        return LOGGER_FACTORY_CLASS_STR;
    }

    // ==========================================================
    //  工厂
    // ==========================================================

    /** 返回转发到 JUL 的 Logger。 */
    public static class Jdk14LoggerFactory implements ILoggerFactory {
        @Override
        public Logger getLogger(String name) {
            return new Jdk14Logger(name);
        }
    }

    // ==========================================================
    //  Logger
    // ==========================================================

    /**
     * 把各等级日志映射到 {@link java.util.logging.Level}。
     *
     * <p>继承 {@link MarkerIgnoringBase} 以获得全部的 Marker 重载实现，
     * 这里只需实现不带 Marker 的那一组。
     */
    public static class Jdk14Logger extends MarkerIgnoringBase {

        private static final long serialVersionUID = 1L;

        /** 统一挂到 NCNick 名下，便于在日志里区分来源 */
        private static final String JUL_LOGGER_NAME = "NCNick";

        private final transient java.util.logging.Logger jul;

        Jdk14Logger(String name) {
            this.name = name;
            this.jul = java.util.logging.Logger.getLogger(JUL_LOGGER_NAME);
        }

        private void log(Level level, String message, Throwable t) {
            try {
                jul.log(level, "[" + name + "] " + message, t);
            } catch (Throwable ignored) {
                // 日志本身不能抛异常影响主流程
            }
        }

        /** 用 SLF4J 的 {} 语法格式化。 */
        private void log(Level level, String format, Object arg1, Object arg2) {
            if (format == null) {
                log(level, "null", null);
                return;
            }
            FormattingTuple tuple = MessageFormatter.format(format, arg1, arg2);
            log(level, tuple.getMessage(), tuple.getThrowable());
        }

        private void logVarargs(Level level, String format, Object... args) {
            if (format == null) {
                log(level, "null", null);
                return;
            }
            FormattingTuple tuple = MessageFormatter.arrayFormat(format, args);
            log(level, tuple.getMessage(), tuple.getThrowable());
        }

        // ---- TRACE：JUL 没有 TRACE，降到 FINEST ----
        @Override
        public boolean isTraceEnabled() {
            return jul.isLoggable(Level.FINEST);
        }

        @Override
        public void trace(String msg) {
            if (isTraceEnabled()) log(Level.FINEST, msg, null);
        }

        @Override
        public void trace(String format, Object arg) {
            if (isTraceEnabled()) log(Level.FINEST, format, arg, null);
        }

        @Override
        public void trace(String format, Object arg1, Object arg2) {
            if (isTraceEnabled()) log(Level.FINEST, format, arg1, arg2);
        }

        @Override
        public void trace(String format, Object... arguments) {
            if (isTraceEnabled()) logVarargs(Level.FINEST, format, arguments);
        }

        @Override
        public void trace(String msg, Throwable t) {
            if (isTraceEnabled()) log(Level.FINEST, msg, t);
        }

        // ---- DEBUG：映射到 FINE ----
        @Override
        public boolean isDebugEnabled() {
            return jul.isLoggable(Level.FINE);
        }

        @Override
        public void debug(String msg) {
            if (isDebugEnabled()) log(Level.FINE, msg, null);
        }

        @Override
        public void debug(String format, Object arg) {
            if (isDebugEnabled()) log(Level.FINE, format, arg, null);
        }

        @Override
        public void debug(String format, Object arg1, Object arg2) {
            if (isDebugEnabled()) log(Level.FINE, format, arg1, arg2);
        }

        @Override
        public void debug(String format, Object... arguments) {
            if (isDebugEnabled()) logVarargs(Level.FINE, format, arguments);
        }

        @Override
        public void debug(String msg, Throwable t) {
            if (isDebugEnabled()) log(Level.FINE, msg, t);
        }

        // ---- INFO ----
        @Override
        public boolean isInfoEnabled() {
            return jul.isLoggable(Level.INFO);
        }

        @Override
        public void info(String msg) {
            if (isInfoEnabled()) log(Level.INFO, msg, null);
        }

        @Override
        public void info(String format, Object arg) {
            if (isInfoEnabled()) log(Level.INFO, format, arg, null);
        }

        @Override
        public void info(String format, Object arg1, Object arg2) {
            if (isInfoEnabled()) log(Level.INFO, format, arg1, arg2);
        }

        @Override
        public void info(String format, Object... arguments) {
            if (isInfoEnabled()) logVarargs(Level.INFO, format, arguments);
        }

        @Override
        public void info(String msg, Throwable t) {
            if (isInfoEnabled()) log(Level.INFO, msg, t);
        }

        // ---- WARN ----
        @Override
        public boolean isWarnEnabled() {
            return jul.isLoggable(Level.WARNING);
        }

        @Override
        public void warn(String msg) {
            if (isWarnEnabled()) log(Level.WARNING, msg, null);
        }

        @Override
        public void warn(String format, Object arg) {
            if (isWarnEnabled()) log(Level.WARNING, format, arg, null);
        }

        @Override
        public void warn(String format, Object arg1, Object arg2) {
            if (isWarnEnabled()) log(Level.WARNING, format, arg1, arg2);
        }

        @Override
        public void warn(String format, Object... arguments) {
            if (isWarnEnabled()) logVarargs(Level.WARNING, format, arguments);
        }

        @Override
        public void warn(String msg, Throwable t) {
            if (isWarnEnabled()) log(Level.WARNING, msg, t);
        }

        // ---- ERROR ----
        @Override
        public boolean isErrorEnabled() {
            return jul.isLoggable(Level.SEVERE);
        }

        @Override
        public void error(String msg) {
            if (isErrorEnabled()) log(Level.SEVERE, msg, null);
        }

        @Override
        public void error(String format, Object arg) {
            if (isErrorEnabled()) log(Level.SEVERE, format, arg, null);
        }

        @Override
        public void error(String format, Object arg1, Object arg2) {
            if (isErrorEnabled()) log(Level.SEVERE, format, arg1, arg2);
        }

        @Override
        public void error(String format, Object... arguments) {
            if (isErrorEnabled()) logVarargs(Level.SEVERE, format, arguments);
        }

        @Override
        public void error(String msg, Throwable t) {
            if (isErrorEnabled()) log(Level.SEVERE, msg, t);
        }
    }
}
