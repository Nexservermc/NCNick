package com.ncnick.driver;

import com.ncnick.NCNick;
import com.ncnick.util.Debug;
import com.ncnick.util.VersionUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库驱动下载器（第 4、5 条）。
 *
 * <p>插件不再把数据库驱动打包进 jar，而是在首次使用数据库时按需下载，
 * 存放到插件数据目录的 {@code lib/} 子目录（即 plugins/NCNick/lib/）。
 *
 * <p>下载策略：按配置的源顺序依次尝试，某个源超时或失败后自动降级到下一个源。
 * 默认顺序为「国内镜像 → 国内镜像 → 官方源」。
 *
 * <p>安全措施：
 * <ul>
 *   <li>只允许 https/http 协议，阻断 file:// 等本地协议读取</li>
 *   <li>校验下载文件大小下限，防止把错误页面当成 jar</li>
 *   <li>校验 jar 的 ZIP 魔数（PK\x03\x04），确保是合法压缩包</li>
 *   <li>先写临时文件，校验通过后再原子移动到目标路径，避免半截文件污染</li>
 *   <li>设置连接与读取超时，防止线程被长时间挂死</li>
 * </ul>
 */
public final class DriverDownloader {

    /** ZIP 魔数，jar 本质是 zip */
    private static final byte[] ZIP_MAGIC = new byte[]{0x50, 0x4B, 0x03, 0x04};

    private final NCNick plugin;
    /** 驱动存放目录：plugins/NCNick/lib/ */
    private final File libDir;

    public DriverDownloader(NCNick plugin) {
        this.plugin = plugin;
        // 插件数据目录下的 lib 子目录
        this.libDir = new File(plugin.getDataFolder(), "lib");
    }

    /** 驱动库目录。 */
    public File getLibDir() {
        return libDir;
    }

    /** 确保 lib 目录存在。 */
    public boolean ensureDir() {
        if (libDir.exists()) {
            return libDir.isDirectory();
        }
        return libDir.mkdirs();
    }

    /**
     * 下载结果。
     */
    public static final class Result {
        private final boolean success;
        private final File file;
        private final String message;
        private final String sourceName;

        Result(boolean success, File file, String message, String sourceName) {
            this.success = success;
            this.file = file;
            this.message = message;
            this.sourceName = sourceName;
        }

        public boolean isSuccess() {
            return success;
        }

        public File getFile() {
            return file;
        }

        public String getMessage() {
            return message;
        }

        /** 实际成功的源名，失败时为最后一次尝试的源名。 */
        public String getSourceName() {
            return sourceName;
        }
    }

    /**
     * 确保指定驱动已就绪：本地有则直接返回，没有则下载。
     *
     * @param artifact 驱动定义
     * @return 结果
     */
    public Result ensure(DriverArtifact artifact) {
        if (artifact == null) {
            return new Result(false, null, "驱动定义为空", "");
        }

        File target = new File(libDir, artifact.getFileName());

        // 本地已存在且合法，直接复用
        if (target.isFile() && isValidJar(target, artifact)) {
            Debug.log(Debug.GENERAL, "驱动已存在，跳过下载: {}", target.getName());
            return new Result(true, target, "已存在", "本地缓存");
        }

        if (!ensureDir()) {
            return new Result(false, null, "无法创建驱动目录: " + libDir.getAbsolutePath(), "");
        }

        return download(artifact, target);
    }

