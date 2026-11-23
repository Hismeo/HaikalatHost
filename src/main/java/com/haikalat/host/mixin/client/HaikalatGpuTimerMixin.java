package com.haikalat.host.mixin.client;

import com.kaleblangley.haikalat.backend.GpuTimer;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GpuTimer.class, remap = false)
abstract class HaikalatGpuTimerMixin {
    @Shadow private boolean lastAttemptSubmitted;
    @Shadow private long skippedSubmissions;

    @Inject(method = "begin(J)Z", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL33;glBeginQuery(II)V"), cancellable = true)
    private void haikalathost$skipNestedElapsedQuery(long sequence, CallbackInfoReturnable<Boolean> ci) {
        if (GL15.glGetQueryi(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY) != 0) {
            // The frame graph or Minecraft's debug profiler already owns this query target.
            lastAttemptSubmitted = false;
            skippedSubmissions++;
            ci.setReturnValue(false);
        }
    }
}
