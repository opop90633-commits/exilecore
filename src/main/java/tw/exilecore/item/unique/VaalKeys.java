package tw.exilecore.item.unique;

import java.util.Set;

/**
 * 2026-11-07 照站長「瓦爾通貨、栽培傳奇全部照原作」：瓦爾傳奇（新的 18 件、48 件的瓦爾詞綴）與遠古魂核用到的新屬性鍵。
 * 每個鍵的語意見各自讀它的地方；這裡集中登記（資料檢查 {@link UniqueStats#all()}、本地屬性 {@link UniqueStats#LOCAL_ITEM}）。
 */
public final class VaalKeys {

    private VaalKeys() {
    }

    public static final String AILMENT_MAGNITUDE_SPELL_PER_100_LIFE = "ailment.magnitude_spell_per_100_life";
    public static final String AILMENT_SELF_DURATION_POISON = "ailment.self_duration.poison";
    public static final String ARMOUR_CHAOS = "armour.chaos";
    public static final String ARMOUR_FROM_LIFE_LOST_8S = "armour.from_life_lost_8s";
    public static final String CHARGE_EXTRA_POWER = "charge.extra.power";
    public static final String CHARGE_POWER_EVERY_SECOND = "charge.power_every_second";
    public static final String CRIT_BONUS_PER_RECENT_SPELL_CRIT = "crit.bonus_per_recent_spell_crit";
    public static final String CRIT_SPELL_PER_100_MANA = "crit.spell_per_100_mana";
    public static final String DAMAGE_EXTRA_FIRE = "damage.extra.fire";
    public static final String DAMAGE_GAIN_IF_LIFE_COST_PHYSICAL = "damage.gain_if_life_cost.physical";
    public static final String DAMAGE_LUCKY_CRIT = "damage.lucky_crit";
    public static final String DAMAGE_NONCHANNEL_SPELL_PER_100_MANA = "damage.nonchannel_spell_per_100_mana";
    public static final String ES_LOSS_PERCENT = "es.loss_percent";
    public static final String ES_RECHARGE_ON_MANA_SPENT = "es.recharge_on_mana_spent";
    public static final String ES_RECOVERY_RATE = "es.recovery_rate";
    public static final String FLAG_ES_RECHARGE_ON_MINION_REFORM = "flag.es_recharge_on_minion_reform";
    public static final String FLAG_LEECH_EXCESS_TO_ES = "flag.leech_excess_to_es";
    public static final String FLAG_LIFE_FLASK_REMOVES_BLEED = "flag.life_flask_removes_bleed";
    public static final String FLAG_LIFE_RECHARGES = "flag.life_recharges";
    public static final String FLAG_LIGHTNING_RESIST_IGNORED = "flag.lightning_resist_ignored";
    public static final String FLAG_MOVEMENT_TO_ES_RECHARGE = "flag.movement_to_es_recharge";
    public static final String FLAG_NO_CHAIN = "flag.no_chain";
    public static final String FLAG_NO_ENERGY_SHIELD = "flag.no_energy_shield";
    public static final String FLAG_NO_FORK = "flag.no_fork";
    public static final String FLAG_NO_PIERCE = "flag.no_pierce";
    public static final String FLAG_OFFERING_EXPIRE_REVIVE = "flag.offering_expire_revive";
    public static final String FLAG_SOUL_EATER = "flag.soul_eater";
    public static final String GEM_CORRUPTED_LEVEL = "gem.corrupted_level";
    public static final String GEM_CORRUPTED_LIFE_COST_PERCENT = "gem.corrupted_life_cost_percent";
    public static final String GRANT_SKILL_VITAL_RESIDUE = "grant_skill.vital_residue";
    public static final String GUARD_ON_DODGE_SACRIFICE = "guard.on_dodge_sacrifice";
    public static final String JEWEL_ALLOCATE_KEYSTONES = "jewel.allocate_keystones";
    public static final String JEWEL_CORRUPTED_RARE_EFFECT = "jewel.corrupted_rare_effect";
    public static final String JEWEL_CRUCIBLE_LESS = "jewel.crucible_less";
    public static final String JEWEL_CRUCIBLE_LESS_KIND = "jewel.crucible_less_kind";
    public static final String JEWEL_LIMIT_ONE = "jewel.limit_one";
    public static final String JEWEL_RANDOM_KEYSTONE = "jewel.random_keystone";
    public static final String KILL_EXPLODE_CRIT = "kill.explode_crit";
    public static final String LIFE_LOW_FROM_MANA = "life.low_from_mana";
    public static final String LIFE_LOW_THRESHOLD = "life.low_threshold";
    public static final String LIFE_PER_POISON_ON_KILL = "life.per_poison_on_kill";
    public static final String LIGHT_RADIUS = "light.radius";
    public static final String LIGHT_RADIUS_AS_AREA = "light.radius_as_area";
    public static final String LOCAL_CORE_AS_FEET = "local.core_as.feet";
    public static final String LOCAL_CORE_AS_HEAD = "local.core_as.head";
    public static final String LOCAL_CORE_AS_LEGS = "local.core_as.legs";
    public static final String LOCAL_CORE_EXTRA_SLOT = "local.core_extra_slot";
    /** 2026-11-07：武器本地的（LocalMods.KNOWN，不在 {@link #LOCAL}）；ItemStats 換成 weapon.crit_override。 */
    public static final String LOCAL_CRIT_OVERRIDE = tw.exilecore.item.LocalMods.CRIT_OVERRIDE;
    /** 2026-11-07：武器本地的（LocalMods.KNOWN，不在 {@link #LOCAL}）；ItemStats 換成 weapon.no_crit_bonus。 */
    public static final String LOCAL_NO_CRIT_BONUS = tw.exilecore.item.LocalMods.NO_CRIT_BONUS;
    public static final String LOCAL_ONLY_SOUL_CORES = "local.only_soul_cores";
    public static final String MANA_LOW_FROM_LIFE = "mana.low_from_life";
    public static final String MANA_PER_3_HELMET_ARMOUR = "mana.per_3_helmet_armour";
    public static final String OFFERING_RECOUP_LIFE = "offering.recoup_life";
    public static final String PENETRATION_MINIMUM = "penetration.minimum";
    public static final String PER_ES_LIFE_REGEN = "per_es.life_regen";
    public static final String PER_STR_ES_RECHARGE = "per_str.es_recharge";
    public static final String POISON_CHANCE_TAKEN = "poison.chance_taken";
    public static final String PRESENCE_ALLY_LEECH_LIFE = "presence.ally_leech_life";
    public static final String PRESENCE_LIGHTNING_RESIST_EQUAL = "presence.lightning_resist_equal";
    public static final String SHAPESHIFT_COPY_MODS = "shapeshift.copy_mods";
    public static final String SHAPESHIFT_COPY_MODS_DURATION = "shapeshift.copy_mods_duration";
    public static final String SKILL_EXTRA_LIFE_COST_OF_MANA = "skill.extra_life_cost_of_mana";
    public static final String SOUL_EATER_ON_UNIQUE_HIT = "soul_eater.on_unique_hit";
    public static final String SURROUNDED_FEWER = "surrounded.fewer";
    public static final String TAKEN_ELEMENTAL_AS_CHAOS = "taken.elemental_as_chaos";
    public static final String TAKEN_VOLATILITY_PHYSICAL_AS_COLD = "taken.volatility_physical_as_cold";
    public static final String THESIS_GLORY_ON_MARK = "thesis.glory_on_mark";
    public static final String UNIQUE_IGNITED_KILL_EXPLODE = "unique.ignited_kill_explode";
    public static final String UNIQUE_SPELL_NOVA_CHANCE = "unique.spell_nova_chance";

