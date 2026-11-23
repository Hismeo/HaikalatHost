package com.haikalat.host.mixin.client;

import com.haikalat.host.client.TerrainMultiDraw;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Mixin(ShaderInstance.class)
abstract class ShaderInstanceMixin {
    @Shadow @Final private List<Integer> samplerLocations;

    @WrapOperation(method = "getOrCreate", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/Program;compileShader(Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;Ljava/io/InputStream;Ljava/lang/String;Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;)Lcom/mojang/blaze3d/shaders/Program;"), require = 0)
    private static Program haikalathost$patchStockTerrainVertex(Program.Type type, String name, InputStream input,
                                                              String sourcePack, GlslPreprocessor preprocessor,
                                                              Operation<Program> original,
                                                              @Local(argsOnly = true) ResourceProvider resources) throws IOException {
        InputStream source = type == Program.Type.VERTEX
                ? TerrainMultiDraw.patchStockTerrain(name, sourcePack, input, resources) : input;
        return original.call(type, name, source, sourcePack, preprocessor);
    }

    @Inject(method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",
            at = @At("RETURN"))
    private void haikalathost$registerStockTerrain(ResourceProvider resources, ResourceLocation location,
                                                  VertexFormat format, CallbackInfo ci) {
        ShaderInstance shader = (ShaderInstance) (Object) this;
        TerrainMultiDraw.registerStockShader(shader, resources, shader.getName());
    }

    @WrapOperation(method = "apply", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/Uniform;glGetUniformLocation(ILjava/lang/CharSequence;)I"))
    private int haikalathost$useLinkedSamplerLocation(int program, CharSequence name, Operation<Integer> original,
                                                     @Local(ordinal = 1) int samplerIndex) {
        // Minecraft resolves these locations when it links each shader, including after reload.
        return samplerLocations.get(samplerIndex);
    }
}
