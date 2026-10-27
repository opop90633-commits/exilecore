package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.TreeEffectKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-26 照 PoE2：移動中使用技能的移動速度懲罰（SkillMovePenalty）。
 * 2026-10-27 照站長「放技能減速全部移除」：放技能、引導、舉盾都不再變慢（下面的純函式還在，只是沒人套用；見 {@link #castingNoLongerSlowsWalking}）。
 */
class SkillMovePenaltyTest {

    @Test
    void baseFollowsPoe2() {
        assertEquals(0.70, SkillMovePenalty.base(Mechanic.PROJECTILE, false, false, false), 1e-9, "大部分技能：更少 70%");
        assertEquals(0.70, SkillMovePenalty.base(Mechanic.STRIKE, false, false, false), 1e-9, "近戰也照 70%（原作會停下來）");
        assertEquals(0.50, SkillMovePenalty.base(Mechanic.ZONE, true, false, false), 1e-9, "召喚物指令：更少 50%");
        assertEquals(0, SkillMovePenalty.base(Mechanic.DASH, false, false, false), 1e-9, "位移技能不算");
        assertEquals(0, SkillMovePenalty.base(Mechanic.LEAP, false, false, false), 1e-9);
        assertEquals(0, SkillMovePenalty.base(Mechanic.AURA, false, false, false), 1e-9, "光環開關不算");
        assertEquals(0, SkillMovePenalty.base(Mechanic.GUARD, false, false, false), 1e-9, "舉盾自己有減速");
        assertEquals(0, SkillMovePenalty.base(Mechanic.PROJECTILE, false, false, true), 1e-9, "騎著坐騎：原作 -100% 懲罰");
    }

    @Test
    void reductionsMultiply() {
        final StatSheet sheet = new StatSheet();
        assertEquals(0.70, SkillMovePenalty.penalty(sheet, 0.70, Set.of()), 1e-9);
        // 天賦樹：減少 5% ＋ 減少 3%
        sheet.add(Modifier.increased(TreeEffectKeys.ACTION_PENALTY, -0.05, "樹"));
        sheet.add(Modifier.increased(TreeEffectKeys.ACTION_PENALTY, -0.03, "樹"));
        assertEquals(0.70 * 0.92, SkillMovePenalty.penalty(sheet, 0.70, Set.of()), 1e-9);
        // 衝刺突擊：更少 30%；鑲嵌的寶石（綠）：更少 40%
        sheet.add(Modifier.more(TreeEffectKeys.ACTION_PENALTY, -0.30, "衝刺突擊"));
        sheet.add(Modifier.more(TreeEffectKeys.ACTION_PENALTY, -0.40, "鑲嵌的寶石"));
        assertEquals(0.70 * 0.92 * 0.7 * 0.6, SkillMovePenalty.penalty(sheet, 0.70, Set.of()), 1e-9);
        // 舉盾、引導用的也一起算（同一個屬性）
        assertEquals(0.50 * 0.92 * 0.7 * 0.6, TreeEffectKeys.actionPenalty(sheet, 0.50), 1e-9);
    }

    @Test
    void deliberationMakesItWorseAndClamps() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.more(TreeEffectKeys.ACTION_PENALTY, 0.30, "凝息"));
        assertEquals(0.91, SkillMovePenalty.penalty(sheet, 0.70, Set.of()), 1e-9, "凝息：更多 30% → 91%");
        sheet.add(Modifier.increased(TreeEffectKeys.ACTION_PENALTY, 1.0, "測試"));
        assertEquals(1.0, SkillMovePenalty.penalty(sheet, 0.70, Set.of()), 1e-9, "最多停住，不會倒退");
        assertEquals(0, SkillMovePenalty.penalty(sheet, 0, Set.of()), 1e-9, "不算的技能還是 0");
    }

    /** 2026-10-27 照站長「放技能減速全部移除」：放技能不再改變走路速度（不呼叫 apply、引導與舉盾不掛移動速度的「更少」）。 */
    @Test
    void castingNoLongerSlowsWalking() throws IOException {
        final String service = Files.readString(Path.of("src/main/java/tw/exilecore/skill/SkillService.java"));
        assertFalse(service.contains("movePenalty.apply("), "放技能不再套用 SkillMovePenalty");
        assertTrue(service.contains("movePenalty.tick(") && service.contains("movePenalty.forget("), "tick／forget 留著清舊的慢速");
        assertFalse(service.contains("ConditionKeys.GUARD_SLOW") || service.contains("TreeEffectKeys.actionPenalty("), "舉盾（招架）不再變慢");
        final String channelling = Files.readString(Path.of("src/main/java/tw/exilecore/skill/Channelling.java"));
        assertFalse(channelling.contains("MOVEMENT_SPEED,"), "引導（凋蝕術、熾息、冥想）與架盾不再掛移動速度的「更少」");
        assertFalse(channelling.contains("\"channel_slow\""), "不再給「引導中」減速");
        // 資料：沒有技能的引導再寫減速，也沒有效果再用「移動中使用技能的移動速度懲罰」
        final String skills = Files.readString(Path.of("src/main/resources/data/skills.yml"));
        assertFalse(skills.matches("(?s).*charge: \\{[^}\n]*slow: [1-9].*"), "skills.yml 的 charge 不再寫 slow");
        for (final String file : new String[] {"supports.yml", "ascendancy.yml", "uniques.yml", "passives/tree.json"}) {
            final String data = Files.readString(Path.of("src/main/resources/data/" + file));
            assertFalse(data.contains("stat: movement.action_penalty") || data.contains("\"stat\":\"movement.action_penalty\""), file + " 不再用移動懲罰");
            assertFalse(data.contains("stat: guard.slow") || data.contains("\"stat\":\"guard.slow\""), file + " 不再用舉盾減速");
        }
    }
}
