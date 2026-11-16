package mcac.client.win;

import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Windows 输入与窗口相关封装。
 *
 * 关键点：连点器会向系统注入鼠标按下/抬起，而 GetAsyncKeyState 会把这些注入事件
 * 也当成真实按键，导致“按住触发”被自己的抬起打断、切换模式被反复翻转。
 * 这里安装 WH_KEYBOARD_LL / WH_MOUSE_LL 低级钩子，过滤带 INJECTED 标记的事件，
 * 从而得到“用户真实按键状态”，注入只负责输出、不再影响触发判断。
 */
public final class Win32 {

    public static final int MOUSEEVENTF_MOVE = 0x0001;
    public static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    public static final int MOUSEEVENTF_LEFTUP = 0x0004;
    public static final int MOUSEEVENTF_RIGHTDOWN = 0x0008;
    public static final int MOUSEEVENTF_RIGHTUP = 0x0010;
    public static final int KEYEVENTF_KEYUP = 0x0002;

    private static final int WH_KEYBOARD_LL = 13;
    private static final int WH_MOUSE_LL = 14;
    private static final int WM_KEYDOWN = 0x0100;
    private static final int WM_KEYUP = 0x0101;
    private static final int WM_SYSKEYDOWN = 0x0104;
    private static final int WM_SYSKEYUP = 0x0105;
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_LBUTTONUP = 0x0202;
    private static final int WM_RBUTTONDOWN = 0x0204;
    private static final int WM_RBUTTONUP = 0x0205;
    private static final int WM_MBUTTONDOWN = 0x0207;
    private static final int WM_MBUTTONUP = 0x0208;
    private static final int WM_XBUTTONDOWN = 0x020B;
    private static final int WM_XBUTTONUP = 0x020C;
    private static final long LLMHF_INJECTED = 0x00000001L;
    private static final int LLKHF_INJECTED = 0x10;

    public interface User32 extends StdCallLibrary {
        User32 I = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer GetForegroundWindow();

        int GetWindowThreadProcessId(Pointer hWnd, IntByReference pid);

        int GetWindowText(Pointer hWnd, char[] text, int max);

        boolean EnumWindows(WndEnumProc callback, Pointer lParam);

        short GetAsyncKeyState(int vk);

        void mouse_event(int flags, int dx, int dy, int data, Pointer extra);

        void keybd_event(byte vk, byte scan, int flags, Pointer extra);

        int MapVirtualKey(int code, int type);

        Pointer SetWindowsHookExW(int idHook, LowLevelProc proc, Pointer hMod, int threadId);

        boolean UnhookWindowsHookEx(Pointer hhk);

        Pointer CallNextHookEx(Pointer hhk, int nCode, Pointer wParam, Pointer lParam);

        int GetMessageW(Pointer msg, Pointer hWnd, int min, int max);

        boolean TranslateMessage(Pointer msg);

        Pointer DispatchMessageW(Pointer msg);
    }

    public interface WndEnumProc extends StdCallLibrary.StdCallCallback {
        boolean callback(Pointer hwnd, Pointer lParam);
    }

    public interface LowLevelProc extends StdCallLibrary.StdCallCallback {
        Pointer callback(int nCode, Pointer wParam, Pointer lParam);
    }

    /** 用户真实按下的按键（已过滤模拟事件）。 */
    private static final Set<Integer> realDown = Collections.synchronizedSet(new HashSet<Integer>());
    private static volatile boolean hooksActive;
    private static Pointer mouseHook;
    private static Pointer keyHook;

