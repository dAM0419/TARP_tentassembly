package com.tarp.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 쓰러짐 판정. 힘의 크기는 무시하고 방향만 본다 (설계서 '안정성').
 * <ul>
 *   <li>수직: 아래 끝이 블록 윗면·말뚝·서 있는 다른 지지대 위에 있으면 선다. 수직 지지대 위의 점은 모든 방향을 받아준다.</li>
 *   <li>수평: 양 끝이 모두 받쳐져야 한다 (블록·말뚝·균형 잡힌 다른 지지대).</li>
 *   <li>대각: 발이 받쳐지고, 기대는 점(꼭대기)이 균형점이어야 한다.</li>
 *   <li>균형: 수평 지지대로 이어진 점들을 하나로 묶고, 대각이 미는 방향·당김줄이 당기는 방향 사이의 가장 큰 빈 각도가 180° 미만이면 선다.</li>
 *   <li>끝으로 땅(블록·말뚝)에 닿지 않은 덩어리는 모두 떨어진다.</li>
 * </ul>
 */
public final class Stability {
    private Stability() {}

    /** 두 점이 같은 점으로 보는 거리. */
    public static final double TOUCH = 0.08;

    public interface Ground {
        /** 지지대·말뚝을 받쳐주는 블록인가 (공기·대체 가능 블록·TARP 블록이 아님). */
        boolean solid(int x, int y, int z);
    }

    public enum Kind { VERTICAL, HORIZONTAL, DIAGONAL }

    public record Pole(int id, V3 a, V3 b) {
        public Kind kind() {
            if (Math.abs(a.x() - b.x()) < 1e-6 && Math.abs(a.z() - b.z()) < 1e-6) return Kind.VERTICAL;
            if (Math.abs(a.y() - b.y()) < 1e-6) return Kind.HORIZONTAL;
            return Kind.DIAGONAL;
        }

        public V3 foot() {
            return a.y() <= b.y() ? a : b;
        }

        public V3 top() {
            return a.y() <= b.y() ? b : a;
        }

        public V3 at(double t) {
            return a.lerp(b, t);
        }
    }

    public record Rope(int id, V3 a, V3 b) {}

    /** 미리보기 색: 초록(섬) / 노랑(기대야 섬) / 빨강(쓰러짐). */
    public enum Forecast { STANDS, LEANS, FALLS }

    // ------------------------------------------------------------------

    /** 서 있는 지지대 id. */
    public static Set<Integer> standing(Collection<Pole> poles, Collection<Rope> ropes, Collection<V3> stakeHeads, Ground ground) {
        return new Solver(poles, ropes, stakeHeads, ground).solve();
    }

    /** 새 지지대를 놓았을 때의 예측. */
    public static Forecast forecast(Pole candidate, Collection<Pole> poles, Collection<Rope> ropes, Collection<V3> stakeHeads, Ground ground) {
        List<Pole> all = new ArrayList<>(poles);
        all.add(candidate);
        Set<Integer> s = standing(all, ropes, stakeHeads, ground);
        if (!s.contains(candidate.id())) return Forecast.FALLS;
        return candidate.kind() == Kind.DIAGONAL ? Forecast.LEANS : Forecast.STANDS;
    }

    /** 방향들 사이의 가장 큰 빈 각도(도). 방향이 없으면 360. */
    public static double maxGap(List<double[]> dirs) {
        List<Double> angles = new ArrayList<>();
        for (double[] d : dirs) {
            double l = Math.hypot(d[0], d[1]);
            if (l < 1e-6) continue;
            angles.add(Math.toDegrees(Math.atan2(d[1], d[0])));
        }
        if (angles.isEmpty()) return 360;
        angles.sort(Double::compare);
        double gap = angles.get(0) + 360 - angles.get(angles.size() - 1);
        for (int i = 1; i < angles.size(); i++) gap = Math.max(gap, angles.get(i) - angles.get(i - 1));
        return gap;
    }

    public static boolean balanced(List<double[]> dirs) {
        return maxGap(dirs) < 180 - 1e-3;
    }

    /** 점이 고체 블록 면에 닿았는가. */
    public static boolean blockContact(V3 p, Ground g) {
        int[] xs = around(p.x()), ys = around(p.y()), zs = around(p.z());
        for (int x : xs) for (int y : ys) for (int z : zs) if (g.solid(x, y, z)) return true;
        return false;
    }

    /** 점이 블록 윗면 위에 있는가. */
    public static boolean onBlockTop(V3 p, Ground g) {
        double fy = Math.rint(p.y());
        if (Math.abs(p.y() - fy) > 1e-4) return false;
        int[] xs = around(p.x()), zs = around(p.z());
        for (int x : xs) for (int z : zs) if (g.solid(x, (int) fy - 1, z)) return true;
        return false;
    }

