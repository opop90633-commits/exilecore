package tw.exilecore.map;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.DamageType;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02 照站長：Boss 換階段的鎖血機制（站位圈、倒數大招、元素弱點）的純邏輯。 */
class PhaseMechanicTest {

    private static YamlNode yaml(final String text) {
        return YamlNode.load("test.yml", new StringReader(text));
    }

    private static MapConfig.Phase phase(final double at, final MapConfig.Shield shield, final PhaseMechanic.Spec mechanic) {
        return new MapConfig.Phase(at, "階段", null, null, 0, List.of(), 0, shield, mechanic);
    }

    private static final MapConfig.Shield SHIELD = new MapConfig.Shield(3, "BLAZE", "星骸結晶", 4, 30, 40);

    // ---- 2026-11-04 照站長：一隻 Boss 只留一個鎖血（破綻）；輪流只剩破綻、只配給第一個沒寫的階段 ----

    @Test
    void onlyOneOpeningPerBoss() {
        assertEquals(List.of(PhaseMechanic.Kind.OPENING), PhaseMechanic.ROTATION, "2026-11-04：站位圈、倒數大招、元素弱點、結晶都不再自動配");
        final List<PhaseMechanic.Kind> kinds = PhaseMechanic.rotation("awakener", Arrays.asList(null, null, null));
        assertEquals(PhaseMechanic.Kind.OPENING, kinds.get(0));
        // 寫 none 的階段保持 none、不算用過
        assertEquals(PhaseMechanic.Kind.NONE, PhaseMechanic.rotation("z", Arrays.asList(PhaseMechanic.Kind.NONE, null)).get(0));
    }

    @Test
    void resolveUsesShieldExplicitAndAutoFill() {
        final PhaseMechanic.Spec countdown = PhaseMechanic.Spec.countdown(PhaseMechanic.defaultCountdown(false));
        final List<MapConfig.Phase> phases = List.of(phase(0.75, SHIELD, null), phase(0.5, null, null), phase(0.25, null, countdown),
                phase(0.1, null, PhaseMechanic.Spec.NONE));
        final List<PhaseMechanic.Spec> specs = PhaseMechanic.resolve("k", phases, true, false);
        assertEquals(PhaseMechanic.Kind.CRYSTAL, specs.get(0).kind(), "寫了 shield：維持結晶");
        assertSame(SHIELD, specs.get(0).crystal());
        assertSame(countdown, specs.get(2), "寫了 mechanic 照寫的");
        assertNull(specs.get(3), "mechanic: none 不鎖");
        assertNull(specs.get(1), "2026-11-04：已經有寫死的鎖血，沒寫的階段不再自動配");
        // 一般地圖（不輪流）：沒寫的不鎖
        final List<PhaseMechanic.Spec> plain = PhaseMechanic.resolve("k", List.of(phase(0.6, null, null), phase(0.3, SHIELD, null)), false, false);
        assertNull(plain.get(0));
        assertEquals(PhaseMechanic.Kind.CRYSTAL, plain.get(1).kind());
        // 終局以上什麼都沒寫：只有第一個階段配破綻（回血照預設 30%、30 秒、3 層），其他不鎖
        final List<MapConfig.Phase> only = List.of(phase(0.5, null, null), phase(0.25, null, null), phase(0.1, null, null));
        final List<PhaseMechanic.Spec> auto = PhaseMechanic.resolve("k", only, true, true);
        assertEquals(PhaseMechanic.Kind.OPENING, auto.get(0).kind());
        assertNull(auto.get(1));
        assertNull(auto.get(2));
        assertEquals(PhaseMechanic.OPENING_DURATION, auto.get(0).seconds(), 1e-9);
        assertEquals(PhaseMechanic.DEFAULT_HEAL, auto.get(0).heal(), 1e-9);
        assertEquals(3, auto.get(0).opening().layers());
        assertFalse(auto.get(0).invulnerable(), "破綻：Boss 不無敵、照常出招");
        // 寫了 shield 的階段還是結晶（寫死的照寫），沒寫的就不再配
        final List<PhaseMechanic.Spec> withShield = PhaseMechanic.resolve("k", List.of(phase(0.6, null, null), phase(0.3, SHIELD, null)), true, false);
        assertNull(withShield.get(0));
        assertEquals(PhaseMechanic.Kind.CRYSTAL, withShield.get(1).kind());
    }

