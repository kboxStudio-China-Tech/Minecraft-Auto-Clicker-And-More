package mcac.client.core;

import mcac.client.Keys;
import mcac.client.Settings;
import mcac.client.win.Win32;

/**
 * 自动疾跑：前进时替玩家按住疾跑键（默认 Ctrl）。
 * 只在游戏窗口前台且已进入世界时生效；松开 W、按住 Shift 或玩家自己按住疾跑键时自动松开，
 * 不会覆盖玩家自己的操作。
 */
public final class Sprint {

    private int heldKey;

    public void update(Settings.Aux cfg, boolean active) {
        int key = cfg.sprintKey > 0 ? cfg.sprintKey : Keys.VK_CONTROL;
        boolean want = cfg.sprint && active
                && Win32.down(Keys.VK_W)
                && !Win32.down(Keys.VK_SHIFT)
                && !Win32.down(key);
        if (want) {
            if (heldKey != key) {
                release();
                Win32.keyDown(key);
                heldKey = key;
            }
        } else {
            release();
        }
    }

    public void release() {
        if (heldKey != 0) {
            Win32.keyUp(heldKey);
            heldKey = 0;
        }
    }
}
