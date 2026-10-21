package tw.exilecore.stats;

import java.util.LinkedHashMap;
import java.util.Map;

/** 昇華招牌天賦（2026-10）的說明文字：屬性 → 一行說明（昇華天賦樹、/stats 共用）。純資料。 */
public final class SignatureText {

    public static final Map<String, String> TEXT = new LinkedHashMap<>();

    static {
        TEXT.put(StatKeys.SIG_LAST_STAND, "背水：生命低於 35% 時，8 秒內受到的傷害減半（冷卻 30 秒）");
        TEXT.put(StatKeys.SIG_BLOOD_RAGE, "血怒：每失去 1% 生命，傷害更多 0.5%（瀕死時最多更多 50%）");
        TEXT.put(StatKeys.SIG_FAR_SHOT, "遠星：投射物打中 5 格外的敵人時，每遠 1 格傷害更多 2%（最多 40%）");
        TEXT.put(StatKeys.SIG_WINDWALK, "御風：移動中受到的傷害更少 20%、造成的傷害更多 10%");
        TEXT.put(StatKeys.SIG_WILDFIRE, "燎原：對點燃中的敵人傷害更多 30%");
        TEXT.put(StatKeys.SIG_WINTER_TOMB, "冬葬：對冰緩中的敵人傷害更多 25%、凍結中的更多 50%");
        TEXT.put(StatKeys.SIG_PLAGUE_LORD, "瘟主：敵人身上每一層中毒，你對牠的傷害更多 6%（最多 30%）");
        TEXT.put(StatKeys.SIG_BLOOD_FEAST, "血宴：法術擊中回復造成傷害 3% 的生命（每下最多最大生命的 2%）；生命九成以上時法術傷害更多 15%");
        TEXT.put(StatKeys.SIG_VERDICT, "斷罪：對生命低於 50% 的敵人傷害更多 35%");
        TEXT.put(StatKeys.SIG_LEGION, "軍陣：12 格內每個隊友或你的召喚物，傷害更多 6%、受到的傷害更少 4%（最多算 5 個）");
        TEXT.put(StatKeys.SIG_THUNDER_SOUL, "雷魂：暴擊時回復 3% 最大魔力與 3% 最大能量護盾");
        TEXT.put(StatKeys.SIG_VOID_STEP, "虛步：閃避或格擋之後 4 秒內，下一次擊中傷害更多 60%");
        TEXT.put(StatKeys.SIG_GODSLAYER, "弒神：對稀有、傳奇怪物傷害更多 25%，對 Boss 更多 35%");
        TEXT.put(StatKeys.SIG_SACRIFICE, "獻祭：擊殺時回復 4% 最大生命，5 秒內傷害更多 20%");
        // ---- 2026-10 PoE2 昇華（數值固定，寫在說明裡）----
        TEXT.put(StatKeys.FLAG_STONE_SKIN, "胸甲的護甲更多 50%");
        TEXT.put(StatKeys.FLAG_THORNS_ANY_HIT, "荊棘也會被遠程攻擊與法術擊中觸發（反彈給出手的怪物）");
        TEXT.put(StatKeys.FLAG_HULKING_FORM, "天賦樹小點的效果增加 50%");
        TEXT.put(StatKeys.DAMAGE_VS_STUNNED, "對重暈中的敵人傷害更多 25%");
        // 2026-10-21：對低血的敵人（damage.vs_low_life）是數值，不是開關：改在 StatLines 照加總寫（補刀＋斬首＝70%）
        TEXT.put(StatKeys.FLAG_IMPLODING, "你造成的完全破甲讓敵人受到的所有擊中傷害增加（不只物理）");
        TEXT.put(StatKeys.BLOCK_MAX, "格擋上限 75%");
        TEXT.put(StatKeys.BLOCK_TAKEN, "被格擋的擊中，你仍受到 20% 傷害");
        TEXT.put(StatKeys.FLAG_RENLY, "盾的基礎格擋率改成 35%");
        TEXT.put(StatKeys.FLAG_STAY_LIGHT, "閃避最多 50%、物理傷害減免最多 50%");
        TEXT.put(StatKeys.FLAG_AMAZON_CRIT, "攻擊命中率可以超過 100%，超過部分的 25% 變成額外暴擊率");
        TEXT.put(StatKeys.FLAG_STALKING_PANTHER, "頭盔、護腿、鞋子的閃避加倍，胸甲的閃避減半");
        TEXT.put(StatKeys.FLAG_LEECH_ELEMENTAL_ALL, "生命偷取也照元素傷害算（跟物理一樣的偷取率）");
        TEXT.put(StatKeys.FLAG_LEECH_CHAOS, "生命偷取也照混沌傷害算（跟物理一樣的偷取率）");
        TEXT.put(StatKeys.LEECH_SPELL, "法術傷害的 20% 偷取為生命");
        TEXT.put(StatKeys.FLAG_MYSTIC_ATTUNEMENT, "戒指與項鍊給的加成增加 25%");
        TEXT.put(StatKeys.LIFE_RECOVERY_PER_MISSING, "每少 5% 生命，生命回復速度增加 10%");
        TEXT.put(StatKeys.EVASION_ELEMENTAL, "閃避的 50% 也像護甲一樣減少元素傷害");
        TEXT.put(StatKeys.FLAG_UNSLOWABLE, "不會被冰緩減速");
        TEXT.put(StatKeys.FLAG_DOUBLE_POISON, "敵人身上你的中毒疊加上限加倍");
        TEXT.put(StatKeys.FLAG_SUNDER, "暴擊無視敵人非負的元素抗性");
        TEXT.put(StatKeys.FLAG_PROTECT_HARM, "護甲減傷照護甲＋閃避的總和算");
        TEXT.put(StatKeys.FLAG_CHAOS_RES_DOUBLE, "混沌抗性加倍");
        TEXT.put(StatKeys.FLAG_ALL_CHILL, "擊中的所有傷害都算進冰緩幅度");
        TEXT.put(StatKeys.FLAG_ALL_SHOCK, "擊中的所有傷害都算進感電機率");
        TEXT.put(StatKeys.FLAG_ALL_IGNITE, "擊中的所有傷害都算進點燃機率與點燃傷害");
        TEXT.put(StatKeys.FLAG_PHASED_FORM, "受到的傷害更少 30%；被擊中 4 秒後，再受到那一擊 30% 的傷害");
        TEXT.put(StatKeys.FLAG_GRASPING_WOUNDS, "被擊中扣的生命有 25% 先擋下來，改成 4 秒內慢慢扣");
        TEXT.put(StatKeys.BEIDAT_HAND, "每 8 最大生命 +1 最大能量護盾；保留 25% 生命");
        TEXT.put(StatKeys.BEIDAT_GAZE, "每 6 最大生命 +1 最大魔力；保留 25% 生命");
        TEXT.put(StatKeys.BEIDAT_WILL, "每 25 最大生命 +1 精魂；保留 25% 生命");
        TEXT.put(StatKeys.SELF_IGNITE_ON_CRIT, "暴擊時你自己著火 4 秒：每秒受到最大生命與能量護盾 15% 的火焰傷害");
        TEXT.put(StatKeys.FLAG_SPELL_BASE_CRIT, "法術的基礎暴擊率是 15%");
        TEXT.put(StatKeys.CRIT_BONUS_PER_LIFE, "每 50 目前生命，增加 1% 暴擊傷害加成");
        TEXT.put(StatKeys.FLAG_CRIMSON_POWER, "胸甲上的能量護盾有多少，最大生命就多加多少（能量護盾本身不變）");
        TEXT.put(StatKeys.FLAG_SANGUIMANCY, "技能另外扣跟魔力消耗一樣多的生命");
        TEXT.put(StatKeys.KILL_EXPLODE_CHANCE, "殺死的敵人 10% 機率爆炸，造成牠最大生命 100% 的傷害");
        TEXT.put(StatKeys.FLAG_CRUSH_PRIMED, "你的擊中是粉碎打擊：蓄勢待暈的敵人被打中直接重暈");
        TEXT.put(StatKeys.FLAG_CULLING, "撲殺：擊中後生命很低的敵人直接倒下");
        TEXT.put(StatKeys.FLAG_ETERNAL_YOUTH, "生息輪迴：生命像能量護盾一樣回充，能量護盾不回充");
        TEXT.put(StatKeys.FLAG_ELDRITCH_BATTERY, "魂池逆流：能量護盾全部變成最大魔力");
        TEXT.put(StatKeys.FLAG_CHAOS_IMMUNE, "無垢之軀：免疫混沌傷害，最大生命變成 1");
        TEXT.put("resist.max.chaos", "+10% 最大混沌抗性");
        TEXT.put("damage.extra.lightning", "獲得 10% 傷害為額外閃電傷害");
        TEXT.put("damage.extra.cold", "獲得 10% 傷害為額外冰冷傷害");
        TEXT.put("damage.extra.chaos", "獲得 4% 傷害為額外混沌傷害");
    }

    private SignatureText() {
    }
}
