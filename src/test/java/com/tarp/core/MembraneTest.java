package com.tarp.core;

import com.tarp.core.Membrane.Obstacle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MembraneTest {
    static V3 p(double x, double y, double z) {
        return new V3(x, y, z);
    }

    static Obstacle pole(V3 a, V3 b) {
        return new Obstacle(a, b, Spec.POLE_CLEAR, true);
    }

    /** 원인: ½칸 단위 끝점은 길이 2라도 칸 3개에 걸친다. 소모는 칸 수가 아니라 길이로 센다. */
    @Test
    void poleCostByLength() {
        V3 a = p(0.5, 64.5, 0.5), b = p(0.5, 66.5, 0.5);
        assertEquals(3, Raster.count(a, b));
        assertEquals(1, Spec.poleCost(a.dist(b)));
        assertEquals(1, Spec.poleCost(0.5));
        assertEquals(2, Spec.poleCost(2.5));
        assertEquals(8, Spec.poleCost(16));
    }

    @Test
    void rasterCounts() {
        assertEquals(3, Raster.count(p(0.5, 64, 0.5), p(0.5, 67, 0.5)));
        assertEquals(5, Raster.count(p(0.5, 64, 0.5), p(4.5, 64, 0.5)));
        assertEquals(2, Raster.count(p(0.5, 64, 0.5), p(0.5, 65.5, 0.5)));
        // 블록 옆면 중앙에서 시작하는 수평 지지대는 그 블록 칸을 세지 않는다
        assertEquals(2, Raster.count(p(1, 64.5, 0.5), p(2.5, 64.5, 0.5)));
        assertTrue(Raster.count(p(0.5, 64, 0.5), p(2.5, 66, 0.5)) >= 3);
    }

    /** 바닥 3×3 피라미드: 모서리 말뚝 4, 가운데 수직 3칸. */
    static Membrane.Result pyramid(double poleTop) {
        List<V3> ties = List.of(p(0.5, 64.5, 0.5), p(4.5, 64.5, 0.5), p(4.5, 64.5, 4.5), p(0.5, 64.5, 4.5));
        return Membrane.compute(ties, List.of(pole(p(2.5, 64, 2.5), p(2.5, poleTop, 2.5))));
    }

    @Test
    void pyramidFitsSmallTarp() {
        Membrane.Result r = pyramid(67);
        assertTrue(r.ok());
        assertTrue(r.exposed.isEmpty());
        assertTrue(r.area() > 16 && r.area() <= Spec.TARP_AREA[0], "area " + r.area());
        assertEquals(0, Spec.sizeFor(r.area()));
        // 꼭대기는 지지대 위로 뜬다
        assertEquals(67 + Spec.POLE_CLEAR, r.height(2.5, 2.5), 0.15);
        // 0.2.0: 면은 빳빳한 평면이다 (꼭대기 모따기 띠 때문에 직선보다 아주 조금만 낮다)
        double mid = r.height(1.5, 2.5);
        double straight = 64.5 + Spec.ALPHA + (67 + Spec.POLE_CLEAR - 64.5 - Spec.ALPHA) * 0.5;
        assertEquals(straight, mid, 0.15, "flat face");
        // 폴리곤 최소화: 네 면 + 꼭대기 모따기
        assertTrue(r.faces.size() <= 12, "faces " + r.faces.size());
        // 0.3.0: 수직 커튼 없음 — 모든 면이 경사면
        for (Hull3.Face f : r.faces) assertTrue(f.n().y() > 0.3, "steep face " + f.n());
    }

    @Test
    void clockwiseInputIsReversed() {
        List<V3> ties = List.of(p(0.5, 64.5, 0.5), p(0.5, 64.5, 4.5), p(4.5, 64.5, 4.5), p(4.5, 64.5, 0.5));
        Membrane.Result r = Membrane.compute(ties, List.of(pole(p(2.5, 64, 2.5), p(2.5, 67, 2.5))));
        assertTrue(r.ok());
        assertTrue(r.reversed);
        assertEquals(pyramid(67).area(), r.area(), 1e-6);
    }

    static List<Obstacle> wedgeObstacles() {
        List<Obstacle> o = new ArrayList<>();
        o.add(pole(p(0.5, 64, 0.5), p(2.5, 67, 0.5)));
        o.add(pole(p(4.5, 64, 0.5), p(2.5, 67, 0.5)));
        o.add(pole(p(0.5, 64, 6.5), p(2.5, 67, 6.5)));
        o.add(pole(p(4.5, 64, 6.5), p(2.5, 67, 6.5)));
        o.add(pole(p(2.5, 67, 0.5), p(2.5, 67, 6.5)));
        return o;
    }

    static Membrane.Result wedge() {
        List<V3> ties = List.of(p(0.5, 64.5, 0.5), p(4.5, 64.5, 0.5), p(4.5, 64.5, 6.5), p(0.5, 64.5, 6.5));
        return Membrane.compute(ties, wedgeObstacles());
    }

    @Test
    void wedgeHasGableCurtains() {
        Membrane.Result r = wedge();
        assertTrue(r.ok());
        // 박공: 앞뒤를 보는 면이 있다 (ㅅ자 다리 평면)
        assertTrue(r.faces.stream().anyMatch(f -> Math.abs(f.n().z()) > 0.9), "gable");
        assertTrue(r.exposed.isEmpty());
        assertTrue(r.area() <= Spec.TARP_AREA[1], "area " + r.area());
        // 용마루 아래 가운데 높이
        assertEquals(67 + Spec.POLE_CLEAR, r.height(2.5, 3.5), 0.2);
        // 경사 두 장 + 용마루 모따기 띠 한 장
        assertTrue(r.faces.size() <= 20, "faces " + r.faces.size());
    }

    @Test
    void wedgeDoorOnGable() {
        Membrane.Result r = wedge();
        int front = -1;
        for (int e = 0; e < r.edges.length; e++) {
            Membrane.Edge ed = r.edges[e];
            if (ed.axis() == 0 && Math.abs(ed.a.z() - 0.5) < 1e-6) front = e;
        }
        assertTrue(front >= 0);
        Door.Plan plan = Door.plan(r, front, wedgeObstacles());
        assertTrue(plan.ok(), String.valueOf(plan.error()));
        assertEquals(64, plan.door().floor, 1e-9);
        assertEquals(66, plan.door().top, 1e-9);
        // 변 길이 4 → 칸 3 → 폭 1
        assertEquals(1, plan.door().s1 - plan.door().s0, 1e-9);
        // 측면 경사 지붕에도 바닥+2칸 등고선까지 파고드는 입구를 낼 수 있다
        int side = -1;
        for (int e = 0; e < r.edges.length; e++) if (r.edges[e].axis() == 1) side = e;
        Door.Plan sidePlan = Door.plan(r, side, wedgeObstacles());
        assertTrue(sidePlan.ok(), String.valueOf(sidePlan.error()));
        assertTrue(sidePlan.door().maxDepth > 0.5);
    }

    @Test
    void lowCoverHasNoDoor() {
        Membrane.Result r = pyramid(65.5);
        assertTrue(r.ok());
        assertEquals(Door.Error.TOO_LOW, Door.plan(r, 0, List.of(pole(p(2.5, 64, 2.5), p(2.5, 65.5, 2.5)))).error());
    }

    @Test
    void centrePostBlocksDoor() {
        List<Obstacle> o = wedgeObstacles();
        o.add(pole(p(2.5, 64, 0.5), p(2.5, 67, 0.5)));
        List<V3> ties = List.of(p(0.5, 64.5, 0.5), p(4.5, 64.5, 0.5), p(4.5, 64.5, 6.5), p(0.5, 64.5, 6.5));
        Membrane.Result r = Membrane.compute(ties, o);
        int front = -1;
        for (int e = 0; e < r.edges.length; e++)
            if (r.edges[e].axis() == 0 && Math.abs(r.edges[e].a.z() - 0.5) < 1e-6) front = e;
        assertEquals(Door.Error.POLE_INSIDE, Door.plan(r, front, o).error());
    }

    @Test
    void doorWidthTable() {
        assertEquals(1, Door.width(1));
        assertEquals(2, Door.width(2));
        assertEquals(1, Door.width(3));
        assertEquals(2, Door.width(4));
        assertEquals(3, Door.width(5));
        assertEquals(2, Door.width(6));
        assertEquals(3, Door.width(7));
    }

    @Test
    void bedouinOpenSides() {
        // 기둥 꼭대기에 묶으면 커튼이 없다
        List<V3> ties = List.of(p(0.5, 67, 0.5), p(6.5, 67, 0.5), p(6.5, 67, 4.5), p(0.5, 67, 4.5));
        List<Obstacle> o = new ArrayList<>();
        for (V3 t : ties) o.add(pole(p(t.x(), 64, t.z()), t));
        o.add(pole(p(3.5, 64, 2.5), p(3.5, 68, 2.5)));
        Membrane.Result r = Membrane.compute(ties, o);
        assertTrue(r.ok());
        // 옆이 열려 있다: 벽처럼 선 면이 없다
        for (Hull3.Face f : r.faces) assertTrue(f.n().y() > 0.5, "wall " + f.n());
        assertTrue(r.exposed.isEmpty());
        // 가운데 기둥에서 가장자리 기둥으로 곧게 내려온다
        assertTrue(r.height(2, 2.5) < 68);
        assertTrue(r.height(2, 2.5) > 67);
    }

    @Test
    void bellWallsFromPerimeterPoles() {
        // 둘레 기둥의 발을 묶는 점으로 쓰면 둘레 벽(커튼)이 생긴다
        List<V3> ties = new ArrayList<>();
        List<Obstacle> o = new ArrayList<>();
        double[][] ring = {{0.5, 0.5}, {4.5, 0.5}, {4.5, 4.5}, {0.5, 4.5}};
        for (double[] c : ring) {
            ties.add(p(c[0], 64, c[1]));
            o.add(pole(p(c[0], 64, c[1]), p(c[0], 65.5, c[1])));
        }
        o.add(pole(p(2.5, 64, 2.5), p(2.5, 68, 2.5)));
        Membrane.Result r = Membrane.compute(ties, o);
        assertTrue(r.ok());
        assertTrue(r.faces.stream().anyMatch(f -> f.n().y() < 0.2), "walls from perimeter poles");
    }

    @Test
    void poleOutsideIsExposed() {
        List<V3> ties = List.of(p(0.5, 64.5, 0.5), p(4.5, 64.5, 0.5), p(4.5, 64.5, 4.5), p(0.5, 64.5, 4.5));
        List<Obstacle> o = List.of(pole(p(2.5, 64, 2.5), p(2.5, 67, 2.5)), pole(p(2.5, 67, 2.5), p(2.5, 67, 7.5)));
        Membrane.Result r = Membrane.compute(ties, o);
        assertFalse(r.exposed.isEmpty());
    }

    /** 티피: 바닥에 띠가 생기지 않고 꼭대기에서 말뚝까지 한 경사로 내려온다. */
    @Test
    void tipiHasNoSkirtBand() {
        List<V3> ring = new ArrayList<>();
        List<Obstacle> o = new ArrayList<>();
        double[][] c = {{3, 0}, {2, 2}, {0, 3}, {-2, 2}, {-3, 0}, {-2, -2}, {0, -3}, {2, -2}};
        for (double[] q : c) {
            ring.add(p(q[0] + 0.5, 64.5, q[1] + 0.5));
            o.add(pole(p(q[0] * 0.8 + 0.5, 64, q[1] * 0.8 + 0.5), p(0.5, 69, 0.5)));
        }
        Membrane.Result r = Membrane.compute(ring, o);
        assertTrue(r.ok());
        for (Hull3.Face f : r.faces) assertTrue(f.n().y() > 0.3, "band " + f.n());
        assertTrue(r.exposed.isEmpty());
    }

    @Test
    void badPolygons() {
        assertEquals(Membrane.Error.TOO_FEW, Membrane.compute(List.of(p(0, 0, 0), p(1, 0, 0)), List.of()).error);
        List<V3> bow = List.of(p(0, 0, 0), p(4, 0, 4), p(4, 0, 0), p(0, 0, 4));
        assertEquals(Membrane.Error.NOT_SIMPLE, Membrane.compute(bow, List.of()).error);
    }

    @Test
    void largeTentIsFast() {
        List<V3> ties = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            double a = Math.PI * 2 * i / 16;
            ties.add(p(Math.floor(10 * Math.cos(a)) + 0.5, 64.5, Math.floor(10 * Math.sin(a)) + 0.5));
        }
        List<Obstacle> o = List.of(pole(p(0.5, 64, 0.5), p(0.5, 72, 0.5)));
        long t0 = System.nanoTime();
        Membrane.Result r = Membrane.compute(ties, o);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r.ok());
        assertTrue(ms < 2000, ms + "ms");
    }
}
