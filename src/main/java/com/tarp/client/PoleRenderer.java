package com.tarp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tarp.content.PoleCellBlockEntity;
import com.tarp.core.Spec;
import com.tarp.core.V3;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 지지대를 점유 칸 블록 엔티티 렌더러로 그린다 (0.3.0). 지지대마다 첫 칸이 그 지지대 전체를 그린다.
 * 블록 엔티티 렌더러라 셰이더팩 그림자에 들어간다.
 */
public class PoleRenderer implements BlockEntityRenderer<PoleCellBlockEntity> {
    public PoleRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(PoleCellBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null || be.heads().isEmpty()) return;
        BlockPos o = be.getBlockPos();
        TextureAtlasSprite wood = MeshRenderer.sprite(ResourceLocation.withDefaultNamespace("block/stripped_spruce_log"));
        Geo.Out out = new Geo.Out(buffers.getBuffer(NoCrumbleTypes.cutout(TextureAtlas.LOCATION_BLOCKS)), true,
                new V3(o.getX(), o.getY(), o.getZ()), (x, y, z) -> Lights.at(level, BlockPos.containing(x, y, z)), ps.last());
        // 0.3.12: 지지대마다 첫 칸 하나만 그린다. 전엔 모든 칸이 프레임마다 지지대 전체의 칸 목록을 다시 셌다.
        for (PoleCellBlockEntity.Entry e : be.heads()) out.prism(e.a(), e.b(), Spec.POLE_THICK / 2, 0xFFFFFFFF, wood, 4);
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
        // 그리지 않는 칸은 자기 칸 크기 → 화면 밖이면 바로 걸러진다
        AABB box = be.headBox();
        return box == null ? new AABB(be.getBlockPos()) : box;
    }
}
