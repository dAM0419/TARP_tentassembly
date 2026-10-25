package com.tarp.client;

import com.tarp.core.Door;
import com.tarp.core.Envelope;
import com.tarp.core.Hull3;
import com.tarp.core.Membrane;
import com.tarp.core.V3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 막 모양을 면으로 낸다 (0.3.0).
 * <ul>
 *   <li>바깥면: 껍질 면 그대로(법선 바깥), 밝기는 면 바깥쪽 칸에서.</li>
 *   <li>안쪽면: 순서만 뒤집어 안에서 보이게 하고, 법선과 해 방향 음영은 바깥면과 같다. 커튼처럼 해를 받는 쪽은 안에서도 밝고,
 *       그늘진 쪽은 안에서도 어둡다. 색은 Spec.CLOTH_INNER 배 (천을 지나며 줄어든 빛).
 *       하늘빛은 바깥 값, 블록 빛은 안쪽 랜턴과 바깥 블록 빛×투과율 중 밝은 쪽 (0.3.11).</li>
 *   <li>층 3 = 안쪽면. 셰이더 그림자 단계에서는 안쪽면만 그림자를 드리운다 (TarpRenderer).</li>
 *   <li>입구 절개선: 모델이 아니라 면 위에 붙인 얇은 박음선 무늬(데칼).</li>
 * </ul>
 * 꼭짓점 순서는 언제나 넘기는 법선 쪽에서 보아 반시계 (셰이더팩의 뒷면 법선 뒤집기와 어긋나지 않게).
 */
final class TarpMesh {
    private TarpMesh() {}

    static final double INNER_OFFSET = 0.01;

    /** 층: 0 천 바깥면, 1 박음선, 2 두루마리, 3 천 안쪽면. */
    static final int OUTER = 0, SEAM = 1, ROLL = 2, INNER = 3;
    record Quad(V3 a, V3 b, V3 c, V3 d, float[] uv, V3 n, int[] light, int argb, int layer) {}

    interface Lighting {
        /** 바깥 쪽 칸 밝기(점 p, 바깥 법선 n). */
        int outside(V3 p, V3 n);

        /** 안쪽 면 밝기. */
        int inside(V3 p, V3 n);

        /** 면 음영 배수 (0~1): 해 방향·경사. 셰이더가 있으면 1 (셰이더가 한다). */
        double shade(V3 n);
    }

    /** 면 하나의 밝기: 꼭짓점들 빛 중 가장 밝은 값 하나를 면 전체에 (0.3.6, 꼭짓점마다 다르면 얼룩이 번진다). */
    private static int faceLight(List<V3> poly, V3 n, Lighting lit, boolean inner) {
        int best = 0, bs = -1;
        for (V3 p : poly) {
            int c = inner ? lit.inside(p, n) : lit.outside(p, n);
            int v = net.minecraft.client.renderer.LightTexture.sky(c) * 16 + net.minecraft.client.renderer.LightTexture.block(c);
            if (v > bs) {
                bs = v;
                best = c;
            }
        }
        return best;
    }

    /** 굽기: 바깥·안쪽 면, 박음선, 두루마리. */
    static List<Quad> build(Membrane.Result r, @Nullable Door door, boolean open, int rgb, Lighting lit) {
        List<Quad> out = new ArrayList<>();
        int argb = Geo.argb(255, rgb);
        int inner = Geo.argb(255, Geo.shade(rgb, com.tarp.core.Spec.CLOTH_INNER));
        boolean cut = door != null && open;
        for (Hull3.Face f : r.faces) {
            V3 n = f.n();
            UV uv = UV.of(n);
            int fl = faceLight(f.poly(), n, lit, false), flIn = faceLight(f.poly(), n, lit, true);
            double sh = lit.shade(n);
            int col = Geo.argb(255, Geo.shade(rgb, sh)), colIn = Geo.argb(255, Geo.shade(rgb, sh * com.tarp.core.Spec.CLOTH_INNER));
            for (List<V3> piece : cut ? cutRoof(f.poly(), door) : List.of(f.poly())) {
                fan(out, piece, n, uv, col, OUTER, p -> fl);
                // 안쪽 면: 안에서 보이게 순서만 뒤집고, 법선·음영은 바깥면 그대로 (커튼처럼 해를 받는 쪽은 안도 밝다)
                // 0.3.7: 바깥면과 같은 자리에 겹치면 셰이더팩이 뒷면을 그릴 때 두 면이 섞여 그늘이 햇빛처럼 칠해진다 → 0.01칸 안으로
                List<V3> rev = new ArrayList<>();
                for (V3 q : piece) rev.add(q.sub(n.mul(INNER_OFFSET)));
                java.util.Collections.reverse(rev);
                fan(out, rev, n, uv, colIn, INNER, p -> flIn);
            }
            if (door != null) seams(out, f, door, cut, uv, fl, flIn);
        }
        if (cut) roll(out, r, door, rgb, lit);
        return out;
    }

