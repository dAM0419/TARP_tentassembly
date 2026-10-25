package com.tarp.core;

/** 설계서 수치. 시험 후 조정할 값은 여기만 바꾼다. */
public final class Spec {
    private Spec() {}

    /** 지지대 최대 길이(점유 칸 수). */
    public static final int POLE_MAX_CELLS = 16;
    /** 지지대 아이템 하나가 채우는 길이(칸). */
    public static final double POLE_PER_ITEM = 2;

    /** 지지대 소모 개수: 길이(칸)를 POLE_PER_ITEM 으로 나눠 올림. 닿는 칸 수가 아니라 실제 길이로 센다. */
    public static int poleCost(double length) {
        return Math.max(1, (int) Math.ceil(length / POLE_PER_ITEM - 1e-6));
    }

    /** 노끈 아이템 하나가 잇는 길이(칸). 0.4.11: 지지대와 같게 2칸 (전엔 1칸). */
    public static final double ROPE_PER_ITEM = 2;

    /** 노끈 소모 개수: 길이(칸)를 ROPE_PER_ITEM 으로 나눠 올림. */
    public static int ropeCost(double length) {
        return Math.max(1, (int) Math.ceil(length / ROPE_PER_ITEM - 1e-6));
    }

    /** 노끈 최대 길이(칸). */
    public static final double ROPE_MAX = 32;
    /** 유격 α (칸). */
    public static final double ALPHA = 0.2;
    /** 지지대 두께 2/16칸. */
    public static final double POLE_THICK = 2.0 / 16;
    /** 노끈 두께 1/16칸. */
    public static final double ROPE_THICK = 1.0 / 16;
    /**
     * 막은 골조 표면에 닿아 지지된다 (띄우지 않는다). 중심선에서 표면까지 = 네모 각기둥 모서리까지 = 반지름 × √2.
     * 간격(유격)은 밑단과 땅 사이의 ALPHA 하나뿐이다 (0.3.4).
     */
    public static final double POLE_CLEAR = POLE_THICK / 2 * Math.sqrt(2);
    /** 노끈 표면까지. */
    public static final double ROPE_CLEAR = ROPE_THICK / 2 * Math.sqrt(2) + 0.015; // 0.4.8: +0.015 여유 (겉면에 딱 붙으면 깜빡이며 비쳤다)

    /** 방수포 넓이 상한(칸², 커튼 포함): 소·중·대. */
    public static final double[] TARP_AREA = {100, 200, 300};
    /** 잎 덮개 넓이 상한 (설계서: 소형 방수포와 같다). */
    public static final double LEAF_AREA = 100;
    /** 잎 덮개 수명(틱). */
    public static final long LEAF_LIFE = 24000;

    /** 천 무늬 한 장이 덮는 길이(칸). */
    public static final double CLOTH_TILE = 2.0;
    /**
     * 안쪽 면 색 배율 (바깥면 대비) = 천을 지나며 줄어드는 빛. 0.3.11: 0.85 → 0.65.
     * 음영(해를 받는 면은 밝고 그늘 면은 어둡다)은 바깥면과 같고, 전체가 이만큼 어둡다.
     */
    public static final double CLOTH_INNER = 0.65;
    /** 천 투과율: 바깥 횃불·랜턴 빛(블록 빛)이 천을 지나 안쪽 면에 닿는 비율. */
    public static final double CLOTH_TRANSMIT = 0.45;
    /**
     * 천 칸(과 천이 지나가는 지지대 칸)이 줄이는 빛 (0~15). 0.3.10: 15 → 6, 0.3.11: 6 → 4.
     * 목표: 바깥(트인 땅, 빛 15)을 100%로 볼 때 안이 약 50% (밝기 설정 기본값 기준).
     * 바깥 15 → 천 칸 11 → 천 바로 안 10 (57%) → 한 칸 더 안 9 (50%) → 8 (43%).
     * 바닐라 빛은 칸마다 정수로 빼는 방식이라 비율을 일정하게 할 수 없다: 바깥이 13이면 54/45%, 10이면 43/33%.
     * 밤에도 트인 땅의 하늘빛 값은 15이고 어두움은 화면 단계에서 곱해지므로, 낮밤으로는 비율이 거의 유지된다.
     */
    public static final int CANVAS_LIGHT_BLOCK = 4;

    /** 막 계산 격자 간격(칸). */
    public static final double GRID = 0.25;
    /** 천막 하나의 최대 가로·세로(칸). */
    public static final double MAX_SPAN = 40;

    /** 방수포 색 바래기 (0.4.4): 채도를 이만큼 빼고(회색 쪽으로), FADE_LIGHT 만큼 옅은 베이지를 섞는다. 아이콘도 같은 식. */
    public static final double FADE_SAT = 0.30, FADE_LIGHT = 0.08;
    public static final int FADE_TINT = 0xE8E0D0;

    public static int fade(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        double l = 0.299 * r + 0.587 * g + 0.114 * b;
        double[] c = {r + (l - r) * FADE_SAT, g + (l - g) * FADE_SAT, b + (l - b) * FADE_SAT};
        int[] t = {(FADE_TINT >> 16) & 255, (FADE_TINT >> 8) & 255, FADE_TINT & 255};
        int out = 0;
        for (int i = 0; i < 3; i++) out = (out << 8) | (int) Math.round(Math.max(0, Math.min(255, c[i] + (t[i] - c[i]) * FADE_LIGHT)));
        return out;
    }

    /** 입구 높이(칸). */
    public static final int DOOR_HEIGHT = 2;

    /** 넓이에 맞는 가장 작은 방수포 크기(0 소, 1 중, 2 대), 없으면 -1. */
    public static int sizeFor(double area) {
        for (int i = 0; i < TARP_AREA.length; i++) if (area <= TARP_AREA[i]) return i;
        return -1;
    }
}
