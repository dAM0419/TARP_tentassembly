package com.tarp.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tarp.core.V3;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** 메시 굽기 도우미. 좌표는 원점(origin)을 뺀 값으로 쓴다. */
final class Geo {
    private Geo() {}

    interface Light {
        int at(double x, double y, double z);
    }

    /** 굽는 메시(NEW_ENTITY) 또는 투영(POSITION_COLOR) 어느 쪽이든. */
    static final class Out {
        final VertexConsumer vc;
        final boolean entity;
        final V3 origin;
        final Light light;
        /** 블록 엔티티 렌더러의 자세 (있으면 좌표·법선을 이것으로 바꾼다). */
        final com.mojang.blaze3d.vertex.PoseStack.Pose pose;

        Out(VertexConsumer vc, boolean entity, V3 origin, Light light) {
            this(vc, entity, origin, light, null);
        }

        Out(VertexConsumer vc, boolean entity, V3 origin, Light light, com.mojang.blaze3d.vertex.PoseStack.Pose pose) {
            this.vc = vc;
            this.entity = entity;
            this.origin = origin;
            this.light = light;
            this.pose = pose;
        }

        void v(V3 p, int argb, float u, float v, V3 n) {
            int l = !entity ? 0 : light == null ? 0xF000F0 : light.at(p.x(), p.y(), p.z());
            v(p, argb, u, v, n, l);
        }

        void v(V3 p, int argb, float u, float v, V3 n, int l) {
            float x = (float) (p.x() - origin.x()), y = (float) (p.y() - origin.y()), z = (float) (p.z() - origin.z());
            if (pose != null) {
                vc.addVertex(pose, x, y, z).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(l)
                        .setNormal(pose, (float) n.x(), (float) n.y(), (float) n.z());
            } else if (entity) {
                vc.addVertex(x, y, z, argb, u, v, OverlayTexture.NO_OVERLAY, l, (float) n.x(), (float) n.y(), (float) n.z());
            } else {
                vc.addVertex(x, y, z).setColor(argb);
            }
        }

        /** 사각형 하나 (a b c d 순서). UV 는 스프라이트 안 비율. */
        void quad(V3 a, V3 b, V3 c, V3 d, int argb, TextureAtlasSprite sp, float u0, float v0, float u1, float v1) {
            V3 n = normal(a, b, c, d);
            float su0 = sp == null ? 0 : lerp(sp.getU0(), sp.getU1(), u0), su1 = sp == null ? 0 : lerp(sp.getU0(), sp.getU1(), u1);
            float sv0 = sp == null ? 0 : lerp(sp.getV0(), sp.getV1(), v0), sv1 = sp == null ? 0 : lerp(sp.getV0(), sp.getV1(), v1);
            v(a, argb, su0, sv0, n);
            v(b, argb, su1, sv0, n);
            v(c, argb, su1, sv1, n);
            v(d, argb, su0, sv1, n);
        }

        /** 사각형 하나, 꼭짓점마다 UV 를 그대로 쓴다 (반복 무늬 텍스처용). n 은 위·바깥을 향한 법선. */
        void quadRaw(V3 a, V3 b, V3 c, V3 d, int argb, float[] uv, V3 n) {
            v(a, argb, uv[0], uv[1], n);
            v(b, argb, uv[2], uv[3], n);
            v(c, argb, uv[4], uv[5], n);
            v(d, argb, uv[6], uv[7], n);
        }

        /** 볼록 다각형을 사각형 부채꼴로 낸다. uvOf 는 점 → {u, v}. */
        void polygon(java.util.List<V3> poly, int argb, java.util.function.Function<V3, float[]> uvOf, V3 n) {
            int m = poly.size();
            if (m < 3) return;
            for (int i = 1; i + 1 < m; i += 2) {
                V3 a = poly.get(0), b = poly.get(i), c = poly.get(i + 1), d = i + 2 < m ? poly.get(i + 2) : poly.get(i + 1);
                float[] ua = uvOf.apply(a), ub = uvOf.apply(b), uc = uvOf.apply(c), ud = uvOf.apply(d);
                quadRaw(a, b, c, d, argb, new float[]{ua[0], ua[1], ub[0], ub[1], uc[0], uc[1], ud[0], ud[1]}, n);
            }
        }

