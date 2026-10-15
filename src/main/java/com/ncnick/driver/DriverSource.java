package com.ncnick.driver;

/**
 * 驱动下载源定义。
 */
public final class DriverSource {

    private final String name;
    private final String url;

    public DriverSource(String name, String url) {
        this.name = name;
        this.url = url;
    }

    /** 源名称，用于日志与提示。 */
    public String getName() {
        return name;
    }

    /** 仓库根地址，例如 https://maven.aliyun.com/repository/public */
    public String getUrl() {
        return url;
    }

    @Override
    public String toString() {
        return name + "(" + url + ")";
    }
}
