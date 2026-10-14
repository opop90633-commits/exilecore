package tw.exilecore.sponsor;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.Gear;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 裝備組（2026-10，月卡福利）：每個角色 {@value PlayerState#GEAR_SETS} 組裝備，每一組的裝備放在自己那一組裡；
 * 切換時現在裝著的收回原本那一組（還在，但不生效），換成那一組的裝備。只有正在用的那一組有效果。
 * 沒有月卡也能切回第 1 組、把其他組的裝備拿出來（不會卡住東西）。
 */
public final class GearSetMenu extends Menu {

    private static final int FIRST = 11;
    private static final int SLOT_BACK = 18;
    private static final int SLOT_CLOSE = 26;

    private final Runnable back;

    public GearSetMenu(final ExileCore plugin, final Player player, final Runnable back) {
        super(plugin, player, 3, "<dark_gray>裝備組（月卡）");
        this.back = back;
    }

    private boolean card() {
        return plugin.sponsors() != null && plugin.sponsors().cardActive(player);
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        final PlayerState state = plugin.players().get(player);
        final int active = state.activeGearSet();
        final boolean card = card();
        set(4, icon(Material.CLOCK, SponsorService.CARD_COLOR + "裝備組",
                "<gray>每一組的裝備放在自己那一組裡，切換時一起換",
                "<gray>只有正在用的那一組有效果；其他組的裝備還在，只是不生效",
                "<gray>在角色面板裝上、卸下的都是正在用的那一組",
                card ? "<green>月卡：可以隨時切換" : "<red>沒有月卡：只能切回第 1 組、把其他組的裝備拿出來"));
        for (int set = 0; set < PlayerState.GEAR_SETS; set++) {
            final boolean current = set == active;
            final ItemStack[] items = current ? state.jewellery() : state.gearSet(set);
            final List<String> lore = new ArrayList<>();
            lore.add(current ? "<green>使用中" : "<gray>放著（不生效）");
            int count = 0;
            for (int i = 0; i < PlayerState.JEWELLERY_SLOTS.length; i++) {
                final ItemStack stack = items[i];
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                count++;
                final Optional<ItemData> data = plugin.items().read(stack);
                lore.add("<gray>" + PlayerState.JEWELLERY_SLOTS[i].displayName() + "：<white>"
                        + ItemRenderer.escape(data.map(ItemData::name).orElse("?")));
            }
            if (count == 0) {
                lore.add("<dark_gray>空的");
            }
            lore.add("");
            if (!current) {
                lore.add(card || set == 0 ? "<yellow>左鍵：換成這一組" : "<dark_gray>要有月卡才能換成這一組");
                if (count > 0) {
                    lore.add("<yellow>Shift＋右鍵：把這一組的裝備拿到背包");
                }
            }
            final ItemStack icon = icon(current ? Material.DIAMOND_CHESTPLATE : count > 0 ? Material.IRON_CHESTPLATE : Material.LEATHER_CHESTPLATE,
                    (current ? "<green>" : "<white>") + "第 " + (set + 1) + " 組", lore);
            set(FIRST + set, current ? highlight(icon) : icon);
        }
        set(SLOT_BACK, backButton("角色"));
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            back.run();
            return;
        }
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        final int set = slot - FIRST;
        if (set < 0 || set >= PlayerState.GEAR_SETS) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return;
        }
        final int active = state.activeGearSet();
        if (set == active) {
            return;
        }
        if (click == ClickType.SHIFT_RIGHT) {
            takeOut(state, set);
            return;
        }
        if (click != ClickType.LEFT) {
            return;
        }
        if (!card() && set != 0) {
            fail("要有月卡才能換成第 " + (set + 1) + " 組（沒有月卡可以切回第 1 組）");
            return;
        }
        state.switchGearSet(active, set);
        plugin.characters().setProgress(player, PlayerState.GEAR_SET_KEY, String.valueOf(set));
        final Gear gear = plugin.characters().gear();
        gear.sync(player);
        plugin.players().sheet(player);
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        player.sendMessage(plugin.text().prefixed("<green>換成第 " + (set + 1) + " 組裝備"));
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.7f, 1.1f);
        redraw();
    }

    /** 把沒在用的那一組的裝備拿到背包（背包滿了就停）。 */
    private void takeOut(final PlayerState state, final int set) {
        final ItemStack[] items = state.gearSet(set);
        int moved = 0;
        boolean full = false;
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null || items[i].isEmpty()) {
                continue;
            }
            int free = -1;
            for (int slot = 0; slot < 36; slot++) {
                if (slot == Gear.HAND_SLOT) {
                    continue;
                }
                final ItemStack there = player.getInventory().getItem(slot);
                if (there == null || there.isEmpty()) {
                    free = slot;
                    break;
                }
            }
            if (free < 0) {
                full = true;
                break;
            }
            player.getInventory().setItem(free, items[i]);
            items[i] = null;
            moved++;
        }
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        player.sendMessage(plugin.text().prefixed("<green>第 " + (set + 1) + " 組：拿出 " + moved + " 件" + (full ? "<red>（背包滿了）" : "")));
        redraw();
    }

    private void fail(final String message) {
        player.sendMessage(plugin.text().prefixed("<red>" + message));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1f);
    }
}
