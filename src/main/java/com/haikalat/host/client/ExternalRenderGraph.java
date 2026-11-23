package com.haikalat.host.client;

import com.kaleblangley.haikalat.core.graph.RenderGraph;

/** Bridge passes render into targets whose state is owned by Minecraft. */
public interface ExternalRenderGraph {
    static RenderGraph create() {
        RenderGraph graph = new RenderGraph(1, 1);
        ((ExternalRenderGraph) (Object) graph).haikalathost$configureBridgePasses();
        return graph;
    }

    void haikalathost$configureBridgePasses();
}
