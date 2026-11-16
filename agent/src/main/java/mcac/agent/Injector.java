package mcac.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读注入：
 * <ul>
 *   <li>tick 目标：方法头插入 {@code SensorBridge.hook(this)}</li>
 *   <li>入站包目标：方法头插入 {@code PacketBacktrack.intercept(this, ctx, packet)}，
 *       返回 true 时直接 RETURN，实现回溯期间暂停处理服务端数据包</li>
 * </ul>
 * 不新增成员、不改签名。
 */
final class Injector implements ClassFileTransformer {

    private static final String BRIDGE = "mcac/bridge/SensorBridge";
    private static final String PACKET = "mcac/bridge/PacketBacktrack";

    /**
     * 运行时 ASM 支持的最高 API 版本。
     * Forge 1.8.9 自带 asm-all-5.0.3，其 ClassVisitor/MethodVisitor 只接受 ASM4/ASM5；
     * 1.20+ 自带 ASM 9.x，需要 ASM7 以上才能读取 NestHost 等新属性。
     */
    private static final int API = detectApi();

    private static int detectApi() {
        int api = Opcodes.ASM4;
        if (opcodes("ASM5")) {
            api = Opcodes.ASM5;
        }
        if (opcodes("ASM6")) {
            api = Opcodes.ASM6;
        }
        if (opcodes("ASM7")) {
            api = Opcodes.ASM7;
        }
        if (opcodes("ASM8")) {
            api = Opcodes.ASM8;
        }
        if (opcodes("ASM9")) {
            api = Opcodes.ASM9;
        }
        return api;
    }

    private static boolean opcodes(String field) {
        try {
            Opcodes.class.getField(field);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private final Map<String, Set<String>> targets = new HashMap<String, Set<String>>();
    private final Map<String, Set<String>> gates = new HashMap<String, Set<String>>();

    void add(String internalName, List<java.lang.reflect.Method> methods) {
        Set<String> keys = new HashSet<String>();
        for (java.lang.reflect.Method m : methods) {
            keys.add(signature(m));
        }
        targets.put(internalName, keys);
    }

    /** 入站包闸门：命中方法在入口先询问 PacketBacktrack，被拦截则直接返回。 */
    void addGate(String internalName, java.lang.reflect.Method method) {
        Set<String> keys = gates.get(internalName);
        if (keys == null) {
            keys = new HashSet<String>();
            gates.put(internalName, keys);
        }
        keys.add(signature(method));
    }

    private static String signature(java.lang.reflect.Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (Class<?> p : ps) {
            sb.append(desc(p));
        }
        sb.append(')').append(desc(m.getReturnType()));
        return sb.toString();
    }

    private static String desc(Class<?> c) {
        if (c == void.class) {
            return "V";
        }
        if (c == boolean.class) {
            return "Z";
        }
        if (c == byte.class) {
            return "B";
        }
        if (c == char.class) {
            return "C";
        }
        if (c == short.class) {
            return "S";
        }
        if (c == int.class) {
            return "I";
        }
        if (c == long.class) {
            return "J";
        }
        if (c == float.class) {
            return "F";
        }
        if (c == double.class) {
            return "D";
        }
        if (c.isArray()) {
            return c.getName().replace('.', '/');
        }
        return "L" + c.getName().replace('.', '/') + ";";
    }

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        final Set<String> keys = targets.get(className);
        final Set<String> gateKeys = gates.get(className);
        if (keys == null && gateKeys == null) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new ClassVisitor(API, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    MethodVisitor base = super.visitMethod(access, name, desc, sig, ex);
                    if (base == null) {
                        return null;
                    }
                    if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                        return base;
                    }
                    final String key = name + desc;
                    if (gateKeys != null && gateKeys.contains(key)) {
                        return new MethodVisitor(API, base) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                // 栈: [] -> [this, ctx, packet] -> [boolean]
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitVarInsn(Opcodes.ALOAD, 2);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, PACKET, "intercept",
                                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                                org.objectweb.asm.Label pass = new org.objectweb.asm.Label();
                                super.visitJumpInsn(Opcodes.IFEQ, pass);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(pass);
                                // 入口处局部变量与初始帧一致、栈为空，补一个 SAME 帧保证校验通过
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }
                        };
                    }
                    if (keys == null || !keys.contains(key)) {
                        return base;
                    }
                    return new MethodVisitor(API, base) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // 栈: [] -> [this] -> hook(this)
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "hook",
                                    "(Ljava/lang/Object;)V", false);
                        }
                    };
                }
            };
            reader.accept(visitor, 0);
            Log.log("注入完成: " + className + " " + (gateKeys != null ? "gate=" + gateKeys : "")
                    + (keys != null ? " tick=" + keys : ""));
            return writer.toByteArray();
        } catch (Throwable t) {
            Log.log("注入失败: " + className, t);
            return null;
        }
    }
}
