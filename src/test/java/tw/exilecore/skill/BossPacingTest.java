package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.map.MapConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boss 一次只做一件事（2026-10，「終局 Boss 好像會同時放兩個招式」）：場地危害等 Boss 這一招放完才落、
 * 落下之前不出新的招；齊射還在飛、光球剛放出去也算這一招還沒完。
 */
class BossPacingTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;
    static MapConfig maps;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        maps = new MapConfig();
        maps.load(DATA);
    }

    // ---- 上一件事還沒完 ----

    @Test
    void leftoversCountAsTheSameSkill() {
        final long now = 1_000;
        assertFalse(BossPacing.unsettled(0, 0, 0, BossPacing.NEVER, now), "什麼都沒有");
        assertTrue(BossPacing.unsettled(1, 0, 0, BossPacing.NEVER, now), "齊射還在飛");
        assertTrue(BossPacing.unsettled(0, 1, 0, BossPacing.NEVER, now), "擴散環還在擴散");
        assertTrue(BossPacing.unsettled(0, 0, 1, BossPacing.NEVER, now), "場地危害還沒落下");
        assertTrue(BossPacing.unsettled(0, 0, 0, now, now), "光球剛放出去");
        assertTrue(BossPacing.unsettled(0, 0, 0, now - BossPacing.ORB_SETTLE_TICKS + 1, now));
        assertFalse(BossPacing.unsettled(0, 0, 0, now - BossPacing.ORB_SETTLE_TICKS, now),
                "光球放出去 2 秒後：還在追人也可以出下一招（不然光球活多久 Boss 就發呆多久）");
        assertEquals(40, BossPacing.ORB_SETTLE_TICKS);
    }

    // ---- 什麼時候能開始下一件事 ----

    @Test
    void hazardsMayLandInTheGapButSkillsWait() {
        final long gap = 30;
        final long readyAt = 1_000;
        assertEquals(BossPacing.Step.ANY, BossPacing.step(readyAt, readyAt, gap));
        assertEquals(BossPacing.Step.ANY, BossPacing.step(readyAt + 500, readyAt, gap));
        assertEquals(BossPacing.Step.HAZARD, BossPacing.step(readyAt - 1, readyAt, gap), "放完一招後的空檔：場地危害可以落");
        assertEquals(BossPacing.Step.HAZARD, BossPacing.step(readyAt - gap, readyAt, gap));
        assertEquals(BossPacing.Step.WAIT, BossPacing.step(readyAt - gap - 1, readyAt, gap),
                "比 gap 長的等待（換階段的停頓、剛看到人）：場地危害也不落");
        assertEquals(BossPacing.Step.WAIT, BossPacing.step(readyAt - 1, readyAt, 0), "沒有場地危害的 Boss：空檔裡不用醒來");
    }

    // ---- 場地危害的計時 ----

    @Test
    void hazardTimersStartWhenTheyBecomeActive() {
        final BossPacing pacing = new BossPacing();
        final Map<String, Long> every = Map.of("0:meteor", 200L, "1:orbs", 280L);
        assertNull(pacing.due(List.of("0:meteor"), every::get, 100), "第一次只開始計時");
        assertEquals(300L, pacing.nextAt("0:meteor"));
        assertNull(pacing.nextAt("1:orbs"), "還沒到會落的階段：不計時");
        assertNull(pacing.due(List.of("0:meteor"), every::get, 299));
        assertEquals("0:meteor", pacing.due(List.of("0:meteor"), every::get, 300));
        assertEquals("0:meteor", pacing.due(List.of("0:meteor"), every::get, 301), "沒呼叫 fired 之前一直是到時間的");
        pacing.fired("0:meteor", 345, 200);
        assertEquals(545L, pacing.nextAt("0:meteor"), "從真的落下的時候起算：等 Boss 耽擱的時間不會讓下一次提早");
        // 到了第二階段才加進來的那一條：從那時起算
        assertNull(pacing.due(List.of("0:meteor", "1:orbs"), every::get, 400));
        assertEquals(680L, pacing.nextAt("1:orbs"));
    }

    @Test
    void oneHazardAtATimeEarliestFirst() {
        final BossPacing pacing = new BossPacing();
        final List<String> keys = List.of("0:a", "1:b", "2:c");
        pacing.due(keys, key -> switch (key) {
            case "0:a" -> 100L;
            case "1:b" -> 50L;
            default -> 100L;
        }, 0);
        assertEquals("1:b", pacing.due(keys, key -> 0, 120), "最早到期的先");
        pacing.fired("1:b", 120, 50);
        assertEquals("0:a", pacing.due(keys, key -> 0, 125), "同時到期：照寫的順序");
        pacing.fired("0:a", 125, 100);
        assertEquals("2:c", pacing.due(keys, key -> 0, 126), "一次一條，下一條等下一次");
    }

    /** 換階段的停頓（2 秒）比 gap（1.5 秒）長：停頓的最後 1.5 秒照空檔的規則可以落，所以換階段時把計時延到停頓結束。 */
    @Test
    void phasePauseHoldsHazards() {
        final BossPacing pacing = new BossPacing();
        final List<String> keys = List.of("0:meteor");
        pacing.due(keys, key -> 100L, 0);
        final long change = 100; // 換階段的那一刻，墜星剛好到時間
        final long readyAt = change + 40;
        pacing.delayHazards(readyAt);
        assertEquals(BossPacing.Step.HAZARD, BossPacing.step(change + 15, readyAt, 30), "只看時間的話停頓的後段可以落");
        assertNull(pacing.due(keys, key -> 100L, change + 15), "但計時已經延到停頓結束");
        assertEquals("0:meteor", pacing.due(keys, key -> 100L, readyAt));
    }

    @Test
    void idleGraceDelaysHazards() {
        final BossPacing pacing = new BossPacing();
        pacing.due(List.of("0:a", "1:b"), key -> key.equals("0:a") ? 20L : 400L, 0);
        pacing.delayHazards(100);
        assertEquals(100L, pacing.nextAt("0:a"), "剛看到人：先等 first_delay");
        assertEquals(400L, pacing.nextAt("1:b"), "本來就比較晚的不動");
        pacing.clear();
        assertNull(pacing.nextAt("0:a"), "重置：重新計時");
    }

    // ---- 照出貨的數值走一遍時間表 ----

    /**
     * 終局 Boss（四隻，一般與極境的最後一個階段）：Boss 一直有招可以放、每一招都放最久的那種（最壞的情況），
     * 照 {@link BossPacing} 的規則一 tick 一 tick 走 3 分鐘：
     * <ul>
     *   <li>Boss 的招與場地危害從來不會同時在進行（場地危害的警告到落下、光球剛放出去的 2 秒都算）；</li>
     *   <li>場地危害不會被 Boss 一直出招擋住：兩次之間最多晚一招（最久的那招 + gap）加上其他場地危害佔掉的時間；</li>
     *   <li>Boss 也不會被場地危害擋到沒空出招：至少一半的時間在放自己的招。</li>
     * </ul>
     */
    @Test
    void shippedFinalBossesDoOneThingAtATime() {
        final BossSkillConfig bosses = config.bossSkills();
        final long gapBase = Math.round(bosses.settings().gapFor(true) * 20); // 終局是 Boss：用 boss_gap
        int checked = 0;
        for (final MapConfig.MapDef map : maps.finals()) {
            final List<MapConfig.Boss> versions = new ArrayList<>();
            final List<Double> factors = new ArrayList<>();
            versions.add(map.boss());
            factors.add(1.0);
            final MapConfig.FinalInfo info = maps.finalInfo(map.id());
            if (info != null && info.uber() != null) {
                versions.add(info.uber().merge(map.boss()));
                factors.add(info.uber().cooldown());
            }
            for (int v = 0; v < versions.size(); v++) {
                final MapConfig.Boss boss = versions.get(v);
                final double factor = factors.get(v);
                if (boss.hazards().isEmpty()) {
                    continue;
                }
                final String label = map.id() + (v == 0 ? "" : "（極境）");
                long longest = 0;
                for (final BossSkillConfig.Entry entry : bosses.kitFor(boss.type(), boss.skills())) {
                    longest = Math.max(longest, castTicks(entry.skill()));
                }
                final long gap = Math.round(gapBase * factor);
                simulate(label, boss, longest, gap, factor);
                checked++;
            }
        }
        assertTrue(checked >= 8, "四隻終局 Boss 的一般與極境版本都有場地危害：" + checked);
    }

    private static void simulate(final String label, final MapConfig.Boss boss, final long castTicks, final long gap, final double factor) {
        final BossSkillConfig bosses = config.bossSkills();
        final List<String> keys = new ArrayList<>();
        final Map<String, Long> every = new HashMap<>();
        final Map<String, Long> occupy = new HashMap<>();
        long occupyAll = 0;
        for (int i = 0; i < boss.hazards().size(); i++) {
            final BossHazard hazard = boss.hazards().get(i);
            final BossSkill skill = bosses.skill(hazard.skill());
            assertNotNull(skill, label + "：" + hazard.skill());
            final String key = i + ":" + hazard.skill();
            keys.add(key);
            every.put(key, Math.round(hazard.every() * 20 * factor));
            final long ticks = hazardTicks(skill);
            occupy.put(key, ticks);
            occupyAll += ticks;
        }
        final BossPacing pacing = new BossPacing();
        final long end = 20 * 180;
        long readyAt = 40;
        long castEnd = -1;
        long busyUntil = -1; // 場地危害落下（或光球放出去 2 秒）之前
        long newestOrb = BossPacing.NEVER;
        long casting = 0;
        final Map<String, Long> last = new HashMap<>();
        for (long now = 0; now < end; now++) {
            if (now < castEnd) {
                casting++;
                assertTrue(now >= busyUntil, label + "：Boss 放招的時候場地危害還沒落下（tick " + now + "）");
                assertTrue(now - newestOrb >= BossPacing.ORB_SETTLE_TICKS, label + "：Boss 放招的時候光球才剛放出去（tick " + now + "）");
                continue;
            }
            if (now == castEnd) {
                readyAt = now + gap; // 放完：隔 gap 才出下一招
            }
            final int pendings = now < busyUntil ? 1 : 0;
            if (BossPacing.unsettled(0, 0, pendings, newestOrb, now)) {
                continue;
            }
            final BossPacing.Step step = BossPacing.step(now, readyAt, gap);
            if (step == BossPacing.Step.WAIT) {
                continue;
            }
            final String key = pacing.due(keys, every::get, now);
            if (key != null) {
                pacing.fired(key, now, every.get(key));
                final Long previous = last.put(key, now);
                if (previous != null) {
                    final long allowed = every.get(key) + castTicks + gap + occupyAll + 1;
                    assertTrue(now - previous <= allowed, label + " 的 " + key + " 被 Boss 的招擋住太久：隔了 " + (now - previous)
                            + " tick（最多 " + allowed + "）");
                }
                if (bosses.skill(key.substring(key.indexOf(':') + 1)).kind() == BossSkill.Kind.ORBS) {
                    newestOrb = now;
                } else {
                    busyUntil = now + occupy.get(key);
                }
                continue;
            }
            if (step == BossPacing.Step.ANY) {
                castEnd = now + castTicks;
            }
        }
        for (final String key : keys) {
            assertTrue(last.containsKey(key), label + " 的 " + key + " 一次都沒落");
        }
        assertTrue(casting >= end / 2, label + "：Boss 只有 " + (casting * 100 / end) + "% 的時間在放招（被場地危害擋太多）");
    }

    /** 一招從前搖到放完的 tick（大概：前搖 + 落下、引導、幾波；齊射、光球的尾巴另外算在「還沒完」裡）。 */
    private static long castTicks(final BossSkill skill) {
        double seconds = skill.telegraph();
        switch (skill.kind()) {
            case SLAM -> seconds += skill.delay() + Math.max(0, skill.count() - 1) * skill.stagger();
            case NOVA, CONE -> seconds += Math.max(0, skill.count() - 1) * skill.stagger();
            case BEAM, PULL -> seconds += skill.duration();
            case RING -> seconds += Math.max(0, skill.count() - 1) * skill.stagger()
                    + (skill.reach() - skill.radius()) / Math.max(0.05, skill.speed()) / 20;
            case CHARGE -> seconds += skill.reach() / Math.max(0.05, skill.speed()) / 20;
            case BLINK -> seconds += 0.7;
            case VOLLEY -> seconds += skill.reach() / Math.max(0.05, skill.speed()) / 20;
            case ORBS -> seconds += BossPacing.ORB_SETTLE_TICKS / 20.0;
            case ECHOES -> seconds += Math.max(0, skill.count() - 1) * skill.stagger() + skill.delay();
            case MARK -> seconds += BossPacing.ORB_SETTLE_TICKS / 20.0;
            default -> {
            }
        }
        return Math.max(1, Math.round(seconds * 20));
    }

    /** 場地危害從開始到落下的 tick（光球是放出去後的 2 秒）。 */
    private static long hazardTicks(final BossSkill skill) {
        return switch (skill.kind()) {
            case SLAM -> Math.round((skill.telegraph() + skill.delay() + Math.max(0, skill.count() - 1) * skill.stagger()) * 20);
            case ZONE -> Math.round(skill.telegraph() * 20);
            default -> BossPacing.ORB_SETTLE_TICKS;
        };
    }
}
