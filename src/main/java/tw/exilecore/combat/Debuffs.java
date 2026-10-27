package tw.exilecore.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 怪物身上的減益（印記、戰吼的恫嚇、詛咒、曝露、定身、眩暈）：一段時間內受到的傷害增加、屬性變差、變慢。純邏輯，不依賴 Bukkit。
 * 同一個代號在同一隻怪物身上只有一條（再施加就覆蓋、重新計時）；只對施放者有效的（印記）每個玩家各自一條。
 */
public final class Debuffs {

    /**
     * @param id        代號（技能代號）
     * @param source    施加的玩家
     * @param untilTick 到哪個 tick 失效
     * @param taken     受到的傷害增加（0.25 = 25%）
     * @param selfOnly  只有施加的玩家打它才增加
     * @param tag       只有帶這個標籤的擊中才增加（2026-10，威壓：physical）；null = 全部
     * @param mods      2026-10：疊在牠屬性表上的修飾器（曝露的抗性、削弱詛咒的傷害、眩暈的暈眩門檻…），
     *                  牠打人、被打的時候都算；selfOnly 的只在施加的玩家打牠時算
     * @param slow      2026-10：移動速度變慢多少（0.3 = 慢 30%；1 = 定身，完全不能移動）
     */
    public record Debuff(String id, UUID source, long untilTick, double taken, boolean selfOnly, String tag,
                         List<tw.exilecore.stats.Modifier> mods, double slow) {
        public Debuff {
            mods = mods == null ? List.of() : List.copyOf(mods);
            slow = Math.max(0, Math.min(1, slow));
        }

        public Debuff(final String id, final UUID source, final long untilTick, final double taken, final boolean selfOnly, final String tag) {
            this(id, source, untilTick, taken, selfOnly, tag, List.of(), 0);
        }

        public Debuff(final String id, final UUID source, final long untilTick, final double taken, final boolean selfOnly) {
            this(id, source, untilTick, taken, selfOnly, null);
        }

        public boolean active(final long now) {
            return now < untilTick;
        }
    }

    private final Map<UUID, List<Debuff>> entries = new HashMap<>();

