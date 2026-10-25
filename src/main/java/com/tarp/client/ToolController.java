package com.tarp.client;

import com.tarp.content.PoleItem;
import com.tarp.content.RopeItem;
import com.tarp.content.TarpItem;
import com.tarp.core.Raster;
import com.tarp.core.Spec;
import com.tarp.core.Stability;
import com.tarp.core.V3;
import com.tarp.net.Msg;
import com.tarp.net.Net;
import com.tarp.world.Codecs;
import com.tarp.world.Planner;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 손에 든 도구별 클릭 흐름. 클릭은 클라이언트에서 받아 점을 모으고, 확정할 때만 서버로 보낸다 (서버가 다시 검증).
 * <ul>
 *   <li>지지대: 시작점 → 끝점. 기존 점을 잡기 전에는 시작점에서 수직으로만 늘어난다. 웅크리고 우클릭 = 취소.</li>
 *   <li>노끈: 묶는 점 → 묶는 점, 끝점에서 이어서 계속. 웅크리고 우클릭 = 끝.</li>
 *   <li>방수포: 묶는 점을 차례로, 첫 점을 다시 찍으면 설치. 웅크리고 골조 = 둘레 자동 제안, 찍는 중 웅크리고 우클릭 = 취소.</li>
 * </ul>
 */
public final class ToolController {
    private ToolController() {}

    public enum Tool { NONE, POLE, ROPE, TARP }

    static Tool tool = Tool.NONE;
    /** 지지대·노끈 시작점. */
    @Nullable static V3 start;
    /** 방수포 묶는 점. */
    static final List<V3> ties = new ArrayList<>();
    /** 이번 프레임 겨냥한 점. */
    @Nullable static Snap.Target target;
    /** 방수포 투영 (점이 바뀔 때만 다시 계산). */
    @Nullable static Planner.TarpPlan plan;
    static int planVersion = -1;
    static ItemStack planItem = ItemStack.EMPTY;
    /** 수직으로 늘릴 때의 길이(칸, 휠로 ½칸씩). */
    static double verticalLen = 2;
    /** 스냅된 방향으로 더하거나 뺀 길이(½칸 단위). 스냅 점이 바뀌면 0. */
    static int extraSteps = 0;
    @Nullable static V3 snapLock;

    static Tool toolOf(ItemStack s) {
        if (s.getItem() instanceof PoleItem) return Tool.POLE;
        if (s.getItem() instanceof RopeItem) return Tool.ROPE;
        if (s.getItem() instanceof TarpItem) return Tool.TARP;
        return Tool.NONE;
    }

