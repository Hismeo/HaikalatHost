package com.haikalat.host.client;

import com.haikalat.host.mixin.client.ShaderProgramInvoker;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL20;

/** Owns linked Minecraft programs while Minecraft loads their shader stages from resource packs. */
public final class MinecraftPrograms {
    private static final Int2ObjectOpenHashMap<ShaderProgram> PROGRAMS = new Int2ObjectOpenHashMap<>();

    private MinecraftPrograms() {}

    public static int create() {
        int id = GL20.glCreateProgram();
        if (id != 0) {
            try {
                PROGRAMS.put(id, ShaderProgramInvoker.haikalathost$adopt(id));
            } catch (RuntimeException | Error failure) {
                GL20.glDeleteProgram(id);
                throw failure;
            }
        }
        return id;
    }

    public static void delete(int id) {
        ShaderProgram program = PROGRAMS.remove(id);
        if (program == null) {
            GL20.glDeleteProgram(id);
        } else {
            program.close();
        }
    }
}
