package mcac.client.core;

import mcac.client.Keys;
import mcac.client.Settings;
import mcac.client.net.Snapshot;
import mcac.client.win.Win32;

/**
 * 受击自动跳：水平速度出现击退式突增时视为被击中，
 * 延迟若干毫秒后补一次空格，削减击退位移。
 *
 * 1.8.9 的多人客户端只收到受伤动画（S19），拿不到伤害来源，
 * 因此用“附近存在其他玩家”来近似“仅玩家攻击造成的伤害”：
 * 玩家近战距离约 3 格，4 格内没有其他玩家时不触发，可过滤摔落与生物击退。
 */
public final class JumpReset {

    private double prevSpeed;
    private long lastHitAt;
    private long pressAt;
    private long releaseAt;
    private boolean down;

    public void update(Settings.Aux cfg, Snapshot s, boolean focused) {
        if (s == null) {
            return;
        }
        long now = System.nanoTime();
        double speed = Math.sqrt(s.vx * s.vx + s.vz * s.vz);

        if (cfg.jumpReset && focused && !down) {
            double delta = speed - prevSpeed;
            if (delta > 0.12D && speed > 0.18D && now - lastHitAt > 350_000_000L
                    && playerAttacker(s)) {
                lastHitAt = now;
                pressAt = now + Math.max(0, cfg.jumpDelay) * 1_000_000L;
            }
        }
        prevSpeed = speed;

        if (pressAt > 0L && !down && now >= pressAt) {
            Win32.keyDown(Keys.VK_SPACE);
            down = true;
            releaseAt = now + 45_000_000L;
            pressAt = 0L;
        }
        if (down && now >= releaseAt) {
            release();
        }
    }

    /** 是否有其他玩家处于近战可及范围内。 */
    private static boolean playerAttacker(Snapshot s) {
        for (int i = 0; i < s.count; i++) {
            if (!s.ePlayer[i]) {
                continue;
            }
            double dx = s.ex[i] - s.px;
            double dz = s.ez[i] - s.pz;
            double dy = s.ey[i] - s.py;
            if (dx * dx + dz * dz > 16.0D) {
                continue;
            }
            if (dy < -3.0D || dy > 2.5D) {
                continue;
            }
            return true;
        }
        return false;
    }

    public void release() {
        if (down) {
            Win32.keyUp(Keys.VK_SPACE);
            down = false;
        }
    }
}
