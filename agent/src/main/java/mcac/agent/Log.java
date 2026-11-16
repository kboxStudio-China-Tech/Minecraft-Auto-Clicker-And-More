package mcac.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;

/** 写入临时目录的日志，客户端注入失败时可直接读取排查。 */
final class Log {

    private static File file;
    private static final Object LOCK = new Object();
    private static long lastOnce;
    private static String lastOnceMsg;

    static synchronized void init() {
        if (file != null) {
            return;
        }
        file = new File(System.getProperty("java.io.tmpdir"), "mcac-agent.log");
        if (file.exists() && file.length() > 2L * 1024L * 1024L) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static void log(String msg) {
        init();
        String line = "[" + new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + "] " + msg;
        synchronized (LOCK) {
            Writer w = null;
            try {
                // 客户端按 UTF-8 读取日志，这里必须用同一编码写，避免中文乱码
                w = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
                w.write(line);
                w.write('\n');
            } catch (Throwable ignored) {
            } finally {
                if (w != null) {
                    try {
                        w.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    static void log(String msg, Throwable t) {
        log(msg + " -> " + t);
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 8; i++) {
            log("    at " + st[i]);
        }
    }

    /** 避免轮询阶段刷屏。 */
    static void once(String msg) {
        if (msg.equals(lastOnceMsg) && System.currentTimeMillis() - lastOnce < 5000L) {
            return;
        }
        lastOnceMsg = msg;
        lastOnce = System.currentTimeMillis();
        log(msg);
    }
}
