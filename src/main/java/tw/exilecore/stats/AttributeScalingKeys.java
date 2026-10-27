package tw.exilecore.stats;

import java.util.List;
import java.util.Set;

/**
 * 2026-10-26：PoE2 0.5 天賦樹「每 x 點屬性」「照裝備的數值換算」「插著幾顆某色輔助寶石」「對特定敵人的命中、撲殺」用的屬性鍵。
 * 不放進 {@link StatKeys}，方便合併。換算都在屬性表算完屬性、裝備之後做（{@link #applyDerived}、{@link #applyLate}，
 * PlayerStateService.build 呼叫）；命中、撲殺的消費端在 DamageCalculator（命中帶這一下的情境標籤）與 CombatListener（撲殺）。
 */
public final class AttributeScalingKeys {

    private AttributeScalingKeys() {
    }

    // ---- 每 x 點屬性（值＝每一份給多少；INCREASED 是小數） ----

    /** FLAT：每點敏捷 +x 異常門檻。 */
    public static final String AILMENT_THRESHOLD_PER_DEX = "ailment.threshold_per_dex";
    /** FLAT：每點力量 +x 暈眩門檻。 */
    public static final String STUN_THRESHOLD_PER_STR = "stun.threshold_per_str";
    /** FLAT：每 4 點敏捷 +x 最大生命。 */
    public static final String LIFE_PER_4_DEX = "life.per_4_dex";
    /** INCREASED：每 10 點力量增加 x 法術傷害。 */
    public static final String SPELL_DAMAGE_PER_10_STR = "damage.spell_per_10_str";
    /** INCREASED：每 15 點力量增加 x 傷害。 */
    public static final String DAMAGE_PER_15_STR = "damage.per_15_str";
    /** INCREASED：每 25 點敏捷增加 x 攻擊速度。 */
    public static final String ATTACK_SPEED_PER_25_DEX = "attack.speed_per_25_dex";
    /** INCREASED：每 10 點智慧增加 x 閃避。 */
    public static final String EVASION_PER_10_INT = "evasion.per_10_int";
    /** INCREASED：最低的那項屬性每 5 點增加 x 傷害。 */
    public static final String DAMAGE_PER_5_LOWEST = "damage.per_5_lowest_attr";
    /** INCREASED：每 100 最大魔力增加 x 法術傷害。 */
    public static final String SPELL_DAMAGE_PER_100_MANA = "damage.spell_per_100_mana";
    /** INCREASED：每 1% 格擋率增加 x 傷害（格擋率照上限算）。 */
    public static final String DAMAGE_PER_BLOCK = "damage.per_block";
    /** INCREASED：每 400 命中增加 x 攻擊速度（最多 {@link #ATTACK_SPEED_PER_ACCURACY_CAP}）。 */
    public static final String ATTACK_SPEED_PER_400_ACCURACY = "attack.speed_per_400_accuracy";
    public static final double ATTACK_SPEED_PER_ACCURACY_CAP = 0.20;

    // ---- 照裝備的數值（裝備自己的護甲、閃避、能量護盾，含本地增加） ----

