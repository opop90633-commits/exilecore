package tw.exilecore.economy;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CurrencyPouch;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.stash.Stash;

import java.util.Locale;
import java.util.Optional;

/**
 * 玩家「付通貨、收通貨、收東西」的共同做法（市集、通貨交易所、面對面交易用）：
 * <ul>
 *   <li>付款：先拿隨身背包的通貨袋，再拿背包裡的，最後拿倉庫的通貨頁。</li>
 *   <li>收通貨：先放倉庫的通貨頁（帳號共用），滿了放通貨袋，再滿放背包。</li>
 *   <li>收物品：背包主要格子 → 隨身背包（跟撿東西一樣）。</li>
 * </ul>
 * 只在主執行緒用；改完記得用 CharacterService.commit 或 save 存檔。
 */
public final class Wallet {

    /** 背包：快捷列＋主背包（不含身上穿的與副手）。 */
    private static final int BACKPACK = 36;

    private final ExileCore plugin;

    /**
     * 2026-10-25：通貨交易所的「星幣」（帳號裡的贊助星幣，不是物品）。這裡只負責數量與名稱；
     * 拿走、存入要跟交易所的資料一起寫進同一個交易，在 MarketService 另外處理（take、deposit 不碰它）。
     */
    public static final String STAR_COIN = "star_coin";

    public Wallet(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 通貨袋＋背包＋倉庫通貨頁裡有幾個這種通貨。 */
    public int available(final Player player, final String currencyId) {
        if (STAR_COIN.equals(currencyId)) {
            return plugin.sponsors() == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(0, plugin.sponsors().coins(player)));
        }
        return pouch(player, currencyId) + inventory(player, currencyId) + stash(player, currencyId);
    }

    public int pouch(final Player player, final String currencyId) {
        return plugin.players().get(player).bag().pouch().amount(currencyId);
    }

    public int inventory(final Player player, final String currencyId) {
        int count = 0;
        final PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < BACKPACK; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (is(stack, currencyId)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    public int stash(final Player player, final String currencyId) {
        return stashOf(player).map(stash -> stash.currency(currencyId)).orElse(0);
    }

    /** 拿走 amount 個（通貨袋 → 背包 → 倉庫）。不夠回傳 false，什麼都不拿。 */
    public boolean take(final Player player, final String currencyId, final int amount) {
        if (amount <= 0) {
            return true;
        }
        if (STAR_COIN.equals(currencyId)) {
            return false; // 星幣不從這裡扣（MarketService 跟交易所的資料一起寫）
        }
        if (available(player, currencyId) < amount) {
            return false;
        }
        int remaining = amount;
        final CurrencyPouch pouch = plugin.players().get(player).bag().pouch();
        remaining -= pouch.withdraw(currencyId, remaining);
        final PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < BACKPACK && remaining > 0; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (!is(stack, currencyId)) {
                continue;
            }
            final int taken = Math.min(remaining, stack.getAmount());
            inventory.setItem(slot, stack.getAmount() - taken <= 0 ? null : stack.asQuantity(stack.getAmount() - taken));
            remaining -= taken;
        }
        if (remaining > 0) {
            final Optional<Stash> stash = stashOf(player);
            if (stash.isPresent()) {
                final int have = stash.get().currency(currencyId);
                final int taken = Math.min(have, remaining);
                stash.get().setCurrency(currencyId, have - taken);
                remaining -= taken;
            }
        }
        if (remaining > 0) {
            // 上面算過夠：走到這裡代表數到一半變了（不應該發生），記一筆
            plugin.getSLF4JLogger().error("付款少拿了 {} 個 {}（{}）", remaining, currencyId, player.getName());
        }
        return true;
    }

    /**
     * 存入 amount 個（倉庫通貨頁 → 通貨袋 → 背包）。
     *
     * @return 放不下的數量
     */
    public int deposit(final Player player, final String currencyId, final int amount) {
        if (STAR_COIN.equals(currencyId)) {
            return Math.max(0, amount); // 星幣不從這裡存（不然會被當成通貨塞進倉庫通貨頁）
        }
        int remaining = Math.max(0, amount);
        final Optional<Stash> stash = stashOf(player);
        if (stash.isPresent()) {
            remaining -= stash.get().depositCurrency(currencyId, remaining);
        }
        if (remaining > 0) {
            remaining -= plugin.players().get(player).bag().pouch().deposit(currencyId, remaining);
        }
        final CurrencyType type = plugin.currencies().repository().get(currencyId);
        while (remaining > 0 && type != null) {
            final int batch = Math.min(remaining, type.stack());
            int notGiven = 0;
            for (final ItemStack rest : player.getInventory().addItem(plugin.currencies().create(type, batch)).values()) {
                notGiven += rest.getAmount();
            }
            remaining -= batch - notGiven;
            if (notGiven > 0) {
                break;
            }
        }
        return remaining;
    }

    /**
     * 這件東西整件收得下嗎（跟 {@link #give} 同一套順序算）。先檢查、收得下才收錢，
     * 就不會發生「只放進去一部分、剩下的又放回商店頁」這種一件變兩件的事。
     */
    public boolean fits(final Player player, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        if (plugin.loot() != null) {
            return plugin.loot().fits(player, plugin.players().get(player), stack);
        }
        // 掉落模組沒啟用：give 用原版的 addItem（背包 0–35，先疊上去再放空格）
        int room = 0;
        final PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < BACKPACK && room < stack.getAmount(); slot++) {
            final ItemStack current = inventory.getItem(slot);
            if (current == null || current.isEmpty()) {
                room += stack.getMaxStackSize();
            } else if (current.isSimilar(stack)) {
                room += Math.max(0, current.getMaxStackSize() - current.getAmount());
            }
        }
        return room >= stack.getAmount();
    }

