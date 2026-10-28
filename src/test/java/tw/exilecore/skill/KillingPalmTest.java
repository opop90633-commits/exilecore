package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.monster.MonsterRarity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 Killing Palm（斷魂掌）：衝刺、連衝撲殺、打死給銳星、可撲殺的標記、品質。 */
class KillingPalmTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        tw.exilecore.currency.GemQuality.load(Path.of("src/main/resources/data"));
    }

    private static boolean has(final String stat) {
        return config.skill(KillingPalm.ID).modifiers().stream().anyMatch(m -> m.stat().equals(stat) && m.value() > 0);
    }

    @Test
    void dashRangeIsPoe2BaseMeleeDashRange() {
        assertEquals(6.5, KillingPalm.DASH_RANGE, 1e-9); // base_melee_dash_range 65（0.1 公尺一單位）
    }

    @Test
    void cullableOnlyUnderThreshold() {
        assertTrue(KillingPalm.cullable(35, 100, 0.35));
        assertTrue(KillingPalm.cullable(4, 100, 0.05));
        assertFalse(KillingPalm.cullable(36, 100, 0.35));
        assertFalse(KillingPalm.cullable(1, 100, 0)); // 不是怪物
        assertFalse(KillingPalm.cullable(0, 100, 0.35)); // 已經死了
    }

    @Test
    void powerChargesByRarity() {
        assertEquals(1, KillingPalm.powerCharges(MonsterRarity.NORMAL));
        assertEquals(1, KillingPalm.powerCharges(MonsterRarity.MAGIC));
        assertEquals(2, KillingPalm.powerCharges(MonsterRarity.RARE));
        assertEquals(3, KillingPalm.powerCharges(MonsterRarity.UNIQUE));
    }

    @Test
    void cullIsRememberedOnlyForThatTickAndTarget() {
        final UUID player = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        KillingPalm.noteCull(player, target, 100);
        assertTrue(KillingPalm.culled(player, target, 100));
        assertFalse(KillingPalm.culled(player, target, 101));
        assertFalse(KillingPalm.culled(player, UUID.randomUUID(), 100));
        assertFalse(KillingPalm.culled(UUID.randomUUID(), target, 100));
    }

    @Test
    void shuffleKeepsAllCandidates() {
        final List<Integer> list = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6));
        KillingPalm.shuffle(list, new Random(7));
        assertEquals(6, list.size());
        assertEquals(new HashSet<>(List.of(1, 2, 3, 4, 5, 6)), new HashSet<>(list));
    }

    @Test
    void skillDataMatchesPoe2() {
        final SkillDefinition palm = config.skill(KillingPalm.ID);
        assertNotNull(palm);
        assertEquals(Mechanic.STRIKE, palm.mechanic());
        assertTrue(has("flag.culling"));
        assertTrue(has("charge.event.kill.power"), "打死給銳星（不是只有撲殺）");
        assertFalse(has("charge.event.cull.power"), "撲殺本身不另外給（不然撲殺打死會給兩次）");
        assertTrue(has("flag.always_hit"), "不會被閃避");
        assertEquals(0.75, palm.attackSpeed(), 1e-9);
        assertEquals(0.9, palm.effectiveness().at(1), 1e-9);
        assertEquals(2.81, palm.effectiveness().at(20), 1e-9);
        assertEquals(6, palm.mana().at(1), 1e-9);
        assertEquals(42, palm.mana().at(20), 1e-9);
        assertTrue(palm.tags().containsAll(List.of("attack", "melee", "strike", "area")));
    }

    @Test
    void qualityRecoversManaOnCull() {
        final String text = SkillService.qualityText(config.skill(KillingPalm.ID), 20);
        assertNotNull(text);
        assertTrue(text.contains("4% 最大魔力"), text);
        assertTrue(text.contains("4 秒內"), "2026-10-28 照 PoE2：4 秒內回復，不是一次回：" + text);
    }

    @Test
    void qualityRecoveryIsOverFourSecondsNotInstant() {
        // 一般品質：撲殺時 4 秒內回復 (0—4)% 最大魔力；進階奇術：4 秒內回復 (0—10)% 最大生命（RePoE／poe2db）
        final double mana = SkillService.qualityModifiers(config.skill(KillingPalm.ID), 20).stream()
                .filter(m -> m.stat().equals(tw.exilecore.stats.StatKeys.MANA_ON_CULL_OVER_TIME)).mapToDouble(m -> m.value()).sum();
        assertEquals(4, mana, 1e-9);
        assertEquals(0, SkillService.qualityModifiers(config.skill(KillingPalm.ID), 20).stream()
                .filter(m -> m.stat().equals(tw.exilecore.stats.StatKeys.MANA_ON_CULL)).count(), "不再一次回");
        final double life = tw.exilecore.currency.GemQuality.advancedModifiers(KillingPalm.ID, 20, "q").stream()
                .filter(m -> m.stat().equals(tw.exilecore.stats.StatKeys.LIFE_ON_CULL_OVER_TIME)).mapToDouble(m -> m.value()).sum();
        assertEquals(10, life, 1e-9);
        // 分成 16 次、每 5 tick 一次，合計 80 tick = 4 秒，總量剛好
        assertEquals(4.0, KillingPalm.RECOVERY_SECONDS, 1e-9);
        assertEquals(80, KillingPalm.RECOVERY_PULSES * KillingPalm.RECOVERY_PERIOD_TICKS);
        double sum = 0;
        for (int i = 0; i < KillingPalm.RECOVERY_PULSES; i++) {
            sum += KillingPalm.perPulse(37);
        }
        assertEquals(37, sum, 1e-9);
        assertEquals(0, KillingPalm.perPulse(0), 1e-9);
        assertEquals(0, KillingPalm.perPulse(-5), 1e-9);
    }

    @Test
    void chainStepUsesAttackTime() {
        // 每一掌是一次攻擊：照攻擊時間（已經吃攻擊速度），不是固定 0.2 秒
        assertEquals(19, KillingPalm.chainStepTicks(19.05));
        assertEquals(10, KillingPalm.chainStepTicks(9.6));
        assertEquals(1, KillingPalm.chainStepTicks(0.3)); // 攻速再快也至少隔 1 tick
        assertEquals(1, KillingPalm.chainStepTicks(0));
        assertEquals(1, KillingPalm.chainStepTicks(Double.NaN));
        assertTrue(KillingPalm.chainStepTicks(30) > KillingPalm.chainStepTicks(15), "攻速越快連衝越快");
    }

    @Test
    void chainHasNoFixedCap() {
        // 2026-10-28 照 PoE2：拿掉 40 掌的上限、0.2 秒的固定間隔（連衝靠「每一掌都要撲殺一隻沒打過的」自然結束）
        for (final String gone : List.of("MAX_CHAIN", "CHAIN_STEP_TICKS")) {
            assertFalse(java.util.Arrays.stream(KillingPalm.class.getDeclaredFields()).anyMatch(f -> f.getName().equals(gone)), gone);
        }
    }
}
