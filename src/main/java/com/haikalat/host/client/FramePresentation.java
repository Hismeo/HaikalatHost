package com.haikalat.host.client;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/** Presents Minecraft's main target at the end of the frame. */
public final class FramePresentation implements AutoCloseable {
    private static FramePresentation active;

    private final GlRenderDevice device = new GlRenderDevice();
    private final VertexArray vertices;
    private final ShaderProgram shader;
    private final CommandBuffer triangle;
    private boolean reported;

    private FramePresentation() {
        shader = ShaderProgram.fromResource(FramePresentation.class,
                "/assets/haikalathost/shaders/present.vert", "/assets/haikalathost/shaders/present.frag");
        try {
            vertices = new VertexArray();
        } catch (RuntimeException | Error failure) {
            shader.close();
            throw failure;
        }
        triangle = device.createCommandBuffer();
        triangle.bindShader(shader).bindVertexArray(vertices.id()).drawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    public static void blit(RenderTarget source, int width, int height, ShaderInstance minecraftBlitShader) {
        RenderSystem.assertOnRenderThread();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (active == null) {
            active = new FramePresentation();
        }
        active.present(source, width, height, minecraftBlitShader);
    }

    private void present(RenderTarget source, int width, int height, ShaderInstance minecraftBlitShader) {
        // Match the main blit's Blaze3D state contract and leave the current scissor unchanged.
        int activeTexture = GlStateManager._getActiveTexture();
        GlStateManager._colorMask(true, true, true, false);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._viewport(0, 0, width, height);
        GlStateManager._disableBlend();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(source.getColorTextureId());
        GlStateManager._activeTexture(activeTexture);
        try {
            device.invalidateState();
            device.execute(triangle);
        } finally {
            // Clear program and texture caches exactly as the game's screen blit does.
            minecraftBlitShader.setSampler("DiffuseSampler", source);
            minecraftBlitShader.clear();
            VertexBuffer.unbind();
            GlStateManager._depthMask(true);
            GlStateManager._colorMask(true, true, true, true);
        }
        if (!reported) {
            reported = true;
            LogUtils.getLogger().info("Haikalat rendered Minecraft's frame to the window using minecraft-present");
        }
    }

    public static void shutdown() {
        if (active != null) {
            active.close();
            active = null;
        }
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        vertices.close();
        shader.close();
    }
}
