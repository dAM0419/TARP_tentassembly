package com.tarp;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 모드 설정 (config/tarp-common.toml, 게임 안 모드 목록 → TARP → 설정에서도 바꿀 수 있다).
 * 공통 설정이라 멀티플레이에서는 서버의 값을 따른다.
 */
public final class TarpConfig {
    private TarpConfig() {}

    public static final ModConfigSpec SPEC;
    /** 천막 키트: 위치를 고정하면 부품을 순서대로 누르지 않고 한 번에 설치한다. */
    public static final ModConfigSpec.BooleanValue KIT_INSTANT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("kit");
        KIT_INSTANT = b.comment("Tent kit: pitch the whole tent at once when the projection is anchored (default false = click parts in order).",
                        "천막 키트: 투영 위치를 고정하면 부품을 하나씩 누르지 않고 천막 전체를 한 번에 설치한다 (기본 false = 부품 순서대로).")
                .translation("tarp.configuration.kitInstantPitch")
                .define("instantPitch", false);
        b.pop();
        SPEC = b.build();
    }
}
