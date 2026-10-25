package com.tarp;

import com.tarp.client.TarpClient;
import com.tarp.content.TarpRegistry;
import com.tarp.net.Net;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/** TARP (Tent Assembly - Rigging and Pitching): 재료 네 가지로 모든 천막을 조립한다. */
@Mod(Tarp.MODID)
public final class Tarp {
    public static final String MODID = "tarp";

    public Tarp(IEventBus modBus, ModContainer container) {
        TarpRegistry.register(modBus);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, TarpConfig.SPEC);
        modBus.addListener(Net::register);
        TarpEvents.init();
        if (FMLEnvironment.dist == Dist.CLIENT) TarpClient.init(modBus, container);
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
