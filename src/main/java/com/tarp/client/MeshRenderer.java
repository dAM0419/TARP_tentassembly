package com.tarp.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.tarp.core.Membrane;
import com.tarp.core.Spec;
import com.tarp.core.Stability;
import com.tarp.core.V3;
import com.tarp.world.TarpRec;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * 지지대·노끈·방수포를 한 번 구워 캐시하고, 바뀔 때만(그리고 빛·잎 마름 반영을 위해 가끔) 다시 굽는다.
 * 지지대·노끈은 버퍼 하나, 방수포는 하나씩.
 */
public final class MeshRenderer {
    private MeshRenderer() {}

    private static final double RANGE = 160;
    private static final int ROPE_RGB = 0xB89A6A;

    private static final class Baked {
        VertexBuffer vb;
        V3 origin;
        boolean empty = true;
        long bakedAt;
    }

    private static final Baked FRAME = new Baked();
    private static int frameVersion = -1;

    public static void clear() {
        free(FRAME);
        FRAME.empty = true;
        TarpRenderer.clear();
        frameVersion = -1;
    }

    private static void free(Baked b) {
        if (b.vb != null) {
            b.vb.close();
            b.vb = null;
        }
    }

    /** 클라이언트 틱. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        long now = level.getGameTime();
        Vec3 pp = mc.player.position();
        boolean stakeGone = false;
        for (long k : STAKES_USED) if (!(level.getBlockState(BlockPos.of(k)).getBlock() instanceof com.tarp.content.StakeBlock)) stakeGone = true;
        if (stakeGone || frameVersion != ClientStore.S.version || now - FRAME.bakedAt > 60
                || (FRAME.origin != null && FRAME.origin.dist(new V3(pp.x, pp.y, pp.z)) > 64)) {
            frameVersion = ClientStore.S.version;
            bakeFrame(level, pp, now);
        }
        TarpRenderer.tick();
    }

    /**
     * 당김줄처럼 한쪽 끝이 천막 안(천 아래 골조)에 묶인 노끈은 천 바깥면에서 끝나게 그린다 (천을 뚫지 않게).
     */
    static V3[] trimRope(V3 a, V3 b) {
        double rx0 = Math.min(a.x(), b.x()), rx1 = Math.max(a.x(), b.x()), rz0 = Math.min(a.z(), b.z()), rz1 = Math.max(a.z(), b.z());
        for (TarpRec t : ClientStore.S.tarps.values()) {
            // 0.3.12: 노끈과 바닥 둘레 상자가 겹치는 방수포만 (전엔 차원의 모든 방수포 × 모든 노끈)
            double[] bb = t.footprint();
            if (rx1 < bb[0] - 1 || rx0 > bb[2] + 1 || rz1 < bb[1] - 1 || rz0 > bb[3] + 1) continue;
            Membrane.Result r = t.shape();
            if (!r.ok()) continue;
            boolean ua = r.under(a) || near(r, a), ub = r.under(b) || near(r, b);
            if (ua == ub) continue;
            java.util.List<Double> ts = r.crossings(a, b);
            if (ts.isEmpty()) continue;
            double tt = ua ? java.util.Collections.max(ts) : java.util.Collections.min(ts);
            V3 hit = a.lerp(b, tt);
            return ua ? new V3[]{hit, b} : new V3[]{a, hit};
        }
        return new V3[]{a, b};
    }

    /** 막 바로 아래(골조 끝점)도 안으로 본다. */
    private static boolean near(Membrane.Result r, V3 p) {
        if (!r.insidePoly(p.x(), p.z()) && com.tarp.core.Polygon.nearestEdge(r.ties, p.x(), p.z())[2] > 0.3) return false;
        for (com.tarp.core.Hull3.Face f : r.faces) {
            double d = f.dist(p);
            if (d < 0 && d > -Spec.POLE_CLEAR - 0.05) return true;
        }
        return false;
    }

    /** 밑단 고리가 닿을 천 모서리: 밑단 점에서 가장 가까운 막 꼭짓점. */
    static V3 nearestCorner(Membrane.Result r, V3 hem) {
        // 0.4.8: 0.8 → 0.15. 멀리 있는 막 꼭짓점(용마루줄을 덮느라 생긴 점)까지 고리를 그으면 그 선이 천을 뚫고 나왔다
        V3 best = hem;
        double bd = 0.15;
        for (com.tarp.core.Hull3.Face f : r.faces)
            for (V3 p : f.poly()) {
                double d = p.dist(hem);
                if (d < bd) {
                    bd = d;
                    best = p;
                }
            }
        return best;
    }

