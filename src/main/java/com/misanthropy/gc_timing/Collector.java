package com.misanthropy.gc_timing;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Collector {
    private static final long MB = 1L << 20;
    private static final long PLATEAU_BYTES = 48 * MB;
    private static final int CHUNK_LONGS = 16384;
    private static final int BALLAST_LONGS = 1022;

    private static GarbageCollectorMXBean young;
    private static MemoryPoolMXBean eden;
    private static MemoryPoolMXBean survivor;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile Object sink;

    public static volatile boolean unfelt;
    public static volatile boolean forcing;
    private static volatile int plateau;
    private static volatile long lastSurvivor;
    private static volatile long lastFlushNanos;

    public static long feltPauses, feltMillis, unfeltPauses, unfeltMillis, forcedPauses, forcedMillis, flushes, fills;
    public static long worstFelt;

    private Collector() {
    }

    static boolean init() {
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (b.getName().equals("G1 Young Generation")) {
                young = b;
            }
        }
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getName().equals("G1 Eden Space")) {
                eden = p;
            } else if (p.getName().equals("G1 Survivor Space")) {
                survivor = p;
            }
        }
        if (young == null || eden == null || survivor == null || !(young instanceof NotificationEmitter emitter)) {
            GcTiming.LOGGER.info("Not running on G1, menu collections are off");
            return false;
        }
        emitter.addNotificationListener((n, h) -> {
            if (GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) {
                GcInfo gi = GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData()).getGcInfo();
                MemoryUsage s = gi.getMemoryUsageAfterGc().get("G1 Survivor Space");
                record(s == null ? 0L : s.getUsed(), gi.getDuration());
            }
        }, null, null);
        return true;
    }

    private static synchronized void record(long survivorBytes, long millis) {
        lastSurvivor = survivorBytes;
        plateau = survivorBytes > PLATEAU_BYTES ? plateau + 1 : 0;
        if (forcing) {
            forcedPauses++;
            forcedMillis += millis;
        } else if (unfelt) {
            unfeltPauses++;
            unfeltMillis += millis;
        } else {
            feltPauses++;
            feltMillis += millis;
            worstFelt = Math.max(worstFelt, millis);
        }
    }

    static double edenFill() {
        MemoryUsage u = eden.getUsage();
        long committed = u.getCommitted();
        return committed <= 0L ? 0.0 : (double) u.getUsed() / committed;
    }

    static boolean wantsFlush() {
        return plateau >= 2 && System.nanoTime() - lastFlushNanos > 20_000_000_000L;
    }

    static boolean busy() {
        return BUSY.get();
    }

    static void start(boolean flush) {
        if (!BUSY.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            forcing = true;
            long t0 = System.nanoTime();
            try {
                if (flush) {
                    flush();
                } else {
                    fill();
                }
            } catch (OutOfMemoryError e) {
                sink = null;
            } finally {
                forcing = false;
                BUSY.set(false);
            }
            if (GcTiming.LOG.get()) {
                GcTiming.LOGGER.info("{} in {} ms, survivors now {} MB", flush ? "flush" : "collection",
                        (System.nanoTime() - t0) / 1_000_000L, lastSurvivor / MB);
            }
        }, "GC Timing");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static boolean fill() {
        long c0 = young.getCollectionCount();
        long budget = eden.getUsage().getCommitted() + 512 * MB;
        long deadline = System.nanoTime() + 3_000_000_000L;
        long done = 0L;
        try {
            while (young.getCollectionCount() == c0) {
                sink = new long[CHUNK_LONGS];
                done += CHUNK_LONGS * 8L;
                if (done > budget || System.nanoTime() > deadline) {
                    return false;
                }
            }
            fills++;
            return true;
        } finally {
            sink = null;
        }
    }

    private static void flush() {
        long e = eden.getUsage().getCommitted();
        long s = survivor.getUsage().getUsed();
        long ballastBytes = (long) ((e + s) * 0.08);
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = heap.getMax() > 0 ? heap.getMax() : Runtime.getRuntime().maxMemory();
        if (heap.getUsed() + e + 3 * ballastBytes > max * 0.9) {
            fill();
            return;
        }
        Object[] ballast = new Object[(int) (ballastBytes / (BALLAST_LONGS * 8L + 16L)) + 1];
        for (int i = 0; i < ballast.length; i++) {
            ballast[i] = new long[BALLAST_LONGS];
        }
        sink = ballast;
        boolean first = fill();
        sink = null;
        ballast = null;
        if (first) {
            fill();
        }
        lastFlushNanos = System.nanoTime();
        flushes++;
    }

    public static void summary() {
        GcTiming.LOGGER.info("young pauses in play {} ({} ms, worst {} ms), in menus {} ({} ms), run by this mod {} ({} ms), fills {}, flushes {}",
                feltPauses, feltMillis, worstFelt, unfeltPauses, unfeltMillis, forcedPauses, forcedMillis, fills, flushes);
    }

    public static void resetStats() {
        feltPauses = feltMillis = unfeltPauses = unfeltMillis = forcedPauses = forcedMillis = flushes = fills = worstFelt = 0L;
    }
}