    /**
     * 按配置的源顺序下载驱动。
     */
    private Result download(DriverArtifact artifact, File target) {
        List<DriverSource> sources = collectSources();
        if (sources.isEmpty()) {
            return new Result(false, null, "未配置任何下载源", "");
        }

        String lastSource = "";
        String lastError = "未知错误";

        for (DriverSource source : sources) {
            lastSource = source.getName();
            String url = buildUrl(source, artifact);

            // 每个源重试若干次
            int retries = Math.max(1, plugin.getConfigManager().getDriverRetries());
            for (int attempt = 1; attempt <= retries; attempt++) {
                try {
                    Debug.log(Debug.GENERAL, "尝试从 {} 下载 {} (第 {} 次)",
                            source.getName(), artifact.getFileName(), attempt);
                    boolean ok = fetchToFile(url, target, artifact);
                    if (ok) {
                        plugin.getLogger().info("已从 " + source.getName() + " 下载驱动 "
                                + artifact.getFileName());
                        return new Result(true, target, "下载成功", source.getName());
                    }
                    lastError = "文件校验未通过";
                } catch (Throwable t) {
                    lastError = t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : (": " + t.getMessage()));
                    Debug.error(Debug.GENERAL, "从 " + source.getName() + " 下载失败", t);
                }

                // 最后一次失败就不再等待
                if (attempt < retries) {
                    try {
                        Thread.sleep(800L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new Result(false, null, "下载被中断", source.getName());
                    }
                }
            }

            plugin.getLogger().warning("下载源「" + source.getName() + "」失败（"
                    + lastError + "），降级到下一个源");
        }

        return new Result(false, null, lastError, lastSource);
    }

