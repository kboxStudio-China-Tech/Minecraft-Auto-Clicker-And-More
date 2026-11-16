package mcac.client;

import mcac.client.net.Attach;

import javax.swing.JOptionPane;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 检查 Attach 能力；必要时把 tools.jar 挂到系统类加载器，或换用本机 JDK 重启自身。 */
final class Toolchain {

    private Toolchain() {
    }

    static void ensure() {
        if (attachAvailable()) {
            return;
        }
        if (installToolsJar()) {
            return;
        }
        if (!Boolean.getBoolean("mcac.relaunched")) {
            File jdk = findJdk();
            File self = selfJar();
            File tools = jdk == null ? null : new File(jdk, "lib/tools.jar");
            if (jdk != null && self != null && tools != null && tools.isFile()) {
                try {
                    List<String> cmd = new ArrayList<String>();
                    cmd.add(new File(jdk, "bin/javaw.exe").getAbsolutePath());
                    cmd.add("-Dmcac.relaunched=true");
                    cmd.add("-cp");
                    // JDK 8 的 Attach API 位于 tools.jar，必须显式加入类路径
                    cmd.add(self.getAbsolutePath() + File.pathSeparator + tools.getAbsolutePath());
                    cmd.add("mcac.client.Main");
                    new ProcessBuilder(cmd).start();
                    System.exit(0);
                } catch (Throwable ignored) {
                }
            }
        }
        JOptionPane.showMessageDialog(null,
                "当前 Java 运行环境缺少 Attach 能力（tools.jar）。\n"
                        + "请使用 JDK 8 启动本程序，例如：\n"
                        + "\"C:\\Program Files\\Java\\jdk1.8.0_202\\bin\\javaw.exe\" -jar MCAC.jar",
                "运行环境提示", JOptionPane.WARNING_MESSAGE);
    }

    /** JDK 8 下把同版本 tools.jar 动态追加到系统类加载器，避免重启进程。 */
    private static boolean installToolsJar() {
        try {
            if (!System.getProperty("java.specification.version", "").startsWith("1.8")) {
                return false;
            }
            File home = new File(System.getProperty("java.home", ""));
            File tools = new File(home, "lib/tools.jar");
            if (!tools.isFile() && home.getParentFile() != null) {
                tools = new File(home.getParentFile(), "lib/tools.jar");
            }
            if (!tools.isFile()) {
                return false;
            }
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            if (!(sys instanceof URLClassLoader)) {
                return false;
            }
            Method add = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            add.setAccessible(true);
            add.invoke(sys, tools.toURI().toURL());
            return attachAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean attachAvailable() {
        try {
            return Attach.available();
        } catch (Throwable t) {
            return false;
        }
    }

    private static File selfJar() {
        try {
            File f = new File(Toolchain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File findJdk() {
        List<File> candidates = new ArrayList<File>();
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            File f = new File(javaHome);
            candidates.add(f);
            if (f.getParentFile() != null) {
                candidates.add(f.getParentFile());
            }
        }
        String env = System.getenv("JAVA_HOME");
        if (env != null && !env.isEmpty()) {
            candidates.add(new File(env));
        }
        String[] roots = {
                "C:/Program Files/Java",
                "C:/Program Files (x86)/Java",
                "C:/Program Files/Eclipse Adoptium",
                "C:/Program Files/Zulu",
                "C:/Program Files/Microsoft"
        };
        for (String root : roots) {
            File dir = new File(root);
            File[] subs = dir.listFiles();
            if (subs == null) {
                continue;
            }
            for (File sub : subs) {
                candidates.add(sub);
            }
        }
        // 优先 JDK 8
        Collections.sort(candidates, (a, b) -> {
            boolean a8 = a.getName().contains("1.8");
            boolean b8 = b.getName().contains("1.8");
            if (a8 != b8) {
                return a8 ? -1 : 1;
            }
            return a.getName().compareTo(b.getName());
        });
        for (File c : candidates) {
            if (new File(c, "bin/javaw.exe").isFile() && new File(c, "lib/tools.jar").isFile()) {
                return c;
            }
        }
        return null;
    }
}