    private static final LowLevelProc MOUSE_PROC = new LowLevelProc() {
        public Pointer callback(int nCode, Pointer wParam, Pointer lParam) {
            try {
                if (nCode >= 0 && wParam != null && lParam != null) {
                    long injected = lParam.getInt(12) & 0xFFFFFFFFL;
                    if ((injected & LLMHF_INJECTED) == 0L) {
                        int msg = (int) Pointer.nativeValue(wParam);
                        if (msg == WM_XBUTTONDOWN || msg == WM_XBUTTONUP) {
                            // 侧键编号位于 mouseData 高 16 位：1=侧键1，2=侧键2
                            int button = (lParam.getInt(8) >>> 16) & 0xFFFF;
                            Integer vk = Integer.valueOf(button == 2 ? 0x06 : 0x05);
                            if (msg == WM_XBUTTONDOWN) {
                                realDown.add(vk);
                            } else {
                                realDown.remove(vk);
                            }
                        } else {
                            int vk = mouseVk(msg);
                            if (vk != 0) {
                                if (msg == WM_LBUTTONDOWN || msg == WM_RBUTTONDOWN || msg == WM_MBUTTONDOWN) {
                                    realDown.add(Integer.valueOf(vk));
                                } else {
                                    realDown.remove(Integer.valueOf(vk));
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return User32.I.CallNextHookEx(null, nCode, wParam, lParam);
        }
    };

    private static final LowLevelProc KEY_PROC = new LowLevelProc() {
        public Pointer callback(int nCode, Pointer wParam, Pointer lParam) {
            try {
                if (nCode >= 0 && wParam != null && lParam != null) {
                    int flags = lParam.getInt(8);
                    if ((flags & LLKHF_INJECTED) == 0) {
                        int vk = lParam.getInt(0);
                        int msg = (int) Pointer.nativeValue(wParam);
                        if (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN) {
                            realDown.add(Integer.valueOf(vk));
                        } else if (msg == WM_KEYUP || msg == WM_SYSKEYUP) {
                            realDown.remove(Integer.valueOf(vk));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return User32.I.CallNextHookEx(null, nCode, wParam, lParam);
        }
    };

    private static final String GAME_TITLE = "Minecraft";

    /** 排除启动器窗口，只认游戏本体（如 “Minecraft 1.8.9”）。 */
    private static boolean isGameTitle(String t) {
        return t != null && t.contains(GAME_TITLE) && !t.contains("Launcher");
    }

    private static volatile long lastCheck;
    private static volatile boolean focusedCache;
    /** 已注入的游戏进程 PID；已知时按进程判定前台，避免自定义窗口标题（如服务器名）导致误判。 */
    private static volatile int gamePid;

    private Win32() {
    }

    public static void setGamePid(int pid) {
        gamePid = pid > 0 ? pid : 0;
        lastCheck = 0L;
    }

    public static int gamePid() {
        return gamePid;
    }

    /** 安装低级输入钩子；失败时 down() 自动退回 GetAsyncKeyState。 */
    public static synchronized void startInputHooks() {
        if (hooksActive || keyHook != null || mouseHook != null) {
            return;
        }
        try {
            Native.setCallbackExceptionHandler(new com.sun.jna.Callback.UncaughtExceptionHandler() {
                public void uncaughtException(com.sun.jna.Callback cb, Throwable t) {
                    // 钩子回调异常只吞掉，绝不能让异常穿回原生代码
                }
            });
        } catch (Throwable ignored) {
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    CallbackThreadInitializer init = new CallbackThreadInitializer(true, true, "MCAC-InputHook");
                    Native.setCallbackThreadInitializer(MOUSE_PROC, init);
                    Native.setCallbackThreadInitializer(KEY_PROC, init);
                    keyHook = User32.I.SetWindowsHookExW(WH_KEYBOARD_LL, KEY_PROC, null, 0);
                    mouseHook = User32.I.SetWindowsHookExW(WH_MOUSE_LL, MOUSE_PROC, null, 0);
                    hooksActive = keyHook != null || mouseHook != null;
                    if (hooksActive) {
                        snapshotHeldKeys();
                    }
                    Memory msg = new Memory(64);
                    while (User32.I.GetMessageW(msg, null, 0, 0) > 0) {
                        User32.I.TranslateMessage(msg);
                        User32.I.DispatchMessageW(msg);
                    }
                } catch (Throwable ignored) {
                    hooksActive = false;
                }
            }
        }, "MCAC-InputHook");
        t.setDaemon(true);
        t.start();
    }

    /** 启动时把已是按下状态的键补进集合（例如启动瞬间就按着鼠标）。 */
    private static void snapshotHeldKeys() {
        for (int vk = 1; vk < 255; vk++) {
            if ((User32.I.GetAsyncKeyState(vk) & 0x8000) != 0) {
                realDown.add(Integer.valueOf(vk));
            }
        }
    }

    public static boolean hooksActive() {
        return hooksActive;
    }

    private static int mouseVk(int msg) {
        switch (msg) {
            case WM_LBUTTONDOWN:
            case WM_LBUTTONUP:
                return 0x01;
            case WM_RBUTTONDOWN:
            case WM_RBUTTONUP:
                return 0x02;
            case WM_MBUTTONDOWN:
            case WM_MBUTTONUP:
                return 0x04;
            default:
                return 0;
        }
    }

    /** 用户真实按键状态；钩子未就绪时退回 GetAsyncKeyState。 */
    public static boolean down(int vk) {
        if (hooksActive) {
            return realDown.contains(Integer.valueOf(vk));
        }
        return (User32.I.GetAsyncKeyState(vk) & 0x8000) != 0;
    }

    public static void mouseMove(int dx, int dy) {
        if (dx == 0 && dy == 0) {
            return;
        }
        User32.I.mouse_event(MOUSEEVENTF_MOVE, dx, dy, 0, null);
    }

    public static void leftDown() {
        User32.I.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, null);
    }

    public static void leftUp() {
        User32.I.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, null);
    }

    public static void rightDown() {
        User32.I.mouse_event(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, null);
    }

    public static void rightUp() {
        User32.I.mouse_event(MOUSEEVENTF_RIGHTUP, 0, 0, 0, null);
    }

    public static void keyDown(int vk) {
        int scan = User32.I.MapVirtualKey(vk, 0);
        User32.I.keybd_event((byte) vk, (byte) scan, 0, null);
    }

    public static void keyUp(int vk) {
        int scan = User32.I.MapVirtualKey(vk, 0);
        User32.I.keybd_event((byte) vk, (byte) scan, KEYEVENTF_KEYUP, null);
    }

    private static String title(Pointer hwnd) {
        if (hwnd == null) {
            return null;
        }
        char[] buf = new char[256];
        int n = User32.I.GetWindowText(hwnd, buf, 256);
        return n > 0 ? new String(buf, 0, n) : null;
    }

    /** 当前前台窗口是否为游戏窗口（120ms 缓存）。 */
    public static boolean gameFocused() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 120L) {
            return focusedCache;
        }
        lastCheck = now;
        Pointer fg = User32.I.GetForegroundWindow();
        int pid = gamePid;
        if (pid > 0) {
            // 已知游戏 PID 时按进程判定：客户端可自定义窗口标题（如服务器名），标题匹配不可靠
            IntByReference ref = new IntByReference();
            User32.I.GetWindowThreadProcessId(fg, ref);
            if (ref.getValue() > 0) {
                focusedCache = ref.getValue() == pid;
                return focusedCache;
            }
        }
        focusedCache = isGameTitle(title(fg));
        return focusedCache;
    }

    /** 枚举标题包含 Minecraft 的窗口，返回其进程 PID；找不到返回 0。 */
    public static int findGamePid() {
        final int[] found = {0};
        User32.I.EnumWindows(new WndEnumProc() {
            public boolean callback(Pointer hwnd, Pointer lParam) {
                String t = title(hwnd);
                if (isGameTitle(t)) {
                    IntByReference pid = new IntByReference();
                    User32.I.GetWindowThreadProcessId(hwnd, pid);
                    if (pid.getValue() > 0) {
                        found[0] = pid.getValue();
                        return false;
                    }
                }
                return true;
            }
        }, null);
        return found[0];
    }

    public static String gameTitle() {
        final String[] found = {null};
        User32.I.EnumWindows(new WndEnumProc() {
            public boolean callback(Pointer hwnd, Pointer lParam) {
                String t = title(hwnd);
                if (isGameTitle(t)) {
                    found[0] = t;
                    return false;
                }
                return true;
            }
        }, null);
        return found[0];
    }

    public interface Kernel32 extends StdCallLibrary {
        Kernel32 I = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer GetCurrentProcess();
    }

    public interface Advapi32 extends StdCallLibrary {
        Advapi32 I = Native.load("advapi32", Advapi32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean OpenProcessToken(Pointer process, int access, PointerByReference token);

        boolean GetTokenInformation(Pointer token, int infoClass, Pointer info, int len, IntByReference retLen);

        boolean CloseHandle(Pointer handle);
    }

    public interface Shell32 extends StdCallLibrary {
        Shell32 I = Native.load("shell32", Shell32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer ShellExecuteW(Pointer hwnd, String operation, String file, String params, String dir, int show);
    }

    /**
     * 当前进程是否以管理员（高完整性）运行。
     * 目标以管理员运行时，普通权限的附加与输入注入都会被系统拒绝。
     */
    public static boolean isElevated() {
        PointerByReference token = new PointerByReference();
        try {
            if (!Advapi32.I.OpenProcessToken(Kernel32.I.GetCurrentProcess(), 0x0008, token)) {
                return false;
            }
            Memory info = new Memory(4);
            IntByReference len = new IntByReference();
            if (!Advapi32.I.GetTokenInformation(token.getValue(), 20, info, 4, len)) {
                return false;
            }
            return info.getInt(0) != 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (token.getValue() != null) {
                try {
                    Advapi32.I.CloseHandle(token.getValue());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 通过 UAC 以管理员身份启动指定程序；用户取消或失败返回 false。 */
    public static boolean runAsAdmin(String exe, String params) {
        try {
            Pointer r = Shell32.I.ShellExecuteW(null, "runas", exe, params, null, 1);
            return Pointer.nativeValue(r) > 32L;
        } catch (Throwable t) {
            return false;
        }
    }
}
