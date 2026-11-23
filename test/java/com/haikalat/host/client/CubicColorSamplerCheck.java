package com.haikalat.host.client;

import net.minecraft.core.BlockPos;
import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

final class CubicColorSamplerCheck {
    private CubicColorSamplerCheck() {}

    static void verify() {
        verifyAt(new Vec3(-2.75, 6.5, 14.125), false);
        verifyAt(new Vec3(8.0, -2.0, 0.0), true);
    }

    private static void verifyAt(Vec3 position, boolean customScale) {
        List<BlockPos> expectedTaps = new ArrayList<>();
        List<BlockPos> actualTaps = new ArrayList<>();
        Vec3 expected = CubicSampler.gaussianSampleVec3(position, fetcher(expectedTaps, customScale));
        Vec3 actual = CubicColorSampler.sample(position, fetcher(actualTaps, customScale));
        if (!expected.equals(actual) || !expectedTaps.equals(actualTaps)) {
            throw new AssertionError("Color sampling differs from Minecraft at " + position);
        }
    }

    private static CubicSampler.Vec3Fetcher fetcher(List<BlockPos> taps, boolean customScale) {
        return (x, y, z) -> {
            taps.add(new BlockPos(x, y, z));
            Vec3 color = Vec3.fromRGB24((x * 0x13579b) ^ (y * 0x29ef21) ^ (z * 0x6b4ed9));
            if (customScale) {
                return new Vec3(color.x, color.y, color.z) {
                    @Override
                    public Vec3 scale(double factor) {
                        return super.scale(factor * 0.5);
                    }
                };
            }
            return color;
        };
    }
}
