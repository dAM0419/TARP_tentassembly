package com.tarp.core;

import java.util.List;

/** 수평면(xz) 다각형 도우미. 점은 V3 의 x, z 를 쓴다. */
public final class Polygon {
    private Polygon() {}

    /** 부호 있는 넓이. 양수면 반시계(x→z 기준). */
    public static double signedArea(List<V3> p) {
        double s = 0;
        for (int i = 0; i < p.size(); i++) {
            V3 a = p.get(i), b = p.get((i + 1) % p.size());
            s += a.x() * b.z() - b.x() * a.z();
        }
        return s / 2;
    }

    public static boolean contains(List<V3> p, double x, double z) {
        boolean in = false;
        for (int i = 0, j = p.size() - 1; i < p.size(); j = i++) {
            double xi = p.get(i).x(), zi = p.get(i).z(), xj = p.get(j).x(), zj = p.get(j).z();
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) in = !in;
        }
        return in;
    }

    /** 변끼리 겹치거나 꼬이지 않은 단순 다각형인가. */
    public static boolean simple(List<V3> p) {
        int n = p.size();
        if (n < 3) return false;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (p.get(i).hdist(p.get(j)) < 1e-6) return false;
                if (j == i + 1 || (i == 0 && j == n - 1)) continue;
                if (cross(p.get(i), p.get((i + 1) % n), p.get(j), p.get((j + 1) % n))) return false;
            }
        }
        return true;
    }

    private static boolean cross(V3 a, V3 b, V3 c, V3 d) {
        double d1 = orient(c, d, a), d2 = orient(c, d, b), d3 = orient(a, b, c), d4 = orient(a, b, d);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true;
        return (Math.abs(d1) < 1e-9 && onSeg(c, d, a)) || (Math.abs(d2) < 1e-9 && onSeg(c, d, b))
                || (Math.abs(d3) < 1e-9 && onSeg(a, b, c)) || (Math.abs(d4) < 1e-9 && onSeg(a, b, d));
    }

    private static double orient(V3 a, V3 b, V3 c) {
        return (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
    }

    private static boolean onSeg(V3 a, V3 b, V3 p) {
        return Math.min(a.x(), b.x()) - 1e-9 <= p.x() && p.x() <= Math.max(a.x(), b.x()) + 1e-9
                && Math.min(a.z(), b.z()) - 1e-9 <= p.z() && p.z() <= Math.max(a.z(), b.z()) + 1e-9;
    }

    /** 점에서 가장 가까운 둘레 위치: {변 번호, 변 위 거리 s, 수평 거리}. */
    public static double[] nearestEdge(List<V3> p, double x, double z) {
        double best = Double.POSITIVE_INFINITY, bestS = 0;
        int bestE = 0;
        for (int i = 0; i < p.size(); i++) {
            V3 a = p.get(i), b = p.get((i + 1) % p.size());
            double ex = b.x() - a.x(), ez = b.z() - a.z();
            double l2 = ex * ex + ez * ez;
            double t = l2 < 1e-12 ? 0 : ((x - a.x()) * ex + (z - a.z()) * ez) / l2;
            t = Math.max(0, Math.min(1, t));
            double px = a.x() + ex * t, pz = a.z() + ez * t;
            double d = Math.hypot(x - px, z - pz);
            if (d < best) {
                best = d;
                bestE = i;
                bestS = t * Math.sqrt(l2);
            }
        }
        return new double[]{bestE, bestS, best};
    }
}
