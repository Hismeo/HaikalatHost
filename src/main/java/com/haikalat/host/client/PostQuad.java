package com.haikalat.host.client;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.state.HostGlState;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.backend.vertex.VertexLayout;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryStack;

/** Geometry for the pixel-coordinate Position input of Minecraft post effects. */
final class PostQuad implements AutoCloseable {
    private final VertexArray array;
    private final GlBuffer vertices;
    private int width;
    private int height;

    PostQuad() {
        try (HostGlState ignored = HostGlState.capture()) {
            array = new VertexArray();
            vertices = GlBuffer.arrayBuffer(GL15.GL_DYNAMIC_DRAW);
            try {
                array.bindVertexBuffer(vertices, VertexLayout.interleaved(3 * Float.BYTES,
                        VertexAttribute.builder().index(0).size(3).build()));
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }
    }

    void prepare(int width, int height) {
        if (this.width == width && this.height == height) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            float depth = 500.0f;
            vertices.upload(stack.floats(0, 0, depth, width, 0, depth, width, height, depth, 0, height, depth));
        }
        this.width = width;
        this.height = height;
    }

    void draw(CommandBuffer commands) {
        commands.bindVertexArray(array.id()).drawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
    }

    @Override
    public void close() {
        array.close();
        vertices.close();
    }
}