    public static boolean holdingTool() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && toolOf(mc.player.getMainHandItem()) != Tool.NONE;
    }

    public static void reset() {
        start = null;
        extraSteps = 0;
        snapLock = null;
        verticalLen = 2;
        ties.clear();
        plan = null;
    }

    /** 클라이언트 틱: 든 도구가 바뀌면 선택을 버리고, 안내 문구를 띄운다. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandItem();
        Tool t = toolOf(held);
        if (t != tool || (t == Tool.TARP && !ItemStack.isSameItemSameComponents(held, planItem) && !ties.isEmpty())) {
            tool = t;
            reset();
        }
        if (!ItemStack.isSameItemSameComponents(held, planItem)) planItem = held.copy();
        if (t == Tool.TARP && !ties.isEmpty() && (plan == null || planVersion != ClientStore.S.version)) replan();
        Component info = KitController.holding() ? KitController.info() : info();
        long now = mc.level == null ? 0 : mc.level.getGameTime();
        if (info != null && now - serverMsgAt > 30) {
            // 0.4.9: 매 틱 다시 띄운다 (0.3.x 방식). 0.4.0 의 "바뀔 때·2초마다만"은 다른 출처의 행동바 문구에
            // 덮이면 최대 2초 안 보였고, 지지대 개수 안내가 사라진 것처럼 보였다. 우리 서버 결과 문구만 1.5초 비켜 준다.
            mc.player.displayClientMessage(info, true);
        }
    }

    private static long serverMsgAt = Long.MIN_VALUE / 2;

    /** 서버가 행동바에 결과를 띄웠다: 2초 동안 안내로 덮지 않는다. */
    public static void serverMessage() {
        Minecraft mc = Minecraft.getInstance();
        serverMsgAt = mc.level == null ? 0 : mc.level.getGameTime();
    }

    /** 렌더 프레임마다 겨냥점을 갱신. */
    public static void frame(float partial) {
        target = switch (tool) {
            case POLE -> start == null ? Snap.pick(partial, false, null) : poleEnd(Snap.pick(partial, false, start));
            case ROPE, TARP -> Snap.pick(partial, true, null);
            case NONE -> null;
        };
    }

    /**
     * 지지대 끝점: 다른 점을 겨누지 않으면 시작점에서 수직으로 verticalLen 만큼.
     * 겨누면 그 방향·각도로 고정하고 휠로 길이만 ½칸씩 바꾼다. 겨눈 점이 바뀌거나 풀리면 다시 수직.
     */
    @Nullable
    static Snap.Target poleEnd(@Nullable Snap.Target t) {
        if (start == null) return t;
        if (t == null || t.kind() == Snap.Kind.AIR) {
            snapLock = null;
            extraSteps = 0;
            return new Snap.Target(new V3(start.x(), start.y() + verticalLen, start.z()), Snap.Kind.AIR, -1);
        }
        if (snapLock == null || snapLock.dist(t.p()) > 1e-6) {
            snapLock = t.p();
            extraSteps = 0;
        }
        if (extraSteps == 0) return t;
        V3 d = t.p().sub(start);
        double len = d.len();
        if (len < 1e-6) return t;
        double nl = Math.max(0.5, Math.min(Spec.POLE_MAX_CELLS, len + extraSteps * 0.5));
        return new Snap.Target(start.add(d.mul(nl / len)), Snap.Kind.BLOCK, -1);
    }

    /** 휠: 지지대·노끈을 찍는 중이면 핫바를 바꾸지 않는다. 지지대는 길이 조정. 처리했으면 true. */
    public static boolean scroll(double dy) {
        if (start == null || (tool != Tool.POLE && tool != Tool.ROPE) || dy == 0) return false;
        int sg = dy > 0 ? 1 : -1;
        if (tool == Tool.POLE) {
            if (snapLock != null) {
                V3 d = snapLock.sub(start);
                double nl = d.len() + (extraSteps + sg) * 0.5;
                if (nl >= 0.5 - 1e-6 && nl <= Spec.POLE_MAX_CELLS + 1e-6) extraSteps += sg;
            } else {
                verticalLen = Math.max(0.5, Math.min(Spec.POLE_MAX_CELLS, verticalLen + sg * 0.5));
            }
        }
        return true;
    }

    /** 우클릭. 처리했으면 true. */
    public static boolean click() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || tool == Tool.NONE) return false;
        boolean sneak = mc.player.isShiftKeyDown();
        frame(1);
        Snap.Target t = target;
        switch (tool) {
            case POLE -> {
                // 웅크리고 우클릭 = 선택 취소 (시작점을 찍은 뒤 어디서든)
                if (sneak && start != null) {
                    reset();
                    tellCancel();
                    return true;
                }
                if (t == null) return true;
                if (start == null) {
                    if (t.kind() != Snap.Kind.AIR) {
                        start = t.p();
                        verticalLen = 2;
                        extraSteps = 0;
                        snapLock = null;
                    }
                } else if (t.p().dist(start) > 0.4) {
                    V3 a = start, b = t.p();
                    Net.toServer(Msg.C_PLACE_POLE, buf -> {
                        Codecs.write(buf, a);
                        Codecs.write(buf, b);
                    });
                    start = null;
                    snapLock = null;
                    extraSteps = 0;
                }
            }
            case ROPE -> {
                if (sneak && start != null) {
                    reset();
                    tellCancel();
                    return true;
                }
                if (t == null) return true;
                if (start == null) start = t.p();
                else if (t.p().dist(start) > 0.3) {
                    V3 a = start, b = t.p();
                    Net.toServer(Msg.C_PLACE_ROPE, buf -> {
                        Codecs.write(buf, a);
                        Codecs.write(buf, b);
                    });
                    start = b;
                }
            }
            case TARP -> {
                if (sneak && !ties.isEmpty()) {
                    reset();
                    tellCancel();
                    return true;
                }
                if (sneak && t == null) {
                    if (!autoPerimeter()) reset();
                    return true;
                }
                if (t == null) return true;
                if (sneak && t.kind() == Snap.Kind.POLE_END && ties.isEmpty()) {
                    autoPerimeter();
                    return true;
                }
                if (ties.size() >= 3 && t.p().dist(ties.get(0)) < Stability.TOUCH) {
                    List<V3> send = new ArrayList<>(ties);
                    Net.toServer(Msg.C_PLACE_TARP, buf -> {
                        buf.writeVarInt(send.size());
                        for (V3 p : send) Codecs.write(buf, p);
                    });
                    reset();
                    return true;
                }
                for (int i = 0; i < ties.size(); i++) {
                    if (ties.get(i).dist(t.p()) < Stability.TOUCH) {
                        // 이미 찍은 점을 다시 찍으면 그 뒤를 지운다
                        while (ties.size() > i + 1) ties.remove(ties.size() - 1);
                        replan();
                        return true;
                    }
                }
                ties.add(t.p());
                replan();
            }
            default -> {
            }
        }
        return true;
    }

    static void tellCancel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable("tarp.hint.cancelled"), true);
    }

    /** 웅크리고 골조를 클릭: 겨냥한 골조 주변 말뚝들을 각도 순으로 둘레로 제안한다. */
    static boolean autoPerimeter() {
        Minecraft mc = Minecraft.getInstance();
        Snap.Target t = Snap.pick(1, false, null);
        if (t == null || t.pole() < 0) return false;
        Stability.Pole p = ClientStore.S.poles.get(t.pole());
        if (p == null) return false;
        List<Stability.Pole> frame = ClientStore.S.component(List.of(p.id()));
        double cx = 0, cz = 0, minY = Double.MAX_VALUE;
        for (Stability.Pole q : frame) {
            cx += q.a().x() + q.b().x();
            cz += q.a().z() + q.b().z();
            minY = Math.min(minY, Math.min(q.a().y(), q.b().y()));
        }
        cx /= frame.size() * 2;
        cz /= frame.size() * 2;
        final double fx = cx, fz = cz, fy = minY;
        double radius = 1;
        for (Stability.Pole q : frame)
            for (V3 e : new V3[]{q.a(), q.b()}) radius = Math.max(radius, Math.hypot(e.x() - fx, e.z() - fz));
        double lim = radius + 2.5;
        List<V3> found = new ArrayList<>();
        for (V3 s : Snap.stakesNear()) {
            if (Math.hypot(s.x() - fx, s.z() - fz) <= lim && Math.abs(s.y() - fy) < 3) found.add(s);
        }
        if (found.size() < 3) {
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable("tarp.msg.tarp.auto_none"), true);
            return true;
        }
        found.sort(Comparator.comparingDouble(s -> Math.atan2(s.z() - fz, s.x() - fx)));
        ties.clear();
        ties.addAll(found);
        replan();
        return true;
    }

    static void replan() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || ties.size() < 3) {
            plan = null;
            return;
        }
        ItemStack held = mc.player.getMainHandItem();
        plan = Planner.planTarp(mc.level, ClientStore.S, ties, held.getItem() instanceof TarpItem ti ? ti : null);
        planVersion = ClientStore.S.version;
    }

    static V3 eye() {
        var e = Minecraft.getInstance().player.getEyePosition();
        return new V3(e.x, e.y, e.z);
    }

    /** 미리보기 지지대의 예측 색. */
    @Nullable
    static Stability.Forecast forecast() {
        Minecraft mc = Minecraft.getInstance();
        if (tool != Tool.POLE || start == null || target == null || mc.level == null) return null;
        if (target.p().dist(start) < 0.4) return null;
        // 0.4.10: 매 프레임 계산하던 것을, 끝점·구조가 바뀌거나 블록이 바뀌었을 수 있을 때(0.5초마다)만
        long slot = mc.level.getGameTime() / 10;
        if (fcResult == null || !start.equals(fcStart) || !target.p().equals(fcEnd) || fcVersion != ClientStore.S.version || fcSlot != slot) {
            fcResult = Planner.forecast(mc.level, ClientStore.S, start, target.p());
            fcStart = start;
            fcEnd = target.p();
            fcVersion = ClientStore.S.version;
            fcSlot = slot;
        }
        return fcResult;
    }

    @Nullable private static Stability.Forecast fcResult;
    @Nullable private static V3 fcStart, fcEnd;
    private static int fcVersion;
    private static long fcSlot;

    @Nullable
    static Component info() {
        switch (tool) {
            case POLE -> {
                if (start == null) return null;
                if (target == null) return Component.translatable("tarp.hint.pole.end");
                int cells = Raster.count(start, target.p());
                double len = start.dist(target.p());
                if (cells > Spec.POLE_MAX_CELLS) return Component.translatable("tarp.hint.pole.too_long", cells, Spec.POLE_MAX_CELLS);
                return Component.translatable(target.kind() == Snap.Kind.AIR ? "tarp.hint.pole.vertical" : "tarp.hint.pole.count",
                        Spec.poleCost(len), String.format("%.1f", len));
            }
            case ROPE -> {
                if (start == null) return null;
                if (target == null) return Component.translatable("tarp.hint.rope.end");
                double len = start.dist(target.p());
                return Component.translatable(len > Spec.ROPE_MAX ? "tarp.hint.rope.too_long" : "tarp.hint.rope.count", (int) Math.ceil(len - 1e-6), (int) Spec.ROPE_MAX);
            }
            case TARP -> {
                if (ties.isEmpty()) return null;
                if (ties.size() < 3 || plan == null) return Component.translatable("tarp.hint.tarp.points", ties.size());
                if (plan.shape == null || !plan.shape.ok()) return Component.translatable("tarp.hint.tarp.bad_shape");
                String size = plan.needSize < 0 ? "none" : String.valueOf(plan.needSize);
                Component sizeName = Component.translatable("tarp.size." + size);
                String key = plan.ok() ? "tarp.hint.tarp.ready" : "tarp.hint.tarp.problem." + plan.error.name().toLowerCase();
                return Component.translatable(key, (int) Math.ceil(plan.area), sizeName,
                        Component.translatable(plan.doorPossible ? "tarp.hint.door.yes" : "tarp.hint.door.no"));
            }
            default -> {
                return null;
            }
        }
    }
}
