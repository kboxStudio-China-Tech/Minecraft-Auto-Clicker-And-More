package mcac.client;

/** Windows 虚拟键码与显示名。 */
public final class Keys {

    public static final int VK_LBUTTON = 0x01;
    public static final int VK_RBUTTON = 0x02;
    public static final int VK_MBUTTON = 0x04;
    public static final int VK_XBUTTON1 = 0x05;
    public static final int VK_XBUTTON2 = 0x06;
    public static final int VK_SHIFT = 0x10;
    public static final int VK_CONTROL = 0x11;
    public static final int VK_MENU = 0x12;
    public static final int VK_SPACE = 0x20;
    public static final int VK_END = 0x23;
    public static final int VK_HOME = 0x24;
    public static final int VK_INSERT = 0x2D;
    public static final int VK_W = 0x57;

    private Keys() {
    }

    public static String name(int vk) {
        switch (vk) {
            case VK_LBUTTON:
                return "鼠标左键";
            case VK_RBUTTON:
                return "鼠标右键";
            case VK_MBUTTON:
                return "鼠标中键";
            case VK_XBUTTON1:
                return "鼠标侧键1";
            case VK_XBUTTON2:
                return "鼠标侧键2";
            case VK_SHIFT:
                return "Shift";
            case VK_CONTROL:
                return "Ctrl";
            case VK_MENU:
                return "Alt";
            case VK_SPACE:
                return "空格";
            case VK_END:
                return "End";
            case VK_HOME:
                return "Home";
            case VK_INSERT:
                return "Insert";
            case 0x1B:
                return "Esc";
            case 0x09:
                return "Tab";
            case 0x08:
                return "Backspace";
            case 0x0D:
                return "Enter";
            case 0x14:
                return "CapsLock";
            case 0x2C:
                return "PrintScreen";
            default:
                break;
        }
        if (vk >= 0x70 && vk <= 0x87) {
            return "F" + (vk - 0x6F);
        }
        if (vk >= 0x30 && vk <= 0x39) {
            return String.valueOf((char) vk);
        }
        if (vk >= 0x41 && vk <= 0x5A) {
            return String.valueOf((char) vk);
        }
        if (vk >= 0x60 && vk <= 0x69) {
            return "小键盘" + (vk - 0x60);
        }
        return "VK_" + vk;
    }
}
