package com.haikalat.host.mixin.client;

import com.haikalat.host.client.CloudMeshCulling;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
abstract class CloudRendererMixin {
    @Unique private static final boolean haikalathost$cloudStats = Boolean.getBoolean("haikalathost.cloudStats");
    @Unique private final CloudMeshCulling haikalathost$cloudCulling = new CloudMeshCulling();
    @Unique private boolean haikalathost$reportedCull;

    @WrapOperation(method = "renderClouds", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;upload(Lcom/mojang/blaze3d/vertex/MeshData;)V"))
    private void haikalathost$captureCloudBounds(VertexBuffer buffer, MeshData mesh, Operation<Void> original) {
        haikalathost$cloudCulling.captureBounds(buffer, mesh);
        original.call(buffer, mesh);
    }

    // Ordinal zero binds for upload. The second site has the final cloud model-view matrix.
    @WrapOperation(method = "renderClouds", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;bind()V", ordinal = 1))
    private void haikalathost$bindVisibleClouds(VertexBuffer buffer, Operation<Void> original,
                                               @Local(argsOnly = true) PoseStack pose,
                                               @Local(argsOnly = true, ordinal = 1) Matrix4f projection,
                                               @Share("cloudOutside") LocalBooleanRef outside) {
        boolean culled = haikalathost$cloudCulling.isOutside(buffer, pose.last().pose(), projection);
        outside.set(culled);
        if (culled) {
            BufferUploader.invalidate();
        } else {
            original.call(buffer);
        }
    }

    @WrapOperation(method = "renderClouds", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V"))
    private void haikalathost$drawVisibleClouds(VertexBuffer buffer, Matrix4f view, Matrix4f projection,
                                               ShaderInstance shader, Operation<Void> original,
                                               @Share("cloudOutside") LocalBooleanRef outside) {
        if (!outside.get()) {
            original.call(buffer, view, projection, shader);
            return;
        }
        if (!CloudMeshCulling.canCull(shader)) {
            buffer.bind();
            outside.set(false);
            original.call(buffer, view, projection, shader);
            return;
        }
        if (haikalathost$cloudStats && !haikalathost$reportedCull) {
            haikalathost$reportedCull = true;
            LogUtils.getLogger().info("Haikalat skipped drawing a vanilla cloud mesh outside the view frustum");
        }
        // Keep render state setup, default uniform values and shader cleanup in vanilla order.
        VertexBuffer previous = CloudMeshCulling.suppressDraw(buffer);
        try {
            original.call(buffer, view, projection, shader);
        } finally {
            CloudMeshCulling.suppressDraw(previous);
        }
    }
}
