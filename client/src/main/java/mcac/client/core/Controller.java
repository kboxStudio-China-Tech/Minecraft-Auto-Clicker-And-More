package mcac.client.core;

import mcac.client.Keys;
import mcac.client.Settings;
import mcac.client.net.Attach;
import mcac.client.net.SensorClient;
import mcac.client.net.Snapshot;
import mcac.client.win.Win32;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 统一调度：热键、瞄准、连点、鹰眼、受击跳。 */
public final class Controller {

    public final Settings settings;
    public final SensorClient sensor = new SensorClient();

    private final Aim aim = new Aim();
    private final Clicker left = new Clicker(false);
    private final Clicker right = new Clicker(true);
    private final Eagle eagle = new Eagle();
    private final JumpReset jump = new JumpReset();
    private final Sprint sprint = new Sprint();

    private final Map<Integer, Boolean> toggles = new HashMap<Integer, Boolean>();
    private final Set<Integer> keyState = new HashSet<Integer>();

    public volatile String injectStatus = "未注入";
    public volatile boolean injected;
    public volatile Runnable onUiState;

    private volatile boolean running;
    private volatile boolean injecting;
    private long lastLoop = System.nanoTime();
    /** 已下发给游戏桥的回溯命令，避免每个 tick 重复发送。 */
    private String backtrackCmd = "";
    /** 已下发给游戏桥的鹰眼边缘距离命令。 */
    private String edgeCmd = "";

