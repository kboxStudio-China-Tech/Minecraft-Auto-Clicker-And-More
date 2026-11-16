package mcac.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/** 全部可调参数，JSON 持久化到 ~/.mcac/config.json */
public final class Settings {

    public Aim aim = new Aim();
    public Clicker left = new Clicker();
    public Clicker right = new Clicker();
    public Aux aux = new Aux();
    public int panicKey = Keys.VK_END;

    public static final class Aim {
        public boolean enabled = true;
        public int key = Keys.VK_RBUTTON;
        public boolean hold = true;
        public double fov = 180.0D;
        public double range = 6.0D;
        public double smooth = 4.0D;
        public double speed = 1500.0D;
        public double tolerance = 0.3D;
        public String point = "head";
        public boolean onlyLiving = true;
        public double sensitivity = 0.5D;
        public double gain = 1.0D;
        public boolean noise = true;
    }

    public static final class Clicker {
        public boolean enabled = false;
        public int key = Keys.VK_LBUTTON;
        public boolean hold = true;
        public double cpsMin = 8.0D;
        public double cpsMax = 12.0D;
        public String curve = "uniform";
        public int jitterMs = 0;
        public double jitterPx = 0.0D;
        /** 仅在探针确认已进入世界后才工作。 */
        public boolean worldOnly = true;
        /** 仅当手持方块或剑时才工作。 */
        public boolean itemOnly = false;
    }

    public static final class Aux {
        public boolean eagle = false;
        /** 运动方向前方多少格悬空时蹲下（格）。 */
        public double eagleEdgeDist = 0.56D;
        public boolean jumpReset = false;
        public int jumpDelay = 30;
        public boolean sprint = false;
        public int sprintKey = Keys.VK_CONTROL;
        /** 回溯：攻击时暂停处理入站数据包。 */
        public boolean backtrack = false;
        /** 最长回溯时间（毫秒）。 */
        public int backtrackMaxMs = 300;
        /** 结束后缓慢排空缓存包，而不是一次性回放（减少回拉）。 */
        public boolean backtrackSlow = false;
    }

    private static File file() {
        File dir = new File(System.getProperty("user.home"), ".mcac");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, "config.json");
    }

    public static Settings load() {
        File f = file();
        if (!f.isFile()) {
            return new Settings();
        }
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), "UTF-8")) {
            Settings s = new Gson().fromJson(r, Settings.class);
            if (s != null) {
                if (s.aim == null) {
                    s.aim = new Aim();
                }
                if (s.left == null) {
                    s.left = new Clicker();
                }
                if (s.right == null) {
                    s.right = new Clicker();
                    s.right.key = Keys.VK_RBUTTON;
                }
                if (s.aux == null) {
                    s.aux = new Aux();
                }
                return s;
            }
        } catch (Throwable ignored) {
        }
        return new Settings();
    }

    public void save() {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file()), "UTF-8")) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            w.write(gson.toJson(this));
        } catch (Throwable ignored) {
        }
    }
}
