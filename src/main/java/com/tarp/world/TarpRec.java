package com.tarp.world;

import com.tarp.core.Door;
import com.tarp.core.Membrane;
import com.tarp.core.V3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 설치된 방수포(또는 잎 덮개) 하나. 모양은 설치 때의 입력(묶는 점 + 장애물)에서 언제나 같은 계산으로 다시 만든다.
 * 서버와 클라이언트가 같은 입력을 가지므로 막 모양을 따로 보내지 않는다.
 */
public final class TarpRec {
    public static final int LEAF = 3;


    public int id;
    /** 0 소, 1 중, 2 대, 3 잎 덮개. */
    public int kind;
    /** 방수포: 염료 색 번호(0~15). 잎 덮개: 쓰지 않음. */
    public int color;
    /** 잎 덮개의 잎 블록 id (색·무늬). */
    public String leaf = "";
    public final List<V3> ties = new ArrayList<>();
    public final List<Membrane.Obstacle> obstacles = new ArrayList<>();
    public int[] poleDeps = new int[0];
    public int[] ropeDeps = new int[0];
    /** 묶는 점 말뚝 블록 위치(BlockPos.asLong). */
    public long[] stakeDeps = new long[0];
    /** 입구 변 (반시계로 맞춘 둘레 기준), 없으면 -1. */
    public int doorEdge = -1;
    public boolean open;
    public long placedAt;
    /** 서버만: 놓은 천 충돌 칸. */
    public long[] cells = new long[0];

    private transient Membrane.Result shape;
    private transient Door door;
    private transient boolean doorBuilt;

    public boolean isLeaf() {
        return kind == LEAF;
    }

    public Membrane.Result shape() {
        if (shape == null) shape = Membrane.compute(ties, obstacles);
        return shape;
    }

    @Nullable
    public Door door() {
        if (!doorBuilt) {
            doorBuilt = true;
            door = null;
            Membrane.Result r = shape();
            if (doorEdge >= 0 && r.ok() && doorEdge < r.edges.length) door = Door.plan(r, doorEdge, obstacles).door();
        }
        return door;
    }

    public void setDoor(int edge) {
        doorEdge = edge;
        doorBuilt = false;
    }

    private transient double[] footprint;

    /** 묶는 점의 수평 상자 {minX, minZ, maxX, maxZ}. */
    public double[] footprint() {
        if (footprint == null) {
            double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
            for (V3 p : ties) {
                x0 = Math.min(x0, p.x());
                z0 = Math.min(z0, p.z());
                x1 = Math.max(x1, p.x());
                z1 = Math.max(z1, p.z());
            }
            footprint = new double[]{x0, z0, x1, z1};
        }
        return footprint;
    }

    /** 바닥 둘레 무게중심 (아이템 떨어뜨릴 곳). */
    public V3 centre() {
        double x = 0, y = Double.MAX_VALUE, z = 0;
        for (V3 p : ties) {
            x += p.x();
            z += p.z();
            y = Math.min(y, p.y());
        }
        return new V3(x / ties.size(), y + 0.5, z / ties.size());
    }
}
