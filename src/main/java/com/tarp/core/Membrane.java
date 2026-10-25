package com.tarp.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 막 덮기 (0.3.0). 서버 검증과 클라이언트 투영·렌더가 같은 코드를 쓴다.
 * <ol>
 *   <li>묶는 점(밑단, 위로 α)과, 밑단보다 높은 지지대·노끈 점을 모은다. 밑단 아래 골조는 유격 범위라 덮지 않는다.</li>
 *   <li>3차원 볼록 껍질을 한 번 구한 뒤, 지지대·노끈 점을 그 위를 덮는 면의 법선 방향으로 여유만큼 밀어내고 다시 구한다.
 *       한 점이 여러 면에 걸치면(꺾이는 곳) 면마다 한 번씩 밀어내서, 꺾인 곳이 좁은 평면 띠(모따기)가 된다.</li>
 *   <li>아래를 향한 바닥 면을 버린다. 남은 면이 막이다. 옆면은 프레임 기울기를 따라 비스듬히 내려온다 (수직 커튼 없음).</li>
 * </ol>
 */
public final class Membrane {
    private Membrane() {}

    /** 막이 피해 가야 하는 선분. frame 이면 지지대(노출 검사 대상), 아니면 노끈. */
    public record Obstacle(V3 a, V3 b, double clear, boolean frame) {}

    public enum Error { TOO_FEW, NOT_SIMPLE, TOO_SMALL, TOO_WIDE, FLAT }

    /** 둘레 변 하나: 묶는 점 a → b (반시계). n 은 수평 안쪽 법선. */
    public static final class Edge {
        public final V3 a, b;
        public final double len, dx, dz, nx, nz;

        Edge(V3 a, V3 b) {
            this.a = a;
            this.b = b;
            this.len = a.hdist(b);
            this.dx = (b.x() - a.x()) / len;
            this.dz = (b.z() - a.z()) / len;
            this.nx = -dz;
            this.nz = dx;
        }

        /** 묶는 점을 잇는 선의 높이. */
        public double tie(double s) {
            return a.y() + (b.y() - a.y()) * clamp01(s / len);
        }

        /** 밑단 높이 = 묶는 점 선 + α. */
        public double bottom(double s) {
            return tie(s) + Spec.ALPHA;
        }

        public double px(double s) {
            return a.x() + dx * s;
        }

        public double pz(double s) {
            return a.z() + dz * s;
        }

        /** 축 방향 변이면 0(x) 또는 1(z), 아니면 -1. */
        public int axis() {
            if (Math.abs(dz) < 1e-6) return 0;
            if (Math.abs(dx) < 1e-6) return 1;
            return -1;
        }
    }

    public static final class Result {
        public Error error;
        /** 반시계로 맞춘 묶는 점. */
        public List<V3> ties;
        public boolean reversed;
        public Edge[] edges;
        /** 막 면 (바깥 법선, 바깥에서 보아 반시계). */
        public final List<Hull3.Face> faces = new ArrayList<>();
        /** 막 밖으로 나온 지지대 점. */
        public final List<V3> exposed = new ArrayList<>();
        public double area;
        /** 입구 계산 간격(칸). */
        public final double h = Spec.GRID;

        public double area() {
            return area;
        }

        public boolean ok() {
            return error == null;
        }

        /** 수평 위치의 막 윗면 높이 (위를 향한 면 중 가장 높은 것), 없으면 NaN. */
        public double height(double x, double z) {
            double best = Double.NaN;
            double[] fb = bounds();
            for (int i = 0; i < faces.size(); i++) {
                Hull3.Face f = faces.get(i);
                if (f.n().y() < 1e-4) continue;
                // 0.4.10: 수평 상자 밖이면 다각형 판정 전에 건너뛴다 (입구 판정이 이 함수를 수천 번 부른다)
                if (x < fb[4 * i] - 1e-7 || x > fb[4 * i + 1] + 1e-7 || z < fb[4 * i + 2] - 1e-7 || z > fb[4 * i + 3] + 1e-7) continue;
                if (!insideXZ(f.poly(), x, z)) continue;
                double y = (f.d() - f.n().x() * x - f.n().z() * z) / f.n().y();
                if (Double.isNaN(best) || y > best) best = y;
            }
            return best;
        }

        private double[] faceBounds;

        /** 면마다 수평 상자 {minX, maxX, minZ, maxZ}. */
        private double[] bounds() {
            if (faceBounds == null || faceBounds.length != faces.size() * 4) {
                double[] b = new double[faces.size() * 4];
                for (int i = 0; i < faces.size(); i++) {
                    double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z0 = Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
                    for (V3 p : faces.get(i).poly()) {
                        x0 = Math.min(x0, p.x());
                        x1 = Math.max(x1, p.x());
                        z0 = Math.min(z0, p.z());
                        z1 = Math.max(z1, p.z());
                    }
                    b[4 * i] = x0;
                    b[4 * i + 1] = x1;
                    b[4 * i + 2] = z0;
                    b[4 * i + 3] = z1;
                }
                faceBounds = b;
            }
            return faceBounds;
        }