    /**
     * 2026-11-07 照站長「瓦爾傳奇照原作」：技能標籤（不是屬性鍵）：這個技能自己消耗生命（{@link UniqueCosts#costsLife}，SkillBuild 加）；
     * 「消耗生命的法術獲得 x% 傷害為額外物理傷害」（{@link #DAMAGE_GAIN_IF_LIFE_COST_PHYSICAL}）照這個判斷。
     */
    public static final String TAG_COSTS_LIFE = "costs_life";

    /**
     * 這件物品自己的（local.）屬性。2026-11-07：local.crit_override、local.no_crit_bonus 拿出去了（武器本地的，LocalMods.KNOWN：
     * 資料檢查照武器本身的屬性擋、只算用那把武器的攻擊）；這次之前寄在這裡只為了載入過得了，沒人讀。
     */
    public static final Set<String> LOCAL = Set.of(LOCAL_CORE_AS_FEET, LOCAL_CORE_AS_HEAD, LOCAL_CORE_AS_LEGS, LOCAL_CORE_EXTRA_SLOT, LOCAL_ONLY_SOUL_CORES);

    /** 其他（進角色屬性表的）屬性。 */
    public static final Set<String> STATS = Set.of(AILMENT_MAGNITUDE_SPELL_PER_100_LIFE, AILMENT_SELF_DURATION_POISON, ARMOUR_CHAOS, ARMOUR_FROM_LIFE_LOST_8S,
            CHARGE_EXTRA_POWER, CHARGE_POWER_EVERY_SECOND, CRIT_BONUS_PER_RECENT_SPELL_CRIT, CRIT_SPELL_PER_100_MANA,
            DAMAGE_EXTRA_FIRE, DAMAGE_GAIN_IF_LIFE_COST_PHYSICAL, DAMAGE_LUCKY_CRIT, DAMAGE_NONCHANNEL_SPELL_PER_100_MANA,
            ES_LOSS_PERCENT, ES_RECHARGE_ON_MANA_SPENT, ES_RECOVERY_RATE, FLAG_ES_RECHARGE_ON_MINION_REFORM,
            FLAG_LEECH_EXCESS_TO_ES, FLAG_LIFE_FLASK_REMOVES_BLEED, FLAG_LIFE_RECHARGES, FLAG_LIGHTNING_RESIST_IGNORED,
            FLAG_MOVEMENT_TO_ES_RECHARGE, FLAG_NO_CHAIN, FLAG_NO_ENERGY_SHIELD, FLAG_NO_FORK,
            FLAG_NO_PIERCE, FLAG_OFFERING_EXPIRE_REVIVE, FLAG_SOUL_EATER, GEM_CORRUPTED_LEVEL,
            GEM_CORRUPTED_LIFE_COST_PERCENT, GRANT_SKILL_VITAL_RESIDUE, GUARD_ON_DODGE_SACRIFICE, JEWEL_ALLOCATE_KEYSTONES,
            JEWEL_CORRUPTED_RARE_EFFECT, JEWEL_CRUCIBLE_LESS, JEWEL_CRUCIBLE_LESS_KIND, JEWEL_LIMIT_ONE,
            JEWEL_RANDOM_KEYSTONE, KILL_EXPLODE_CRIT, LIFE_LOW_FROM_MANA, LIFE_LOW_THRESHOLD,
            LIFE_PER_POISON_ON_KILL, LIGHT_RADIUS, LIGHT_RADIUS_AS_AREA, MANA_LOW_FROM_LIFE,
            MANA_PER_3_HELMET_ARMOUR, OFFERING_RECOUP_LIFE, PENETRATION_MINIMUM, PER_ES_LIFE_REGEN,
            PER_STR_ES_RECHARGE, POISON_CHANCE_TAKEN, PRESENCE_ALLY_LEECH_LIFE, PRESENCE_LIGHTNING_RESIST_EQUAL,
            SHAPESHIFT_COPY_MODS, SHAPESHIFT_COPY_MODS_DURATION, SKILL_EXTRA_LIFE_COST_OF_MANA, SOUL_EATER_ON_UNIQUE_HIT,
            SURROUNDED_FEWER, TAKEN_ELEMENTAL_AS_CHAOS, TAKEN_VOLATILITY_PHYSICAL_AS_COLD, THESIS_GLORY_ON_MARK,
            UNIQUE_IGNITED_KILL_EXPLODE, UNIQUE_SPELL_NOVA_CHANCE);
}
