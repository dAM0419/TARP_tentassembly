package com.tarp.world;

import com.tarp.core.Spec;
import com.tarp.core.Stability;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** 차원 하나의 TARP 구조 저장소. */
public final class TarpData extends SavedData {
    public static final String FILE_ID = "tarp_structures";

    public final Structures s = new Structures();
    /** 지지대 id → 들어간 아이템 수 (서버만). 자를 때 길이 비율로 나눠 아이템이 늘지 않게 한다. */
    public final java.util.Map<Integer, Integer> poleValue = new java.util.HashMap<>();

    public int value(Stability.Pole p) {
        Integer v = poleValue.get(p.id());
        return v != null ? v : Spec.poleCost(p.a().dist(p.b()));
    }
    /**
     * 노끈 id → 들어간 아이템 수 (서버만, 0.4.11). 걷을 때 이만큼 돌려준다.
     * 0.4.10 이하에서 친 노끈은 값이 없고, 그때 규칙(1칸에 1개)으로 돌려준다 (낸 만큼 돌려받게).
     */
    public final java.util.Map<Integer, Integer> ropeValue = new java.util.HashMap<>();

    public int value(Stability.Rope r) {
        Integer v = ropeValue.get(r.id());
        return v != null ? v : (int) Math.ceil(r.a().dist(r.b()) - 1e-6);
    }

    private int nextId = 1;

    public static TarpData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(TarpData::new, TarpData::load), FILE_ID);
    }

    public int newId() {
        setDirty();
        return nextId++;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("nextId", nextId);
        ListTag poles = new ListTag();
        for (Stability.Pole p : s.poles.values()) poles.add(Codecs.pole(p));
        tag.put("poles", poles);
        int[] vals = new int[poleValue.size() * 2];
        int i = 0;
        for (var e : poleValue.entrySet()) {
            vals[i++] = e.getKey();
            vals[i++] = e.getValue();
        }
        tag.putIntArray("poleValues", vals);
        int[] rv = new int[ropeValue.size() * 2];
        i = 0;
        for (var e : ropeValue.entrySet()) {
            rv[i++] = e.getKey();
            rv[i++] = e.getValue();
        }
        tag.putIntArray("ropeValues", rv);
        ListTag ropes = new ListTag();
        for (Stability.Rope r : s.ropes.values()) ropes.add(Codecs.rope(r));
        tag.put("ropes", ropes);
        ListTag tarps = new ListTag();
        for (TarpRec t : s.tarps.values()) tarps.add(Codecs.tarp(t));
        tag.put("tarps", tarps);
        return tag;
    }

    public static TarpData load(CompoundTag tag, HolderLookup.Provider registries) {
        TarpData d = new TarpData();
        d.nextId = Math.max(1, tag.getInt("nextId"));
        for (Tag raw : tag.getList("poles", Tag.TAG_COMPOUND)) d.s.addPole(Codecs.pole((CompoundTag) raw));
        int[] vals = tag.getIntArray("poleValues");
        for (int i = 0; i + 1 < vals.length; i += 2) d.poleValue.put(vals[i], vals[i + 1]);
        for (Tag raw : tag.getList("ropes", Tag.TAG_COMPOUND)) d.s.addRope(Codecs.rope((CompoundTag) raw));
        int[] rv = tag.getIntArray("ropeValues");
        for (int i = 0; i + 1 < rv.length; i += 2) d.ropeValue.put(rv[i], rv[i + 1]);
        for (Tag raw : tag.getList("tarps", Tag.TAG_COMPOUND)) d.s.addTarp(Codecs.tarp((CompoundTag) raw));
        return d;
    }
}