    public Controller(Settings settings) {
        this.settings = settings;
        Win32.startInputHooks();
        this.sensor.start();
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "MCAC-Core");
        t.setDaemon(true);
        t.start();
    }

    public void shutdown() {
        running = false;
        sensor.stop();
        releaseAll();
    }

    public void injectAsync() {
        if (injecting) {
            return;
        }
        int pid = Win32.findGamePid();
        if (pid == 0) {
            injectStatus = "未找到 Minecraft 窗口，可在“其他”页按 PID 注入";
            notifyUi();
            return;
        }
        injectPidAsync(pid);
    }

    /** 按进程 PID 注入（适用于多开、标题被隐藏或自动查找失败）。 */
    public void injectPidAsync(final int pid) {
        if (injecting) {
            return;
        }
        if (pid <= 0) {
            injectStatus = "PID 无效";
            notifyUi();
            return;
        }
        injecting = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                injectStatus = "正在注入 PID " + pid + "…";
                notifyUi();
                try {
                    Attach.Result r = Attach.inject(pid);
                    injected = true;
                    Win32.setGamePid(pid);
                    sensor.setPort(r.port);
                    injectStatus = "已注入 PID " + pid + " · 探针端口 " + r.port;
                } catch (Attach.DeniedException de) {
                    relaunchElevated(pid);
                } catch (Throwable e) {
                    injectStatus = "注入失败: " + shortMsg(e);
                } finally {
                    injecting = false;
                    notifyUi();
                }
            }
        }, "MCAC-Attach");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 目标以管理员运行时，普通权限既无法附加也无法向游戏窗口发送输入，
     * 因此通过 UAC 以管理员身份重启自身，并由新实例自动注入该 PID。
     */
    private void relaunchElevated(final int pid) {
        if (Win32.isElevated()) {
            injectStatus = "注入被拒绝：目标为其他用户或受保护进程";
            notifyUi();
            return;
        }
        File jar = Attach.selfPath();
        String home = System.getProperty("java.home");
        File exe = home == null ? null : new File(home, "bin/javaw.exe");
        if (jar == null || exe == null || !exe.isFile()) {
            injectStatus = "需要管理员权限：请右键以管理员身份运行本工具";
            notifyUi();
            return;
        }
        StringBuilder cp = new StringBuilder(jar.getAbsolutePath());
        File tools = new File(home, "lib/tools.jar");
        if (tools.isFile()) {
            cp.append(File.pathSeparator).append(tools.getAbsolutePath());
        }
        String params = "-Dmcac.relaunched=true -cp \"" + cp + "\" mcac.client.Main --inject-pid " + pid;
        if (Win32.runAsAdmin(exe.getAbsolutePath(), params)) {
            settings.save();
            injectStatus = "目标以管理员运行，已提权重启";
            notifyUi();
            System.exit(0);
            return;
        }
        injectStatus = "已取消提权：目标以管理员运行，需以管理员身份启动本工具";
        notifyUi();
    }

    public String statusText() {
        if (!sensor.connected) {
            return injected ? "已注入 · 等待探针连接" : injectStatus;
        }
        if (!sensor.worldReady) {
            return "已连接 · 未进入世界";
        }
        Snapshot s = sensor.latest;
        long age = System.currentTimeMillis() - sensor.lastFrameAt;
        if (s == null || age > 900L) {
            return "世界中 · 等待数据";
        }
        return "世界中 · 实体 " + s.count + " · 正常";
    }

    public boolean live() {
        return sensor.connected && sensor.worldReady
                && System.currentTimeMillis() - sensor.lastFrameAt < 900L;
    }

    private static String shortMsg(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private void loop() {
        while (running) {
            long t0 = System.nanoTime();
            try {
                tick();
            } catch (Throwable ignored) {
            }
            long spent = (System.nanoTime() - t0) / 1_000_000L;
            try {
                Thread.sleep(Math.max(1L, 3L - spent));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void tick() {
        long now = System.nanoTime();
        double dt = (now - lastLoop) / 1.0E9D;
        lastLoop = now;
        if (dt <= 0.0D || dt > 0.5D) {
            dt = 0.01D;
        }

        boolean focused = Win32.gameFocused();
        Settings.Aim ac = settings.aim;
        Settings.Clicker lc = settings.left;
        Settings.Clicker rc = settings.right;
        Settings.Aux aux = settings.aux;

        if (edge(settings.panicKey)) {
            panic();
        }

        Snapshot s = sensor.latest;
        boolean fresh = s != null && System.currentTimeMillis() - sensor.lastFrameAt < 600L;
        int held = fresh ? s.hold : 0;

        boolean aimOn = ac.enabled && (ac.hold ? Win32.down(ac.key) : toggle(ac.key));
        if (aimOn && focused && fresh) {
            aim.update(dt, ac, s);
        } else {
            aim.reset();
        }

        boolean leftOn = lc.enabled && (lc.hold ? Win32.down(lc.key) : toggle(lc.key));
        left.update(lc, leftOn, focused, sensor.worldReady, held);

        boolean rightOn = rc.enabled && (rc.hold ? Win32.down(rc.key) : toggle(rc.key));
        right.update(rc, rightOn, focused, sensor.worldReady, held);

        eagle.update(aux, s, focused && fresh);
        jump.update(aux, s, focused && fresh);
        sprint.update(aux, focused && sensor.worldReady);
        updateEdge(aux);
        updateBacktrack(aux, focused, leftOn);
    }

    /** 把“距方块边缘多少格下蹲”同步给游戏内的方块探测点。 */
    private void updateEdge(Settings.Aux aux) {
        if (!sensor.connected) {
            edgeCmd = "";
            return;
        }
        double dist = Math.max(0.1D, Math.min(1.5D, aux.eagleEdgeDist));
        String cmd = "E " + String.format(java.util.Locale.ROOT, "%.2f", dist);
        if (cmd.equals(edgeCmd)) {
            return;
        }
        edgeCmd = sensor.send(cmd) ? cmd : "";
    }

    /** 回溯只在攻击时激活：真实左键或连点器正在左键输出都算攻击。 */
    private void updateBacktrack(Settings.Aux aux, boolean focused, boolean leftOn) {
        if (!sensor.connected) {
            backtrackCmd = "";
            return;
        }
        boolean attack = aux.backtrack && focused && sensor.worldReady
                && (Win32.down(Keys.VK_LBUTTON) || leftOn);
        int maxMs = Math.max(50, Math.min(5000, aux.backtrackMaxMs));
        String cmd = attack ? "B 1 " + maxMs + (aux.backtrackSlow ? " 1" : " 0") : "B 0";
        if (cmd.equals(backtrackCmd)) {
            return;
        }
        backtrackCmd = sensor.send(cmd) ? cmd : "";
    }

    private boolean edge(int vk) {
        boolean down = Win32.down(vk);
        boolean was = keyState.contains(vk);
        if (down && !was) {
            keyState.add(vk);
            return true;
        }
        if (!down && was) {
            keyState.remove(vk);
        }
        return false;
    }

    private boolean toggle(int vk) {
        if (edge(vk)) {
            toggles.put(vk, !Boolean.TRUE.equals(toggles.get(vk)));
        }
        return Boolean.TRUE.equals(toggles.get(vk));
    }

    private void panic() {
        settings.aim.enabled = false;
        settings.left.enabled = false;
        settings.right.enabled = false;
        settings.aux.eagle = false;
        settings.aux.jumpReset = false;
        settings.aux.sprint = false;
        settings.aux.backtrack = false;
        if (sensor.send("B 0")) {
            backtrackCmd = "B 0";
        } else {
            backtrackCmd = "";
        }
        releaseAll();
        settings.save();
        notifyUi();
    }

    private void releaseAll() {
        left.release();
        right.release();
        eagle.release();
        jump.release();
        sprint.release();
        aim.reset();
    }

    private void notifyUi() {
        Runnable r = onUiState;
        if (r != null) {
            r.run();
        }
    }
}
