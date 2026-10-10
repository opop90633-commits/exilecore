package tw.exilecore.map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ascendancy.AscendancyConfig;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomyConfig;
import tw.exilecore.skill.BossSkill;
import tw.exilecore.skill.BossSkillUse;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 終局與試煉 Boss 的特殊機制（護盾、狂暴、場地危害）與極境（Uber）：讀設定、合併、出貨的資料。 */
class UberTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static MapConfig config;
    static SkillConfig skills;
    static CurrencyRepository currencies;

    @BeforeAll
    static void load() throws IOException {
        config = new MapConfig();
        config.load(DATA);
        skills = new SkillConfig();
        skills.load(DATA);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
    }

    private static YamlNode yaml(final String text) {
        return YamlNode.load("test.yml", new StringReader(text));
    }

    // ---- 護盾、狂暴 ----

    @Test
    void shieldAndEnrageParseWithDefaultsAndLimits() {
        final MapConfig.Shield shield = MapConfig.parseShield(yaml("count: 2\n"));
        assertEquals(2, shield.count());
        assertEquals("BLAZE", shield.type());
        assertEquals("星骸結晶", shield.name());
        assertEquals(4, shield.life(), 1e-9);
        assertEquals(25, shield.duration(), 1e-9);
        assertEquals(25, shield.heal(), 1e-9);
        assertEquals("STRAY", MapConfig.parseShield(yaml("type: stray\n")).type(), "生物代號轉大寫");
        assertThrows(IllegalArgumentException.class, () -> MapConfig.parseShield(yaml("count: 0\n")));
        assertThrows(IllegalArgumentException.class, () -> MapConfig.parseShield(yaml("count: 9\n")));
        assertThrows(IllegalArgumentException.class, () -> MapConfig.parseShield(yaml("duration: 3\n")), "太短打不完");
        assertEquals(100, MapConfig.parseShield(yaml("heal: 500\n")).heal(), 1e-9, "最多回滿");

        final MapConfig.Enrage enrage = MapConfig.parseEnrage(yaml("after: 300\n"));
        assertEquals(300, enrage.after(), 1e-9);
        assertEquals(100, enrage.damage(), 1e-9);
        assertEquals(0.3, enrage.speed(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> MapConfig.parseEnrage(yaml("after: 10\n")), "開打 10 秒就狂暴太短");

        final MapConfig.Boss boss = MapConfig.parseBoss(yaml("""
                name: 測試
                phases:
                  - at: 0.5
                    shield: { count: 2, duration: 20 }
                  - at: 0.8
                enrage: { after: 120, damage: 50 }
                hazards:
                  - { skill: hazard_star_meteor, every: 9 }
                """));
        assertEquals(0.8, boss.phases().getFirst().at(), 1e-9, "階段照血量由高到低排");
        assertNull(boss.phases().getFirst().shield());
        assertEquals(2, boss.phases().get(1).shield().count());
        assertEquals(50, boss.enrage().damage(), 1e-9);
        assertEquals(1, boss.hazards().size());
        final MapConfig.Boss plain = MapConfig.parseBoss(yaml("name: 普通\n"));
        assertNull(plain.enrage());
        assertTrue(plain.hazards().isEmpty());
    }

    // ---- 極境 ----

    @Test
    void uberParsesWithDefaults() {
        final MapConfig.Uber uber = MapConfig.parseUber(yaml("""
                skills: [uber_emperor_cross_beams]
                drops:
                  extra:
                    - { currency: zenith_orb, amount: 3, chance: 0.5 }
                    - { support: some_support }
                """));
        assertNull(uber.name());
        assertEquals(4, uber.life(), 1e-9, "2026-10：3 → 4（預設跟出貨的 maps.yml 一樣）");
        assertEquals(1.5, uber.damage(), 1e-9);
        assertEquals(0.75, uber.cooldown(), 1e-9);
        assertEquals(4, uber.levelBonus());
        assertEquals("abyss_core", uber.key());
        assertEquals(1, uber.keyCost());
        assertEquals(0.1, uber.keyChance(), 1e-9);
        assertEquals(12, uber.keyMinTier());
        assertEquals(4, uber.drops().maps());
        assertEquals(0.2, uber.drops().uniqueChance(), 1e-9);
        assertEquals(new MapConfig.ExtraDrop("currency", "zenith_orb", 3, 0.5), uber.drops().extra().getFirst());
        assertEquals(new MapConfig.ExtraDrop("support", "some_support", 1, 1.0), uber.drops().extra().get(1));
        assertTrue(uber.phases().isEmpty(), "沒寫階段：照原本的");
        assertThrows(IllegalArgumentException.class, () -> MapConfig.parseUber(yaml("cooldown: 0.1\n")));
        assertThrows(IllegalArgumentException.class, () -> MapConfig.parseUber(yaml("drops:\n  extra:\n    - { amount: 2 }\n")),
                "額外掉落要寫是哪種通貨");
    }

    @Test
    void uberMergeMultipliesAndAppends() {
        final MapConfig.MapDef emperor = config.finalMap();
        final MapConfig.Uber uber = config.uber(emperor.id());
        assertNotNull(uber, "曜帝有極境");
        final MapConfig.Boss base = emperor.boss();
        final MapConfig.Boss merged = uber.merge(base);
        assertEquals(base.name() + "・極境", merged.name(), "沒寫名字：原本的加「・極境」");
        assertEquals(base.life() * uber.life(), merged.life(), 1e-9);
        assertEquals(base.damage() * uber.damage(), merged.damage(), 1e-9);
        assertEquals(base.scale() * 1.1, merged.scale(), 1e-9, "體型大一點");
        assertTrue(merged.named());
        assertEquals(base.skills().size() + uber.skills().size(), merged.skills().size(), "招式加在後面");
        assertEquals(base.skills(), merged.skills().subList(0, base.skills().size()));
        assertEquals(uber.phases(), merged.phases(), "極境寫了階段：整組換掉");
        assertEquals(uber.enrage(), merged.enrage());
        assertEquals(base.hazards().size() + uber.hazards().size(), merged.hazards().size());
        assertEquals(uber.intro(), merged.intro());
        assertEquals(base.defeat(), merged.defeat());
        assertEquals(new HashSet<>(base.mods()), new HashSet<>(merged.mods()), "沒有多的詞綴");
        // 沒寫的極境：照原本的
        final MapConfig.Uber bare = MapConfig.parseUber(yaml("name: 測試極境\n"));
        final MapConfig.Boss same = bare.merge(base);
        assertEquals("測試極境", same.name());
        assertEquals(base.phases(), same.phases());
        assertEquals(base.enrage(), same.enrage());
        assertEquals(base.skills(), same.skills());
    }

    @Test
    void uberStatsUseTheTopTierPlusBonusAndShortenCooldowns() {
        final MapConfig.MapDef emperor = config.finalMap();
        final MapConfig.Uber uber = config.uber(emperor.id());
        final MapStats stats = config.uberStats(emperor.id(), uber);
        assertEquals(config.tiers().max(), stats.tier());
        assertEquals(config.tiers().topLevel() + config.finalLevelBonus(emperor.id()) + uber.levelBonus(), stats.monsterLevel());
        final StatSheet sheet = new StatSheet().addAll(stats.monsterModifiers());
        assertEquals(uber.cooldown(), sheet.apply(StatKeys.SKILL_COOLDOWN, 1.0, Set.of()), 1e-9, "怪物的招式冷卻 ×0.75");
        final MapConfig.Uber custom = MapConfig.parseUber(yaml("""
                cooldown: 1
                monster:
                  - { stat: damage, type: INCREASED, value: 20 }
                player:
                  - { stat: resist.fire, type: FLAT, value: -10 }
                """));
        final MapStats customStats = config.uberStats(emperor.id(), custom);
        assertEquals(1, customStats.monsterModifiers().size(), "冷卻不變就不加；只有那條傷害");
        final Modifier monster = customStats.monsterModifiers().getFirst();
        assertEquals(StatKeys.DAMAGE, monster.stat());
        assertEquals(0.2, monster.value(), 1e-9);
        assertEquals(-10, new StatSheet().addAll(customStats.playerModifiers()).get(StatKeys.RESIST_FIRE), 1e-9);
    }

    @Test
    void finalBossLifeGrowsWithTier() {
        // 純計算：from 階（含）以下 ×1，往上每階等量增加，最高階 ×max
        assertEquals(1.0, MapConfig.finalLifeMultiplier(1, 5, 2.5, 16), 1e-9);
        assertEquals(1.0, MapConfig.finalLifeMultiplier(5, 5, 2.5, 16), 1e-9, "T5 跟以前一樣");
        assertEquals(1 + 1.5 / 11, MapConfig.finalLifeMultiplier(6, 5, 2.5, 16), 1e-9);
        assertEquals(1 + 1.5 * 5 / 11, MapConfig.finalLifeMultiplier(10, 5, 2.5, 16), 1e-9);
        assertEquals(2.5, MapConfig.finalLifeMultiplier(16, 5, 2.5, 16), 1e-9);
        assertEquals(2.5, MapConfig.finalLifeMultiplier(20, 5, 2.5, 16), 1e-9, "超過最高階不再加");
        assertEquals(1.0, MapConfig.finalLifeMultiplier(16, 5, 1, 16), 1e-9, "max 寫 1：關掉");
        assertEquals(1.0, MapConfig.finalLifeMultiplier(16, 16, 2.5, 16), 1e-9, "from 寫到最高階：關掉（不會除以 0）");

        // 出貨的 maps.yml：T5 以下 ×1、T16 ×2.5，中間一階比一階多
        final int max = config.tiers().max();
        assertEquals(1.0, config.finalLifeMultiplier(1), 1e-9);
        assertEquals(1.0, config.finalLifeMultiplier(5), 1e-9, "剛進終局的人照原本的時間打");
        assertEquals(2.5, config.finalLifeMultiplier(max), 1e-9);
        for (int tier = 6; tier <= max; tier++) {
            assertTrue(config.finalLifeMultiplier(tier) > config.finalLifeMultiplier(tier - 1), "T" + tier + " 比前一階多");
        }
        // 極境開在最高階（也吃 ×2.5），再乘自己的 ×4：比 T16 終局多 4 倍
        for (final MapConfig.MapDef map : config.finals()) {
            final MapConfig.Uber uber = config.uber(map.id());
            assertEquals(4, uber.life(), 1e-9, map.id() + "：極境的生命 ×4");
            assertEquals(max, config.uberStats(map.id(), uber).tier(), map.id() + "：極境開在最高階");
        }
    }

    @Test
    void phasesBeyondTheBossAreReported() {
        final MapConfig.Boss boss = MapConfig.parseBoss(yaml("""
                name: 測試
                skills:
                  - slam
                  - { id: meteor, phase: 2 }
                  - { id: nope, phase: 1 }
                hazards:
                  - { skill: hazard_star_meteor, every: 9, phase: 3 }
                  - { skill: quake, every: 9 }
                phases:
                  - at: 0.5
                """));
        final List<String> problems = new java.util.ArrayList<>();
        MapConfig.bossProblems(problems, "測試", boss, skills.bossSkills());
        assertEquals(4, problems.size(), String.join("\n", problems));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("nope")), "不存在的招式");
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("meteor") && problem.contains("永遠用不到")), "第 2 階段，但只有 1 個");
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("hazard_star_meteor") && problem.contains("永遠不會落下")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("quake")), "重踏不能當場地危害");
    }

    // ---- 出貨的資料 ----

    @Test
    void everyFinalHasMechanicsAndAnUberVersion() {
        final List<MapConfig.MapDef> finals = config.finals();
        assertEquals(4, finals.size());
        for (final MapConfig.MapDef map : finals) {
            final MapConfig.Boss boss = map.boss();
            assertNotNull(boss.enrage(), map.id() + "：狂暴");
            assertFalse(boss.hazards().isEmpty(), map.id() + "：場地危害");
            assertTrue(boss.phases().stream().anyMatch(phase -> phase.shield() != null), map.id() + "：有一個階段會張護盾");
            final Set<BossSkill.Kind> kinds = new HashSet<>();
            for (final BossSkillUse use : boss.skills()) {
                kinds.add(skills.bossSkills().skill(use.id()).kind());
            }
            int special = 0;
            for (final BossSkill.Kind kind : kinds) {
                if (kind.channelled() || kind == BossSkill.Kind.ORBS) {
                    special++;
                }
            }
            assertTrue(special >= 3, map.id() + "：至少三種特殊招式，實際 " + kinds);
            assertTrue(kinds.contains(BossSkill.Kind.SANCTUARY), map.id() + "：有全場大招");
            assertTrue(kinds.contains(BossSkill.Kind.RING), map.id() + "：有擴散環");

            final MapConfig.Uber uber = config.uber(map.id());
            assertNotNull(uber, map.id() + "：有極境");
            assertTrue(uber.life() >= 2 && uber.damage() >= 1.3 && uber.cooldown() < 1, map.id() + "：極境更硬、更痛、更快");
            assertFalse(uber.skills().isEmpty(), map.id() + "：極境專屬的招式");
            for (final BossSkillUse use : uber.skills()) {
                assertTrue(use.id().startsWith("uber_"), map.id() + "：極境的招式代號 " + use.id());
            }
            final MapConfig.Boss merged = uber.merge(boss);
            for (final BossSkillUse use : merged.skills()) {
                assertTrue(use.fromPhase() <= merged.phases().size(), map.id() + "：極境的 " + use.id() + " 要等第 " + use.fromPhase()
                        + " 階段，但只有 " + merged.phases().size() + " 個階段（永遠用不到）");
            }
            assertTrue(merged.phases().stream().filter(phase -> phase.shield() != null).count() >= 2, map.id() + "：極境張兩次護盾");
            assertTrue(merged.enrage().after() < boss.enrage().after(), map.id() + "：極境的狂暴來得比較早");
            final CurrencyType key = currencies.get(uber.key());
            assertNotNull(key, map.id() + "：開門的通貨要存在");
            assertTrue(uber.keyMinTier() <= config.tiers().max(), map.id() + "：一般版本開得到會掉鑰匙的階級");
            for (final MapConfig.ExtraDrop extra : uber.drops().extra()) {
                if ("support".equals(extra.kind())) {
                    assertNotNull(skills.support(extra.id()), map.id() + "：額外掉落的輔助寶石 " + extra.id());
                    assertFalse(skills.support(extra.id()).cuttable(), map.id() + "：極境掉的是專屬的輔助寶石");
                } else {
                    assertNotNull(currencies.get(extra.id()), map.id() + "：額外掉落 " + extra.id());
                }
            }
            assertTrue(uber.drops().extra().stream().anyMatch(extra -> "support".equals(extra.kind())
                    && skills.support(extra.id()).droppedBy(map.id())), map.id() + "：極境也掉這個終局自己的專屬輔助（機率比一般版本高）");
            assertTrue(uber.drops().exclusiveChance() > 0, map.id() + "：極境專屬傳奇");
        }
        assertEquals(List.of(), config.bossProblems(skills.bossSkills()), "每個 Boss（含極境）的招式與場地危害都存在、可以用");
    }

    @Test
    void trialsTeachOneMechanicEachAndSkillsExist() throws IOException {
        final AscendancyConfig ascendancy = new AscendancyConfig();
        ascendancy.load(DATA);
        int shields = 0;
        int hazards = 0;
        for (final AscendancyConfig.Trial trial : ascendancy.trials()) {
            final MapConfig.Boss boss = trial.boss();
            final List<String> problems = new java.util.ArrayList<>();
            MapConfig.bossProblems(problems, trial.id(), boss, skills.bossSkills());
            assertEquals(List.of(), problems);
            boolean special = false;
            for (final BossSkillUse use : boss.skills()) {
                final BossSkill skill = skills.bossSkills().skill(use.id());
                special |= skill.kind().channelled() || skill.kind() == BossSkill.Kind.ORBS;
            }
            assertTrue(special, trial.id() + "：每場試煉至少一種特殊招式");
            shields += (int) boss.phases().stream().filter(phase -> phase.shield() != null).count();
            hazards += boss.hazards().size();
        }
        assertEquals(1, shields, "第三試煉教護盾");
        assertEquals(1, hazards, "第四試煉有場地危害");
    }

    @Test
    void abyssCoreIsAKeyThatOnlyBossesDrop() throws IOException {
        final CurrencyType core = currencies.get("abyss_core");
        assertNotNull(core);
        assertEquals("深淵星核", core.name());
        assertEquals(0, core.dropWeight(), "怪物不掉，只有終局 Boss 有機率掉");
        assertTrue(tw.exilecore.currency.CurrencyArt.has("abyss_core"), "有自己的圖示");
        final EconomyConfig economy = new EconomyConfig();
        economy.load(DATA);
        assertTrue(economy.value("abyss_core") > economy.value("sky_fragment"), "比天穹碎片值錢");
    }
}
