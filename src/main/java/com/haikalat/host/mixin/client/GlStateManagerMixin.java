package com.haikalat.host.mixin.client;

import com.haikalat.host.client.Blaze3dCommands;
import com.haikalat.host.client.MinecraftPrograms;
import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlStateManager.class)
abstract class GlStateManagerMixin {
    @Redirect(method = "_clear", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClear(I)V", remap = false))
    private static void haikalathost$clear(int mask) {
        Blaze3dCommands.clear(mask);
    }

    @Redirect(method = "glCreateProgram", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20;glCreateProgram()I", remap = false))
    private static int haikalathost$createProgram() {
        return MinecraftPrograms.create();
    }

    @Redirect(method = "glDeleteProgram", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20;glDeleteProgram(I)V", remap = false))
    private static void haikalathost$deleteProgram(int program) {
        MinecraftPrograms.delete(program);
    }
}
