package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 照站長「昇華的部分照原作」：三位巨靈與支援火力的砲手是有實體、不會受傷、只聽指令的召喚物；
 * 冥想是受到傷害就中斷的引導（Channelling 的 recharge 模式）。
 */
class AscMinionSystemsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void djinnsAndArtilleryAreCommandOnlyMinions() {
        for (final String id : List.of("foul_sand_spirit", "final_mirage_spirit", "flame_blade_spirit", "covering_fire")) {
            final SkillDefinition definition = config.skill(id);
            assertNotNull(definition, id);
            assertEquals(Mechanic.AURA, definition.mechanic(), id + "：常駐");
            assertNotNull(definition.summon(), id + "：有實體的召喚物");
            assertEquals(1, definition.summon().count(), id);
            assertTrue(definition.summon().fixedMax(), id);
            assertTrue(definition.summon().permanent(), id);
            assertTrue(MinionAbilities.commandOnly(id), id + "：不會受傷、只聽指令");
        }
        assertTrue(config.skill("foul_sand_spirit").summon().flying(), "巨靈飄在空中");
        assertEquals("SKELETON", config.skill("covering_fire").summon().kind());
        assertFalse(MinionAbilities.commandOnly("summon_skeletons"));
        assertFalse(MinionAbilities.commandOnly(null));
    }

    @Test
    void supportingFireCommandsDeathFromAbove() {
        // 照 PoE2 0.5（RePoE DeathFromAboveDaemonMinion／CommandDeathFromAbovePlayer）
        final MinionAbilities.Command command = MinionAbilities.command("covering_fire");
        assertNotNull(command);
        assertEquals("從天而降", command.name());
        assertEquals(10, command.cooldown(), 1e-9);
        assertEquals(8, command.manaAt(1));
        assertEquals(83, command.manaAt(20));
        assertEquals(7.63, MinionAbilities.VOLLEY_DAMAGE, 1e-9);
        assertEquals(6, MinionAbilities.VOLLEYS);
        assertEquals(15, MinionAbilities.VOLLEY_TICKS, "每 0.75 秒一輪");
        assertEquals(tw.exilecore.core.Units.metres(14) * tw.exilecore.core.Units.metres(14), Math.PI * MinionAbilities.VOLLEY_RADIUS * MinionAbilities.VOLLEY_RADIUS, 1.0, "14 × 14 公尺的面積"); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertTrue(MinionAbilities.command("foul_sand_spirit") == null, "巨靈的指令是另外的技能（珂芮絲的詭計…），按巨靈的鍵不是下指令");
    }

    @Test
    void meditateIsAChannelInterruptedByDamage() {
        final ChargeSpec spec = config.charge("calm_mind_trance");
        assertNotNull(spec, "冥想是引導");
        assertEquals(ChargeSpec.Mode.RECHARGE, spec.mode());
        assertTrue(spec.channel().interruptOnHit(), "受到傷害就中斷");
        assertEquals(0, spec.channel().slow(), 1e-9, "2026-10-27 照站長「放技能減速全部移除」：引導中不再變慢（這次之前更少 70%）");
        // 一般的回充還沒開始（剛被打過）：整份（基礎 × 更多）由冥想加
        double[] step = Channelling.meditateStep(50, 100, 0, 1.0, 1.0, false);
        assertEquals(52, step[0], 1e-9);
        // 一般的回充已經在跑：冥想只加「更多」的那份
        step = Channelling.meditateStep(50, 100, 0, 1.0, 1.0, true);
        assertEquals(51, step[0], 1e-9);
        // 滿了：溢出（最多最大的 50%）
        step = Channelling.meditateStep(100, 100, 49, 1.0, 1.0, true);
        assertEquals(100, step[0], 1e-9);
        assertEquals(50, step[1], 1e-9, "溢出最多到最大的 1.5 倍");
        // 其他引導沒有受到傷害中斷
        assertFalse(config.charge("shield_charge") != null && config.charge("shield_charge").channel().interruptOnHit());
    }
}