    private static int[] around(double v) {
        double r = Math.rint(v);
        if (Math.abs(v - r) < 1e-4) return new int[]{(int) r - 1, (int) r};
        return new int[]{(int) Math.floor(v)};
    }

    /** 두 선분의 가장 가까운 점의 매개변수 (s on P, t on Q). */
    public static double[] closest(V3 p0, V3 p1, V3 q0, V3 q1) {
        V3 d1 = p1.sub(p0), d2 = q1.sub(q0), r = p0.sub(q0);
        double a = d1.dot(d1), e = d2.dot(d2), f = d2.dot(r);
        double s, t;
        if (a < 1e-12 && e < 1e-12) return new double[]{0, 0};
        if (a < 1e-12) {
            s = 0;
            t = clamp(f / e);
        } else {
            double c = d1.dot(r);
            if (e < 1e-12) {
                t = 0;
                s = clamp(-c / a);
            } else {
                double b = d1.dot(d2), denom = a * e - b * b;
                s = denom > 1e-12 ? clamp((b * f - c * e) / denom) : 0;
                t = (b * s + f) / e;
                if (t < 0) {
                    t = 0;
                    s = clamp(-c / a);
                } else if (t > 1) {
                    t = 1;
                    s = clamp((b - c) / a);
                }
            }
        }
        return new double[]{s, t};
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    // ------------------------------------------------------------------

    private static final class Solver {
        final List<Pole> poles;
        final Collection<Rope> ropes;
        final Collection<V3> stakes;
        final Ground ground;

        /** 점: 지지대 index, 매개변수, 위치. */
        final List<int[]> ptPole = new ArrayList<>();
        final List<Double> ptT = new ArrayList<>();
        final List<V3> ptPos = new ArrayList<>();
        int[] parent;

        /** 노드(점 묶음)별 정보. */
        final Map<Integer, Node> nodes = new HashMap<>();
        /** 지지대별 노드 목록 (매개변수 순). */
        final List<List<Integer>> poleNodes = new ArrayList<>();
        final List<int[]> poleEnds = new ArrayList<>();

        Solver(Collection<Pole> poles, Collection<Rope> ropes, Collection<V3> stakes, Ground ground) {
            this.poles = new ArrayList<>(poles);
            this.ropes = ropes;
            this.stakes = stakes;
            this.ground = ground;
        }

        static final class Node {
            V3 pos;
            final Set<Integer> members = new HashSet<>();
            boolean ground, stake;
        }

        int addPoint(int pole, double t) {
            ptPole.add(new int[]{pole});
            ptT.add(t);
            ptPos.add(poles.get(pole).at(t));
            return ptPos.size() - 1;
        }

        int find(int i) {
            while (parent[i] != i) i = parent[i] = parent[parent[i]];
            return i;
        }

        void union(int a, int b) {
            a = find(a);
            b = find(b);
            if (a != b) parent[a] = b;
        }

        void build() {
            int n = poles.size();
            for (int i = 0; i < n; i++) {
                addPoint(i, 0);
                addPoint(i, 1);
            }
            List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Pole p = poles.get(i);
                for (int j = i + 1; j < n; j++) {
                    Pole q = poles.get(j);
                    // 0.4.10: 상자가 TOUCH 안으로 겹치지 않으면 선분 거리 계산을 건너뛴다
                    if (apart(p, q)) continue;
                    double[] st = closest(p.a(), p.b(), q.a(), q.b());
                    if (p.at(st[0]).dist(q.at(st[1])) < TOUCH) {
                        int pi = addPoint(i, st[0]), qi = addPoint(j, st[1]);
                        pairs.add(new int[]{pi, qi});
                    }
                }
            }
            int m = ptPos.size();
            parent = new int[m];
            for (int i = 0; i < m; i++) parent[i] = i;
            for (int[] pr : pairs) union(pr[0], pr[1]);
            // 0.4.10: 가까운 점 합치기를 격자(칸 크기 > TOUCH)로. 전엔 모든 점 쌍 (m²/2)
            Map<Long, List<Integer>> grid = new HashMap<>();
            for (int i = 0; i < m; i++) {
                V3 pi = ptPos.get(i);
                long gx = (long) Math.floor(pi.x() / GRID), gy = (long) Math.floor(pi.y() / GRID), gz = (long) Math.floor(pi.z() / GRID);
                for (long dx = -1; dx <= 1; dx++)
                    for (long dy = -1; dy <= 1; dy++)
                        for (long dz = -1; dz <= 1; dz++) {
                            List<Integer> cell = grid.get(gkey(gx + dx, gy + dy, gz + dz));
                            if (cell == null) continue;
                            for (int j : cell) if (ptPos.get(j).dist(pi) < TOUCH) union(i, j);
                        }
                grid.computeIfAbsent(gkey(gx, gy, gz), k -> new ArrayList<>()).add(i);
            }
            for (int i = 0; i < n; i++) {
                poleNodes.add(new ArrayList<>());
                nodeT.add(new HashMap<>());
            }
            for (int i = 0; i < m; i++) {
                int root = find(i);
                Node node = nodes.computeIfAbsent(root, k -> new Node());
                if (node.pos == null) node.pos = ptPos.get(i);
                int pole = ptPole.get(i)[0];
                node.members.add(pole);
                if (!poleNodes.get(pole).contains(root)) poleNodes.get(pole).add(root);
                nodeT.get(pole).put(root, ptT.get(i)); // 같은 노드의 점이 여럿이면 마지막 점 (전과 같다)
            }
            for (Node node : nodes.values()) {
                node.ground = blockContact(node.pos, ground);
                for (V3 s : stakes) if (s.dist(node.pos) < TOUCH) node.stake = true;
            }
            for (int i = 0; i < n; i++) {
                final Map<Integer, Double> ts = nodeT.get(i);
                poleNodes.get(i).sort((x, y) -> Double.compare(ts.getOrDefault(x, 0.0), ts.getOrDefault(y, 0.0)));
                poleEnds.add(new int[]{find(0 + 2 * i), find(1 + 2 * i)});
            }
        }

        /** 지지대별 노드 → 매개변수 (0.4.10: 정렬 비교자 안에서 모든 점을 훑던 tOf 대신). */
        final List<Map<Integer, Double>> nodeT = new ArrayList<>();

        static final double GRID = 0.1;

        static long gkey(long x, long y, long z) {
            return (x & 0x1FFFFF) << 42 | (y & 0x1FFFFF) << 21 | (z & 0x1FFFFF);
        }

        static boolean apart(Pole p, Pole q) {
            return Math.max(p.a().x(), p.b().x()) + TOUCH < Math.min(q.a().x(), q.b().x())
                    || Math.max(q.a().x(), q.b().x()) + TOUCH < Math.min(p.a().x(), p.b().x())
                    || Math.max(p.a().y(), p.b().y()) + TOUCH < Math.min(q.a().y(), q.b().y())
                    || Math.max(q.a().y(), q.b().y()) + TOUCH < Math.min(p.a().y(), p.b().y())
                    || Math.max(p.a().z(), p.b().z()) + TOUCH < Math.min(q.a().z(), q.b().z())
                    || Math.max(q.a().z(), q.b().z()) + TOUCH < Math.min(p.a().z(), p.b().z());
        }

        int footNode(int i) {
            Pole p = poles.get(i);
            return p.a().y() <= p.b().y() ? poleEnds.get(i)[0] : poleEnds.get(i)[1];
        }

        int topNode(int i) {
            Pole p = poles.get(i);
            return p.a().y() <= p.b().y() ? poleEnds.get(i)[1] : poleEnds.get(i)[0];
        }

        boolean otherAlive(int node, int self, boolean[] alive) {
            for (int m : nodes.get(node).members) if (m != self && alive[m]) return true;
            return false;
        }

        /** 대각이 기대는 점: 꼭대기가 무언가에 닿았으면 꼭대기, 아니면 다른 지지대와 닿은 가장 높은 점. */
        int leanNode(int i, boolean[] alive) {
            int top = topNode(i);
            Node tn = nodes.get(top);
            if (tn.ground || tn.stake || otherAlive(top, i, alive)) return top;
            int best = -1;
            double bestY = Double.NEGATIVE_INFINITY;
            for (int nd : poleNodes.get(i)) {
                if (nd == footNode(i) || !otherAlive(nd, i, alive)) continue;
                double y = nodes.get(nd).pos.y();
                if (y > bestY) {
                    bestY = y;
                    best = nd;
                }
            }
            return best >= 0 ? best : top;
        }

        boolean supported(int node, int self, boolean[] alive) {
            Node nd = nodes.get(node);
            return nd.ground || nd.stake || otherAlive(node, self, alive);
        }

        /** 이번 판정 회차의 묶음 균형 결과 (노드 → 결과). 한 회차 안에서는 alive·lean 이 그대로라 묶음마다 한 번만 센다 (0.4.10). */
        Map<Integer, Boolean> balancedCache = new HashMap<>();
        Kind[] kinds;

        /** 수평 지지대로 이어진 점 묶음의 균형. */
        boolean groupBalanced(int start, boolean[] alive, int[] lean) {
            Boolean cached = balancedCache.get(start);
            if (cached != null) return cached;
            Set<Integer> group = new HashSet<>();
            boolean res = groupBalancedRaw(start, alive, lean, group);
            for (int nd : group) balancedCache.put(nd, res);
            return res;
        }

        private boolean groupBalancedRaw(int start, boolean[] alive, int[] lean, Set<Integer> group) {
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            group.add(start);
            queue.add(start);
            while (!queue.isEmpty()) {
                int nd = queue.poll();
                for (int m : nodes.get(nd).members) {
                    if (!alive[m] || kinds[m] != Kind.HORIZONTAL) continue;
                    for (int other : poleNodes.get(m)) if (group.add(other)) queue.add(other);
                }
            }
            for (int nd : group) {
                Node node = nodes.get(nd);
                if (node.ground || node.stake) return true;
                for (int m : node.members) if (alive[m] && kinds[m] == Kind.VERTICAL) return true;
            }
            List<double[]> dirs = new ArrayList<>();
            for (int i = 0; i < poles.size(); i++) {
                if (!alive[i] || kinds[i] != Kind.DIAGONAL || !group.contains(lean[i])) continue;
                V3 d = poles.get(i).top().sub(poles.get(i).foot());
                dirs.add(new double[]{d.x(), d.z()});
            }
            for (Rope r : ropes) {
                for (int end = 0; end < 2; end++) {
                    V3 here = end == 0 ? r.a() : r.b(), there = end == 0 ? r.b() : r.a();
                    boolean atGroup = false;
                    for (int nd : group) if (nodes.get(nd).pos.dist(here) < TOUCH) atGroup = true;
                    if (!atGroup) continue;
                    boolean toStake = false;
                    for (V3 s : stakes) if (s.dist(there) < TOUCH) toStake = true;
                    if (toStake) dirs.add(new double[]{there.x() - here.x(), there.z() - here.z()});
                }
            }
            return balanced(dirs);
        }

        Set<Integer> solve() {
            build();
            int n = poles.size();
            boolean[] alive = new boolean[n];
            java.util.Arrays.fill(alive, true);
            kinds = new Kind[n];
            for (int i = 0; i < n; i++) kinds[i] = poles.get(i).kind();
            boolean changed = true;
            while (changed) {
                changed = false;
                balancedCache = new HashMap<>();
                int[] lean = new int[n];
                for (int i = 0; i < n; i++) lean[i] = alive[i] && kinds[i] == Kind.DIAGONAL ? leanNode(i, alive) : -1;
                boolean[] next = alive.clone();
                for (int i = 0; i < n; i++) {
                    if (!alive[i]) continue;
                    Pole p = poles.get(i);
                    boolean ok = switch (kinds[i]) {
                        case VERTICAL -> {
                            int f = footNode(i);
                            yield onBlockTop(p.foot(), ground) || nodes.get(f).stake || otherAlive(f, i, alive);
                        }
                        case HORIZONTAL -> {
                            boolean all = true;
                            for (int end : poleEnds.get(i)) {
                                Node nd = nodes.get(end);
                                if (nd.ground || nd.stake) continue;
                                if (!otherAlive(end, i, alive) || !groupBalanced(end, alive, lean)) all = false;
                            }
                            yield all;
                        }
                        case DIAGONAL -> supported(footNode(i), i, alive) && groupBalanced(lean[i], alive, lean);
                    };
                    if (!ok) {
                        next[i] = false;
                        changed = true;
                    }
                }
                alive = next;
                if (!changed) changed = removeUngrounded(alive);
            }
            Set<Integer> out = new HashSet<>();
            for (int i = 0; i < n; i++) if (alive[i]) out.add(poles.get(i).id());
            return out;
        }

        /** 땅에 닿지 않은 덩어리를 지운다. 지웠으면 true. */
        boolean removeUngrounded(boolean[] alive) {
            int n = poles.size();
            int[] comp = new int[n];
            java.util.Arrays.fill(comp, -1);
            boolean removed = false;
            for (int s = 0; s < n; s++) {
                if (!alive[s] || comp[s] >= 0) continue;
                List<Integer> members = new ArrayList<>();
                ArrayDeque<Integer> q = new ArrayDeque<>();
                q.add(s);
                comp[s] = s;
                boolean grounded = false;
                while (!q.isEmpty()) {
                    int i = q.poll();
                    members.add(i);
                    for (int nd : poleNodes.get(i)) {
                        Node node = nodes.get(nd);
                        if (node.ground || node.stake) grounded = true;
                        for (int m : node.members) {
                            if (alive[m] && comp[m] < 0) {
                                comp[m] = s;
                                q.add(m);
                            }
                        }
                    }
                }
                if (!grounded) {
                    for (int i : members) alive[i] = false;
                    removed = true;
                }
            }
            return removed;
        }
    }
}
