package com.tarp.world;

import com.tarp.content.StakeBlock;
import com.tarp.content.TarpItem;
import com.tarp.content.TarpRegistry;
import com.tarp.core.Door;
import com.tarp.core.Membrane;
import com.tarp.core.Raster;
import com.tarp.core.Spec;
import com.tarp.core.Stability;
import com.tarp.core.V3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 천막 키트 (설계서 0.2.0 '천막 키트'). 완성된 천막을 설계도 + 재료로 담고, 다른 곳에 부품 순서대로 다시 친다.
 * <p>
 * 설계도 좌표는 기준점 O(칸 가로 가운데, 정수 높이)에서 뺀 값이고, 입구가 있으면 입구가 북쪽(-Z)을 보도록 돌려 둔다.
 * 놓을 때는 기준점 O' 와 회전 k(시계 방향 90°×k)로 되돌린다. 키트 설치 중에는 쓰러짐 판정을 하지 않는다.
 */
public final class Kit {
    public enum Part { POLE, STAKE, ROPE, TARP }

    public record PoleBp(V3 a, V3 b, int value, boolean contactA, boolean contactB) {}

    public record StakeBp(BlockPos rel, Direction facing) {}

    public record RopeBp(V3 a, V3 b) {}

    public final List<PoleBp> poles = new ArrayList<>();
    public final List<StakeBp> stakes = new ArrayList<>();
    public final List<RopeBp> ropes = new ArrayList<>();
    public final List<V3> ties = new ArrayList<>();
    public int kind, color;
    /** 입구 변의 두 묶는 점 (없으면 null). */
    @Nullable public V3 doorA, doorB;

    // ---- 설치 진행 (키트에 함께 저장) ----
    public boolean anchored;
    public V3 origin = V3.ZERO;
    public int rot;
    public boolean[] donePoles = new boolean[0], doneStakes = new boolean[0], doneRopes = new boolean[0];

    public boolean hasDoor() {
        return doorA != null;
    }

    // ------------------------------------------------------------------ 좌표 변환

    /** 시계 방향 90°×k (위에서 보아, 북→동→남→서). */
    public static V3 rotRel(V3 p, int k) {
        double x = p.x(), z = p.z();
        for (int i = 0; i < ((k % 4) + 4) % 4; i++) {
            double nx = -z, nz = x;
            x = nx;
            z = nz;
        }
        return new V3(x, p.y(), z);
    }

    public static Direction rotDir(Direction d, int k) {
        Rotation r = switch (((k % 4) + 4) % 4) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        return r.rotate(d);
    }

    public V3 world(V3 rel) {
        return origin.add(rotRel(rel, rot));
    }

    public BlockPos world(BlockPos rel) {
        V3 r = rotRel(new V3(rel.getX(), rel.getY(), rel.getZ()), rot);
        BlockPos o = BlockPos.containing(origin.x(), origin.y(), origin.z());
        return o.offset((int) Math.round(r.x()), (int) Math.round(r.y()), (int) Math.round(r.z()));
    }

    public Direction worldFacing(StakeBp s) {
        return rotDir(s.facing(), rot);
    }

    public List<V3> worldTies() {
        List<V3> out = new ArrayList<>();
        for (V3 t : ties) out.add(world(t));
        return out;
    }

    /** 놓을 때 입구가 바라볼 방향(수평 단위 벡터)에 맞는 회전. 입구가 없으면 0 (회수 때의 북쪽이 북쪽). */
    public int baseRotToward(double dx, double dz) {
        if (!hasDoor()) return 0;
        // 설계도에서 입구 바깥은 북쪽(0,-1). 북→0, 동→1, 남→2, 서→3
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? 1 : 3;
        return dz > 0 ? 2 : 0;
    }

    // ------------------------------------------------------------------ 진행 단계