    /** 투영(색만): 바깥면만. */
    static void emitPreview(Geo.Out o, Membrane.Result r, int argb) {
        for (Hull3.Face f : r.faces) o.polygon(f.poly(), argb, p -> new float[]{0, 0}, f.n());
    }

    static void emit(Geo.Out o, List<Quad> quads, int layer) {
        for (Quad q : quads) {
            if (q.layer() != layer) continue;
            float[] t = q.uv();
            int[] l = q.light();
            o.v(q.a(), q.argb(), t[0], t[1], q.n(), l[0]);
            o.v(q.b(), q.argb(), t[2], t[3], q.n(), l[1]);
            o.v(q.c(), q.argb(), t[4], t[5], q.n(), l[2]);
            o.v(q.d(), q.argb(), t[6], t[7], q.n(), l[3]);
        }
    }

    /** 면 위 무늬 방향: 수평 접선 t, 경사 방향 b. UV 는 월드 칸 단위 (텍스처 반복). */
    record UV(V3 t, V3 b) {
        static UV of(V3 n) {
            V3 t = Math.abs(n.y()) > 0.999 ? new V3(1, 0, 0) : Geo.cross(new V3(0, 1, 0), n);
            t = t.mul(1 / t.len());
            return new UV(t, Geo.cross(n, t));
        }

        float[] at(V3 p) {
            return new float[]{(float) (p.dot(t) / com.tarp.core.Spec.CLOTH_TILE), (float) (-p.dot(b) / com.tarp.core.Spec.CLOTH_TILE)};
        }
    }

    private static void fan(List<Quad> out, List<V3> poly, V3 n, UV uv, int argb, int layer, java.util.function.ToIntFunction<V3> light) {
        int m = poly.size();
        if (m < 3) return;
        for (int i = 1; i + 1 < m; i += 2) {
            V3 a = poly.get(0), b = poly.get(i), c = poly.get(i + 1), d = i + 2 < m ? poly.get(i + 2) : poly.get(i + 1);
            float[] ua = uv.at(a), ub = uv.at(b), uc = uv.at(c), ud = uv.at(d);
            out.add(new Quad(a, b, c, d, new float[]{ua[0], ua[1], ub[0], ub[1], uc[0], uc[1], ud[0], ud[1]}, n,
                    new int[]{light.applyAsInt(a), light.applyAsInt(b), light.applyAsInt(c), light.applyAsInt(d)}, argb, layer));
        }
    }

    /** 열린 입구: 띠 안(s0~s1), 깊이 안, 입구 높이 아래를 도려낸다. */
    static List<List<V3>> cutRoof(List<V3> poly, Door door) {
        Membrane.Edge e = door.e;
        Envelope.Lin s = p -> (p.x() - e.a.x()) * e.dx + (p.z() - e.a.z()) * e.dz;
        Envelope.Lin d = p -> (p.x() - e.a.x()) * e.nx + (p.z() - e.a.z()) * e.nz;
        double depth = door.maxDepth + 0.01;
        List<List<V3>> out = new ArrayList<>();
        add(out, Envelope.clip(poly, p -> door.s0 - s.at(p)));
        add(out, Envelope.clip(poly, p -> s.at(p) - door.s1));
        List<V3> mid = Envelope.clip(Envelope.clip(poly, p -> s.at(p) - door.s0), p -> door.s1 - s.at(p));
        if (mid.size() >= 3) {
            add(out, Envelope.clip(mid, p -> d.at(p) - depth));
            add(out, Envelope.clip(Envelope.clip(mid, p -> depth - d.at(p)), p -> p.y() - door.top));
        }
        return out;
    }

    /** 입구 영역 (투영·절개선 공통): 띠 [s0, s1] × 깊이 안 × 입구 높이 아래. */
    static List<V3> doorRegion(List<V3> poly, Door door, double s0, double s1) {
        Membrane.Edge e = door.e;
        Envelope.Lin s = p -> (p.x() - e.a.x()) * e.dx + (p.z() - e.a.z()) * e.dz;
        Envelope.Lin d = p -> (p.x() - e.a.x()) * e.nx + (p.z() - e.a.z()) * e.nz;
        double depth = door.maxDepth + 0.01;
        List<V3> q = Envelope.clip(poly, p -> s.at(p) - s0);
        q = Envelope.clip(q, p -> s1 - s.at(p));
        q = Envelope.clip(q, p -> depth - d.at(p));
        return Envelope.clip(q, p -> door.top - p.y());
    }

    private static void add(List<List<V3>> out, List<V3> p) {
        if (p.size() >= 3) out.add(p);
    }