        public boolean insidePoly(double x, double z) {
            return Polygon.contains(ties, x, z);
        }

        /**
         * 막 표면에서, 변 e 의 위치 s 를 지나 안쪽(d)으로 뻗은 높이 y 의 수평선이 처음 닿는 곳의 d (가장 바깥).
         * 입구 두루마리를 실제 천 표면에 붙일 때 쓴다. 없으면 NaN.
         */
        public double outerD(Edge e, double s, double y) {
            double best = Double.NaN;
            V3 base = new V3(e.px(s), y, e.pz(s));
            V3 dir = new V3(e.nx, 0, e.nz);
            for (Hull3.Face f : faces) {
                double den = f.n().dot(dir);
                if (Math.abs(den) < 1e-6) continue;
                double d = (f.d() - f.n().dot(base)) / den;
                V3 p = base.add(dir.mul(d));
                if (!onFace(f, p)) continue;
                if (Double.isNaN(best) || d < best) best = d;
            }
            return best;
        }

        static boolean onFace(Hull3.Face f, V3 p) {
            List<V3> poly = f.poly();
            for (int i = 0; i < poly.size(); i++) {
                V3 a = poly.get(i), b = poly.get((i + 1) % poly.size());
                if (Hull3.cross(b.sub(a), p.sub(a)).dot(f.n()) < -1e-6) return false;
            }
            return true;
        }

        /** 점이 막 아래(천막 안)에 있는가. */
        public boolean under(V3 p) {
            if (!insidePoly(p.x(), p.z())) return false;
            double h = height(p.x(), p.z());
            return !Double.isNaN(h) && p.y() < h;
        }

        /** 선분이 막 면을 지나는 매개변수 t (0~1) 들. */
        public List<Double> crossings(V3 a, V3 b) {
            List<Double> out = new ArrayList<>();
            V3 ab = b.sub(a);
            for (Hull3.Face f : faces) {
                double den = f.n().dot(ab);
                if (Math.abs(den) < 1e-9) continue;
                double t = (f.d() - f.n().dot(a)) / den;
                if (t <= 1e-6 || t >= 1 - 1e-6) continue;
                if (onFace(f, a.add(ab.mul(t)))) out.add(t);
            }
            return out;
        }

        /** 묶는 점 i 의 밑단 위치 (고리가 이어지는 곳). */
        public V3 hem(int i) {
            V3 t = ties.get(i);
            // 0.4.5: 땅 가까이(칸 바닥에서 1/4칸 안) 묶은 점은 밑단을 "칸 바닥 + 유격"에 둔다 (말뚝 머리 + 유격이면 붕 떴다)
            double base = Math.floor(t.y() + 1e-6), frac = t.y() - base;
            double y = frac < 0.25 ? Math.max(t.y() + 0.08, base + Spec.ALPHA) : t.y() + Spec.ALPHA; // 0.08: 묶은 노끈 겉면(√2·반지름 + 여유)보다 위
            return new V3(t.x(), y, t.z());
        }
    }

    /** 수평 투영이 볼록 다각형 안인가 (꼭짓점 순서 무관). */
    static boolean insideXZ(List<V3> poly, double x, double z) {
        int n = poly.size();
        int sign = 0;
        for (int i = 0; i < n; i++) {
            V3 a = poly.get(i), b = poly.get((i + 1) % n);
            double c = (b.x() - a.x()) * (z - a.z()) - (b.z() - a.z()) * (x - a.x());
            if (Math.abs(c) < 1e-9) continue;
            int s = c > 0 ? 1 : -1;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
        }
        return true;
    }

