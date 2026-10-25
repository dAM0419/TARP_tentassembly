package com.tarp.client;

import com.tarp.core.Stability;
import com.tarp.net.Msg;
import com.tarp.net.Net;
import com.tarp.world.Codecs;
import com.tarp.world.Structures;
import com.tarp.world.TarpRec;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

/** 서버가 보낸 지금 차원의 구조 사본. */
public final class ClientStore {
    private ClientStore() {}

    public static final Structures S = new Structures();

    /** 입구 미리보기: 방수포 id, 변, 오류(-1 = 가능), 끝나는 틱. */
    public static int previewTarp = -1, previewEdge = -1, previewError = -1;
    public static long previewUntil;

    public static void clear() {
        S.clear();
        previewTarp = -1;
    }

    public static void receive(Net.Frame f) {
        FriendlyByteBuf b = f.buf();
        switch (f.kind()) {
            case Msg.S_FULL -> {
                S.clear();
                int n = b.readVarInt();
                for (int i = 0; i < n; i++) S.addPole(Codecs.readPole(b));
                n = b.readVarInt();
                for (int i = 0; i < n; i++) S.addRope(Codecs.readRope(b));
                n = b.readVarInt();
                for (int i = 0; i < n; i++) S.addTarp(Codecs.readTarp(b));
            }
            case Msg.S_POLE_ADD -> {
                int n = b.readVarInt();
                for (int i = 0; i < n; i++) {
                    Stability.Pole p = Codecs.readPole(b);
                    S.removePole(p.id());
                    S.addPole(p);
                }
            }
            case Msg.S_POLE_DEL -> {
                int n = b.readVarInt();
                for (int i = 0; i < n; i++) S.removePole(b.readVarInt());
            }
            case Msg.S_ROPE_ADD -> {
                int n = b.readVarInt();
                for (int i = 0; i < n; i++) S.addRope(Codecs.readRope(b));
            }
            case Msg.S_ROPE_DEL -> {
                int n = b.readVarInt();
                for (int i = 0; i < n; i++) S.removeRope(b.readVarInt());
            }
            case Msg.S_TARP_ADD -> {
                int n = b.readVarInt();
                for (int i = 0; i < n; i++) S.addTarp(Codecs.readTarp(b));
            }
            case Msg.S_TARP_DEL -> {
                int n = b.readVarInt();
                for (int i = 0; i < n; i++) S.removeTarp(b.readVarInt());
            }
            case Msg.S_TARP_DOOR -> {
                TarpRec t = S.tarps.get(b.readVarInt());
                int edge = b.readVarInt();
                boolean open = b.readBoolean();
                if (t != null) {
                    t.setDoor(edge);
                    t.open = open;
                    S.version++;
                    TarpRenderer.dirty(t.id);
                }
                previewTarp = -1;
            }
            case Msg.S_DOOR_PREVIEW -> {
                previewTarp = b.readVarInt();
                previewEdge = b.readVarInt();
                previewError = b.readVarInt();
                Minecraft mc = Minecraft.getInstance();
                previewUntil = mc.level == null ? 0 : mc.level.getGameTime() + 400;
            }
            default -> {
            }
        }
    }
}
