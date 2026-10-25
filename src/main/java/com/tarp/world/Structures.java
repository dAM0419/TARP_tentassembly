package com.tarp.world;

import com.tarp.core.Raster;
import com.tarp.core.Stability;
import com.tarp.core.V3;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 한 차원의 지지대·노끈·방수포. 서버 저장소(TarpData)와 클라이언트 사본(ClientStore)이 같이 쓴다. */
public final class Structures {
    public final Map<Integer, Stability.Pole> poles = new LinkedHashMap<>();
    public final Map<Integer, Stability.Rope> ropes = new LinkedHashMap<>();
    public final Map<Integer, TarpRec> tarps = new LinkedHashMap<>();
    /** 칸(BlockPos.asLong) → 지나가는 지지대 id. */
    private final Map<Long, Set<Integer>> cellIndex = new HashMap<>();
    /** 바뀔 때마다 오른다 (클라이언트 메시 다시 굽기). */
    public int version;

    public void clear() {
        poles.clear();
        ropes.clear();
        tarps.clear();
        cellIndex.clear();
        version++;
    }

    public void addPole(Stability.Pole p) {
        poles.put(p.id(), p);
        for (Raster.Cell c : Raster.cells(p.a(), p.b())) cellIndex.computeIfAbsent(BlockPos.asLong(c.x(), c.y(), c.z()), k -> new HashSet<>()).add(p.id());
        version++;
    }

    public Stability.Pole removePole(int id) {
        Stability.Pole p = poles.remove(id);
        if (p != null) {
            for (Raster.Cell c : Raster.cells(p.a(), p.b())) {
                long key = BlockPos.asLong(c.x(), c.y(), c.z());
                Set<Integer> s = cellIndex.get(key);
                if (s != null) {
                    s.remove(id);
                    if (s.isEmpty()) cellIndex.remove(key);
                }
            }
            version++;
        }
        return p;
    }

    public void addRope(Stability.Rope r) {
        ropes.put(r.id(), r);
        version++;
    }

    public Stability.Rope removeRope(int id) {
        Stability.Rope r = ropes.remove(id);
        if (r != null) version++;
        return r;
    }

    public void addTarp(TarpRec t) {
        tarps.put(t.id, t);
        version++;
    }

    public TarpRec removeTarp(int id) {
        TarpRec t = tarps.remove(id);
        if (t != null) version++;
        return t;
    }

    public Set<Integer> polesAt(long cell) {
        Set<Integer> s = cellIndex.get(cell);
        return s == null ? Set.of() : s;
    }

    /** 칸과 그 26 이웃을 지나는 지지대. */
    public Set<Integer> polesNear(int x, int y, int z) {
        Set<Integer> out = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) out.addAll(polesAt(BlockPos.asLong(x + dx, y + dy, z + dz)));
        return out;
    }

    /** 이 점이 어떤 지지대의 끝점인가. */
    public boolean isPoleEnd(V3 p) {
        for (int id : polesNear((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()))) {
            Stability.Pole q = poles.get(id);
            if (q.a().dist(p) < Stability.TOUCH || q.b().dist(p) < Stability.TOUCH) return true;
        }
        return false;
    }

    /** 서로 닿은(끝점·교차) 지지대 덩어리. 시작 지지대들에서 넓혀 간다. */
    public List<Stability.Pole> component(Collection<Integer> seeds) {
        Set<Integer> seen = new HashSet<>();
        List<Integer> queue = new ArrayList<>();
        for (int s : seeds) if (poles.containsKey(s) && seen.add(s)) queue.add(s);
        for (int qi = 0; qi < queue.size(); qi++) {
            Stability.Pole p = poles.get(queue.get(qi));
            for (Raster.Cell c : Raster.cells(p.a(), p.b())) {
                for (int other : polesNear(c.x(), c.y(), c.z())) {
                    if (seen.contains(other)) continue;
                    if (touches(p, poles.get(other))) {
                        seen.add(other);
                        queue.add(other);
                    }
                }
            }
        }
        List<Stability.Pole> out = new ArrayList<>();
        for (int id : queue) out.add(poles.get(id));
        return out;
    }

    public static boolean touches(Stability.Pole p, Stability.Pole q) {
        return segDist(p.a(), p.b(), q.a(), q.b()) < Stability.TOUCH;
    }

    /**
     * 두 선분 사이 최단 거리 (해석해, 0.3.12). 전엔 50점 표본이라 긴 지지대가 가운데서 엇갈리면 표본 간격(최대 0.67칸)이
     * 닿음 기준 0.08칸보다 커서 놓쳤다 — 쓰러짐 판정(Stability)은 해석해로 닿음을 보는데 덩어리 모으기가 그 지지대를 빠뜨렸다.
     */
    public static double segDist(V3 p0, V3 p1, V3 q0, V3 q1) {
        double[] st = Stability.closest(p0, p1, q0, q1);
        return p0.lerp(p1, st[0]).dist(q0.lerp(q1, st[1]));
    }

    public static double pointSeg(V3 p, V3 a, V3 b) {
        V3 ab = b.sub(a);
        double l2 = ab.dot(ab);
        double t = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, p.sub(a).dot(ab) / l2));
        return p.dist(a.add(ab.mul(t)));
    }

    /** 끝점이 이 점에 닿은 노끈. */
    public List<Stability.Rope> ropesAt(V3 p) {
        List<Stability.Rope> out = new ArrayList<>();
        for (Stability.Rope r : ropes.values()) if (r.a().dist(p) < Stability.TOUCH || r.b().dist(p) < Stability.TOUCH) out.add(r);
        return out;
    }
}
