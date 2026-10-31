package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 照 PoE2 0.5（站長「技能品質的部分也全部按照原作改」）：每顆技能寶石的品質效果照原作（gem_quality.yml，對照工具 tools/audit_poe2_quality.py）。
 * 原作第二條（額外的品質效果）只有進階奇術才有；原作品質沒有效果的寶石寫 []；寶石說明要寫得出中文。
 */
class GemQualityPoe2Test {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;
    static Map<String, Object> table;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        GemQuality.load(DATA);
        try (Reader reader = Files.newBufferedReader(DATA.resolve("gem_quality.yml"), StandardCharsets.UTF_8)) {
            table = new Yaml().load(reader);
        }
    }

    private static double sum(final List<Modifier> mods, final String stat) {
        return mods.stream().filter(m -> m.stat().equals(stat)).mapToDouble(Modifier::value).sum();
    }

    /** 屬性鍵（skill.area、damage.vs_chilled 這種）不該直接出現在玩家看的說明裡。 */
    static final Pattern RAW_KEY = Pattern.compile("[a-z]+[._][a-z_]+");

    @Test
    void everyEntryIsAnActiveSkillWithReadableText() {
        final List<String> bad = new ArrayList<>();
        for (final String id : table.keySet()) {
            if (id.equals(GemQuality.ADVANCED)) {
                continue;
            }
            final SkillDefinition definition = config.skill(id);
            assertNotNull(definition, "gem_quality.yml 寫了不存在的技能：" + id);
            assertTrue(GemQuality.has(id), id);
            final String text = SkillService.qualityText(definition, 20);
            if (text == null || RAW_KEY.matcher(text).find() || text.contains("特殊效果") || text.contains("強化這個技能自己的效果")
                    || !text.startsWith("品質 +20%：")) {
                bad.add(id + " → " + text);
            }
        }
        assertTrue(bad.isEmpty(), "品質說明寫不出來：" + bad);
    }

    @Test
    void gemsWithoutQualityInPoe2GetNothing() {
        for (final String id : List.of("raise_guard", "parry", "spear_throw", "wither")) {
            assertTrue(GemQuality.has(id), id);
            assertTrue(SkillService.qualityModifiers(config.skill(id), 20).isEmpty(), id + "：原作品質沒有效果，不再套通用的增加傷害");
            assertEquals("品質 +20%：這顆寶石的品質沒有效果", SkillService.qualityText(config.skill(id), 20));
        }
    }

    @Test
    void secondPoe2LineIsAdvancedQuality() {
        // poe2db 寶石頁的第二條（RePoE quality_stats 沒有）只有進階奇術才有；這次之前算在一般品質裡
        final Object[][] moved = {
            {"emergency_reload", SkillService.EMERGENCY_MORE, 10.0},
            {"flicker_strike", tw.exilecore.charge.ChargeService.SUP_KEEP, 20.0},
            {"arctic_howl", tw.exilecore.stats.SupportKeys.WARCRY_TIRELESS, 20.0},
            {"lunar_blessing", StatKeys.COOLDOWN_RECOVERY, 0.4},
            {"cull_the_weak", tw.exilecore.charge.ChargeService.EXTRA_RANDOM, 20.0},
            {"charge_regulation", StatKeys.GRANT_PREFIX + StatKeys.DAMAGE_PER_CHARGE, 2.0},
            {"killing_palm", StatKeys.LIFE_ON_CULL_OVER_TIME, 10.0}, // 2026-10-28 照 PoE2：4 秒內回復（這次之前 LIFE_ON_CULL 一次回）
        };
        for (final Object[] row : moved) {
            final String id = (String) row[0];
            final String stat = (String) row[1];
            assertEquals(0, sum(SkillService.qualityModifiers(config.skill(id), 20), stat), 1e-9, id + "：一般品質沒有這條");
            assertEquals((double) row[2], sum(GemQuality.advancedModifiers(id, 20, "q"), stat), 1e-9, id + "：高階奇術才有");
        }
        assertEquals(10, sum(SkillService.qualityModifiers(config.skill("emergency_reload"), 20), StatKeys.COOLDOWN_RECOVERY) * 100, 1e-9);
        assertEquals(20, sum(SkillService.qualityModifiers(config.skill("cull_the_weak"), 20), tw.exilecore.charge.ChargeService.EXTRA), 1e-9);
        assertEquals(2.4, sum(SkillService.qualityModifiers(config.skill("charge_regulation"), 20),
                StatKeys.GRANT_PREFIX + tw.exilecore.charge.ChargeService.REGULATION_CONSUME), 1e-9);
        final String reload = SkillService.qualityText(config.skill("emergency_reload"), 20);
        assertTrue(reload.contains("冷卻回復速度") && reload.contains("10%") && !reload.contains("更多"), reload);
        assertEquals("品質 +20%：獲得戰星時 20% 機率多一顆同種的", SkillService.qualityText(config.skill("cull_the_weak"), 20));
        assertEquals("品質 +20%：消耗戰星的間隔多 2.4 秒", SkillService.qualityText(config.skill("charge_regulation"), 20));
        // 戰吼的說明只寫一般品質（這次之前連進階奇術的那條也寫，顯示 0）
        for (final String id : List.of("infernal_cry", "seismic_cry", "fortifying_cry", "ancestral_cry", "arctic_howl", "ferocious_roar", "lunar_blessing")) {
            final String text = SkillService.qualityText(config.skill(id), 20);
            assertFalse(text.matches(".*[^0-9.]0(%| 格).*"), id + "：" + text);
        }
    }

    @Test
    void newQualityEffectsFollowPoe2() {
        // 火球：(0—10)% 機率多射 2 發；冰霜新星：對冰緩的敵人更多 20%；地動：鋸齒地面上限 +2；崩城落彈：對被禁錮的敵人更多 40%
        assertEquals(10, sum(SkillService.qualityModifiers(config.skill("fireball"), 20), "skill.projectile_chance_two"), 1e-9);
        assertEquals(0.2, sum(SkillService.qualityModifiers(config.skill("frost_nova"), 20), StatKeys.DAMAGE_VS_AILMENT + "chilled"), 1e-9);
        assertEquals(2, sum(SkillService.qualityModifiers(config.skill("earthquake"), 20), "limit.jagged"), 1e-9);
        final List<Modifier> siege = SkillService.qualityModifiers(config.skill("siege_salvo"), 20);
        assertEquals(1, siege.size());
        assertEquals(StatKeys.DAMAGE, siege.getFirst().stat());
        assertEquals(Set.of("vs_immobilised"), siege.getFirst().tags());
        // 詛咒照原作寫明（數值跟通用規則一樣）；戰旗、引星：保留效率
        assertEquals(0.1, sum(SkillService.qualityModifiers(config.skill("despair_curse"), 20), StatKeys.CURSE_EFFECT), 1e-9);
        assertEquals(-0.1, sum(SkillService.qualityModifiers(config.skill("war_banner"), 20), StatKeys.AURA_RESERVATION), 1e-9);
        // 引星：獲得的能量（品質加到 MetaGems.energyModifiers）
        final double[] energy = new double[3];
        MetaGems.addEnergy(energy, GemQuality.modifiers("meta_minion_death", 20, "q"));
        assertEquals(20, energy[0], 1e-9);
    }

    @Test
    void fractionalCountsRoundDown() {
        // 灰狼隨：+(0—1) 隻，20% 品質才 +1（這次之前四捨五入，10% 就 +1）
        final SkillDefinition wolves = config.skill("wolf_pack");
        final int base = build(wolves, 0).minions();
        assertEquals(base, build(wolves, 19).minions());
        assertEquals(base + 1, build(wolves, 20).minions());
        // 凍雨彈：可以存放 +(0—5) 支弩矢（小數捨去）
        final SkillDefinition hail = config.skill("hail_rounds");
        final int clip = SkillService.clipSize(config, build(hail, 0), new StatSheet());
        assertEquals(clip + 2, SkillService.clipSize(config, build(hail, 10), new StatSheet()));
        assertEquals(clip + 5, SkillService.clipSize(config, build(hail, 20), new StatSheet()));
    }

    /** 2026-10-28：這次照原作接上的 111 顆（這次之前列在 audit 的 UNREP，照通用規則每 1% 增加 1% 傷害）。 */
    static final List<String> WIRED = List.of("alchemists_boon", "attrition", "barkskin", "bear_slam", "beast_companion", "berserk", "blast_arrow",
            "bloodhounds_mark", "bone_cage", "bone_offering", "bone_spikes", "bramble_arrow", "briarpatch", "combat_frenzy", "comet", "contagion",
            "convalescence", "dark_pact", "decompose", "devour", "discipline", "disengage", "elemental_conflux", "elemental_trinity", "ember_volley",
            "entangle", "eternal_rage", "exsanguinate", "fangs_of_frost", "feast_of_flesh", "flame_breath", "freezing_mark", "frost_aegis",
            "frost_mine", "frostbolt", "fulmination", "gale_sweep", "grim_feast", "herald_of_ash", "herald_of_blood", "herald_of_ice",
            "herald_of_plague", "herald_of_thunder", "hex_burst", "hunters_mark", "impurity", "incendiary_shot", "iron_ward", "lingering_illusion",
            "linked_volley", "magma_barrier", "malice", "mana_drain", "mana_remnants", "mana_storm", "meta_elemental_invocation", "mirage_archer",
            "mirror_of_refraction", "piercing_rounds", "plague_bearer", "pounce", "profane_ritual", "purity_of_fire", "purity_of_ice",
            "purity_of_lightning", "rain_of_arrows", "raise_zombie", "rake", "rampage", "rapid_fire", "rapid_thrust", "ravenous_swarm",
            "rolling_magma", "rolling_slam", "ruin_mantra", "sacrifice", "savage_fury", "scavenged_plating", "serpent_fury", "shatter_spikes",
            "shield_rush", "shield_wall", "shock_arrow", "sigil_of_power", "siphon_elements", "skeletal_cleric", "skeletal_pyromancer",
            "skeletal_reaver", "soul_offering", "spear_thicket", "spiraling_conspiracy", "stampede", "storm_javelin", "summon_mount", "sunder",
            "sunfire_spear", "supercharged_slam", "tempest_bell", "thunder_leap", "thunderstorm", "time_of_need", "trail_of_caltrops",
            "vault_strike", "volcanic_fissure", "voltaic_mark", "walking_calamity", "whirlwind_lance", "wind_dancer", "wing_blast",
            "withering_presence", "wyvern_rend");

    @Test
    void formerlyUnrepresentedGemsFollowPoe2() {
        assertEquals(111, WIRED.size());
        for (final String id : WIRED) {
            assertTrue(GemQuality.has(id), id + "：要寫在 gem_quality.yml");
            final String text = SkillService.qualityText(config.skill(id), 20);
            assertNotNull(GemQualityText.body(id, GemQuality.modifiers(id, 20, "q"), 20), id + "：要有說明樣板");
            assertFalse(text.contains("增加 20% 傷害"), id + "：不再是通用規則：" + text);
        }
        // 說明照原作的數值（20% 品質）
        assertEquals("品質 +20%：光環另外給 +8% 火焰抗性", SkillService.qualityText(config.skill("purity_of_fire"), 20));
        assertEquals("品質 +20%：落雷次數 +2", SkillService.qualityText(config.skill("herald_of_thunder"), 20));
        assertEquals("品質 +10%：落雷次數 +1", SkillService.qualityText(config.skill("herald_of_thunder"), 10));
        assertEquals("品質 +20%：撲殺需要的層數少 5", SkillService.qualityText(config.skill("attrition"), 20));
        assertEquals("品質 +20%：需要的連擊少 1", SkillService.qualityText(config.skill("ruin_mantra"), 20));
        assertEquals("品質 +10%：需要的連擊少 0", SkillService.qualityText(config.skill("ruin_mantra"), 10));
        assertTrue(SkillService.qualityText(config.skill("wind_dancer"), 20).contains("沒有效果"));
        assertTrue(SkillService.qualityModifiers(config.skill("wind_dancer"), 20).isEmpty());
    }

    @Test
    void qualityIsAddedToTheBuffItself() {
        // 焰守：光環的火焰抗性 + 8（同一條，乘光環效果）；元素潮汐：更多 40% → 50%（不是另外乘 10%）
        final SkillDefinition purity = config.skill("purity_of_fire");
        final double base = sum(build(purity, 0).auraModifiers(), StatKeys.RESIST_FIRE);
        assertEquals(base + 8, sum(build(purity, 20).auraModifiers(), StatKeys.RESIST_FIRE), 1e-6);
        assertEquals(1, build(purity, 20).auraModifiers().stream().filter(m -> m.stat().equals(StatKeys.RESIST_FIRE)).count());
        final SkillDefinition conflux = config.skill("elemental_conflux");
        final List<Modifier> plain = build(conflux, 0).auraModifiers();
        final List<Modifier> quality = build(conflux, 20).auraModifiers();
        assertEquals(plain.size(), quality.size());
        assertEquals(plain.getFirst().value() + 0.10, quality.getFirst().value(), 1e-6);
        // 律盾：沒有對應的那條多一條（能量護盾回充速度）
        assertEquals(0.20, build(config.skill("discipline"), 20).auraModifiers().stream()
                .filter(m -> m.stat().equals(StatKeys.ES_RECHARGE)).mapToDouble(Modifier::value).sum(), 1e-6);
        // 合併的純函式：同屬性同種類同標籤加在一起，乘效果倍率
        final List<Modifier> merged = SkillBuild.mergeEffects(List.of(Modifier.flat("x", 10, "a")),
                List.of(Modifier.flat("x", 2, "q"), Modifier.flat("y", 1, "q")), 1.5, "s");
        assertEquals(13, merged.getFirst().value(), 1e-9);
        assertEquals(1.5, merged.get(1).value(), 1e-9);
        // 品質只算一般品質與進階奇術加倍的，不算技能自己的逐級數值
        assertEquals(-5, build(config.skill("attrition"), 20).qualityFlat(OriginalSkills.ATTRITION_CULL), 1e-9);
    }

    @Test
    void wiredMechanicsUseTheQuality() {
        // 獵手印記：每個敵人 3% → 4%、上限 45% → 60%
        assertEquals(60, OriginalSkills.markTaken(20, 1, 15), 1e-9);
        assertEquals(12, OriginalSkills.markTaken(3, 1, 15), 1e-9);
        assertEquals(1, OriginalSkills.qualityInt(1.0));
        assertEquals(0, OriginalSkills.qualityInt(-0.5));
        assertEquals(7, OriginalSkills.mantraCombo(build(config.skill("ruin_mantra"), 20)));
        // 嵐棍橫掃：1.5 公尺內全部、4 公尺沒有、中間遞減
        assertEquals(0.20, SkillExecutor.closeMore(20, 1.0), 1e-9);
        assertEquals(0.10, SkillExecutor.closeMore(20, tw.exilecore.core.Units.metres(2.75)), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(0, SkillExecutor.closeMore(20, tw.exilecore.core.Units.metres(4.0)), 1e-9);
        // 引導：爆焰矢最大階段 +1、熾息前 1 秒不耗怒氣、鐵壁衝撞距離加成上限 +40
        final ChargeSpec arrow = config.charge("blast_arrow");
        assertEquals(arrow.stages() + 1, ChargeSpec.withQuality(arrow, build(config.skill("blast_arrow"), 20)).stages());
        assertEquals(arrow.stages(), ChargeSpec.withQuality(arrow, build(config.skill("blast_arrow"), 19)).stages());
        final ChargeSpec breath = config.charge("flame_breath");
        assertEquals(breath.channel().rageAfter() + 1, ChargeSpec.withQuality(breath, build(config.skill("flame_breath"), 20)).channel().rageAfter(), 1e-9);
        final ChargeSpec rush = config.charge("shield_rush");
        assertEquals(rush.channel().distanceMore() + 40, ChargeSpec.withQuality(rush, build(config.skill("shield_rush"), 20)).channel().distanceMore(), 1e-9);
        // 裂血掠：照衝刺距離最多更多 100%（品質再 +30）
        final SkillBuild rake = build(config.skill("rake"), 20);
        assertEquals(1.30, SkillExecutor.dashDistanceMore(rake, rake.range()), 1e-9);
        assertEquals(0.65, SkillExecutor.dashDistanceMore(rake, rake.range() / 2), 1e-9);
        // 連珠：更少 50% → 45%
        assertEquals(45, config.barrage("linked_volley").withLess(build(config.skill("linked_volley"), 20)
                .qualityFlat(tw.exilecore.stats.GemQualityKeys.BARRAGE_LESS)).less(), 1e-9);
        // 喚屍：強化效果增加 25%
        assertEquals(sum(MinionService.empowered(20), StatKeys.DAMAGE) * 1.25, sum(MinionService.empowered(20, 25), StatKeys.DAMAGE), 1e-9);
        // 寒晶雷：曝露上限 50 → 60
        assertEquals(60, StageLeftovers.bombExposure(40, 10), 1e-9);
        // 元祈引星：最大能量 +100（固定值，再乘擴能）
        final MetaSpec meta = config.meta("meta_elemental_invocation");
        assertEquals((meta.maxEnergy() + 100) * 1.8, MetaGems.invocationMax(meta, new double[] {0, 80, 0}, new double[] {1, 1, 0}, 100), 1e-9);
        // 血脈牽：+1 道（20% 品質；19% 還沒有）
        final SkillDefinition blood = config.skill("exsanguinate");
        assertEquals(build(blood, 0).chains() + 1, build(blood, 20).chains());
        assertEquals(build(blood, 0).chains(), build(blood, 19).chains());
        // 霜使：碎裂半徑 +0.4 公尺（加在基礎上）
        final SkillBuild shatter = build(config.skill("herald_ice_shatter"), 0);
        assertEquals(shatter.radius() + 0.4, shatter.withBaseRadius(0.4).radius(), 1e-9);
        // 積雷雲：濕透的感電更多 50% → 60%、凍結累積 25% → 30%
        assertEquals(0.60, sum(SupportExtras.drenchedMods(5, 10), tw.exilecore.stats.SupportExtraKeys.SHOCK_CHANCE_TAKEN), 1e-9);
        assertEquals(0.30, sum(SupportExtras.drenchedMods(5, 10), tw.exilecore.stats.SupportExtraKeys.FREEZE_BUILDUP_TAKEN), 1e-9);
        // 蝕咒裂：詛咒剩 3.5 秒 → 更多 30%（每整秒 10%）
        final SkillBuild hex = build(config.skill("hex_burst"), 20);
        assertEquals(0.3, SkillExecutor.hexblastProfile(hex, 70).local().stream()
                .filter(m -> "詛咒剩餘時間".equals(m.source())).mapToDouble(Modifier::value).sum(), 1e-9);
        assertEquals(hex.profile(), SkillExecutor.hexblastProfile(hex, 19));
    }

    private static SkillBuild build(final SkillDefinition definition, final int quality) {
        return SkillBuild.of(config, definition, 20, 90, false, List.of(), Set.of(Tags.BOW, "crossbow"), new StatSheet(), Set.of(),
                SkillService.qualityModifiers(definition, quality));
    }
}
