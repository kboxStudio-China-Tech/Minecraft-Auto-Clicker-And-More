package mcac.agent;

import mcac.bridge.PacketBacktrack;
import mcac.bridge.SensorBridge;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构发现：不依赖任何硬编码类名/字段名/方法名，适配 1.8.9 ~ 1.21.8
 * （Forge / NeoForge / Fabric / 原版混淆端）。
 *
 * 现代版本与 1.8 的主要差异：
 * <ul>
 *   <li>LocalPlayer 仍持有 Minecraft 字段，实体/AABB/朝向结构与 1.8 相同；</li>
 *   <li>世界玩家列表为 ClientLevel.players，全量实体通过 entitiesForRendering() 迭代；</li>
 *   <li>方块判定是 Level.isEmptyBlock(BlockPos)，BlockPos 的 3 个 int 在父类 Vec3i；</li>
 *   <li>手持物品改用 Item 的翻译键（block.* / 含 sword）判断，避免依赖 ItemBlock/SwordItem 类名；</li>
 *   <li>入站包类从 NetworkManager 变为 Connection，按 netty Channel 字段结构定位。</li>
 * </ul>
 */
final class Discovery implements Runnable {

    private final Instrumentation inst;

    private Class<?> mcClass;
    private Method getMc;
    private Object mc;

    private Class<?> injectedPlayerClass;
    private Method cachedInbound;

    private final Map<Class<?>, Boolean> listFieldCache = new HashMap<Class<?>, Boolean>();

    Discovery(Instrumentation inst) {
        this.inst = inst;
    }

    public void run() {
        Log.log("运行环境: Java " + System.getProperty("java.version")
                + " / " + System.getProperty("java.vendor"));
        int tries = 0;
        while (tries++ < 3000) {
            try {
                if (attempt()) {
                    Log.log("注入与配置完成");
                    return;
                }
            } catch (Throwable t) {
                Log.log("发现过程异常", t);
            }
            try {
                Thread.sleep(600L);
            } catch (InterruptedException e) {
                return;
            }
        }
        Log.log("发现超时退出");
    }

