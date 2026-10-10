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
        assertEquals(0.5, config.monsterThreshold(MonsterRarity.UNIQUE), 1e-9, "Boss 的門檻打五折");
        assertEquals(4.0, config.igniteDuration(), 1e-9);
        assertEquals(8.0, config.chillDuration(false), 1e-9, "PoE2：怪物身上的冰緩 8 秒");
        assertEquals(2.0, config.chillDuration(true), 1e-9, "玩家 2 秒");
        assertEquals(1.5, config.freezeDuration(true), 1e-9);
        assertEquals(8.0, config.shockDuration(false), 1e-9);
        assertEquals(4.0, config.shockDuration(true), 1e-9);
        assertEquals(2.8, AilmentConfig.duration(4, 0.4, -0.5), 1e-9, "4 × 1.4 × 0.5");
        assertEquals(0.4, AilmentConfig.duration(4, -1, 0), 1e-9, "最少基礎的 10%");
    }

    @Test
    void igniteAndShockChance() {
        assertEquals(0.0625, config.igniteChance(10, 40, 0, 0), 1e-9, "門檻的 25% → 6.25%（每 4% 門檻 1%）");
        assertEquals(0.2625, config.igniteChance(10, 40, 20, 0), 1e-9, "熾星彈 +20%");
        assertEquals(0.525, config.igniteChance(10, 40, 20, 1.0), 1e-9, "增加 100% 機率");
        assertEquals(0, config.igniteChance(0, 40, 50, 0), 1e-9, "沒有火焰傷害不會點燃");
        assertEquals(1.0, config.igniteChance(1000, 40, 0, 0), 1e-9, "最多 100%");
        assertEquals(0.0625, config.shockChance(10, 40, 0, 0), 1e-9);
        assertEquals(2.0, config.igniteDps(10, 0, 1), 1e-9, "每秒 20%");
        assertEquals(3.64, config.igniteDps(10, 0.3, 1.4), 1e-9);
    }

    @Test
    void chillFreezeAndShockMagnitude() {
        assertEquals(0.5 * Math.pow(0.25, 0.4), config.chillMagnitude(10, 40, 0), 1e-9, "50% × 比例^0.4");
        assertEquals(0.5, config.chillMagnitude(100, 40, 0), 1e-9, "最多 50%");
        assertEquals(0, config.chillMagnitude(0.001, 40, 0), 1e-9, "太小（< 5%）不算");
        assertEquals(0.5, config.freezeBuildup(10, 40, 1, false), 1e-9, "門檻的 25% × 200%");
        assertEquals(1.0, config.freezeBuildup(10, 40, 2, false), 1e-9, "霜星爆增加 100% → 一次滿");
        assertEquals(0.25, config.freezeBuildup(10, 40, 1, true), 1e-9, "傳奇怪只有 50%");
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
        final SkillBuild fireball = SkillBuild.of(skills, skills.skill("fireball"), 1, 1, false, List.of(), Set.of(), sheet);
        final StatSheet effective = fireball.profile().effective(sheet);
        final Set<String> fire = fireball.profile().contextFor(DamageType.FIRE);
        assertEquals(20, effective.flat(StatKeys.IGNITE_CHANCE, fire), 1e-9, "熾星彈自己帶點燃機率 +20%");

        final SkillBuild searing = SkillBuild.of(skills, skills.skill("fireball"), 1, 1, false, List.of(skills.support("searing")), Set.of(), sheet);
        final StatSheet boosted = searing.profile().effective(sheet);
        assertEquals(45, boosted.flat(StatKeys.IGNITE_CHANCE, fire), 1e-9, "燎原再 +25%");
        assertEquals(1.4, boosted.more(StatKeys.IGNITE_MAGNITUDE, fire), 1e-9);

        final SkillBuild focused = SkillBuild.of(skills, skills.skill("fireball"), 1, 1, false,
                List.of(skills.support("elemental_focus")), Set.of(), sheet);
        assertTrue(StatKeys.flag(focused.profile().effective(sheet), StatKeys.FLAG_NO_AILMENTS), "純化：不能造成元素異常");
        assertEquals(List.of("<gray>不能造成元素異常"), AilmentText.lines(focused.profile().effective(sheet), focused.profile(),
                DamageCalculator.expectedOutgoing(sheet, focused.profile())));

        final SkillBuild nova = SkillBuild.of(skills, skills.skill("frost_nova"), 1, 1, false, List.of(skills.support("glacial")), Set.of(), sheet);
        assertEquals(2.0 * 1.6, nova.profile().effective(sheet).apply(StatKeys.FREEZE_BUILDUP, 1.0,
                nova.profile().contextFor(DamageType.COLD)), 1e-9, "霜星爆增加 100% × 冰封 60% 更多");

        final List<String> lines = AilmentText.lines(effective, fireball.profile(), DamageCalculator.expectedOutgoing(sheet, fireball.profile()));
        assertEquals(List.of("<#ff7a1a>點燃<gray> 機率 +20%"), lines);
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
        assertEquals(8, config.poisonMaxStacks());
        // 預設不靠傷害比例：沒有機率來源就不會流血、中毒
        assertEquals(0, config.bleedChance(1000, 100, 0, 0), 1e-9);
        assertEquals(0, config.poisonChance(1000, 100, 0, 0), 1e-9);
        assertEquals(0.3, config.bleedChance(50, 100, 30, 0), 1e-9, "流血機率 +30%");
        assertEquals(0.45, config.poisonChance(50, 100, 30, 0.5), 1e-9, "中毒機率 +30%，增加 50%");
        // 每秒傷害：那一擊的 15%／20%
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
        final SkillBuild strike = SkillBuild.of(skills, skills.skill("heavy_strike"), 1, 1, false, List.of(skills.support("lacerate")), Set.of(), sheet);
        final StatSheet effective = strike.profile().effective(sheet);
        final Set<String> physical = strike.profile().contextFor(DamageType.PHYSICAL);
        assertEquals(30, effective.flat(StatKeys.BLEED_CHANCE, physical), 1e-9, "割裂：流血機率 +30%");
        assertEquals(1.4, effective.more(StatKeys.BLEED_MAGNITUDE, physical), 1e-9);
        final List<String> lines = AilmentText.lines(effective, strike.profile(), DamageCalculator.expectedOutgoing(sheet, strike.profile()));
        assertTrue(lines.contains("<#ff4a4a>流血<gray> 機率 +30%、傷害 ×1.40"), String.join(" | ", lines));

        final SkillBuild plain = SkillBuild.of(skills, skills.skill("heavy_strike"), 1, 1, false, List.of(), Set.of(), sheet);
        final List<String> none = AilmentText.lines(plain.profile().effective(sheet), plain.profile(), DamageCalculator.expectedOutgoing(sheet, plain.profile()));
        assertFalse(none.stream().anyMatch(line -> line.contains("流血") || line.contains("中毒")), "沒有機率來源就不列");

        final SkillBuild fireball = SkillBuild.of(skills, skills.skill("fireball"), 1, 1, false, List.of(skills.support("envenom")), Set.of(), sheet);
        final StatSheet venom = fireball.profile().effective(sheet);
        assertEquals(30, venom.flat(StatKeys.POISON_CHANCE, fireball.profile().contextFor(DamageType.CHAOS)), 1e-9, "淬毒：中毒機率 +30%");
        final List<String> fire = AilmentText.lines(venom, fireball.profile(), DamageCalculator.expectedOutgoing(sheet, fireball.profile()));
        assertFalse(fire.stream().anyMatch(line -> line.contains("中毒")), "熾星彈沒有物理或混沌傷害：淬毒掛了也不會中毒，不列");

        assertEquals("流血機率 +30%", ModifierText.describe(new Modifier(StatKeys.BLEED_CHANCE, ModType.FLAT, 30, Set.of(), "x")));
        assertEquals("30% 更多中毒傷害", ModifierText.describe(new Modifier(StatKeys.POISON_MAGNITUDE, ModType.MORE, 0.3, Set.of(), "x")));
        assertEquals("25% 機率避免中毒", ModifierText.describe(new Modifier(StatKeys.AVOID_POISON, ModType.FLAT, 25, Set.of(), "x")));
    }

    // ---- 暈眩與電擊（PoE2）----

    @Test
    void stunAndElectrocuteSettingsLoad() {
        final AilmentConfig.MeterSettings stun = config.stun();
        assertEquals(120, stun.buildup(), 1e-9);
        assertEquals(0.5, config.stunElementalMultiplier(), 1e-9);
        assertEquals(1.5, stun.duration(false), 1e-9);
        assertEquals(0.8, stun.duration(true), 1e-9);
        assertEquals(30, stun.decayDelayTicks());
        assertEquals(0.40 / 20, stun.decayPerTick(), 1e-12);
        // 一擊打掉 30% 門檻 → 累積 36%；傳奇怪只有四成
        assertEquals(0.36, stun.buildupFor(30, 100, 1.0, false), 1e-9);
        assertEquals(0.36 * 0.4, stun.buildupFor(30, 100, 1.0, true), 1e-9);
        assertEquals(0.36 * 1.5, stun.buildupFor(30, 100, 1.5, false), 1e-9, "震懾：50% 更多累積");
        assertEquals(0, stun.buildupFor(0, 100, 1.0, false), 1e-9);
        final AilmentConfig.MeterSettings electrocute = config.electrocute();
        assertEquals(150, electrocute.buildup(), 1e-9);
        assertEquals(3, electrocute.duration(false), 1e-9);
        assertEquals(1.5, electrocute.duration(true), 1e-9);
        assertEquals(8, electrocute.uniqueImmunity(), 1e-9);
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
        final SkillBuild strike = SkillBuild.of(skills, skills.skill("heavy_strike"), 1, 1, false, List.of(skills.support("concussive")), Set.of(), sheet);
        final StatSheet effective = strike.profile().effective(sheet);
        assertEquals(1.5, effective.apply(StatKeys.STUN_BUILDUP, 1.0, strike.profile().contextFor(DamageType.PHYSICAL)), 1e-9, "震懾：50% 更多暈眩累積");
        final List<String> lines = AilmentText.lines(effective, strike.profile(), DamageCalculator.expectedOutgoing(sheet, strike.profile()));
        assertTrue(lines.contains("<#ffd27f>暈眩<gray> 累積 ×1.50"), String.join(" | ", lines));
        final SkillBuild plain = SkillBuild.of(skills, skills.skill("heavy_strike"), 1, 1, false, List.of(), Set.of(), sheet);
        final List<String> none = AilmentText.lines(plain.profile().effective(sheet), plain.profile(), DamageCalculator.expectedOutgoing(sheet, plain.profile()));
        assertFalse(none.stream().anyMatch(line -> line.contains("暈眩") || line.contains("電擊")), "沒有加成就不列（暈眩本來就會累積）");

        final SkillBuild arc = SkillBuild.of(skills, skills.skill("arc"), 1, 1, false, List.of(skills.support("electrocute")), Set.of(), sheet);
        final StatSheet shocking = arc.profile().effective(sheet);
        assertEquals(50, shocking.flat(StatKeys.ELECTROCUTE_CHANCE, arc.profile().contextFor(DamageType.LIGHTNING)), 1e-9, "電擊：機率 +50%");
        final List<String> arcLines = AilmentText.lines(shocking, arc.profile(), DamageCalculator.expectedOutgoing(sheet, arc.profile()));
        assertTrue(arcLines.contains("<#9fe8ff>電擊<gray> 機率 +50%、累積 ×1.30"), String.join(" | ", arcLines));
        final SkillBuild fireball = SkillBuild.of(skills, skills.skill("fireball"), 1, 1, false, List.of(skills.support("electrocute")), Set.of(), sheet);
        final List<String> fireLines = AilmentText.lines(fireball.profile().effective(sheet), fireball.profile(), DamageCalculator.expectedOutgoing(sheet, fireball.profile()));
        assertFalse(fireLines.stream().anyMatch(line -> line.contains("電擊")), "熾星彈沒有閃電傷害：電擊掛了沒用，不列");

        assertEquals("50% 更多暈眩累積", ModifierText.describe(new Modifier(StatKeys.STUN_BUILDUP, ModType.MORE, 0.5, Set.of(), "x")));
        assertEquals("電擊機率 +35%", ModifierText.describe(new Modifier(StatKeys.ELECTROCUTE_CHANCE, ModType.FLAT, 35, Set.of(), "x")));
        assertEquals("增加 40% 暈眩門檻", ModifierText.describe(new Modifier(StatKeys.STUN_THRESHOLD, ModType.INCREASED, 0.4, Set.of(), "x")));
        assertEquals("25% 機率避免電擊", ModifierText.describe(new Modifier(StatKeys.AVOID_ELECTROCUTE, ModType.FLAT, 25, Set.of(), "x")));
    }
}
