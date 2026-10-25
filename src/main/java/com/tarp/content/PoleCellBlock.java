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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * 지지대 점유 칸. 그림은 클라이언트 메시가 그리고, 이 블록은 충돌·조준·부수기만 맡는다.
 * COVERED (0.3.10): 방수포가 지나가는 칸. 천 칸 대신 이 칸이 천 노릇을 하므로 하늘빛을 천 칸과 똑같이 줄인다.
 * 전에는 마룻대·꼭대기 지지대 칸으로 하늘빛이 그대로 새어 천막 안 한가운데가 바깥만큼 밝았다.
 */
public class PoleCellBlock extends BaseEntityBlock {
    public static final MapCodec<PoleCellBlock> CODEC = simpleCodec(PoleCellBlock::new);
    public static final BooleanProperty COVERED = BooleanProperty.create("covered");

    public PoleCellBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(COVERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(COVERED);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PoleCellBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return level.getBlockEntity(pos) instanceof PoleCellBlockEntity be ? be.shape() : Shapes.empty();
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return !state.getValue(COVERED);
    }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getValue(COVERED) ? com.tarp.core.Spec.CANVAS_LIGHT_BLOCK : 0;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos from, boolean moving) {
        if (level instanceof ServerLevel sl) TarpService.scheduleCheck(sl, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!newState.is(this) && level instanceof ServerLevel sl) TarpService.poleCellRemoved(sl, pos);
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(TarpRegistry.POLE.get());
    }
}
