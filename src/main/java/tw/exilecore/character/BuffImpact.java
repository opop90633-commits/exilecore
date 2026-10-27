package tw.exilecore.character;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 增益對面板數值的影響（2026-10）：拿玩家身上的屬性表跟「沒有暫時增益」的表（{@link PlayerStateService#baselineSheet}）比，
 * 列出變了的數值：「生命上限 1,000 → 1,200（+200）」。
 * <p>
 * 暫時增益＝光環、戰吼、護符、球、疾速、圖騰、魔像，加上條件成立才有的詞綴（最近被擊中、藥劑效果中……）與怒氣。
 * 只在擊中當下才算的（戰吼強化、連擊、對低血的敵人）沒辦法事先算，不加進數字；2026-10-27 起看目標的另外列名字（{@link #conditionalNames}），
 * 不看目標的（季節循環、穿透、痛極生力、血鋒、馭星訣、身上的減益）照戰鬥的算法疊進面板的表。
 */
public final class BuffImpact {

    /** 防禦、攻擊兩邊各自變了的行（MiniMessage）；都空的＝增益沒改到面板上的數值。 */
    public record Lines(List<String> defence, List<String> offence) {
        public boolean isEmpty() {
            return defence.isEmpty() && offence.isEmpty();
        }

        public List<String> all() {
            final List<String> all = new ArrayList<>(defence);
            all.addAll(offence);
            return all;
        }
    }

    private static final double EPSILON = 1e-6;

    private BuffImpact() {
    }

    /**
     * 面板要用的「現在」的屬性表：玩家身上的表，再疊上戰鬥裡擊中時才算、但不看目標、現在就看得出來的：
     * 身上減益的修飾器（萎力詛咒更少傷害…）、一直生效的估計（{@link #estimateModifiers}：季節循環、穿透、痛極生力）、
     * 血鋒（照目前生命）、怒氣（原初飢餓時沒有）、馭星訣（每顆戰星）。跟 CombatListener 的算法一樣。
     */
    public static StatSheet liveSheet(final ExileCore plugin, final Player player) {
        final StatSheet sheet = plugin.players().sheet(player);
        final List<Modifier> extra = new ArrayList<>(situational(plugin, player, sheet));
        extra.addAll(hitTimeModifiers(plugin, player, sheet));
        return extra.isEmpty() ? sheet : sheet.overlay(extra);
    }

    /**
     * 2026-10-27：不是增益、但戰鬥裡擊中時才疊上的（面板的「現在」與「沒有增益」兩張表都疊，增益比較才不會把它們當成增益）：
     * 身上減益擊中時才疊的修飾器、{@link #estimateModifiers}（照目前生命）、血鋒（照目前生命）。
     */
    private static List<Modifier> situational(final ExileCore plugin, final Player player, final StatSheet sheet) {
        final PlayerState state = plugin.players().get(player);
        final List<Modifier> out = new ArrayList<>(selfDebuffs(plugin, player));
        out.addAll(estimateModifiers(sheet, lifeFraction(state)));
        final double perLife = sheet.flat(StatKeys.CRIT_BONUS_PER_LIFE, Set.of());
        if (perLife > 0) {
            out.add(Modifier.increased(StatKeys.CRIT_BONUS, Math.floor(state.life() / 50) * perLife / 100.0, "血鋒"));
        }
        return out;
    }

    private static double lifeFraction(final PlayerState state) {
        return state.maxLife() <= 0 ? 1 : state.life() / state.maxLife();
    }

    /** 2026-10-27：身上的減益（詛咒、怪物給的）擊中時才疊的修飾器（冷卻時間這類已經在屬性表裡，不重複）。 */
    private static List<Modifier> selfDebuffs(final ExileCore plugin, final Player player) {
        if (plugin.combat() == null || plugin.combat().debuffs() == null) {
            return List.of();
        }
        return tw.exilecore.combat.SelfDebuffs.hitPart(plugin.combat().debuffs().modifiers(player.getUniqueId(), null,
                plugin.getServer().getCurrentTick()));
    }

    /**
     * 2026-10-27：一直生效、不看目標、戰鬥裡擊中時才算的加成換成估計值（角色面板、技能每秒傷害、網頁計算器共用）：
     * 季節循環、穿透（{@link tw.exilecore.ascendancy.AscendancyEffects#alwaysOnEstimate}）；痛極生力（基石：滿血時暴擊傷害加成更少 30%，
     * 生命 35% 以下更多 30%，跟 CombatListener 一樣）。這次之前面板、技能每秒傷害、網頁都沒算。
     *
     * @param lifeFraction 目前生命比例（網頁、技能每秒傷害照滿血估：1）
     */
    public static List<Modifier> estimateModifiers(final StatSheet sheet, final double lifeFraction) {
        final List<Modifier> out = new ArrayList<>(tw.exilecore.ascendancy.AscendancyEffects.alwaysOnEstimate(sheet));
        out.addAll(painAttunement(sheet, lifeFraction));
        return out;
    }

    /** 痛極生力（PoE2 痛苦調和）：滿血時暴擊傷害加成更少 30%，低血（35% 以下）更多 30%。純函式（測試用）。 */
    public static List<Modifier> painAttunement(final StatSheet sheet, final double lifeFraction) {
        if (!StatKeys.flag(sheet, StatKeys.FLAG_PAIN_ATTUNEMENT)) {
            return List.of();
        }
        if (lifeFraction >= 0.999) {
            return List.of(Modifier.more(StatKeys.CRIT_BONUS, -0.3, "痛極生力"));
        }
        if (lifeFraction <= 0.35) {
            return List.of(Modifier.more(StatKeys.CRIT_BONUS, 0.3, "痛極生力"));
        }
        return List.of();
    }

    /** 增益類、擊中時才算、不看目標的（只疊在「現在」那張）：怒氣、馭星訣（每顆戰星增加傷害）。 */
    private static List<Modifier> hitTimeModifiers(final ExileCore plugin, final Player player, final StatSheet sheet) {
        final List<Modifier> out = new ArrayList<>(2);
        final int rage = plugin.skills() == null ? 0 : plugin.skills().resources().rage(player);
        out.addAll(rageModifiers(rage, sheet));
        // 2026-10-27：德魯伊之冠（每 2 點怒氣法術更多 1%，AscendancyMechanics 擊中時乘）；這次之前面板沒算
        out.addAll(tw.exilecore.ascendancy.AscendancyMechanics.druidicModifiers(sheet, rage));
        // 2026-10-27：馭星訣（身上每顆戰星增加 x% 傷害，CombatListener 擊中時算）；這次之前面板沒算
        final double perCharge = sheet.flat(StatKeys.DAMAGE_PER_CHARGE, Set.of());
        if (perCharge > 0 && plugin.combat() != null && plugin.combat().charges() != null) {
            int held = 0;
            for (final tw.exilecore.charge.ChargeType type : plugin.combat().charges().config().types()) {
                held += plugin.combat().charges().count(player, type.id());
            }
            if (held > 0) {
                out.add(Modifier.increased(StatKeys.DAMAGE, perCharge * held / 100.0, "馭星訣"));
            }
        }
        return out;
    }

    /**
     * 怒氣：每點增加 1% 攻擊傷害（吃「怒氣效果」）；2026-10-27：原初飢餓（基石）時怒氣沒有效果，跟 CombatListener 一樣不算
     * （這次之前面板照樣顯示怒氣的加成）。純函式（測試用）。
     */
    public static List<Modifier> rageModifiers(final int rage, final StatSheet sheet) {
        if (rage <= 0 || StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_PRIMAL_HUNGER)) {
            return List.of();
        }
        final double per = 0.01 * Math.max(0, 1 + sheet.increased(StatKeys.RAGE_EFFECT, Set.of()));
        return List.of(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, rage * per, Set.of(Tags.ATTACK), "怒氣"));
    }

    /** 怒氣讓攻擊傷害增加多少（小數；沒有怒氣、原初飢餓是 0）。 */
    public static double rageBonus(final ExileCore plugin, final Player player) {
        double total = 0;
        for (final Modifier modifier : rageModifiers(plugin.skills() == null ? 0 : plugin.skills().resources().rage(player),
                plugin.players().sheet(player))) {
            total += modifier.value();
        }
        return total;
    }

    /**
     * 2026-10-27：防禦面板用的表：玩家身上的表，再疊上被打時才疊的——身上減益的修飾器（三元詛咒的抗性…）、被怪物破掉的護甲、
     * 神聖儀式（目前護盾的 x% 加到護甲）與適應（AscendancyEffects.defenderSheet）。跟 CombatListener 挨打時一樣。
     * 這次之前防禦面板只看屬性表，被詛咒、被破甲時數字沒變。
     */
    public static StatSheet defenceSheet(final ExileCore plugin, final Player player) {
        return defenceOverlay(plugin, player, plugin.players().sheet(player));
    }

    private static StatSheet defenceOverlay(final ExileCore plugin, final Player player, final StatSheet base) {
        final List<Modifier> debuffs = selfDebuffs(plugin, player);
        StatSheet sheet = debuffs.isEmpty() ? base : base.overlay(debuffs);
        final double rawArmour = sheet.get(StatKeys.ARMOUR);
        if (rawArmour > 0) {
            final double less = tw.exilecore.combat.PlayerArmourBreak.lessFraction(
                    tw.exilecore.combat.PlayerArmourBreak.broken(player.getUniqueId(), plugin.getServer().getCurrentTick()), rawArmour);
            if (less > 0) {
                sheet = sheet.overlay(List.of(Modifier.more(StatKeys.ARMOUR, -less, "被破甲")));
            }
        }
        return tw.exilecore.ascendancy.AscendancyEffects.defenderSheet(player, plugin.players().get(player), sheet);
    }

    /**
     * 2026-10-27：身上的減益與被打時才算的狀態（面板列出來，數字已經含）：「萎力詛咒、三元詛咒、被破甲（護甲少 40%）」、神聖儀式加的護甲。
     * 沒有是空的。
     */
    public static List<String> situationLines(final ExileCore plugin, final Player player) {
        final List<String> names = new ArrayList<>();
        final List<Modifier> debuffs = plugin.combat() == null || plugin.combat().debuffs() == null ? List.of()
                : plugin.combat().debuffs().modifiers(player.getUniqueId(), null, plugin.getServer().getCurrentTick());
        for (final Modifier mod : debuffs) {
            final String name = mod.source().startsWith("換界石：") ? mod.source().substring(4) : mod.source();
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final StatSheet withDebuffs = debuffs.isEmpty() ? sheet : sheet.overlay(tw.exilecore.combat.SelfDebuffs.hitPart(debuffs));
        final double rawArmour = withDebuffs.get(StatKeys.ARMOUR);
        final double less = rawArmour <= 0 ? 0 : tw.exilecore.combat.PlayerArmourBreak.lessFraction(
                tw.exilecore.combat.PlayerArmourBreak.broken(player.getUniqueId(), plugin.getServer().getCurrentTick()), rawArmour);
        if (less > 0) {
            names.add(less >= 1 - EPSILON ? "完全破甲（沒有護甲）" : String.format(Locale.ROOT, "被破甲（護甲少 %d%%）", Math.round(less * 100)));
        }
        final List<String> lines = new ArrayList<>();
        if (!names.isEmpty()) {
            lines.add("<#b07aff>身上的減益<dark_gray>（上面的數字已含）：<gray>" + String.join("<dark_gray>、<gray>", names));
        }
        final double esArmour = tw.exilecore.ascendancy.AscendancyEffects.defenderSheet(player, plugin.players().get(player), withDebuffs)
                .get(StatKeys.ARMOUR) - rawArmour;
        if (esArmour >= 1) {
            lines.add(String.format(Locale.ROOT, "<dark_gray>　護甲含神聖儀式 +%,d（照目前的能量護盾，被打時才算）", Math.round(esArmour)));
        }
        return lines;
    }

    /**
     * 2026-10-27：同 {@link #conditionalNames(StatSheet)}，好幾張表（角色的、每個技能疊上輔助的）合起來，不重複。
     * 掛在輔助上的條件（處刑、貪匣之鎖、貼身、連擊、毒慾…）只在技能的表上，只看角色的表會漏掉。
     */
    public static List<String> conditionalNames(final java.util.Collection<StatSheet> sheets) {
        final List<String> names = new ArrayList<>();
        for (final StatSheet sheet : sheets) {
            for (final String name : conditionalNames(sheet)) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** 掠獸（只能傷害低血的敵人）在面板上的名字（要標清楚：數字照打得到的時候算）。 */
    public static final String BEASTRITE = "掠獸（只能傷害生命 35% 以下的敵人）";

    /**
     * 2026-10-27：擊中時看目標（或技能的狀態）才算、沒有算進面板數字的加成（屬性表上有才列；純函式，測試用）。
     * 傳技能的表（{@code profile.effective(sheet)}）才看得到掛在輔助上的；2026-10-27 補上擊中前的條件（毒慾、星律、孤圍、噬寒、乘虛…）、
     * 鼎元訣、零點射擊／遠射、掠獸（這次之前只看角色屬性表上的十幾種）。
     */
    public static List<String> conditionalNames(final StatSheet sheet) {
        final List<String> names = new ArrayList<>();
        if (StatKeys.flag(sheet, tw.exilecore.stats.LineageKeys.LOW_LIFE_ONLY)) {
            names.add(BEASTRITE); // 放最前面
        }
        final String[][] table = {
                {StatKeys.DAMAGE_VS_LOW_LIFE, "對低血的敵人"}, {StatKeys.DAMAGE_VS_STUNNED, "對重暈的敵人"},
                {StatKeys.DAMAGE_VS_FULL_LIFE, "對滿血的敵人"}, {StatKeys.CRIT_VS_FULL_LIFE, "對滿血的敵人"},
                {StatKeys.DAMAGE_VS_RARE, "對稀有、傳奇"}, {StatKeys.DAMAGE_VS_UNIQUE, "對稀有、傳奇"},
                {StatKeys.DAMAGE_VS_CURSED, "對詛咒的敵人"}, {StatKeys.DAMAGE_CLOSE, "近身"},
                {StatKeys.DAMAGE_VS_AILMENT + "chilled", "對異常的敵人"}, {StatKeys.DAMAGE_VS_AILMENT + "frozen", "對異常的敵人"},
                {StatKeys.DAMAGE_VS_AILMENT + "ignited", "對異常的敵人"}, {StatKeys.DAMAGE_VS_AILMENT + "shocked", "對異常的敵人"},
                {StatKeys.DAMAGE_VS_AILMENT + "poisoned", "對異常的敵人"}, {StatKeys.DAMAGE_VS_AILMENT + "bleeding", "對異常的敵人"},
                {StatKeys.DAMAGE_HIGH_MANA, "魔力充沛"}, {StatKeys.DAMAGE_CLASH, "背水"},
                {StatKeys.DAMAGE_PER_COMBO, "連擊"}, {StatKeys.COMBO_FINISHER, "終式"}, {StatKeys.RAGEFORGED, "怒鑄"},
                {tw.exilecore.charge.ChargeService.SUP_INHIBIT, "抑星"},
                {tw.exilecore.ascendancy.AscKeys.WEAKNESS, "開放弱點"}, {tw.exilecore.ascendancy.AscKeys.NO_MERCY, "毫不留情"},
                {tw.exilecore.ascendancy.AscKeys.BLOOD_BOIL_MORE, "血液沸騰"},
                // 2026-10-27：擊中前看目標或最近狀態的（SupportMechanics.preHit、LineageMechanics.preHit、SupportExtras.preHit）
                {tw.exilecore.stats.LineageKeys.PER_POISON, "毒慾（敵人身上的中毒）"},
                {tw.exilecore.stats.LineageKeys.PER_COOLDOWN_SKILL, "星律（最近用過的冷卻技能）"},
                {tw.exilecore.stats.LineageKeys.PER_COMMAND, "令福（最近用過的指令技能）"},
                {tw.exilecore.stats.LineageKeys.VS_ISOLATED, "孤圍（孤立的敵人）"},
                {tw.exilecore.stats.LineageKeys.CONSUME_FREEZE, "霜換（凍結的敵人）"},
                {tw.exilecore.stats.LineageKeys.LIFE_FLASK_MORE, "聖杯（生命藥劑生效時）"},
                {tw.exilecore.stats.LineageKeys.RHYTHM, "律勁"},
                {tw.exilecore.stats.LineageKeys.CRIT_PER_5_RAGE, "怒準（怒氣）"},
                {tw.exilecore.stats.SupportExtraKeys.CONSUME_FREEZE, "噬寒（凍結的敵人）"},
                {tw.exilecore.stats.SupportExtraKeys.CONSUME_BROKEN, "乘虛（完全破甲的敵人）"},
                {tw.exilecore.stats.SupportExtraKeys.CRIT_VS_BLINDED, "攻其不備（致盲的敵人）"},
                {tw.exilecore.stats.SupportExtraKeys.PURSUIT, "追擊（最近投射物擊中過）"},
                {tw.exilecore.stats.SupportExtraKeys.RETREAT, "撤擊（最近近戰擊中過）"},
                {tw.exilecore.combat.Trinity.MORE_STAT, "鼎元訣（親和）"},
                {tw.exilecore.ascendancy.AscendancyMechanics.POINT_BLANK, "零點射擊（距離）"},
                {tw.exilecore.ascendancy.AscendancyMechanics.FAR_SHOT, "遠射（距離）"},
        };
        for (final String[] row : table) {
            if (names.contains(row[1])) {
                continue;
            }
            for (final Modifier mod : sheet.modifiersOf(row[0])) {
                if (Math.abs(mod.value()) > EPSILON) {
                    names.add(row[1]);
                    break;
                }
            }
        }
        return names;
    }

    public static Lines of(final ExileCore plugin, final Player player) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet now = liveSheet(plugin, player);
        final StatSheet rawBase = plugin.players().baselineSheet(player);
        // 2026-10-27：「沒有增益」那張也疊上不是增益的（減益、季節循環、痛極生力…），比較時才只剩增益的差別
        final List<Modifier> baseExtra = situational(plugin, player, rawBase);
        final StatSheet base = baseExtra.isEmpty() ? rawBase : rawBase.overlay(baseExtra);
        return new Lines(defence(defenceOverlay(plugin, player, now), defenceOverlay(plugin, player, base)),
                offence(now, base, state.weaponTags()));
    }

    // ---- 防禦 ----

    private static List<String> defence(final StatSheet now, final StatSheet base) {
        final List<String> lines = new ArrayList<>();
        number(lines, StatsReport.LIFE + "生命上限", PlayerState.maxLife(base), PlayerState.maxLife(now));
        number(lines, StatsReport.MANA + "魔力上限", PlayerState.maxMana(base), PlayerState.maxMana(now));
        number(lines, StatsReport.ES + "能量護盾", PlayerState.maxEnergyShield(base), PlayerState.maxEnergyShield(now));
        number(lines, "<#c9a7ff>精魂上限", Math.max(0, base.get(StatKeys.SPIRIT_MAX)), Math.max(0, now.get(StatKeys.SPIRIT_MAX)));
        decimal(lines, StatsReport.LIFE + "生命回復/秒", lifeRegen(base), lifeRegen(now));
        decimal(lines, StatsReport.MANA + "魔力再生/秒", base.get(StatKeys.MANA_REGEN), now.get(StatKeys.MANA_REGEN));
        number(lines, "<gray>護甲", base.get(StatKeys.ARMOUR), now.get(StatKeys.ARMOUR));
        number(lines, "<gray>閃避", base.get(StatKeys.EVASION), now.get(StatKeys.EVASION));
        number(lines, "<gray>偏斜", deflection(base), deflection(now));
        // 2026-10-27：上限含「格擋上限 +x%」，最多 90%（PoE2；這次之前固定 50）
        percentPoints(lines, StatsReport.BLOCK + "格擋", Math.min(tw.exilecore.stats.DamageCalculator.blockCap(base), base.get(StatKeys.BLOCK_CHANCE)),
                Math.min(tw.exilecore.stats.DamageCalculator.blockCap(now), now.get(StatKeys.BLOCK_CHANCE)));
        for (final DamageType type : DamageType.values()) {
            if (type == DamageType.PHYSICAL) {
                continue;
            }
            percentPoints(lines, "<" + tw.exilecore.combat.DamageNumbers.color(type) + ">" + type.displayName() + "抗性",
                    resist(base, type), resist(now, type));
        }
        percentPoints(lines, "<gray>抗性上限", base.get(StatKeys.RESIST_MAX), now.get(StatKeys.RESIST_MAX));
        return lines;
    }

    private static double lifeRegen(final StatSheet sheet) {
        return sheet.get(StatKeys.LIFE_REGEN) + PlayerState.maxLife(sheet) * sheet.get(StatKeys.LIFE_REGEN_PERCENT) / 100.0;
    }

    private static double deflection(final StatSheet sheet) {
        return tw.exilecore.stats.DamageCalculator.deflectionRating(sheet); // 2026-10-27：增加偏斜也乘閃避轉來的
    }

    private static double resist(final StatSheet sheet, final DamageType type) {
        return Math.min(sheet.get(type.mitigationStat()), DamageCalculator.resistCap(sheet, type)); // 2026-10-20：含這一種自己的上限
    }

    // ---- 攻擊 ----

    private static List<String> offence(final StatSheet now, final StatSheet base, final Set<String> weaponTags) {
        final List<String> lines = new ArrayList<>();
        final SkillProfile attack = SkillProfile.defaultAttack(weaponTags);
        final double baseHit = DamageCalculator.expectedOutgoing(base, attack).total();
        final double nowHit = DamageCalculator.expectedOutgoing(now, attack).total();
        number(lines, "<gray>普攻平均傷害", baseHit, nowHit);
        decimal2(lines, "<gray>普攻每秒次數", DamageCalculator.attacksPerSecond(base, attack.tags()), DamageCalculator.attacksPerSecond(now, attack.tags()));
        percentPoints1(lines, "<gray>普攻暴擊率", DamageCalculator.critChance(base, attack) * 100, DamageCalculator.critChance(now, attack) * 100);
        percentPoints(lines, "<gray>暴擊傷害", DamageCalculator.critMultiplier(base, attack.tags(), null) * 100,
                DamageCalculator.critMultiplier(now, attack.tags(), null) * 100);
        number(lines, "<gray>普攻估計每秒傷害", attackDps(base, attack), attackDps(now, attack));
        final SkillProfile spell = new SkillProfile("stats_spell", Set.of(Tags.SPELL), 1.0, null);
        percentPoints1(lines, "<gray>法術暴擊率", DamageCalculator.critChance(base, spell) * 100, DamageCalculator.critChance(now, spell) * 100);
        number(lines, "<gray>命中", base.get(StatKeys.ACCURACY), now.get(StatKeys.ACCURACY));
        // 傷害倍率：先看不分種類的，再看各種類多出來的（跟不分種類一樣就不重複列）
        final double general = ratio(base, now, StatKeys.DAMAGE, Set.of());
        relative(lines, "<white>傷害（全部）", general);
        final String[][] contexts = {{Tags.ATTACK, "攻擊傷害"}, {Tags.SPELL, "法術傷害"}};
        for (final String[] context : contexts) {
            final double r = ratio(base, now, StatKeys.DAMAGE, Set.of(context[0]));
            if (Math.abs(r - general) > EPSILON) {
                relative(lines, "<white>" + context[1], r);
            }
        }
        for (final DamageType type : DamageType.values()) {
            final double r = ratio(base, now, StatKeys.DAMAGE, type.tags());
            if (Math.abs(r - general) > EPSILON) {
                relative(lines, "<" + tw.exilecore.combat.DamageNumbers.color(type) + ">" + type.displayName() + "傷害", r);
            }
        }
        for (final DamageType type : DamageType.values()) {
            if (type == DamageType.PHYSICAL) {
                continue;
            }
            percentPoints(lines, "<" + tw.exilecore.combat.DamageNumbers.color(type) + ">" + type.displayName() + "穿透",
                    base.get(StatKeys.PENETRATION, type.tags()), now.get(StatKeys.PENETRATION, type.tags()));
        }
        relative(lines, "<gray>施法速度", ratio(base, now, StatKeys.CAST_SPEED, Set.of(Tags.SPELL)));
        increasedPoints(lines, "<gray>移動速度", base.increased(StatKeys.MOVEMENT_SPEED, Set.of()), now.increased(StatKeys.MOVEMENT_SPEED, Set.of()));
        increasedPoints(lines, "<gray>技能範圍", base.increased(StatKeys.SKILL_AREA, Set.of()), now.increased(StatKeys.SKILL_AREA, Set.of()));
        increasedPoints(lines, "<gray>持續時間", base.increased(StatKeys.SKILL_DURATION, Set.of()), now.increased(StatKeys.SKILL_DURATION, Set.of()));
        increasedPoints(lines, "<gray>冷卻時間", base.increased(StatKeys.SKILL_COOLDOWN, Set.of()), now.increased(StatKeys.SKILL_COOLDOWN, Set.of()));
        percentPoints1(lines, "<gray>物理偷取生命", base.get(StatKeys.LEECH_LIFE), now.get(StatKeys.LEECH_LIFE));
        number(lines, "<gray>擊中回復生命", base.get(StatKeys.LIFE_ON_HIT), now.get(StatKeys.LIFE_ON_HIT));
        return lines;
    }

    /** 跟 StatsReport 的「估計每秒傷害」同一個算法。 */
    private static double attackDps(final StatSheet sheet, final SkillProfile attack) {
        final double crit = DamageCalculator.critChance(sheet, attack);
        final double multi = DamageCalculator.critMultiplier(sheet, attack.tags(), null);
        return DamageCalculator.expectedOutgoing(sheet, attack).total() * DamageCalculator.attacksPerSecond(sheet, attack.tags())
                * (1 + crit * (multi - 1));
    }

    /** 這個屬性的（增加 × 更多）倍率，現在比沒有增益時大幾倍。 */
    private static double ratio(final StatSheet base, final StatSheet now, final String stat, final Set<String> tags) {
        final double before = base.apply(stat, 1.0, tags);
        final double after = now.apply(stat, 1.0, tags);
        if (before <= EPSILON) {
            return after <= EPSILON ? 1.0 : Double.POSITIVE_INFINITY;
        }
        return after / before;
    }

    // ---- 格式 ----

    private static String arrow(final double delta) {
        return delta > 0 ? "<green>" : "<red>";
    }

    /** 整數：「生命上限 1,000 → 1,200（+200）」。 */
    private static void number(final List<String> into, final String label, final double before, final double after) {
        final long b = Math.round(before);
        final long a = Math.round(after);
        if (a == b) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <white>%,d <dark_gray>→ %s%,d <dark_gray>（%s%,d）", label, b, arrow(a - b), a, a > b ? "+" : "−", Math.abs(a - b)));
    }

    /** 一位小數：回復速度。 */
    private static void decimal(final List<String> into, final String label, final double before, final double after) {
        if (Math.abs(after - before) < 0.05) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <white>%.1f <dark_gray>→ %s%.1f", label, before, arrow(after - before), after));
    }

    /** 兩位小數：每秒次數。 */
    private static void decimal2(final List<String> into, final String label, final double before, final double after) {
        if (Math.abs(after - before) < 0.005) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <white>%.2f <dark_gray>→ %s%.2f", label, before, arrow(after - before), after));
    }

    /** 百分比點數（整數）：抗性、格擋、暴擊傷害。 */
    private static void percentPoints(final List<String> into, final String label, final double before, final double after) {
        final long b = Math.round(before);
        final long a = Math.round(after);
        if (a == b) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <white>%d%% <dark_gray>→ %s%d%%", label, b, arrow(a - b), a));
    }

    /** 百分比點數（一位小數）：暴擊率、偷取。 */
    private static void percentPoints1(final List<String> into, final String label, final double before, final double after) {
        if (Math.abs(after - before) < 0.05) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <white>%.1f%% <dark_gray>→ %s%.1f%%", label, before, arrow(after - before), after));
    }

    /** 「增加 x%」的總和：移動速度 +10% → +30%。 */
    private static void increasedPoints(final List<String> into, final String label, final double before, final double after) {
        final long b = Math.round(before * 100);
        final long a = Math.round(after * 100);
        if (a == b) {
            return;
        }
        // 冷卻時間：增加是壞事（顏色反過來）
        final boolean good = label.contains("冷卻") ? a < b : a > b;
        into.add(String.format(Locale.ROOT, "%s <white>%s <dark_gray>→ %s%s", label, signed(b), good ? "<green>" : "<red>", signed(a)));
    }

    /** 倍率：「火焰傷害 更多 +18%」（相對沒有增益的時候）。 */
    private static void relative(final List<String> into, final String label, final double ratio) {
        if (Double.isInfinite(ratio)) {
            into.add(label + " <green>（原本是 0）");
            return;
        }
        final long percent = Math.round((ratio - 1) * 100);
        if (percent == 0) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <dark_gray>比沒有增益時 %s%s%d%%", label, percent > 0 ? "<green>多 " : "<red>少 ", "", Math.abs(percent)));
    }

    private static String signed(final long percent) {
        return (percent >= 0 ? "+" : "−") + Math.abs(percent) + "%";
    }
}
