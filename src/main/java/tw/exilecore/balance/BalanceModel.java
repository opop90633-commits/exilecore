package tw.exilecore.balance;

import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.core.config.LevelCurve;
import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixMod;
import tw.exilecore.item.AffixTier;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemStats;
import tw.exilecore.item.Rarity;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.monster.MonsterSheets;
import tw.exilecore.skill.SkillBuild;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SupportDefinition;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.Formulas;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.zone.ZoneConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * 平衡模型：用實際的傷害公式（{@link DamageCalculator}、{@link SkillBuild}、裝備與詞綴資料）
 * 算「中等裝備的參考角色」在每個等級的輸出與生命，對上同等級普通怪與區域 Boss。
 * /ec balance 用它印報表；monsters.yml 的生命／傷害錨點、skills.yml 的法術成長、zones.yml 的 Boss 生命倍率
 * 就是照它對準 balance.yml 的目標算出來的。純函式，不依賴 Bukkit。
 * <p>
 * 「擊殺秒數」用的是生命 ÷ 每秒傷害（把打過頭的傷害也算進去的換算值，平滑、好對目標）；
 * 實際上一下打不死也要揮完整下，所以另外用擲骰模擬算「平均幾下打死」，報表兩個都列。
 */
public final class BalanceModel {

    /** 同等級普通怪用哪種原版生物的倍率（default = 1 倍）。 */
    private static final String REFERENCE_MONSTER = "default";
    /** 模擬「平均幾下打死」每個等級打幾隻（固定亂數種子，每次結果一樣；每個等級用同一串亂數，相鄰等級才不會亂跳）。 */
    private static final int KILL_SAMPLES = 100;
    /** 攻擊與法術各自的亂數種子。 */
    private static final long ATTACK_SEED = 11;
    private static final long SPELL_SEED = 23;

    /**
     * 參考角色的一邊（攻擊或法術）打某個目標。
     *
     * @param skill          技能名稱
     * @param gemLevel       技能寶石等級（角色能用的最高等級）
     * @param supports       掛了哪些輔助（名稱）
     * @param gear           身上的傷害詞綴（說明用）
     * @param perHit         打中一下的平均傷害（目標的護甲、抗性減免後，不含暴擊）
     * @param perSecond      每秒動作次數
     * @param hitChance      命中率（法術 1）
     * @param critChance     暴擊率
     * @param critMultiplier 暴擊倍率（2.0 = 200%）
     * @param dps            每秒平均傷害
     * @param life           參考角色的最大生命
     * @param swingsToKill   平均幾下打死目標（擲骰模擬；沒模擬時是 0）
     */
    public record Side(String skill, int gemLevel, List<String> supports, List<String> gear, double perHit,
                       double perSecond, double hitChance, double critChance, double critMultiplier, double dps,
                       double life, double swingsToKill) {
        public Side {
            supports = List.copyOf(supports);
            gear = List.copyOf(gear);
        }

        /** 實際打死一隻的秒數：平均幾下 × 一下的時間。 */
        public double actualSeconds() {
            return swingsToKill / Math.max(1e-9, perSecond);
        }
    }

    /**
     * 一個等級的結果（對同等級普通怪）。
     *
     * @param monsterLife       同等級普通怪的生命
     * @param monsterHit        同等級普通怪打一下的平均傷害（減免前）
     * @param targetSeconds     目標擊殺秒數（換算值）
     * @param targetHitPercent  目標：打一下扣參考角色幾 % 生命
     * @param spellVsAttack     目標：法術單體是攻擊的幾倍
     */
    public record Row(int level, Side attack, Side spell, double monsterLife, double monsterHit,
                      double targetSeconds, double targetHitPercent, double spellVsAttack) {

        /** 參考攻擊角色打死一隻的換算秒數（生命 ÷ 每秒傷害）。 */
        public double attackSeconds() {
            return monsterLife / Math.max(1e-9, attack.dps());
        }

        /** 參考法術角色打死一隻的換算秒數（單體）。 */
        public double spellSeconds() {
            return monsterLife / Math.max(1e-9, spell.dps());
        }

        /** 法術的目標秒數：攻擊的目標 ÷ 法術對攻擊的比例。 */
        public double spellTargetSeconds() {
            return targetSeconds / Math.max(1e-9, spellVsAttack);
        }

        /** 怪物打一下扣參考攻擊角色幾 % 生命。 */
        public double hitPercent() {
            return monsterHit / Math.max(1e-9, attack.life()) * 100;
        }

        /** 法術單體輸出是攻擊的幾倍。 */
        public double spellRatio() {
            return spell.dps() / Math.max(1e-9, attack.dps());
        }

        /** 照目標擊殺秒數，這個等級的怪物生命應該是多少。 */
        public double suggestedLife() {
            return attack.dps() * targetSeconds;
        }

        /** 照目標，這個等級的怪物打一下應該是多少（平均）。 */
        public double suggestedHit() {
            return attack.life() * targetHitPercent / 100;
        }
    }

