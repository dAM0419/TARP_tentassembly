package com.tarp.client;

import com.tarp.content.CanvasCellBlock;
import com.tarp.content.KitItem;
import com.tarp.core.Membrane;
import com.tarp.core.Spec;
import com.tarp.core.V3;
import com.tarp.net.Msg;
import com.tarp.net.Net;
import com.tarp.world.Codecs;
import com.tarp.world.Kit;
import com.tarp.world.Structures;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 천막 키트 조작 (설계서 0.2.0).
 * <ul>
 *   <li>빈 키트: 친 천막의 천을 우클릭 → 회수.</li>
 *   <li>담긴 키트: 천막 전체가 시선을 따라 투영된다. 입구가 플레이어를 보며 시작, Q/E 로 90°씩. 우클릭 = 위치 고정.</li>
 *   <li>고정 뒤: 지지대 → 말뚝 → 노끈 → 방수포 순으로 부품을 우클릭하면 그대로 설치. 웅크리고 우클릭 = 고정 풀기(아무것도 놓기 전).</li>
 * </ul>
 */
public final class KitController {
    private KitController() {}

    private static ItemStack cachedStack = ItemStack.EMPTY;
    @Nullable private static Kit kit;
    /** 투영을 시작할 때 정한 회전 + Q/E 로 더한 회전. */
    private static int baseRot, extraRot;
    private static boolean projecting;
    /** 이번 프레임의 투영 (위치·회전이 바뀔 때만 다시 계산). */
    @Nullable private static Kit view;
    @Nullable private static Membrane.Result viewShape;
    private static List<BlockPos> viewBad = List.of();
    @Nullable private static V3 viewOrigin;
    private static int viewRot = -1;
    /** 고정 뒤 겨누는 부품. */
    @Nullable private static Kit.Part aimPart;
    private static int aimIndex = -1;

