package tw.exilecore.skill;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5 Lightning Rod（雷樁矢）：箭插在地上、閃電雷束連鎖過去再炸。 */
class LightningRodTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        tw.exilecore.currency.GemQuality.load(Path.of("src/main/resources/data"));
    }

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private static LightningRods.Rod rod(final double x, final long now) {
        final SkillConfig.RodSpec spec = config.rod("lightning_rod");
        return new LightningRods.Rod(OWNER, WORLD, new Vector(x, 64, 0), now, now + Math.round(spec.duration() * 20), spec.chains(),
                Math.round(spec.charge() * 20), null, 2.0, "lightning_arrow");
    }

    @Test
    void skillDataMatchesPoe2() {
        final SkillDefinition rod = config.skill("lightning_rod");
        assertTrue(rod.tags().contains(Tags.DURATION));
        assertTrue(rod.tags().contains(Tags.LIGHTNING));
        assertTrue(rod.tags().contains(Tags.PROJECTILE));
        final SkillConfig.RodSpec spec = config.rod("lightning_rod");
        assertNotNull(spec);
        assertEquals(20, spec.duration(), 1e-9);
        assertEquals(10, spec.max());
        assertEquals(8, spec.chains());
        assertEquals(1.0, spec.charge(), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(2.0), rod.params().radius().at(1), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertNull(config.rod("lightning_arrow"));
        assertFalse(rod.description().contains("需要程式"));
    }

    @Test
    void embedsUpToTenAndExpiresAfterTwentySeconds() {
        final LightningRods rods = new LightningRods();
        final List<LightningRods.Rod> removed = new ArrayList<>();
        final LightningRods.Rod first = rods.place(rod(0, 0), 10, removed::add);
        for (int i = 1; i < 10; i++) {
            rods.place(rod(i, i), 10, removed::add);
        }
        assertEquals(10, rods.count(OWNER));
        assertTrue(removed.isEmpty());
        rods.place(rod(10, 10), 10, removed::add);
        assertEquals(10, rods.count(OWNER), "最多 10 支");
        assertEquals(List.of(first), removed, "多的先拿掉最舊的");

        final LightningRods solo = new LightningRods();
        final List<LightningRods.Rod> gone = new ArrayList<>();
        solo.place(rod(0, 0), 10, gone::add);
        solo.tick(399, r -> true, r -> { }, gone::add);
        assertEquals(1, solo.count(OWNER));
        solo.tick(400, r -> true, r -> { }, gone::add);
        assertEquals(0, solo.count(OWNER), "20 秒後消失");
        assertEquals(1, gone.size());

        final LightningRods offline = new LightningRods();
        offline.place(rod(0, 0), 10, r -> { });
        offline.tick(1, r -> false, r -> { }, r -> { });
        assertEquals(0, offline.count(OWNER), "主人離線、換世界就拿掉");
    }

    @Test
    void chainingChargesOneSecondThenBurstsAndEightChainsUseItUp() {
        final LightningRods rods = new LightningRods();
        final LightningRods.Rod rod = rods.place(rod(0, 0), 10, r -> { });
        assertSame(rod, rods.nearest(OWNER, WORLD, new Vector(3, 64, 0), 5, Set.of(), null));
        assertNull(rods.nearest(OWNER, WORLD, new Vector(30, 64, 0), 5, Set.of(), null), "太遠");
        assertNull(rods.nearest(UUID.randomUUID(), WORLD, new Vector(3, 64, 0), 5, Set.of(), null), "別人的箭不算");
        assertNull(rods.nearest(OWNER, WORLD, new Vector(3, 64, 0), 5, Set.of(rod), null), "同一道連鎖連過的不再連");

        final List<LightningRods.Rod> bursts = new ArrayList<>();
        assertTrue(rods.chainTo(rod, 100, 0));
        assertEquals(1, rod.chained);
        rods.tick(119, r -> true, bursts::add, r -> { });
        assertTrue(bursts.isEmpty(), "充能中");
        rods.tick(120, r -> true, bursts::add, r -> { });
        assertEquals(1, bursts.size(), "充能 1 秒後炸");

        for (int i = 0; i < 7; i++) {
            assertTrue(rods.chainTo(rod, 130, 0));
        }
        assertEquals(8, rod.chained);
        assertTrue(rod.spent);
        assertFalse(rods.chainTo(rod, 131, 0), "連 8 次之後不能再連");
        assertNull(rods.nearest(OWNER, WORLD, new Vector(0, 64, 0), 5, Set.of(), null));
        assertEquals(1, rods.count(OWNER), "排好的還沒炸完先留著");
        final List<LightningRods.Rod> removed = new ArrayList<>();
        rods.tick(150, r -> true, bursts::add, removed::add);
        assertEquals(8, bursts.size(), "排好的 7 次都炸");
        assertEquals(List.of(rod), removed, "炸完就消失");
        assertEquals(0, rods.count(OWNER));
    }

    @Test
    void lightningArrowBeamsBurstAfterPointOneSecondAtMostEveryPointOneSecond() {
        final StatSheet sheet = new StatSheet();
        final SkillBuild arrow = SkillBuild.of(config, config.skill("lightning_arrow"), 1, 1, false, List.of(), Set.of(Tags.BOW), sheet);
        assertEquals(0.1, arrow.profile().effective(sheet).flat(LightningRods.BURST_DELAY, arrow.profile().tags()), 1e-9);
        assertTrue(SkillZones.chainsToRods(arrow.profile()), "雷鳴箭的雷束是閃電");
        final SkillBuild arc = SkillBuild.of(config, config.skill("arc"), 1, 1, false, List.of(), Set.of(), sheet);
        assertTrue(SkillZones.chainsToRods(arc.profile()), "鏈雷的連鎖可以連到箭");
        assertEquals(0, arc.profile().effective(sheet).flat(LightningRods.BURST_DELAY, arc.profile().tags()), 1e-9, "鏈雷照箭自己的 1 秒");
        final SkillBuild blood = SkillBuild.of(config, config.skill("exsanguinate"), 1, 1, false, List.of(), Set.of(), sheet);
        assertFalse(SkillZones.chainsToRods(blood.profile()), "不是閃電的連鎖不算");

        final LightningRods rods = new LightningRods();
        final LightningRods.Rod rod = rods.place(rod(0, 0), 10, r -> { });
        final List<LightningRods.Rod> bursts = new ArrayList<>();
        rods.chainTo(rod, 100, 0.1);
        rods.chainTo(rod, 100, 0.1); // 同一 tick 兩道雷束
        rods.tick(101, r -> true, bursts::add, r -> { });
        assertTrue(bursts.isEmpty());
        rods.tick(102, r -> true, bursts::add, r -> { });
        assertEquals(1, bursts.size(), "0.1 秒就炸");
        rods.tick(103, r -> true, bursts::add, r -> { });
        assertEquals(1, bursts.size());
        rods.tick(104, r -> true, bursts::add, r -> { });
        assertEquals(2, bursts.size(), "同一支箭最快每 0.1 秒一次");
        assertEquals(2, rod.chained, "每道都算一次連鎖");
    }

    @Test
    void qualityGivesExtraBurstChanceAndAdvancedAttackSpeed() {
        final List<Modifier> mods = tw.exilecore.currency.GemQuality.modifiers("lightning_rod", 20, "品質");
        assertEquals(20, mods.stream().filter(m -> m.stat().equals(LightningRods.EXTRA_BURST)).mapToDouble(Modifier::value).sum(), 1e-9);
        assertEquals(0, mods.stream().filter(m -> m.stat().equals(StatKeys.DAMAGE)).count(), "不再是通用的增加傷害");
        final List<Modifier> advanced = tw.exilecore.currency.GemQuality.advancedModifiers("lightning_rod", 20, "進階奇術");
        assertEquals(0.2, advanced.stream().filter(m -> m.stat().equals(StatKeys.ATTACK_SPEED)).mapToDouble(Modifier::value).sum(), 1e-9);
        assertFalse(ModifierText.describe(mods.getFirst()).startsWith("特殊效果"));
        assertTrue(ModifierText.describe(Modifier.flat(LightningRods.BURST_DELAY, 0.1, "雷鳴箭")).contains("0.1 秒"));

        final LightningRods rods = new LightningRods();
        final LightningRods.Rod rod = rods.place(rod(0, 0), 10, r -> { });
        rods.extraBurst(rod, 0);
        final List<LightningRods.Rod> bursts = new ArrayList<>();
        rods.tick(LightningRods.EXTRA_BURST_TICKS, r -> true, bursts::add, r -> { });
        assertEquals(1, bursts.size(), "品質多炸的一次");
        assertEquals(0, rod.chained, "不算連鎖");
    }
}
