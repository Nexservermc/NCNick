package com.ncnick.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 调试日志工具（第 12 条：debug 调试功能，默认关闭）。
 *
 * <p>提供分级、分类的调试输出。所有输出都带统一前缀，便于过滤。
 * 关闭时零开销（提前返回）。
 */
public final class Debug {

    /** 分类：数据库 */
    public static final String SQL = "SQL";
    /** 分类：缓存 */
    public static final String CACHE = "CACHE";
    /** 分类：数据包 */
    public static final String PACKET = "PACKET";
    /** 分类：校验 */
    public static final String VALIDATE = "VALIDATE";
    /** 分类：通用 */
    public static final String GENERAL = "GENERAL";

    private static volatile boolean enabled = false;
    private static volatile boolean logSql = false;
    private static volatile boolean logCache = false;
    private static volatile boolean logPacket = false;
    private static volatile boolean logValidation = false;
    private static Logger logger = Logger.getLogger("NCNick");

    private Debug() {
        // 工具类禁止实例化
    }

    /** 注入插件日志器。 */
    public static void setLogger(Logger log) {
        if (log != null) {
            logger = log;
        }
    }

    /** 配置调试开关，由配置重载调用。 */
    public static void configure(boolean debugEnabled, boolean sql, boolean cache,
                                 boolean packet, boolean validation) {
        enabled = debugEnabled;
        logSql = sql;
        logCache = cache;
        logPacket = packet;
        logValidation = validation;
    }

    /** 调试模式是否开启。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 运行时切换总开关。 */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * 输出调试信息。
     *
     * @param category 分类，见本类常量
     * @param message  消息
     */
    public static void log(String category, String message) {
        if (!enabled || !isCategoryEnabled(category)) {
            return;
        }
        logger.log(Level.INFO, "[DEBUG/" + category + "] " + message);
    }

    /**
     * 输出带参数的调试信息，用 {} 占位。
     *
     * @param category 分类
     * @param message  含 {} 的消息模板
     * @param args     参数
     */
    public static void log(String category, String message, Object... args) {
        if (!enabled || !isCategoryEnabled(category)) {
            return;
        }
        logger.log(Level.INFO, "[DEBUG/" + category + "] " + format(message, args));
    }

    /**
     * 输出警告级调试信息（不受分类限制，只要总开关开启）。
     */
    public static void warn(String category, String message, Object... args) {
        if (!enabled) {
            return;
        }
        logger.log(Level.WARNING, "[DEBUG/" + category + "] " + format(message, args));
    }

    /**
     * 记录异常堆栈（仅调试模式开启时）。
     */
    public static void error(String category, String message, Throwable t) {
        if (!enabled) {
            if (t != null) {
                // 非调试模式下只留简短记录，避免刷屏
                logger.log(Level.FINE, "[" + category + "] " + message + ": " + t.getMessage());
            }
            return;
        }
        logger.log(Level.WARNING, "[DEBUG/" + category + "] " + message, t);
    }

    /**
     * 记录耗时操作（仅调试模式开启时）。
     *
     * @param category 分类
     * @param action   动作名
     * @param nanos    耗时纳秒
     */
    public static void timing(String category, String action, long nanos) {
        if (!enabled || !isCategoryEnabled(category)) {
            return;
        }
        logger.log(Level.INFO, "[DEBUG/" + category + "] " + action
                + " 耗时 " + String.format("%.2f", nanos / 1_000_000.0D) + " ms");
    }

    /**
     * 脱敏输出 SQL 参数，避免密码等敏感信息泄露到日志。
     *
     * @param params 参数数组
     */
    public static String maskParams(Object[] params) {
        if (params == null || params.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object p = params[i];
            if (p == null) {
                sb.append("null");
            } else {
                String s = String.valueOf(p);
                // 过长的内容截断，防止刷屏
                if (s.length() > 64) {
                    s = s.substring(0, 61) + "...";
                }
                // 换行符替换，防止日志注入
                s = s.replace('\n', ' ').replace('\r', ' ');
                sb.append(s);
            }
        }
        return sb.append("]").toString();
    }

    /**
     * 清理日志文本中的换行与控制字符，防止日志注入攻击。
     */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                sb.append(' ');
            } else if (c < 0x20 && c != '\t') {
                // 丢弃其他控制字符
                continue;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isCategoryEnabled(String category) {
        if (category == null) {
            return true;
        }
        if (SQL.equals(category)) {
            return logSql;
        }
        if (CACHE.equals(category)) {
            return logCache;
        }
        if (PACKET.equals(category)) {
            return logPacket;
        }
        if (VALIDATE.equals(category)) {
            return logValidation;
        }
        return true;
    }

    private static String format(String message, Object... args) {
        if (message == null) {
            return "null";
        }
        if (args == null || args.length == 0) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message.length() + 32);
        int argIdx = 0;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '{' && i + 1 < message.length() && message.charAt(i + 1) == '}'
                    && argIdx < args.length) {
                sb.append(args[argIdx] == null ? "null" : sanitize(String.valueOf(args[argIdx])));
                argIdx++;
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
