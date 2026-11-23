package com.haikalat.host.client;

import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.core.mesh.InstanceBatchStats;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL45C;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Render-thread cache for opaque sections whose complete baked geometry is identical. */
final class TerrainInstanceBatch implements AutoCloseable {
    static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("haikalathost.terrainInstances", "true"));
    static final int INSTANCE_ATTRIBUTE = 7;
    static final int TRANSLATION_ATTRIBUTE = INSTANCE_ATTRIBUTE + 3;
    static final int LIGHTMAP_ATTRIBUTE = INSTANCE_ATTRIBUTE + 4;
    private static final boolean REPORT_STATS = Boolean.getBoolean("haikalathost.terrainStats");
    private static final int MAX_MESH_BYTES = 32 * 1024;
    private static final int MAX_TRACKED_BYTES = 8 * 1024 * 1024;
    private static final int MAX_INSTANCES = 4096;
    private static final int STRIDE = DefaultVertexFormat.BLOCK.getVertexSize();
    private static final Map<ByteBuffer, Geometry> GEOMETRIES = new HashMap<>();
    private static final Int2ObjectOpenHashMap<Geometry> SOURCES = new Int2ObjectOpenHashMap<>();
    private static int trackedBytes;

    private final ArrayList<Matrix4f> transforms = new ArrayList<>();
    private Geometry geometry;
    private Mesh mesh;
    private InstancedMeshBatch batch;
    private int capacity;
    private boolean reported;
    private boolean submitted;

    static void remember(int source, ByteBuffer vertices, int vertexCount, int indexCount) {
        int length = vertices.remaining();
        if (!ENABLED || length == 0 || length > MAX_MESH_BYTES || indexCount < 6 || indexCount % 6 != 0
                || vertexCount != indexCount / 6 * 4 || (long) vertexCount * STRIDE != length) {
            return;
        }
        // ByteBuffer compares the entire remaining payload, including light, tint and quad order.
        Geometry match = GEOMETRIES.get(vertices);
        if (match == null) {
            if (trackedBytes + length > MAX_TRACKED_BYTES) {
                return;
            }
            ByteBuffer snapshot = ByteBuffer.allocate(length);
            snapshot.put(vertices.duplicate()).flip();
            match = new Geometry(snapshot);
            GEOMETRIES.put(snapshot, match);
            trackedBytes += length;
        }
        SOURCES.put(source, match);
        match.references++;
    }

    static void forget(int source) {
        Geometry removed = SOURCES.remove(source);
        if (removed != null && --removed.references == 0) {
            GEOMETRIES.remove(removed.vertices);
            trackedBytes -= removed.vertices.remaining();
        }
    }

    static void clearGeometry() {
        SOURCES.clear();
        GEOMETRIES.clear();
        trackedBytes = 0;
    }

    boolean replay(TerrainMultiDraw.Layer layer, TerrainMultiDraw.ShaderBinding shader, int program,
                   int chunkOffsetLocation, int[] stream, int size) {
        submitted = false;
        int stride = TerrainMultiDraw.STREAM_INTS;
        int count = size / stride;
        if (size % stride != 0 || count < 2 || count > MAX_INSTANCES) {
            return false;
        }
        int firstSource = stream[TerrainMultiDraw.VERTICES];
        Geometry first = SOURCES.get(firstSource);
        if (first == null) {
            return false;
        }
        int indexCount = stream[TerrainMultiDraw.INDEX_COUNT];
        if (indexCount != first.vertices.remaining() / STRIDE / 4 * 6) {
            return false;
        }
        // One compatible run keeps section order and is at most one draw per pending submission.
        for (int i = 0; i < size; i += stride) {
            if (stream[i] != TerrainDrawBatch.CHUNK_OFFSET || stream[i + 4] != TerrainDrawBatch.BIND_SEQUENTIAL_SECTION
                    || stream[i + 7] != TerrainDrawBatch.DRAW || stream[i + 8] != GL11C.GL_TRIANGLES
                    || stream[i + TerrainMultiDraw.INDEX_COUNT] != indexCount
                    || stream[i + 11] != 0 || stream[i + 12] != 0
                    || SOURCES.get(stream[i + TerrainMultiDraw.VERTICES]) != first) {
                return false;
            }
        }
        int previousArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            if (geometry != first || capacity < count) {
                recreate(first, firstSource, indexCount, count);
            }
            while (transforms.size() < count) {
                transforms.add(new Matrix4f());
            }
            for (int instance = 0; instance < count; instance++) {
                int i = instance * stride;
                transforms.get(instance).translation(offset(stream, i + 1), offset(stream, i + 2), offset(stream, i + 3));
            }
            GL41C.glProgramUniform1i(program, shader.enabledUniform(), 2);
            batch.prepareOwnedSnapshots(transforms.subList(0, count));
            batch.drawPrepared();
            batch.finishPrepared();
            submitted = true;
        } finally {
            try {
                if (batch != null) {
                    batch.abortPrepared();
                }
            } finally {
                int last = size - stride;
                GL41C.glProgramUniform1i(program, shader.enabledUniform(), 0);
                GL41C.glProgramUniform3f(program, chunkOffsetLocation, offset(stream, last + 1),
                        offset(stream, last + 2), offset(stream, last + 3));
                GL30C.glBindVertexArray(previousArray);
                GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
            }
        }
        if (REPORT_STATS && !reported) {
            LogUtils.getLogger().info("Haikalat {} terrain used InstancedMeshBatch for {} sections in {} draw",
                    layer, count, batch.statistics().drawCalls());
            reported = true;
        }
        return true;
    }

    private void recreate(Geometry template, int source, int indexCount, int instances) {
        close();
        int[] indices = new int[indexCount];
        for (int i = 0, v = 0; i < indices.length; i += 6, v += 4) {
            indices[i] = v;
            indices[i + 1] = v + 1;
            indices[i + 2] = v + 2;
            indices[i + 3] = v + 2;
            indices[i + 4] = v + 3;
            indices[i + 5] = v;
        }
        int length = template.vertices.remaining();
        mesh = Mesh.builder().vertices(new float[length / Float.BYTES], STRIDE, attributes()).indices(indices).build();
        try {
            // Preserve the packed Minecraft vertex bytes without a readback or float conversion.
            GL45C.glCopyNamedBufferSubData(source, mesh.vertexBuffer().id(), 0, 0, length);
            capacity = Integer.highestOneBit(instances - 1) << 1;
            batch = InstancedMeshBatch.of(mesh, capacity, INSTANCE_ATTRIBUTE);
            geometry = template;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private static VertexAttribute[] attributes() {
        VertexFormat format = DefaultVertexFormat.BLOCK;
        VertexAttribute[] attributes = new VertexAttribute[format.getElements().size()];
        for (int index = 0; index < attributes.length; index++) {
            VertexFormatElement element = format.getElements().get(index);
            boolean lightmap = element == VertexFormatElement.UV2;
            // The batch API uses glVertexAttribPointer. A separate float shader input preserves every short.
            attributes[index] = VertexAttribute.builder().index(lightmap ? LIGHTMAP_ATTRIBUTE : index)
                    .size(element.count()).type(element.type().glType()).offsetBytes(format.getOffset(element))
                    .normalized(element.usage() == VertexFormatElement.Usage.COLOR
                            || element.usage() == VertexFormatElement.Usage.NORMAL).build();
        }
        return attributes;
    }

    private static float offset(int[] stream, int index) {
        return Float.intBitsToFloat(stream[index]);
    }

    InstanceBatchStats statistics() {
        return submitted ? batch.statistics() : InstanceBatchStats.empty();
    }

    @Override
    public void close() {
        submitted = false;
        if (batch != null) {
            batch.close();
            batch = null;
        }
        if (mesh != null) {
            mesh.close();
            mesh = null;
        }
        geometry = null;
        capacity = 0;
        transforms.clear();
    }

    private static final class Geometry {
        private final ByteBuffer vertices;
        private int references;

        Geometry(ByteBuffer vertices) {
            this.vertices = vertices;
        }
    }
}
