package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 某隻 Boss 的招式組裡的一項（maps.yml／zones.yml 的 {@code skills:}）：哪一招、這隻 Boss 用時的冷卻與傷害、第幾階段起才用。純資料。
 *
 * @param id        boss_skills.yml 的招式代號
 * @param cooldown  這隻 Boss 用這招的冷卻（秒）；-1 = 照招式的設定
 * @param damage    這隻 Boss 用這招的傷害倍率；-1 = 照招式的設定
 * @param fromPhase 第幾階段起才會用（0 = 一開始就會；地圖 Boss 的 phases 第 n 個進入後是第 n 階段）
 * @param with      放這一招的同時一起落下的招（2026-10，{@code with: [star_fall]}；只能用 slam、zone、orbs、mark，
 *                  它們放出去之後自己跑，Boss 照樣放手上這一招）。沒寫是空的
 */
public record BossSkillUse(String id, double cooldown, double damage, int fromPhase, List<String> with) {

    public BossSkillUse {
        with = with == null ? List.of() : List.copyOf(with);
    }

    public BossSkillUse(final String id, final double cooldown, final double damage, final int fromPhase) {
        this(id, cooldown, damage, fromPhase, List.of());
    }

    public static BossSkillUse of(final String id) {
        return new BossSkillUse(id, -1, -1, 0);
    }

    /** 這隻 Boss 實際用的招式：套上冷卻與傷害的覆蓋值。 */
    public BossSkill resolve(final BossSkill skill) {
        if (cooldown < 0 && damage < 0) {
            return skill;
        }
        return skill.with(damage, cooldown);
    }

    /**
     * 讀 {@code skills:}：清單裡每一項可以是代號字串，或 {@code { id, cooldown, damage, phase, with }}。
     * 沒寫這個鍵回傳空清單（用預設的招式組）。
     */
    public static List<BossSkillUse> parseList(final YamlNode node, final String key) {
        final Object raw = node.raw(key);
        if (raw == null) {
            return List.of();
        }
        final String where = node.path() + "." + key;
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(where + "：要寫成清單（- slam 或 - { id: slam, phase: 1 }）");
        }
        final List<BossSkillUse> uses = new ArrayList<>();
        int index = 0;
        for (final Object item : list) {
            final String at = where + "[" + index++ + "]";
            if (item instanceof Map<?, ?> map) {
                final YamlNode sub = YamlNode.of(at, map);
                final double cooldown = sub.decimal("cooldown", -1);
                final double damage = sub.decimal("damage", -1);
                final int phase = sub.integer("phase", 0);
                if (cooldown != -1 && (cooldown < 1 || cooldown > 300)) {
                    throw new IllegalArgumentException(at + ".cooldown：要在 1 到 300 秒之間");
                }
                if (damage != -1 && (damage < 0 || damage > 20)) {
                    throw new IllegalArgumentException(at + ".damage：要在 0 到 20 之間（普通一下的幾倍）");
                }
                if (phase < 0 || phase > 10) {
                    throw new IllegalArgumentException(at + ".phase：要在 0 到 10 之間");
                }
                uses.add(new BossSkillUse(sub.requireString("id").trim(), cooldown, damage, phase, parseWith(at, sub.raw("with"))));
            } else if (item != null && !String.valueOf(item).isBlank()) {
                uses.add(of(String.valueOf(item).trim()));
            } else {
                throw new IllegalArgumentException(at + "：要寫招式代號或 { id: … }");
            }
        }
        return List.copyOf(uses);
    }

    /** {@code with:} 可以寫一個代號或一串代號。 */
    private static List<String> parseWith(final String at, final Object raw) {
        if (raw == null) {
            return List.of();
        }
        final List<String> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (final Object item : list) {
                if (item == null || String.valueOf(item).isBlank()) {
                    throw new IllegalArgumentException(at + ".with：要寫招式代號");
                }
                ids.add(String.valueOf(item).trim());
            }
        } else if (!String.valueOf(raw).isBlank()) {
            ids.add(String.valueOf(raw).trim());
        }
        if (ids.size() > 3) {
            throw new IllegalArgumentException(at + ".with：最多同時再放 3 招");
        }
        return ids;
    }
}