    @Test
    void parseMechanicForms() {
        final MapConfig.Boss boss = MapConfig.parseBoss(yaml("""
                name: 測試
                phases:
                  - at: 0.75
                    shield: { count: 2, duration: 30, heal: 35 }
                  - at: 0.5
                    shield: { count: 2, duration: 20, heal: 40 }
                    mechanic: zones
                  - at: 0.25
                    mechanic: { type: countdown, name: 天塌, delay: 6, safe: 5, damage: 2, weak: 4, weak_taken: 0.5 }
                  - at: 0.1
                    mechanic: { type: element, share: 0.5, rotate: 10, warn: 3 }
                """));
        final List<MapConfig.Phase> phases = boss.phases();
        assertNull(phases.get(0).mechanic(), "只寫 shield：mechanic 沒寫");
        final PhaseMechanic.Spec zones = phases.get(1).mechanic();
        assertEquals(PhaseMechanic.Kind.ZONES, zones.kind());
        assertEquals(20, zones.zones().duration(), 1e-9, "限時沒寫照同一個階段的 shield");
        assertEquals(40, zones.zones().heal(), 1e-9);
        final PhaseMechanic.Countdown countdown = phases.get(2).mechanic().countdown();
        assertEquals("天塌", countdown.name());
        assertEquals(6, countdown.delay(), 1e-9);
        assertEquals(2, countdown.safe(), "安全區最多 2 個");
        assertEquals(PhaseMechanic.COUNTDOWN_DAMAGE_MAX, countdown.damage(), 1e-9, "不會超過 95%（滿血不直接秒殺）");
        final PhaseMechanic.Element element = phases.get(3).mechanic().element();
        assertEquals(0.5, element.share(), 1e-9);
        assertEquals(PhaseMechanic.DEFAULT_DURATION, element.duration(), 1e-9, "沒有 shield：預設 25 秒");
        assertEquals(PhaseMechanic.Kind.CRYSTAL, PhaseMechanic.parse(yaml("mechanic: crystal\n"), SHIELD).kind());
        assertSame(SHIELD, PhaseMechanic.parse(yaml("mechanic: crystal\n"), SHIELD).crystal(), "結晶照同一個階段的 shield");
        assertEquals("BLAZE", PhaseMechanic.parse(yaml("mechanic: crystal\n"), null).crystal().type(), "沒寫 shield：預設的結晶");
        assertSame(PhaseMechanic.Spec.NONE, PhaseMechanic.parse(yaml("mechanic: none\n"), SHIELD));
        assertThrows(IllegalArgumentException.class, () -> PhaseMechanic.parse(yaml("mechanic: laser\n"), null));
        assertThrows(IllegalArgumentException.class, () -> PhaseMechanic.parse(yaml("mechanic: { type: countdown, delay: 1 }\n"), null));
        assertThrows(IllegalArgumentException.class, () -> PhaseMechanic.parse(yaml("mechanic: { type: element, duration: 2 }\n"), null));
    }

    // ---- 站位圈 ----

    @Test
    void zoneCountFollowsPlayers() {
        // 2026-11-02 照站長第二次：圈數 = 在場的人數，1～6（這次之前每人份、至少 2 最多 8）
        assertEquals(1, PhaseMechanic.zoneCount(1), "單人一個圈，不用來回跑");
        assertEquals(2, PhaseMechanic.zoneCount(2));
        assertEquals(4, PhaseMechanic.zoneCount(4), "一人一個");
        assertEquals(6, PhaseMechanic.zoneCount(6));
        assertEquals(6, PhaseMechanic.zoneCount(12), "最多 6 個");
        assertEquals(1, PhaseMechanic.zoneCount(0), "沒算到人也有一個");
    }

    @Test
    void chargeFillsWhileOccupiedAndDrainsSlowlyWhenEmpty() {
        assertEquals(0.1, PhaseMechanic.charge(0, true, 0.5, 5, 0.25), 1e-9, "站 0.5 秒充 1/10");
        assertEquals(1, PhaseMechanic.charge(0.95, true, 0.5, 5, 0.25), 1e-9, "最多 1");
        double charge = 0;
        int steps = 0;
        while (charge < 1) {
            charge = PhaseMechanic.charge(charge, true, 0.5, 5, 0.25);
            steps++;
        }
        assertEquals(10, steps, "5 秒（每 0.5 秒一次）充滿");
        // 沒人站：慢慢退，速度是充能的 drain 倍（四分之一）
        assertEquals(0.5 - 0.1 * 0.25, PhaseMechanic.charge(0.5, false, 0.5, 5, 0.25), 1e-9);
        charge = 1;
        steps = 0;
        while (charge > 1e-9) {
            charge = PhaseMechanic.charge(charge, false, 0.5, 5, 0.25);
            steps++;
        }
        assertEquals(40, steps, 1, "充滿的圈沒人站約 20 秒才掉光（充能的四倍久）");
        assertEquals(0, PhaseMechanic.charge(0.01, false, 1, 5, 0.25), 1e-9, "最低 0");
        assertEquals(0.3, PhaseMechanic.charge(0.3, false, 1, 5, 0), 1e-9, "drain 0：不退");
        final PhaseMechanic.Zones zones = PhaseMechanic.defaultZones(25, 30);
        assertTrue(zones.drain() > 0 && zones.drain() < 0.5, "預設明顯比充能慢");
        assertEquals(0.5, PhaseMechanic.parseZones(yaml("drain: 0.5\n"), 25, 30).drain(), 1e-9, "寫在設定裡可以調");
        assertTrue(PhaseMechanic.allCharged(new double[] {1, 1}));
        assertFalse(PhaseMechanic.allCharged(new double[] {1, 0.99}));
        assertFalse(PhaseMechanic.allCharged(new double[0]), "沒有圈不算完成");
    }