    /**
     * 給一件東西：背包主要格子 → 隨身背包（掉落自動撿取同一套）；掉落模組沒啟用時放背包。
     * 先用 {@link #fits} 檢查過就會全部放下。
     *
     * @return 放不下的部分（全部放下回傳 null）
     */
    public ItemStack give(final Player player, final ItemStack stack) {
        if (plugin.loot() != null) {
            return plugin.loot().stow(player, plugin.players().get(player), stack);
        }
        int rest = 0;
        ItemStack sample = null;
        for (final ItemStack leftover : player.getInventory().addItem(stack.clone()).values()) {
            rest += leftover.getAmount();
            sample = leftover;
        }
        return sample == null ? null : sample.asQuantity(rest);
    }

    /** 萬一放不下（檢查過不應該發生）：掉在腳下，只有自己看得到、撿得到，不會消失。 */
    public void dropFor(final Player player, final ItemStack rest) {
        if (rest == null || rest.isEmpty()) {
            return;
        }
        plugin.personalDrops().dropForever(player, rest);
        plugin.getSLF4JLogger().warn("{} 的背包放不下 {}，掉在腳下", player.getName(), describe(rest));
    }

    private Optional<Stash> stashOf(final Player player) {
        return plugin.stash() == null ? Optional.empty() : plugin.stash().stash(player);
    }

    private boolean is(final ItemStack stack, final String currencyId) {
        return stack != null && !stack.isEmpty() && plugin.currencies().read(stack).map(type -> type.id().equals(currencyId)).orElse(false);
    }

    // ---- 文字 ----

    /** 物品給人看的名稱（純文字，給紀錄用）。 */
    public static String describe(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "（空）";
        }
        final String name = PlainTextComponentSerializer.plainText().serialize(stack.effectiveName());
        return stack.getAmount() > 1 ? name + " ×" + stack.getAmount() : name;
    }

    /** 「3 至臻石」這種說法。 */
    public String price(final String currencyId, final int amount) {
        final CurrencyType type = plugin.currencies().repository().get(currencyId);
        return String.format(Locale.ROOT, "%,d %s", amount, type == null ? (STAR_COIN.equals(currencyId) ? "星幣" : currencyId) : type.name());
    }
}
