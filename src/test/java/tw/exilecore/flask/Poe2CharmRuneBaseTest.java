package tw.exilecore.flask;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.ItemStats;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.Runes;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：護符、符文、腰帶／戒指／項鍊基底照 PoE2 0.5。 */
class Poe2CharmRuneBaseTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static YamlNode charms;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        charms = YamlNode.load(DATA.resolve(FlaskConfig.FILE)).node("charms");
    }

    @Test
    void charmsMatchThePoe2Table() {
        assertEquals(0, charms.integer("base_slots", -1), "角色本身 0 格，護符欄只有腰帶給");
        // 代號 → 等級、持續時間、充能、每次消耗、觸發
        final Map<String, Object[]> table = Map.ofEntries(
                Map.entry("thawing_charm", new Object[] {12, 3.0, 40, 40, "freeze"}),
                Map.entry("dousing_charm", new Object[] {32, 3.0, 40, 30, "ignite"}),
                Map.entry("grounding_charm", new Object[] {32, 3.0, 40, 30, "shock"}),
                Map.entry("staunching_charm", new Object[] {18, 3.0, 40, 30, "bleed"}),
                Map.entry("antidote_charm", new Object[] {24, 3.0, 40, 20, "poison"}),
                Map.entry("stone_charm", new Object[] {8, 3.0, 40, 20, "stun"}),
                Map.entry("ruby_charm", new Object[] {5, 4.0, 40, 20, "hit_fire"}),
                Map.entry("sapphire_charm", new Object[] {5, 4.0, 40, 20, "hit_cold"}),
                Map.entry("topaz_charm", new Object[] {5, 4.0, 40, 20, "hit_lightning"}),
                Map.entry("amethyst_charm", new Object[] {40, 4.0, 40, 30, "hit_chaos"}),
                Map.entry("silver_charm", new Object[] {10, 3.0, 40, 20, "slow"}),
                Map.entry("golden_charm", new Object[] {50, 1.0, 80, 80, "kill_rare"}),
                Map.entry("cleansing_charm", new Object[] {40, 3.0, 40, 20, "curse"}));
        final YamlNode bases = charms.node("bases");
        for (final Map.Entry<String, Object[]> entry : table.entrySet()) {
            final YamlNode node = bases.node(entry.getKey());
            final Object[] want = entry.getValue();
            assertEquals(want[0], node.integer("level", 0), entry.getKey());
            assertEquals((double) want[1], node.decimal("duration", 0), 1e-9, entry.getKey());
            assertEquals(want[2], node.integer("charges", 0), entry.getKey());
            assertEquals(want[3], node.integer("per_use", 0), entry.getKey());
            assertEquals(want[4], node.string("trigger", ""), entry.getKey());
            assertTrue(CharmService.TRIGGERS.contains(node.string("trigger", "")), entry.getKey());
            assertFalse(node.bool("retired", false), entry.getKey());
        }
        assertTrue(bases.node("heartwood_charm").bool("retired", false), "續命護符原作沒有：停用（舊物品照常能用）");
        // 物品引擎的基底：停用的不在圖鑑清單裡，但找得到（舊物品）
        assertNotNull(repository.base("heartwood_charm"));
        assertTrue(repository.retiredBase("heartwood_charm"));
        assertTrue(repository.flaskLikeBases().stream().noneMatch(base -> base.id().equals("heartwood_charm")));
        assertTrue(repository.flaskLikeBases().stream().anyMatch(base -> base.id().equals("cleansing_charm")), "新的淨化護符");
        assertTrue(repository.retiredBase("molten_life_flask") && repository.retiredBase("relic_mana_flask"));
    }

    @Test
    void everyBeltGivesCharmSlots() {
        int belts = 0;
        for (final ItemBase base : repository.bases()) {
            if (base.slot() != ItemSlot.BELT) {
                continue;
            }
            belts++;
            assertNotNull(base.implicit(), base.id());
            assertTrue(base.implicit().mods().stream().anyMatch(mod -> mod.stat().equals(CharmService.STAT_SLOTS)),
                    base.id() + " 要有「有 x 個護符欄位」：" + base.implicit().text());
        }
        assertEquals(15, belts, "PoE2 0.5 的 15 種腰帶");
        assertEquals(List.of(1, 3), List.of(repository.base("jbelt_01").implicit().vars().get("slots")[0],
                repository.base("jbelt_01").implicit().vars().get("slots")[1]));
        for (final String fixedOne : List.of("jbelt_04", "jbelt_05", "jbelt_06", "jbelt_stalking")) {
            assertNull(repository.base(fixedOne).implicit().vars().get("slots"), fixedOne + "：Has 1 Charm Slot（固定 1 格）");
        }
    }

    @Test
    void newJewelleryBasesExist() {
        for (final String id : List.of("jbelt_wide", "jbelt_long", "jbelt_ornate", "jbelt_mail", "jbelt_double", "jbelt_stalking", "jbelt_utility",
                "jbelt_fine", "jring_biostatic", "jring_unset", "jring_breach", "jring_breach_refined", "jring_abyssal", "jring_grasping",
                "jring_dusk", "jring_gloam", "jring_penumbra", "jring_tenebrous", "jamulet_twisted", "jamulet_distorted", "jamulet_dusk",
                "jamulet_gloam", "jamulet_penumbra", "jamulet_tenebrous", "jamulet_corona")) {
            final ItemBase base = repository.base(id);
            assertNotNull(base, id);
            assertTrue(base.isJewellery() && !repository.retiredBase(id), id);
        }
        assertEquals(52, repository.base("jring_biostatic").requirements().level());
        // 賦予技能的三種（悲歌、兆示、空無）還沒有技能，只剩缺點：先停用
        for (final String id : List.of("jamulet_lament", "jamulet_portent", "jamulet_absent")) {
            assertNotNull(repository.base(id), id);
            assertTrue(repository.retiredBase(id), id);
        }
        assertTrue(repository.basesDroppableAt(100).stream().anyMatch(base -> base.id().equals("jbelt_fine")));
    }

    @Test
    void prefixSuffixAllowanceChangesTheAffixCaps() {
        final ItemGenerator generator = new ItemGenerator(repository);
        final ItemData dusk = new ItemData("jring_dusk", Rarity.RARE, 60);
        assertEquals(4, generator.maxAffixes(dusk, AffixType.PREFIX));
        assertEquals(2, generator.maxAffixes(dusk, AffixType.SUFFIX));
        final ItemData absent = new ItemData("jamulet_absent", Rarity.RARE, 60); // 停用的基底，規則照樣算
        assertEquals(2, generator.maxAffixes(absent, AffixType.PREFIX));
        assertEquals(2, generator.maxAffixes(absent, AffixType.SUFFIX));
        final ItemData plain = new ItemData("jring_02", Rarity.RARE, 60);
        assertEquals(3, generator.maxAffixes(plain, AffixType.PREFIX));
        // 前後綴上限的固定詞綴不進角色屬性表
        for (final Modifier modifier : ItemStats.modifiers(repository, dusk)) {
            assertFalse(modifier.stat().startsWith("local."), modifier.toString());
        }
    }

    @Test
    void runesAreThirteenFamiliesOfFourTiersWithThreeCategories() {
        assertEquals(52, repository.runes().size());
        for (final String old : List.of("iron", "ember", "frost", "storm", "mind", "swift")) {
            assertNotNull(repository.rune(old), "原有的代號要留著：" + old);
            assertEquals(15, repository.rune(old).level(), old + " 是普通階（15 級）");
        }
        assertEquals("完美鐵骨符文", repository.rune("iron_perfect").name());
        assertEquals(50, repository.rune("iron_perfect").level());
        final ItemBase wand = repository.base("cwand_01");
        final ItemBase mace = repository.base("mace1_01");
        final ItemBase body = repository.base("body_str_01");
        final Runes.Rune iron = repository.rune("iron");
        assertEquals("增加 25% 法術傷害", iron.on(wand).getFirst().text(), "法杖類照 caster");
        assertEquals("增加 16% 物理傷害", iron.on(mace).getFirst().text());
        assertEquals("增加 16% 護甲、閃避與能量護盾", iron.on(body).getFirst().text());
        // 防具上的護甲增加是本地的：算進胸甲的護甲，不進角色的全域增加
        final ItemData plate = new ItemData("body_str_01", Rarity.NORMAL, 1);
        final double before = ItemStats.snapshot(repository, body, plate).armour();
        plate.addRune("iron");
        final double after = ItemStats.snapshot(repository, body, plate).armour();
        assertEquals(Math.round(before * 1.16), after, 1.0, "護甲 ×1.16");
        for (final Modifier modifier : ItemStats.modifiers(repository, plate)) {
            assertFalse(modifier.type() == tw.exilecore.stats.ModType.INCREASED
                    && Set.of(StatKeys.ARMOUR, StatKeys.EVASION, StatKeys.ES_MAX).contains(modifier.stat()), "不該有全域的防禦增加：" + modifier);
        }
    }
}
