package com.haikalat.host.client;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.vertex.VertexAttribute;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.mesh.InstancedMeshBatch;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;

/** One indexed quad, repeated with the same transforms and program on both paths. */
final class InstancedBatchScene {
    private static final int INSTANCE_ATTRIBUTE = 4;
    private static final VertexFormat.Mode MODE = VertexFormat.Mode.QUADS;
    private static final float[] POSITIONS = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
    private static final String VERTEX_SOURCE = """
            #version 460 core
            layout(location = 0) in vec3 Position;
            layout(location = 4) in mat4 InstanceTransform;
            uniform mat4 ModelViewMat;
            uniform int UseInstances;
            void main() {
                mat4 model = UseInstances != 0 ? InstanceTransform : ModelViewMat;
                gl_Position = model * vec4(Position, 1.0);
            }
            """;
    private static final String FRAGMENT_SOURCE = """
            #version 460 core
            out vec4 FragColor;
            void main() {
                FragColor = vec4(0.2, 0.65, 0.4, 1.0);
            }
            """;

    private final ShaderProgram shader;
    private final VertexBuffer vanilla;
    private final InstancedMeshBatch batch;
    private final List<Matrix4f> transforms;
    private final int modelViewLocation;
    private final int useInstancesLocation;
    private final GlRenderDevice device = new GlRenderDevice();
    private final CommandBuffer orderedDraws;
    private final CommandBuffer instancedDraw;

    InstancedBatchScene(ShaderProgram shader, VertexBuffer vanilla, InstancedMeshBatch batch, int count) {
        this.shader = shader;
        this.vanilla = vanilla;
        this.batch = batch;
        transforms = transforms(count);
        modelViewLocation = shader.uniformLocation("ModelViewMat");
        useInstancesLocation = shader.uniformLocation("UseInstances");
        instancedDraw = device.createCommandBuffer().drawInstancedBatch(batch, transforms);
        int vao = bakeVanillaQuad(vanilla);
        int indexType = RenderSystem.getSequentialBuffer(MODE).type().asGLType;
        orderedDraws = device.createCommandBuffer().bindVertexArray(vao);
        for (Matrix4f transform : transforms) {
            orderedDraws.setUniformMat4(shader, "ModelViewMat", transform)
                    .drawElements(MODE.asGLMode, MODE.indexCount(4), indexType);
        }
    }

    static ShaderProgram createProgram() {
        return ShaderProgram.fromSources(VERTEX_SOURCE, FRAGMENT_SOURCE);
    }

    static Mesh createMesh() {
        return Mesh.builder()
                .vertices(POSITIONS, 3 * Float.BYTES, VertexAttribute.builder().index(0).size(3).build())
                .indices(new int[]{0, 1, 2, 2, 3, 0})
                .build();
    }

    static InstancedMeshBatch createBatch(Mesh mesh, int count) {
        return InstancedMeshBatch.of(mesh, count, INSTANCE_ATTRIBUTE);
    }

    private static int bakeVanillaQuad(VertexBuffer buffer) {
        try (var bytes = new ByteBufferBuilder(POSITIONS.length * Float.BYTES)) {
            var builder = new BufferBuilder(bytes, MODE, DefaultVertexFormat.POSITION);
            for (int i = 0; i < POSITIONS.length; i += 3) {
                builder.addVertex(POSITIONS[i], POSITIONS[i + 1], POSITIONS[i + 2]);
            }
            buffer.bind();
            buffer.upload(builder.buildOrThrow());
            return glGetInteger(GL_VERTEX_ARRAY_BINDING);
        } finally {
            VertexBuffer.unbind();
        }
    }

    void render(RenderBenchmark.Renderer renderer) {
        boolean instanced = renderer == RenderBenchmark.Renderer.INSTANCED_BATCH;
        shader.use().setInt(useInstancesLocation, instanced ? 1 : 0);
        try {
            if (instanced) {
                device.execute(instancedDraw);
                return;
            }
            if (renderer == RenderBenchmark.Renderer.COMMAND_BATCH) {
                device.invalidateState();
                device.execute(orderedDraws);
                return;
            }
            boolean shared = renderer == RenderBenchmark.Renderer.SHARED_VAO;
            if (shared) {
                vanilla.bind();
            }
            for (Matrix4f transform : transforms) {
                shader.setMat4(modelViewLocation, transform);
                if (!shared) {
                    vanilla.bind();
                }
                vanilla.draw();
            }
        } finally {
            VertexBuffer.unbind();
            shader.stopUsing();
        }
    }

    void verifyDrawCount() {
        var stats = batch.statistics();
        if (stats.drawnInstances() != transforms.size() || stats.drawCalls() != 1 || stats.meshGroups() != 1) {
            throw new IllegalStateException("Unexpected Haikalat instance batch statistics " + stats);
        }
    }

    private static List<Matrix4f> transforms(int count) {
        int columns = (int) Math.ceil(Math.sqrt(count * (double) RenderBenchmark.WIDTH / RenderBenchmark.HEIGHT));
        int rows = (count + columns - 1) / columns;
        var transforms = new ArrayList<Matrix4f>(count);
        for (int instance = 0; instance < count; instance++) {
            float x = (2f * (instance % columns) + 1f) / columns - 1f;
            float y = (2f * (instance / columns) + 1f) / rows - 1f;
            transforms.add(new Matrix4f().translation(x, y, 0).scale(0.8f / columns, 0.8f / rows, 1f));
        }
        return transforms;
    }
}
