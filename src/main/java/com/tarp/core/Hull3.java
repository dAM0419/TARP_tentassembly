package com.tarp.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 3차원 볼록 껍질 (점 하나씩 넣는 증분법). 면은 바깥을 향하는 법선을 갖고, 같은 평면의 삼각형은 볼록 다각형 하나로 묶는다.
 * 다각형 꼭짓점 순서는 바깥에서 보아 반시계 (= 법선 쪽이 앞면).
 */
public final class Hull3 {
    private Hull3() {}

    public record Face(V3 n, double d, List<V3> poly, Set<Integer> verts) {
        public double dist(V3 p) {
            return n.dot(p) - d;
        }
    }

    private static final double EPS = 1e-7;

    private static final class Tri {
        final int a, b, c;
        final double nx, ny, nz, d;
        boolean dead;

        Tri(List<V3> p, int a, int b, int c) {
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

    /** 껍질의 면들. 점이 4개 미만이거나 모두 한 평면이면 빈 목록. */
    public static List<Face> build(List<V3> pts) {
        int n = pts.size();
        if (n < 4) return List.of();
        // 처음 사면체
        int i0 = 0, i1 = -1, i2 = -1, i3 = -1;
        double best = -1;
        for (int i = 0; i < n; i++) {
            double dd = pts.get(i).dist(pts.get(i0));
            if (dd > best) {
                best = dd;
                i1 = i;
            }
        }
        best = -1;
        for (int i = 0; i < n; i++) {
            double dd = pts.get(i).dist(pts.get(i1));
            if (dd > best) {
                best = dd;
                i0 = i;
            }
        }
        V3 a = pts.get(i0), b = pts.get(i1);
        V3 ab = b.sub(a);
        best = -1;
        for (int i = 0; i < n; i++) {
            V3 c = cross(ab, pts.get(i).sub(a));
            if (c.len() > best) {
                best = c.len();
                i2 = i;
            }
        }
        if (best < 1e-9) return List.of();
        V3 nrm = cross(ab, pts.get(i2).sub(a));
        nrm = nrm.mul(1 / nrm.len());
        best = -1;
        for (int i = 0; i < n; i++) {
            double dd = Math.abs(nrm.dot(pts.get(i).sub(a)));
            if (dd > best) {
                best = dd;
                i3 = i;
            }
        }
        if (best < 1e-7) return List.of();

        List<Tri> tris = new ArrayList<>();
        int[][] tet = {{i0, i1, i2}, {i0, i1, i3}, {i1, i2, i3}, {i2, i0, i3}};
        V3 inside = pts.get(i0).add(pts.get(i1)).add(pts.get(i2)).add(pts.get(i3)).mul(0.25);
        for (int[] t : tet) {
            Tri f = new Tri(pts, t[0], t[1], t[2]);
            if (f.dist(inside) > 0) f = new Tri(pts, t[0], t[2], t[1]);
            tris.add(f);
        }
        Set<Integer> used = new HashSet<>(List.of(i0, i1, i2, i3));
        for (int i = 0; i < n; i++) {
            if (used.contains(i)) continue;
            V3 q = pts.get(i);
            List<Tri> visible = new ArrayList<>();
            for (Tri f : tris) if (!f.dead && f.dist(q) > EPS) visible.add(f);
            if (visible.isEmpty()) continue;
            Map<Long, int[]> edges = new HashMap<>();
            for (Tri f : visible) {
                f.dead = true;
                int[][] es = {{f.a, f.b}, {f.b, f.c}, {f.c, f.a}};
                for (int[] e : es) {
                    long rev = key(e[1], e[0]);
                    if (edges.containsKey(rev)) edges.remove(rev);
                    else edges.put(key(e[0], e[1]), e);
                }
            }
            for (int[] e : edges.values()) tris.add(new Tri(pts, e[0], e[1], i));
            tris.removeIf(f -> f.dead);
        }

        // 같은 평면끼리 묶기
        List<Face> out = new ArrayList<>();
        List<double[]> planes = new ArrayList<>();
        List<Set<Integer>> groups = new ArrayList<>();
        for (Tri f : tris) {
            int g = -1;
            for (int k = 0; k < planes.size(); k++) {
                double[] p = planes.get(k);
                if (Math.abs(p[0] - f.nx) < 1e-5 && Math.abs(p[1] - f.ny) < 1e-5 && Math.abs(p[2] - f.nz) < 1e-5 && Math.abs(p[3] - f.d) < 1e-4) {
                    g = k;
                    break;
                }
            }
            if (g < 0) {
                planes.add(new double[]{f.nx, f.ny, f.nz, f.d});
                groups.add(new HashSet<>());
                g = planes.size() - 1;
            }
            groups.get(g).add(f.a);
            groups.get(g).add(f.b);
            groups.get(g).add(f.c);
        }
        for (int k = 0; k < planes.size(); k++) {
            double[] p = planes.get(k);
            V3 n3 = new V3(p[0], p[1], p[2]);
            List<V3> poly = planarHull(pts, groups.get(k), n3);
            if (poly.size() >= 3) out.add(new Face(n3, p[3], poly, groups.get(k)));
        }
        return out;
    }

    private static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    public static V3 cross(V3 a, V3 b) {
        return new V3(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }

    /** 평면 위 점들의 볼록 다각형, 법선 쪽에서 보아 반시계. */
    static List<V3> planarHull(List<V3> p, Set<Integer> idx, V3 n) {
        V3 ref = Math.abs(n.y()) < 0.9 ? new V3(0, 1, 0) : new V3(1, 0, 0);
        V3 u = cross(ref, n);
        u = u.mul(1 / u.len());
        V3 w = cross(n, u);
        List<double[]> q = new ArrayList<>();
        for (int i : idx) {
            V3 v = p.get(i);
            q.add(new double[]{v.dot(u), v.dot(w), i});
        }
        q.sort((x, y) -> x[0] != y[0] ? Double.compare(x[0], y[0]) : Double.compare(x[1], y[1]));
        int m = q.size();
        if (m < 3) return List.of();
        double[][] h = new double[2 * m][];
        int k = 0;
        for (double[] r : q) {
            while (k >= 2 && cr(h[k - 2], h[k - 1], r) <= 1e-12) k--;
            h[k++] = r;
        }
        for (int i = m - 2, t = k + 1; i >= 0; i--) {
            double[] r = q.get(i);
            while (k >= t && cr(h[k - 2], h[k - 1], r) <= 1e-12) k--;
            h[k++] = r;
        }
        List<V3> out = new ArrayList<>();
        for (int i = 0; i < k - 1; i++) out.add(p.get((int) h[i][2]));
        // (u, w, n) 은 오른손 좌표계 → 반시계 = 법선 쪽에서 보아 반시계
        return out;
    }

    private static double cr(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** 볼록 다각형 넓이. */
    public static double area(List<V3> poly) {
        V3 s = V3.ZERO;
        for (int i = 1; i + 1 < poly.size(); i++) s = s.add(cross(poly.get(i).sub(poly.get(0)), poly.get(i + 1).sub(poly.get(0))));
        return s.len() / 2;
    }
}
