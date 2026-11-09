package tw.exilecore.character;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長「武器切換系統」：兩組武器、武器組天賦、技能綁定、點數（PoE2：一般節點＋兩組裡多的那組）。 */
class WeaponSetsTest {

    /** 不碰伺服器的假物品（只比對是不是同一件）。 */
    @SuppressWarnings("deprecation")
    private static final class Fake extends ItemStack {
        Fake() {
            super();
        }
    }

    private static PlayerState state() {
        return new PlayerState(UUID.randomUUID());
    }

    @Test
    void swapExchangesWeaponsAndSetPassives() {
        final PlayerState state = state();
        final ItemStack bow = new Fake();
        final ItemStack quiver = new Fake();
        final ItemStack staff = new Fake();
        state.jewellery()[Gear.MAIN] = bow;
        state.jewellery()[Gear.OFF] = quiver;
        state.jewellery()[PlayerState.SPARE_MAIN_SLOT] = staff;
        state.passives().addAll(Set.of("common", "bow_node"));
        state.progress().put(WeaponSets.nodesKey(1), "bow_node");
        state.progress().put(WeaponSets.nodesKey(2), "staff_node");
        assertEquals(1, state.activeWeaponSet());

        assertEquals(2, WeaponSets.swap(state));
        assertEquals(2, state.activeWeaponSet());
        assertSame(staff, state.jewellery(Gear.MAIN), "武器欄換成第 II 組");
        assertEquals(null, state.jewellery(Gear.OFF), "第 II 組沒有副手");
        assertSame(bow, state.jewellery(PlayerState.SPARE_MAIN_SLOT), "第 I 組收起來（不生效）");
        assertSame(quiver, state.jewellery(PlayerState.SPARE_OFF_SLOT));
        assertEquals(Set.of("common", "staff_node"), state.passives(), "武器組天賦只有正在用那一組的生效");

        assertEquals(1, WeaponSets.swap(state));
        assertSame(bow, state.jewellery(Gear.MAIN));
        assertSame(quiver, state.jewellery(Gear.OFF));
        assertEquals(Set.of("common", "bow_node"), state.passives());
    }

    @Test
    void pointsCountCommonPlusTheBiggerSet() {
        final PlayerState state = state();
        state.passives().addAll(Set.of("a", "b", "c", "w1", "w2"));
        state.progress().put(WeaponSets.nodesKey(1), "w1,w2");
        state.progress().put(WeaponSets.nodesKey(2), "x1,x2,x3,x4,x5");
        assertEquals(3 + 5, WeaponSets.usedPoints(state), "PoB2：PointsUsed − min(ws1, ws2)");
        assertEquals(2, WeaponSets.used(state, 1));
        assertEquals(5, WeaponSets.used(state, 2));
        assertTrue(WeaponSets.activeSetNode(state, "w1"));
        assertFalse(WeaponSets.activeSetNode(state, "a"));
    }

    @Test
    void normalizeDropsStaleNodes() {
        final PlayerState state = state();
        state.passives().addAll(Set.of("a", "ghost_from_set2"));
        state.progress().put(WeaponSets.nodesKey(1), "refunded");
        state.progress().put(WeaponSets.nodesKey(2), "ghost_from_set2");
        WeaponSets.normalize(state);
        assertEquals(Set.of(), WeaponSets.nodes(state.progress(), 1), "正在用的那一組被退掉的節點拿掉");
        assertEquals(Set.of("a"), state.passives(), "另一組的節點不該在生效的配點裡");

        final PlayerState cleared = state();
        cleared.progress().put(WeaponSets.nodesKey(1), "x");
        cleared.progress().put(WeaponSets.nodesKey(2), "y");
        WeaponSets.normalize(cleared);
        assertTrue(WeaponSets.nodes(cleared.progress(), 1).isEmpty() && WeaponSets.nodes(cleared.progress(), 2).isEmpty(),
                "天賦全部退回（換職業、樹改版）：兩組一起清掉");
    }

    @Test
    void skillBindingCycles() {
        final PlayerState state = state();
        assertEquals(0, WeaponSets.binding(state.progress(), "fireball"), "沒設定＝用現在那一組");
        state.progress().put(WeaponSets.skillKey("fireball"), "2");
        assertEquals(2, WeaponSets.binding(state.progress(), "fireball"));
        assertEquals(1, WeaponSets.nextBinding(0));
        assertEquals(2, WeaponSets.nextBinding(1));
        assertEquals(0, WeaponSets.nextBinding(2));
    }

    @Test
    void oldSavesStillFit() {
        final int legacy = PlayerState.JEWELLERY_SLOTS.length + 6 + PlayerState.CHARM_SLOTS + 1;
        assertEquals(legacy + 2, PlayerState.STORED_SLOTS, "多兩格放另一組武器，接在最後面");
        assertTrue(PlayerState.SPARE_MAIN_SLOT >= legacy && PlayerState.SPARE_OFF_SLOT >= legacy, "舊存檔讀進來這兩格是空的，前面的位置都沒動");
        assertTrue(PlayerState.RING3_SLOT < legacy);
    }
}
