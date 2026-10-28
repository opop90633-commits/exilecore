package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.Modifier;

import java.util.List;
import java.util.Locale;

/**
 * 技能的資源（2026-10 PoE2）：連段、榮耀、元素灌注。寫在 skills.yml 技能的 resource 區塊：
 * <pre>
 * resource:
 *   combo_gain: true      這個技能打中敵人會累積連段（長棍、長矛的打擊）
 *   combo: 4              要有 4 段連段才放得出來，放的時候用掉全部的連段
 *   per_combo: [ {stat, type, value} ]   每用掉 1 段加這些（只對這一下）
 *   glory: 200            要有 200 榮耀才放得出來，放了用掉
 *   infusion: [fire, cold, lightning]   有這幾種元素灌注時用掉一個（任一種，照順序），給 infused 的加成
 *   infused: [ ... ]      用掉灌注時的加成（只對這一下）
 *   empowered: [ ... ]    付了連段或榮耀時的加成
 *   infusion_each: true   2026-10-26：infusion 寫的每一種都各用掉一層（PoE2 Firestorm：一次可以三種各用一個），各給 infused_元素 的加成
 *   infused_fire / infused_cold / infused_lightning: [ ... ]   用掉那一種時的加成（沒寫就用 infused）
 *   remnant: cold          2026-10-26：放完過 remnant_delay 秒在落點留下一個灌注殘骸（fire／cold／lightning／random），主人走過去撿到獲得那種灌注
 *   remnant_delay: 4       （PoE2 Frost Bomb 引爆、Living Bomb 爆開、Orb of Storms 消失時留下殘骸）
 *   infused_set:          2026-10-28 照 PoE2（游雷的 Cold-Infused 這一組數值）：用掉灌注時整組換掉——
 *     damage_by_type: { cold: [[..]], lightning: [[..]] }   這一下的基礎傷害改成這幾張逐級表（不是附加）
 *     projectiles: [12, …]   基礎投射物數改成這個（逐級；+x 投射物照樣加上去）
 *     nova: true             改成往四周一圈射出
 *   glory_from: attack_hit   2026-10-26：榮耀從哪裡來（attack_hit 攻擊打中照怪物強度、ailment 讓敵人冰凍／感電／點燃各 1 點、
 *                         heavy_stun 重暈敵人照怪物強度、ignite 點燃敵人照怪物強度）；戰吼的 warcry.glory 沒寫就是 ignite
 * </pre>
 * 2026-10-26 照 PoE2 接回來（這次之前連段、榮耀、灌注的消耗都拿掉、改成冷卻）：combo 要有幾點連擊才放得出來（放了用掉）、
 * glory 要幾點榮耀（放了用掉）、infusion 有灌注時用掉一層換 infused 的加成。
 */
