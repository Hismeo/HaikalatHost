package com.haikalat.host.client;

import net.minecraft.client.renderer.RenderType;

/** Published with a compiled section, before it is available to the render thread. */
public interface TerrainLayerMask {
    static int index(RenderType layer) {
        if (layer == RenderType.solid()) return 0;
        if (layer == RenderType.cutoutMipped()) return 1;
        if (layer == RenderType.cutout()) return 2;
        if (layer == RenderType.translucent()) return 3;
        if (layer == RenderType.tripwire()) return 4;
        return -1;
    }

    void haikalathost$publishLayerMask();

    int haikalathost$nonemptyLayers();
}
