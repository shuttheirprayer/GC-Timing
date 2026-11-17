package com.misanthropy.gc_timing;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConstantCallbackExtension implements IExtension {
    private static final Logger LOGGER = LoggerFactory.getLogger("GC Timing");
    private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CALLBACK_DESCRIPTOR = "()L" + CALLBACK_INFO + ";";
    private static final Handle BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            "com/misanthropy/gc_timing/ConstantCallbackInfo", "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
            false);

    private static final String RETURNABLE = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final Handle POOL_BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            "com/misanthropy/gc_timing/PooledReturnables", "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/invoke/CallSite;",
            false);
    private static final boolean POOL = "true".equals(System.getProperty("gc_timing.pooledReturnables"));
    private static int pooled;
    private static int nextPoolReport = 1;
    private static boolean installed;
    private static int rewritten;
    private static int nextReport = 1;

    private ConstantCallbackExtension() {
    }

    @SuppressWarnings("unchecked")
    public static synchronized void install() {
        if (installed || "false".equals(System.getProperty("gc_timing.sharedCallbackInfo"))) {
            return;
        }
        installed = true;
        try {
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            if (!(transformer instanceof IMixinTransformer mixinTransformer) || !(mixinTransformer.getExtensions() instanceof Extensions extensions)) {
                LOGGER.warn("Mixin transformer not found, callback infos stay allocated per call");
                return;
            }
            ConstantCallbackExtension extension = new ConstantCallbackExtension();
            extensions.add(extension);
            Field active = Extensions.class.getDeclaredField("activeExtensions");
            active.setAccessible(true);
            List<IExtension> list = new ArrayList<>((List<IExtension>) active.get(extensions));
            list.add(extension);
            active.set(extensions, Collections.unmodifiableList(list));
        } catch (Throwable throwable) {
            LOGGER.warn("Could not share callback infos, they stay allocated per call", throwable);
        }
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
    }

    @Override
    public void postApply(ITargetClassContext context) {
        ClassNode node = context.getClassNode();
        if ((node.version & 0xFFFF) < Opcodes.V1_7) {
            return;
        }
        int before = rewritten;
        int poolBefore = pooled;
        int index = 0;
        for (MethodNode method : node.methods) {
            rewrite(method.instructions);
            if (POOL) {
                index = pool(node.name, method, index);
            }
        }
        if (pooled >= nextPoolReport && pooled > poolBefore) {
            LOGGER.info("Pooled {} cancellable returnable callback infos so far (latest {})", pooled, node.name);
            nextPoolReport = nextPoolReport < 1000 ? 1000 : nextPoolReport * 2;
        }
        if (rewritten >= nextReport && rewritten > before) {
            LOGGER.info("Shared {} non-cancellable callback infos so far (latest {})", rewritten, node.name);
            nextReport = nextReport < 1000 ? 1000 : nextReport * 2;
        }
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {
    }

    private static AbstractInsnNode real(AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() < 0) {
            insn = insn.getNext();
        }
        return insn;
    }

    private static int pool(String owner, MethodNode method, int index) {
        InsnList list = method.instructions;
        for (AbstractInsnNode insn = list.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.NEW || !RETURNABLE.equals(((TypeInsnNode) insn).desc)) {
                continue;
            }
            AbstractInsnNode dup = insn.getNext();
            AbstractInsnNode ldc = dup == null ? null : dup.getNext();
            AbstractInsnNode flag = ldc == null ? null : ldc.getNext();
            if (dup == null || dup.getOpcode() != Opcodes.DUP || !(ldc instanceof LdcInsnNode constant) || !(constant.cst instanceof String id)
                    || flag == null || flag.getOpcode() != Opcodes.ICONST_1) {
                continue;
            }
            MethodInsnNode init = null;
            int steps = 0;
            for (AbstractInsnNode scan = flag.getNext(); scan != null && steps < 12; scan = scan.getNext(), steps++) {
                if (scan instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && RETURNABLE.equals(call.owner) && "<init>".equals(call.name)) {
                    init = call;
                    break;
                }
                if (scan.getOpcode() == Opcodes.NEW || scan instanceof JumpInsnNode || scan instanceof MethodInsnNode || scan instanceof InvokeDynamicInsnNode) {
                    break;
                }
            }
            if (init == null || !init.desc.startsWith("(Ljava/lang/String;Z")) {
                continue;
            }
            AbstractInsnNode store = real(init.getNext());
            if (!(store instanceof VarInsnNode st) || st.getOpcode() != Opcodes.ASTORE) {
                continue;
            }
            int local = st.var;
            JumpInsnNode branch = null;
            AbstractInsnNode returnInsn = null;
            for (AbstractInsnNode scan = store.getNext(); scan != null; scan = scan.getNext()) {
                if (scan instanceof VarInsnNode v && v.var == local && v.getOpcode() == Opcodes.ASTORE) {
                    break;
                }
                if (scan instanceof VarInsnNode v && v.var == local && v.getOpcode() == Opcodes.ALOAD) {
                    AbstractInsnNode next = real(scan.getNext());
                    AbstractInsnNode after = next == null ? null : real(next.getNext());
                    if (next instanceof MethodInsnNode call && "isCancelled".equals(call.name) && "()Z".equals(call.desc)
                            && after instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) {
                        AbstractInsnNode load = real(jump.getNext());
                        AbstractInsnNode get = load == null ? null : real(load.getNext());
                        if (load instanceof VarInsnNode lv && lv.var == local && lv.getOpcode() == Opcodes.ALOAD
                                && get instanceof MethodInsnNode getter && getter.name.startsWith("getReturnValue")) {
                            AbstractInsnNode ret = real(get.getNext());
                            if (ret != null && ret.getOpcode() == Opcodes.CHECKCAST) {
                                ret = real(ret.getNext());
                            }
                            if (ret != null && ret.getOpcode() >= Opcodes.IRETURN && ret.getOpcode() <= Opcodes.ARETURN) {
                                branch = jump;
                                returnInsn = ret;
                            }
                        }
                        break;
                    }
                }
            }
            if (branch == null || usedAfter(branch.label, local)) {
                continue;
            }
            String key = owner + "#" + index++;
            Type[] args = Type.getArgumentTypes(init.desc);
            StringBuilder desc = new StringBuilder("(");
            for (int i = 2; i < args.length; i++) {
                desc.append(args[i].getDescriptor());
            }
            desc.append(")L").append(RETURNABLE).append(';');
            InvokeDynamicInsnNode take = new InvokeDynamicInsnNode("take", desc.toString(), POOL_BOOTSTRAP, key, id, 1);
            list.insert(init, take);
            list.remove(init);
            list.remove(insn);
            list.remove(dup);
            list.remove(ldc);
            list.remove(flag);
            InsnList release = new InsnList();
            release.add(new VarInsnNode(Opcodes.ALOAD, local));
            release.add(new InvokeDynamicInsnNode("release", "(L" + RETURNABLE + ";)V", POOL_BOOTSTRAP, key, id, 1));
            list.insertBefore(returnInsn, release);
            InsnList release2 = new InsnList();
            release2.add(new VarInsnNode(Opcodes.ALOAD, local));
            release2.add(new InvokeDynamicInsnNode("release", "(L" + RETURNABLE + ";)V", POOL_BOOTSTRAP, key, id, 1));
            AbstractInsnNode at = branch.label;
            while (at.getNext() != null && at.getNext().getOpcode() < 0 && !(at.getNext() instanceof LabelNode)) {
                at = at.getNext();
            }
            list.insert(at, release2);
            insn = take;
            pooled++;
        }
        return index;
    }

    private static boolean usedAfter(LabelNode label, int local) {
        for (AbstractInsnNode scan = label.getNext(); scan != null; scan = scan.getNext()) {
            if (scan instanceof VarInsnNode v && v.var == local) {
                return v.getOpcode() != Opcodes.ASTORE;
            }
        }
        return false;
    }

    private static void rewrite(InsnList instructions) {
        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.NEW || !CALLBACK_INFO.equals(((TypeInsnNode) insn).desc)) {
                continue;
            }
            AbstractInsnNode dup = insn.getNext();
            AbstractInsnNode ldc = dup == null ? null : dup.getNext();
            AbstractInsnNode flag = ldc == null ? null : ldc.getNext();
            AbstractInsnNode init = flag == null ? null : flag.getNext();
            if (dup == null || dup.getOpcode() != Opcodes.DUP || !(ldc instanceof LdcInsnNode constant) || !(constant.cst instanceof String id)
                    || flag == null || flag.getOpcode() != Opcodes.ICONST_0
                    || !(init instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !CALLBACK_INFO.equals(call.owner) || !"<init>".equals(call.name) || !"(Ljava/lang/String;Z)V".equals(call.desc)) {
                continue;
            }
            InvokeDynamicInsnNode shared = new InvokeDynamicInsnNode("callbackInfo", CALLBACK_DESCRIPTOR, BOOTSTRAP, id);
            instructions.insertBefore(insn, shared);
            instructions.remove(insn);
            instructions.remove(dup);
            instructions.remove(ldc);
            instructions.remove(flag);
            instructions.remove(init);
            insn = shared;
            rewritten++;
        }
    }
}
