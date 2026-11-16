package mcac.client.net;

import com.sun.tools.attach.VirtualMachine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** 通过 Attach API 把内嵌的 agent 载入游戏进程。 */
public final class Attach {

    /** 注入结果：agent 落盘路径与桥端实际监听端口。 */
    public static final class Result {
        public final String agentPath;
        public final int port;

        Result(String agentPath, int port) {
            this.agentPath = agentPath;
            this.port = port;
        }
    }

    /** 目标进程权限更高（典型为 Minecraft 以管理员运行），普通权限无法附加。 */
    public static final class DeniedException extends Exception {
        DeniedException(String message) {
            super(message);
        }
    }

    private Attach() {
    }

    public static boolean available() {
        try {
            Class.forName("com.sun.tools.attach.VirtualMachine");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按 PID 注入，并等待桥端写出端口文件后返回实际端口。
     *
     * Java 8 客户端附加 Java 9+ 目标时，loadAgent 的回复解析会因协议差异报
     * “Non-numeric value found - int expected”，但 agent 往往已经加载成功。
     * 因此对 Java 9+ 目标改用目标 JVM 自己的 java 运行助手进程完成附加，
     * 助手不可用时退回进程内附加，并以端口文件出现作为成功判据。
     */
    public static Result inject(int pid) throws Exception {
        File agent = extractAgent();
        String[] target = probe(pid);
        int major = major(target == null ? null : target[0]);
        if (major >= 9) {
            int port = attachByHelper(pid, agent, target);
            if (port > 0) {
                return new Result(agent.getAbsolutePath(), port);
            }
        }

        Throwable err = null;
        boolean loaded = false;
        try {
            VirtualMachine vm = VirtualMachine.attach(String.valueOf(pid));
            try {
                // 传 0 让桥端自行选择空闲端口，避免多开/残留进程争抢固定端口
                vm.loadAgent(agent.getAbsolutePath(), "0");
                loaded = true;
            } finally {
                try {
                    vm.detach();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            if (isDenied(t)) {
                throw new DeniedException("目标进程以管理员权限运行，普通权限无法注入");
            }
            err = t;
        }
        int port = readPort(pid, loaded ? 8000L : 4000L);
        if (port > 0) {
            return new Result(agent.getAbsolutePath(), port);
        }
        if (err != null) {
            throw new IllegalStateException("注入失败: " + shortMsg(err));
        }
        throw new IllegalStateException("注入成功但未读到探针端口，请查看注入日志");
    }

    /** 目标 JVM 上的附加助手：{@code --attach-helper <pid> <agentPath>}。 */
    public static void helper(String[] args) {
        try {
            VirtualMachine vm = VirtualMachine.attach(args[1]);
            try {
                vm.loadAgent(args[2], "0");
            } finally {
                try {
                    vm.detach();
                } catch (Throwable ignored) {
                }
            }
            System.exit(0);
        } catch (Throwable t) {
            // 输出会被客户端重定向到 %TEMP%\mcac\attach-helper.log，便于排查提权/模块缺失
            t.printStackTrace();
            System.exit(2);
        }
    }

    /** 目标进程的 java.version / java.home；权限不足时抛出 {@link DeniedException}。 */
    private static String[] probe(int pid) throws DeniedException {
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(String.valueOf(pid));
            Properties p = vm.getSystemProperties();
            return new String[]{p.getProperty("java.version"), p.getProperty("java.home")};
        } catch (Throwable t) {
            if (isDenied(t)) {
                throw new DeniedException("目标进程以管理员权限运行，无法读取其运行环境");
            }
            return null;
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 判断异常是否为系统拒绝访问（UAC 完整性级别不同）。 */
    public static boolean isDenied(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            String m = x.getMessage();
            if (m == null) {
                continue;
            }
            String s = m.toLowerCase();
            if (s.contains("拒绝访问") || s.contains("access is denied") || s.contains("access denied")
                    || s.contains("accessdenied") || s.contains("access_denied")) {
                return true;
            }
        }
        return false;
    }

    private static int major(String version) {
        if (version == null || version.isEmpty()) {
            return 0;
        }
        try {
            if (version.startsWith("1.")) {
                int i = version.indexOf('.', 2);
                return Integer.parseInt(i > 0 ? version.substring(2, i) : version.substring(2));
            }
            int i = version.indexOf('.');
            String head = i > 0 ? version.substring(0, i) : version;
            int j = 0;
            while (j < head.length() && Character.isDigit(head.charAt(j))) {
                j++;
            }
            return j == 0 ? 0 : Integer.parseInt(head.substring(0, j));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 用目标 JVM（或本机 JDK 17+）启动助手完成附加；成功时返回探针端口。 */
    private static int attachByHelper(int pid, File agent, String[] target) {
        File java = findHelperJava(target == null ? null : target[1]);
        File cp = selfPath();
        if (java == null || cp == null) {
            return 0;
        }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(java.getAbsolutePath(), "-cp", cp.getAbsolutePath(),
                    "mcac.client.Main", "--attach-helper", String.valueOf(pid), agent.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(new File(bridgeDir(), "attach-helper.log")));
            p = pb.start();
            if (!p.waitFor(20L, TimeUnit.SECONDS)) {
                p.destroy();
                return 0;
            }
        } catch (Throwable t) {
            if (p != null) {
                p.destroy();
            }
            return 0;
        }
        return readPort(pid, 8000L);
    }

    /** 助手 JVM：优先目标进程自己的 java，其次本机 JDK 17+。 */
    private static File findHelperJava(String targetHome) {
        File own = javaExe(targetHome);
        if (own != null) {
            return own;
        }
        String javaHome = System.getenv("JAVA_HOME");
        File jh = javaExe(javaHome);
        if (jh != null && runtimeMajor(javaHome) >= 17) {
            return jh;
        }
        String[] roots = {
                "C:\\Program Files\\Java",
                "C:\\Program Files\\Eclipse Adoptium",
                "C:\\Program Files\\Microsoft",
                "C:\\Program Files\\Zulu",
                "C:\\Program Files\\BellSoft",
                "C:\\Program Files\\Amazon Corretto",
                "C:\\Program Files\\Semeru"
        };
        for (String root : roots) {
            File[] dirs = new File(root).listFiles();
            if (dirs == null) {
                continue;
            }
            java.util.Arrays.sort(dirs);
            for (File d : dirs) {
                File exe = javaExe(d.getAbsolutePath());
                if (exe != null && runtimeMajor(d.getAbsolutePath()) >= 17) {
                    return exe;
                }
            }
        }
        return null;
    }

    private static File javaExe(String home) {
        if (home == null || home.isEmpty()) {
            return null;
        }
        File f = new File(home, "bin/javaw.exe");
        if (!f.isFile()) {
            f = new File(home, "bin/java.exe");
        }
        return f.isFile() ? f : null;
    }

    /** 从 JDK 的 release 文件读取版本，无法确定时返回 0。 */
    private static int runtimeMajor(String home) {
        try {
            FileInputStream in = new FileInputStream(new File(home, "release"));
            try {
                Properties p = new Properties();
                p.load(in);
                return major(p.getProperty("JAVA_VERSION", "").replace("\"", ""));
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 当前客户端自身的 jar（或类目录），供助手进程/提权重启作为 classpath。 */
    public static File selfPath() {
        try {
            java.security.CodeSource cs = Attach.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File f = new File(cs.getLocation().toURI());
                if (f.exists()) {
                    return f;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    public static File bridgeDir() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "mcac");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public static File portFile(int pid) {
        return new File(bridgeDir(), "bridge-" + pid + ".port");
    }

    /** 轮询读取指定进程的端口文件。 */
    public static int readPort(int pid, long timeoutMs) {
        File f = portFile(pid);
        long end = System.currentTimeMillis() + Math.max(200L, timeoutMs);
        while (true) {
            int p = parsePort(f);
            if (p > 0) {
                return p;
            }
            if (System.currentTimeMillis() >= end) {
                return 0;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                return 0;
            }
        }
    }

    /** 扫描目录里最新的端口文件，用于客户端启动时自动发现已注入的游戏。 */
    public static int discoverPort() {
        return parsePort(newestPortFile());
    }

    /** 最新端口文件对应的游戏进程 PID；用于按进程判定游戏窗口是否在前台。 */
    public static int discoverPid() {
        File f = newestPortFile();
        if (f == null) {
            return 0;
        }
        String n = f.getName();
        try {
            return Integer.parseInt(n.substring("bridge-".length(), n.length() - ".port".length()));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static File newestPortFile() {
        File[] files = bridgeDir().listFiles();
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith("bridge-") || !n.endsWith(".port")) {
                continue;
            }
            if (newest == null || f.lastModified() > newest.lastModified()) {
                newest = f;
            }
        }
        return newest;
    }

    private static int parsePort(File f) {
        if (f == null || !f.isFile()) {
            return 0;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16);
            byte[] buf = new byte[32];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String text = new String(bos.toByteArray(), "UTF-8").trim();
            int p = Integer.parseInt(text);
            return p > 0 && p < 65536 ? p : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 把客户端 jar 中内嵌的 agent 释放到临时目录。 */
    public static File extractAgent() throws Exception {
        InputStream in = Attach.class.getResourceAsStream("/mcac-agent.jar");
        if (in == null) {
            throw new IllegalStateException("客户端缺少 mcac-agent.jar 资源");
        }
        byte[] data;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(262144);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            data = bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }

        File dir = bridgeDir();
        // 文件名带内容校验，避免重建后长度相同却复用了旧 agent
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        String tag = data.length + "-" + Long.toHexString(crc.getValue());
        File out = new File(dir, "mcac-agent-" + tag + ".jar");
        if (out.isFile() && out.length() == data.length) {
            return out;
        }
        File tmp = File.createTempFile("mcac-agent", ".jar", dir);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(data);
        }
        if (!tmp.renameTo(out)) {
            out = new File(dir, "mcac-agent-" + tag + "-" + System.currentTimeMillis() + ".jar");
            if (!tmp.renameTo(out)) {
                return tmp;
            }
        }
        return out;
    }
}
