package tw.exilecore.character;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import tw.exilecore.ascendancy.AscendancyConfig;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.passive.TreeCode;
import tw.exilecore.skill.ModifierText;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.web.BuildCalc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三個戒指欄（2026-10-26 照 PoE2 昇華・展露之指「+1 戒指欄位」；這次之前平衡等效成戒指的詞綴效果增加 50%）：
 * 接在護符欄後面的虛擬欄位，舊存檔的位置都沒動；裝備組、網頁計算器都多一格。
 */
class ThirdRingSlotTest {

    static final Path DATA = Path.of("src/main/resources/data");

    @Test
    void oldSaveIndicesDoNotMove() {
        // 存檔的位置：裝備欄 0–9、快捷列收起來的 10–15、護符 16–18；第三個戒指接在最後（舊存檔讀進來是空的）
        assertEquals(10, PlayerState.JEWELLERY_SLOTS.length);
        assertEquals(10, PlayerState.HOTBAR_STASH);
        assertEquals(16, PlayerState.CHARM_SLOT);
        assertEquals(19, PlayerState.RING3_SLOT);
        assertEquals(20, PlayerState.STORED_SLOTS);
    }

    @Test
    void gearSlotsAppendTheThirdRing() {
        assertEquals(11, PlayerState.GEAR_SLOTS.length);
        assertTrue(Arrays.equals(PlayerState.JEWELLERY_SLOTS, Arrays.copyOf(PlayerState.GEAR_SLOTS, PlayerState.JEWELLERY_SLOTS.length)),
                "前 10 格跟原本的裝備欄一樣（網頁、裝備組的舊資料照樣對得上）");
        assertEquals(ItemSlot.RING, PlayerState.GEAR_SLOTS[PlayerState.RING3]);
        for (int i = 0; i < PlayerState.JEWELLERY_SLOTS.length; i++) {
            assertEquals(i, PlayerState.gearIndex(i));
        }
        assertEquals(PlayerState.RING3_SLOT, PlayerState.gearIndex(PlayerState.RING3));
        final PlayerState state = new PlayerState(UUID.randomUUID());
        assertEquals(PlayerState.GEAR_SLOTS.length, state.gearSet(0).length, "裝備組也有第三個戒指");
        assertEquals(PlayerState.STORED_SLOTS, state.jewellery().length);
    }

    @Test
    void unfurledFingerGrantsARingSlot() throws IOException {
        final AscendancyConfig config = new AscendancyConfig();
        config.load(DATA);
        final AscendancyConfig.Node node = config.ascendancy("ritualist").node("unfurled_finger");
        assertNotNull(node);
        assertEquals(1, node.stats().size());
        final Modifier stat = node.stats().getFirst();
        assertEquals(PlayerState.RING_EXTRA_STAT, stat.stat());
        assertEquals(ModType.FLAT, stat.type());
        assertEquals(1, stat.value(), 1e-9);
        assertTrue(node.stats().stream().noneMatch(mod -> mod.stat().equals("asc.ring_effect")), "拿掉了平衡等效的戒指效果 50%");
        assertEquals("+1 戒指欄位", ModifierText.describe(stat));
    }

    @Test
    void ringSlotOpenFollowsTheStat() {
        final StatSheet sheet = new StatSheet();
        assertFalse(Gear.ringSlotOpen(sheet));
        assertFalse(Gear.ringSlotOpen((StatSheet) null));
        sheet.add(Modifier.flat(PlayerState.RING_EXTRA_STAT, 1, "展露之指"));
        assertTrue(Gear.ringSlotOpen(sheet));
    }

    @Test
    void webCalculatorReadsTheEleventhGearSlot() throws Exception {
        final PassiveTree tree = PassiveTree.load(DATA.resolve("passives/tree.json"));
        final SkillConfig skills = new SkillConfig();
        skills.load(DATA);
        final JsonObject json = new JsonObject();
        json.addProperty("tree", TreeCode.encode(tree, "witch", Set.of()));
        json.addProperty("level", 70);
        final JsonArray gear = new JsonArray();
        for (int i = 0; i < PlayerState.JEWELLERY_SLOTS.length; i++) {
            gear.add(JsonNull.INSTANCE);
        }
        gear.add(JsonParser.parseString("{\"base\":\"x_ring\",\"rarity\":\"RARE\",\"ilvl\":60}"));
        json.add("gear", gear);
        final BuildCalc.Build build = BuildCalc.parse(json.toString(), tree, skills);
        assertEquals(PlayerState.GEAR_SLOTS.length, build.gear().size());
        assertNotNull(build.gear().get(PlayerState.RING3));
        assertEquals("x_ring", build.gear().get(PlayerState.RING3).base());

        // 舊的 10 格：第三個戒指是空的
        final JsonArray legacy = new JsonArray();
        for (int i = 0; i < PlayerState.JEWELLERY_SLOTS.length; i++) {
            legacy.add(JsonNull.INSTANCE);
        }
        json.add("gear", legacy);
        final BuildCalc.Build old = BuildCalc.parse(json.toString(), tree, skills);
        assertEquals(PlayerState.GEAR_SLOTS.length, old.gear().size());
        assertNull(old.gear().get(PlayerState.RING3));
    }
}
