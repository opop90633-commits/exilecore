package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 一次施放實際用的數值：技能定義 + 寶石等級 + 掛的輔助寶石 + 玩家的屬性表
 * → 等級、投射物數量、範圍、穿透、連鎖、魔力消耗、傷害用的 {@link SkillProfile}。
 * 純函式，不依賴 Bukkit。
 *
 * @param level      實際施放的等級：寶石等級，但不超過角色等級能用的（等級不夠時照能用的最高等級放），
 *                   再加上裝備的「+x 技能等級」（可以超過 20 級，最高 {@link #MAX_EFFECTIVE_LEVEL}）
 * @param gemLevel   寶石本身的等級
 * @param isDefault  普攻（沒有輔助寶石）
 * @param supports   有生效的輔助寶石（掛了但不能輔助這個技能的不算）
 * @param areaScale     範圍技能的半徑倍率（範圍效果倍率開根號，PoE 規則）
 * @param bonusLevel    裝備加的技能等級（已經算進 level）
 * @param cooldownTicks 冷卻時間（tick；0 = 沒有），已經算進「減少冷卻時間」
 * @param durationScale 持續時間的倍率（地面效果、印記、漩渦…），已經算進「增加持續時間」
 * @param forks         投射物第一次打中時分裂成幾發（0 = 不分裂）
 * @param returning     投射物停下來之後飛回來
 * @param echo          法術再自動重複一次
 * @param deploy        交給圖騰、陷阱或鐘放（技能自己的設定，或圖騰施法、陷阱施法輔助套的預設）；null = 自己放
 * @param totems        同時最多幾個圖騰（含「圖騰列陣」）
 * @param minions       召喚技能同時最多幾隻（含「+x 召喚物數量」；不是召喚技能為 0）
 * @param reserve       光環保留的精魂（已經算進「光環保留」的增減；不是光環為 0；照隻數算的召喚是固定的那一部分，例如輔助的精魂）
 * @param minionReserve 照隻數算的精魂召喚：每一隻保留多少精魂（已經算進保留效率；0 = 不是照隻數算）
 * @param auraEffect    光環效果的倍率（1 = 照寫的；「增加 x% 光環效果」）
 * @param generosity    光環不影響自己、只給隊友與召喚物（慷慨輔助）
 * @param exerts        戰吼之後強化幾次攻擊（含「+x 次強化」；不是戰吼為 0）
 * @param repeats       近戰攻擊按一次之後自動再打幾下（複擊；每一下照攻擊間隔，打完才能做別的；瞬影擊是再閃幾次；0 = 不重複）
 */
public record SkillBuild(SkillDefinition definition, int level, int gemLevel, boolean isDefault,
                         List<SupportDefinition> supports, SkillProfile profile,
                         double reach, double angle, int targets, double knockback,
                         int projectiles, double spread, double speed, double range, double size,
                         int pierce, int chains, double explode, double wobble, boolean arrow,
                         double radius, double length, double chainRange, int manaCost, double areaScale, int bonusLevel,
                         int cooldownTicks, double durationScale, int forks, boolean returning, boolean echo,
                         DeploySpec deploy, int totems, int minions, double reserve, double minionReserve, double auraEffect, boolean generosity, int exerts,
                         int repeats) {

    /** 能不能交給圖騰施法、陷阱施法放（位移、印記、骸爆、召喚、光環、戰吼、瞬影擊、號召、掘墳不行）。 */
    public static boolean deployable(final SkillDefinition definition) {
        final Mechanic mechanic = definition.mechanic();
        return mechanic != Mechanic.DASH && mechanic != Mechanic.LEAP && mechanic != Mechanic.MARK && mechanic != Mechanic.CORPSE
                && mechanic != Mechanic.SUMMON && mechanic != Mechanic.AURA && mechanic != Mechanic.WARCRY && mechanic != Mechanic.FLICKER
                && mechanic != Mechanic.RALLY && mechanic != Mechanic.DESECRATE
                && mechanic != Mechanic.GUARD;
    }

    /** 2026-10：精魂輔助多保留的精魂。 */
    private static double supportSpirit(final List<SupportDefinition> supports) {
        double total = 0;
        for (final SupportDefinition support : supports) {
            for (final Modifier modifier : support.modifiers()) {
                if (modifier.stat().equals(StatKeys.SUPPORT_SPIRIT)) {
                    total += modifier.value();
                }
            }
        }
        return total;
    }

    /** 光環保留的倍率：aura.reservation 的加總（小數，負的 = 效率）→ 減少的時候 1 ÷ (1 + 效率)，增加的時候 1 + 增加。 */
    public static double reservationFactor(final double increased) {
        return increased < 0 ? 1.0 / (1.0 - increased) : 1.0 + increased;
    }

    /** 互斥的多重投射輔助（2026-10）：掛了兩顆只有第一顆生效。 */
    public static final Set<String> MULTI_PROJECTILE = Set.of("multiple_projectiles", "greater_multiple_projectiles");

    /** 複擊最多再打幾下。 */
    public static final int MAX_REPEATS = 4;

    /** 一個召喚技能同時最多幾隻（連「+x 召喚物數量」都算進去的上限）。 */
    public static final int MAX_MINIONS = 40;

    /** 投射物連鎖時，下一個目標最遠幾格。 */
    public static final double PROJECTILE_CHAIN_RANGE = 10;
    /** 一次最多幾發投射物。 */
    public static final int MAX_PROJECTILES = 20;
    /**
     * 實際施放的等級最高到幾級：寶石最高 20 級，裝備的「+x 技能等級」可以再往上加（照 PoE：21 級以上只有靠裝備）。
     * 超過 20 級的數值照 1 → 20 級的趨勢往外推（{@link Scaled#at}），法術基礎傷害每級再多 skills.yml 的 extra_level_damage。
     */
    public static final int MAX_EFFECTIVE_LEVEL = 40; // 2026-10：25 → 40（照 PoE2，寶石資料到 40 級）

    public SkillBuild {
        supports = List.copyOf(supports);
    }

    /**
     * 算出一次施放的數值。
     *
     * @param gemLevel       寶石等級（普攻給 1）
     * @param characterLevel 角色等級：寶石等級超過角色能用的就降到能用的最高等級
     * @param supports       掛在技能上的輔助寶石（不能輔助這個技能的會被略過）
     * @param sheet          玩家目前的屬性表
     */
    public static SkillBuild of(final SkillConfig config, final SkillDefinition definition, final int gemLevel,
                                final int characterLevel, final boolean isDefault, final List<SupportDefinition> supports,
                                final Set<String> weaponTags, final StatSheet sheet) {
        // 2026-10：Lv.21 的寶石（劫盜大金庫、金庫之主）跟 Lv.20 一樣要 80 級，角色夠了就照 21 級算（以前一律被壓回 20）
        final int usable = gemLevel > tw.exilecore.character.SkillLoadout.MAX_LEVEL
                && characterLevel >= config.requiredLevel(tw.exilecore.character.SkillLoadout.MAX_LEVEL)
                ? Math.min(gemLevel, tw.exilecore.character.SkillLoadout.GEM_CAP) : config.maxLevelFor(characterLevel);
        final int baseLevel = isDefault ? 1 : Math.max(1, Math.min(gemLevel, usable));
        // 傳奇的「+1 技能等級」：照技能自己的標籤（法術、混沌…），可以超過 20 級；普攻不吃
        final int bonus = isDefault || sheet == null ? 0
                : Math.max(0, (int) Math.round(sheet.flat(StatKeys.SKILL_LEVEL, definition.tags())));
        // 2026-10：輔助寶石的「+1 被輔助技能等級」（元素專精這類）
        int supportLevels = 0;
        if (!isDefault) {
            for (final SupportDefinition support : supports) {
                if (support != null && support.supports(definition)) {
                    for (final Modifier modifier : support.modifiers()) {
                        if (modifier.stat().equals(StatKeys.SKILL_LEVEL) && modifier.appliesTo(definition.tags())) {
                            supportLevels += (int) Math.round(modifier.value());
                        }
                    }
                }
            }
        }
        final int level = Math.min(MAX_EFFECTIVE_LEVEL, baseLevel + bonus + supportLevels);
        final List<SupportDefinition> active = new ArrayList<>();
        final List<Modifier> local = new ArrayList<>(definition.modifiers()); // 技能自己帶的（例如點燃機率）
        if (!isDefault) {
            boolean deployer = definition.deploy() != null;
            boolean exclusive = false;
            boolean multiProjectile = false;
            for (final SupportDefinition support : supports) {
                if (support == null || !support.supports(definition)) {
                    continue;
                }
                if (!support.cuttable()) {
                    if (exclusive) {
                        continue; // 一個技能只有一顆專屬掉落的輔助生效（2026-10；以前掛了兩顆的，第二顆不算）
                    }
                    exclusive = true;
                }
                if (support.flags(StatKeys.FLAG_SPELL_TOTEM) || support.flags(StatKeys.FLAG_TRAP)) {
                    if (deployer) {
                        continue; // 圖騰施法與陷阱施法只能有一個生效（第二個連倍率都不算）
                    }
                    deployer = true;
                }
                if (MULTI_PROJECTILE.contains(support.id())) {
                    if (multiProjectile) {
                        continue; // 分光與裂光只有一個生效（2026-10，照 PoE）
                    }
                    multiProjectile = true;
                }
                active.add(support);
                local.addAll(support.allModifiers());
            }
        }
        SkillProfile profile = SkillProfiles.build(config, definition, level, weaponTags, local);
        final StatSheet effective = profile.effective(sheet);
        // 圖騰施法、陷阱施法輔助：技能從此算圖騰／陷阱技能（「增加 x% 圖騰傷害」、「陷阱持續時間」這些天賦與詞綴才吃得到）
        DeploySpec deploy = definition.deploy();
        final boolean deployable = deployable(definition);
        if (deploy == null && !isDefault && !definition.isAttack() && deployable) {
            if (StatKeys.flag(effective, StatKeys.FLAG_SPELL_TOTEM)) {
                deploy = DeploySpec.spellTotem(definition.castTime());
                profile = profile.derive(profile.id(), Set.of(Tags.TOTEM, Tags.DURATION), Set.of());
            } else if (StatKeys.flag(effective, StatKeys.FLAG_TRAP)) {
                deploy = DeploySpec.trap();
                profile = profile.derive(profile.id(), Set.of(Tags.TRAP, Tags.DURATION), Set.of());
            }
        }
        final Set<String> context = profile.tags();
        final SkillParams params = definition.params();
        final boolean area = definition.tags().contains(Tags.AREA);
        final double areaScale = area ? Math.sqrt(Math.max(0.05, effective.apply(StatKeys.SKILL_AREA, 1.0, context))) : 1.0;

        // 2026-10：瞬影箭永遠只有一支（多一支、歧射都不吃：好幾支不知道要瞬移到哪一支）
        final int projectiles = params.blink() ? 1 : clamp((int) Math.round(params.projectiles().intAt(level)
                + effective.flat(StatKeys.SKILL_PROJECTILES, context)), 1, MAX_PROJECTILES);
        double spread = params.spread();
        if (StatKeys.flag(effective, StatKeys.FLAG_PROJECTILE_NOVA) && definition.mechanic() == Mechanic.PROJECTILE && !params.blink()) {
            spread = 360; // 2026-10：環射（投射物往四周一圈射出）
        } else if (projectiles > 1 && spread < 1) {
            spread = Math.min(60, 12.0 * (projectiles - 1)); // 本來只有一發的技能多了投射物：排成扇形
        }
        final double speed = Math.max(0.2, Math.min(6, params.speed()
                * Math.max(0.1, effective.apply(StatKeys.SKILL_PROJECTILE_SPEED, 1.0, context))));
        final int pierce = clamp((int) Math.round(params.pierce() + effective.flat(StatKeys.SKILL_PIERCE, context)), 0, 20);
        final int extraChains = Math.max(0, (int) Math.round(effective.flat(StatKeys.SKILL_CHAINS, context)));
        final int chains = switch (definition.mechanic()) {
            case CHAIN -> params.chains().intAt(level) + extraChains;
            case PROJECTILE -> extraChains;
            default -> 0;
        };
        final double reach = definition.mechanic() == Mechanic.STRIKE && area ? params.reach() * areaScale : params.reach();
        final int manaCost = (int) Math.round(definition.mana().at(level)
                * Math.max(0, effective.apply(StatKeys.SKILL_COST, 1.0, context)));
        final double baseCooldown = definition.cooldown() > 0 ? definition.cooldown()
                : Math.max(0, effective.flat(StatKeys.SKILL_ADDED_COOLDOWN, context)); // 2026-10：輔助給的冷卻
        final int cooldownTicks = baseCooldown <= 0 ? 0
                : (int) Math.max(1, Math.round(baseCooldown * 20 * Math.max(0.1, effective.apply(StatKeys.SKILL_COOLDOWN, 1.0, context))));
        final double durationScale = Math.max(0.1, effective.apply(StatKeys.SKILL_DURATION, 1.0, context));
        final int forks = definition.mechanic() == Mechanic.PROJECTILE && !params.blink()
                ? clamp((int) Math.round(effective.flat(StatKeys.SKILL_FORK, context)), 0, 3) : 0;
        final boolean returning = definition.mechanic() == Mechanic.PROJECTILE && !params.blink() && params.fuse() <= 0
                && StatKeys.flag(effective, StatKeys.FLAG_RETURNING);
        final int totems = deploy == null || deploy.kind() != DeploySpec.Kind.TOTEM ? 0
                : clamp((deploy.max() + (int) Math.round(effective.flat(StatKeys.SKILL_TOTEMS, context)))
                        * (StatKeys.flag(effective, StatKeys.FLAG_ANCESTRAL_BOND) ? 2 : 1), 1, 12); // 祖靈契約（PoE2）：圖騰上限加倍
        final boolean echo = !isDefault && !definition.isAttack() && deploy == null && StatKeys.flag(effective, StatKeys.FLAG_ECHO)
                && definition.mechanic() != Mechanic.DASH && definition.mechanic() != Mechanic.LEAP && definition.mechanic() != Mechanic.MARK
                && definition.mechanic() != Mechanic.SUMMON && definition.mechanic() != Mechanic.AURA && definition.mechanic() != Mechanic.WARCRY
                && definition.mechanic() != Mechanic.FLICKER && definition.mechanic() != Mechanic.RALLY && definition.mechanic() != Mechanic.DESECRATE
                && definition.mechanic() != Mechanic.GUARD;
        final SummonSpec summon = definition.summon();
        final int minions = summon == null ? 0 : clamp(summon.max().intAt(level)
                + (summon.fixedMax() ? 0 : (int) Math.round(effective.flat(StatKeys.SKILL_MINIONS, context))), 1, MAX_MINIONS);
        final AuraSpec aura = definition.aura();
        // 2026-10：照 PoE 的「保留效率」：減少的部分加總成效率，保留 = 原本 ÷ (1 + 效率)；增加的照乘（以前減少直接相加，疊到 -100% 就不用保留）
        final double reserveFactor = reservationFactor(effective.increased(StatKeys.AURA_RESERVATION, context));
        final double reserve = aura == null ? 0
                : Math.max(0, Math.min(10000, (aura.reserve() + supportSpirit(active)) * reserveFactor));
        // 2026-10（照 PoE2）：精魂召喚每一隻保留多少（已經算進保留效率；超過 20 級不再變少）；0 = 不是照隻數算
        final double minionReserve = aura == null || aura.perMinion() == null ? 0 : Math.max(0.01, aura.perMinion().at(Math.min(level, tw.exilecore.character.SkillLoadout.MAX_LEVEL)) * reserveFactor);
        // 常駐技能（焚心業火）不算光環：光環效果的加成不吃
        final double auraEffect = aura == null
                ? (definition.mechanic() == Mechanic.MARK ? Math.max(0, effective.apply(StatKeys.CURSE_EFFECT, 1.0, context)) // 2026-10：詛咒、印記效果
                : definition.mechanic() == Mechanic.WARCRY ? Math.max(0, effective.apply(StatKeys.WARCRY_EFFECT, 1.0, context)) : 1) // 戰吼效果
                : aura.herald() ? Math.max(0, effective.apply(StatKeys.HERALD_EFFECT, 1.0, context)) // 捷光環不吃光環效果，只吃捷光環效果（2026-10）
                : aura.persistent() ? 1
                : Math.max(0, effective.apply(StatKeys.AURA_EFFECT, 1.0, context));
        final boolean generosity = aura != null && StatKeys.flag(effective, StatKeys.FLAG_GENEROSITY);
        final WarcrySpec warcry = definition.warcry();
        final int exerts = warcry == null || warcry.exerts() <= 0 ? 0
                : clamp(warcry.exerts() + (int) Math.round(effective.flat(StatKeys.WARCRY_EXERTS, context)), 1, 20);
        // 複擊（2026-10）：自己放的近戰攻擊（打擊、扇形、繞身一圈）與瞬影擊才會重複；衝刺、跳躍、圖騰、鐘不會
        final boolean repeatable = !isDefault && definition.isAttack() && deploy == null
                && (definition.mechanic() == Mechanic.STRIKE || definition.mechanic() == Mechanic.CONE || definition.mechanic() == Mechanic.NOVA
                || definition.mechanic() == Mechanic.FLICKER);
        final int repeats = repeatable ? clamp((int) Math.round(effective.flat(StatKeys.SKILL_REPEATS, context)), 0, MAX_REPEATS) : 0;
        return new SkillBuild(definition, level, isDefault ? 1 : gemLevel, isDefault, active, profile,
                reach, params.angle(), params.targets(), Math.max(0, effective.apply(StatKeys.SKILL_KNOCKBACK, params.knockback() + effective.flat(StatKeys.SKILL_KNOCKBACK, context), context)),
                projectiles, spread, speed, params.range(), params.size(),
                pierce, chains, params.explode().at(level) * areaScale, params.wobble(), params.arrow(),
                params.radius().at(level) * areaScale, params.length().at(level) * areaScale, params.chainRange(),
                manaCost, areaScale, level - baseLevel, cooldownTicks, durationScale, forks, returning, echo, deploy, totems, minions,
                reserve, minionReserve, auraEffect, generosity, exerts, repeats);
    }

    /** 2026-10：封印解開的這一次：範圍倍率、投射物數量換掉。 */
    public SkillBuild withAreaAndProjectiles(final double newAreaScale, final int newProjectiles) {
        return new SkillBuild(definition, level, gemLevel, isDefault, supports, profile,
                reach, angle, targets, knockback, newProjectiles, spread < 1 && newProjectiles > 1 ? Math.min(60, 12.0 * (newProjectiles - 1)) : spread,
                speed, range, size, pierce, chains, explode * (newAreaScale / Math.max(1e-6, areaScale)), wobble, arrow,
                radius * (newAreaScale / Math.max(1e-6, areaScale)), length, chainRange, manaCost, newAreaScale, bonusLevel, cooldownTicks,
                durationScale, forks, returning, echo, deploy, totems, minions, reserve, minionReserve, auraEffect, generosity, exerts, repeats);
    }

    /** 同一次施放換一個技能描述（戰吼強化的攻擊：傷害更多）。 */
    public SkillBuild withProfile(final SkillProfile newProfile) {
        return new SkillBuild(definition, level, gemLevel, isDefault, supports, newProfile,
                reach, angle, targets, knockback, projectiles, spread, speed, range, size, pierce, chains, explode, wobble, arrow,
                radius, length, chainRange, manaCost, areaScale, bonusLevel, cooldownTicks, durationScale, forks, returning, echo,
                deploy, totems, minions, reserve, minionReserve, auraEffect, generosity, exerts, repeats);
    }

    /**
     * 幻元箭（2026-10）：這一次施放隨機挑一種元素——物理全部轉成它、只造成它的傷害，特效也換成那種元素的。
     * 技能沒有 flag.random_element 就是原本那個。
     */
    public SkillBuild rollElement(final StatSheet sheet, final java.util.random.RandomGenerator random) {
        if (isDefault || sheet == null || !StatKeys.flag(profile.effective(sheet), StatKeys.FLAG_RANDOM_ELEMENT)) {
            return this;
        }
        final tw.exilecore.stats.DamageType[] elements = {tw.exilecore.stats.DamageType.FIRE, tw.exilecore.stats.DamageType.COLD,
                tw.exilecore.stats.DamageType.LIGHTNING};
        final tw.exilecore.stats.DamageType element = elements[random.nextInt(elements.length)];
        final String visual = switch (element) {
            case FIRE -> "burning_arrow";
            case COLD -> "ice_shot";
            default -> "lightning_arrow";
        };
        final SkillProfile rolled = profile.withLocal(List.of(
                Modifier.flat(StatKeys.damageConvert(element), 100, "幻元箭"),
                Modifier.flat(StatKeys.DAMAGE_ONLY + element.tag(), 1, "幻元箭")))
                .derive(profile.id(), Set.of(element.tag(), tw.exilecore.stats.Tags.ELEMENTAL), Set.of());
        return new SkillBuild(definition.withVisual(visual), level, gemLevel, isDefault, supports, rolled,
                reach, angle, targets, knockback, projectiles, spread, speed, range, size, pierce, chains, explode, wobble, arrow,
                radius, length, chainRange, manaCost, areaScale, bonusLevel, cooldownTicks, durationScale, forks, returning, echo,
                deploy, totems, minions, reserve, minionReserve, auraEffect, generosity, exerts, repeats);
    }

    /** 光環的效果（這個等級、含光環效果倍率）。 */
    public List<Modifier> auraModifiers() {
        final AuraSpec aura = definition.aura();
        if (aura == null) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        for (final ScaledMod mod : aura.effects()) {
            list.add(mod.toModifier(level, auraEffect, "光環 " + definition.name()));
        }
        return list;
    }

    /** 2026-10：精魂輔助給你自己的增益（「grant.」開頭的屬性；只給自己，不給隊友與召喚物）。 */
    public List<Modifier> grantModifiers() {
        if (definition.aura() == null) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        for (final SupportDefinition support : supports) {
            for (final Modifier modifier : support.modifiers()) {
                if (modifier.stat().startsWith(StatKeys.GRANT_PREFIX)) {
                    list.add(new Modifier(modifier.stat().substring(StatKeys.GRANT_PREFIX.length()), modifier.type(), modifier.value(),
                            modifier.tags(), support.name()));
                }
            }
        }
        return list;
    }

    /** 戰吼的增益（這個等級）。 */
    public List<Modifier> warcryModifiers() {
        final WarcrySpec warcry = definition.warcry();
        if (warcry == null) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        for (final ScaledMod mod : warcry.buff()) {
            list.add(mod.toModifier(level, auraEffect, "戰吼 " + definition.name())); // 2026-10：戰吼效果
        }
        for (final SupportDefinition support : supports) { // 2026-10：輔助給戰吼增益加的（「grant.」開頭）
            for (final Modifier modifier : support.modifiers()) {
                if (modifier.stat().startsWith(StatKeys.GRANT_PREFIX)) {
                    list.add(new Modifier(modifier.stat().substring(StatKeys.GRANT_PREFIX.length()), modifier.type(), modifier.value(),
                            modifier.tags(), support.name()));
                }
            }
        }
        return list;
    }

    /** 這個技能的原始參數（運作方式的細節）。 */
    public SkillParams params() {
        return definition.params();
    }

    /** 持續時間（秒）換成 tick，已經乘上持續時間的倍率。 */
    public int durationTicks(final double seconds) {
        return (int) Math.max(1, Math.round(seconds * 20 * durationScale));
    }

    /** 一個隨等級變的半徑，乘上範圍倍率。 */
    public double scaledRadius(final Scaled value) {
        return value.at(level) * areaScale;
    }

    /** 冷卻時間（秒），顯示用。 */
    public double cooldownSeconds() {
        return cooldownTicks / 20.0;
    }

    public boolean isAttack() {
        return definition.isAttack();
    }

    /** 寶石等級比角色能用的高（照能用的最高等級放；裝備加的等級另外算）。 */
    public boolean levelCapped() {
        return !isDefault && level - bonusLevel < gemLevel;
    }

    /** 這個技能兩次之間要幾 tick：攻擊看武器攻速，法術看施法時間與施法速度（都吃輔助寶石）。 */
    public double intervalTicks(final StatSheet sheet) {
        return SkillProfiles.intervalTicks(sheet, definition, profile);
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
