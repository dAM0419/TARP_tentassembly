package com.tarp.content;

import com.tarp.core.Spec;
import com.tarp.core.V3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/** 노끈 점유 칸 (0.4.7): 이 칸을 지나는 노끈 목록. 충돌·조준 모양은 노끈 굵기의 선분. */
public class RopeCellBlockEntity extends BlockEntity {
    public record Entry(int id, V3 a, V3 b) {}

    private final List<Entry> entries = new ArrayList<>();
    private VoxelShape shape;

    public RopeCellBlockEntity(BlockPos pos, BlockState state) {
        super(TarpRegistry.ROPE_CELL_BE.get(), pos, state);
    }

    public List<Entry> entries() {
        return entries;
    }

    public void add(int id, V3 a, V3 b) {
        entries.removeIf(e -> e.id() == id);
        entries.add(new Entry(id, a, b));
        changed();
    }

    public void remove(int id) {
        if (entries.removeIf(e -> e.id() == id)) changed();
    }

    private void changed() {
        shape = null;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public VoxelShape shape() {
        if (shape == null) {
            VoxelShape s = Shapes.empty();
            // 조준하기 쉽게 실제 굵기(1/16)보다 조금 굵게
            for (Entry e : entries) s = Shapes.or(s, PoleCellBlockEntity.segmentShape(worldPosition, e.a(), e.b(), Spec.ROPE_THICK));
            shape = s.optimize();
        }
        return shape;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag t = new CompoundTag();
            t.putInt("id", e.id());
            t.putDouble("ax", e.a().x());
            t.putDouble("ay", e.a().y());
            t.putDouble("az", e.a().z());
            t.putDouble("bx", e.b().x());
            t.putDouble("by", e.b().y());
            t.putDouble("bz", e.b().z());
            list.add(t);
        }
        tag.put("ropes", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        entries.clear();
        for (Tag raw : tag.getList("ropes", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            entries.add(new Entry(t.getInt("id"), new V3(t.getDouble("ax"), t.getDouble("ay"), t.getDouble("az")),
                    new V3(t.getDouble("bx"), t.getDouble("by"), t.getDouble("bz"))));
        }
        shape = null;
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
