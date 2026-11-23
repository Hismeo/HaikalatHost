package com.haikalat.host.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;

/** Ordered quad runs. The render thread drops ranges only when every quad in them is back-facing. */
public final class TerrainFaceRanges {
    public static final boolean ENABLED = Boolean.getBoolean("haikalathost.terrainFaceCulling");
    static final int MAX_RANGES = 4;
    private static final int UNCLASSIFIED = 6;
    private static final int MIN_INTERNAL_GAP = 8;
    private static final double PLANE_MARGIN = 1.0 / 32;
    private final float[] planes;
    private final int[] runs;
    private final int quads;
    private int lastMask;
    private int[] ranges;
    private int rangeCount;
    private int skippedQuads;

    private TerrainFaceRanges(float[] planes, int[] runs, int quads) {
        this.planes = planes;
        this.runs = runs;
        this.quads = quads;
    }

    public static TerrainFaceRanges bake(MeshData mesh) {
        MeshData.DrawState state = mesh.drawState();
        if (state.format() != DefaultVertexFormat.BLOCK || state.mode() != VertexFormat.Mode.QUADS
                || state.vertexCount() < 4 || state.vertexCount() % 4 != 0
                || state.indexCount() != state.vertexCount() / 4 * 6 || mesh.indexBuffer() != null) {
            return null;
        }
        int stride = state.format().getVertexSize();
        int position = state.format().getOffset(VertexFormatElement.POSITION);
        ByteBuffer vertices = mesh.vertexBuffer();
        if ((long) state.vertexCount() * stride != vertices.remaining()) return null;
        float[] planes = new float[6];
        for (int face = 0; face < planes.length; face++) {
            planes[face] = (face & 1) == 0 ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        }
        IntArrayList runs = new IntArrayList();
        int quads = state.vertexCount() / 4;
        int previous = -1;
        int classified = 0;
        for (int quad = 0, first = vertices.position() + position; quad < quads; quad++, first += 4 * stride) {
            int face = face(vertices, first, stride, planes);
            if (face != UNCLASSIFIED) classified++;
            if (face != previous) {
                runs.add(quad << 3 | face);
                previous = face;
            }
        }
        return classified == 0 ? null : new TerrainFaceRanges(planes, runs.toIntArray(), quads);
    }

    private static int face(ByteBuffer vertices, int first, int stride, float[] planes) {
        for (int axis = 0; axis < 3; axis++) {
            int coord = axis * Float.BYTES;
            float plane = vertices.getFloat(first + coord);
            if (!Float.isFinite(plane) || vertices.getFloat(first + stride + coord) != plane
                    || vertices.getFloat(first + 2 * stride + coord) != plane
                    || vertices.getFloat(first + 3 * stride + coord) != plane) continue;
            int u = (axis + 1) % 3 * Float.BYTES;
            int v = (axis + 2) % 3 * Float.BYTES;
            double u0 = vertices.getFloat(first + u), v0 = vertices.getFloat(first + v);
            double u1 = vertices.getFloat(first + stride + u) - u0, v1 = vertices.getFloat(first + stride + v) - v0;
            double u2 = vertices.getFloat(first + 2 * stride + u) - u0, v2 = vertices.getFloat(first + 2 * stride + v) - v0;
            double u3 = vertices.getFloat(first + 3 * stride + u) - u0, v3 = vertices.getFloat(first + 3 * stride + v) - v0;
            double firstTriangle = u1 * v2 - v1 * u2;
            double secondTriangle = u2 * v3 - v2 * u3;
            // The vanilla sequential EBO uses 0,1,2 and 2,3,0. Both triangles must give the same winding.
            if (!Double.isFinite(firstTriangle) || !Double.isFinite(secondTriangle)) return UNCLASSIFIED;
            if (firstTriangle > 1e-6 && secondTriangle > 1e-6) {
                int direction = 2 * axis + 1;
                planes[direction] = Math.min(planes[direction], plane);
                return direction;
            }
            if (firstTriangle < -1e-6 && secondTriangle < -1e-6) {
                int direction = 2 * axis;
                planes[direction] = Math.max(planes[direction], plane);
                return direction;
            }
        }
        return UNCLASSIFIED;
    }

    int select(View view, float x, float y, float z, int indexCount) {
        if (indexCount != quads * 6 || !view.valid) return 0;
        int mask = behind(view.x - x, 0) | behind(view.y - y, 2) | behind(view.z - z, 4);
        if (mask == 0) return 0;
        if (mask != lastMask) {
            rebuildRanges(mask);
            lastMask = mask;
        }
        return skippedQuads;
    }

    private int behind(double camera, int direction) {
        double margin = PLANE_MARGIN + 4 * Math.ulp((float) camera);
        int result = 0;
        if (camera > planes[direction] + margin && Float.isFinite(planes[direction])) result |= 1 << direction;
        if (camera < planes[direction + 1] - margin && Float.isFinite(planes[direction + 1])) result |= 1 << (direction + 1);
        return result;
    }

    private void rebuildRanges(int mask) {
        if (ranges == null) ranges = new int[2 * MAX_RANGES];
        rangeCount = 0;
        skippedQuads = 0;
        int firstKept = 0;
        for (int i = 0; i < runs.length;) {
            int run = runs[i++];
            if ((mask & (1 << (run & 7))) == 0) continue;
            int firstSkipped = run >>> 3;
            while (i < runs.length && (mask & (1 << (runs[i] & 7))) != 0) i++;
            int end = i == runs.length ? quads : runs[i] >>> 3;
            // Dropping the prefix/suffix costs no additional draw. Limit interior splits in fragmented meshes.
            if (firstSkipped != 0 && end != quads
                    && (end - firstSkipped < MIN_INTERNAL_GAP || rangeCount >= MAX_RANGES - 1)) continue;
            addRange(firstKept, firstSkipped);
            firstKept = end;
            skippedQuads += end - firstSkipped;
        }
        addRange(firstKept, quads);
    }

    private void addRange(int first, int end) {
        if (end <= first) return;
        ranges[2 * rangeCount] = first * 6;
        ranges[2 * rangeCount + 1] = (end - first) * 6;
        rangeCount++;
    }

    int rangeCount() {
        return rangeCount;
    }

    int firstIndex(int range) {
        return ranges[2 * range];
    }

    int indexCount(int range) {
        return ranges[2 * range + 1];
    }

    /** Effective eye in camera-relative geometry coordinates, including projection/model-view transforms. */
    static final class View {
        double x, y, z;
        boolean valid;

        void set(Matrix4f clip) {
            valid = false;
            if (!clip.isFinite()) return;
            // Intersect clip x=0, y=0 and w=0. A regular CCW perspective has a negative determinant.
            double ax = clip.m00(), ay = clip.m10(), az = clip.m20();
            double bx = clip.m01(), by = clip.m11(), bz = clip.m21();
            double cx = clip.m03(), cy = clip.m13(), cz = clip.m23();
            double u = by * cz - bz * cy, v = bz * cx - bx * cz, w = bx * cy - by * cx;
            double det = ax * u + ay * v + az * w;
            if (!(det < -1e-9)) return;
            double tx = -clip.m30(), ty = -clip.m31(), tw = -clip.m33();
            x = (tx * u + ty * (cy * az - cz * ay) + tw * (ay * bz - az * by)) / det;
            y = (tx * v + ty * (cz * ax - cx * az) + tw * (az * bx - ax * bz)) / det;
            z = (tx * w + ty * (cx * ay - cy * ax) + tw * (ax * by - ay * bx)) / det;
            valid = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
    }
}
