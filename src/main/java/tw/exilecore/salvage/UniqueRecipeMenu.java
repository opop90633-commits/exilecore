package tw.exilecore.salvage;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import tw.exilecore.ExileCore;
import tw.exilecore.economy.TradeLogRepository;
import tw.exilecore.economy.Wallet;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.UniqueItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 傳奇三換一（拆解台左下的按鈕打開）：上面 4 列是你的背包（跟拆解台一樣：前 3 列＝背包、第 4 列＝快捷列），
 * 點三件傳奇放進來，按「換一件」再確認一次，就拿走那三件、給一件隨機傳奇（池子與規則見 {@link UniqueRecipe}）。
 * 換之前再核對每一格還是同一件（物品 UUID），中途換過位置、鎖起來的不會被拿走。身上穿的、飾品欄位不會出現在這裡。
 * 放的是追逐級（2026-10）：必定換一件隨機追逐級（不能跟一般傳奇混放）。
 */
public final class UniqueRecipeMenu extends Menu {

    private static final int MIRROR_SLOTS = 36;
    private static final int SLOT_SUMMARY = 45;
    private static final int SLOT_CLEAR = 47;
    private static final int SLOT_CONFIRM = 49;
    private static final int SLOT_BACK = 51;
    private static final int SLOT_CLOSE = 53;
    /** 說明裡一行列幾個傳奇的名字。 */
    private static final int NAMES_PER_LINE = 6;

    /** 背包格 → 放進來時那件的 UUID（照點的順序）。 */
    private final Map<Integer, String> selected = new LinkedHashMap<>();
    private final UniqueRecipe.Settings settings;

