package com.tarp.gametest;

import com.tarp.Tarp;
import com.tarp.content.CanvasCellBlockEntity;
import com.tarp.content.PoleCellBlock;
import com.tarp.content.StakeBlock;
import com.tarp.content.TarpRegistry;
import com.tarp.core.Door;
import com.tarp.core.Membrane;
import com.tarp.core.V3;
import com.tarp.world.Planner;
import com.tarp.world.TarpData;
import com.tarp.world.TarpRec;
import com.tarp.world.TarpService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 월드 동작 시험. 구조물 floor: 16×6×16, 바닥 돌은 상대 y=1 → 시험 좌표는 y 를 한 칸 내려 쓴다 (땅 윗면 = 시험 y 1).
 * 좌표 도우미 at() 는 상대 좌표를 월드 좌표(실수)로 바꾼다.
 */
@GameTestHolder(Tarp.MODID)
@PrefixGameTestTemplate(false)
public final class TarpGameTests {
    private static final String FLOOR = "floor";

    private TarpGameTests() {}

    /** 구조물 바닥 돌은 상대 y=1 이라, 시험 좌표 y 에 1 을 더한다 (땅 윗면 = 시험 y 1). */
    static V3 at(GameTestHelper h, double x, double y, double z) {
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        return new V3(o.getX() + x, o.getY() + y + 1, o.getZ() + z);
    }

    static BlockPos bp(int x, int y, int z) {
        return new BlockPos(x, y + 1, z);
    }

    static TarpData data(GameTestHelper h) {
        return TarpData.get(h.getLevel());
    }

