package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.TreeEffectKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2：傳奇的限定／全域掉落分類、稀有度權重、這次補的傳奇。 */
class UniqueRarityTest {

    static ItemRepository repository;
    static ItemGenerator generator;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
        generator = new ItemGenerator(repository);
    }

    @Test
    void rarityTableMapsToWeights() {
        assertEquals(100, UniqueRarity.COMMON.dropWeight());
        assertEquals(100, UniqueRarity.COMMON.gambleWeight());
        assertEquals(50, UniqueRarity.UNCOMMON.dropWeight());
        assertEquals(40, UniqueRarity.UNCOMMON.gambleWeight());
        assertEquals(20, UniqueRarity.RARE.dropWeight());
        assertEquals(12, UniqueRarity.RARE.gambleWeight());
        assertEquals(8, UniqueRarity.VERY_RARE.dropWeight());
        assertEquals(4, UniqueRarity.VERY_RARE.gambleWeight());
        assertEquals(UniqueRarity.VERY_RARE, UniqueRarity.parse("very_rare"));
        assertEquals(UniqueRarity.RARE, UniqueRarity.parse("稀有"));
        assertNull(UniqueRarity.parse(null));
        assertThrows(IllegalArgumentException.class, () -> UniqueRarity.parse("mythic"));
        // 一般地越稀有權重越低
        UniqueRarity last = null;
        for (final UniqueRarity rarity : UniqueRarity.values()) {
            if (last != null) {
                assertTrue(rarity.dropWeight() < last.dropWeight() && rarity.gambleWeight() < last.gambleWeight(), rarity.name());
            }
            last = rarity;
        }
    }

    @Test
    void everyGlobalUniqueHasARarityAndWeightFollowsIt() {
        int global = 0;
        int restricted = 0;
        int disabled = 0;
        for (final UniqueItem unique : repository.uniques()) {
            if (UniqueRarity.disabled(unique)) {
                disabled++;
                continue;
            }
            if (!UniqueRarity.global(unique)) {
                restricted++;
                continue;
            }
            global++;
            if (unique.chase()) {
                continue; // 追逐級＝極稀有，走 chase 池
            }
            final UniqueRarity rarity = repository.uniqueRarity(unique.id());
            assertNotNull(rarity, unique.id() + "：全域掉落要寫 rarity");
            assertEquals(rarity.dropWeight(), unique.weight(), unique.id());
            assertEquals(rarity.gambleWeight(), repository.gambleWeight(unique), unique.id());
        }
        assertEquals(0, disabled, "2026-10-28 照站長：焰扣束帶、奔雷皮靴、禁視鏈冠直接刪除（這次之前禁止掉落 3 件）");
        assertTrue(global > restricted, global + " / " + restricted);
        assertEquals(repository.uniques().size(), global + restricted + disabled);
    }

    @Test
    void classificationFollowsPoe2() {
        // 原作 Boss 限定 → 限定
        for (final String id : List.of("hateforge_greaves", "pragmatist_armour", "wisdom_action_wraps", "collapsing_horizon", "tailwind_quiver",
                "sacred_flame", "atziris_contempt")) {
            assertFalse(UniqueRarity.global(repository.unique(id)), id);
        }
        assertEquals(Set.of("breach_lord"), repository.unique("wisdom_action_wraps").sources());
        assertEquals(Set.of("ritual_priest"), repository.unique("pragmatist_armour").sources());
        assertEquals(Set.of("delirium_mirror"), repository.unique("collapsing_horizon").sources());
        // 原作沒有限定、以前內容專屬 → 全域（內容照樣掉）
        final UniqueItem windscream = repository.unique("somnambulist");
        assertTrue(UniqueRarity.global(windscream) && windscream.droppedBy("delirium") && windscream.droppedBy("map_boss"));
        // 禁止掉落
        for (final String id : List.of("flameclasp_belt", "thunderdash_boots", "forbidden_tiara")) {
            assertTrue(UniqueRarity.disabled(repository.unique(id)), id);
        }
        // 稀有度個別調整與分層
        assertEquals(UniqueRarity.RARE, repository.uniqueRarity("undertow_band"));
        assertEquals(UniqueRarity.COMMON, repository.uniqueRarity("goldleaf_hood"));
        assertEquals(UniqueRarity.VERY_RARE, repository.uniqueRarity("oracle_spear"));
    }

    @Test
    void gambleFactorScalesByRarity() {
        final UniqueItem common = repository.unique("marrowblight");
        final UniqueItem veryRare = repository.unique("oracle_spear");
        assertEquals(1.0, repository.gambleFactor(List.of(common)), 1e-9);
        assertEquals(0.04, repository.gambleFactor(List.of(veryRare)), 1e-9);
        assertEquals(1.0, repository.gambleFactor(List.of(common, veryRare)), 1e-9, "最多 1");
        assertEquals(0, repository.gambleFactor(List.of()), 1e-9);
        assertEquals(0.08, UniqueRarity.factor(List.of(veryRare), UniqueItem::weight), 1e-9);
        // 照賭博權重挑：常見的遠比很稀有的常出
        final Random random = new Random(7);
        int rare = 0;
        for (int i = 0; i < 2000; i++) {
            if (repository.pickGamble(List.of(common, veryRare), random) == veryRare) {
                rare++;
            }
        }
        assertTrue(rare > 30 && rare < 130, "4／104 ≈ 77：" + rare);
    }

    @Test
    void newPoe2UniquesLoad() {
        for (final String id : List.of("reverie", "atziris_contempt", "the_vertex", "sacred_flame", "the_taming", "bereks_pass",
                "bereks_respite", "shavronnes_satchel", "geofris_sanctuary")) {
            final UniqueItem unique = repository.unique(id);
            assertNotNull(unique, id);
            final ItemData data = generator.generateUnique(unique, 84, new Random(3));
            assertEquals(id, data.unique());
            assertEquals(unique.affixIds().size(), data.affixes().size(), id);
        }
        assertEquals("迷夢", repository.unique("reverie").name());
        assertEquals("吉菲雅的聖所", repository.unique("geofris_sanctuary").name());
        assertEquals("元素馴服", repository.unique("the_taming").name());
        assertEquals(4, repository.unique("undertow_band").version(), "貝雷克的冰與雷之握換回原作的疾風技能那一條");
    }

    @Test
    void vertexChoosesEquipmentOrGemRequirements() {
        final StatSheet none = new StatSheet();
        assertEquals(0, TreeEffectKeys.noAttributeRequirements(none));
        assertEquals(1.0, TreeEffectKeys.requirementScale(none), 1e-9);
        final StatSheet equipment = new StatSheet();
        equipment.add(Modifier.flat(tw.exilecore.item.unique.UniqueStats.NO_ATTR_REQ, 1, "謎容"));
        assertEquals(0, TreeEffectKeys.requirementScale(equipment), 1e-9);
        assertEquals(1.0, tw.exilecore.skill.GemAttributes.scale(equipment), 1e-9);
        final StatSheet gems = new StatSheet();
        gems.add(Modifier.flat(tw.exilecore.item.unique.UniqueStats.NO_ATTR_REQ, 2, "謎容"));
        assertEquals(1.0, TreeEffectKeys.requirementScale(gems), 1e-9);
        assertEquals(0, tw.exilecore.skill.GemAttributes.scale(gems), 1e-9);
    }

    @Test
    void geofriEnergyShieldEqualsStrength() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(StatKeys.STR, 150, "測試"));
        sheet.add(Modifier.flat(StatKeys.ES_MAX, 999, "測試"));
        sheet.add(Modifier.flat(tw.exilecore.item.unique.UniqueStats.ES_EQUAL_TO_STR, 250, "吉菲雅的聖所"));
        assertEquals(375, tw.exilecore.character.PlayerState.maxEnergyShield(sheet), 1e-9);
        sheet.add(Modifier.flat(StatKeys.FLAG_ELDRITCH_BATTERY, 1, "測試"));
        assertEquals(375, tw.exilecore.character.PlayerState.maxEnergyShield(sheet), 1e-9, "無法被轉換");
    }

    @Test
    void lowestResistInPresence() {
        final StatSheet monster = new StatSheet();
        monster.add(Modifier.flat(DamageType.FIRE.mitigationStat(), 40, "測試"));
        monster.add(Modifier.flat(DamageType.COLD.mitigationStat(), 10, "測試"));
        monster.add(Modifier.flat(DamageType.LIGHTNING.mitigationStat(), 25, "測試"));
        final StatSheet after = monster.overlay(tw.exilecore.combat.Presence.lowestResist(monster));
        for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
            assertEquals(10, after.get(type.mitigationStat()), 1e-9, type.name());
        }
    }
}
