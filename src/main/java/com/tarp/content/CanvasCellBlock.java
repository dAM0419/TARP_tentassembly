package com.tarp.content;

import com.mojang.serialization.MapCodec;
import com.tarp.world.TarpService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.Tags;

import javax.annotation.Nullable;

/**
 * 천 충돌 칸. 그림은 블록 엔티티 렌더러(TarpRenderer)가 그린다.
 * 좌클릭으로 부수기 또는 가위로 우클릭 = 방수포 걷기(방수포·노끈이 돌아온다), 빈손 웅크려 우클릭 = 입구 지정, 빈손 우클릭 = 입구 여닫기.
 */
public class CanvasCellBlock extends BaseEntityBlock {
    public static final MapCodec<CanvasCellBlock> CODEC = simpleCodec(CanvasCellBlock::new);

    public CanvasCellBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CanvasCellBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return level.getBlockEntity(pos) instanceof CanvasCellBlockEntity be ? be.shape() : Shapes.empty();
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    /** 천은 하늘빛을 일부 막는다 → 천막 안은 은은하게 어둡다 (0.3.0). 빛 계산 스레드에서도 불리므로 블록 엔티티를 보지 않는 상수. */
    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return com.tarp.core.Spec.CANVAS_LIGHT_BLOCK;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(Tags.Items.TOOLS_SHEAR)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level instanceof ServerLevel sl && level.getBlockEntity(pos) instanceof CanvasCellBlockEntity be)
            TarpService.shearTarp(sl, be.tarp(), player);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CanvasCellBlockEntity be)) return InteractionResult.PASS;
        if (!player.getMainHandItem().isEmpty()) return InteractionResult.PASS;
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
            return TarpService.canvasUse(sl, be.tarp(), sp, hit.getLocation(), player.isShiftKeyDown())
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!newState.is(this) && level instanceof ServerLevel sl && level.getBlockEntity(pos) instanceof CanvasCellBlockEntity be)
            TarpService.canvasRemoved(sl, be.tarp());
        super.onRemove(state, level, pos, newState, moving);
    }
}
