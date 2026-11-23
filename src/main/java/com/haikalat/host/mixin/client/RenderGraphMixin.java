package com.haikalat.host.mixin.client;

import com.haikalat.host.client.ExternalRenderGraph;
import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = RenderGraph.class, remap = false)
abstract class RenderGraphMixin implements ExternalRenderGraph {
    @Unique private static final boolean haikalathost$bridgeGpuTiming = Boolean.getBoolean("haikalathost.graphGpuTiming");
    @Unique private boolean haikalathost$bridgePasses;

    @Override
    public void haikalathost$configureBridgePasses() {
        haikalathost$bridgePasses = true;
    }

    @WrapOperation(method = "executeFrame", at = @At(value = "INVOKE",
            target = "Lcom/kaleblangley/haikalat/core/command/CommandBuffer;enableScissor(Z)Lcom/kaleblangley/haikalat/core/command/CommandBuffer;"))
    private CommandBuffer haikalathost$applyScissorDefault(CommandBuffer commands, boolean enabled,
                                                          Operation<CommandBuffer> original) {
        if (haikalathost$bridgePasses) {
            return commands;
        }
        return original.call(commands, enabled);
    }

    // In Haikalat 0.28.2 these two field reads only control timer allocation inside executeFrame.
    @WrapOperation(method = "executeFrame", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lcom/kaleblangley/haikalat/core/graph/RenderGraph;allocateResources:Z"), require = 2, allow = 2)
    private boolean haikalathost$allocatePassTimers(RenderGraph graph, Operation<Boolean> original) {
        return (!haikalathost$bridgePasses || haikalathost$bridgeGpuTiming) && original.call(graph);
    }
}
