package com.tarp.content;

import com.mojang.serialization.MapCodec;
import com.tarp.world.TarpService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * 노끈 점유 칸 (0.4.7). 그림은 MeshRenderer 가 그리고, 이 블록은 충돌·조준·부수기만 맡는다.
 * 부수면 그 노끈 한 가닥만 걷히고 길이만큼 노끈이 떨어진다.
 */
public class RopeCellBlock extends BaseEntityBlock {
    public static final MapCodec<RopeCellBlock> CODEC = simpleCodec(RopeCellBlock::new);

    public RopeCellBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RopeCellBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return level.getBlockEntity(pos) instanceof RopeCellBlockEntity be ? be.shape() : Shapes.empty();
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }

    /** 천 아래 용마루줄 칸은 천 칸이 못 들어가므로 천 칸처럼 빛을 줄인다 (안 그러면 그 줄을 따라 하늘빛이 샌다). */
    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return com.tarp.core.Spec.CANVAS_LIGHT_BLOCK;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!newState.is(this) && level instanceof ServerLevel sl) TarpService.ropeCellRemoved(sl, pos);
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(TarpRegistry.ROPE.get());
    }
}