    /**
     * 实际执行 HTTP 下载，带完整校验。
     *
     * <p>安全措施：
     * <ul>
     *   <li>协议与主机白名单（阻断 SSRF 到内网/回环地址）</li>
     *   <li>禁止自动重定向，改由本方法逐跳校验后再跳转</li>
     *   <li>临时文件使用随机名，避免并发下载互相覆盖</li>
     * </ul>
     *
     * @return 是否成功且校验通过
     */
    private boolean fetchToFile(String urlStr, File target, DriverArtifact artifact)
            throws IOException {
        int timeout = plugin.getConfigManager().getDriverTimeout();

        // 用随机临时文件，避免并发下载写同一个 .tmp 造成文件损坏
        File tempDir = target.getParentFile();
        File temp = File.createTempFile(target.getName() + ".", ".tmp", tempDir);

        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            URL url = new URL(urlStr);
            conn = openCheckedConnection(url, timeout);
            if (conn == null) {
                throw new IOException("下载地址未通过安全校验");
            }

            in = conn.getInputStream();
            out = new FileOutputStream(temp);

            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();
            out.close();
            out = null;

            // 校验大小下限
            long minSize = plugin.getConfigManager().getDriverMinFileSize();
            if (total < minSize) {
                throw new IOException("文件过小（" + total + " 字节），疑似下载到错误页面");
            }

            // 校验 ZIP 魔数
            if (!hasZipMagic(temp)) {
                throw new IOException("文件不是合法的 jar（ZIP 魔数校验失败）");
            }

            // 校验必需类是否存在（更严格的完整性检查）
            if (!containsClass(temp, artifact.getMainClass())) {
                throw new IOException("jar 中缺少主类 " + artifact.getMainClass());
            }

            // 原子移动到目标位置
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                // 某些文件系统不支持原子移动，退回普通移动
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            if (conn != null) {
                conn.disconnect();
            }
            // 清理残留临时文件（随机名，不会误删其他线程的文件）
            if (temp.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    /**
     * 打开经过安全校验的 HTTP 连接。
     *
     * <p>手工处理重定向：每一跳都重新做协议与主机校验，
     * 防止下载源通过 302 把请求引向内网或元数据服务（SSRF）。
     *
     * @return 校验通过的连接；失败返回 null
     */
    private HttpURLConnection openCheckedConnection(URL url, int timeout) throws IOException {
        int maxRedirects = 5;
        URL current = url;

        for (int hop = 0; hop <= maxRedirects; hop++) {
            if (!isAllowedUrl(current)) {
                plugin.getLogger().warning("拒绝不安全的下载地址: " + current);
                return null;
            }

            HttpURLConnection conn = null;
            try {
                URLConnection raw = current.openConnection();
                if (!(raw instanceof HttpURLConnection)) {
                    plugin.getLogger().warning("非 HTTP 连接，已拒绝: " + current);
                    return null;
                }
                conn = (HttpURLConnection) raw;
                conn.setConnectTimeout(timeout);
                conn.setReadTimeout(timeout);
                // 禁止自动重定向，改为手工逐跳校验
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent",
                        "NCNick/" + plugin.getDescription().getVersion());
                conn.setRequestProperty("Accept", "*/*");

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    return conn;
                }
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null || location.isEmpty()) {
                        throw new IOException("重定向缺少 Location 头");
                    }
                    // 处理相对跳转
                    URL next = new URL(current, location);
                    Debug.log(Debug.GENERAL, "跟随重定向 {} -> {}", current, next);
                    current = next;
                    continue;
                }
                conn.disconnect();
                throw new IOException("HTTP " + code);
            } catch (IOException e) {
                if (conn != null) {
                    conn.disconnect();
                }
                throw e;
            }
        }
        throw new IOException("重定向次数过多（超过 " + maxRedirects + " 次）");
    }

    /**
     * 构造某个源上的完整下载地址。
     *
     * <p>标准 Maven 布局：{base}/{group 路径}/{artifact}/{version}/{file}
     */
    private String buildUrl(DriverSource source, DriverArtifact artifact) {
        String base = source.getUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String groupPath = artifact.getGroup().replace('.', '/');
        return base + "/" + groupPath + "/" + artifact.getArtifact() + "/"
                + artifact.getVersion() + "/" + artifact.getFileName();
    }

    /**
     * 读取配置中的源列表。
     *
     * <p>配置结构：
     * <pre>
     * driver:
     *   sources:
     *     - name: "阿里云镜像"
     *       url: "https://maven.aliyun.com/repository/public"
     * </pre>
     */
    private List<DriverSource> collectSources() {
        List<DriverSource> out = new ArrayList<DriverSource>();
        List<?> raw = plugin.getConfig().getList("driver.sources");
        if (raw == null) {
            // 配置缺失时使用内置兜底源，保证功能可用
            out.add(new DriverSource("阿里云镜像",
                    "https://maven.aliyun.com/repository/public"));
            out.add(new DriverSource("Maven 官方中央仓库",
                    "https://repo1.maven.org/maven2"));
            return out;
        }

        for (Object o : raw) {
            if (o instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) o;
                Object nameObj = map.get("name");
                Object urlObj = map.get("url");
                String name = nameObj == null ? "未命名源" : String.valueOf(nameObj);
                String url = urlObj == null ? "" : String.valueOf(urlObj).trim();
                if (!url.isEmpty()) {
                    out.add(new DriverSource(name, url));
                }
            } else if (o instanceof String) {
                // 兼容只写 URL 的简写形式
                String url = ((String) o).trim();
                if (!url.isEmpty()) {
                    out.add(new DriverSource(url, url));
                }
            }
        }

        if (out.isEmpty()) {
            out.add(new DriverSource("阿里云镜像",
                    "https://maven.aliyun.com/repository/public"));
            out.add(new DriverSource("Maven 官方中央仓库",
                    "https://repo1.maven.org/maven2"));
        }
        return out;
    }

    /**
     * 判断 URL 是否允许访问。
     *
     * <p>安全措施（防 SSRF）：
     * <ol>
     *   <li>只允许 http / https 协议，阻断 file: / jar: 等本地读取</li>
     *   <li>解析主机名到 IP，拒绝回环、私有网段、链路本地与多播地址，
     *       防止下载源通过重定向把请求引向内网或云元数据服务</li>
     * </ol>
     *
     * @param url 待校验地址
     * @return 是否允许
     */
    private static boolean isAllowedUrl(URL url) {
        if (url == null) {
            return false;
        }

        // ---- 1. 协议白名单 ----
        String protocol = url.getProtocol();
        if (protocol == null) {
            return false;
        }
        String lower = protocol.toLowerCase();
        if (!"http".equals(lower) && !"https".equals(lower)) {
            return false;
        }

        // ---- 2. 主机名与地址检查 ----
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        // 显式拒绝 localhost 别名
        String hostLower = host.toLowerCase();
        if ("localhost".equals(hostLower) || hostLower.endsWith(".localhost")
                || hostLower.endsWith(".local") || hostLower.endsWith(".internal")) {
            return false;
        }

        try {
            // 解析所有 A/AAAA 记录，任一为内网地址即拒绝
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (java.net.InetAddress addr : addresses) {
                if (isBlockedAddress(addr)) {
                    return false;
                }
            }
        } catch (java.net.UnknownHostException e) {
            // 无法解析的主机名直接拒绝
            return false;
        } catch (Throwable t) {
            // 其他解析异常按不安全处理
            return false;
        }

        return true;
    }

    /**
     * 判断是否为需要阻断的地址（内网/回环/保留段）。
     */
    private static boolean isBlockedAddress(java.net.InetAddress addr) {
        if (addr == null) {
            return true;
        }
        // 回环、任意本地、链路本地、站点本地、多播
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }

        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            // 0.0.0.0/8
            if (b0 == 0) {
                return true;
            }
            // 100.64.0.0/10（运营商级 NAT）
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true;
            }
            // 169.254.0.0/16（链路本地，含云元数据 169.254.169.254）
            if (b0 == 169 && b1 == 254) {
                return true;
            }
            // 192.0.0.0/24 与 192.0.2.0/24（保留/文档）
            if (b0 == 192 && (b1 == 0 || b1 == 2)) {
                return true;
            }
            // 198.18.0.0/15（基准测试）
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true;
            }
            // 240.0.0.0/4（保留）
            if (b0 >= 240) {
                return true;
            }
        } else if (raw.length == 16) {
            // IPv6：唯一本地地址 fc00::/7
            int b0 = raw[0] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) {
                return true;
            }
            // IPv4 映射地址 ::ffff:x.x.x.x，递归检查内嵌的 IPv4
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                try {
                    java.net.InetAddress v4 = java.net.InetAddress.getByAddress(
                            new byte[]{raw[12], raw[13], raw[14], raw[15]});
                    return isBlockedAddress(v4);
                } catch (Throwable ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断 URL 字符串是否允许访问（兼容旧调用点）。
     */
    private static boolean isAllowedUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            return isAllowedUrl(new URL(url));
        } catch (java.net.MalformedURLException e) {
            return false;
        }
    }

    /** 校验文件开头的 ZIP 魔数。 */
    private static boolean hasZipMagic(File file) {
        InputStream in = null;
        try {
            in = Files.newInputStream(file.toPath());
            byte[] head = new byte[4];
            int read = 0;
            while (read < 4) {
                int r = in.read(head, read, 4 - read);
                if (r < 0) {
                    return false;
                }
                read += r;
            }
            for (int i = 0; i < 4; i++) {
                if (head[i] != ZIP_MAGIC[i]) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * 检查 jar 里是否包含指定类。
     *
     * <p>用 java.util.jar 读取，避免依赖任何外部库。
     */
    private static boolean containsClass(File file, String className) {
        if (className == null || className.isEmpty()) {
            return true;
        }
        String entryName = className.replace('.', '/') + ".class";
        java.util.jar.JarFile jar = null;
        try {
            jar = new java.util.jar.JarFile(file);
            return jar.getEntry(entryName) != null;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    /** 完整校验一个已存在的 jar 是否可用。 */
    private static boolean isValidJar(File file, DriverArtifact artifact) {
        if (!file.isFile()) {
            return false;
        }
        long min = 10240L;
        if (file.length() < min) {
            return false;
        }
        if (!hasZipMagic(file)) {
            return false;
        }
        return containsClass(file, artifact.getMainClass());
    }

    /** 删除指定驱动文件（用于强制重新下载）。 */
    public boolean delete(DriverArtifact artifact) {
        if (artifact == null) {
            return false;
        }
        File f = new File(libDir, artifact.getFileName());
        return !f.isFile() || f.delete();
    }

    /** 列出 lib 目录下所有 jar。 */
    public List<File> listJars() {
        List<File> out = new ArrayList<File>();
        File[] files = libDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // 忽略关闭异常
            }
        }
    }
}