    /**
     * 2026-10-23：拿掉某個玩家施加的減益（詛咒、印記、曝露、眩暈、致盲、癱瘓、戰吼的恫嚇、光環的減益…）：
     * 那個玩家死了、下線、離開地圖。回傳拿掉幾條。
     */
    public int removeSource(final UUID source) {
        if (source == null) {
            return 0;
        }
        int removed = 0;
        final Iterator<Map.Entry<UUID, List<Debuff>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            final List<Debuff> list = iterator.next().getValue();
            final int before = list.size();
            list.removeIf(debuff -> source.equals(debuff.source()));
            removed += before - list.size();
            if (list.isEmpty()) {
                iterator.remove();
            }
        }
        return removed;
    }

    /** 2026-10-23：時間泡泡：這個目標身上還沒結束的減益都晚 extra tick 結束。 */
    public void extend(final UUID target, final long extra, final long now) {
        final List<Debuff> list = entries.get(target);
        if (list == null || extra <= 0) {
            return;
        }
        list.replaceAll(debuff -> debuff.active(now) ? new Debuff(debuff.id(), debuff.source(), debuff.untilTick() + extra, debuff.taken(),
                debuff.selfOnly(), debuff.tag(), debuff.mods(), debuff.slow()) : debuff);
    }

    public void apply(final UUID target, final Debuff debuff) {
        final List<Debuff> list = entries.computeIfAbsent(target, uuid -> new ArrayList<>());
        list.removeIf(existing -> existing.id().equals(debuff.id())
                && (!debuff.selfOnly() || !existing.selfOnly() || existing.source().equals(debuff.source())));
        list.add(debuff);
    }

    /** 放過的印記代號（2026-10-22：同一個玩家同時只能有一個印記）。 */
    private final java.util.Set<String> markIds = new java.util.HashSet<>();

    /**
     * 2026-10-22：照 PoE2，一個玩家同時只能有一個印記（引雷印、霜縛印、血嗅印、獵殺印記、處決印記…）：
     * 放新的印記時，這個玩家放在任何敵人身上的其他印記（包括同一種放在別隻身上的）都拿掉。詛咒、其他減益不受影響。
     */
    public void applyMark(final UUID target, final Debuff mark) {
        markIds.add(mark.id());
        final Iterator<Map.Entry<UUID, List<Debuff>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            final List<Debuff> list = iterator.next().getValue();
            list.removeIf(existing -> markIds.contains(existing.id()) && existing.source().equals(mark.source()));
            if (list.isEmpty()) {
                iterator.remove();
            }
        }
        apply(target, mark);
    }

    /** 詛咒的減益代號 → 哪個詛咒技能（縛根一次放好幾條：entangle.physical…）。 */
    private final Map<String, String> curseSkills = new HashMap<>();

    /**
     * 2026-10-22：照 PoE2，一隻敵人身上同時只能有你的一個詛咒（創痕、凋魂、三元、萎力、遲滯、縛根…）：
     * 放新的詛咒時，你放在這隻身上的其他詛咒拿掉；同一個詛咒重放照舊刷新。別人的詛咒、印記、輔助觸發的減益（殘肢、目眩…）不受影響。
     *
     * @param skill 這個詛咒的技能代號（同一次施放的幾條減益用同一個）
     */
    public void applyCurse(final UUID target, final Debuff curse, final String skill) {
        applyCurse(target, curse, skill, 1);
    }

    /**
     * 2026-10-26 照 PoE2 基石（滅世之語：可以額外施加 1 個詛咒）：同一個施放者在一隻敵人身上最多 allowed 種詛咒，
     * 超過的話最早結束的那一種拿掉。
     */
    public void applyCurse(final UUID target, final Debuff curse, final String skill, final int allowed) {
        if (curseImmunity.test(target)) {
            return; // 2026-10-26：免疫詛咒（PoE2 淨化護符）
        }
        curseSkills.put(curse.id(), skill);
        // 2026-10-26（昇華・不斷爭吵，PoE2）：你施加的詛咒持續時間無限、可以額外施加 1 個詛咒
        final int[] rules = tw.exilecore.ascendancy.AscendancyEffects.curseRules(curse.source());
        final Debuff placed = placedCurse(curse, rules[1] > 0, playerTarget.test(target));
        final List<Debuff> list = entries.get(target);
        if (list != null) {
            while (true) {
                final Map<String, Long> others = new HashMap<>(); // 其他詛咒技能 → 最晚結束的 tick
                for (final Debuff existing : list) {
                    final String other = curseSkills.get(existing.id());
                    if (existing.source().equals(curse.source()) && other != null && !other.equals(skill)) {
                        others.merge(other, existing.untilTick(), Math::max);
                    }
                }
                if (others.size() <= Math.max(0, allowed - 1 + rules[0])) { // 2026-10-26：昇華・不斷爭吵多 1 個
                    break;
                }
                final String oldest = others.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();
                list.removeIf(existing -> existing.source().equals(curse.source()) && oldest.equals(curseSkills.get(existing.id())));
            }
        }
        apply(target, placed);
    }

    /**
     * 實際放上去的詛咒：持續時間無限（昇華・不斷爭吵）只對非玩家的目標。
     * 2026-10-27：這次之前競技場裡對玩家也是無限，對手整場都帶著詛咒；對玩家照原本的持續時間。純函式（測試用）。
     */
    static Debuff placedCurse(final Debuff curse, final boolean infinite, final boolean targetIsPlayer) {
        if (!infinite || targetIsPlayer) {
            return curse;
        }
        return new Debuff(curse.id(), curse.source(), Long.MAX_VALUE / 4, curse.taken(), curse.selfOnly(), curse.tag(), curse.mods(), curse.slow());
    }

    /** 2026-10-27：目標是不是玩家（伺服器沒啟動時——單元測試——一律不是）。 */
    private final java.util.function.Predicate<UUID> playerTarget = target -> org.bukkit.Bukkit.getServer() != null
            && org.bukkit.Bukkit.getPlayer(target) != null;

    /** 2026-10-26：誰免疫詛咒（藥劑模組的淨化護符掛上來；預設沒有人）。 */
    private java.util.function.Predicate<UUID> curseImmunity = target -> false;

    public void setCurseImmunity(final java.util.function.Predicate<UUID> immunity) {
        this.curseImmunity = immunity == null ? target -> false : immunity;
    }

    /** 2026-10-26：拿掉這個目標身上所有的詛咒（PoE2 淨化護符）；回傳拿掉幾條。 */
    public int removeCurses(final UUID target) {
        final List<Debuff> list = entries.get(target);
        if (list == null) {
            return 0;
        }
        final int before = list.size();
        list.removeIf(debuff -> debuff.id().contains("_curse") || curseSkills.containsKey(debuff.id()));
        if (list.isEmpty()) {
            entries.remove(target);
        }
        return before - list.size();
    }

    /** 攻擊方打這個目標時，受到的傷害倍率（1.25 = 增加 25%；好幾條相加）。 */
    public double takenMultiplier(final UUID target, final UUID attacker, final long now) {
        return takenMultiplier(target, attacker, now, java.util.Set.of());
    }

    /** hitTags：這一擊的標籤（有標籤限制的減益只對帶那個標籤的擊中有效）。 */
    public double takenMultiplier(final UUID target, final UUID attacker, final long now, final java.util.Set<String> hitTags) {
        final List<Debuff> list = entries.get(target);
        if (list == null) {
            return 1.0;
        }
        double increased = 0;
        for (final Debuff debuff : list) {
            if (debuff.active(now) && (!debuff.selfOnly() || debuff.source().equals(attacker))
                    && !isDamageType(debuff.tag()) // 2026-10-21：傷害類型的（凋息、縛根）只加那一種傷害，見 typedTaken
                    && (debuff.tag() == null || hitTags.contains(debuff.tag()))) {
                increased += debuff.taken();
            }
        }
        return 1.0 + increased;
    }

    /** 這個標籤是不是一種傷害類型（physical、fire、cold、lightning、chaos）。 */
    static boolean isDamageType(final String tag) {
        if (tag == null) {
            return false;
        }
        for (final tw.exilecore.stats.DamageType type : tw.exilecore.stats.DamageType.values()) {
            if (type.tag().equals(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 2026-10-21：只加某一種傷害的減益（凋息「受到的混沌傷害增加」、縛根「受到的物理傷害增加」）：這種傷害增加幾（0.22 = 22%）。
     * 擊中只加那一種傷害的部分（不管技能本身有沒有那個標籤），持續傷害（中毒、流血、點燃）也算。
     */
    public double typedTaken(final UUID target, final UUID attacker, final long now, final tw.exilecore.stats.DamageType type) {
        final List<Debuff> list = entries.get(target);
        if (list == null) {
            return 0;
        }
        double increased = 0;
        for (final Debuff debuff : list) {
            if (debuff.active(now) && (type.tag().equals(debuff.tag()) || witheredAlsoFire(debuff, type))
                    && (!debuff.selfOnly() || attacker != null && debuff.source().equals(attacker))) {
                increased += debuff.taken();
            }
        }
        return increased;
    }

    /**
     * 2026-10-28 照 PoE2 Blackflame（黯炎）：施加者有「你施加的凋零同時增加受到的火焰傷害」時，凋零（每層受到的混沌傷害增加）
     * 也以同樣的數值增加受到的火焰傷害。這次之前凋零只加混沌。
     */
    boolean witheredAlsoFire(final Debuff debuff, final tw.exilecore.stats.DamageType type) {
        return type == tw.exilecore.stats.DamageType.FIRE && debuff.id().startsWith(WITHERED_PREFIX)
                && tw.exilecore.stats.DamageType.CHAOS.tag().equals(debuff.tag()) && debuff.source() != null
                && witherFireSources.test(debuff.source());
    }

    /** 凋零減益代號的前綴（HitEffects.addWithered：withered.0～withered.9）。 */
    public static final String WITHERED_PREFIX = "withered.";

    /** 2026-10-28：誰施加的凋零也增加受到的火焰傷害（傳奇模組掛上來；預設沒有人）。 */
    private java.util.function.Predicate<UUID> witherFireSources = source -> false;

    public void setWitherFireSources(final java.util.function.Predicate<UUID> sources) {
        this.witherFireSources = sources == null ? source -> false : sources;
    }

    /**
     * 2026-10-28 照 PoE2 Blackflame（黯炎）：被點燃的敵人身上 source 施加的凋零不會失效——每次檢查把還沒結束的凋零往後延 extra tick
     * （等於點燃期間暫停計時，點燃結束後照剩下的時間繼續倒數）。回傳延了幾層。
     */
    public int holdWithered(final UUID target, final UUID source, final long now, final long extra) {
        final List<Debuff> list = entries.get(target);
        if (list == null || source == null || extra <= 0) {
            return 0;
        }
        int held = 0;
        for (int i = 0; i < list.size(); i++) {
            final Debuff debuff = list.get(i);
            if (debuff.active(now) && debuff.id().startsWith(WITHERED_PREFIX) && source.equals(debuff.source())
                    && debuff.untilTick() < Long.MAX_VALUE / 8) { // 無限的（司祭法衣）不用延
                list.set(i, new Debuff(debuff.id(), debuff.source(), debuff.untilTick() + extra, debuff.taken(), debuff.selfOnly(),
                        debuff.tag(), debuff.mods(), debuff.slow()));
                held++;
            }
        }
        return held;
    }

    /** 2026-10-28：這個目標身上有沒有 source 施加、還沒結束的凋零。 */
    public boolean hasWitheredFrom(final UUID target, final UUID source, final long now) {
        final List<Debuff> list = entries.get(target);
        if (list == null || source == null) {
            return false;
        }
        for (final Debuff debuff : list) {
            if (debuff.active(now) && debuff.id().startsWith(WITHERED_PREFIX) && source.equals(debuff.source())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 這個目標身上減益帶的修飾器（2026-10：曝露、削弱、眩暈…）；viewer 是另一方（打牠的人，或被牠打的人），
     * 只對施加者有效的減益只在 viewer 是施加者時算。沒有回傳空的。
     */
    public List<tw.exilecore.stats.Modifier> modifiers(final UUID target, final UUID viewer, final long now) {
        final List<Debuff> list = entries.get(target);
        if (list == null) {
            return List.of();
        }
        List<tw.exilecore.stats.Modifier> result = null;
        for (final Debuff debuff : list) {
            if (debuff.mods().isEmpty() || !debuff.active(now) || debuff.selfOnly() && !debuff.source().equals(viewer)) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<>();
            }
            result.addAll(debuff.mods());
        }
        return result == null ? List.of() : result;
    }

    /** 2026-10-26：這個減益代號是不是印記（天賦樹「被你標記的敵人附近的敵人被致盲」）。 */
    public boolean isMark(final String id) {
        return markIds.contains(id);
    }

    /** 2026-10-26：身上有沒有印記（任何人放的；天賦樹「對被標記的敵人」）。 */
    public boolean marked(final UUID target, final long now) {
        for (final Debuff debuff : active(target, now)) {
            if (markIds.contains(debuff.id())) {
                return true;
            }
        }
        return false;
    }

    /** 身上有沒有詛咒（2026-10：詛咒技能的代號都以 _curse 結尾）。 */
    public boolean cursed(final UUID target, final long now) {
        final List<Debuff> list = entries.get(target);
        if (list == null) {
            return false;
        }
        for (final Debuff debuff : list) {
            if (debuff.active(now) && debuff.id().contains("_curse")) {
                return true;
            }
        }
        return false;
    }

    /** 移動速度變慢多少（取最大的；1 = 定身）。 */
    public double slow(final UUID target, final long now) {
        final List<Debuff> list = entries.get(target);
        if (list == null) {
            return 0;
        }
        double slow = 0;
        for (final Debuff debuff : list) {
            if (debuff.active(now)) {
                slow = Math.max(slow, debuff.slow());
            }
        }
        return slow;
    }

    /** 這個目標身上還有效的減益。 */
    public List<Debuff> active(final UUID target, final long now) {
        final List<Debuff> list = entries.get(target);
        if (list == null) {
            return List.of();
        }
        final List<Debuff> result = new ArrayList<>();
        for (final Debuff debuff : list) {
            if (debuff.active(now)) {
                result.add(debuff);
            }
        }
        return result;
    }

    /** 拿掉某個來源的某種減益（2026-10：狙擊印記打出暴擊時用掉）；有拿掉回傳 true。 */
    public boolean remove(final UUID target, final String id, final UUID source, final long now) {
        final List<Debuff> list = entries.get(target);
        return list != null && list.removeIf(debuff -> debuff.id().equals(id) && debuff.source().equals(source) && debuff.active(now));
    }

    public boolean has(final UUID target, final String id, final long now) {
        for (final Debuff debuff : active(target, now)) {
            if (debuff.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 目標死了、消失了。 */
    public void forget(final UUID target) {
        entries.remove(target);
    }

    /** 清掉過期的（每隔一陣子呼叫）。 */
    public void expire(final long now) {
        final Iterator<Map.Entry<UUID, List<Debuff>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, List<Debuff>> entry = iterator.next();
            entry.getValue().removeIf(debuff -> !debuff.active(now));
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    /** 身上帶著減益的目標（畫印記用）。 */
    public List<UUID> targets() {
        return new ArrayList<>(entries.keySet());
    }

    public int size() {
        return entries.size();
    }
}
