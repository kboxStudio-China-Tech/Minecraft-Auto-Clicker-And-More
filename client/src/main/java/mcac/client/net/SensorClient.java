package mcac.client.net;

import mcac.client.win.Win32;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 连接游戏内探针，持续读取世界状态心跳与逐 tick 快照。
 * 端口由 agent 动态分配并写入端口文件，这里支持按 PID 指定或自动发现。
 */
public final class SensorClient {

    public volatile Snapshot latest;
    public volatile boolean connected;
    /** 桥端显式上报的“玩家 tick 是否在推进”，即是否真的在世界中。 */
    public volatile boolean worldReady;
    public volatile long lastFrameAt;
    public volatile long lastStatusAt;
    public volatile String lastError = "";

    private volatile int port;
    private volatile Socket socket;
    private volatile OutputStream out;
    private final Object sendLock = new Object();
    private volatile boolean running = true;

    /** 向游戏内桥发送控制命令（回溯开关等）；未连接时返回 false。 */
    public boolean send(String line) {
        OutputStream o = out;
        if (o == null) {
            return false;
        }
        synchronized (sendLock) {
            try {
                o.write((line + "\n").getBytes("UTF-8"));
                o.flush();
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    public int port() {
        return port;
    }

    /** 由注入流程写入实际端口；端口变化会立即断开旧连接并重连。 */
    public void setPort(int p) {
        if (p <= 0 || p == port) {
            return;
        }
        port = p;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public void start() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "MCAC-Sensor");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void loop() {
        while (running) {
            int p = port;
            int discoveredPid = 0;
            if (p <= 0) {
                // 客户端先启动、游戏已注入时自动发现最新端口
                p = Attach.discoverPort();
                if (p <= 0) {
                    sleep(600L);
                    continue;
                }
                discoveredPid = Attach.discoverPid();
                port = p;
            }
            Socket s = new Socket();
            socket = s;
            try {
                s.connect(new InetSocketAddress("127.0.0.1", p), 600);
                s.setTcpNoDelay(true);
                out = s.getOutputStream();
                connected = true;
                lastError = "";
                if (discoveredPid > 0) {
                    // 自动发现端口时同步游戏 PID，窗口标题被客户端改写也能正确判定前台
                    Win32.setGamePid(discoveredPid);
                }
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.startsWith("F ")) {
                        Snapshot snap = parse(line);
                        if (snap != null) {
                            latest = snap;
                            lastFrameAt = System.currentTimeMillis();
                        }
                    } else if (line.startsWith("S ")) {
                        worldReady = "1".equals(line.substring(2).trim());
                        lastStatusAt = System.currentTimeMillis();
                    }
                }
            } catch (Throwable t) {
                lastError = String.valueOf(t.getMessage());
            } finally {
                connected = false;
                worldReady = false;
                if (socket == s) {
                    socket = null;
                    out = null;
                }
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
            sleep(400L);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Snapshot parse(String line) {
        String[] p = line.split(" ");
        if (p.length < 14) {
            return null;
        }
        try {
            Snapshot s = new Snapshot();
            s.seq = Long.parseLong(p[1]);
            s.px = Double.parseDouble(p[2]);
            s.py = Double.parseDouble(p[3]);
            s.pz = Double.parseDouble(p[4]);
            s.ph = Double.parseDouble(p[5]);
            s.yaw = Double.parseDouble(p[6]);
            s.pitch = Double.parseDouble(p[7]);
            s.edge = "1".equals(p[8]);
            s.vx = Double.parseDouble(p[9]);
            s.vz = Double.parseDouble(p[10]);
            s.ground = "1".equals(p[11]);
            s.hold = Integer.parseInt(p[12]);
            int n = Integer.parseInt(p[13]);
            int idx = 14;
            for (int i = 0; i < n && i < 48 && idx + 4 < p.length; i++) {
                s.ex[i] = Double.parseDouble(p[idx]);
                s.ey[i] = Double.parseDouble(p[idx + 1]);
                s.ez[i] = Double.parseDouble(p[idx + 2]);
                s.eh[i] = Double.parseDouble(p[idx + 3]);
                s.ePlayer[i] = "1".equals(p[idx + 4]);
                idx += 5;
            }
            s.count = Math.min(n, 48);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }
}
