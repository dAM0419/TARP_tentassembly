package com.tarp.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 점들 위로 팽팽하게 당긴 막 = 위쪽 볼록 껍질 (평면 몇 장). 3차원 볼록 껍질을 점 하나씩 넣어 만들고(증분법),
 * 위를 향한 면만 남긴 뒤 같은 평면끼리 묶어 다각형으로 돌려준다.
 * 높이 함수는 그 평면들의 최솟값이다 (오목 함수 = 받치는 평면들의 아래 포락).
 */
public final class Envelope {
    /** 평면 y = (d - nx·x - nz·z) / ny, 법선은 위쪽(ny &gt; 0)으로 맞춘다. */
    public record Plane(double nx, double ny, double nz, double d) {
        public double y(double x, double z) {
            return (d - nx * x - nz * z) / ny;
        }

        boolean same(Plane o) {
            return Math.abs(nx - o.nx) < 1e-5 && Math.abs(ny - o.ny) < 1e-5 && Math.abs(nz - o.nz) < 1e-5 && Math.abs(d - o.d) < 1e-4;
        }
    }

    public final List<Plane> planes = new ArrayList<>();

    private Envelope() {}

    public double height(double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (Plane p : planes) best = Math.min(best, p.y(x, z));
        return best;
    }

    public boolean empty() {
        return planes.isEmpty();
    }

    // ------------------------------------------------------------------ 증분 볼록 껍질

    private static final double EPS = 1e-7;

    private static final class Face {
        final int a, b, c;
        final double nx, ny, nz, d;
        boolean dead;

        Face(List<V3> p, int a, int b, int c) {
            this.a = a;
            this.b = b;
            this.c = c;
            V3 pa = p.get(a), pb = p.get(b), pc = p.get(c);
            double ux = pb.x() - pa.x(), uy = pb.y() - pa.y(), uz = pb.z() - pa.z();
            double vx = pc.x() - pa.x(), vy = pc.y() - pa.y(), vz = pc.z() - pa.z();
            double x = uy * vz - uz * vy, y = uz * vx - ux * vz, z = ux * vy - uy * vx;
            double l = Math.sqrt(x * x + y * y + z * z);
            if (l < 1e-15) l = 1e-15;
            nx = x / l;
            ny = y / l;
            nz = z / l;
            d = nx * pa.x() + ny * pa.y() + nz * pa.z();
        }

        double dist(V3 q) {
            return nx * q.x() + ny * q.y() + nz * q.z() - d;
        }
    }

    public static Envelope of(List<V3> input) {
        Envelope env = new Envelope();
        List<V3> pts = dedupe(input);
        if (pts.size() < 3) return env;
        // 모든 점보다 한참 아래의 가상 점: 점들이 한 평면에 있어도 입체가 되게 한다. 위쪽 면에는 끼지 않는다.
        double cx = 0, cz = 0, minY = Double.POSITIVE_INFINITY;
        for (V3 p : pts) {
            cx += p.x();
            cz += p.z();
            minY = Math.min(minY, p.y());
        }
        cx /= pts.size();
        cz /= pts.size();
        int virt = pts.size();
        List<V3> p = new ArrayList<>(pts);
        p.add(new V3(cx, minY - 1000, cz));

        // 처음 사면체: 수평으로 가장 먼 두 점, 그 선에서 가장 먼 점, 가상 점
        int i0 = 0, i1 = -1, i2 = -1;
        double best = -1;
        for (int i = 0; i < virt; i++) {
            double dd = p.get(i).hdist(p.get(i0));
            if (dd > best) {
                best = dd;
                i1 = i;
            }
        }
        best = -1;
        for (int i = 0; i < virt; i++) {
            double dd = p.get(i).hdist(p.get(i1));
            if (dd > best) {
                best = dd;
                i0 = i;
            }
        }
        best = -1;
        V3 a0 = p.get(i0), a1 = p.get(i1);
        for (int i = 0; i < virt; i++) {
            V3 q = p.get(i);
            double cr = Math.abs((a1.x() - a0.x()) * (q.z() - a0.z()) - (a1.z() - a0.z()) * (q.x() - a0.x()));
            if (cr > best) {
                best = cr;
                i2 = i;
            }
        }
        if (best < 1e-9) return env;

        List<Face> faces = new ArrayList<>();
        int[][] tet = {{i0, i1, i2}, {i0, i1, virt}, {i1, i2, virt}, {i2, i0, virt}};
        V3 inside = p.get(i0).add(p.get(i1)).add(p.get(i2)).add(p.get(virt)).mul(0.25);
        for (int[] t : tet) {
            Face f = new Face(p, t[0], t[1], t[2]);
            if (f.dist(inside) > 0) f = new Face(p, t[0], t[2], t[1]);
            faces.add(f);
        }
        Set<Integer> used = new HashSet<>(List.of(i0, i1, i2, virt));
        for (int i = 0; i < virt; i++) {
            if (used.contains(i)) continue;
            V3 q = p.get(i);
            List<Face> visible = new ArrayList<>();
            for (Face f : faces) if (!f.dead && f.dist(q) > EPS) visible.add(f);
            if (visible.isEmpty()) continue;
            // 지평선: 보이는 면들의 변 중 한 번만 나오는 변 (방향 유지)
            Map<Long, int[]> edges = new HashMap<>();
            for (Face f : visible) {
                f.dead = true;
                int[][] es = {{f.a, f.b}, {f.b, f.c}, {f.c, f.a}};
                for (int[] e : es) {
                    long rev = key(e[1], e[0]);
                    if (edges.containsKey(rev)) edges.remove(rev);
                    else edges.put(key(e[0], e[1]), e);
                }
            }
            for (int[] e : edges.values()) faces.add(new Face(p, e[0], e[1], i));
            faces.removeIf(f -> f.dead);
        }

        // 위쪽 면 → 평면 (0.4.10: 쓰이지 않던 면 다각형 만들기는 뺐다)
        for (Face f : faces) {
            if (f.a == virt || f.b == virt || f.c == virt || f.ny < 1e-6) continue;
            Plane pl = new Plane(f.nx, f.ny, f.nz, f.d);
            boolean dup = false;
            for (Plane q : env.planes) if (q.same(pl)) dup = true;
            if (!dup) env.planes.add(pl);
        }
        return env;
    }

    private static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    private static List<V3> dedupe(List<V3> in) {
        List<V3> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (V3 v : in) {
            long k = Math.round(v.x() * 4096) * 73856093L ^ Math.round(v.y() * 4096) * 19349663L ^ Math.round(v.z() * 4096) * 83492791L;
            if (seen.add(k)) out.add(v);
        }
        return out;
    }

    // ------------------------------------------------------------------ 다각형 자르기

    /** 선형 함수 f 가 0 이상인 쪽만 남긴다 (볼록 다각형 → 볼록 다각형). */
    public interface Lin {
        double at(V3 p);
    }

    public static List<V3> clip(List<V3> poly, Lin f) {
        List<V3> out = new ArrayList<>();
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            V3 a = poly.get(i), b = poly.get((i + 1) % n);
            double fa = f.at(a), fb = f.at(b);
            if (fa >= 0) out.add(a);
            if ((fa >= 0) != (fb >= 0)) out.add(a.lerp(b, fa / (fa - fb)));
        }
        return out;
    }
}
