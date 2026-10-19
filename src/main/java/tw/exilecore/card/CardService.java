package tw.exilecore.card;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomyLedger;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.stash.Stash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * 星諭卡（2026-10-19）：掉落、兌換、星諭牌堆、賭卡。規則在 {@link CardConfig}（data/cards.yml），卡本身是通貨。
 * 只在主執行緒用。
 */
public final class CardService {

    private final ExileCore plugin;
    private final CardConfig config;

    public CardService(final ExileCore plugin, final CardConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public CardConfig config() {
        return config;
    }

    private RandomGenerator random() {
        return plugin.items().random();
    }

    /** 這種通貨是星諭卡（cards.yml 有寫）。 */
    public boolean isCard(final CurrencyType type) {
        return type != null && config.card(type.id()) != null;
    }

    /** 這種通貨是星諭牌堆。 */
    public static boolean isDeck(final CurrencyType type) {
        return type != null && CardConfig.DECK.equals(type.id());
    }

    /** 撿起來直接進倉庫的：星諭卡與牌堆。 */
    public boolean goesToStash(final CurrencyType type) {
        return isCard(type) || isDeck(type);
    }

    // ---- 掉落 ----

    /**
     * 地圖怪倒下時替一個人擲一張卡，擲到就加進他那份掉落（一般的掉落規則照算：撿取、過濾、掉落倍率）。地圖外的怪不掉。
     */
    public void rollKill(final Player owner, final LivingEntity entity, final MonsterData data, final List<DropTable.Drop> rolled) {
        if (owner == null || plugin.maps() == null || plugin.currencies() == null) {
            return;
        }
        final tw.exilecore.map.MapInstance instance = plugin.maps().instanceOf(entity);
        if (instance == null || instance.map() == null) {
            return;
        }
        if (instance.special() != null) {
            return; // 2026-10-19：劫盜、金庫之主這些特別的地方不掉卡（沒有自己的卡，只會抽到「任何地圖」的追逐卡）
        }
        final boolean boss = instance.boss() == entity;
        final CardConfig.Chances chances = config.chances();
        final double chance = boss ? (instance.usesAttempts() ? chances.finalBoss() : chances.boss()) : switch (data.rarity()) {
            case NORMAL -> chances.normal();
            case MAGIC -> chances.magic();
            case RARE -> chances.rare();
            case UNIQUE -> chances.unique();
        };
        if (chance <= 0 || random().nextDouble() >= chance) {
            return;
        }
        final int tier = Math.max(1, instance.stats().tier());
        final CardConfig.Card card = config.pickKill(config.forKill(instance.map().id(), tier, boss), random()); // 2026-10-19：沒有自己的卡的地方補「什麼都沒有」
        final CurrencyType type = card == null ? null : plugin.currencies().repository().get(card.id());
        if (type != null) {
            rolled.add(DropTable.Drop.currency(type));
        }
    }

    /** 完成地圖事件時替一個人擲一張那個事件的卡；擲到回傳那種通貨（由事件那邊交給他），沒有回傳 null。 */
    public CurrencyType rollEvent(final String event, final int tier) {
        if (plugin.currencies() == null || config.chances().event() <= 0 || random().nextDouble() >= config.chances().event()) {
            return null;
        }
        final CardConfig.Card card = CardConfig.pick(config.forEvent(event, Math.max(1, tier)), random());
        return card == null ? null : plugin.currencies().repository().get(card.id());
    }

    // ---- 兌換 ----

    /**
     * 從一疊卡（背包裡的、手上的）兌換：all = 這疊能換幾套換幾套，不然換一套。stack 會被扣掉用掉的張數。
     *
     * @return 換了幾套
     */
    public int exchangeStack(final Player player, final ItemStack stack, final boolean all) {
        final CurrencyType type = plugin.currencies().read(stack).orElse(null);
        final CardConfig.Card card = type == null ? null : config.card(type.id());
        if (card == null) {
            return 0;
        }
        final int sets = all ? stack.getAmount() / card.set() : Math.min(1, stack.getAmount() / card.set());
        if (sets <= 0) {
            notEnough(player, type, card, stack.getAmount());
            return 0;
        }
        stack.setAmount(stack.getAmount() - sets * card.set());
        reward(player, type, card, sets);
        return sets;
    }

    /** 背包（快捷列＋主背包）裡這種卡全部加起來兌換：all = 能換幾套換幾套。回傳換了幾套。 */
    public int exchangeInventory(final Player player, final CurrencyType type, final boolean all) {
        final CardConfig.Card card = config.card(type.id());
        if (card == null) {
            return 0;
        }
        final ItemStack[] contents = player.getInventory().getStorageContents();
        int have = 0;
        for (final ItemStack stack : contents) {
            if (stack != null && !stack.isEmpty() && plugin.currencies().read(stack).map(t -> t.id().equals(type.id())).orElse(false)) {
                have += stack.getAmount();
            }
        }
        final int sets = all ? have / card.set() : Math.min(1, have / card.set());
        if (sets <= 0) {
            notEnough(player, type, card, have);
            return 0;
        }
        int remove = sets * card.set();
        for (int slot = 0; slot < contents.length && remove > 0; slot++) {
            final ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty() || !plugin.currencies().read(stack).map(t -> t.id().equals(type.id())).orElse(false)) {
                continue;
            }
            final int take = Math.min(remove, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            player.getInventory().setItem(slot, stack.getAmount() <= 0 ? null : stack);
            remove -= take;
        }
        reward(player, type, card, sets);
        return sets;
    }

    /** 倉庫裡的這種卡兌換（倉庫「星諭卡」那一組：右鍵一套、Shift＋右鍵全部）。回傳換了幾套。 */
    public int exchangeStash(final Player player, final Stash stash, final CurrencyType type, final boolean all) {
        final CardConfig.Card card = config.card(type.id());
        if (card == null) {
            return 0;
        }
        final int have = stash.currency(type.id());
        final int sets = all ? have / card.set() : Math.min(1, have / card.set());
        if (sets <= 0) {
            notEnough(player, type, card, have);
            return 0;
        }
        stash.setCurrency(type.id(), have - sets * card.set());
        plugin.players().markDirty(player);
        reward(player, type, card, sets);
        return sets;
    }

    private void notEnough(final Player player, final CurrencyType type, final CardConfig.Card card, final int have) {
        player.sendActionBar(plugin.text().parse("<gray>" + ItemRenderer.escape(type.name()) + "：<white>" + have + " / " + card.set()
                + "<gray> 張，還差 " + Math.max(0, card.set() - have) + " 張"));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
    }

    /** 交出 sets 套卡（已經扣掉了），給獎勵、記流水帳、稀有以上全服公告。 */
    private void reward(final Player player, final CurrencyType type, final CardConfig.Card card, final int sets) {
        ledger().consumed(type.id(), EconomyLedger.CARDS, (long) sets * card.set());
        final List<String> got = new ArrayList<>();
        for (int i = 0; i < sets; i++) {
            final String one = giveReward(player, card.reward());
            if (one != null) {
                got.add(one);
            }
        }
        final String summary = summarize(got);
        player.sendMessage(plugin.text().prefixed("<#c9a0ff>✦ 交出 " + sets * card.set() + " 張" + ItemRenderer.escape(type.name())
                + "<gray>（" + sets + " 套）：<white>" + ItemRenderer.escape(summary)));
        player.playSound(player.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.8f, 1.1f);
        plugin.getSLF4JLogger().info("[星諭卡] {} 交出 {} 套 {}：{}", player.getName(), sets, type.name(), summary);
        if (card.tier().announced()) {
            final String name = plugin.chat() == null ? player.getName() : plugin.chat().display(player);
            final Component message = plugin.text().prefixed("<#c9a0ff>✦ " + ItemRenderer.escape(name) + " <light_purple>集滿了"
                    + card.tier().displayName() + "星諭卡 <#c9a0ff><bold>" + ItemRenderer.escape(type.name()) + "</bold><light_purple>，換到 <white>"
                    + ItemRenderer.escape(summary));
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                online.sendMessage(message);
                if (card.tier() == CardConfig.Tier.CHASE) {
                    online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.7f, 1.2f);
                }
            }
            if (card.tier() == CardConfig.Tier.CHASE && plugin.discord() != null) {
                plugin.discord().achievement(ItemRenderer.escape(name) + " 集滿了星諭卡「" + ItemRenderer.escape(type.name()) + "」，換到 "
                        + ItemRenderer.escape(summary) + "！");
            }
        }
    }

    /** 「至臻石 ×6、守墓巨鎚」：同名的合併數量。 */
    private static String summarize(final List<String> got) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final String one : got) {
            final int cross = one.lastIndexOf(" ×");
            final String name = cross > 0 ? one.substring(0, cross) : one;
            final int amount = cross > 0 ? Integer.parseInt(one.substring(cross + 2)) : 1;
            counts.merge(name, amount, Integer::sum);
        }
        final List<String> parts = new ArrayList<>();
        counts.forEach((name, amount) -> parts.add(amount > 1 ? name + " ×" + amount : name));
        return parts.isEmpty() ? "（沒有東西）" : String.join("、", parts);
    }

    /** 給一份獎勵；回傳「名稱 ×數量」（給不出來回傳 null，寫進紀錄）。 */
    private String giveReward(final Player player, final CardConfig.Reward reward) {
        switch (reward.kind()) {
            case "currency" -> {
                final CurrencyType type = plugin.currencies().repository().get(reward.id());
                if (type == null) {
                    plugin.getSLF4JLogger().warn("[星諭卡] 獎勵的通貨 {} 不存在", reward.id());
                    return null;
                }
                giveCurrency(player, type, reward.amount());
                return type.name() + " ×" + reward.amount();
            }
            case "essence" -> {
                final List<CurrencyType> essences = new ArrayList<>();
                for (final CurrencyType type : plugin.currencies().repository().all()) {
                    if (type.operation() == CurrencyOperation.ESSENCE) {
                        essences.add(type);
                    }
                }
                if (essences.isEmpty()) {
                    return null;
                }
                final List<String> names = new ArrayList<>();
                for (int i = 0; i < reward.amount(); i++) {
                    final CurrencyType type = essences.get(random().nextInt(essences.size()));
                    giveCurrency(player, type, 1);
                    names.add(type.name());
                }
                return String.join("、", names);
            }
            case "unique" -> {
                return giveUnique(player, plugin.items().repository().unique(reward.id()));
            }
            case "unique_pool" -> {
                return giveUnique(player, ItemGenerator.pickUnique(plugin.items().repository().uniquesFor(reward.id(), config.itemLevel()), random()));
            }
            default -> {
                return null;
            }
        }
    }

    private void giveCurrency(final Player player, final CurrencyType type, final int amount) {
        ledger().produced(type.id(), EconomyLedger.CARDS, amount);
        int remaining = amount;
        while (remaining > 0) {
            final int batch = Math.min(remaining, type.stack());
            final ItemStack item = plugin.currencies().create(type, batch);
            final ItemStack rest = plugin.loot() != null ? plugin.loot().stowCurrency(player, plugin.players().get(player), type, item)
                    : plugin.economy().wallet().give(player, item);
            plugin.economy().wallet().dropFor(player, rest);
            remaining -= batch;
        }
    }

    private String giveUnique(final Player player, final UniqueItem unique) {
        if (unique == null) {
            plugin.getSLF4JLogger().warn("[星諭卡] 獎勵的傳奇不存在或池子是空的");
            return null;
        }
        final ItemData data = plugin.items().generator().generateUnique(unique, Math.max(config.itemLevel(), unique.dropLevel()), random());
        final ItemStack stack = plugin.items().toStack(data);
        plugin.economy().wallet().dropFor(player, plugin.economy().wallet().give(player, stack));
        if (plugin.loot() != null) {
            plugin.loot().broadcastChase(player, data); // 追逐級傳奇：全服廣播（不是追逐級的不廣播）
        }
        return unique.name();
    }

    // ---- 星諭牌堆 ----

    /**
     * 打開 count 個牌堆（已經扣掉了），每個照權重變成一張卡，直接進倉庫（倉庫滿了進通貨袋、背包）。回傳開到的卡（名稱 → 張數）。
     */
    public Map<String, Integer> openDecks(final Player player, final int count) {
        final Map<String, Integer> got = new LinkedHashMap<>();
        if (count <= 0) {
            return got;
        }
        ledger().consumed(CardConfig.DECK, EconomyLedger.CARDS, count);
        final Map<String, Integer> byId = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            final CardConfig.Card card = config.pickAny(random());
            if (card != null) {
                byId.merge(card.id(), 1, Integer::sum);
            }
        }
        for (final Map.Entry<String, Integer> entry : byId.entrySet()) {
            final CurrencyType type = plugin.currencies().repository().get(entry.getKey());
            if (type == null) {
                continue;
            }
            ledger().produced(type.id(), EconomyLedger.CARDS, entry.getValue());
            deposit(player, type, entry.getValue());
            got.put(type.name(), entry.getValue());
        }
        final List<String> parts = new ArrayList<>();
        got.forEach((name, amount) -> parts.add(amount > 1 ? name + " ×" + amount : name));
        player.sendMessage(plugin.text().prefixed("<#c9a0ff>✦ 打開 " + count + " 個星諭牌堆：<white>" + ItemRenderer.escape(String.join("、", parts))
                + "<gray>（放進倉庫的「星諭卡」）"));
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.3f);
        return got;
    }

    /** 從一疊牌堆打開：all = 整疊，不然一個。stack 會被扣。回傳打開幾個。 */
    public int openStack(final Player player, final ItemStack stack, final boolean all) {
        final int count = all ? stack.getAmount() : Math.min(1, stack.getAmount());
        if (count <= 0) {
            return 0;
        }
        stack.setAmount(stack.getAmount() - count);
        openDecks(player, count);
        return count;
    }

    /** 從倉庫打開牌堆：all = 全部，不然一個。 */
    public int openStash(final Player player, final Stash stash, final boolean all) {
        final int have = stash.currency(CardConfig.DECK);
        final int count = all ? have : Math.min(1, have);
        if (count <= 0) {
            return 0;
        }
        stash.setCurrency(CardConfig.DECK, have - count);
        plugin.players().markDirty(player);
        openDecks(player, count);
        return count;
    }

    // ---- 賭卡 ----

    /** 賭這一疊（倉庫裡這種卡的全部）要多少金幣。 */
    public long gambleCost(final CurrencyType type, final int count) {
        final CardConfig.Card card = config.card(type.id());
        return card == null ? 0 : config.gambleCost(card.tier()) * Math.max(0, count);
    }

    /** 2026-10-19 跟 PoE 一樣：一次只賭「一疊」，一疊最多是一套的張數（不滿一套也能賭）。 */
    public int gambleCount(final CurrencyType type, final int have) {
        final CardConfig.Card card = config.card(type.id());
        return card == null ? 0 : Math.max(0, Math.min(have, card.set()));
    }

    /**
     * 賭卡（PoE 花園）：倉庫裡這種卡拿一疊 n 張（最多一套的張數，{@link #gambleCount}），花 {@link #gambleCost} 金幣，變成 0～2n 張（每個整數一樣機率）。
     *
     * @return 結果的張數；沒辦法賭（沒卡、錢不夠）回傳空的
     */
    public Optional<Integer> gamble(final Player player, final Stash stash, final CurrencyType type) {
        final CardConfig.Card card = config.card(type.id());
        final int all = stash.currency(type.id());
        final int have = gambleCount(type, all); // 這次賭的張數（一疊）
        if (card == null || have <= 0) {
            return Optional.empty();
        }
        final long cost = gambleCost(type, have);
        if (!plugin.gold().take(player, cost, EconomyLedger.CARD_GAMBLE)) {
            player.sendMessage(plugin.text().prefixed("<red>金幣不夠：賭 " + have + " 張" + ItemRenderer.escape(type.name()) + "要 "
                    + tw.exilecore.economy.GoldService.format(cost) + " 金幣"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return Optional.empty();
        }
        final int result = CardConfig.gamble(have, random());
        ledger().consumed(type.id(), EconomyLedger.CARD_GAMBLE, have);
        ledger().produced(type.id(), EconomyLedger.CARD_GAMBLE, result);
        stash.setCurrency(type.id(), all - have);
        final int overflow = result - stash.depositCurrency(type.id(), result);
        if (overflow > 0) {
            final int rest = plugin.economy().wallet().deposit(player, type.id(), overflow); // 倉庫放不下：通貨袋、背包
            if (rest > 0) {
                plugin.economy().wallet().dropFor(player, plugin.currencies().create(type, rest));
            }
        }
        plugin.players().markDirty(player);
        final String verdict = result == 0 ? "<red>全部沒了" : result > have ? "<green>變多了" : result == have ? "<yellow>不多不少" : "<gold>變少了";
        player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<#c9a0ff>✦ 賭卡：<white>%d 張%s → %d 張　%s",
                have, ItemRenderer.escape(type.name()), result, verdict)));
        player.playSound(player.getLocation(), result >= have ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_FIRE_EXTINGUISH, 0.7f, 1.2f);
        plugin.getSLF4JLogger().info("[星諭卡] {} 賭 {} 張 {}（{} 金幣）→ {} 張", player.getName(), have, type.name(), cost, result);
        return Optional.of(result);
    }

    // ---- 共用 ----

    /** 卡、牌堆放進倉庫（滿了進通貨袋、背包，再滿掉在腳下只有他撿得到）。 */
    public void deposit(final Player player, final CurrencyType type, final int amount) {
        final int rest = plugin.economy().wallet().deposit(player, type.id(), amount);
        if (rest > 0) {
            plugin.economy().wallet().dropFor(player, plugin.currencies().create(type, rest));
        }
        plugin.players().markDirty(player);
    }

    private EconomyLedger ledger() {
        return plugin.economy().ledger();
    }

    /** 事件那邊交卡用（{@link tw.exilecore.map.MapEvents}）。 */
    public static int tierAt(final ExileCore plugin, final Location at) {
        if (plugin.maps() == null || at == null) {
            return 1;
        }
        final tw.exilecore.map.MapInstance instance = plugin.maps().instanceAt(at);
        return instance == null ? 1 : Math.max(1, instance.stats().tier());
    }
}
