package tw.exilecore.skill;

import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 把修飾器寫成玩家看得懂的一句話（輔助寶石的說明）。純函式。 */
public final class ModifierText {

    /** 標籤在句子裡的順序：先技能種類，再傷害類型。 */
    private static final List<String> TAG_ORDER = List.of(Tags.MELEE, Tags.PROJECTILE, Tags.AREA, Tags.ATTACK, Tags.SPELL,
            Tags.PHYSICAL, Tags.ELEMENTAL, Tags.FIRE, Tags.COLD, Tags.LIGHTNING, Tags.CHAOS);

    private ModifierText() {
    }

    public static String tagName(final String tag) {
        return switch (tag) {
            case Tags.ATTACK -> "攻擊";
            case Tags.SPELL -> "法術";
            case Tags.MELEE -> "近戰";
            case Tags.PROJECTILE -> "投射物";
            case Tags.AREA -> "範圍";
            case Tags.STRIKE -> "打擊";
            case Tags.SLAM -> "猛擊";
            case Tags.FIRE -> "火焰";
            case Tags.COLD -> "冰冷";
            case Tags.LIGHTNING -> "閃電";
            case Tags.CHAOS -> "混沌";
            case Tags.PHYSICAL -> "物理";
            case Tags.ELEMENTAL -> "元素";
            case Tags.BOW -> "弓";
            case Tags.WAND -> "法杖";
            case Tags.STAFF -> "長杖";
            case Tags.CROSSBOW -> "弩";
            case Tags.QUARTERSTAFF -> "長棍";
            case Tags.SPEAR -> "矛";
            case Tags.TALISMAN -> "獸魂符";
            case Tags.SHAPESHIFT -> "變身";
            case Tags.BEAR -> "熊形態";
            case Tags.WEREWOLF -> "狼人形態";
            case Tags.WYVERN -> "飛龍形態";
            case "chain" -> "連鎖";
            case "nova" -> "新星";
            case Tags.MOVEMENT -> "移動";
            case Tags.TRAVEL -> "突進";
            case Tags.DURATION -> "持續";
            case Tags.COOLDOWN -> "冷卻";
            case Tags.MARK -> "印記";
            case "curse" -> "詛咒";
            case "crystal" -> "冰晶";
            case Tags.GRENADE -> "榴彈";
            case Tags.CORPSE -> "屍體";
            case Tags.MINION -> "召喚物";
            case "command" -> "指令";
            case Tags.TOTEM -> "圖騰";
            case Tags.BELL -> "鐘";
            case Tags.TRAP -> "陷阱";
            case Tags.AURA -> "光環";
            case Tags.HERALD -> "捷光環";
            case Tags.WARCRY -> "戰吼";
            case Tags.PERSISTENT -> "常駐";
            case Tags.SWORD -> "劍";
            case Tags.AXE -> "斧";
            case Tags.MACE -> "錘";
            case Tags.DAGGER -> "匕首";
            case Tags.CLAW -> "爪";
            case "sceptre" -> "權杖";
            case Tags.ONE_HAND -> "單手武器";
            case Tags.TWO_HAND -> "雙手武器";
            case "melee_1h" -> "單手近戰";
            case "melee_2h" -> "雙手近戰";
            case "shield" -> "盾";
            case "weapon" -> "武器";
            case "ranged" -> "遠程";
            case "caster" -> "施法武器";
            default -> {
                final String word = tw.exilecore.ascendancy.StatLines.tagWord(tag);
                yield word != null ? word : tag;
            }
        };
    }