    /** FLAT：每 8 點頭盔護甲 +x 最大能量護盾。 */
    public static final String ES_PER_8_HELMET_ARMOUR = "es.per_8_helmet_armour";
    /** FLAT：每 1 點鞋子能量護盾 +x 護甲。 */
    public static final String ARMOUR_PER_BOOTS_ES = "armour.per_boots_es";
    /** FLAT：胸甲的閃避每這麼多點 +1 最大能量護盾（值是除數：12）。 */
    public static final String ES_PER_BODY_EVASION = "es.per_body_evasion";
    /** FLAT：每 1 點手套（我們的護腿）護甲 +x 閃避。 */
    public static final String EVASION_PER_GLOVES_ARMOUR = "evasion.per_gloves_armour";
    /** FLAT：每 1 點頭盔能量護盾 +x 閃避。 */
    public static final String EVASION_PER_HELMET_ES = "evasion.per_helmet_es";
    /** INCREASED：盾的護甲＋閃避每 75 點增加 x 攻擊傷害。 */
    public static final String ATTACK_DAMAGE_PER_75_SHIELD = "damage.attack_per_75_shield";
    /** FLAT %：獲得等同頭盔、手套（我們的護腿）、鞋子力量需求總和 x% 的護甲。 */
    public static final String ARMOUR_FROM_STR_REQ = "armour.from_str_requirement";
    /** FLAT %（萬分比換算過）：獲得等同胸甲護甲 x% 的物理荊棘傷害。 */
    public static final String THORNS_FROM_BODY_ARMOUR = "thorns.from_body_armour";
    /** INCREASED：胸甲給的能量護盾（照基底數值加，跟胸甲本地的增加相加，同 {@link TreeEffectKeys#BODY_ARMOUR}）。 */
    public static final String BODY_ES = "es.body";
    /** INCREASED：胸甲給的閃避。 */
    public static final String BODY_EVASION = "evasion.body";
    /** 旗標：頭盔的護甲比閃避高時免疫流血。 */
    public static final String HELMET_ARMOUR_BLEED_IMMUNE = "flag.helmet_armour_bleed_immune";
    /** 旗標：頭盔的閃避比護甲高時免疫中毒。 */
    public static final String HELMET_EVASION_POISON_IMMUNE = "flag.helmet_evasion_poison_immune";

    // ---- 輔助寶石顏色：supgem.門檻.真正的屬性代號（門檻成立才換成真正的屬性） ----

    public static final String SUPPORT_GATE = "supgem.";
    /** 至少 5 顆紅色／綠色／藍色；紅綠藍各至少 5 顆。 */
    public static final String RED5 = "red5";
    public static final String GREEN5 = "green5";
    public static final String BLUE5 = "blue5";
    public static final String RGB5 = "rgb5";
    /** 至少 10 顆。 */
    public static final String RED10 = "red10";
    public static final String GREEN10 = "green10";
    public static final String BLUE10 = "blue10";
    /** 插著的輔助寶石過半是這個顏色。 */
    public static final String MAJORITY_RED = "majred";
    public static final String MAJORITY_GREEN = "majgreen";
    public static final String MAJORITY_BLUE = "majblue";

    /** supgem.門檻.屬性 的寫法。 */
    public static String gated(final String gate, final String stat) {
        return SUPPORT_GATE + gate + "." + stat;
    }

    /** 插著的輔助寶石：各色幾顆、總共幾顆（tw.exilecore.skill.SupportColours 數的）。 */
    public record Supports(int red, int green, int blue, int total) {
        public static final Supports NONE = new Supports(0, 0, 0, 0);
    }

    /** 門檻成不成立（不認得的門檻不成立）。 */
    public static boolean gate(final String gate, final Supports supports) {
        return switch (gate) {
            case RED5 -> supports.red() >= 5;
            case GREEN5 -> supports.green() >= 5;
            case BLUE5 -> supports.blue() >= 5;
            case RGB5 -> supports.red() >= 5 && supports.green() >= 5 && supports.blue() >= 5;
            case RED10 -> supports.red() >= 10;
            case GREEN10 -> supports.green() >= 10;
            case BLUE10 -> supports.blue() >= 10;
            case MAJORITY_RED -> supports.red() * 2 > supports.total();
            case MAJORITY_GREEN -> supports.green() * 2 > supports.total();
            case MAJORITY_BLUE -> supports.blue() * 2 > supports.total();
            default -> false;
        };
    }

    /**
     * 裝備的數值。pieces：生效的防具（{@link KeystoneKeys.Piece}，含副手的盾）；body*：胸甲的基底護甲、閃避、能量護盾
     * （「增加 x% 胸甲的…」照基底加）；strRequirement：頭盔、手套、鞋子的力量需求總和。
     */
    public record Gear(List<KeystoneKeys.Piece> pieces, double bodyBaseEvasion, double bodyBaseEs, double strRequirement) {
        public static final Gear NONE = new Gear(List.of(), 0, 0, 0);

        KeystoneKeys.Piece slot(final String slot) {
            for (final KeystoneKeys.Piece piece : pieces) {
                if (slot.equals(piece.slot())) {
                    return piece;
                }
            }
            return null;
        }
    }

