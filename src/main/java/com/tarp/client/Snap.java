package com.tarp.client;

import com.tarp.content.StakeBlock;
import com.tarp.core.Stability;
import com.tarp.core.V3;
import com.tarp.world.Planner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 끝점 스냅 (설계서 '끝점 스냅'): 기존 지지대 끝점·지지대 위 ½칸 지점·말뚝 머리 → 블록 면(윗면은 칸 가운데, 옆면은 면 중앙)
 * → 공중(시선 방향, 가로는 칸 가운데, 높이는 ½칸 단위).
 */
public final class Snap {
    private Snap() {}

    public enum Kind { POLE_END, POLE_MID, STAKE, BLOCK, AIR }

    public record Target(V3 p, Kind kind, int pole) {
        public boolean anchor() {
            return kind == Kind.POLE_END || kind == Kind.STAKE;
        }
    }

    /** 주변 말뚝 머리 (21×21×21 훑기, 0.3.12: 매 틱 → 4틱에 한 번). */
    private static final List<V3> STAKES = new ArrayList<>();
    private static long stakesTick = -1;

    public static List<V3> stakesNear() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return STAKES;
        long t = mc.level.getGameTime();
        if (t >= stakesTick && t - stakesTick < 4) return STAKES;
        stakesTick = t;
        STAKES.clear();
        BlockPos c = mc.player.blockPosition();
        int r = 10;
        int x0 = c.getX() - r, x1 = c.getX() + r, y0 = c.getY() - r, y1 = c.getY() + r, z0 = c.getZ() - r, z1 = c.getZ() + r;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // 0.4.10: 칸 묶음(16³)마다 팔레트에 말뚝이 없으면 통째로 건너뛴다 (전엔 21³ = 9261칸을 하나씩)
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunk(cx, cz);
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    int si = mc.level.getSectionIndexFromSectionY(sy);
                    if (si < 0 || si >= chunk.getSections().length) continue;
                    net.minecraft.world.level.chunk.LevelChunkSection sec = chunk.getSection(si);
                    if (sec.hasOnlyAir() || !sec.maybeHas(st -> st.getBlock() instanceof StakeBlock)) continue;
                    for (int x = Math.max(x0, cx << 4); x <= Math.min(x1, (cx << 4) + 15); x++)
                        for (int y = Math.max(y0, sy << 4); y <= Math.min(y1, (sy << 4) + 15); y++)
                            for (int z = Math.max(z0, cz << 4); z <= Math.min(z1, (cz << 4) + 15); z++) {
                                BlockState st = sec.getBlockState(x & 15, y & 15, z & 15);
                                if (st.getBlock() instanceof StakeBlock) STAKES.add(Planner.stakeHead(st, m.set(x, y, z)));
                            }
                }
            }
        return STAKES;
    }

    /**
     * @param anchorsOnly 노끈·방수포: 지지대 끝점·말뚝 머리만
     * @param from        지지대 시작점. 있으면 기존 점(지지대 끝점·½칸 점·말뚝 머리)을 잡기 전까지는
     *                    시작점에서 수직으로만 늘린다 (블록 면·공중 점은 쓰지 않는다)
     */
    @Nullable
    public static Target pick(float partial, boolean anchorsOnly, @Nullable V3 from) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        Vec3 eyeV = mc.player.getEyePosition(partial), lookV = mc.player.getViewVector(partial);
        V3 eye = new V3(eyeV.x, eyeV.y, eyeV.z), look = new V3(lookV.x, lookV.y, lookV.z);
        double reach = mc.player.blockInteractionRange() + 1;
        HitResult hr = mc.hitResult;
        double blockDist = hr != null && hr.getType() == HitResult.Type.BLOCK ? hr.getLocation().distanceTo(eyeV) : reach;

        Target best = null;
        double bestScore = Double.MAX_VALUE;
        for (Stability.Pole p : ClientStore.S.poles.values()) {
            if (p.a().dist(eye) > reach + 16 && p.b().dist(eye) > reach + 16) continue;
            for (int end = 0; end < 2; end++) {
                V3 q = end == 0 ? p.a() : p.b();
                if (from != null && q.dist(from) < 0.3) continue;
                double sc = score(eye, look, q, reach, blockDist);
                if (sc < bestScore) {
                    bestScore = sc;
                    best = new Target(q, Kind.POLE_END, p.id());
                }
            }
            if (anchorsOnly) continue;
            double len = p.a().dist(p.b());
            for (double d = 0.5; d < len - 0.25; d += 0.5) {
                V3 q = p.a().lerp(p.b(), d / len);
                if (from != null && q.dist(from) < 0.3) continue;
                double sc = score(eye, look, q, reach, blockDist) + 0.02;
                if (sc < bestScore) {
                    bestScore = sc;
                    best = new Target(q, Kind.POLE_MID, p.id());
                }
            }
        }
        for (V3 s : stakesNear()) {
            if (from != null && s.dist(from) < 0.3) continue;
            double sc = score(eye, look, s, reach, blockDist + 0.6);
            if (sc < bestScore) {
                bestScore = sc;
                best = new Target(s, Kind.STAKE, -1);
            }
        }
        if (best != null) return best;
        if (anchorsOnly) return null;
        if (from != null) return vertical(from, eye, look);
        if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK) {
            BlockState st = mc.level.getBlockState(bh.getBlockPos());
            if (!Planner.ours(st))
                return new Target(face(bh, st.canBeReplaced()), Kind.BLOCK, -1);
        }
        return null;
    }

    /** 시작점을 지나는 수직선에서 시선과 가장 가까운 점 (높이는 ½칸 단위, 길이 0.5~16칸). */
    static Target vertical(V3 from, V3 eye, V3 look) {
        double b = look.y(), denom = 1 - b * b;
        V3 w = from.sub(eye);
        double s;
        if (denom < 1e-4) s = b > 0 ? 2 : -2;
        else s = (b * look.dot(w) - w.y()) / denom;
        s = Math.round(s * 2) / 2.0;
        if (Math.abs(s) < 0.5) s = s < 0 ? -0.5 : 0.5;
        s = Math.max(-com.tarp.core.Spec.POLE_MAX_CELLS, Math.min(com.tarp.core.Spec.POLE_MAX_CELLS, s));
        return new Target(new V3(from.x(), from.y() + s, from.z()), Kind.AIR, -1);
    }

    /** 시선에서 떨어진 정도. 맞지 않으면 MAX. */
    private static double score(V3 eye, V3 look, V3 q, double reach, double blockDist) {
        V3 v = q.sub(eye);
        double t = v.dot(look);
        if (t < 0.2 || t > reach || t > blockDist + 0.35) return Double.MAX_VALUE;
        double perp = v.sub(look.mul(t)).len();
        double tol = 0.22 + 0.02 * t;
        return perp > tol ? Double.MAX_VALUE : perp;
    }

    /** 블록 면 스냅: 윗면 = 칸 가로 가운데·윗면 높이, 옆면·아랫면 = 면 중앙. 대체 가능 블록(풀)은 그 아래 땅 윗면. */
    static V3 face(BlockHitResult bh, boolean replaceable) {
        BlockPos p = bh.getBlockPos();
        Direction f = bh.getDirection();
        if (replaceable) return new V3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        return switch (f) {
            case UP -> new V3(p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5);
            case DOWN -> new V3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            default -> new V3(p.getX() + 0.5 + f.getStepX() * 0.5, p.getY() + 0.5, p.getZ() + 0.5 + f.getStepZ() * 0.5);
        };
    }
}