    /** 一條修飾器的說明。 */
    public static String describe(final Modifier modifier) {
        final double value = modifier.value();
        final String percent = number(Math.abs(value) * 100);
        final String stat = modifier.stat();
        if (stat.startsWith(StatKeys.GRANT_PREFIX)) { // 2026-10：精魂輔助
            return "被輔助的技能生效時你得到：" + describe(new Modifier(stat.substring(StatKeys.GRANT_PREFIX.length()), modifier.type(), value,
                    modifier.tags(), modifier.source()));
        }
        if (stat.startsWith(StatKeys.SELF_AILMENT_DURATION + ".")) { // 2026-10：單一種異常（精魂輔助）
            final String kind = switch (stat.substring(StatKeys.SELF_AILMENT_DURATION.length() + 1)) {
                case "ignite" -> "點燃";
                case "chill" -> "冰緩";
                case "freeze" -> "凍結";
                case "shock" -> "感電";
                default -> "異常";
            };
            return "你身上的" + kind + "持續時間" + (value < 0 ? "減少 " : "增加 ") + percent + "%";
        }
        if (stat.startsWith(StatKeys.DAMAGE_EXTRA_PER_MANA) && modifier.type() == tw.exilecore.stats.ModType.FLAT) { // 2026-10：祕法之心
            return "每 100 點最大魔力，" + tagPrefix(modifier) + "獲得 " + number(value) + "% 傷害為額外的"
                    + tagName(stat.substring(StatKeys.DAMAGE_EXTRA_PER_MANA.length())) + "傷害";
        }
        if (stat.equals(StatKeys.SKILL_COST_MAX_MANA) && modifier.type() == tw.exilecore.stats.ModType.FLAT) { // 2026-10：祕法之心
            return tagPrefix(modifier) + "魔力消耗多最大魔力的 " + number(value) + "%";
        }
        if (stat.startsWith("damage.extra.") && modifier.type() == tw.exilecore.stats.ModType.FLAT) { // 2026-10
            return "獲得 " + number(value) + "% 傷害為額外的" + tagName(stat.substring("damage.extra.".length())) + "傷害";
        }
        if (stat.startsWith(SupportProcs.PREFIX)) { // 2026-10：輔助觸發的專屬技能
            final String when = switch (stat.substring(SupportProcs.PREFIX.length())) {
                case "hit" -> "擊中時";
                case "hit_ignited" -> "擊中點燃中的敵人時（用掉點燃）";
                case "hit_frozen" -> "擊中凍結中的敵人時（用掉凍結）";
                case "hit_shocked" -> "擊中感電中的敵人時（用掉感電）";
                case "hit_bleeding" -> "擊中流血中的敵人時（用掉流血）";
                case "hit_poisoned" -> "擊中中毒的敵人時（用掉中毒）";
                case "hit_elemental" -> "擊中身上有點燃、凍結或感電的敵人時（用掉一種）";
                case "heavy_stun" -> "擊中暈眩中的敵人時";
                case "kill_ignited" -> "打死點燃中的敵人時";
                case "kill_frozen" -> "打死凍結中的敵人時";
                case "kill_shocked" -> "打死感電中的敵人時";
                case "kill_bleeding" -> "打死流血中的敵人時";
                case "kill_poisoned" -> "打死中毒的敵人時";
                case "kill_stunned" -> "打死暈眩中的敵人時";
                case "use" -> "放這個技能時";
                case "crit" -> "暴擊時";
                case "vs_ignited" -> "擊中點燃中的敵人時";
                case "vs_frozen" -> "擊中凍結中的敵人時";
                case "vs_shocked" -> "擊中感電中的敵人時";
                case "vs_chilled" -> "擊中冰緩中的敵人時";
                case "vs_bleeding" -> "擊中流血中的敵人時";
                case "vs_poisoned" -> "擊中中毒的敵人時";
                case "vs_stunned" -> "擊中暈眩中的敵人時";
                case "armour_broken" -> "讓敵人完全破甲時";
                case "stun_applied" -> "讓敵人重暈時";
                case "freeze" -> "讓敵人凍結時";
                case "minion_death" -> "被輔助的技能叫出來的召喚物死掉時（在牠倒下的地方）";
                default -> "條件成立時";
            };
            final String sub = modifier.tags().isEmpty() ? "" : modifier.tags().iterator().next();
            return when + (value >= 100 ? "" : "有 " + number(value) + "% 機率") + "觸發「" + SkillConfig.nameOf(sub) + "」";
        }
        if (stat.equals(StatKeys.SUPPORT_SPIRIT)) {
            return "多保留 " + number(value) + " 精魂";
        }
        final String charge = tw.exilecore.charge.ChargeText.describe(stat, value, tagPrefix(modifier));
        if (charge != null) {
            return charge; // 「擊中時 20% 機率獲得一顆怒星」
        }
        if (stat.startsWith(StatKeys.DAMAGE_CONVERT)) {
            final DamageType type = DamageType.fromTag(stat.substring(StatKeys.DAMAGE_CONVERT.length()));
            return "物理傷害的 " + number(value) + "% 轉換為" + (type == null ? stat : type.displayName()) + "傷害";
        }
        if (stat.startsWith(StatKeys.DAMAGE_ONLY)) {
            final DamageType type = DamageType.fromTag(stat.substring(StatKeys.DAMAGE_ONLY.length()));
            return "只造成" + (type == null ? stat : type.displayName()) + "傷害";
        }
        if (stat.equals(StatKeys.PENETRATION)) {
            return "穿透 " + number(value) + "% " + (modifier.tags().isEmpty() ? "" : tagPrefix(modifier)) + "抗性";
        }
        if (stat.equals(StatKeys.PER_FRENZY)) {
            return "每顆怒星：這個技能更多 " + number(value) + "% 攻擊傷害與 " + number(value) + "% 攻擊速度";
        }
        if (stat.equals(StatKeys.FLAG_RANDOM_ELEMENT)) {
            return "每次施放隨機選擇火焰、冰冷或閃電：物理傷害全部轉換為它，只造成那種傷害";
        }
        if (stat.equals(StatKeys.FLAG_TRINITY)) {
            return "擊中時，打最多的那種元素讓另外兩種元素的共鳴各 +25（最多 50，2 秒沒拿到每秒 -5）；"
                    + "最低的共鳴每 5 點 更多 4% 元素傷害，三種都至少 25 時穿透 15% 元素抗性";
        }
        if (stat.startsWith(StatKeys.DAMAGE_GAIN)) {
            final DamageType type = DamageType.fromTag(stat.substring(StatKeys.DAMAGE_GAIN.length()));
            return "物理傷害的 " + number(value) + "% 額外附加為" + (type == null ? stat : type.displayName()) + "傷害";
        }
        if (stat.startsWith(StatKeys.DAMAGE_VS_AILMENT) && !stat.equals(StatKeys.DAMAGE_VS_RARE) && !stat.equals(StatKeys.DAMAGE_VS_UNIQUE)
                && !stat.equals(StatKeys.DAMAGE_VS_CURSED) && !stat.equals(StatKeys.DAMAGE_VS_LOW_LIFE) && !stat.equals(StatKeys.DAMAGE_VS_STUNNED)) {
            final String word = switch (stat.substring(StatKeys.DAMAGE_VS_AILMENT.length())) {
                case "chilled" -> "冰緩中";
                case "frozen" -> "凍結中";
                case "ignited" -> "點燃中";
                case "shocked" -> "感電中";
                case "poisoned" -> "中毒";
                case "bleeding" -> "流血中";
                default -> null;
            };
            if (word != null) {
                return scaled(modifier, "對" + word + "的敵人的傷害", percent);
            }
        }
        if (stat.startsWith(StatKeys.RESIST_MAX + ".")) {
            final DamageType type = DamageType.fromTag(stat.substring(StatKeys.RESIST_MAX.length() + 1));
            if (type != null) {
                return (value < 0 ? "" : "+") + number(value) + "% 最大" + type.displayName() + "抗性";
            }
        }
        return switch (stat) {
            case StatKeys.DAMAGE -> scaled(modifier, tagPrefix(modifier) + "傷害", percent);
            case StatKeys.ATTACK_SPEED -> scaled(modifier, tagPrefix(modifier) + "攻擊速度", percent);
            case StatKeys.CAST_SPEED -> scaled(modifier, tagPrefix(modifier) + "施法速度", percent);
            case StatKeys.SKILL_AREA -> scaled(modifier, "範圍效果", percent);
            case StatKeys.SKILL_PROJECTILE_SPEED -> scaled(modifier, "投射物速度", percent);
            case StatKeys.SKILL_COST -> scaled(modifier, "魔力消耗", percent);
            case StatKeys.SKILL_PROJECTILES -> "額外 " + number(value) + " 個投射物";
            case StatKeys.SKILL_PIERCE -> "投射物多穿透 " + number(value) + " 個敵人";
            case StatKeys.SKILL_CHAINS -> "多連鎖 " + number(value) + " 次";
            case StatKeys.LEECH_LIFE -> "造成的物理傷害有 " + number(value) + "% 偷取為生命";
            case StatKeys.LEECH_LIFE_ELEMENTAL -> "造成的元素傷害有 " + number(value) + "% 偷取為生命";
            case StatKeys.LEECH_INSTANT -> "偷取有 " + number(value) + "% 立即生效（一下最多回 6% 最大生命）";
            case StatKeys.SKILL_DURATION -> scaled(modifier, "持續時間", percent);
            case StatKeys.SKILL_COOLDOWN -> scaled(modifier, "冷卻時間", percent);
            case StatKeys.SKILL_ADDED_COOLDOWN -> "技能有 " + number(value) + " 秒冷卻時間（本來就有冷卻的不能掛）";
            case StatKeys.SKILL_FORK -> "投射物第一次打中時分裂成兩發";
            case StatKeys.FLAG_RETURNING -> "投射物停下來之後飛回你身邊，路上再打一次";
            case StatKeys.FLAG_ECHO -> "法術施放後再自動重複一次";
            case StatKeys.SKILL_REPEATS -> "近戰攻擊按一次自動再打 " + number(value) + " 下（魔力只扣一次）";
            case StatKeys.STRIKE_TARGETS -> "打擊技能多打 " + number(value) + " 個附近的敵人";
            case StatKeys.MELEE_SPLASH -> "近戰打擊打中時，目標周圍的敵人受到這一下 " + number(value) + "% 的傷害";
            case StatKeys.FLAG_SPELL_TOTEM -> "法術改由圖騰施放（放下圖騰，它每隔一段時間對附近的敵人放一次）";
            case StatKeys.FLAG_TRAP -> "法術改成丟出去的陷阱（敵人靠近才觸發）";
            case StatKeys.SKILL_TOTEMS -> "同時最多多 " + number(value) + " 個圖騰";
            case StatKeys.SKILL_MINIONS -> "同時最多多 " + number(value) + " 隻召喚物";
            case StatKeys.AURA_EFFECT -> scaled(modifier, "光環效果", percent);
            case StatKeys.CURSE_EFFECT -> scaled(modifier, "詛咒與印記效果", percent);
            case StatKeys.THORNS_DAMAGE -> scaled(modifier, "荊棘傷害", percent);
            case StatKeys.WARCRY_EFFECT -> scaled(modifier, "戰吼效果", percent);
            case StatKeys.SKILL_KNOCKBACK -> modifier.type() == tw.exilecore.stats.ModType.FLAT ? "擊中時把敵人擊退（+" + number(value) + " 格）"
                    : scaled(modifier, "擊退距離", percent);
            case StatKeys.AURA_RESERVATION -> modifier.value() < 0 && modifier.type() == tw.exilecore.stats.ModType.INCREASED
                    ? "精魂技能的保留效率增加 " + percent + "%"
                    : scaled(modifier, "精魂保留", percent);
            case StatKeys.WARCRY_EXERTS -> "戰吼多強化 " + number(value) + " 次攻擊";
            case StatKeys.FLAG_GENEROSITY -> "光環不影響你自己，隊友與召喚物得到的效果更強";
            case StatKeys.ARMOUR -> scaled(modifier, "護甲", percent);
            case StatKeys.EVASION -> scaled(modifier, "閃避", percent);
            case StatKeys.ES_MAX -> scaled(modifier, "最大能量護盾", percent);
            case StatKeys.MANA_MAX -> scaled(modifier, "最大魔力", percent);
            case StatKeys.MANA_REGEN -> modifier.type() == tw.exilecore.stats.ModType.FLAT ? "每秒回復 " + number(value) + " 魔力"
                    : scaled(modifier, "魔力回復", percent);
            case StatKeys.ES_RECHARGE -> scaled(modifier, "能量護盾回充速度", percent);
            case StatKeys.TOTEM_LIFE -> scaled(modifier, "圖騰生命", percent);
            case StatKeys.SPIRIT_MAX -> modifier.type() == tw.exilecore.stats.ModType.FLAT ? "+" + number(value) + " 最大精魂"
                    : scaled(modifier, "精魂", percent);
            case StatKeys.FLAG_ELEMENTAL_AILMENT_IMMUNE -> "免疫元素異常（點燃、冰緩、凍結、感電）";
            case StatKeys.TOTEM_VENGEFUL -> "圖騰被打（或你在圖騰 10 格內被打）時，往打的敵人丟一團怨燼：造成圖騰最大生命 " + number(value)
                    + "% 的火焰傷害（基礎值，冷卻 1 秒）";
            case StatKeys.FLAG_TOTEM_TAUNT -> "圖騰不施法，改成嘲諷附近的怪物過來打它（Boss 只被引走走位與普攻，每次 4 秒）";
            case StatKeys.FLAG_SEARING_BOND -> "圖騰不施法，改成你與圖騰、圖騰與圖騰之間連起火鏈，每 0.5 秒燒碰到的敵人";
            case StatKeys.FLAG_TOTEM_DETONATE -> "圖騰放到上限時全部引爆，每根原地炸一圈火（引爆幾根，每一下更多 30%）";
            case StatKeys.ES_DELAY -> scaled(modifier, "能量護盾開始回充的等待時間", percent);
            case StatKeys.FLAG_ALWAYS_HIT -> "攻擊必定命中";
            case StatKeys.FLAG_CULLING -> "擊中後敵人生命低於一定比例直接倒下（普通 30%、魔法 20%、稀有 10%、傳奇與 Boss 5%）";
            case StatKeys.LIFE_REGEN -> "每秒回復 " + number(value) + " 生命";
            case StatKeys.ACCURACY -> scaled(modifier, "命中", percent);
            case StatKeys.RESIST_FIRE -> "+" + number(value) + "% 火焰抗性";
            case StatKeys.RESIST_COLD -> "+" + number(value) + "% 冰冷抗性";
            case StatKeys.RESIST_LIGHTNING -> "+" + number(value) + "% 閃電抗性";
            case StatKeys.RESIST_CHAOS -> "+" + number(value) + "% 混沌抗性";
            case StatKeys.AILMENT_THRESHOLD -> scaled(modifier, "異常門檻", percent);
            case StatKeys.DAMAGE_ADDED_MIN -> "附加至少 " + number(value) + " " + tagPrefix(modifier) + "傷害";
            case StatKeys.DAMAGE_ADDED_MAX -> "附加最多 " + number(value) + " " + tagPrefix(modifier) + "傷害";
            case StatKeys.LIFE_MAX -> scaled(modifier, tagPrefix(modifier) + "最大生命", percent);
            case StatKeys.LIFE_REGEN_PERCENT -> tagPrefix(modifier) + "每秒回復 " + number(value) + "% 最大生命";
            case StatKeys.MOVEMENT_SPEED -> scaled(modifier, tagPrefix(modifier) + "移動速度", percent);
            case StatKeys.CRIT_CHANCE -> scaled(modifier, tagPrefix(modifier) + "暴擊率", percent);
            case StatKeys.CRIT_MULTI -> tagPrefix(modifier) + "暴擊傷害 +" + number(value) + "%";
            case StatKeys.LIFE_ON_HIT -> tagPrefix(modifier) + "每次擊中回復 " + number(value) + " 生命";
            case StatKeys.DAMAGE_DOUBLE -> "擊中時 " + number(value) + "% 機率造成雙倍傷害";
            case StatKeys.SKILL_LEVEL -> "+" + number(value) + " " + tagPrefix(modifier) + "技能等級";
            case StatKeys.IGNITE_CHANCE -> chance(modifier, "點燃機率", percent);
            case StatKeys.SHOCK_CHANCE -> chance(modifier, "感電機率", percent);
            case StatKeys.IGNITE_MAGNITUDE -> scaled(modifier, "點燃傷害", percent);
            case StatKeys.IGNITE_DURATION -> scaled(modifier, "點燃持續時間", percent);
            case StatKeys.CHILL_EFFECT -> scaled(modifier, "冰緩效果", percent);
            case StatKeys.CHILL_DURATION -> scaled(modifier, "冰緩持續時間", percent);
            case StatKeys.FREEZE_BUILDUP -> scaled(modifier, "凍結累積", percent);
            case StatKeys.FREEZE_DURATION -> scaled(modifier, "凍結持續時間", percent);
            case StatKeys.SHOCK_EFFECT -> scaled(modifier, "感電效果", percent);
            case StatKeys.SHOCK_DURATION -> scaled(modifier, "感電持續時間", percent);
            case StatKeys.BLEED_CHANCE -> chance(modifier, "流血機率", percent);
            case StatKeys.BLEED_MAGNITUDE -> scaled(modifier, "流血傷害", percent);
            case StatKeys.BLEED_DURATION -> scaled(modifier, "流血持續時間", percent);
            case StatKeys.POISON_CHANCE -> chance(modifier, "中毒機率", percent);
            case StatKeys.POISON_MAGNITUDE -> scaled(modifier, "中毒傷害", percent);
            case StatKeys.POISON_DURATION -> scaled(modifier, "中毒持續時間", percent);
            case StatKeys.STUN_BUILDUP -> scaled(modifier, "暈眩累積", percent);
            case StatKeys.ARMOUR_BREAK -> modifier.type() == tw.exilecore.stats.ModType.FLAT
                    ? "擊中時破甲：破掉物理傷害 " + number(value) + "% 的護甲（普通怪 ×3、魔法 ×2；破到 0 = 完全破甲 12 秒，物理擊中傷害增加 20%）"
                    : scaled(modifier, "破甲", percent);
            case StatKeys.FLAG_CRUSH_PRIMED -> "擊中蓄勢待暈的敵人時直接重暈";
            case StatKeys.CRIT_BONUS -> scaled(modifier, tagPrefix(modifier) + "暴擊傷害加成", percent);
            case StatKeys.ARMOUR_ELEMENTAL -> "護甲的 " + number(value) + "% 也套用在元素傷害上";
            case StatKeys.PHYS_REDUCTION -> number(value) + "% 額外物理傷害減免";
            case StatKeys.CRIT_BONUS_TAKEN -> "擊中你的暴擊傷害加成" + (value < 0 ? "減少 " : "增加 ") + percent + "%";
            case StatKeys.LIFE_PER_KILL -> "每擊殺一個敵人回復 " + number(value) + " 生命";
            case StatKeys.ATTR_CHOICE -> "+" + number(value) + " 任一屬性";
            case StatKeys.MANA_PER_KILL -> "每擊殺一個敵人回復 " + number(value) + " 魔力";
            case StatKeys.STUN_THRESHOLD -> scaled(modifier, "暈眩門檻", percent);
            case StatKeys.STUN_DURATION -> scaled(modifier, "暈眩持續時間", percent);
            case StatKeys.AVOID_STUN -> number(value) + "% 機率避免暈眩";
            case StatKeys.ELECTROCUTE_CHANCE -> chance(modifier, "電擊機率", percent);
            case StatKeys.ELECTROCUTE_BUILDUP -> scaled(modifier, "電擊累積", percent);
            case StatKeys.ELECTROCUTE_DURATION -> scaled(modifier, "電擊持續時間", percent);
            case StatKeys.AVOID_ELECTROCUTE -> number(value) + "% 機率避免電擊";
            case StatKeys.AVOID_BLEED -> number(value) + "% 機率避免流血";
            case StatKeys.AVOID_POISON -> number(value) + "% 機率避免中毒";
            case StatKeys.AILMENT_DURATION -> scaled(modifier, "異常狀態持續時間", percent);
            case StatKeys.SKILL_RECOIL -> "放完往後跳開";
            case StatKeys.FLAG_POWER_RAISE -> "附近沒有屍體時改吃一顆銳星：叫出一隻強化殭屍（生命更多 40%、傷害更多 100%、移動更快 25%）再加兩隻一般的";
            case StatKeys.FLAG_TOTEM_LINKED -> "圖騰每隔一段時間在附近的敵人身邊叫出一個英靈，用技能面板上下一格的錘子攻擊技能打一次";
            case StatKeys.FLAG_SUMMON_ON_FIRE -> "你自己放火焰法術時叫出焚魂（範圍法術兩隻）";
            case StatKeys.FLAG_NOVA_FROM_FROSTBOLT -> "你的霜鑽還在飛的時候，從霜鑽的位置爆開";
            case StatKeys.FLAG_MARK_CRIT -> "你對被標記的敵人打出暴擊時印記用掉，給你一顆怒星";
            case StatKeys.FLAG_NO_AILMENTS -> "不能造成元素異常（點燃、冰緩、凍結、感電）";
            case StatKeys.AVOID_IGNITE -> number(value) + "% 機率避免被點燃";
            case StatKeys.AVOID_CHILL -> number(value) + "% 機率避免被冰緩";
            case StatKeys.AVOID_FREEZE -> number(value) + "% 機率避免被凍結";
            case StatKeys.AVOID_SHOCK -> number(value) + "% 機率避免被感電";
            case StatKeys.RESIST_MAX -> (value < 0 ? "" : "+") + number(value) + "% 所有抗性上限";
            case "drop.quantity" -> scaled(modifier, "物品掉落數量", percent);
            case StatKeys.EXPOSURE_CHANCE -> "擊中時 " + number(value) + "% 機率讓敵人曝露（打出的元素抗性 −20%，4 秒）";
            case StatKeys.EXPOSURE_EFFECT -> scaled(modifier, "曝露效果", percent);
            case StatKeys.PIN_CHANCE -> "投射物攻擊擊中時 " + number(value) + "% 機率定身敵人";
            case StatKeys.DAZE_CHANCE -> "擊中時 " + number(value) + "% 機率讓敵人眩暈（傷害更少 15%、暈眩門檻減少 30%，4 秒）";
            case StatKeys.RAGE_ON_HIT -> "近戰攻擊擊中時獲得 " + number(value) + " 點怒氣";
            case StatKeys.RAGE_MAX -> "+" + number(value) + " 怒氣上限";
            case StatKeys.RAGE_EFFECT -> scaled(modifier, "怒氣效果", percent);
            case StatKeys.DAMAGE_VS_RARE -> scaled(modifier, "對稀有與傳奇怪物的傷害", percent);
            case StatKeys.ONKILL_SPEED -> "擊殺後 4 秒內增加 " + number(value) + "% 攻擊與施法速度";
            case StatKeys.ONKILL_DAMAGE -> "擊殺後 4 秒內增加 " + number(value) + "% 傷害";
            case StatKeys.CHANNEL_STAGES -> "持續施放（按住）：每放一次加一層蓄力，最多 " + number(value) + " 層";
            case StatKeys.CHANNEL_MORE -> "每層蓄力更多 " + number(value) + "% 傷害";
            case StatKeys.TRIGGER_CRIT -> "攻擊暴擊時自動施放這個法術（不花魔力，間隔至少 0.75 秒）";
            case StatKeys.TRIGGER_HIT_TAKEN -> "被敵人擊中時自動施放這個法術（不花魔力，間隔至少 0.75 秒）";
            case StatKeys.TRIGGER_KILL -> "擊殺敵人時自動施放這個法術（不花魔力，間隔至少 0.75 秒）";
            case StatKeys.TRIGGER_AILMENT -> "讓敵人點燃、冰緩、凍結或感電時自動施放這個法術（不花魔力，間隔至少 0.75 秒）";
            case StatKeys.LIFE_ON_CULL -> "斷命打死敵人時回復 " + number(value) + "% 最大生命";
            case StatKeys.MANA_ON_CULL -> "斷命打死敵人時回復 " + number(value) + "% 最大魔力";
            case StatKeys.FLAG_PROJECTILE_NOVA -> "投射物改成往四周一圈射出";
            case StatKeys.FLAG_DODGE_CALTROPS -> "翻滾時在腳下撒一片鐵蒺";
            case StatKeys.FLAG_MIRAGE -> "弓的攻擊過一下由身旁的幻影再射一次（傷害少 60%）";
            case StatKeys.SEAL_MAX -> "平常會慢慢蓄封印（最多 " + number(value) + " 個），放的時候全部解開";
            case StatKeys.SEAL_INTERVAL -> "每 " + number(value) + " 秒蓄一個封印";
            case StatKeys.SEAL_REPEAT -> "每解開一個封印，自動多放 " + number(value) + " 次（傷害少一半）";
            case StatKeys.SEAL_AREA -> "每解開一個封印，這一次範圍增加 " + number(value) + "%";
            case StatKeys.SEAL_PROJECTILES -> "每解開一個封印，這一次多射 " + number(value) + " 發投射物";
            case StatKeys.CRYSTAL_COUNT -> "留下 " + number(value) + " 顆冰晶（擊中冰晶旁邊的敵人會打到冰晶，打碎時炸出一圈冰）";
            case StatKeys.CRYSTAL_HITS -> modifier.type() == tw.exilecore.stats.ModType.FLAT
                    ? (value >= 0 ? "冰晶要多打 " + number(value) + " 下才碎" : "冰晶少打 " + number(-value) + " 下就碎（最少一下）")
                    : scaled(modifier, "冰晶要打的下數", percent);
            case StatKeys.FLAG_HOARFROST -> "冰晶周圍一直結霜，冰傷周圍的敵人";
            case StatKeys.FLAG_CRYSTAL_SHARDS -> "冰晶碎掉時往四周噴出冰片";
            case StatKeys.FLAG_VERGLAS -> "打碎冰晶時你 6 秒內物理傷害的 15% 額外附加為冰冷傷害";
            case StatKeys.MANA_ON_MINION_DEATH -> "被輔助的技能叫出來的召喚物死掉時，回復 " + number(value) + "% 最大魔力";
            case StatKeys.MINION_DEATH_HEAL -> "被輔助的技能叫出來的召喚物死掉時，3 格內你的其他召喚物回復 " + number(value) + "% 最大生命並清掉異常";
            case StatKeys.FLASK_KILL_LIFE -> "被輔助的技能打死敵人時，生命藥劑拿到的充能更多 " + number(value) + "%";
            case StatKeys.FLASK_KILL_MANA -> "被輔助的技能打死敵人時，魔力藥劑拿到的充能更多 " + number(value) + "%";
            case StatKeys.CHARM_KILL -> "被輔助的技能打死敵人時，護符拿到的充能更多 " + number(value) + "%";
            case StatKeys.FLAG_NO_BOUNCE -> "榴彈碰到地面就黏在原地，不再彈跳";
            case StatKeys.GRENADE_FUSE -> scaled(modifier, "榴彈引信時間", percent);
            case StatKeys.HERALD_EFFECT -> scaled(modifier, "捷光環效果", percent);
            case StatKeys.DAMAGE_RANDOM -> "每一下的傷害在 " + number(100 - value) + "%～" + number(100 + value * 1.5) + "% 之間隨機";
            case StatKeys.CRIT_SUPER -> "暴擊時有 " + number(value) + "% 機率變成超暴擊（暴擊傷害加成變兩倍）";
            case StatKeys.INFUSION_GAIN -> "放完獲得 " + number(value) + " 層跟技能同元素的灌注（火、冰、電；身上每種最多 2 層，10 秒沒拿到就散掉）";
            case StatKeys.INFUSION_GAIN_FIRE -> "放完獲得 " + number(value) + " 層火焰灌注";
            case StatKeys.INFUSION_GAIN_COLD -> "放完獲得 " + number(value) + " 層冰冷灌注";
            case StatKeys.INFUSION_GAIN_LIGHTNING -> "放完獲得 " + number(value) + " 層閃電灌注";
            case StatKeys.INFUSION_MAX -> "每種灌注最多多 " + number(value) + " 層";
            case StatKeys.INFUSION_CONSUME -> "放的時候用掉身上每種灌注各一層";
            case StatKeys.INFUSION_MORE -> "每用掉一層灌注，這一下更多 " + number(value) + "% 傷害";
            case StatKeys.INFUSION_EXTRA -> "用掉哪種灌注，這一下就獲得 " + number(value) + "% 傷害為額外的那種元素傷害";
            case StatKeys.COMBO_EXTRA_CHANCE -> "打中累積連擊時有 " + number(value) + "% 機率多累積一層";
            case StatKeys.COMBO_DURATION -> scaled(modifier, "連擊持續時間", percent);
            case StatKeys.DAMAGE_PER_COMBO -> "每層連擊更多 " + number(value) + "% 傷害（最多 10 層）";
            case StatKeys.COMBO_FINISHER -> "放的時候有 3 層以上連擊就全部用掉，這一下更多 " + number(value) + "% 傷害";
            case StatKeys.RAGEFORGED -> "放的時候有 10 點怒氣就用掉，這一下更多 " + number(value) + "% 傷害";
            case StatKeys.CROSSBOW_CLIP -> (value >= 0 ? "+" : "") + number(value) + " 弩的彈匣容量";
            case StatKeys.CROSSBOW_RELOAD -> scaled(modifier, "弩的裝填速度", percent);
            case StatKeys.CROSSBOW_AMMO_SAVE -> "弩攻擊有 " + number(value) + "% 機率不消耗弩箭";
            case StatKeys.FLAG_RELOAD_ON_STUN -> "擊中暈眩中的敵人時立刻把這個弩技能裝滿";
            case StatKeys.FLAG_RELOAD_ALL -> "放了就把所有弩技能立刻裝滿";
            case StatKeys.TRIGGER_DODGE -> "翻滾時自動施放這個法術（不花魔力，間隔至少 0.75 秒）";
            case StatKeys.TRIGGER_MINION_DEATH -> "你的召喚物死掉時，在牠倒下的地方自動施放這個法術（不花魔力，間隔至少 0.75 秒）";
            case StatKeys.DAMAGE_VS_LOW_LIFE -> "對生命 35% 以下的敵人增加 " + number(value) + "% 擊中傷害";
            case StatKeys.DAMAGE_VS_CURSED -> scaled(modifier, "對被詛咒的敵人的傷害", percent);
            case StatKeys.DAMAGE_CLOSE -> scaled(modifier, "對近身（3.5 格內）敵人的傷害", percent);
            case StatKeys.DAMAGE_VS_FULL_LIFE -> scaled(modifier, "對滿血敵人的傷害", percent);
            case StatKeys.CRIT_VS_FULL_LIFE -> scaled(modifier, "對滿血敵人的暴擊率", percent);
            case StatKeys.DAMAGE_HIGH_MANA -> scaled(modifier, "魔力 90% 以上時的傷害", percent);
            case StatKeys.DAMAGE_CLASH -> "生命百分比比敵人低時更多 " + number(value) + "% 傷害，比敵人高時更少 " + number(value / 2) + "%";
            case StatKeys.DAMAGE_VS_UNIQUE -> scaled(modifier, "對傳奇怪物與 Boss 的傷害", percent);
            case StatKeys.DEFLECTION -> scaled(modifier, "偏斜值", percent);
            case StatKeys.DEFLECTION_FROM_EVASION -> "獲得閃避值 " + number(value) + "% 的偏斜值";
            case "charm.slots" -> "+" + number(value) + " 護符欄位";
            case "flag.freeze_immune" -> "免疫凍結";
            case "thorns.min" -> "荊棘：被近戰擊中時反彈至少 " + number(value) + " 物理傷害";
            case "thorns.max" -> "荊棘：被近戰擊中時反彈最多 " + number(value) + " 物理傷害";
            default -> {
                // 2026-10：不認得的交給昇華的說明（那邊的屬性比較齊）；都不認得也不要秀出英文代號
                if (tw.exilecore.ascendancy.StatLines.knows(stat)) {
                    yield tw.exilecore.ascendancy.StatLines.describe(modifier);
                }
                yield "特殊效果（" + number(value) + "）";
            }
        };
    }