    @Nullable
    public Part stage() {
        for (boolean b : donePoles) if (!b) return Part.POLE;
        for (boolean b : doneStakes) if (!b) return Part.STAKE;
        for (boolean b : doneRopes) if (!b) return Part.ROPE;
        return Part.TARP;
    }

    public boolean anyDone() {
        for (boolean b : donePoles) if (b) return true;
        for (boolean b : doneStakes) if (b) return true;
        for (boolean b : doneRopes) if (b) return true;
        return false;
    }

    // ------------------------------------------------------------------ 바닥 판정

    /** 놓을 수 없게 하는 칸: 받침이 맞지 않는 바닥, 부품이 지나갈 칸이 막힌 곳. */
    public List<BlockPos> problems(BlockGetter level) {
        List<BlockPos> bad = new ArrayList<>();
        Stability.Ground g = Planner.ground(level);
        for (PoleBp p : poles) {
            V3 a = world(p.a()), b = world(p.b());
            if (p.contactA() && !Stability.blockContact(a, g)) bad.add(below(a));
            if (p.contactB() && !Stability.blockContact(b, g)) bad.add(below(b));
            for (Raster.Cell c : Raster.cells(a, b)) {
                BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
                if (!Planner.poleCanPass(level.getBlockState(pos))) bad.add(pos);
            }
        }
        for (StakeBp s : stakes) {
            BlockPos pos = world(s.rel());
            BlockState cur = level.getBlockState(pos);
            if (!(cur.isAir() || cur.canBeReplaced() || cur.getBlock() instanceof StakeBlock)) bad.add(pos);
            BlockPos sup = pos.relative(worldFacing(s).getOpposite());
            if (!StakeBlock.canHold(level.getBlockState(sup))) bad.add(sup);
        }
        if (relShape().ok()) {
            BlockPos o = BlockPos.containing(origin.x(), origin.y(), origin.z());
            for (long cell : relCells()) {
                BlockPos p = BlockPos.of(cell).offset(o);
                BlockState st = level.getBlockState(p);
                if (st.isAir() || st.canBeReplaced() || Planner.ours(st)) continue;
                bad.add(p);
            }
        }
        return bad;
    }

    private static BlockPos below(V3 p) {
        return BlockPos.containing(p.x(), p.y() - 0.01, p.z());
    }

    /** 상대 막 모양의 기준점: 칸 (0,0,0) 의 가로 가운데·바닥. 실제 기준점은 언제나 여기서 정수 칸만큼 옮긴 곳이다. */
    public static final V3 REL_ORIGIN = new V3(0.5, 0, 0.5);

    private transient Membrane.Result relShape;
    private transient long[] relCells;
    private transient int relRot = -1;

    /**
     * 투영용 막 모양 (실제 설치는 Planner.planTarp 로 다시 검증한다). 0.4.10: 기준점 REL_ORIGIN 에서 회전마다 한 번만 구한다.
     * 기준점은 정수 칸 단위로만 움직이므로 실제 모양은 이것을 (origin − REL_ORIGIN) 만큼 옮긴 것이다.
     * 전엔 투영 기준 칸이 바뀔 때마다 막을 두 번(모양·바닥 판정) 새로 계산했다.
     */
    public Membrane.Result relShape() {
        if (relShape == null || relRot != rot) {
            V3 o = origin;
            origin = REL_ORIGIN;
            try {
                List<Membrane.Obstacle> obs = new ArrayList<>();
                for (PoleBp p : poles) obs.add(new Membrane.Obstacle(world(p.a()), world(p.b()), Spec.POLE_CLEAR, true));
                for (RopeBp r : ropes) obs.add(new Membrane.Obstacle(world(r.a()), world(r.b()), Spec.ROPE_CLEAR, false));
                relShape = Membrane.compute(worldTies(), obs);
            } finally {
                origin = o;
            }
            relCells = relShape.ok() ? Planner.masks(relShape, null, false).keySet().toLongArray() : new long[0];
            relRot = rot;
        }
        return relShape;
    }

