package com.haikalat.host;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;

@Mod(HaikalatHost.MOD_ID)
public final class HaikalatHost {
    public static final String MOD_ID = "haikalathost";

    public HaikalatHost() {
        LogUtils.getLogger().info("Haikalat Host initialized");
    }
}
