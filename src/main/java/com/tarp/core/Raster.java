package com.tarp.core;

import java.util.ArrayList;
import java.util.List;

/** 선분이 지나가는 블록 칸 (Amanatides–Woo). 끝점이 칸 경계에 닿기만 하는 칸은 세지 않는다. */
public final class Raster {
    private Raster() {}

    /** 칸 하나와 그 칸 안의 선분 구간 [t0, t1] (원래 선분 기준 0~1). */
    public record Cell(int x, int y, int z, double t0, double t1) {}

    public static List<Cell> cells(V3 a, V3 b) {
        List<Cell> out = new ArrayList<>();
        double len = a.dist(b);
        if (len < 1e-9) {
            out.add(new Cell((int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z()), 0, 1));
            return out;
        }
        double shrink = Math.min(1e-4, len / 4) / len;
        double sx = a.x(), sy = a.y(), sz = a.z();
        double dx = b.x() - sx, dy = b.y() - sy, dz = b.z() - sz;
        double ts = shrink, te = 1 - shrink;
        int x = (int) Math.floor(sx + dx * ts), y = (int) Math.floor(sy + dy * ts), z = (int) Math.floor(sz + dz * ts);
        int ex = (int) Math.floor(sx + dx * te), ey = (int) Math.floor(sy + dy * te), ez = (int) Math.floor(sz + dz * te);
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tMaxX = boundary(sx, dx, x, stepX), tMaxY = boundary(sy, dy, y, stepY), tMaxZ = boundary(sz, dz, z, stepZ);
        double tDX = stepX != 0 ? 1 / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tDY = stepY != 0 ? 1 / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double tDZ = stepZ != 0 ? 1 / Math.abs(dz) : Double.POSITIVE_INFINITY;
        double tEnter = 0;
        for (int guard = 0; guard < 4096; guard++) {
            boolean last = x == ex && y == ey && z == ez;
            double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (last || next >= te) {
                out.add(new Cell(x, y, z, tEnter, 1));
                break;
            }
            out.add(new Cell(x, y, z, tEnter, next));
            tEnter = next;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                tMaxX += tDX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                tMaxY += tDY;
            } else {
                z += stepZ;
                tMaxZ += tDZ;
            }
        }
        return out;
    }

    private static double boundary(double s, double d, int cell, int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double edge = step > 0 ? cell + 1 : cell;
        return (edge - s) / d;
    }

    /** 첫 칸이 (x, y, z) 인가. cells(a, b).get(0) 과 같은 판정이되 목록을 만들지 않는다. */
    public static boolean firstIs(V3 a, V3 b, int x, int y, int z) {
        double len = a.dist(b);
        double t = len < 1e-9 ? 0 : Math.min(1e-4, len / 4) / len;
        return (int) Math.floor(a.x() + (b.x() - a.x()) * t) == x && (int) Math.floor(a.y() + (b.y() - a.y()) * t) == y
                && (int) Math.floor(a.z() + (b.z() - a.z()) * t) == z;
    }

    /** 점유 칸 수 = 소모 개수. */
    public static int count(V3 a, V3 b) {
        return cells(a, b).size();
    }
}
