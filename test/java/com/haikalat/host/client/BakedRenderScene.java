package com.haikalat.host.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING;
import static org.lwjgl.opengl.GL15C.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING;
import static org.lwjgl.opengl.GL20C.glGetVertexAttribi;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;
import static org.lwjgl.opengl.GL45C.*;

/** Fixed Minecraft vertex buffers shared by both render paths. */
final class BakedRenderScene implements AutoCloseable {
    private static final VertexFormat.Mode MODE = VertexFormat.Mode.QUADS;
    private static final int SECTION_SIZE = 16;
    private static final int CLOUD_SIZE = 64;
    private static final int TEXTURE_SIZE = 16;
    private static final int QUADS_FOR_INT_INDICES = 16385;

    private final ShaderInstance shader;
    private ShaderInstance multiDrawShader;
    private final CloudMeshCulling cloudCulling;
    private final TerrainMultiDraw.Layer terrainLayer;
    private final boolean repeatedGeometry;
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final List<Draw> draws = new ArrayList<>();
    private int texture;
    private int lightmap;
    private int indexType;
    private int quads;
    private int sortedMeshes;
    private float terrainShift;
    private boolean customIndexDraw;
    private boolean faceFixture;

    private BakedRenderScene(ShaderInstance shader, TerrainMultiDraw.Layer terrainLayer, boolean repeatedGeometry) {
        this.shader = shader;
        this.terrainLayer = terrainLayer;
        this.repeatedGeometry = repeatedGeometry;
        this.cloudCulling = terrainLayer == null ? new CloudMeshCulling() : null;
    }

    static BakedRenderScene create(String name, ResourceProvider pack, int sections, int quadsPerSide) throws IOException {
        return create(name, pack, sections, quadsPerSide, false);
    }

    static BakedRenderScene createMixedTranslucent(ResourceProvider pack) throws IOException {
        return create("terrain-translucent", pack, 18, 2, true);
    }

    private static BakedRenderScene create(String name, ResourceProvider pack, int sections, int quadsPerSide,
                                           boolean mixedIndices) throws IOException {
        TerrainMultiDraw.Layer terrainLayer = switch (name) {
            case "terrain", "terrain-instances", "terrain-face-ranges" -> TerrainMultiDraw.Layer.SOLID;
            case "terrain-cutout" -> TerrainMultiDraw.Layer.CUTOUT;
            case "terrain-cutout-mipped" -> TerrainMultiDraw.Layer.CUTOUT_MIPPED;
            case "terrain-translucent" -> TerrainMultiDraw.Layer.TRANSLUCENT;
            default -> null;
        };
        boolean terrain = terrainLayer != null;
        String shaderName = switch (name) {
            case "terrain", "terrain-instances", "terrain-face-ranges" -> "rendertype_solid";
            case "terrain-cutout" -> "rendertype_cutout";
            case "terrain-cutout-mipped" -> "rendertype_cutout_mipped";
            case "terrain-translucent" -> "rendertype_translucent";
            default -> "rendertype_clouds";
        };
        VertexFormat format = terrain ? DefaultVertexFormat.BLOCK : DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL;
        var scene = new BakedRenderScene(new ShaderInstance(pack, ResourceLocation.withDefaultNamespace(shaderName), format),
                terrainLayer, name.equals("terrain-instances"));
        try {
            if (scene.shader.SCREEN_SIZE != null) {
                throw new IllegalStateException(shaderName + " requires a game window for ScreenSize");
            }
            boolean alphaTest = terrainLayer == TerrainMultiDraw.Layer.CUTOUT || terrainLayer == TerrainMultiDraw.Layer.CUTOUT_MIPPED;
            scene.texture = createTexture(255, terrain ? 170 : 220, alphaTest);
            RenderSystem.setShaderTexture(0, scene.texture);
            if (terrain) {
                scene.multiDrawShader = loadMultiDrawShader(pack, format, shaderName);
                scene.lightmap = createTexture(255, scene.repeatedGeometry ? 96 : 255, false);
                RenderSystem.setShaderTexture(2, scene.lightmap);
                RenderSystem.enableCull();
                if (name.equals("terrain-face-ranges")) {
                    scene.faceFixture = true;
                    scene.bakeFacingTerrain();
                } else {
                    scene.bakeTerrain(sections, quadsPerSide, mixedIndices);
                }
            } else {
                RenderSystem.disableCull();
                scene.bakeClouds(name.equals("clouds-offscreen"));
            }
            // The shared quad EBO can change index type while the fixture is uploaded.
            scene.indexType = RenderSystem.getSequentialBuffer(MODE).type().asGLType;
            VertexBuffer.unbind();
            return scene;
        } catch (IOException | RuntimeException | Error failure) {
            scene.close();
            throw failure;
        }
    }

