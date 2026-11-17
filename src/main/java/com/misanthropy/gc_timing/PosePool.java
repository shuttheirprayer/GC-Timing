package com.misanthropy.gc_timing;

public final class PosePool {
    public static boolean enabled = !"false".equals(System.getProperty("gc_timing.posePool"));

    private PosePool() {
    }
}