    /**
     * 一隻區域 Boss（zones.yml）。
     *
     * @param zone           區域代號
     * @param name           名稱
     * @param level          等級（區域的最高等級：Boss 場地）
     * @param type           原版生物種類（monsters.yml 的 types 倍率）
     * @param lifeMultiplier zones.yml 的 life 倍率
     * @param mods           怪物詞綴
     */
    /**
     * @param targetScale 目標秒數的倍數（極境、世界 Boss 本來就要打比較久）
     * @param fileValue   設定檔裡實際寫的那個數字（極境的 uber.life、回響的 weekly.life；一般 Boss 跟 lifeMultiplier 一樣）
     */
    public record BossSpec(String zone, String name, int level, String type, double lifeMultiplier, List<String> mods, double targetScale,
                           double fileValue) {
        public BossSpec {
            mods = List.copyOf(mods);
            targetScale = targetScale <= 0 ? 1 : targetScale;
        }

        public BossSpec(final String zone, final String name, final int level, final String type, final double lifeMultiplier,
                        final List<String> mods) {
            this(zone, name, level, type, lifeMultiplier, mods, 1, lifeMultiplier);
        }

        public BossSpec(final String zone, final String name, final int level, final String type, final double lifeMultiplier,
                        final List<String> mods, final double targetScale) {
            this(zone, name, level, type, lifeMultiplier, mods, targetScale, lifeMultiplier);
        }
    }

    /** zones.yml 的區域 Boss。Boss 的等級是區域的最高等級（Boss 場地）。 */
    public static List<BossSpec> bossSpecs(final ZoneConfig zones) {
        final List<BossSpec> specs = new ArrayList<>();
        for (final ZoneConfig.ZoneDefinition zone : zones.combatZones()) {
            final ZoneConfig.BossDefinition boss = zone.boss();
            if (boss != null) {
                specs.add(new BossSpec(zone.id(), boss.name(), zone.maxLevel(), boss.vanillaType(), boss.lifeMultiplier(), boss.mods()));
            }
        }
        return specs;
    }

    /**
     * maps.yml 的地圖 Boss 與終局 Boss（代號寫成 map:地圖）。地圖 Boss 生出來時生命倍率會扣掉那種生物本身的倍率，
     * 所以這裡用參考怪物的種類、直接用設定檔寫的倍率，建議值也就是 maps.yml 要寫的數字。
     * 每張地圖都能開 T1–T16；這裡算「圈數那一階」（第 3 圈 = T3），終局算最外圈那一階＋終局的等級加成，
     * 跟 maps.yml 的倍率當初對準的一樣（更高階的 Boss 跟著同等級怪物一起變硬，打的時間照 boss_seconds 的比例變長）。
     * 終局的 final.tier_life 在這一階是 ×1（T5 以下不加），更高階的終局 Boss 再照它乘，這裡不算。
     */
    public static List<BossSpec> mapBossSpecs(final tw.exilecore.map.MapConfig maps) {
        final List<BossSpec> specs = new ArrayList<>();
        for (final tw.exilecore.map.MapConfig.MapDef map : maps.all()) {
            final int level = map.isFinal() ? maps.tiers().level(maps.maxRing()) + maps.finalLevelBonus() : maps.tiers().level(map.ring());
            specs.add(new BossSpec("map:" + map.id(), map.boss().name(), level, REFERENCE_MONSTER, map.boss().life(), map.boss().mods()));
        }
        return specs;
    }

    /** 一隻區域 Boss 的結果：生命、參考角色要打幾秒（站著打、魔力無限）、目標。 */
    public record BossRow(BossSpec spec, double life, double attackSeconds, double spellSeconds, double targetSeconds) {

        /** 照目標秒數，zones.yml 的 life 倍率應該是多少。 */
        public double suggestedLifeMultiplier() {
            return spec.fileValue() * targetSeconds / Math.max(1e-9, attackSeconds);
        }
    }

