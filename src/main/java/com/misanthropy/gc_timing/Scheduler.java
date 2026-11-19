package com.misanthropy.gc_timing;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class Scheduler {
    public static boolean enabled = true;
    private static int quietTicks;
    private static long lastStart;

    private Scheduler() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean quiet = mc.screen != null || !mc.isWindowActive();
        Collector.unfelt = quiet;
        if (mc.screen != null && mc.level == null && mc.getOverlay() == null && GcTiming.GROW_HEAP.get()) {
            Grow.once();
        }
        quietTicks = quiet ? quietTicks + 1 : 0;
        if (!enabled || quietTicks < 3 || Collector.busy() || !GcTiming.ENABLED.get()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastStart < 4_000_000_000L) {
            return;
        }
        if (GcTiming.FLUSH_SURVIVORS.get() && Collector.wantsFlush()) {
            lastStart = now;
            Collector.start(true);
        } else if (Collector.edenFill() >= GcTiming.EDEN_FRACTION.get()) {
            lastStart = now;
            Collector.start(false);
        }
    }
}
