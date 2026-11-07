package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.stats.KeystoneKeys;
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
import static org.junit.jupiter.api.Assertions.assertSame;

/** 2026-11-08 照站長「檢查他身上是不是還有一些隱藏的 BUG」：召喚物說明／估算跟真的叫出來的一致。 */
class MinionEstimateFixTest {

    static SkillConfig config;
    static MonsterConfig monsters;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        monsters = new MonsterConfig();
        monsters.load(Path.of("src/main/resources/data"));
    }

    @Test
    void lifeAsEnergyShieldUsesBaseLifeAndEsModifiers() {
        // 照 PoB2：生命的 15% 轉護盾、「獲得最大生命 30% 為額外護盾」都照基礎生命 1000 算 → 450；生命加成不影響護盾，護盾加成兩份都吃
        final SummonSpec spec = config.skill("summon_bone_thundermage").summon();
        final MinionSheets.Base base = new MinionSheets.Base(1000 / spec.life(), 0, 0, 0, 1, 1, 5);
        final StatSheet sheet = MinionSheets.build(spec, base, List.of(
                new Modifier(KeystoneKeys.MINION_LIFE_AS_ES, ModType.FLAT, 30, Set.of(), "品質"),
                Modifier.increased(StatKeys.LIFE_MAX, 1.0, "召喚物生命")));
        assertEquals(1000 * 0.85 * 2, MinionSheets.life(sheet), 1e-6);
        assertEquals(450, MinionSheets.energyShield(sheet), 1e-6);
        sheet.add(Modifier.increased(StatKeys.ES_MAX, 0.5, "召喚物護盾"));
        assertEquals(675, MinionSheets.energyShield(sheet), 1e-6);
    }

    @Test
    void possessedMonsterMultipliers() {
        final SummonSpec spec = config.skill("soul_attach_art").summon();
        final SummonSpec spider = MinionService.spectreSpec(spec, "CAVE_SPIDER", monsters);
        assertEquals(spec.life() * 0.6, spider.life(), 1e-9);
        assertEquals(spec.damage() * 0.7, spider.damage(), 1e-9);
        assertSame(spec, MinionService.ownSpec(spec, "soul_attach_art", null, monsters), "沒有角色資料照技能寫的");
    }

    @Test
    void minionOnlyExtraDamageSaysMinion() {
        assertEquals("召喚物獲得 21% 傷害為額外的火焰傷害",
                StatLines.describe(new Modifier(StatKeys.DAMAGE_EXTRA + Tags.FIRE, ModType.FLAT, 21, Set.of(Tags.MINION), "x")));
        assertEquals("獲得 21% 傷害為額外的火焰傷害",
                StatLines.describe(new Modifier(StatKeys.DAMAGE_EXTRA + Tags.FIRE, ModType.FLAT, 21, Set.of(), "x")));
    }
}
