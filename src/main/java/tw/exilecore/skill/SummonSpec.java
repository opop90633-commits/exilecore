package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.AffixMod;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 召喚物的設定（skills.yml 裡 mechanic: summon 的 summon 區塊）。純資料，不依賴 Bukkit。
 * <p>
 * 召喚物的數值用「同等級普通怪物」當底：生命、傷害、命中、閃避、護甲都照怪物的等級曲線
 * （等級 = 技能等級對應的需求角色等級），再乘這裡的倍率；只吃主人身上帶 minion 標籤的修飾器與掛的輔助寶石。
 *
 * @param kind        原版生物種類（{@link #KINDS}）
 * @param count       一次召出幾隻
 * @param max         同時最多幾隻（隨技能等級；再召就把最舊的收掉）
 * @param fixedMax    true = 數量上限不吃「+x 召喚物數量」（魔像這種只有一尊的）
 * @param duration    活幾秒（0 = 永久：直到被打死、主人下線或換角色）
 * @param life        生命是同等級普通怪的幾倍
 * @param damage      每一下的傷害是同等級普通怪的幾倍
 * @param attackSpeed 每秒攻擊幾次（基礎）
 * @param speed       移動速度倍率（原版該生物的）
 * @param reach       攻擊距離（身體邊緣到身體邊緣，格）
 * @param radius      打中時連目標周圍幾格內的敵人一起打（0 = 只打一個；2026-10-29：skills.yml 寫公尺，讀進來已換成格）
 * @param corpse      要用一具屍體（怪物死後十秒內）當材料，在屍體的位置出現
 * @param search      屍體在主人幾格內都算
 * @param helmet      頭上戴什麼（原版物品名稱；外觀用，沒有就不戴）
 * @param weapon      手上拿什麼（原版物品名稱；外觀用）
 * @param resists     各類型的抗性（百分比點數，例如焚魂的火焰抗性 75）
 * @param taunt       每隔幾秒吼一聲，把附近的怪引過來打它（0 = 不會）
 * @param tauntRadius 吼聲引多遠內的怪
 * @param buff        有至少一隻活著時加在主人身上的修飾器（魔像）
 * @param ranged      2026-10 遠程：大於 0 = 站在這麼多格內射擊（骷髏弓手、法師），不用走到旁邊；0 = 近戰
 * @param bolt        遠程的彈道外觀：arrow、fire、cold、lightning、chaos
 * @param spectre     2026-10 附身：用屍體原本的那種怪物當身體（Boss 的屍體不行）；2026-10-26 起附魂術是精魂召喚，
 *                    按鍵附身一具屍體之後一直叫那種怪（這次之前要 corpse: true，每放一次用一具屍體）
 * @param heal        2026-10 治療：每秒替主人與附近 8 格內自己的召喚物回復最大生命的 x%
 * @param ally        2026-10-26 照 PoE2：召喚物等級 = 寶石等級 × 2，生命、傷害、護甲、閃避用 PoE2 的召喚物（ally）表，
 *                    life／damage／armour／evasion 是 PoE2 那隻怪的倍率（見 {@link MinionSheets#allyBase}）；false = 舊的「同等級普通怪」算法
 * @param armour      （ally）護甲倍率（PoE2 的怪物護甲表 × 這個；0 = 沒有護甲）
 * @param evasion     （ally）閃避倍率（0 = 沒有閃避）
 * @param spread      （ally）每一下傷害上下浮動多少（PoE2 多半 ±30%）
 * @param interval    2026-10-26 精魂召喚每隔幾秒叫一隻（蟲噬群：每 6 秒一群、最多 3 群、每群活幾秒）；0 = 死了才補
 * @param stats       2026-10-26 召喚物自己身上的修飾器（骸骨戰士 30% 格擋、重卒更多暈眩累積…）
 * @param es          2026-10-26 照 PoE2（Energy Shield from Life）：生命的這個比例改成能量護盾（焰術士 0.10、霜術士／雷術士／祭司 0.15）；
 *                    4 秒沒受傷後每秒回充 12.5%（見 {@link MinionAbilities#ES_RECHARGE_PER_SECOND}）
 */
public record SummonSpec(String kind, int count, Scaled max, boolean fixedMax, double duration, double life, double damage,
                         double attackSpeed, double speed, double reach, double radius, boolean corpse, double search,
                         String helmet, String weapon, Map<DamageType, Double> resists, double taunt, double tauntRadius,
                         List<Modifier> buff, double ranged, String bolt, boolean spectre, double heal,
                         boolean ally, double armour, double evasion, double spread, double interval, List<Modifier> stats,
                         double es) {

    /** 能當召喚物的原版生物（都有近戰 AI、能走路或飛；惱鬼會飛、穿牆）。 */
    public static final Set<String> KINDS = Set.of("ZOMBIE", "HUSK", "SKELETON", "STRAY", "WITHER_SKELETON", "VEX", "IRON_GOLEM", "WOLF");

    public SummonSpec {
        buff = buff == null ? List.of() : List.copyOf(buff);
        stats = stats == null ? List.of() : List.copyOf(stats);
        resists = resists == null || resists.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(resists));
    }

    /** 遠程的（2026-10）。 */
    public boolean isRanged() {
        return ranged > 0;
    }

    /** 會飛的（惱鬼）：不用尋路，直接飄過去。 */
    public boolean flying() {
        return "VEX".equals(kind);
    }

    /** 永久的（沒有持續時間）。 */
    public boolean permanent() {
        return duration <= 0;
    }

    /** 顯示用的名字：殭屍、骷髏、焚魂、魔像…。 */
    public String kindName() {
        return switch (kind) {
            case "ZOMBIE", "HUSK" -> "殭屍";
            case "SKELETON", "STRAY" -> "骷髏";
            case "WITHER_SKELETON" -> "凋零骷髏";
            case "VEX" -> "焚魂";
            case "IRON_GOLEM" -> "魔像";
            case "WOLF" -> "狼";
            default -> kind;
        };
    }

    /** skills.yml 的 summon 區塊；沒有回傳 null。 */
    static SummonSpec parse(final YamlNode skill, final String skillName) {
        if (!skill.has("summon")) {
            return null;
        }
        final YamlNode node = skill.node("summon");
        final String where = node.path();
        final String kind = node.requireString("kind").trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException(where + ".kind：不認得「" + kind + "」，可用：" + String.join("、", KINDS.stream().sorted().toList()));
        }
        final Scaled max = Scaled.parse(where + ".max", node.raw("max"), 1);
        check(where, "max", max.first(), 1, 40);
        check(where, "max", max.last(), 1, 40);
        final Map<DamageType, Double> resists = new EnumMap<>(DamageType.class);
        final YamlNode resistNode = node.node("resists");
        for (final String key : resistNode.keys()) {
            final DamageType type = DamageType.fromTag(key);
            if (type == null || type == DamageType.PHYSICAL) {
                throw new IllegalArgumentException(where + ".resists：不認得的類型「" + key + "」（fire、cold、lightning、chaos）");
            }
            resists.put(type, check(where, "resists." + key, resistNode.decimal(key, 0), -100, 100));
        }
        final List<Modifier> buff = new ArrayList<>();
        for (final YamlNode child : node.list("buff")) {
            buff.add(AffixMod.parse(child).toModifier(Map.of(), skillName));
        }
        final List<Modifier> stats = new ArrayList<>();
        for (final YamlNode child : node.list("stats")) {
            stats.add(AffixMod.parse(child).toModifier(Map.of(), skillName));
        }
        final boolean corpse = node.bool("corpse", false);
        return new SummonSpec(kind,
                (int) check(where, "count", node.integer("count", 1), 1, 5),
                max, node.bool("fixed_max", false),
                check(where, "duration", node.decimal("duration", 0), 0, 120),
                check(where, "life", node.decimal("life", 1.0), 0.05, 10),
                check(where, "damage", node.decimal("damage", 1.0), 0.1, 10),
                check(where, "attack_speed", node.decimal("attack_speed", 1.0), 0.3, 3),
                check(where, "speed", node.decimal("speed", 1.0), 0.5, 2),
                check(where, "reach", node.decimal("reach", 2.0), 1, 4),
                // 2026-10-29 照站長：1 公尺 = 2 格（Units）——召喚物打中時的範圍照 PoE2 寫公尺（骸骨焰術士火彈 1.6），檢查照公尺
                tw.exilecore.core.Units.metres(check(where, "radius", node.decimal("radius", 0), 0, 4)),
                corpse, check(where, "search", node.decimal("search", 10), 2, 16),
                item(node, "helmet"), item(node, "weapon"), resists,
                check(where, "taunt", node.decimal("taunt", 0), 0, 30),
                check(where, "taunt_radius", node.decimal("taunt_radius", 8), 1, 16), buff,
                check(where, "ranged", node.decimal("ranged", 0), 0, 24), bolt(where, node.string("bolt", "arrow")),
                spectre(where, node, corpse), check(where, "heal", node.decimal("heal", 0), 0, 10),
                node.bool("ally", false), check(where, "armour", node.decimal("armour", 0), 0, 5),
                check(where, "evasion", node.decimal("evasion", 0), 0, 5), check(where, "spread", node.decimal("spread", 0.3), 0, 0.9),
                check(where, "interval", node.decimal("interval", 0), 0, 60), stats,
                check(where, "es", node.decimal("es", 0), 0, 0.9));
    }

    /**
     * 2026-10-26：換掉生命、傷害倍率（附魂術：照附身的那種怪物的倍率，見 monsters.yml 的 types）。
     */
    public SummonSpec withMultipliers(final double newLife, final double newDamage) {
        return new SummonSpec(kind, count, max, fixedMax, duration, newLife, newDamage, attackSpeed, speed, reach, radius, corpse, search,
                helmet, weapon, resists, taunt, tauntRadius, buff, ranged, bolt, spectre, heal, ally, armour, evasion, spread, interval, stats, es);
    }

    /** 附身召喚能用的怪物種類（都走得動、打得了近戰，不會自己爆炸或瞬移）。 */
    public static final Set<String> SPECTRES = Set.of("ZOMBIE", "HUSK", "DROWNED", "ZOMBIE_VILLAGER", "SKELETON", "STRAY", "BOGGED",
            "WITHER_SKELETON", "SPIDER", "CAVE_SPIDER", "PIGLIN", "PIGLIN_BRUTE", "ZOMBIFIED_PIGLIN", "VINDICATOR", "PILLAGER", "WOLF",
            "IRON_GOLEM", "RAVAGER", "HOGLIN", "ZOGLIN", "WITCH", "EVOKER");

    private static boolean spectre(final String where, final YamlNode node, final boolean corpse) {
        // 2026-10-26 照 PoE2：附魂術改成精魂召喚（按鍵附身一具屍體，之後一直叫那種怪），不再要 corpse: true
        return node.bool("spectre", false);
    }

    private static String bolt(final String where, final String raw) {
        final String bolt = raw.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("arrow", "fire", "cold", "lightning", "chaos").contains(bolt)) {
            throw new IllegalArgumentException(where + ".bolt：要寫 arrow、fire、cold、lightning、chaos，卻是「" + raw + "」");
        }
        return bolt;
    }

    private static String item(final YamlNode node, final String key) {
        final String value = node.string(key, null);
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static double check(final String where, final String key, final double value, final double min, final double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(where + "." + key + "：要在 " + min + " 到 " + max + " 之間，卻是 " + value);
        }
        return value;
    }
}
