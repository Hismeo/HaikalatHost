package com.haikalat.host.client;

import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

import java.nio.FloatBuffer;
import java.util.Arrays;

/** Records one ordered terrain layer while Minecraft enumerates visible sections. */
public final class TerrainDrawBatch {
    private static final int BIND_ARRAY = 0;
    static final int BIND_SECTION = 1;
    static final int CHUNK_OFFSET = 2;
    static final int DRAW = 3;
    static final int BIND_SEQUENTIAL_SECTION = 4;

    private static final boolean SHARED_LAYOUT = Boolean.parseBoolean(System.getProperty("haikalathost.sharedTerrainLayout", "true"));
    private static final boolean REPORT_STATS = Boolean.getBoolean("haikalathost.terrainStats");
    // Store primitive command operands once per layer without allocating a Runnable per section.
    private static int[] stream = new int[1024];
    private static int streamSize;
    private static final GlRenderDevice DEVICE = new GlRenderDevice();
    private static final CommandBuffer COMMANDS = DEVICE.createCommandBuffer();
    private static final Runnable REPLAY = TerrainDrawBatch::replay;
    private static VertexArray sharedLayout;
    private static boolean recording;
    private static boolean useSharedLayout;
    private static boolean reported;
    private static int program;
    private static int chunkOffsetLocation;
    private static TerrainMultiDraw.ShaderBinding multiDrawShader;
    private static TerrainMultiDraw.Layer multiDrawLayer;

    private TerrainDrawBatch() {}

    public static void begin(ShaderInstance shader, TerrainMultiDraw.Layer batchLayer) {
        if (recording) {
            throw new IllegalStateException("A terrain layer is already recording");
        }
        program = shader.getId();
        Uniform chunkOffset = shader.CHUNK_OFFSET;
        chunkOffsetLocation = chunkOffset == null ? -1 : chunkOffset.getLocation();
        useSharedLayout = SHARED_LAYOUT && shader.getVertexFormat() == DefaultVertexFormat.BLOCK
                && GL.getCapabilities().OpenGL45;
        multiDrawLayer = batchLayer;
        multiDrawShader = batchLayer != null && useSharedLayout && chunkOffsetLocation >= 0
                ? TerrainMultiDraw.shader(shader) : null;
        if (multiDrawShader != null) TerrainMultiDraw.configureFaces(shader, batchLayer);
        recording = true;
    }

    public static void beginStockLayer(ShaderInstance shader, TerrainMultiDraw.Layer layer) {
        if (layer != null && (!TerrainMultiDraw.ENABLED || TerrainMultiDraw.shader(shader) != null)) {
            begin(shader, layer);
        }
    }

    public static boolean useSharedLayout() {
        return recording && useSharedLayout;
    }

    public static boolean recordSection(int vertices, int indices, boolean sequential, int count, int type,
                                        float x, float y, float z) {
        if (!useSharedLayout() || vertices <= 0 || indices <= 0 || chunkOffsetLocation < 0 || count < 6
                || count % 6 != 0 || (type != GL11C.GL_UNSIGNED_SHORT && type != GL11C.GL_UNSIGNED_INT)
                || (!sequential && multiDrawLayer != TerrainMultiDraw.Layer.TRANSLUCENT)) {
            return false;
        }
        int slot = reserve(TerrainMultiDraw.STREAM_INTS);
        stream[slot] = CHUNK_OFFSET;
        stream[slot + 1] = Float.floatToRawIntBits(x);
        stream[slot + 2] = Float.floatToRawIntBits(y);
        stream[slot + 3] = Float.floatToRawIntBits(z);
        stream[slot + 4] = sequential ? BIND_SEQUENTIAL_SECTION : BIND_SECTION;
        stream[slot + 5] = vertices;
        stream[slot + 6] = indices;
        stream[slot + 7] = DRAW;
        stream[slot + 8] = GL11C.GL_TRIANGLES;
        stream[slot + 9] = count;
        stream[slot + 10] = type;
        stream[slot + 11] = 0;
        stream[slot + 12] = 0;
        return true;
    }

    public static boolean recordSharedBinding(int vertices, int indices, boolean sequentialIndices) {
        if (indices <= 0) {
            return false;
        }
        int slot = reserve(3);
        stream[slot] = sequentialIndices ? BIND_SEQUENTIAL_SECTION : BIND_SECTION;
        stream[slot + 1] = vertices;
        stream[slot + 2] = indices;
        return true;
    }

    public static boolean recordVertexArray(int vao) {
        if (!recording) {
            return false;
        }
        int slot = reserve(2);
        stream[slot] = BIND_ARRAY;
        stream[slot + 1] = vao;
        return true;
    }

    public static boolean recordIndexedDraw(int mode, int count, int indexType, long offset) {
        if (!recording) {
            return false;
        }
        int slot = reserve(6);
        stream[slot] = DRAW;
        stream[slot + 1] = mode;
        stream[slot + 2] = count;
        stream[slot + 3] = indexType;
        stream[slot + 4] = (int) offset;
        stream[slot + 5] = (int) (offset >>> Integer.SIZE);
        return true;
    }

    public static boolean recordUniform3(int location, FloatBuffer value) {
        if (!recording) {
            return false;
        }
        if (program == 0 || chunkOffsetLocation != location || value.remaining() != 3) {
            // The raw upload must occur after all earlier recorded draws.
            multiDrawShader = null;
            submit();
            return false;
        }
        int first = value.position();
        recordChunkOffset(Float.floatToRawIntBits(value.get(first)), Float.floatToRawIntBits(value.get(first + 1)),
                Float.floatToRawIntBits(value.get(first + 2)));
        return true;
    }

