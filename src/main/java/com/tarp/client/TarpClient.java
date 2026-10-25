package com.tarp.client;

import com.tarp.content.TarpItem;
import com.tarp.content.TarpRegistry;
import com.tarp.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** 클라이언트 쪽 등록: 수신, 도구 클릭, 스크롤, 그리기, 아이템 색. */
public final class TarpClient {
    private TarpClient() {}

    public static void init(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        // 모드 목록의 '설정' 버튼 (NeoForge 기본 설정 화면)
        container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                net.neoforged.neoforge.client.gui.ConfigurationScreen::new);
        Net.clientReceiver = ClientStore::receive;
        modBus.addListener(TarpClient::itemColors);
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e) -> {
            e.registerBlockEntityRenderer(TarpRegistry.CANVAS_CELL_BE.get(), TarpRenderer::new);
            e.registerBlockEntityRenderer(TarpRegistry.POLE_CELL_BE.get(), PoleRenderer::new);
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> KitController.tickPre());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            ToolController.tick();
            MeshRenderer.tick();
        });
        NeoForge.EVENT_BUS.addListener(MeshRenderer::render);
        NeoForge.EVENT_BUS.addListener(PreviewRenderer::render);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, TarpClient::rightClickBlock);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, TarpClient::rightClickItem);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (InputEvent.MouseScrollingEvent e) -> {
            if (Minecraft.getInstance().screen == null && ToolController.scroll(e.getScrollDeltaY()))
                e.setCanceled(true);
        });

        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientChatReceivedEvent.System e) -> {
            // 0.4.6: 다른 모드·바닐라의 행동바 메시지까지 받으면 안내가 계속 밀려 지지대 높이가 늦게 갱신됐다
            if (e.isOverlay() && e.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
                    && tc.getKey().startsWith("tarp.msg")) ToolController.serverMessage();
        });

        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            ClientStore.clear();
            MeshRenderer.clear();
            ToolController.reset();
            Net.resetClient();
        });
    }

    private static void rightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!e.getLevel().isClientSide()) return;
        if (KitController.holding()) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            if (e.getHand() == InteractionHand.MAIN_HAND) KitController.click();
            return;
        }
        if (!ToolController.holdingTool()) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() == InteractionHand.MAIN_HAND) ToolController.click();
    }

    private static void rightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (!e.getLevel().isClientSide()) return;
        if (KitController.holding()) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            if (e.getHand() == InteractionHand.MAIN_HAND) KitController.click();
            return;
        }
        if (!ToolController.holdingTool()) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getHand() == InteractionHand.MAIN_HAND) ToolController.click();
    }


    private static void itemColors(RegisterColorHandlersEvent.Item e) {
        e.register((stack, tint) -> {
            if (tint != 0) return -1;
            int c = Minecraft.getInstance().getBlockColors().getColor(TarpItem.leafBlock(TarpItem.leafId(stack)).defaultBlockState(), null, null, 0);
            return c == -1 ? -1 : 0xFF000000 | c;
        }, TarpRegistry.LEAF_COVER.get());
    }
}
