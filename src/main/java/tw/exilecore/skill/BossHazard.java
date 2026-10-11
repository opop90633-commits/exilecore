package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 場地危害（maps.yml／ascendancy.yml 的 Boss 底下的 {@code hazards:}）：每隔 every 秒自己落下一次的招式
 * （隕石、冰柱、毒雨…），從第 fromPhase 階段起才開始。招式寫在 boss_skills.yml，只能用 slam、zone、orbs、mark 這幾種
 * （不用 Boss 站著放的；擴散環也不行）。Boss 正在放招時等那一招放完才落，落下之前 Boss 也不出新的招
 * （一次只做一件事，見 BossPacing）。純資料。
 * <pre>
 * hazards:
 *   - { skill: star_rain, every: 9 }
 *   - { skill: frost_spikes, every: 6, phase: 2 }
 * </pre>
 *
 * @param skill     boss_skills.yml 的招式代號
 * @param every     幾秒一次
 * @param fromPhase 第幾階段起（0 = 一開始）
 */
public record BossHazard(String skill, double every, int fromPhase) {

    /** 讀 {@code hazards:}；沒寫是空的。 */
    public static List<BossHazard> parseList(final YamlNode node, final String key) {
        final Object raw = node.raw(key);
        if (raw == null) {
            return List.of();
        }
        final String where = node.path() + "." + key;
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(where + "：要寫成清單（- { skill: star_rain, every: 9 }）");
        }
        final List<BossHazard> hazards = new ArrayList<>();
        int index = 0;
        for (final Object item : list) {
            final String at = where + "[" + index++ + "]";
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(at + "：要寫 { skill: 招式代號, every: 秒數 }");
            }
            final YamlNode sub = YamlNode.of(at, map);
            final double every = sub.decimal("every", 10);
            if (every < 2 || every > 120) {
                throw new IllegalArgumentException(at + ".every：要在 2 到 120 秒之間");
            }
            final int phase = sub.integer("phase", 0);
            if (phase < 0 || phase > 10) {
                throw new IllegalArgumentException(at + ".phase：要在 0 到 10 之間");
            }
            hazards.add(new BossHazard(sub.requireString("skill").trim(), every, phase));
        }
        return List.copyOf(hazards);
    }

    /**
     * 這些危害有沒有問題：招式不存在、或是要 Boss 站著放的種類（回傳說明；沒問題是空的）。
     */
    public static List<String> problems(final List<BossHazard> hazards, final BossSkillConfig config) {
        final List<String> problems = new ArrayList<>();
        for (final BossHazard hazard : hazards) {
            final BossSkill skill = config.skill(hazard.skill());
            if (skill == null) {
                problems.add("沒有這個招式「" + hazard.skill() + "」");
            } else if (!skill.kind().hazardCapable()) {
                problems.add("「" + hazard.skill() + "」是 " + skill.kind().name().toLowerCase(java.util.Locale.ROOT)
                        + "，場地危害只能用 slam、zone、orbs、mark");
            }
        }
        return problems;
    }
}
