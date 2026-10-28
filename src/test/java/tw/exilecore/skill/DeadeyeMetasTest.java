package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.charge.ChargeService;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2：星矢的預告射擊（Called Shots）、幻影銳眼（Mirage Deadeye）是主要寶石：孔裡放技能、迅返與長留掛得上去且有效。 */
class DeadeyeMetasTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        GemQuality.load(DATA);
    }

    private static SkillDefinition skill(final String id) {
        final SkillDefinition definition = config.skill(id);
        assertNotNull(definition, id);
        return definition;
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition definition = config.support(id);
        assertNotNull(definition, id);
        return definition;
    }

    private static SkillBuild build(final String skill, final List<SupportDefinition> supports) {
        return SkillBuild.of(config, skill(skill), 20, 90, false, supports, Set.of("bow", Tags.RANGED), new StatSheet());
    }

    @Test
    void bothAreHostsAndTakeWhatPoe2Allows() {
        final SkillDefinition called = skill(DeadeyeMetas.CALLED_SHOTS);
        final SkillDefinition mirage = skill(DeadeyeMetas.MIRAGE);
        assertTrue(SupportDefinition.hostsSkill(called));
        assertTrue(SupportDefinition.hostsSkill(mirage));
        // 預告射擊：印記（詛咒不行）
        assertNull(DeadeyeMetas.problem(called, skill("hunters_mark")));
        assertNull(DeadeyeMetas.problem(called, skill("voltaic_mark")));
        assertNotNull(DeadeyeMetas.problem(called, skill("vulnerability_curse")), "詛咒不是印記");
        assertNotNull(DeadeyeMetas.problem(called, skill("lightning_arrow")), "攻擊不是印記");
        // 幻影銳眼：遠程投射物攻擊
        assertNull(DeadeyeMetas.problem(mirage, skill("lightning_arrow")));
        assertNotNull(DeadeyeMetas.problem(mirage, skill("hunters_mark")), "印記不是攻擊");
        assertNotNull(DeadeyeMetas.problem(mirage, skill("fireball")), "法術不行");
        assertNotNull(DeadeyeMetas.problem(mirage, skill("ice_strike")), "近戰不行");
        // 2026-10-28 照 PoE2 0.5：引導／蓄力、有冷卻的攻擊也可以（幻影自己引導、自己算冷卻）
        assertNull(DeadeyeMetas.problem(mirage, skill("snipe")));
        assertNull(DeadeyeMetas.problem(mirage, skill("blast_arrow")));
        assertNull(DeadeyeMetas.problem(mirage, skill("plasma_blast")));
        assertNull(DeadeyeMetas.problem(mirage, skill("explosive_grenade")));
        assertNotNull(DeadeyeMetas.problem(mirage, called), "主要寶石不行");
        assertNotNull(DeadeyeMetas.problem(called, mirage), "主要寶石不行");
    }

    @Test
    void cooldownAndDurationSupportsSocket() {
        final SkillDefinition called = skill(DeadeyeMetas.CALLED_SHOTS);
        final SkillDefinition mirage = skill(DeadeyeMetas.MIRAGE);
        final SupportDefinition cooldown = support("swift_cooldown"); // 冷卻恢復 II
        final SupportDefinition duration = support("prolonged_duration"); // 延長持續時間 II
        // 使用者的配置：預告射擊（獵殺印記）＋冷卻恢復＋延長持續時間；幻影銳眼（閃電箭）＋冷卻恢復＋延長持續時間＋快速攻擊
        assertNull(cooldown.hostRefusal(called, skill("hunters_mark")));
        assertNull(duration.hostRefusal(called, skill("hunters_mark")));
        assertNull(cooldown.hostRefusal(mirage, skill("lightning_arrow")));
        assertNull(duration.hostRefusal(mirage, skill("lightning_arrow")));
        assertNull(cooldown.hostRefusal(mirage, null), "套在幻影上：孔裡還沒放也能掛");
        assertNull(support("faster_attacks").hostRefusal(mirage, skill("lightning_arrow")));
        // 沒有因此開放給不相干的技能
        assertNotNull(cooldown.refusal(skill("lightning_arrow")));
        assertNotNull(cooldown.refusal(skill("fireball")));
        assertNotNull(cooldown.hostRefusal(called, null), "預告射擊自己沒有冷卻：要看孔裡的印記");
        assertNotNull(cooldown.hostRefusal(skill("ancestral_totem"), skill("leap_slam")));
        assertTrue(DeadeyeMetas.onHost(cooldown));
        assertTrue(DeadeyeMetas.onHost(duration));
        assertFalse(DeadeyeMetas.onHost(support("faster_attacks")));
        assertFalse(skill(DeadeyeMetas.CALLED_SHOTS).tags().contains(Tags.COOLDOWN));
        assertTrue(mirage.tags().contains(Tags.COOLDOWN));
        assertTrue(mirage.tags().contains(Tags.DURATION));
    }

    @Test
    void supportsActuallyChangeTheNumbers() {
        final SupportDefinition cooldown = support("swift_cooldown");
        final SupportDefinition duration = support("prolonged_duration");
        // 幻影：冷卻 10 秒 × (1 − 23%)（PoE2 冷卻回復速度增加 30%：1 ÷ 1.3 ≈ 0.77）、持續時間更多 35%
        final SkillBuild host = build(DeadeyeMetas.MIRAGE, List.of(cooldown, duration));
        final StatSheet effective = host.profile().effective(new StatSheet());
        assertEquals(0.77, DeadeyeMetas.cooldownFactor(effective, host.profile().tags()), 1e-9);
        assertEquals(1.35, DeadeyeMetas.durationFactor(effective, host.profile().tags()), 1e-9);
        final SkillBuild bare = build(DeadeyeMetas.MIRAGE, List.of());
        assertEquals(1.0, DeadeyeMetas.cooldownFactor(bare.profile().effective(new StatSheet()), bare.profile().tags()), 1e-9);
        // 預告射擊：孔裡的印記吃長留（印記持續時間）與迅返（間隔）
        final SkillBuild mark = build("hunters_mark", List.of(cooldown, duration));
        assertEquals(1.35, mark.durationScale(), 1e-9);
        assertEquals(0.77, DeadeyeMetas.cooldownFactor(mark.profile().effective(new StatSheet()), mark.profile().tags()), 1e-9);
        // 閃電箭沒有冷卻、持續時間：迅返不會套上去（幻影自己吃）
        final SkillBuild arrow = build("lightning_arrow", List.of(cooldown));
        assertEquals(1.0, DeadeyeMetas.cooldownFactor(arrow.profile().effective(new StatSheet()), arrow.profile().tags()), 1e-9);
        assertEquals(200, DeadeyeMetas.ticks(DeadeyeMetas.MIRAGE_COOLDOWN));
    }

    @Test
    void calledShotsIntervalIsNotShortenedByCooldownRecovery() {
        // PoE2：Called Shots 沒有 Cooldown 類型，5 秒是它自己的；冷卻恢復縮短的是孔裡印記自己的冷卻（獵殺印記 6 秒 → 6 ÷ 1.3）
        assertEquals(5, DeadeyeMetas.CALLED_INTERVAL, 1e-9);
        final SkillBuild host = build(DeadeyeMetas.CALLED_SHOTS, List.of(support("swift_cooldown")));
        assertEquals(0, host.cooldownTicks());
        final SkillBuild mark = build("hunters_mark", List.of(support("swift_cooldown")));
        assertEquals(Math.round(6 * 20 * 0.77), mark.cooldownTicks());
        assertEquals(120, build("hunters_mark", List.of()).cooldownTicks());
        assertEquals(tw.exilecore.core.Units.metres(3), DeadeyeMetas.SPREAD_RADIUS, 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
    }

    @Test
    void persistentBuffAndAreaSupportsWorkOnTheHosts() {
        final SkillDefinition called = skill(DeadeyeMetas.CALLED_SHOTS);
        final SkillDefinition mirage = skill(DeadeyeMetas.MIRAGE);
        for (final String id : List.of("vitality", "precision", "herbalism", "clarity", "direstrike_ii")) {
            final SupportDefinition buff = support(id);
            assertTrue(DeadeyeMetas.appliesToHost(buff, called), id);
            assertNull(buff.hostRefusal(called, skill("hunters_mark")), id);
            assertNull(buff.hostRefusal(mirage, skill("lightning_arrow")), id);
            assertNotNull(buff.refusal(skill("lightning_arrow")), id + "：沒開放給一般攻擊");
        }
        assertFalse(DeadeyeMetas.appliesToHost(support("faster_attacks"), mirage));
        // 保留精魂、給的增益算在主要寶石身上
        final SkillBuild withVitality = build(DeadeyeMetas.MIRAGE, List.of(support("vitality")));
        assertEquals(20, withVitality.reserve(), 1e-9);
        assertEquals(0, build(DeadeyeMetas.MIRAGE, List.of()).reserve(), 1e-9);
        // 預告射擊（Area）：擴散加大放印記的範圍
        final SupportDefinition area = support("increased_area");
        assertTrue(DeadeyeMetas.appliesToHost(area, called));
        assertFalse(DeadeyeMetas.appliesToHost(area, mirage));
        assertNull(area.hostRefusal(called, skill("hunters_mark")));
        assertTrue(build(DeadeyeMetas.CALLED_SHOTS, List.of(area)).areaScale() > 1.1);
    }

    @Test
    void mirageCannotConsumeChargesAndDealsLess() {
        final SkillBuild arrow = build("lightning_arrow", List.of());
        final SkillBuild mirage = DeadeyeMetas.mirageBuild(arrow);
        final StatSheet effective = mirage.profile().effective(new StatSheet());
        assertTrue(effective.flat(ChargeService.SUP_INHIBIT, mirage.profile().tags()) > 0, "不能消耗戰星");
        assertEquals(0, arrow.profile().effective(new StatSheet()).flat(ChargeService.SUP_INHIBIT, arrow.profile().tags()), 1e-9);
        assertEquals(0.5, effective.more(StatKeys.DAMAGE, mirage.profile().tags())
                / arrow.profile().effective(new StatSheet()).more(StatKeys.DAMAGE, arrow.profile().tags()), 1e-9);
    }

    @Test
    void mirageChannelsToFullStagesAndPerfectTiming() {
        final StatSheet sheet = new StatSheet();
        final SkillBuild arrow = build("lightning_arrow", List.of());
        assertEquals(0, SkillService.mirageRelease(config.charge("lightning_arrow"), arrow, sheet).holdTicks());
        final SkillBuild blast = build("blast_arrow", List.of());
        final ChargeSpec blastSpec = config.charge("blast_arrow");
        final SkillService.MirageRelease blastRelease = SkillService.mirageRelease(blastSpec, blast, sheet);
        assertTrue(blastRelease.holdTicks() >= Math.round(blast.intervalTicks(sheet) * (blastSpec.stages() - 1)) - 1, "疊滿要引導好幾次攻擊時間");
        assertEquals(blastSpec.channel().stageValue() * blastSpec.stages(),
                blastRelease.build().profile().effective(sheet).flat(blastSpec.channel().stageStat(), blastRelease.build().profile().tags()), 1e-6);
        final SkillBuild snipe = build("snipe", List.of());
        final ChargeSpec snipeSpec = config.charge("snipe");
        final SkillService.MirageRelease snipeRelease = SkillService.mirageRelease(snipeSpec, snipe, sheet);
        assertEquals(snipeSpec.perfectTick(snipe.intervalTicks(sheet)), snipeRelease.holdTicks());
        assertTrue(snipeRelease.build().profile().id().endsWith(SupportProcs.PERFECT_SUFFIX));
    }
}
