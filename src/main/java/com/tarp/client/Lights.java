package com.tarp.client;

import com.tarp.content.CanvasCellBlock;
import com.tarp.content.PoleCellBlock;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 지지대·노끈 꼭짓점 밝기 (0.3.10). 천 칸과 천이 지나가는 지지대 칸은 하늘빛을 줄이므로 그 칸 안의 빛 값은 어둡다.
 * 그런 칸에 놓인 꼭짓점은 둘레 여섯 칸 중 밝은 쪽(천 칸 제외)을 쓴다 — 바깥으로 삐져나온 지지대 끝이 얼룩지지 않게.
 */
final class Lights {
    private Lights() {}

    static boolean dims(BlockState s) {
        return s.getBlock() instanceof CanvasCellBlock || s.getBlock() instanceof com.tarp.content.RopeCellBlock
                || s.getBlock() instanceof PoleCellBlock && s.getValue(PoleCellBlock.COVERED);
    }

    static int at(BlockAndTintGetter level, BlockPos pos) {
        if (!dims(level.getBlockState(pos))) return LevelRenderer.getLightColor(level, pos);
        int sky = -1, block = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            m.setWithOffset(pos, d);
            if (dims(level.getBlockState(m))) continue;
            int c = LevelRenderer.getLightColor(level, m);
            sky = Math.max(sky, LightTexture.sky(c));
            block = Math.max(block, LightTexture.block(c));
        }
        return sky < 0 ? LevelRenderer.getLightColor(level, pos) : LightTexture.pack(block, sky);
    }
}
