package tw.exilecore.skill;

import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * 2026-10-29 照 PoE2（poe2db Minion Commands：「You activate a Command skill, and one of your Minions carries out the Command」）：
 * 巨靈（克拉里、納維拉、魯兹安）的指令是巨靈放的，傷害照巨靈（召喚物）的數值算——召喚物傷害、召喚物暴擊這些吃得到，你自己的法術傷害、
 * 法術暴擊吃不到。做法：指令（和它帶出來的分身爆炸、熔岩浪、甲蟲…）打中時，SkillDamage 改用那隻巨靈當攻擊者，
 * CombatListener 用巨靈的屬性表配上指令自己的技能描述結算（傷害仍算你的：掉落、經驗、傷害數字）。
 * 施法時間（2026-11-01 改了，見下）、範圍、冷卻、魔力消耗照舊是你放指令的那一下（你按的指令）。巨靈沒叫出來（或不在同一個世界）就照舊算你的。
 * 這次之前整個指令都當成你自己放的法術。
 * <p>
 * 2026-11-01 照 PoE2（PoB2 Command*Player castTime 0.4）：下指令是你自己很快的一下（{@link #COMMAND_TIME}，吃你的施法速度），
 * 巨靈接著放牠的招式，過了「招式施法時間 ÷ 巨靈的施法速度」才出手（SkillService.djinnCastTicks；巨靈的施法速度＝召喚技能上的輔助、
 * 你給召喚物的施法速度）。範圍、冷卻、傷害照巨靈的屬性表。這次之前整個指令是你的一次施法：用巨靈招式的施法時間、照你的施法速度、馬上出手。
 */
public final class DjinnCommands {

    /** 克拉里的惡言（Kelari's Malediction）：自己是一顆召喚屍甲蟲的寶石，甲蟲吃這顆上的輔助（{@link MinionTypes#HOSTED}）。 */
    public static final String VENOM = "sand_spirit_venom_word";
    /** 屍甲蟲衝過去爆炸的那一下（照克拉里的召喚物數值算，但吃的是惡言上的輔助，不是克拉里召喚技能上的）。 */
    public static final String BEETLE = "asc_corpse_beetle";

    /**
     * 2026-11-01 照 PoE2（poe2db Kelari's Deception…：Skill Types 只有 CommandsMinions、UsableWhileMoving、UsableWhileShapeshifted）：
     * 你面板上的巨靈指令技能（昇華天賦賦予的 Kelari's Brutality／Deception／Judgment、Navira's Oasis／Well／Fracturing、
     * Ruzhan's Reckoning／Fury／Trap）。巨靈真正放的招式（PoB2 Data/Skills/minion.lua 的 KnifeThrowSandDjinn、MeteorFireDjinn…）
     * 是巨靈的技能，吃的是掛在巨靈召喚技能上的輔助（Minion Types，skills.yml 的 summon.minion_tags）。
     */
    public static final Set<String> COMMANDS = Set.of("sand_spirit_trick", "sand_spirit_cruelty", "sand_spirit_verdict",
            "mirage_spirit_haven", "mirage_spirit_spring", "mirage_spirit_freeze",
            "blade_spirit_settle", "blade_spirit_wrath_fire", "blade_spirit_device");

    /**
     * 2026-11-01 照 PoE2：指令技能（CommandsMinions、UsableWhileMoving、UsableWhileShapeshifted）掛得上的輔助——照 PoB2 每顆輔助的
     * requireSkillTypes／excludeSkillTypes 算出來（doesTypeExpressionMatch）、對得到我們的輔助的：
     * Efficiency II（節流）、Blood Magic（血付）、Atalui's Bloodletting（血滲）、Uhtred's Exodus／Omen／Augury（孤星、雙兆、三兆）、
     * Mobility（輕身）、Deliberation（凝息）、Bidding I／II（骸令、骸令 II）、Kurgal's Leash（縛魔）、Catharsis（淨化）、
     * Profanity II（褻屍 II）、Armour Demolisher（拆甲）、Uruk's Smelting（熔甲）、Dialla's Desire（願晶）、Wind Wave（震風）、
     * Breachlord's Rift（凝破）。其他（傷害、範圍、投射物、異常…）原作都掛不上指令技能，要掛在巨靈的召喚技能上。
     * （原作沒有的、我們自己的輔助也不行：指令技能本身只有這幾種類型。）
     */
    static final Set<String> COMMAND_SUPPORTS = Set.of("cost_saving", "blood_pay", "blood_seep", "lone_star", "twin_portent", "triple_portent",
            "light_body", "held_breath", "bone_command", "bone_command_ii", "bind_demon", "cleanse_rite", "corpse_defile_ii",
            "dismantle_plate", "molten_armor", "wish_crystal", "quake_gale", "condense_break");

    /**
     * 2026-11-01 照 PoE2：巨靈真的會射出投射物的（羅贊的怒焰是一輪一輪的火燼、機關擲出去的巨劍；克拉里的殘酷是射向地面的飛刀）：
     * 穿透、多射、連鎖、分裂、投射物速度從巨靈的召喚技能這邊掛才有作用（指令照巨靈的屬性表算投射物）。
     * 這次之前只有羅贊（克拉里的殘酷做成落點的刀刃，投射物的輔助吃不到）。
     */
    static final Set<String> SHOOTERS = Set.of("flame_blade_spirit", "foul_sand_spirit");

    /**
     * 2026-11-01 照 PoE2（PoB2 Data/Skills/other.lua：CommandSandDjinnKnifeThrowPlayer、CommandWaterDjinnBubblePlayer、
     * CommandFireDjinnLivingBombPlayer 的 castTime 0.4；沒有 Spell 類型、也不是攻擊，PoB2 CalcActiveSkill 給 ModFlag.Cast，吃你的施法速度）：
     * 你下指令的那一下（秒）。巨靈接著用牠自己的招式，出手照招式的施法時間 ÷ 巨靈的施法速度（{@link #djinnCastTicks}）。
     * 這次之前你下指令要用巨靈招式的施法時間（照你的施法速度），巨靈的施法速度沒有作用。
     */
    public static final double COMMAND_TIME = 0.4;

    /**
     * 2026-11-01 照 PoE2：按巨靈召喚技能的鍵下的指令（巨靈自帶的那一個）的巨靈招式施法時間（秒）：
     * 炎刃．羅贊 → 羅贊的烙印（LivingBombFireDjinn castTime 0.6）、終幻．娜瑟菈 → 娜瑟菈的擁抱（WaterBubbleWaterDjinn castTime 2）。
     * 污沙．珂芮絲自帶的殘酷是另外一個技能（sand_spirit_cruelty，施法時間照它的 cast_time）。
     */
    public static final Map<String, Double> PRESS_CAST_TIMES = Map.of("flame_blade_spirit", 0.6, "final_mirage_spirit", 2.0);

    /**
     * 2026-11-01 照 PoE2 Kelari's Brutality（PoB2 Data/Skills/minion.lua KnifeThrowSandDjinn）：飛刀數（djinn_knife_throw_number_of_knives_created 12；
     * skills.yml 的 projectile.count 也是 12，多的投射物照加）、飛刀落點散在目標周圍幾公尺內（active_skill_base_area_of_effect_radius 30 = 3 公尺，吃範圍）、
     * 鑽進地面之後多久爆出刀刃（原作只寫「短暫延遲」，PoB2、poe2db 都沒有數字：照這次之前的 0.6～0.85 秒）。
     * 2026-11-01 照 PoE2（KnifeThrowSandDjinn qualityStats：djinn_knife_throw_number_of_knives_created 每 1% 品質 0.1）：品質多 0～2 把
     * （gem_quality.yml 的 skill.projectiles；昇華賦予的技能品質固定 20%，所以是 14 把）。這次之前品質沒有效果。
     */
    public static final String BRUTALITY = "sand_spirit_cruelty";
    public static final String BRUTALITY_BLADE = "asc_kelari_blade";
    public static final int KNIVES = 12;
    public static final double KNIFE_SPREAD_METRES = 3;
    public static final int ERUPT_TICKS = 12;
    public static final int ERUPT_JITTER = 6;
    /** always_pierce：穿透所有目標（引擎的穿透上限 20，跟羅贊的機關一樣）。 */
    public static final int PIERCE_ALL = 20;

    /**
     * 2026-11-01 照 PoE2（PoB2 KnifeThrowSandDjinn：always_pierce、CannotChain）：克拉里會射的只有殘酷的飛刀，本來就穿透所有目標、不會連鎖——
     * 多穿透、連鎖的輔助掛在克拉里身上沒有作用（照站長「沒有作用就不讓掛」，MinionTypes）。多射、分裂、投射物速度照常。
     */
    static final Set<String> ALWAYS_PIERCE = Set.of("foul_sand_spirit");
    static final Set<String> CANNOT_CHAIN = Set.of("foul_sand_spirit");

    /**
     * 2026-11-01 照 PoE2 Kelari's Judgment（PoB2 minion.lua HandSlamSandDjinn：Channel、GainsStages、castTime 0.5、
     * sand_djinn_hand_slam_maximum_number_of_stages 4、channel_start_lock_cancelling_of_cast_time_% 100、
     * djinn_hand_slam_damage_+%_final_per_additional_stage 125、djinn_hand_slam_area_of_effect_+%_final_per_additional_stage 25、
     * djinn_hand_slam_minimum／maximum_area_of_effect_+%_final −20／+20；poe2db：Cost 3.87—39.52 Mana per second）：
     * 你按住引導（指令），珂芮絲陪你引導、在目標上方懸起刀刃：每 0.5 秒（÷ 珂芮絲的施法速度）一段、最多 4 段，第一段不能取消（最短引導一段）；
     * 放開時刀刃落下造成震波，每多一段更多 125% 傷害、範圍更多 25%，每次震波的範圍在 −20%～+20% 之間（{@link #judgmentAreaRoll}）。
     * 引導中逐秒扣魔力（skills.yml 的 charge：locked_start、drain）。這次之前是固定 1.5 秒的珂芮絲施法、當作引導 3 段（傷害 ×3.5）。
     */
    public static final String JUDGMENT = "sand_spirit_verdict";
    /** djinn_hand_slam_minimum_area_of_effect_+%_final：震波範圍最少 −20%。 */
    public static final double JUDGMENT_MIN_AREA = -20;
    /** djinn_hand_slam_maximum_area_of_effect_+%_final：震波範圍最多 +20%。 */
    public static final double JUDGMENT_MAX_AREA = 20;

    /** 2026-11-01 照 PoE2：這個指令是按住引導的（珂芮絲的裁決）。 */
    public static boolean channelled(final String skillId) {
        return JUDGMENT.equals(skillId);
    }

    /**
     * 純邏輯：珂芮絲的裁決這一次震波的範圍倍率（random 0～1 → 1 − 20% ～ 1 + 20%；範圍是面積，半徑開根號）。
     * PoB2 沒有拿這兩個數值做計算（沒有 statMap），poe2db 也只列數值：照字面當作每次在 −20%～+20% 之間。
     */
    public static double judgmentAreaRoll(final double random) {
        final double r = Math.max(0, Math.min(1, random));
        return 1 + (JUDGMENT_MIN_AREA + (JUDGMENT_MAX_AREA - JUDGMENT_MIN_AREA) * r) / 100.0;
    }

    /** 2026-11-01 照 PoE2：巨靈放招式的施法速度倍率（巨靈的屬性表，招式的情境 {@link #djinnContext}）。 */
    public static double djinnCastSpeed(final StatSheet djinn, final SkillDefinition definition) {
        return djinn == null ? 1.0 : DamageCalculator.castSpeedMultiplier(djinn, djinnContext(definition.tags()));
    }

    /**
     * 純邏輯（估計用）：巨靈的指令一輪要幾 tick——你下指令（commandTicks）＋巨靈施法（djinnTicks）；
     * 引導的指令（珂芮絲的裁決）你引導的那段時間就是指令，也是珂芮絲引導的時間（channelTicks），沒有另外的下指令、施法。
     */
    public static double cycleTicks(final boolean channelled, final double commandTicks, final double djinnTicks, final double channelTicks) {
        return channelled ? Math.max(1, channelTicks) : commandTicks + djinnTicks;
    }

    /** 2026-11-01 照 PoE2：招式有 Cascadable 的巨靈（羅贊的清算 MeteorFireDjinn）：連瀑從召喚技能這邊掛。 */
    static final Set<String> CASCADERS = Set.of("flame_blade_spirit");

    private DjinnCommands() {
    }

    /** 2026-11-01 照 PoE2：這是你對巨靈下的指令（{@link #COMMANDS}，或按炎刃．羅贊、終幻．娜瑟菈的鍵：{@link #PRESS_CAST_TIMES}）。 */
    public static boolean isCommand(final String skillId) {
        return skillId != null && (COMMANDS.contains(skillId) || PRESS_CAST_TIMES.containsKey(skillId));
    }

    /** 下這個指令的是哪一位巨靈（指令技能照 {@link #djinnFor}；按巨靈的鍵就是那一位）；不是指令回傳 null。 */
    public static String commandedDjinn(final String skillId) {
        if (skillId == null) {
            return null;
        }
        if (PRESS_CAST_TIMES.containsKey(skillId)) {
            return skillId;
        }
        return COMMANDS.contains(skillId) ? djinnFor(skillId) : null;
    }

    /** 巨靈招式的基礎施法時間（秒）：按巨靈的鍵照 {@link #PRESS_CAST_TIMES}，指令技能照它自己的 cast_time（skillCastTime）。 */
    public static double djinnCastTime(final String skillId, final double skillCastTime) {
        final Double press = skillId == null ? null : PRESS_CAST_TIMES.get(skillId);
        return press != null ? press : skillCastTime;
    }

    /** 純邏輯：你下指令的那一下要幾 tick（{@link #COMMAND_TIME} ÷ 你的施法速度倍率；最少 {@link SkillMath#MIN_INTERVAL_TICKS}）。 */
    public static double commandTicks(final double playerCastSpeed) {
        return SkillMath.castInterval(COMMAND_TIME, playerCastSpeed);
    }

    /** 純邏輯：巨靈放招式要幾 tick（招式施法時間 ÷ 巨靈的施法速度倍率；最少 {@link SkillMath#MIN_INTERVAL_TICKS}）。 */
    public static long djinnCastTicks(final double castTime, final double djinnCastSpeed) {
        return Math.max(1, Math.round(SkillMath.castInterval(castTime, djinnCastSpeed)));
    }

    /**
     * 2026-11-01 照 PoE2（PoB2 Command*Player castTime 0.4，ModFlag.Cast）：你下指令那一下要幾 tick：0.4 秒 ÷ 你的施法速度
     * （playerSheet 疊上指令技能自己的輔助：輕身、凝息也算；只看 command 情境，法術、元素的施法速度不算：指令技能本身不是法術）。
     */
    public static double commandTicks(final SkillBuild build, final StatSheet playerSheet) {
        final StatSheet effective = build.profile().effective(playerSheet);
        return commandTicks(DamageCalculator.castSpeedMultiplier(effective, Commandables.CONTEXT));
    }

    /** 2026-11-01 照 PoE2：巨靈放這個指令的招式要幾 tick：招式的施法時間 ÷ 巨靈的施法速度（djinn = 巨靈的屬性表，含召喚技能上的輔助）。 */
    public static long djinnCastTicks(final StatSheet djinn, final SkillDefinition definition) {
        final double castTime = djinnCastTime(definition.id(), definition.castTime());
        return djinnCastTicks(castTime, DamageCalculator.castSpeedMultiplier(djinn, djinnContext(definition.tags())));
    }

    /**
     * 查巨靈施法速度的情境：巨靈的招式都是法術（PoB2 minion.lua 的 Spell），加上指令（command：軍令、天賦只給指令的那些）。
     * 召喚技能自己的 minion、persistent 這種標籤拿掉（巨靈屬性表上的修飾器早就拿掉 minion 了）。
     */
    public static Set<String> djinnContext(final Set<String> skillTags) {
        final Set<String> context = new HashSet<>(skillTags == null ? Set.of() : skillTags);
        context.remove("minion");
        context.remove("persistent");
        context.remove("aura");
        context.add("spell");
        context.add(Commandables.TAG);
        return Set.copyOf(context);
    }

    /**
     * 純邏輯：克拉里的殘酷每一把飛刀的落點（相對目標點的水平位移 {dx, dz}）：第一把落在目標點，其他的平均散在半徑 spread 的圓內
     * （random 每次給 0～1）。
     */
    public static List<double[]> knifeOffsets(final int knives, final double spread, final DoubleSupplier random) {
        final List<double[]> list = new ArrayList<>(Math.max(1, knives));
        list.add(new double[] {0, 0});
        for (int i = 1; i < knives; i++) {
            final double angle = random.getAsDouble() * Math.PI * 2;
            final double distance = Math.sqrt(random.getAsDouble()) * Math.max(0, spread);
            list.add(new double[] {Math.cos(angle) * distance, Math.sin(angle) * distance});
        }
        return list;
    }

    /** 2026-11-01 照 PoE2：這顆輔助不能掛在這個巨靈指令技能上的原因；不是巨靈指令、或掛得上回傳 null。 */
    public static String commandRefusal(final String supportId, final String skillId) {
        if (skillId == null || !COMMANDS.contains(skillId) || COMMAND_SUPPORTS.contains(supportId)) {
            return null;
        }
        return "指令技能只能掛指令、消耗、等級這類的輔助（傷害、範圍、投射物這些掛在巨靈的召喚技能上）";
    }

    /**
     * 2026-11-01 照 PoE2：這一下（技能描述代號）是巨靈自己的招式、吃巨靈召喚技能上的輔助（指令和它帶出來的分身爆炸、刀刃、熔岩浪、
     * 火焰龍捲、羅贊的烙印、狂熱斬擊）。屍甲蟲照克拉里的數值算，但不吃克拉里召喚技能上的輔助（{@link #BEETLE}）。
     */
    public static boolean gemSupported(final String profileId) {
        return djinnFor(profileId) != null && !BEETLE.equals(base(profileId));
    }

    private static String base(final String profileId) {
        final int dot = profileId.indexOf('.');
        return dot < 0 ? profileId : profileId.substring(0, dot);
    }

    /** 這一下（技能描述代號）是哪一位巨靈放的；不是巨靈的指令回傳 null。 */
    public static String djinnFor(final String profileId) {
        if (profileId == null) {
            return null;
        }
        final int dot = profileId.indexOf('.');
        String id = dot < 0 ? profileId : profileId.substring(0, dot);
        if ("asc_corpse_beetle".equals(id)) {
            return "foul_sand_spirit"; // 克拉里的惡言生出來的屍甲蟲
        }
        if (id.startsWith("asc_")) {
            id = id.substring(4);
        }
        if (id.endsWith("_djinn") || id.equals("foul_sand_spirit") || id.equals("final_mirage_spirit") || id.equals("flame_blade_spirit")) {
            return null; // 巨靈本身
        }
        if (id.startsWith("kelari_") || id.startsWith("sand_spirit_")) { // 2026-10-31：玩家技能改了代號（sand_spirit_*），昇華的 asc_kelari_* 照舊
            return "foul_sand_spirit";
        }
        if (id.startsWith("navira_") || id.startsWith("mirage_spirit_")) {
            return "final_mirage_spirit";
        }
        if (id.startsWith("ruzhan_") || id.startsWith("blade_spirit_")) {
            return "flame_blade_spirit";
        }
        return null;
    }
}
