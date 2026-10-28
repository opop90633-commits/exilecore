package tw.exilecore.item.unique;

import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 2026-10-26：屬性表算完裝備之後才知道的傳奇效果（{@link tw.exilecore.character.PlayerStateService} 在裝備、昇華之後呼叫）。純函式。
 * <ul>
 *   <li>{@link UniqueStats#RESIST_PER_POWER}：每顆銳星 x% 全部元素抗性（星軌魔杖）。</li>
 *   <li>{@link UniqueStats#SPIRIT_EFFICIENCY_PER_LIFE}：每 100 最大生命，精魂的保留效率增加 x%（稜壁）。</li>
 *   <li>{@link UniqueStats#BLOCK_CHANCE_FOCUS}：拿著法器時 +x% 格擋率（香爐墜）。</li>
 *   <li>{@link UniqueStats#UNHOLY_MIGHT}：邪力，獲得 30%（乘邪力效果）傷害為額外的混沌傷害（未啟骸杖）。</li>
 *   <li>2026-10-27 第二批：{@link #second}（生命／魔力換護盾、每顆戰星、照屬性點數、魔力換荊棘、格擋率換火焰）、{@link #rings}（映鏡指環、巧匠束帶）。</li>
 * </ul>
 */
public final class UniqueDerived {

    private static final String SOURCE = "傳奇";

    private UniqueDerived() {
    }

    /**
     * @param powerCharges 身上有幾顆銳星
     * @param holdingFocus 副手拿著法器
     */
    public static List<Modifier> modifiers(final StatSheet sheet, final int powerCharges, final boolean holdingFocus) {
        final List<Modifier> list = new ArrayList<>();
        final double perPower = sheet.flat(UniqueStats.RESIST_PER_POWER, Set.of());
        if (perPower != 0 && powerCharges > 0) {
            for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                list.add(Modifier.flat(type.mitigationStat(), perPower * powerCharges, SOURCE + "（每顆銳星）"));
            }
        }
        final double perLife = sheet.flat(UniqueStats.SPIRIT_EFFICIENCY_PER_LIFE, Set.of());
        if (perLife > 0) {
            final double life = tw.exilecore.character.PlayerState.maxLife(sheet);
            final double efficiency = Math.floor(Math.max(0, life) / 100.0) * perLife;
            if (efficiency > 0) {
                // aura.reservation 負的 = 保留效率（SkillBuild.reservationFactor）
                list.add(Modifier.increased(StatKeys.AURA_RESERVATION, -efficiency / 100.0, SOURCE + "（每 100 最大生命）"));
            }
        }
        final double focusBlock = sheet.flat(UniqueStats.BLOCK_CHANCE_FOCUS, Set.of());
        if (focusBlock > 0 && holdingFocus) {
            list.add(Modifier.flat(StatKeys.BLOCK_CHANCE, focusBlock, SOURCE + "（拿著法器）"));
        }
        if (StatKeys.flag(sheet, UniqueStats.UNHOLY_MIGHT)) {
            final double magnitude = UniqueStats.UNHOLY_MIGHT_PERCENT * Math.max(0, 1 + sheet.increased(UniqueStats.UNHOLY_MIGHT_EFFECT, Set.of()));
            list.add(Modifier.flat(StatKeys.damageExtra(DamageType.CHAOS), magnitude, "邪力"));
        }
        return list;
    }

    /**
     * 2026-10-27 照 PoE2 0.5 第二批傳奇：
     * 生命／魔力換額外的能量護盾（冷蔑金冠、恆望墜）、每顆怒星的移動速度（暗芒戰靴）、每顆銳星的暴擊傷害加成（銳踏便鞋）、
     * 照屬性點數給的加成（知行捲布、囚神長棍、織光法器）、魔力換冰冷荊棘（冰塚法鎧）、格擋率換額外火焰傷害（黑神柱盾）。
     *
     * @param chargeCount 身上某種戰星有幾顆（frenzy、power）
     */
    /** 幽墓束縛自己給的物品稀有度（%）：其他的無效時留下這麼多。 */
    public static final double RARITY_ONLY_THIS_KEEP = 20;

    /**
     * 2026-10-28 照 PoE2（毀面者）：空手時，每 5 點力量空手攻擊傷害更多 x%（PlayerStateService 只在沒拿武器時呼叫）。
     */
    public static List<Modifier> unarmed(final StatSheet sheet) {
        final List<Modifier> list = new ArrayList<>();
        final double per = sheet.flat(UniqueStats.PER_STR_UNARMED_MORE, Set.of());
        final double fives = per > 0 ? Math.floor(sheet.get(StatKeys.STR) / 5) : 0;
        if (fives > 0) {
            list.add(Modifier.more(StatKeys.DAMAGE, per * fives / 100.0, SOURCE + "（空手、力量）").withTags(Set.of(Tags.ATTACK)));
        }
        // 2026-10-28 照 PoE2（磐石之拳的毀面者）：每 5 點智慧獲得 1% 空手傷害為額外火焰傷害、每 10 點智慧增加 1% 空手攻擊範圍
        final double intelligence = sheet.get(StatKeys.INT);
        final double fire = sheet.flat(UniqueStats.PER_INT_UNARMED_FIRE, Set.of());
        if (fire > 0 && intelligence >= 5) {
            list.add(Modifier.flat(StatKeys.damageExtra(DamageType.FIRE), fire * Math.floor(intelligence / 5), SOURCE + "（空手、智慧）")
                    .withTags(Set.of(Tags.ATTACK)));
        }
        final double area = sheet.flat(UniqueStats.PER_INT_UNARMED_AREA, Set.of());
        if (area > 0 && intelligence >= 10) {
            list.add(Modifier.increased(StatKeys.SKILL_AREA, area * Math.floor(intelligence / 10) / 100.0, SOURCE + "（空手、智慧）")
                    .withTags(Set.of(Tags.ATTACK)));
        }
        return list;
    }

    public static List<Modifier> second(final StatSheet sheet, final java.util.function.ToIntFunction<String> chargeCount) {
        final List<Modifier> list = new ArrayList<>();
        final Set<String> none = Set.of();
        final double fromLife = sheet.flat(UniqueStats.ES_FROM_LIFE, none);
        if (fromLife > 0) {
            list.add(Modifier.flat(StatKeys.ES_MAX, tw.exilecore.character.PlayerState.maxLife(sheet) * fromLife / 100.0, SOURCE + "（最大生命）"));
        }
        final double fromMana = sheet.flat(UniqueStats.ES_FROM_MANA, none);
        if (fromMana > 0) {
            list.add(Modifier.flat(StatKeys.ES_MAX, tw.exilecore.character.PlayerState.maxMana(sheet) * fromMana / 100.0, SOURCE + "（最大魔力）"));
        }
        final double perFrenzy = sheet.flat(UniqueStats.MOVEMENT_PER_FRENZY, none);
        final int frenzy = perFrenzy > 0 ? chargeCount.applyAsInt("frenzy") : 0;
        if (frenzy > 0) {
            list.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, perFrenzy * frenzy / 100.0, SOURCE + "（每顆怒星）"));
        }
        final double perPower = sheet.flat(UniqueStats.CRIT_BONUS_PER_POWER, none);
        final int power = perPower > 0 ? chargeCount.applyAsInt("power") : 0;
        if (power > 0) {
            list.add(Modifier.increased(StatKeys.CRIT_BONUS, perPower * power / 100.0, SOURCE + "（每顆銳星）"));
        }
        final double str = sheet.get(StatKeys.STR);
        final double dex = sheet.get(StatKeys.DEX);
        final double intelligence = sheet.get(StatKeys.INT);
        final double perDex = sheet.flat(UniqueStats.PER_DEX_ATTACK_SPEED, none);
        if (perDex > 0 && dex >= 10) {
            list.add(Modifier.increased(StatKeys.ATTACK_SPEED, perDex * Math.floor(dex / 10) / 100.0, SOURCE + "（敏捷）"));
        }
        final double perIntArea = sheet.flat(UniqueStats.PER_INT_ATTACK_AREA, none);
        if (perIntArea > 0 && intelligence >= 10) {
            list.add(Modifier.increased(StatKeys.SKILL_AREA, perIntArea * Math.floor(intelligence / 10) / 100.0, SOURCE + "（智慧）")
                    .withTags(Set.of(Tags.ATTACK)));
        }
        final double perStr = sheet.flat(UniqueStats.PER_STR_ATTACK_DAMAGE, none);
        if (perStr > 0 && str >= 10) {
            list.add(Modifier.increased(StatKeys.DAMAGE, perStr * Math.floor(str / 10) / 100.0, SOURCE + "（力量）").withTags(Set.of(Tags.ATTACK)));
        }
        final double perSpirit = sheet.flat(UniqueStats.PER_SPIRIT_SPELL_DAMAGE, none);
        final double spirit = perSpirit > 0 ? sheet.get(StatKeys.SPIRIT_MAX) : 0;
        if (spirit >= 10) {
            list.add(Modifier.increased(StatKeys.DAMAGE, perSpirit * Math.floor(spirit / 10) / 100.0, SOURCE + "（精魂）").withTags(Set.of(Tags.SPELL)));
        }
        final double lightning = sheet.flat(UniqueStats.PER_INT_ATTACK_LIGHTNING, none);
        final double twenties = lightning > 0 ? Math.floor(intelligence / 20) : 0;
        if (twenties > 0) {
            final Set<String> tags = Set.of(Tags.ATTACK, DamageType.LIGHTNING.tag());
            list.add(Modifier.flat(StatKeys.DAMAGE_ADDED_MIN, twenties * lightning / 10.0, SOURCE + "（智慧）").withTags(tags));
            list.add(Modifier.flat(StatKeys.DAMAGE_ADDED_MAX, twenties * lightning, SOURCE + "（智慧）").withTags(tags));
        }
        // 2026-10-28 照 PoE2（磐石之拳的智行之手）：每 20 點敏捷攻擊附加冰冷傷害、每 20 點智慧增加範圍效果
        final double coldMax = sheet.flat(UniqueStats.PER_DEX_ATTACK_COLD_MAX, none);
        final double dexTwenties = coldMax > 0 ? Math.floor(dex / 20) : 0;
        if (dexTwenties > 0) {
            final Set<String> tags = Set.of(Tags.ATTACK, DamageType.COLD.tag());
            list.add(Modifier.flat(StatKeys.DAMAGE_ADDED_MIN, dexTwenties * sheet.flat(UniqueStats.PER_DEX_ATTACK_COLD_MIN, none), SOURCE + "（敏捷）")
                    .withTags(tags));
            list.add(Modifier.flat(StatKeys.DAMAGE_ADDED_MAX, dexTwenties * coldMax, SOURCE + "（敏捷）").withTags(tags));
        }
        final double perIntAll = sheet.flat(UniqueStats.PER_INT_AREA, none);
        if (perIntAll > 0 && intelligence >= 20) {
            list.add(Modifier.increased(StatKeys.SKILL_AREA, perIntAll * Math.floor(intelligence / 20) / 100.0, SOURCE + "（智慧）"));
        }
        // 2026-10-28 照 PoE2（毀面者）：每 1 點力量 +1 護甲
        final double perStrArmour = sheet.flat(UniqueStats.PER_STR_ARMOUR, none);
        if (perStrArmour > 0 && str > 0) {
            list.add(Modifier.flat(StatKeys.ARMOUR, perStrArmour * Math.floor(str), SOURCE + "（力量）"));
        }
        // 2026-10-28 照 PoE2（幽墓束縛）：其他的物品稀有度加成無效——全部的「增加物品稀有度」加起來壓回這件給的 20%
        final double onlyThis = sheet.flat(UniqueStats.RARITY_ONLY_THIS, none);
        if (onlyThis > 0) {
            final double current = sheet.increased(StatKeys.DROP_RARITY, none);
            final double keep = RARITY_ONLY_THIS_KEEP / 100.0;
            if (Math.abs(current - keep) > 1e-9) {
                list.add(Modifier.increased(StatKeys.DROP_RARITY, keep - current, SOURCE + "（其他稀有度無效）"));
            }
        }
        // 2026-10-28 照 PoE2（吉爾菲的聖殿）：每 10 點智慧每秒回復 x 生命
        final double regenPerInt = sheet.flat(UniqueStats.PER_INT_LIFE_REGEN, none);
        if (regenPerInt > 0 && intelligence >= 10) {
            list.add(Modifier.flat(StatKeys.LIFE_REGEN, regenPerInt * Math.floor(intelligence / 10), SOURCE + "（智慧）"));
        }
        final double coldThorns = sheet.flat(UniqueStats.THORNS_COLD_PER_MANA, none);
        if (coldThorns > 0) {
            final double amount = tw.exilecore.character.PlayerState.maxMana(sheet) * coldThorns / 100.0;
            list.add(Modifier.flat(UniqueStats.THORNS_COLD_MIN, amount, SOURCE + "（最大魔力）"));
            list.add(Modifier.flat(UniqueStats.THORNS_COLD_MAX, amount, SOURCE + "（最大魔力）"));
        }
        final double perBlock = sheet.flat(UniqueStats.FIRE_PER_BLOCK, none);
        if (perBlock > 0) {
            final double block = Math.max(0, Math.min(tw.exilecore.stats.DamageCalculator.blockCap(sheet), sheet.get(StatKeys.BLOCK_CHANCE)));
            if (block > 0) {
                list.add(Modifier.flat(StatKeys.damageExtra(DamageType.FIRE), block * perBlock, SOURCE + "（格擋率）"));
            }
        }
        return list;
    }

    /**
     * 2026-10-27：戒指的傳奇效果（照裝備順序，第一枚是左手、第二枚是右手）。
     * 映鏡指環：另一隻手的戒指的詞綴在這裡再算一次（兩枚都是映鏡就都不算；有條件的、賦予技能的不抄）；
     * 巧匠束帶：左手、右手戒指的加成增加 x%（像箭袋效果一樣放大那枚戒指的詞綴，「更多」與旗標不放大）。
     */
    public static List<Modifier> rings(final StatSheet sheet, final List<List<Modifier>> rings) {
        if (rings.isEmpty()) {
            return List.of();
        }
        final List<Modifier> out = new ArrayList<>();
        final List<List<Modifier>> effective = new ArrayList<>(rings.subList(0, Math.min(2, rings.size())));
        if (effective.size() == 2) {
            for (int i = 0; i < 2; i++) {
                final List<Modifier> other = rings.get(1 - i);
                if (reflects(rings.get(i)) && !reflects(other)) {
                    final List<Modifier> copy = new ArrayList<>();
                    for (final Modifier mod : other) {
                        if (!tw.exilecore.stats.Recently.hasCondition(mod.stat()) && !mod.stat().startsWith(UniqueStats.GRANT_SKILL)
                                && !UniqueStats.ONLY_RING.equals(mod.stat())) {
                            copy.add(new Modifier(mod.stat(), mod.type(), mod.value(), mod.tags(), "映鏡指環"));
                        }
                    }
                    out.addAll(copy);
                    final List<Modifier> merged = new ArrayList<>(rings.get(i));
                    merged.addAll(copy);
                    effective.set(i, merged);
                }
            }
        }
        final String[] stats = {UniqueStats.RING_EFFECT_LEFT, UniqueStats.RING_EFFECT_RIGHT};
        final String[] names = {"左手戒指的加成", "右手戒指的加成"};
        for (int i = 0; i < effective.size(); i++) {
            final double inc = sheet.increased(stats[i], Set.of());
            if (inc != 0) {
                final List<Modifier> plain = effective.get(i).stream()
                        .filter(mod -> !tw.exilecore.stats.Recently.hasCondition(mod.stat()) && !mod.stat().startsWith(UniqueStats.GRANT_SKILL)
                                && !mod.stat().startsWith("ring.")).toList();
                out.addAll(tw.exilecore.stats.TreeEffectKeys.amplify(plain, inc, names[i]));
            }
        }
        return out;
    }

    private static boolean reflects(final List<Modifier> modifiers) {
        for (final Modifier modifier : modifiers) {
            if (UniqueStats.RING_REFLECT.equals(modifier.stat()) && modifier.type() == ModType.FLAT && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 召喚物的抗性等於主人的（冥河之衛）：主人有這個旗標時，把召喚物四種抗性與抗性上限的基礎值調到總和剛好等於主人的。
     */
    public static void mirrorResists(final StatSheet owner, final StatSheet minion) {
        if (owner == null || minion == null || !StatKeys.flag(owner, UniqueStats.MINION_RESIST_MIRROR)) {
            return;
        }
        for (final String stat : new String[] {StatKeys.RESIST_MAX, StatKeys.RESIST_FIRE, StatKeys.RESIST_COLD, StatKeys.RESIST_LIGHTNING,
                StatKeys.RESIST_CHAOS}) {
            final double target = owner.get(stat);
            final double now = minion.get(stat);
            minion.setBase(stat, minion.base(stat) + (target - now));
        }
    }

    /** 這件裝備有沒有「不能使用其他戒指」。 */
    public static boolean onlyRing(final List<Modifier> itemModifiers) {
        for (final Modifier modifier : itemModifiers) {
            if (UniqueStats.ONLY_RING.equals(modifier.stat()) && modifier.type() == ModType.FLAT && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 這件裝備賦予的技能（grant_skill.代號 → 有沒有），沒有回傳空的。 */
    public static List<String> grantedSkills(final List<Modifier> itemModifiers) {
        List<String> list = null;
        for (final Modifier modifier : itemModifiers) {
            if (modifier.stat().startsWith(UniqueStats.GRANT_SKILL) && modifier.value() > 0) {
                if (list == null) {
                    list = new ArrayList<>();
                }
                list.add(modifier.stat().substring(UniqueStats.GRANT_SKILL.length()));
            }
        }
        return list == null ? List.of() : list;
    }
}
