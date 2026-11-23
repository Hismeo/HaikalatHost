package com.haikalat.host.mixin.client;

import net.minecraft.client.renderer.PostPass;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.function.IntSupplier;

@Mixin(PostPass.class)
public interface PostPassAccessor {
    @Accessor("auxAssets") List<IntSupplier> haikalathost$auxAssets();
    @Accessor("auxNames") List<String> haikalathost$auxNames();
    @Accessor("auxWidths") List<Integer> haikalathost$auxWidths();
    @Accessor("auxHeights") List<Integer> haikalathost$auxHeights();
    @Accessor("shaderOrthoMatrix") Matrix4f haikalathost$projection();
}
