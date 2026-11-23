package com.haikalat.host.client;

import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import org.lwjgl.opengl.GL15;

import java.util.ArrayList;
import java.util.List;

/** Owns the render system's shared sequential index buffers until the GL context closes. */
public final class SharedIndexBuffers {
    private static final List<GlBuffer> BUFFERS = new ArrayList<>(3);

    private SharedIndexBuffers() {}

    public static GlBuffer create() {
        GlBuffer buffer = GlBuffer.elementArrayBuffer(GL15.GL_DYNAMIC_DRAW);
        BUFFERS.add(buffer);
        return buffer;
    }

    public static void close() {
        for (GlBuffer buffer : BUFFERS) {
            buffer.close();
        }
        BUFFERS.clear();
    }
}
