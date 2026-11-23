package com.haikalat.host.client;

/** Captures one already uploaded block quad buffer at the terrain layer call site. */
public interface TerrainVertexBufferAccess {
    boolean haikalathost$recordTerrain(float x, float y, float z);

    void haikalathost$forgetTerrain();
}
