package com.tarp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tarp.Tarp;
import com.tarp.content.CanvasCellBlock;
import com.tarp.content.CanvasCellBlockEntity;
import com.tarp.content.TarpItem;
import com.tarp.core.Door;
import com.tarp.core.Hull3;
import com.tarp.core.Membrane;
import com.tarp.core.Spec;
import com.tarp.core.V3;
import com.tarp.world.Planner;
import com.tarp.world.TarpRec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 방수포를 천 칸 블록 엔티티 렌더러로 그린다 (0.3.0). 방수포마다 기준 칸 하나만 그 방수포 전체를 그린다.
 * 블록 엔티티 렌더러라 셰이더팩의 그림자 계산과 정점 형식 확장에 그대로 들어간다.
 */
public class TarpRenderer implements BlockEntityRenderer<CanvasCellBlockEntity> {
    static final ResourceLocation CANVAS = ResourceLocation.fromNamespaceAndPath(Tarp.MODID, "textures/entity/canvas.png");
    static final ResourceLocation SEAM = ResourceLocation.fromNamespaceAndPath(Tarp.MODID, "textures/entity/seam.png");

    private static final class Entry {
        TarpRec rec;
        int doorEdge;
        boolean open;
        long builtAt = Long.MIN_VALUE;
        List<TarpMesh.Quad> quads = List.of();
        ResourceLocation texture = CANVAS;
        AABB bounds;
        long anchor = Long.MIN_VALUE;
    }

    private static final long NONE = Long.MIN_VALUE + 1;

    private static final Map<Integer, Entry> CACHE = new HashMap<>();

    public TarpRenderer(BlockEntityRendererProvider.Context ctx) {}

    public static void dirty(int id) {
        Entry e = CACHE.get(id);
        if (e != null) e.builtAt = Long.MIN_VALUE;
    }

    public static void clear() {
        CACHE.clear();
    }