    /** 절개선 무늬: 양옆 경계를 따라 폭 1/8칸 띠, 면에서 살짝 띄워 바깥·안쪽 모두. */
    private static void seams(List<Quad> out, Hull3.Face f, Door door, boolean open, UV uv, int fl, int flIn) {
        double w = 1 / 16.0;
        V3 n = f.n();
        for (double s : new double[]{door.s0, door.s1}) {
            List<V3> strip = doorRegion(f.poly(), door, s - w, s + w);
            if (strip.size() < 3) continue;
            // 박음선 텍스처: u 는 띠 폭 방향(0~1), v 는 길이 방향
            Membrane.Edge e = door.e;
            java.util.function.Function<V3, float[]> suv = p -> {
                double ss = (p.x() - e.a.x()) * e.dx + (p.z() - e.a.z()) * e.dz;
                return new float[]{(float) ((ss - (s - w)) / (2 * w)), (float) (-p.y() * 2)};
            };
            for (int side = 0; side < 2; side++) {
                V3 nn = side == 0 ? n : n.mul(-1);
                List<V3> poly = new ArrayList<>();
                for (V3 p : strip) poly.add(p.add(nn.mul(side == 0 ? 0.004 : INNER_OFFSET + 0.004)));
                V3 shadeN = n;
                if (side == 1) java.util.Collections.reverse(poly);
                int m = poly.size();
                for (int i = 1; i + 1 < m; i += 2) {
                    V3 a = poly.get(0), b = poly.get(i), c = poly.get(i + 1), d = i + 2 < m ? poly.get(i + 2) : poly.get(i + 1);
                    float[] ua = suv.apply(a), ub = suv.apply(b), uc = suv.apply(c), ud = suv.apply(d);
                    int lv = side == 0 ? fl : flIn;
                    java.util.function.ToIntFunction<V3> l = p -> lv;
                    out.add(new Quad(a, b, c, d, new float[]{ua[0], ua[1], ub[0], ub[1], uc[0], uc[1], ud[0], ud[1]}, shadeN,
                            new int[]{l.applyAsInt(a), l.applyAsInt(b), l.applyAsInt(c), l.applyAsInt(d)}, 0xFFFFFFFF, 1));
                }
            }
        }
    }

    /** 두루마리 자리: 입구 위 테두리가 실제 천 표면에 닿는 곳, 바깥으로 두루마리 반지름만큼. */
    static V3 rollPoint(Membrane.Result r, Door door, double s) {
        Membrane.Edge ed = door.e;
        double d = r.outerD(ed, s, door.top);
        if (Double.isNaN(d)) d = door.rollDepth(s);
        return new V3(ed.px(s) + ed.nx * (d - 0.12), door.top + 0.05, ed.pz(s) + ed.nz * (d - 0.12));
    }

    /** 말아 올린 두루마리 (모델): 입구 위쪽 테두리를 따라 굵은 팔각기둥. */
    private static void roll(List<Quad> out, Membrane.Result r, Door door, int rgb, Lighting lit) {
        Membrane.Edge ed = door.e;
        V3 a = rollPoint(r, door, door.s0), b = rollPoint(r, door, door.s1);
        V3 dir = b.sub(a);
        double len = dir.len();
        if (len < 1e-4) return;
        dir = dir.mul(1 / len);
        V3 u = Geo.cross(dir, new V3(0, 1, 0));
        u = u.mul(1 / u.len());
        V3 w = Geo.cross(u, dir);
        int sides = 8;
        double rad = 0.12;
        int argb = Geo.argb(255, Geo.shade(rgb, 0.9));
        for (int i = 0; i < sides; i++) {
            double a0 = Math.PI * 2 * i / sides, a1 = Math.PI * 2 * (i + 1) / sides;
            V3 o0 = u.mul(Math.cos(a0) * rad).add(w.mul(Math.sin(a0) * rad)), o1 = u.mul(Math.cos(a1) * rad).add(w.mul(Math.sin(a1) * rad));
            V3 n = o0.add(o1);
            n = n.mul(1 / n.len());
            V3 p0 = a.add(o0), p1 = b.add(o0), p2 = b.add(o1), p3 = a.add(o1);
            // 법선 쪽에서 보아 반시계가 되게
            V3 cr = Geo.cross(p1.sub(p0), p3.sub(p0));
            List<V3> q = cr.dot(n) >= 0 ? List.of(p0, p1, p2, p3) : List.of(p0, p3, p2, p1);
            int l = lit.outside(a.lerp(b, 0.5), n);
            int col = Geo.argb(255, Geo.shade(rgb, 0.9 * lit.shade(n)));
            float v0 = (float) (i * 0.5 / sides), v1 = (float) ((i + 1) * 0.5 / sides);
            out.add(new Quad(q.get(0), q.get(1), q.get(2), q.get(3), new float[]{0, v0, (float) len, v0, (float) len, v1, 0, v1}, n,
                    new int[]{l, l, l, l}, col, 2));
        }
    }
}
