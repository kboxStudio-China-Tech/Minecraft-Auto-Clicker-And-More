package mcac.client;

import com.formdev.flatlaf.FlatLightLaf;
import mcac.client.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // Java 9+ 目标进程由目标 JVM 自己的 java 附加（见 Attach.inject），助手模式不初始化界面
        if (args.length >= 3 && "--attach-helper".equals(args[0])) {
            mcac.client.net.Attach.helper(args);
            return;
        }
        // 提权重启时带上目标 PID，新实例启动后自动注入
        final int autoPid = intArg(args, "--inject-pid");
        Toolchain.ensure();
        final Settings settings = Settings.load();

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                FlatLightLaf.setup();
                theme();
                MainFrame frame = new MainFrame(settings);
                frame.setVisible(true);
                if (autoPid > 0) {
                    frame.injectPid(autoPid);
                }
            }
        });
    }

    private static int intArg(String[] args, String key) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (key.equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (Throwable ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static void theme() {
        String font = "Microsoft YaHei UI";
        boolean has = false;
        for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if (font.equals(f)) {
                has = true;
                break;
            }
        }
        if (has) {
            Font base = new Font(font, Font.PLAIN, 13);
            UIManager.put("defaultFont", base);
        }
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("ProgressBar.arc", 8);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.accentColor", new Color(0x2563EB));
        UIManager.put("Component.borderColor", new Color(0xE2E8F0));
        UIManager.put("Panel.background", Color.WHITE);
        UIManager.put("TabbedPane.selectedBackground", Color.WHITE);
        UIManager.put("TabbedPane.hoverColor", new Color(0xF1F5F9));
        UIManager.put("ScrollBar.thumbArc", 8);
        UIManager.put("ScrollBar.width", 10);
    }
}
