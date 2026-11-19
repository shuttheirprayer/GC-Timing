package com.misanthropy.gc_timing;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(GcTiming.MOD_ID)
public final class GcTiming {
    public static final String MOD_ID = "gc_timing";
    public static final Logger LOGGER = LoggerFactory.getLogger("GC Timing");

    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue ENABLED;
    static final ForgeConfigSpec.BooleanValue FLUSH_SURVIVORS;
    static final ForgeConfigSpec.DoubleValue EDEN_FRACTION;
    static final ForgeConfigSpec.BooleanValue LOG;
    static final ForgeConfigSpec.BooleanValue GROW_HEAP;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        ENABLED = b.comment("Run young garbage collections while a menu, a loading screen or another window has focus, so fewer pauses land during play.")
                .define("enabled", true);
        FLUSH_SURVIVORS = b.comment("When long-lived world data keeps getting copied by every collection, move it to the old generation during a menu.")
                .define("flushSurvivors", false);
        EDEN_FRACTION = b.comment("How full the young generation must be before a menu collection runs (0.1 to 0.9).")
                .defineInRange("edenFraction", 0.3, 0.1, 0.9);
        LOG = b.comment("Log every collection this mod runs.")
                .define("log", false);
        GROW_HEAP = b.comment("Grow the heap to its full size once on the title screen. Otherwise the JVM does it in one step in the middle of play, the first time the heap fills up, and the whole game freezes for 130 to 310 ms. Skipped when the PC has little free memory.")
                .define("growHeapEarly", true);
        SPEC = b.build();
    }

    public GcTiming() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT && Collector.init()) {
            MinecraftForge.EVENT_BUS.register(Scheduler.class);
        }
    }
}
