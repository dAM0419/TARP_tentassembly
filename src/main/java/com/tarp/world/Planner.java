package com.tarp.world;

import com.tarp.content.CanvasCellBlock;
import com.tarp.content.CanvasCellBlockEntity;
import com.tarp.content.PoleCellBlock;
import com.tarp.content.StakeBlock;
import com.tarp.content.TarpItem;
import com.tarp.core.Door;
import com.tarp.core.Membrane;
import com.tarp.core.Polygon;
import com.tarp.core.Raster;
import com.tarp.core.Spec;
import com.tarp.core.Stability;
import com.tarp.core.V3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 월드와 순수 계산(core)을 잇는다. 서버 검증과 클라이언트 미리보기가 같은 함수를 부른다.
 */
public final class Planner {
    private Planner() {}

    // ------------------------------------------------------------------
    // 블록 판정
    // ------------------------------------------------------------------

    public static boolean ours(BlockState s) {
        return s.getBlock() instanceof PoleCellBlock || s.getBlock() instanceof CanvasCellBlock || s.getBlock() instanceof StakeBlock
                || s.getBlock() instanceof com.tarp.content.RopeCellBlock;
    }

    /** 지지대를 받쳐주는 블록. */
    public static Stability.Ground ground(BlockGetter level) {
        return (x, y, z) -> {
            BlockPos p = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced() || ours(s)) return false;
            return !s.getCollisionShape(level, p).isEmpty();
        };
    }

    /** 지지대가 지나갈 수 있는 칸: 공기·대체 가능 블록·다른 지지대 칸. */
    public static boolean poleCanPass(BlockState s) {
        if (s.getBlock() instanceof PoleCellBlock) return true;
        return (s.isAir() || s.canBeReplaced()) && s.getFluidState().isEmpty() && !ours(s);
    }

    /** 말뚝 머리가 박힌 면에서 떨어진 거리 (0.4.5: 깊이 박음, 1/2 → 3/16). */
    public static final double STAKE_HEAD = 3.0 / 16;

    /** 말뚝 머리 = 박힌 면(FACING 반대쪽)에서 STAKE_HEAD 만큼 나온 곳, 면의 가운데. */
    public static V3 stakeHead(BlockGetter level, BlockPos p) {
        return stakeHead(level.getBlockState(p), p);
    }

    /** 블록 상태를 직접 받는다 (onRemove 때는 그 칸이 이미 다른 블록이다). */
    public static V3 stakeHead(BlockState s, BlockPos p) {
        net.minecraft.core.Direction f = s.getBlock() instanceof StakeBlock ? s.getValue(StakeBlock.FACING) : net.minecraft.core.Direction.UP;
        double k = 0.5 - STAKE_HEAD;
        return new V3(p.getX() + 0.5 - f.getStepX() * k, p.getY() + 0.5 - f.getStepY() * k, p.getZ() + 0.5 - f.getStepZ() * k);
    }

    public static boolean stakeAt(BlockGetter level, V3 p) {
        BlockPos b = BlockPos.containing(p.x(), p.y(), p.z());
        return level.getBlockState(b).getBlock() instanceof StakeBlock && stakeHead(level, b).dist(p) < Stability.TOUCH;
    }

    /** 노끈·방수포를 묶을 수 있는 점: 말뚝 머리 또는 지지대 끝점. */
    public static boolean isAnchor(BlockGetter level, Structures s, V3 p) {
        return stakeAt(level, p) || s.isPoleEnd(p);
    }

    /** 이 지지대·노끈들에 닿은 말뚝 머리. */
    public static List<V3> stakeHeads(BlockGetter level, Collection<Stability.Pole> poles, Collection<Stability.Rope> ropes) {
        Set<Long> seen = new HashSet<>();
        List<V3> out = new ArrayList<>();
        List<V3> pts = new ArrayList<>();
        for (Stability.Pole p : poles) {
            pts.add(p.a());
            pts.add(p.b());
        }
        for (Stability.Rope r : ropes) {
            pts.add(r.a());
            pts.add(r.b());
        }
        for (V3 p : pts) {
            BlockPos b = BlockPos.containing(p.x(), p.y(), p.z());
            if (stakeAt(level, p) && seen.add(b.asLong())) out.add(stakeHead(level, b));
        }
        return out;
    }

    /** 덩어리 판정에 쓸 노끈: 끝이 덩어리 지지대 끝점에 닿은 것. */
    public static List<Stability.Rope> ropesFor(Structures s, Collection<Stability.Pole> comp) {
        Set<Stability.Rope> out = new LinkedHashSet<>();
        for (Stability.Pole p : comp) {
            out.addAll(s.ropesAt(p.a()));
            out.addAll(s.ropesAt(p.b()));
        }
        return new ArrayList<>(out);
    }

    /** 새 지지대의 미리보기 색. */
    public static Stability.Forecast forecast(BlockGetter level, Structures s, V3 a, V3 b) {
        Stability.Pole cand = new Stability.Pole(-1, a, b);
        Set<Integer> seeds = new HashSet<>();
        for (Raster.Cell c : Raster.cells(a, b)) {
            for (int id : s.polesNear(c.x(), c.y(), c.z())) if (Structures.touches(cand, s.poles.get(id))) seeds.add(id);
        }
        List<Stability.Pole> comp = s.component(seeds);
        List<Stability.Pole> all = new ArrayList<>(comp);
        all.add(cand);
        List<Stability.Rope> ropes = ropesFor(s, all);
        return Stability.forecast(cand, comp, ropes, stakeHeads(level, all, ropes), ground(level));
    }

    // ------------------------------------------------------------------
    // 방수포 계획
    // ------------------------------------------------------------------

    public enum TarpError { SHAPE, NOT_ANCHOR, TOO_BIG, BLOCKED, EXPOSED, NO_FRAME }

    public static final class TarpPlan {
        public Membrane.Result shape;
        public final List<Membrane.Obstacle> obstacles = new ArrayList<>();
        public final List<Integer> poles = new ArrayList<>();
        public final List<Integer> ropes = new ArrayList<>();
        public final List<Long> stakes = new ArrayList<>();
        public final List<BlockPos> blocked = new ArrayList<>();
        public final List<V3> notAnchor = new ArrayList<>();
        public double area;
        /** 넓이에 맞는 크기 (0 소, 1 중, 2 대, -1 없음). */
        public int needSize = -1;
        /** 입구를 낼 수 있는 변이 하나라도 있는가. */
        public boolean doorPossible;
        public TarpError error;

        public boolean ok() {
            return error == null;
        }
    }

    /** 묶는 점들로 방수포를 덮어 본다. item 이 있으면 넓이 상한도 본다. */
    public static TarpPlan planTarp(BlockGetter level, Structures s, List<V3> ties, TarpItem item) {
        TarpPlan plan = new TarpPlan();
        for (V3 t : ties) {
            if (!isAnchor(level, s, t)) plan.notAnchor.add(t);
            if (stakeAt(level, t)) plan.stakes.add(BlockPos.containing(t.x(), t.y(), t.z()).asLong());
        }
        gatherFrame(s, ties, plan);
        plan.shape = Membrane.compute(ties, plan.obstacles);
        if (!plan.shape.ok()) {
            plan.error = TarpError.SHAPE;
            return plan;
        }
        plan.area = plan.shape.area();
        plan.needSize = Spec.sizeFor(plan.area);
        for (int e = 0; e < plan.shape.edges.length && !plan.doorPossible; e++)
            if (Door.plan(plan.shape, e, plan.obstacles).ok()) plan.doorPossible = true;
        for (Map.Entry<Long, Long> me : masks(plan.shape, null, false).entrySet()) {
            long cell = me.getKey();
            BlockPos p = BlockPos.of(cell);
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.canBeReplaced() || st.getBlock() instanceof PoleCellBlock || st.getBlock() instanceof StakeBlock
                    || st.getBlock() instanceof com.tarp.content.RopeCellBlock) continue;
            plan.blocked.add(p);
        }
        if (!plan.notAnchor.isEmpty()) plan.error = TarpError.NOT_ANCHOR;
        else if (!plan.blocked.isEmpty()) plan.error = TarpError.BLOCKED;
        else if (!plan.shape.exposed.isEmpty()) plan.error = TarpError.EXPOSED;
        else if (item != null && plan.area > item.maxArea()) plan.error = TarpError.TOO_BIG;
        return plan;
    }

    /**
     * 골조 모으기: 다각형 위(여유 0.4칸)에 걸치거나 묶는 점에 끝이 닿은 지지대에서 시작해, 닿은 지지대로 넓힌다.
     * 대각 지지대끼리 만나는 가장 높은 점보다 위로 삐져나온 부분(티피 꼭대기)은 막을 들어 올리지 않게 잘라낸다.
     */
    static void gatherFrame(Structures s, List<V3> ties, TarpPlan plan) {
        if (ties.size() < 3) return;
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (V3 t : ties) {
            minX = Math.min(minX, t.x());
            maxX = Math.max(maxX, t.x());
            minZ = Math.min(minZ, t.z());
            maxZ = Math.max(maxZ, t.z());
        }
        Set<Integer> seeds = new HashSet<>();
        for (Stability.Pole p : s.poles.values()) {
            if (Math.max(p.a().x(), p.b().x()) < minX - 1 || Math.min(p.a().x(), p.b().x()) > maxX + 1) continue;
            if (Math.max(p.a().z(), p.b().z()) < minZ - 1 || Math.min(p.a().z(), p.b().z()) > maxZ + 1) continue;
            boolean seed = false;
            for (V3 t : ties) if (p.a().dist(t) < Stability.TOUCH || p.b().dist(t) < Stability.TOUCH) seed = true;
            for (int i = 0; i <= 16 && !seed; i++) {
                V3 q = p.at(i / 16.0);
                if (Polygon.contains(ties, q.x(), q.z()) || Polygon.nearestEdge(ties, q.x(), q.z())[2] < 0.4) seed = true;
            }
            if (seed) seeds.add(p.id());
        }
        List<Stability.Pole> frame = s.component(seeds);
        for (Stability.Pole p : frame) {
            plan.poles.add(p.id());
            V3[] seg = trimmed(p, frame);
            plan.obstacles.add(new Membrane.Obstacle(seg[0], seg[1], Spec.POLE_CLEAR, true));
        }
        for (Stability.Rope r : s.ropes.values()) {
            boolean over = false;
            for (int i = 0; i <= 32 && !over; i++) {
                V3 q = r.a().lerp(r.b(), i / 32.0);
                if (Polygon.contains(ties, q.x(), q.z()) || Polygon.nearestEdge(ties, q.x(), q.z())[2] < 0.3) over = true;
            }
            if (!over) continue;
            plan.ropes.add(r.id());
            plan.obstacles.add(new Membrane.Obstacle(r.a(), r.b(), Spec.ROPE_CLEAR, false));
        }
    }

    /** 대각 지지대: 다른 대각과 만나는 가장 높은 점 위로 나온 부분을 잘라낸 선분. */
    static V3[] trimmed(Stability.Pole p, List<Stability.Pole> frame) {
        if (p.kind() != Stability.Kind.DIAGONAL) return new V3[]{p.a(), p.b()};
        double bestT = -1, bestY = -Double.MAX_VALUE;
        V3 foot = p.foot(), top = p.top();
        for (Stability.Pole q : frame) {
            if (q.id() == p.id() || q.kind() != Stability.Kind.DIAGONAL) continue;
            int n = 64;
            for (int i = 0; i <= n; i++) {
                double t = (double) i / n;
                V3 pt = foot.lerp(top, t);
                if (Structures.pointSeg(pt, q.a(), q.b()) < Stability.TOUCH && pt.y() > bestY) {
                    bestY = pt.y();
                    bestT = t;
                }
            }
        }
        if (bestT < 0 || bestT > 0.97) return new V3[]{foot, top};
        return new V3[]{foot, foot.lerp(top, bestT)};
    }

    // ------------------------------------------------------------------
    // 천 충돌 칸
    // ------------------------------------------------------------------

    /** 칸(BlockPos.asLong) → 작은 칸 비트. 막 면을 0.125칸 간격으로 훑는다. 열린 입구 띠는 비운다 (두루마리 자리는 남긴다). */
    public static Map<Long, Long> masks(Membrane.Result r, Door door, boolean open) {
        Map<Long, Long> out = new HashMap<>();
        boolean cut = door != null && open;
        for (com.tarp.core.Hull3.Face f : r.faces) {
            List<V3> poly = f.poly();
            V3 o = poly.get(0);
            for (int i = 1; i + 1 < poly.size(); i++) {
                V3 a = poly.get(i), b = poly.get(i + 1);
                double la = a.sub(o).len(), lb = b.sub(o).len(), lab = b.sub(a).len();
                int n = Math.max(1, (int) Math.ceil(Math.max(la, Math.max(lb, lab)) / 0.125));
                for (int u = 0; u <= n; u++) {
                    for (int v = 0; v + u <= n; v++) {
                        V3 q = o.add(a.sub(o).mul((double) u / n)).add(b.sub(o).mul((double) v / n));
                        if (cut && door.cutsRoof(q.x(), q.y(), q.z())) continue;
                        mark(out, q);
                    }
                }
            }
        }
        // 두루마리 (열렸을 때도 조준할 수 있게)
        if (door != null) {
            Membrane.Edge ed = door.e;
            for (double s = door.s0; s <= door.s1 + 1e-9; s += 0.125) {
                double d = r.outerD(ed, s, door.top);
                if (Double.isNaN(d)) d = door.rollDepth(s);
                mark(out, new V3(ed.px(s) + ed.nx * d, door.top, ed.pz(s) + ed.nz * d));
            }
        }
        return out;
    }


    private static void mark(Map<Long, Long> out, V3 q) {
        int bx = (int) Math.floor(q.x()), by = (int) Math.floor(q.y()), bz = (int) Math.floor(q.z());
        int sx = Math.min(3, (int) ((q.x() - bx) * 4)), sy = Math.min(3, (int) ((q.y() - by) * 4)), sz = Math.min(3, (int) ((q.z() - bz) * 4));
        out.merge(BlockPos.asLong(bx, by, bz), 1L << CanvasCellBlockEntity.bit(sx, sy, sz), (x, y) -> x | y);
    }

    /** 입구 고르기: 클릭한 점에서 가장 가까운 변. */
    public static int nearestEdge(Membrane.Result r, V3 hit) {
        return (int) Polygon.nearestEdge(r.ties, hit.x(), hit.z())[0];
    }
}
