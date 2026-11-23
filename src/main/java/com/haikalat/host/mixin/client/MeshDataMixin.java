package com.haikalat.host.mixin.client;

import com.haikalat.host.client.TerrainFaceRanges;
import com.haikalat.host.client.TerrainMeshAccess;
import com.mojang.blaze3d.vertex.MeshData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

@Mixin(MeshData.class)
abstract class MeshDataMixin implements TerrainMeshAccess {
    @Unique @Nullable private TerrainFaceRanges haikalathost$faceRanges;

    @Override
    public void haikalathost$setFaceRanges(@Nullable TerrainFaceRanges ranges) {
        haikalathost$faceRanges = ranges;
    }

    @Override
    public @Nullable TerrainFaceRanges haikalathost$faceRanges() {
        return haikalathost$faceRanges;
    }
}
