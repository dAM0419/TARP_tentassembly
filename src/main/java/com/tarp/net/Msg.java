package com.tarp.net;

/** 통신 종류 번호 (Net.PART = 0 은 조각). */
public final class Msg {
    private Msg() {}

    // 서버 → 클라이언트
    public static final int S_FULL = 1;
    public static final int S_POLE_ADD = 2;
    public static final int S_POLE_DEL = 3;
    public static final int S_ROPE_ADD = 4;
    public static final int S_ROPE_DEL = 5;
    public static final int S_TARP_ADD = 6;
    public static final int S_TARP_DEL = 7;
    public static final int S_TARP_DOOR = 8;
    public static final int S_DOOR_PREVIEW = 9;

    // 클라이언트 → 서버
    public static final int C_PLACE_POLE = 1;
    public static final int C_PLACE_ROPE = 2;
    public static final int C_PLACE_TARP = 3;
    public static final int C_KIT_CAPTURE = 4;
    public static final int C_KIT_ANCHOR = 5;
    public static final int C_KIT_UNANCHOR = 6;
    public static final int C_KIT_PART = 7;
}