public record ResourceSpec(boolean comboGain, int combo, List<Modifier> perCombo, int glory, List<String> infusion,
                           List<Modifier> infused, List<Modifier> empowered, String gloryFrom, boolean infusionEach,
                           java.util.Map<String, List<Modifier>> infusedBy, String remnant, double remnantDelay, InfusedSet infusedSet) {

    public static final ResourceSpec NONE = new ResourceSpec(false, 0, List.of(), 0, List.of(), List.of(), List.of(), null, false,
            java.util.Map.of(), null, 0, null);

    /**
     * 2026-10-28 照 PoE2（RePoE SparkColdInfusedPlayer）：用掉灌注時改用的另一組數值。damage = 每種傷害的逐級表（空的 = 照原本的傷害）、
     * projectiles = 基礎投射物數（null = 照原本的）、nova = 往四周一圈射出。這次之前灌注只能給固定的修飾器（游雷做成多 7 顆、120% 額外冰冷）。
     */
    public record InfusedSet(java.util.Map<tw.exilecore.stats.DamageType, int[]> damage, Scaled projectiles, boolean nova) {
        public InfusedSet {
            damage = damage == null ? java.util.Map.of() : java.util.Map.copyOf(damage);
        }

        static InfusedSet parse(final YamlNode node) {
            final java.util.Map<tw.exilecore.stats.DamageType, int[]> damage = node.has("damage_by_type")
                    ? SkillDefinition.typedTables(node.path() + ".damage_by_type", node.node("damage_by_type")) : java.util.Map.of();
            final Scaled projectiles = node.has("projectiles") ? Scaled.parse(node.path() + ".projectiles", node.raw("projectiles"), 1) : null;
            if (projectiles != null && (projectiles.min() < 1 || projectiles.last() > SkillBuild.MAX_PROJECTILES)) {
                throw new IllegalArgumentException(node.path() + ".projectiles：要在 1 到 " + SkillBuild.MAX_PROJECTILES + " 之間");
            }
            return new InfusedSet(damage, projectiles, node.bool("nova", false));
        }
    }

    /** 灌注殘骸可以寫的元素（random = 三種隨機一種）。 */
    public static final List<String> REMNANT_ELEMENTS = List.of("fire", "cold", "lightning", "random");

    /** 榮耀的來源。 */
    // 2026-10-26 照 PoE2：壞滅災變（元素擊中）、夏烏拉統治者（混沌擊中）的榮耀
    public static final List<String> GLORY_SOURCES = List.of("attack_hit", "ailment", "heavy_stun", "ignite", "rage_at_max", "elemental_hit", "chaos_hit"); // 2026-10-26：rage_at_max（怒氣滿時再拿到的怒氣，墜星熊吼）

    public ResourceSpec {
        perCombo = List.copyOf(perCombo);
        infusion = List.copyOf(infusion);
        infused = List.copyOf(infused);
        empowered = List.copyOf(empowered);
        infusedBy = java.util.Map.copyOf(infusedBy);
    }

    /** 用掉某一種灌注時的加成：有 infused_元素 就用那個，沒有用 infused。 */
    public List<Modifier> infusedFor(final String element) {
        return infusedBy.getOrDefault(element, infused);
    }

    public boolean isEmpty() {
        return !comboGain && combo <= 0 && glory <= 0 && infusion.isEmpty() && gloryFrom == null && remnant == null;
    }

    private static List<Modifier> mods(final YamlNode node, final String key, final String source) {
        final java.util.List<Modifier> list = new java.util.ArrayList<>();
        for (final YamlNode child : node.list(key)) {
            list.add(tw.exilecore.item.AffixMod.parse(child).toModifier(java.util.Map.of(), source));
        }
        return list;
    }

    static ResourceSpec parse(final String id, final YamlNode node) {
        if (node == null || node.keys().isEmpty()) {
            return NONE;
        }
        final List<String> infusion = node.strings("infusion").stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
        for (final String element : infusion) {
            if (!List.of("fire", "cold", "lightning").contains(element)) {
                throw new IllegalArgumentException(node.path() + ".infusion：只能是 fire、cold、lightning（" + element + "）");
            }
        }
        final String gloryFrom = node.has("glory_from") ? node.string("glory_from", "").trim().toLowerCase(Locale.ROOT) : null;
        if (gloryFrom != null && !GLORY_SOURCES.contains(gloryFrom)) {
            throw new IllegalArgumentException(node.path() + ".glory_from：要寫 " + GLORY_SOURCES + " 其中一個（" + gloryFrom + "）");
        }
        final java.util.Map<String, List<Modifier>> infusedBy = new java.util.HashMap<>();
        for (final String element : List.of("fire", "cold", "lightning")) {
            if (node.has("infused_" + element)) {
                infusedBy.put(element, mods(node, "infused_" + element, id));
            }
        }
        final String remnant = node.has("remnant") ? node.string("remnant", "").trim().toLowerCase(Locale.ROOT) : null;
        if (remnant != null && !REMNANT_ELEMENTS.contains(remnant)) {
            throw new IllegalArgumentException(node.path() + ".remnant：要寫 " + REMNANT_ELEMENTS + " 其中一個（" + remnant + "）");
        }
        return new ResourceSpec(node.bool("combo_gain", false), Math.max(0, node.integer("combo", 0)),
                mods(node, "per_combo", id), Math.max(0, node.integer("glory", 0)), infusion,
                mods(node, "infused", id), mods(node, "empowered", id), gloryFrom, node.bool("infusion_each", false), infusedBy,
                remnant, Math.max(0, node.decimal("remnant_delay", 0)),
                node.has("infused_set") ? InfusedSet.parse(node.node("infused_set")) : null);
    }
}
