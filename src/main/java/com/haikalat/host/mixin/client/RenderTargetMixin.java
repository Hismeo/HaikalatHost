package com.haikalat.host.mixin.client;

import com.haikalat.host.client.Blaze3dCommands;
import com.kaleblangley.haikalat.backend.GlCapabilityContract;
import com.kaleblangley.haikalat.backend.framebuffer.Framebuffer;
import com.kaleblangley.haikalat.backend.framebuffer.FramebufferDescriptor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(RenderTarget.class)
abstract class RenderTargetMixin {
    @Shadow public int width;
    @Shadow public int height;
    @Shadow public int viewWidth;
    @Shadow public int viewHeight;
    @Shadow public int filterMode;
    @Shadow public int frameBufferId;
    @Shadow protected int colorTextureId;
    @Shadow protected int depthBufferId;
    @Shadow @Final public boolean useDepth;
    @Shadow public abstract boolean isStencilEnabled();
    @Shadow public abstract void clear(boolean clearError);
    @Shadow public abstract void unbindRead();
    @Shadow public abstract void unbindWrite();

    @Unique private Framebuffer haikalathost$framebuffer;

    @Inject(method = "createBuffers", at = @At("HEAD"), cancellable = true)
    private void haikalathost$createBuffers(int width, int height, boolean clearError, CallbackInfo ci) {
        RenderSystem.assertOnRenderThreadOrInit();
        int limit = RenderSystem.maxSupportedTextureSize();
        if (width <= 0 || height <= 0 || width > limit || height > limit) {
            throw new IllegalArgumentException("Render target size outside GL limits: " + width + "x" + height);
        }
        GlCapabilityContract.requireCurrent();
        var depth = FramebufferDescriptor.DepthAttachment.none();
        if (useDepth) {
            depth = isStencilEnabled() ? new FramebufferDescriptor.DepthAttachment(
                    GL30.GL_DEPTH32F_STENCIL8, GL30.GL_DEPTH_STENCIL, GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV,
                    GL30.GL_DEPTH_STENCIL_ATTACHMENT, FramebufferDescriptor.AttachmentStorage.TEXTURE_2D)
                    : FramebufferDescriptor.DepthAttachment.texture();
        }
        var descriptor = new FramebufferDescriptor(width, height, 1,
                List.of(FramebufferDescriptor.ColorAttachment.texture(GL11.GL_RGBA8)), depth);
        Framebuffer replacement = Framebuffer.fromDescriptor(descriptor);
        if (haikalathost$framebuffer != null) {
            haikalathost$release();
        }

        haikalathost$framebuffer = replacement;
        this.width = this.viewWidth = width;
        this.height = this.viewHeight = height;
        this.frameBufferId = replacement.id();
        this.colorTextureId = replacement.colorAttachment();
        this.depthBufferId = useDepth ? replacement.depthAttachment() : -1;
        this.filterMode = GL11.GL_NEAREST;
        GL45.glTextureParameteri(colorTextureId, GL11.GL_TEXTURE_MIN_FILTER, filterMode);
        GL45.glTextureParameteri(colorTextureId, GL11.GL_TEXTURE_MAG_FILTER, filterMode);
        if (useDepth) {
            GL45.glTextureParameteri(depthBufferId, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            GL45.glTextureParameteri(depthBufferId, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        }
        this.clear(clearError);
        this.unbindRead();
        ci.cancel();
    }

    @Inject(method = "destroyBuffers", at = @At("HEAD"), cancellable = true)
    private void haikalathost$destroyBuffers(CallbackInfo ci) {
        if (haikalathost$framebuffer != null) {
            RenderSystem.assertOnRenderThreadOrInit();
            haikalathost$release();
            ci.cancel();
        }
    }

    @Inject(method = "copyDepthFrom", at = @At("HEAD"), cancellable = true)
    private void haikalathost$copyDepthFrom(RenderTarget other, CallbackInfo ci) {
        Blaze3dCommands.copyDepth(other.frameBufferId, frameBufferId, other.width, other.height, width, height);
        ci.cancel();
    }

    @Unique
    private void haikalathost$release() {
        this.unbindRead();
        this.unbindWrite();
        haikalathost$framebuffer.close();
        haikalathost$framebuffer = null;
        // GL ignores the released names. Blaze3D clears the corresponding cached texture bindings.
        GlStateManager._deleteTexture(colorTextureId);
        if (depthBufferId >= 0) {
            GlStateManager._deleteTexture(depthBufferId);
        }
        frameBufferId = colorTextureId = depthBufferId = -1;
    }
}