    public UniqueRecipeMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 6, "<dark_gray>" + plugin.salvage().recipe().title());
        this.settings = plugin.salvage().recipe();
    }

    /** 放進來的一件（核對過還在原位）。 */
    private record Input(int slot, ItemStack stack, ItemData data) {
    }

    @Override
    protected void draw() {
        final PlayerInventory inventory = player.getInventory();
        // 已經不在原位（或鎖起來、不能放了）的拿出來
        selected.entrySet().removeIf(entry -> !entry.getValue().equals(eligibleUuid(inventory.getItem(entry.getKey()))));
        for (int slot = 0; slot < MIRROR_SLOTS; slot++) {
            final int inventorySlot = SalvageMenu.inventorySlot(slot);
            final ItemStack stack = inventory.getItem(inventorySlot);
            if (stack == null || stack.isEmpty()) {
                set(slot, null);
                continue;
            }
            final Optional<ItemData> data = plugin.items().read(stack);
            if (data.isEmpty() || data.get().rarity() != Rarity.UNIQUE) {
                set(slot, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>不是傳奇", "<dark_gray>這裡只能放傳奇"));
                continue;
            }
            set(slot, preview(stack, data.get(), selected.containsKey(inventorySlot)));
        }
        for (int slot = MIRROR_SLOTS; slot < 54; slot++) {
            set(slot, filler());
        }
        set(SLOT_SUMMARY, summaryIcon());
        set(SLOT_CLEAR, icon(Material.MILK_BUCKET, "<gray>清除選擇", "<yellow>左鍵：全部拿出來"));
        set(SLOT_CONFIRM, confirmIcon());
        set(SLOT_BACK, backButton("拆解台"));
        set(SLOT_CLOSE, closeButton());
    }

    /** 這件能放的話回傳它的 UUID；不是傳奇、不能放的回傳 null。 */
    private String eligibleUuid(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty() || problem(stack, data.get()) != null) {
            return null;
        }
        return data.get().uuid();
    }

    /** 這一格為什麼不能放；可以放回傳 null。一格疊了好幾件的（不該有）也不收。 */
    private String problem(final ItemStack stack, final ItemData data) {
        final String problem = UniqueRecipe.problem(data, plugin.items().repository().unique(data.unique()));
        if (problem != null) {
            return problem;
        }
        return stack.getAmount() != 1 ? "這一格疊了 " + stack.getAmount() + " 件，一次只能放一件" : null;
    }

    private ItemStack preview(final ItemStack stack, final ItemData data, final boolean chosen) {
        final ItemStack copy = stack.clone();
        final String problem = problem(stack, data);
        final List<String> lines = new ArrayList<>();
        lines.add("");
        if (problem != null) {
            lines.add("<red>✘ 不能放：" + ItemRenderer.escape(problem));
        } else if (chosen) {
            lines.add("<green>✔ 已放進來（再點一次拿出來）");
        } else {
            lines.add("<yellow>左鍵：放進來");
            lines.add("<dark_gray>右鍵：鎖定（之後不會被拿去換）");
        }
        appendLore(copy, lines);
        if (!chosen) {
            return copy;
        }
        copy.editMeta(meta -> {
            final Component name = meta.hasDisplayName() ? meta.displayName() : null;
            if (name != null) {
                meta.displayName(ItemRenderer.line("<green>✔ ").append(name));
            }
        });
        if (frames(copy)) {
            return highlight(copy); // 放進來的：外框
        }
        // 畫不出外框（沒套用資源包）：換成綠色玻璃片（傳奇本來就會發光，只靠發光分不出放了沒）
        final ItemStack pane = ItemStack.of(Material.LIME_STAINED_GLASS_PANE);
        final ItemMeta source = copy.getItemMeta();
        pane.editMeta(meta -> {
            if (source.hasDisplayName()) {
                meta.displayName(source.displayName());
            }
            meta.lore(source.lore());
        });
        return pane;
    }

    /** 放進來的那幾件（還在原位、還能放的；同一個 UUID 只算一次）。 */
    private List<Input> current() {
        final List<Input> inputs = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (final Map.Entry<Integer, String> entry : selected.entrySet()) {
            final ItemStack stack = player.getInventory().getItem(entry.getKey());
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final Optional<ItemData> data = plugin.items().read(stack);
            if (data.isPresent() && entry.getValue().equals(data.get().uuid()) && problem(stack, data.get()) == null
                    && seen.add(data.get().uuid())) {
                inputs.add(new Input(entry.getKey(), stack, data.get()));
            }
        }
        return inputs;
    }

    private int itemLevel(final List<Input> inputs) {
        final List<ItemData> data = new ArrayList<>();
        for (final Input input : inputs) {
            data.add(input.data());
        }
        return UniqueRecipe.itemLevel(data);
    }

    /** 這件是不是追逐級。 */
    private boolean chase(final ItemData data) {
        final UniqueItem definition = plugin.items().repository().unique(data.unique());
        return definition != null && definition.chase();
    }

    /** 放進來的是追逐級（看第一件；空的 false）。 */
    private boolean chaseMode(final List<Input> inputs) {
        return !inputs.isEmpty() && chase(inputs.getFirst().data());
    }

    /** 這幾件可能換到的傳奇（追逐級：必定換追逐級）。 */
    private List<UniqueItem> outcomes(final List<Input> inputs) {
        final List<String> ids = new ArrayList<>();
        for (final Input input : inputs) {
            ids.add(input.data().unique());
        }
        return chaseMode(inputs)
                ? UniqueRecipe.chaseOutcomes(plugin.items().repository().uniques(), itemLevel(inputs))
                : UniqueRecipe.outcomes(plugin.items().repository().uniques(), settings, ids, itemLevel(inputs));
    }

    /** 傳奇的名字，一行幾個。 */
    private static List<String> nameLines(final List<UniqueItem> uniques) {
        final List<String> lines = new ArrayList<>();
        final StringBuilder line = new StringBuilder();
        int count = 0;
        for (final UniqueItem unique : uniques) {
            if (count > 0) {
                line.append("<dark_gray>、");
            }
            line.append("<#af6025>").append(ItemRenderer.escape(unique.name()));
            if (++count == NAMES_PER_LINE) {
                lines.add("　" + line);
                line.setLength(0);
                count = 0;
            }
        }
        if (count > 0) {
            lines.add("　" + line);
        }
        return lines;
    }

    private ItemStack summaryIcon() {
        final int need = settings.inputs();
        final List<Input> inputs = current();
        if (inputs.isEmpty()) {
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>1. 點上面 " + need + " 件傳奇放進來（重複的、用不到的）");
            lore.add("<gray>2. 按「換一件」：拿走這 " + need + " 件，換一件隨機傳奇");
            lore.add("");
            lore.add("<gray>可能換到：");
            lore.addAll(nameLines(UniqueRecipe.outcomes(plugin.items().repository().uniques(), settings, List.of(), Integer.MAX_VALUE)));
            lore.add("");
            if (settings.excludeInputs()) {
                lore.add("<dark_gray>不會換到你放進去的那幾種");
            }
            lore.add("<dark_gray>物品等級＝放進去最高的那件");
            lore.add("<#ffb347>" + need + " 件追逐級：必定換一件隨機追逐級<dark_gray>（可能換到同一種；不能跟一般傳奇混放）");
            lore.add("<dark_gray>極境專屬不能放；一般傳奇有極低的機率換到追逐級（跟小怪掉的一樣）");
            lore.add("<dark_gray>想留的先右鍵鎖定");
            return icon(Material.BOOK, "<white>怎麼用", lore);
        }
        final List<String> lore = new ArrayList<>();
        for (final Input input : inputs) {
            lore.add("<white>· " + ItemRenderer.escape(Wallet.describe(input.stack())) + " <dark_gray>（物品等級 " + input.data().ilvl() + "）");
        }
        lore.add("");
        final List<UniqueItem> pool = outcomes(inputs);
        if (pool.isEmpty()) {
            lore.add("<red>換不到東西：物品等級太低（最高 " + itemLevel(inputs) + "）");
        } else {
            lore.add((chaseMode(inputs) ? "<#ffb347>追逐級：" : "") + "<gray>可能換到（" + pool.size() + " 種，隨機一種；物品等級 " + itemLevel(inputs) + "）：");
            lore.addAll(nameLines(pool));
        }
        return icon(Material.BOOK, "<white>放了 " + inputs.size() + "／" + need + " 件", lore);
    }

    private ItemStack confirmIcon() {
        final int need = settings.inputs();
        final int have = current().size();
        if (have < need) {
            return icon(Material.SMITHING_TABLE, "<gray>換一件", "<gray>還要放 <white>" + (need - have) + "</white> 件傳奇",
                    "<dark_gray>點上面的傳奇放進來");
        }
        return glowing(icon(Material.SMITHING_TABLE, "<#af6025>換一件", "<gray>拿走這 " + need + " 件，換一件隨機傳奇",
                "<red>放進去的就沒了，按了會再問一次", "", "<yellow>左鍵：換"));
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot < MIRROR_SLOTS) {
            // 只認左鍵、右鍵（雙擊多送的那一下、數字鍵、Q 不算，免得一下子放進去又拿出來）
            final int inventorySlot = SalvageMenu.inventorySlot(slot);
            if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
                toggleLock(inventorySlot);
            } else if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
                toggle(inventorySlot);
            } else {
                return;
            }
        } else if (slot == SLOT_CLEAR) {
            selected.clear();
        } else if (slot == SLOT_CONFIRM) {
            if (acceptAction(click)) {
                requestTrade();
            }
            return;
        } else if (slot == SLOT_BACK) {
            new SalvageMenu(plugin, player).open();
            return;
        } else if (slot == SLOT_CLOSE) {
            goBack();
            return;
        } else {
            return;
        }
        redraw();
    }

    private void toggle(final int inventorySlot) {
        if (selected.remove(inventorySlot) != null) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.0f);
            return;
        }
        final ItemStack stack = player.getInventory().getItem(inventorySlot);
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty() || data.get().rarity() != Rarity.UNIQUE) {
            return;
        }
        String problem = problem(stack, data.get());
        if (problem == null && selected.containsValue(data.get().uuid())) {
            problem = "同一件已經放進來了";
        }
        final List<Input> already = current();
        if (problem == null && !already.isEmpty()) {
            problem = UniqueRecipe.mixProblem(chaseMode(already), chase(data.get()));
        }
        if (problem != null) {
            player.sendActionBar(plugin.text().parse("<red>不能放：" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        if (selected.size() >= settings.inputs()) {
            player.sendActionBar(plugin.text().parse("<yellow>已經放了 " + settings.inputs() + " 件：先點一件拿出來"));
            return;
        }
        selected.put(inventorySlot, data.get().uuid());
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
    }

    /** 右鍵：鎖定或解除鎖定傳奇（鎖著的不能放；這裡看不到的其他裝備不動）。 */
    private void toggleLock(final int inventorySlot) {
        final ItemStack stack = player.getInventory().getItem(inventorySlot);
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty() || data.get().rarity() != Rarity.UNIQUE) {
            return;
        }
        final boolean locked = !data.get().locked();
        data.get().setLocked(locked);
        plugin.items().update(stack, data.get());
        if (locked) {
            selected.remove(inventorySlot);
        }
        player.sendActionBar(plugin.text().parse(locked ? "<#ffd27f>🔒 已鎖定，不會被拿去換" : "<gray>已解除鎖定"));
        player.playSound(player.getLocation(), locked ? Sound.BLOCK_CHAIN_PLACE : Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.2f);
    }

    /** 核對放進來的那幾件；變了的拿出來、跟他說。都沒變而且放滿了回傳那幾件，不然 null。 */
    private List<Input> verified() {
        final List<Input> inputs = current();
        if (inputs.size() != selected.size()) {
            selected.clear();
            for (final Input input : inputs) {
                selected.put(input.slot(), input.data().uuid());
            }
            player.sendMessage(plugin.text().prefixed("<yellow>背包裡有東西變了（移動、鎖定或不見了），已經拿出來；請再看一次"));
            redraw();
            return null;
        }
        if (inputs.size() < settings.inputs()) {
            player.sendActionBar(plugin.text().parse("<yellow>還要放 " + (settings.inputs() - inputs.size()) + " 件傳奇"));
            return null;
        }
        return inputs;
    }

    /** 設定重新讀過（關掉了、或改了件數與池子）就不能再用這個畫面：關掉、請他重開。 */
    private boolean sameSettings() {
        final SalvageConfig config = plugin.salvage();
        if (config != null && config.recipe().enabled() && config.recipe().equals(settings)) {
            return true;
        }
        player.sendMessage(plugin.text().prefixed("<yellow>" + settings.title() + "的設定改了（或關掉了），請重新打開"));
        player.closeInventory();
        return false;
    }

    /** 按了「換一件」：再確認一次（列出拿走哪幾件、可能換到幾種）。 */
    private void requestTrade() {
        if (!sameSettings()) {
            return;
        }
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            return;
        }
        final List<Input> inputs = verified();
        if (inputs == null) {
            return;
        }
        final List<UniqueItem> pool = outcomes(inputs);
        if (pool.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>換不到東西：放進去的傳奇物品等級太低（最高 " + itemLevel(inputs) + "）"));
            return;
        }
        final List<String> body = new ArrayList<>();
        body.add("<gray>拿走這 " + inputs.size() + " 件：");
        for (final Input input : inputs) {
            body.add("<white>· " + ItemRenderer.escape(Wallet.describe(input.stack())));
        }
        body.add("");
        body.add("<gray>換一件隨機" + (chaseMode(inputs) ? "<#ffb347>追逐級<gray>" : "") + "傳奇：" + pool.size() + " 種裡抽一種" + (settings.excludeInputs() && !chaseMode(inputs) ? "（不會是放進去的那幾種）" : "")
                + "，物品等級 " + itemLevel(inputs));
        body.add("");
        body.add("<red>放進去的就沒了，不能復原。");
        // 確認框下一個 tick 才關掉選單：這中間背包變了的話不換、請他再按一次
        final Map<Integer, String> asked = new LinkedHashMap<>(selected);
        plugin.dialogs().confirm(player, "<dark_gray>" + settings.title(), body, "換一件", () -> {
            if (!sameSettings()) {
                return;
            }
            if (selected.equals(asked)) {
                trade();
            } else {
                player.sendMessage(plugin.text().prefixed("<yellow>放進來的東西變了，請再按一次"));
            }
            open();
        }, this::open);
    }

    /** 真的換：拿走放進來的那幾件、給一件，寫紀錄、跟存檔一起寫進資料庫。 */
    private void trade() {
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            return;
        }
        final List<Input> inputs = verified();
        if (inputs == null) {
            return;
        }
        // 2026-10：先擲一次跟小怪一樣的極低機率（maps.yml 的 drops.chase_mob_chance）：換到追逐級傳奇
        // （放的是追逐級：outcomes 本來就是追逐級的池子，不用再擲）
        final boolean chaseMode = chaseMode(inputs);
        UniqueItem pick = null;
        final List<UniqueItem> chase = chaseMode ? List.of() : plugin.items().generator().chaseUniques(itemLevel(inputs));
        if (!chase.isEmpty() && plugin.items().random().nextDouble() < ItemGenerator.chaseChance()) {
            pick = ItemGenerator.pickUnique(chase, plugin.items().random());
        }
        if (pick == null) {
            pick = ItemGenerator.pickUnique(outcomes(inputs), plugin.items().random());
        }
        if (pick == null) {
            player.sendMessage(plugin.text().prefixed("<red>換不到東西：放進去的傳奇物品等級太低（最高 " + itemLevel(inputs) + "）"));
            return;
        }
        final ItemData result = plugin.items().generator().generateUnique(pick, itemLevel(inputs), plugin.items().random());
        result.setIdentified(true);
        final ItemStack item = plugin.items().toStack(result);
        final String counter = settings.countText() + "換一";
        final String opId = TradeLogRepository.newTradeId();
        final List<TradeLogRepository.Entry> log = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final PlayerInventory inventory = player.getInventory();
        for (final Input input : inputs) {
            final String name = Wallet.describe(input.stack());
            names.add(name);
            inventory.setItem(input.slot(), null);
            log.add(new TradeLogRepository.Entry(opId, TradeLogRepository.RECIPE, input.data().uuid(), name, 1,
                    player.getUniqueId(), player.getName(), null, counter));
        }
        selected.clear();
        final String got = Wallet.describe(item);
        log.add(new TradeLogRepository.Entry(opId, TradeLogRepository.RECIPE, result.uuid(), got, 1,
                null, counter, player.getUniqueId(), player.getName()));
        // 放進第一件空出來的那一格：一定放得下，不會因為背包滿了掉在地上
        inventory.setItem(inputs.getFirst().slot(), item);
        plugin.players().markDirty(player);
        final TradeLogRepository tradeLog = plugin.economy().tradeLog();
        plugin.characters().commit(List.of(player), connection -> tradeLog.insert(connection, log), opId,
                settings.title() + "（" + player.getName() + "）");
        plugin.getSLF4JLogger().info("[recipe] {} 用 {} 換到 {}", player.getName(), String.join("、", names), got);
        player.sendMessage(plugin.text().prefixed("<gray>" + settings.title() + "：用 " + ItemRenderer.escape(String.join("、", names))
                + " 換到 <#af6025>" + ItemRenderer.escape(got)));
        player.playSound(player.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 0.8f, 1.0f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        if (pick.chase() && !chaseMode && plugin.loot() != null) {
            plugin.loot().broadcastChase(player, result); // 一般傳奇換到追逐級：全服廣播（追逐級換追逐級不廣播）
        }
    }
}
