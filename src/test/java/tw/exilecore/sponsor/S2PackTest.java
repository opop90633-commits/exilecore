package tw.exilecore.sponsor;

import org.junit.jupiter.api.Test;
import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-11 照站長「S2 贊助禮包的內容物，這次主打可愛點的時裝」：企鵝冰淇淋系列 8 件＋限定稱號，模型、貼圖都在包裡。 */
class S2PackTest {

    @Test
    void bundleHasEightLimitedCosmeticsAndTheTitle() throws Exception {
        final SponsorConfig config = new SponsorConfig();
        config.load(Path.of("src/main/resources/data"));
        final SponsorConfig.Bundle bundle = config.bundles().get("s2_penguin");
        assertNotNull(bundle);
        assertEquals(9, bundle.cosmetics().size());
        final Set<String> slots = new HashSet<>();
        for (final String id : bundle.cosmetics()) {
            if (id.startsWith("title:")) {
                assertNotNull(config.limitedTitles().get(id.substring("title:".length())), id);
                continue;
            }
            final SponsorConfig.Cosmetic cosmetic = config.cosmetics().get(id);
            assertNotNull(cosmetic, id);
            assertTrue(cosmetic.isLimited(), id + " 要是限定（不能抽、不能換）");
            assertEquals("S2 贊助禮包限定", cosmetic.limitedTag());
            assertEquals(0, cosmetic.fashionPrice());
            slots.add(cosmetic.skinSlot());
        }
        assertEquals(Set.of("armor", "weapon", "bow", "crossbow", "offhand", "back", "pet"), slots, "每個部位都有（武器兩把）");
    }

    @Test
    void titleGivesQuantityAndLighterDeathPenalty() throws Exception {
        final SponsorConfig config = new SponsorConfig();
        config.load(Path.of("src/main/resources/data"));
        final List<Modifier> stats = config.limitedTitles().get("s2_penguin").stats();
        assertEquals(2, stats.size());
        for (final Modifier stat : stats) {
            assertNull(StatLines.problem(stat), stat.stat());
        }
        assertEquals(List.of("增加 5% 物品掉落數量", "死亡扣的經驗 -5%"), StatLines.describeAll(stats));
        final Modifier death = stats.get(1);
        assertEquals(StatKeys.DEATH_PENALTY, death.stat());
        assertEquals(ModType.FLAT, death.type());
        assertEquals(0.05, StatKeys.deathPenaltyWith(0.10, death.value()), 1e-9, "扣 10% 變 5%");
        assertEquals(0.0, StatKeys.deathPenaltyWith(0.04, death.value()), 1e-9, "最低 0（累積贊助已經減到 4% 的）");
        assertEquals(0.0, StatKeys.deathPenaltyWith(0.0, death.value()), 1e-9, "本來不扣的等級照舊");
        assertEquals(0.15, StatKeys.deathPenaltyWith(0.20, death.value()), 1e-9, "100 級起扣 20% 的變 15%");
    }

    @Test
    void modelsAndTexturesAreInThePacks() throws Exception {
        final Path web = Path.of("src/main/resources/web");
        try (ZipFile zip = new ZipFile(web.resolve("pack3d.zip").toFile())) {
            for (final String item : List.of("s2_penguin_pop_blade", "s2_penguin_pop_staff", "s2_cone_staff_blade", "s2_cone_staff_staff",
                    "s2_popsicle_bow_bow", "s2_cone_crossbow_crossbow", "s2_waffle_shield_shield", "s2_waffle_shield_quiver",
                    "s2_frost_wings_back_r", "s2_frost_wings_back_l", "s2_pet_penguin_pet", "armor3d_penguin_helmet",
                    "armor3d_penguin_chestplate", "armor3d_penguin_leggings", "armor3d_penguin_boots")) {
                assertNotNull(zip.getEntry("assets/exilecore/items/skin/" + item + ".json"), item);
            }
            assertNotNull(zip.getEntry("assets/exilecore/equipment/skin_armor3d_penguin.json"));
        }
        final String skins = Files.readString(web.resolve("skins/index.txt"));
        for (final String name : List.of("penguin_chestplate", "penguin_leggings", "penguin_boots", "penguin_humanoid", "penguin_humanoid_leggings")) {
            assertTrue(skins.contains(name + ".png"), name);
            assertTrue(Files.exists(web.resolve("skins/" + name + ".png")), name);
        }
        final String bm = Files.readString(Path.of("src/main/resources/cosmetics_bm/index.txt"));
        assertTrue(bm.contains("s2_frost_wings wings") && bm.contains("s2_pet_penguin pet"));
        assertTrue(Files.exists(Path.of("src/main/resources/cosmetics_bm/s2_frost_wings.bbmodel")));
        assertTrue(Files.exists(Path.of("src/main/resources/cosmetics_bm/s2_pet_penguin.bbmodel")));
    }
}
