package tw.exilecore.combat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.skill.ModifierText;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-11 照站長「偷到的怪物詞綴照原作補（PoE2）」：怪物專用的屬性換成玩家版、行為詞綴掛旗標。 */
class StolenModsTest {

    static MonsterConfig monsters;

    @BeforeAll
    static void load() throws IOException {
        monsters = new MonsterConfig();
        monsters.load(Path.of("src/main/resources/data"));
    }

    private static List<Modifier> of(final String id) {
        final MonsterConfig.MonsterMod mod = monsters.mod(id);
        assertNotNull(mod, id);
        return StolenMods.modifiers(mod, "test");
    }

    @Test
    void everyEffectIdIsARealMod() {
        for (final String id : StolenMods.EFFECTS.keySet()) {
            assertNotNull(monsters.mod(id), id);
        }
    }

    @Test
    void noMonsterOnlyStatReachesPlayers() {
        for (final MonsterConfig.MonsterMod mod : monsters.mods()) {
            for (final Modifier modifier : StolenMods.modifiers(mod, "test")) {
                assertFalse(modifier.stat().startsWith("monster."), mod.id() + " " + modifier.stat());
            }
        }
    }

    @Test
    void regeneratesTwoPercent() {
        final List<Modifier> list = of("regenerating");
        assertTrue(list.stream().anyMatch(m -> m.stat().equals(StatKeys.LIFE_REGEN_PERCENT) && m.value() > 0), list.toString());
    }

    @Test
    void shieldGivesEsFromLife() {
        final List<Modifier> list = of("shielded");
        assertTrue(list.stream().anyMatch(m -> m.stat().equals(tw.exilecore.item.unique.UniqueStats.ES_FROM_LIFE) && m.value() > 0), list.toString());
    }

    @Test
    void powerfulMinionsBuffsMinions() {
        final MonsterConfig.MonsterMod mod = monsters.mods().stream()
                .filter(m -> m.mods().stream().anyMatch(a -> a.toModifier(java.util.Map.of(), "").stat().equals(StatKeys.MONSTER_POWERFUL_MINIONS)))
                .findFirst().orElseThrow();
        final List<Modifier> list = StolenMods.modifiers(mod, "test");
        assertTrue(list.stream().anyMatch(m -> m.stat().equals(StatKeys.LIFE_MAX) && m.type() == ModType.INCREASED
                && m.tags().contains(Tags.MINION) && Math.abs(m.value() - 0.5) < 1e-9));
        assertTrue(list.stream().anyMatch(m -> m.stat().equals(StatKeys.DAMAGE) && m.tags().contains(Tags.MINION)
                && Math.abs(m.value() - 0.25) < 1e-9));
    }

    @Test
    void mechanicModsGetAFlagWithTheirValue() {
        assertEquals(3.6, flag(of("hindering"), "hindering"), 1e-9);
        assertEquals(5, flag(of("proximal"), "proximal"), 1e-9);
        assertEquals(202, flag(of("empowering_volatile_plants"), "empowering_volatile_plants"), 1e-9);
        assertEquals(1, flag(of("fire_unleasher"), "fire_unleasher"), 1e-9); // 只有招式：數值寫 1
        assertEquals(1, flag(of("shroud_walker"), "shroud_walker"), 1e-9);
        // 雷暴的：本來的抗性照樣有
        assertTrue(of("stormcaller").stream().anyMatch(m -> m.stat().equals("resist.lightning")));
    }

    @Test
    void flagsHaveText() {
        final Modifier modifier = Modifier.flat(StolenMods.FLAG_PREFIX + "soul_eater", 1, "test");
        assertTrue(ModifierText.describe(modifier).contains("吞魂"));
    }

    private static double flag(final List<Modifier> list, final String id) {
        return list.stream().filter(m -> m.stat().equals(StolenMods.FLAG_PREFIX + id)).mapToDouble(Modifier::value).sum();
    }
}
