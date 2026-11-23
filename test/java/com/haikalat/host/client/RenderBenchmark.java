package com.haikalat.host.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.ObjIntConsumer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING;
import static org.lwjgl.opengl.GL46C.GL_PRIMITIVES_SUBMITTED;

/** Runs one JVM and one hidden GLFW window for every scene and comparison. */
public final class RenderBenchmark {
    static final int WIDTH = 640;
    static final int HEIGHT = 360;

    private RenderBenchmark() {}

    public static void main(String[] args) throws IOException {
        Settings settings = Settings.parse(args);
        CubicColorSamplerCheck.verify();
        long started = System.nanoTime();
        List<Measurement> measurements = new ArrayList<>();
        try (GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err).set()) {
            try {
                if (!GLFW.glfwInit()) {
                    throw new IllegalStateException("GLFW init failed");
                }
                GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
                long window = GLFW.glfwCreateWindow(1, 1, "HaikalatHost benchmark", 0, 0);
                if (window == 0) {
                    throw new IllegalStateException("An OpenGL 4.6 core context is required by Haikalat");
                }
                try {
                    GLFW.glfwMakeContextCurrent(window);
                    GL.createCapabilities();
                    RenderSystem.initRenderThread();
                    System.out.printf("GPU %s | OpenGL %s | %dx%d offscreen%n",
                            glGetString(GL_RENDERER), glGetString(GL_VERSION), WIDTH, HEIGHT);
                    var packLocation = new PackLocationInfo("vanilla", Component.literal("Minecraft shader resources"),
                            PackSource.BUILT_IN, Optional.empty());
                    try (var pack = new VanillaPackResourcesBuilder().pushJarResources().exposeNamespace("minecraft").build(packLocation);
                         var target = new BenchmarkTarget(WIDTH, HEIGHT)) {
                        for (String name : Settings.SCENES) {
                            if (settings.scene.equals("all") || settings.scene.equals(name)) {
                                if (name.equals("instanced-batch")) {
                                    runInstancedScene(settings, target, measurements);
                                } else if (name.equals("terrain-instances")) {
                                    verifyTerrainInstancing(pack.asProvider(), target);
                                } else if (name.equals("terrain-face-ranges")) {
                                    verifyTerrainFaceRanges(pack.asProvider(), target);
                                } else {
                                    runScene(name, settings, pack.asProvider(), target, measurements);
                                }
                            }
                        }
                    } finally {
                        TerrainDrawBatch.close();
                        GL.setCapabilities(null);
                    }
                } finally {
                    GLFW.glfwDestroyWindow(window);
                }
            } finally {
                GLFW.glfwTerminate();
                GLFW.glfwSetErrorCallback(null);
            }
        }
        Path report = Path.of(System.getProperty("renderBenchmark.report", "build/reports/render-benchmark/rounds.csv"))
                .toAbsolutePath().normalize();
        writeReport(report, measurements);
        System.out.printf(Locale.ROOT, "Benchmark JVM elapsed %.2f s%n", (System.nanoTime() - started) * 1e-9);
        System.out.println(report);
    }

    private static void runScene(String name, Settings settings, ResourceProvider pack,
                                 BenchmarkTarget target, List<Measurement> results) throws IOException {
        List<Renderer> renderers = isTerrain(name) ? List.of(Renderer.VANILLA, Renderer.HOST, Renderer.MULTI_DRAW)
                : List.of(Renderer.VANILLA, Renderer.HOST);
        long bakeStart = System.nanoTime();
        try (BakedRenderScene scene = BakedRenderScene.create(name, pack, settings.sections, settings.quadsPerSide)) {
            glFinish();
            double bakeMillis = (System.nanoTime() - bakeStart) * 1e-6;
            verifyScene(name, scene, target);
            if (isTerrain(name)) {
                verifyTerrainRebuild(scene, target, settings.quadsPerSide);
                if (name.equals("terrain-translucent")) {
                    verifyTranslucentResort(scene, target);
                }
            }
            System.out.printf(Locale.ROOT, "  %d meshes | %d quads | bake %.2f ms | RGBA/depth match%n",
                    scene.meshes(), scene.quads(), bakeMillis);
            measureScene(name, scene.meshes(), scene.quads(), settings, renderers,
                    (renderer, frame) -> renderSample(name, scene, target, renderer, settings.animateOffsets, frame), results);
        }
        if (isTerrain(name)) {
            verifyShaderFallback(pack, name.equals("terrain-translucent") ? "rendertype_translucent" : "rendertype_solid");
        }
        if (name.equals("terrain-translucent")) {
            TerrainMultiDraw.clearGeometry();
            try (var mixed = BakedRenderScene.createMixedTranslucent(pack)) {
                verifyScene("terrain-translucent (sorted short, sorted int, sequential)", mixed, target);
                verifySectionFallback(mixed, target);
            }
            // Keep the VAO alive while the larger scene grows its arena more than once.
            try (var grown = BakedRenderScene.create(name, pack, 96, 26)) {
                verifyScene("terrain-translucent (arena growth)", grown, target);
            }
        }
    }

    private static void runInstancedScene(Settings settings, BenchmarkTarget target, List<Measurement> results) {
        String name = "instanced-batch";
        List<Renderer> renderers = List.of(Renderer.VANILLA, Renderer.SHARED_VAO, Renderer.COMMAND_BATCH, Renderer.INSTANCED_BATCH);
        int count = settings.sections;
        try (var shader = InstancedBatchScene.createProgram(); var vanilla = new VertexBuffer(VertexBuffer.Usage.STATIC);
             var mesh = InstancedBatchScene.createMesh(); var batch = InstancedBatchScene.createBatch(mesh, count)) {
            var scene = new InstancedBatchScene(shader, vanilla, batch, count);
            ObjIntConsumer<Renderer> sample = (renderer, frame) -> {
                target.beginFrame();
                scene.render(renderer);
            };
            target.beginFrame();
            var clear = target.read();
            BenchmarkTarget.Pixels expected = null;
            for (Renderer renderer : renderers) {
                if (countPrimitives(() -> sample.accept(renderer, 0)) != 2 * count) {
                    throw new IllegalStateException(renderer.label + " drew an unexpected number of triangles");
                }
                var actual = target.read();
                if (expected == null) {
                    if (clear.rgba().mismatch(actual.rgba()) == -1 || clear.depth().mismatch(actual.depth()) == -1) {
                        throw new IllegalStateException("The instancing fixture did not change the color and depth targets");
                    }
                    expected = actual;
                } else {
                    verifyPixels(renderer.label, expected, actual);
                }
            }
            scene.verifyDrawCount();
            checkGl();
            System.out.printf("%n%s | %d identical quads | %d triangles | %d vanilla draws, 1 instanced draw | RGBA/depth match%n",
                    name, count, 2 * count, count);
            measureScene(name, count, count, settings, renderers, sample, results);
        }
    }

    private static void verifySectionFallback(BakedRenderScene scene, BenchmarkTarget target) {
        TerrainMultiDraw.clearGeometry();
        scene.padVertexBuffer(1);
        int vanilla = countPrimitives(scene, target, Renderer.VANILLA);
        var expected = target.read();
        int actual = countPrimitives(scene, target, Renderer.MULTI_DRAW);
        verifyPixels("ordered translucent fallback", expected, target.read());
        if (vanilla != actual || TerrainMultiDraw.residentMeshCount() != scene.meshes() - 1
                || TerrainMultiDraw.residentIndexCount() != scene.sortedMeshes() - 1) {
            throw new IllegalStateException("Expected one fallback between two retained runs of translucent draws");
        }
        checkGl();
        System.out.printf("  ordered translucent fallback | 1 of %d sections | %d triangles | RGBA/depth match%n",
                scene.meshes(), actual);
        TerrainMultiDraw.clearGeometry();
    }

    private static void verifyTerrainFaceRanges(ResourceProvider pack, BenchmarkTarget target) throws IOException {
        verifyArenaAllocation();
        try (var scene = BakedRenderScene.create("terrain-face-ranges", pack, 16, 1)) {
            RenderSystem.enableCull();
            int vanilla = countPrimitives(scene, target, Renderer.VANILLA);
            var expected = target.read();
            int actual = countPrimitives(scene, target, Renderer.MULTI_DRAW);
            verifyPixels("opaque face ranges", expected, target.read());
            if (vanilla != 96 || actual != 32) {
                throw new IllegalStateException("Unexpected face-culling primitive counts " + vanilla + " / " + actual);
            }
            RenderSystem.disableCull();
            int twoSided = countPrimitives(scene, target, Renderer.VANILLA);
            expected = target.read();
            if (countPrimitives(scene, target, Renderer.MULTI_DRAW) != twoSided || twoSided != vanilla) {
                throw new IllegalStateException("Face ranges changed a two-sided pass");
            }
            verifyPixels("two-sided face ranges", expected, target.read());
            checkGl();
            System.out.printf("%nterrain-face-ranges | %d -> %d triangles | RGBA/depth match | two-sided fallback verified%n",
                    vanilla, actual);
        } finally {
            TerrainMultiDraw.clearGeometry();
        }
    }

    private static void verifyArenaAllocation() {
        var arena = new TerrainBufferArena(Integer.BYTES, 4, 16);
        int[] sources = new int[6];
        org.lwjgl.opengl.GL45C.glCreateBuffers(sources);
        try {
            for (int i = 0; i < sources.length; i++) {
                org.lwjgl.opengl.GL45C.glNamedBufferData(sources[i], (i == 5 ? 6 : 4) * Integer.BYTES, GL_STATIC_DRAW);
            }
            for (int i = 0; i < 4; i++) {
                if (arena.copy(sources[i], 4, 1) != i * 4) throw new IllegalStateException("Arena slice moved");
            }
            if (arena.copy(sources[4], 4, 1) != TerrainBufferArena.CAPACITY) {
                throw new IllegalStateException("Arena capacity was not reported");
            }
            arena.invalidate(sources[1]);
            arena.invalidate(sources[3]);
            if (arena.copy(sources[5], 6, 1) != TerrainBufferArena.FRAGMENTATION
                    || arena.copy(sources[4], 4, 2) != 4) {
                throw new IllegalStateException("Arena fragmentation or hole reuse was misreported");
            }
            arena.touch(sources[0], 2);
            if (arena.evictBefore(2) != 1 || arena.copy(sources[5], 6, 2) != 8 || arena.size() != 3) {
                throw new IllegalStateException("Arena failed to reclaim just the expired slice");
            }
            checkGl();
            System.out.println("  arena capacity, fragmentation, reuse and visibility eviction verified");
        } finally {
            arena.clear();
            glDeleteBuffers(sources);
        }
    }

    private static void verifyTerrainInstancing(ResourceProvider pack, BenchmarkTarget target) throws IOException {
        int sections = 32;
        int quadsPerSide = 4;
        try (BakedRenderScene scene = BakedRenderScene.create("terrain-instances", pack, sections, quadsPerSide)) {
            var layer = TerrainMultiDraw.Layer.SOLID;
            int vanilla = countPrimitives(scene, target, Renderer.VANILLA);
            var expected = target.read();
            int submitted = countPrimitives(scene, target, Renderer.MULTI_DRAW);
            verifyPixels("repeated opaque terrain", expected, target.read());
            var statistics = layer.instanceStatistics();
            if (vanilla != scene.quads() * 2 || submitted != vanilla
                    || statistics.drawCalls() != 1 || statistics.drawnInstances() != sections) {
                throw new IllegalStateException("Expected one terrain instance draw: " + statistics);
            }
            scene.setTerrainShift(0.375f);
            render(scene, target, Renderer.VANILLA);
            var moved = target.read();
            if (expected.rgba().mismatch(moved.rgba()) == -1) {
                throw new IllegalStateException("The moving instance fixture did not change its pixels");
            }
            render(scene, target, Renderer.MULTI_DRAW);
            verifyPixels("moved repeat terrain", moved, target.read());
            if (layer.instanceStatistics().drawnInstances() != sections) {
                throw new IllegalStateException("Expected an instanced draw after changing the camera offset");
            }
            scene.setTerrainShift(0f);
            verifyTerrainRebuild(scene, target, quadsPerSide);
            if (layer.instanceStatistics().drawCalls() != 0) {
                throw new IllegalStateException("Used stale instance data after rebuilding a mesh");
            }
            checkGl();
            System.out.printf("%nterrain-instances | %d sections in one instance draw | %d triangles | RGBA/depth match%n",
                    sections, submitted);
        } finally {
            TerrainMultiDraw.clearGeometry();
        }
    }

    private static void verifyScene(String name, BakedRenderScene scene, BenchmarkTarget target) {
        target.beginFrame();
        BenchmarkTarget.Pixels clear = target.read();
        int vanillaPrimitives = countPrimitives(scene, target, Renderer.VANILLA);
        BenchmarkTarget.Pixels vanilla = target.read();
        int hostPrimitives = countPrimitives(scene, target, Renderer.HOST);
        BenchmarkTarget.Pixels host = target.read();
        checkGl();
        verifyPixels(name, vanilla, host);
        boolean offscreen = name.equals("clouds-offscreen");
        if ((clear.rgba().mismatch(vanilla.rgba()) == -1) != offscreen
                || (clear.depth().mismatch(vanilla.depth()) == -1) != offscreen) {
            throw new IllegalStateException(name + " did not produce the expected visible coverage");
        }
        int expectedPrimitives = scene.quads() * (isTerrain(name) ? 2 : 4);
        if (vanillaPrimitives != expectedPrimitives || hostPrimitives != (offscreen ? 0 : expectedPrimitives)) {
            throw new IllegalStateException(name + " submitted unexpected primitive counts (vanilla "
                    + vanillaPrimitives + ", host " + hostPrimitives + ")");
        }
        System.out.printf("%n%s | submitted triangles vanilla %d | host %d%n", name, vanillaPrimitives, hostPrimitives);
        if (isTerrain(name)) {
            int batched = countPrimitives(scene, target, Renderer.MULTI_DRAW);
            verifyPixels("terrain multi-draw", vanilla, target.read());
            checkGl();
            boolean fitsArena = (long) scene.quads() * 4 <= TerrainMultiDraw.MAX_VERTICES;
            int residents = TerrainMultiDraw.residentMeshCount();
            boolean meshCountValid = scene.meshes() < 16 ? residents == 0
                    : fitsArena ? residents == scene.meshes() : residents > 0 && residents < scene.meshes();
            int residentIndices = TerrainMultiDraw.residentIndexCount();
            if (batched != expectedPrimitives || !meshCountValid || residentIndices > scene.sortedMeshes()
                    || (fitsArena && residents > 0 && residentIndices != scene.sortedMeshes())) {
                throw new IllegalStateException("Unexpected resident buffer or triangle count after terrain submission");
            }
            System.out.printf("  multi-draw | triangles %d | resident sections %d | resident sorted indices %d%n",
                    batched, TerrainMultiDraw.residentMeshCount(), TerrainMultiDraw.residentIndexCount());
        }
        if (offscreen) {
            verifyCloudReentry(scene, target, expectedPrimitives);
            System.out.println("  cloud reentry RGBA/depth match");
        }
    }

    private static void verifyShaderFallback(ResourceProvider pack, String shaderName) throws IOException {
        var path = ResourceLocation.withDefaultNamespace("shaders/core/" + shaderName + ".vsh");
        try (var input = pack.open(path)) {
            byte[] stock = input.readAllBytes();
            byte[] edited = Arrays.copyOf(stock, stock.length + 1);
            edited[edited.length - 1] = '\n';
            try (var customPack = TerrainMultiDraw.patchStockTerrain(shaderName, "example-pack", new ByteArrayInputStream(stock), pack);
                 var changedSource = TerrainMultiDraw.patchStockTerrain(shaderName, "vanilla", new ByteArrayInputStream(edited), pack)) {
                if (!Arrays.equals(customPack.readAllBytes(), stock) || !Arrays.equals(changedSource.readAllBytes(), edited)) {
                    throw new IllegalStateException("A custom terrain shader was patched");
                }
            }
        }
        var location = new PackLocationInfo("benchmark-resource-pack", Component.literal("Custom shader resources"),
                PackSource.DEFAULT, Optional.empty());
        try (var customPack = new VanillaPackResourcesBuilder().build(location)) {
            for (String overridden : List.of("shaders/core/" + shaderName + ".json", "shaders/core/" + shaderName + ".fsh",
                    "shaders/include/light.glsl", "shaders/include/fog.glsl")) {
                var overriddenId = ResourceLocation.withDefaultNamespace(overridden);
                ResourceProvider provider = id -> pack.getResource(id).map(resource ->
                        id.equals(overriddenId) ? new Resource(customPack, resource::open) : resource);
                try (var shader = BakedRenderScene.loadMultiDrawShader(provider, DefaultVertexFormat.BLOCK, shaderName)) {
                    if (TerrainMultiDraw.shader(shader) != null
                            || org.lwjgl.opengl.GL20C.glGetUniformLocation(shader.getId(), "HaikalatTerrainBatch") >= 0) {
                        throw new IllegalStateException("Terrain enabled multi-draw with an overridden shader resource " + overridden);
                    }
                }
            }
        }
    }

    private static void verifyTerrainRebuild(BakedRenderScene scene, BenchmarkTarget target, int quadsPerSide) {
        render(scene, target, Renderer.VANILLA);
        BenchmarkTarget.Pixels before = target.read();
        scene.rebuildFirstTerrainMesh(quadsPerSide);
        render(scene, target, Renderer.VANILLA);
        BenchmarkTarget.Pixels rebuilt = target.read();
        if (before.rgba().mismatch(rebuilt.rgba()) == -1) {
            throw new IllegalStateException("Rebuilt fixture did not change terrain pixels");
        }
        int indirect = glGenBuffers();
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect);
        try {
            render(scene, target, Renderer.MULTI_DRAW);
            verifyPixels("rebuilt terrain", rebuilt, target.read());
            render(scene, target, Renderer.MULTI_DRAW);
            verifyPixels("cached terrain", rebuilt, target.read());
            if (scene.sortedMeshes() == 0) {
                scene.setCustomIndexDraw(true);
                render(scene, target, Renderer.MULTI_DRAW);
                verifyPixels("custom-index terrain fallback", rebuilt, target.read());
                scene.setCustomIndexDraw(false);
            }
            scene.setTerrainShift(0.375f);
            render(scene, target, Renderer.VANILLA);
            BenchmarkTarget.Pixels moved = target.read();
            if (rebuilt.rgba().mismatch(moved.rgba()) == -1) {
                throw new IllegalStateException("Camera-relative fixture offsets did not change terrain pixels");
            }
            render(scene, target, Renderer.MULTI_DRAW);
            verifyPixels("moving terrain", moved, target.read());
            if (glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING) != indirect) {
                throw new IllegalStateException("Terrain changed another renderer's indirect buffer binding");
            }
            checkGl();
        } finally {
            scene.setCustomIndexDraw(false);
            scene.setTerrainShift(0f);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
            glDeleteBuffers(indirect);
        }
        System.out.println("  mesh rebuild, changed offsets and GL state verified");
    }

    private static void verifyTranslucentResort(BakedRenderScene scene, BenchmarkTarget target) {
        render(scene, target, Renderer.MULTI_DRAW);
        BenchmarkTarget.Pixels before = target.read();
        int residents = TerrainMultiDraw.residentIndexCount();
        scene.resortFirstTerrainMesh();
        render(scene, target, Renderer.VANILLA);
        BenchmarkTarget.Pixels sorted = target.read();
        if (before.rgba().mismatch(sorted.rgba()) == -1) {
            throw new IllegalStateException("Translucent fixture did not change pixels after sorting");
        }
        int primitives = countPrimitives(scene, target, Renderer.MULTI_DRAW);
        verifyPixels("translucent sort-only upload", sorted, target.read());
        render(scene, target, Renderer.MULTI_DRAW);
        verifyPixels("cached translucent sort-only upload", sorted, target.read());
        if (primitives != scene.quads() * 2 || TerrainMultiDraw.residentIndexCount() != residents) {
            throw new IllegalStateException("Translucent sort-only upload changed the draw count or residency");
        }
        checkGl();
        System.out.println("  sort-only upload to the same EBO changed RGBA and matched vanilla");
    }

    private static void verifyCloudReentry(BakedRenderScene scene, BenchmarkTarget target, int expectedPrimitives) {
        scene.setCloudOffscreen(false);
        try {
            // Use the same shader and buffer immediately after a culled Host pass.
            int hostPrimitives = countPrimitives(scene, target, Renderer.HOST);
            BenchmarkTarget.Pixels host = target.read();
            int vanillaPrimitives = countPrimitives(scene, target, Renderer.VANILLA);
            checkGl();
            verifyPixels("cloud reentry", target.read(), host);
            if (hostPrimitives != expectedPrimitives || vanillaPrimitives != expectedPrimitives) {
                throw new IllegalStateException("Cloud reentry submitted unexpected primitive counts");
            }
        } finally {
            scene.setCloudOffscreen(true);
        }
    }

    private static void verifyPixels(String name, BenchmarkTarget.Pixels vanilla, BenchmarkTarget.Pixels host) {
        if (vanilla.rgba().mismatch(host.rgba()) != -1 || vanilla.depth().mismatch(host.depth()) != -1) {
            throw new IllegalStateException(name + " produced different color or depth pixels");
        }
    }

    private static int countPrimitives(BakedRenderScene scene, BenchmarkTarget target, Renderer renderer) {
        return countPrimitives(() -> render(scene, target, renderer));
    }

    private static int countPrimitives(Runnable draw) {
        int query = glGenQueries();
        try {
            glBeginQuery(GL_PRIMITIVES_SUBMITTED, query);
            try {
                draw.run();
            } finally {
                glEndQuery(GL_PRIMITIVES_SUBMITTED);
            }
            return glGetQueryObjectui(query, GL_QUERY_RESULT);
        } finally {
            glDeleteQueries(query);
        }
    }

    private static void measureScene(String name, int meshes, int quads, Settings settings, List<Renderer> renderers,
                                     ObjIntConsumer<Renderer> sample, List<Measurement> results) {
        for (int frame = 0; frame < settings.warmup; frame++) {
            for (Renderer renderer : renderers) {
                sample.accept(renderer, frame);
            }
        }
        List<Measurement> sceneResults = new ArrayList<>();
        for (int round = 1; round <= settings.rounds; round++) {
            for (int order = 0; order < renderers.size(); order++) {
                Renderer renderer = renderers.get((round - 1 + order) % renderers.size());
                sceneResults.add(measure(name, meshes, quads, settings.frames, sample, round, order + 1, renderer));
            }
        }
        results.addAll(sceneResults);
        for (Renderer renderer : renderers) {
            double mean = sceneResults.stream().filter(result -> result.renderer == renderer)
                    .mapToDouble(Measurement::completedMillis).average().orElseThrow();
            System.out.printf(Locale.ROOT, "  mean completed frame %-15s %.3f ms%n", renderer.label, mean);
        }
    }

    private static Measurement measure(String name, int meshes, int quads, int frames,
                                       ObjIntConsumer<Renderer> sample, int round, int order, Renderer renderer) {
        glFinish();
        long started = System.nanoTime();
        for (int frame = 0; frame < frames; frame++) {
            sample.accept(renderer, frame);
        }
        long submitted = System.nanoTime();
        glFinish();
        long completed = System.nanoTime();
        checkGl();
        double submit = (submitted - started) * 1e-6 / frames;
        double finish = (completed - started) * 1e-6 / frames;
        System.out.printf(Locale.ROOT, "  %d.%d %-15s submit %.3f ms | completed %.3f ms%n", round, order,
                renderer.label, submit, finish);
        return new Measurement(name, round, order, renderer, meshes, quads, frames, submit, finish);
    }

    private static boolean isTerrain(String scene) {
        return scene.startsWith("terrain");
    }

    private static void renderSample(String name, BakedRenderScene scene, BenchmarkTarget target, Renderer renderer,
                                     boolean animateOffsets, int frame) {
        if (animateOffsets && isTerrain(name)) {
            scene.setTerrainShift((frame % 64) * 0.0625f);
        }
        render(scene, target, renderer);
    }

    private static void render(BakedRenderScene scene, BenchmarkTarget target, Renderer renderer) {
        target.beginFrame();
        scene.render(renderer);
    }

    private static void checkGl() {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new IllegalStateException("OpenGL error 0x" + Integer.toHexString(error));
        }
    }


    private static void writeReport(Path path, List<Measurement> results) throws IOException {
        Files.createDirectories(path.getParent());
        try (BufferedWriter output = Files.newBufferedWriter(path)) {
            output.write("scene,round,order,renderer,meshes,quads,frames,submit_ms_per_frame,completed_ms_per_frame\n");
            for (Measurement result : results) {
                output.write(String.format(Locale.ROOT, "%s,%d,%d,%s,%d,%d,%d,%.6f,%.6f%n", result.scene, result.round,
                        result.order, result.renderer.label, result.meshes, result.quads, result.frames,
                        result.submitMillis, result.completedMillis));
            }
        }
    }

    enum Renderer {
        VANILLA("vanilla"), HOST("host"), MULTI_DRAW("multi-draw"),
        SHARED_VAO("shared-vao"), COMMAND_BATCH("command-batch"), INSTANCED_BATCH("instanced-batch");

        final String label;

        Renderer(String label) {
            this.label = label;
        }
    }

    private record Measurement(String scene, int round, int order, Renderer renderer, int meshes, int quads, int frames,
                               double submitMillis, double completedMillis) {}

    private record Settings(String scene, int sections, int quadsPerSide, int warmup, int frames, int rounds, boolean animateOffsets) {
        private static final List<String> SCENES = List.of("terrain", "terrain-cutout", "terrain-cutout-mipped", "terrain-translucent",
                "terrain-instances", "terrain-face-ranges", "clouds-visible", "clouds-offscreen", "instanced-batch");

        static Settings parse(String[] args) {
            String scene = "all";
            int sections = 512;
            int quadsPerSide = 4;
            int warmup = 120;
            int frames = 200;
            int rounds = 4;
            boolean animateOffsets = false;
            for (String arg : args) {
                String[] pair = arg.split("=", 2);
                if (pair.length != 2) {
                    throw new IllegalArgumentException("Expected --option=value, got " + arg);
                }
                switch (pair[0]) {
                    case "--scene" -> scene = pair[1];
                    case "--sections" -> sections = numeric(pair, 1, 4096);
                    case "--quads-per-side" -> quadsPerSide = numeric(pair, 1, 32);
                    case "--warmup" -> warmup = numeric(pair, 0, 100_000);
                    case "--frames" -> frames = numeric(pair, 1, 100_000);
                    case "--rounds" -> rounds = numeric(pair, 1, 100);
                    case "--animate-offsets" -> {
                        if (!pair[1].equals("true") && !pair[1].equals("false")) {
                            throw new IllegalArgumentException("Expected --animate-offsets=true or false");
                        }
                        animateOffsets = Boolean.parseBoolean(pair[1]);
                    }
                    default -> throw new IllegalArgumentException("Unknown option " + pair[0]);
                }
            }
            if (!scene.equals("all") && !SCENES.contains(scene)) {
                throw new IllegalArgumentException("Unknown scene " + scene + " in " + Arrays.toString(args));
            }
            int quadsPerCell = scene.equals("all") || scene.equals("terrain-translucent") ? 2 : 1;
            if ((long) sections * quadsPerSide * quadsPerSide * quadsPerCell > 1_000_000) {
                throw new IllegalArgumentException("Terrain fixture exceeds 1,000,000 quads");
            }
            return new Settings(scene, sections, quadsPerSide, warmup, frames, rounds, animateOffsets);
        }

        private static int numeric(String[] option, int min, int max) {
            int value = Integer.parseInt(option[1]);
            if (value < min || value > max) {
                throw new IllegalArgumentException(option[0] + " must be in " + min + ".." + max);
            }
            return value;
        }
    }
}
