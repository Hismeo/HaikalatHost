package com.haikalat.host.mixin.client;

import com.haikalat.host.client.SharedIndexBuffers;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;

@Mixin(RenderSystem.AutoStorageIndexBuffer.class)
abstract class AutoStorageIndexBufferMixin {
    @Unique private GlBuffer haikalathost$indices;
    @Unique private long haikalathost$size;

    @Redirect(method = "bind", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I"))
    private int haikalathost$create() {
        haikalathost$indices = SharedIndexBuffers.create();
        return haikalathost$indices.id();
    }

    @Redirect(method = "ensureStorage", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glBufferData(IJI)V"))
    private void haikalathost$allocate(int target, long size, int usage) {
        haikalathost$indices.allocate(size);
        haikalathost$size = size;
    }

    @Redirect(method = "ensureStorage", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glMapBuffer(II)Ljava/nio/ByteBuffer;"))
    private ByteBuffer haikalathost$mapWrite(int target, int access) {
        return haikalathost$indices.mapRange(0, haikalathost$size, GL30.GL_MAP_WRITE_BIT);
    }

    @Redirect(method = "ensureStorage", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glUnmapBuffer(I)V"))
    private void haikalathost$unmap(int target) {
        if (!haikalathost$indices.unmap()) {
            throw new IllegalStateException("Failed to upload Minecraft's sequential indices");
        }
    }
}
