package com.haikalat.host.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import javax.annotation.Nullable;

/** Cached lighting coordinates while Minecraft scans visible sections. */
public interface TerrainSectionAccess {
    @Nullable SectionPos haikalathost$sectionPos(BlockPos origin);
}
