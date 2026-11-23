# Minecraft 1.21.1 渲染基准

在项目根目录执行。当前主机使用 Java 21 和支持 OpenGL 4.6 core 的显卡驱动。

```powershell
$env:JAVA_HOME = 'D:\java\zulu21'
.\gradlew.bat renderBenchmark --offline
```

仅跑地形场景，比较 512 个已烘焙区段的提交开销。

```powershell
.\gradlew.bat renderBenchmark --offline '--args=--scene=terrain --sections=512 --quads-per-side=4 --warmup=120 --frames=200 --rounds=4'
```

`--scene` 可选 `all`、`terrain`、`terrain-cutout`、`terrain-cutout-mipped`、`terrain-translucent`、`terrain-instances`、`terrain-face-ranges`、`clouds-visible`、`clouds-offscreen`、`instanced-batch`。默认跑全部场景。每个场景中，每条路径预热 120 帧，每轮采样 200 帧，共 4 轮，每轮轮换测试顺序。`--sections` 和 `--quads-per-side` 调整地形区段数和每区段单边的四边形数。`--animate-offsets=true` 每帧改变地形偏移，用于测量命令缓存持续失效时的开销。

基准通过 Minecraft 的 `BufferBuilder` 和 `VertexBuffer.upload` 在计时前烘焙并上传确定性网格。各条路径共享网格、棋盘格测试纹理和 640×360 离屏 framebuffer。原版直接执行 `Uniform.upload` 与 `VertexBuffer.bind/draw`，`host` 使用逐区段的共享 VAO 提交，`multi-draw` 使用生产代码的共享地形缓冲和间接绘制。云层场景比较原版与 Host 视锥剔除。单个独立 JVM 和隐藏的 OpenGL 上下文完成所有场景。

正式计时前，测试会逐像素比较 RGBA 与深度，并通过 OpenGL 统计已提交的三角形数量。地形测试还覆盖网格重建、偏移变化、自定义着色器与索引回退，以及间接缓冲绑定的恢复。半透明场景使用原版四边形排序，在同一个索引缓冲上重排并验证像素变化。它还覆盖 16 位和 32 位排序索引与共享顺序索引混用时的绘制顺序，以及单个区段回退时前后间接绘制的顺序。`clouds-offscreen` 要求原版提交网格且输出保持清屏状态，Host 提交零个三角形。

`build/reports/render-benchmark/rounds.csv` 记录最近一次运行的逐轮数据。

- `submit_ms_per_frame` 是烘焙网格绘制循环的墙钟时间，包含驱动排队或等待。
- `completed_ms_per_frame` 是计时批次经 `glFinish` 等待完成后的总时间除以帧数。

计时范围为固定网格的离屏绘制和共享清屏。着色器加载、网格生成、静态几何上传和像素回读均在采样前完成。

`instanced-batch` 使用相同的四边形、着色器和变换，对比逐次绑定并绘制、共用 VAO 逐条绘制、通过一个 Haikalat `CommandBuffer` 执行全部绘制，以及 `InstancedMeshBatch` 合并绘制。`--sections` 控制实例数。实例数据上传计入每轮采样，验证阶段检查 RGBA、深度、三角形数和实例批次的绘制次数。

`terrain-instances` 验证 32 个烘焙网格完全相同的区段通过 `InstancedMeshBatch` 一次绘制，并核对光照、RGBA、深度、三角形数和网格重建后的回退。

实机默认在 Minecraft 1.21.1 的原版 `solid`、`cutout_mipped`、`cutout` 和 `translucent` 图层使用批量绘制。同一不透明批次中的网格数据逐字节相同，且单个网格不超过 32 KiB 时，可合成一次实例绘制。用于比较网格的快照缓存上限为 8 MiB。`-Dhaikalathost.terrainInstances=false` 可停用实例分支。

混合网格使用间接多重绘制。半透明提交按原版区段顺序绘制，并沿用区段内已排序的索引。共享顶点缓存按需增长，默认上限为 256 MiB。可用 JVM 参数 `-Dhaikalathost.terrainVertexBudgetMiB=96` 恢复原上限，支持范围为 16–512 MiB。排序索引缓存上限为 24 MiB。单个区段无法驻留时，按原顺序提交前一段间接批次，用共享 VAO 绘制该区段，再提交后续批次。网格重建、区段重用和卸载会使旧副本失效，超出约两秒未参与批量提交的副本会释放其缓存空间。资源包覆盖地形着色器时，该图层默认走原版提交。`-Dhaikalathost.terrainMultiDraw=false` 可切回共享 VAO 路径。

`-Dhaikalathost.terrainFaceCulling=true` 开启可选的背面索引范围剔除。编译时记录不透明层中轴对齐、绕序一致的四边形范围，绘制时根据实际着色器矩阵确定相机位置。每个区段最多保留四段索引范围，按原索引顺序提交。`terrain-face-ranges` 验证原版和剔除后的三角形数、RGBA、深度、双面绘制，以及缓存分配、碎片和可见性回收。

区段编译完成后缓存非空图层位掩码。每帧从原版可见区段生成同序的图层列表，半透明层从列表末端遍历。`-Dhaikalathost.terrainLayerLists=false` 可切回原版可见列表。

`-Dhaikalathost.terrainStats=true` 在客户端日志中每秒输出 `TERRAIN_STATS`。依次记录提交批次、区段总数、回退区段、含回退的批次、全回退的批次、间接多重绘制调用次数、顶点容量回退、顶点碎片回退、索引容量回退、索引碎片回退、小批次回退区段、数据不匹配的回退区段、回收顶点副本、回收索引副本、实例化区段和跳过的四边形。回退原因按实际绘制区段计数，包含复用缓存提交计划的帧。
