package com.misanthropy.gc_timing;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Grow {
    private static final long MB = 1L << 20;
    private static final long RATE = 256 * MB;
    private static final long QUIET_NANOS = 2_000_000_000L;
    private static final AtomicBoolean DONE = new AtomicBoolean();

    private Grow() {
    }

    static void once() {
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(Grow::run, "GC Timing heap");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void run() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        long max = heap.getMax();
        long from = heap.getCommitted();
        long region;
        long freeRam;
        try {
            region = Long.parseLong(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption("G1HeapRegionSize").getValue());
            freeRam = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class).getFreeMemorySize();
        } catch (Throwable e) {
            return;
        }
        if (region < MB || max <= 0L || max - from < 16 * region) {
            return;
        }
        if (freeRam < max - from + 3072 * MB) {
            GcTiming.LOGGER.info("Heap left at {} MB of {} MB, only {} MB of memory is free", from / MB, max / MB, freeRam / MB);
            return;
        }
        int longs = (int) (region / 8L) - 8;
        long pause = Math.max(1L, region * 1000L / RATE);
        ArrayList<long[]> ballast = new ArrayList<>();
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        long t0 = System.nanoTime();
        long collections = collections(collectors);
        long quietSince = t0;
        try {
            while (mem.getHeapMemoryUsage().getCommitted() <= max - 8 * region && System.nanoTime() - t0 < 180_000_000_000L) {
                if (mem.getHeapMemoryUsage().getUsed() + 384 * MB + region > max) {
                    break;
                }
                long now = collections(collectors);
                if (now != collections) {
                    collections = now;
                    quietSince = System.nanoTime();
                }
                if (System.nanoTime() - quietSince >= QUIET_NANOS) {
                    ballast.add(new long[longs]);
                }
                Thread.sleep(pause);
            }
        } catch (OutOfMemoryError | InterruptedException e) {
            ballast.clear();
        }
        long held = ballast.size() * region;
        ballast.clear();
        GcTiming.LOGGER.info("Heap grown from {} MB to {} MB in {} ms ({} MB held for that time)", from / MB,
                mem.getHeapMemoryUsage().getCommitted() / MB, (System.nanoTime() - t0) / 1_000_000L, held / MB);
    }

    private static long collections(List<GarbageCollectorMXBean> collectors) {
        long n = 0L;
        for (GarbageCollectorMXBean c : collectors) {
            n += c.getCollectionCount();
        }
        return n;
    }
}
