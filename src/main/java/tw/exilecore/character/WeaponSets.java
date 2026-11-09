package tw.exilecore.character;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 2026-11-08 照站長「想做出原作的武器切換系統」（PoE2 的武器組）：兩組武器（主手＋副手），F 鍵切換；
 * 技能可以綁定某一組（放的時候自動切過去，站長要求不延遲）；天賦樹的節點可以配成「第 I 組／第 II 組專用」（武器組天賦），只在那一組生效。
 * <p>
 * 存法：正在用的那一組武器一律在 {@link Gear#MAIN}／{@link Gear#OFF}，另一組在 {@link PlayerState#SPARE_MAIN_SLOT}／{@link PlayerState#SPARE_OFF_SLOT}；
 * 武器組天賦記在角色進度 {@code wsnodes.1}／{@code wsnodes.2}（節點代號用逗號隔開），正在用那一組的節點同時放在 {@link PlayerState#passives()}
 * （所以屬性表、條件、網頁照舊只看 passives），切換時把兩組對調。技能綁定記在角色進度 {@code skillws.<技能代號>}（1 或 2；沒有＝不指定）。
 * <p>
 * 點數照 PoE2（PoB2 Build.lua EstimatePlayerProgress）：用掉的天賦點＝一般節點＋max(第 I 組, 第 II 組)（兩組各自配，同一點兩組都能用）；
 * 每一組最多 character.yml 的 {@code weapon_set_points}（原作劇情任務給的 24 點）＋「天賦點數轉為武器組天賦點數」（百兵宗匠 100）。
 * 純邏輯，不依賴 Bukkit。
 */
public final class WeaponSets {

    /** 角色進度：第 set 組的武器組天賦節點。 */
    public static String nodesKey(final int set) {
        return "wsnodes." + set;
    }

    /** 角色進度：這個技能綁第幾組武器。 */
    public static String skillKey(final String skillId) {
        return "skillws." + skillId;
    }

    /** 2026-11-08：昇華「百兵宗匠」的「100 天賦點數轉為武器組天賦點數」（加在每一組的上限）。 */
    public static final String EXTRA_STAT = "passive.weapon_set_points";

    private WeaponSets() {
    }

    /** 第 set 組（1、2）的武器組天賦節點（照記錄的順序）。 */
    public static Set<String> nodes(final Map<String, String> progress, final int set) {
        final Set<String> out = new LinkedHashSet<>();
        final String raw = progress.get(nodesKey(set));
        if (raw != null) {
            for (final String id : raw.split(",")) {
                if (!id.isBlank()) {
                    out.add(id.strip());
                }
            }
        }
        return out;
    }

    /** 記下第 set 組的武器組天賦（空的寫成空字串，存檔時才蓋得掉資料庫裡舊的）。 */
    public static void setNodes(final Map<String, String> progress, final int set, final Set<String> nodes) {
        if (nodes.isEmpty() && !progress.containsKey(nodesKey(set))) {
            return;
        }
        progress.put(nodesKey(set), String.join(",", nodes));
    }

    public static int other(final int set) {
        return set == 2 ? 1 : 2;
    }

    /** 這個技能綁第幾組（0 = 不指定）。 */
    public static int binding(final Map<String, String> progress, final String skillId) {
        if (skillId == null) {
            return 0;
        }
        final String raw = progress.get(skillKey(skillId));
        return "1".equals(raw) ? 1 : "2".equals(raw) ? 2 : 0;
    }

    /** 下一個綁定（不指定 → I → II → 不指定）。 */
    public static int nextBinding(final int now) {
        return now == 0 ? 1 : now == 1 ? 2 : 0;
    }

    public static String setName(final int set) {
        return set == 2 ? "第 II 組" : set == 1 ? "第 I 組" : "不指定";
    }

    /**
     * 切到另一組：武器對調、武器組天賦換成那一組的、記下正在用的組（只改 state，存檔由呼叫的人負責）。回傳新的組。
     */
    public static int swap(final PlayerState state) {
        final int from = state.activeWeaponSet();
        final int to = other(from);
        normalize(state);
        state.exchangeWeaponSlots();
        final Set<String> passives = state.passives();
        passives.removeAll(nodes(state.progress(), from));
        passives.addAll(nodes(state.progress(), to));
        state.progress().put(PlayerState.WEAPON_SET_KEY, String.valueOf(to));
        state.markDirty();
        return to;
    }

    /**
     * 對齊：正在用那一組的武器組節點一定在 passives 裡（不在就從記錄拿掉：被退點、被換天賦組洗掉了）；
     * 另一組的節點不能同時是一般節點（是的話當一般節點，從那一組拿掉）、也不能在 passives 裡（存檔中斷時可能留著）。
     */
    public static void normalize(final PlayerState state) {
        final Map<String, String> progress = state.progress();
        final int active = state.activeWeaponSet();
        final Set<String> mine = nodes(progress, active);
        final Set<String> theirs = nodes(progress, other(active));
        final Set<String> passives = state.passives();
        if (passives.isEmpty() && (!mine.isEmpty() || !theirs.isEmpty())) {
            setNodes(progress, 1, Set.of()); // 天賦全部退回（換職業、天賦樹改版）：兩組的武器組天賦也一起清掉
            setNodes(progress, 2, Set.of());
            return;
        }
        if (mine.retainAll(passives)) {
            setNodes(progress, active, mine);
        }
        // 同一個節點兩組都配（各自用武器組點數）可以；另一組的在 passives 裡又不在這一組＝存檔不同步，從 passives 拿掉
        boolean changed = false;
        for (final String id : theirs) {
            if (!mine.contains(id) && passives.remove(id)) {
                changed = true;
            }
        }
        if (changed) {
            state.markDirty();
        }
    }

    /** 這個節點是不是正在用那一組的武器組天賦。 */
    public static boolean activeSetNode(final PlayerState state, final String nodeId) {
        return nodes(state.progress(), state.activeWeaponSet()).contains(nodeId);
    }

    /** 用掉的天賦點：一般節點＋max(第 I 組, 第 II 組)（PoB2：PointsUsed − min(ws1, ws2)）。 */
    public static int usedPoints(final PlayerState state) {
        final Map<String, String> progress = state.progress();
        final int active = state.activeWeaponSet();
        final Set<String> mine = nodes(progress, active);
        int common = 0;
        for (final String id : state.passives()) {
            if (!mine.contains(id)) {
                common++;
            }
        }
        return common + Math.max(mine.size(), nodes(progress, other(active)).size());
    }

    /** 第 set 組用了幾點武器組天賦。 */
    public static int used(final PlayerState state, final int set) {
        return nodes(state.progress(), set).size();
    }

    /** 配一個節點進正在用那一組的武器組天賦（呼叫前已經加進 passives）。 */
    public static void addActive(final PlayerState state, final String nodeId) {
        final int active = state.activeWeaponSet();
        final Set<String> mine = nodes(state.progress(), active);
        mine.add(nodeId);
        setNodes(state.progress(), active, mine);
    }

    /** 從正在用那一組拿掉（退點）。 */
    public static void removeActive(final PlayerState state, final String nodeId) {
        final int active = state.activeWeaponSet();
        final Set<String> mine = nodes(state.progress(), active);
        if (mine.remove(nodeId)) {
            setNodes(state.progress(), active, mine);
        }
    }
}
