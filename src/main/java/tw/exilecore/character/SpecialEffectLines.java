package tw.exilecore.character;

import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.charge.ChargeText;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 身上的特殊效果（傳奇裝備、基石、戰星的來源）寫成一行一行，給 /stats 與角色面板：
 * 「+1 法術技能等級」「擊中時 10% 機率造成雙倍傷害」「擊殺時 25% 機率獲得一顆怒星」「免疫混沌傷害」…。純函式，不依賴 Bukkit。
 * 同一個屬性、同樣標籤的加在一起；沒有的不列。
 */
public final class SpecialEffectLines {

    /** 照這個順序列。 */
    public static final List<String> STATS = List.of(StatKeys.SKILL_LEVEL, StatKeys.DAMAGE_DOUBLE, StatKeys.LIFE_ON_HIT,
            StatKeys.LIFE_ON_KILL, StatKeys.MANA_ON_KILL, StatKeys.ES_ON_KILL, StatKeys.ONSLAUGHT_ON_KILL,
            StatKeys.KILL_EXPLODE, StatKeys.DAMAGE_FROM_MANA, StatKeys.LIFE_RECOUP, StatKeys.LEECH_LIFE, StatKeys.LEECH_MANA,
            StatKeys.LEECH_LIFE_ELEMENTAL, StatKeys.LEECH_INSTANT, StatKeys.DAMAGE_VS_LOW_LIFE, // 2026-10-21：對低血照加總寫
            // 2026-10-22：獲得 x% 傷害為額外的…、最大混沌抗性：以前招牌天賦寫死 10%／4%，現在照身上的加總寫
            StatKeys.DAMAGE_EXTRA + "fire", StatKeys.DAMAGE_EXTRA + "cold", StatKeys.DAMAGE_EXTRA + "lightning",
            StatKeys.DAMAGE_EXTRA + "chaos", StatKeys.RESIST_MAX + ".chaos");

    /** 開關（基石，或給基石的傳奇）→ 說明。 */
    private static final Map<String, String> FLAGS = new LinkedHashMap<>();

    static {
        FLAGS.put(StatKeys.FLAG_ALWAYS_HIT, "攻擊必定命中");
        FLAGS.put(StatKeys.FLAG_CRUSH_PRIMED, "擊中蓄勢待暈的敵人時直接重暈");
        FLAGS.put(StatKeys.FLAG_BLOOD_MAGIC, "血償契：沒有魔力，技能的消耗改扣生命");
        FLAGS.put(StatKeys.FLAG_ELDRITCH_BATTERY, "魂池逆流：能量護盾全部轉為最大魔力");
        FLAGS.put(StatKeys.FLAG_BLACKFLAME, "墨焰誓：火焰法術的火焰傷害全部轉為混沌");
        FLAGS.put(StatKeys.FLAG_AVATAR_FIRE, "焚身星骸：75% 傷害轉為火焰，不造成非火焰傷害");
        FLAGS.put(StatKeys.FLAG_PAIN_ATTUNEMENT, "痛極生力：滿血暴擊加成更少 30%，低血更多 30%");
        FLAGS.put(StatKeys.FLAG_DANCE_WITH_DEATH, "單刃疾舞：單手武器、副手空著時攻擊與施法速度更多 25%");
        FLAGS.put(StatKeys.FLAG_ZEALOTS_OATH, "溢生為盾：溢出的生命回復變成護盾，護盾不回充");
        FLAGS.put(StatKeys.FLAG_ETERNAL_YOUTH, "生息輪迴：生命像護盾一樣回充，護盾不回充");
        FLAGS.put(StatKeys.FLAG_BULWARK, "鐵甲翻身：翻滾不能躲傷害，翻滾中受到的擊中傷害更少 30%");
        FLAGS.put(StatKeys.FLAG_UNWAVERING, "磐石不移：不會被輕暈，不能翻滾");
        FLAGS.put(StatKeys.FLAG_CRIMSON_ASSAULT, "血湧狂潮：流血加劇、基礎 1 秒、更多 50%");
        FLAGS.put(StatKeys.FLAG_ANCESTRAL_BOND, "圖騰之林：圖騰上限加倍");
        FLAGS.put(StatKeys.FLAG_CULLING, "斷命：擊中後生命低於門檻的敵人直接倒下（普通 30%、魔法 20%、稀有 10%、傳奇與 Boss 5%）");
        FLAGS.put(StatKeys.FLAG_AURA_LIFE, "光環保留生命而非魔力（最大生命照保留的比例縮小）");
        FLAGS.put(StatKeys.FLAG_NO_CRIT, "無法暴擊");
        FLAGS.put(StatKeys.FLAG_CHAOS_IMMUNE, "免疫混沌傷害（最大生命 1）");
        FLAGS.put(StatKeys.FLAG_IRON_REFLEXES, "閃避全部轉為護甲");
        FLAGS.put(StatKeys.FLAG_FREEZE_IMMUNE, "無法被凍結");
        FLAGS.put(StatKeys.FLAG_ELEMENTAL_AILMENT_IMMUNE, "免疫元素異常（點燃、冰緩、凍結、感電）");
        FLAGS.put(StatKeys.FLAG_MAIM_IMMUNE, "免疫癱瘓");
        FLAGS.put(StatKeys.FLAG_HINDER_IMMUNE, "不會被阻礙");
        FLAGS.put(StatKeys.FLAG_BLIND_IMMUNE, "不會被致盲");
        FLAGS.put(StatKeys.FLAG_CORRUPTED_BLOOD_IMMUNE, "免疫腐化之血");
        FLAGS.put(StatKeys.FLAG_INSTANT_LEECH, "偷取全部立即生效（一下最多回 6% 最大生命）；生命回復沒有效果");
        FLAGS.put(StatKeys.FLAG_NO_LEECH, "無法偷取（地圖詞綴）");
        FLAGS.putAll(tw.exilecore.stats.SignatureText.TEXT); // 昇華的招牌天賦
    }

