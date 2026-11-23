package com.haikalat.host.client;

import net.minecraft.util.CubicSampler;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Gaussian biome color sampling without a temporary vector for each accumulated tap. */
public final class CubicColorSampler {
    private static final double[] KERNEL = {0.0, 1.0, 4.0, 6.0, 4.0, 1.0, 0.0};
    private static final int RADIUS = 2;

    private CubicColorSampler() {}

    public static Vec3 sample(Vec3 position, CubicSampler.Vec3Fetcher fetcher) {
        int x = Mth.floor(position.x());
        int y = Mth.floor(position.y());
        int z = Mth.floor(position.z());
        double dx = position.x() - x;
        double dy = position.y() - y;
        double dz = position.z() - z;
        double totalWeight = 0.0;
        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;

        // Keep Minecraft's fetch order and normalization, including zero-weight samples.
        for (int i = 0; i < KERNEL.length - 1; i++) {
            double xWeight = Mth.lerp(dx, KERNEL[i + 1], KERNEL[i]);
            for (int j = 0; j < KERNEL.length - 1; j++) {
                double xyWeight = xWeight * Mth.lerp(dy, KERNEL[j + 1], KERNEL[j]);
                for (int k = 0; k < KERNEL.length - 1; k++) {
                    double weight = xyWeight * Mth.lerp(dz, KERNEL[k + 1], KERNEL[k]);
                    totalWeight += weight;
                    Vec3 color = fetcher.fetch(x - RADIUS + i, y - RADIUS + j, z - RADIUS + k);
                    if (color.getClass() == Vec3.class) {
                        red += color.x * weight;
                        green += color.y * weight;
                        blue += color.z * weight;
                    } else {
                        Vec3 weighted = color.scale(weight);
                        red += weighted.x;
                        green += weighted.y;
                        blue += weighted.z;
                    }
                }
            }
        }
        double normalization = 1.0 / totalWeight;
        return new Vec3(red * normalization, green * normalization, blue * normalization);
    }
}
