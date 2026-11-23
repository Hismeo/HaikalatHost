package com.haikalat.host.mixin.client;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ShaderProgram.class, remap = false)
public interface ShaderProgramInvoker {
    @Invoker("<init>")
    static ShaderProgram haikalathost$adopt(int id) {
        throw new AssertionError();
    }
}