    @Test
    void vulnerabilityStacksInsideAndDecaysOutside() {
        final PhaseMechanic.Zones zones = PhaseMechanic.defaultZones(25, 30);
        double v = 0;
        for (int i = 0; i < 10; i++) {
            v = PhaseMechanic.vulnerability(v, true, 0.5, zones); // 站 5 秒
        }
        assertEquals(30, v, 1e-9, "每秒 +6%");
        for (int i = 0; i < 40; i++) {
            v = PhaseMechanic.vulnerability(v, true, 0.5, zones);
        }
        assertEquals(zones.vulnMax(), v, 1e-9, "最多 60%");
        v = PhaseMechanic.vulnerability(v, false, 1, zones);
        assertEquals(56, v, 1e-9, "離開後每秒 −4%（慢慢消退）");
        assertEquals(0, PhaseMechanic.vulnerability(2, false, 1, zones), 1e-9, "最低 0");
    }

    // ---- 倒數大招 ----

    @Test
    void safeZoneJudgement() {
        final List<double[]> spots = List.of(new double[] {0, 0}, new double[] {10, 0});
        assertTrue(PhaseMechanic.inside(0, 0, spots, 3), "圈心");
        assertTrue(PhaseMechanic.inside(3.5, 0, spots, 3), "邊上放寬 0.75 格");
        assertFalse(PhaseMechanic.inside(3.9, 0, spots, 3), "超過放寬就不算");
        assertTrue(PhaseMechanic.inside(8, 1, spots, 3), "第二個安全區");
        assertFalse(PhaseMechanic.inside(5, 0, spots, 3), "兩個中間");
        assertFalse(PhaseMechanic.inside(0, 0, List.of(), 3), "沒有安全區");
    }

    @Test
    void slamDamageIsAFractionOfMaxLife() {
        assertEquals(600, PhaseMechanic.slamDamage(1000, 0, 0.6), 1e-9);
        assertEquals(850, PhaseMechanic.slamDamage(600, 400, 0.85), 1e-9, "生命＋能量護盾");
        assertEquals(950, PhaseMechanic.slamDamage(1000, 0, 5), 1e-9, "最多 95%：滿血不會被直接秒殺");
        assertTrue(PhaseMechanic.slamDamage(1000, 0, PhaseMechanic.COUNTDOWN_DAMAGE_HEAVY) < 1000, "高階接近致命但不秒殺");
        assertEquals(0, PhaseMechanic.slamDamage(1000, 0, -1), 1e-9);
    }

    // ---- 元素弱點 ----

    @Test
    void elementFactorOnlyCountsTheWeakElement() {
        assertEquals(1, PhaseMechanic.elementFactor(Map.of(DamageType.FIRE, 100.0), DamageType.FIRE, 0.1), 1e-9, "全部是弱點元素：照算");
        assertEquals(0.1, PhaseMechanic.elementFactor(Map.of(DamageType.COLD, 100.0), DamageType.FIRE, 0.1), 1e-9, "其他元素：一成");
        assertEquals(0.1, PhaseMechanic.elementFactor(Map.of(DamageType.PHYSICAL, 50.0, DamageType.CHAOS, 50.0), DamageType.LIGHTNING, 0.1), 1e-9,
                "物理、混沌也只打一成");
        assertEquals((60 + 40 * 0.1) / 100, PhaseMechanic.elementFactor(Map.of(DamageType.FIRE, 60.0, DamageType.PHYSICAL, 40.0), DamageType.FIRE, 0.1),
                1e-9, "混合的照比例");
        assertEquals(1, PhaseMechanic.elementFactor(Map.of(), DamageType.FIRE, 0.1), 1e-9, "沒有傷害：不改");
        assertEquals(1, PhaseMechanic.elementFactor(Map.of(DamageType.COLD, 10.0), null, 0.1), 1e-9, "沒有弱點：不改");
    }

