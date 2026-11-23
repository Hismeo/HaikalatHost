package com.haikalat.host.mixin.client;

import com.haikalat.host.client.PostPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(PostChain.class)
abstract class PostChainMixin {
    @Shadow @Final private RenderTarget screenTarget;
    @Shadow @Final private List<PostPass> passes;
    @Shadow @Final private String name;
    @Shadow private float time;
    @Shadow private void setFilterMode(int filterMode) { throw new AssertionError(); }

    @Unique private PostPipeline haikalathost$pipeline;

    @Inject(method = "process", at = @At("HEAD"), cancellable = true)
    private void haikalathost$process(float partialTicks, CallbackInfo ci) {
        RenderSystem.assertOnRenderThread();
        time += partialTicks;
        while (time > 20.0F) {
            time -= 20.0F;
        }
        if (haikalathost$pipeline == null) {
            haikalathost$pipeline = new PostPipeline(passes, this::setFilterMode, name);
        }
        haikalathost$pipeline.render(screenTarget, time / 20.0F);
        ci.cancel();
    }

    @Inject(method = "addPass", at = @At("RETURN"))
    private void haikalathost$onPassAdded(CallbackInfoReturnable<PostPass> ci) {
        haikalathost$discardPipeline();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void haikalathost$close(CallbackInfo ci) {
        haikalathost$discardPipeline();
    }

    @Unique
    private void haikalathost$discardPipeline() {
        if (haikalathost$pipeline != null) {
            haikalathost$pipeline.close();
            haikalathost$pipeline = null;
        }
    }
}