    static int dropped(GameTestHelper h, Item item) {
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        List<ItemEntity> items = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(o).inflate(20));
        int n = 0;
        for (ItemEntity e : items) if (e.getItem().is(item)) n += e.getItem().getCount();
        return n;
    }

    static void stake(GameTestHelper h, int x, int y, int z) {
        h.setBlock(bp(x, y, z), TarpRegistry.STAKE.get().defaultBlockState().setValue(StakeBlock.FACING, Direction.UP));
    }

    static void check(boolean ok, String msg) {
        if (!ok) throw new net.minecraft.gametest.framework.GameTestAssertException(msg);
    }

    @GameTest(template = FLOOR)
    public static void verticalStands(GameTestHelper h) {
        String below = h.getBlockState(bp(2, 0, 2)) + " / " + h.getBlockState(bp(2, 1, 2)) + " / " + h.getBlockState(bp(2, 2, 2));
        int id = TarpService.addPole(h.getLevel(), at(h, 2.5, 1, 2.5), at(h, 2.5, 4, 2.5));
        check(id > 0, "vertical should stand; blocks y0/y1/y2 = " + below + " ground=" + Planner.ground(h.getLevel()).solid(h.absolutePos(bp(2, 0, 2)).getX(), h.absolutePos(bp(2, 0, 2)).getY(), h.absolutePos(bp(2, 0, 2)).getZ()));
        for (int y = 1; y <= 3; y++) h.assertBlockPresent(TarpRegistry.POLE_CELL.get(), bp(2, y, 2));
        h.assertBlockNotPresent(TarpRegistry.POLE_CELL.get(), bp(2, 4, 2));
        h.succeed();
    }

    @GameTest(template = FLOOR)
    public static void lonelyDiagonalFalls(GameTestHelper h) {
        int id = TarpService.addPole(h.getLevel(), at(h, 2.5, 1, 2.5), at(h, 4.5, 3, 2.5));
        check(id < 0, "single diagonal must fall");
        check(dropped(h, TarpRegistry.POLE.get()) == 2, "fallen 2.83-long pole gives back 2 items, got " + dropped(h, TarpRegistry.POLE.get()));
        h.assertBlockNotPresent(TarpRegistry.POLE_CELL.get(), bp(2, 1, 2));
        h.succeed();
    }

    @GameTest(template = FLOOR)
    public static void tipiOrder(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        V3 apex = at(h, 5.5, 5, 5.5);
        int post = TarpService.addPole(l, at(h, 5.5, 1, 5.5), apex);
        int a = TarpService.addPole(l, at(h, 2.5, 1, 4.5), apex);
        int b = TarpService.addPole(l, at(h, 8.5, 1, 4.5), apex);
        int c = TarpService.addPole(l, at(h, 5.5, 1, 8.5), apex);
        check(post > 0 && a > 0 && b > 0 && c > 0, "legs lean on the post");
        // 수직을 아래에서 잘라 치우면 세 다리만으로 선다
        h.getLevel().setBlock(h.absolutePos(bp(5, 1, 5)), Blocks.AIR.defaultBlockState(), 3);
        TarpData d = data(h);
        check(d.s.poles.containsKey(a) && d.s.poles.containsKey(b) && d.s.poles.containsKey(c), "tripod stands");
        check(!d.s.poles.containsKey(post), "post was cut");
        h.assertBlockNotPresent(TarpRegistry.POLE_CELL.get(), bp(5, 2, 5));
        h.succeed();
    }

    @GameTest(template = FLOOR)
    public static void cutBottomDropsAll(GameTestHelper h) {
        int id = TarpService.addPole(h.getLevel(), at(h, 2.5, 1, 2.5), at(h, 2.5, 5, 2.5));
        check(id > 0, "stands");
        h.getLevel().destroyBlock(h.absolutePos(bp(2, 1, 2)), false);
        check(data(h).s.polesAt(h.absolutePos(bp(2, 3, 2)).asLong()).isEmpty(), "upper piece fell");
        h.assertBlockNotPresent(TarpRegistry.POLE_CELL.get(), bp(2, 3, 2));
        check(dropped(h, TarpRegistry.POLE.get()) == 2, "4-long pole cost 2 and gives back exactly 2, got " + dropped(h, TarpRegistry.POLE.get()));
        h.succeed();
    }

    @GameTest(template = FLOOR)
    public static void cutTopShortens(GameTestHelper h) {
        TarpService.addPole(h.getLevel(), at(h, 2.5, 1, 2.5), at(h, 2.5, 5, 2.5));
        h.getLevel().destroyBlock(h.absolutePos(bp(2, 4, 2)), false);
        h.assertBlockPresent(TarpRegistry.POLE_CELL.get(), bp(2, 3, 2));
        check(dropped(h, TarpRegistry.POLE.get()) == 1, "cut leaves 1 in the 3-long piece and drops 1");
        h.succeed();
    }

    /** 쐐기: 임시 수직으로 꼭대기를 받친 채 ㅅ자 다리·용마루·당김줄을 놓고 수직을 치운다. */
    static int[] wedge(GameTestHelper h, boolean guys) {
        ServerLevel l = h.getLevel();
        V3 f = at(h, 5.5, 4, 3.5), b = at(h, 5.5, 4, 9.5);
        int p1 = TarpService.addPole(l, at(h, 5.5, 1, 3.5), f);
        int p2 = TarpService.addPole(l, at(h, 5.5, 1, 9.5), b);
        int[] legs = {
                TarpService.addPole(l, at(h, 3.5, 1, 3.5), f), TarpService.addPole(l, at(h, 7.5, 1, 3.5), f),
                TarpService.addPole(l, at(h, 3.5, 1, 9.5), b), TarpService.addPole(l, at(h, 7.5, 1, 9.5), b),
                TarpService.addPole(l, f, b)};
        for (int x : legs) check(x > 0, "frame leans on posts");
        if (guys) {
            stake(h, 5, 1, 0);
            stake(h, 5, 1, 13);
            TarpService.addRope(l, f, at(h, 5.5, 1.5, 0.5));
            TarpService.addRope(l, b, at(h, 5.5, 1.5, 13.5));
        }
        l.setBlock(h.absolutePos(bp(5, 1, 3)), Blocks.AIR.defaultBlockState(), 3);
        l.setBlock(h.absolutePos(bp(5, 1, 9)), Blocks.AIR.defaultBlockState(), 3);
        check(!data(h).s.poles.containsKey(p1) && !data(h).s.poles.containsKey(p2), "posts removed");
        return legs;
    }

    @GameTest(template = FLOOR)
    public static void wedgeWithGuyLinesStands(GameTestHelper h) {
        int[] legs = wedge(h, true);
        for (int x : legs) check(data(h).s.poles.containsKey(x), "wedge stands with guy lines");
        h.succeed();
    }

    @GameTest(template = FLOOR)
    public static void wedgeWithoutGuyLinesFalls(GameTestHelper h) {
        int[] legs = wedge(h, false);
        for (int x : legs) check(!data(h).s.poles.containsKey(x), "wedge without guy lines falls");
        h.succeed();
    }

    static List<V3> pyramidTies(GameTestHelper h) {
        stake(h, 2, 1, 2);
        stake(h, 7, 1, 2);
        stake(h, 7, 1, 7);
        stake(h, 2, 1, 7);
        return List.of(at(h, 2.5, 1.5, 2.5), at(h, 7.5, 1.5, 2.5), at(h, 7.5, 1.5, 7.5), at(h, 2.5, 1.5, 7.5));
    }

    @GameTest(template = FLOOR)
    public static void pyramidTarpAndCollapse(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        List<V3> ties = pyramidTies(h);
        int post = TarpService.addPole(l, at(h, 5, 1, 5), at(h, 5, 5, 5));
        check(post > 0, "post");
        Planner.TarpPlan plan = Planner.planTarp(l, data(h).s, ties, TarpRegistry.tarpItem(1));
        check(plan.ok(), "plan " + plan.error + " area " + plan.area + " blocked " + plan.blocked.size() + " exposed " + (plan.shape == null ? -1 : plan.shape.exposed.size()));
        TarpRec rec = TarpService.addTarp(l, plan, ties, 1, 14, "");
        check(rec.cells.length > 20, "canvas cells placed: " + rec.cells.length);
        BlockPos any = BlockPos.of(rec.cells[0]);
        check(l.getBlockState(any).is(TarpRegistry.CANVAS_CELL.get()), "canvas block");
        // 골조를 자르면 방수포가 떨어진다
        l.destroyBlock(h.absolutePos(bp(5, 1, 5)), false);
        check(!data(h).s.tarps.containsKey(rec.id), "tarp dropped");
        check(!l.getBlockState(any).is(TarpRegistry.CANVAS_CELL.get()), "canvas removed");
        check(dropped(h, TarpRegistry.tarp(1, 14)) == 1, "tarp item back");
        check(dropped(h, TarpRegistry.ROPE.get()) == 4, "ropes back");
        h.succeed();
    }

    @GameTest(template = FLOOR)
    public static void doorOpensAndCloses(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        List<V3> ties = pyramidTies(h);
        TarpService.addPole(l, at(h, 5, 1, 5), at(h, 5, 5.5, 5));
        Planner.TarpPlan plan = Planner.planTarp(l, data(h).s, ties, TarpRegistry.tarpItem(2));
        check(plan.ok(), "plan " + plan.error);
        TarpRec rec = TarpService.addTarp(l, plan, ties, 2, 0, "");
        Membrane.Result r = rec.shape();
        int edge = -1;
        for (int e = 0; e < r.edges.length; e++) if (Door.plan(r, e, rec.obstacles).ok()) edge = e;
        check(edge >= 0, "some side takes a door");
        check(TarpService.setDoor(l, rec, edge), "door set");
        int closed = bits(l, rec);
        TarpService.setOpen(l, rec, true);
        int open = bits(l, rec);
        check(open < closed, "opening clears collision: " + open + " < " + closed);
        TarpService.setOpen(l, rec, false);
        check(bits(l, rec) == closed, "closing restores collision");
        h.succeed();
    }

    /** 천막 키트: 담기 → 다른 자리·방향에 부품 순서대로 다시 치기. */
    @GameTest(template = FLOOR)
    public static void kitRoundTrip(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        List<V3> ties = pyramidTies(h);
        TarpService.addPole(l, at(h, 5, 1, 5), at(h, 5, 5.5, 5));
        Planner.TarpPlan plan = Planner.planTarp(l, data(h).s, ties, TarpRegistry.tarpItem(2));
        check(plan.ok(), "plan " + plan.error);
        TarpRec rec = TarpService.addTarp(l, plan, ties, 2, 5, "");
        Membrane.Result r = rec.shape();
        int edge = -1;
        for (int e = 0; e < r.edges.length; e++) if (Door.plan(r, e, rec.obstacles).ok()) edge = e;
        check(edge >= 0 && TarpService.setDoor(l, rec, edge), "door");

        net.minecraft.world.item.ItemStack kit = new net.minecraft.world.item.ItemStack(TarpRegistry.KIT_ITEM.get());
        check(com.tarp.world.Kit.captureInto(l, kit, rec.id) == null, "captured");
        check(data(h).s.tarps.isEmpty() && data(h).s.poles.isEmpty(), "tent gone from the world");
        h.assertBlockNotPresent(TarpRegistry.STAKE.get(), bp(2, 1, 2));
        check(dropped(h, TarpRegistry.POLE.get()) == 0 && dropped(h, TarpRegistry.ROPE.get()) == 0, "nothing dropped");
        com.tarp.world.Kit k = com.tarp.world.Kit.of(kit);
        check(k != null && k.hasDoor() && k.stakes.size() == 4, "blueprint");

        int bad = com.tarp.world.Kit.anchorStack(l, kit, at(h, 8.5, 1, 8.5), 1);
        check(bad == 0, "anchor: bad cells " + bad);
        k = com.tarp.world.Kit.of(kit);
        for (com.tarp.world.Kit.Part part : new com.tarp.world.Kit.Part[]{com.tarp.world.Kit.Part.POLE, com.tarp.world.Kit.Part.STAKE, com.tarp.world.Kit.Part.ROPE}) {
            int n = switch (part) {
                case POLE -> k.poles.size();
                case STAKE -> k.stakes.size();
                default -> k.ropes.size();
            };
            for (int i = 0; i < n; i++) {
                var msg = com.tarp.world.Kit.placePartStack(l, kit, part, i);
                check(msg == null, part + " " + i + ": " + (msg == null ? "" : msg.getString()));
            }
        }
        // 순서를 지키지 않으면 거절한다 (이미 방수포 차례라 지지대는 거절)
        check(com.tarp.world.Kit.placePartStack(l, kit, com.tarp.world.Kit.Part.POLE, 0) != null || com.tarp.world.Kit.of(kit).poles.isEmpty(), "order");
        var msg = com.tarp.world.Kit.placePartStack(l, kit, com.tarp.world.Kit.Part.TARP, 0);
        check(msg == null, "tarp: " + (msg == null ? "" : msg.getString()));
        check(com.tarp.world.Kit.of(kit) == null, "kit emptied");
        check(data(h).s.tarps.size() == 1, "tarp pitched");
        TarpRec again = data(h).s.tarps.values().iterator().next();
        check(again.doorEdge >= 0, "door restored");
        check(again.color == 5 && again.kind == 2, "same tarp");
        h.succeed();
    }

    static int bits(ServerLevel l, TarpRec rec) {
        int n = 0;
        for (long c : rec.cells) if (l.getBlockEntity(BlockPos.of(c)) instanceof CanvasCellBlockEntity be) n += Long.bitCount(be.mask());
        return n;
    }

    @GameTest(template = FLOOR)
    public static void stakeOnWallDropsWithWall(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        h.setBlock(bp(4, 1, 4), Blocks.OAK_LOG.defaultBlockState());
        h.setBlock(bp(5, 1, 4), TarpRegistry.STAKE.get().defaultBlockState().setValue(StakeBlock.FACING, Direction.EAST));
        stake(h, 9, 1, 4);
        TarpService.addRope(l, at(h, 5.5, 1.5, 4.5), at(h, 9.5, 1.5, 4.5));
        check(data(h).s.ropes.size() >= 1, "rope");
        int before = data(h).s.ropes.size();
        h.setBlock(bp(4, 1, 4), Blocks.AIR.defaultBlockState());
        h.assertBlockNotPresent(TarpRegistry.STAKE.get(), bp(5, 1, 4));
        check(data(h).s.ropes.size() == before - 1, "rope dropped with the stake");
        h.succeed();
    }

    @GameTest(template = FLOOR, timeoutTicks = 100)
    public static void leafCoverWithers(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        List<V3> ties = pyramidTies(h);
        TarpService.addPole(l, at(h, 5, 1, 5), at(h, 5, 3, 5));
        Planner.TarpPlan plan = Planner.planTarp(l, data(h).s, ties, TarpRegistry.LEAF_COVER.get());
        check(plan.ok(), "plan " + plan.error + " area " + plan.area);
        TarpRec rec = TarpService.addTarp(l, plan, ties, TarpRec.LEAF, 0, "minecraft:oak_leaves");
        rec.placedAt = l.getGameTime() - 24000;
        h.succeedWhen(() -> {
            check(!data(h).s.tarps.containsKey(rec.id), "leaf cover withered");
            check(dropped(h, TarpRegistry.LEAF_COVER.get()) == 0, "nothing comes back");
        });
    }

    /** 칸마다 잘라도 아이템이 늘지 않는다. */
    @GameTest(template = FLOOR)
    public static void cuttingNeverDuplicates(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        // 기둥 둘 사이 수평 지지대 (길이 6 → 3개), 가운데부터 칸마다 잘라 본다
        TarpService.addPole(l, at(h, 2.5, 1, 2.5), at(h, 2.5, 3.5, 2.5));
        TarpService.addPole(l, at(h, 8.5, 1, 2.5), at(h, 8.5, 3.5, 2.5));
        int beam = TarpService.addPole(l, at(h, 2.5, 3.5, 2.5), at(h, 8.5, 3.5, 2.5));
        check(beam > 0, "beam");
        int before = data(h).value(data(h).s.poles.get(beam));
        check(before == 3, "6 long beam costs 3, got " + before);
        for (int x = 3; x <= 7; x++) {
            BlockPos p = h.absolutePos(bp(x, 3, 2));
            if (l.getBlockState(p).is(TarpRegistry.POLE_CELL.get())) l.destroyBlock(p, false);
        }
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        AABB box = new AABB(o).expandTowards(16, 8, 16);
        int left = 0;
        for (var p : data(h).s.poles.values()) {
            V3 m = p.a().lerp(p.b(), 0.5);
            if (box.contains(m.x(), m.y(), m.z())) left += data(h).value(p);
        }
        int drops = 0;
        for (ItemEntity e : l.getEntitiesOfClass(ItemEntity.class, box)) if (e.getItem().is(TarpRegistry.POLE.get())) drops += e.getItem().getCount();
        // 기둥 둘(각 2.5 → 2개) + 들보 3 = 7 을 넘지 않는다
        int total = left + drops;
        check(total <= 7, "no duplication: " + total);
        h.succeed();
    }

    @GameTest(template = FLOOR)
    public static void breakingCanvasTakesTarpDown(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        List<V3> ties = pyramidTies(h);
        TarpService.addPole(l, at(h, 5, 1, 5), at(h, 5, 4, 5));
        Planner.TarpPlan plan = Planner.planTarp(l, data(h).s, ties, TarpRegistry.tarpItem(0));
        check(plan.ok(), "plan " + plan.error);
        TarpRec rec = TarpService.addTarp(l, plan, ties, 0, 3, "");
        BlockPos any = BlockPos.of(rec.cells[rec.cells.length / 2]);
        l.destroyBlock(any, true);
        check(!data(h).s.tarps.containsKey(rec.id), "tarp taken down");
        for (long c : rec.cells) check(!l.getBlockState(BlockPos.of(c)).is(TarpRegistry.CANVAS_CELL.get()), "all canvas gone");
        check(dropped(h, TarpRegistry.tarp(0, 3)) == 1, "tarp back");
        check(dropped(h, TarpRegistry.ROPE.get()) == 4, "ropes back");
        h.succeed();
    }

    @GameTest(template = FLOOR)
    public static void poleBlockedBySolid(GameTestHelper h) {
        h.setBlock(bp(2, 2, 2), Blocks.STONE.defaultBlockState());
        check(TarpService.checkPole(h.getLevel(), at(h, 2.5, 1, 2.5), at(h, 2.5, 4, 2.5)) == TarpService.PoleResult.BLOCKED, "blocked");
        check(TarpService.checkPole(h.getLevel(), at(h, 2.5, 1, 2.5), at(h, 2.5, 30, 2.5)) == TarpService.PoleResult.TOO_LONG, "too long");
        h.assertBlockNotPresent(TarpRegistry.POLE_CELL.get(), bp(2, 1, 2));
        check(!(h.getBlockState(bp(2, 1, 2)).getBlock() instanceof PoleCellBlock), "nothing placed");
        h.succeed();
    }
}
