package tw.exilecore.stats;

import java.util.Map;

/**
 * 2026-10-26：PoE2 0.5 天賦樹要用的「系統」標籤與屬性鍵（夥伴、強化攻擊、戰旗、招架、引導、觸發、危害、植物、引爆、弩台、
 * 存在範圍內的友軍、戰星補滿、殘影、灌注…）。照 PoE2 的意思讓天賦真的生效；不放進 {@link StatKeys}／{@link Tags}，方便合併。
 * <p>
 * 標籤寫在 skills.yml 的技能上（或程式在那一下補上，例如戰吼強化的攻擊、主要寶石觸發的法術），天賦的修飾器帶同樣的標籤才吃得到。
 */
public final class TreeMechanicKeys {

    private TreeMechanicKeys() {
    }

    // ---- 標籤 ----
    /** 夥伴（PoE2 Companion：馴服的野獸、狼群）：技能帶這個標籤，叫出來的召喚物吃「夥伴…」的修飾器（[minion, companion]）。 */
    public static final String COMPANION = "companion";
    /** 被強化的攻擊（PoE2 Empowered：戰吼強化的那幾下）：程式在那一下補上。 */
    public static final String EMPOWERED = "empowered";
    /** 戰旗（PoE2 Banner）。 */
    public static final String BANNER = "banner";
    /** 招架（PoE2 Parry）：招架本身與招架橫掃。 */
    public static final String PARRY = "parry";
    /** 引導技能（PoE2 Channelling）。 */
    public static final String CHANNELLING = "channelling";
    /** 被觸發的法術（PoE2 Triggered：主要寶石觸發的插槽法術）：程式在那一下補上。 */
    public static final String TRIGGERED = "triggered";
    /** Invocation 類主要寶石（skills.yml 已經有）；它觸發的法術也補上（PoE2 Invocated Spells）。 */
    public static final String INVOCATION = "invocation";
    /** 危害（PoE2 Hazard：鐵蒺、矛陣、冰碎片…放在地上等敵人踩的）。 */
    public static final String HAZARD = "hazard";
    /** 植物技能（PoE2 Plant）。 */
    public static final String PLANT = "plant";
    /** 引爆技能（PoE2 Detonator）。 */
    public static final String DETONATOR = "detonator";
    /** 弩台（PoE2 Ballista：弩的圖騰）。 */
    public static final String BALLISTA = "ballista";
    /** 祭品技能（PoE2 Offering）。 */
    public static final String OFFERING = "offering";
    /**
     * 2026-10-30 照 PoE2（offering_life_+%）：INCREASED 祭品骨柱的最大生命（MinionService.spike 讀；骨柱是召喚物，召喚物生命的加成也吃）。
     * 這次之前骨柱沒有生命，天賦樹與珠寶的「祭品的最大生命」換成一半的祭品持續時間。
     */
    public static final String OFFERING_LIFE = "offering.life";
    /** 2026-10-30 照 PoE2 Bone Offering：FLAT 骸骨祭的護盾記號（有的召喚物吸收第一下超過最大生命 25% 的擊中，然後護盾爆掉）。 */
    public static final String OFFERING_BONE_SHIELD = "offering.bone_shield";
    /**
     * 在你存在範圍（臨在，{@code tw.exilecore.combat.Presence}）內的友軍：「存在範圍內的友軍…」的修飾器寫 [minion, in_presence]，
     * 召喚物在範圍內時才給牠（MinionService 每 2 秒重算時看距離）。隊友（玩家）還沒有做。
     */
    public static final String IN_PRESENCE = "in_presence";

    // ---- 屬性鍵 ----
    /** INCREASED：你造成的「被招架」減益效果（PoE2 Parried Debuff Magnitude；基礎受到的攻擊傷害增加 50%）。 */
    public static final String PARRIED_EFFECT = "parry.parried_effect";
    /** INCREASED：「被招架」減益持續時間（基礎 2 秒）。 */
    public static final String PARRIED_DURATION = "parry.parried_duration";
    /** FLAT %：獲得某種戰星時，改成直接補到上限的機率；後面接戰星代號（charge.fill.power）。 */
    public static final String CHARGE_FILL = "charge.fill.";
    /** INCREASED：撿殘影的距離（PoE2 Remnant pickup range）。 */
    public static final String REMNANT_RANGE = "remnant.pickup_range";
    /** FLAT %：撿到殘影時回復最大生命的 x%。 */
    public static final String REMNANT_LIFE = "remnant.life_on_pickup";
    /** FLAT %：撿到殘影時回復最大魔力的 x%。 */
    public static final String REMNANT_MANA = "remnant.mana_on_pickup";
    /** FLAT %：殘影的效果給兩次的機率。 */
    public static final String REMNANT_DOUBLE = "remnant.double_chance";
    /** INCREASED：元素灌注的持續時間（基礎 10 秒）。 */
    public static final String INFUSION_DURATION = "infusion.duration";
    /** FLAT：某一種灌注最多多幾層；後面接元素（infusion.max.fire）。 */
    public static final String INFUSION_MAX_ELEMENT = "infusion.max.";
    /** FLAT %：獲得灌注時多一層同種的機率。 */
    public static final String INFUSION_EXTRA_CHANCE = "infusion.extra_chance";
    /** FLAT %：用掉灌注時不拿掉（效果照算）的機率。 */
    public static final String INFUSION_KEEP_CHANCE = "infusion.keep_chance";

    /** INCREASED：封印技能蓄封印的頻率（間隔 ÷ (1 + 頻率)）。 */
    public static final String SEAL_FREQUENCY = "seal.frequency";

    /** FLAT：你造成的曝露再多降幾 % 抗性（加在基礎 20 上）。 */
    public static final String EXPOSURE_EXTRA = "exposure.extra";
    /** INCREASED：你造成的致盲、阻礙的持續時間。 */
    public static final String BLIND_DURATION = "blind.duration";
    public static final String HINDER_DURATION = "hinder.duration";
    /** INCREASED：你造的碎地（Jagged Ground）的效果（減速幅度）與持續時間。 */
    public static final String JAGGED_EFFECT = "jagged.effect";
    public static final String JAGGED_DURATION = "jagged.duration";
    /** INCREASED：你造成的眩暈（Daze）的持續時間。 */
    public static final String DAZE_DURATION = "daze.duration";
    /** INCREASED（負的 = 降低）：被你致盲的敵人的暴擊率。 */
    public static final String BLIND_ENEMY_CRIT = "blind.enemy_crit";

    /** 給 /stats、說明用的標籤名稱（沒有就是 null）。 */
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry(COMPANION, "夥伴"), Map.entry(EMPOWERED, "強化攻擊"), Map.entry(BANNER, "戰旗"), Map.entry(PARRY, "招架"),
            Map.entry(CHANNELLING, "引導"), Map.entry(TRIGGERED, "被觸發"), Map.entry(HAZARD, "危害"), Map.entry(PLANT, "植物"),
            Map.entry(DETONATOR, "引爆"), Map.entry(BALLISTA, "弩台"), Map.entry(OFFERING, "祭品"), Map.entry(IN_PRESENCE, "存在範圍內"));

    public static String tagName(final String tag) {
        return NAMES.get(tag);
    }
}
