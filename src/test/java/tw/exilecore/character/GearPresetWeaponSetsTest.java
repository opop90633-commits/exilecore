package tw.exilecore.character;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 2026-11-09 照站長「月卡能記錄裝備與天賦組的嗎」：裝備組連兩組武器一起換——存的時候照第 I／II 組記（不管正在用哪一組）、
 * 記下當時用第幾組；換上來照現在正在用的那一組放。舊的裝備組（沒有第 II 組）照常換。
 */
class GearPresetWeaponSetsTest {

    @SuppressWarnings("deprecation")
    private static final class Fake extends ItemStack {
        Fake() {
            super();
        }
    }

    @Test
    void presetsKeepBothWeaponSets() {
        final PlayerState state = new PlayerState(UUID.randomUUID());
        final ItemStack bow = new Fake(), quiver = new Fake(), staff = new Fake(), helmet = new Fake();
        state.jewellery()[Gear.MAIN] = staff; // 正在用第 II 組：法杖
        state.jewellery()[PlayerState.SPARE_MAIN_SLOT] = bow; // 第 I 組：弓＋箭袋
        state.jewellery()[PlayerState.SPARE_OFF_SLOT] = quiver;
        state.jewellery()[6] = helmet;
        state.progress().put(PlayerState.WEAPON_SET_KEY, "2");

        state.switchGearSet(0, 1); // 收進第 1 組，換上空的第 2 組
        final ItemStack[] stored = state.gearSet(0);
        assertSame(bow, stored[Gear.MAIN], "第 I 組放在主手那格");
        assertSame(quiver, stored[Gear.OFF]);
        assertSame(staff, stored[PlayerState.PRESET_SET2_MAIN], "第 II 組放在後面");
        assertSame(helmet, stored[6]);
        assertEquals("2", state.progress().get(PlayerState.gearSetWeaponKey(0)), "記下當時用第 II 組");
        assertNull(state.jewellery()[Gear.MAIN]);
        assertNull(state.jewellery()[PlayerState.SPARE_MAIN_SLOT], "另一組武器也收起來了");

        state.progress().put(PlayerState.WEAPON_SET_KEY, "1"); // 換上來時正在用第 I 組
        state.switchGearSet(1, 0);
        assertSame(bow, state.jewellery()[Gear.MAIN], "正在用第 I 組：弓放在武器欄");
        assertSame(quiver, state.jewellery()[Gear.OFF]);
        assertSame(staff, state.jewellery()[PlayerState.SPARE_MAIN_SLOT], "法杖是另一組");
        assertSame(helmet, state.jewellery()[6]);
        assertNull(state.gearSet(0)[PlayerState.PRESET_SET2_MAIN]);
    }

    @Test
    void oldPresetsWithoutSecondSetStillSwitch() {
        final PlayerState state = new PlayerState(UUID.randomUUID());
        final ItemStack sword = new Fake();
        state.gearSet(1)[Gear.MAIN] = sword; // 舊的裝備組：只有一組武器
        state.switchGearSet(0, 1);
        assertSame(sword, state.jewellery()[Gear.MAIN]);
        assertNull(state.jewellery()[PlayerState.SPARE_MAIN_SLOT]);
        assertEquals(PlayerState.GEAR_PRESET_SLOTS, state.gearSet(0).length);
    }
}
