package com.ncnick.config;

import com.ncnick.NCNick;
import com.ncnick.util.Debug;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * 单个配置文件。
 *
 * <h3>为什么需要它</h3>
 * 早期所有配置塞在一个 {@code config.yml} 里，超过 370 行，
 * 服主很难快速定位某项设置。现在按功能拆分为多个文件：
 * <pre>
 * plugins/NCNick/
 * ├── config.yml        主配置（版本、语言、总开关）
 * ├── validation.yml    昵称校验（字符集、长度、屏蔽词）
 * ├── economy.yml       金融（价格、支付方式、货币）
 * ├── display.yml       显示格式（聊天/TAB/头顶/占位符）
 * ├── storage.yml       存储与驱动下载
 * ├── experimental.yml  实验性功能
 * ├── gui.yml           界面外观
 * └── debug.yml         调试
 * </pre>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>自动生成</b>：文件不存在时从 jar 内释放默认值</li>
 *   <li><b>自动补全</b>：已有文件缺少新增键时，从 jar 内默认值补齐
 *       （不覆盖用户已改的值），这样升级后不会因为缺键而报错</li>
 *   <li><b>缺失即回退</b>：读取失败时回退到 jar 内默认值，插件仍能启动</li>
 * </ul>
 */
public final class FileConfig {

    private final NCNick plugin;
    /** 文件名，如 "economy.yml" */
    private final String fileName;
    /** 磁盘上的实际文件 */
    private final File file;
    /** 用户配置（可能为 null） */
    private FileConfiguration config;
    /** jar 内默认值（用于补全与回退） */
    private FileConfiguration defaults;
    /**
     * 本文件是否是**本次启动时新建**的（此前磁盘上不存在）。
     *
     * <p>用于旧版配置迁移：只有当新拆分的文件是首次生成时，
     * 才应该从旧版 {@code config.yml} 里搬数据过来，
     * 否则会覆盖用户已经改好的值。
     */
    private boolean createdNew;

    public FileConfig(NCNick plugin, String fileName) {
        this.plugin = plugin;
        this.fileName = fileName;
        this.file = new File(plugin.getDataFolder(), fileName);
        load();
    }

    /** 从磁盘加载；不存在则释放默认文件。 */
    public void load() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                Debug.error(Debug.GENERAL,
                        "无法创建插件目录: " + plugin.getDataFolder(), null);
            }
            // 释放/更新默认文件
            if (!file.exists()) {
                createdNew = true;
                plugin.saveResource(fileName, false);
            }
            config = YamlConfiguration.loadConfiguration(file);
            // 读取 jar 内默认值
            defaults = readDefaults();
            // 补全缺失的键
            if (defaults != null) {
                config.setDefaults(defaults);
                config.options().copyDefaults(false);
            }
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "加载配置文件失败: " + fileName, t);
            config = new YamlConfiguration();
            defaults = readDefaults();
            if (defaults != null) {
                config.setDefaults(defaults);
            }
        }
    }

    /** 保存到磁盘。 */
    public void save() {
        if (config == null) {
            return;
        }
        try {
            config.save(file);
        } catch (IOException e) {
            Debug.error(Debug.GENERAL, "保存配置文件失败: " + fileName, e);
        }
    }

    /** 读取 jar 内的同名资源作为默认值。 */
    private FileConfiguration readDefaults() {
        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) {
                return null;
            }
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Throwable t) {
            Debug.log(Debug.GENERAL, "读取默认配置失败: {} - {}",
                    fileName, t.getMessage());
            return null;
        }
    }

    // ==========================================================
    //  取值（全部带回退）
    // ==========================================================

    public String getString(String path, String def) {
        try {
            if (config != null && config.contains(path)) {
                return config.getString(path, def);
            }
        } catch (Throwable ignored) {
            // 回退
        }
        return def;
    }

    public int getInt(String path, int def) {
        try {
            if (config != null && config.contains(path)) {
                return config.getInt(path, def);
            }
        } catch (Throwable ignored) {
            // 回退
        }
        return def;
    }

    public long getLong(String path, long def) {
        try {
            if (config != null && config.contains(path)) {
                return config.getLong(path, def);
            }
        } catch (Throwable ignored) {
            // 回退
        }
        return def;
    }

    public double getDouble(String path, double def) {
        try {
            if (config != null && config.contains(path)) {
                return config.getDouble(path, def);
            }
        } catch (Throwable ignored) {
            // 回退
        }
        return def;
    }

    public boolean getBoolean(String path, boolean def) {
        try {
            if (config != null && config.contains(path)) {
                return config.getBoolean(path, def);
            }
        } catch (Throwable ignored) {
            // 回退
        }
        return def;
    }

    public List<String> getStringList(String path) {
        try {
            if (config != null) {
                List<String> v = config.getStringList(path);
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
            // 回退
        }
        return new ArrayList<String>();
    }

    public ConfigurationSection getSection(String path) {
        try {
            if (config != null) {
                return config.getConfigurationSection(path);
            }
        } catch (Throwable ignored) {
            // 回退
        }
        return null;
    }

    /**
     * 取任意类型原始值（用于「可能是 all 也可能是数字」这类配置）。
     *
     * @param path 路径
     * @param def  默认值
     */
    public Object get(String path, Object def) {
        try {
            if (config != null && config.contains(path)) {
                return config.get(path, def);
            }
        } catch (Throwable ignored) {
            // 回退
        }
        return def;
    }

    /** 文件是否成功加载。 */
    public boolean isLoaded() {
        return config != null;
    }

    /**
     * 本文件是否是本次启动时**新建**的。
     *
     * <p>旧版配置迁移只在此时进行，避免覆盖用户已修改的值。
     */
    public boolean isCreatedNew() {
        return createdNew;
    }

    /**
     * 写入一个值并立即落盘（仅用于配置迁移）。
     *
     * @param path  配置路径
     * @param value 值
     */
    public void setAndSave(String path, Object value) {
        if (config == null) {
            return;
        }
        config.set(path, value);
        save();
    }

    /** 磁盘文件（用于错误提示）。 */
    public File getFile() {
        return file;
    }

    /** 文件名。 */
    public String getFileName() {
        return fileName;
    }
}
