package tw.exilecore.ascendancy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.Runes;
import tw.exilecore.passive.JewelSockets;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 照站長「昇華的部分照原作」：之前因為缺系統而平衡等效的昇華天賦，系統做出來之後照原作生效
 * （進階奇術的額外品質效果、聖賢智慧的符文羈絆詞綴、晶化護符的命匣、巨量承載的說明；巨靈、支援火力、冥想、元素湧動見 skill 的 AscMinionSystemsTest）。
 */
class AscOriginalSystemsTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static AscendancyConfig config;
    static ItemRepository repository;
    static PassiveTree tree;

    @BeforeAll
    static void load() throws IOException {
        config = new AscendancyConfig();
        config.load(DATA);
        GemQuality.load(DATA);
        repository = new ItemRepository();
        repository.load(DATA);
        tree = PassiveTree.load(DATA.resolve("passives/tree.json"));
    }

    private static AscendancyConfig.Node node(final String ascendancy, final String id) {
        return config.ascendancy(ascendancy).node(id);
    }

    @Test
    void reworkedNodesHaveNoServerNotes() {
        for (final AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
            for (final AscendancyConfig.Node node : ascendancy.nodes()) {
                if (node.note() != null) {
                    assertFalse(node.note().contains("本伺服器"), node.id());
                }
                for (final String line : node.lines()) {
                    assertFalse(line.contains("本伺服器"), node.id());
                }
            }
        }
        int checked = 0;
        for (final AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
            for (final AscendancyConfig.Node node : ascendancy.nodes()) {
                if (List.of("colossal_capacity", "unleash_hell", "advanced_thaumaturgy", "faith_choice", "barya_kelari", "barya_navira",
                        "barya_ruzhan", "crystalline", "wisdom_maji").contains(node.id())) {
                    assertNull(node.note(), ascendancy.id() + "." + node.id() + "：照原作做出來了，不用補充說明");
                    checked++;
                }
            }
        }
        assertEquals(10, checked); // 2026-10-27：淵井骸主也有晶化護符；這次之前 9
    }

    @Test
    void wisdomOfTheMajiTurnsOnBondedRuneModifiers() {
        AscendancyConfig.Node wisdom = null;
        for (final AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
            if (ascendancy.node("wisdom_maji") != null) {
                wisdom = ascendancy.node("wisdom_maji");
            }
        }
        assertTrue(wisdom != null && wisdom.stats().stream().anyMatch(m -> m.stat().equals(AscKeys.BONDED)), "聖賢智慧給 asc.bonded");
        assertTrue(wisdom.stats().stream().noneMatch(m -> m.stat().equals(AscKeys.RUNE_EFFECT)), "不再是符文效果增加 50%");
        // 每一顆符文都有羈絆詞綴（PoE2 0.5：防具 +20 最大生命、+20 最大魔力）
        for (final Runes.Rune rune : repository.runes()) {
            assertFalse(rune.bondedArmour().isEmpty(), rune.id());
            assertFalse(rune.bondedWeapon().isEmpty(), rune.id());
        }
        final ItemBase mace = repository.base("mace1_01");
        final ItemBase wand = repository.base("cwand_01");
        final ItemData weapon = new ItemData("mace1_01", Rarity.NORMAL, 1);
        weapon.addRune("ember");
        final List<Modifier> bonded = Runes.bondedModifiers(repository, mace, weapon);
        assertEquals(1, bonded.size());
        assertEquals(StatKeys.IGNITE_MAGNITUDE, bonded.getFirst().stat());
        assertEquals(ModType.INCREASED, bonded.getFirst().type());
        assertEquals(0.30, bonded.getFirst().value(), 1e-9, "焰紋：增加 30% 點燃幅度");
        assertTrue(Runes.modifiers(repository, mace, weapon).stream().noneMatch(m -> m.stat().equals(StatKeys.IGNITE_MAGNITUDE)),
                "沒有聖賢智慧時羈絆詞綴不在一般的符文加成裡");
        // 法杖類、防具的羈絆
        assertEquals("+50 最大能量護盾", repository.rune("resolve").bondedOn(wand).getFirst().text());
        final ItemBase body = repository.base("body_str_01");
        final ItemData plate = new ItemData("body_str_01", Rarity.NORMAL, 1);
        plate.addRune("iron");
        final List<Modifier> armour = Runes.bondedModifiers(repository, body, plate);
        assertEquals(20, armour.stream().filter(m -> m.stat().equals(StatKeys.LIFE_MAX)).mapToDouble(Modifier::value).sum(), 1e-9);
        assertEquals(20, armour.stream().filter(m -> m.stat().equals(StatKeys.MANA_MAX)).mapToDouble(Modifier::value).sum(), 1e-9);
    }

    @Test
    void advancedThaumaturgyGivesTheAdditionalQualityEffect() {
        // 冰霜箭：額外的品質效果 (0—40)% 更多投射物速度
        final List<Modifier> frostbolt = AscBuild.advanced("frostbolt", 20, List.of());
        assertEquals(1, frostbolt.size());
        assertEquals(StatKeys.SKILL_PROJECTILE_SPEED, frostbolt.getFirst().stat());
        assertEquals(ModType.MORE, frostbolt.getFirst().type());
        assertEquals(0.40, frostbolt.getFirst().value(), 1e-9);
        // 一般品質效果照樣保留，再加上額外的
        final List<Modifier> normal = List.of(Modifier.increased(StatKeys.DAMAGE, 0.2, "品質 20%"));
        final List<Modifier> spark = AscBuild.advanced("spark", 20, normal);
        assertEquals(2, spark.size());
        assertEquals(4, spark.get(1).value(), 1e-9, "電球：+4 投射物");
        // 額外效果的機制還沒有的寶石：照舊把一般品質再算一次（平衡等效）
        assertEquals(AscBuild.twice(normal), AscBuild.advanced("fireball", 20, normal));
        // 從一般品質移到額外品質效果的（原作的一般品質沒有這條）
        assertTrue(GemQuality.modifiers("snap", 20, "q").stream().noneMatch(m -> m.stat().equals(StatKeys.COOLDOWN_RECOVERY)));
        assertTrue(GemQuality.hasAdvanced("snap"));
        assertNull(GemQuality.advancedModifiers("no_such_skill", 20, "q"));
    }

    @Test
    void crystallinePhylacteryHoldsABasicJewelAtDoubleEffect() {
        final ItemBase jewelBase = repository.bases().stream()
                .filter(b -> b.slot() == tw.exilecore.item.ItemSlot.JEWEL && !b.isRadiusJewel()
                        && !tw.exilecore.passive.TimelessJewels.isTimeless(b)).findFirst().orElseThrow();
        final ItemData jewel = new ItemData(jewelBase.id(), Rarity.MAGIC, 50);
        final tw.exilecore.item.Affix affix = repository.affixes().stream()
                .filter(a -> a.spawnTags().stream().anyMatch(jewelBase.tags()::contains)).findFirst().orElseThrow();
        jewel.affixes().add(new tw.exilecore.item.RolledAffix(affix.id(), affix.tiers().getFirst().tier(),
                affix.tiers().getFirst().roll(new java.util.Random(1))));
        final List<Modifier> base = tw.exilecore.item.ItemStats.modifiers(repository, jewel);
        assertFalse(base.isEmpty());
        final Map<String, ItemData> jewels = Map.of(JewelSockets.PHYLACTERY, jewel);
        final List<Modifier> doubled = JewelSockets.modifiers(repository, jewels, Set.of(), tree, 1.0, true);
        assertEquals(base.size(), doubled.size());
        for (int i = 0; i < base.size(); i++) {
            assertEquals(base.get(i).stat(), doubled.get(i).stat());
            assertEquals(base.get(i).value() * 2, doubled.get(i).value(), 1e-9, "效果增加 100%");
        }
        assertTrue(JewelSockets.modifiers(repository, jewels, Set.of(), tree, 1.0, false).isEmpty(), "沒有晶化護符：命匣裡的珠寶沒有效果");
        final ItemData unique = new ItemData(jewelBase.id(), Rarity.UNIQUE, 50);
        assertTrue(JewelSockets.modifiers(repository, Map.of(JewelSockets.PHYLACTERY, unique), Set.of(), tree, 1.0, true).isEmpty(),
                "傳奇珠寶不能放命匣");
        // 天賦樹上的珠寶不再被晶化護符加倍（這次之前是第一顆非傳奇的基礎珠寶加倍）
        final tw.exilecore.passive.PassiveNode socket = tree.ordered().stream().filter(tw.exilecore.passive.PassiveNode::isSocket).findFirst().orElseThrow();
        assertEquals(base, JewelSockets.modifiers(repository, Map.of(socket.id(), jewel), Set.of(socket.id()), tree, 1.0, true));
    }
}
