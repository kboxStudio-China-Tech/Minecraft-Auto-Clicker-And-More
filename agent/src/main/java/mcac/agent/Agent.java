package mcac.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** Java Agent 入口：把探针桥挂到 Bootstrap，然后异步做结构发现与注入。 */
public final class Agent {

    /** 0 表示由系统分配空闲端口，避免多开或残留进程争抢同一端口。 */
    public static final int DEFAULT_PORT = 0;

    private static boolean started;

    private Agent() {
    }

    public static void premain(String args, Instrumentation inst) {
        start(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        start(args, inst);
    }

    private static synchronized void start(String args, Instrumentation inst) {
        if (started) {
            Log.log("重复加载，忽略");
            return;
        }
        started = true;
        Log.init();
        Log.log("agent 加载开始, args=" + args);

        int port = DEFAULT_PORT;
        if (args != null) {
            try {
                port = Integer.parseInt(args.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        try {
            // 只把桥类挂到 Bootstrap：注入代码位于游戏类中，需要能解析桥类。
            // 不能挂整包，否则 agent 自身的包级访问类会被 Bootstrap 抢先加载。
            File bridge = bridgeJar();
            inst.appendToBootstrapClassLoaderSearch(new JarFile(bridge));
            Log.log("已挂载桥类到 bootstrap: " + bridge);
        } catch (Throwable t) {
            Log.log("挂载 bootstrap 失败", t);
        }

        try {
            int actual = mcac.bridge.SensorBridge.start(port);
            Log.log("探针服务已监听 127.0.0.1:" + actual + "（端口文件 bridge-" + pid() + ".port）");
        } catch (Throwable t) {
            Log.log("启动探针服务失败", t);
        }

        Thread t = new Thread(new Discovery(inst), "MCAC-Discovery");
        t.setDaemon(true);
        t.start();
    }

    private static String pid() {
        String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = n.indexOf('@');
        return at > 0 ? n.substring(0, at) : "0";
    }

    /** 从自身 jar 中抽取 mcac/bridge/*.class 生成独立小包，避免污染 agent 命名空间。 */
    private static File bridgeJar() throws Exception {
        File self = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File dir = new File(System.getProperty("java.io.tmpdir"), "mcac");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, "mcac-bridge-" + self.length() + "-" + self.lastModified() + ".jar");
        if (out.isFile() && out.length() > 0L) {
            return out;
        }
        JarFile src = new JarFile(self);
        try {
            JarOutputStream jos = new JarOutputStream(new FileOutputStream(out));
            try {
                byte[] buf = new byte[8192];
                Enumeration<JarEntry> entries = src.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    String name = e.getName();
                    if (!name.startsWith("mcac/bridge/") || !name.endsWith(".class")) {
                        continue;
                    }
                    jos.putNextEntry(new JarEntry(name));
                    InputStream in = src.getInputStream(e);
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        jos.write(buf, 0, r);
                    }
                    in.close();
                    jos.closeEntry();
                }
            } finally {
                jos.close();
            }
        } finally {
            src.close();
        }
        return out;
    }
}
