package com.haikalat.host.client;

import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;

/** Executes the game scene and its UI in the render graph before the host's presentation step. */
public final class MinecraftFramePipeline implements AutoCloseable {
    private final GlRenderDevice device = new GlRenderDevice();
    private final RenderGraph graph = ExternalRenderGraph.create();
    private GameRenderer gameRenderer;
    private DeltaTracker deltaTracker;
    private boolean renderLevel;
    private PresentationTarget presentationTarget;
    private boolean reported;

    public MinecraftFramePipeline() {
        graph.addPass("minecraft-game-frame").writeToExternalTarget().noClear().execute((resources, commands) ->
                commands.custom(this::drawFrame));
        graph.compile();
        graph.sealTopology();
    }

    public void render(RenderTarget target, GameRenderer renderer, DeltaTracker delta, boolean world) {
        RenderSystem.assertOnRenderThread();
        if (target.width <= 0 || target.height <= 0) {
            return;
        }
        if (graph.isExecuting()) {
            renderer.render(delta, world);
            return;
        }
        gameRenderer = renderer;
        deltaTracker = delta;
        renderLevel = world;
        if (presentationTarget == null || presentationTarget.width() != target.width
                || presentationTarget.height() != target.height || presentationTarget.drawFramebufferId() != target.frameBufferId) {
            presentationTarget = PresentationTarget.builder(target.width, target.height).framebuffer(target.frameBufferId).build();
        }
        try {
            device.invalidateState();
            graph.execute(device, presentationTarget);
            if (!reported) {
                reported = true;
                LogUtils.getLogger().info("Haikalat executed minecraft-game-frame");
            }
        } finally {
            gameRenderer = null;
            deltaTracker = null;
        }
    }

    private void drawFrame() {
        gameRenderer.render(deltaTracker, renderLevel);
    }

    @Override
    public void close() {
        graph.close();
    }
}