    /**
     * 照目標算出來的錨點建議（錨點等級跟現在的表一樣）。
     *
     * @param life        monsters.yml 的 life（等級 → 生命）
     * @param damage      monsters.yml 的 damage（等級 → [最小, 最大]，照現在的比例拆）
     * @param spellGrowth skills.yml 的 spell_damage_growth（需求等級 → 倍率）
     */
    public record Suggestions(NavigableMap<Integer, Double> life, NavigableMap<Integer, double[]> damage,
                              NavigableMap<Integer, Double> spellGrowth) {
    }

    private final ItemRepository items;
    private final SkillConfig skills;
    private final MonsterConfig monsters;
    private final BalanceConfig config;

    public BalanceModel(final ItemRepository items, final SkillConfig skills, final MonsterConfig monsters, final BalanceConfig config) {
        this.items = items;
        this.skills = skills;
        this.monsters = monsters;
        this.config = config;
    }

    public BalanceConfig config() {
        return config;
    }

    // ---- 檢查 ----

    /** 設定對不到的東西（找不到的武器、技能、詞綴、輔助，技能不能用這把武器…）；空的表示可以算。 */
    public List<String> problems() {
        final List<String> problems = new ArrayList<>();
        check(problems, "attack", config.attack(), true);
        check(problems, "spell", config.spell(), false);
        if (items.affix("life_flat") == null) {
            problems.add("找不到生命詞綴 life_flat（affixes）");
        }
        return problems;
    }

    private void check(final List<String> problems, final String where, final BalanceConfig.Build build, final boolean attack) {
        final String path = "balance.yml reference." + where;
        final ItemBase weapon = items.base(build.weapon());
        if (weapon == null || !weapon.isWeapon()) {
            problems.add(path + ".weapon：" + build.weapon() + " 不是武器基底");
        }
        final SkillDefinition skill = skills.skill(build.skill());
        if (skill == null || skill.hidden()) {
            problems.add(path + ".skill：找不到技能 " + build.skill());
        } else {
            if (skill.isAttack() != attack) {
                problems.add(path + ".skill：" + build.skill() + (attack ? " 不是攻擊技能" : " 不是法術"));
            }
            if (weapon != null && !skill.usableWith(weapon.tags())) {
                problems.add(path + "：" + build.skill() + " 不能用 " + build.weapon() + "（" + skill.weaponText() + "）");
            }
        }
        for (final BalanceConfig.GearAffix gear : build.gear()) {
            if (items.affix(gear.affix()) == null) {
                problems.add(path + ".gear：找不到詞綴 " + gear.affix());
            }
        }
        for (final String id : build.supports()) {
            if (skills.support(id) == null) {
                problems.add(path + ".supports：找不到輔助寶石 " + id);
            }
        }
    }

    // ---- 計算 ----

    /** 1 到 maxLevel 每一級。設定有問題時丟 IllegalStateException（先看 {@link #problems()}）。 */
    public List<Row> rows(final int maxLevel) {
        final List<Row> rows = new ArrayList<>();
        for (int level = 1; level <= maxLevel; level++) {
            rows.add(row(level));
        }
        return rows;
    }

    /** 算一個等級（含「平均幾下打死」的擲骰模擬）。 */
    public Row row(final int level) {
        final StatSheet monster = monsterSheet(level, MonsterRarity.NORMAL, List.of(), 1.0, false, REFERENCE_MONSTER);
        final double life = monster.get(StatKeys.LIFE_MAX);
        final Side attack = side(config.attack(), level, monster, life, ATTACK_SEED);
        final Side spell = side(config.spell(), level, monster, life, SPELL_SEED);
        final double[] damage = monsters.damageAt(level);
        final double damageMultiplier = monsters.type(REFERENCE_MONSTER).damageMultiplier()
                * monsters.rarity(MonsterRarity.NORMAL).damageMultiplier();
        final double monsterHit = (damage[0] + damage[1]) / 2 * damageMultiplier;
        return new Row(level, attack, spell, life, monsterHit, config.killSeconds(level), config.hitPercent(level),
                config.spellVsAttack());
    }

