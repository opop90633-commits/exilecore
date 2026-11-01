package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.HashMap;
import java.util.Map;

/**
 * 2026-11-01 照 PoE2（base_totem_range：圖騰放置距離）：寫了 totem.place_range 的圖騰放在準心指的地方（最遠 place_range 公尺），
 * 不是固定放在面前 1.5 格。沒寫的圖騰照舊（SkillDeployables.place）。
 * <pre>
 *   totem: { ..., place_range: 8 }   # 落釘弩台、衝霄弩台（base_totem_range 80 → 8 公尺）
 * </pre>
 */
public final class TotemPlacement {

    private static volatile Map<String, Double> ranges = Map.of();

    private TotemPlacement() {
    }

    /** 讀一個技能 totem 區塊的 place_range（公尺 → 格；沒寫回傳 0）。 */
    static double parse(final YamlNode skill) {
        if (!skill.has("totem")) {
            return 0;
        }
        final YamlNode totem = skill.node("totem");
        final double metres = totem.decimal("place_range", 0);
        if (metres < 0 || metres > 30) {
            throw new IllegalArgumentException(totem.path() + ".place_range：要在 0 到 30 格之間，卻是 " + metres);
        }
        return metres * tw.exilecore.core.Units.BLOCKS_PER_METRE;
    }

    /** 換上新讀的設定（SkillConfig.load；技能代號 → 格）。 */
    static void install(final Map<String, Double> parsed) {
        ranges = Map.copyOf(parsed);
    }

    /** 這個圖騰最遠放多遠（格；0 = 照舊放在面前）。 */
    public static double range(final String skillId) {
        final Double range = skillId == null ? null : ranges.get(skillId);
        return range == null ? 0 : range;
    }

    /** 測試用。 */
    static Map<String, Double> all() {
        return new HashMap<>(ranges);
    }
}
