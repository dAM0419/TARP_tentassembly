package com.tarp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tarp.content.PoleCellBlockEntity;
import com.tarp.core.Spec;
import com.tarp.core.V3;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 지지대를 점유 칸 블록 엔티티 렌더러로 그린다 (0.3.0). 지지대마다 첫 칸이 그 지지대 전체를 그린다.
 * 블록 엔티티 렌더러라 셰이더팩 그림자에 들어간다.
 * <p>
 * 0.4.10: 모양은 한 번 구워 두고(지지대 목록이 바뀌면 다시), 빛은 1초마다 꼭짓점이 놓인 칸별로 한 번씩만 다시 잰다.
 * 전엔 매 프레임 각기둥을 새로 만들고 꼭짓점마다 빛을 쟀다 (덮인 칸이면 꼭짓점 하나에 블록 7번·빛 6번).
 */
public class PoleRenderer implements BlockEntityRenderer<PoleCellBlockEntity> {
    private static final ResourceLocation WOOD = ResourceLocation.withDefaultNamespace("block/stripped_spruce_log");
    private static final int LIGHT_EVERY = 20;

    private static TextureAtlasSprite sprite;
    private static long spriteTick = Long.MIN_VALUE;

    public PoleRenderer(BlockEntityRendererProvider.Context ctx) {}

    /** 구운 메시: 꼭짓점마다 {x, y, z, u, v, nx, ny, nz} (칸 원점 기준) + 빛 칸 번호. */
    private static final class Baked {
        float[] v;
        int[] lightIdx;
        long[] lightPos;
        int[] lights;
        long litAt = Long.MIN_VALUE;
        TextureAtlasSprite sprite;
    }

    /** 텍스처 묶음이 다시 불리면 스프라이트가 바뀐다 → 틱마다 한 번만 찾아 비교한다. */
    private static TextureAtlasSprite sprite(long now) {
        if (sprite == null || now != spriteTick) {
            sprite = MeshRenderer.sprite(WOOD);
            spriteTick = now;
        }
        return sprite;
    }

    @Override
    public void render(PoleCellBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null || be.heads().isEmpty()) return;
        long now = level.getGameTime();
        TextureAtlasSprite sp = sprite(now);
        Baked b = be.renderCache instanceof Baked x && x.sprite == sp ? x : null;
        if (b == null) {
            b = bake(be, sp);
            be.renderCache = b;
        }
        if (now - b.litAt >= LIGHT_EVERY || now < b.litAt) {
            for (int i = 0; i < b.lightPos.length; i++) b.lights[i] = Lights.at(level, BlockPos.of(b.lightPos[i]));
            b.litAt = now;
        }
        VertexConsumer vc = buffers.getBuffer(NoCrumbleTypes.cutout(TextureAtlas.LOCATION_BLOCKS));
        PoseStack.Pose pose = ps.last();
        float[] v = b.v;
        for (int i = 0, n = b.lightIdx.length; i < n; i++) {
            int o = i * 8;
            vc.addVertex(pose, v[o], v[o + 1], v[o + 2]).setColor(0xFFFFFFFF).setUv(v[o + 3], v[o + 4]).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(b.lights[b.lightIdx[i]]).setNormal(pose, v[o + 5], v[o + 6], v[o + 7]);
        }
    }

    private static Baked bake(PoleCellBlockEntity be, TextureAtlasSprite sp) {
        BlockPos o = be.getBlockPos();
        Capture cap = new Capture();
        Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        LongArrayList positions = new LongArrayList();
        // 빛 함수 자리에 "빛 칸 번호"를 넣어 받아 둔다 (그릴 때 번호 → 지금 빛)
        Geo.Light light = (x, y, z) -> {
            long key = BlockPos.containing(x, y, z).asLong();
            int idx = index.getOrDefault(key, -1);
            if (idx < 0) {
                idx = positions.size();
                positions.add(key);
                index.put(key, idx);
            }
            return idx;
        };
        Geo.Out out = new Geo.Out(cap, true, new V3(o.getX(), o.getY(), o.getZ()), light);
        for (PoleCellBlockEntity.Entry e : be.heads()) out.prism(e.a(), e.b(), Spec.POLE_THICK / 2, 0xFFFFFFFF, sp, 4);
        Baked b = new Baked();
        b.v = cap.verts.toFloatArray();
        b.lightIdx = cap.light.toIntArray();
        b.lightPos = positions.toLongArray();
        b.lights = new int[b.lightPos.length];
        b.sprite = sp;
        return b;
    }

    /** 꼭짓점을 받아 적기만 하는 소비자 (빛 자리 = 빛 칸 번호). */
    private static final class Capture implements VertexConsumer {
        final it.unimi.dsi.fastutil.floats.FloatArrayList verts = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        final it.unimi.dsi.fastutil.ints.IntArrayList light = new it.unimi.dsi.fastutil.ints.IntArrayList();
        private int base = -8;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            base = verts.size();
            for (int i = 0; i < 8; i++) verts.add(0);
            verts.set(base, x);
            verts.set(base + 1, y);
            verts.set(base + 2, z);
            light.add(0);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            verts.set(base + 3, u);
            verts.set(base + 4, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            light.set(light.size() - 1, (u & 0xFFFF) | (v << 16));
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            verts.set(base + 5, x);
            verts.set(base + 6, y);
            verts.set(base + 7, z);
            return this;
        }
    }

    /** 0.4.10: 그릴 지지대가 없는 칸은 바닐라의 칸별 빛 조회까지 가지 않게 한다. */
    @Override
    public boolean shouldRender(PoleCellBlockEntity be, Vec3 cam) {
        return !be.heads().isEmpty() && BlockEntityRenderer.super.shouldRender(be, cam);
    }

    @Override
    public boolean shouldRenderOffScreen(PoleCellBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 160;
    }

    @Override
    public AABB getRenderBoundingBox(PoleCellBlockEntity be) {
        // 그리지 않는 칸은 자기 칸 크기 → 화면 밖이면 바로 걸러진다 (0.4.10: 매 프레임 새로 만들지 않는다)
        AABB box = be.headBox();
        return box == null ? be.cellBox() : box;
    }
}