    @Test
    void elementChoiceExcludesTheHighestResist() {
        assertEquals(List.of(DamageType.FIRE, DamageType.LIGHTNING), PhaseMechanic.allowedElements(PhaseMechanic.resists(30, 75, 30)),
                "冰抗最高（冰霜女王）：不輪到冰");
        assertEquals(PhaseMechanic.ELEMENTS, PhaseMechanic.allowedElements(PhaseMechanic.resists(30, 30, 30)), "一樣高：都可以");
        assertEquals(PhaseMechanic.ELEMENTS, PhaseMechanic.allowedElements(PhaseMechanic.resists(50, 50, 20)), "兩個並列最高：不排除");
        final Random random = new Random(7);
        final List<DamageType> allowed = List.of(DamageType.FIRE, DamageType.LIGHTNING);
        for (int i = 0; i < 20; i++) {
            assertEquals(DamageType.LIGHTNING, PhaseMechanic.nextElement(allowed, DamageType.FIRE, random), "換的時候一定換成另一種");
        }
        final List<DamageType> seen = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final DamageType pick = PhaseMechanic.nextElement(PhaseMechanic.ELEMENTS, DamageType.COLD, random);
            assertNotEquals(DamageType.COLD, pick);
            seen.add(pick);
        }
        assertTrue(seen.contains(DamageType.FIRE) && seen.contains(DamageType.LIGHTNING), "隨機");
        assertEquals(DamageType.FIRE, PhaseMechanic.nextElement(List.of(DamageType.FIRE), DamageType.FIRE, random), "只剩一種就是那一種");
    }

    @Test
    void elementRequiredShareOfThePhase() {
        final List<MapConfig.Phase> phases = List.of(phase(0.75, null, null), phase(0.5, null, null), phase(0.25, null, null));
        assertEquals(0.25 * 0.3, PhaseMechanic.requiredFraction(phases, 0, 0.3), 1e-9, "0.75 到 0.5 的三成");
        assertEquals(0.25 * 0.3, PhaseMechanic.requiredFraction(phases, 2, 0.3), 1e-9, "最後一個階段到 0");
        assertEquals(0, PhaseMechanic.requiredFraction(phases, 3, 0.3), 1e-9);
        assertEquals(0.5, PhaseMechanic.elementProgress(0.75, 0.7125, 0.075), 1e-9);
        assertEquals(1, PhaseMechanic.elementProgress(0.75, 0.6, 0.075), 1e-9, "最多 1");
        assertEquals(0, PhaseMechanic.elementProgress(0.75, 0.8, 0.075), 1e-9, "回血了也不會變負的");
    }

    @Test
    void mechanicTextNamesWhatToDo() {
        final MapConfig.Phase plain = phase(0.5, null, null);
        assertTrue(BossMechanicText.phaseNotes(plain, PhaseMechanic.Spec.zones(PhaseMechanic.defaultZones(25, 30)), List.of()).getFirst()
                .contains("站滿全部光圈"));
        assertTrue(BossMechanicText.phaseNotes(plain, PhaseMechanic.Spec.countdown(PhaseMechanic.defaultCountdown(false)), List.of()).getFirst()
                .contains("安全區"));
        assertTrue(BossMechanicText.phaseNotes(plain, PhaseMechanic.Spec.element(PhaseMechanic.defaultElement(25, 30)), List.of()).getFirst()
                .contains("只吃一種元素"));
        assertEquals("Boss 變得更強", BossMechanicText.phaseNotes(plain, null, List.of()).getFirst());
        for (final PhaseMechanic.Kind kind : PhaseMechanic.Kind.values()) {
            assertFalse(kind.label().contains("公尺"));
        }
        // 2026-11-02 照站長：倒數大招放完不再虛弱
        assertEquals(0, PhaseMechanic.defaultCountdown(false).weak(), 1e-9);
        assertFalse(BossMechanicText.weakened(PhaseMechanic.defaultCountdown(false))[0].contains("虛弱"));
        final String[] weak = BossMechanicText.weakened(new PhaseMechanic.Countdown("x", 8, 2, 3, 0.6, 6, 0.5));
        assertTrue(weak[1].contains("50%") && weak[1].contains("6 秒"), weak[1]);
    }
}
