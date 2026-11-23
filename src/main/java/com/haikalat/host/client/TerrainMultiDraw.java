package com.haikalat.host.client;

import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.kaleblangley.haikalat.core.mesh.InstanceBatchStats;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.WeakHashMap;

/** Submits ordered stock terrain draws from shared vertex and index buffers. All access is render-thread only. */
public final class TerrainMultiDraw {
    static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("haikalathost.terrainMultiDraw", "true"));
    private static final String STOCK_VERTEX_HASH = "e5ff19e08740d63b03e02b0bfa2ef970f53e47ad44c4ee1c390c670b8edadfa7";
    private static final String OFFSET_ATTRIBUTE = "HaikalatSectionOffset";
    private static final String INSTANCE_TRANSLATION = "HaikalatInstanceTranslation";
    private static final String INSTANCE_LIGHTMAP = "HaikalatInstanceLightmap";
    private static final String BATCH_UNIFORM = "HaikalatTerrainBatch";
    private static final boolean REPORT_STATS = Boolean.getBoolean("haikalathost.terrainStats");
    private static final int VERTEX_BYTES = DefaultVertexFormat.BLOCK.getVertexSize();
    // The arena grows on demand. Large views need enough resident space to keep all terrain layers batched.
    private static final int VERTEX_BUDGET_MIB = Math.clamp(
            Integer.getInteger("haikalathost.terrainVertexBudgetMiB", 256), 16, 512);
    static final int MAX_VERTICES = (VERTEX_BUDGET_MIB * 1024 * 1024) / VERTEX_BYTES;
    private static final int MAX_INDEX_WORDS = (24 * 1024 * 1024) / Integer.BYTES;
    private static final int MIN_DRAWS = 16;
    // First command, count, element buffer, index type, first section in the input stream.
    private static final int GROUP_INTS = 5;
    private static final int FALLBACK = -1;
    private static final int EPOCH_NANOS = 250_000_000;
    private static final int RETAIN_EPOCHS = 8;
    // Chunk offset (4 ints), section-buffer bind (3), indexed draw (6).
    static final int STREAM_INTS = 13;
    static final int VERTICES = 5;
    private static final int INDICES = 6;
    static final int INDEX_COUNT = 9;
    static final int INDEX_TYPE = 10;
    private static final int ARGUMENT_BYTES = 5 * Integer.BYTES;
    // DrawElementsIndirectCommand followed by the exact vanilla chunk offset for the instance.
    private static final int DRAW_BYTES = ARGUMENT_BYTES + 3 * Float.BYTES;
    private static final WeakHashMap<ShaderInstance, ShaderBinding> SHADERS = new WeakHashMap<>();
    private static final ResourceLocation FOG_INCLUDE = ResourceLocation.withDefaultNamespace("shaders/include/fog.glsl");
    private static final ResourceLocation LIGHT_INCLUDE = ResourceLocation.withDefaultNamespace("shaders/include/light.glsl");
    private static final TerrainBufferArena VERTEX_ARENA = new TerrainBufferArena(VERTEX_BYTES, 32768, MAX_VERTICES);
    // Quad index streams are 4-byte aligned for either unsigned index type.
    private static final TerrainBufferArena INDEX_ARENA = new TerrainBufferArena(Integer.BYTES, 16384, MAX_INDEX_WORDS);
    private static final IntArrayList GROUPS = new IntArrayList();
    private static final Int2ObjectOpenHashMap<TerrainFaceRanges> FACE_RANGES = new Int2ObjectOpenHashMap<>();
    private static final TerrainFaceRanges.View FACE_VIEW = new TerrainFaceRanges.View();
    private static final Matrix4f FACE_CLIP = new Matrix4f();
    private static final Matrix4f FACE_MODEL_VIEW = new Matrix4f();

    private static VertexArray layout;
    private static int layoutDrawBuffer;
    private static long layoutArenaVersion = -1;
    private static int layoutAttribute = -1;
    private static long geometryVersion;
    private static long sortedIndexVersion;
    private static ByteBuffer draws;
    private static long epoch;
    private static long nextSweep;
    private static long nextReport;
    private static final long[] totals = new long[16];

    private TerrainMultiDraw() {}

    public static InputStream patchStockTerrain(String name, String sourcePack, InputStream original,
                                                ResourceProvider resources) throws IOException {
        String shaderName = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
        if (!ENABLED || !"vanilla".equals(sourcePack) || !isStockTerrain(shaderName)
                || !GL.getCapabilities().OpenGL45 || !GL.getCapabilities().GL_ARB_explicit_attrib_location
                || !fromVanilla(resources, coreResource(name, ".json"))
                || !fromVanilla(resources, coreResource(name, ".fsh"))
                || !vanillaIncludes(resources)) {
            return original;
        }
        byte[] bytes = original.readAllBytes();
        if (!STOCK_VERTEX_HASH.equals(sha256(bytes))) {
            return new ByteArrayInputStream(bytes);
        }
        String source = new String(bytes, StandardCharsets.UTF_8)
                .replace("#version 150", "#version 150\n#extension GL_ARB_explicit_attrib_location : require")
                .replace("uniform vec3 ChunkOffset;", "uniform vec3 ChunkOffset;\nuniform int " + BATCH_UNIFORM
                        + ";\nlayout(location = 7) in vec3 " + OFFSET_ATTRIBUTE + ";")
                .replace("Position + ChunkOffset", "Position + (" + BATCH_UNIFORM + " != 0 ? "
                        + OFFSET_ATTRIBUTE + " : ChunkOffset)");
        if (TerrainInstanceBatch.ENABLED && !"rendertype_translucent".equals(shaderName)) {
            source = source.replace("in ivec2 UV2;", "in ivec2 UV2;\nlayout(location = "
                            + TerrainInstanceBatch.LIGHTMAP_ATTRIBUTE + ") in vec2 " + INSTANCE_LIGHTMAP + ";\nlayout(location = "
                            + TerrainInstanceBatch.TRANSLATION_ATTRIBUTE + ") in vec3 " + INSTANCE_TRANSLATION + ";")
                    .replace(OFFSET_ATTRIBUTE + " : ChunkOffset", "(" + BATCH_UNIFORM + " == 2 ? "
                            + INSTANCE_TRANSLATION + " : " + OFFSET_ATTRIBUTE + ") : ChunkOffset")
                    .replace("Sampler2, UV2)", "Sampler2, " + BATCH_UNIFORM + " == 2 ? ivec2("
                            + INSTANCE_LIGHTMAP + ") : UV2)");
        }
        return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isStockTerrain(String shaderName) {
        return switch (shaderName) {
            case "rendertype_solid", "rendertype_cutout_mipped", "rendertype_cutout", "rendertype_translucent" -> true;
            default -> false;
        };
    }

    public static Layer layerFor(RenderType layer) {
        if (layer == RenderType.solid()) return Layer.SOLID;
        if (layer == RenderType.cutoutMipped()) return Layer.CUTOUT_MIPPED;
        if (layer == RenderType.cutout()) return Layer.CUTOUT;
        if (layer == RenderType.translucent()) return Layer.TRANSLUCENT;
        return null;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static ShaderBinding shader(ShaderInstance shader) {
        return SHADERS.get(shader);
    }

    public static void registerStockShader(ShaderInstance shader, ResourceProvider resources, String stockName) {
        if (!ENABLED || !isStockTerrain(stockName) || !GL.getCapabilities().OpenGL45
                || !fromVanilla(resources, coreResource(shader.getName(), ".json"))
                || !fromVanilla(resources, coreResource(shader.getVertexProgram().getName(), ".vsh"))
                || !fromVanilla(resources, coreResource(shader.getFragmentProgram().getName(), ".fsh"))
                || !vanillaIncludes(resources)) {
            return;
        }
        // A fragment can also read ChunkOffset. Only known stock programs can use the per-instance offset.
        int enabled = GL20C.glGetUniformLocation(shader.getId(), BATCH_UNIFORM);
        int attribute = GL20C.glGetAttribLocation(shader.getId(), OFFSET_ATTRIBUTE);
        if (enabled >= 0 && attribute >= DefaultVertexFormat.BLOCK.getElements().size()) {
            boolean instancing = TerrainInstanceBatch.ENABLED
                    && GL20C.glGetAttribLocation(shader.getId(), INSTANCE_TRANSLATION) == TerrainInstanceBatch.TRANSLATION_ATTRIBUTE
                    && GL20C.glGetAttribLocation(shader.getId(), INSTANCE_LIGHTMAP) == TerrainInstanceBatch.LIGHTMAP_ATTRIBUTE;
            SHADERS.put(shader, new ShaderBinding(enabled, attribute, instancing));
        }
    }

    private static ResourceLocation coreResource(String name, String extension) {
        ResourceLocation id = ResourceLocation.parse(name);
        return id.withPath("shaders/core/" + id.getPath() + extension);
    }

    private static boolean vanillaIncludes(ResourceProvider resources) {
        return fromVanilla(resources, FOG_INCLUDE) && fromVanilla(resources, LIGHT_INCLUDE);
    }

    private static boolean fromVanilla(ResourceProvider resources, ResourceLocation location) {
        return resources.getResource(location).filter(resource -> "vanilla".equals(resource.sourcePackId())).isPresent();
    }

    public static void beginFrame() {
        long now = System.nanoTime();
        if (nextSweep == 0 || now - nextSweep >= 0) {
            long ticks = nextSweep == 0 ? 1 : 1 + (now - nextSweep) / EPOCH_NANOS;
            epoch += ticks;
            nextSweep = now + EPOCH_NANOS;
            int evictedVertices = VERTEX_ARENA.evictBefore(epoch - RETAIN_EPOCHS);
            int evictedIndices = INDEX_ARENA.evictBefore(epoch - RETAIN_EPOCHS);
            if (evictedVertices != 0) geometryVersion++;
            if (evictedIndices != 0) sortedIndexVersion++;
            if (REPORT_STATS) {
                totals[12] += evictedVertices;
                totals[13] += evictedIndices;
            }
        }
        if (REPORT_STATS && (nextReport == 0 || now - nextReport >= 0)) {
            if (nextReport != 0) {
                System.out.println("TERRAIN_STATS " + System.currentTimeMillis() + " " + Arrays.toString(totals));
                Arrays.fill(totals, 0);
            }
            nextReport = now + 1_000_000_000L;
        }
    }

    static void configureFaces(ShaderInstance shader, Layer layer) {
        FACE_VIEW.valid = false;
        if (TerrainFaceRanges.ENABLED && layer != Layer.TRANSLUCENT && shader.MODEL_VIEW_MATRIX != null
                && shader.PROJECTION_MATRIX != null && GL11C.glIsEnabled(GL11C.GL_CULL_FACE)
                && GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE) == GL11C.GL_BACK
                && GL11C.glGetInteger(GL11C.GL_FRONT_FACE) == GL11C.GL_CCW
                && GL11C.glGetInteger(GL45C.GL_CLIP_ORIGIN) == GL45C.GL_LOWER_LEFT) {
            FACE_MODEL_VIEW.set(shader.MODEL_VIEW_MATRIX.getFloatBuffer());
            FACE_CLIP.set(shader.PROJECTION_MATRIX.getFloatBuffer()).mul(FACE_MODEL_VIEW);
            FACE_VIEW.set(FACE_CLIP);
        }
    }

    static boolean replay(Layer layer, ShaderBinding shader, int program, int chunkOffsetLocation, int[] stream, int size) {
        int count = size / STREAM_INTS;
        if (layer != Layer.TRANSLUCENT && shader.instancing()
                && layer.instances.replay(layer, shader, program, chunkOffsetLocation, stream, size)) {
            if (REPORT_STATS) {
                totals[0]++;
                totals[1] += count;
                totals[14] += count;
            }
            return true;
        }
        if (size % STREAM_INTS != 0 || size < MIN_DRAWS * STREAM_INTS) {
            if (REPORT_STATS && size % STREAM_INTS == 0 && count != 0) {
                totals[0]++;
                totals[1] += count;
                totals[2] += count;
                totals[3]++;
                totals[4]++;
                totals[10] += count;
            }
            return false;
        }
        DrawCache cache = layer.cache;
        long indexVersion = layer == Layer.TRANSLUCENT ? sortedIndexVersion : 0;
        if (cache.matches(stream, size, indexVersion)) {
            if (cache.lastTouched != epoch) {
                // Refresh visibility in a stable scene without traversing residents every frame.
                for (int i = 0; i < size; i += STREAM_INTS) {
                    VERTEX_ARENA.touch(stream[i + VERTICES], epoch);
                    if (stream[i + 4] == TerrainDrawBatch.BIND_SECTION) {
                        INDEX_ARENA.touch(stream[i + INDICES], epoch);
                    }
                }
                cache.lastTouched = epoch;
            }
            draw(layer, shader, program, chunkOffsetLocation, stream, count);
            return true;
        }
        // Validate before building any draw. Production captures these commands at the section call site.
        for (int i = 0; i < size; i += STREAM_INTS) {
            int binding = stream[i + 4];
            int indexCount = stream[i + INDEX_COUNT];
            int indexType = stream[i + INDEX_TYPE];
            if (stream[i] != TerrainDrawBatch.CHUNK_OFFSET || stream[i + 7] != TerrainDrawBatch.DRAW
                    || (binding != TerrainDrawBatch.BIND_SEQUENTIAL_SECTION
                        && !(binding == TerrainDrawBatch.BIND_SECTION && layer == Layer.TRANSLUCENT))
                    || stream[i + 8] != GL11C.GL_TRIANGLES || stream[i + 11] != 0 || stream[i + 12] != 0
                    || indexCount < 6 || indexCount % 6 != 0
                    || (indexType != GL11C.GL_UNSIGNED_SHORT && indexType != GL11C.GL_UNSIGNED_INT)) {
                if (REPORT_STATS) {
                    totals[0]++;
                    totals[1] += count;
                    totals[2] += count;
                    totals[3]++;
                    totals[4]++;
                    totals[11] += count;
                }
                return false;
            }
        }
        prepareDraws(count);
        GROUPS.clear();
        int commandCount = 0;
        cache.resetCounts();
        for (int i = 0; i < size; i += STREAM_INTS) {
            int indexCount = stream[i + INDEX_COUNT];
            int indexType = stream[i + INDEX_TYPE];
            int vertices = stream[i + VERTICES];
            TerrainFaceRanges faces = FACE_VIEW.valid && stream[i + 4] == TerrainDrawBatch.BIND_SEQUENTIAL_SECTION
                    ? FACE_RANGES.get(vertices) : null;
            int skipped = faces == null ? 0 : faces.select(FACE_VIEW, Float.intBitsToFloat(stream[i + 1]),
                    Float.intBitsToFloat(stream[i + 2]), Float.intBitsToFloat(stream[i + 3]), indexCount);
            if (skipped == indexCount / 6) {
                cache.culledQuads += skipped;
                continue;
            }
            int baseVertex = VERTEX_ARENA.copy(vertices, (indexCount / 6) * 4, epoch);
            if (baseVertex < 0) {
                cache.failed(baseVertex, true);
                appendGroup(commandCount, 1, FALLBACK, 0, i);
                continue;
            }
            int indexBuffer = stream[i + INDICES];
            int firstIndex = 0;
            if (stream[i + 4] == TerrainDrawBatch.BIND_SECTION) {
                boolean shorts = indexType == GL11C.GL_UNSIGNED_SHORT;
                int firstWord = INDEX_ARENA.copy(indexBuffer, shorts ? indexCount / 2 : indexCount, epoch);
                if (firstWord < 0) {
                    cache.failed(firstWord, false);
                    appendGroup(commandCount, 1, FALLBACK, 0, i);
                    continue;
                }
                firstIndex = shorts ? firstWord * 2 : firstWord;
                // Zero identifies the arena even if it grows again while filling this pass.
                indexBuffer = 0;
            }
            int ranges = skipped == 0 ? 1 : faces.rangeCount();
            cache.culledQuads += skipped;
            appendGroup(commandCount, ranges, indexBuffer, indexType, i);
            for (int range = 0; range < ranges; range++) {
                int rangeCount = skipped == 0 ? indexCount : faces.indexCount(range);
                int rangeFirst = skipped == 0 ? firstIndex : faces.firstIndex(range);
                ensureCommandCapacity();
                draws.putInt(rangeCount).putInt(1).putInt(rangeFirst).putInt(baseVertex).putInt(commandCount++);
                draws.putInt(stream[i + 1]).putInt(stream[i + 2]).putInt(stream[i + 3]);
            }
        }
        if (commandCount != 0) {
            if (cache.buffer == 0) {
                cache.buffer = GL45C.glCreateBuffers();
            }
            GL45C.glNamedBufferData(cache.buffer, draws.flip(), GL15C.GL_STREAM_DRAW);
        }
        cache.remember(stream, size, indexVersion);
        draw(layer, shader, program, chunkOffsetLocation, stream, count);
        return true;
    }

    private static void appendGroup(int first, int count, int indexBuffer, int indexType, int streamOffset) {
        int last = GROUPS.size() - GROUP_INTS;
        if (last >= 0 && GROUPS.getInt(last + 2) == indexBuffer && GROUPS.getInt(last + 3) == indexType
                && (indexBuffer != FALLBACK || GROUPS.getInt(last + 4) + GROUPS.getInt(last + 1) * STREAM_INTS == streamOffset)) {
            GROUPS.set(last + 1, GROUPS.getInt(last + 1) + count);
            return;
        }
        GROUPS.add(first);
        GROUPS.add(count);
        GROUPS.add(indexBuffer);
        GROUPS.add(indexType);
        GROUPS.add(streamOffset);
    }

    private static void prepareDraws(int count) {
        int bytes = Math.multiplyExact(count, DRAW_BYTES);
        if (draws == null || draws.capacity() < bytes) {
            int capacity = Math.max(4096, Integer.highestOneBit(bytes - 1) << 1);
            draws = draws == null ? MemoryUtil.memAlloc(capacity) : MemoryUtil.memRealloc(draws, capacity);
        }
        draws.clear();
    }

    private static void ensureCommandCapacity() {
        if (draws.remaining() < DRAW_BYTES) {
            int position = draws.position();
            draws = MemoryUtil.memRealloc(draws, Math.multiplyExact(draws.capacity(), 2));
            draws.clear().position(position);
        }
    }

    private static void draw(Layer layer, ShaderBinding shader, int program, int chunkOffsetLocation, int[] stream, int count) {
        DrawCache cache = layer.cache;
        if (layout == null) {
            layout = TerrainDrawBatch.createSharedLayout();
            GL45C.glVertexArrayBindingDivisor(layout.id(), 1, 1);
        }
        if (layoutDrawBuffer != cache.buffer) {
            GL45C.glVertexArrayVertexBuffer(layout.id(), 1, cache.buffer, ARGUMENT_BYTES, DRAW_BYTES);
            layoutDrawBuffer = cache.buffer;
        }
        // A new backing buffer can reuse a deleted GL name while the VAO still references the old object.
        long arenaVersion = VERTEX_ARENA.storageVersion();
        if (layoutArenaVersion != arenaVersion) {
            GL45C.glVertexArrayVertexBuffer(layout.id(), 0, VERTEX_ARENA.buffer(), 0L, VERTEX_BYTES);
            layoutArenaVersion = arenaVersion;
        }
        if (layoutAttribute != shader.offsetAttribute()) {
            if (layoutAttribute >= 0) {
                GL45C.glDisableVertexArrayAttrib(layout.id(), layoutAttribute);
            }
            layoutAttribute = shader.offsetAttribute();
            GL45C.glVertexArrayAttribFormat(layout.id(), layoutAttribute, 3, GL11C.GL_FLOAT, false, 0);
            GL45C.glVertexArrayAttribBinding(layout.id(), layoutAttribute, 1);
            GL45C.glEnableVertexArrayAttrib(layout.id(), layoutAttribute);
        }
        int previousIndirect = GL11C.glGetInteger(GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING);
        boolean indirect = false;
        int indirectCalls = 0;
        try {
            for (int i = 0; i < cache.groupSize; i += GROUP_INTS) {
                int groupCount = cache.groups[i + 1];
                int indexBuffer = cache.groups[i + 2];
                if (indexBuffer == FALLBACK) {
                    if (indirect) {
                        GL41C.glProgramUniform1i(program, shader.enabledUniform(), 0);
                        indirect = false;
                    }
                    int start = cache.groups[i + 4];
                    TerrainDrawBatch.replayImmediate(program, chunkOffsetLocation, stream, start,
                            start + groupCount * STREAM_INTS);
                } else {
                    if (!indirect) {
                        GL30C.glBindVertexArray(layout.id());
                        GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, cache.buffer);
                        GL41C.glProgramUniform1i(program, shader.enabledUniform(), 1);
                        indirect = true;
                    }
                    GL45C.glVertexArrayElementBuffer(layout.id(), indexBuffer == 0 ? INDEX_ARENA.buffer() : indexBuffer);
                    GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, cache.groups[i + 3],
                            (long) cache.groups[i] * DRAW_BYTES, groupCount, DRAW_BYTES);
                    indirectCalls++;
                }
            }
        } finally {
            GL41C.glProgramUniform1i(program, shader.enabledUniform(), 0);
            int last = (count - 1) * STREAM_INTS;
            GL41C.glProgramUniform3f(program, chunkOffsetLocation, Float.intBitsToFloat(stream[last + 1]),
                    Float.intBitsToFloat(stream[last + 2]), Float.intBitsToFloat(stream[last + 3]));
            GL45C.glVertexArrayElementBuffer(layout.id(), 0);
            GL15C.glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER, previousIndirect);
        }
        if (REPORT_STATS) {
            totals[0]++;
            totals[1] += count;
            totals[2] += cache.fallbackSections;
            if (cache.fallbackSections != 0) totals[3]++;
            if (cache.fallbackSections == count) totals[4]++;
            totals[5] += indirectCalls;
            totals[6] += cache.vertexCapacity;
            totals[7] += cache.vertexFragmentation;
            totals[8] += cache.indexCapacity;
            totals[9] += cache.indexFragmentation;
            totals[11] += cache.invalidSources;
            totals[15] += cache.culledQuads;
        }
    }

    public static void rememberVertices(int source, ByteBuffer vertices, int vertexCount, int indexCount) {
        if (ENABLED) {
            TerrainInstanceBatch.remember(source, vertices, vertexCount, indexCount);
        }
    }

    public static void rememberFaces(int source, TerrainFaceRanges ranges) {
        FACE_RANGES.put(source, ranges);
        geometryVersion++;
    }

    public static void invalidate(int source) {
        TerrainInstanceBatch.forget(source);
        boolean hadFaces = FACE_RANGES.remove(source) != null;
        if (VERTEX_ARENA.invalidate(source) || hadFaces) {
            geometryVersion++;
        }
    }

    public static void invalidateIndices(int source) {
        if (INDEX_ARENA.invalidate(source)) {
            sortedIndexVersion++;
        }
    }

    static int residentMeshCount() {
        return VERTEX_ARENA.size();
    }

    static int residentIndexCount() {
        return INDEX_ARENA.size();
    }

    public static void clearGeometry() {
        for (Layer layer : Layer.values()) {
            layer.instances.close();
        }
        TerrainInstanceBatch.clearGeometry();
        FACE_RANGES.clear();
        FACE_VIEW.valid = false;
        if (layout != null && layoutArenaVersion != -1) {
            GL45C.glVertexArrayVertexBuffer(layout.id(), 0, 0, 0L, 0);
        }
        VERTEX_ARENA.clear();
        INDEX_ARENA.clear();
        geometryVersion++;
        sortedIndexVersion++;
        layoutArenaVersion = -1;
    }

    static void close() {
        if (layout != null) {
            layout.close();
            layout = null;
        }
        for (Layer layer : Layer.values()) {
            layer.cache.close();
        }
        clearGeometry();
        if (draws != null) {
            MemoryUtil.memFree(draws);
            draws = null;
        }
        SHADERS.clear();
        layoutAttribute = -1;
        layoutDrawBuffer = 0;
    }

    public enum Layer {
        SOLID, CUTOUT_MIPPED, CUTOUT, TRANSLUCENT;

        private final DrawCache cache = new DrawCache();
        private final TerrainInstanceBatch instances = new TerrainInstanceBatch();

        InstanceBatchStats instanceStatistics() {
            return instances.statistics();
        }
    }

    private static final class DrawCache {
        private int buffer;
        private int[] commands = new int[0];
        private int size;
        private int[] groups = new int[0];
        private int groupSize;
        private long version;
        private long indexVersion;
        private long lastTouched;
        private int fallbackSections;
        private int vertexCapacity;
        private int vertexFragmentation;
        private int indexCapacity;
        private int indexFragmentation;
        private int invalidSources;
        private int culledQuads;
        private boolean faceViewValid;
        private double faceX, faceY, faceZ;

        void resetCounts() {
            fallbackSections = vertexCapacity = vertexFragmentation = indexCapacity = indexFragmentation = invalidSources = culledQuads = 0;
        }

        void failed(int reason, boolean vertex) {
            fallbackSections++;
            switch (reason) {
                case TerrainBufferArena.CAPACITY -> {
                    if (vertex) vertexCapacity++;
                    else indexCapacity++;
                }
                case TerrainBufferArena.FRAGMENTATION -> {
                    if (vertex) vertexFragmentation++;
                    else indexFragmentation++;
                }
                default -> invalidSources++;
            }
        }

        boolean matches(int[] stream, int length, long sortedVersion) {
            return invalidSources == 0 && length == size && version == geometryVersion && indexVersion == sortedVersion
                    && faceViewValid == FACE_VIEW.valid
                    && (!faceViewValid || (faceX == FACE_VIEW.x && faceY == FACE_VIEW.y && faceZ == FACE_VIEW.z))
                    && Arrays.equals(stream, 0, length, commands, 0, size);
        }

        void remember(int[] stream, int length, long sortedVersion) {
            if (commands.length < length) {
                commands = new int[Math.max(length, commands.length * 2)];
            }
            System.arraycopy(stream, 0, commands, 0, length);
            size = length;
            groupSize = GROUPS.size();
            if (groups.length < groupSize) {
                groups = new int[Math.max(groupSize, groups.length * 2)];
            }
            System.arraycopy(GROUPS.elements(), 0, groups, 0, groupSize);
            version = geometryVersion;
            indexVersion = sortedVersion;
            lastTouched = epoch;
            faceViewValid = FACE_VIEW.valid;
            faceX = FACE_VIEW.x;
            faceY = FACE_VIEW.y;
            faceZ = FACE_VIEW.z;
        }

        void close() {
            if (buffer != 0) {
                GL15C.glDeleteBuffers(buffer);
                buffer = 0;
            }
            commands = new int[0];
            size = 0;
            groups = new int[0];
            groupSize = 0;
            resetCounts();
        }
    }

    record ShaderBinding(int enabledUniform, int offsetAttribute, boolean instancing) {}

}