    private SpecialEffectLines() {
    }

    /** 同一個屬性、同樣標籤的固定值加起來，各寫一行（加起來是 0 的不寫）。 */
    private static void sums(final StatSheet sheet, final String stat, final List<String> lines) {
        final Map<Set<String>, Double> sums = new LinkedHashMap<>();
        for (final Modifier modifier : sheet.modifiersOf(stat)) {
            if (modifier.type() == ModType.FLAT) {
                sums.merge(modifier.tags(), modifier.value(), Double::sum);
            }
        }
        for (final Map.Entry<Set<String>, Double> entry : sums.entrySet()) {
            if (Math.abs(entry.getValue()) > 1e-9) {
                lines.add(StatLines.describe(new Modifier(stat, ModType.FLAT, entry.getValue(), entry.getKey(), "")));
            }
        }
    }

    public static List<String> of(final StatSheet sheet) {
        final List<String> lines = new ArrayList<>();
        for (final String stat : STATS) {
            sums(sheet, stat, lines);
        }
        // 戰星的來源（天賦、詞綴、傳奇）：「擊殺時 25% 機率獲得一顆怒星」「擊中時 5% 機率獲得一顆怒星」「+1 怒星上限」
        for (final String charge : ChargeText.ids()) {
            sums(sheet, StatKeys.chargeChance(charge), lines);
            sums(sheet, StatKeys.chargeOnHit(charge), lines);
            sums(sheet, StatKeys.chargeMax(charge), lines);
        }
        final double thornsMax = sheet.flat(StatKeys.THORNS_MAX, Set.of());
        if (thornsMax > 0) {
            final double thornsMin = Math.min(thornsMax, Math.max(0, sheet.flat(StatKeys.THORNS_MIN, Set.of())));
            lines.add("被近戰擊中時，反彈 " + Math.round(thornsMin) + " 到 " + Math.round(thornsMax) + " 物理傷害給攻擊者");
        }
        for (final Map.Entry<String, String> flag : FLAGS.entrySet()) {
            if (StatKeys.flag(sheet, flag.getKey())) {
                lines.add(flag.getValue());
            }
        }
        return lines;
    }
}
