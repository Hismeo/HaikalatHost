package com.haikalat.host.client;

import javax.annotation.Nullable;

/** Travels with the same MeshData snapshot as the vertex payload. */
public interface TerrainMeshAccess {
    void haikalathost$setFaceRanges(@Nullable TerrainFaceRanges ranges);

    @Nullable TerrainFaceRanges haikalathost$faceRanges();
}
