package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.SupportExtraKeys;
import tw.exilecore.stats.Tags;

import java.util.HashSet;
import java.util.Set;

/**
 * 2026-10-27 照站長（防呆）：PoE2 0.5 補上的一般輔助寶石，掛上去完全沒有作用的技能就不讓掛（{@link SupportDefinition#refusal} 呼叫）。
 * 條件照 RePoE 的 allowed_types／excluded_types 對到我們的機制；回傳 null = 可以掛。
 * 要看 skills.yml 其他設定的（有沒有條件、會不會留殘骸）在 {@link #index(SkillConfig)} 讀設定時記下來。
 */
public final class SupportGuards {

    private SupportGuards() {
    }

    /** 有使用條件的技能（要榮耀、要連擊才能用）。 */
    private static volatile Set<String> conditional = Set.of();
    /**
     * 會留下殘骸的技能（resource.remnant；我們的殘骸是元素灌注殘骸）。2026-10-27：加上自己在程式裡留殘骸的（響指、素痕湧、燼種），
     * 拿掉魔泉湧（我們的魔泉湧不會留殘骸；這次之前殘骸輔助是「放技能後撿的殘骸都算」，掛在它上面也有作用）。
     */
    private static volatile Set<String> remnants = Set.of();
    /** 2026-10-30：有耐久（用幾次就消失）的物件的技能：鐘、雷樁矢／電鍊箭矢插的箭、龍捲風。 */
    private static volatile Set<String> durables = Set.of();
    /** 2026-10-28：resource.combo_gain 的技能（打中就累積連擊；頂點不能掛）。 */
    private static volatile Set<String> comboGainers = Set.of();

    /** 讀完 skills.yml（SkillConfig.load 呼叫）：記下有使用條件、會留殘骸的技能。 */
    static void index(final SkillConfig config) {
        final Set<String> withCondition = new HashSet<>();
        final Set<String> withRemnant = new HashSet<>();
        final Set<String> gainers = new HashSet<>();
        for (final SkillDefinition skill : config.all()) {
            final ResourceSpec resource = config.resource(skill.id());
            if (resource != null && resource.comboGain()) {
                gainers.add(skill.id());
            }
            if (resource != null && (resource.glory() > 0 || resource.combo() > 0)
                    || skill.warcry() != null && skill.warcry().extras().glory() > 0) {
                withCondition.add(skill.id());
            }
            if (resource != null && resource.remnant() != null || CODE_REMNANTS.contains(skill.id())) {
                withRemnant.add(skill.id());
            }
        }
        final Set<String> withDurability = new HashSet<>();
        for (final SkillDefinition skill : config.all()) {
            // 2026-10-30 照 PoE2 Durability：有「用幾次就消失」的物件（鐘、雷樁矢的箭、龍捲風）
            if (config.rod(skill.id()) != null || config.tornado(skill.id()) != null
                    || skill.deploy() != null && skill.deploy().rings() > 0) {
                withDurability.add(skill.id());
            }
        }
        durables = Set.copyOf(withDurability);
        conditional = Set.copyOf(withCondition);
        remnants = Set.copyOf(withRemnant);
        comboGainers = Set.copyOf(gainers);
    }

    /** 2026-10-27：不寫 resource.remnant、在程式裡留殘骸的技能（Snap、SkillTriggers 的素痕湧、OriginalSkills 的燼種）。 */
    /** 2026-11-08：會留下殘骸的技能（殘骸輔助、拾息共用）。 */
    static boolean makesRemnants(final String skillId) {
        return remnants.contains(skillId) || CODE_REMNANTS.contains(skillId);
    }

    static final Set<String> CODE_REMNANTS = Set.of("anomaly_devour_blast", "element_trace_surge", "cinder_sprout",
            "magic_spring_well", // 2026-11-01 照 PoE2 Mana Remnants（Remnant 標籤）：魔力痕跡吃殘骸輔助
            "vital_residue"); // 2026-11-02 照 PoE2 Life Remnants（Remnant 標籤）：生命痕跡吃殘骸輔助（AscGranted.spawnLifeRemnant）

