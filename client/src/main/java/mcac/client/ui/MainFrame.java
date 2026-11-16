package mcac.client.ui;

import mcac.client.Keys;
import mcac.client.Settings;
import mcac.client.core.Controller;
import mcac.client.win.Win32;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public final class MainFrame extends JFrame {

    private final Settings settings;
    private final Controller controller;
    private final List<Runnable> syncers = new ArrayList<Runnable>();

    private Timer saveTimer;
    private boolean dirty;

    private Ui.Chip statusChip;
    private JButton injectButton;
    private JLabel infoConn;
    private JLabel infoInject;

    public MainFrame(Settings settings) {
        super("MCAC · 我的世界外置助手");
        this.settings = settings;
        this.controller = new Controller(settings);

        this.saveTimer = new Timer(700, e -> {
            if (dirty) {
                dirty = false;
                settings.save();
            }
        });
        this.saveTimer.setRepeats(false);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                controller.shutdown();
                settings.save();
                dispose();
                System.exit(0);
            }
        });
        setContentPane(build());
        setSize(620, 800);
        setMinimumSize(new Dimension(560, 620));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Ui.BG);

        controller.onUiState = new Runnable() {
            public void run() {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        syncAll();
                    }
                });
            }
        };
        controller.start();

        Timer refresh = new Timer(400, e -> refreshStatus());
        refresh.setRepeats(true);
        refresh.start();
        refreshStatus();
    }

    /** 提权重启后由启动参数指定目标 PID，自动注入。 */
    public void injectPid(int pid) {
        controller.injectPidAsync(pid);
    }

    private JComponent build() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.BG);
        root.add(buildHeader(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Ui.BODY);
        tabs.setBackground(Ui.BG);
        tabs.addTab("连点器", Ui.scroll(buildClickerTab()));
        tabs.addTab("瞄准", Ui.scroll(buildAimTab()));
        tabs.addTab("辅助", Ui.scroll(buildAuxTab()));
        tabs.addTab("其他", Ui.scroll(buildMiscTab()));
        root.add(tabs, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        return root;
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(Ui.CARD);
        header.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, Ui.BORDER),
                new EmptyBorder(16, 20, 16, 20)));

        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setOpaque(false);
        JLabel logo = new JLabel("MCAC");
        logo.setFont(Ui.BODY.deriveFont(Font.BOLD, 19.0F));
        logo.setForeground(Ui.TEXT);
        JLabel sub = new JLabel("Minecraft 1.8.x · 只读注入 · 外置控制");
        sub.setFont(Ui.BODY.deriveFont(11.0F));
        sub.setForeground(Ui.MUTED);
        titles.add(logo);
        titles.add(sub);
        header.add(titles, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        statusChip = new Ui.Chip();
        statusChip.setText("未注入");
        right.add(statusChip);

        injectButton = new JButton("注入游戏");
        injectButton.setFont(Ui.BODY.deriveFont(13.0F));
        injectButton.setForeground(Color.WHITE);
        injectButton.setBackground(Ui.ACCENT);
        injectButton.setFocusPainted(false);
        injectButton.setBorder(new EmptyBorder(8, 16, 8, 16));
        injectButton.putClientProperty("JButton.buttonType", "roundRect");
        injectButton.addActionListener(e -> controller.injectAsync());
        right.add(injectButton);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setBackground(Ui.CARD);
        footer.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, Ui.BORDER),
                new EmptyBorder(9, 20, 9, 20)));
        JLabel hint = new JLabel("进入世界后生效 · 停用全部模块请按 " + Keys.name(settings.panicKey));
        hint.setFont(Ui.BODY.deriveFont(11.5F));
        hint.setForeground(Ui.MUTED);
        footer.add(hint, BorderLayout.WEST);
        return footer;
    }

    private JPanel buildClickerTab() {
        JPanel page = Ui.page();
        page.add(buildClickerCard("左键连点器", "按住左键时以设定 CPS 连续点击", settings.left));
        page.add(Ui.gap(12));
        page.add(buildClickerCard("右键连点器", "按住右键时连续使用物品", settings.right));
        return page;
    }

    private JPanel buildClickerCard(String title, String subtitle, final Settings.Clicker c) {
        JPanel card = Ui.card(title, subtitle);

        Ui.add(card, track(new Ui.Check("启用模块", () -> c.enabled, v -> {
            c.enabled = v;
            changed();
        })));
        Ui.add(card, track(new Ui.KeyRow("触发按键", () -> c.key, v -> {
            c.key = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Check("仅按住触发键时工作（关闭则按键切换）", () -> c.hold, v -> {
            c.hold = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Check("仅在进入世界后生效", () -> c.worldOnly, v -> {
            c.worldOnly = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Check("仅在手持方块或剑时生效", () -> c.itemOnly, v -> {
            c.itemOnly = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Slider("最低 CPS", 1.0D, 30.0D, 0, "", () -> c.cpsMin, v -> {
            c.cpsMin = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Slider("最高 CPS", 1.0D, 30.0D, 0, "", () -> c.cpsMax, v -> {
            c.cpsMax = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Choice("点击曲线", new String[]{"均匀分布", "高斯分布", "偏态分布"},
                new String[]{"uniform", "gauss", "beta"}, () -> c.curve, v -> {
                    c.curve = v;
                    changed();
                })));
        Ui.add(card, track(new Ui.Slider("定时抖动", 0.0D, 30.0D, 0, " ms", () -> c.jitterMs * 1.0D, v -> {
            c.jitterMs = (int) Math.round(v);
            changed();
        })));
        Ui.add(card, track(new Ui.Slider("准星抖动", 0.0D, 6.0D, 1, " px", () -> c.jitterPx, v -> {
            c.jitterPx = v;
            changed();
        })));
        return card;
    }

    private JPanel buildAimTab() {
        JPanel page = Ui.page();
        Settings.Aim a = settings.aim;
        JPanel card = Ui.card("瞄准辅助", "读取本地玩家与周边实体坐标，平滑移动到设定范围");

        Ui.add(card, track(new Ui.Check("启用模块", () -> a.enabled, v -> {
            a.enabled = v;
            changed();
        })));
        Ui.add(card, track(new Ui.KeyRow("触发按键", () -> a.key, v -> {
            a.key = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Choice("触发模式", new String[]{"按住触发", "按键切换"},
                new String[]{"hold", "toggle"}, () -> a.hold ? "hold" : "toggle", v -> {
                    a.hold = "hold".equals(v);
                    changed();
                })));
        Ui.add(card, track(new Ui.Slider("视野角度", 10.0D, 360.0D, 0, " °", () -> a.fov, v -> {
            a.fov = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Slider("锁定距离", 2.0D, 8.0D, 1, " 格", () -> a.range, v -> {
            a.range = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Slider("平滑度", 1.0D, 20.0D, 1, "", () -> a.smooth, v -> {
            a.smooth = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Slider("最大速度", 30.0D, 3000.0D, 0, " °/s", () -> a.speed, v -> {
            a.speed = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Slider("精确度", 0.05D, 5.0D, 2, " °", () -> a.tolerance, v -> {
            a.tolerance = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Choice("瞄准位置", new String[]{"头部", "身体", "就近"},
                new String[]{"head", "body", "nearest"}, () -> a.point, v -> {
                    a.point = v;
                    changed();
                })));
        Ui.add(card, track(new Ui.Check("仅瞄准生物（高度 ≥ 1 格）", () -> a.onlyLiving, v -> {
            a.onlyLiving = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Check("模拟人手抖动", () -> a.noise, v -> {
            a.noise = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Slider("游戏内灵敏度", 0.0D, 1.0D, 2, "", () -> a.sensitivity, v -> {
            a.sensitivity = v;
            changed();
        })));
        Ui.add(card, track(new Ui.Slider("灵敏度校准", 0.5D, 2.0D, 2, "x", () -> a.gain, v -> {
            a.gain = v;
            changed();
        })));
        card.add(Ui.hint("“游戏内灵敏度”必须与 Minecraft 设置中的鼠标灵敏度一致；若准星总是过冲或不足，微调“灵敏度校准”。"));
        page.add(card);
        return page;
    }

    private JPanel buildAuxTab() {
        JPanel page = Ui.page();
        Settings.Aux aux = settings.aux;

        JPanel eagle = Ui.card("鹰眼（安全行走 / 蹲搭）", "站在方块边缘且前方即将踩空时自动蹲下");
        Ui.add(eagle, track(new Ui.Check("启用鹰眼", () -> aux.eagle, v -> {
            aux.eagle = v;
            changed();
        })));
        Ui.add(eagle, track(new Ui.Slider("下蹲触发距离", 0.10D, 1.50D, 2, " 格",
                () -> aux.eagleEdgeDist, v -> {
                    aux.eagleEdgeDist = v;
                    changed();
                })));
        eagle.add(Ui.hint("行走时检测运动方向前方设定距离（0.56 格即原版半格）；"
                + "脚下为实地、前方悬空时自动蹲下，离开边缘立即站起；"
                + "若你自己已按住 Shift，则不接管。"));
        page.add(eagle);
        page.add(Ui.gap(12));

        JPanel jump = Ui.card("受击跳跃", "仅在其他玩家攻击造成击退时自动补跳，降低击退位移");
        Ui.add(jump, track(new Ui.Check("启用受击跳跃", () -> aux.jumpReset, v -> {
            aux.jumpReset = v;
            changed();
        })));
        Ui.add(jump, track(new Ui.Slider("触发延迟", 0.0D, 200.0D, 0, " ms", () -> aux.jumpDelay * 1.0D, v -> {
            aux.jumpDelay = (int) Math.round(v);
            changed();
        })));
        jump.add(Ui.hint("建议 20~60ms；延迟过大会在击退位移已产生后才起跳。"
                + "客户端只能收到受伤动画、拿不到伤害来源，这里以“4 格内存在其他玩家”近似判断玩家攻击，"
                + "摔落伤害、生物击退不会触发。"));
        page.add(jump);
        page.add(Ui.gap(12));

        JPanel backtrack = Ui.card("回溯（Backtrack）", "攻击时暂停处理服务端入站数据包，使目标短暂停在旧位置");
        Ui.add(backtrack, track(new Ui.Check("启用回溯", () -> aux.backtrack, v -> {
            aux.backtrack = v;
            changed();
        })));
        Ui.add(backtrack, track(new Ui.Slider("最长回溯时间", 50.0D, 5000.0D, 0, " ms",
                () -> aux.backtrackMaxMs * 1.0D, v -> {
                    aux.backtrackMaxMs = (int) Math.round(v);
                    changed();
                })));
        Ui.add(backtrack, track(new Ui.Check("持续缓慢释放（避免一次性回拉）", () -> aux.backtrackSlow, v -> {
            aux.backtrackSlow = v;
            changed();
        })));
        backtrack.add(Ui.hint("仅在攻击（真实左键或连点器左键输出）时激活，松开攻击即停止缓存；"
                + "达到最长回溯时间后自动结束本轮回溯。"
                + "开启“持续缓慢释放”后，缓存包按约 30 包/秒逐批补发，位置不会瞬间回拉，"
                + "时间越长越容易察觉延迟，建议 150~400ms。"));
        page.add(backtrack);
        page.add(Ui.gap(12));

        JPanel sprint = Ui.card("自动疾跑", "前进时自动按住疾跑键，松开前进或潜行时自动松开");
        Ui.add(sprint, track(new Ui.Check("启用自动疾跑", () -> aux.sprint, v -> {
            aux.sprint = v;
            changed();
        })));
        Ui.add(sprint, track(new Ui.KeyRow("疾跑按键", () -> aux.sprintKey, v -> {
            aux.sprintKey = v;
            changed();
        })));
        sprint.add(Ui.hint("仅在游戏窗口前台且进入世界后生效；按住 W 前进时自动按下疾跑键，"
                + "你已按住 Shift 或自己按住疾跑键时不会接管。"));
        page.add(sprint);
        page.add(Ui.gap(12));

        JPanel panic = Ui.card("紧急停止", "一键关闭全部模块并松开所有模拟按键");
        Ui.add(panic, track(new Ui.KeyRow("停止按键", () -> settings.panicKey, v -> {
            settings.panicKey = v;
            changed();
        })));
        page.add(panic);
        return page;
    }

    private JPanel buildMiscTab() {
        JPanel page = Ui.page();

        JPanel conn = Ui.card("运行状态", null);
        infoConn = new JLabel("-");
        infoInject = new JLabel("-");
        infoConn.setFont(Ui.BODY.deriveFont(12.0F));
        infoConn.setForeground(Ui.TEXT);
        infoInject.setFont(Ui.BODY.deriveFont(12.0F));
        infoInject.setForeground(Ui.TEXT);
        infoConn.setAlignmentX(Component.LEFT_ALIGNMENT);
        infoInject.setAlignmentX(Component.LEFT_ALIGNMENT);
        conn.add(infoConn);
        conn.add(Box.createVerticalStrut(4));
        conn.add(infoInject);
        conn.add(Box.createVerticalStrut(10));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton reInject = new JButton("重新注入");
        reInject.addActionListener(e -> controller.injectAsync());
        JButton log = new JButton("注入日志");
        log.addActionListener(e -> showAgentLog());
        JButton dir = new JButton("配置目录");
        dir.addActionListener(e -> openConfigDir());
        for (JButton b : new JButton[]{reInject, log, dir}) {
            b.setFont(Ui.BODY.deriveFont(12.0F));
            b.putClientProperty("JButton.buttonType", "roundRect");
            b.setFocusable(false);
            buttons.add(b);
        }
        conn.add(buttons);
        page.add(conn);
        page.add(Ui.gap(12));

        JPanel pidCard = Ui.card("PID 注入", "适用于多开、启动器隐藏标题或自动查找失败的情况");
        JPanel pidRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pidRow.setOpaque(false);
        pidRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel pidLabel = new JLabel("进程 PID");
        pidLabel.setFont(Ui.BODY);
        pidLabel.setForeground(Ui.TEXT);
        final JTextField pidField = new JTextField();
        pidField.setFont(Ui.BODY);
        pidField.setPreferredSize(new Dimension(110, 28));
        pidField.putClientProperty("JTextField.placeholderText", "例如 12345");
        JButton byPid = new JButton("按 PID 注入");
        byPid.setFont(Ui.BODY.deriveFont(12.0F));
        byPid.putClientProperty("JButton.buttonType", "roundRect");
        byPid.setFocusable(false);
        byPid.addActionListener(e -> {
            String text = pidField.getText().trim();
            if (text.isEmpty()) {
                controller.injectAsync();
                return;
            }
            try {
                controller.injectPidAsync(Integer.parseInt(text));
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "PID 必须是数字。", "提示", JOptionPane.WARNING_MESSAGE);
            }
        });
        JButton findPid = new JButton("查找游戏 PID");
        findPid.setFont(Ui.BODY.deriveFont(12.0F));
        findPid.putClientProperty("JButton.buttonType", "roundRect");
        findPid.setFocusable(false);
        findPid.addActionListener(e -> {
            int pid = Win32.findGamePid();
            if (pid > 0) {
                pidField.setText(String.valueOf(pid));
            } else {
                JOptionPane.showMessageDialog(this, "未找到 Minecraft 游戏窗口，请手动填写任务管理器中的 javaw.exe PID。",
                        "提示", JOptionPane.WARNING_MESSAGE);
            }
        });
        pidRow.add(pidLabel);
        pidRow.add(pidField);
        pidRow.add(byPid);
        pidRow.add(findPid);
        pidCard.add(pidRow);
        pidCard.add(Ui.hint("在任务管理器“详细信息”页查看游戏进程 PID；"
                + "注入成功后会显示实际探针端口，端口由游戏进程动态分配，多开互不冲突。"));
        page.add(pidCard);
        page.add(Ui.gap(12));

        JPanel about = Ui.card("说明", null);
        about.add(Ui.hint("注入方式为只读字节码注入：只在 LocalPlayer 的 tick 方法头插入一次探针调用，"
                + "不修改任何游戏逻辑、不新增字段或方法，游戏退出即失效。"));
        about.add(Ui.hint("状态“已连接 · 未进入世界”表示探针已注入但玩家还没进入存档；"
                + "进入世界后会自动变成“世界中 · 实体 N · 正常”。"));
        about.add(Ui.hint("若注入后状态一直停留在“已注入 · 等待探针连接”，请点击“注入日志”查看 agent 的定位过程。"));
        about.add(Ui.hint("本工具仅使用 127.0.0.1 本地回环通信，不联网、不上传任何数据。"));
        page.add(about);
        return page;
    }

    private <T extends JComponent> T track(T component) {
        if (component instanceof Ui.Syncable) {
            final Ui.Syncable s = (Ui.Syncable) component;
            syncers.add(new Runnable() {
                public void run() {
                    s.sync();
                }
            });
        }
        return component;
    }

    private void changed() {
        dirty = true;
        if (saveTimer != null) {
            saveTimer.restart();
        }
    }

    private void syncAll() {
        for (Runnable r : syncers) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
        refreshStatus();
    }

    private void refreshStatus() {
        String text = controller.statusText();
        boolean live = controller.live();
        if (statusChip != null) {
            statusChip.setText(text);
            if (live) {
                statusChip.paint(new Color(0xECFDF5), Ui.OK);
            } else if (controller.sensor.connected) {
                statusChip.paint(new Color(0xFFF7ED), new Color(0xB45309));
            } else {
                statusChip.paint(new Color(0xEFF6FF), Ui.ACCENT);
            }
        }
        if (injectButton != null) {
            injectButton.setEnabled(!"正在注入…".equals(controller.injectStatus));
        }
        if (infoConn != null) {
            infoConn.setText("探针连接：" + (controller.sensor.connected
                    ? "已连接 127.0.0.1:" + controller.sensor.port()
                    : "未连接" + (controller.sensor.port() > 0 ? "（端口 " + controller.sensor.port() + "）" : "")));
        }
        if (infoInject != null) {
            infoInject.setText("注入状态：" + controller.injectStatus);
        }
    }

    private void showAgentLog() {
        File f = new File(System.getProperty("java.io.tmpdir"), "mcac-agent.log");
        StringBuilder sb = new StringBuilder();
        if (f.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (Throwable ignored) {
            }
        }
        if (sb.length() == 0) {
            sb.append("暂无日志。请先注入游戏。");
        }
        JTextArea area = new JTextArea(sb.toString());
        area.setEditable(false);
        area.setFont(new Font("Consolas", Font.PLAIN, 12));
        area.setBorder(new EmptyBorder(8, 8, 8, 8));
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(760, 420));
        JOptionPane.showMessageDialog(this, sp, "注入日志", JOptionPane.PLAIN_MESSAGE);
    }

    private void openConfigDir() {
        try {
            File dir = new File(System.getProperty("user.home"), ".mcac");
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            java.awt.Desktop.getDesktop().open(dir);
        } catch (Throwable t) {
            JOptionPane.showMessageDialog(this, "无法打开目录：" + t.getMessage(), "提示",
                    JOptionPane.WARNING_MESSAGE);
        }
    }
}
