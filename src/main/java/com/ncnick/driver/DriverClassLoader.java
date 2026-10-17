package com.ncnick.driver;

import com.ncnick.util.Debug;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库驱动类加载器。
 *
 * <p>把下载到 {@code plugins/NCNick/lib/} 的驱动 jar 动态加入类路径，
 * 使插件能在不打包驱动的前提下使用 JDBC。
 *
 * <p>关键设计：
 * <ul>
 *   <li>父加载器使用插件自身的类加载器，保证驱动能访问 JDK 的 java.sql 接口</li>
 *   <li>注册驱动后用 {@link DriverManager} 校验，失败则注销，避免污染全局状态</li>
 *   <li>支持重复调用（幂等），已加载的驱动不会重复注册</li>
 *   <li>兼容 SQLite 驱动的原生库解压（其 jar 内含各平台 .so/.dll）</li>
 * </ul>
 */
public final class DriverClassLoader {

    /**
     * 受信的驱动 jar 文件名前缀。
     *
     * <p>动态类加载等同于代码执行，因此只允许加载这些已知的
     * 数据库驱动包，防止有人往 lib/ 里投放任意 jar 获得 RCE。
     */
    private static final String[] TRUSTED_PREFIXES = new String[]{
            "mysql-connector-j-",
            "mysql-connector-java-",
            "sqlite-jdbc-",
            "mariadb-java-client-"
    };

    /** 用于输出告警的日志器（由外部注入） */
    private static java.util.logging.Logger logger =
            java.util.logging.Logger.getLogger("NCNick");

    /** 注入日志器。 */
    public static void setLogger(java.util.logging.Logger log) {
        if (log != null) {
            logger = log;
        }
    }

    private final File libDir;
    /** 已创建的类加载器，便于关闭时释放文件句柄 */
    private URLClassLoader loader;
    /** 真正从 {@code lib/} 目录加载的驱动主类名 */
    private final List<String> loaded = new ArrayList<String>();
    /**
     * 从**服务端 / 其它插件**（父类加载器）继承来的驱动主类名。
     *
     * <p>为什么必须区分：{@link URLClassLoader} 默认是<b>父优先</b>委派，
     * 即使我们只想加载 {@code lib/} 里的 jar，只要 lib/ 里存在**任意**驱动，
     * 类加载器就会被创建，随后 {@code Class.forName} 会先向父加载器询问 ——
     * Paper 自带的 MySQL 驱动就这样"混"了进来，被误判为"已从 lib/ 加载"。
     *
     * <p>后果：{@code ensureDriver()} 认为驱动已就绪而跳过下载，
     * 一旦换到不自带该驱动的核心就直接连不上库。
     */
    private final List<String> inherited = new ArrayList<String>();
    /** 本插件注册到 DriverManager 的驱动包装器，用于关闭时注销 */
    private final List<Driver> registered = new ArrayList<Driver>();

    public DriverClassLoader(File libDir) {
        this.libDir = libDir;
    }

