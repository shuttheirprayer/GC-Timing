package com.misanthropy.gc_timing.mixin;

import com.misanthropy.gc_timing.PosePool;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Deque;

@Mixin(value = PoseStack.class, priority = 100, remap = false)
public abstract class PoseStackMixin {
    @Shadow
    @Final
    private Deque<PoseStack.Pose> f_85834_;

    @Unique
    private PoseStack.Pose[] gct$free;

    @Unique
    private int gct$freeCount;

    @Overwrite
    public void m_85836_() {
        PoseStack.Pose last = this.f_85834_.getLast();
        PoseStack.Pose next;
        if (this.gct$freeCount > 0) {
            next = this.gct$free[--this.gct$freeCount];
            this.gct$free[this.gct$freeCount] = null;
            next.pose().set(last.pose());
            next.normal().set(last.normal());
        } else {
            next = PoseInvoker.gct$create(new Matrix4f(last.pose()), new Matrix3f(last.normal()));
        }
        this.f_85834_.addLast(next);
    }

    @Overwrite
    public void m_85849_() {
        PoseStack.Pose top = this.f_85834_.removeLast();
        if (!PosePool.enabled) {
            return;
        }
        PoseStack.Pose[] free = this.gct$free;
        if (free == null) {
            free = this.gct$free = new PoseStack.Pose[8];
        } else if (this.gct$freeCount == free.length) {
            if (free.length >= 64) {
                return;
            }
            free = this.gct$free = java.util.Arrays.copyOf(free, free.length * 2);
        }
        free[this.gct$freeCount++] = top;
    }
}