    private static Geo.Light lights(ClientLevel level) {
        Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            m.set(Math.floor(x), Math.floor(y), Math.floor(z));
            long key = m.asLong();
            if (cache.containsKey(key)) return cache.get(key);
            int l = Lights.at(level, m);
            cache.put(key, l);
            return l;
        };
    }

    static TextureAtlasSprite sprite(ResourceLocation rl) {
        return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(rl);
    }

    private static void bakeFrame(ClientLevel level, Vec3 pp, long now) {
        V3 origin = new V3(Math.floor(pp.x), Math.floor(pp.y), Math.floor(pp.z));
        ByteBufferBuilder mem = new ByteBufferBuilder(1 << 16);
        BufferBuilder bb = new BufferBuilder(mem, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        Geo.Out out = new Geo.Out(bb, true, origin, lights(level));
        TextureAtlasSprite wool = sprite(ResourceLocation.withDefaultNamespace("block/white_wool"));
        int rope = Geo.argb(255, ROPE_RGB);
        for (Stability.Rope r : ClientStore.S.ropes.values()) {
            if (r.a().hdist(origin) > RANGE && r.b().hdist(origin) > RANGE) continue;
            V3[] seg = trimRope(r.a(), r.b());
            out.prism(seg[0], seg[1], Spec.ROPE_THICK / 2, rope, wool, 4);
        }
        // 밑단 고리: 묶는 점에서 막 가장자리까지
        for (TarpRec t : ClientStore.S.tarps.values()) {
            if (t.centre().hdist(origin) > RANGE) continue; // 0.4.10: 거리 먼저 (먼 방수포는 모양을 구하지 않는다)
            Membrane.Result r = t.shape();
            if (!r.ok()) continue;
            for (int i = 0; i < r.ties.size(); i++) out.prism(r.ties.get(i), nearestCorner(r, r.hem(i)), Spec.ROPE_THICK / 2, rope, wool, 4);
        }
        lashings(level, out, origin, rope, wool);
        upload(FRAME, bb, origin, now);
        mem.close();
    }

    /** 지난 굽기에서 목 띠를 그린 말뚝 칸. 그 칸이 사라지면 다음 틱에 다시 굽는다 (전엔 최대 3초 남았다). */
    private static final java.util.Set<Long> STAKES_USED = new java.util.HashSet<>();

    /** 감은 띠 두 줄의 위치 (끝점에서 안쪽으로, 칸 단위). */
    private static final double[][] WRAPS = {{0.03, 0.11}, {0.14, 0.22}};
    private static final double BAND = 0.014;

    /**
     * 노끈 매듭 (0.4.2, 겉모양만): 노끈 끝이 걸린 지지대 끝·말뚝 목, 지지대끼리 만나는 끝에 노끈을 두 바퀴 감은 띠.
     * 쓰러짐 판정과는 상관없다 (설계서: 결속 기능은 없다).
     */
    private static void lashings(ClientLevel level, Geo.Out out, V3 origin, int rope, TextureAtlasSprite wool) {
        java.util.Set<Long> done = new java.util.HashSet<>();
        java.util.List<V3> ropeEnds = new java.util.ArrayList<>();
        for (Stability.Rope r : ClientStore.S.ropes.values()) {
            ropeEnds.add(r.a());
            ropeEnds.add(r.b());
        }
        for (TarpRec t : ClientStore.S.tarps.values()) ropeEnds.addAll(t.ties); // 묶는 점 (순서만 다를 뿐 모양의 ties 와 같은 점)
        // 0.4.10: 끝점 찾기를 격자로 (전엔 지지대 끝 × 모든 노끈 끝)
        java.util.Map<Long, java.util.List<V3>> endGrid = new java.util.HashMap<>();
        for (V3 q : ropeEnds) endGrid.computeIfAbsent(gridKey(q, 0, 0, 0), k -> new java.util.ArrayList<>()).add(q);
        // 지지대 끝: 노끈이 걸렸거나 다른 지지대와 만나면
        for (Stability.Pole p : ClientStore.S.poles.values()) {
            if (p.a().hdist(origin) > RANGE && p.b().hdist(origin) > RANGE) continue;
            for (int e = 0; e < 2; e++) {
                V3 end = e == 0 ? p.a() : p.b(), other = e == 0 ? p.b() : p.a();
                boolean tied = false;
                for (int dx = -1; dx <= 1 && !tied; dx++)
                    for (int dy = -1; dy <= 1 && !tied; dy++)
                        for (int dz = -1; dz <= 1 && !tied; dz++) {
                            java.util.List<V3> cell = endGrid.get(gridKey(end, dx, dy, dz));
                            if (cell != null) for (V3 q : cell) if (q.dist(end) < Stability.TOUCH) tied = true;
                        }
                if (!tied) {
                    for (int id : ClientStore.S.polesNear((int) Math.floor(end.x()), (int) Math.floor(end.y()), (int) Math.floor(end.z()))) {
                        Stability.Pole q = ClientStore.S.poles.get(id);
                        if (q != null && q.id() != p.id() && com.tarp.world.Structures.pointSeg(end, q.a(), q.b()) < Stability.TOUCH) tied = true;
                    }
                }
                if (!tied || !done.add(((long) p.id() << 1) | e)) continue;
                V3 dir = other.sub(end);
                double len = dir.len();
                if (len < 0.5) continue;
                dir = dir.mul(1 / len);
                for (double[] w : WRAPS)
                    out.prism(end.add(dir.mul(w[0])), end.add(dir.mul(w[1])), Spec.POLE_THICK / 2 + BAND, rope, wool, 4);
            }
        }
        // 말뚝 목 (머리 바로 아래): 노끈·밑단 고리가 걸린 말뚝만
        java.util.Set<Long> stakes = new java.util.HashSet<>();
        STAKES_USED.clear();
        for (V3 q : ropeEnds) {
            if (q.hdist(origin) > RANGE) continue;
            BlockPos b = BlockPos.containing(q.x(), q.y(), q.z());
            if (!stakes.add(b.asLong())) continue;
            var st = level.getBlockState(b);
            if (!(st.getBlock() instanceof com.tarp.content.StakeBlock)) continue;
            STAKES_USED.add(b.asLong());
            net.minecraft.core.Direction f = st.getValue(com.tarp.content.StakeBlock.FACING);
            V3 head = com.tarp.world.Planner.stakeHead(level, b), down = new V3(-f.getStepX(), -f.getStepY(), -f.getStepZ());
            // 모델(0.4.5): 머리 2~4/16, 몸통 0~2/16 (깊이 박힘) → 목 한 바퀴
            double[][] neck = {{1.15 / 16, 1.95 / 16}};
            for (double[] w : neck)
                out.prism(head.add(down.mul(w[0])), head.add(down.mul(w[1])), 1.5 / 16 + BAND, rope, wool, 4);
        }
    }

    /** 끝점 격자 칸 (크기 0.5 > TOUCH): 점이 든 칸에서 (dx, dy, dz) 옮긴 칸. */
    private static long gridKey(V3 p, int dx, int dy, int dz) {
        long x = (long) Math.floor(p.x() * 2) + dx, y = (long) Math.floor(p.y() * 2) + dy, z = (long) Math.floor(p.z() * 2) + dz;
        return (x & 0x1FFFFF) << 42 | (y & 0x1FFFFF) << 21 | (z & 0x1FFFFF);
    }

    private static void upload(Baked b, BufferBuilder bb, V3 origin, long now) {
        MeshData data = bb.build();
        b.bakedAt = now;
        b.origin = origin;
        if (data == null) {
            b.empty = true;
            return;
        }
        if (b.vb == null) b.vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        b.vb.bind();
        b.vb.upload(data);
        VertexBuffer.unbind();
        b.empty = false;
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Vec3 cam = event.getCamera().getPosition();
        RenderType type = RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS);
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) draw(FRAME, event, cam, shader);
        VertexBuffer.unbind();
        type.clearRenderState();
    }

    private static void draw(@Nullable Baked b, RenderLevelStageEvent event, Vec3 cam, ShaderInstance shader) {
        if (b == null || b.empty || b.vb == null || b.origin == null) return;
        if (Math.abs(b.origin.x() - cam.x) > RANGE + 64 || Math.abs(b.origin.z() - cam.z) > RANGE + 64) return;
        Matrix4f mv = new Matrix4f(event.getModelViewMatrix())
                .translate((float) (b.origin.x() - cam.x), (float) (b.origin.y() - cam.y), (float) (b.origin.z() - cam.z));
        b.vb.bind();
        b.vb.drawWithShader(mv, event.getProjectionMatrix(), shader);
    }
}