    /** 一串修飾器的說明：附加傷害的最小、最大（同標籤）合成一句「附加 4–22 火焰傷害」。 */
    public static List<String> describeAll(final List<Modifier> modifiers) {
        final List<String> lines = new ArrayList<>();
        final java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int i = 0; i < modifiers.size(); i++) {
            if (used.contains(i)) {
                continue;
            }
            final Modifier modifier = modifiers.get(i);
            if (modifier.stat().equals(StatKeys.DAMAGE_ADDED_MIN) && modifier.type() == tw.exilecore.stats.ModType.FLAT) {
                int pair = -1;
                for (int j = 0; j < modifiers.size() && pair < 0; j++) {
                    final Modifier other = modifiers.get(j);
                    if (!used.contains(j) && other.stat().equals(StatKeys.DAMAGE_ADDED_MAX) && other.tags().equals(modifier.tags())) {
                        pair = j;
                    }
                }
                if (pair >= 0) {
                    used.add(pair);
                    lines.add("附加 " + number(modifier.value()) + "–" + number(modifiers.get(pair).value()) + " " + tagPrefix(modifier) + "傷害");
                    continue;
                }
            }
            lines.add(describe(modifier));
        }
        return lines;
    }

    /** 機率：FLAT 是直接加（點燃機率 +25%），INCREASED 是增加（增加 25% 點燃機率）。 */
    private static String chance(final Modifier modifier, final String what, final String percent) {
        if (modifier.type() == tw.exilecore.stats.ModType.FLAT) {
            return what + " " + (modifier.value() >= 0 ? "+" : "") + number(modifier.value()) + "%";
        }
        return scaled(modifier, what, percent);
    }

    /** 增加／減少、更多／更少、附加。 */
    private static String scaled(final Modifier modifier, final String what, final String percent) {
        final boolean up = modifier.value() >= 0;
        return switch (modifier.type()) {
            case MORE -> percent + "% " + (up ? "更多" : "更少") + what;
            case INCREASED -> (up ? "增加 " : "減少 ") + percent + "% " + what;
            case FLAT -> what + " " + (up ? "+" : "") + number(modifier.value());
        };
    }

    /** 「近戰物理」「投射物」這種傷害前綴。 */
    private static String tagPrefix(final Modifier modifier) {
        final List<String> names = new ArrayList<>();
        for (final String tag : TAG_ORDER) {
            if (modifier.tags().contains(tag)) {
                names.add(tagName(tag));
            }
        }
        for (final String tag : modifier.tags()) {
            if (!TAG_ORDER.contains(tag)) {
                names.add(tagName(tag));
            }
        }
        return String.join("", names);
    }

    /** 整數不帶小數點，其他留一位。 */
    static String number(final double value) {
        final double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.format(Locale.ROOT, "%.1f", rounded);
    }
}
