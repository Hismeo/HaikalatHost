package com.haikalat.host.client;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL14C.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL45C.*;

/** A single framebuffer for both renderers, with no window swaps. */
final class BenchmarkTarget implements AutoCloseable {
    private final int width;
    private final int height;
    private final int framebuffer;
    private final int color;
    private final int depth;

    BenchmarkTarget(int width, int height) {
        this.width = width;
        this.height = height;
        framebuffer = glCreateFramebuffers();
        color = glCreateTextures(GL_TEXTURE_2D);
        depth = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(color, 1, GL_RGBA8, width, height);
        glTextureStorage2D(depth, 1, GL_DEPTH_COMPONENT24, width, height);
        glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, color, 0);
        glNamedFramebufferTexture(framebuffer, GL_DEPTH_ATTACHMENT, depth, 0);
        if (glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            close();
            throw new IllegalStateException("Offscreen framebuffer is incomplete");
        }
        RenderSystem.setupDefaultState(0, 0, width, height);
        glClearColor(0.04f, 0.07f, 0.1f, 1f);
    }

    void beginFrame() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    Pixels read() {
        IntBuffer rgba = BufferUtils.createIntBuffer(width * height);
        IntBuffer depthValues = BufferUtils.createIntBuffer(width * height);
        glGetTextureImage(color, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        glGetTextureImage(depth, 0, GL_DEPTH_COMPONENT, GL_FLOAT, depthValues);
        return new Pixels(rgba, depthValues);
    }

    @Override
    public void close() {
        glDeleteFramebuffers(framebuffer);
        glDeleteTextures(color);
        glDeleteTextures(depth);
    }

    record Pixels(IntBuffer rgba, IntBuffer depth) {}
}
