package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26 照 PoE2 的引導（Channel）：逐秒扣魔力、衝鋒、持續作用。 */
class ChannellingTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void drainEverySecondFromTheStart() {
        assertTrue(ChargeSpec.drainDue(0), "開始的那一 tick 扣");
        assertFalse(ChargeSpec.drainDue(19));
        assertTrue(ChargeSpec.drainDue(20));
        assertTrue(ChargeSpec.drainDue(40));
        assertFalse(ChargeSpec.drainDue(-1));
    }

    @Test
    void shieldChargeRushesAndScalesWithDistance() {
        final ChargeSpec spec = config.charge("shield_rush");
        assertNotNull(spec);
        assertEquals(ChargeSpec.Mode.RUSH, spec.mode());
        assertTrue(spec.drain());
        assertEquals(1.0, spec.channel().distanceFactor(0), 1e-9);
        assertEquals(1.5, spec.channel().distanceFactor(tw.exilecore.core.Units.metres(4)), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(2.0, spec.channel().distanceFactor(30), 1e-9, "最多更多 100%");
        assertEquals(26, spec.channel().minTicks(), "最短引導 1.3 秒");
        assertEquals(0, config.skill("shield_rush").cooldown(), 1e-9);
    }

    @Test
    void rampageSpendsRageAfterTheFreeWindow() {
        final ChargeSpec spec = config.charge("rampage");
        assertNotNull(spec);
        assertEquals(ChargeSpec.Mode.RUSH, spec.mode());
        assertEquals(0, config.skill("rampage").cooldown(), 1e-9, "做完引導後拿掉暫時保留的冷卻");
        assertEquals(0, spec.channel().rageDue(20));
        assertEquals(0, spec.channel().rageDue(49));
        assertEquals(5, spec.channel().rageDue(50), "2.5 秒後每秒 5 點（300／分鐘）");
        assertEquals(0, spec.channel().rageDue(51));
        assertEquals(5, spec.channel().rageDue(70));
    }

    @Test
    void gatheringStormIsAPerfectTimedChannel() {
        final ChargeSpec spec = config.charge("gathering_storm");
        assertNotNull(spec);
        assertTrue(spec.perfect());
        assertTrue(spec.drain());
        assertEquals(tw.exilecore.core.Units.metres(10), spec.channel().perfectRange(), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(0, config.skill("gathering_storm").cooldown(), 1e-9, "做完引導後拿掉暫時保留的冷卻");
        assertNotNull(config.skill("proc_storm_shockwave"));
    }

    @Test
    void detonatingArrowGainsFirePerStageAndOnlyExplodesWhenFull() {
        final ChargeSpec spec = config.charge("blast_arrow");
        assertNotNull(spec);
        assertEquals(4, spec.stages());
        assertTrue(spec.channel().perAttack());
        assertTrue(spec.channel().explodeAtMax());
        assertEquals("damage.gain.fire", spec.channel().stageStat());
        assertEquals(120, spec.channel().stageValue(), 1e-9);
    }

    @Test
    void witherPulsesWhileHeld() {
        final ChargeSpec spec = config.charge("wither");
        assertNotNull(spec);
        assertEquals(ChargeSpec.Mode.PULSE, spec.mode());
        assertEquals(2.0, config.skill("wither").params().mark().duration(1), 1e-9);
        assertEquals(3.95, config.skill("wither").params().mark().duration(40), 1e-9);
    }

    /** 2026-10-26 照 PoE2 Flame Breath：連續出手的引導、10 秒冷卻、4 秒後每秒 6 點怒氣、用掉銳星強化（怒氣少一半）。 */
    @Test
    void flameBreathStreamsWithCooldownAndRage() {
        final ChargeSpec spec = config.charge("flame_breath");
        assertNotNull(spec);
        assertEquals(ChargeSpec.Mode.STREAM, spec.mode());
        assertEquals(10, config.skill("flame_breath").cooldown(), 1e-9);
        assertEquals(60, spec.channel().minTicks(), "最短 3 秒");
        assertEquals(0, spec.channel().rageDue(79));
        assertEquals(6, spec.channel().rageDue(80), "4 秒後每秒 6 點（360／分鐘）");
        assertEquals(3, spec.channel().rageWhenEmpowered(6), "強化：怒氣花少 50%");
        assertEquals("power", spec.channel().empowerCharge());
        // 2026-10-27：channels() 改成有引導標籤也算（蓄勢的防呆），這裡直接看修飾器有沒有舊的 channel.stages
        assertFalse(config.skill("flame_breath").modifiers().stream()
                .anyMatch(m -> m.stat().equals(tw.exilecore.stats.StatKeys.CHANNEL_STAGES)), "不再是舊的 channel.stages");
    }

    // 2026-10-26：站長把焚燼（scorch_breath）併入焚流，燃料（fuel）一起拿掉；這次之前這裡測焚燼的燃料（incinerateBurnsFuel）

    @Test
    void channelledReleaseSkillsDrainMana() {
        for (final String id : new String[] {"true_strike", "snipe", "bone_storm", "inferno_blast", "supercharged_slam"}) {
            final ChargeSpec spec = config.charge(id);
            assertNotNull(spec, id);
            assertTrue(spec.drain(), id + " 的魔力按住逐秒扣");
        }
    }
}
