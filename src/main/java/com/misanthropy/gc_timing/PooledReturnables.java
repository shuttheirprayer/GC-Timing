package com.misanthropy.gc_timing;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sun.misc.Unsafe;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

public final class PooledReturnables {
    public static volatile boolean enabled = "true".equals(System.getProperty("gc_timing.pooledReturnables"));
    private static final Unsafe UNSAFE;
    private static final long CANCELLED;
    private static final long RETURN_VALUE;
    private static final ConcurrentHashMap<String, Site> SITES = new ConcurrentHashMap<>();
    private static final int MAX_DEPTH = 8;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
            CANCELLED = UNSAFE.objectFieldOffset(CallbackInfo.class.getDeclaredField("cancelled"));
            RETURN_VALUE = UNSAFE.objectFieldOffset(CallbackInfoReturnable.class.getDeclaredField("returnValue"));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private PooledReturnables() {
    }

    static boolean available() {
        try {
            return UNSAFE != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static final class Stack {
        final CallbackInfoReturnable<?>[] items = new CallbackInfoReturnable<?>[MAX_DEPTH];
        int size;
    }

    static final class Site {
        final String name;
        final boolean cancellable;
        final ThreadLocal<Stack> stacks = ThreadLocal.withInitial(Stack::new);

        Site(String name, boolean cancellable) {
            this.name = name;
            this.cancellable = cancellable;
        }

        CallbackInfoReturnable<?> take(Object value) {
            if (!enabled) {
                return fresh(value);
            }
            Stack stack = stacks.get();
            if (stack.size == 0) {
                return fresh(value);
            }
            CallbackInfoReturnable<?> cir = stack.items[--stack.size];
            stack.items[stack.size] = null;
            UNSAFE.putBoolean(cir, CANCELLED, false);
            UNSAFE.putObject(cir, RETURN_VALUE, value);
            return cir;
        }

        private CallbackInfoReturnable<?> fresh(Object value) {
            CallbackInfoReturnable<?> cir = new CallbackInfoReturnable<>(name, cancellable);
            if (value != null) {
                UNSAFE.putObject(cir, RETURN_VALUE, value);
            }
            return cir;
        }

        void give(CallbackInfoReturnable<?> cir) {
            if (!enabled) {
                return;
            }
            Stack stack = stacks.get();
            if (stack.size < MAX_DEPTH) {
                UNSAFE.putObject(cir, RETURN_VALUE, null);
                stack.items[stack.size++] = cir;
            }
        }
    }

    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type, String key, String id, int cancellable) throws ReflectiveOperationException {
        Site site = SITES.computeIfAbsent(key, k -> new Site(id, cancellable != 0));
        MethodHandles.Lookup self = MethodHandles.lookup();
        MethodHandle target;
        if (name.equals("release")) {
            target = self.findVirtual(Site.class, "give", MethodType.methodType(void.class, CallbackInfoReturnable.class)).bindTo(site);
        } else if (type.parameterCount() == 0) {
            target = MethodHandles.insertArguments(self.findVirtual(Site.class, "take", MethodType.methodType(CallbackInfoReturnable.class, Object.class)).bindTo(site), 0, (Object) null);
        } else {
            target = self.findVirtual(Site.class, "take", MethodType.methodType(CallbackInfoReturnable.class, Object.class)).bindTo(site);
        }
        return new ConstantCallSite(target.asType(type));
    }
}