    static ShaderInstance loadMultiDrawShader(ResourceProvider stockPack, VertexFormat format, String stockName) throws IOException {
        String alias = "haikalathost_benchmark_" + stockName;
        ResourceProvider provider = location -> {
            String path = location.getPath();
            String prefix = "shaders/core/" + alias;
            if (!path.startsWith(prefix) || !location.getNamespace().equals("minecraft")) {
                return stockPack.getResource(location);
            }
            return stockPack.getResource(location.withPath("shaders/core/" + stockName + path.substring(prefix.length())))
                    .map(resource -> new Resource(resource.source(), () -> {
                        try (var input = resource.open()) {
                            if (path.endsWith(".json")) {
                                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                                return new ByteArrayInputStream(json.replace(stockName, alias).getBytes(StandardCharsets.UTF_8));
                            }
                            var source = path.endsWith(".vsh")
                                    ? TerrainMultiDraw.patchStockTerrain(stockName, resource.sourcePackId(), input, stockPack) : input;
                            return new ByteArrayInputStream(source.readAllBytes());
                        }
                    }));
        };
        var shader = new ShaderInstance(provider, ResourceLocation.withDefaultNamespace(alias), format);
        TerrainMultiDraw.registerStockShader(shader, provider, stockName);
        return shader;
    }

    private boolean translucent() {
        return terrainLayer == TerrainMultiDraw.Layer.TRANSLUCENT;
    }

    private void bakeFacingTerrain() {
        float aspect = (float) RenderBenchmark.WIDTH / RenderBenchmark.HEIGHT;
        projection.perspective((float) Math.toRadians(60), aspect, 0.1f, 32f);
        view.translation(0.125f, 0, 0);
        try (var bytes = new ByteBufferBuilder(12 * DefaultVertexFormat.BLOCK.getVertexSize())) {
            for (int section = 0; section < 16; section++) {
                var builder = new BufferBuilder(bytes, MODE, DefaultVertexFormat.BLOCK);
                addBackFace(builder, 0.125f, 0xfff00000);
                addQuad(builder, 0, 0, 0, 1, 0xff50a0d0, true);
                addBackFace(builder, 0.25f, 0xff00f000);
                upload(builder.buildOrThrow(), bytes, false, (section % 4 - 2) * 1.5f, (section / 4 - 2) * 1.5f, -8);
            }
        }
    }

    private void addBackFace(BufferBuilder builder, float z, int color) {
        addVertex(builder, 0, 0, z, 0, 0, color, true);
        addVertex(builder, 0, 1, z, 0, 1, color, true);
        addVertex(builder, 1, 1, z, 1, 1, color, true);
        addVertex(builder, 1, 0, z, 1, 0, color, true);
    }

    void padVertexBuffer(int section) {
        int source = draws.get(section).vertices;
        int bytes = glGetNamedBufferParameteri(source, org.lwjgl.opengl.GL15C.GL_BUFFER_SIZE);
        int temporary = glCreateBuffers();
        try {
            glNamedBufferData(temporary, bytes, org.lwjgl.opengl.GL15C.GL_STATIC_DRAW);
            glCopyNamedBufferSubData(source, temporary, 0, 0, bytes);
            glNamedBufferData(source, bytes + DefaultVertexFormat.BLOCK.getVertexSize(), org.lwjgl.opengl.GL15C.GL_STATIC_DRAW);
            glCopyNamedBufferSubData(temporary, source, 0, 0, bytes);
            TerrainMultiDraw.invalidate(source);
        } finally {
            glDeleteBuffers(temporary);
        }
    }