    private boolean attempt() throws Exception {
        if (mcClass == null) {
            mcClass = findMinecraftClass();
            if (mcClass == null) {
                Log.once("等待 Minecraft 类加载");
                return false;
            }
            getMc = findSelfGetter(mcClass);
            if (getMc == null) {
                mcClass = null;
                return false;
            }
            Log.log("定位 Minecraft 类: " + mcClass.getName() + " / " + getMc.getName());
        }

        Object current = getMc.invoke(null);
        if (current != null) {
            mc = current;
        }
        if (mc == null) {
            Log.once("等待游戏实例");
            return false;
        }

        Object player = findPlayer();
        if (player == null) {
            Log.once("等待进入世界（未找到玩家实例）");
            return false;
        }
        Class<?> playerClass = player.getClass();
        if (injectedPlayerClass == playerClass) {
            return true;
        }

        Class<?> entityClass = topAncestor(playerClass);
        if (entityClass == playerClass || entityClass == Object.class) {
            Log.once("玩家类继承链异常");
            return false;
        }
        Log.log("玩家类: " + playerClass.getName() + " 实体基类: " + entityClass.getName());

        Field boxField = findBoxField(player, playerClass);
        if (boxField == null) {
            Log.once("未找到实体碰撞箱字段");
            return false;
        }
        Field[] boxFields = fieldsOfType(boxField.getType(), double.class);
        double[] box = readBox(boxFields, boxField.get(player));

        Object[] worldTriple = findWorld(player);
        if (worldTriple == null) {
            Log.once("未找到世界/实体列表");
            return false;
        }
        Object world = worldTriple[0];
        Field worldField = (Field) worldTriple[1];
        Field listField = (Field) worldTriple[2];

        // 必须用玩家中心点采样：碰撞箱最小角可能落在相邻的悬空方块列上
        Object[] airPair = findAirMethod(world, (box[0] + box[3]) * 0.5D, box[1], (box[2] + box[5]) * 0.5D);
        Method isAir = airPair == null ? null : (Method) airPair[0];
        Constructor<?> posCtor = airPair == null ? null : (Constructor<?>) airPair[1];
        if (isAir == null) {
            Log.once("未找到方块读取方法（鹰眼不可用）");
        } else {
            Log.log("方块读取: " + isAir.getName() + "(" + isAir.getParameterTypes()[0].getName() + ")");
        }

        Method entityIter = findEntityIter(world, entityClass);
        if (entityIter != null) {
            Log.log("实体遍历: " + entityIter.getName());
        }

        Method look = null;
        Field[] lookFields = null;
        for (Class<?> k = entityClass; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0) {
                    continue;
                }
                Field[] df = fieldsOfType(m.getReturnType(), double.class);
                if (df.length != 3) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object vec = m.invoke(player);
                    if (vec == null) {
                        continue;
                    }
                    for (Field d : df) {
                        d.setAccessible(true);
                    }
                    double x = df[0].getDouble(vec);
                    double y = df[1].getDouble(vec);
                    double z = df[2].getDouble(vec);
                    double len = Math.sqrt(x * x + y * y + z * z);
                    if (len > 0.9D && len < 1.1D) {
                        look = m;
                        lookFields = df;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (look != null) {
                break;
            }
        }

        Field yawField = null;
        Field pitchField = null;
        if (look == null) {
            Field[] floats = fieldsOfType(entityClass, float.class);
            for (int i = 0; i + 3 < floats.length; i++) {
                try {
                    for (int j = 0; j < 4; j++) {
                        floats[i + j].setAccessible(true);
                    }
                    float a = floats[i].getFloat(player);
                    float b = floats[i + 1].getFloat(player);
                    float c = floats[i + 2].getFloat(player);
                    float d = floats[i + 3].getFloat(player);
                    if (a >= -180F && a <= 180F && b >= -90F && b <= 90F
                            && c >= -180F && c <= 180F && d >= -90F && d <= 90F) {
                        yawField = floats[i];
                        pitchField = floats[i + 1];
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (look == null && yawField == null) {
            Log.once("未找到视角朝向");
            return false;
        } else {
            Log.log("朝向来源: " + (look != null ? look.getName() : yawField.getName() + "/" + pitchField.getName()));
        }

        List<Method> ticks = findTickMethods(playerClass, entityClass);
        if (ticks.isEmpty()) {
            Log.once("未找到 tick 方法");
            return false;
        }
        Log.log("注入点: " + ticks);

        ClassLoader loader = playerClass.getClassLoader();
        Class<?> blockItem = firstLoaded(loader,
                "net.minecraft.item.ItemBlock", "net.minecraft.world.item.BlockItem");
        Class<?> swordItem = firstLoaded(loader,
                "net.minecraft.item.ItemSword", "net.minecraft.world.item.SwordItem");
        Class<?> playerEntity = playerClass.getSuperclass();
        if (playerEntity == null || playerEntity == entityClass || playerEntity == Object.class) {
            playerEntity = firstLoaded(loader,
                    "net.minecraft.entity.player.EntityPlayer", "net.minecraft.world.entity.player.Player");
        }

        List<Object[]> held = findHeldCandidates(player, playerClass, blockItem, swordItem);
        List<Method> heldDesc = new ArrayList<Method>();
        for (Object[] h : held) {
            List<Method> ds = findDescriptionGetters(((Method) h[1]).getReturnType());
            for (Method d : ds) {
                if (!heldDesc.contains(d)) {
                    heldDesc.add(d);
                }
            }
        }
        if (held.isEmpty()) {
            Log.log("未找到手持物品读取方法（手持过滤不可用）");
        } else {
            Log.log("手持物品候选: " + held.size() + " 个 / 翻译键方法 " + heldDesc.size() + " 个");
        }
        if (playerEntity == null) {
            Log.log("未找到玩家实体基类（受击来源判定退化）");
        } else {
            Log.log("玩家基类: " + playerEntity.getName());
        }

        Method inbound = cachedInbound;
        if (inbound == null) {
            inbound = findInboundMethod(loader);
            cachedInbound = inbound;
        }
        if (inbound == null) {
            Log.log("未找到入站包处理方法（回溯不可用）");
        } else {
            Log.log("入站包处理: " + inbound.getDeclaringClass().getName() + "." + inbound.getName());
        }

        Injector injector = new Injector();
        injector.add(playerClass.getName().replace('.', '/'), ticks);
        if (inbound != null) {
            Class<?> netClass = inbound.getDeclaringClass();
            injector.addGate(netClass.getName().replace('.', '/'), inbound);
            PacketBacktrack.configure(inbound);
        }
        inst.addTransformer(injector, true);
        inst.retransformClasses(playerClass);
        if (inbound != null) {
            try {
                inst.retransformClasses(inbound.getDeclaringClass());
            } catch (Throwable t) {
                Log.log("回溯注入失败", t);
            }
        }

        SensorBridge.Config cfg = new SensorBridge.Config();
        cfg.player = player;
        cfg.playerClass = playerClass;
        cfg.mc = mc;
        cfg.worldField = worldField;
        cfg.listField = listField;
        cfg.entityIter = entityIter;
        cfg.entityClass = entityClass;
        cfg.boxField = boxField;
        cfg.boxFields = boxFields;
        cfg.lookMethod = look;
        cfg.lookFields = lookFields;
        cfg.yawField = yawField;
        cfg.pitchField = pitchField;
        cfg.isAir = isAir;
        cfg.posCtor = posCtor;
        cfg.held = held;
        cfg.heldDesc = heldDesc;
        cfg.blockItemClass = blockItem;
        cfg.swordItemClass = swordItem;
        cfg.playerEntityClass = playerEntity;
        SensorBridge.configure(cfg);

        injectedPlayerClass = playerClass;
        Log.log("探针配置完成: 实体列表=" + listField.getName()
                + " 全量实体=" + (entityIter == null ? "无" : entityIter.getName())
                + " 方块判定=" + (isAir == null ? "无" : "有"));
        if (isAir == null) {
            startAirRetry(cfg);
        }
        return true;
    }

    /**
     * 玩家离地（飞行/跳跃/乘坐）时脚下是空气，无法区分“测试空气”的方法；
     * 这里持续等待，直到落地瞬间再补装鹰眼，避免注入时正在飞行导致永久不可用。
     */
    private static void startAirRetry(final SensorBridge.Config cfg) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (cfg.isAir == null) {
                    try {
                        Thread.sleep(500L);
                        Object player = cfg.player;
                        Object world = cfg.worldField == null ? null : cfg.worldField.get(cfg.mc);
                        if (player == null || world == null) {
                            continue;
                        }
                        Object box = cfg.boxField.get(player);
                        if (box == null) {
                            continue;
                        }
                        double[] b = readBox(cfg.boxFields, box);
                        Object[] pair = findAirMethod(world, (b[0] + b[3]) * 0.5D, b[1], (b[2] + b[5]) * 0.5D);
                        if (pair != null) {
                            SensorBridge.setAir((Method) pair[0], (Constructor<?>) pair[1]);
                            Log.log("方块读取补装完成: " + ((Method) pair[0]).getName());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "MCAC-AirRetry");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 客户端主类名：命名映射（Forge/NeoForge/官方映射）、Fabric intermediary、1.8.9 原版混淆。
     * 名称匹配最可靠，结构判据只作为兜底。
     */
    private static final String[] CLIENT_NAMES = {
            "net.minecraft.client.Minecraft",
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.class_310",
            "ave"
    };

    private Class<?> findMinecraftClass() {
        Class<?> known = findNamedClient();
        if (known != null) {
            Log.log("定位 Minecraft 类（名称）: " + known.getName());
            return known;
        }
        Class<?>[] loaded = inst.getAllLoadedClasses();
        Class<?> best = null;
        int bestScore = 0;
        int selfGetters = 0;
        StringBuilder near = new StringBuilder();
        for (Class<?> c : loaded) {
            try {
                if (!eligible(c) || findSelfGetter(c) == null) {
                    continue;
                }
                selfGetters++;
                int score = 0;
                if (hasEntityLikeField(c)) {
                    score += 2;
                }
                if (hasSelfBackref(c)) {
                    score += 3;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
                if (score > 0 && near.length() < 240) {
                    near.append(c.getName()).append('=').append(score).append(' ');
                }
            } catch (Throwable ignored) {
            }
        }
        if (best != null && bestScore >= 5) {
            Log.log("定位 Minecraft 类（结构）: " + best.getName());
            return best;
        }
        if (near.length() > 0) {
            Log.once("未命中客户端主类: " + near);
        } else {
            Log.once("等待 Minecraft 类加载（已加载 " + loaded.length + " 个类，自取类 " + selfGetters + " 个）");
        }
        return null;
    }

    private Class<?> findNamedClient() {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            try {
                if (!eligible(c) || !isClientName(c.getName())) {
                    continue;
                }
                if (findSelfGetter(c) == null) {
                    continue;
                }
                return c;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isClientName(String n) {
        for (String s : CLIENT_NAMES) {
            if (s.equals(n)) {
                return true;
            }
        }
        return false;
    }

    private Object findPlayer() {
        Object p = scanPlayer(true);
        if (p != null) {
            return p;
        }
        // 现代版本位置/朝向结构变化时退化：只要求类型深度足够且反向持有 Minecraft 字段
        return scanPlayer(false);
    }

    private Object scanPlayer(boolean strict) {
        for (Field f : allFields(mcClass)) {
            try {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isArray() || t == String.class || t == mcClass || t.isInterface()) {
                    continue;
                }
                if (strict ? !isEntityLike(t) : depthOf(t) < 3) {
                    continue;
                }
                if (!declaresFieldOfType(t, mcClass)) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(mc);
                if (v != null) {
                    Log.log("玩家字段: " + f.getName() + " -> " + v.getClass().getName());
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 碰撞箱：6 个 double 的字段，且当前值满足 min < max、尺寸在玩家范围内。 */
    private static Field findBoxField(Object player, Class<?> playerClass) {
        for (Class<?> k = playerClass; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                try {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Class<?> t = f.getType();
                    if (t.isPrimitive() || t.isArray() || t.isInterface()) {
                        continue;
                    }
                    Field[] df = fieldsOfType(t, double.class);
                    if (df.length != 6) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object box = f.get(player);
                    if (box == null) {
                        continue;
                    }
                    for (Field d : df) {
                        d.setAccessible(true);
                    }
                    double[] v = new double[6];
                    for (int i = 0; i < 6; i++) {
                        v[i] = df[i].getDouble(box);
                    }
                    double h = v[4] - v[1];
                    double w = v[3] - v[0];
                    if (v[0] < v[3] && v[1] < v[4] && v[2] < v[5]
                            && h >= 0.5D && h <= 3.6D && w >= 0.1D && w <= 2.6D) {
                        return f;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 世界 = Minecraft 中一个非空字段，其类里存在包含玩家实例的 List。
     * 1.8 为 World.loadedEntityList，1.20+ 为 ClientLevel.players。
     */
    private Object[] findWorld(Object player) {
        for (Field f : allFields(mcClass)) {
            try {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isArray() || t.isInterface() || t == String.class) {
                    continue;
                }
                if (!hasListField(t)) {
                    continue;
                }
                f.setAccessible(true);
                Object w = f.get(mc);
                if (w == null || w == player) {
                    continue;
                }
                for (Field g : allFields(w.getClass())) {
                    if (Modifier.isStatic(g.getModifiers()) || !List.class.isAssignableFrom(g.getType())) {
                        continue;
                    }
                    g.setAccessible(true);
                    Object list = g.get(w);
                    if (list instanceof List && ((List<?>) list).contains(player)) {
                        return new Object[]{w, f, g};
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean hasListField(Class<?> c) {
        Boolean v = listFieldCache.get(c);
        if (v != null) {
            return v.booleanValue();
        }
        boolean has = false;
        for (Class<?> k = c; k != null && k != Object.class && !has; k = k.getSuperclass()) {
            try {
                for (Field f : k.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && List.class.isAssignableFrom(f.getType())) {
                        has = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        listFieldCache.put(c, Boolean.valueOf(has));
        return has;
    }

    /**
     * 全量实体遍历方法（1.20+：ClientLevel.entitiesForRendering / Level.getAllEntities）。
     * 选取“返回 Iterable 且所有元素都是实体”的方法中元素最多的一个；
     * 世界中暂时没有任何实体时退回按名称识别，运行期再实际读取。
     */
    private static Method findEntityIter(Object world, Class<?> entityClass) {
        Method best = null;
        int bestCount = 0;
        Method named = null;
        for (Method m : world.getClass().getMethods()) {
            try {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0) {
                    continue;
                }
                if (!Iterable.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                String name = m.getName().toLowerCase();
                if (named == null && (name.contains("entitiesforrendering") || name.contains("getallentities"))) {
                    named = m;
                }
                m.setAccessible(true);
                Object raw = m.invoke(world);
                if (!(raw instanceof Iterable)) {
                    continue;
                }
                int total = 0;
                int entities = 0;
                for (Object e : (Iterable<?>) raw) {
                    if (e == null) {
                        continue;
                    }
                    total++;
                    if (entityClass.isInstance(e)) {
                        entities++;
                    }
                    if (total >= 32) {
                        break;
                    }
                }
                if (total == 0 || entities != total) {
                    continue;
                }
                if (total > bestCount) {
                    best = m;
                    bestCount = total;
                }
            } catch (Throwable ignored) {
            }
        }
        if (best != null) {
            return best;
        }
        return named;
    }

    private List<Method> findTickMethods(Class<?> playerClass, Class<?> entityClass) {
        List<Method> out = new ArrayList<Method>();
        for (Method m : playerClass.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (!Modifier.isPublic(mod) || Modifier.isStatic(mod)
                    || Modifier.isAbstract(mod) || Modifier.isNative(mod)) {
                continue;
            }
            if (m.getParameterTypes().length != 0 || m.getReturnType() != void.class) {
                continue;
            }
            if (m.isSynthetic() || m.isBridge()) {
                continue;
            }
            if (findMethod(entityClass, m.getName()) == null) {
                continue;
            }
            out.add(m);
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    return m;
                }
            }
        }
        return null;
    }

    private static Object[] findAirMethod(Object world, double centerX, double footY, double centerZ) {
        Class<?> wc = world.getClass();
        int px = (int) Math.floor(centerX);
        int pySolid = (int) Math.floor(footY - 0.1D);
        int pz = (int) Math.floor(centerZ);
        // 头顶可能贴着天花板或长着草，取多个高度，只要其中一个为空气即可
        int[] pyAir = {
                (int) Math.floor(footY + 0.5D),
                (int) Math.floor(footY + 1.05D),
                (int) Math.floor(footY + 1.7D)
        };
        for (Method m : wc.getMethods()) {
            try {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != boolean.class) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1) {
                    continue;
                }
                Class<?> p = ps[0];
                if (p.isPrimitive() || p.isArray() || p.isInterface() || p.getClassLoader() == null) {
                    continue;
                }
                if (intFieldCount(p) != 3) {
                    continue;
                }
                Constructor<?> ctor = p.getDeclaredConstructor(int.class, int.class, int.class);
                ctor.setAccessible(true);
                // 脚下必须是“非空气”，才能确认该方法返回 true 表示空气
                if (!Boolean.FALSE.equals(m.invoke(world, ctor.newInstance(px, pySolid, pz)))) {
                    continue;
                }
                for (int y : pyAir) {
                    if (Boolean.TRUE.equals(m.invoke(world, ctor.newInstance(px, y, pz)))) {
                        return new Object[]{m, ctor};
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** BlockPos 继承 Vec3i，坐标 int 字段声明在父类，需要跨继承链统计。 */
    private static int intFieldCount(Class<?> c) {
        int n = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            n += fieldsOfType(k, int.class).length;
        }
        return n;
    }

    private static double[] readBox(Field[] df, Object box) throws Exception {
        double[] v = new double[6];
        for (int i = 0; i < 6; i++) {
            v[i] = df[i].getDouble(box);
        }
        return v;
    }

    /**
     * 入站包处理类结构定位：
     * 持有 netty Channel 字段，且声明了 void (ChannelHandlerContext, 非 Throwable) 方法。
     * 1.8 为 NetworkManager.channelRead0，1.20+ 为 Connection.channelRead0。
     */
    private Method findInboundMethod(ClassLoader loader) {
        Method best = null;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            try {
                if (!eligible(c) || Modifier.isAbstract(c.getModifiers()) || c.isInterface()) {
                    continue;
                }
                if (!hasChannelField(c)) {
                    continue;
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) || Modifier.isAbstract(m.getModifiers())) {
                        continue;
                    }
                    if (m.isSynthetic() || m.isBridge() || m.getReturnType() != void.class) {
                        continue;
                    }
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length != 2) {
                        continue;
                    }
                    if (!"io.netty.channel.ChannelHandlerContext".equals(ps[0].getName())) {
                        continue;
                    }
                    if (Throwable.class.isAssignableFrom(ps[1])) {
                        continue;
                    }
                    m.setAccessible(true);
                    if (best == null || preferred(best) == null && preferred(m) != null) {
                        best = m;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (best != null) {
            return best;
        }
        // 兜底：按常见类名读取（Forge/NeoForge 类名完整可见）
        Class<?> named = firstLoaded(loader,
                "net.minecraft.network.Connection", "net.minecraft.network.NetworkManager");
        if (named != null) {
            for (Method m : named.getDeclaredMethods()) {
                try {
                    Class<?>[] ps = m.getParameterTypes();
                    if (m.getReturnType() == void.class && ps.length == 2
                            && ps[0].getName().contains("ChannelHandlerContext")) {
                        m.setAccessible(true);
                        return m;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 命名偏好：Connection / NetworkManager 优先于其它持有 Channel 的处理器。 */
    private static String preferred(Method m) {
        String n = m.getDeclaringClass().getName();
        if (n.endsWith("Connection") || n.contains("NetworkManager")) {
            return n;
        }
        return null;
    }

    private static boolean hasChannelField(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                for (Field f : k.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())
                            && "io.netty.channel.Channel".equals(f.getType().getName())) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 手持物品读取链：
     * 玩家类上无参方法 -> ItemStack -> Item -> 翻译键方法（1.20+ getDescriptionId / 1.8 getUnlocalizedName）。
     * 优先使用 ItemBlock/ItemSword 的 instanceof 判定（1.8 与官方映射端），
     * 其他情况用翻译键前缀（block. 与 tile.）及 sword 关键字判断。
     */
    private static List<Object[]> findHeldCandidates(Object player, Class<?> playerClass,
                                                    Class<?> blockItem, Class<?> swordItem) {
        List<Object[]> out = new ArrayList<Object[]>();
        if (blockItem != null && swordItem != null) {
            Object[] legacy = findHeldAccessors(playerClass, blockItem, swordItem);
            if (legacy != null) {
                Method desc = findDescriptionGetter(((Method) legacy[1]).getReturnType(), null);
                out.add(new Object[]{legacy[0], legacy[1], desc});
            }
        }
        for (Class<?> k = playerClass; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                try {
                    if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0
                            || m.isSynthetic() || m.isBridge()) {
                        continue;
                    }
                    Class<?> stackType = m.getReturnType();
                    if (!isPlainClass(stackType) || stackType == String.class) {
                        continue;
                    }
                    if (sameMethod(out, m)) {
                        continue;
                    }
                    Object[] probe = findItemChain(stackType, null);
                    if (probe == null) {
                        continue;
                    }
                    m.setAccessible(true);
                    Object stack = null;
                    try {
                        stack = m.invoke(player);
                    } catch (Throwable ignored) {
                    }
                    Object[] chain = stack == null ? null : findItemChain(stackType, stack);
                    if (chain == null) {
                        // 1.8 空手时 getHeldItem() 为 null，只能用结构特征兜底
                        if (!isLikelyItemGetter((Method) probe[0])) {
                            continue;
                        }
                        chain = probe;
                    }
                    out.add(new Object[]{m, chain[0], chain[1]});
                    if (out.size() >= 6) {
                        return out;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (out.isEmpty()) {
            // 结构探测全部失败时，按类名特征兜底（ItemStack / Item 名称在命名映射端稳定）
            for (Class<?> k = playerClass; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    try {
                        if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0
                                || m.isSynthetic() || m.isBridge()) {
                            continue;
                        }
                        Class<?> rt = m.getReturnType();
                        if (!isPlainClass(rt) || !rt.getSimpleName().toLowerCase().contains("itemstack")) {
                            continue;
                        }
                        for (Method g : rt.getMethods()) {
                            if (Modifier.isStatic(g.getModifiers()) || g.getParameterTypes().length != 0) {
                                continue;
                            }
                            Class<?> it = g.getReturnType();
                            if (!isPlainClass(it) || !it.getSimpleName().toLowerCase().contains("item")) {
                                continue;
                            }
                            m.setAccessible(true);
                            g.setAccessible(true);
                            out.add(new Object[]{m, g, null});
                            return out;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return out;
    }

    /**
     * 在 ItemStack 类上找无参、返回 Item 的方法。
     * stack 非空时用真实物品的翻译键校验，为空时只做结构筛选。
     */
    private static Object[] findItemChain(Class<?> stackType, Object stack) {
        Method bestG = null;
        Method bestD = null;
        int bestScore = -1;
        for (Class<?> k = stackType; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method g : k.getDeclaredMethods()) {
                try {
                    if (Modifier.isStatic(g.getModifiers()) || g.getParameterTypes().length != 0
                            || g.isSynthetic() || g.isBridge()) {
                        continue;
                    }
                    Class<?> itemType = g.getReturnType();
                    if (!isPlainClass(itemType) || itemType == String.class) {
                        continue;
                    }
                    g.setAccessible(true);
                    Object item = null;
                    if (stack != null) {
                        item = g.invoke(stack);
                        if (item == null) {
                            continue;
                        }
                    }
                    Method desc = findDescriptionGetter(itemType, item);
                    if (desc == null) {
                        continue;
                    }
                    if (item != null) {
                        return new Object[]{g, desc};
                    }
                    int score = (g.getName().toLowerCase().contains("item")
                            || itemType.getSimpleName().toLowerCase().contains("item")) ? 2 : 0;
                    if (score > bestScore) {
                        bestScore = score;
                        bestG = g;
                        bestD = desc;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return bestG == null ? null : new Object[]{bestG, bestD};
    }

    private static boolean isLikelyItemGetter(Method g) {
        String n = g.getName().toLowerCase();
        String r = g.getReturnType().getSimpleName().toLowerCase();
        return n.contains("item") || r.contains("item");
    }

    /**
     * Item 的翻译键方法。item 非空时用实际返回值校验（"item."/"block."/"tile." 前缀），
     * 为空（1.8 空手）时按方法名偏好选择。
     */
    private static Method findDescriptionGetter(Class<?> itemType, Object item) {
        Method fallback = null;
        for (Class<?> k = itemType; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method d : k.getDeclaredMethods()) {
                try {
                    if (Modifier.isStatic(d.getModifiers()) || d.getParameterTypes().length != 0
                            || d.getReturnType() != String.class || d.isSynthetic()) {
                        continue;
                    }
                    d.setAccessible(true);
                    if (item != null) {
                        Object s = d.invoke(item);
                        if (s instanceof String && looksLikeItemId((String) s)) {
                            return d;
                        }
                    } else {
                        String n = d.getName().toLowerCase();
                        if (n.contains("description") || n.contains("unlocalized")) {
                            return d;
                        }
                        if (fallback == null) {
                            fallback = d;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return fallback;
    }

    private static boolean looksLikeItemId(String s) {
        return s.startsWith("item.") || s.startsWith("block.") || s.startsWith("tile.");
    }

    /**
     * Item 上全部无参 String 方法（翻译键候选）。
     * SRG 环境方法名不可判断语义，只能把候选交给运行时逐个尝试；
     * 命名映射环境优先把 description/unlocalized 排在前面。
     */
    private static List<Method> findDescriptionGetters(Class<?> itemType) {
        List<Method> preferred = new ArrayList<Method>();
        List<Method> rest = new ArrayList<Method>();
        for (Class<?> k = itemType; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method d : k.getDeclaredMethods()) {
                try {
                    if (Modifier.isStatic(d.getModifiers()) || d.getParameterTypes().length != 0
                            || d.getReturnType() != String.class || d.isSynthetic()) {
                        continue;
                    }
                    d.setAccessible(true);
                    String n = d.getName().toLowerCase();
                    if (n.contains("description") || n.contains("unlocalized")) {
                        preferred.add(d);
                    } else {
                        rest.add(d);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        preferred.addAll(rest);
        return preferred;
    }

    /**
     * 1.8 路径：玩家类上无参、返回 ItemStack 的方法 → ItemStack 上无参、返回 Item 的方法。
     * ItemBlock/ItemSword 的父类只有 Item，因此用“父类型可接收二者”作为结构特征。
     */
    private static Object[] findHeldAccessors(Class<?> playerClass, Class<?> itemBlock, Class<?> itemSword) {
        for (Class<?> k = playerClass; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0) {
                    continue;
                }
                Class<?> stack = m.getReturnType();
                if (!isPlainClass(stack)) {
                    continue;
                }
                Method getItem = null;
                for (Method g : stack.getMethods()) {
                    if (Modifier.isStatic(g.getModifiers()) || g.getParameterTypes().length != 0) {
                        continue;
                    }
                    Class<?> t = g.getReturnType();
                    if (!isPlainClass(t) || t == Object.class) {
                        continue;
                    }
                    if (t.isAssignableFrom(itemBlock) && t.isAssignableFrom(itemSword)) {
                        getItem = g;
                        break;
                    }
                }
                if (getItem == null) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    getItem.setAccessible(true);
                    return new Object[]{m, getItem};
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean sameMethod(List<Object[]> list, Method m) {
        for (Object[] o : list) {
            if (o[0].equals(m)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlainClass(Class<?> c) {
        return c != null && !c.isPrimitive() && !c.isArray() && !c.isInterface() && c != Object.class;
    }

    private static Class<?> firstLoaded(ClassLoader loader, String... names) {
        for (String n : names) {
            Class<?> c = loadClass(loader, n);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    private static Class<?> loadClass(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
        }
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findSelfGetter(Class<?> c) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0
                        && m.getReturnType() == c) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean hasEntityLikeField(Class<?> c) {
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (isEntityLike(f.getType())) {
                return true;
            }
        }
        return false;
    }

    /** Minecraft 类中存在一个类型反过来持有 Minecraft 字段（如 LocalPlayer.minecraft）。 */
    private static boolean hasSelfBackref(Class<?> c) {
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (declaresFieldOfType(f.getType(), c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresFieldOfType(Class<?> owner, Class<?> type) {
        try {
            for (Field f : owner.getDeclaredFields()) {
                if (f.getType() == type) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isEntityLike(Class<?> c) {
        if (c == null || c.isPrimitive() || c.isArray() || c.isInterface() || c.isEnum()) {
            return false;
        }
        if (depthOf(c) < 3) {
            return false;
        }
        Class<?> top = topAncestor(c);
        if (fieldsOfType(top, float.class).length < 2) {
            return false;
        }
        if (fieldsOfType(top, double.class).length >= 3) {
            return true;
        }
        // 1.17+ 位置改为 Vec3（3 个 double）字段，实体类不再直接声明 3 个 double
        return hasVectorField(top);
    }

    /** 位置向量：3 个 double、无 float 的小型值对象（Vec3 / Vector3d）。 */
    private static boolean hasVectorField(Class<?> owner) {
        for (Field f : owner.getDeclaredFields()) {
            try {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> t = f.getType();
                if (!isPlainClass(t)) {
                    continue;
                }
                if (fieldsOfType(t, double.class).length == 3 && fieldsOfType(t, float.class).length == 0) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static int depthOf(Class<?> c) {
        int depth = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            depth++;
        }
        return depth;
    }

    private static Class<?> topAncestor(Class<?> c) {
        Class<?> k = c;
        while (k.getSuperclass() != null && k.getSuperclass() != Object.class) {
            k = k.getSuperclass();
        }
        return k;
    }

    private static Field[] fieldsOfType(Class<?> c, Class<?> type) {
        List<Field> out = new ArrayList<Field>();
        try {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == type) {
                    out.add(f);
                }
            }
        } catch (Throwable ignored) {
        }
        return out.toArray(new Field[0]);
    }

    private static List<Field> allFields(Class<?> c) {
        List<Field> out = new ArrayList<Field>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                for (Field f : k.getDeclaredFields()) {
                    out.add(f);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static boolean eligible(Class<?> c) {
        if (c.getClassLoader() == null || c.isArray() || c.isPrimitive() || c.isInterface() || c.isEnum()) {
            return false;
        }
        String n = c.getName();
        return !(n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.")
                || n.startsWith("sun.") || n.startsWith("com.sun.") || n.startsWith("mcac.")
                || n.startsWith("org.lwjgl") || n.startsWith("org.objectweb")
                || n.startsWith("org.apache") || n.startsWith("com.mojang"));
    }
}
