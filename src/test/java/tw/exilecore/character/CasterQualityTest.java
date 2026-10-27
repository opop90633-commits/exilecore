package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemStats;
import tw.exilecore.item.Rarity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 照 PoE2 0.5（poe2wiki Quality）：法杖、長杖、權杖的品質不加武器自己的數值，加在賦予的技能上
 * （每 1% 品質 = 賦予的技能 +1% 品質）；權杖的精魂不吃品質。
 */
class CasterQualityTest {

    @Test
    void grantedSkillTakesWeaponQuality() {
        final SkillLoadout loadout = new SkillLoadout();
        loadout.syncGranted(Map.of("firebolt", 10), Map.of("firebolt", 15));
        final SkillLoadout.Entry entry = loadout.skill(loadout.find("firebolt"));
        assertTrue(entry.granted());
        assertEquals(15, entry.quality(), "賦予的技能的品質 = 武器的品質");
        assertFalse(entry.encodeSupports().contains(SkillLoadout.Entry.QUALITY_PREFIX + "15"), "武器給的品質不存檔");

        loadout.syncGranted(Map.of("firebolt", 10), Map.of("firebolt", 26));
        assertEquals(26, loadout.skill(loadout.find("firebolt")).quality(), "特殊詞綴超過寶石上限也照算");

        loadout.syncGranted(Map.of("firebolt", 10), Map.of());
        assertEquals(0, loadout.skill(loadout.find("firebolt")).quality(), "換成沒品質的武器就回到 0");

        loadout.syncGranted(Map.of("firebolt", 10));
        assertEquals(0, loadout.skill(loadout.find("firebolt")).quality());
    }

    @Test
    void sceptreSpiritAndCasterStatsIgnoreQuality() throws IOException {
        final ItemRepository items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        for (final String id : new String[] {"csceptre_01", "cstaff_01", "cwand_01"}) {
            assertNotNull(items.grantedSkill(id), id + " 賦予技能");
            final ItemData plain = generate(items, id);
            final ItemData quality = plain.copy();
            quality.setQuality(20);
            final ItemStats.Snapshot a = ItemStats.snapshot(items, items.base(id), plain);
            final ItemStats.Snapshot b = ItemStats.snapshot(items, items.base(id), quality);
            assertEquals(a.damage().keySet(), b.damage().keySet(), id + "：品質不加傷害");
            assertEquals(a.armour() + a.evasion() + a.energyShield(), b.armour() + b.evasion() + b.energyShield(), 1e-9, id + "：品質不加防禦");
            assertEquals(ItemStats.sceptreSpirit(items.base(id), ItemStats.modifiers(items, plain)),
                    ItemStats.sceptreSpirit(items.base(id), ItemStats.modifiers(items, quality)), 1e-9, id + "：精魂不吃品質");
        }
        final ItemData sceptre = generate(items, "csceptre_01");
        sceptre.setQuality(20);
        assertEquals(ItemStats.SCEPTRE_SPIRIT, ItemStats.sceptreSpirit(items.base("csceptre_01"), ItemStats.modifiers(items, sceptre)), 1e-9,
                "20% 品質的權杖還是 100 精魂");
    }

    private static ItemData generate(final ItemRepository items, final String id) {
        final ItemData data = new tw.exilecore.item.ItemGenerator(items).generate(items.base(id), Rarity.NORMAL, 60, new java.util.SplittableRandom(1));
        data.setQuality(0);
        return data;
    }
}