    /** relShape 의 천 칸 (REL_ORIGIN 기준). */
    public long[] relCells() {
        relShape();
        return relCells;
    }

    /** 실제 모양까지의 평행이동 (정수 칸). */
    public V3 relOffset() {
        return origin.sub(REL_ORIGIN);
    }

    // ------------------------------------------------------------------ 저장

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag ps = new ListTag();
        for (PoleBp p : poles) {
            CompoundTag c = new CompoundTag();
            c.put("a", v3(p.a()));
            c.put("b", v3(p.b()));
            c.putInt("v", p.value());
            c.putBoolean("ca", p.contactA());
            c.putBoolean("cb", p.contactB());
            ps.add(c);
        }
        t.put("poles", ps);
        ListTag ss = new ListTag();
        for (StakeBp s : stakes) {
            CompoundTag c = new CompoundTag();
            c.putIntArray("p", new int[]{s.rel().getX(), s.rel().getY(), s.rel().getZ()});
            c.putInt("f", s.facing().get3DDataValue());
            ss.add(c);
        }
        t.put("stakes", ss);
        ListTag rs = new ListTag();
        for (RopeBp r : ropes) {
            CompoundTag c = new CompoundTag();
            c.put("a", v3(r.a()));
            c.put("b", v3(r.b()));
            rs.add(c);
        }
        t.put("ropes", rs);
        ListTag ts = new ListTag();
        for (V3 v : ties) ts.add(v3(v));
        t.put("ties", ts);
        t.putInt("kind", kind);
        t.putInt("color", color);
        if (doorA != null) {
            t.put("doorA", v3(doorA));
            t.put("doorB", v3(doorB));
        }
        t.putBoolean("anchored", anchored);
        t.put("origin", v3(origin));
        t.putInt("rot", rot);
        t.putByteArray("dp", bits(donePoles));
        t.putByteArray("ds", bits(doneStakes));
        t.putByteArray("dr", bits(doneRopes));
        return t;
    }

    public static Kit load(CompoundTag t) {
        Kit k = new Kit();
        for (Tag raw : t.getList("poles", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) raw;
            k.poles.add(new PoleBp(v3(c.getCompound("a")), v3(c.getCompound("b")), c.getInt("v"), c.getBoolean("ca"), c.getBoolean("cb")));
        }
        for (Tag raw : t.getList("stakes", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) raw;
            int[] p = c.getIntArray("p");
            if (p.length == 3) k.stakes.add(new StakeBp(new BlockPos(p[0], p[1], p[2]), Direction.from3DDataValue(c.getInt("f"))));
        }
        for (Tag raw : t.getList("ropes", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) raw;
            k.ropes.add(new RopeBp(v3(c.getCompound("a")), v3(c.getCompound("b"))));
        }
        for (Tag raw : t.getList("ties", Tag.TAG_COMPOUND)) k.ties.add(v3((CompoundTag) raw));
        k.kind = t.getInt("kind");
        k.color = t.getInt("color");
        if (t.contains("doorA")) {
            k.doorA = v3(t.getCompound("doorA"));
            k.doorB = v3(t.getCompound("doorB"));
        }
        k.anchored = t.getBoolean("anchored");
        k.origin = v3(t.getCompound("origin"));
        k.rot = t.getInt("rot");
        k.donePoles = unbits(t.getByteArray("dp"), k.poles.size());
        k.doneStakes = unbits(t.getByteArray("ds"), k.stakes.size());
        k.doneRopes = unbits(t.getByteArray("dr"), k.ropes.size());
        return k;
    }

    private static byte[] bits(boolean[] b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) (b[i] ? 1 : 0);
        return out;
    }

    private static boolean[] unbits(byte[] b, int n) {
        boolean[] out = new boolean[n];
        for (int i = 0; i < n && i < b.length; i++) out[i] = b[i] != 0;
        return out;
    }

    static CompoundTag v3(V3 p) {
        CompoundTag c = new CompoundTag();
        c.putDouble("x", p.x());
        c.putDouble("y", p.y());
        c.putDouble("z", p.z());
        return c;
    }

    static V3 v3(CompoundTag c) {
        return new V3(c.getDouble("x"), c.getDouble("y"), c.getDouble("z"));
    }

    @Nullable
    public static Kit of(ItemStack stack) {
        CompoundTag t = stack.get(TarpRegistry.KIT.get());
        return t == null || t.isEmpty() ? null : load(t);
    }

    public static void store(ItemStack stack, @Nullable Kit k) {
        if (k == null) stack.remove(TarpRegistry.KIT.get());
        else stack.set(TarpRegistry.KIT.get(), k.save());
    }

    // ------------------------------------------------------------------ 서버: 회수

    public enum CaptureError { LEAF, WALL_STAKE, SHARED, NO_TARP }

    /** 천 칸을 웅크리고 우클릭: 그 천막을 키트에 담고 월드에서 없앤다. */
    public static void capture(ServerPlayer pl, BlockPos canvas) {
        ServerLevel level = pl.serverLevel();
        ItemStack stack = pl.getMainHandItem();
        if (!(stack.getItem() instanceof com.tarp.content.KitItem)) return;
        if (!pl.isShiftKeyDown()) return; // 0.3.10: 웅크리고 우클릭만 회수
        if (of(stack) != null) {
            TarpService.tellPublic(pl, Component.translatable("tarp.msg.kit.full"));
            return;
        }
        if (!(level.getBlockEntity(canvas) instanceof com.tarp.content.CanvasCellBlockEntity be)) return;
        CaptureError err = captureInto(level, stack, be.tarp());
        if (err != null) {
            TarpService.tellPublic(pl, Component.translatable("tarp.msg.kit.capture." + err.name().toLowerCase()));
            return;
        }
        Kit k = of(stack);
        level.playSound(null, pl.blockPosition(), SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 1, 0.8F);
        TarpService.tellPublic(pl, Component.translatable("tarp.msg.kit.captured", k.poles.size(), k.stakes.size(), k.ropes.size()));
    }

    /** 천막을 빈 키트에 담는다. 성공하면 null. */
    @Nullable
    public static CaptureError captureInto(ServerLevel level, ItemStack stack, int tarpId) {
        TarpData d = TarpData.get(level);
        TarpRec rec = d.s.tarps.get(tarpId);
        CaptureError err = null;
        if (rec == null || !rec.shape().ok()) err = CaptureError.NO_TARP;
        else if (rec.isLeaf()) err = CaptureError.LEAF;
        Set<Integer> poleIds = new LinkedHashSet<>(), ropeIds = new LinkedHashSet<>();
        Set<Long> stakeKeys = new LinkedHashSet<>();
        if (err == null) {
            List<Integer> seeds = new ArrayList<>();
            for (int id : rec.poleDeps) seeds.add(id);
            List<Stability.Pole> comp = d.s.component(seeds);
            for (Stability.Pole p : comp) poleIds.add(p.id());
            for (int id : rec.ropeDeps) if (d.s.ropes.containsKey(id)) ropeIds.add(id);
            for (Stability.Rope r : Planner.ropesFor(d.s, comp)) ropeIds.add(r.id());
            for (long s : rec.stakeDeps) stakeKeys.add(s);
            for (V3 t : rec.ties) for (Stability.Rope r : d.s.ropesAt(t)) ropeIds.add(r.id());
            for (int id : ropeIds) {
                Stability.Rope r = d.s.ropes.get(id);
                for (V3 e : new V3[]{r.a(), r.b()}) if (Planner.stakeAt(level, e)) stakeKeys.add(BlockPos.containing(e.x(), e.y(), e.z()).asLong());
            }
            for (long s : stakeKeys) {
                BlockState st = level.getBlockState(BlockPos.of(s));
                if (st.getBlock() instanceof StakeBlock && st.getValue(StakeBlock.FACING) != Direction.UP) err = CaptureError.WALL_STAKE;
            }
            for (TarpRec o : d.s.tarps.values()) {
                if (o.id == rec.id) continue;
                for (int x : o.poleDeps) if (poleIds.contains(x)) err = CaptureError.SHARED;
                for (int x : o.ropeDeps) if (ropeIds.contains(x)) err = CaptureError.SHARED;
                for (long x : o.stakeDeps) if (stakeKeys.contains(x)) err = CaptureError.SHARED;
            }
        }
        if (err != null) return err;
        Kit k = new Kit();
        k.kind = rec.kind;
        k.color = rec.color;
        Door door = rec.door();
        V3 o;
        int norm = 0;
        if (door != null) {
            Membrane.Edge e = door.e;
            double mid = (door.s0 + door.s1) / 2;
            o = new V3(Math.floor(e.px(mid)) + 0.5, door.floor, Math.floor(e.pz(mid)) + 0.5);
            // 입구 바깥(-n)을 북쪽으로: 북 0, 동이면 반시계 한 번(=시계 3), 남 2, 서 1
            double ox = -e.nx, oz = -e.nz;
            if (Math.abs(ox) > Math.abs(oz)) norm = ox > 0 ? 3 : 1;
            else norm = oz > 0 ? 2 : 0;
        } else {
            V3 c = rec.centre();
            double minY = Double.MAX_VALUE;
            for (V3 t : rec.ties) minY = Math.min(minY, t.y());
            o = new V3(Math.floor(c.x()) + 0.5, Math.floor(minY), Math.floor(c.z()) + 0.5);
        }
        final V3 origin = o;
        final int nr = norm;
        java.util.function.Function<V3, V3> rel = p -> rotRel(p.sub(origin), nr);
        Stability.Ground g = Planner.ground(level);
        for (int id : poleIds) {
            Stability.Pole p = d.s.poles.get(id);
            k.poles.add(new PoleBp(rel.apply(p.a()), rel.apply(p.b()), d.value(p), Stability.blockContact(p.a(), g), Stability.blockContact(p.b(), g)));
        }
        BlockPos ob = BlockPos.containing(origin.x(), origin.y(), origin.z());
        for (long s : stakeKeys) {
            BlockPos pos = BlockPos.of(s);
            BlockState st = level.getBlockState(pos);
            if (!(st.getBlock() instanceof StakeBlock)) continue;
            V3 r = rotRel(new V3(pos.getX() - ob.getX(), pos.getY() - ob.getY(), pos.getZ() - ob.getZ()), nr);
            k.stakes.add(new StakeBp(new BlockPos((int) Math.round(r.x()), (int) Math.round(r.y()), (int) Math.round(r.z())), rotDir(st.getValue(StakeBlock.FACING), nr)));
        }
        for (int id : ropeIds) {
            Stability.Rope r = d.s.ropes.get(id);
            k.ropes.add(new RopeBp(rel.apply(r.a()), rel.apply(r.b())));
        }
        for (V3 t : rec.ties) k.ties.add(rel.apply(t));
        if (door != null) {
            k.doorA = rel.apply(door.e.a);
            k.doorB = rel.apply(door.e.b);
        }
        k.donePoles = new boolean[k.poles.size()];
        k.doneStakes = new boolean[k.stakes.size()];
        k.doneRopes = new boolean[k.ropes.size()];
        TarpService.kitRemove(level, rec.id, poleIds, ropeIds, stakeKeys);
        store(stack, k);
        return null;
    }

    // ------------------------------------------------------------------ 서버: 위치 고정·부품 설치

    public static void anchor(ServerPlayer pl, V3 origin, int rot) {
        if (!TarpService.finite(origin) || pl.getEyePosition().distanceTo(new net.minecraft.world.phys.Vec3(origin.x(), origin.y(), origin.z())) > 16) return;
        ItemStack stack = pl.getMainHandItem();
        int bad = anchorStack(pl.serverLevel(), stack, origin, rot);
        if (bad < 0) return;
        if (bad == 0 && com.tarp.TarpConfig.KIT_INSTANT.get()) {
            Component msg = placeAll(pl.serverLevel(), stack);
            TarpService.tellPublic(pl, msg != null ? msg : Component.translatable("tarp.msg.kit.done"));
            return;
        }
        TarpService.tellPublic(pl, Component.translatable(bad == 0 ? "tarp.msg.kit.anchored" : "tarp.msg.kit.bad_ground", bad));
    }

    /** 위치를 고정한다. 맞지 않는 칸 수를 돌려준다 (0 이면 고정됨, -1 이면 해당 없음). */
    public static int anchorStack(ServerLevel level, ItemStack stack, V3 origin, int rot) {
        Kit k = of(stack);
        if (k == null || k.anchored) return -1;
        k.origin = new V3(Math.floor(origin.x()) + 0.5, Math.floor(origin.y()), Math.floor(origin.z()) + 0.5);
        k.rot = ((rot % 4) + 4) % 4;
        List<BlockPos> bad = k.problems(level);
        if (!bad.isEmpty()) return bad.size();
        k.anchored = true;
        store(stack, k);
        return 0;
    }

    /**
     * 즉시 설치 (설정 kit.instantPitch): 남은 부품을 지지대 → 말뚝 → 노끈 → 방수포 순으로 한꺼번에 놓는다.
     * 중간에 막히면 거기서 멈추고 그 안내를 돌려준다 (키트는 고정된 채 남아 보통 방식으로 이어서 칠 수 있다). 다 치면 null.
     */
    @Nullable
    public static Component placeAll(ServerLevel level, ItemStack stack) {
        for (int guard = 0; guard < 4096; guard++) {
            Kit k = of(stack);
            if (k == null) return null;
            if (!k.anchored) return null;
            Part stage = k.stage();
            int idx = switch (stage) {
                case POLE -> firstUndone(k.donePoles);
                case STAKE -> firstUndone(k.doneStakes);
                case ROPE -> firstUndone(k.doneRopes);
                case TARP -> 0;
            };
            Component msg = placePartStack(level, stack, stage, idx);
            if (msg != null) return msg;
        }
        return null;
    }

    private static int firstUndone(boolean[] done) {
        for (int i = 0; i < done.length; i++) if (!done[i]) return i;
        return -1;
    }

    /** 고정을 푼다 (아직 아무것도 놓지 않았을 때만). */
    public static void unanchor(ServerPlayer pl) {
        ItemStack stack = pl.getMainHandItem();
        Kit k = of(stack);
        if (k == null || !k.anchored) return;
        if (k.anyDone()) {
            TarpService.tellPublic(pl, Component.translatable("tarp.msg.kit.in_progress"));
            return;
        }
        k.anchored = false;
        store(stack, k);
        TarpService.tellPublic(pl, Component.translatable("tarp.msg.kit.unanchored"));
    }

    public static void placePart(ServerPlayer pl, int partOrdinal, int index) {
        if (partOrdinal < 0 || partOrdinal >= Part.values().length) return;
        ItemStack stack = pl.getMainHandItem();
        Kit before = of(stack);
        Component msg = placePartStack(pl.serverLevel(), stack, Part.values()[partOrdinal], index);
        if (msg != null) TarpService.tellPublic(pl, msg);
        else if (before != null && of(stack) == null) TarpService.tellPublic(pl, Component.translatable("tarp.msg.kit.done"));
    }

    /** 부품 하나를 놓는다. 문제가 있으면 그 안내를, 놓았으면 null. 방수포까지 놓으면 키트가 빈다. */
    @Nullable
    public static Component placePartStack(ServerLevel level, ItemStack stack, Part part, int index) {
        Kit k = of(stack);
        if (k == null || !k.anchored) return null;
        Part stage = k.stage();
        if (part != stage) return Component.translatable("tarp.msg.kit.stage", Component.translatable("tarp.kit.part." + stage.name().toLowerCase()));
        switch (part) {
            case POLE -> {
                if (index < 0 || index >= k.poles.size() || k.donePoles[index]) return null;
                PoleBp p = k.poles.get(index);
                V3 a = k.world(p.a()), b = k.world(p.b());
                if (TarpService.checkPole(level, a, b) != null) {
                    return Component.translatable("tarp.msg.kit.blocked");
                }
                TarpService.putPoleRaw(level, a, b, p.value());
                k.donePoles[index] = true;
                level.playSound(null, BlockPos.containing(b.x(), b.y(), b.z()), SoundEvents.BAMBOO_WOOD_PLACE, SoundSource.BLOCKS, 1, 0.9F);
            }
            case STAKE -> {
                if (index < 0 || index >= k.stakes.size() || k.doneStakes[index]) return null;
                StakeBp s = k.stakes.get(index);
                BlockPos pos = k.world(s.rel());
                if (!TarpService.putStake(level, pos, k.worldFacing(s))) {
                    return Component.translatable("tarp.msg.kit.blocked");
                }
                k.doneStakes[index] = true;
                level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1, 1);
            }
            case ROPE -> {
                if (index < 0 || index >= k.ropes.size() || k.doneRopes[index]) return null;
                RopeBp r = k.ropes.get(index);
                V3 a = k.world(r.a()), b = k.world(r.b());
                TarpData d = TarpData.get(level);
                if (!Planner.isAnchor(level, d.s, a) || !Planner.isAnchor(level, d.s, b)) {
                    return Component.translatable("tarp.msg.kit.no_anchor");
                }
                TarpService.addRope(level, a, b);
                k.doneRopes[index] = true;
                level.playSound(null, BlockPos.containing(b.x(), b.y(), b.z()), SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 1, 1);
            }
            case TARP -> {
                List<V3> ties = k.worldTies();
                TarpItem item = TarpRegistry.tarpItem(k.kind);
                Planner.TarpPlan plan = Planner.planTarp(level, TarpData.get(level).s, ties, item);
                if (!plan.ok() && plan.error != Planner.TarpError.TOO_BIG) {
                    return Component.translatable("tarp.msg.tarp." + plan.error.name().toLowerCase(), (int) Math.ceil(plan.area), (int) item.maxArea());
                }
                TarpRec rec = TarpService.addTarp(level, plan, ties, k.kind, k.color, "");
                if (k.doorA != null) {
                    V3 da = k.world(k.doorA), db = k.world(k.doorB);
                    Membrane.Result r = rec.shape();
                    for (int e = 0; e < r.edges.length; e++) {
                        Membrane.Edge ed = r.edges[e];
                        boolean same = (ed.a.dist(da) < 1e-3 && ed.b.dist(db) < 1e-3) || (ed.a.dist(db) < 1e-3 && ed.b.dist(da) < 1e-3);
                        if (same) {
                            TarpService.setDoor(level, rec, e);
                            break;
                        }
                    }
                }
                level.playSound(null, BlockPos.containing(rec.centre().x(), rec.centre().y(), rec.centre().z()), SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1, 0.8F);
                store(stack, null);
                return null;
            }
        }
        store(stack, k);
        return null;
    }

    /** 재료 합계 (툴팁): {지지대, 말뚝, 노끈}. 노끈은 밑단 고리 포함. */
    public int[] materials() {
        int p = 0;
        for (PoleBp b : poles) p += b.value();
        int r = ties.size();
        for (RopeBp b : ropes) r += (int) Math.ceil(b.a().dist(b.b()) - 1e-6);
        return new int[]{p, stakes.size(), r};
    }

}
