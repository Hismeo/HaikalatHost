package com.haikalat.host.mixin.client;

import com.haikalat.host.client.TerrainLayerMask;
import com.haikalat.host.client.TerrainSectionAccess;
import com.haikalat.host.client.TerrainVertexBufferAccess;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.CompiledSection;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.Map;

@Mixin(RenderSection.class)
abstract class RenderSectionMixin implements TerrainSectionAccess {
    @Shadow @Final private BlockPos.MutableBlockPos origin;
    @Shadow @Final private Map<RenderType, VertexBuffer> buffers;
    @Unique @Nullable private SectionPos haikalathost$sectionPos;

    @Inject(method = "setOrigin", at = @At("RETURN"))
    private void haikalathost$refreshSectionPos(int x, int y, int z, CallbackInfo ci) {
        // ViewArea calls setOrigin when it reuses a render section at another position.
        haikalathost$sectionPos = SectionPos.of(origin);
    }

    @Inject(method = "setCompiled", at = @At("HEAD"))
    private void haikalathost$publishMask(CompiledSection compiled, CallbackInfo ci) {
        ((TerrainLayerMask) compiled).haikalathost$publishLayerMask();
    }

    @Inject(method = "reset", at = @At("RETURN"))
    private void haikalathost$discardReusedSection(CallbackInfo ci) {
        if (haikalathost$sectionPos != null) {
            for (VertexBuffer buffer : buffers.values()) {
                ((TerrainVertexBufferAccess) buffer).haikalathost$forgetTerrain();
            }
        }
    }

    @Override
    @Nullable
    public SectionPos haikalathost$sectionPos(BlockPos origin) {
        return origin == this.origin ? haikalathost$sectionPos : null;
    }
}
