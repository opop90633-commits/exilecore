package tw.exilecore.hook;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PacketEvents 的監聽（只有裝了 PacketEvents 才會載入這個類別）：送給玩家的物品格子（整個視窗、一格、游標、自己的背包格）
 * 照 {@link TooltipHook#extraLore} 多加幾行說明。只改這個封包的複本，伺服器上的物品不動。
 */
final class PacketTooltips extends PacketListenerAbstract {

    private PacketTooltips() {
        super(PacketListenerPriority.NORMAL);
    }

    static Object register() {
        return PacketEvents.getAPI().getEventManager().registerListener(new PacketTooltips());
    }

    static void unregister(final Object listener) {
        PacketEvents.getAPI().getEventManager().unregisterListener((PacketListenerCommon) listener);
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        final PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Server.WINDOW_ITEMS && type != PacketType.Play.Server.SET_SLOT
                && type != PacketType.Play.Server.SET_CURSOR_ITEM && type != PacketType.Play.Server.SET_PLAYER_INVENTORY) {
            return;
        }
        final Object who = event.getPlayer();
        if (!(who instanceof Player player)) {
            return;
        }
        try {
            if (type == PacketType.Play.Server.WINDOW_ITEMS) {
                final WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
                boolean changed = false;
                final List<ItemStack> items = new ArrayList<>(wrapper.getItems());
                for (int i = 0; i < items.size(); i++) {
                    final ItemStack decorated = decorate(player, items.get(i));
                    if (decorated != null) {
                        items.set(i, decorated);
                        changed = true;
                    }
                }
                if (changed) {
                    wrapper.setItems(items);
                }
                final Optional<ItemStack> carried = wrapper.getCarriedItem();
                if (carried != null && carried.isPresent()) {
                    final ItemStack decorated = decorate(player, carried.get());
                    if (decorated != null) {
                        wrapper.setCarriedItem(decorated);
                        changed = true;
                    }
                }
                if (changed) {
                    event.markForReEncode(true);
                }
            } else if (type == PacketType.Play.Server.SET_SLOT) {
                final WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
                final ItemStack decorated = decorate(player, wrapper.getItem());
                if (decorated != null) {
                    wrapper.setItem(decorated);
                    event.markForReEncode(true);
                }
            } else if (type == PacketType.Play.Server.SET_CURSOR_ITEM) {
                final WrapperPlayServerSetCursorItem wrapper = new WrapperPlayServerSetCursorItem(event);
                final ItemStack decorated = decorate(player, wrapper.getStack());
                if (decorated != null) {
                    wrapper.setStack(decorated);
                    event.markForReEncode(true);
                }
            } else {
                final WrapperPlayServerSetPlayerInventory wrapper = new WrapperPlayServerSetPlayerInventory(event);
                final ItemStack decorated = decorate(player, wrapper.getStack());
                if (decorated != null) {
                    wrapper.setStack(decorated);
                    event.markForReEncode(true);
                }
            }
        } catch (final RuntimeException ignored) {
            // 封包讀不懂：照原本的送
        }
    }

    /** 要加說明的物品回傳加好的複本；不用加回傳 null。 */
    private static ItemStack decorate(final Player player, final ItemStack stack) {
        if (stack == null) {
            return null;
        }
        final org.bukkit.inventory.ItemStack bukkit = SpigotConversionUtil.toBukkitItemStack(stack);
        if (bukkit == null || bukkit.isEmpty()) {
            return null;
        }
        final List<Component> extra = TooltipHook.extraLore(player, bukkit);
        if (extra.isEmpty()) {
            return null;
        }
        final List<Component> lore = bukkit.lore() == null ? new ArrayList<>() : new ArrayList<>(bukkit.lore());
        lore.addAll(extra);
        bukkit.lore(lore);
        return SpigotConversionUtil.fromBukkitItemStack(bukkit);
    }
}
