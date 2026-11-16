package mcac.bridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 回溯（Backtrack）：通过“暂停处理入站数据包”实现。
 *
 * 注入点在 NetworkManager 的入站包处理方法入口：{@link #intercept} 返回 true 时直接返回，
 * 包被缓存而不是交给后续逻辑，因此服务端的位置/实体更新会短暂停在旧状态；
 * 关闭回溯或达到最长回溯时间后，在同一个 netty 线程内按原顺序回放。
 *
 * 只在攻击时由客户端下发 {@code B 1 <ms> <slow>} 激活，松开攻击下发 {@code B 0}。
 * slow=1 时不再一次性回放，而是按约 30 包/秒逐批释放，避免位置瞬间回拉。
 */
public final class PacketBacktrack {

    private static final Object LOCK = new Object();
    private static final List<Object[]> QUEUE = new ArrayList<Object[]>();

    /** 缓慢释放速率（包/秒）。 */
    private static final double SLOW_RATE = 30.0D;

    private static volatile Method inbound;
    private static volatile boolean active;
    private static volatile boolean exhausted;
    private static volatile boolean replaying;
    private static volatile boolean slow;
    /** 已关闭回溯但仍在缓慢排空缓存。 */
    private static volatile boolean draining;
    private static volatile long maxNanos = 300_000_000L;
    private static long startedAt;
    private static long lastDrainAt;
    private static double drainBudget;

    private PacketBacktrack() {
    }

    public static void configure(Method method) {
        inbound = method;
    }

    /** 由客户端控制命令调用：激活/关闭回溯并设置最长延迟。关闭时沿用上次的释放模式。 */
    public static void set(boolean on, long maxMs, boolean slowMode) {
        synchronized (LOCK) {
            if (on) {
                slow = slowMode;
                maxNanos = Math.max(0L, Math.min(5000L, maxMs)) * 1_000_000L;
                exhausted = false;
                startedAt = 0L;
                drainBudget = 0.0D;
                lastDrainAt = 0L;
                draining = false;
                active = true;
            } else {
                active = false;
                exhausted = false;
                if (slow && !QUEUE.isEmpty()) {
                    // 缓慢模式：把剩余缓存交给后续 intercept 逐批排空
                    draining = true;
                    drainBudget = 0.0D;
                    lastDrainAt = 0L;
                } else {
                    draining = false;
                }
            }
        }
    }

    /**
     * 入站包闸门。返回 true 表示该包已被缓存，调用方应立即返回。
     */
    public static boolean intercept(Object self, Object ctx, Object packet) {
        if (replaying) {
            return false;
        }
        Method m = inbound;
        if (m == null) {
            return false;
        }
        long now = System.nanoTime();
        synchronized (LOCK) {
            if (!active) {
                if (!QUEUE.isEmpty()) {
                    if (draining) {
                        drainSlowLocked(m, now);
                    } else {
                        flushLocked(m);
                    }
                }
                return false;
            }
            if (exhausted) {
                return false;
            }
            if (QUEUE.isEmpty()) {
                startedAt = now;
            }
            if (now - startedAt >= maxNanos) {
                // 达到最长回溯时间：停止缓存；缓慢模式交给后续排空，否则立即一次回放
                exhausted = true;
                if (slow) {
                    draining = true;
                    drainBudget = 0.0D;
                    lastDrainAt = 0L;
                } else {
                    flushLocked(m);
                }
                return false;
            }
            QUEUE.add(new Object[]{self, ctx, packet});
            return true;
        }
    }

    /** 按时间预算逐批回放；每次 intercept 调用先排空该释放的包再继续正常处理。 */
    private static void drainSlowLocked(Method m, long now) {
        if (lastDrainAt == 0L) {
            lastDrainAt = now;
        }
        double dt = (now - lastDrainAt) / 1.0E9D;
        lastDrainAt = now;
        drainBudget = Math.min(4.0D, drainBudget + Math.max(0.0D, dt) * SLOW_RATE);
        int n = (int) drainBudget;
        if (n <= 0) {
            return;
        }
        drainBudget -= n;
        draining = true;
        replaying = true;
        try {
            for (int i = 0; i < n && !QUEUE.isEmpty(); i++) {
                Object[] e = QUEUE.remove(0);
                try {
                    m.invoke(e[0], e[1], e[2]);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            replaying = false;
            draining = !QUEUE.isEmpty();
        }
    }

    private static void flushLocked(Method m) {
        if (QUEUE.isEmpty()) {
            return;
        }
        List<Object[]> copy = new ArrayList<Object[]>(QUEUE);
        QUEUE.clear();
        draining = false;
        replaying = true;
        try {
            for (Object[] e : copy) {
                try {
                    m.invoke(e[0], e[1], e[2]);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            replaying = false;
        }
    }
}
