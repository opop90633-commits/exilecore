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
 * </pre>
 */
public record ResourceSpec(boolean comboGain, int combo, List<Modifier> perCombo, int glory, List<String> infusion,
                           List<Modifier> infused, List<Modifier> empowered) {

    public static final ResourceSpec NONE = new ResourceSpec(false, 0, List.of(), 0, List.of(), List.of(), List.of());

    public ResourceSpec {
        perCombo = List.copyOf(perCombo);
        infusion = List.copyOf(infusion);
        infused = List.copyOf(infused);
        empowered = List.copyOf(empowered);
    }

    public boolean isEmpty() {
        return !comboGain && combo <= 0 && glory <= 0 && infusion.isEmpty();
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
        return new ResourceSpec(node.bool("combo_gain", false), Math.max(0, node.integer("combo", 0)),
                mods(node, "per_combo", id), Math.max(0, node.integer("glory", 0)), infusion,
                mods(node, "infused", id), mods(node, "empowered", id));
    }
}