        /** 선분을 따라 굵기 2r 의 각기둥. 길면 1칸마다 나눠 무늬를 이어 붙인다. */
        void prism(V3 a, V3 b, double r, int argb, TextureAtlasSprite sp, int sides) {
            V3 d = b.sub(a);
            double len = d.len();
            if (len < 1e-4) return;
            V3 dir = d.mul(1 / len);
            V3 ref = Math.abs(dir.y()) < 0.9 ? new V3(0, 1, 0) : new V3(1, 0, 0);
            V3 u = cross(dir, ref);
            u = u.mul(1 / u.len());
            V3 w = cross(dir, u);
            int pieces = Math.max(1, (int) Math.ceil(len - 1e-6));
            V3[] ring0 = new V3[sides], ring1 = new V3[sides];
            for (int pc = 0; pc < pieces; pc++) {
                double t0 = pc / (double) pieces, t1 = (pc + 1) / (double) pieces;
                V3 c0 = a.lerp(b, t0), c1 = a.lerp(b, t1);
                float vlen = (float) Math.min(1, len / pieces);
                for (int i = 0; i < sides; i++) {
                    double ang = Math.PI * 2 * (i + 0.5) / sides;
                    double rr = sides == 4 ? r * Math.sqrt(2) : r;
                    V3 off = u.mul(Math.cos(ang) * rr).add(w.mul(Math.sin(ang) * rr));
                    ring0[i] = c0.add(off);
                    ring1[i] = c1.add(off);
                }
                for (int i = 0; i < sides; i++) {
                    int j = (i + 1) % sides;
                    float uu0 = (float) i / sides * 0.25F + 0.375F, uu1 = (float) (i + 1) / sides * 0.25F + 0.375F;
                    // 바깥을 앞면으로 (법선·꼭짓점 순서가 바깥을 향하게)
                    V3 mid = ring0[i].add(ring0[j]).mul(0.5).sub(c0);
                    if (normal(ring0[i], ring0[j], ring1[j], ring1[i]).dot(mid) >= 0)
                        quad(ring0[i], ring0[j], ring1[j], ring1[i], argb, sp, uu0, 0, uu1, vlen);
                    else
                        quad(ring1[i], ring1[j], ring0[j], ring0[i], argb, sp, uu0, vlen, uu1, 0);
                }
                if (pc == 0) cap(ring0, argb, sp, dir.mul(-1));
                if (pc == pieces - 1) cap(ring1, argb, sp, dir);
            }
        }

        private void cap(V3[] ring, int argb, TextureAtlasSprite sp, V3 out) {
            for (int i = 1; i + 1 < ring.length; i++) {
                V3 n = cross(ring[i].sub(ring[0]), ring[i + 1].sub(ring[0]));
                if (n.dot(out) >= 0) quad(ring[0], ring[i], ring[i + 1], ring[i + 1], argb, sp, 0.4F, 0.4F, 0.6F, 0.6F);
                else quad(ring[0], ring[i + 1], ring[i], ring[i], argb, sp, 0.4F, 0.4F, 0.6F, 0.6F);
            }
        }

        /** 상자 (투영 표시용). */
        void box(V3 lo, V3 hi, int argb) {
            double x0 = lo.x(), y0 = lo.y(), z0 = lo.z(), x1 = hi.x(), y1 = hi.y(), z1 = hi.z();
            V3 a = new V3(x0, y0, z0), b = new V3(x1, y0, z0), c = new V3(x1, y0, z1), d = new V3(x0, y0, z1);
            V3 e = new V3(x0, y1, z0), f = new V3(x1, y1, z0), g = new V3(x1, y1, z1), h = new V3(x0, y1, z1);
            quad(a, b, c, d, argb, null, 0, 0, 0, 0);
            quad(e, h, g, f, argb, null, 0, 0, 0, 0);
            quad(a, e, f, b, argb, null, 0, 0, 0, 0);
            quad(d, c, g, h, argb, null, 0, 0, 0, 0);
            quad(a, d, h, e, argb, null, 0, 0, 0, 0);
            quad(b, f, g, c, argb, null, 0, 0, 0, 0);
        }

        void cube(V3 c, double r, int argb) {
            box(c.sub(new V3(r, r, r)), c.add(new V3(r, r, r)), argb);
        }
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static V3 cross(V3 a, V3 b) {
        return new V3(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }

    static V3 normal(V3 a, V3 b, V3 c, V3 d) {
        V3 n = cross(c.sub(a), d.sub(b));
        double l = n.len();
        return l < 1e-9 ? new V3(0, 1, 0) : n.mul(1 / l);
    }

    static int argb(int a, int rgb) {
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    static int mix(int c1, int c2, double t) {
        int r = (int) (((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t);
        int g = (int) (((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t);
        int b = (int) ((c1 & 255) * (1 - t) + (c2 & 255) * t);
        return (r << 16) | (g << 8) | b;
    }

    static int shade(int rgb, double k) {
        return mix(rgb, 0, 1 - k);
    }
}
