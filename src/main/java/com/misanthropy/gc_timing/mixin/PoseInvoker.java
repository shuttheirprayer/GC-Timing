package com.misanthropy.gc_timing.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = PoseStack.Pose.class, remap = false)
public interface PoseInvoker {
    @Invoker("<init>")
    static PoseStack.Pose gct$create(Matrix4f pose, Matrix3f normal) {
        throw new AssertionError();
    }
}
