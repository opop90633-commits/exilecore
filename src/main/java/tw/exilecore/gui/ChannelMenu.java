package tw.exilecore.gui;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.world.TownChannels;

import java.util.List;

/**
 * 主城分流（2026-10-22，選單的「分流」）：每條線幾個人，點一條換過去。
 * 在營地裡馬上換；不在營地就記下來，下次回營地（/town、死掉重生、登入）到那一條。人多也不會被拉去別條線。
 */
public final class ChannelMenu extends Menu {

    /** 第 1～9 線放在第 2 排中間開始。 */
    private static final int FIRST_SLOT = 19;
    private static final int SLOT_CLOSE = SLOT_CLOSE_BAR;

    public ChannelMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 6, "<dark_gray>分流");
    }

    private TownChannels channels() {
        return plugin.world() == null || plugin.world().worlds() == null ? null : plugin.world().worlds().channels();
    }

    @Override
    protected void draw() {
        frame();
        final TownChannels channels = channels();
        if (channels == null || !channels.enabled()) {
            set(22, icon(Material.BARRIER, "<gray>這個伺服器的營地沒有分線"));
            set(SLOT_CLOSE, closeButton());
            return;
        }
        final List<World> towns = channels.towns();
        final World here = player.getWorld();
        final int remembered = channels.remembered(player);
        final boolean inTown = plugin.zones() != null && plugin.zones().inTown(player.getLocation());
        for (int i = 0; i < towns.size() && i < TownChannels.MAX_CHANNELS; i++) {
            final World world = towns.get(i);
            final int channel = i + 1;
            final int count = channels.population(world);
            final boolean current = inTown && world.equals(here);
            final boolean chosen = !inTown && remembered == channel;
            final String state = current ? "<green>你在這一線" : chosen ? "<green>下次回營地到這一線" : inTown ? "<yellow>左鍵：換到這一線"
                    : "<yellow>左鍵：下次回營地到這一線";
            final org.bukkit.inventory.ItemStack stack = icon(current || chosen ? Material.LIME_CONCRETE : count >= 30 ? Material.RED_CONCRETE
                            : count >= 15 ? Material.YELLOW_CONCRETE : Material.WHITE_CONCRETE,
                    (current || chosen ? "<green>" : "<#f5b942>") + "第 " + channel + " 線",
                    "<gray>現在 <white>" + count + "</white> 人", "", state);
            set(FIRST_SLOT + i, withCount(current || chosen ? highlight(stack) : stack, Math.max(1, Math.min(64, count))));
        }
        set(SLOT_HELP, helpButton(List.of("營地分成好幾條線，人比較分散、比較不卡",
                "在營地裡點一條線馬上換過去；在外面點的話，下次回營地會到那一條",
                "上次待的線會記住，人多也不會把你換走",
                "<dark_gray>指令：/channel、/channel 線")));
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        final TownChannels channels = channels();
        if (channels == null || !channels.enabled()) {
            return;
        }
        final int channel = slot - FIRST_SLOT + 1;
        final World target = channels.world(channel);
        if (target == null) {
            return;
        }
        final boolean inTown = plugin.zones() != null && plugin.zones().inTown(player.getLocation());
        if (inTown) {
            player.closeInventory();
            plugin.zones().switchChannel(player, channel);
            return;
        }
        channels.remember(player, target);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
        player.sendMessage(plugin.text().prefixed("<gray>下次回營地會到第 <white>" + channel + "</white> 線"));
        redraw();
    }
}