    private void bakeTerrain(int sections, int quadsPerSide, boolean mixedIndices) {
        float aspect = (float) RenderBenchmark.WIDTH / RenderBenchmark.HEIGHT;
        int cells = translucent() ? (sections + 1) / 2 : sections;
        int columns = (int) Math.ceil(Math.sqrt(cells * aspect));
        int rows = (cells + columns - 1) / columns;
        float height = Math.max(rows * SECTION_SIZE, columns * SECTION_SIZE / aspect) + 2;
        projection.setOrtho(0, height * aspect, 0, height, 0.1f, 32);
        int meshQuads = quadsPerSide * quadsPerSide * (translucent() ? 2 : 1);
        try (var bytes = new ByteBufferBuilder(meshQuads * 4 * shader.getVertexFormat().getVertexSize())) {
            for (int section = 0; section < sections; section++) {
                int cell = translucent() ? section / 2 : section;
                float depth = translucent() ? -8f + (section % 2) * 0.5f : -8f - (section % 5) * 0.5f;
                // Both pairs straddle group boundaries. Moving later short draws into an earlier group changes blending.
                boolean intIndices = mixedIndices && section == 1;
                boolean sort = translucent() && !(mixedIndices && section == 3);
                BufferBuilder builder = buildTerrainMesh(bytes, repeatedGeometry ? 0 : section, quadsPerSide, false, intIndices);
                upload(builder.buildOrThrow(), bytes, sort, cell % columns * SECTION_SIZE + 1,
                        cell / columns * SECTION_SIZE + 1, depth);
            }
        }
    }

    private BufferBuilder buildTerrainMesh(ByteBufferBuilder bytes, int section, int quadsPerSide, boolean rebuilt,
                                           boolean intIndices) {
        float pitch = (float) SECTION_SIZE / quadsPerSide;
        var builder = new BufferBuilder(bytes, MODE, shader.getVertexFormat());
        for (int y = 0; y < quadsPerSide; y++) {
            for (int x = 0; x < quadsPerSide; x++) {
                int color = 0xff506830 + ((section + x) % 5) * 0x000c0000 + ((section + y) % 7) * 0x00000700;
                if (rebuilt) {
                    color ^= 0x002040f0;
                }
                if (translucent()) {
                    color &= 0x80ffffff;
                }
                addQuad(builder, x * pitch, y * pitch, 0, pitch * 0.92f, color, true);
                if (translucent()) {
                    addQuad(builder, x * pitch, y * pitch, 0.25f, pitch * 0.92f, color ^ 0x00393068, true);
                }
            }
        }
        if (intIndices) {
            // Let vanilla choose the index width. Only the quads above contribute pixels.
            for (int quad = 2 * quadsPerSide * quadsPerSide; quad < QUADS_FOR_INT_INDICES; quad++) {
                addQuad(builder, -1000, -1000, 0, 1, 0x800000ff, true);
            }
        }
        return builder;
    }

    void rebuildFirstTerrainMesh(int quadsPerSide) {
        Draw first = draws.getFirst();
        try (var bytes = new ByteBufferBuilder(quadsPerSide * quadsPerSide * 4 * shader.getVertexFormat().getVertexSize())) {
            MeshData mesh = buildTerrainMesh(bytes, 0, quadsPerSide, true, false).buildOrThrow();
            MeshData.SortState sortState = translucent() ? mesh.sortQuads(bytes, VertexSorting.ORTHOGRAPHIC_Z) : null;
            first.buffer.bind();
            // This standalone benchmark calls the same invalidation as VertexBufferMixin.
            TerrainMultiDraw.invalidate(first.vertices);
            if (sortState != null) {
                TerrainMultiDraw.invalidateIndices(first.indices);
            }
            first.buffer.upload(mesh);
            draws.set(0, new Draw(first.buffer, first.vao, first.vertices, first.indices, first.count, first.x, first.y, first.z, sortState));
        } finally {
            VertexBuffer.unbind();
        }
    }

