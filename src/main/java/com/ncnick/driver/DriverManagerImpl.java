package com.ncnick.driver;

import com.ncnick.NCNick;
import com.ncnick.util.Debug;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据库驱动管理器。
 *
 * <p>统一负责驱动的读取配置、按需下载、动态加载与状态查询。
 * 这是第 4、5 条需求的落地入口。
 */
public final class DriverManagerImpl {

    private final NCNick plugin;
    private final DriverDownloader downloader;
    private final DriverClassLoader classLoader;
    /** 驱动定义表：key -> artifact */
    private final Map<String, DriverArtifact> artifacts = new LinkedHashMap<String, DriverArtifact>();

    public DriverManagerImpl(NCNick plugin) {
        this.plugin = plugin;
        this.downloader = new DriverDownloader(plugin);
        this.classLoader = new DriverClassLoader(downloader.getLibDir());
        loadArtifactDefinitions();
    }

    /**
     * 重新读取驱动坐标定义（供 reload 调用）。
     *
     * <p>让 {@code driver.artifacts.*} 的修改无需重启即可生效。
     */
    public void reloadArtifacts() {
        loadArtifactDefinitions();
        Debug.log(Debug.SQL, "驱动定义已重新加载");
    }

    /**
     * 从配置文件读取驱动坐标定义。
     *
     * <p>配置缺失时使用内置默认值，保证开箱即用。
     */
    private void loadArtifactDefinitions() {
        artifacts.clear();

        // MySQL
        artifacts.put("mysql", readArtifact("mysql",
                "com.mysql", "mysql-connector-j", "8.0.33",
                "mysql-connector-j-8.0.33.jar", "com.mysql.cj.jdbc.Driver"));

        // SQLite
        artifacts.put("sqlite", readArtifact("sqlite",
                "org.xerial", "sqlite-jdbc", "3.45.3.0",
                "sqlite-jdbc-3.45.3.0.jar", "org.sqlite.JDBC"));

        Debug.log(Debug.SQL, "已加载 {} 个驱动定义", artifacts.size());
    }

    private DriverArtifact readArtifact(String key, String defGroup, String defArtifact,
                                        String defVersion, String defFile, String defMain) {
        String base = "driver.artifacts." + key + ".";
        String group = safeCoord(getString(base + "group", defGroup), defGroup, key, "group");
        String artifact = safeCoord(getString(base + "artifact", defArtifact), defArtifact,
                key, "artifact");
        String version = safeCoord(getString(base + "version", defVersion), defVersion,
                key, "version");
        String fileName = safeFileName(getString(base + "file-name", defFile), defFile, key);
        String mainClass = safeClassName(getString(base + "main-class", defMain), defMain, key);
        return new DriverArtifact(key, group, artifact, version, fileName, mainClass);
    }

