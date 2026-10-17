package com.ncnick.config;

import com.ncnick.NCNick;
import com.ncnick.util.Debug;
import com.ncnick.util.Msg;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 语言文件管理器（第 11 条：单独的语言文件）。
 *
 * <p>从 jar 内置资源释放语言文件到 {@code plugins/NCNick/lang/}，
 * 支持用户自定义与缺失键回退。
 *
 * <p>回退链：用户文件 → 内置默认 → 键名本身。
 */
public final class LangManager {

    /**
     * 内置支持的语言。
     *
     * <p>新增语言只需两步：
     * <ol>
     *   <li>把 {@code <code>.yml} 放进 {@code src/main/resources/lang/}</li>
     *   <li>在下方数组里登记该语言代码</li>
     * </ol>
     * 键结构必须与 {@code zh_CN.yml} 完全一致（自检脚本会校验）。
     */
    private static final String[] BUILTIN = new String[]{
            // ---- 东亚 ----
            "zh_CN",   // 简体中文
            "zh_TW",   // 繁體中文
            "ja_JP",   // 日本語
            "ko_KR",   // 한국어
            // ---- 东南亚 ----
            "vi_VN",   // Tiếng Việt
            "th_TH",   // ไทย
            "id_ID",   // Bahasa Indonesia
            // ---- 南亚 / 中东 ----
            "ar_SA",   // العربية
            "he_IL",   // עברית
            "tr_TR",   // Türkçe
            // ---- 西欧 ----
            "en_US",   // English
            "de_DE",   // Deutsch
            "fr_FR",   // Français
            "es_ES",   // Español
            "pt_BR",   // Português (Brasil)
            "it_IT",   // Italiano
            "nl_NL",   // Nederlands
            // ---- 北欧 ----
            "sv_SE",   // Svenska
            "da_DK",   // Dansk
            "fi_FI",   // Suomi
            "nb_NO",   // Norsk bokmål
            // ---- 中东欧 ----
            "pl_PL",   // Polski
            "cs_CZ",   // Čeština
            "sk_SK",   // Slovenčina
            "hu_HU",   // Magyar
            "ro_RO",   // Română
            "bg_BG",   // Български
            // ---- 东欧 / 南欧 ----
            "ru_RU",   // Русский
            "uk_UA",   // Українська
            "el_GR"    // Ελληνικά
    };

    private final NCNick plugin;
    private final File langDir;
    /** 当前语言代码 */
    private String currentLocale = "zh_CN";
    /** 用户语言文件 */
    private FileConfiguration userConfig;
    /** 内置默认（从 jar 读取，保证键完整） */
    private FileConfiguration defaultConfig;

    public LangManager(NCNick plugin) {
        this.plugin = plugin;
        this.langDir = new File(plugin.getDataFolder(), "lang");
    }

    /**
     * 初始化：读取配置中的语言设置，释放并加载语言文件。
     *
     * @param locale 配置里指定的语言代码，可为空
     */
    public void load(String locale) {
        if (locale == null || locale.trim().isEmpty()) {
            locale = "zh_CN";
        }
        this.currentLocale = locale.trim();

        // 释放内置语言文件（若用户目录中不存在）
        releaseBuiltins();

        // 加载内置默认，作为回退基础
        defaultConfig = readBuiltin(this.currentLocale);
        if (defaultConfig == null) {
            plugin.getLogger().warning("语言文件 " + this.currentLocale
                    + ".yml 不存在于插件内置资源中，回退到 zh_CN");
            this.currentLocale = "zh_CN";
            defaultConfig = readBuiltin("zh_CN");
        }

        // 加载用户文件（可覆盖内置内容）
        File userFile = new File(langDir, this.currentLocale + ".yml");
        if (userFile.isFile()) {
            userConfig = YamlConfiguration.loadConfiguration(userFile);
            // 补齐用户文件中缺失的键（从内置复制）
            if (defaultConfig != null) {
                userConfig.setDefaults(defaultConfig);
                userConfig.options().copyDefaults(true);
            }
        } else {
            userConfig = defaultConfig;
        }

        if (userConfig == null) {
            plugin.getLogger().warning("无法加载语言文件，消息将显示为键名");
            userConfig = new YamlConfiguration();
        }

        Debug.log(Debug.GENERAL, "语言文件已加载: {} ({} 个键)",
                this.currentLocale, userConfig.getKeys(true).size());
    }

    /** 当前语言代码。 */
    public String getLocale() {
        return currentLocale;
    }

    /** 语言目录。 */
    public File getLangDir() {
        return langDir;
    }