    public static Result compute(List<V3> input, List<Obstacle> obs) {
        Result r = new Result();
        if (input.size() < 3) return fail(r, Error.TOO_FEW);
        List<V3> ties = new ArrayList<>(input);
        if (!Polygon.simple(ties)) return fail(r, Error.NOT_SIMPLE);
        double sa = Polygon.signedArea(ties);
        if (Math.abs(sa) < 0.5) return fail(r, Error.TOO_SMALL);
        if (sa < 0) {
            java.util.Collections.reverse(ties);
            r.reversed = true;
        }
        r.ties = ties;
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (V3 p : ties) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        if (maxX - minX > Spec.MAX_SPAN || maxZ - minZ > Spec.MAX_SPAN) return fail(r, Error.TOO_WIDE);
        int n = ties.size();
        r.edges = new Edge[n];
        for (int e = 0; e < n; e++) r.edges[e] = new Edge(ties.get(e), ties.get((e + 1) % n));

        // 밑단 면: 묶는 점만으로 만든 위쪽 포락. 이보다 낮은 골조는 덮지 않는다 (유격 범위)
        List<V3> hems = new ArrayList<>();
        for (int i = 0; i < n; i++) hems.add(r.hem(i));
        Envelope hemEnv = Envelope.of(hems);
        double lowHem = Double.MAX_VALUE;
        for (V3 hm : hems) lowHem = Math.min(lowHem, hm.y());

        // 덮을 골조 점 (0.25칸 간격)
        List<double[]> frame = new ArrayList<>();
        for (Obstacle o : obs) {
            double len = o.a().dist(o.b());
            int m = Math.max(1, (int) Math.ceil(len / 0.25));
            for (int i = 0; i <= m; i++) {
                V3 q = o.a().lerp(o.b(), (double) i / m);
                boolean in = Polygon.contains(ties, q.x(), q.z());
                double edgeDist = Polygon.nearestEdge(ties, q.x(), q.z())[2];
                double hemY = hemEnv.empty() ? lowHem : hemEnv.height(q.x(), q.z());
                if (!Double.isFinite(hemY)) hemY = lowHem;
                // 0.4.7: 밑단 포락면(천이 적어도 지나가는 높이)보다 골조 "겉면"까지 낮을 때만 거른다.
                // 전엔 골조 중심만 봐서, 포락면 바로 아래(여유 안)에 있는 노끈·지지대가 빠지고 천이 그 겉면을 뚫었다
                // (묶는 점끼리 이은 용마루줄이 대표적: 중심은 밑단보다 α 아래지만 겉면은 천 위로 나온다).
                if (q.y() + o.clear() + 0.01 <= hemY) continue;
                if (!in && edgeDist > 0.3) {
                    if (o.frame()) r.exposed.add(q);
                    continue;
                }
                frame.add(new double[]{q.x(), q.y(), q.z(), o.clear()});
            }
        }

        // 바닥 아래 가상 점: 점들이 한 평면에 있어도 입체가 되게 한다 (이 점이 낀 면은 버린다)
        double cx = 0, cz = 0;
        for (V3 t : hems) {
            cx += t.x();
            cz += t.z();
        }
        V3 virt = new V3(cx / n, lowHem - 0.05, cz / n);

        // 1차: 골조 점 그대로
        List<V3> pts = new ArrayList<>(hems);
        for (double[] f : frame) pts.add(new V3(f[0], f[1], f[2]));
        pts.add(virt);
        List<Hull3.Face> first = Hull3.build(pts);
        if (first.isEmpty()) return fail(r, Error.FLAT);

        // 2차: 골조 점을 덮는 면의 법선 방향으로 여유만큼 밀어낸다 (꺾이는 곳은 면마다 → 모따기 띠)
        List<V3> pts2 = new ArrayList<>(hems);
        for (double[] f : frame) {
            V3 q = new V3(f[0], f[1], f[2]);
            Hull3.Face near = null;
            double nearD = -Double.MAX_VALUE;
            boolean any = false;
            for (Hull3.Face face : first) {
                if (!usable(face, virt)) continue;
                double d = face.dist(q);
                if (d > -0.02) {
                    pts2.add(q.add(face.n().mul(f[3])));
                    any = true;
                }
                if (d > nearD) {
                    nearD = d;
                    near = face;
                }
            }
            if (!any && near != null && nearD > -f[3]) pts2.add(q.add(near.n().mul(f[3])));
        }
        pts2.add(virt);
        List<Hull3.Face> second = Hull3.build(pts2);
        if (second.isEmpty()) return fail(r, Error.FLAT);
        // 바닥과, 밑단에서 골조 밑으로 말려 들어가는 아래 향한 조각은 버린다 (천 끝은 골조를 따라 끝난다)
        for (Hull3.Face f : second)
            if (usable(f, virt) && !bottom(f, hems) && f.n().y() > -0.3 && Hull3.area(f.poly()) > 1e-4) r.faces.add(f);
        double area = 0;
        for (Hull3.Face f : r.faces) area += Hull3.area(f.poly());
        r.area = area;
        return r;
    }

    /** 가상 점이 끼지 않은 면. */
    private static boolean usable(Hull3.Face f, V3 virt) {
        for (V3 p : f.poly()) if (p.dist(virt) < 1e-9) return false;
        return true;
    }

    /** 밑단 점만으로 된 아래를 향한 면 = 바닥. */
    private static boolean bottom(Hull3.Face f, List<V3> hems) {
        if (f.n().y() >= 0) return false;
        for (V3 p : f.poly()) {
            boolean isHem = false;
            for (V3 hm : hems) if (hm.dist(p) < 1e-9) isHem = true;
            if (!isHem) return false;
        }
        return true;
    }

    private static Result fail(Result r, Error e) {
        r.error = e;
        return r;
    }

    static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
