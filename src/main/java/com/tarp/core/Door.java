package com.tarp.core;

import java.util.List;

/**
 * 입구 (설계서 '입구'). 축 방향 변 하나의 가운데에, 바닥에서 2칸 높이까지 절개한다.
 * 위에서 보면 항상 축 정렬 직사각형이고, 경사면이면 바닥+2칸 등고선까지 안쪽으로 파고든다.
 */
public final class Door {
    public enum Error { NOT_AXIS, TOO_SHORT, TOO_LOW, POLE_INSIDE }

    public final int edge;
    public final Membrane.Edge e;
    /** 변 위 구간 [s0, s1]. */
    public final double s0, s1;
    public final double floor, top;
    /** 열마다(간격 h/2) 안쪽으로 파고드는 깊이. */
    final double[] depth;
    final double colStep;
    public final double maxDepth;

    private Door(int edge, Membrane.Edge e, double s0, double s1, double floor, double[] depth, double colStep) {
        this.edge = edge;
        this.e = e;
        this.s0 = s0;
        this.s1 = s1;
        this.floor = floor;
        this.top = floor + Spec.DOOR_HEIGHT;
        this.depth = depth;
        this.colStep = colStep;
        double m = 0;
        for (double d : depth) m = Math.max(m, d);
        this.maxDepth = m;
    }

    /** 결과: 성공이면 door, 아니면 error. */
    public record Plan(Door door, Error error) {
        public boolean ok() {
            return door != null;
        }
    }

    /** 변 칸 수(묶는 점 사이 거리 − 1)에 따른 입구 폭. */
    public static int width(int cells) {
        if (cells <= 0) return 0;
        if (cells <= 2) return cells;
        if (cells % 2 == 0) return 2;
        return cells == 3 ? 1 : 3;
    }

    public static Plan plan(Membrane.Result r, int edge, List<Membrane.Obstacle> obstacles) {
        Membrane.Edge e = r.edges[edge];
        if (e.axis() < 0) return new Plan(null, Error.NOT_AXIS);
        int cells = (int) Math.round(e.len) - 1;
        int w = width(cells);
        if (w <= 0) return new Plan(null, Error.TOO_SHORT);
        double mid = e.len / 2, s0 = mid - w / 2.0, s1 = mid + w / 2.0;
        double floor = Math.floor(Math.min(e.tie(s0), e.tie(s1)) + 1e-6);
        double top = floor + Spec.DOOR_HEIGHT;
        double step = r.h / 2;
        int cols = Math.max(1, (int) Math.round((s1 - s0) / step));
        double[] depth = new double[cols];
        for (int c = 0; c < cols; c++) {
            double s = s0 + (c + 0.5) * (s1 - s0) / cols;
            double found = -1;
            for (double d = step / 2; d <= Spec.MAX_SPAN; d += step) {
                double x = e.px(s) + e.nx * d, z = e.pz(s) + e.nz * d;
                if (!r.insidePoly(x, z)) break;
                double u = r.height(x, z);
                if (!Double.isNaN(u) && u >= top) {
                    found = d;
                    break;
                }
            }
            if (found < 0) return new Plan(null, Error.TOO_LOW);
            depth[c] = found;
        }
        Door door = new Door(edge, e, s0, s1, floor, depth, (s1 - s0) / cols);
        for (Membrane.Obstacle o : obstacles) {
            if (o.frame() && door.crossedBy(o.a(), o.b())) return new Plan(null, Error.POLE_INSIDE);
        }
        return new Plan(door, null);
    }

    /** 변 기준 좌표: {s, 안쪽 거리 d}. */
    public double[] local(double x, double z) {
        double rx = x - e.a.x(), rz = z - e.a.z();
        return new double[]{rx * e.dx + rz * e.dz, rx * e.nx + rz * e.nz};
    }

    public double depthAt(double s) {
        int c = (int) Math.floor((s - s0) / colStep);
        if (c < 0 || c >= depth.length) return -1;
        return depth[c];
    }

    public boolean inSpan(double s) {
        return s >= s0 - 1e-6 && s <= s1 + 1e-6;
    }

    /** 지붕(안쪽 면) 점이 절개 구간에 드는가. */
    public boolean cutsRoof(double x, double y, double z) {
        double[] l = local(x, z);
        if (!inSpan(l[0]) || y >= top) return false;
        double d = depthAt(Math.min(l[0], s1 - 1e-6));
        return d >= 0 && l[1] >= -0.6 && l[1] <= d + 1e-6;
    }

    /** 두루마리 위치: 변 위 s 의 안쪽 거리. */
    public double rollDepth(double s) {
        double d = depthAt(Math.min(Math.max(s, s0), s1 - 1e-6));
        return Math.max(0, d);
    }

    /** 선분이 입구 공간을 가로지르는가. */
    boolean crossedBy(V3 a, V3 b) {
        double[] la = local(a.x(), a.z()), lb = local(b.x(), b.z());
        double[] p0 = {la[0], la[1], a.y()}, p1 = {lb[0], lb[1], b.y()};
        double[] lo = {s0 + 0.05, -0.15, floor + 0.05}, hi = {s1 - 0.05, maxDepth + 0.3, top - 0.05};
        double t0 = 0, t1 = 1;
        for (int i = 0; i < 3; i++) {
            double d = p1[i] - p0[i];
            if (Math.abs(d) < 1e-12) {
                if (p0[i] < lo[i] || p0[i] > hi[i]) return false;
                continue;
            }
            double ta = (lo[i] - p0[i]) / d, tb = (hi[i] - p0[i]) / d;
            if (ta > tb) {
                double t = ta;
                ta = tb;
                tb = t;
            }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) return false;
        }
        return true;
    }
}
