package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.map.LineageDrops;
import tw.exilecore.map.MapConfig;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.LineageKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 照 PoE2 0.5：血脈輔助寶石（data/supports_lineage.yml）：讀得進來、切不出來、不佔專屬名額、各自的限制、
 * 建技能時的加成、說明文字、掉落機率（站長：一般 = 追逐級傳奇的 2 倍 ≈ 50 萬隻一顆；稀有更難）。
 */
class LineageSupportTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition support = config.support(id);
        assertNotNull(support, id);
        return support;
    }

    private static List<SupportDefinition> lineage() {
        final List<SupportDefinition> list = new ArrayList<>();
        for (final SupportDefinition support : config.supports()) {
            if (support.lineage()) {
                list.add(support);
            }
        }
        return list;
    }

    @Test
    void lineageSupportsLoadAndCannotBeCut() {
        final List<SupportDefinition> all = lineage();
        // 68 顆新的（supports_lineage.yml）＋ supports.yml 原本的 8 顆（existing 標成血脈）；Rigwald's Ferocity 沒做（我們沒有武器組）
        assertEquals(68 + 8, all.size());
        assertEquals(21, config.supportsFrom(LineageRules.COMMON).size(), "一般：poe2db 寫 Global 的");
        assertEquals(41, config.supportsFrom(LineageRules.RARE).size(), "稀有：特定 Boss 或沒寫來源的");
        assertEquals(14, config.supportsFrom(LineageRules.PINNACLE).size(), "巔峰：巔峰 Boss 的、改變玩法的");
        final Set<String> names = new HashSet<>();
        for (final SupportDefinition support : all) {
            assertFalse(support.cuttable(), support.id() + "：未切割輔助寶石切不出來");
            assertFalse(config.supportsUpTo(3).contains(support), support.id());
            assertEquals(3, support.tier(), support.id());
            assertFalse(support.description().isBlank(), support.id());
            assertTrue(names.add(support.name()), "名字重複：" + support.name());
            assertFalse(support.modifiers().isEmpty(), support.id());
        }
        for (final String id : List.of("vruuns_aftermath", "zerphis_infamy", "paquates_pact", "bhatairs_vengeance", "guatelitzi_ablation",
                "ailith_chimes", "morganas_tempest", "helbryms_hide")) {
            assertTrue(support(id).lineage(), id + "：supports.yml 原本的血脈輔助也標成血脈掉落");
        }
        assertEquals(LineageRules.PINNACLE, support("zerphis_infamy").source());
        assertEquals(LineageRules.COMMON, support("helbryms_hide").source());
        assertNull(config.support("rigwalds_ferocity"));
    }

    @Test
    void everyLineageModifierHasPlayerText() {
        for (final SupportDefinition support : lineage()) {
            for (final Modifier modifier : support.modifiers()) {
                final String text = ModifierText.describe(modifier);
                assertNotNull(text, support.id() + " 的 " + modifier.stat());
                assertFalse(text.contains("lineage."), support.id() + "：" + text);
            }
        }
    }

    @Test
    void lineageDoesNotTakeTheExclusiveSlot() {
        final SupportDefinition heart = support("permafrost_heart");
        final SupportDefinition flow = support("rakiatas_flow");
        final SupportDefinition pyre = support("xophs_pyre");
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("fireball", 5).withSockets(4).withSupport(0, heart.id());
        assertNull(SupportDefinition.exclusiveConflict(entry, -1, flow, config::support), "專屬輔助旁邊可以掛血脈輔助");
        // 2026-10-28 照 PoE2：不同的血脈輔助可以掛在同一個技能上（這次之前一個技能只能一顆）
        assertNull(SupportDefinition.exclusiveConflict(entry.withSupport(1, flow.id()), -1, pyre, true, config::support));
        assertNull(SupportDefinition.exclusiveConflict(entry.withSupport(1, flow.id()), 1, pyre, config::support), "換掉同一孔可以");
        final SkillBuild build = SkillBuild.of(config, config.skill("fireball"), 5, 60, false, List.of(heart, flow, pyre), Set.of(), new StatSheet());
        assertEquals(3, build.supports().size(), "2026-10-28：不同的血脈輔助都生效");
    }

    @Test
    void lineageRefusals() {
        assertNull(support("rakiatas_flow").refusal(config.skill("fireball")));
        assertNull(support("ahns_citadel").refusal(config.skill("flame_wall")));
        assertEquals("要立牆的技能（焰幕、霜壁）", support("ahns_citadel").refusal(config.skill("fireball")));
        assertNull(support("kaoms_madness").refusal(config.skill("volcanic_fissure")));
        assertNotNull(support("kaoms_madness").refusal(config.skill("fireball")));
        assertNull(support("xibaquas_rending").refusal(config.skill("leap_slam")));
        assertNotNull(support("xibaquas_rending").refusal(config.skill("boneshatter")));
        assertNull(support("atziris_communion").refusal(config.skill("frost_aegis")));
        assertNotNull(support("atziris_communion").refusal(config.skill("raise_zombie")), "叫召喚物的不行");
        assertNull(support("breachlords_amalgam").refusal(config.skill("lightning_arrow")));
        assertNotNull(support("breachlords_amalgam").refusal(config.skill("fireball")), "要冰冷或閃電");
        assertNull(support("rathas_assault").refusal(config.skill("explosive_bolt")));
        assertNotNull(support("rathas_assault").refusal(config.skill("lightning_arrow")));
        final SkillDefinition permanent = config.visible().stream().filter(s -> s.summon() != null && s.summon().permanent()).findFirst().orElseThrow();
        assertNull(support("tecrods_revenge").refusal(permanent), "常駐的召喚物");
        assertEquals("要常駐的召喚物技能", support("tecrods_revenge").refusal(config.skill("raise_zombie")), "我們的殭屍有持續時間");
        assertNull(support("styrns_ferocity").refusal(config.skill("shield_rush")));
        assertNotNull(support("styrns_ferocity").refusal(config.skill("boneshatter")));
        assertNull(support("styrns_mountain").refusal(config.skill("raise_guard")));
        assertNull(support("zarokhs_refrain").refusal(config.skill("frost_nova")), "以自己為中心的範圍法術");
        assertNotNull(support("zarokhs_refrain").refusal(config.skill("fireball")), "投射物不是 cascadable");
        assertNull(support("tricksters_shard").refusal(config.skill("fireball")));
        assertNull(support("ataluis_bloodletting").refusal(config.skill("fireball")));
        assertEquals("保留精魂的技能不行", support("ataluis_bloodletting").refusal(config.skill("frost_aegis")));
        assertNull(support("tasalios_rhythm").refusal(config.skill("true_strike")));
        assertNull(support("arbiters_ignition").refusal(config.skill("fireball")));
        assertNotNull(support("arbiters_ignition").refusal(config.skill("spark")), "要火焰法術");
        for (final SupportDefinition support : lineage()) {
            assertFalse(support.supports(config.skill(SkillConfig.DEFAULT_MELEE)), "普攻不能掛輔助：" + support.id());
            // 每顆至少有一個玩家學得到的技能掛得上去
            assertTrue(config.visible().stream().anyMatch(support::supports), support.id() + " 沒有技能掛得上去");
        }
    }

    @Test
    void uhtredLevelsDependOnOtherSupports() {
        final SkillDefinition fireball = config.skill("fireball");
        final StatSheet sheet = new StatSheet();
        final SkillBuild plain = SkillBuild.of(config, fireball, 10, 90, false, List.of(), Set.of(), sheet);
        final SkillBuild alone = SkillBuild.of(config, fireball, 10, 90, false, List.of(support("uhtreds_exodus")), Set.of(), sheet);
        assertEquals(plain.level() + 3, alone.level(), "孤星：沒有其他輔助 +3");
        final SupportDefinition other = support("faster_casting");
        final SkillBuild withOne = SkillBuild.of(config, fireball, 10, 90, false, List.of(support("uhtreds_exodus"), other), Set.of(), sheet);
        assertEquals(plain.level(), withOne.level(), "孤星：有其他輔助就沒有");
        final SkillBuild omen = SkillBuild.of(config, fireball, 10, 90, false, List.of(support("uhtreds_omen"), other), Set.of(), sheet);
        assertEquals(plain.level() + 2, omen.level(), "雙兆：剛好一顆其他輔助 +2");
        final SkillBuild augury = SkillBuild.of(config, fireball, 10, 90, false, List.of(support("uhtreds_augury"), other, support("pierce")),
                Set.of(), sheet);
        assertEquals(plain.level() + 2, augury.level(), "三兆：剛好兩顆其他輔助 +2");
        final SkillBuild dialla = SkillBuild.of(config, fireball, 10, 90, false, List.of(support("diallas_desire")), Set.of(), sheet);
        assertEquals(plain.level() + 1, dialla.level(), "願晶 +1");
        assertTrue(dialla.manaCost() < SkillBuild.of(config, fireball, 11, 90, false, List.of(), Set.of(), sheet).manaCost(), "願晶：消耗 90%");
        assertFalse(LineageRules.buildModifiers(support("diallas_desire"), fireball).isEmpty(), "願晶的品質");
    }

    @Test
    void chainsAndProjectileSpeed() {
        final SkillDefinition arc = config.skill("arc");
        final StatSheet sheet = new StatSheet();
        final SkillBuild plain = SkillBuild.of(config, arc, 10, 90, false, List.of(), Set.of(), sheet);
        final SkillBuild doubled = SkillBuild.of(config, arc, 10, 90, false, List.of(support("dominus_grasp")), Set.of(), sheet);
        assertEquals(plain.chains() * 2, doubled.chains(), "倍鏈：連鎖更多 100%");
        final StatSheet fast = new StatSheet().overlay(List.of(Modifier.increased(StatKeys.CAST_SPEED, 0.4, "測試"),
                Modifier.flat(LineageKeys.CAST_TO_PROJECTILE_SPEED, 75, "推詠")));
        assertEquals(0.3, LineageRules.castSpeedAsProjectileSpeed(fast, Set.of()), 1e-9, "40% 施法速度的 75%");
        assertEquals(0, LineageRules.castSpeedAsProjectileSpeed(new StatSheet(), Set.of()), 1e-9);
    }

    @Test
    void smallFormulas() {
        assertEquals(10, LineageMechanics.bloodlettingPercent(100, 2), 1e-9, "100 生命：每 20 點 2%");
        assertEquals(40, LineageMechanics.bloodlettingPercent(10000, 2), 1e-9, "最多 40%");
        assertEquals(5, LineageMechanics.voltaicPercent(5, 1), 1e-9);
        assertEquals(19, LineageMechanics.voltaicPercent(5, 20), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(2.5), LineageMechanics.voltaicRadius(20), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(531, LineageMechanics.natureGuard(20), 1e-9, "自然交換 20 級 531 守護（poe2db）");
        assertEquals(4, LineageMechanics.hubrisAdded(200).size());
        assertEquals(30, LineageMechanics.hubrisAdded(200).get(0).value(), 1e-9, "傲寒：消耗的 15%");
        assertEquals(2, LineageMechanics.recentKinds(Map.of("a", 100L, "b", 150L, "c", 0L), 200, 160));
    }

    @Test
    void dropRatesFollowTheOwnersRule() throws IOException {
        final LineageRules.Drops drops = config.lineageDrops();
        final MapConfig maps = new MapConfig();
        maps.load(DATA);
        final MonsterConfig monsters = new MonsterConfig();
        monsters.load(DATA);
        // 怪物組成（monsters.yml 的 weight）平均起來每隻的機率
        double weight = 0;
        double common = 0;
        double rare = 0;
        double pinnacle = 0;
        for (final MonsterRarity rarity : List.of(MonsterRarity.NORMAL, MonsterRarity.MAGIC, MonsterRarity.RARE)) {
            final MonsterConfig.RaritySettings settings = monsters.rarity(rarity);
            final double[] chances = LineageDrops.chances(drops, 80, settings.quantityBonus(), false, false);
            weight += settings.weight();
            common += settings.weight() * chances[0];
            rare += settings.weight() * chances[1];
            pinnacle += settings.weight() * chances[2];
        }
        common /= weight;
        rare /= weight;
        pinnacle /= weight;
        assertEquals(1 / 4_000_000.0, pinnacle, 1 / 4_000_000.0 * 0.02, "巔峰血脈：約 400 萬隻一顆");
        assertEquals(2 * maps.chaseMobChance(), common, maps.chaseMobChance() * 0.02, "一般血脈：平均每隻 = 追逐級傳奇的 2 倍（約 50 萬隻一顆）");
        assertEquals(1 / 500_000.0, common, 1 / 500_000.0 * 0.02);
        assertEquals(1 / 2_000_000.0, rare, 1 / 2_000_000.0 * 0.02, "稀有血脈：約 200 萬隻一顆");
        final double[] boss = LineageDrops.chances(drops, 80, 0, true, false);
        assertEquals(2 * maps.drops().chaseChance(), boss[0], 1e-9, "地圖 Boss：追逐級的 2 倍");
        assertEquals(maps.drops().chaseChance() / 2, boss[1], 1e-9);
        assertEquals(drops.bossPinnacleChance(), boss[2], 1e-12);
        assertEquals(0, drops.pinnacleChance(), "終局以上的 Boss 另外的 0.5% 拿掉了（2026-10-27 站長）");
        assertEquals(drops.bossPinnacleChance(), LineageDrops.chances(drops, 80, 0, true, true)[2], 1e-12, "終局以上的 Boss 跟地圖 Boss 一樣");
        assertEquals(0, LineageDrops.chances(drops, drops.minLevel() - 1, 19.8, false, false)[0], "怪物等級不夠不掉");
    }

    @Test
    void coloursFollowRepoe() {
        assertEquals(SupportColours.RED, SupportColours.colour("uhtreds_exodus"));
        assertEquals(SupportColours.GREEN, SupportColours.colour("rakiatas_flow"));
        assertEquals(SupportColours.BLUE, SupportColours.colour("zarokhs_revolt"));
        assertEquals(0, SupportColours.colour("seraphs_heart"), "原作是白色");
    }
}
