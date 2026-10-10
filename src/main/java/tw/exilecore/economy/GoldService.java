package tw.exilecore.economy;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemRenderer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 金幣（PoE2 做法）：怪物掉在地上的一堆金幣，走過去自動撿起來，不占背包格。
 * 餘額是帳號共用的（accounts.gold，所有角色看到同一個數字），跟著角色存檔一起寫；
 * 退點、商人、賭博，以及市集、通貨交易所、面對面交易的手續費都花這個。金幣本身不能交易（照 PoE2）。
 */
public final class GoldService {

    private final ExileCore plugin;
    private final EconomyLedger ledger;
    private final NamespacedKey goldKey;

    public GoldService(final ExileCore plugin, final EconomyLedger ledger) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.goldKey = new NamespacedKey(plugin, "gold");
    }

    /** 做出一堆金幣的地上物品。 */
    public ItemStack createPile(final long amount) {
        final ItemStack stack = ItemStack.of(Material.GOLD_NUGGET);
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line(String.format(Locale.ROOT, "<#f5b942>%,d 金幣", amount)));
            meta.lore(List.of(ItemRenderer.line("<gray>走過去自動撿起")));
            meta.setMaxStackSize(1);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(goldKey, PersistentDataType.LONG, amount);
        });
        return stack;
    }

    /** 這個物品是不是金幣；是的話回傳金額（一疊裡的每一個都算）。 */
    public Optional<Long> amountOf(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        final Long amount = stack.getPersistentDataContainer().get(goldKey, PersistentDataType.LONG);
        return amount == null ? Optional.empty() : Optional.of(amount * Math.max(1, stack.getAmount()));
    }

    /**
     * 在地上丟一堆金幣。有 owner 時永遠只有他看得到、撿得到（照 PoE2：金幣是每個人各自的，隊友也看不到；
     * 不像裝備 60 秒後誰都能撿——金幣不能交易，也不能經過地上換手）。沒有 owner（沒有玩家有份的怪）誰都能撿。
     */
    public Item drop(final Location location, final long amount, final Player owner) {
        final var personal = plugin.personalDrops();
        final Item item = location.getWorld().dropItemNaturally(location, createPile(amount), created -> {
            created.setPickupDelay(5);
            if (owner != null) {
                personal.prepareForever(created, owner.getUniqueId());
            }
        });
        item.customName(ItemRenderer.line(String.format(Locale.ROOT, "<#f5b942>%,d 金幣", amount)));
        item.setCustomNameVisible(true);
        if (owner != null) {
            personal.spawned(item, owner);
        }
        return item;
    }

    /**
     * 加到帳號餘額，不記流水帳（呼叫端自己分來源記，例如擊殺時怪物的金幣與過濾換的金幣分開記）。
     * 沒角色回傳 false（金幣會留在地上：沒角色就不會存檔）。
     */
    public boolean add(final Player player, final long amount) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return false;
        }
        state.setGold(state.gold() + amount);
        state.notice(String.format(Locale.ROOT, "<#f5b942>+%,d 金幣", amount), plugin.getServer().getCurrentTick() + 40);
        return true;
    }

    /** 加到帳號餘額，並記一筆產出（source 見 {@link EconomyLedger}）。沒角色回傳 false。 */
    public boolean earn(final Player player, final long amount, final String source) {
        if (!add(player, amount)) {
            return false;
        }
        ledger.gold(source, amount);
        return true;
    }

    /** 扣錢並記一筆消耗（sink 見 {@link EconomyLedger}）；不夠（或沒角色）回傳 false，什麼都不扣。 */
    public boolean take(final Player player, final long amount, final String sink) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter() || amount < 0 || state.gold() < amount) {
            return false;
        }
        state.setGold(state.gold() - amount);
        ledger.gold(sink, -amount);
        return true;
    }

    /** 退回剛剛 {@link #take} 的錢（後面的步驟沒成立）：不跳「+金幣」的提示，這筆錢根本沒離開過；流水帳也扣回來。 */
    public void refund(final Player player, final long amount, final String sink) {
        final PlayerState state = plugin.players().get(player);
        if (amount > 0 && state.isLoaded() && state.hasCharacter()) {
            state.setGold(state.gold() + amount);
            ledger.gold(sink, amount);
        }
    }

    /** 帳號的金幣（還沒載入回傳 0）。 */
    public long balance(final Player player) {
        final PlayerState state = plugin.players().get(player);
        return state.isLoaded() ? state.gold() : 0;
    }

    /** 金幣的顯示字串（千分位）。 */
    public static String format(final long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }
}
