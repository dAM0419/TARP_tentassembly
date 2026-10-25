package com.tarp.world;

import com.tarp.content.CanvasCellBlockEntity;
import com.tarp.content.PoleCellBlock;
import com.tarp.content.PoleCellBlockEntity;
import com.tarp.content.TarpItem;
import com.tarp.content.TarpRegistry;
import com.tarp.core.Door;
import com.tarp.core.Membrane;
import com.tarp.core.Raster;
import com.tarp.core.Spec;
import com.tarp.core.Stability;
import com.tarp.core.V3;
import com.tarp.net.Msg;
import com.tarp.net.Net;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 서버 쪽 규칙: 지지대·노끈·방수포 놓기와 걷기, 쓰러짐, 입구, 잎 덮개 수명, 클라이언트 동기화.
 * 블록을 우리가 바꾸는 동안에는 internal 을 켜서 블록 쪽 onRemove 가 다시 들어오지 않게 한다.
 */
public final class TarpService {
    private TarpService() {}

    private static boolean internal;
    private static final Map<ServerLevel, Set<Long>> CHECKS = new HashMap<>();
    private static final Map<UUID, long[]> DOOR_PENDING = new HashMap<>();

    public static TarpData data(ServerLevel level) {
        return TarpData.get(level);
    }

    // ------------------------------------------------------------------
    // 지지대
    // ------------------------------------------------------------------

    public enum PoleResult { PLACED, FELL, TOO_SHORT, TOO_LONG, BLOCKED, NEED_ITEMS, TOO_FAR }

    /** 플레이어가 지지대를 놓는다. */
    public static void placePole(ServerPlayer pl, V3 a, V3 b) {
        Vec3 eye = pl.getEyePosition();
        V3 e = new V3(eye.x, eye.y, eye.z);
        PoleResult r;
        if (e.dist(a) > 14 || e.dist(b) > 16) r = PoleResult.TOO_FAR;
        else {
            PoleResult check = checkPole(pl.serverLevel(), a, b);
            int n = Spec.poleCost(a.dist(b));
            if (check != null) r = check;
            else if (!pl.getAbilities().instabuild && count(pl, TarpRegistry.POLE.get()) < n) r = PoleResult.NEED_ITEMS;
            else {
                if (!pl.getAbilities().instabuild) consume(pl, TarpRegistry.POLE.get(), n);
                r = addPole(pl.serverLevel(), a, b) > 0 ? PoleResult.PLACED : PoleResult.FELL;
                if (r == PoleResult.PLACED) pl.serverLevel().playSound(null, BlockPos.containing(b.x(), b.y(), b.z()), SoundEvents.BAMBOO_WOOD_PLACE, SoundSource.BLOCKS, 1, 0.9F);
            }
            tell(pl, Component.translatable("tarp.msg.pole." + r.name().toLowerCase(), n));
            return;
        }
        tell(pl, Component.translatable("tarp.msg.pole." + r.name().toLowerCase(), 0));
    }

    @Nullable
    public static PoleResult checkPole(ServerLevel level, V3 a, V3 b) {
        if (!finite(a) || !finite(b) || a.dist(b) < 0.45) return PoleResult.TOO_SHORT;
        List<Raster.Cell> cells = Raster.cells(a, b);
        if (cells.size() > Spec.POLE_MAX_CELLS) return PoleResult.TOO_LONG;
        for (Raster.Cell c : cells) if (!Planner.poleCanPass(level.getBlockState(new BlockPos(c.x(), c.y(), c.z())))) return PoleResult.BLOCKED;
        return null;
    }

    /** 지지대를 놓고 쓰러짐을 판정한다. 서 있으면 id, 쓰러졌으면 -1 (아이템은 떨어진다). */
    public static int addPole(ServerLevel level, V3 a, V3 b) {
        TarpData d = data(level);
        Stability.Pole p = new Stability.Pole(d.newId(), a, b);
        putPole(level, d, p);
        d.poleValue.put(p.id(), Spec.poleCost(a.dist(b)));
        Set<Integer> seeds = new HashSet<>();
        seeds.add(p.id());
        settle(level, d, seeds);
        return d.s.poles.containsKey(p.id()) ? p.id() : -1;
    }

