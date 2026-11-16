package mcac.client.core;

import mcac.client.Settings;
import mcac.client.net.Snapshot;
import mcac.client.win.Win32;

/**
 * 瞄准辅助：以玩家视角预测角度为基准，按剩余角度比例移动鼠标，
 * 并把角度误差换算成 1.8 的鼠标计数（保留小数余量）。
 */
public final class Aim {

    private long lastSeq = -1L;
    private double predYaw;
    private double predPitch;
    private double accX;
    private double accY;

    public void reset() {
        lastSeq = -1L;
        accX = 0.0D;
        accY = 0.0D;
    }

    public void update(double dt, Settings.Aim cfg, Snapshot s) {
        if (s == null || s.count == 0 || dt <= 0.0D) {
            return;
        }
        if (s.seq != lastSeq) {
            lastSeq = s.seq;
            predYaw = s.yaw;
            predPitch = s.pitch;
        }

        double footY = s.py - s.ph * 0.5D;
        double eyeY = footY + 1.62D;

        int best = -1;
        double bestErr = Double.MAX_VALUE;
        double bestY = 0.0D;

        for (int i = 0; i < s.count; i++) {
            double h = s.eh[i];
            if (cfg.onlyLiving && h < 1.0D) {
                continue;
            }
            double dx = s.ex[i] - s.px;
            double dz = s.ez[i] - s.pz;
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat > cfg.range) {
                continue;
            }
            double yHead = s.ey[i] + h * 0.5D - Math.min(0.2D, h * 0.12D);
            double yBody = s.ey[i];
            double y;
            if ("body".equals(cfg.point)) {
                y = yBody;
            } else if ("nearest".equals(cfg.point)) {
                double eHead = Math.abs(pitchTo(dx, yHead - eyeY, dz) - predPitch);
                double eBody = Math.abs(pitchTo(dx, yBody - eyeY, dz) - predPitch);
                y = eHead <= eBody ? yHead : yBody;
            } else {
                y = yHead;
            }

            double errY = wrap(yawTo(dx, dz) - predYaw);
            if (Math.abs(errY) > cfg.fov * 0.5D) {
                continue;
            }
            double errP = pitchTo(dx, y - eyeY, dz) - predPitch;
            double err = Math.sqrt(errY * errY + errP * errP);
            if (err < bestErr) {
                bestErr = err;
                best = i;
                bestY = y;
            }
        }

        if (best < 0 || bestErr <= Math.max(0.05D, cfg.tolerance)) {
            return;
        }

        double dx = s.ex[best] - s.px;
        double dz = s.ez[best] - s.pz;
        double errY = wrap(yawTo(dx, dz) - predYaw);
        double errP = pitchTo(dx, bestY - eyeY, dz) - predPitch;
        double err = Math.sqrt(errY * errY + errP * errP);
        if (err < 1.0E-4D) {
            return;
        }

        double maxStep = Math.max(1.0D, cfg.speed) * dt;
        double alpha = 1.0D / Math.max(1.0D, cfg.smooth);
        double step = Math.min(maxStep, err * alpha * (dt / 0.01D));
        if (step < 0.004D) {
            step = Math.min(maxStep, 0.004D);
        }
        if (step > err) {
            step = err;
        }

        double k = step / err;
        double dYaw = errY * k;
        double dPitch = errP * k;
        if (cfg.noise) {
            dYaw += (Math.random() - 0.5D) * 0.02D;
            dPitch += (Math.random() - 0.5D) * 0.02D;
        }

        double f = Math.max(0.0D, Math.min(1.0D, cfg.sensitivity)) * 0.6D + 0.2D;
        double degPerCount = 1.2D * f * f * f * Math.max(0.2D, cfg.gain);
        if (degPerCount <= 1.0E-4D) {
            return;
        }

        accX += dYaw / degPerCount;
        // 1.8 的 pitch 正方向向下，鼠标 y 轴向下为正，因此两者同号
        accY += dPitch / degPerCount;
        int mx = (int) accX;
        int my = (int) accY;
        accX -= mx;
        accY -= my;

        if (mx != 0 || my != 0) {
            Win32.mouseMove(mx, my);
            predYaw = wrap(predYaw + mx * degPerCount);
            predPitch = Math.max(-90.0D, Math.min(90.0D, predPitch + my * degPerCount));
        }
    }

    private static double yawTo(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static double pitchTo(double dx, double dy, double dz) {
        double flat = Math.sqrt(dx * dx + dz * dz);
        return -Math.toDegrees(Math.atan2(dy, flat));
    }

    static double wrap(double a) {
        double v = a;
        while (v > 180.0D) {
            v -= 360.0D;
        }
        while (v < -180.0D) {
            v += 360.0D;
        }
        return v;
    }
}
