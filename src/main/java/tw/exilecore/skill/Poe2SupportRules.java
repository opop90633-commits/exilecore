package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-30 照站長（未切割輔助寶石選技能後，那個技能是不是真的能裝上那顆輔助）：照 PoE2 0.5 的技能類型，
 * 我們的標籤規則讓掛、原作不讓掛的組合擋掉（data/support_poe2.yml，tools/gen_poe2_support_types.py 產生）。
 * {@link SupportDefinition#refusal} 最先問這裡；宿主寶石照孔裡的技能驗，不在表上。
 */
public final class Poe2SupportRules {

    /** 資料檔名。 */
    public static final String FILE = "support_poe2.yml";

    /** 輔助 → 技能 → 擋掉的原因。 */
    private static volatile Map<String, Map<String, String>> blocked = Map.of();

    private Poe2SupportRules() {
    }

    /** 這顆輔助掛在這個技能上原作不讓掛的原因；可以掛（或不在表上）回傳 null。 */
    static String refusal(final String supportId, final String skillId) {
        final Map<String, String> skills = blocked.get(supportId);
        return skills == null ? null : skills.get(skillId);
    }

    /** 讀 support_poe2.yml（SkillConfig.load 呼叫）；技能或輔助代號不認得就報錯（資料跟設定對不上了，要重產）。 */
    static Map<String, Map<String, String>> parse(final YamlNode root, final Set<String> skills, final Set<String> supports) {
        final Map<String, Map<String, String>> out = new HashMap<>();
        final YamlNode section = root.node("blocked");
        if (section == null) {
            return out;
        }
        for (final String support : section.keys()) {
            if (!supports.contains(support)) {
                throw new IllegalArgumentException(FILE + "：不認得的輔助 " + support + "（重跑 tools/gen_poe2_support_types.py）");
            }
            final YamlNode reasons = section.node(support);
            final Map<String, String> bySkill = new HashMap<>();
            for (final String reason : reasons.keys()) {
                for (final String skill : reasons.strings(reason)) {
                    if (!skills.contains(skill)) {
                        throw new IllegalArgumentException(FILE + "：" + support + " 寫了不認得的技能 " + skill + "（重跑 tools/gen_poe2_support_types.py）");
                    }
                    bySkill.put(skill, reason);
                }
            }
            out.put(support, Map.copyOf(bySkill));
        }
        return out;
    }

    static void install(final Map<String, Map<String, String>> rules) {
        blocked = Map.copyOf(rules);
    }

    /** 2026-10-30 照 PoE2（excluded_types 有 UsedByProxy）：不能用在代放的技能上（幻影銳眼的幻影）的輔助。 */
    private static volatile Set<String> proxy = Set.of();

    /** 這顆輔助能不能用在代放的技能上（幻影銳眼的幻影）。 */
    static boolean proxyExcluded(final String supportId) {
        return proxy.contains(supportId);
    }

    static Set<String> parseProxy(final YamlNode root, final Set<String> supports) {
        final java.util.Set<String> out = new java.util.HashSet<>();
        for (final String support : root.strings("proxy")) {
            if (!supports.contains(support)) {
                throw new IllegalArgumentException(FILE + "：proxy 寫了不認得的輔助 " + support + "（重跑 tools/gen_poe2_support_types.py）");
            }
            out.add(support);
        }
        return out;
    }

    static void installProxy(final Set<String> supports) {
        proxy = Set.copyOf(supports);
    }

    /** 2026-10-30 照 PoE2（輔助的 added_types）：輔助 → 解開它的輔助 → 技能（同一個技能掛著那顆時，原作的擋就不算）。 */
    private static volatile Map<String, Map<String, Set<String>>> unlocks = Map.of();

    /** 這顆輔助掛在這個技能上，others 裡有沒有能解開原作限制的輔助（只看名單，那顆自己掛不掛得上另外看）。 */
    static java.util.List<String> unlockers(final String supportId, final String skillId) {
        final Map<String, Set<String>> by = unlocks.get(supportId);
        if (by == null) {
            return java.util.List.of();
        }
        final java.util.List<String> out = new java.util.ArrayList<>();
        by.forEach((other, skills) -> {
            if (skills.contains(skillId)) {
                out.add(other);
            }
        });
        return out;
    }

    static Map<String, Map<String, Set<String>>> parseUnlocks(final YamlNode root, final Set<String> skills, final Set<String> supports) {
        final Map<String, Map<String, Set<String>>> out = new HashMap<>();
        final YamlNode section = root.node("unlocks");
        if (section == null) {
            return out;
        }
        for (final String support : section.keys()) {
            if (!supports.contains(support)) {
                throw new IllegalArgumentException(FILE + "：unlocks 寫了不認得的輔助 " + support + "（重跑 tools/gen_poe2_support_types.py）");
            }
            final YamlNode by = section.node(support);
            final Map<String, Set<String>> map = new HashMap<>();
            for (final String other : by.keys()) {
                if (!supports.contains(other)) {
                    throw new IllegalArgumentException(FILE + "：unlocks." + support + " 寫了不認得的輔助 " + other);
                }
                final java.util.Set<String> list = new java.util.HashSet<>();
                for (final String skill : by.strings(other)) {
                    if (skills.contains(skill)) {
                        list.add(skill);
                    }
                }
                map.put(other, Set.copyOf(list));
            }
            out.put(support, Map.copyOf(map));
        }
        return out;
    }

    static void installUnlocks(final Map<String, Map<String, Set<String>>> map) {
        unlocks = Map.copyOf(map);
    }

    /** 2026-10-30 照 PoE2（excluded_types 擋 Triggered）：不能用在觸發型主要寶石孔裡的法術上的輔助（蓄詠…）。 */
    private static volatile Set<String> triggered = Set.of();
    /** 主要寶石 → 孔裡的技能是觸發的（true）或代放的（false，法術圖騰）。 */
    private static volatile Map<String, Boolean> metaHosts = Map.of();

    static boolean triggeredExcluded(final String supportId) {
        return triggered.contains(supportId);
    }

    static Set<String> parseTriggered(final YamlNode root, final Set<String> supports) {
        final java.util.Set<String> out = new java.util.HashSet<>();
        for (final String support : root.strings("triggered")) {
            if (!supports.contains(support)) {
                throw new IllegalArgumentException(FILE + "：triggered 寫了不認得的輔助 " + support + "（重跑 tools/gen_poe2_support_types.py）");
            }
            out.add(support);
        }
        return out;
    }

    static void installTriggered(final Set<String> supports, final Map<String, MetaSpec> metas) {
        triggered = Set.copyOf(supports);
        final Map<String, Boolean> hosts = new java.util.HashMap<>();
        metas.forEach((id, meta) -> hosts.put(id, meta.kind() != MetaSpec.Kind.TOTEM));
        metaHosts = Map.copyOf(hosts);
    }

    /**
     * 2026-10-30 照 PoE2：主要寶石孔裡的技能吃不到的輔助——觸發型（Cast on Critical 等加 Triggered）擋 Triggered 的、
     * 法術圖騰（加 UsedByProxy）擋 UsedByProxy 的。可以回傳 null，不行回傳原因。
     */
    static String metaHostRefusal(final String supportId, final String hostId) {
        final Boolean triggers = hostId == null ? null : metaHosts.get(hostId);
        if (triggers == null) {
            return null;
        }
        if (triggers && triggered.contains(supportId)) {
            return "觸發的技能不能用";
        }
        if (!triggers && proxy.contains(supportId)) {
            return "圖騰代放的技能不能用";
        }
        return null;
    }
}
