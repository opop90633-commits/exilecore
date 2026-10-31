package tw.exilecore.ailment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.skill.ModifierText;
import tw.exilecore.skill.SkillBuild;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 批次 3：元素異常的公式、狀態、技能與輔助寶石上的加成。 */
class AilmentTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static AilmentConfig config;
    static SkillConfig skills;

    @BeforeAll
    static void load() throws IOException {
        config = new AilmentConfig();
        config.load(DATA);
        skills = new SkillConfig();
        skills.load(DATA);
    }

    /** 2026-10-26 照 PoE2：Boss 的暈眩門檻（地圖 ×3.13、巔峰 ×9.38）、輕暈 0.5 秒。 */
    @Test
    void bossPoiseAndLightStunFollowRef() {
        assertEquals(3.13, config.bossPoise(false), 1e-9);
        assertEquals(9.38, config.bossPoise(true), 1e-9);
        assertEquals(0.5, config.lightStunDuration(false), 1e-9);
        assertEquals(0.5, config.lightStunDuration(true), 1e-9);
    }

    @Test
    void avoidanceIsCappedAndThresholdsAreSeparate() {
        assertEquals(0.62, AilmentConfig.avoidChance(62), 1e-9);
        assertEquals(0.75, AilmentConfig.avoidChance(330), 1e-9, "兩條最高階＋天賦也只到 75%");
        assertEquals(0.75, AilmentConfig.avoidChance(StatKeys.AVOID_CAP), 1e-9);
        assertEquals(0, AilmentConfig.avoidChance(-10), 1e-9);
        // 元素異常門檻與暈眩門檻各乘各的，最少基礎的 10%
        assertEquals(1400, AilmentConfig.threshold(1000, 0.4), 1e-9);
        assertEquals(1000, AilmentConfig.threshold(1000, 0), 1e-9, "暈眩不吃元素異常門檻：只用自己的增加");
        assertEquals(100, AilmentConfig.threshold(1000, -5), 1e-9);
    }

    @Test
    void thresholdsAndDurations() {
        assertEquals(1.0, config.monsterThreshold(MonsterRarity.RARE), 1e-9);
        // 2026-10-26 照 PoE2：怪物門檻改用等級表、不看稀有度（這次之前傳奇 0.5）；玩家 = 生命的 50%
        assertEquals(1.0, config.monsterThreshold(MonsterRarity.UNIQUE), 1e-9, "門檻不看稀有度");
        assertEquals(0.5, config.playerThresholdLife(), 1e-9, "玩家 = 生命的 50%");
        assertEquals(4.0, config.igniteDuration(), 1e-9);
        assertEquals(8.0, config.chillDuration(false), 1e-9, "PoE2：怪物身上的冰緩 8 秒");
        assertEquals(2.0, config.chillDuration(true), 1e-9, "玩家 2 秒");
        assertEquals(2.0, config.freezeDuration(true), 1e-9, "2026-10-26 照 PoE2：玩家凍結 2 秒（這次之前 1.5）");
        assertEquals(4.0, config.freezeDuration(false), 1e-9);
        assertEquals(8.0, config.flammabilityDuration(false), 1e-9, "易燃在怪物身上 8 秒");
        assertEquals(4.0, config.flammabilityDuration(true), 1e-9);
        assertEquals(8.0, config.shockDuration(false), 1e-9);
        assertEquals(4.0, config.shockDuration(true), 1e-9);
        assertEquals(2.8, AilmentConfig.duration(4, 0.4, -0.5), 1e-9, "4 × 1.4 × 0.5");
        assertEquals(0.4, AilmentConfig.duration(4, -1, 0), 1e-9, "最少基礎的 10%");
    }

    @Test
    void igniteAndShockChance() {
        // 2026-10-26 照 PoE2：每 5% 門檻 1%（這次之前每 4% 門檻 1%）
        assertEquals(0.05, config.igniteChance(10, 40, 0, 0), 1e-9, "門檻的 25% → 5%（每 5% 門檻 1%）");
        assertEquals(0.25, config.igniteChance(10, 40, 20, 0), 1e-9, "熾星彈 +20%");
        assertEquals(0.5, config.igniteChance(10, 40, 20, 1.0), 1e-9, "增加 100% 機率");
        assertEquals(250, config.flammability(500, 40, 0, 0), 1e-9, "易燃可以超過 100（疊起來再夾）");
        assertEquals(0, config.igniteChance(0, 40, 50, 0), 1e-9, "沒有火焰傷害不會點燃");
        assertEquals(1.0, config.igniteChance(1000, 40, 0, 0), 1e-9, "最多 100%");
        assertEquals(0.0625, config.shockChance(10, 40, 0, 0), 1e-9);
        assertEquals(2.0, config.igniteDps(10, 0, 1), 1e-9, "每秒 20%");
        assertEquals(3.64, config.igniteDps(10, 0.3, 1.4), 1e-9);
    }

    @Test
    void chillFreezeAndShockMagnitude() {
        // 2026-10-26 照 PoE2：冰緩 = 100 × 比例（直線），最低 30%；凍結 = 冰冷 × 2.1 ÷ 暈眩門檻（打玩家 × 2），沒有傳奇怪打折
        assertEquals(0.35, config.chillMagnitude(14, 40, 0), 1e-9, "100 × 比例");
        assertEquals(0, config.chillMagnitude(10, 40, 0), 1e-9, "25% 小於 30% 不算");
        assertEquals(0.5, config.chillMagnitude(100, 40, 0), 1e-9, "最多 50%");
        assertEquals(0, config.chillMagnitude(0.001, 40, 0), 1e-9, "太小不算");
        assertEquals(0.525, config.freezeBuildup(10, 40, 1, false), 1e-9, "門檻的 25% × 210%");
        assertEquals(1.05, config.freezeBuildup(10, 40, 2, false), 1e-9, "霜星爆增加 100% → 一次滿");
        assertEquals(0.5, config.freezeBuildup(10, 40, 1, true), 1e-9, "打玩家 × 2.0");
        assertEquals(0.2, config.shockEffect(0), 1e-9);
        assertEquals(0.25, config.shockEffect(0.25), 1e-9);
        assertEquals(0.5, config.shockEffect(5), 1e-9, "最多 50%");
    }

    @Test
    void strongestIgniteBurnsThenTheNext() {
        final AilmentState state = new AilmentState();
        final UUID player = UUID.randomUUID();
        state.addIgnite(5, 100, player);
        state.addIgnite(9, 60, null);
        state.addIgnite(3, 200, null);
        assertEquals(9, state.ignite(10).dps(), 1e-9, "只有最強的在燒");
        assertEquals(5, state.ignite(60).dps(), 1e-9, "最強的結束後下一個接著燒");
        assertEquals(player, state.ignite(60).source());
        assertEquals(3, state.ignite(100).dps(), 1e-9);
        assertNull(state.ignite(200));
        for (int i = 0; i < 20; i++) {
            state.addIgnite(i + 1, 1000, null);
        }
        assertEquals(20, state.ignite(300).dps(), 1e-9, "太多時丟掉弱的，留下強的");
    }

    @Test
    void chillAndShockKeepTheStrongest() {
        final AilmentState state = new AilmentState();
        assertTrue(state.applyChill(0.3, 100, 0));
        assertFalse(state.applyChill(0.2, 300, 10), "比較弱的不會蓋掉");
        assertEquals(0.3, state.chill(50), 1e-9);
        assertTrue(state.applyChill(0.4, 80, 50), "比較強的蓋過去");
        assertEquals(0, state.chill(80), 1e-9, "時間到就結束");
        assertTrue(state.applyShock(0.2, 100, 0));
        assertTrue(state.applyShock(0.2, 150, 50), "一樣強：延長");
        assertEquals(150, state.shockEnd());
        assertEquals(0, state.shock(150), 1e-9);
    }

    @Test
    void freezeBuildsUpDecaysAndFreezes() {
        final AilmentState state = new AilmentState();
        final long delay = 40;
        final double perTick = 0.5 / 20.0; // 每秒 50%
        assertFalse(state.addBuildup(0.6, 0, delay, perTick));
        assertEquals(0.6, state.buildup(), 1e-9);
        state.decay(40, delay, perTick);
        assertEquals(0.6, state.buildup(), 1e-9, "2 秒內不消退");
        state.decay(60, delay, perTick);
        assertEquals(0.1, state.buildup(), 1e-9, "之後每秒少 50%");
        assertTrue(state.addBuildup(0.95, 60, delay, perTick), "累積到 100%");
        state.freeze(60, 80, 0);
        assertTrue(state.frozen(100));
        assertEquals(0, state.buildup(), 1e-9);
        assertFalse(state.addBuildup(5, 100, delay, perTick), "凍結中不累積");
        assertFalse(state.frozen(140));
        assertTrue(state.isEmpty(200));

        final AilmentState boss = new AilmentState();
        boss.freeze(100, 80, 120); // 傳奇怪：凍結結束後 6 秒不能再累積
        assertFalse(boss.addBuildup(5, 200, delay, perTick), "免疫期間不累積");
        assertFalse(boss.isEmpty(250), "免疫期間要繼續追蹤");
        assertTrue(boss.addBuildup(5, 300, delay, perTick));
    }

    @Test
    void brokenReloadKeepsOldSettings(@TempDir final Path dir) throws IOException {
        final AilmentConfig live = new AilmentConfig();
        live.load(DATA);
        Files.writeString(dir.resolve(AilmentConfig.FILE), "ignite:\n  duration: 9\nchill:\n  min: 80\n  max: 50\n");
        assertThrows(IllegalArgumentException.class, () -> live.load(dir));
        assertEquals(4.0, live.igniteDuration(), 1e-9, "寫錯時還是用原本的設定");
    }

    @Test
    void skillsAndSupportsCarryAilmentModifiers() {
        final StatSheet sheet = new StatSheet();
        final SkillBuild fireball = SkillBuild.of(skills, skills.skill("blazing_star_shell"), 1, 1, false, List.of(), Set.of(), sheet);
        final StatSheet effective = fireball.profile().effective(sheet);
        final Set<String> fire = fireball.profile().contextFor(DamageType.FIRE);
        // 2026-10-26 照 PoE2：熾星彈拿掉原作沒有的點燃機率 +20%
        assertEquals(0, effective.flat(StatKeys.IGNITE_CHANCE, fire), 1e-9, "熾星彈自己不帶點燃機率");

        final SkillBuild searing = SkillBuild.of(skills, skills.skill("blazing_star_shell"), 1, 1, false, List.of(skills.support("prairie_blaze_ii")), Set.of(), sheet); // 2026-10-26：燎原 I 併入燎原 II
        final StatSheet boosted = searing.profile().effective(sheet);
        // 2026-10-26 照 PoE2（Deadly Ignites I）：燎原不加點燃機率，點燃更多 75%、擊中傷害更少 25%；這次之前點燃機率 +25%、點燃更多 40%
        assertEquals(0, boosted.flat(StatKeys.IGNITE_CHANCE, fire), 1e-9, "燎原不加點燃機率（熾星彈本身也不再 +20%）");
        assertEquals(2.0, boosted.more(StatKeys.IGNITE_MAGNITUDE, fire), 1e-9); // 2026-10-26：燎原 II 點燃更多 100%（這次之前測燎原 I 的 75%）

        final SkillBuild focused = SkillBuild.of(skills, skills.skill("blazing_star_shell"), 1, 1, false,
                List.of(skills.support("purify_essence")), Set.of(), sheet);
        assertTrue(StatKeys.flag(focused.profile().effective(sheet), StatKeys.FLAG_NO_AILMENTS), "純化：不能造成元素異常");
        assertEquals(List.of("<gray>不能造成元素異常"), AilmentText.lines(focused.profile().effective(sheet), focused.profile(),
                DamageCalculator.expectedOutgoing(sheet, focused.profile())));

        final SkillBuild nova = SkillBuild.of(skills, skills.skill("frost_star_blast"), 1, 1, false, List.of(skills.support("ice_seal")), Set.of(), sheet);
        assertEquals(2.0 * 1.5, nova.profile().effective(sheet).apply(StatKeys.FREEZE_BUILDUP, 1.0,
                nova.profile().contextFor(DamageType.COLD)), 1e-9, "霜星爆增加 100% × 冰封 50% 更多（2026-10-26 照 PoE2 Freeze）");

        final List<String> lines = AilmentText.lines(effective, fireball.profile(), DamageCalculator.expectedOutgoing(sheet, fireball.profile()));
        assertEquals(List.of(), lines, "2026-10-26 照 PoE2：熾星彈不再自帶點燃機率");
    }

    @Test
    void modifierText() {
        assertEquals("點燃機率 +25%", ModifierText.describe(new Modifier(StatKeys.IGNITE_CHANCE, ModType.FLAT, 25, Set.of(), "x")));
        assertEquals("增加 60% 凍結累積", ModifierText.describe(new Modifier(StatKeys.FREEZE_BUILDUP, ModType.INCREASED, 0.6, Set.of(), "x")));
        assertEquals("40% 更多點燃傷害", ModifierText.describe(new Modifier(StatKeys.IGNITE_MAGNITUDE, ModType.MORE, 0.4, Set.of(), "x")));
        assertTrue(ModifierText.describe(new Modifier(StatKeys.FLAG_NO_AILMENTS, ModType.FLAT, 1, Set.of(), "x")).startsWith("不能造成元素異常"));
    }

    // ---- 流血與中毒（PoE2）----

    @Test
    void bleedAndPoisonSettingsLoad() {
        assertEquals(5, config.bleedDuration(), 1e-9);
        assertEquals(2.0, config.bleedMovingMultiplier(), 1e-9);
        assertEquals(2, config.poisonDuration(), 1e-9);
        assertEquals(1, config.poisonMaxStacks(), "2026-10-26 照 PoE2：預設只有 1 層生效（這次之前 12）");
        // 預設不靠傷害比例：沒有機率來源就不會流血、中毒
        assertEquals(0, config.bleedChance(1000, 100, 0, 0), 1e-9);
        assertEquals(0, config.poisonChance(1000, 100, 0, 0), 1e-9);
        assertEquals(0.3, config.bleedChance(50, 100, 30, 0), 1e-9, "流血機率 +30%");
        assertEquals(0.45, config.poisonChance(50, 100, 30, 0.5), 1e-9, "中毒機率 +30%，增加 50%");
        // 每秒傷害：那一擊的 15%／20%（2026-10-26 照 PoE2：中毒 20%，這次之前 30%）
        assertEquals(15, config.bleedDps(100, 0, 1.0), 1e-9);
        assertEquals(15 * 1.5 * 1.4, config.bleedDps(100, 0.5, 1.4), 1e-9);
        assertEquals(20, config.poisonDps(100, 0, 1.0), 1e-9);
    }

    @Test
    void bleedKeepsStrongestAndPoisonStacks() {
        final AilmentState state = new AilmentState();
        final UUID player = UUID.randomUUID();
        state.addBleed(4, 100, player);
        state.addBleed(7, 60, null);
        assertEquals(7, state.bleed(10).dps(), 1e-9, "只有最強的流血在流");
        assertEquals(4, state.bleed(60).dps(), 1e-9);
        assertEquals(player, state.bleed(60).source());
        assertNull(state.bleed(100));

        state.addPoison(3, 40, player, 8);
        state.addPoison(5, 60, null, 8);
        state.addPoison(2, 80, null, 8);
        assertEquals(3, state.poisons(10).size(), "中毒疊加");
        assertEquals(10, state.poisonDps(10), 1e-9, "全部一起算");
        assertEquals(70, state.poisonRemaining(10), "最晚結束的");
        assertEquals(player, state.poisonSource(10), "有玩家來源的最強那個");
        assertEquals(7, state.poisonDps(40), 1e-9, "第一個結束了");
        for (int i = 0; i < 20; i++) {
            state.addPoison(10 + i, 200, null, 4);
        }
        assertEquals(4, state.poisons(100).size(), "超過上限丟掉最弱的");
        assertEquals(26 + 27 + 28 + 29, state.poisonDps(100), 1e-9);
        assertFalse(state.isEmpty(100));
        state.clear();
        assertTrue(state.isEmpty(100));
        assertNull(state.bleed(0));
        assertEquals(0, state.poisonDps(0), 1e-9);
    }

    @Test
    void bleedAndPoisonSupportsAndText() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 10, Set.of(tw.exilecore.stats.Tags.PHYSICAL), "武器"));
        sheet.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 20, Set.of(tw.exilecore.stats.Tags.PHYSICAL), "武器"));
        final SkillBuild strike = SkillBuild.of(skills, skills.skill("ground_tremor"), 1, 1, false, List.of(skills.support("rend_cut_iii")), Set.of(), sheet); // 2026-10-26：割裂 I、II 併入割裂 III
        final StatSheet effective = strike.profile().effective(sheet);
        final Set<String> physical = strike.profile().contextFor(DamageType.PHYSICAL);
        // 2026-10-26 照 PoE2（Bleed I）：流血機率 50%、沒有流血傷害加成；這次之前機率 +30%、流血更多 40%
        assertEquals(100, effective.flat(StatKeys.BLEED_CHANCE, physical), 1e-9, "割裂 III：流血機率 +100%（這次之前測割裂 I 的 50%）");
        assertEquals(1.0, effective.more(StatKeys.BLEED_MAGNITUDE, physical), 1e-9);
        final List<String> lines = AilmentText.lines(effective, strike.profile(), DamageCalculator.expectedOutgoing(sheet, strike.profile()));
        assertTrue(lines.contains("<#ff4a4a>流血<gray> 機率 +100%"), String.join(" | ", lines));

        final SkillBuild plain = SkillBuild.of(skills, skills.skill("ground_tremor"), 1, 1, false, List.of(), Set.of(), sheet);
        final List<String> none = AilmentText.lines(plain.profile().effective(sheet), plain.profile(), DamageCalculator.expectedOutgoing(sheet, plain.profile()));
        assertFalse(none.stream().anyMatch(line -> line.contains("流血") || line.contains("中毒")), "沒有機率來源就不列");

        final SkillBuild fireball = SkillBuild.of(skills, skills.skill("blazing_star_shell"), 1, 1, false, List.of(skills.support("poison_temper_iii")), Set.of(), sheet); // 2026-10-26：淬毒 I、II 併入淬毒 III
        final StatSheet venom = fireball.profile().effective(sheet);
        assertEquals(60, venom.flat(StatKeys.POISON_CHANCE, fireball.profile().contextFor(DamageType.CHAOS)), 1e-9, "淬毒 III：中毒機率 +60%（2026-10-26 I、II 併入 III；這次之前測淬毒 I 的 +40%）");
        final List<String> fire = AilmentText.lines(venom, fireball.profile(), DamageCalculator.expectedOutgoing(sheet, fireball.profile()));
        assertFalse(fire.stream().anyMatch(line -> line.contains("中毒")), "熾星彈沒有物理或混沌傷害：淬毒掛了也不會中毒，不列");

        assertEquals("流血機率 +30%", ModifierText.describe(new Modifier(StatKeys.BLEED_CHANCE, ModType.FLAT, 30, Set.of(), "x")));
        assertEquals("30% 更多中毒傷害", ModifierText.describe(new Modifier(StatKeys.POISON_MAGNITUDE, ModType.MORE, 0.3, Set.of(), "x")));
        assertEquals("25% 機率避免中毒", ModifierText.describe(new Modifier(StatKeys.AVOID_POISON, ModType.FLAT, 25, Set.of(), "x")));
    }

    @Test
    void onlyTheStrongestPoisonsOfASourceApply() {
        // 2026-10-26 照 PoE2：同一個人只有最強的 N 層生效，弱的留著等強的結束再接著毒
        final AilmentState state = new AilmentState();
        final UUID player = UUID.randomUUID();
        state.addPoison(10, 40, player, 1);
        state.addPoison(4, 100, player, 1);
        assertEquals(1, state.poisons(0).size(), "預設 1 層");
        assertEquals(10, state.poisonDps(0), 1e-9, "最強的那層");
        assertEquals(4, state.poisonDps(50), 1e-9, "強的結束，弱的接著毒");
        assertEquals(60, state.poisonRemaining(40));
        // 2026-10-27：層數上限記在每一層自己身上：新的 2 層上限的毒不會讓舊的 1 層上限的毒變成生效（這次之前整個來源改成 2 層，期望 10）
        state.addPoison(6, 100, player, 2);
        assertEquals(6, state.poisonDps(50), 1e-9, "舊的 1 層上限的毒排第二，不生效");
        state.addPoison(5, 100, player, 2);
        assertEquals(11, state.poisonDps(50), 1e-9, "兩層都是 2 層上限：一起算");
    }

    @Test
    void higherLimitPoisonDoesNotUnlockStoredWeakerOnes() {
        // 2026-10-27：瘟疫之使傳染或召喚物毒氣帶 20 層上限，不會讓同一個來源記著的 1 層上限毒全部生效
        final AilmentState state = new AilmentState();
        final UUID player = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            state.addPoison(10 + i, 200, player, 1);
        }
        assertEquals(1, state.poisons(0).size());
        state.addPoison(100, 200, player, AilmentState.MAX_POISONS);
        assertEquals(1, state.poisons(0).size(), "只有最強那層（20 層上限的那層）生效");
        assertEquals(100, state.poisonDps(0), 1e-9);
        state.addPoison(5, 200, player, AilmentState.MAX_POISONS);
        assertEquals(105, state.poisonDps(0), 1e-9, "20 層上限的毒自己照疊，1 層上限的還是不生效");
        assertTrue(state.removeSource(player));
        assertTrue(state.isEmpty(0));
    }

    @Test
    void meterThresholdRisesAfterTriggerAndFallsBack() {
        // 2026-10-26 照 PoE2：觸發後門檻提高 500%，直線降回來（取代免疫時間）
        final AilmentState.Meter meter = new AilmentState().freezeMeter();
        assertEquals(1.0, meter.thresholdMultiplier(0), 1e-9);
        meter.trigger(0, 80, 0);
        meter.raiseThreshold(5.0, 280, 0);
        assertEquals(6.0, meter.thresholdMultiplier(0), 1e-9);
        assertEquals(3.5, meter.thresholdMultiplier(140), 1e-9, "一半的時間降一半");
        assertFalse(meter.idle(200), "門檻還沒降回來要繼續追蹤");
        assertEquals(1.0, meter.thresholdMultiplier(280), 1e-9);
        assertTrue(meter.idle(280));
    }

    @Test
    void flammabilityStacks() {
        final AilmentState state = new AilmentState();
        assertEquals(30, state.addFlammability(30, 160, 0), 1e-9);
        assertEquals(70, state.addFlammability(40, 200, 20), 1e-9, "易燃疊加");
        assertEquals(40, state.flammability(160), 1e-9, "第一層結束");
        assertFalse(state.isEmpty(170));
        assertTrue(state.isEmpty(200));
    }

    @Test
    void poisonCapAndCreditArePerSource() {
        final AilmentState state = new AilmentState();
        final UUID strong = UUID.randomUUID();
        final UUID weak = UUID.randomUUID();
        for (int i = 0; i < 8; i++) {
            state.addPoison(100, 200, strong, 8);
        }
        state.addPoison(5, 200, weak, 8);
        state.addPoison(5, 200, weak, 8);
        assertEquals(10, state.poisons(0).size(), "弱的人的毒不會被強的人擠掉");
        state.addPoison(200, 200, strong, 8);
        assertEquals(10, state.poisons(0).size(), "超過自己的上限只丟自己最弱的");
        final java.util.Map<UUID, Double> shares = state.poisonBySource(0);
        assertEquals(10, shares.get(weak), 1e-9, "弱的人的傷害算他自己的");
        assertEquals(900, shares.get(strong), 1e-9);
        assertEquals(List.of(weak, strong), List.copyOf(shares.keySet()), "最強的排最後");
        for (int i = 0; i < 100; i++) {
            state.addPoison(1, 200, UUID.randomUUID(), 8);
        }
        // 2026-10-26：保險上限算的是記著的（含還沒輪到的弱毒）；強的人記了 9 層、只有 8 層生效，所以生效的是 63
        assertEquals(AilmentState.MAX_POISONS_TOTAL - 1, state.poisons(0).size(), "整隻的保險上限");
    }

    // ---- 暈眩與電擊（PoE2）----

    @Test
    void stunAndElectrocuteSettingsLoad() {
        // 2026-10-26 照 PoE2 0.5（這次之前：累積 100%、元素只算一半、1.5／0.8 秒、1.5 秒後每秒退 40%、傳奇怪四成）
        final AilmentConfig.MeterSettings stun = config.stun();
        assertEquals(58, stun.buildup(), 1e-9, "傷害 × 0.58 ÷ 暈眩門檻");
        assertEquals(1.0, config.stunElementalMultiplier(), 1e-9, "元素傷害全算");
        assertEquals(2.0, stun.duration(false), 1e-9);
        assertEquals(160, stun.decayDelayTicks(), "8 秒沒累積才開始退");
        assertEquals(0.05 / 20, stun.decayPerTick(), 1e-12, "每秒退 5%");
        assertEquals(40, stun.decayDelayTicks(true), "玩家 2 秒");
        assertEquals(5.0, stun.after(false), 1e-9, "重暈後門檻 +500%");
        assertEquals(330, stun.afterTicks(false));
        assertEquals(1.0, stun.after(true), 1e-9, "玩家 +100%");
        assertEquals(0.30 * 0.58, stun.buildupFor(30, 100, 1.0, false), 1e-9);
        assertEquals(0.30 * 0.58 * 1.5, stun.buildupFor(30, 100, 1.5, false), 1e-9, "震懾：50% 更多累積");
        assertEquals(0, stun.buildupFor(0, 100, 1.0, false), 1e-9);
        final AilmentConfig.MeterSettings electrocute = config.electrocute();
        assertEquals(170, electrocute.buildup(), 1e-9);
        assertEquals(5, electrocute.duration(false), 1e-9);
        assertEquals(2, electrocute.duration(true), 1e-9);
        assertEquals(300, electrocute.afterTicks(false));
        final AilmentConfig.MeterSettings pin = config.pin();
        assertEquals(420, pin.buildup(), 1e-9);
        assertEquals(3, pin.duration(false), 1e-9);
        assertEquals(2, pin.duration(true), 1e-9);
        assertEquals(260, pin.afterTicks(false));
        assertEquals(280, config.freeze().afterTicks(false));
    }

    @Test
    void meterBuildsUpDecaysTriggersAndGoesImmune() {
        final AilmentState state = new AilmentState();
        final AilmentState.Meter stun = state.stun();
        assertFalse(stun.add(0.4, 0, 30, 0.02));
        assertEquals(0.4, stun.buildup(), 1e-9);
        assertFalse(stun.add(0.4, 10, 30, 0.02));
        assertTrue(stun.add(0.3, 20, 30, 0.02), "累積到 110% 滿了");
        stun.trigger(20, 16, 60);
        assertTrue(stun.active(30));
        assertTrue(state.incapacitated(30), "暈眩中不能動");
        assertFalse(state.frozen(30), "不是凍結");
        assertEquals(36, stun.activeUntil());
        assertFalse(stun.active(36));
        assertFalse(stun.add(0.5, 40, 30, 0.02), "免疫期間不累積");
        assertEquals(0, stun.buildup(), 1e-9);
        assertFalse(state.isEmpty(40), "免疫中還要追蹤");
        assertTrue(state.isEmpty(96), "免疫結束才算空");
        // 消退：最後一次累積 30 tick 後每 tick 少 2%
        assertFalse(stun.add(0.5, 100, 30, 0.02));
        stun.decay(120, 30, 0.02);
        assertEquals(0.5, stun.buildup(), 1e-9, "還沒開始消退");
        stun.decay(140, 30, 0.02);
        assertEquals(0.3, stun.buildup(), 1e-9, "130 起每 tick 少 2%：10 tick 少 20%");
        // 被避免掉：歸零
        assertTrue(stun.add(0.8, 141, 30, 0.02));
        stun.reset();
        assertEquals(0, stun.buildup(), 1e-9);
        // 電擊是另一條，各自獨立
        assertFalse(state.electrocute().add(0.5, 141, 40, 0.025));
        assertEquals(0.5, state.electrocute().buildup(), 1e-9);
        state.clear();
        assertTrue(state.isEmpty(141));
        assertEquals(0, state.electrocute().buildup(), 1e-9);
    }

    @Test
    void stunAndElectrocuteSupportsAndText() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 10, Set.of(tw.exilecore.stats.Tags.PHYSICAL), "武器"));
        sheet.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 20, Set.of(tw.exilecore.stats.Tags.PHYSICAL), "武器"));
        final SkillBuild strike = SkillBuild.of(skills, skills.skill("ground_tremor"), 1, 1, false, List.of(skills.support("awe_shock")), Set.of(), sheet);
        final StatSheet effective = strike.profile().effective(sheet);
        assertEquals(1.5, effective.apply(StatKeys.STUN_BUILDUP, 1.0, strike.profile().contextFor(DamageType.PHYSICAL)), 1e-9, "震懾：50% 更多暈眩累積");
        final List<String> lines = AilmentText.lines(effective, strike.profile(), DamageCalculator.expectedOutgoing(sheet, strike.profile()));
        assertTrue(lines.contains("<#ffd27f>暈眩<gray> 累積 ×1.50"), String.join(" | ", lines));
        final SkillBuild plain = SkillBuild.of(skills, skills.skill("ground_tremor"), 1, 1, false, List.of(), Set.of(), sheet);
        final List<String> none = AilmentText.lines(plain.profile().effective(sheet), plain.profile(), DamageCalculator.expectedOutgoing(sheet, plain.profile()));
        assertFalse(none.stream().anyMatch(line -> line.contains("暈眩") || line.contains("電擊")), "沒有加成就不列（暈眩本來就會累積）");

        final SkillBuild arc = SkillBuild.of(skills, skills.skill("chain_thunder"), 1, 1, false, List.of(skills.support("electrocute")), Set.of(), sheet);
        final StatSheet shocking = arc.profile().effective(sheet);
        // 2026-10-26 照 PoE2（Electrocute I）：閃電傷害全部算進電擊累積（機率 100%）、不能感電；這次之前機率 +50%、累積更多 30%
        assertEquals(100, shocking.flat(StatKeys.ELECTROCUTE_CHANCE, arc.profile().contextFor(DamageType.LIGHTNING)), 1e-9, "電擊：機率 +100%");
        final List<String> arcLines = AilmentText.lines(shocking, arc.profile(), DamageCalculator.expectedOutgoing(sheet, arc.profile()));
        assertTrue(arcLines.contains("<#9fe8ff>電擊<gray> 機率 +100%"), String.join(" | ", arcLines));
        final SkillBuild fireball = SkillBuild.of(skills, skills.skill("blazing_star_shell"), 1, 1, false, List.of(skills.support("electrocute")), Set.of(), sheet);
        final List<String> fireLines = AilmentText.lines(fireball.profile().effective(sheet), fireball.profile(), DamageCalculator.expectedOutgoing(sheet, fireball.profile()));
        assertFalse(fireLines.stream().anyMatch(line -> line.contains("電擊")), "熾星彈沒有閃電傷害：電擊掛了沒用，不列");

        assertEquals("50% 更多暈眩累積", ModifierText.describe(new Modifier(StatKeys.STUN_BUILDUP, ModType.MORE, 0.5, Set.of(), "x")));
        assertEquals("電擊機率 +35%", ModifierText.describe(new Modifier(StatKeys.ELECTROCUTE_CHANCE, ModType.FLAT, 35, Set.of(), "x")));
        assertEquals("增加 40% 暈眩門檻", ModifierText.describe(new Modifier(StatKeys.STUN_THRESHOLD, ModType.INCREASED, 0.4, Set.of(), "x")));
        assertEquals("25% 機率避免電擊", ModifierText.describe(new Modifier(StatKeys.AVOID_ELECTROCUTE, ModType.FLAT, 25, Set.of(), "x")));
    }

    /**
     * 2026-10-28 照 PoE2 Ignited Ground（燃燒地面太痛）：地面「視同 x 點火焰傷害」——每次疊易燃 x ÷ 門檻 × 20%，到 50% 才點燃；
     * 點燃每秒 x 的 20%、吃火焰抗性與身上的點燃效果。這次之前 x 直接當每秒傷害、每 0.25 秒直接點燃。
     */
    @Test
    void groundIgniteFollowsRefIgnitedGround() {
        // 83 級 T16 地圖的點燃地面：視同 937 點；2500 生命（門檻 1250）每次疊 15%，第 4 次（1 秒）才點燃
        final double threshold = 2500 * config.playerThresholdLife();
        assertEquals(1250, threshold, 1e-9);
        final double perApplication = config.flammability(937, threshold, 0, 0);
        assertEquals(14.99, perApplication, 0.01);
        assertTrue(perApplication * 3 < AilmentService.GROUND_FLAMMABILITY && perApplication * 4 >= AilmentService.GROUND_FLAMMABILITY);
        // 每秒：937 × 20% = 187.4；75% 火抗 → 46.85（這次之前 937 × 25% = 234）
        assertEquals(187.4, AilmentService.groundIgniteDps(config, 937, 1, 1), 1e-9);
        assertEquals(46.85, AilmentService.groundIgniteDps(config, 937, 0.25, 1), 1e-9);
        assertEquals(23.425, AilmentService.groundIgniteDps(config, 937, 0.25, 0.5), 1e-9, "身上的點燃效果減少 50%");
        assertEquals(0, AilmentService.groundIgniteDps(config, 937, 0, 1), 1e-9);
        assertEquals(4, config.igniteDuration(), 1e-9);
        assertEquals(4, config.flammabilityDuration(true), 1e-9);
    }
}
