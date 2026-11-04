package tw.exilecore.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

/**
 * 2026-11-04 照站長「融解漩渦、妄想症、掙扎吶喊、嗓音、信仰稜鏡都做出來」「血脈輔助照原作改成一個角色一顆」：純規則的部分。
 */
class EventUniqueMechanicsTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static ItemGenerator generator;
    static tw.exilecore.skill.SkillConfig skills;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        generator = new ItemGenerator(repository);
        skills = new tw.exilecore.skill.SkillConfig();
        skills.load(DATA); // 也接上信仰稜鏡的技能候選（UniquePicks）
    }

    @Test
    void lineageSupportsAreOnePerCharacterAndSolusIpseAddsTwo() {
        final tw.exilecore.skill.SupportDefinition lineage = skills.supports().stream()
                .filter(tw.exilecore.skill.SupportDefinition::lineage).findFirst().orElseThrow();
        assertTrue(skills.oncePerCharacter(lineage.id()));
        assertEquals(1, skills.supportCopies(lineage.id(), new StatSheet()));
        final StatSheet solus = new StatSheet();
        solus.add(Modifier.flat(tw.exilecore.item.unique.UniqueStats.LINEAGE_EXTRA_COPIES, 2, "唯我論"));
        assertEquals(3, skills.supportCopies(lineage.id(), solus), "唯我論：不同技能再多 2 顆");
        final tw.exilecore.skill.SupportDefinition plain = skills.supports().stream()
                .filter(s -> !s.lineage() && !s.spiritSupport()).findFirst().orElseThrow();
        assertEquals(Integer.MAX_VALUE, skills.supportCopies(plain.id(), solus), "一般輔助照舊不限");
    }

    @Test
    void strugglescreamHoldsFourInstilsAndRotates() {
        final ItemData amulet = generator.generateUnique(repository.unique("strugglescream_amulet"), 70, new Random(1));
        assertEquals(4, ItemStats.instilCapacity(repository, amulet));
        for (final String id : List.of("a", "b", "c", "d")) {
            tw.exilecore.currency.Crafting.instil(amulet, id, 4);
        }
        assertEquals(List.of("a", "b", "c", "d"), amulet.instilledAll());
        tw.exilecore.currency.Crafting.instil(amulet, "e", 4);
        assertEquals(List.of("b", "c", "d", "e"), amulet.instilledAll(), "滿了換掉最早的");
        assertEquals(List.of("b", "c", "d", "e"), amulet.copy().instilledAll());
        final ItemData plain = new ItemData("jamulet_09", Rarity.RARE, 70);
        assertEquals(1, ItemStats.instilCapacity(repository, plain));
        tw.exilecore.currency.Crafting.instil(plain, "x", 1);
        tw.exilecore.currency.Crafting.instil(plain, "y", 1);
        assertEquals(List.of("y"), plain.instilledAll());
    }

    @Test
    void prismOfBeliefLevelsOneSkillChosenByTheItem() {
        final ItemData prism = generator.generateUnique(repository.unique("prism_of_belief"), 80, new Random(2));
        assertTrue(prism.corrupted(), "照 PoE2：掉出來就是已汙染");
        final List<String> picked = UniquePicks.picks(prism, UniquePicks.SKILL, 1);
        assertEquals(1, picked.size());
        assertEquals(picked, UniquePicks.picks(prism, UniquePicks.SKILL, 1), "同一件永遠是同一個");
        final Modifier level = ItemStats.modifiers(repository, prism).stream().filter(m -> StatKeys.SKILL_LEVEL.equals(m.stat())).findFirst().orElseThrow();
        assertEquals(java.util.Set.of(tw.exilecore.item.unique.UniqueStats.SKILL_TAG_PREFIX + picked.getFirst()), level.tags());
        assertTrue(level.value() >= 1 && level.value() <= 3);
    }

    @Test
    void megalomaniacAndVoicesAreCorruptedJewels() {
        for (final String id : List.of("megalomaniac_jewel", "voices_jewel", "vitality_spirit")) {
            assertTrue(generator.generateUnique(repository.unique(id), 80, new Random(3)).corrupted(), id);
        }
        final ItemData voices = generator.generateUnique(repository.unique("voices_jewel"), 80, new Random(4));
        final int count = tw.exilecore.passive.JewelSockets.voiceCount(repository, voices);
        assertTrue(count >= 2 && count <= 4, "嗓音開 2～4 個孔：" + count);
        // 2026-11-04 照站長：2 孔 89%、3 孔 10%、4 孔 1%（value_weights）
        final int[] seen = new int[5];
        final Random random = new Random(42);
        for (int i = 0; i < 4000; i++) {
            seen[tw.exilecore.passive.JewelSockets.voiceCount(repository, generator.generateUnique(repository.unique("voices_jewel"), 80, random))]++;
        }
        assertTrue(seen[2] > 3400 && seen[2] < 3700, "2 孔約 89%：" + seen[2]);
        assertTrue(seen[3] > 300 && seen[3] < 520, "3 孔約 10%：" + seen[3]);
        assertTrue(seen[4] > 10 && seen[4] < 90, "4 孔約 1%：" + seen[4]);
        assertFalse(generator.generateUnique(repository.unique("glimpse_of_chaos"), 80, new Random(5)).corrupted());
    }

    @Test
    void moriorInvictusSocketModsScaleWithFilledSockets() {
        final ItemData chest = generator.generateUnique(repository.unique("morior_invictus"), 80, new Random(8));
        final List<UniquePicks.RolledSocketMod> mods = UniquePicks.socketMods(chest, 3);
        assertEquals(3, mods.size());
        assertEquals(3, mods.stream().map(m -> m.mod().id()).distinct().count(), "三種不重複");
        assertEquals(mods, UniquePicks.socketMods(chest, 3), "同一件永遠一樣");
        final String stat = mods.getFirst().mod().stats().getFirst();
        final double none = ItemStats.modifiers(repository, chest).stream().filter(m -> m.source().contains("插槽詞綴")).count();
        assertEquals(0, none, "沒嵌東西不給");
        chest.addRune("iron");
        chest.addRune("iron");
        final double two = ItemStats.modifiers(repository, chest).stream().filter(m -> m.source().contains("插槽詞綴") && m.stat().equals(stat))
                .mapToDouble(Modifier::value).sum();
        final double one = mods.getFirst().modifiers(1, "").getFirst().value();
        assertEquals(one * 2, two, 1e-9, "嵌兩個給兩份");
    }

    @Test
    void glimpseOfChaosStaysModifiableWhenCorrupted() {
        final ItemData glimpse = generator.generateUnique(repository.unique("glimpse_of_chaos"), 80, new Random(6));
        glimpse.setCorrupted(true);
        assertTrue(tw.exilecore.currency.CurrencyOperation.REROLL_VALUES.allowedOn(glimpse, repository), "已汙染時仍可調整");
        assertFalse(tw.exilecore.currency.CurrencyOperation.CORRUPT.allowedOn(glimpse, repository), "不能再腐化");
        final ItemData other = generator.generateUnique(repository.unique("zerphi_genesis"), 80, new Random(7));
        other.setCorrupted(true);
        assertFalse(tw.exilecore.currency.CurrencyOperation.REROLL_VALUES.allowedOn(other, repository));
    }
}
