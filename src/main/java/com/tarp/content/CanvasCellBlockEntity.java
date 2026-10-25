package com.tarp.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.concurrent.ConcurrentHashMap;

/** 천 충돌 칸: 방수포 id 와 4×4×4 작은 칸 비트(64비트). */
public class CanvasCellBlockEntity extends BlockEntity {
    private static final ConcurrentHashMap<Long, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    private int tarp;
    private long mask;

    public CanvasCellBlockEntity(BlockPos pos, BlockState state) {
        super(TarpRegistry.CANVAS_CELL_BE.get(), pos, state);
    }

    public int tarp() {
        return tarp;
    }

    public long mask() {
        return mask;
    }

    public void set(int tarp, long mask) {
        if (this.tarp == tarp && this.mask == mask) return;
        this.tarp = tarp;
        this.mask = mask;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public VoxelShape shape() {
        return shapeOf(mask);
    }

    /** 작은 칸 비트 번호: x*16 + y*4 + z (각 0~3). */
    public static int bit(int sx, int sy, int sz) {
        return sx * 16 + sy * 4 + sz;
    }

    public static VoxelShape shapeOf(long mask) {
        if (mask == 0) return Shapes.empty();
        return SHAPES.computeIfAbsent(mask, m -> {
            VoxelShape s = Shapes.empty();
            for (int i = 0; i < 64; i++) {
                if ((m >>> i & 1L) == 0) continue;
                int x = i / 16, y = (i / 4) % 4, z = i % 4;
                s = Shapes.joinUnoptimized(s, Shapes.box(x / 4.0, y / 4.0, z / 4.0, (x + 1) / 4.0, (y + 1) / 4.0, (z + 1) / 4.0),
                        net.minecraft.world.phys.shapes.BooleanOp.OR);
            }
            return s.optimize();
        });
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("tarp", tarp);
        tag.putLong("mask", mask);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        tarp = tag.getInt("tarp");
        mask = tag.getLong("mask");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
