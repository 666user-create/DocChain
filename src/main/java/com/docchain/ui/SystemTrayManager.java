package com.docchain.ui;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Font;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * 系统托盘管理器 — 提供「Open Panel / Exit」右键菜单（ASCII 标签，原因见菜单构造处注释）。
 */
public class SystemTrayManager {

    private static final Logger log = LoggerFactory.getLogger(SystemTrayManager.class);

    private final MainPanelController mainPanel;

    private TrayIcon trayIcon;
    private SystemTray tray;

    public SystemTrayManager(MainPanelController mainPanel) {
        this.mainPanel = mainPanel;
    }

    public void show() {
        // 关掉面板后应用继续驻留托盘（而不是因无窗口而退出）
        Platform.setImplicitExit(false);

        if (!SystemTray.isSupported()) {
            log.warn("System tray not supported, opening main panel directly");
            Platform.runLater(mainPanel::show);
            return;
        }

        tray = SystemTray.getSystemTray();

        BufferedImage icon = loadTrayIcon();
        trayIcon = new TrayIcon(icon, "DocChain - 文档版本管理");
        trayIcon.setImageAutoSize(true);

        PopupMenu menu = new PopupMenu();
        // 菜单标签保持 ASCII：部分 Windows 的 GDI 字体替换失效时，AWT 弹出菜单（GDI 路径）
        // 渲染中文会显示豆腐块，且 Java 侧设置任何字体都绕不开（验证见 qa/font-render-test.png）；
        // JavaFX/系统控件走 DirectWrite 路径不受影响，所以主面板仍可放心使用中文。
        MenuItem openItem = new MenuItem("Open Panel");
        openItem.addActionListener(e -> Platform.runLater(mainPanel::show));

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> exit());

        menu.add(openItem);
        menu.addSeparator();
        menu.add(exitItem);

        trayIcon.setPopupMenu(menu);
        trayIcon.addActionListener(e -> Platform.runLater(mainPanel::show));

        try {
            tray.add(trayIcon);
            log.info("DocChain tray icon displayed");
        } catch (AWTException e) {
            log.error("Failed to add tray icon", e);
            Platform.runLater(mainPanel::show);
        }
    }

    private void exit() {
        log.info("DocChain exiting...");
        if (tray != null && trayIcon != null) {
            tray.remove(trayIcon);
        }
        Platform.exit();
        System.exit(0);
    }

    private BufferedImage loadTrayIcon() {
        try (InputStream is = getClass().getResourceAsStream("/icons/tray-icon.png")) {
            if (is != null) return ImageIO.read(is);
        } catch (IOException ignored) {}

        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(new java.awt.Color(74, 144, 226));
        g.fillRoundRect(0, 0, 16, 16, 5, 5);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.drawString("D", 5, 12);
        g.dispose();
        return img;
    }
}
