package mcac.client.ui;

import mcac.client.Keys;
import mcac.client.win.Win32;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** 白色 / 极简风格的控件工厂。 */
public final class Ui {

    public static final Color BG = new Color(0xF6F8FB);
    public static final Color CARD = Color.WHITE;
    public static final Color BORDER = new Color(0xE2E8F0);
    public static final Color TEXT = new Color(0x0F172A);
    public static final Color MUTED = new Color(0x64748B);
    public static final Color ACCENT = new Color(0x2563EB);
    public static final Color OK = new Color(0x16A34A);
    public static final Font BODY = new Font("Microsoft YaHei UI", Font.PLAIN, 13);

    public interface Syncable {
        void sync();
    }

    private Ui() {
    }

    public static JPanel page() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BG);
        p.setBorder(new EmptyBorder(14, 14, 14, 14));
        return p;
    }

    public static JPanel card(String title, String subtitle) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(CARD);
        Border line = BorderFactory.createLineBorder(BORDER, 1, true);
        p.setBorder(BorderFactory.createCompoundBorder(line, new EmptyBorder(14, 14, 12, 14)));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel t = new JLabel(title);
        t.setFont(BODY.deriveFont(Font.BOLD, 14.0F));
        t.setForeground(TEXT);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            JLabel s = new JLabel(subtitle);
            s.setFont(BODY.deriveFont(12.0F));
            s.setForeground(MUTED);
            s.setAlignmentX(Component.LEFT_ALIGNMENT);
            s.setBorder(new EmptyBorder(4, 0, 0, 0));
            p.add(s);
        }
        p.add(Box.createVerticalStrut(10));
        return p;
    }

    public static void add(JPanel card, JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(c);
        card.add(Box.createVerticalStrut(4));
    }

    public static Component gap(int h) {
        return Box.createVerticalStrut(h);
    }

    public static JLabel hint(String text) {
        JLabel l = new JLabel("<html><body style='width:380px'>" + text + "</body></html>");
        l.setFont(BODY.deriveFont(11.5F));
        l.setForeground(MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(new EmptyBorder(2, 0, 4, 0));
        return l;
    }

    public static JScrollPaneScroll scroll(Component view) {
        return new JScrollPaneScroll(view);
    }

    /** 竖向滚动的简单封装。 */
    public static final class JScrollPaneScroll extends javax.swing.JScrollPane {
        JScrollPaneScroll(Component view) {
            super(view, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER);
            setBorder(null);
            getViewport().setBackground(BG);
            setBackground(BG);
            getVerticalScrollBar().setUnitIncrement(18);
        }
    }

    /** 滑块行：标题 + 数值 + 滑条。 */
    public static final class Slider extends JPanel implements Syncable {
        private final JSlider slider = new JSlider(0, 1000, 500);
        private final JLabel valueLabel = new JLabel();
        private final double min;
        private final double max;
        private final int decimals;
        private final String suffix;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;
        private boolean updating;

        public Slider(String name, double min, double max, int decimals, String suffix,
                      DoubleSupplier getter, DoubleConsumer setter) {
            this.min = min;
            this.max = max;
            this.decimals = decimals;
            this.suffix = suffix;
            this.getter = getter;
            this.setter = setter;

            setLayout(new BorderLayout(0, 2));
            setOpaque(false);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel top = new JPanel(new BorderLayout(8, 0));
            top.setOpaque(false);
            JLabel nameLabel = new JLabel(name);
            nameLabel.setForeground(TEXT);
            nameLabel.setFont(BODY);
            valueLabel.setForeground(ACCENT);
            valueLabel.setFont(BODY.deriveFont(Font.BOLD, 12.5F));
            valueLabel.setHorizontalAlignment(JLabel.RIGHT);
            valueLabel.setPreferredSize(new Dimension(78, 18));
            top.add(nameLabel, BorderLayout.WEST);
            top.add(valueLabel, BorderLayout.EAST);

            slider.setOpaque(false);
            slider.setFocusable(false);
            slider.addChangeListener(e -> {
                if (updating) {
                    return;
                }
                double v = min + (max - min) * (slider.getValue() / 1000.0D);
                v = round(v);
                setter.accept(v);
                valueLabel.setText(format(v));
            });

            add(top, BorderLayout.NORTH);
            add(slider, BorderLayout.CENTER);
            sync();
        }

        private double round(double v) {
            double p = Math.pow(10.0D, decimals);
            return Math.round(v * p) / p;
        }

        private String format(double v) {
            return String.format("%." + decimals + "f", v) + suffix;
        }

        public void sync() {
            double v = Math.max(min, Math.min(max, getter.getAsDouble()));
            updating = true;
            slider.setValue((int) Math.round((v - min) / (max - min) * 1000.0D));
            updating = false;
            valueLabel.setText(format(v));
        }
    }

    public static final class Check extends JPanel implements Syncable {
        private final JCheckBox box;
        private final Supplier<Boolean> getter;

        public Check(String text, Supplier<Boolean> getter, Consumer<Boolean> setter) {
            this.getter = getter;
            setLayout(new BorderLayout());
            setOpaque(false);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            box = new JCheckBox(text);
            box.setOpaque(false);
            box.setFont(BODY);
            box.setForeground(TEXT);
            box.setFocusable(false);
            box.addActionListener(e -> setter.accept(box.isSelected()));
            add(box, BorderLayout.WEST);
            sync();
        }

        public void sync() {
            box.setSelected(Boolean.TRUE.equals(getter.get()));
        }
    }

    public static final class KeyRow extends JPanel implements Syncable {
        private final JButton button = new JButton();
        private final IntSupplier getter;

        public KeyRow(String name, IntSupplier getter, IntConsumer setter) {
            this.getter = getter;
            setLayout(new BorderLayout(10, 0));
            setOpaque(false);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel label = new JLabel(name);
            label.setForeground(TEXT);
            label.setFont(BODY);

            button.setFont(BODY.deriveFont(12.5F));
            button.setFocusable(false);
            button.putClientProperty("JButton.buttonType", "roundRect");
            button.setPreferredSize(new Dimension(148, 28));
            button.addActionListener(e -> {
                Integer vk = captureKey(button);
                if (vk != null) {
                    setter.accept(vk);
                    sync();
                }
            });

            add(label, BorderLayout.WEST);
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            right.setOpaque(false);
            right.add(button);
            add(right, BorderLayout.EAST);
            sync();
        }

        public void sync() {
            button.setText(Keys.name(getter.getAsInt()));
        }
    }

    public static final class Choice extends JPanel implements Syncable {
        private final JComboBox<String> box = new JComboBox<String>();
        private final String[] values;
        private final Supplier<String> getter;
        private final Consumer<String> setter;
        private boolean updating;

        public Choice(String name, String[] labels, String[] values,
                      Supplier<String> getter, Consumer<String> setter) {
            this.values = values;
            this.getter = getter;
            this.setter = setter;
            setLayout(new BorderLayout(10, 0));
            setOpaque(false);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel label = new JLabel(name);
            label.setForeground(TEXT);
            label.setFont(BODY);
            for (String l : labels) {
                box.addItem(l);
            }
            box.setFont(BODY.deriveFont(12.5F));
            box.setFocusable(false);
            box.setPreferredSize(new Dimension(148, 28));
            box.addActionListener(e -> {
                if (updating) {
                    return;
                }
                int i = box.getSelectedIndex();
                if (i >= 0 && i < this.values.length) {
                    setter.accept(this.values[i]);
                }
            });
            add(label, BorderLayout.WEST);
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            right.setOpaque(false);
            right.add(box);
            add(right, BorderLayout.EAST);
            sync();
        }

        public void sync() {
            String v = getter.get();
            updating = true;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(v)) {
                    box.setSelectedIndex(i);
                    break;
                }
            }
            updating = false;
        }
    }

    /** 顶部状态胶囊。 */
    public static final class Chip extends JLabel {
        private Color fill = new Color(0xEFF6FF);

        public Chip() {
            setOpaque(false);
            setFont(BODY.deriveFont(12.0F));
            setForeground(ACCENT);
            setBorder(new EmptyBorder(6, 12, 6, 12));
        }

        public void paint(Color fillColor, Color textColor) {
            this.fill = fillColor;
            setForeground(textColor);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 绑定按键：捕获下一个按下的键。 */
    public static Integer captureKey(Component parent) {
        final JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
                "绑定按键", Dialog.ModalityType.APPLICATION_MODAL);
        final Integer[] result = {null};
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(CARD);
        panel.setBorder(new EmptyBorder(26, 30, 26, 30));
        JLabel label = new JLabel("请按下要绑定的按键或鼠标按键…（Esc 取消）");
        label.setFont(BODY.deriveFont(14.0F));
        label.setForeground(TEXT);
        panel.add(label, BorderLayout.CENTER);
        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setResizable(false);

        // 鼠标按键同样可以绑定：记录弹窗时已按下的键，并延迟一小段时间再开始捕获，
        // 避免把“点击绑定按钮”的那一次左键误当成要绑定的按键。
        final long openAt = System.currentTimeMillis();
        final Set<Integer> heldAtOpen = new HashSet<Integer>();
        for (int vk = 1; vk <= 6; vk++) {
            if (Win32.down(vk)) {
                heldAtOpen.add(Integer.valueOf(vk));
            }
        }
        final Timer timer = new Timer(20, null);
        timer.addActionListener(e -> {
            if (System.currentTimeMillis() - openAt < 280L) {
                return;
            }
            for (int vk = 1; vk < 256; vk++) {
                if (vk == 0x00) {
                    continue;
                }
                if (heldAtOpen.contains(Integer.valueOf(vk)) && Win32.down(vk)) {
                    continue;
                }
                if (Win32.down(vk)) {
                    if (vk != 0x1B) {
                        result[0] = Integer.valueOf(vk);
                    }
                    timer.stop();
                    dialog.dispose();
                    return;
                }
            }
        });
        timer.start();
        dialog.setVisible(true);
        timer.stop();
        return result[0];
    }
}
