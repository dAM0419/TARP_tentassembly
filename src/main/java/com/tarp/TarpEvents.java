package com.tarp;

import com.tarp.content.PoleItem;
import com.tarp.content.RopeItem;
import com.tarp.content.TarpItem;
import com.tarp.core.V3;
import com.tarp.net.Msg;
import com.tarp.net.Net;
import com.tarp.world.Codecs;
import com.tarp.world.Demo;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.tarp.world.TarpService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;

/** 서버(공통) 이벤트: 틱, 접속·차원 이동 시 전체 동기화, 도구 클릭 막기, 클라이언트 요청 처리. */
public final class TarpEvents {
    private TarpEvents() {}

    public static void init() {
        Net.serverReceiver = TarpEvents::receive;
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post e) -> {
            if (e.getLevel() instanceof ServerLevel sl) TarpService.tick(sl);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> sync(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                Commands.literal("tarp").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("demo").executes(ctx -> Demo.build(ctx.getSource().getPlayerOrException())))));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> sync(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> sync(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> Net.forgetPlayer(e.getEntity().getUUID()));
        // 도구는 클라이언트가 클릭을 받아 따로 보낸다. 서버로 온 기본 사용 패킷은 막는다 (상자 열기 등 방지)
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock e) -> {
            if (!e.getLevel().isClientSide() && isTool(e.getEntity().getMainHandItem())) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickItem e) -> {
            if (!e.getLevel().isClientSide() && isTool(e.getEntity().getMainHandItem())) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
            }
        });
    }


    static boolean isTool(ItemStack s) {
        return s.getItem() instanceof PoleItem || s.getItem() instanceof RopeItem || s.getItem() instanceof TarpItem
                || s.getItem() instanceof com.tarp.content.KitItem;
    }

    private static void sync(net.minecraft.world.entity.player.Player p) {
        if (p instanceof ServerPlayer sp) TarpService.sendAll(sp);
    }

    private static void receive(ServerPlayer pl, Net.Frame f) {
        var b = f.buf();
        switch (f.kind()) {
            case Msg.C_PLACE_POLE -> TarpService.placePole(pl, Codecs.readV3(b), Codecs.readV3(b));
            case Msg.C_PLACE_ROPE -> TarpService.placeRope(pl, Codecs.readV3(b), Codecs.readV3(b));
            case Msg.C_PLACE_TARP -> {
                int n = b.readVarInt();
                if (n < 3 || n > 64) return;
                List<V3> ties = new ArrayList<>();
                for (int i = 0; i < n; i++) ties.add(Codecs.readV3(b));
                TarpService.placeTarp(pl, ties);
            }
            case Msg.C_KIT_CAPTURE -> com.tarp.world.Kit.capture(pl, b.readBlockPos());
            case Msg.C_KIT_ANCHOR -> com.tarp.world.Kit.anchor(pl, Codecs.readV3(b), b.readVarInt());
            case Msg.C_KIT_UNANCHOR -> com.tarp.world.Kit.unanchor(pl);
            case Msg.C_KIT_PART -> com.tarp.world.Kit.placePart(pl, b.readVarInt(), b.readVarInt());
            default -> {
            }
        }
    }
}
