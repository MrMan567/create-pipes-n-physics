package de.devin.pipesnphysics.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

/**
 * The shared gradient-rod emitter behind every overlay drawn ALONG a pipe run — the /pipegraph
 * edge rods and the pump reach sleeve. Extrudes a square prism between two world points with its
 * vertex colors blended start→end, so the GPU carries the gradient.
 *
 * The half-extent is the caller's: a hairline 1/32 reads as a line threaded through the pipe,
 * while a value just past Create's 4..12 pipe core wraps the pipe as a sleeve.
 */
public final class RodRender {
    private RodRender() {}

    /** A square prism from (x0,y0,z0) to (x1,y1,z1), drawn as its four side quads. */
    public static void segment(Matrix4f m, VertexConsumer buf,
                               float x0, float y0, float z0,
                               float x1, float y1, float z1,
                               Rgb startColor, Rgb endColor, float half, int alpha) {
        float[][] offs = crossSection(x1 - x0, y1 - y0, z1 - z0);
        if (offs == null) return;
        for (int i = 0; i < 4; i++) {
            float[] o0 = offs[i];
            float[] o1 = offs[(i + 1) % 4];
            // One side face of the square prism, wound p0.o0 → p0.o1 → p1.o1 → p1.o0.
            vertex(m, buf, x0, y0, z0, o0, half, startColor, alpha);
            vertex(m, buf, x0, y0, z0, o1, half, startColor, alpha);
            vertex(m, buf, x1, y1, z1, o1, half, endColor, alpha);
            vertex(m, buf, x1, y1, z1, o0, half, endColor, alpha);
        }
    }

    /** One rod vertex at a corner offset scaled to {@code half}; POSITION_COLOR, no normal. */
    private static void vertex(Matrix4f m, VertexConsumer buf,
                               float cx, float cy, float cz,
                               float[] off, float half, Rgb color, int alpha) {
        buf.addVertex(m, cx + off[0] * half, cy + off[1] * half, cz + off[2] * half)
                .setColor(color.r(), color.g(), color.b(), alpha);
    }

    /**
     * The four corner directions of a square cross-section orthogonal to (dx,dy,dz), as unit
     * offsets; null when the direction is degenerate.
     */
    private static float[][] crossSection(float dx, float dy, float dz) {
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5f) return null;
        dx /= len; dy /= len; dz /= len;

        // Reference axis not parallel to the direction, so the cross-product is well-defined.
        float refx, refy, refz;
        if (Math.abs(dy) > 0.9f) { refx = 1; refy = 0; refz = 0; }
        else { refx = 0; refy = 1; refz = 0; }

        // u = dir x ref, then v = dir x u — orthonormal cross-section axes.
        float ux = dy * refz - dz * refy;
        float uy = dz * refx - dx * refz;
        float uz = dx * refy - dy * refx;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        float vx = dy * uz - dz * uy;
        float vy = dz * ux - dx * uz;
        float vz = dx * uy - dy * ux;

        return new float[][] {
                { ux + vx, uy + vy, uz + vz },
                { ux - vx, uy - vy, uz - vz },
                { -ux - vx, -uy - vy, -uz - vz },
                { -ux + vx, -uy + vy, -uz + vz },
        };
    }
}
