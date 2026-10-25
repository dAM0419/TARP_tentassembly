package com.tarp.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.tarp.core.Door;
import com.tarp.core.Membrane;
import com.tarp.core.Spec;
import com.tarp.core.Stability;
import com.tarp.core.V3;
import com.tarp.world.Planner;
import com.tarp.world.TarpRec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * 투영: 지지대·노끈 미리보기 선, 방수포 반투명 투영(막힌 칸·노출 지지대 빨강), 입구 투영, 겨냥점.
 * 클라이언트에만 그리고 공간을 차지하지 않는다.
 */
public final class PreviewRenderer {
    private PreviewRenderer() {}

    private static final ByteBufferBuilder MEM = new ByteBufferBuilder(1 << 16);
    private static final ByteBufferBuilder MEM_X = new ByteBufferBuilder(1 << 12);
    @Nullable private static VertexBuffer buf, bufX;
    private static boolean has, hasX;

    static final int GREEN = 0x55D46A, YELLOW = 0xF2C53D, RED = 0xE5483B, WHITE = 0xFFFFFF, CYAN = 0x6FE3F0;

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        ToolController.frame(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        // 0.3.12: 도구·키트를 들지 않았고 입구 투영도 없으면 버퍼를 만들지 않는다 (전엔 매 프레임 빈 버퍼 두 개)
        long gt = mc.level.getGameTime();
        if (ToolController.tool == ToolController.Tool.NONE && !KitController.holding()
                && (ClientStore.previewTarp < 0 || gt > ClientStore.previewUntil)) return;
        Vec3 cam = event.getCamera().getPosition();
        V3 origin = new V3(Math.floor(cam.x), Math.floor(cam.y), Math.floor(cam.z));
        BufferBuilder b = new BufferBuilder(MEM, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        BufferBuilder bx = new BufferBuilder(MEM_X, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Geo.Out out = new Geo.Out(b, false, origin, null), outX = new Geo.Out(bx, false, origin, null);
        build(out, outX, mc.level.getGameTime());
        has = upload(b, true);
        hasX = upload(bx, false);
        Matrix4f mv = new Matrix4f(event.getModelViewMatrix())
                .translate((float) (origin.x() - cam.x), (float) (origin.y() - cam.y), (float) (origin.z() - cam.z));
        if (has) draw(TarpRenderTypes.GHOST, buf, mv, event.getProjectionMatrix());
        if (hasX) draw(TarpRenderTypes.GHOST_SEE_THROUGH, bufX, mv, event.getProjectionMatrix());
    }

    private static void build(Geo.Out out, Geo.Out outX, long now) {
        Snap.Target t = ToolController.target;
        switch (ToolController.tool) {
            case POLE -> {
                if (t != null) outX.cube(t.p(), 0.07, Geo.argb(200, t.anchor() ? CYAN : WHITE));
                V3 s = ToolController.start;
                if (s != null) {
                    outX.cube(s, 0.07, Geo.argb(200, WHITE));
                    if (t != null) {
                        Stability.Forecast f = ToolController.forecast();
                        int rgb = f == null ? WHITE : switch (f) {
                            case STANDS -> GREEN;
                            case LEANS -> YELLOW;
                            case FALLS -> RED;
                        };
                        out.prism(s, t.p(), Spec.POLE_THICK / 2 + 0.01, Geo.argb(150, rgb), null, 4);
                    }
                }
            }
            case ROPE -> {
                if (t != null) outX.cube(t.p(), 0.07, Geo.argb(200, CYAN));
                V3 s = ToolController.start;
                if (s != null) {
                    outX.cube(s, 0.07, Geo.argb(200, WHITE));
                    if (t != null) out.prism(s, t.p(), Spec.ROPE_THICK / 2 + 0.01, Geo.argb(170, s.dist(t.p()) > Spec.ROPE_MAX ? RED : 0xE8D2A0), null, 4);
                }
            }
            case TARP -> tarp(out, outX, t);
            default -> {
            }
        }
        doorPreview(out, now);
        KitController.frame();
        KitController.render(out, outX);
    }

    private static void tarp(Geo.Out out, Geo.Out outX, @Nullable Snap.Target t) {
        if (t != null) outX.cube(t.p(), 0.07, Geo.argb(200, CYAN));
        var ties = ToolController.ties;
        for (int i = 0; i < ties.size(); i++) {
            outX.cube(ties.get(i), i == 0 ? 0.1 : 0.07, Geo.argb(220, i == 0 ? YELLOW : WHITE));
            if (i + 1 < ties.size()) out.prism(ties.get(i), ties.get(i + 1), 0.02, Geo.argb(200, WHITE), null, 4);
        }
        if (t != null && !ties.isEmpty()) out.prism(ties.get(ties.size() - 1), t.p(), 0.015, Geo.argb(120, WHITE), null, 4);
        Planner.TarpPlan plan = ToolController.plan;
        if (plan == null || plan.shape == null || !plan.shape.ok()) return;
        int col = plan.ok() ? 0x9FD8FF : 0xFFB0A8;
        TarpMesh.emitPreview(out, plan.shape, Geo.argb(90, col));
        for (BlockPos p : plan.blocked)
            outX.box(new V3(p.getX() + 0.02, p.getY() + 0.02, p.getZ() + 0.02), new V3(p.getX() + 0.98, p.getY() + 0.98, p.getZ() + 0.98), Geo.argb(70, RED));
        int shown = 0;
        for (V3 e : plan.shape.exposed) if (shown++ % 2 == 0) outX.cube(e, 0.06, Geo.argb(200, RED));
        for (V3 e : plan.notAnchor) outX.cube(e, 0.12, Geo.argb(200, RED));
    }

    @Nullable private static Door.Plan doorPlan;
    @Nullable private static TarpRec doorPlanTarp;
    private static int doorPlanEdge = -1;

    /** 서버가 보낸 입구 투영 (웅크려 첫 클릭 뒤 20초). */
    private static void doorPreview(Geo.Out out, long now) {
        if (ClientStore.previewTarp < 0 || now > ClientStore.previewUntil) return;
        TarpRec rec = ClientStore.S.tarps.get(ClientStore.previewTarp);
        if (rec == null || !rec.shape().ok()) return;
        Membrane.Result r = rec.shape();
        if (ClientStore.previewEdge < 0 || ClientStore.previewEdge >= r.edges.length) return;
        // 0.3.12: 입구 계산은 방수포·변이 바뀔 때만 (전엔 투영 20초 동안 매 프레임)
        if (doorPlan == null || doorPlanTarp != rec || doorPlanEdge != ClientStore.previewEdge) {
            doorPlan = Door.plan(r, ClientStore.previewEdge, rec.obstacles);
            doorPlanTarp = rec;
            doorPlanEdge = ClientStore.previewEdge;
        }
        Door.Plan plan = doorPlan;
        Membrane.Edge ed = r.edges[ClientStore.previewEdge];
        if (!plan.ok()) {
            out.prism(new V3(ed.a.x(), ed.bottom(0), ed.a.z()), new V3(ed.b.x(), ed.bottom(ed.len), ed.b.z()), 0.06, Geo.argb(160, RED), null, 4);
            return;
        }
        Door d = plan.door();
        int c = Geo.argb(110, GREEN);
        for (com.tarp.core.Hull3.Face f : r.faces) {
            java.util.List<V3> reg = TarpMesh.doorRegion(f.poly(), d, d.s0, d.s1);
            if (reg.size() < 3) continue;
            java.util.List<V3> lifted = new java.util.ArrayList<>();
            for (V3 p : reg) lifted.add(p.add(f.n().mul(0.03)));
            out.polygon(lifted, c, p -> new float[]{0, 0}, f.n());
        }
    }

    private static boolean upload(BufferBuilder b, boolean main) {
        MeshData data = b.build();
        if (data == null) return false;
        VertexBuffer vb = main ? buf : bufX;
        if (vb == null) {
            vb = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            if (main) buf = vb;
            else bufX = vb;
        }
        vb.bind();
        vb.upload(data);
        VertexBuffer.unbind();
        return true;
    }

    private static void draw(RenderType type, VertexBuffer vb, Matrix4f mv, Matrix4f proj) {
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            vb.bind();
            vb.drawWithShader(mv, proj, shader);
            VertexBuffer.unbind();
        }
        type.clearRenderState();
    }
}