    /**
     * 把 jar 内置的语言文件释放到磁盘（不覆盖已存在的用户文件）。
     *
     * <p>使用 {@link #BUILTIN} 列表逐个释放。新增语言时
     * 只需把 yml 放进 {@code resources/lang/} 并在此列表登记即可。
     */
    private void releaseBuiltins() {
        if (!langDir.isDirectory() && !langDir.mkdirs()) {
            plugin.getLogger().warning("无法创建语言目录: " + langDir.getAbsolutePath());
            return;
        }
        for (String locale : BUILTIN) {
            File out = new File(langDir, locale + ".yml");
            if (out.isFile()) {
                continue;
            }
            InputStream in = null;
            Writer writer = null;
            try {
                in = plugin.getResource("lang/" + locale + ".yml");
                if (in == null) {
                    continue;
                }
                // 读取为字符串再写出，保证 UTF-8 编码正确
                String content = readAll(in);
                writer = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
                writer.write(content);
                writer.flush();
                Debug.log(Debug.GENERAL, "已释放语言文件: {}", out.getName());
            } catch (Throwable t) {
                Debug.error(Debug.GENERAL, "释放语言文件失败: " + locale, t);
            } finally {
                closeQuietly(writer);
                closeQuietly(in);
            }
        }
    }

    private FileConfiguration readBuiltin(String locale) {
        InputStream in = null;
        Reader reader = null;
        try {
            in = plugin.getResource("lang/" + locale + ".yml");
            if (in == null) {
                return null;
            }
            reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "读取内置语言文件失败: " + locale, t);
            return null;
        } finally {
            closeQuietly(reader);
            closeQuietly(in);
        }
    }

    // ==========================================================
    //  取文本
    // ==========================================================

    /**
     * 取原始文本（未做颜色转换）。
     *
     * @param key 形如 "msg.no-permission" 或 "validate.invalid-length"
     * @return 文本；找不到时返回 key 本身，便于排查
     */
    public String raw(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String v = null;
        if (userConfig != null) {
            v = userConfig.getString(key);
        }
        if (v == null && defaultConfig != null) {
            v = defaultConfig.getString(key);
        }
        return v == null ? key : v;
    }

    /**
     * 取已着色文本。
     */
    public String text(String key) {
        return Msg.color(raw(key));
    }

    /**
     * 取已着色文本并替换占位符。
     *
     * @param key    语言键
     * @param values 变量表
     */
    public String text(String key, Map<String, String> values) {
        return Msg.color(Msg.fill(raw(key), values));
    }

    /**
     * 取字符串列表（用于 lore 等）。
     */
    public List<String> list(String key) {
        List<String> out = new ArrayList<String>();
        List<String> v = null;
        if (userConfig != null) {
            v = userConfig.getStringList(key);
        }
        if ((v == null || v.isEmpty()) && defaultConfig != null) {
            v = defaultConfig.getStringList(key);
        }
        if (v != null) {
            for (String s : v) {
                out.add(Msg.color(s));
            }
        }
        return out;
    }

    /**
     * 取字符串列表并替换占位符。
     *
     * <p>顺序与 {@link #text(String, Map)} 保持一致：<b>先替换占位符、再着色</b>。
     * 若反过来（先着色再替换），占位符值里携带的 {@code &}/{@code #} 颜色码
     * 不会被转换，管理员会看到字面的颜色代码。
     */
    public List<String> list(String key, Map<String, String> values) {
        List<String> out = new ArrayList<String>();
        List<String> raw = null;
        if (userConfig != null) {
            raw = userConfig.getStringList(key);
        }
        if ((raw == null || raw.isEmpty()) && defaultConfig != null) {
            raw = defaultConfig.getStringList(key);
        }
        if (raw != null) {
            for (String s : raw) {
                // 先 fill，再 color
                out.add(Msg.color(Msg.fill(s, values)));
            }
        }
        return out;
    }

    /** 前缀（已着色）。 */
    public String prefix() {
        return text("prefix");
    }

    /**
     * 带前缀的消息。
     */
    public String msg(String key) {
        return prefix() + text(key);
    }

    /**
     * 带前缀并替换占位符的消息。
     */
    public String msg(String key, Map<String, String> values) {
        return prefix() + text(key, values);
    }

    /**
     * 不带前缀、仅替换占位符的消息。
     */
    public String plain(String key, Map<String, String> values) {
        return text(key, values);
    }

    /**
     * 语言文件是否缺失某个键（用于启动自检）。
     */
    public boolean has(String key) {
        if (userConfig != null && userConfig.contains(key)) {
            return true;
        }
        return defaultConfig != null && defaultConfig.contains(key);
    }

    /**
     * 重载语言文件。
     */
    public void reload(String locale) {
        load(locale);
    }

    /** 可用语言列表。 */
    public static List<String> availableLocales() {
        return Arrays.asList(BUILTIN);
    }

    /**
     * 创建带单个键值对的变量表（方便调用）。
     */
    public static Map<String, String> vars(String k1, String v1) {
        Map<String, String> m = new HashMap<String, String>();
        m.put(k1, v1);
        return m;
    }

    /** 两个键值对。 */
    public static Map<String, String> vars(String k1, String v1, String k2, String v2) {
        Map<String, String> m = vars(k1, v1);
        m.put(k2, v2);
        return m;
    }

    /** 三个键值对。 */
    public static Map<String, String> vars(String k1, String v1, String k2, String v2,
                                           String k3, String v3) {
        Map<String, String> m = vars(k1, v1, k2, v2);
        m.put(k3, v3);
        return m;
    }

    // ==========================================================
    //  工具
    // ==========================================================

    private static String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }
}
