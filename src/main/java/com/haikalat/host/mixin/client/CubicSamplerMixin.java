package com.haikalat.host.mixin.client;

import com.haikalat.host.client.CubicColorSampler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CubicSampler.class)
abstract class CubicSamplerMixin {
    @WrapMethod(method = "gaussianSampleVec3")
    private static Vec3 haikalathost$sampleColor(Vec3 position, CubicSampler.Vec3Fetcher fetcher, Operation<Vec3> original) {
        return CubicColorSampler.sample(position, fetcher);
    }
}
