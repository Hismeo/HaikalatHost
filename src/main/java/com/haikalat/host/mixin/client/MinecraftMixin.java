package com.haikalat.host.mixin.client;

import com.haikalat.host.client.FramePresentation;
import com.haikalat.host.client.MinecraftFramePipeline;
import com.haikalat.host.client.PostPipeline;
import com.haikalat.host.client.SharedIndexBuffers;
import com.haikalat.host.client.TerrainDrawBatch;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Unique private MinecraftFramePipeline haikalathost$pipeline;

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void haikalathost$renderFrame(GameRenderer renderer, DeltaTracker delta, boolean world) {
        if (haikalathost$pipeline == null) {
            haikalathost$pipeline = new MinecraftFramePipeline();
        }
        Minecraft game = (Minecraft) (Object) this;
        haikalathost$pipeline.render(game.getMainRenderTarget(), renderer, delta, world);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void haikalathost$presentFrame(RenderTarget source, int width, int height) {
        Minecraft game = (Minecraft) (Object) this;
        FramePresentation.blit(source, width, height, game.gameRenderer.blitShader);
    }

    @Redirect(method = "close", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;close()V"), require = 1, allow = 2)
    private void haikalathost$closeWindow(Window window) {
        try {
            if (haikalathost$pipeline != null) {
                haikalathost$pipeline.close();
                haikalathost$pipeline = null;
            }
            FramePresentation.shutdown();
            PostPipeline.shutdown();
            TerrainDrawBatch.close();
            SharedIndexBuffers.close();
            ((Minecraft) (Object) this).getMainRenderTarget().destroyBuffers();
        } finally {
            window.close();
        }
    }
}
