package com.haikalat.host.client;

import com.haikalat.host.mixin.client.PostPassAccessor;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.presentation.PresentationTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostPass;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.function.IntConsumer;

/** Executes the post effects declared by a Minecraft resource pack as Haikalat passes. */
public final class PostPipeline implements AutoCloseable {
    private static PostQuad quad;

    private final GlRenderDevice device = new GlRenderDevice();
    private final RenderGraph graph = ExternalRenderGraph.create();
    private final IntConsumer setFilter;
    private final String name;
    private float time;
    private PresentationTarget presentationTarget;
    private int filter;
    private boolean reported;
    private PostPass activePass;

    public PostPipeline(List<PostPass> passes, IntConsumer setFilter, String name) {
        this.setFilter = setFilter;
        this.name = name;
        try {
            for (int index = 0; index < passes.size(); index++) {
                PostPass pass = passes.get(index);
                var builder = graph.addPass("minecraft-post-" + index).writeToExternalTarget().noClear();
                if (index > 0) {
                    // A resource pack can reuse the same targets several times in one chain.
                    builder.dependsOn("minecraft-post-" + (index - 1));
                }
                builder.execute((resources, commands) -> {
                    commands.custom(() -> prepare(pass));
                    quad.draw(commands);
                    commands.custom(this::finishPass);
                });
            }
            graph.compile();
            graph.sealTopology();
        } catch (RuntimeException | Error failure) {
            graph.close();
            throw failure;
        }
    }

    public void render(RenderTarget screen, float time) {
        RenderSystem.assertOnRenderThread();
        if (screen.width <= 0 || screen.height <= 0) {
            return;
        }
        if (quad == null) {
            quad = new PostQuad();
        }
        this.time = time;
        filter = GL11.GL_NEAREST;
        if (presentationTarget == null || presentationTarget.width() != screen.width
                || presentationTarget.height() != screen.height || presentationTarget.drawFramebufferId() != screen.frameBufferId) {
            presentationTarget = PresentationTarget.builder(screen.width, screen.height).framebuffer(screen.frameBufferId).build();
        }
        try {
            device.invalidateState();
            graph.execute(device, presentationTarget);
            if (!reported) {
                reported = true;
                LogUtils.getLogger().info("Haikalat submitted Minecraft post chain {}", name);
            }
        } finally {
            finishPass();
            setFilter.accept(GL11.GL_NEAREST);
        }
    }

    private void prepare(PostPass pass) {
        activePass = pass;
        if (filter != pass.getFilterMode()) {
            filter = pass.getFilterMode();
            setFilter.accept(filter);
        }
        RenderTarget input = pass.inTarget;
        RenderTarget output = pass.outTarget;
        var shader = pass.getEffect();
        var inputs = (PostPassAccessor) pass;
        var window = Minecraft.getInstance().getWindow();

        input.unbindWrite();
        shader.setSampler("DiffuseSampler", input::getColorTextureId);
        var auxiliaryNames = inputs.haikalathost$auxNames();
        for (int index = 0; index < auxiliaryNames.size(); index++) {
            shader.setSampler(auxiliaryNames.get(index), inputs.haikalathost$auxAssets().get(index));
            shader.safeGetUniform("AuxSize" + index).set(
                    inputs.haikalathost$auxWidths().get(index).floatValue(),
                    inputs.haikalathost$auxHeights().get(index).floatValue());
        }
        shader.safeGetUniform("ProjMat").set(inputs.haikalathost$projection());
        shader.safeGetUniform("InSize").set((float) input.width, (float) input.height);
        shader.safeGetUniform("OutSize").set((float) output.width, (float) output.height);
        shader.safeGetUniform("ScreenSize").set((float) window.getWidth(), (float) window.getHeight());
        shader.safeGetUniform("Time").set(time);
        shader.apply();
        output.clear(Minecraft.ON_OSX);
        output.bindWrite(false);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        quad.prepare(output.width, output.height);
    }

    private void finishPass() {
        if (activePass == null) {
            return;
        }
        PostPass pass = activePass;
        activePass = null;
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        pass.getEffect().clear();
        pass.outTarget.unbindWrite();
        pass.inTarget.unbindRead();
        // Haikalat bound the post VAO outside BufferUploader's binding cache.
        BufferUploader.invalidate();
    }

    public static void shutdown() {
        if (quad != null) {
            quad.close();
            quad = null;
        }
    }

    @Override
    public void close() {
        graph.close();
    }
}
