package com.tarp.world;

import com.tarp.core.Membrane;
import com.tarp.core.Stability;
import com.tarp.core.V3;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

/** 지지대·노끈·방수포를 NBT 와 통신 버퍼로 옮긴다. */
public final class Codecs {
    private Codecs() {}

    // ---- 통신 ----

    public static void write(FriendlyByteBuf b, V3 p) {
        b.writeDouble(p.x());
        b.writeDouble(p.y());
        b.writeDouble(p.z());
    }

    public static V3 readV3(FriendlyByteBuf b) {
        return new V3(b.readDouble(), b.readDouble(), b.readDouble());
    }

    public static void write(FriendlyByteBuf b, Stability.Pole p) {
        b.writeVarInt(p.id());
        write(b, p.a());
        write(b, p.b());
    }

    public static Stability.Pole readPole(FriendlyByteBuf b) {
        return new Stability.Pole(b.readVarInt(), readV3(b), readV3(b));
    }

    public static void write(FriendlyByteBuf b, Stability.Rope r) {
        b.writeVarInt(r.id());
        write(b, r.a());
        write(b, r.b());
    }

    public static Stability.Rope readRope(FriendlyByteBuf b) {
        return new Stability.Rope(b.readVarInt(), readV3(b), readV3(b));
    }

    public static void write(FriendlyByteBuf b, TarpRec t) {
        b.writeVarInt(t.id);
        b.writeVarInt(t.kind);
        b.writeVarInt(t.color);
        b.writeUtf(t.leaf);
        b.writeVarInt(t.ties.size());
        for (V3 p : t.ties) write(b, p);
        b.writeVarInt(t.obstacles.size());
        for (Membrane.Obstacle o : t.obstacles) {
            write(b, o.a());
            write(b, o.b());
            b.writeDouble(o.clear());
            b.writeBoolean(o.frame());
        }
        b.writeVarInt(t.doorEdge);
        b.writeBoolean(t.open);
        b.writeLong(t.placedAt);
    }

    public static TarpRec readTarp(FriendlyByteBuf b) {
        TarpRec t = new TarpRec();
        t.id = b.readVarInt();
        t.kind = b.readVarInt();
        t.color = b.readVarInt();
        t.leaf = b.readUtf();
        int n = b.readVarInt();
        for (int i = 0; i < n; i++) t.ties.add(readV3(b));
        int m = b.readVarInt();
        for (int i = 0; i < m; i++) t.obstacles.add(new Membrane.Obstacle(readV3(b), readV3(b), b.readDouble(), b.readBoolean()));
        t.setDoor(b.readVarInt());
        t.open = b.readBoolean();
        t.placedAt = b.readLong();
        return t;
    }

    // ---- NBT ----

    static ListTag v3s(List<V3> list) {
        ListTag out = new ListTag();
        for (V3 p : list) out.add(v3(p));
        return out;
    }

    static CompoundTag v3(V3 p) {
        CompoundTag t = new CompoundTag();
        t.putDouble("x", p.x());
        t.putDouble("y", p.y());
        t.putDouble("z", p.z());
        return t;
    }

    static V3 v3(CompoundTag t) {
        return new V3(t.getDouble("x"), t.getDouble("y"), t.getDouble("z"));
    }

    static CompoundTag pole(Stability.Pole p) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", p.id());
        t.put("a", v3(p.a()));
        t.put("b", v3(p.b()));
        return t;
    }

    static Stability.Pole pole(CompoundTag t) {
        return new Stability.Pole(t.getInt("id"), v3(t.getCompound("a")), v3(t.getCompound("b")));
    }

    static CompoundTag rope(Stability.Rope r) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", r.id());
        t.put("a", v3(r.a()));
        t.put("b", v3(r.b()));
        return t;
    }

    static Stability.Rope rope(CompoundTag t) {
        return new Stability.Rope(t.getInt("id"), v3(t.getCompound("a")), v3(t.getCompound("b")));
    }

    static CompoundTag tarp(TarpRec r) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", r.id);
        t.putInt("kind", r.kind);
        t.putInt("color", r.color);
        t.putString("leaf", r.leaf);
        t.put("ties", v3s(r.ties));
        ListTag obs = new ListTag();
        for (Membrane.Obstacle o : r.obstacles) {
            CompoundTag ot = new CompoundTag();
            ot.put("a", v3(o.a()));
            ot.put("b", v3(o.b()));
            ot.putDouble("c", o.clear());
            ot.putBoolean("f", o.frame());
            obs.add(ot);
        }
        t.put("obs", obs);
        t.putIntArray("poles", r.poleDeps);
        t.putIntArray("ropes", r.ropeDeps);
        t.putLongArray("stakes", r.stakeDeps);
        t.putInt("door", r.doorEdge);
        t.putBoolean("open", r.open);
        t.putLong("placed", r.placedAt);
        t.putLongArray("cells", r.cells);
        return t;
    }

    static TarpRec tarp(CompoundTag t) {
        TarpRec r = new TarpRec();
        r.id = t.getInt("id");
        r.kind = t.getInt("kind");
        r.color = t.getInt("color");
        r.leaf = t.getString("leaf");
        for (Tag raw : t.getList("ties", Tag.TAG_COMPOUND)) r.ties.add(v3((CompoundTag) raw));
        for (Tag raw : t.getList("obs", Tag.TAG_COMPOUND)) {
            CompoundTag ot = (CompoundTag) raw;
            r.obstacles.add(new Membrane.Obstacle(v3(ot.getCompound("a")), v3(ot.getCompound("b")), ot.getDouble("c"), ot.getBoolean("f")));
        }
        r.poleDeps = t.getIntArray("poles");
        r.ropeDeps = t.getIntArray("ropes");
        r.stakeDeps = t.getLongArray("stakes");
        r.setDoor(t.getInt("door"));
        r.open = t.getBoolean("open");
        r.placedAt = t.getLong("placed");
        r.cells = t.getLongArray("cells");
        return r;
    }
}
