package com.haikalat.host.client;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;

/** Translates immediate Blaze3D calls at the point where their GL inputs are current. */
public final class Blaze3dCommands {
    private static final GlRenderDevice DEVICE = new GlRenderDevice();
    private static final CommandBuffer IMMEDIATE = DEVICE.createCommandBuffer();

    private Blaze3dCommands() {}

    public static void drawElements(int mode, int count, int type, long offset) {
        if (offset == 0) {
            IMMEDIATE.drawElements(mode, count, type);
        } else {
            IMMEDIATE.drawElementsInstanced(mode, count, type, offset, 1);
        }
        submit();
    }

    public static void clear(int mask) {
        int colorAndDepth = GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT;
        if ((mask & ~colorAndDepth) == 0) {
            IMMEDIATE.clear((mask & GL11.GL_COLOR_BUFFER_BIT) != 0, (mask & GL11.GL_DEPTH_BUFFER_BIT) != 0);
        } else {
            IMMEDIATE.custom(() -> GL11.glClear(mask));
        }
        submit();
    }

    public static void copyDepth(int source, int destination, int sourceWidth, int sourceHeight,
                                 int destinationWidth, int destinationHeight) {
        RenderSystem.assertOnRenderThreadOrInit();
        // The source and destination are Minecraft-owned GL state.
        DEVICE.invalidateState();
        IMMEDIATE.blitFramebuffer(source, destination, sourceWidth, sourceHeight,
                destinationWidth, destinationHeight, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        submit();
    }

    private static void submit() {
        try {
            DEVICE.execute(IMMEDIATE);
        } finally {
            IMMEDIATE.reset();
        }
    }
}
