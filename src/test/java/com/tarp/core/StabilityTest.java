package com.tarp.core;

import com.tarp.core.Stability.Pole;
import com.tarp.core.Stability.Rope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 설계서 '판정 예' 표를 그대로 시험한다. 땅은 y < 64 (윗면 y = 64). */
class StabilityTest {
    static final Stability.Ground GROUND = (x, y, z) -> y < 64;

    static V3 p(double x, double y, double z) {
        return new V3(x, y, z);
    }

    static Set<Integer> run(List<Pole> poles, List<Rope> ropes, List<V3> stakes) {
        return Stability.standing(poles, ropes, stakes, GROUND);
    }

    @Test
    void verticalStandsAlone() {
        assertEquals(Set.of(1), run(List.of(new Pole(1, p(0.5, 64, 0.5), p(0.5, 67, 0.5))), List.of(), List.of()));
    }

    @Test
    void floatingVerticalFalls() {
        assertTrue(run(List.of(new Pole(1, p(0.5, 66, 0.5), p(0.5, 68, 0.5))), List.of(), List.of()).isEmpty());
    }

    @Test
    void singleDiagonalFalls() {
        assertTrue(run(List.of(new Pole(1, p(0.5, 64, 0.5), p(2.5, 66, 0.5))), List.of(), List.of()).isEmpty());
    }

    @Test
    void aFrameFallsSideways() {
        List<Pole> poles = List.of(
                new Pole(1, p(0.5, 64, 0.5), p(2.5, 66, 0.5)),
                new Pole(2, p(4.5, 64, 0.5), p(2.5, 66, 0.5)));
        assertTrue(run(poles, List.of(), List.of()).isEmpty());
    }

    static List<Pole> wedgeFrame() {
        List<Pole> poles = new ArrayList<>();
        poles.add(new Pole(1, p(0.5, 64, 0.5), p(2.5, 66, 0.5)));
        poles.add(new Pole(2, p(4.5, 64, 0.5), p(2.5, 66, 0.5)));
        poles.add(new Pole(3, p(0.5, 64, 4.5), p(2.5, 66, 4.5)));
        poles.add(new Pole(4, p(4.5, 64, 4.5), p(2.5, 66, 4.5)));
        poles.add(new Pole(5, p(2.5, 66, 0.5), p(2.5, 66, 4.5)));
        return poles;
    }

    @Test
    void twoAFramesWithRidgeFall() {
        assertTrue(run(wedgeFrame(), List.of(), List.of()).isEmpty());
    }

    @Test
    void wedgeWithGuyLinesStands() {
        List<V3> stakes = List.of(p(2.5, 64.5, -2.5), p(2.5, 64.5, 7.5));
        List<Rope> ropes = List.of(
                new Rope(1, p(2.5, 66, 0.5), stakes.get(0)),
                new Rope(2, p(2.5, 66, 4.5), stakes.get(1)));
        assertEquals(Set.of(1, 2, 3, 4, 5), run(wedgeFrame(), ropes, stakes));
    }

    @Test
    void wedgeWithOneGuyLineFalls() {
        List<V3> stakes = List.of(p(2.5, 64.5, -2.5));
        List<Rope> ropes = List.of(new Rope(1, p(2.5, 66, 0.5), stakes.get(0)));
        assertTrue(run(wedgeFrame(), ropes, stakes).isEmpty());
    }

    static List<Pole> tripod() {
        V3 apex = p(2.5, 67, 1.5);
        return new ArrayList<>(List.of(
                new Pole(1, p(0.5, 64, 0.5), apex),
                new Pole(2, p(4.5, 64, 0.5), apex),
                new Pole(3, p(2.5, 64, 4.5), apex)));
    }

    @Test
    void tripodStands() {
        assertEquals(Set.of(1, 2, 3), run(tripod(), List.of(), List.of()));
    }

    @Test
    void tipiOrderVerticalRemovedStillStands() {
        List<Pole> withVertical = tripod();
        withVertical.add(new Pole(9, p(2.5, 64, 1.5), p(2.5, 67, 1.5)));
        assertEquals(Set.of(1, 2, 3, 9), run(withVertical, List.of(), List.of()));
        // 두 다리만이면 수직이 있을 때만 선다
        List<Pole> two = List.of(withVertical.get(0), withVertical.get(1), withVertical.get(3));
        assertEquals(Set.of(1, 2, 9), run(two, List.of(), List.of()));
        assertTrue(run(List.of(withVertical.get(0), withVertical.get(1)), List.of(), List.of()).isEmpty());
        // 세 다리는 수직을 빼도 선다
        assertEquals(Set.of(1, 2, 3), run(tripod(), List.of(), List.of()));
    }

    @Test
    void beamBetweenPostsStandsCantileverFalls() {
        Pole a = new Pole(1, p(0.5, 64, 0.5), p(0.5, 66, 0.5));
        Pole b = new Pole(2, p(4.5, 64, 0.5), p(4.5, 66, 0.5));
        Pole beam = new Pole(3, p(0.5, 66, 0.5), p(4.5, 66, 0.5));
        assertEquals(Set.of(1, 2, 3), run(List.of(a, b, beam), List.of(), List.of()));
        assertEquals(Set.of(1), run(List.of(a, beam), List.of(), List.of()));
    }

    @Test
    void beamToWallStands() {
        Stability.Ground wall = (x, y, z) -> y < 64 || (x == 5 && y < 70);
        Pole post = new Pole(1, p(0.5, 64, 0.5), p(0.5, 66.5, 0.5));
        Pole beam = new Pole(2, p(0.5, 66.5, 0.5), p(5, 66.5, 0.5));
        assertEquals(Set.of(1, 2), Stability.standing(List.of(post, beam), List.of(), List.of(), wall));
    }

    @Test
    void diagonalLeaningOnWallStands() {
        Stability.Ground wall = (x, y, z) -> y < 64 || (x == 3 && y < 70);
        Pole lean = new Pole(1, p(0.5, 64, 0.5), p(3, 66.5, 0.5));
        assertEquals(Set.of(1), Stability.standing(List.of(lean), List.of(), List.of(), wall));
    }

    @Test
    void cutVerticalUpperPieceFalls() {
        Pole lower = new Pole(1, p(0.5, 64, 0.5), p(0.5, 65, 0.5));
        Pole upper = new Pole(2, p(0.5, 66, 0.5), p(0.5, 68, 0.5));
        assertEquals(Set.of(1), run(List.of(lower, upper), List.of(), List.of()));
    }

    @Test
    void forecastColors() {
        Pole v = new Pole(1, p(0.5, 64, 0.5), p(0.5, 67, 0.5));
        assertEquals(Stability.Forecast.STANDS, Stability.forecast(v, List.of(), List.of(), List.of(), GROUND));
        Pole d = new Pole(2, p(2.5, 64, 0.5), p(0.5, 67, 0.5));
        assertEquals(Stability.Forecast.LEANS, Stability.forecast(d, List.of(v), List.of(), List.of(), GROUND));
        assertEquals(Stability.Forecast.FALLS, Stability.forecast(d, List.of(), List.of(), List.of(), GROUND));
    }

    @Test
    void maxGap() {
        assertEquals(360, Stability.maxGap(List.of()), 1e-9);
        assertEquals(180, Stability.maxGap(List.of(new double[]{1, 0}, new double[]{-1, 0})), 1e-9);
        assertTrue(Stability.balanced(List.of(new double[]{1, 0}, new double[]{-1, 0}, new double[]{0, 1}, new double[]{0, -1})));
    }
}
