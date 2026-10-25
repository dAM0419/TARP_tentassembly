package com.tarp.content;

import com.mojang.serialization.MapCodec;
import com.tarp.world.TarpService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * 말뚝. 블록 종류와 상관없이 어느 면에나 박힌다 (윗면이면 수직, 옆면이면 수평).
 * FACING 은 말뚝이 뻗는 방향이고, 말뚝 머리(노끈·방수포 고정점)는 이 칸의 가운데다.
 */
public class StakeBlock extends Block {
    public static final MapCodec<StakeBlock> CODEC = simpleCodec(StakeBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        SHAPES.put(Direction.UP, box(6, 0, 6, 10, 4, 10));
        SHAPES.put(Direction.DOWN, box(6, 12, 6, 10, 16, 10));
        SHAPES.put(Direction.NORTH, box(6, 6, 12, 10, 10, 16));
        SHAPES.put(Direction.SOUTH, box(6, 6, 0, 10, 10, 4));
        SHAPES.put(Direction.WEST, box(12, 6, 6, 16, 10, 10));
        SHAPES.put(Direction.EAST, box(0, 6, 6, 4, 10, 10));
    }

    public StakeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState s = defaultBlockState().setValue(FACING, ctx.getClickedFace());
        return s.canSurvive(ctx.getLevel(), ctx.getClickedPos()) ? s : null;
    }

    /** 박을 수 있는 블록: 공기·대체 가능 블록·TARP 블록이 아니면 무엇이든. */
    public static boolean canHold(BlockState support) {
        if (support.isAir() || support.canBeReplaced()) return false;
        return !(support.getBlock() instanceof PoleCellBlock) && !(support.getBlock() instanceof CanvasCellBlock)
                && !(support.getBlock() instanceof StakeBlock) && !support.is(Blocks.MOVING_PISTON);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return canHold(level.getBlockState(pos.relative(state.getValue(FACING).getOpposite())));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (dir == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!newState.is(this) && level instanceof ServerLevel sl) TarpService.stakeRemoved(sl, pos, state);
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