    /**
     * 屬性表算完屬性、裝備之後（TreeEffectKeys.applyDerived 之前）：輔助寶石顏色的門檻、裝備數值換算、每 x 點屬性。
     * 先加門檻（最大魔力、生命會變），再算裝備，最後算照屬性、魔力、格擋的。
     */
    public static void applyDerived(final StatSheet sheet, final Gear gear, final Supports supports) {
        final Set<String> none = Set.of();
        // 1. 輔助寶石顏色
        for (final Modifier mod : List.copyOf(sheet.allModifiers())) {
            if (!mod.stat().startsWith(SUPPORT_GATE)) {
                continue;
            }
            final String rest = mod.stat().substring(SUPPORT_GATE.length());
            final int dot = rest.indexOf('.');
            if (dot > 0 && gate(rest.substring(0, dot), supports)) {
                sheet.add(new Modifier(rest.substring(dot + 1), mod.type(), mod.value(), mod.tags(), "輔助寶石"));
            }
        }
        // 2. 裝備
        final KeystoneKeys.Piece helmet = gear.slot("HEAD");
        final KeystoneKeys.Piece gloves = gear.slot("LEGS"); // 我們沒有手套欄，PoE2 的手套對應護腿（同林豹的做法）
        final KeystoneKeys.Piece boots = gear.slot("FEET");
        final KeystoneKeys.Piece body = gear.slot("CHEST");
        final KeystoneKeys.Piece shield = gear.slot("OFF_HAND");
        if (helmet != null) {
            perPoint(sheet, ES_PER_8_HELMET_ARMOUR, helmet.armour(), 8, StatKeys.ES_MAX, "頭盔護甲");
            perPoint(sheet, EVASION_PER_HELMET_ES, helmet.energyShield(), 1, StatKeys.EVASION, "頭盔護盾");
            if (StatKeys.flag(sheet, HELMET_ARMOUR_BLEED_IMMUNE) && helmet.armour() > helmet.evasion()) {
                sheet.add(Modifier.flat("flag.immune.bleed", 1, "頭盔"));
            }
            if (StatKeys.flag(sheet, HELMET_EVASION_POISON_IMMUNE) && helmet.evasion() > helmet.armour()) {
                sheet.add(Modifier.flat("flag.immune.poison", 1, "頭盔"));
            }
        }
        if (gloves != null) {
            perPoint(sheet, EVASION_PER_GLOVES_ARMOUR, gloves.armour(), 1, StatKeys.EVASION, "手套護甲");
        }
        if (boots != null) {
            perPoint(sheet, ARMOUR_PER_BOOTS_ES, boots.energyShield(), 1, StatKeys.ARMOUR, "鞋子護盾");
        }
        if (body != null) {
            final double divisor = sheet.flat(ES_PER_BODY_EVASION, none);
            if (divisor > 0 && body.evasion() > 0) {
                sheet.add(Modifier.flat(StatKeys.ES_MAX, Math.floor(body.evasion() / divisor), "胸甲閃避"));
            }
            // 2026-10-26 合併：胸甲護甲的荊棘、胸甲護盾／閃避增加由 PlayerStateService.ascendancyEquipment（TreeNewMechanicKeys）處理
        }
        if (shield != null) {
            final double per = sheet.increased(ATTACK_DAMAGE_PER_75_SHIELD, none);
            final double total = Math.max(0, shield.armour()) + Math.max(0, shield.evasion());
            if (per != 0 && total >= 75) {
                sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, Math.floor(total / 75) * per, Set.of(Tags.ATTACK), "盾的防禦"));
            }
        }
        // 2026-10-26 合併：力量需求換護甲由 PlayerStateService.ascendancyEquipment（TreeNewMechanicKeys）處理，這裡不重複算
        // 3. 屬性
        final double str = Math.max(0, sheet.get(StatKeys.STR));
        final double dex = Math.max(0, sheet.get(StatKeys.DEX));
        final double intelligence = Math.max(0, sheet.get(StatKeys.INT));
        perPoint(sheet, AILMENT_THRESHOLD_PER_DEX, dex, 1, StatKeys.AILMENT_THRESHOLD, "敏捷");
        perPoint(sheet, STUN_THRESHOLD_PER_STR, str, 1, StatKeys.STUN_THRESHOLD, "力量");
        perPoint(sheet, LIFE_PER_4_DEX, dex, 4, StatKeys.LIFE_MAX, "敏捷");
        perIncrease(sheet, SPELL_DAMAGE_PER_10_STR, str, 10, StatKeys.DAMAGE, Set.of(Tags.SPELL), "力量");
        perIncrease(sheet, DAMAGE_PER_15_STR, str, 15, StatKeys.DAMAGE, Set.of(), "力量");
        perIncrease(sheet, ATTACK_SPEED_PER_25_DEX, dex, 25, StatKeys.ATTACK_SPEED, Set.of(), "敏捷");
        perIncrease(sheet, EVASION_PER_10_INT, intelligence, 10, StatKeys.EVASION, Set.of(), "智慧");
        perIncrease(sheet, DAMAGE_PER_5_LOWEST, Math.min(str, Math.min(dex, intelligence)), 5, StatKeys.DAMAGE, Set.of(), "最低屬性");
        // 4. 魔力、格擋
        perIncrease(sheet, SPELL_DAMAGE_PER_100_MANA, Math.max(0, sheet.get(StatKeys.MANA_MAX)), 100, StatKeys.DAMAGE, Set.of(Tags.SPELL), "最大魔力");
        final double blockCap = DamageCalculator.blockCap(sheet); // 2026-10-27：最多 90%（PoE2）
        perIncrease(sheet, DAMAGE_PER_BLOCK, Math.max(0, Math.min(blockCap, sheet.get(StatKeys.BLOCK_CHANCE))), 1, StatKeys.DAMAGE, Set.of(), "格擋率");
    }

    /** 命中都加完之後（KeystoneKeys.applyDerived 之後）：每 400 命中的攻擊速度（最多 20%）。 */
    public static void applyLate(final StatSheet sheet) {
        final double per = sheet.increased(ATTACK_SPEED_PER_400_ACCURACY, Set.of());
        if (per > 0) {
            final double inc = Math.min(ATTACK_SPEED_PER_ACCURACY_CAP, Math.floor(Math.max(0, sheet.get(StatKeys.ACCURACY)) / 400) * per);
            if (inc > 0) {
                sheet.add(Modifier.increased(StatKeys.ATTACK_SPEED, inc, "命中"));
            }
        }
    }

    /** FLAT：每 per 點來源給 key 的值 → target 加 floor(來源 / per) × 值。 */
    private static void perPoint(final StatSheet sheet, final String key, final double source, final double per, final String target, final String why) {
        final double value = sheet.flat(key, Set.of());
        if (value != 0 && source >= per) {
            sheet.add(Modifier.flat(target, Math.floor(Math.floor(source / per) * value), why));
        }
    }

    /** INCREASED：每 per 點來源給 key 的增加 → target 增加 floor(來源 / per) × 值。 */
    private static void perIncrease(final StatSheet sheet, final String key, final double source, final double per, final String target,
                                    final Set<String> tags, final String why) {
        final double value = sheet.increased(key, Set.of());
        if (value != 0 && source >= per) {
            sheet.add(new Modifier(target, ModType.INCREASED, Math.floor(source / per) * value, tags, why));
        }
    }

    // ---- 撲殺 ----

    /**
     * 這一下有沒有撲殺：flag.culling 帶這一下的情境標籤（弓的攻擊、對被標記的敵人、對野獸…）也算。
     * context 是技能的標籤加上目標狀態的條件標籤（TargetConditions）。
     */
    public static boolean culls(final StatSheet skillSheet, final Set<String> context) {
        return skillSheet.flat(StatKeys.FLAG_CULLING, context) > 0;
    }

    /** 撲殺門檻的倍率：「增加 x% 撲殺門檻」帶情境標籤（對稀有、傳奇，對被禁錮的）也算。 */
    public static double cullScale(final StatSheet skillSheet, final Set<String> context) {
        return Math.max(0, 1 + skillSheet.increased(SupportKeys.CULL_THRESHOLD, context));
    }
}
