package com.tarp.world;

import com.tarp.content.StakeBlock;
import com.tarp.content.TarpRegistry;
import com.tarp.core.Door;
import com.tarp.core.Membrane;
import com.tarp.core.V3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * /tarp demo: 플레이어 앞(+x 방향)에 설계서의 천막 종류를 규칙대로 세운다. 모양·조작감 확인용.
 * 모두 일반 규칙(지지대·노끈·말뚝·방수포)으로만 짓는다. 천막 종류별 코드는 여기에도 없다.
 */
public final class Demo {
    private Demo() {}

    private static ServerLevel level;
    private static int ox, oy, oz;

    public static int build(ServerPlayer pl) {
        level = pl.serverLevel();
        BlockPos base = pl.blockPosition().offset(3, 0, -4);
        ox = base.getX();
        oz = base.getZ();
        oy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ox, oz);
        // 터 고르기: 60×14 를 풀밭으로
        for (int x = -1; x <= 60; x++) {
            for (int z = -4; z <= 14; z++) {
                level.setBlock(new BlockPos(ox + x, oy - 1, oz + z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int y = 0; y < 12; y++) level.setBlock(new BlockPos(ox + x, oy + y, oz + z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        List<String> done = new ArrayList<>();
        done.add("wedge " + wedge(0));
        done.add("pyramid " + pyramid(12));
        done.add("tipi " + tipi(23));
        done.add("bedouin " + bedouin(35));
        done.add("lean-to " + leanTo(48));
        pl.sendSystemMessage(Component.literal("TARP demo: " + String.join(", ", done)));
        return 1;
    }

    static V3 at(double x, double y, double z) {
        return new V3(ox + x, oy + y, oz + z);
    }

    static V3 stake(int x, int y, int z) {
        BlockPos p = new BlockPos(ox + x, oy + y, oz + z);
        level.setBlock(p, TarpRegistry.STAKE.get().defaultBlockState().setValue(StakeBlock.FACING, Direction.UP), 3);
        return Planner.stakeHead(level, p);
    }

    static V3 wallStake(int x, int y, int z, Direction face) {
        BlockPos p = new BlockPos(ox + x, oy + y, oz + z);
        level.setBlock(p, TarpRegistry.STAKE.get().defaultBlockState().setValue(StakeBlock.FACING, face), 3);
        return Planner.stakeHead(level, p);
    }

    static void cut(V3 foot) {
        level.destroyBlock(BlockPos.containing(foot.x(), foot.y() + 0.01, foot.z()), false);
    }

    static String tarp(List<V3> ties, int kind, int color, String leaf, boolean door) {
        Planner.TarpPlan plan = Planner.planTarp(level, TarpData.get(level).s, ties,
                kind == TarpRec.LEAF ? TarpRegistry.LEAF_COVER.get() : TarpRegistry.tarpItem(kind));
        if (!plan.ok()) return "x:" + plan.error + "/" + (int) plan.area;
        TarpRec rec = TarpService.addTarp(level, plan, ties, kind, color, leaf);
        if (door) {
            Membrane.Result r = rec.shape();
            int best = -1;
            double bestZ = Double.MAX_VALUE;
            for (int e = 0; e < r.edges.length; e++) {
                Door.Plan p = Door.plan(r, e, rec.obstacles);
                double z = (r.edges[e].a.z() + r.edges[e].b.z()) / 2;
                if (p.ok() && z < bestZ) {
                    bestZ = z;
                    best = e;
                }
            }
            if (best >= 0) {
                TarpService.setDoor(level, rec, best);
                TarpService.setOpen(level, rec, true);
            } else return "ok, no door";
        }
        return "ok";
    }

    /** 쐐기: 앞뒤 ㅅ자 다리 + 용마루 + 앞뒤 당김줄. 임시 수직으로 받쳐 세운 뒤 수직을 잘라낸다. 다리 발이 묶는 점. */
    static String wedge(int x0) {
        V3 f = at(x0 + 2.5, 4, 0.5), b = at(x0 + 2.5, 4, 6.5);
        V3 pf = at(x0 + 2.5, 0, 0.5), pb = at(x0 + 2.5, 0, 6.5);
        TarpService.addPole(level, pf, f);
        TarpService.addPole(level, pb, b);
        V3[] feet = {at(x0 + 0.5, 0, 0.5), at(x0 + 4.5, 0, 0.5), at(x0 + 4.5, 0, 6.5), at(x0 + 0.5, 0, 6.5)};
        TarpService.addPole(level, feet[0], f);
        TarpService.addPole(level, feet[1], f);
        TarpService.addPole(level, feet[2], b);
        TarpService.addPole(level, feet[3], b);
        TarpService.addPole(level, f, b);
        TarpService.addRope(level, f, stake(x0 + 2, 0, -3));
        TarpService.addRope(level, b, stake(x0 + 2, 0, 10));
        cut(pf);
        cut(pb);
        return tarp(List.of(feet), 1, DyeColor.GREEN.getId(), "", true);
    }

    static String pyramid(int x0) {
        TarpService.addPole(level, at(x0 + 3.5, 0, 3.5), at(x0 + 3.5, 4, 3.5));
        List<V3> ties = List.of(stake(x0, 0, 0), stake(x0 + 6, 0, 0), stake(x0 + 6, 0, 6), stake(x0, 0, 6));
        return tarp(ties, 1, DyeColor.ORANGE.getId(), "", true);
    }

    /** 티피: 수직으로 꼭대기를 받쳐 다리 다섯을 기대고, 수직을 잘라낸다. 둘레 앞쪽에 축 방향 변을 하나 둔다. */
    static String tipi(int x0) {
        V3 apex = at(x0 + 4.5, 5, 4.5);
        V3 post = at(x0 + 4.5, 0, 4.5);
        TarpService.addPole(level, post, apex);
        int[][] feet = {{2, 2}, {6, 2}, {7, 6}, {4, 7}, {1, 5}};
        for (int[] f : feet) TarpService.addPole(level, at(x0 + f[0] + 0.5, 0, f[1] + 0.5), apex);
        cut(post);
        List<V3> ties = new ArrayList<>();
        int[][] ring = {{3, 0}, {5, 0}, {7, 1}, {8, 3}, {8, 6}, {6, 8}, {3, 8}, {1, 7}, {0, 5}, {0, 2}, {1, 1}};
        for (int[] r : ring) ties.add(stake(x0 + r[0], 0, r[1]));
        return tarp(ties, 2, DyeColor.WHITE.getId(), "", true);
    }
    /** 베두인: 높이가 다른 기둥 여러 줄, 가장자리 기둥 꼭대기에 묶어 옆이 열린 물결 지붕. */
    static String bedouin(int x0) {
        int[][] edge = {{0, 0}, {4, 0}, {8, 0}, {8, 6}, {4, 6}, {0, 6}};
        List<V3> ties = new ArrayList<>();
        for (int[] e : edge) {
            V3 top = at(x0 + e[0] + 0.5, 2.5, e[1] + 0.5);
            TarpService.addPole(level, at(x0 + e[0] + 0.5, 0, e[1] + 0.5), top);
            ties.add(top);
        }
        TarpService.addPole(level, at(x0 + 2.5, 0, 3.5), at(x0 + 2.5, 4, 3.5));
        TarpService.addPole(level, at(x0 + 6.5, 0, 3.5), at(x0 + 6.5, 4, 3.5));
        return tarp(ties, 2, DyeColor.RED.getId(), "", false);
    }

    /** 외쪽지붕 잎 덮개: 벽 말뚝 2 + 땅 말뚝 2. */
    static String leanTo(int x0) {
        for (int x = 0; x <= 5; x++)
            for (int y = 0; y < 4; y++) level.setBlock(new BlockPos(ox + x0 + x, oy + y, oz + 8), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        V3 w1 = wallStake(x0, 3, 7, Direction.NORTH), w2 = wallStake(x0 + 5, 3, 7, Direction.NORTH);
        List<V3> ties = List.of(stake(x0, 0, 3), stake(x0 + 5, 0, 3), w2, w1);
        return tarp(ties, TarpRec.LEAF, 0, "minecraft:oak_leaves", false);
    }
}
