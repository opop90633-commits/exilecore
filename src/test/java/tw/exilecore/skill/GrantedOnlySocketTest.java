package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.item.ItemRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 照 PoE2 0.5：武器（法杖、長杖、權杖、矛）、昇華天賦賦予的技能沒有寶石（RePoE skill_gems 的 crafting_level 0），
 * 雕不出來，也不能插進星咒圖騰、引星（祈喚）或任何主要寶石；主要寶石保留的精魂照 RePoE skills 的 reservations。
 */
class GrantedOnlySocketTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;
    static ItemRepository items;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        items = new ItemRepository();
        items.load(DATA);
    }

    @Test
    void everyWeaponGrantedSkillIsGrantedOnly() {
        // RePoE 0.5：法杖、長杖、權杖賦予的技能（含烈陽珠 Solar Orb）都是 crafting_level 0，沒有寶石
        for (final String id : items.grantedSkills().values()) {
            final SkillDefinition definition = config.skill(id);
            assertNotNull(definition, id);
            assertTrue(definition.grantedOnly(), id + " 是武器賦予的技能，照 PoE2 沒有寶石");
            assertFalse(definition.engravable(), id);
        }
        assertTrue(config.skill("spear_throw").grantedOnly(), "擲矛：每把矛都賦予");
        assertTrue(config.skill("time_freeze").grantedOnly(), "昇華賦予");
        assertTrue(config.skill("fire_spell_on_hit").grantedOnly(), "昇華賦予");
        // 有寶石、傳奇也賦予的（游雷、掠閃、凋息）照樣是寶石
        for (final String id : List.of("spark", "blink", "withering_presence", "fireball")) {
            assertFalse(config.skill(id).grantedOnly(), id);
        }
        for (final SkillDefinition definition : config.engravable(SkillLoadout.GEM_CAP)) {
            assertFalse(definition.grantedOnly(), definition.id() + " 雕得出來卻是賦予的技能");
        }
    }

    @Test
    void grantedOnlySpellsCannotGoIntoAnyMetaGem() {
        int metas = 0;
        for (final SkillDefinition host : config.all()) {
            final MetaSpec spec = config.meta(host.id());
            if (spec == null) {
                continue;
            }
            metas++;
            for (final SkillDefinition spell : config.all()) {
                if (spell.grantedOnly()) {
                    assertNotNull(MetaGems.problem(spec, spell), spell.id() + " 不能插進 " + host.id());
                }
            }
        }
        assertTrue(metas >= 10, "星咒圖騰、引星、祈喚、咒擲、命中引火術都算");
        // 使用者回報的：星咒圖騰、祈喚（元祈、咒擲）
        for (final String host : List.of("totem_casting", "meta_elemental_invocation", "meta_reaper_invocation", "meta_barrier_invocation",
                "spellslinger", "meta_crit")) {
            for (final String spell : List.of("firebolt", "chaos_bolt", "freezing_shards", "lightning_bolt", "sun_orb", "volatile_dead")) {
                assertNotNull(MetaGems.problem(config.meta(host), config.skill(spell)), spell + " → " + host);
            }
            assertNull(MetaGems.problem(config.meta(host), config.skill("fireball")), "一般的法術寶石照樣能插 " + host);
        }
        assertEquals(MetaGems.GRANTED_ONLY, MetaGems.problem(config.meta("meta_elemental_invocation"), config.skill("firebolt")));
        assertEquals(MetaGems.GRANTED_ONLY, MetaGems.problem(config.meta("totem_casting"), config.skill("firebolt")));
    }

    @Test
    void freeCastsOfGrantedSpellsStillWork() {
        // 命運同調、預告射擊用 metaCast 免費放面板上的法術：武器賦予的也可以（只擋插槽，不擋觸發）
        final MetaSpec any = new MetaSpec(MetaSpec.Kind.CRIT, 1, Scaled.of(0), 0, 0, 0, 0, 0, Scaled.of(1), 0);
        assertNull(MetaGems.castProblem(any, config.skill("firebolt")));
        assertNull(MetaGems.castProblem(any, config.skill("chaos_bolt")));
    }

    @Test
    void oldGemsOfGrantedOnlySkillsAreRecognised() {
        // 以前管理指令給的、雕出來的烈陽珠寶石（不是賦予的那一格）要換成未切割技能寶石；面板上賦予的那一格不動
        assertTrue(GemService.grantedOnlyGem(config, new SkillLoadout.Entry("sun_orb", 12)));
        assertTrue(GemService.grantedOnlyGem(config, new SkillLoadout.Entry("firebolt", 5)));
        assertFalse(GemService.grantedOnlyGem(config, new SkillLoadout.Entry("firebolt", 5).withGranted(true)));
        assertFalse(GemService.grantedOnlyGem(config, new SkillLoadout.Entry("fireball", 5)));
        assertFalse(GemService.grantedOnlyGem(config, null));
        // 放在引星孔裡的（存檔的 @代號/等級/孔數）讀出來不是賦予的，也認得
        final SkillLoadout.Entry host = new SkillLoadout.Entry("meta_elemental_invocation", 10)
                .withSpell(SkillLoadout.Entry.INNER_SOCKET, new SkillLoadout.Entry("firebolt", 8));
        assertTrue(GemService.grantedOnlyGem(config, host.spellAt(SkillLoadout.Entry.INNER_SOCKET)));
    }

    @Test
    void metaGemSpiritReservationFollowsPoe2() {
        // RePoE skills.json static.reservations（PoE2 0.5）：Spell Totem 沒有（每次放下花魔力、消耗銳星／磐星）、
        // Cast on Critical／Elemental Ailment／Dodge 100、Cast on Minion Death 30、Elemental Invocation 60、
        // Reaper's／Barrier Invocation 30、Spellslinger 與 Fire Spell on Hit 沒有
        assertNull(config.skill("totem_casting").aura(), "星咒圖騰不保留精魂");
        assertTrue(config.skill("totem_casting").mana().first() > 0, "星咒圖騰放下時花魔力");
        final Map<String, Double> expected = Map.of(
                "meta_crit", 100.0, "meta_ailment", 100.0, "meta_dodge", 100.0, "meta_minion_death", 30.0,
                "meta_elemental_invocation", 60.0, "meta_reaper_invocation", 30.0, "meta_barrier_invocation", 30.0,
                "spellslinger", 0.0, "fire_spell_on_hit", 0.0);
        for (final Map.Entry<String, Double> entry : expected.entrySet()) {
            final SkillDefinition definition = config.skill(entry.getKey());
            assertNotNull(definition.aura(), entry.getKey() + " 是常駐（綁在按鍵格上才開、保留精魂）");
            assertEquals(entry.getValue(), definition.aura().reserve(), 1e-9, entry.getKey());
        }
    }
}
