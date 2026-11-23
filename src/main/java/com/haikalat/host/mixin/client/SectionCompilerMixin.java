package com.haikalat.host.mixin.client;

import com.haikalat.host.client.TerrainFaceRanges;
import com.haikalat.host.client.TerrainMeshAccess;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
    @Inject(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;"
            + "Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;"
            + "Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;", at = @At("RETURN"))
    private void haikalathost$bakeFaceRanges(CallbackInfoReturnable<SectionCompiler.Results> ci) {
        if (!TerrainFaceRanges.ENABLED) return;
        for (var entry : ci.getReturnValue().renderedLayers.entrySet()) {
            RenderType type = entry.getKey();
            if (type == RenderType.solid() || type == RenderType.cutout() || type == RenderType.cutoutMipped()) {
                var mesh = entry.getValue();
                ((TerrainMeshAccess) mesh).haikalathost$setFaceRanges(TerrainFaceRanges.bake(mesh));
            }
        }
    }
}