    /**
     * 클라이언트 틱: 사라진 방수포 정리, 기준 칸 확인 (0.3.12: 프레임마다·칸마다 하던 것을 틱마다 방수포마다 한 번으로).
     * 기준 칸이 없을 때(아직 칸이 안 들어옴) 다시 찾기는 5틱에 한 번.
     */
    public static void tick() {
        CACHE.keySet().removeIf(id -> !ClientStore.S.tarps.containsKey(id));
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) return;
        long now = level.getGameTime();
        double px = mc.player.getX(), pz = mc.player.getZ(), far = 160 + Spec.MAX_SPAN;
        for (TarpRec rec : ClientStore.S.tarps.values()) {
            // 0.4.10: 그리는 거리 밖 방수포는 모양도 구하지 않는다 (전엔 접속 직후 차원의 모든 방수포를 계산했다)
            double[] fp = rec.footprint();
            if (px < fp[0] - far || px > fp[2] + far || pz < fp[1] - far || pz > fp[3] + far) continue;
            if (!rec.shape().ok()) continue;
            Entry e = CACHE.computeIfAbsent(rec.id, k -> new Entry());
            if (e.rec != rec) {
                e.rec = rec;
                e.builtAt = Long.MIN_VALUE;
                e.anchor = Long.MIN_VALUE;
            }
            if (anchorValid(level, e)) continue;
            if (e.anchor == NONE && (now + rec.id) % 5 != 0) continue;
            e.anchor = findAnchor(level, e);
        }
    }

    @Override
    public void render(CanvasCellBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        // 기준 칸이 아니면 곧바로 끝 (방수포 하나에 천 칸이 수백 개)
        Entry e = CACHE.get(be.tarp());
        if (e == null || e.anchor != be.getBlockPos().asLong()) return;
        Level level = be.getLevel();
        TarpRec rec = e.rec;
        if (level == null || rec == null) return;
        long now = level.getGameTime();
        if (e.builtAt == Long.MIN_VALUE || e.doorEdge != rec.doorEdge || e.open != rec.open || now - e.builtAt > 40) build(level, rec, e, now);
        BlockPos o = be.getBlockPos();
        Geo.Out cloth = new Geo.Out(buffers.getBuffer(NoCrumbleTypes.cutout(e.texture)), true, new V3(o.getX(), o.getY(), o.getZ()), null, ps.last());
        if (shadowPass()) {
            // 0.3.11: 셰이더 그림자 단계에는 안쪽면(과 두루마리)만 낸다. 바깥면까지 내면 0.01칸 안쪽에 있는 안쪽면이
            // 바깥면 그림자에 묻혀, 해를 받는 쪽도 안에서 보면 그늘로 칠해졌다. 땅에 지는 천막 그림자는 안쪽면이 그대로 만든다.
            TarpMesh.emit(cloth, e.quads, TarpMesh.INNER);
            TarpMesh.emit(cloth, e.quads, TarpMesh.ROLL);
            return;
        }
        TarpMesh.emit(cloth, e.quads, TarpMesh.OUTER);
        TarpMesh.emit(cloth, e.quads, TarpMesh.INNER);
        TarpMesh.emit(cloth, e.quads, TarpMesh.ROLL);
        Geo.Out seam = new Geo.Out(buffers.getBuffer(NoCrumbleTypes.cutout(SEAM)), true, new V3(o.getX(), o.getY(), o.getZ()), null, ps.last());
        TarpMesh.emit(seam, e.quads, TarpMesh.SEAM);
    }

    private static boolean anchorValid(Level level, Entry e) {
        if (e.anchor == Long.MIN_VALUE || e.anchor == NONE) return false;
        if (level.getBlockEntity(BlockPos.of(e.anchor)) instanceof CanvasCellBlockEntity be && be.tarp() == e.rec.id) {
            be.renderAnchor = true;
            return true;
        }
        return false;
    }

    /** 이 방수포의 천 칸 중 좌표값이 가장 작은 칸. 0.4.10: 칸 목록은 TarpRec 캐시를 쓴다. */
    private static long findAnchor(Level level, Entry e) {
        for (long k : e.rec.closedCells()) {
            if (level.getBlockEntity(BlockPos.of(k)) instanceof CanvasCellBlockEntity be && be.tarp() == e.rec.id) {
                be.renderAnchor = true;
                return k;
            }
        }
        return NONE;
    }

    private static void build(Level level, TarpRec rec, Entry e, long now) {
        Membrane.Result r = rec.shape();
        int rgb;
        if (rec.isLeaf()) {
            Block block = TarpItem.leafBlock(rec.leaf);
            BlockState st = block.defaultBlockState();
            TextureAtlasSprite sp = Minecraft.getInstance().getBlockRenderer().getBlockModel(st).getParticleIcon();
            ResourceLocation name = sp == null || sp.contents().name().equals(MissingTextureAtlasSprite.getLocation())
                    ? ResourceLocation.withDefaultNamespace("block/oak_leaves") : sp.contents().name();
            e.texture = ResourceLocation.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
            int tint = Minecraft.getInstance().getBlockColors().getColor(st, null, null, 0);
            rgb = tint == -1 ? 0xFFFFFF : tint & 0xFFFFFF;
            double age = (now - rec.placedAt) / (double) Spec.LEAF_LIFE;
            if (age >= 0.8) rgb = Geo.mix(rgb, 0x7A5A32, 0.75);
            else if (age >= 0.5) rgb = Geo.mix(rgb, 0xC2A94A, 0.65);
        } else {
            e.texture = CANVAS;
            rgb = com.tarp.core.Spec.fade(DyeColor.byId(rec.color).getTextureDiffuseColor() & 0xFFFFFF);
        }
        Door door = rec.door();
        e.quads = TarpMesh.build(r, door, rec.open, rgb, lighting(level));
        e.doorEdge = rec.doorEdge;
        e.open = rec.open;
        e.builtAt = now;
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (Hull3.Face f : r.faces)
            for (V3 p : f.poly()) {
                x0 = Math.min(x0, p.x());
                y0 = Math.min(y0, p.y());
                z0 = Math.min(z0, p.z());
                x1 = Math.max(x1, p.x());
                y1 = Math.max(y1, p.y());
                z1 = Math.max(z1, p.z());
            }
        e.bounds = new AABB(x0, y0, z0, x1, y1, z1).inflate(0.5);
    }

    /**
     * 바깥면 밝기는 면 바깥쪽(천 칸이 아닌 첫 칸)에서, 안쪽면은 안쪽 빛과 바깥 빛×투과율 중 밝은 쪽.
     * 천 칸은 하늘빛을 줄이므로 천막 안은 어둡고, 해를 받는 천 자체와 안에서 본 천은 밝다.
     */
    static TarpMesh.Lighting lighting(Level level) {
        Map<Long, Integer> cache = new HashMap<>();
        boolean shader = shaderOn(); // 0.4.10: 면마다 리플렉션으로 묻던 것을 굽기마다 한 번
        java.util.function.Function<V3, int[]> sample = p -> {
            BlockPos pos = BlockPos.containing(p.x(), p.y(), p.z());
            long k = pos.asLong();
            Integer c = cache.get(k);
            if (c == null) {
                BlockState st = level.getBlockState(pos);
                // 0.3.9: 천 칸뿐 아니라 지지대·말뚝 칸도 건너뛴다 (그 칸 안의 빛 값을 바깥 빛으로 잘못 집었다)
                c = st.getBlock() instanceof CanvasCellBlock || Planner.ours(st) ? -1 : LevelRenderer.getLightColor(level, pos);
                cache.put(k, c);
            }
            return new int[]{c};
        };
        return new TarpMesh.Lighting() {
            int along(V3 p, V3 dir) {
                for (double d : new double[]{0.35, 0.8, 1.3, 1.9}) {
                    int c = sample.apply(p.add(dir.mul(d)))[0];
                    if (c != -1) return c;
                }
                return LevelRenderer.getLightColor(level, BlockPos.containing(p.x(), p.y(), p.z()));
            }

            @Override
            public int outside(V3 p, V3 n) {
                return along(p, n);
            }

            @Override
            public double shade(V3 n) {
                return faceShade(level, n, shader);
            }

            /**
             * 안쪽면 (0.3.11): 하늘빛은 바깥면과 같게 (해 쪽은 안에서도 밝다, 줄어드는 몫은 색 배율 CLOTH_INNER 이 맡는다).
             * 블록 빛은 안쪽 칸 빛(천막 안 랜턴)과 바깥 블록 빛×투과율 중 밝은 쪽.
             */
            @Override
            public int inside(V3 p, V3 n) {
                int in = along(p, n.mul(-1)), out = along(p, n);
                int block = Math.max(LightTexture.block(in), (int) Math.round(LightTexture.block(out) * Spec.CLOTH_TRANSMIT));
                return LightTexture.pack(block, LightTexture.sky(out));
            }
        };
    }

    /**
     * 바깥에서 본 음영 (0.3.6): 해를 정면으로 받는 면은 밝고, 등진 면·가파른 면은 어둡다. 밤엔 차이가 줄어든다.
     * 셰이더팩이 켜져 있으면 셰이더가 해 방향 음영을 하므로 1.
     */
    static double faceShade(Level level, V3 n, boolean shader) {
        if (shader) return 1;
        double a = level.getSunAngle(1);
        V3 sun = new V3(-Math.sin(a), Math.cos(a), 0);
        double day = Math.max(0, Math.min(1, sun.y() * 4 + 0.2));
        double direct = Math.max(0, n.dot(sun)) * day;
        double sky = 0.5 + 0.5 * n.y();
        return Math.min(1, 0.62 + 0.13 * sky + 0.25 * direct);
    }

    private static Boolean irisPresent;
    private static Object irisApi;
    private static java.lang.reflect.Method irisShadow;
    private static boolean irisShadowBroken;

    /** Iris 가 지금 셰이더 그림자 지도를 그리는 중인가 (없으면 false). */
    static boolean shadowPass() {
        try {
            if (irisPresent == null) irisPresent = net.neoforged.fml.ModList.get().isLoaded("iris") || net.neoforged.fml.ModList.get().isLoaded("oculus");
            if (!irisPresent || irisShadowBroken) return false;
            if (irisShadow == null) {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                irisShadow = api.getMethod("isRenderingShadowPass");
            }
            return (Boolean) irisShadow.invoke(irisApi);
        } catch (Throwable t) {
            irisShadowBroken = true;
            return false;
        }
    }

    private static java.lang.reflect.Method irisShaderInUse;

    static boolean shaderOn() {
        try {
            if (irisPresent == null) irisPresent = net.neoforged.fml.ModList.get().isLoaded("iris") || net.neoforged.fml.ModList.get().isLoaded("oculus");
            if (!irisPresent || irisShadowBroken) return false;
            if (irisShaderInUse == null) {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                if (irisApi == null) irisApi = api.getMethod("getInstance").invoke(null);
                irisShaderInUse = api.getMethod("isShaderPackInUse");
            }
            return (Boolean) irisShaderInUse.invoke(irisApi);
        } catch (Throwable t) {
            irisShadowBroken = true;
            return false;
        }
    }

    @Override
    public boolean shouldRenderOffScreen(CanvasCellBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 160;
    }

    /** 0.4.10: 기준 칸이 아니면 그리지 않는다 → 바닐라가 칸마다 하던 빛 조회를 건너뛴다 (천막 하나에 칸이 수백 개). */
    @Override
    public boolean shouldRender(CanvasCellBlockEntity be, net.minecraft.world.phys.Vec3 cam) {
        return be.renderAnchor && BlockEntityRenderer.super.shouldRender(be, cam);
    }

    @Override
    public AABB getRenderBoundingBox(CanvasCellBlockEntity be) {
        // 기준 칸만 방수포 전체 크기, 나머지 칸은 자기 칸 크기(0.4.10: 매 프레임 새로 만들지 않는다)
        if (!be.renderAnchor) return be.cellBox();
        Entry e = CACHE.get(be.tarp());
        if (e == null || e.anchor != be.getBlockPos().asLong()) return be.cellBox();
        if (e.bounds != null) return e.bounds;
        V3 c = e.rec.centre();
        return new AABB(c.x(), c.y(), c.z(), c.x(), c.y(), c.z()).inflate(Spec.MAX_SPAN / 2);
    }
}
