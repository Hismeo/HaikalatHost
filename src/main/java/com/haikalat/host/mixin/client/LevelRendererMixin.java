package com.haikalat.host.mixin.client;

import com.haikalat.host.client.TerrainDrawBatch;
import com.haikalat.host.client.TerrainLayerMask;
import com.haikalat.host.client.TerrainMultiDraw;
import com.haikalat.host.client.TerrainSectionAccess;
import com.haikalat.host.client.TerrainVertexBufferAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.CompiledSection;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Shadow @Final private ObjectArrayList<RenderSection> visibleSections;

    @Unique private static final boolean haikalathost$filterLayers = Boolean.parseBoolean(
            System.getProperty("haikalathost.terrainLayerLists", "true"));
    @Unique private final List<ObjectArrayList<RenderSection>> haikalathost$visibleLayers = List.of(
            new ObjectArrayList<>(), new ObjectArrayList<>(), new ObjectArrayList<>(),
            new ObjectArrayList<>(), new ObjectArrayList<>());
    @Unique private boolean haikalathost$visibleLayersReady;
    @Unique private RenderType haikalathost$activeLayer;
    @Unique private RenderSection haikalathost$currentSection;
    @Unique private double haikalathost$cameraX, haikalathost$cameraY, haikalathost$cameraZ;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void haikalathost$beginFrame(CallbackInfo ci) {
        haikalathost$visibleLayersReady = false;
        TerrainMultiDraw.beginFrame();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;compileSections(Lnet/minecraft/client/Camera;)V"))
    private void haikalathost$collectVisibleLayers(CallbackInfo ci) {
        if (!haikalathost$filterLayers) return;
        haikalathost$clearLayerLists();
        // Section uploads are drained by compileSections before creating this frame's lists.
        for (int i = 0; i < visibleSections.size(); i++) {
            RenderSection section = visibleSections.get(i);
            CompiledSection compiled = section.getCompiled();
            if (compiled == CompiledSection.UNCOMPILED || compiled == CompiledSection.EMPTY) continue;
            int mask = ((TerrainLayerMask) compiled).haikalathost$nonemptyLayers();
            while (mask != 0) {
                int layer = Integer.numberOfTrailingZeros(mask);
                haikalathost$visibleLayers.get(layer).add(section);
                mask &= mask - 1;
            }
        }
        haikalathost$visibleLayersReady = true;
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void haikalathost$endFrame(CallbackInfo ci) {
        haikalathost$clearLayerLists();
    }

    @Unique
    private void haikalathost$clearLayerLists() {
        haikalathost$visibleLayersReady = false;
        for (int layer = 0; layer < haikalathost$visibleLayers.size(); layer++) {
            haikalathost$visibleLayers.get(layer).clear();
        }
    }

    @WrapOperation(method = "compileSections", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/SectionPos;of(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/SectionPos;"))
    private SectionPos haikalathost$reuseSectionPosition(BlockPos origin, Operation<SectionPos> original,
                                                        @Local RenderSection section) {
        SectionPos cached = ((TerrainSectionAccess) (Object) section).haikalathost$sectionPos(origin);
        return cached != null ? cached : original.call(origin);
    }

    @Inject(method = "setLevel", at = @At("HEAD"), require = 0)
    private void haikalathost$releaseTerrainGeometry(ClientLevel level, CallbackInfo ci) {
        haikalathost$clearLayerLists();
        TerrainMultiDraw.clearGeometry();
    }

    @WrapMethod(method = "renderSectionLayer")
    private void haikalathost$withLayer(RenderType layer, double x, double y, double z,
                                       Matrix4f view, Matrix4f projection, Operation<Void> original) {
        // Finish any outer submission while its shader is still active before reentry.
        TerrainDrawBatch.end();
        var previousLayer = haikalathost$activeLayer;
        var previousSection = haikalathost$currentSection;
        double previousX = haikalathost$cameraX, previousY = haikalathost$cameraY, previousZ = haikalathost$cameraZ;
        haikalathost$activeLayer = layer;
        haikalathost$currentSection = null;
        haikalathost$cameraX = x;
        haikalathost$cameraY = y;
        haikalathost$cameraZ = z;
        try {
            original.call(layer, x, y, z, view, projection);
        } finally {
            TerrainDrawBatch.discard();
            haikalathost$activeLayer = previousLayer;
            haikalathost$currentSection = previousSection;
            haikalathost$cameraX = previousX;
            haikalathost$cameraY = previousY;
            haikalathost$cameraZ = previousZ;
        }
    }

    @WrapOperation(method = "renderSectionLayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ShaderInstance;apply()V"))
    private void haikalathost$beginTerrain(ShaderInstance shader, Operation<Void> original) {
        original.call(shader);
        TerrainDrawBatch.beginStockLayer(shader, TerrainMultiDraw.layerFor(haikalathost$activeLayer));
    }

    @WrapOperation(method = "renderSectionLayer", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;listIterator(I)Lit/unimi/dsi/fastutil/objects/ObjectListIterator;", remap = false))
    private ObjectListIterator<RenderSection> haikalathost$terrainIterator(ObjectArrayList<RenderSection> sections,
            int start, Operation<ObjectListIterator<RenderSection>> original) {
        int layer = TerrainLayerMask.index(haikalathost$activeLayer);
        if (haikalathost$visibleLayersReady && sections == visibleSections && layer >= 0) {
            var filtered = haikalathost$visibleLayers.get(layer);
            return original.call(filtered, haikalathost$activeLayer == RenderType.translucent() ? filtered.size() : 0);
        }
        return original.call(sections, start);
    }

    @WrapOperation(method = "renderSectionLayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getCompiled()Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$CompiledSection;"))
    private CompiledSection haikalathost$captureSection(RenderSection section, Operation<CompiledSection> original) {
        haikalathost$currentSection = section;
        return original.call(section);
    }

    @WrapOperation(method = "renderSectionLayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$CompiledSection;isEmpty(Lnet/minecraft/client/renderer/RenderType;)Z"))
    private boolean haikalathost$queueVisibleSection(CompiledSection compiled, RenderType layer,
            Operation<Boolean> original) {
        RenderSection section = haikalathost$currentSection;
        boolean empty = original.call(compiled, layer);
        if (empty || !TerrainDrawBatch.useSharedLayout()) {
            return empty;
        }
        BlockPos origin = section.getOrigin();
        if (section.getBuffer(layer) instanceof TerrainVertexBufferAccess access
                && access.haikalathost$recordTerrain((float) (origin.getX() - haikalathost$cameraX),
                (float) (origin.getY() - haikalathost$cameraY), (float) (origin.getZ() - haikalathost$cameraZ))) {
            return true;
        }
        // Preserve the relative order when the vanilla draw still runs.
        TerrainDrawBatch.flushPending();
        return false;
    }

    @WrapOperation(method = "renderSectionLayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ShaderInstance;clear()V"))
    private void haikalathost$submitTerrain(ShaderInstance shader, Operation<Void> original) {
        try {
            TerrainDrawBatch.end();
        } finally {
            original.call(shader);
        }
    }
}
