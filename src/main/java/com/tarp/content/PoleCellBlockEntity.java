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

/** 이 칸을 지나는 지지대 목록. 충돌·조준 모양은 여기서 만든다. */
public class PoleCellBlockEntity extends BlockEntity {
    public record Entry(int id, V3 a, V3 b) {}

    private final List<Entry> entries = new ArrayList<>();
    private VoxelShape shape;
    /** 이 칸이 첫 칸인 지지대 (그리는 칸, 0.3.12). 바뀔 때만 다시 센다. */
    private List<Entry> heads;
    @javax.annotation.Nullable private net.minecraft.world.phys.AABB headBox;

    public PoleCellBlockEntity(BlockPos pos, BlockState state) {
        super(TarpRegistry.POLE_CELL_BE.get(), pos, state);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** 이 칸에서 그리는 지지대 (지지대마다 첫 칸 하나만 그린다). */
    public List<Entry> heads() {
        if (heads == null) {
            List<Entry> h = new ArrayList<>();
            net.minecraft.world.phys.AABB box = null;
            for (Entry e : entries) {
                if (!com.tarp.core.Raster.firstIs(e.a(), e.b(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ())) continue;
                h.add(e);
                net.minecraft.world.phys.AABB b = new net.minecraft.world.phys.AABB(e.a().x(), e.a().y(), e.a().z(), e.b().x(), e.b().y(), e.b().z()).inflate(0.2);
                box = box == null ? b : box.minmax(b);
            }
            heads = h;
            headBox = box;
        }
        return heads;
    }

    /** 클라이언트만 (0.4.10): PoleRenderer 가 구워 둔 메시. 지지대 목록이 바뀌면 버린다. */
    @javax.annotation.Nullable public Object renderCache;
    @javax.annotation.Nullable private net.minecraft.world.phys.AABB cellBox;

    public net.minecraft.world.phys.AABB cellBox() {
        if (cellBox == null) cellBox = new net.minecraft.world.phys.AABB(worldPosition);
        return cellBox;
    }

    /** 그리는 지지대 전체를 덮는 상자, 그릴 것이 없으면 null. */
    @javax.annotation.Nullable
    public net.minecraft.world.phys.AABB headBox() {
        heads();
        return headBox;
    }

    /** 이 칸을 지나는 지지대의 끝점이 칸 b 에 닿는가 (칸 경계 위의 점은 양쪽 칸에 닿는다, Stability.blockContact 와 같은 기준). */
    public boolean endTouches(BlockPos b) {
        for (Entry e : entries)
            for (V3 p : new V3[]{e.a(), e.b()})
                if (p.x() >= b.getX() - 1e-4 && p.x() <= b.getX() + 1 + 1e-4 && p.y() >= b.getY() - 1e-4 && p.y() <= b.getY() + 1 + 1e-4
                        && p.z() >= b.getZ() - 1e-4 && p.z() <= b.getZ() + 1 + 1e-4) return true;
        return false;
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
        heads = null;
        renderCache = null;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public VoxelShape shape() {
        if (shape == null) {
            VoxelShape s = Shapes.empty();
            for (Entry e : entries) s = Shapes.or(s, segmentShape(worldPosition, e.a(), e.b(), Spec.POLE_THICK / 2));
            shape = s.optimize();
        }
        return shape;
    }

    /** 선분 중 이 칸 안에 든 부분을 굵기 r 의 상자들로. 축 방향이면 상자 하나. */
    public static VoxelShape segmentShape(BlockPos pos, V3 a, V3 b, double r) {
        V3 o = new V3(pos.getX(), pos.getY(), pos.getZ());
        V3 la = a.sub(o), lb = b.sub(o);
        double[] t = clip(la, lb);
        if (t == null) return Shapes.empty();
        V3 p0 = la.lerp(lb, t[0]), p1 = la.lerp(lb, t[1]);
        V3 d = lb.sub(la);
        int axes = (Math.abs(d.x()) > 1e-6 ? 1 : 0) + (Math.abs(d.y()) > 1e-6 ? 1 : 0) + (Math.abs(d.z()) > 1e-6 ? 1 : 0);
        if (axes <= 1) return box(Math.min(p0.x(), p1.x()) - r, Math.min(p0.y(), p1.y()) - r, Math.min(p0.z(), p1.z()) - r,
                Math.max(p0.x(), p1.x()) + r, Math.max(p0.y(), p1.y()) + r, Math.max(p0.z(), p1.z()) + r);
        VoxelShape s = Shapes.empty();
        double len = p0.dist(p1);
        int n = Math.max(1, (int) Math.ceil(len / 0.125));
        for (int i = 0; i <= n; i++) {
            V3 p = p0.lerp(p1, (double) i / n);
            s = Shapes.or(s, box(p.x() - r, p.y() - r, p.z() - r, p.x() + r, p.y() + r, p.z() + r));
        }
        return s;
    }

    private static VoxelShape box(double x0, double y0, double z0, double x1, double y1, double z1) {
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        z0 = Math.max(0, z0);
        x1 = Math.min(1, x1);
        y1 = Math.min(1, y1);
        z1 = Math.min(1, z1);
        if (x1 - x0 < 1e-4 || y1 - y0 < 1e-4 || z1 - z0 < 1e-4) return Shapes.empty();
        return Shapes.box(x0, y0, z0, x1, y1, z1);
    }

    /** 선분을 단위 칸 [0,1]³ 로 자른 매개변수 구간. */
    static double[] clip(V3 a, V3 b) {
        double t0 = 0, t1 = 1;
        double[] p = {a.x(), a.y(), a.z()}, d = {b.x() - a.x(), b.y() - a.y(), b.z() - a.z()};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (p[i] < -1e-6 || p[i] > 1 + 1e-6) return null;
                continue;
            }
            double ta = (0 - p[i]) / d[i], tb = (1 - p[i]) / d[i];
            if (ta > tb) {
                double x = ta;
                ta = tb;
                tb = x;
            }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) return null;
        }
        return new double[]{t0, t1};
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
        tag.put("poles", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        entries.clear();
        for (Tag raw : tag.getList("poles", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            entries.add(new Entry(t.getInt("id"), new V3(t.getDouble("ax"), t.getDouble("ay"), t.getDouble("az")),
                    new V3(t.getDouble("bx"), t.getDouble("by"), t.getDouble("bz"))));
        }
        shape = null;
        heads = null;
        renderCache = null;
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