    private static void recordChunkOffset(int x, int y, int z) {
        int slot = reserve(4);
        stream[slot] = CHUNK_OFFSET;
        stream[slot + 1] = x;
        stream[slot + 2] = y;
        stream[slot + 3] = z;
    }

    private static int reserve(int length) {
        int first = streamSize;
        int end = first + length;
        if (end > stream.length) {
            stream = Arrays.copyOf(stream, Math.max(end, stream.length * 2));
        }
        streamSize = end;
        return first;
    }

    static VertexArray createSharedLayout() {
        VertexArray layout = new VertexArray();
        VertexFormat format = DefaultVertexFormat.BLOCK;
        for (int index = 0; index < format.getElements().size(); index++) {
            VertexFormatElement element = format.getElements().get(index);
            int type = element.type().glType();
            int offset = format.getOffset(element);
            if (element.usage() == VertexFormatElement.Usage.UV && type != GL11C.GL_FLOAT) {
                GL45C.glVertexArrayAttribIFormat(layout.id(), index, element.count(), type, offset);
            } else {
                boolean normalized = element.usage() == VertexFormatElement.Usage.COLOR || element.usage() == VertexFormatElement.Usage.NORMAL;
                GL45C.glVertexArrayAttribFormat(layout.id(), index, element.count(), type, normalized, offset);
            }
            GL45C.glVertexArrayAttribBinding(layout.id(), index, 0);
            GL45C.glEnableVertexArrayAttrib(layout.id(), index);
        }
        return layout;
    }

    private static void replay() {
        if (multiDrawShader == null || !TerrainMultiDraw.replay(multiDrawLayer, multiDrawShader,
                program, chunkOffsetLocation, stream, streamSize)) {
            replayImmediate(program, chunkOffsetLocation, stream, 0, streamSize);
        }
    }

    static void replayImmediate(int program, int chunkOffsetLocation, int[] commands, int first, int end) {
        int currentArray = -1;
        int currentVertexBuffer = -1;
        int currentIndexBuffer = -1;
        int arrayBinds = 0;
        int sharedBinds = 0;
        int draws = 0;
        int stride = DefaultVertexFormat.BLOCK.getVertexSize();
        try {
            for (int i = first; i < end;) {
                switch (commands[i++]) {
                    case BIND_ARRAY -> {
                        int array = commands[i++];
                        if (currentArray != array) {
                            GL30C.glBindVertexArray(array);
                            currentArray = array;
                            if (REPORT_STATS) {
                                arrayBinds++;
                            }
                        }
                    }
                    case BIND_SECTION, BIND_SEQUENTIAL_SECTION -> {
                        int vertices = commands[i++];
                        int indices = commands[i++];
                        if (sharedLayout == null) {
                            sharedLayout = createSharedLayout();
                        }
                        if (currentArray != sharedLayout.id()) {
                            GL30C.glBindVertexArray(sharedLayout.id());
                            currentArray = sharedLayout.id();
                            if (REPORT_STATS) {
                                arrayBinds++;
                            }
                        }
                        if (currentVertexBuffer != vertices) {
                            GL43C.glBindVertexBuffer(0, vertices, 0L, stride);
                            currentVertexBuffer = vertices;
                        }
                        if (currentIndexBuffer != indices) {
                            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indices);
                            currentIndexBuffer = indices;
                        }
                        if (REPORT_STATS) {
                            sharedBinds++;
                        }
                    }
                    case CHUNK_OFFSET -> GL41C.glProgramUniform3f(program, chunkOffsetLocation,
                            Float.intBitsToFloat(commands[i++]), Float.intBitsToFloat(commands[i++]), Float.intBitsToFloat(commands[i++]));
                    case DRAW -> {
                        int mode = commands[i++];
                        int count = commands[i++];
                        int type = commands[i++];
                        long offset = Integer.toUnsignedLong(commands[i++]) | ((long) commands[i++] << Integer.SIZE);
                        if (offset == 0) {
                            GL11C.glDrawElements(mode, count, type, 0L);
                        } else {
                            GL31C.glDrawElementsInstanced(mode, count, type, offset, 1);
                        }
                        if (REPORT_STATS) {
                            draws++;
                        }
                    }
                    default -> throw new IllegalStateException("Unknown terrain command");
                }
            }
        } finally {
            // The shared VAO must not keep deleted section buffers alive across frames or level changes.
            if (currentVertexBuffer != -1) {
                GL45C.glVertexArrayVertexBuffer(sharedLayout.id(), 0, 0, 0L, 0);
                GL45C.glVertexArrayElementBuffer(sharedLayout.id(), 0);
            }
        }
        if (REPORT_STATS && !reported && draws >= 128) {
            reported = true;
            LogUtils.getLogger().info("Haikalat terrain layer had {} draws, {} VAO binds and {} shared-layout sections", draws, arrayBinds, sharedBinds);
        }
    }

    private static void submit() {
        if (streamSize == 0) {
            return;
        }
        try {
            COMMANDS.custom(REPLAY);
            DEVICE.execute(COMMANDS);
        } finally {
            COMMANDS.reset();
            streamSize = 0;
        }
    }

    public static void flushPending() {
        submit();
    }

    public static void end() {
        try {
            submit();
        } finally {
            discard();
        }
    }

    public static void discard() {
        recording = false;
        multiDrawShader = null;
        multiDrawLayer = null;
        streamSize = 0;
    }

    public static void close() {
        TerrainMultiDraw.close();
        if (sharedLayout != null) {
            sharedLayout.close();
            sharedLayout = null;
        }
    }
}