    void resortFirstTerrainMesh() {
        Draw first = draws.getFirst();
        try (var bytes = new ByteBufferBuilder(first.count * first.sortState.indexType().bytes)) {
            var result = first.sortState.buildSortedIndexBuffer(bytes, VertexSorting.byDistance(0, 0, -8));
            first.buffer.bind();
            TerrainMultiDraw.invalidateIndices(first.indices);
            first.buffer.uploadIndexBuffer(result);
            if (glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING) != first.indices) {
                throw new IllegalStateException("Vanilla changed the element buffer during a sort-only upload");
            }
        } finally {
            VertexBuffer.unbind();
        }
    }

    void setTerrainShift(float shift) {
        terrainShift = shift;
    }

    void setCustomIndexDraw(boolean customIndexDraw) {
        this.customIndexDraw = customIndexDraw;
    }

    private void bakeClouds(boolean offscreen) {
        setCloudOffscreen(offscreen);
        VertexFormat format = shader.getVertexFormat();
        try (var bytes = new ByteBufferBuilder(CLOUD_SIZE * CLOUD_SIZE * 4 * format.getVertexSize())) {
            var builder = new BufferBuilder(bytes, MODE, format);
            for (int y = 0; y < CLOUD_SIZE; y++) {
                for (int x = 0; x < CLOUD_SIZE; x++) {
                    float z = ((x / 8 + y / 8) & 1) * 0.125f;
                    addQuad(builder, x, y, z, 1, 0xcce0ebff, false);
                }
            }
            upload(builder.buildOrThrow(), bytes, false, 0, 0, 0);
        }
    }

    void setCloudOffscreen(boolean offscreen) {
        float width = CLOUD_SIZE * (float) RenderBenchmark.WIDTH / RenderBenchmark.HEIGHT;
        projection.setOrtho(0, width, 0, CLOUD_SIZE, 0.1f, 32);
        view.translation(offscreen ? width + 1 : (width - CLOUD_SIZE) * 0.5f, 0, -8);
    }

    private void addQuad(BufferBuilder builder, float x, float y, float z, float size, int color, boolean lit) {
        addVertex(builder, x, y, z, 0, 0, color, lit);
        addVertex(builder, x + size, y, z, 1, 0, color, lit);
        addVertex(builder, x + size, y + size, z, 1, 1, color, lit);
        addVertex(builder, x, y + size, z, 0, 1, color, lit);
    }

    private void addVertex(BufferBuilder builder, float x, float y, float z, float u, float v, int color, boolean lit) {
        var vertex = builder.addVertex(x, y, z).setColor(color).setUv(u, v).setNormal(0, 0, 1);
        if (lit) {
            vertex.setLight(repeatedGeometry ? LightTexture.pack(7, 11) : LightTexture.FULL_BRIGHT);
        }
    }

    private void upload(MeshData mesh, ByteBufferBuilder bytes, boolean sort, float x, float y, float z) {
        var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        boolean added = false;
        try {
            buffer.bind();
            int count = mesh.drawState().indexCount();
            MeshData.SortState sortState = sort ? mesh.sortQuads(bytes, VertexSorting.ORTHOGRAPHIC_Z) : null;
            if (cloudCulling != null) {
                cloudCulling.captureBounds(buffer, mesh);
            }
            var state = mesh.drawState();
            ByteBuffer verticesForInstances = null;
            if (repeatedGeometry) {
                verticesForInstances = ByteBuffer.allocate(mesh.vertexBuffer().remaining());
                verticesForInstances.put(mesh.vertexBuffer().duplicate()).flip();
            }
            TerrainFaceRanges faces = faceFixture ? TerrainFaceRanges.bake(mesh) : null;
            buffer.upload(mesh); // VertexBuffer takes ownership of MeshData.
            int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int vertices = glGetVertexAttribi(0, GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING);
            int indices = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
            if (vao == 0 || vertices == 0 || indices == 0) {
                throw new IllegalStateException("Minecraft did not bind all baked mesh buffers");
            }
            if (verticesForInstances != null) {
                TerrainMultiDraw.rememberVertices(vertices, verticesForInstances, state.vertexCount(), state.indexCount());
            }
            if (faces != null) TerrainMultiDraw.rememberFaces(vertices, faces);
            draws.add(new Draw(buffer, vao, vertices, indices, count, x, y, z, sortState));
            if (sortState != null) {
                sortedMeshes++;
            }
            added = true;
            quads += count / 6;
        } finally {
            if (!added) {
                buffer.close();
            }
        }
    }

    private static int createTexture(int bright, int dark, boolean cutout) {
        int id = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(id, 1, GL_RGBA8, TEXTURE_SIZE, TEXTURE_SIZE);
        glTextureParameteri(id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pixels = stack.malloc(TEXTURE_SIZE * TEXTURE_SIZE * 4);
            for (int y = 0; y < TEXTURE_SIZE; y++) {
                for (int x = 0; x < TEXTURE_SIZE; x++) {
                    boolean brightTile = ((x / 4 + y / 4) & 1) == 0;
                    byte value = (byte) (brightTile ? bright : dark);
                    byte alpha = (byte) (cutout && !brightTile ? 0 : 255);
                    pixels.put(value).put(value).put(value).put(alpha);
                }
            }
            glTextureSubImage2D(id, 0, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels.flip());
        }
        return id;
    }

    int meshes() {
        return draws.size();
    }

    int sortedMeshes() {
        return sortedMeshes;
    }

    int quads() {
        return quads;
    }

    void render(RenderBenchmark.Renderer renderer) {
        boolean host = renderer != RenderBenchmark.Renderer.VANILLA;
        if (translucent()) {
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            try {
                renderTerrain(host, renderer == RenderBenchmark.Renderer.MULTI_DRAW);
            } finally {
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            }
        } else if (cloudCulling == null) {
            renderTerrain(host, renderer == RenderBenchmark.Renderer.MULTI_DRAW);
        } else {
            renderClouds(host);
        }
    }

    private void renderTerrain(boolean host, boolean multiDraw) {
        ShaderInstance program = multiDraw ? multiDrawShader : shader;
        program.setDefaultUniforms(MODE, view, projection, null);
        program.apply();
        Uniform offset = program.CHUNK_OFFSET;
        if (host) {
            TerrainDrawBatch.begin(program, multiDraw ? terrainLayer : null);
        }
        try {
            boolean sharedLayout = host && TerrainDrawBatch.useSharedLayout();
            // The same per-section calls as LevelRenderer.renderSectionLayer, using baked visible sections.
            for (Draw draw : draws) {
                offset.set(draw.x + terrainShift, draw.y, draw.z);
                if (multiDraw && !customIndexDraw && TerrainDrawBatch.recordSection(draw.vertices, draw.indices,
                        draw.sortState == null, draw.count,
                        draw.sortState == null ? indexType : draw.sortState.indexType().asGLType,
                        draw.x + terrainShift, draw.y, draw.z)) {
                    continue;
                }
                if (host) {
                    if (!TerrainDrawBatch.recordUniform3(offset.getLocation(), offset.getFloatBuffer())) {
                        offset.upload();
                    }
                    BufferUploader.invalidate();
                    boolean sequential = draw.sortState == null && (!customIndexDraw || draw != draws.getFirst());
                    if (!sharedLayout || !TerrainDrawBatch.recordSharedBinding(draw.vertices, draw.indices, sequential)) {
                        TerrainDrawBatch.recordVertexArray(draw.vao);
                    }
                    int type = draw.sortState == null ? indexType : draw.sortState.indexType().asGLType;
                    TerrainDrawBatch.recordIndexedDraw(MODE.asGLMode, draw.count, type, 0L);
                } else {
                    offset.upload();
                    draw.buffer.bind();
                    draw.buffer.draw();
                }
            }
            offset.set(0f, 0f, 0f);
            if (host) {
                TerrainDrawBatch.end();
            }
        } finally {
            if (host) {
                TerrainDrawBatch.discard();
            }
            program.clear();
            VertexBuffer.unbind();
        }
    }

    private void renderClouds(boolean host) {
        Draw cloud = draws.getFirst();
        boolean culled = host && cloudCulling.isOutsideStockMesh(cloud.buffer, view, projection);
        if (culled) {
            BufferUploader.invalidate();
        } else {
            cloud.buffer.bind();
        }
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        try {
            // Keep the depth and color passes and their cleanup in vanilla order.
            RenderSystem.colorMask(false, false, false, false);
            drawCloud(cloud, host, culled);
            RenderSystem.colorMask(true, true, true, true);
            drawCloud(cloud, host, culled);
        } finally {
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            VertexBuffer.unbind();
        }
    }

    private void drawCloud(Draw cloud, boolean host, boolean culled) {
        setDefaultUniforms();
        if (CloudMeshCulling.shouldApplyShader(shader, culled)) {
            shader.apply();
        }
        try {
            if (!culled) {
                if (host) {
                    Blaze3dCommands.drawElements(MODE.asGLMode, cloud.count, indexType, 0L);
                } else {
                    cloud.buffer.draw();
                }
            }
        } finally {
            shader.clear();
        }
    }

    private void setDefaultUniforms() {
        // Both stock shaders were checked at bake time to have no ScreenSize uniform.
        shader.setDefaultUniforms(MODE, view, projection, null);
    }

    @Override
    public void close() {
        VertexBuffer.unbind();
        for (Draw draw : draws) {
            TerrainMultiDraw.invalidate(draw.vertices);
            if (draw.sortState != null) {
                TerrainMultiDraw.invalidateIndices(draw.indices);
            }
            draw.buffer.close();
        }
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.setShaderTexture(2, 0);
        glDeleteTextures(texture);
        glDeleteTextures(lightmap);
        if (multiDrawShader != null) {
            multiDrawShader.close();
        }
        shader.close();
    }

    private record Draw(VertexBuffer buffer, int vao, int vertices, int indices, int count, float x, float y, float z,
                        MeshData.SortState sortState) {}
}
