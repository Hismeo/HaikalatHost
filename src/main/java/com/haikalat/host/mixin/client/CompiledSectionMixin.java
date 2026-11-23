package com.haikalat.host.mixin.client;

import com.haikalat.host.client.TerrainLayerMask;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.CompiledSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Set;

@Mixin(CompiledSection.class)
abstract class CompiledSectionMixin implements TerrainLayerMask {
    @Shadow @Final private Set<RenderType> hasBlocks;
    @Unique private int haikalathost$nonemptyLayers = -1;

    @Override
    public void haikalathost$publishLayerMask() {
        haikalathost$nonemptyLayers = haikalathost$readLayers();
    }

    @Override
    public int haikalathost$nonemptyLayers() {
        int mask = haikalathost$nonemptyLayers;
        return mask >= 0 ? mask : haikalathost$readLayers();
    }

    @Unique
    private int haikalathost$readLayers() {
        int mask = 0;
        for (RenderType type : hasBlocks) {
            int index = TerrainLayerMask.index(type);
            if (index >= 0) mask |= 1 << index;
        }
        return mask;
    }
}
