package com.ncnick.driver;

/**
 * 数据库驱动坐标定义。
 *
 * <p>从配置文件 {@code driver.artifacts} 读取，用于构造下载地址与加载类。
 */
public final class DriverArtifact {

    private final String key;
    private final String group;
    private final String artifact;
    private final String version;
    private final String fileName;
    private final String mainClass;

    public DriverArtifact(String key, String group, String artifact, String version,
                          String fileName, String mainClass) {
        this.key = key;
        this.group = group;
        this.artifact = artifact;
        this.version = version;
        this.fileName = fileName;
        this.mainClass = mainClass;
    }

    /** 配置中的键名，如 "mysql" / "sqlite"。 */
    public String getKey() {
        return key;
    }

    public String getGroup() {
        return group;
    }

    public String getArtifact() {
        return artifact;
    }

    public String getVersion() {
        return version;
    }

    /** 本地文件名与仓库中的文件名。 */
    public String getFileName() {
        return fileName;
    }

    /** JDBC 驱动主类全限定名。 */
    public String getMainClass() {
        return mainClass;
    }

    @Override
    public String toString() {
        return group + ":" + artifact + ":" + version;
    }
}
