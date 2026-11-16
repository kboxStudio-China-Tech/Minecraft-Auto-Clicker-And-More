package mcac.bridge;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 运行在被注入进程中的探针桥。
 * 该类会被添加到 BootstrapClassLoader 搜索路径，因此只能依赖 JDK 类。
 * 注入点（LocalPlayer 的 tick 方法头部）会以 20Hz 调用 {@link #hook(Object)}。
 *
 * 协议（逐行 UTF-8 文本）：
 *   HELLO 5              连接建立
 *   S 0/1                世界状态心跳（0=未进入世界，1=世界中），每 400ms 一次
 *   F seq ...            玩家/实体快照
 *   B 1 <ms> <slow> / B 0  客户端→游戏：激活/关闭回溯（最长延迟毫秒，slow=1 缓慢释放）
 *   E <dist>             客户端→游戏：鹰眼探测距离（格）
 */
public final class SensorBridge {

    public static final int VERSION = 5;

    /** 由 agent 填充的反射句柄。 */
    public static final class Config {
        public volatile Object player;
        public Class<?> playerClass;
        public Object mc;
        public Field worldField;
        public Field listField;            // 含玩家的 List（1.8 loadedEntityList / 1.20+ players）
        public Method entityIter;          // 全量实体遍历（1.20+ entitiesForRendering），可为 null
        public Class<?> entityClass;
        public Field boxField;
        public Field[] boxFields;       // 6 个: minX,minY,minZ,maxX,maxY,maxZ
        public Method lookMethod;       // 返回 Vec3 的朝向方法（优先）
        public Field[] lookFields;      // Vec3 的 3 个 double
        public Field yawField;          // 备选
        public Field pitchField;        // 备选
        public volatile Method isAir;   // 方块空气判定（可后台补装）
        public volatile Constructor<?> posCtor;  // (int,int,int)
        /** 手持物品候选：每项 {读取方法, ItemStack->Item 方法, Item->翻译键方法}。 */
        public List<Object[]> held;
        /** 手持物品翻译键的全部候选方法（SRG 环境下名称不可靠，运行时逐个尝试）。 */
        public List<Method> heldDesc;
        public Class<?> blockItemClass;      // ItemBlock（可空）
        public Class<?> swordItemClass;      // ItemSword/SwordItem（可空）
        public Class<?> playerEntityClass;   // AbstractClientPlayer（含其他玩家）
    }

    private static volatile Config cfg;
    private static volatile Socket client;
    private static volatile String pending;
    private static volatile boolean running;
    private static volatile ServerSocket server;
    private static volatile int boundPort;

    private static final Object SEND_LOCK = new Object();
    private static final Object QUEUE_LOCK = new Object();

    private static long seq;
    private static long lastHookNanos;
    private static long lastCaptureNanos;
    private static boolean havePrev;
    private static double prevX, prevZ;

    /** 鹰眼探测距离（格），由客户端命令下发。 */
    private static volatile double edgeDist = 0.56D;

    public static volatile String lastError = "";

    private SensorBridge() {
    }

    /**
     * 启动探针服务并同步返回实际监听端口；requested <= 0 时由系统分配空闲端口。
     * 实际端口会写入 %TEMP%\mcac\bridge-<pid>.port，客户端按 PID 读取。
     */
    public static int start(int requested) {
        if (running) {
            return boundPort;
        }
        running = true;
        int actual = bind(requested);
        if (actual <= 0) {
            running = false;
            return 0;
        }
        boundPort = actual;
        writePortFile(actual);
        spawn("MCAC-BridgeAccept", new Runnable() {
            public void run() {
                acceptLoop();
            }
        });
        spawn("MCAC-BridgeSend", new Runnable() {
            public void run() {
                sendLoop();
            }
        });
        spawn("MCAC-BridgeState", new Runnable() {
            public void run() {
                heartbeatLoop();
            }
        });
        return actual;
    }

    public static int port() {
        return boundPort;
    }

    public static void configure(Config c) {
        cfg = c;
        havePrev = false;
        lastError = "";
    }

    /** 发现阶段若玩家离地无法验证方块方法，可稍后在落地时补装。 */
    public static void setAir(Method m, Constructor<?> ctor) {
        Config c = cfg;
        if (c == null) {
            return;
        }
        c.posCtor = ctor;
        c.isAir = m;
    }

    public static boolean ready() {
        return cfg != null;
    }

    /** 玩家 tick 仍在推进即视为“世界中”，退出世界后自动变回 0。 */
    private static boolean inWorld() {
        return lastHookNanos != 0L && System.nanoTime() - lastHookNanos < 1_000_000_000L;
    }

    private static int bind(int requested) {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), Math.max(0, requested)), 4);
            server = s;
            return s.getLocalPort();
        } catch (Throwable t) {
            lastError = "bind: " + t;
            return 0;
        }
    }

    private static void writePortFile(int port) {
        try {
            final File f = new File(bridgeDir(), "bridge-" + pid() + ".port");
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write((port + "\n").getBytes("UTF-8"));
            } finally {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                public void run() {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }, "MCAC-PortCleanup"));
        } catch (Throwable t) {
            lastError = "port file: " + t;
        }
    }

    private static File bridgeDir() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "mcac");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static String pid() {
        String n = ManagementFactory.getRuntimeMXBean().getName();
        int at = n.indexOf('@');
        return at > 0 ? n.substring(0, at) : "0";
    }

    private static void spawn(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void acceptLoop() {
        while (true) {
            ServerSocket srv = server;
            if (srv == null) {
                sleep(500);
                continue;
            }
            try {
                Socket s = srv.accept();
                s.setTcpNoDelay(true);
                Socket old = client;
                client = s;
                if (old != null) {
                    try {
                        old.close();
                    } catch (Throwable ignored) {
                    }
                }
                writeLine("HELLO " + VERSION);
                writeLine("S " + (inWorld() ? 1 : 0));
                spawn("MCAC-BridgeCmd", new Runnable() {
                    public void run() {
                        commandLoop(s);
                    }
                });
            } catch (Throwable t) {
                lastError = "accept: " + t;
                sleep(200);
            }
        }
    }

    /** 接收客户端控制命令（回溯开关、鹰眼距离）。 */
    private static void commandLoop(Socket s) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (client != s) {
                    return;
                }
                String t = line.trim();
                if (t.startsWith("E ")) {
                    try {
                        double d = Double.parseDouble(t.substring(2).trim());
                        edgeDist = Math.max(0.1D, Math.min(1.5D, d));
                    } catch (Throwable ignored) {
                    }
                    continue;
                }
                if (!t.startsWith("B ")) {
                    continue;
                }
                String[] p = t.split(" ");
                if (p.length < 2) {
                    continue;
                }
                if ("1".equals(p[1])) {
                    long ms = 300L;
                    if (p.length >= 3) {
                        try {
                            ms = Long.parseLong(p[2]);
                        } catch (Throwable ignored) {
                        }
                    }
                    boolean slow = p.length >= 4 && "1".equals(p[3]);
                    PacketBacktrack.set(true, ms, slow);
                } else {
                    PacketBacktrack.set(false, 0L, false);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void heartbeatLoop() {
        while (true) {
            sleep(400);
            if (client != null) {
                writeLine("S " + (inWorld() ? 1 : 0));
            }
        }
    }

    private static void sendLoop() {
        while (true) {
            String line;
            synchronized (QUEUE_LOCK) {
                while (pending == null) {
                    try {
                        QUEUE_LOCK.wait(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                line = pending;
                pending = null;
            }
            if (client != null) {
                writeLine(line);
            }
        }
    }

    private static void writeLine(String line) {
        Socket s = client;
        if (s == null) {
            return;
        }
        synchronized (SEND_LOCK) {
            try {
                OutputStream os = s.getOutputStream();
                os.write((line + "\n").getBytes("UTF-8"));
                os.flush();
            } catch (Throwable t) {
                closeClient(s);
            }
        }
    }

    private static void closeClient(Socket s) {
        if (client == s) {
            client = null;
            PacketBacktrack.set(false, 0L, false);
        }
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }

    /** 注入点调用，运行在游戏主线程。 */
    public static void hook(Object entity) {
        Config c = cfg;
        if (c == null) {
            return;
        }
        long now = System.nanoTime();
        lastHookNanos = now;
        if (entity != c.player) {
            // 重新进入世界会创建新的本地玩家实例，这里就地接管
            if (c.playerClass == null || entity == null || entity.getClass() != c.playerClass) {
                return;
            }
            c.player = entity;
            havePrev = false;
        }
        // 同一 tick 内可能命中多个注入点，去重
        if (now - lastCaptureNanos < 15_000_000L) {
            return;
        }
        lastCaptureNanos = now;
        if (client == null) {
            return;
        }
        try {
            capture(c);
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        }
    }

    private static void capture(Config c) throws Exception {
        Field[] bf = c.boxFields;
        Object box = c.boxField.get(c.player);
        if (box == null) {
            return;
        }
        double minX = bf[0].getDouble(box);
        double minY = bf[1].getDouble(box);
        double minZ = bf[2].getDouble(box);
        double maxX = bf[3].getDouble(box);
        double maxY = bf[4].getDouble(box);
        double maxZ = bf[5].getDouble(box);
        double px = (minX + maxX) * 0.5D;
        double py = (minY + maxY) * 0.5D;
        double pz = (minZ + maxZ) * 0.5D;
        double ph = maxY - minY;

        double yaw;
        double pitch;
        if (c.lookMethod != null) {
            Object v = c.lookMethod.invoke(c.player);
            double lx = c.lookFields[0].getDouble(v);
            double ly = c.lookFields[1].getDouble(v);
            double lz = c.lookFields[2].getDouble(v);
            yaw = Math.toDegrees(Math.atan2(-lx, lz));
            if (ly > 1.0D) {
                ly = 1.0D;
            } else if (ly < -1.0D) {
                ly = -1.0D;
            }
            pitch = -Math.toDegrees(Math.asin(ly));
        } else {
            yaw = c.yawField.getFloat(c.player);
            pitch = c.pitchField.getFloat(c.player);
        }

        double vx = 0.0D;
        double vz = 0.0D;
        if (havePrev) {
            vx = px - prevX;
            vz = pz - prevZ;
        }
        prevX = px;
        prevZ = pz;
        havePrev = true;

        double footY = minY;
        boolean ground = false;
        boolean edge = false;
        Object world = null;
        if (c.worldField != null) {
            world = c.worldField.get(c.mc);
        }
        if (world != null && c.isAir != null && c.posCtor != null) {
            ground = !air(c, world, px, footY - 0.1D, pz);
            if (ground) {
                double dx = vx;
                double dz = vz;
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 0.004D) {
                    double rad = Math.toRadians(yaw);
                    dx = -Math.sin(rad);
                    dz = Math.cos(rad);
                    len = 1.0D;
                }
                dx /= len;
                dz /= len;
                double d = edgeDist;
                edge = air(c, world, px + dx * d, footY - 0.1D, pz + dz * d);
            }
        }

        StringBuilder out = new StringBuilder(384);
        out.append('F').append(' ').append(++seq)
                .append(' ').append(px).append(' ').append(py).append(' ').append(pz)
                .append(' ').append(ph)
                .append(' ').append(yaw).append(' ').append(pitch)
                .append(' ').append(edge ? 1 : 0);
        out.append(' ').append(vx).append(' ').append(vz);
        out.append(' ').append(ground ? 1 : 0);
        out.append(' ').append(heldCode(c));

        StringBuilder ents = new StringBuilder(512);
        int n = 0;
        if (world != null) {
            // 1.8 只有 loadedEntityList；1.20+ 玩家列表与全量实体迭代可能是两个来源，合并后按身份去重
            List<Object> all = new ArrayList<Object>();
            if (c.listField != null) {
                try {
                    Object raw = c.listField.get(world);
                    if (raw instanceof List) {
                        all.addAll((List<?>) raw);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (c.entityIter != null) {
                try {
                    Object raw = c.entityIter.invoke(world);
                    if (raw instanceof Iterable) {
                        for (Object e : (Iterable<?>) raw) {
                            if (e != null) {
                                all.add(e);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
            for (int i = 0; i < all.size() && n < 48; i++) {
                Object e = all.get(i);
                if (e == null || e == c.player || !c.entityClass.isInstance(e)) {
                    continue;
                }
                if (seen.put(e, Boolean.TRUE) != null) {
                    continue;
                }
                Object eb = c.boxField.get(e);
                if (eb == null) {
                    continue;
                }
                double ex = (bf[0].getDouble(eb) + bf[3].getDouble(eb)) * 0.5D;
                double ey = (bf[1].getDouble(eb) + bf[4].getDouble(eb)) * 0.5D;
                double ez = (bf[2].getDouble(eb) + bf[5].getDouble(eb)) * 0.5D;
                double eh = bf[4].getDouble(eb) - bf[1].getDouble(eb);
                double ddx = ex - px;
                double ddz = ez - pz;
                if (ddx * ddx + ddz * ddz > 4096.0D) {
                    continue;
                }
                boolean isPlayer = c.playerEntityClass != null && c.playerEntityClass.isInstance(e);
                ents.append(' ').append(ex).append(' ').append(ey).append(' ').append(ez).append(' ').append(eh)
                        .append(' ').append(isPlayer ? 1 : 0);
                n++;
            }
        }
        // 实体数量位于手持物品之后
        out.append(' ').append(n).append(ents).append('\n');
        pending = out.toString();
        synchronized (QUEUE_LOCK) {
            QUEUE_LOCK.notifyAll();
        }
    }

    /**
     * 0=空手/其他，1=方块，2=剑。
     * 优先用类名判定（1.8 ItemBlock/ItemSword、1.20+ BlockItem/SwordItem）；
     * 类名解析失败时退回物品类名关键字与翻译键（item.*_sword / block.* / tile.*）。
     */
    private static int heldCode(Config c) {
        List<Object[]> held = c.held;
        if (held == null || held.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < held.size(); i++) {
            Object[] h = held.get(i);
            if (h == null || h.length < 2) {
                continue;
            }
            Object item;
            try {
                Object stack = ((Method) h[0]).invoke(c.player);
                if (stack == null) {
                    continue;
                }
                item = ((Method) h[1]).invoke(stack);
            } catch (Throwable t) {
                continue;
            }
            if (item == null) {
                continue;
            }
            if (c.blockItemClass != null && c.blockItemClass.isInstance(item)) {
                return 1;
            }
            if (c.swordItemClass != null && c.swordItemClass.isInstance(item)) {
                return 2;
            }
            int byName = codeByName(item);
            if (byName != 0) {
                return byName;
            }
            int byDesc = codeByDesc(c, item);
            if (byDesc != 0) {
                return byDesc;
            }
        }
        return 0;
    }

    /** 物品类名兜底：ItemBlock/BlockItem 家族与剑类。 */
    private static int codeByName(Object item) {
        for (Class<?> k = item.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            if (n.endsWith("ItemBlock") || n.endsWith("BlockItem")) {
                return 1;
            }
            if (n.endsWith("Sword") || n.endsWith("SwordItem")) {
                return 2;
            }
        }
        return 0;
    }

    /** 翻译键兜底：1.8 为 tile./item.，1.20+ 为 block./item.。 */
    private static int codeByDesc(Config c, Object item) {
        List<Method> descs = c.heldDesc;
        if (descs == null || descs.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < descs.size(); i++) {
            try {
                Object desc = descs.get(i).invoke(item);
                if (!(desc instanceof String)) {
                    continue;
                }
                String id = ((String) desc).toLowerCase(Locale.ROOT);
                if (id.isEmpty() || id.equals("air") || id.endsWith(".air")) {
                    continue;
                }
                if (!id.startsWith("item.") && !id.startsWith("block.") && !id.startsWith("tile.")) {
                    continue;
                }
                if (id.contains("sword")) {
                    return 2;
                }
                if (id.startsWith("tile.") || id.startsWith("block.")) {
                    return 1;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static boolean air(Config c, Object world, double x, double y, double z) throws Exception {
        Object pos = c.posCtor.newInstance((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        Object r = c.isAir.invoke(world, pos);
        return r instanceof Boolean && (Boolean) r;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