    public static boolean holding() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getMainHandItem().getItem() instanceof KitItem;
    }

    @Nullable
    static Kit kit() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof KitItem)) {
            cachedStack = ItemStack.EMPTY;
            kit = null;
            return null;
        }
        if (!ItemStack.isSameItemSameComponents(held, cachedStack)) {
            cachedStack = held.copy();
            kit = Kit.of(held);
            kitStamp++;
        }
        return kit;
    }

    /** 틱 시작: 투영 중 Q/E(버리기·인벤토리 키)를 가로채 회전에 쓴다. */
    public static void tickPre() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        Kit k = kit();
        boolean now = k != null && !k.anchored;
        if (now && !projecting) {
            Vec3 pp = mc.player.position();
            V3 o = guessOrigin();
            double dx = o == null ? -mc.player.getLookAngle().x : pp.x - o.x(), dz = o == null ? -mc.player.getLookAngle().z : pp.z - o.z();
            baseRot = k.baseRotToward(dx, dz);
            extraRot = 0;
        }
        projecting = now;
        if (!projecting) return;
        while (mc.options.keyDrop.consumeClick()) extraRot += 3;
        while (mc.options.keyInventory.consumeClick()) extraRot += 1;
        extraRot %= 4;
    }

    static int rot() {
        return (baseRot + extraRot) % 4;
    }

    /** 겨눈 블록 위 칸 가운데(정수 높이)를 기준점으로. */
    @Nullable
    static V3 guessOrigin() {
        Minecraft mc = Minecraft.getInstance();
        HitResult hr = mc.hitResult;
        if (!(hr instanceof BlockHitResult bh) || hr.getType() != HitResult.Type.BLOCK || mc.level == null) return null;
        BlockPos p = bh.getBlockPos();
        if (mc.level.getBlockState(p).canBeReplaced()) return new V3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        BlockPos q = bh.getDirection() == Direction.UP ? p.above() : p.relative(bh.getDirection());
        return new V3(q.getX() + 0.5, q.getY(), q.getZ() + 0.5);
    }

    /** 렌더 프레임마다: 투영 갱신과 겨누는 부품. */
    static void frame() {
        Kit k = kit();
        view = null;
        aimPart = null;
        aimIndex = -1;
        if (k == null) return;
        V3 o;
        int r;
        if (k.anchored) {
            o = k.origin;
            r = k.rot;
        } else {
            o = guessOrigin();
            r = rot();
            if (o == null) return;
        }
        // 0.4.10: 키트가 바뀔 때만 복사하고(전엔 매 프레임 NBT 전체 해시), 기준점·회전이 바뀌면 상대 막 모양을 옮겨 쓴다
        if (cachedView == null || viewStamp != kitStamp) {
            cachedView = Kit.load(k.save());
            viewStamp = kitStamp;
            viewOrigin = null;
        }
        if (viewOrigin == null || !viewOrigin.equals(o) || viewRot != r || ClientStore.S.version != lastVersion) {
            Kit v = cachedView;
            v.origin = o;
            v.rot = r;
            viewOrigin = o;
            viewRot = r;
            lastVersion = ClientStore.S.version;
            viewShape = v.relShape();
            viewOffset = v.relOffset();
            Minecraft mc = Minecraft.getInstance();
            viewBad = k.anchored || mc.level == null ? List.of() : v.problems(mc.level);
        }
        view = cachedView;
        if (k.anchored) pickPart(view);
    }

    @Nullable private static Kit cachedView;
    private static int lastVersion = -1;
    /** 손에 든 키트가 바뀐 횟수 (투영 복사본을 다시 만들 때). */
    private static int kitStamp, viewStamp = -1;
    private static V3 viewOffset = V3.ZERO;

    private static void pickPart(Kit v) {
        Kit.Part stage = v.stage();
        aimPart = stage;
        if (stage == Kit.Part.TARP) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 e = mc.player.getEyePosition(), l = mc.player.getViewVector(1);
        V3 eye = new V3(e.x, e.y, e.z), look = new V3(l.x, l.y, l.z);
        double reach = mc.player.blockInteractionRange() + 2;
        V3 far = eye.add(look.mul(reach));
        double best = 0.35;
        switch (stage) {
            case POLE -> {
                for (int i = 0; i < v.poles.size(); i++) {
                    if (v.donePoles[i]) continue;
                    Kit.PoleBp p = v.poles.get(i);
                    double d = Structures.segDist(eye, far, v.world(p.a()), v.world(p.b()));
                    if (d < best) {
                        best = d;
                        aimIndex = i;
                    }
                }
            }
            case STAKE -> {
                for (int i = 0; i < v.stakes.size(); i++) {
                    if (v.doneStakes[i]) continue;
                    BlockPos b = v.world(v.stakes.get(i).rel());
                    V3 c = new V3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
                    double d = Structures.pointSeg(c, eye, far);
                    if (d < best + 0.15) {
                        best = d - 0.15;
                        aimIndex = i;
                    }
                }
            }
            case ROPE -> {
                for (int i = 0; i < v.ropes.size(); i++) {
                    if (v.doneRopes[i]) continue;
                    Kit.RopeBp r = v.ropes.get(i);
                    double d = Structures.segDist(eye, far, v.world(r.a()), v.world(r.b()));
                    if (d < best) {
                        best = d;
                        aimIndex = i;
                    }
                }
            }
            default -> {
            }
        }
    }

    /** 우클릭. 처리했으면 true. */
    public static boolean click() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !holding()) return false;
        Kit k = kit();
        boolean sneak = mc.player.isShiftKeyDown();
        if (k == null) {
            HitResult hr = mc.hitResult;
            // 0.3.10: 회수는 웅크리고 우클릭만. 설치를 끝낸 직후 한 번 더 눌러 다시 담기는 사고를 막는다.
            if (sneak && hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK && mc.level != null
                    && mc.level.getBlockState(bh.getBlockPos()).getBlock() instanceof CanvasCellBlock) {
                BlockPos pos = bh.getBlockPos();
                Net.toServer(Msg.C_KIT_CAPTURE, b -> b.writeBlockPos(pos));
            } else {
                mc.player.displayClientMessage(Component.translatable("tarp.hint.kit.empty"), true);
            }
            return true;
        }
        frame();
        if (!k.anchored) {
            if (view == null) return true;
            if (!viewBad.isEmpty()) {
                mc.player.displayClientMessage(Component.translatable("tarp.msg.kit.bad_ground", viewBad.size()), true);
                return true;
            }
            V3 o = view.origin;
            int r = view.rot;
            Net.toServer(Msg.C_KIT_ANCHOR, b -> {
                Codecs.write(b, o);
                b.writeVarInt(r);
            });
            return true;
        }
        if (sneak) {
            Net.toServer(Msg.C_KIT_UNANCHOR, b -> {
            });
            return true;
        }
        if (aimPart == null) return true;
        if (aimPart != Kit.Part.TARP && aimIndex < 0) {
            mc.player.displayClientMessage(Component.translatable("tarp.hint.kit.aim", Component.translatable("tarp.kit.part." + aimPart.name().toLowerCase())), true);
            return true;
        }
        Kit.Part part = aimPart;
        int idx = aimIndex;
        Net.toServer(Msg.C_KIT_PART, b -> {
            b.writeVarInt(part.ordinal());
            b.writeVarInt(idx);
        });
        return true;
    }

    @Nullable
    static Component info() {
        Kit k = kit();
        if (!holding()) return null;
        if (k == null) return Component.translatable("tarp.hint.kit.empty");
        if (!k.anchored) return Component.translatable(!viewBad.isEmpty() ? "tarp.hint.kit.bad"
                : com.tarp.TarpConfig.KIT_INSTANT.get() ? "tarp.hint.kit.project_instant" : "tarp.hint.kit.project", viewBad.size());
        Kit.Part st = k.stage();
        return Component.translatable("tarp.hint.kit.stage", Component.translatable("tarp.kit.part." + st.name().toLowerCase()));
    }

    // ------------------------------------------------------------------ 투영

    static void render(Geo.Out out, Geo.Out outX) {
        Kit k = kit();
        Kit v = view;
        if (k == null || v == null) return;
        boolean bad = !viewBad.isEmpty();
        int base = bad ? PreviewRenderer.RED : 0x9FD8FF;
        Kit.Part stage = k.anchored ? v.stage() : null;
        for (int i = 0; i < v.poles.size(); i++) {
            if (v.donePoles[i]) continue;
            Kit.PoleBp p = v.poles.get(i);
            int rgb = colour(stage, Kit.Part.POLE, i, base);
            out.prism(v.world(p.a()), v.world(p.b()), Spec.POLE_THICK / 2 + 0.01, Geo.argb(stage == Kit.Part.POLE ? 170 : 110, rgb), null, 4);
        }
        for (int i = 0; i < v.stakes.size(); i++) {
            if (v.doneStakes[i]) continue;
            BlockPos b = v.world(v.stakes.get(i).rel());
            int rgb = colour(stage, Kit.Part.STAKE, i, base);
            out.cube(new V3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5), 0.12, Geo.argb(stage == Kit.Part.STAKE ? 180 : 110, rgb));
        }
        for (int i = 0; i < v.ropes.size(); i++) {
            if (v.doneRopes[i]) continue;
            Kit.RopeBp r = v.ropes.get(i);
            int rgb = colour(stage, Kit.Part.ROPE, i, base);
            out.prism(v.world(r.a()), v.world(r.b()), Spec.ROPE_THICK / 2 + 0.01, Geo.argb(stage == Kit.Part.ROPE ? 170 : 100, rgb), null, 4);
        }
        if (viewShape != null && viewShape.ok()) {
            int rgb = stage == Kit.Part.TARP ? PreviewRenderer.YELLOW : base;
            Geo.Out moved = new Geo.Out(out.vc, out.entity, out.origin.sub(viewOffset), out.light, out.pose);
            TarpMesh.emitPreview(moved, viewShape, Geo.argb(stage == Kit.Part.TARP ? 110 : 70, rgb));
        }
        for (BlockPos p : viewBad)
            outX.box(new V3(p.getX() + 0.02, p.getY() + 0.02, p.getZ() + 0.02), new V3(p.getX() + 0.98, p.getY() + 0.98, p.getZ() + 0.98), Geo.argb(80, PreviewRenderer.RED));
    }

    private static int colour(@Nullable Kit.Part stage, Kit.Part part, int i, int base) {
        if (stage == null) return base;
        if (stage != part) return PreviewRenderer.WHITE;
        return aimPart == part && aimIndex == i ? PreviewRenderer.GREEN : PreviewRenderer.YELLOW;
    }
}
