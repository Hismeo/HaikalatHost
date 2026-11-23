package com.haikalat.host.mixin.client;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MainTarget.class)
abstract class MainTargetMixin {
    @Inject(method = "createFrameBuffer", at = @At("HEAD"), cancellable = true)
    private void haikalathost$createMainTarget(int width, int height, CallbackInfo ci) {
        int limit = RenderSystem.maxSupportedTextureSize();
        if (width <= 0 || height <= 0 || width > limit || height > limit) {
            width = MainTarget.DEFAULT_WIDTH;
            height = MainTarget.DEFAULT_HEIGHT;
        }
        ((RenderTarget) (Object) this).createBuffers(width, height, Minecraft.ON_OSX);
        ci.cancel();
    }
}