    /**
     * 校验 Maven 坐标分量，防止路径穿越与 URL 注入。
     *
     * <p>坐标会被拼进下载 URL，若含 {@code ..} 或 {@code /} 可逃逸出仓库路径。
     */
    private String safeCoord(String value, String fallback, String key, String field) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        // 只允许字母、数字、点、下划线、短横线
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) {
                plugin.getLogger().warning("驱动 " + key + " 的 " + field
                        + " 含非法字符，已回退为默认值: " + value);
                return fallback;
            }
        }
        if (value.contains("..")) {
            plugin.getLogger().warning("驱动 " + key + " 的 " + field
                    + " 含路径穿越字符，已回退为默认值: " + value);
            return fallback;
        }
        return value;
    }

    /**
     * 校验驱动文件名，防止路径穿越写出数据目录。
     *
     * <p>文件名会被用于 {@code new File(libDir, fileName)}，
     * 若含 {@code ../} 或盘符可逃逸出插件目录。
     */
    private String safeFileName(String value, String fallback, String key) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        // 禁止任何路径分隔符与盘符
        if (value.contains("/") || value.contains("\\") || value.contains(":")
                || value.contains("..") || value.startsWith("~")) {
            plugin.getLogger().warning("驱动 " + key + " 的 file-name 含路径字符，"
                    + "已回退为默认值: " + value);
            return fallback;
        }
        // 只允许安全字符，且必须以 .jar 结尾
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) {
                plugin.getLogger().warning("驱动 " + key + " 的 file-name 含非法字符，"
                        + "已回退为默认值: " + value);
                return fallback;
            }
        }
        if (!value.toLowerCase().endsWith(".jar")) {
            plugin.getLogger().warning("驱动 " + key + " 的 file-name 必须以 .jar 结尾，"
                    + "已回退为默认值: " + value);
            return fallback;
        }
        return value;
    }

    /**
     * 校验驱动主类名，防止反射加载任意类。
     */
    private String safeClassName(String value, String fallback, String key) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '$';
            if (!ok) {
                plugin.getLogger().warning("驱动 " + key + " 的 main-class 含非法字符，"
                        + "已回退为默认值: " + value);
                return fallback;
            }
        }
        if (!value.contains(".")) {
            return fallback;
        }
        return value;
    }

    private String getString(String path, String def) {
        String v = plugin.getConfig().getString(path, def);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    /** 获取驱动定义。 */
    public DriverArtifact getArtifact(String key) {
        return artifacts.get(key);
    }

    /** 下载器（供管理员命令直接调用）。 */
    public DriverDownloader getDownloader() {
        return downloader;
    }

    /** 类加载器。 */
    public DriverClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * 确保某个驱动可用：需要时下载，然后加载。
     *
     * <p>必须在异步线程调用（内部有网络与磁盘 IO）。
     *
     * @param key 驱动键名，如 "mysql" / "sqlite"
     * @return 是否最终可用
     */
    public boolean ensureAvailable(String key) {
        DriverArtifact artifact = artifacts.get(key);
        if (artifact == null) {
            plugin.getLogger().warning("未知的驱动类型: " + key);
            return false;
        }

        // 先尝试直接从本地加载
        File target = new File(downloader.getLibDir(), artifact.getFileName());
        if (target.isFile()) {
            loadDriver();
            // 加载后检测主类是否真的可用，而不是依赖加载计数
            if (classLoader.isAvailable(artifact.getMainClass())) {
                return true;
            }
        }

        // 本地没有或加载失败，尝试下载
        if (!plugin.getConfigManager().isDriverAutoDownload()) {
            plugin.getLogger().warning("驱动 " + artifact.getFileName()
                    + " 缺失，且已关闭自动下载。请手动放入: "
                    + downloader.getLibDir().getAbsolutePath());
            return false;
        }

        DriverDownloader.Result result = downloader.ensure(artifact);
        if (!result.isSuccess()) {
            plugin.getLogger().severe("驱动 " + artifact.getFileName() + " 不可用："
                    + result.getMessage());
            return false;
        }

        // 下载后重新加载
        loadDriver();
        boolean ok = classLoader.isAvailable(artifact.getMainClass());
        if (!ok) {
            plugin.getLogger().severe("驱动 " + artifact.getFileName()
                    + " 已下载但无法加载，请检查文件完整性。");
        }
        return ok;
    }

    /**
     * 加载 lib 目录下所有驱动。
     *
     * @return 新加载的驱动数量
     */
    public int loadDriver() {
        if (!downloader.ensureDir()) {
            plugin.getLogger().warning("无法创建驱动目录: "
                    + downloader.getLibDir().getAbsolutePath());
            return 0;
        }
        return classLoader.loadAll();
    }

    /**
     * 在插件启动时预加载已有驱动（不触发下载）。
     *
     * @return 是否至少加载到一个驱动
     */
    public boolean preload() {
        downloader.ensureDir();
        int count = classLoader.loadAll();
        if (count > 0) {
            Debug.log(Debug.GENERAL, "预加载了 {} 个数据库驱动", count);
        }
        return count > 0;
    }

    /**
     * 强制重新下载指定驱动（先删除本地文件）。
     *
     * <p>必须在异步线程调用。
     */
    public DriverDownloader.Result forceDownload(String key) {
        DriverArtifact artifact = artifacts.get(key);
        if (artifact == null) {
            return null;
        }
        classLoader.close();
        downloader.delete(artifact);
        DriverDownloader.Result r = downloader.ensure(artifact);
        if (r.isSuccess()) {
            loadDriver();
        }
        return r;
    }

    /**
     * 生成驱动状态报告（供 /nca driver status 使用）。
     */
    public Map<String, String> status() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, DriverArtifact> e : artifacts.entrySet()) {
            DriverArtifact a = e.getValue();
            File f = new File(downloader.getLibDir(), a.getFileName());
            String state;
            if (!f.isFile()) {
                state = "缺失";
            } else if (classLoader.isAvailable(a.getMainClass())) {
                state = "已就绪";
            } else {
                state = "已下载未加载";
            }
            out.put(e.getKey() + " (" + a.getFileName() + ")", state);
        }
        return out;
    }

    /** 驱动目录绝对路径。 */
    public String libPath() {
        return downloader.getLibDir().getAbsolutePath();
    }

    /**
     * 关闭并释放驱动文件句柄。
     */
    public void close() {
        classLoader.close();
    }
}
