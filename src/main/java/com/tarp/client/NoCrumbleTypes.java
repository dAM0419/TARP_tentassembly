package com.tarp.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/**
 * entityCutout 과 같되 금 간 무늬(블록 캐기 진행)를 받지 않는 렌더 타입 (0.3.8).
 * 방수포·지지대는 칸 하나가 전체를 그리므로, 그 칸을 캐면 천막 전체에 금이 덮였다.
 */
final class NoCrumbleTypes extends RenderType {
    private NoCrumbleTypes(String n, VertexFormat f, VertexFormat.Mode m, int s, boolean c, boolean so, Runnable a, Runnable b) {
        super(n, f, m, s, c, so, a, b);
    }

    private static final Function<ResourceLocation, RenderType> CUTOUT = Util.memoize(tex -> {
        CompositeState st = CompositeState.builder().setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                .setTextureState(new TextureStateShard(tex, false, false)).setTransparencyState(NO_TRANSPARENCY)
                .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY).createCompositeState(true);
        return create("tarp_cutout", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, false, false, st);
    });

    static RenderType cutout(ResourceLocation tex) {
        return CUTOUT.apply(tex);
    }
}
