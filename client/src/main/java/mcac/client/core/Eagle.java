package mcac.client.core;

import mcac.client.Keys;
import mcac.client.Settings;
import mcac.client.net.Snapshot;
import mcac.client.win.Win32;

/**
 * 鹰眼（安全行走 / 自动搭路蹲下）。
 * 站在地面且运动方向前方指定距离（edgeDist）处悬空时蹲下，离开边缘立即站起。
 * 悬空距离由客户端下发到桥端，探测点位置随之变化。
 * Win32.down 为真实按键状态（已过滤模拟事件），所以可以区分玩家自己按住的 Shift。
 */
public final class Eagle {

    private boolean sneaking;

    public void update(Settings.Aux cfg, Snapshot s, boolean focused) {
        boolean userShift = Win32.down(Keys.VK_SHIFT);
        boolean want = cfg.eagle && focused && !userShift
                && s != null && s.ground && s.edge;
        if (want && !sneaking) {
            Win32.keyDown(Keys.VK_SHIFT);
            sneaking = true;
        } else if (!want) {
            release();
        }
    }

    public void release() {
        if (sneaking) {
            Win32.keyUp(Keys.VK_SHIFT);
            sneaking = false;
        }
    }
}
