package com.haikalat.host.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;

/** Bounds the uploaded cloud mesh in the same coordinate space used by its shader. */
public final class CloudMeshCulling {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("haikalathost.cloudFrustumCull", "true"));
    private static final ResourceLocation CLOUD_VERTEX = ResourceLocation.withDefaultNamespace("shaders/core/rendertype_clouds.vsh");
    private static final ResourceLocation FOG_INCLUDE = ResourceLocation.withDefaultNamespace("shaders/include/fog.glsl");
    private static final float BOUNDS_PADDING = 0.01f;
    private static ShaderInstance checkedShader;
    private static boolean usesVanillaTransform;
    private static VertexBuffer suppressedDraw;

    private final Matrix4f clip = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private VertexBuffer meshOwner;
    private float minX, minY, minZ, maxX, maxY, maxZ;

    public void captureBounds(VertexBuffer target, MeshData mesh) {
        meshOwner = null;
        if (!ENABLED || mesh.drawState().format() != DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL
                || mesh.drawState().mode() != VertexFormat.Mode.QUADS || mesh.drawState().vertexCount() == 0) {
            return;
        }
        VertexFormat format = mesh.drawState().format();
        ByteBuffer data = mesh.vertexBuffer();
        int stride = format.getVertexSize();
        int first = data.position() + format.getOffset(VertexFormatElement.POSITION);
        int count = mesh.drawState().vertexCount();
        if ((long) first + (long) (count - 1) * stride + 3 * Float.BYTES > data.limit()) {
            return;
        }
        float lowX = Float.POSITIVE_INFINITY, lowY = Float.POSITIVE_INFINITY, lowZ = Float.POSITIVE_INFINITY;
        float highX = Float.NEGATIVE_INFINITY, highY = Float.NEGATIVE_INFINITY, highZ = Float.NEGATIVE_INFINITY;
        for (int vertex = 0, offset = first; vertex < count; vertex++, offset += stride) {
            float x = data.getFloat(offset);
            float y = data.getFloat(offset + Float.BYTES);
            float z = data.getFloat(offset + 2 * Float.BYTES);
            lowX = Math.min(lowX, x);
            lowY = Math.min(lowY, y);
            lowZ = Math.min(lowZ, z);
            highX = Math.max(highX, x);
            highY = Math.max(highY, y);
            highZ = Math.max(highZ, z);
        }
        if (!Float.isFinite(lowX) || !Float.isFinite(lowY) || !Float.isFinite(lowZ)
                || !Float.isFinite(highX) || !Float.isFinite(highY) || !Float.isFinite(highZ)) {
            return;
        }
        minX = lowX - BOUNDS_PADDING;
        minY = lowY - BOUNDS_PADDING;
        minZ = lowZ - BOUNDS_PADDING;
        maxX = highX + BOUNDS_PADDING;
        maxY = highY + BOUNDS_PADDING;
        maxZ = highZ + BOUNDS_PADDING;
        meshOwner = target;
    }

    public boolean isOutside(VertexBuffer buffer, Matrix4f view, Matrix4f projection) {
        return ownsMesh(buffer) && canCull(GameRenderer.getRendertypeCloudsShader()) && isOutsideBounds(view, projection);
    }

    // The caller uses the stock cloud shader, which transforms with ModelViewMat and ProjMat.
    boolean isOutsideStockMesh(VertexBuffer buffer, Matrix4f view, Matrix4f projection) {
        return ownsMesh(buffer) && isOutsideBounds(view, projection);
    }

    private boolean ownsMesh(VertexBuffer buffer) {
        return meshOwner == buffer && !buffer.isInvalid();
    }

    private boolean isOutsideBounds(Matrix4f view, Matrix4f projection) {
        clip.set(projection).mul(view);
        return clip.isFinite() && !frustum.set(clip, false).testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static boolean canCull(ShaderInstance shader) {
        if (!ENABLED || shader == null || shader != GameRenderer.getRendertypeCloudsShader()) {
            return false;
        }
        if (shader != checkedShader) {
            checkedShader = shader;
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            usesVanillaTransform = shader.MODEL_VIEW_MATRIX != null && shader.PROJECTION_MATRIX != null
                    && shader.getVertexProgram().getName().equals("rendertype_clouds")
                    && fromVanilla(resources, CLOUD_VERTEX) && fromVanilla(resources, FOG_INCLUDE);
        }
        return usesVanillaTransform;
    }

    private static boolean fromVanilla(ResourceManager resources, ResourceLocation path) {
        return resources.getResource(path).map(resource -> resource.sourcePackId().equals("vanilla")).orElse(false);
    }

    public static VertexBuffer suppressDraw(VertexBuffer buffer) {
        VertexBuffer previous = suppressedDraw;
        suppressedDraw = buffer;
        return previous;
    }

    public static boolean shouldApplyShader(ShaderInstance shader, boolean culled) {
        // Preserve setDefaultUniforms and clear so sampler and program state are restored.
        return !culled || shader.getClass() != ShaderInstance.class;
    }

    public static boolean isSuppressed(VertexBuffer buffer) {
        return suppressedDraw == buffer;
    }
}
