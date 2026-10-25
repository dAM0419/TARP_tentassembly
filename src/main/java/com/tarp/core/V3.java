package com.tarp.core;

/** 마인크래프트와 무관한 3차원 점·벡터. 좌표는 월드 블록 단위. */
public record V3(double x, double y, double z) {
    public static final V3 ZERO = new V3(0, 0, 0);

    public V3 add(V3 o) {
        return new V3(x + o.x, y + o.y, z + o.z);
    }

    public V3 sub(V3 o) {
        return new V3(x - o.x, y - o.y, z - o.z);
    }

    public V3 mul(double k) {
        return new V3(x * k, y * k, z * k);
    }

    public double dot(V3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public double len() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double dist(V3 o) {
        return sub(o).len();
    }

    /** 수평(xz) 거리. */
    public double hdist(V3 o) {
        double dx = x - o.x, dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public V3 lerp(V3 o, double t) {
        return new V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    @Override
    public String toString() {
        return String.format("(%.3f, %.3f, %.3f)", x, y, z);
    }
}
