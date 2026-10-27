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
 *   glory_from: attack_hit   2026-10-26：榮耀從哪裡來（attack_hit 攻擊打中照怪物強度、ailment 讓敵人冰凍／感電／點燃各 1 點、
 *                         heavy_stun 重暈敵人照怪物強度、ignite 點燃敵人照怪物強度）；戰吼的 warcry.glory 沒寫就是 ignite
 * </pre>
 * 2026-10-26 照 PoE2 接回來（這次之前連段、榮耀、灌注的消耗都拿掉、改成冷卻）：combo 要有幾點連擊才放得出來（放了用掉）、
 * glory 要幾點榮耀（放了用掉）、infusion 有灌注時用掉一層換 infused 的加成。
 */
public record ResourceSpec(boolean comboGain, int combo, List<Modifier> perCombo, int glory, List<String> infusion,
                           List<Modifier> infused, List<Modifier> empowered, String gloryFrom, boolean infusionEach,
                           java.util.Map<String, List<Modifier>> infusedBy, String remnant, double remnantDelay) {

    public static final ResourceSpec NONE = new ResourceSpec(false, 0, List.of(), 0, List.of(), List.of(), List.of(), null, false,
            java.util.Map.of(), null, 0);

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
                remnant, Math.max(0, node.decimal("remnant_delay", 0)));
    }
}
