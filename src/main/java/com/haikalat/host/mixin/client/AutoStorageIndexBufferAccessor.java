package com.haikalat.host.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSystem.AutoStorageIndexBuffer.class)
public interface AutoStorageIndexBufferAccessor {
    @Accessor("name")
    int haikalathost$getBufferId();
}
