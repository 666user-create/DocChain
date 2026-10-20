import java.awt.*;

/**
 * 托盘菜单字体验证探针 v2：测试"Microsoft YaHei UI"物理字体能否让
 * AWT 菜单正确渲染中文（替代默认字体的豆腐块问题），并输出字体诊断。
 */
public class TrayMenuProbe {
    public static void main(String[] args) throws Exception {
        Font def = new Frame().getFont();
        System.out.println("default frame font = " + def);
        System.out.println("default canDisplay CJK = " + (def != null && def.canDisplay('打')));

        Font msyh = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
        System.out.println("yahei font = " + msyh.getName() + " canDisplay CJK = " + msyh.canDisplay('打'));

        Frame f = new Frame();
        f.setUndecorated(true);
        f.setBounds(960, 380, 1, 1);
        PopupMenu m = new PopupMenu();
        MenuItem open = new MenuItem("打开主面板");
        MenuItem exit = new MenuItem("退出");
        open.setFont(msyh);
        exit.setFont(msyh);
        m.add(open);
        m.addSeparator();
        m.add(exit);
        f.add(m);
        f.setVisible(true);
        m.show(f, 0, 0);
        System.out.println("MENU_SHOWN");
        Thread.sleep(4000);
        f.dispose();
    }
}
