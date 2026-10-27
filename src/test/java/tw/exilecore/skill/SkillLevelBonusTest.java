package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 傳奇裝備的「+x 技能等級」：照技能標籤、可以超過 20 級，法術基礎傷害每級多 extra_level_damage。 */
class SkillLevelBonusTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
    }

    private static StatSheet levels(final double value, final String... tags) {
        return new StatSheet().add(new Modifier(StatKeys.SKILL_LEVEL, ModType.FLAT, value, Set.of(tags), "傳奇"));
    }

    private static SkillBuild build(final String skill, final int gemLevel, final int characterLevel, final StatSheet sheet) {
        return SkillBuild.of(config, config.skill(skill), gemLevel, characterLevel, false, List.of(), Set.of(), sheet);
    }

    @Test
    void bonusLevelsFollowTheSkillTagsAndGoPastTwenty() {
        final SkillBuild plain = build("fireball", 20, 100, new StatSheet());
        assertEquals(20, plain.level());
        assertEquals(0, plain.bonusLevel());

        final SkillBuild spell = build("fireball", 20, 100, levels(1, Tags.SPELL));
        assertEquals(21, spell.level(), "+1 法術技能等級：20 級的寶石以 21 級施放");
        assertEquals(1, spell.bonusLevel());
        assertEquals(20, spell.gemLevel());
        assertFalse(spell.levelCapped());

        assertEquals(22, build("fireball", 20, 100, levels(2, Tags.SPELL, Tags.FIRE)).level(), "+2 火焰法術技能等級");
        assertEquals(20, build("spark", 20, 100, levels(2, Tags.SPELL, Tags.FIRE)).level(), "火花是閃電，不吃火焰法術的等級");
        assertEquals(21, build("boneshatter", 20, 100, levels(1)).level(), "沒寫標籤 = 全部技能");
        assertEquals(20, build("boneshatter", 20, 100, levels(1, Tags.SPELL)).level(), "攻擊不吃法術技能等級");
        assertEquals(30, build("fireball", 20, 100, levels(10)).level());
        assertEquals(SkillBuild.MAX_EFFECTIVE_LEVEL, build("fireball", 20, 100, levels(30)).level(), "最高 40 級（2026-10）");

        // 普攻不吃
        final SkillBuild basic = SkillBuild.of(config, config.skill(SkillConfig.DEFAULT_WAND), 1, 100, true, List.of(), Set.of(Tags.WAND),
                levels(3));
        assertEquals(1, basic.level());
        assertEquals(0, basic.bonusLevel());
    }

    @Test
    void bonusLevelsStackOnTopOfTheCharacterLevelCap() {
        final int cap = config.maxLevelFor(70);
        assertTrue(cap < SkillLoadout.MAX_LEVEL, "70 級還用不了 20 級的寶石");
        final SkillBuild build = build("fireball", 20, 70, levels(1, Tags.SPELL));
        assertEquals(cap + 1, build.level(), "先照角色能用的最高等級，再加裝備的等級");
        assertTrue(build.levelCapped(), "寶石本身還是被角色等級限制住");
    }

    @Test
    void spellDamageAndScaledValuesKeepGrowingPastTwenty() {
        // 2026-10-26：照 PoE2 逐級表的法術（熾星彈）超過 20 級直接查表：22 級 286–429
        final double twentyTwo = build("fireball", 20, 100, levels(2, Tags.SPELL)).profile().spellMax().total();
        assertEquals(429, twentyTwo, 1e-6, "22 級照 PoE2 的表");
        assertEquals(1.0 + SkillConfig.DEFAULT_EXTRA_LEVEL_DAMAGE / 100.0, config.extraLevelGrowth(), 1e-9);

        // 攻擊的傷害效益：碎脊擊照 PoE2 逐級表（20 級 312%、21 級 327%）
        final double effectiveness20 = build("boneshatter", 20, 100, new StatSheet()).profile().effectiveness();
        final double effectiveness21 = build("boneshatter", 20, 100, levels(1)).profile().effectiveness();
        assertEquals(3.12, effectiveness20, 1e-9);
        assertEquals(3.27, effectiveness21, 1e-9);
        assertTrue(config.skill("boneshatter").effectiveness().isTable());

        // 兩端內插的寫法照 1 → 20 級的趨勢往上加
        assertEquals(2 + 1 / 19.0, new Scaled(1, 2).at(21), 1e-9);
        // 逐級表比等級短的照最後兩級的比例往外推
        assertEquals(8, Scaled.table(new double[] {1, 2, 4}).at(4), 1e-9);

        // 往外推不會把正的數值推成負的
        assertEquals(0, new Scaled(1, 0).at(SkillBuild.MAX_EFFECTIVE_LEVEL), 1e-9);
        assertEquals(-1 - 5 / 19.0, new Scaled(0, -1).at(25), 1e-9, "本來就是負的照推");
        assertEquals(2, new Scaled(1, 2).at(20), 1e-9);
        assertEquals(2, new Scaled(1, 2).at(SkillLoadout.MAX_LEVEL), 1e-9);
    }
}