    /** 一隻區域 Boss：站著打、魔力無限要打幾秒（Boss 的生命、護甲、抗性、詞綴都照遊戲裡的算）。 */
    public BossRow boss(final BossSpec spec) {
        final List<String> known = new ArrayList<>();
        for (final String id : spec.mods()) {
            if (monsters.mod(id) != null) {
                known.add(id);
            }
        }
        final StatSheet sheet = monsterSheet(spec.level(), MonsterRarity.UNIQUE, known, spec.lifeMultiplier(), true,
                spec.type() == null ? REFERENCE_MONSTER : spec.type());
        final double life = sheet.get(StatKeys.LIFE_MAX);
        final Side attack = side(config.attack(), spec.level(), sheet, life, 0);
        final Side spell = side(config.spell(), spec.level(), sheet, life, 0);
        return new BossRow(spec, life, life / Math.max(1e-9, attack.dps()), life / Math.max(1e-9, spell.dps()),
                config.bossSeconds(spec.level()) * spec.targetScale()); // 極境、世界 Boss 這類本來就要打比較久
    }

    private StatSheet monsterSheet(final int level, final MonsterRarity rarity, final List<String> mods,
                                   final double lifeMultiplier, final boolean boss, final String type) {
        return MonsterSheets.build(monsters, new MonsterData(level, rarity, mods, "參考怪物", lifeMultiplier, 1.0, boss), type);
    }

    /**
     * 參考角色打一個目標。seed ≠ 0 時另外擲骰模擬「平均幾下打死」（目標生命 targetLife）。
     */
    private Side side(final BalanceConfig.Build build, final int level, final StatSheet target, final double targetLife, final long seed) {
        final ItemBase weapon = items.base(build.weapon());
        final SkillDefinition skill = skills.skill(build.skill());
        if (weapon == null || skill == null) {
            throw new IllegalStateException("balance.yml 的參考角色設定有誤：" + problems());
        }
        final List<String> gear = new ArrayList<>();
        final StatSheet sheet = referenceSheet(build, weapon, level, gear);
        final int gemLevel = skills.maxLevelFor(level);
        final List<SupportDefinition> supports = supports(build, skill, level);
        // 2026-10-27：寶石屬性不足會自動降級；平衡曲線假設參考角色配得起滿級寶石（需求 ×0）
        final StatSheet noRequirement = sheet.overlay(List.of(tw.exilecore.stats.Modifier.increased(
                tw.exilecore.stats.TreeEffectKeys.ITEM_ATTR_REQUIREMENT, -1, "平衡模型")));
        final SkillBuild skillBuild = SkillBuild.of(skills, skill, gemLevel, level, false, supports, weapon.tags(), noRequirement);
        final SkillProfile profile = skillBuild.profile();
        final StatSheet effective = profile.effective(sheet);

        final DamagePacket outgoing = DamageCalculator.expectedOutgoing(sheet, profile);
        final double perHit = DamageCalculator.mitigate(outgoing, target).total();
        final double critChance = DamageCalculator.critChance(sheet, profile);
        final double critMultiplier = tw.exilecore.stats.DamageCalculator.critMultiplier(effective, profile.tags(), null);
        final double hitChance = skill.isAttack()
                ? Formulas.hitChance(effective.get(StatKeys.ACCURACY), target.get(StatKeys.EVASION)) : 1.0;
        final double block = skill.isAttack() ? Math.min(DamageCalculator.blockCap(target), target.get(StatKeys.BLOCK_CHANCE)) / 100.0 : 0;
        final double perSecond = 20.0 / skillBuild.intervalTicks(sheet);
        final double dps = perHit * (1 + critChance * (critMultiplier - 1)) * hitChance * (1 - block) * perSecond;
        double swings = 0;
        if (seed != 0) {
            // 平均每下的傷害（含沒打中、被格擋、暴擊）幾乎是 0：打不死，不用模擬（不然會揮到上限、卡住伺服器）
            final double perSwing = dps / Math.max(1e-9, perSecond);
            if (perSwing <= targetLife * 1e-4) {
                swings = Double.POSITIVE_INFINITY;
            } else {
                // 輔助寶石先疊好，擲骰時不用每一下重疊一次
                final SkillProfile bare = new SkillProfile(profile.id(), profile.tags(), profile.effectiveness(),
                        profile.spellDamage(), profile.spellDamageMax(), profile.conversion(), List.of());
                final int cap = (int) Math.min(10_000, Math.max(50, Math.ceil(20 * targetLife / perSwing)));
                swings = swingsToKill(effective, bare, target, targetLife, seed, cap);
            }
        }
        final List<String> supportNames = new ArrayList<>();
        for (final SupportDefinition support : skillBuild.supports()) {
            supportNames.add(support.name());
        }
        return new Side(skill.name(), skillBuild.level(), supportNames, gear, perHit, perSecond, hitChance,
                critChance, critMultiplier, dps, sheet.get(StatKeys.LIFE_MAX), swings);
    }

