package com.haikalat.host.mixin.client;

import com.haikalat.host.client.CloudMeshCulling;
import com.haikalat.host.client.TerrainDrawBatch;
import com.haikalat.host.client.TerrainMeshAccess;
import com.haikalat.host.client.TerrainVertexBufferAccess;
import com.haikalat.host.client.TerrainMultiDraw;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.vertex.VertexArray;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(VertexBuffer.class)
abstract class VertexBufferMixin implements TerrainVertexBufferAccess {
    @Shadow @Final private VertexBuffer.Usage usage;
    @Shadow private int vertexBufferId;
    @Shadow private int indexBufferId;
    @Shadow private VertexFormat format;
    @Shadow private VertexFormat.Mode mode;
    @Shadow private VertexFormat.IndexType indexType;
    @Shadow private int indexCount;
    @Shadow private RenderSystem.AutoStorageIndexBuffer sequentialIndices;

    @Unique private GlBuffer haikalathost$vertices;
    @Unique private GlBuffer haikalathost$indices;
    @Unique private VertexArray haikalathost$vertexArray;
    @Unique private boolean haikalathost$trackedTerrain;

    @Override
    public boolean haikalathost$recordTerrain(float x, float y, float z) {
        if (usage != VertexBuffer.Usage.STATIC || format != DefaultVertexFormat.BLOCK
                || mode != VertexFormat.Mode.QUADS || vertexBufferId <= 0 || indexCount <= 0) {
            return false;
        }
        RenderSystem.AutoStorageIndexBuffer sequential = sequentialIndices;
        if (sequential != null && sequential != RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)) {
            return false;
        }
        int elements = sequential == null ? indexBufferId
                : ((AutoStorageIndexBufferAccessor) (Object) sequential).haikalathost$getBufferId();
        return TerrainDrawBatch.recordSection(vertexBufferId, elements, sequential != null, indexCount,
                (sequential == null ? indexType : sequential.type()).asGLType, x, y, z);
    }

    @Override
    public void haikalathost$forgetTerrain() {
        if (haikalathost$trackedTerrain) {
            TerrainMultiDraw.invalidate(vertexBufferId);
            TerrainMultiDraw.invalidateIndices(indexBufferId);
            haikalathost$trackedTerrain = false;
        }
    }

    @WrapWithCondition(method = "_drawWithShader", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ShaderInstance;apply()V"))
    private boolean haikalathost$shouldApplyCloudShader(ShaderInstance shader) {
        return CloudMeshCulling.shouldApplyShader(shader, CloudMeshCulling.isSuppressed((VertexBuffer) (Object) this));
    }

    @WrapWithCondition(method = "_drawWithShader", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V"))
    private boolean haikalathost$shouldDrawMesh(VertexBuffer buffer) {
        return !CloudMeshCulling.isSuppressed(buffer);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I", ordinal = 0))
    private int haikalathost$allocateVertices(VertexBuffer.Usage usage) {
        haikalathost$vertices = GlBuffer.arrayBuffer(haikalathost$usage(usage));
        return haikalathost$vertices.id();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I", ordinal = 1))
    private int haikalathost$allocateIndices(VertexBuffer.Usage usage) {
        haikalathost$indices = GlBuffer.elementArrayBuffer(haikalathost$usage(usage));
        return haikalathost$indices.id();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I"))
    private int haikalathost$allocateLayout() {
        haikalathost$vertexArray = new VertexArray();
        return haikalathost$vertexArray.id();
    }

    @WrapOperation(method = "uploadVertexBuffer", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;glBufferData(ILjava/nio/ByteBuffer;I)V"))
    private void haikalathost$uploadVertices(int target, ByteBuffer vertices, int bufferUsage, Operation<Void> original,
                                            @Local(argsOnly = true) MeshData.DrawState state) {
        TerrainMultiDraw.invalidate(vertexBufferId);
        original.call(target, vertices, bufferUsage);
        if (usage == VertexBuffer.Usage.STATIC && state.format() == DefaultVertexFormat.BLOCK
                && state.mode() == VertexFormat.Mode.QUADS) {
            haikalathost$trackedTerrain = true;
            TerrainMultiDraw.rememberVertices(vertexBufferId, vertices, state.vertexCount(), state.indexCount());
        }
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void haikalathost$publishFaces(MeshData mesh, CallbackInfo ci) {
        var ranges = ((TerrainMeshAccess) mesh).haikalathost$faceRanges();
        if (ranges != null && sequentialIndices != null && vertexBufferId > 0) {
            TerrainMultiDraw.rememberFaces(vertexBufferId, ranges);
        }
    }

    @Redirect(method = {
            "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;)V",
            "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/MeshData$DrawState;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/systems/RenderSystem$AutoStorageIndexBuffer;"
    }, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;glBufferData(ILjava/nio/ByteBuffer;I)V"), require = 2)
    private void haikalathost$uploadIndices(int target, ByteBuffer indices, int usage) {
        TerrainMultiDraw.invalidateIndices(indexBufferId);
        haikalathost$indices.upload(indices);
    }

    @Redirect(method = "close", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;glDeleteBuffers(I)V", ordinal = 0))
    private void haikalathost$releaseVertices(int id) {
        TerrainMultiDraw.invalidate(id);
        haikalathost$vertices.close();
    }

    @Redirect(method = "close", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;glDeleteBuffers(I)V", ordinal = 1))
    private void haikalathost$releaseIndices(int id) {
        TerrainMultiDraw.invalidateIndices(id);
        haikalathost$indices.close();
    }

    @Redirect(method = "close", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;glDeleteVertexArrays(I)V"))
    private void haikalathost$releaseLayout(int id) {
        haikalathost$vertexArray.close();
    }

    @Unique
    private static int haikalathost$usage(VertexBuffer.Usage usage) {
        return usage == VertexBuffer.Usage.STATIC ? GL15.GL_STATIC_DRAW : GL15.GL_DYNAMIC_DRAW;
    }
}
