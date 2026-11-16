package mcac.client.core;

import mcac.client.Settings;
import mcac.client.win.Win32;

/** 单键连点器：CPS 区间 + 分布曲线 + 抖动。 */
public final class Clicker {

    private final boolean right;
    private boolean down;
    private long nextAt;
    private long releaseAt;

    public Clicker(boolean right) {
        this.right = right;
    }

    /**
     * @param trigger  触发键是否激活
     * @param focused  游戏窗口是否在前台
     * @param inWorld  探针是否确认已进入世界
     * @param held     手持物品：0=空手/其他，1=方块，2=剑
     */
    public void update(Settings.Clicker cfg, boolean trigger, boolean focused, boolean inWorld, int held) {
        boolean blocked = (cfg.worldOnly && !inWorld)
                || (cfg.itemOnly && held != 1 && held != 2);
        if (!cfg.enabled || !trigger || !focused || blocked) {
            release();
            nextAt = 0L;
            return;
        }
        long now = System.nanoTime();
        if (nextAt == 0L) {
            nextAt = now;
        }
        if (!down) {
            if (now >= nextAt) {
                long interval = intervalNanos(cfg);
                press(cfg);
                down = true;
                long hold = (long) (interval * (0.35D + Math.random() * 0.2D));
                hold = Math.max(6_000_000L, Math.min(55_000_000L, hold));
                if (hold > interval - 2_000_000L) {
                    hold = Math.max(4_000_000L, interval - 2_000_000L);
                }
                releaseAt = now + hold;
                nextAt = now + interval;
            }
        } else if (now >= releaseAt) {
            up();
            down = false;
        }
    }

    public boolean isDown() {
        return down;
    }

    public void release() {
        if (down) {
            up();
            down = false;
        }
    }

    private void press(Settings.Clicker cfg) {
        if (cfg.jitterPx > 0.05D) {
            int jx = (int) Math.round((Math.random() * 2.0D - 1.0D) * cfg.jitterPx);
            int jy = (int) Math.round((Math.random() * 2.0D - 1.0D) * cfg.jitterPx);
            Win32.mouseMove(jx, jy);
        }
        if (right) {
            Win32.rightDown();
        } else {
            Win32.leftDown();
        }
    }

    private void up() {
        if (right) {
            Win32.rightUp();
        } else {
            Win32.leftUp();
        }
    }

    private static long intervalNanos(Settings.Clicker cfg) {
        double cps = sampleCps(cfg);
        double ms = 1000.0D / cps;
        if (cfg.jitterMs > 0) {
            ms += (Math.random() * 2.0D - 1.0D) * cfg.jitterMs;
        }
        ms = Math.max(20.0D, ms);
        return (long) (ms * 1_000_000.0D);
    }

    private static double sampleCps(Settings.Clicker cfg) {
        double min = Math.max(0.5D, Math.min(cfg.cpsMin, cfg.cpsMax));
        double max = Math.max(0.5D, Math.max(cfg.cpsMin, cfg.cpsMax));
        double r = Math.random();
        double v;
        if ("gauss".equals(cfg.curve)) {
            double mean = (min + max) * 0.5D;
            double sd = Math.max(0.05D, (max - min) / 6.0D);
            v = mean + gaussian() * sd;
        } else if ("beta".equals(cfg.curve)) {
            // 偏态分布：更多落在区间下段，模拟人工点击的不均匀
            v = min + (max - min) * Math.pow(r, 1.7D);
        } else {
            v = min + (max - min) * r;
        }
        return Math.max(min, Math.min(max, v));
    }

    private static double gaussian() {
        double u1 = Math.max(1.0E-9D, Math.random());
        double u2 = Math.random();
        return Math.sqrt(-2.0D * Math.log(u1)) * Math.cos(2.0D * Math.PI * u2);
    }
}