    /** 這個技能有沒有使用條件（Delayed Gratification）。 */
    static boolean conditional(final SkillDefinition skill) {
        return conditional.contains(skill.id());
    }

    /** 盾牌技能（Vanguard：要盾的攻擊）。 */
    static boolean shieldSkill(final SkillDefinition skill) {
        return skill.isAttack() && (skill.id().contains("shield") || Warcries.SHIELD_ATTACKS.contains(skill.id())) || Warcries.needsShield(skill); // 2026-10-31：盾的技能改了代號，不一定含 shield
    }

    /** 會造成非異常持續傷害（混沌減益、地面效果）的技能。 */
    static boolean dot(final SkillDefinition skill) {
        if (skill.mechanic() == Mechanic.ZONE) {
            return true;
        }
        for (final Modifier modifier : skill.modifiers()) {
            if ((modifier.stat().equals(StatKeys.DEBUFF_CHAOS) || modifier.stat().equals(tw.exilecore.stats.SkillMechanicKeys.DEBUFF_CHAOS_BASE))
                    && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 2026-11-01：這顆輔助有沒有觸發 subId 這個隱藏技能（proc.事件 的修飾器，標籤是技能代號）。 */
    private static boolean triggers(final SupportDefinition support, final String subId) {
        for (final Modifier modifier : support.modifiers()) {
            if (modifier.stat().startsWith(SupportProcs.PREFIX) && modifier.tags().contains(subId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean has(final SkillDefinition skill, final String stat) {
        for (final Modifier modifier : skill.modifiers()) {
            if (modifier.stat().equals(stat) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 這種元素（或攻擊：武器、裝備可以加那種元素）。 */
    private static boolean element(final SkillDefinition skill, final String tag) {
        return skill.tags().contains(tag) || skill.isAttack();
    }

    /** 不能掛的原因（null = 可以）。 */
    static String refusal(final SupportDefinition support, final SkillDefinition skill) {
        final Set<String> tags = skill.tags();
        final Mechanic mechanic = skill.mechanic();
        if (support.flags(SupportExtraKeys.DAZE_PHYSICAL_CRIT) && !tags.contains(Tags.PHYSICAL)) {
            return "要會造成物理傷害的法術";
        }
        if (support.flags(SupportExtraKeys.EXPOSURE_ON_IGNITE) && !element(skill, Tags.FIRE)) {
            return "要會點燃的技能（火焰技能或攻擊）";
        }
        if (support.flags(SupportExtraKeys.STORMFIRE) && !element(skill, Tags.FIRE)) {
            return "要會點燃的技能（火焰技能或攻擊）";
        }
        if (support.flags(SupportExtraKeys.WILDFIRE) && !element(skill, Tags.FIRE)) {
            return "要會點燃的技能（火焰技能或攻擊）";
        }
        if (support.flags(SupportExtraKeys.EXPOSURE_ON_COLD_CRIT) && !element(skill, Tags.COLD)) {
            return "要會造成冰冷傷害的技能（冰冷技能或攻擊）";
        }
        if ((support.flags(SupportExtraKeys.EXPOSURE_ON_SHOCK) || support.flags(SupportExtraKeys.SHOCK_NEARBY)) && !element(skill, Tags.LIGHTNING)) {
            return "要會造成閃電傷害的技能（閃電技能或攻擊）";
        }
        if (support.flags(SupportExtraKeys.FLAG_MALADY) && !has(skill, StatKeys.BLEED_CHANCE) && !has(skill, StatKeys.POISON_CHANCE)) {
            return "技能本身要有流血或中毒機率";
        }
        if ((support.flags(SupportExtraKeys.DRAIN_AILMENTS) || support.flags(SupportExtraKeys.ACRIMONY) || support.flags(SupportExtraKeys.DERANGE)
                || support.flags(SupportExtraKeys.FLAG_CHAOTIC_FREEZE)) && !dot(skill)) {
            return "要會造成持續傷害的技能（地面效果、混沌減益）";
        }
        if (support.flags(SupportExtraKeys.FLAG_CHAOTIC_FREEZE) && !tags.contains(Tags.CHAOS)) {
            return "要混沌技能";
        }
        if (support.flags(SupportExtraKeys.CONCOCT) && !skill.isAttack() && !has(skill, StatKeys.BLEED_CHANCE)) {
            return "要會流血的技能（攻擊，或本身有流血機率）";
        }
        if (support.flags(SupportExtraKeys.TUMULT) && !skill.isAttack() && !tags.contains(Tags.PHYSICAL)) {
            return "要會造成物理傷害的技能";
        }
        if (support.flags(SupportExtraKeys.VANGUARD) && !shieldSkill(skill)) {
            return "要盾牌技能";
        }
        if (support.flags(SupportExtraKeys.VOLATILITY_ON_EMPOWERED) && !skill.isAttack()) {
            return "要會被強化的攻擊";
        }
        if ((support.flags(SupportExtraKeys.ANCESTRAL_AID) || support.flags(SupportExtraKeys.ANCESTRAL_CALL))
                && (mechanic != Mechanic.STRIKE || !skill.isAttack())) {
            return "要打擊技能";
        }
        // 2026-10-27 照 PoE2（PerformsFinalStrike）：有最後一擊的打擊
        if ((support.flags(SupportExtraKeys.CRESCENDO) || support.flags(SupportExtraKeys.RENDING_APEX))
                && !SupportExtraKeys.FINAL_STRIKE_SKILLS.containsKey(skill.id())) {
            return "要有最後一擊的打擊（寒冰震波、原獸連擊、風暴連擊、撕咬）";
        }
        // 2026-10-27 照 PoE2（CommandableMinion）：要有指令的召喚物
        // 2026-11-01 照 PoE2：六種骷髏、掩護火力、鴉群密謀、三位巨靈（Commandables.SKILLS）；這次之前只有 MinionAbilities 的指令表（不含鴉群、巨靈）
        if (support.flags(SupportExtraKeys.FLAG_COMMANDMENT) && !Commandables.commandable(skill.id())) {
            return "要有指令的召喚物";
        }
        if ((support.flags(SupportExtraKeys.ANCESTRAL_AID) || support.flags(SupportExtraKeys.DAUNTLESS)
                || support.flags(SupportExtraKeys.CATHARSIS)) && conditional(skill)) {
            return "本來就有使用條件的技能不行";
        }
        // 2026-10-28 照 PoE2 Culmination（頂點）：要近戰攻擊（Attack AND Melee）；本來就會獲得連擊的（打擊、resource.combo_gain）、
        //   有使用條件的（HasUsageCondition：要榮耀、連擊才能用）不行；召喚物另外由 excludes 擋
        if (support.flags(SupportExtraKeys.CULMINATION)) {
            if (!skill.isAttack() || !tags.contains(Tags.MELEE)) {
                return "要近戰攻擊";
            }
            if (tags.contains(Tags.STRIKE) || comboGainers.contains(skill.id())) {
                return "本來就會獲得連擊的技能不行";
            }
            if (conditional(skill)) {
                return "本來就有使用條件的技能不行";
            }
        }
        // 2026-10-29 照 PoE2 Hit and Run：只能輔助原本沒有使用條件的技能
        if (support.flags(tw.exilecore.stats.SupportKeys.HIT_AND_RUN) && conditional(skill)) {
            return "本來就有使用條件的技能不行";
        }
        if (support.flags(SupportExtraKeys.GRATIFICATION) && !conditional(skill)) {
            return "要有使用條件的技能（要榮耀、連擊才能用的）";
        }
        if ((support.flags(SupportExtraKeys.PERFECT_MORE) || support.flags(SupportExtraKeys.PERFECTION))
                && !SupportDefinition.PERFECT_TIMING.contains(skill.id())) {
            return "要有完美時機的技能（蓄勁一擊、穿雲箭）";
        }
        if (support.flags(SupportExtraKeys.FRESH_CLIP) && !skill.weapons().contains("crossbow")) {
            return "要弩的技能";
        }
        if ((support.flags(SupportExtraKeys.CHARGED_SHOTS) || support.flags(SupportExtraKeys.ALIGNMENT))
                && (!skill.weapons().contains("bow") || mechanic != Mechanic.PROJECTILE)) {
            return "要弓的投射物攻擊";
        }
        if (support.flags(SupportExtraKeys.FLAG_SPECTRAL) && ((!skill.weapons().contains("bow") && !skill.weapons().contains("spear"))
                || mechanic != Mechanic.PROJECTILE)) {
            return "要弓或長矛的投射物攻擊";
        }
        // 2026-11-01 照 PoE2 Caltrops（撒蒺）：Spear AND RangedAttack（長矛的投射物攻擊）；這次之前所有投射物攻擊
        if (triggers(support, tw.exilecore.stats.SupportRemakeKeys.CALTROPS_PROC)
                && (!skill.weapons().contains("spear") || !skill.isAttack() || mechanic != Mechanic.PROJECTILE)) {
            return "要長矛的投射物攻擊";
        }
        // 2026-11-01 照 PoE2 Hulking Minions（巨魄）：Minion AND Persistent（叫出持續型召喚物的技能）；這次之前所有召喚技能
        if (support.flags(tw.exilecore.stats.SupportRemakeKeys.MINIONS_GIGANTIC)
                && (skill.summon() == null || !tags.contains(Tags.MINION) || !tags.contains(Tags.PERSISTENT))) {
            return "要叫出持續型召喚物的技能";
        }
        if (support.flags(SupportExtraKeys.WILDSHARDS) && (!tags.contains(Tags.SPELL) || mechanic != Mechanic.PROJECTILE)) {
            return "要射出投射物的法術";
        }
        // 2026-10-28 照 PoE2：Spell Echo 的 allowed_types 只有 Cascadable（雙詠和照它做的終局版都一樣）；這次之前所有法術（排除位移、印記…）都能插
        if (support.flags(StatKeys.FLAG_ECHO) && !cascadable(skill)) {
            return "要可以連瀑的範圍法術（落在目標區域、身邊一圈的，或詛咒）";
        }
        if (support.flags(SupportExtraKeys.CASCADE) && !cascadable(skill)) {
            return "要可以連瀑的範圍法術（落在目標區域、身邊一圈的，或詛咒）"; // 2026-10-28 照 PoE2 Cascadable；這次之前「要落在目標區域或身邊一圈的範圍法術」
        }
        if (support.flags(SupportExtraKeys.FLAG_ASTRAL) && mechanic != Mechanic.NOVA) {
            return "要繞身邊一圈的技能（新星）";
        }
        if ((support.flags(SupportExtraKeys.FISSURE_BRANCHES) || support.flags(SupportExtraKeys.FISSURE_EXTRA))
                && !tags.contains(tw.exilecore.stats.KeystoneKeys.FISSURE)) {
            return "要裂地技能";
        }
        if ((support.flags(SupportExtraKeys.ENCROACH) || support.flags(SupportExtraKeys.RIME)) && mechanic != Mechanic.ZONE) {
            return "要留下地面效果的技能";
        }
        if (support.flags(SupportExtraKeys.RIME) && !tags.contains(Tags.COLD)) {
            return "要冰冷的地面效果";
        }
        if (support.flags(SupportExtraKeys.FLAG_FORTRESS) && (skill.params().zone() == null || !skill.params().zone().line())) {
            return "要一道牆的技能";
        }
        if (support.flags(SupportExtraKeys.HOLY_DESCENT) && mechanic != Mechanic.LEAP) {
            return "要跳躍技能";
        }
        if (support.flags(SupportExtraKeys.DEADLY_RESOLVE) || support.flags(SupportExtraKeys.CRACKLING) || prefixed(support, SupportExtraKeys.CHANNELLING_PREFIX)) {
            if (!SupportDefinition.channels(skill)) {
                return "要引導技能"; // 2026-11-01 照站長：引導不叫蓄力
            }
        }
        if ((prefixed(support, SupportExtraKeys.USING_QUARTER_PREFIX) || support.flags(SupportExtraKeys.STOICISM)) && SupportDefinition.channels(skill)) {
            return "引導技能不行"; // 2026-11-01 照站長：引導不叫蓄力
        }
        if (prefixed(support, SupportExtraKeys.REMNANT_PREFIX) && !remnants.contains(skill.id())) {
            return "要會留下殘骸的技能";
        }
        if (support.flags(SupportExtraKeys.GORGE) && mechanic != Mechanic.CORPSE && !tags.contains("corpse")) {
            return "要用屍體的技能";
        }
        if (support.flags(SupportExtraKeys.LOYALTY) && !tags.contains(tw.exilecore.stats.TreeMechanicKeys.COMPANION)) {
            return "要叫出夥伴的技能";
        }
        // 2026-10-30 照 PoE2 Loyalty（excluded_types MinionsAreUndamagable）：不會受傷的夥伴（幻化武器）不行；這次之前掛得上去但沒有作用
        if (support.flags(SupportExtraKeys.LOYALTY) && skill.summon() != null && skill.summon().immortal()) {
            return "夥伴不會受傷的技能不行";
        }
        // 2026-10-28：常駐召喚（骷髏、殭屍…）沒有 persistent 標籤，改看召喚本身是不是常駐（LineageRules.persistentMinion）；這次之前常駐召喚都插不進去
        if ((support.flags(SupportExtraKeys.LAST_GASP) || support.flags(SupportExtraKeys.CRAZED)) && !tags.contains("persistent")
                && !LineageRules.persistentMinion(skill)) {
            return "要常駐的召喚物（保留精魂的）";
        }
        // 2026-11-01 照 PoE2 Essence Harvest（魂收：allowed Minion AND Persistent）：只能掛在叫出會復活的常駐召喚物的技能；這次之前任何召喚技能
        if (support.flags(StatKeys.MANA_ON_MINION_DEATH) && !tags.contains("persistent") && !LineageRules.persistentMinion(skill)) {
            return "要會復活的常駐召喚物技能";
        }
        if (support.flags(SupportExtraKeys.MARK_SIPHON) && tags.contains("curse")) {
            return "詛咒不行，要印記";
        }
        if ((support.flags(SupportExtraKeys.CURSED_GROUND) || support.flags(SupportExtraKeys.HEX_BLOOM)) && !tags.contains("curse")) {
            return "要詛咒技能";
        }
        if (support.flags(SupportExtraKeys.POTENTIAL) && SupportDefinition.consumes(skill, "power")) {
            return "本來就會消耗銳星的技能不行";
        }
        if (support.flags(SupportExtraKeys.TUMULT) && SupportDefinition.consumes(skill, "frenzy")) {
            return "本來就會消耗怒星的技能不行";
        }
        if (support.flags(SupportExtraKeys.RALLY) && SupportDefinition.consumes(skill, "endurance")) {
            return "本來就會消耗磐星的技能不行";
        }
        if (support.flags(SupportExtraKeys.CONSUME_FREEZE) && skill.id().equals("sunder")) {
            return "本來就會用掉凍結的技能不行";
        }
        if (support.flags(SupportExtraKeys.DURABILITY) && !durables.contains(skill.id())) {
            return "要留下有耐久物件的技能（鐘、雷樁矢、龍捲風）"; // 2026-10-30 照 PoE2；這次之前是留冰晶的技能（冰晶多打兩下）
        }
        if (support.flags(SupportExtraKeys.SLOW_POTENCY) && !slows(skill)) {
            return "要會緩速的技能（冰冷、詛咒、癱瘓、阻礙）";
        }
        if (support.flags(SupportExtraKeys.SECOND_WIND) && skill.cooldown() <= 0) {
            return "要有冷卻時間的技能";
        }
        return parityRefusal(support, skill);
    }

    /**
     * 2026-11-01 照 PoE2 的技能類型 Jumping（跳躍）：跳躍類機制（mechanic: leap），再加上原作是跳躍、我們用別的機制做的
     * （回槍躍＝Disengage、噬咬＝Devour、奔雷踏＝Stampede、霜浪＝Wave of Frost）。
     * 2026-11-01 照站長：躍退射刪掉，拿掉。
     */
    static final Set<String> JUMPING_SKILLS = Set.of("spear_recoil_leap", "gnawing_bite", "rushing_thunder_stomp", "frost_billow");

    /** 跳躍技能（PoE2 Jumping）。 */
    static boolean jumping(final SkillDefinition skill) {
        return skill.mechanic() == Mechanic.LEAP || JUMPING_SKILLS.contains(skill.id());
    }

    /**
     * 2026-11-01 照 PoE2 的技能類型 CreatesGroundEffect（產生地面效果）：Path of Building 0.5 匯出有這個類型的技能，對到我們的代號
     * （刺棘身＝Briarpatch、聖地＝Consecrate、地動＝Earthquake、熾息＝Flame Breath、瘴氣榴彈＝Gas Grenade、黑油雨＝Oil Barrage、火油榴彈＝Oil Grenade、
     * 蠻衝＝Rampage、奔雷踏＝Stampede、炎爆矛＝Explosive Spear、霜牙刺＝Fangs of Frost、蝕毒箭＝Gas Arrow、熾陽之矛＝Spear of Solaris、
     * 霆落矛＝Storm Lance、滾雷珠＝Ball Lightning、腐屍爆＝Decompose、退步凝晶＝Frozen Locus、引霆突＝Gathering Storm、霜星爆＝Ice Nova、
     * 焚流＝Incinerate、雷遁＝Lightning Warp、靈影奔騰＝Vivid Stampede）。
     * 2026-11-01 照 PoE2 0.5 逐一查（PoB2 act_*.lua、poe2db）：拿掉 3 個類型還留著、但 0.5 實際上不留任何地面的——
     * 退步凝晶＝Frozen Locus（0.4.0「No longer creates chilled ground」，只剩冰晶）、蠻衝＝Rampage（沒有任何地面的數值、也沒有持續時間類型）、
     * 熾息＝Flame Breath（只有 2 公尺的「焚燒範圍」，沒有地面的持續時間或效果，poe2db 的點燃地面、燃燒地面清單都沒有它）；
     * 掛上去原作也沒有東西可以拉長。這次之前這 3 個也算。
     */
    static final Set<String> GROUND_EFFECT_SKILLS = Set.of("thorn_body", "holy_ground", "ground_tremor", "miasma_grenade",
            "black_oil_rain", "kerosene_grenade", "rushing_thunder_stomp", "explosive_spear", "frost_tooth_stab", "venom_etch_arrow",
            "blazing_sun_spear", "thunderfall_spear", "rolling_thunder_orb", "rotting_corpse_blast", "thunder_lure_thrust",
            "frost_star_blast", "burning_current", "thunder_escape", "vivid_stampede");

    /** 這顆輔助有沒有觸發某個隱藏技能（proc.事件 的 tags 寫那個技能代號）。 */
    static boolean procs(final SupportDefinition support, final String hidden) {
        for (final Modifier modifier : support.modifiers()) {
            if (modifier.stat().startsWith(SupportProcs.PREFIX) && modifier.value() > 0 && modifier.tags().contains(hidden)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 2026-11-01 照 PoE2 Hinder（Contagion、Essence Drain、Soulrend、Profane Ritual）：{@link #dot} 再加上逐級寫的混沌減益
     * （level_modifiers 的 debuff.chaos／debuff.chaos_base：汲魂彈、疫潮、輪迴禮）；這次之前只看 modifiers，這幾個被拒絕。
     */
    static boolean chaosDot(final SkillDefinition skill) {
        if (dot(skill)) {
            return true;
        }
        final SkillConfig config = SkillConfig.current();
        if (config == null) {
            return false;
        }
        for (final ScaledMod mod : config.levelModifiers(skill.id())) {
            if (mod.stat().equals(StatKeys.DEBUFF_CHAOS) || mod.stat().equals(tw.exilecore.stats.SkillMechanicKeys.DEBUFF_CHAOS_BASE)) {
                return true;
            }
        }
        return false;
    }

    /** 2026-11-01 照 PoE2 Hinder：這顆輔助讓混沌持續傷害阻礙敵人（hinder.dot；這次之前是擊中時觸發 proc_hinder）。 */
    static boolean hinders(final SupportDefinition support) {
        if (procs(support, "proc_hinder")) {
            return true;
        }
        for (final Modifier modifier : support.modifiers()) {
            if (modifier.stat().equals(tw.exilecore.stats.SupportSystemKeys.HINDER_DOT) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 2026-11-01：{@link #parityRefusal} 那幾顆的「可以輔助：」說明（不是那幾顆回傳 null）。 */
    static String parityText(final SupportDefinition support) {
        if ("split_rampart".equals(support.id())) {
            // 2026-11-03 照站長（裂垣看起來像什麼範圍技能都能掛）：實際只掛得上一字排開的牆（LineageRules.refusal）；這次之前寫「範圍技能（召喚物除外）」
            return "立牆的技能（焰幕、霜壁、土壁擊、凍壁矢）";
        }
        if (support.flags(tw.exilecore.stats.SupportRuleKeys.GROUND_DURATION)) {
            return "產生地面效果的技能（召喚物除外）";
        }
        if (procs(support, "proc_crater")) {
            return "跳躍的近戰技能（圖騰、召喚物除外）";
        }
        if (procs(support, "proc_charged_ground")) {
            return "跳躍技能（召喚物除外）";
        }
        if (hinders(support)) {
            return "會造成混沌持續傷害的法術（召喚物除外）";
        }
        return null;
    }

    /** 2026-11-01 照 PoE2 各輔助的 allowed_types／excluded_types（推擊、久地、熔坑擊、躍電、拖滯、不退）。 */
    private static String parityRefusal(final SupportDefinition support, final SkillDefinition skill) {
        // Persistent Ground：只輔助產生地面效果的技能（CreatesGroundEffect）
        if (support.flags(tw.exilecore.stats.SupportRuleKeys.GROUND_DURATION) && !GROUND_EFFECT_SKILLS.contains(skill.id())) {
            return "要產生地面效果的技能";
        }
        // Crater：近戰 AND 跳躍（不是猛擊就行）；Shocking Leap：跳躍
        if ((procs(support, "proc_crater") || procs(support, "proc_charged_ground")) && !jumping(skill)) {
            return "要跳躍技能";
        }
        // Hinder：只輔助造成非異常混沌持續傷害的法術
        if (hinders(support) && (!skill.tags().contains(Tags.SPELL) || !skill.tags().contains(Tags.CHAOS) || !chaosDot(skill))) {
            return "要會造成混沌持續傷害的法術（混沌地面效果、混沌減益）";
        }
        // Unyielding：要近戰攻擊（Attack AND Melee；召喚物的近戰標籤不算）
        if (support.flags("using_qs.life.recoup") && !skill.isAttack()) {
            return "要近戰攻擊";
        }
        return null;
    }

    /** 會緩速的技能：冰冷（冰緩）、詛咒、會癱瘓或阻礙的。 */
    static boolean slows(final SkillDefinition skill) {
        return skill.tags().contains(Tags.COLD) || skill.tags().contains("curse") || has(skill, StatKeys.MAIM_CHANCE) || has(skill, StatKeys.HINDER_ON_HIT)
                || has(skill, StatKeys.HINDER_CHANCE);
    }

    /**
     * PoE2 Cascadable。2026-10-28 照 PoE2：改看 SkillCascade（原作有對應的照原作：詛咒可以、雷雲、烈陽珠、燼種、疫潮這些不行）；
     * 這次之前只看形狀（落在目標區域的範圍法術、繞身邊一圈的法術）。
     */
    static boolean cascadable(final SkillDefinition skill) {
        return SkillCascade.cascadable(skill);
    }

    /** 這顆輔助有沒有某個開頭的屬性。 */
    static boolean prefixed(final SupportDefinition support, final String prefix) {
        for (final Modifier modifier : support.modifiers()) {
            if (modifier.stat().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
