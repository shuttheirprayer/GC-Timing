package com.misanthropy.gc_timing;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class ConstantCallbackInfo {
    private ConstantCallbackInfo() {
    }

    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type, String id) {
        return new ConstantCallSite(MethodHandles.constant(CallbackInfo.class, new CallbackInfo(id, false)));
    }
}