    private static void putPole(ServerLevel level, TarpData d, Stability.Pole p) {
        d.s.addPole(p);
        d.setDirty();
        boolean was = internal;
        internal = true;
        try {
            for (Raster.Cell c : Raster.cells(p.a(), p.b())) {
                BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
                if (!(level.getBlockState(pos).getBlock() instanceof PoleCellBlock))
                    level.setBlock(pos, TarpRegistry.POLE_CELL.get().defaultBlockState(), 3);
                if (level.getBlockEntity(pos) instanceof PoleCellBlockEntity be) be.add(p.id(), p.a(), p.b());
            }
        } finally {
            internal = was;
        }
        broadcast(level, Msg.S_POLE_ADD, b -> {
            b.writeVarInt(1);
            Codecs.write(b, p);
        });
    }

    /** 지지대를 없앤다. drop 이면 차지하던 칸 수만큼 떨어뜨린다. 영향을 받는 지지대는 seeds 에 넣는다. */
    private static void takePole(ServerLevel level, TarpData d, int id, boolean drop, Set<Integer> seeds) {
        Stability.Pole p = d.s.removePole(id);
        if (p == null) return;
        int value = d.value(p);
        d.poleValue.remove(id);
        d.setDirty();
        List<Raster.Cell> cells = Raster.cells(p.a(), p.b());
        boolean was = internal;
        internal = true;
        try {
            for (Raster.Cell c : cells) {
                BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
                // 지금 부서지는 칸(이미 다른 블록)은 건드리지 않는다
                if (!(level.getBlockState(pos).getBlock() instanceof PoleCellBlock)) continue;
                if (level.getBlockEntity(pos) instanceof PoleCellBlockEntity be) {
                    be.remove(id);
                    if (be.entries().isEmpty()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
                seeds.addAll(d.s.polesNear(c.x(), c.y(), c.z()));
            }
        } finally {
            internal = was;
        }
        broadcast(level, Msg.S_POLE_DEL, b -> {
            b.writeVarInt(1);
            b.writeVarInt(id);
        });
        if (drop && value > 0) dropItems(level, p.a().lerp(p.b(), 0.5), new ItemStack(TarpRegistry.POLE.get(), value));
        for (V3 end : new V3[]{p.a(), p.b()}) {
            for (Stability.Rope r : d.s.ropesAt(end)) {
                if (!Planner.isAnchor(level, d.s, end)) takeRope(level, d, r.id(), true, seeds);
            }
        }
        dropDependents(level, d, t -> contains(t.poleDeps, id));
    }

    /** 이 칸의 점유 블록이 사라졌다: 지나가던 지지대를 그 칸에서 자른다. */
    public static void poleCellRemoved(ServerLevel level, BlockPos pos) {
        if (internal) return;
        TarpData d = data(level);
        Set<Integer> ids = new HashSet<>(d.s.polesAt(pos.asLong()));
        if (ids.isEmpty()) return;
        Set<Integer> seeds = new HashSet<>();
        for (int id : ids) {
            Stability.Pole p = d.s.poles.get(id);
            List<Raster.Cell> cells = Raster.cells(p.a(), p.b());
            int k = -1;
            for (int i = 0; i < cells.size(); i++) {
                Raster.Cell c = cells.get(i);
                if (c.x() == pos.getX() && c.y() == pos.getY() && c.z() == pos.getZ()) k = i;
            }
            // 들어간 아이템을 조각 길이 비율로 나누고(내림), 남는 것은 자른 칸에서 떨어뜨린다 → 자르기로 아이템이 늘지 않는다
            int value = d.value(p);
            double len = p.a().dist(p.b());
            int left = 0;
            // 남는 조각을 먼저 놓아야 끝점에 묶인 노끈이 풀리지 않는다
            if (k > 0) {
                Stability.Pole a = new Stability.Pole(d.newId(), p.a(), p.at(cells.get(k).t0()));
                putPole(level, d, a);
                int v = (int) Math.floor(value * a.a().dist(a.b()) / len);
                d.poleValue.put(a.id(), v);
                left += v;
                seeds.add(a.id());
            }
            if (k >= 0 && k < cells.size() - 1) {
                Stability.Pole b = new Stability.Pole(d.newId(), p.at(cells.get(k).t1()), p.b());
                putPole(level, d, b);
                int v = (int) Math.floor(value * b.a().dist(b.b()) / len);
                d.poleValue.put(b.id(), v);
                left += v;
                seeds.add(b.id());
            }
            d.poleValue.put(id, 0);
            takePole(level, d, id, false, seeds);
            if (value - left > 0)
                dropItems(level, new V3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), new ItemStack(TarpRegistry.POLE.get(), value - left));
        }
        settle(level, d, seeds);
    }

    /** 칸 주변이 바뀌었다: 틱 끝에 그 칸의 구조를 다시 판정한다. */
    public static void scheduleCheck(ServerLevel level, BlockPos pos) {
        if (internal) return;
        CHECKS.computeIfAbsent(level, k -> new HashSet<>()).add(pos.asLong());
    }

    /** 덩어리를 다시 판정하고 쓰러진 지지대를 떨어뜨린다. 연쇄 붕괴도 한 번에. */
    static void settle(ServerLevel level, TarpData d, Set<Integer> seeds) {
        Stability.Ground ground = Planner.ground(level);
        for (int guard = 0; guard < 256 && !seeds.isEmpty(); guard++) {
            List<Stability.Pole> comp = d.s.component(seeds);
            seeds = new HashSet<>();
            if (comp.isEmpty()) break;
            List<Stability.Rope> ropes = Planner.ropesFor(d.s, comp);
            Set<Integer> stand = Stability.standing(comp, ropes, Planner.stakeHeads(level, comp, ropes), ground);
            for (Stability.Pole p : comp) if (!stand.contains(p.id())) takePole(level, d, p.id(), true, seeds);
            seeds.removeIf(id -> !d.s.poles.containsKey(id));
        }
    }

    // ------------------------------------------------------------------
    // 노끈
    // ------------------------------------------------------------------

    public enum RopeResult { PLACED, NOT_ANCHOR, TOO_LONG, SAME, NEED_ITEMS }

    public static void placeRope(ServerPlayer pl, V3 a, V3 b) {
        ServerLevel level = pl.serverLevel();
        TarpData d = data(level);
        int n = (int) Math.ceil(a.dist(b) - 1e-6);
        RopeResult r;
        if (!finite(a) || !finite(b) || a.dist(b) < 0.3) r = RopeResult.SAME;
        else if (a.dist(b) > Spec.ROPE_MAX) r = RopeResult.TOO_LONG;
        else if (!Planner.isAnchor(level, d.s, a) || !Planner.isAnchor(level, d.s, b)) r = RopeResult.NOT_ANCHOR;
        else if (!pl.getAbilities().instabuild && count(pl, TarpRegistry.ROPE.get()) < n) r = RopeResult.NEED_ITEMS;
        else {
            if (!pl.getAbilities().instabuild) consume(pl, TarpRegistry.ROPE.get(), n);
            addRope(level, a, b);
            level.playSound(null, BlockPos.containing(b.x(), b.y(), b.z()), SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 1, 1);
            r = RopeResult.PLACED;
        }
        tell(pl, Component.translatable("tarp.msg.rope." + r.name().toLowerCase(), n));
    }

    public static int addRope(ServerLevel level, V3 a, V3 b) {
        TarpData d = data(level);
        Stability.Rope r = new Stability.Rope(d.newId(), a, b);
        d.s.addRope(r);
        d.setDirty();
        putRopeCells(level, r);
        broadcast(level, Msg.S_ROPE_ADD, buf -> {
            buf.writeVarInt(1);
            Codecs.write(buf, r);
        });
        return r.id();
    }

    private static void takeRope(ServerLevel level, TarpData d, int id, boolean drop, Set<Integer> seeds) {
        Stability.Rope r = d.s.removeRope(id);
        if (r == null) return;
        d.setDirty();
        clearRopeCells(level, r);
        broadcast(level, Msg.S_ROPE_DEL, b -> {
            b.writeVarInt(1);
            b.writeVarInt(id);
        });
        if (drop) dropItems(level, r.a().lerp(r.b(), 0.5), new ItemStack(TarpRegistry.ROPE.get(), (int) Math.ceil(r.a().dist(r.b()) - 1e-6)));
        for (V3 end : new V3[]{r.a(), r.b()})
            seeds.addAll(d.s.polesNear((int) Math.floor(end.x()), (int) Math.floor(end.y()), (int) Math.floor(end.z())));
        dropDependents(level, d, t -> contains(t.ropeDeps, id));
    }

    // ------------------------------------------------------------------
    // 노끈 점유 칸 (0.4.7): 노끈이 지나는 빈 칸에 보이지 않는 블록. 지지대·말뚝·천 칸·단단한 블록은 건너뛴다.
    // ------------------------------------------------------------------

    private static void putRopeCells(ServerLevel level, Stability.Rope r) {
        boolean was = internal;
        internal = true;
        try {
            for (Raster.Cell c : Raster.cells(r.a(), r.b())) {
                BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
                BlockState st = level.getBlockState(pos);
                if (!(st.getBlock() instanceof com.tarp.content.RopeCellBlock)) {
                    if (!(st.isAir() || st.canBeReplaced()) || !st.getFluidState().isEmpty()) continue;
                    level.setBlock(pos, TarpRegistry.ROPE_CELL.get().defaultBlockState(), 3);
                }
                if (level.getBlockEntity(pos) instanceof com.tarp.content.RopeCellBlockEntity be) be.add(r.id(), r.a(), r.b());
            }
        } finally {
            internal = was;
        }
        ROPE_CELLS.computeIfAbsent(level, k -> new HashSet<>()).add(r.id());
    }

    private static void clearRopeCells(ServerLevel level, Stability.Rope r) {
        boolean was = internal;
        internal = true;
        try {
            for (Raster.Cell c : Raster.cells(r.a(), r.b())) {
                BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
                if (level.getBlockEntity(pos) instanceof com.tarp.content.RopeCellBlockEntity be) {
                    be.remove(r.id());
                    if (be.entries().isEmpty()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        } finally {
            internal = was;
        }
    }

    /** 노끈 칸을 부쉈다: 그 칸을 지나던 노끈만 걷고(길이만큼 노끈 떨굼) 구조를 다시 판정한다. */
    public static void ropeCellRemoved(ServerLevel level, BlockPos pos) {
        if (internal) return;
        TarpData d = data(level);
        List<Integer> hit = new ArrayList<>();
        for (Stability.Rope r : d.s.ropes.values())
            for (Raster.Cell c : Raster.cells(r.a(), r.b()))
                if (c.x() == pos.getX() && c.y() == pos.getY() && c.z() == pos.getZ()) {
                    hit.add(r.id());
                    break;
                }
        if (hit.isEmpty()) return;
        Set<Integer> seeds = new HashSet<>();
        for (int id : hit) takeRope(level, d, id, true, seeds);
        settle(level, d, seeds);
    }

    /** 노끈 칸을 맞춘 노끈 (월드를 다시 열면 비므로 0.4.6 이하에서 친 노끈도 한 번씩 칸을 만든다). */
    private static final Map<ServerLevel, Set<Integer>> ROPE_CELLS = new HashMap<>();

    /** 말뚝이 사라졌다: 거기 묶인 노끈·방수포를 떨어뜨리고 구조를 다시 판정한다. */
    public static void stakeRemoved(ServerLevel level, BlockPos pos, BlockState old) {
        if (internal) return;
        TarpData d = data(level);
        V3 head = Planner.stakeHead(old, pos);
        Set<Integer> seeds = new HashSet<>(d.s.polesNear(pos.getX(), pos.getY(), pos.getZ()));
        for (Stability.Rope r : d.s.ropesAt(head)) takeRope(level, d, r.id(), true, seeds);
        long key = pos.asLong();
        dropDependents(level, d, t -> contains(t.stakeDeps, key));
        settle(level, d, seeds);
    }

    // ------------------------------------------------------------------
    // 방수포
    // ------------------------------------------------------------------

    public static void placeTarp(ServerPlayer pl, List<V3> ties) {
        ItemStack stack = pl.getMainHandItem();
        if (!(stack.getItem() instanceof TarpItem item)) return;
        ServerLevel level = pl.serverLevel();
        Planner.TarpPlan plan = Planner.planTarp(level, data(level).s, ties, item);
        if (!plan.ok()) {
            tell(pl, Component.translatable("tarp.msg.tarp." + plan.error.name().toLowerCase(), (int) Math.ceil(plan.area), (int) item.maxArea()));
            return;
        }
        int n = ties.size();
        if (!pl.getAbilities().instabuild && count(pl, TarpRegistry.ROPE.get()) < n) {
            tell(pl, Component.translatable("tarp.msg.tarp.need_ropes", n));
            return;
        }
        TarpRec rec = addTarp(level, plan, ties, item.kind, TarpItem.color(stack), item.isLeaf() ? TarpItem.leafId(stack) : "");
        if (!pl.getAbilities().instabuild) {
            stack.shrink(1);
            consume(pl, TarpRegistry.ROPE.get(), n);
        }
        level.playSound(null, BlockPos.containing(rec.centre().x(), rec.centre().y(), rec.centre().z()), SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1, 0.8F);
        tell(pl, Component.translatable("tarp.msg.tarp.placed", (int) Math.ceil(plan.area), plan.doorPossible ? 1 : 0));
    }

    /** 계획대로 설치한다 (검증은 끝난 것으로 본다). */
    public static TarpRec addTarp(ServerLevel level, Planner.TarpPlan plan, List<V3> ties, int kind, int color, String leaf) {
        TarpData d = data(level);
        TarpRec rec = new TarpRec();
        rec.id = d.newId();
        rec.kind = kind;
        rec.color = color;
        rec.leaf = leaf;
        rec.ties.addAll(ties);
        rec.obstacles.addAll(plan.obstacles);
        rec.poleDeps = plan.poles.stream().mapToInt(Integer::intValue).toArray();
        rec.ropeDeps = plan.ropes.stream().mapToInt(Integer::intValue).toArray();
        rec.stakeDeps = plan.stakes.stream().mapToLong(Long::longValue).toArray();
        rec.placedAt = level.getGameTime();
        d.s.addTarp(rec);
        d.setDirty();
        writeCanvas(level, rec);
        broadcast(level, Msg.S_TARP_ADD, b -> {
            b.writeVarInt(1);
            Codecs.write(b, rec);
        });
        return rec;
    }

    /** 천 충돌 칸을 지금 상태(입구 열림)에 맞춘다. */
    private static void writeCanvas(ServerLevel level, TarpRec rec) {
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap masks = rec.open && rec.door() != null
                ? Planner.masks(rec.shape(), rec.door(), true) : rec.closedMasks();
        Set<Long> cells = new HashSet<>();
        for (long c : rec.cells) cells.add(c);
        boolean was = internal;
        internal = true;
        try {
            for (long c : cells) {
                if (masks.containsKey(c)) continue;
                if (level.getBlockEntity(BlockPos.of(c)) instanceof CanvasCellBlockEntity be && be.tarp() == rec.id) be.set(rec.id, 0);
            }
            for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry e : masks.long2LongEntrySet()) {
                BlockPos pos = BlockPos.of(e.getLongKey());
                if (level.getBlockEntity(pos) instanceof CanvasCellBlockEntity be) {
                    if (be.tarp() == rec.id) be.set(rec.id, e.getLongValue());
                    continue;
                }
                BlockState st = level.getBlockState(pos);
                if (st.getBlock() instanceof PoleCellBlock) {
                    setCovered(level, pos, st, true);
                    continue;
                }
                if (!st.isAir() && !st.canBeReplaced()) continue;
                level.setBlock(pos, TarpRegistry.CANVAS_CELL.get().defaultBlockState(), 3);
                if (level.getBlockEntity(pos) instanceof CanvasCellBlockEntity be) be.set(rec.id, e.getLongValue());
                cells.add(e.getLongKey());
            }
        } finally {
            internal = was;
        }
        rec.cells = cells.stream().mapToLong(Long::longValue).toArray();
        LIT.computeIfAbsent(level, k -> new HashSet<>()).add(rec.id);
        data(level).setDirty();
    }

    private static void clearCanvas(ServerLevel level, TarpRec rec) {
        boolean was = internal;
        internal = true;
        try {
            for (long c : rec.cells) {
                BlockPos pos = BlockPos.of(c);
                if (level.getBlockEntity(pos) instanceof CanvasCellBlockEntity be && be.tarp() == rec.id)
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            uncoverPoles(level, rec);
        } finally {
            internal = was;
        }
        Set<Integer> lit = LIT.get(level);
        if (lit != null) lit.remove(rec.id);
    }

    // ------------------------------------------------------------------
    // 천이 지나가는 지지대 칸 (0.3.10): 하늘빛을 천 칸처럼 막는다
    // ------------------------------------------------------------------

    /** 지지대 칸 덮임 표시를 맞춘 방수포 (월드를 다시 열면 비므로, 0.3.9 이하에서 친 천막도 한 번씩 맞춘다). */
    private static final Map<ServerLevel, Set<Integer>> LIT = new HashMap<>();

    private static void setCovered(ServerLevel level, BlockPos pos, BlockState st, boolean covered) {
        if (st.getValue(PoleCellBlock.COVERED) == covered) return;
        boolean was = internal;
        internal = true;
        try {
            // 같은 블록의 상태만 바꾼다: 블록 엔티티(지지대 목록)는 남고, 빛만 다시 계산된다. 이웃 갱신(쓰러짐 재판정)은 하지 않는다.
            level.setBlock(pos, st.setValue(PoleCellBlock.COVERED, covered), 2);
        } finally {
            internal = was;
        }
    }

    /** 방수포가 지나가는 지지대 칸을 덮임으로 (입구가 열려 있어도 닫힌 모양 기준: 입구 띠에는 지지대가 없다). */
    private static void coverPoles(ServerLevel level, TarpRec rec) {
        for (long c : rec.closedCells()) {
            BlockPos pos = BlockPos.of(c);
            BlockState st = level.getBlockState(pos);
            if (st.getBlock() instanceof PoleCellBlock) setCovered(level, pos, st, true);
        }
    }

    /** 걷은 방수포가 덮던 지지대 칸을 되돌린다. 다른 방수포가 같은 칸을 덮고 있으면 그대로 둔다. */
    private static void uncoverPoles(ServerLevel level, TarpRec rec) {
        if (!rec.shape().ok()) return;
        List<TarpRec> others = null;
        for (long c : rec.closedCells()) {
            BlockPos pos = BlockPos.of(c);
            BlockState st = level.getBlockState(pos);
            if (!(st.getBlock() instanceof PoleCellBlock) || !st.getValue(PoleCellBlock.COVERED)) continue;
            if (others == null) {
                // 0.4.10: 주변 방수포의 칸을 모두 새로 훑어 합치던 것을, 캐시된 칸에서 그 칸만 찾는 것으로
                others = new ArrayList<>();
                V3 mid = rec.centre();
                for (TarpRec t : data(level).s.tarps.values())
                    if (t.id != rec.id && t.centre().dist(mid) <= 2 * Spec.MAX_SPAN && t.shape().ok()) others.add(t);
            }
            boolean kept = false;
            for (TarpRec t : others) {
                double[] fp = t.footprint();
                if (pos.getX() < fp[0] - 2 || pos.getX() > fp[2] + 2 || pos.getZ() < fp[1] - 2 || pos.getZ() > fp[3] + 2) continue;
                if (t.closedMasks().containsKey(c)) {
                    kept = true;
                    break;
                }
            }
            if (!kept) setCovered(level, pos, st, false);
        }
    }

    /** 방수포를 걷는다. giveTarp 이면 그 크기·색의 방수포가 돌아온다(잎 덮개는 돌아오지 않는다). 노끈은 언제나 돌아온다. */
    public static void takeTarp(ServerLevel level, int id, boolean giveTarp, @Nullable Player to) {
        TarpData d = data(level);
        TarpRec rec = d.s.removeTarp(id);
        if (rec == null) return;
        d.setDirty();
        clearCanvas(level, rec);
        broadcast(level, Msg.S_TARP_DEL, b -> {
            b.writeVarInt(1);
            b.writeVarInt(id);
        });
        List<ItemStack> items = new ArrayList<>();
        if (giveTarp && !rec.isLeaf()) items.add(TarpItem.of(rec.kind, rec.color));
        items.add(new ItemStack(TarpRegistry.ROPE.get(), rec.ties.size()));
        for (ItemStack s : items) {
            if (to != null && !to.getAbilities().instabuild) {
                if (!to.getInventory().add(s)) dropItems(level, rec.centre(), s);
            } else if (to == null) dropItems(level, rec.centre(), s);
        }
    }

    private static void dropDependents(ServerLevel level, TarpData d, java.util.function.Predicate<TarpRec> test) {
        List<Integer> gone = new ArrayList<>();
        for (TarpRec t : d.s.tarps.values()) if (test.test(t)) gone.add(t.id);
        for (int id : gone) takeTarp(level, id, true, null);
    }

    public static void shearTarp(ServerLevel level, int id, Player player) {
        if (!data(level).s.tarps.containsKey(id)) return;
        takeTarp(level, id, true, player);
        level.playSound(null, player.blockPosition(), SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 1, 1);
    }

    public static void canvasRemoved(ServerLevel level, int id) {
        if (internal) return;
        takeTarp(level, id, true, null);
    }

    // ------------------------------------------------------------------
    // 입구
    // ------------------------------------------------------------------

    /** 빈손으로 천을 우클릭. 웅크리면 입구 지정(두 번 눌러 확정), 아니면 입구 여닫기. 처리했으면 true. */
    public static boolean canvasUse(ServerLevel level, int id, ServerPlayer pl, Vec3 hit, boolean sneak) {
        TarpRec rec = data(level).s.tarps.get(id);
        if (rec == null || !rec.shape().ok()) return false;
        Membrane.Result r = rec.shape();
        if (sneak) {
            if (rec.doorEdge >= 0) {
                tell(pl, Component.translatable("tarp.msg.door.exists"));
                return true;
            }
            int edge = Planner.nearestEdge(r, new V3(hit.x, hit.y, hit.z));
            Door.Plan plan = Door.plan(r, edge, rec.obstacles);
            long now = level.getGameTime();
            long[] pend = DOOR_PENDING.get(pl.getUUID());
            if (plan.ok() && pend != null && pend[0] == id && pend[1] == edge && now - pend[2] < 1200) {
                DOOR_PENDING.remove(pl.getUUID());
                setDoor(level, rec, edge);
                tell(pl, Component.translatable("tarp.msg.door.set"));
                return true;
            }
            DOOR_PENDING.put(pl.getUUID(), new long[]{id, edge, now});
            Net.toPlayer(pl, Msg.S_DOOR_PREVIEW, b -> {
                b.writeVarInt(id);
                b.writeVarInt(edge);
                b.writeVarInt(plan.ok() ? -1 : plan.error().ordinal());
            });
            tell(pl, Component.translatable(plan.ok() ? "tarp.msg.door.preview" : "tarp.msg.door." + plan.error().name().toLowerCase()));
            return true;
        }
        Door door = rec.door();
        if (door == null) return false;
        double[] l = door.local(hit.x, hit.z);
        boolean near = l[0] >= door.s0 - 0.3 && l[0] <= door.s1 + 0.3 && hit.y < door.top + 0.5 && l[1] >= -0.5 && l[1] <= door.maxDepth + 0.5;
        if (!near) return false;
        setOpen(level, rec, !rec.open);
        level.playSound(null, BlockPos.containing(hit), rec.open ? SoundEvents.WOOL_HIT : SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1, 1.2F);
        return true;
    }

    public static boolean setDoor(ServerLevel level, TarpRec rec, int edge) {
        if (!Door.plan(rec.shape(), edge, rec.obstacles).ok()) return false;
        rec.setDoor(edge);
        rec.open = false;
        writeCanvas(level, rec);
        sendDoor(level, rec);
        return true;
    }

    public static void setOpen(ServerLevel level, TarpRec rec, boolean open) {
        rec.open = open;
        writeCanvas(level, rec);
        sendDoor(level, rec);
    }

    private static void sendDoor(ServerLevel level, TarpRec rec) {
        broadcast(level, Msg.S_TARP_DOOR, b -> {
            b.writeVarInt(rec.id);
            b.writeVarInt(rec.doorEdge);
            b.writeBoolean(rec.open);
        });
    }

    // ------------------------------------------------------------------
    // 천막 키트 (0.2.0): 드롭 없이 걷고, 쓰러짐 판정 없이 놓는다
    // ------------------------------------------------------------------

    /** 키트에 담으며 월드에서 없앤다. 아이템은 떨어뜨리지 않는다. */
    public static void kitRemove(ServerLevel level, int tarpId, java.util.Collection<Integer> poleIds, java.util.Collection<Integer> ropeIds, java.util.Collection<Long> stakes) {
        TarpData d = data(level);
        boolean was = internal;
        internal = true;
        try {
            TarpRec rec = d.s.removeTarp(tarpId);
            if (rec != null) {
                clearCanvas(level, rec);
                broadcast(level, Msg.S_TARP_DEL, b -> {
                    b.writeVarInt(1);
                    b.writeVarInt(tarpId);
                });
            }
            for (int id : ropeIds) {
                Stability.Rope gone = d.s.removeRope(id);
                if (gone == null) continue;
                clearRopeCells(level, gone);
                broadcast(level, Msg.S_ROPE_DEL, b -> {
                    b.writeVarInt(1);
                    b.writeVarInt(id);
                });
            }
            Set<Integer> ignore = new HashSet<>();
            for (int id : poleIds) takePole(level, d, id, false, ignore);
            for (long c : stakes) {
                BlockPos pos = BlockPos.of(c);
                if (level.getBlockState(pos).getBlock() instanceof com.tarp.content.StakeBlock) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        } finally {
            internal = was;
        }
        d.setDirty();
    }

    /** 키트 설치: 지지대를 쓰러짐 판정 없이 놓는다. */
    public static int putPoleRaw(ServerLevel level, V3 a, V3 b, int value) {
        TarpData d = data(level);
        Stability.Pole p = new Stability.Pole(d.newId(), a, b);
        putPole(level, d, p);
        d.poleValue.put(p.id(), value);
        return p.id();
    }

    public static boolean putStake(ServerLevel level, BlockPos pos, net.minecraft.core.Direction facing) {
        BlockState st = TarpRegistry.STAKE.get().defaultBlockState().setValue(com.tarp.content.StakeBlock.FACING, facing);
        BlockState cur = level.getBlockState(pos);
        if (!(cur.isAir() || cur.canBeReplaced()) || !st.canSurvive(level, pos)) return false;
        boolean was = internal;
        internal = true;
        try {
            level.setBlock(pos, st, 3);
        } finally {
            internal = was;
        }
        return true;
    }

    public static void tellPublic(Player pl, Component c) {
        tell(pl, c);
    }

    // ------------------------------------------------------------------
    // 틱
    // ------------------------------------------------------------------

    public static void tick(ServerLevel level) {
        Set<Long> checks = CHECKS.remove(level);
        TarpData d = null;
        if (checks != null && !checks.isEmpty()) {
            d = data(level);
            Set<Integer> seeds = new HashSet<>();
            for (long c : checks) {
                BlockPos p = BlockPos.of(c);
                seeds.addAll(d.s.polesNear(p.getX(), p.getY(), p.getZ()));
            }
            settle(level, d, seeds);
        }
        if (level.getGameTime() % 20 == 0) {
            if (d == null) d = data(level);
            List<Integer> gone = new ArrayList<>();
            long now = level.getGameTime();
            for (TarpRec t : d.s.tarps.values()) {
                if (!t.isLeaf() || now - t.placedAt < Spec.LEAF_LIFE) continue;
                V3 c = t.centre();
                if (level.isLoaded(BlockPos.containing(c.x(), c.y(), c.z()))) gone.add(t.id);
            }
            for (int id : gone) takeTarp(level, id, false, null);
            Set<Integer> rc = ROPE_CELLS.computeIfAbsent(level, k -> new HashSet<>());
            // 0.4.10: 칸을 맞출 노끈만 모은다 (전엔 1초마다 노끈 목록 전체를 복사했다). 칸을 놓다 노끈이 바뀔 수 있어 모은 뒤 처리한다
            List<Stability.Rope> todo = null;
            for (Stability.Rope r : d.s.ropes.values()) {
                if (rc.contains(r.id()) || !level.isLoaded(BlockPos.containing(r.a().x(), r.a().y(), r.a().z()))) continue;
                if (todo == null) todo = new ArrayList<>();
                todo.add(r);
            }
            if (todo != null) for (Stability.Rope r : todo) if (d.s.ropes.containsKey(r.id())) putRopeCells(level, r);
            Set<Integer> lit = LIT.computeIfAbsent(level, k -> new HashSet<>());
            for (TarpRec t : d.s.tarps.values()) {
                if (lit.contains(t.id) || !t.shape().ok()) continue;
                V3 c = t.centre();
                if (!level.isLoaded(BlockPos.containing(c.x(), c.y(), c.z()))) continue;
                coverPoles(level, t);
                lit.add(t.id);
            }
        }
    }

    // ------------------------------------------------------------------
    // 동기화
    // ------------------------------------------------------------------

    public static void sendAll(ServerPlayer pl) {
        TarpData d = data(pl.serverLevel());
        Net.toPlayer(pl, Msg.S_FULL, b -> {
            b.writeVarInt(d.s.poles.size());
            for (Stability.Pole p : d.s.poles.values()) Codecs.write(b, p);
            b.writeVarInt(d.s.ropes.size());
            for (Stability.Rope r : d.s.ropes.values()) Codecs.write(b, r);
            b.writeVarInt(d.s.tarps.size());
            for (TarpRec t : d.s.tarps.values()) Codecs.write(b, t);
        });
    }

    private static void broadcast(ServerLevel level, int kind, Consumer<FriendlyByteBuf> writer) {
        if (level.players().isEmpty()) return;
        byte[] body = Net.encode(writer);
        for (ServerPlayer p : level.players()) Net.toPlayer(p, kind, body);
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    static boolean finite(V3 p) {
        return Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z());
    }

    static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    static boolean contains(long[] a, long v) {
        for (long x : a) if (x == v) return true;
        return false;
    }

    static void tell(Player pl, Component c) {
        pl.displayClientMessage(c, true);
    }

    public static int count(Player pl, Item item) {
        int n = 0;
        for (ItemStack s : pl.getInventory().items) if (s.is(item)) n += s.getCount();
        for (ItemStack s : pl.getInventory().offhand) if (s.is(item)) n += s.getCount();
        return n;
    }

    public static void consume(Player pl, Item item, int n) {
        List<ItemStack> all = new ArrayList<>(pl.getInventory().items);
        all.addAll(pl.getInventory().offhand);
        for (ItemStack s : all) {
            if (n <= 0) break;
            if (!s.is(item)) continue;
            int take = Math.min(n, s.getCount());
            s.shrink(take);
            n -= take;
        }
    }

    public static void dropItems(Level level, V3 at, ItemStack stack) {
        while (!stack.isEmpty()) {
            ItemStack part = stack.split(stack.getMaxStackSize());
            ItemEntity e = new ItemEntity(level, at.x(), at.y(), at.z(), part);
            e.setDefaultPickUpDelay();
            level.addFreshEntity(e);
        }
    }
}
