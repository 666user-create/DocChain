package com.docchain.util;

import java.nio.file.Path;

/**
 * 平台相关工具方法。
 */
public final class PlatformUtils {

    private PlatformUtils() {}

    /**
     * 获取 DocChain 的应用数据目录（跨平台）。
     * <ul>
     *   <li>Windows: %APPDATA%/DocChain</li>
     *   <li>macOS: ~/Library/Application Support/DocChain</li>
     *   <li>Linux: ~/.local/share/DocChain</li>
     * </ul>
     */
    public static Path getAppDataDir() {
        String os = System.getProperty("os.name").toLowerCase();
        String home = System.getProperty("user.home");

        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            return Path.of(appdata != null ? appdata : home + "/AppData/Roaming", "DocChain");
        } else if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "DocChain");
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            return Path.of(xdg != null ? xdg : home + "/.local/share", "DocChain");
        }
    }

    /**
     * 判断当前运行的操作系统类型。
     */
    public static OS getOS() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return OS.WINDOWS;
        if (os.contains("mac")) return OS.MACOS;
        return OS.LINUX;
    }

    public enum OS { WINDOWS, MACOS, LINUX }
}
