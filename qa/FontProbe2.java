import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 字体问题决定性实验：
 * 1) Graphics2D 直接渲染中文 → 排查 AWT 文字渲染管线本身是否正常
 * 2) 把字体设置在 PopupMenu 对象上（而非 MenuItem 上）再弹出 → 排查菜单 peer 是否忽略项字体
 */
public class FontProbe2 {
    public static void main(String[] args) throws Exception {
        String text = "打开主面板 退出";

        // ── 1) Graphics2D 渲染测试 ──
        BufferedImage img = new BufferedImage(760, 380, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 760, 380);
        g.setColor(Color.BLACK);
        Font[] fonts = {
                new Font(Font.DIALOG, Font.PLAIN, 20),
                new Font("Microsoft YaHei UI", Font.PLAIN, 20),
                new Font("SimSun", Font.PLAIN, 20),
                new Font("Arial", Font.PLAIN, 20),
        };
        int y = 40;
        for (Font f : fonts) {
            g.setFont(f);
            g.drawString(f.getFontName() + " → " + text, 15, y);
            System.out.println("g2d font=" + f.getFontName()
                    + " family=" + f.getFamily()
                    + " canDisplay=" + f.canDisplay('打'));
            y += 80;
        }
        g.dispose();
        ImageIO.write(img, "png", new File("D:/java/DocChain/qa/font-render-test.png"));
        System.out.println("IMAGE_WRITTEN");

        // ── 2) 菜单对象级字体 ──
        Frame f2 = new Frame();
        f2.setUndecorated(true);
        f2.setBounds(960, 360, 1, 1);
        PopupMenu m = new PopupMenu();
        m.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        MenuItem open = new MenuItem("打开主面板");
        MenuItem exit = new MenuItem("退出");
        m.add(open);
        m.addSeparator();
        m.add(exit);
        f2.add(m);
        f2.setVisible(true);
        m.show(f2, 0, 0);
        System.out.println("item font (unset) = " + open.getFont());
        System.out.println("MENU_SHOWN");
        Thread.sleep(4500);
        f2.dispose();
    }
}
