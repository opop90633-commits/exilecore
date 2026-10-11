package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.DamageType;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boss 的特殊招式（光束、擴散環、漩渦、光球、全場大招）的幾何、讀設定、場地危害。 */
class BossMechanicsTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    private static YamlNode yaml(final String text) {
        return YamlNode.load("test.yml", new StringReader(text));
    }

    private static BossSkill skill(final String text) {
        return BossSkill.parse("x", yaml(text));
    }

    // ---- 幾何 ----

    @Test
    void anglesWrapAroundTheCircle() {
        assertEquals(0, BossGeometry.angleTo(0, 0, 5, 0), 1e-9, "+X 方向是 0");
        assertEquals(Math.PI / 2, BossGeometry.angleTo(0, 0, 0, 5), 1e-9, "+Z 方向是 90 度");
        assertEquals(0.2, BossGeometry.angleDifference(0.1, Math.PI * 2 - 0.1), 1e-9, "跨過 0 度也算近");
        assertEquals(Math.PI, BossGeometry.angleDifference(0, Math.PI), 1e-9);
        assertEquals(0.5, BossGeometry.angleDifference(Math.PI * 6 + 0.5, 0), 1e-9, "轉好幾圈也一樣");
    }

    @Test
    void beamsSpreadEvenlyAndSweepTogether() {
        final double start = 0.3;
        assertEquals(start, BossGeometry.beamAngle(start, 0, 2, Math.PI, 1, 0), 1e-9);
        assertEquals(start + Math.PI, BossGeometry.beamAngle(start, 1, 2, Math.PI, 1, 0), 1e-9, "兩道光束一前一後");
        assertEquals(start + Math.PI / 2, BossGeometry.beamAngle(start, 1, 4, Math.PI, 1, 0), 1e-9, "四道是十字");
        assertEquals(start + Math.PI / 2, BossGeometry.beamAngle(start, 0, 2, Math.PI, 1, 0.5), 1e-9, "掃到一半：轉了 90 度");
        assertEquals(start - Math.PI, BossGeometry.beamAngle(start, 0, 2, Math.PI, -1, 1), 1e-9, "反方向轉");
        assertEquals(start + Math.PI, BossGeometry.beamAngle(start, 0, 1, Math.PI, 1, 3), 1e-9, "進度超過 1 就停在終點");
    }

    @Test
    void beamHitsOnlyAlongItsLengthAndWidth() {
        // 從原點朝 +X 射、長 10、左右各 1
        assertTrue(BossGeometry.inBeam(0, 0, 0, 10, 1, 5, 0.5, 0));
        assertTrue(BossGeometry.inBeam(0, 0, 0, 10, 1, 9.9, -0.9, 0));
        assertFalse(BossGeometry.inBeam(0, 0, 0, 10, 1, 5, 1.5, 0), "旁邊太遠");
        assertTrue(BossGeometry.inBeam(0, 0, 0, 10, 1, 5, 1.2, 0.3), "算上半個身寬就碰到了");
        assertFalse(BossGeometry.inBeam(0, 0, 0, 10, 1, -3, 0, 0), "背後不會被打到");
        assertFalse(BossGeometry.inBeam(0, 0, 0, 10, 1, 11, 0, 0), "超過長度");
        assertTrue(BossGeometry.inBeam(0, 0, Math.PI / 2, 10, 1, 0.2, 6, 0), "朝 +Z 的光束");
    }

    @Test
    void ringHitsOnlyNearItsEdgeAndNotInsideAGap() {
        assertTrue(BossGeometry.onRing(8.3, 8, 0.5));
        assertFalse(BossGeometry.onRing(7, 8, 0.5), "環還沒到（或已經過去了）");
        final double[] gaps = BossGeometry.gapCenters(2, 0.25);
        assertEquals(2, gaps.length);
        assertEquals(0.25, gaps[0], 1e-9);
        assertEquals(0.25 + Math.PI, gaps[1], 1e-9, "兩個缺口在對面");
        final double gapAngle = Math.toRadians(40);
        assertTrue(BossGeometry.inGap(0.25 + Math.toRadians(19), gaps, gapAngle));
        assertFalse(BossGeometry.inGap(0.25 + Math.toRadians(25), gaps, gapAngle), "缺口只有左右各 20 度");
        assertTrue(BossGeometry.inGap(0.25 + Math.PI - Math.toRadians(10), gaps, gapAngle), "對面那個缺口");
        assertTrue(BossGeometry.inGap(-0.1, BossGeometry.gapCenters(1, Math.PI * 2 - 0.05), gapAngle), "跨過 0 度的缺口");
        assertEquals(0, BossGeometry.gapCenters(0, 1).length, "沒有缺口");
        assertFalse(BossGeometry.inGap(0, new double[0], gapAngle));
    }

    @Test
    void safeSpotCandidatesStartFarThenTurnAndComeCloser() {
        final List<double[]> candidates = BossGeometry.safeSpotCandidates(0, 2, 10, 2.5, 0);
        assertEquals(25, candidates.size(), "5 種距離 × 5 個方向");
        assertArrayEquals(new double[] {10, 0}, candidates.getFirst(), 1e-9, "第一個照原本的方向與距離");
        assertEquals(Math.toRadians(15), Math.atan2(candidates.get(1)[1], candidates.get(1)[0]), 1e-9, "站不上去就轉 15 度");
        assertEquals(8, Math.hypot(candidates.get(5)[0], candidates.get(5)[1]), 1e-9, "再來近一點");
        for (final double[] candidate : candidates) {
            assertTrue(Math.hypot(candidate[0], candidate[1]) >= 3.5 - 1e-9, "最近離 Boss 安全區半徑 + 1 格");
        }
        final double[] second = BossGeometry.safeSpotCandidates(1, 2, 10, 2.5, 0).getFirst();
        assertArrayEquals(new double[] {-10, 0}, second, 1e-9, "第二個安全區在對面");
        assertTrue(BossGeometry.insideAny(9, 1, List.of(new double[] {10, 0}), 2.5));
        assertFalse(BossGeometry.insideAny(6, 0, List.of(new double[] {10, 0}, new double[] {-10, 0}), 2.5));
    }

    @Test
    void pullDragsTowardsTheCentreWithoutOvershooting() {
        final double[] step = BossGeometry.pullStep(0, 0, 10, 0, 0.1);
        assertArrayEquals(new double[] {-0.1, 0}, step, 1e-9);
        final double[] near = BossGeometry.pullStep(0, 0, 0, 0.65, 0.1);
        assertEquals(-0.05, near[1], 1e-9, "快到中心時只拉到旁邊");
        assertArrayEquals(new double[] {0, 0}, BossGeometry.pullStep(0, 0, 0.3, 0.3, 0.1), 1e-9, "已經在中心");
        final double[] diagonal = BossGeometry.pullStep(0, 0, 3, 4, 0.1);
        assertEquals(0.1, Math.hypot(diagonal[0], diagonal[1]), 1e-9);
    }

    // ---- 讀設定 ----

    @Test
    void newKindsHaveSensibleDefaults() {
        final BossSkill beam = skill("kind: beam\n");
        assertEquals(BossSkill.Kind.BEAM, beam.kind());
        assertEquals(180, beam.angle(), 1e-9);
        assertEquals(14, beam.length(), 1e-9);
        assertEquals(1.2, beam.width(), 1e-9);
        assertEquals(3, beam.duration(), 1e-9);
        assertEquals(0.25, beam.interval(), 1e-9);
        assertEquals(1.2, beam.telegraph(), 1e-9);
        assertEquals(1, beam.count());
        final BossSkill ring = skill("kind: ring\ngaps: 2\n");
        assertEquals(1.5, ring.radius(), 1e-9);
        assertEquals(16, ring.reach(), 1e-9);
        assertEquals(0.4, ring.speed(), 1e-9);
        assertEquals(0.8, ring.width(), 1e-9);
        assertEquals(0.8, ring.stagger(), 1e-9);
        assertEquals(2, ring.gaps());
        assertEquals(45, ring.gapAngle(), 1e-9);
        final BossSkill pull = skill("kind: pull\n");
        assertEquals(12, pull.radius(), 1e-9);
        assertEquals(0.1, pull.speed(), 1e-9);
        assertEquals(4, pull.explode(), 1e-9);
        assertEquals(3, pull.duration(), 1e-9);
        final BossSkill orbs = skill("kind: orbs\ntype: cold\n");
        assertEquals(3, orbs.count());
        assertEquals(0.18, orbs.speed(), 1e-9);
        assertEquals(2.5, orbs.explode(), 1e-9);
        assertEquals(8, orbs.duration(), 1e-9);
        assertEquals(DamageType.COLD, orbs.type());
        assertEquals("boss_cold", orbs.visual());
        final BossSkill sanctuary = skill("kind: sanctuary\n");
        assertEquals(2.5, sanctuary.telegraph(), 1e-9, "全場大招前搖長");
        assertEquals(18, sanctuary.radius(), 1e-9);
        assertEquals(2, sanctuary.count());
        assertEquals(9, sanctuary.scatter(), 1e-9);
        assertEquals(2.5, sanctuary.safe(), 1e-9);
        for (final BossSkill special : List.of(beam, ring, pull, orbs, sanctuary)) {
            assertTrue(special.dealsDamage(), special.kind() + " 會造成傷害");
        }
        // 招式組覆蓋冷卻與傷害時，新的欄位要留著
        final BossSkill overridden = ring.with(3.0, 5);
        assertEquals(3.0, overridden.damage(), 1e-9);
        assertEquals(5, overridden.cooldown(), 1e-9);
        assertEquals(2, overridden.gaps());
        assertEquals(45, overridden.gapAngle(), 1e-9);
        assertEquals(sanctuary.safe(), sanctuary.with(-1, -1).safe(), 1e-9);
    }

    @Test
    void channelledAndHazardKinds() {
        for (final BossSkill.Kind kind : BossSkill.Kind.values()) {
            final boolean channelled = kind == BossSkill.Kind.BEAM || kind == BossSkill.Kind.RING || kind == BossSkill.Kind.PULL
                    || kind == BossSkill.Kind.SANCTUARY || kind == BossSkill.Kind.ECHOES;
            assertEquals(channelled, kind.channelled(), kind + "：Boss 站著放");
            final boolean hazard = kind == BossSkill.Kind.SLAM || kind == BossSkill.Kind.ZONE || kind == BossSkill.Kind.ORBS
                    || kind == BossSkill.Kind.MARK;
            assertEquals(hazard, kind.hazardCapable(), kind + "：能不能當場地危害");
        }
    }

    @Test
    void badSpecialSkillsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> skill("kind: pull\nspeed: 0.2\n"), "漩渦拉太快，跑步逃不掉");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: orbs\nspeed: 0.3\n"), "光球比跑步快，甩不掉");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: ring\ngaps: 4\ngap_angle: 80\n"), "缺口加起來超過 300 度");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: ring\ngaps: 7\n"), "最多 6 個缺口");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: ring\ngap_angle: 5\n"), "缺口太窄");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: sanctuary\nsafe: 8\n"), "安全區太大");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: beam\ndamage: 0\n"), "光束要造成傷害");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: vortex\n"), "不認得的種類");
        // 剛好在上限的可以
        assertEquals(BossSkill.MAX_PULL_SPEED, skill("kind: pull\nspeed: 0.15\n").speed(), 1e-9);
        assertEquals(6, skill("kind: ring\ngaps: 6\ngap_angle: 50\n").gaps());
    }

    // ---- 2026-10：點名標記、殘影、場地收縮、兩招同時放 ----

    @Test
    void newKindsParseWithSensibleDefaults() {
        final BossSkill mark = skill("kind: mark\ntype: chaos\n");
        assertEquals(1, mark.count(), "一次點一個人");
        assertEquals(4, mark.duration(), 1e-9, "4 秒後爆炸");
        assertEquals(4, mark.explode(), 1e-9);
        assertTrue(mark.dealsDamage());
        final BossSkill echoes = skill("kind: echoes\n");
        assertEquals(12, echoes.count(), "十二個殘影");
        assertEquals(9, echoes.scatter(), 1e-9);
        assertEquals(0.5, echoes.stagger(), 1e-9);
        assertEquals(1.2, echoes.delay(), 1e-9);
        assertEquals(24, echoes.reach(), 1e-9);
        assertEquals(1.2, echoes.width(), 1e-9);
        final BossSkill arena = skill("kind: arena\ndamage: 0.3\n");
        assertEquals(18, arena.reach(), 1e-9, "一開始的圈");
        assertEquals(7, arena.radius(), 1e-9, "最後的圈");
        assertEquals(20, arena.duration(), 1e-9);
        assertEquals(0.5, arena.interval(), 1e-9);
        // 疊在一起只算一下
        assertEquals(BossSkill.SAME_SKILL_GAP_TICKS, mark.hitGapTicks());
        assertEquals(BossSkill.SAME_SKILL_GAP_TICKS, echoes.hitGapTicks());
        assertEquals(9, arena.hitGapTicks(), "圈外每 0.5 秒一下");
        // 提示
        for (final BossSkill special : List.of(mark, echoes, arena)) {
            assertTrue(BossAlerts.special(special), special.kind() + " 要跳大字");
            assertFalse(BossAlerts.hint(special).isBlank());
        }
        assertTrue(BossAlerts.hint(mark).contains("4 秒"), BossAlerts.hint(mark));
        // 覆蓋冷卻與傷害時欄位留著
        assertEquals(arena.radius(), arena.with(1, 5).radius(), 1e-9);
    }

    @Test
    void newKindsMustBeEscapable() {
        assertThrows(IllegalArgumentException.class, () -> skill("kind: mark\nduration: 1.5\n"), "標記太快炸，走不開");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: echoes\ndelay: 0.5\n"), "殘影的紅線太短");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: echoes\nscatter: 2\n"), "殘影貼著 Boss");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: arena\nreach: 10\nradius: 12\n"), "圈越縮越大");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: arena\nradius: 3\nreach: 10\n"), "最後的圈太小");
        assertThrows(IllegalArgumentException.class, () -> skill("kind: arena\nduration: 0\n"), "要寫幾秒縮完");
        assertEquals(BossSkill.MIN_MARK_FUSE, skill("kind: mark\nduration: 2.5\n").duration(), 1e-9, "剛好在下限的可以");
    }

    @Test
    void arenaShrinksThenHolds() {
        assertEquals(18, BossGeometry.arenaRadius(18, 7, 0, 200), 1e-9);
        assertEquals(12.5, BossGeometry.arenaRadius(18, 7, 100, 200), 1e-9, "縮到一半");
        assertEquals(7, BossGeometry.arenaRadius(18, 7, 200, 200), 1e-9);
        assertEquals(7, BossGeometry.arenaRadius(18, 7, 5000, 200), 1e-9, "縮完之後維持");
        assertFalse(BossGeometry.outsideArena(0, 0, 7, 7.2, 0, 0.3), "身體還有一半在圈裡不算");
        assertTrue(BossGeometry.outsideArena(0, 0, 7, 7.4, 0, 0.3));
    }

    @Test
    void echoesSurroundTheBossAndTakeTurnsAcrossTheCircle() {
        final List<double[]> offsets = BossGeometry.echoOffsets(12, 10, 0);
        assertEquals(12, offsets.size());
        for (final double[] offset : offsets) {
            assertEquals(10, Math.hypot(offset[0], offset[1]), 1e-9, "都在 10 格的圈上");
        }
        final int[] order = BossGeometry.echoOrder(12);
        assertEquals(12, java.util.Arrays.stream(order).distinct().count(), "每個殘影都出手一次");
        for (int i = 1; i < order.length; i++) {
            final int apart = Math.floorMod(order[i] - order[i - 1], 12);
            assertTrue(apart != 1 && apart != 11, "連續兩槍不從隔壁射出來：" + java.util.Arrays.toString(order));
        }
        assertArrayEquals(new int[] {0, 1}, BossGeometry.echoOrder(2), "太少就照順序");
    }

    @Test
    void combosParseAndOnlyAllowDetachedSkills() {
        final List<BossSkillUse> uses = BossSkillUse.parseList(yaml("""
                skills:
                  - { id: warden_star_well, with: [warden_star_rain] }
                  - { id: warden_star_cross, with: hazard_star_orbs }
                  - { id: warden_star_hammer, with: [warden_star_cross, nope] }
                """), "skills");
        assertEquals(List.of("warden_star_rain"), uses.get(0).with());
        assertEquals(List.of("hazard_star_orbs"), uses.get(1).with(), "寫一個也可以");
        assertEquals(new BossSkillUse("slam", -1, -1, 0), new BossSkillUse("slam", -1, -1, 0, null), "沒寫 with 是空的");
        final BossSkillConfig bosses = config.bossSkills();
        assertEquals(List.of("nope"), bosses.unknown(uses), "with 的招式也要存在");
        final List<String> problems = bosses.comboProblems(uses);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("warden_star_cross"), "光束要 Boss 站著放，不能跟著一起放");
        final List<BossSkillConfig.Entry> kit = bosses.kitFor("VINDICATOR", uses);
        assertEquals(List.of("warden_star_rain"), kit.get(0).with().stream().map(BossSkill::id).toList());
        assertEquals(List.of(), kit.get(2).with(), "不能一起放的、不存在的略過");
        assertThrows(IllegalArgumentException.class, () -> BossSkillUse.parseList(yaml(
                "skills:\n  - { id: slam, with: [a, b, c, d] }\n"), "skills"), "最多同時再放 3 招");
    }

    @Test
    void fourthTrialIsTheFinalExam() throws IOException {
        final tw.exilecore.ascendancy.AscendancyConfig ascendancy = new tw.exilecore.ascendancy.AscendancyConfig();
        ascendancy.load(DATA);
        final tw.exilecore.ascendancy.AscendancyConfig.Trial star = ascendancy.trial("star");
        assertEquals(75, star.level(), "照 PoE2：第四次昇華在終局 75 級");
        final List<BossSkillConfig.Entry> kit = config.bossSkills().kitFor(star.boss().type(), star.boss().skills());
        final java.util.Set<BossSkill.Kind> kinds = java.util.EnumSet.noneOf(BossSkill.Kind.class);
        boolean combo = false;
        for (final BossSkillConfig.Entry entry : kit) {
            kinds.add(entry.skill().kind());
            combo |= !entry.with().isEmpty();
        }
        assertTrue(kinds.containsAll(java.util.EnumSet.of(BossSkill.Kind.MARK, BossSkill.Kind.ECHOES, BossSkill.Kind.ARENA,
                BossSkill.Kind.BEAM, BossSkill.Kind.PULL, BossSkill.Kind.ORBS, BossSkill.Kind.RING, BossSkill.Kind.SANCTUARY)), kinds.toString());
        assertTrue(combo, "至少一招是兩招同時放");
        final List<String> problems = new java.util.ArrayList<>();
        tw.exilecore.map.MapConfig.bossProblems(problems, "star", star.boss(), config.bossSkills());
        assertEquals(List.of(), problems);
    }

    // ---- 場地危害 ----

    @Test
    void hazardsParseAndValidate() {
        final YamlNode node = yaml("""
                hazards:
                  - { skill: hazard_star_meteor, every: 9 }
                  - { skill: hazard_ice_orbs, every: 12, phase: 2 }
                """);
        final List<BossHazard> hazards = BossHazard.parseList(node, "hazards");
        assertEquals(2, hazards.size());
        assertEquals(new BossHazard("hazard_star_meteor", 9, 0), hazards.getFirst());
        assertEquals(2, hazards.get(1).fromPhase());
        assertEquals(List.of(), BossHazard.parseList(yaml("name: x\n"), "hazards"), "沒寫是空的");
        assertEquals(List.of(), BossHazard.problems(hazards, config.bossSkills()));
        assertThrows(IllegalArgumentException.class, () -> BossHazard.parseList(yaml("hazards: { skill: x }\n"), "hazards"), "要寫成清單");
        assertThrows(IllegalArgumentException.class, () -> BossHazard.parseList(yaml("hazards:\n  - { skill: x, every: 1 }\n"), "hazards"),
                "太頻繁");
        assertThrows(IllegalArgumentException.class, () -> BossHazard.parseList(yaml("hazards:\n  - { skill: x, phase: 11 }\n"), "hazards"));
        assertThrows(IllegalArgumentException.class, () -> BossHazard.parseList(yaml("hazards:\n  - { every: 9 }\n"), "hazards"), "要寫招式");
        final List<String> problems = BossHazard.problems(List.of(new BossHazard("nope", 9, 0), new BossHazard("quake", 9, 0)),
                config.bossSkills());
        assertEquals(2, problems.size());
        assertTrue(problems.getFirst().contains("nope"));
        assertTrue(problems.get(1).contains("nova"), "重踏是 Boss 自己放的，不能當場地危害：" + problems.get(1));
    }

    @Test
    void shippedSpecialSkillsAreUsableAndEscapable() {
        final BossSkillConfig bosses = config.bossSkills();
        int special = 0;
        for (final BossSkill skill : bosses.skills().values()) {
            switch (skill.kind()) {
                case PULL -> assertTrue(skill.explode() < skill.radius(), skill.id() + "：爆炸範圍要比吸的範圍小（才跑得出去）");
                case SANCTUARY -> {
                    assertTrue(skill.telegraph() >= 2.5, skill.id() + "：全場大招的前搖至少 2.5 秒");
                    assertTrue(skill.scatter() / skill.telegraph() <= 5.5, skill.id() + "：安全區要跑得到（跑步每秒大約 5.6 格）");
                }
                // 2026-10：沒有缺口的環（第四試煉的無缺星環）要夠薄、跑得慢，翻滾穿得過去，提示也要說清楚
                case RING -> assertTrue(skill.gaps() > 0 || skill.width() <= 0.6 && skill.speed() <= 0.3 && skill.hint() != null,
                        skill.id() + "：擴散環要有缺口，沒有缺口的要夠薄、夠慢、寫 hint");
                case MARK -> assertTrue(skill.explode() <= 4, skill.id() + "：標記的爆炸不要太大（走得出隊友的圈）");
                case ECHOES -> assertTrue(skill.delay() >= 1.0, skill.id() + "：殘影的紅線至少標 1 秒");
                case ARENA -> assertTrue(skill.radius() >= 6 && skill.damage() <= 0.5, skill.id() + "：場地收縮最後的圈要站得下、圈外一下不要太痛");
                case BEAM -> assertTrue(skill.interval() >= 0.25, skill.id() + "：光束不要每 tick 都打");
                default -> {
                }
            }
            if (skill.kind().channelled() || skill.kind() == BossSkill.Kind.ORBS) {
                special++;
            }
        }
        assertTrue(special >= 25, "特殊招式至少 25 招，實際 " + special);
        for (final String id : List.of("hazard_star_meteor", "hazard_star_orbs", "hazard_frost_spike", "hazard_ice_orbs",
                "hazard_grave_hands", "hazard_ember_fall", "hazard_ember_orbs", "hazard_warden_star")) {
            assertTrue(bosses.skill(id) != null && bosses.skill(id).kind().hazardCapable(), id);
        }
    }
}