    /**
     * 判断文件名是否为受信的驱动包。
     *
     * <p>匹配规则：以受信前缀开头且以 .jar 结尾。
     */
    private static boolean isTrustedDriverFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".jar")) {
            return false;
        }
        for (String prefix : TRUSTED_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 已加载的驱动主类列表。 */
    public List<String> getLoadedDrivers() {
        return new ArrayList<String>(loaded);
    }

    /** 指定驱动类是否已加载。 */
    public boolean isLoaded(String mainClass) {
        return mainClass != null && loaded.contains(mainClass);
    }

    /**
     * 加载 lib 目录下的所有 jar 并注册其中的 JDBC 驱动。
     *
     * @return 本次新加载的驱动数量
     */
    public int loadAll() {
        if (libDir == null || !libDir.isDirectory()) {
            Debug.log(Debug.GENERAL, "驱动目录不存在: {}", libDir);
            return 0;
        }

        File[] files = libDir.listFiles();
        if (files == null || files.length == 0) {
            return 0;
        }

        List<URL> urls = new ArrayList<URL>();
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            String lower = f.getName().toLowerCase();
            if (!lower.endsWith(".jar")) {
                // 清理上次中断留下的临时文件
                if (lower.endsWith(".tmp")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                continue;
            }

            // ---- 安全：只加载已知的数据库驱动 jar ----
            // 动态类加载等于代码执行，因此不能盲目加载 lib/ 下的任意 jar。
            // 只接受文件名匹配受信列表的文件，其余一律跳过并告警。
            if (!isTrustedDriverFile(f.getName())) {
                logger.warning("跳过未受信的驱动文件（出于安全考虑不加载）: "
                        + f.getName());
                continue;
            }

            try {
                urls.add(f.toURI().toURL());
            } catch (Throwable t) {
                Debug.error(Debug.GENERAL, "无法解析驱动路径: " + f, t);
            }
        }

        if (urls.isEmpty()) {
            return 0;
        }

        try {
            // 父加载器使用插件类加载器，保证 java.sql.* 可见
            ClassLoader parent = getClass().getClassLoader();
            URLClassLoader newLoader = new URLClassLoader(
                    urls.toArray(new URL[0]), parent);
            if (loader != null) {
                closeQuietly(loader);
            }
            loader = newLoader;
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "创建驱动类加载器失败", t);
            return 0;
        }

        return registerKnownDrivers();
    }

    /**
     * 尝试注册已知的 JDBC 驱动类。
     *
     * <p>使用 Class.forName(name, true, loader) 触发静态块，
     * 大多数 JDBC 驱动会在静态块里调用 DriverManager.registerDriver。
     */
    private int registerKnownDrivers() {
        String[] candidates = new String[]{
                "com.mysql.cj.jdbc.Driver",
                "com.mysql.jdbc.Driver",
                "org.sqlite.JDBC",
                "org.mariadb.jdbc.Driver"
        };

        int count = 0;
        for (String className : candidates) {
            if (loaded.contains(className) || inherited.contains(className)) {
                continue;
            }
            Class<?> clazz = loadClassQuietly(className);
            if (clazz == null) {
                continue;
            }
            // 关键：判断这个类到底是谁加载的。
            // 只有 getClassLoader() == loader 才是真正来自 lib/ 的 jar；
            // 否则是父加载器（服务端自带或别的插件）提供的。
            boolean fromLib = (clazz.getClassLoader() == loader);
            if (fromLib) {
                loaded.add(className);
                registerShim(clazz, className);
                count++;
                Debug.log(Debug.GENERAL, "驱动已从 lib/ 注册: {}", className);
            } else {
                inherited.add(className);
                Debug.log(Debug.GENERAL,
                        "驱动来自服务端（非 lib/）: {}（类加载器 {}）",
                        className, clazz.getClassLoader());
            }
        }
        return count;
    }

    /**
     * 尝试加载驱动类（只加载，不注册包装器）。
     *
     * @return 加载成功返回 Class，失败返回 null
     */
    private Class<?> loadClassQuietly(String className) {
        try {
            Class<?> clazz = Class.forName(className, true, loader);
            if (!Driver.class.isAssignableFrom(clazz)) {
                return null;
            }
            return clazz;
        } catch (ClassNotFoundException e) {
            // 该驱动不在 lib 目录里，属正常情况
            return null;
        } catch (Throwable t) {
            Debug.error(Debug.GENERAL, "加载驱动失败: " + className, t);
            return null;
        }
    }

    /**
     * 为驱动类注册一个对插件类加载器可见的包装器。
     *
     * <p>为什么必须包装（实测结论）：
     * {@code DriverManager.getDrivers()} 会按<b>调用方类加载器</b>过滤驱动。
     * 真实驱动类由本类的子 URLClassLoader 加载，插件类加载器看不到它，
     * 因此从插件代码里调用 {@code DriverManager.getConnection()} 会报
     * "No suitable driver"。包装器 {@link DriverShim} 的类来自插件类加载器，
     * 对 DriverManager 可见，由它把调用转发给真实驱动即可绕过该限制。
     */
    private void registerShim(Class<?> clazz, String className) {
        try {
            Driver d = (Driver) clazz.getDeclaredConstructor().newInstance();
            Driver shim = new DriverShim(d);
            DriverManager.registerDriver(shim);
            registered.add(shim);
        } catch (Throwable t) {
            // 实例化失败时不影响后续（真实驱动可能已自我注册）
            Debug.log(Debug.GENERAL, "驱动包装注册失败: {} - {}",
                    className, t.getMessage());
        }
    }

    /**
     * 检查某个驱动是否已注册到 DriverManager，避免重复注册。
     */
    private static boolean isDriverRegistered(Driver driver) {
        try {
            java.util.Enumeration<Driver> it = DriverManager.getDrivers();
            while (it.hasMoreElements()) {
                Driver d = it.nextElement();
                if (d.getClass().getName().equals(driver.getClass().getName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 探测失败时按未注册处理
        }
        return false;
    }

    /**
     * 判断某个驱动类当前**能否被解析**（不触发初始化）。
     *
     * <p><b>注意语义</b>：本方法在 {@code lib/} 为空时会回退到插件类加载器，
     * 因此**服务端自带的驱动也算"可用"** —— 例如 Paper 1.18+ 自带
     * MySQL 驱动，会走服务端类加载器被解析到。
     *
     * <p>因此本方法适合回答"我能不能拿到连接"，
     * 但**不适合**用来判断"是否需要下载驱动"。
     * 后者请用 {@link #isSelfLoaded(String)}。
     *
     * @param mainClass 驱动主类名
     */
    public boolean isAvailable(String mainClass) {
        if (mainClass == null) {
            return false;
        }
        try {
            Class.forName(mainClass, false, loader != null ? loader : getClass().getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 判断某个驱动是否**由本插件自己加载**（即来自 {@code lib/} 目录）。
     *
     * <h3>为什么需要这个方法</h3>
     * 早期 {@code ensureDriver()} 用 {@link #isAvailable(String)} 判断"驱动是否就绪"，
     * 但该方法在没有本地驱动时会回退到插件类加载器，**服务端自带的驱动也算命中**。
     *
     * <p>后果（用户实测遇到）：服务器用 Paper 1.21（自带 MySQL 驱动），
     * {@code lib/} 明明是空的，插件却判定"驱动已就绪" → 跳过下载 →
     * 用户看不到任何下载日志，也没法验证下载功能是否正常。
     * 一旦换到不带该驱动的核心，插件就直接连不上数据库。
     *
     * <p>本方法只认"自己加载的"，从而保证：
     * 需求要求的独立驱动下载模块**确实被走到**，
     * {@code lib/} 下会真实落盘一份驱动，不依赖服务端碰巧带了什么。
     *
     * @param mainClass 驱动主类名
     * @return 是否由本插件的类加载器加载
     */
    public boolean isSelfLoaded(String mainClass) {
        return mainClass != null && loaded.contains(mainClass);
    }

    /**
     * 判断驱动文件是否已存在于 lib 目录（不加载、不联网）。
     *
     * @param fileName 驱动 jar 文件名
     */
    public boolean isFilePresent(String fileName) {
        if (fileName == null || libDir == null) {
            return false;
        }
        File f = new File(libDir, fileName);
        return f.isFile() && f.length() > 0;
    }

    /**
     * 该驱动是否由**服务端或其它插件**提供（而非 lib/）。
     *
     * <p>用于在"下载失败但服务端自带"时给出准确提示，
     * 而不是误以为驱动已正确安装到 lib/。
     */
    public boolean isInherited(String mainClass) {
        return mainClass != null && inherited.contains(mainClass);
    }

    /**
     * 关闭类加载器，释放 jar 文件句柄并注销驱动。
     *
     * <p>为何必须注销：{@link DriverManager} 会永久持有注册的驱动实例，
     * 进而持有其 Class 对象与整个 URLClassLoader。若不注销，
     * 每次重载都会累积一份，最终导致 Metaspace 内存泄漏。
     *
     * <p>在插件卸载/重载时调用，同时也避免 Windows 上文件被占用无法替换。
     */
    public void close() {
        // ---- 先注销驱动，断开 DriverManager 对类加载器的强引用 ----
        for (Driver d : registered) {
            try {
                DriverManager.deregisterDriver(d);
            } catch (Throwable ignored) {
                // 忽略单个注销失败
            }
        }
        registered.clear();

        if (loader != null) {
            closeQuietly(loader);
            loader = null;
        }
        loaded.clear();
        inherited.clear();
    }

    private static void closeQuietly(URLClassLoader l) {
        if (l == null) {
            return;
        }
        try {
            l.close();
        } catch (Throwable ignored) {
            // 忽略关闭异常
        }
    }

    /**
     * 驱动包装器。
     *
     * <p>为何需要：某些驱动（尤其 SQLite）由独立的 URLClassLoader 加载，
     * 而 DriverManager 只允许"调用方类加载器可见"的驱动被使用。
     * 套一层本插件类加载器创建的包装对象可以绕过这个限制。
     */
    private static final class DriverShim implements Driver {
        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.sql.Connection connect(String url, java.util.Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info)
                throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            try {
                return delegate.getParentLogger();
            } catch (Throwable t) {
                throw new java.sql.SQLFeatureNotSupportedException("驱动不支持 getParentLogger");
            }
        }
    }
}