    /** 擲骰模擬：平均幾下（含沒打中的）才把 life 打光；一隻最多揮 cap 下（超過就當 cap 下）。 */
    private static double swingsToKill(final StatSheet attacker, final SkillProfile profile, final StatSheet target,
                                       final double life, final long seed, final int cap) {
        final SplittableRandom random = new SplittableRandom(seed);
        long total = 0;
        for (int i = 0; i < KILL_SAMPLES; i++) {
            double remaining = life;
            int swings = 0;
            while (remaining > 1e-9 && swings < cap) {
                final HitResult result = DamageCalculator.calculate(attacker, profile, target, 0, random);
                swings++;
                remaining -= result.total();
            }
            total += swings;
        }
        return total / (double) KILL_SAMPLES;
    }

    /**
     * 參考角色的屬性表：職業起始屬性 + 天賦估計 + 那個物品等級的武器 + 傷害與生命詞綴，最後算屬性帶來的基礎值（跟角色的重算同一套公式）。
     * 裝備的物品等級 = 角色等級，最高到 balance.yml 的 item_level_cap（最高階地圖的怪物等級）。
     */
    private StatSheet referenceSheet(final BalanceConfig.Build build, final ItemBase weapon, final int level, final List<String> gear) {
        final int itemLevel = Math.min(level, config.itemLevelCap());
        final StatSheet sheet = new StatSheet();
        final CharacterClass characterClass = build.characterClass();
        final double bonus = config.attributePerLevel() * (level - 1);
        final String main = mainAttribute(characterClass);
        sheet.setBase(StatKeys.STR, characterClass.strength() + (StatKeys.STR.equals(main) ? bonus : 0));
        sheet.setBase(StatKeys.DEX, characterClass.dexterity() + (StatKeys.DEX.equals(main) ? bonus : 0));
        sheet.setBase(StatKeys.INT, characterClass.intelligence() + (StatKeys.INT.equals(main) ? bonus : 0));
        sheet.setBase(StatKeys.RESIST_MAX, 75);
        sheet.setBase(StatKeys.CRIT_MULTI, 200);
        sheet.setBase(StatKeys.LIFE_REGEN, 0);

        // 天賦（估）：每級固定的傷害與生命加成
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, config.treeDamagePerLevel() * (level - 1) / 100.0, Set.of(), "天賦（估計）"));
        sheet.add(Modifier.increased(StatKeys.LIFE_MAX, config.treeLifePerLevel() * (level - 1) / 100.0, "天賦（估計）"));

        // 武器：那個物品等級的基底數值與固定詞綴（取中間值）
        final ItemData data = new ItemData(weapon.id(), Rarity.NORMAL, itemLevel);
        if (weapon.implicit() != null) {
            data.setImplicitVars(middle(weapon.implicit().vars()));
        }
        sheet.addAll(ItemStats.modifiers(items, data));

        // 傷害詞綴與生命詞綴（中等裝備：那個物品等級能出的第 gear_tier 好的階級，數值取中間）
        for (final BalanceConfig.GearAffix entry : build.gear()) {
            if (entry.from() <= level) {
                addAffix(sheet, entry.affix(), itemLevel, gear);
            }
        }
        for (int i = 0; i < config.lifeAffixes(level); i++) {
            addAffix(sheet, "life_flat", itemLevel, gear);
        }

        // 屬性帶來的基礎值（跟 PlayerStateService.rebuild 同一套公式）
        final double str = sheet.get(StatKeys.STR);
        final double dex = sheet.get(StatKeys.DEX);
        final double intelligence = sheet.get(StatKeys.INT);
        sheet.setBase(StatKeys.LIFE_MAX, Formulas.baseLife(level) + Formulas.lifeFromStrength(str));
        sheet.setBase(StatKeys.MANA_MAX, Formulas.baseMana(level) + Formulas.manaFromIntelligence(intelligence));
        sheet.setBase(StatKeys.EVASION, Formulas.baseEvasion(level));
        sheet.setBase(DamageCalculator.FLAG_PLAYER_SIDE, 1); // 2026-10-27：跟 PlayerStateService 一樣
        sheet.setBase(StatKeys.ACCURACY, Formulas.baseAccuracy(level) + Formulas.accuracyFromDexterity(dex));
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, Formulas.meleePhysicalIncreaseFromStrength(str),
                Set.of(Tags.MELEE, Tags.PHYSICAL), "力量"));
        return sheet;
    }

    private void addAffix(final StatSheet sheet, final String id, final int itemLevel, final List<String> gear) {
        final Affix affix = items.affix(id);
        if (affix == null) {
            return;
        }
        final Map<String, Double> vars = gearValues(affix, itemLevel, config.gearTier());
        if (vars.isEmpty()) {
            return;
        }
        final Map<String, Integer> shown = new LinkedHashMap<>();
        for (final Map.Entry<String, Double> entry : vars.entrySet()) {
            shown.put(entry.getKey(), (int) Math.round(entry.getValue()));
        }
        for (final AffixMod mod : affix.mods()) {
            final boolean localPhysical = tw.exilecore.item.LocalMods.PHYSICAL.equals(mod.stat());
            if (mod.isLocal() && !localPhysical) {
                continue; // 武器本身的詞綴（local.）只改武器數值，參考角色的武器另外算
            }
            double value = mod.constant();
            if (mod.variable() != null) {
                final Double rolled = vars.get(mod.variable());
                if (rolled == null) {
                    continue;
                }
                value = (mod.negate() ? -rolled : rolled) / mod.divisor(); // 「-{value}」取負、「{value/10}」是小數
            }
            if (mod.percent()) {
                value /= 100.0;
            }
            // 2026-10：本地的物理增加（PoE2 的武器詞綴）估成角色的「增加物理傷害」
            sheet.add(localPhysical ? new Modifier(StatKeys.DAMAGE, mod.type(), value, java.util.Set.of(tw.exilecore.stats.Tags.PHYSICAL), affix.id())
                    : new Modifier(mod.stat(), mod.type(), value, mod.tags(), affix.id()));
        }
        gear.add(affix.render(shown));
    }

    /**
     * 中等裝備的一條詞綴數值（每個變數取範圍中間）：那個物品等級能出的第 gearTier 好的階級。
     * 為了不讓參考角色在新階級解鎖的那一級突然變強（實際上裝備是慢慢換的），
     * 兩個解鎖等級之間從上一個階級的數值等比過渡到下一個。回傳空的表示這個等級一個階級都出不了。
     */
    public static Map<String, Double> gearValues(final Affix affix, final int itemLevel, final int gearTier) {
        final List<AffixTier> tiers = new ArrayList<>(affix.tiers());
        tiers.sort(Comparator.comparingInt(AffixTier::ilvl).thenComparing(Comparator.comparingInt(AffixTier::tier).reversed()));
        if (tiers.isEmpty() || tiers.get(0).ilvl() > itemLevel) {
            return Map.of();
        }
        // 解鎖到第 unlocked 個（照物品等級排，0 = 最差）時，中等裝備是第 max(0, unlocked − (gearTier − 1)) 個
        int unlocked = 0;
        while (unlocked + 1 < tiers.size() && tiers.get(unlocked + 1).ilvl() <= itemLevel) {
            unlocked++;
        }
        final int lag = Math.max(0, gearTier - 1);
        final AffixTier current = tiers.get(Math.max(0, unlocked - lag));
        final Map<String, Double> values = middle(current);
        if (unlocked + 1 >= tiers.size()) {
            return values;
        }
        final AffixTier next = tiers.get(Math.max(0, unlocked + 1 - lag));
        if (next == current) {
            return values;
        }
        final int from = tiers.get(unlocked).ilvl();
        final int to = tiers.get(unlocked + 1).ilvl();
        final double t = to <= from ? 1.0 : (itemLevel - from) / (double) (to - from);
        final Map<String, Double> nextValues = middle(next);
        final Map<String, Double> blended = new LinkedHashMap<>();
        for (final Map.Entry<String, Double> entry : values.entrySet()) {
            final Double target = nextValues.get(entry.getKey());
            blended.put(entry.getKey(), target == null ? entry.getValue() : LevelCurve.between(entry.getValue(), target, t));
        }
        return blended;
    }

    private static Map<String, Double> middle(final AffixTier tier) {
        final Map<String, Double> values = new LinkedHashMap<>();
        for (final Map.Entry<String, int[]> entry : tier.vars().entrySet()) {
            values.put(entry.getKey(), (entry.getValue()[0] + entry.getValue()[1]) / 2.0);
        }
        return values;
    }

    /** 照順序掛輔助：孔數照等級、階級要已經掉得到（未切割輔助寶石的階級）、要能輔助這個技能。 */
    private List<SupportDefinition> supports(final BalanceConfig.Build build, final SkillDefinition skill, final int level) {
        final int sockets = config.supportSockets(level);
        final int maxTier = skills.supportTierFor(level);
        final List<SupportDefinition> list = new ArrayList<>();
        for (final String id : build.supports()) {
            if (list.size() >= sockets) {
                break;
            }
            final SupportDefinition support = skills.support(id);
            if (support != null && support.tier() <= maxTier && support.supports(skill)) {
                list.add(support);
            }
        }
        return list;
    }

    private static Map<String, Integer> middle(final Map<String, int[]> ranges) {
        final Map<String, Integer> vars = new LinkedHashMap<>();
        for (final Map.Entry<String, int[]> entry : ranges.entrySet()) {
            vars.put(entry.getKey(), (int) Math.round((entry.getValue()[0] + entry.getValue()[1]) / 2.0));
        }
        return vars;
    }

    private static String mainAttribute(final CharacterClass characterClass) {
        if (characterClass.strength() >= characterClass.dexterity() && characterClass.strength() >= characterClass.intelligence()) {
            return StatKeys.STR;
        }
        return characterClass.dexterity() >= characterClass.intelligence() ? StatKeys.DEX : StatKeys.INT;
    }

    // ---- 建議的錨點 ----

    /**
     * 照目標算錨點建議：對每一級做對數最小平方，錨點等級跟現在的表一樣。
     * 最高等級以上的錨點（終局地圖用）照現在的表跟最後一個被算的錨點的比例延伸。
     *
     * @param rows {@link #rows(int)} 的結果（1 級起、連續）
     */
    public Suggestions suggest(final List<Row> rows) {
        final int maxLevel = rows.isEmpty() ? 1 : rows.get(rows.size() - 1).level();
        final Map<Integer, Double> lifeTargets = new TreeMap<>();
        final Map<Integer, Double> hitTargets = new TreeMap<>();
        final double damageMultiplier = monsters.type(REFERENCE_MONSTER).damageMultiplier()
                * monsters.rarity(MonsterRarity.NORMAL).damageMultiplier();
        final Map<Integer, Row> byLevel = new TreeMap<>();
        for (final Row row : rows) {
            lifeTargets.put(row.level(), row.suggestedLife());
            hitTargets.put(row.level(), row.suggestedHit() / Math.max(1e-9, damageMultiplier));
            byLevel.put(row.level(), row);
        }
        // 法術成長（依寶石的需求等級）：每個寶石等級從它的需求等級用到下一個寶石等級的前一級（最高的用到 maxLevel），
        // 那幾級需要的倍率（現在的倍率 × 目標比例 ÷ 現在的比例）取幾何平均，
        // 不然寶石等級間隔長的地方（50 級以後），快拿到下一個寶石等級前法術一直偏弱。
        final Map<Integer, Double> growthTargets = new TreeMap<>();
        for (int skillLevel = 1; skillLevel <= SkillLoadout.MAX_LEVEL; skillLevel++) {
            // 2026-10-26：法術成長照掉落用的等級表（需求等級改成 PoE2 之後成長曲線不跟著變）
            final int from = skills.dropLevel(skillLevel);
            final int to = skillLevel < SkillLoadout.MAX_LEVEL ? skills.dropLevel(skillLevel + 1) - 1 : maxLevel;
            double logSum = 0;
            int count = 0;
            for (int level = from; level <= Math.min(to, maxLevel); level++) {
                final Row row = byLevel.get(level);
                if (row != null && row.spellRatio() > 0) {
                    logSum += Math.log(skills.spellGrowth(from) * config.spellVsAttack() / row.spellRatio());
                    count++;
                }
            }
            if (count > 0) {
                growthTargets.put(from, Math.exp(logSum / count));
            }
        }
        final NavigableMap<Integer, Double> life = fitAndExtend(monsters.lifeAnchors(), lifeTargets, maxLevel);
        final NavigableMap<Integer, double[]> currentDamage = monsters.damageAnchors();
        final NavigableMap<Integer, Double> currentAverage = new TreeMap<>();
        for (final Map.Entry<Integer, double[]> entry : currentDamage.entrySet()) {
            currentAverage.put(entry.getKey(), (entry.getValue()[0] + entry.getValue()[1]) / 2);
        }
        final NavigableMap<Integer, Double> average = fitAndExtend(currentAverage, hitTargets, maxLevel);
        final NavigableMap<Integer, double[]> damage = new TreeMap<>();
        for (final Map.Entry<Integer, Double> entry : average.entrySet()) {
            final double[] current = currentDamage.get(entry.getKey());
            final double ratio = current == null || current[0] <= 0 ? 1.5 : current[1] / current[0];
            final double min = entry.getValue() * 2 / (1 + ratio);
            damage.put(entry.getKey(), new double[] {min, min * ratio});
        }
        final NavigableMap<Integer, Double> growth = fitAndExtend(skills.spellGrowthAnchors(), growthTargets, maxLevel);
        return new Suggestions(life, damage, growth);
    }

    /**
     * 用資料撐得住的錨點去貼目標，其他的照現在的表延伸：
     * <ul>
     *   <li>≤ maxLevel 的錨點都算；maxLevel 之後的第一個錨點只有在資料蓋到那一段的一半以上、而且有兩個以上的點時才算
     *       （不然一兩個點就把它拉歪）。</li>
     *   <li>資料權重不到一個點的錨點（例如那一段剛好沒有寶石的需求等級）不拿去貼。</li>
     *   <li>沒被算到的錨點照現在的表，跟最近的（下面的，沒有就上面的）算出來的錨點保持原本的比例。</li>
     * </ul>
     */
    static NavigableMap<Integer, Double> fitAndExtend(final NavigableMap<Integer, Double> current,
                                                      final Map<Integer, Double> targets, final int maxLevel) {
        final List<Integer> candidates = new ArrayList<>(current.headMap(maxLevel, true).keySet());
        final Integer after = current.higherKey(maxLevel);
        if (after != null && !candidates.isEmpty()) {
            final int last = candidates.get(candidates.size() - 1);
            int covered = 0;
            for (final Integer level : targets.keySet()) {
                if (level > last && level < after) {
                    covered++;
                }
            }
            if (covered >= 2 && maxLevel >= last + (after - last) / 2.0) {
                candidates.add(after);
            }
        }
        // 每個錨點分到的資料權重（內插權重的總和）
        final List<Integer> fitted = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            final int anchor = candidates.get(i);
            final Integer previous = i > 0 ? candidates.get(i - 1) : null;
            final Integer next = i + 1 < candidates.size() ? candidates.get(i + 1) : null;
            double weight = 0;
            for (final Map.Entry<Integer, Double> entry : targets.entrySet()) {
                if (!(entry.getValue() > 0)) {
                    continue;
                }
                final int level = entry.getKey();
                if (level == anchor || (previous == null && level < anchor) || (next == null && level > anchor)) {
                    weight += 1;
                } else if (previous != null && level > previous && level < anchor) {
                    weight += (level - previous) / (double) (anchor - previous);
                } else if (next != null && level > anchor && level < next) {
                    weight += (next - level) / (double) (next - anchor);
                }
            }
            if (weight >= 1.0) {
                fitted.add(anchor);
            }
        }
        final NavigableMap<Integer, Double> result = new TreeMap<>();
        if (fitted.isEmpty()) {
            return result;
        }
        final int maxFitted = fitted.get(fitted.size() - 1);
        final Map<Integer, Double> usable = new TreeMap<>();
        for (final Map.Entry<Integer, Double> entry : targets.entrySet()) {
            // 最後一個錨點之後的資料只會把它拉平（那一段沒有下一個錨點），不拿去貼
            if (entry.getKey() <= maxFitted || !current.containsKey(maxFitted) || current.higherKey(maxFitted) == null) {
                usable.put(entry.getKey(), entry.getValue());
            }
        }
        result.putAll(CurveFit.fit(fitted.stream().mapToInt(Integer::intValue).toArray(), usable));
        // 其他錨點照現在的表的比例延伸
        for (final Map.Entry<Integer, Double> entry : current.entrySet()) {
            if (result.containsKey(entry.getKey())) {
                continue;
            }
            Integer base = result.floorKey(entry.getKey());
            if (base == null) {
                base = result.ceilingKey(entry.getKey());
            }
            final Double baseCurrent = current.get(base);
            final double ratio = baseCurrent == null || baseCurrent <= 0 ? 1 : entry.getValue() / baseCurrent;
            result.put(entry.getKey(), result.get(base) * ratio);
        }
        return result;
    }
}
