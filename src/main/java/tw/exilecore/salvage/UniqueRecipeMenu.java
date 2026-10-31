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
 * 放的是追逐級（2026-10）：必定換一件隨機追逐級（追逐級自己一組）。
 * 批量（2026-10）：可以放很多件（3 的倍數），一次換好幾組；「全部放進來」把背包裡能放的都放進來（鎖定的不會）。
 * 極境專屬也能放，但那一組換出來的是一般賭博賭得到的傳奇。
 */
public final class UniqueRecipeMenu extends Menu {

    private static final int MIRROR_SLOTS = 36;
    private static final int SLOT_SUMMARY = 45;
    /** 2026-10：全部放進來（鎖定的不會）。 */
    private static final int SLOT_ALL = 46;
    /** 說明裡最多列幾件放進來的。 */
    private static final int LIST_MAX = 12;
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
        set(SLOT_ALL, icon(Material.HOPPER, "<white>全部放進來", "<gray>背包裡能放的傳奇全部放進來（批量換）",
                "<dark_gray>鎖定的、還沒鑑定的不會；想留的先右鍵鎖定", "", "<yellow>左鍵：全部放進來"));
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

    /** 2026-10-31：藥劑欄裡正在用的東西（跟拆解台 protectedFlask 一樣）。 */
    private boolean flaskSlot(final int inventorySlot, final ItemStack stack) {
        return plugin.flasks() != null && inventorySlot >= 0 && inventorySlot < 9 && plugin.flasks().kindAt(player, inventorySlot) != null
                && stack != null && !stack.isEmpty() && !tw.exilecore.flask.FlaskService.isPlaceholder(stack);
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

    /** 這件是不是極境專屬。 */
    private boolean uber(final ItemData data) {
        return UniqueRecipe.uberExclusive(plugin.items().repository().unique(data.unique()));
    }

    /** 這一組是追逐級（追逐級自己一組）。 */
    private boolean chaseMode(final List<Input> group) {
        return !group.isEmpty() && chase(group.getFirst().data());
    }

    /** 這一組有極境專屬：換一般賭博賭得到的。 */
    private boolean uberMode(final List<Input> group) {
        for (final Input input : group) {
            if (uber(input.data())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分組（2026-10 批量）：追逐級照放進來的順序每 N 件一組；其他的一般傳奇在前、極境專屬在後，每 N 件一組。
     * 湊不滿一組的不換（留在背包）。
     */
    private List<List<Input>> groups(final List<Input> inputs) {
        final int need = settings.inputs();
        final List<Input> chase = new ArrayList<>();
        final List<Input> normal = new ArrayList<>();
        final List<Input> ubers = new ArrayList<>();
        for (final Input input : inputs) {
            (chase(input.data()) ? chase : uber(input.data()) ? ubers : normal).add(input);
        }
        normal.addAll(ubers);
        final List<List<Input>> groups = new ArrayList<>();
        for (final List<Input> list : List.of(chase, normal)) {
            for (int from = 0; from + need <= list.size(); from += need) {
                groups.add(List.copyOf(list.subList(from, from + need)));
            }
        }
        return groups;
    }

    private static int grouped(final List<List<Input>> groups) {
        int total = 0;
        for (final List<Input> group : groups) {
            total += group.size();
        }
        return total;
    }

    /** 這一組可能換到的傳奇（追逐級：必定換追逐級；有極境專屬：一般賭博賭得到的）。 */
    private List<UniqueItem> outcomes(final List<Input> group) {
        final List<String> ids = new ArrayList<>();
        for (final Input input : group) {
            ids.add(input.data().unique());
        }
        if (chaseMode(group)) {
            return UniqueRecipe.chaseOutcomes(plugin.items().repository().uniques(), itemLevel(group));
        }
        return uberMode(group)
                ? UniqueRecipe.gambleOutcomes(plugin.items().repository().uniques(), settings, ids, itemLevel(group))
                : UniqueRecipe.outcomes(plugin.items().repository().uniques(), settings, ids, itemLevel(group));
    }

    private String poolLabel(final List<Input> group) {
        return chaseMode(group) ? "<#ffb347>追逐級" : uberMode(group) ? "<#c77dff>賭博的傳奇" : "<gray>一般";
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
            lore.add("<gray>1. 點上面的傳奇放進來（每 " + need + " 件換一件；可以放很多組，一次換完）");
            lore.add("<gray>   或按右邊的「全部放進來」");
            lore.add("<gray>2. 按「換」：拿走湊滿的那幾組，每組換一件隨機傳奇");
            lore.add("");
            lore.add("<gray>可能換到：");
            lore.addAll(nameLines(UniqueRecipe.outcomes(plugin.items().repository().uniques(), settings, List.of(), Integer.MAX_VALUE)));
            lore.add("");
            if (settings.excludeInputs()) {
                lore.add("<dark_gray>不會換到那一組放進去的那幾種");
            }
            lore.add("<dark_gray>物品等級＝那一組放進去最高的那件");
            lore.add("<#ffb347>" + need + " 件追逐級：必定換一件隨機追逐級<dark_gray>（可能換到同一種；追逐級自己一組）");
            lore.add("<#c77dff>極境專屬也能放：<gray>那一組換出來的是一般賭博賭得到的傳奇");
            lore.add("<dark_gray>一般傳奇有極低的機率換到追逐級（跟小怪掉的一樣）");
            lore.add("<dark_gray>想留的先右鍵鎖定");
            return icon(Material.BOOK, "<white>怎麼用", lore);
        }
        final List<String> lore = new ArrayList<>();
        for (int i = 0; i < inputs.size() && i < LIST_MAX; i++) {
            final Input input = inputs.get(i);
            lore.add("<white>· " + ItemRenderer.escape(Wallet.describe(input.stack())) + " <dark_gray>（物品等級 " + input.data().ilvl() + "）");
        }
        if (inputs.size() > LIST_MAX) {
            lore.add("<dark_gray>…還有 " + (inputs.size() - LIST_MAX) + " 件");
        }
        lore.add("");
        final List<List<Input>> groups = groups(inputs);
        final int left = inputs.size() - grouped(groups);
        if (groups.isEmpty()) {
            lore.add("<gray>還不夠一組（每組 " + need + " 件；追逐級要 " + need + " 件追逐級）");
        } else if (groups.size() == 1) {
            final List<UniqueItem> pool = outcomes(groups.getFirst());
            if (pool.isEmpty()) {
                lore.add("<red>換不到東西：物品等級太低（最高 " + itemLevel(groups.getFirst()) + "）");
            } else {
                lore.add(poolLabel(groups.getFirst()) + "<gray>：可能換到（" + pool.size() + " 種，隨機一種；物品等級 " + itemLevel(groups.getFirst()) + "）：");
                lore.addAll(nameLines(pool));
            }
        } else {
            lore.add("<gray>會換 <white>" + groups.size() + "</white> 次：");
            for (int i = 0; i < groups.size() && i < LIST_MAX; i++) {
                final List<Input> group = groups.get(i);
                final int kinds = outcomes(group).size();
                lore.add("<gray>　第 " + (i + 1) + " 組：" + poolLabel(group) + "<gray>，" + (kinds == 0 ? "<red>換不到（物品等級太低）"
                        : kinds + " 種裡抽一種、物品等級 " + itemLevel(group)));
            }
            if (groups.size() > LIST_MAX) {
                lore.add("<dark_gray>　…還有 " + (groups.size() - LIST_MAX) + " 組");
            }
        }
        if (left > 0 && !groups.isEmpty()) {
            lore.add("<dark_gray>剩 " + left + " 件湊不滿一組：不會被拿走");
        }
        return icon(Material.BOOK, "<white>放了 " + inputs.size() + " 件" + (groups.isEmpty() ? "" : "（" + groups.size() + " 組）"), lore);
    }

    private ItemStack confirmIcon() {
        final int need = settings.inputs();
        final List<Input> inputs = current();
        final List<List<Input>> groups = groups(inputs);
        if (groups.isEmpty()) {
            return icon(Material.SMITHING_TABLE, "<gray>換一件", "<gray>每 <white>" + need + "</white> 件傳奇換一件",
                    "<dark_gray>點上面的傳奇放進來");
        }
        final int count = groups.size();
        return glowing(icon(Material.SMITHING_TABLE, "<#af6025>換 " + count + " 件", "<gray>拿走 " + grouped(groups) + " 件，換 " + count + " 件隨機傳奇",
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
        } else if (slot == SLOT_ALL) {
            selectAll();
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
        String problem = flaskSlot(inventorySlot, stack) ? "藥劑欄（快捷欄 " + (inventorySlot + 1) + "）的藥劑、護符不能換：要換先移到別格"
                : problem(stack, data.get());
        if (problem == null && selected.containsValue(data.get().uuid())) {
            problem = "同一件已經放進來了";
        }
        if (problem != null) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>不能放：" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        selected.put(inventorySlot, data.get().uuid());
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
    }

    /** 全部放進來（2026-10 批量）：背包裡能放的傳奇（鎖定的、沒鑑定的不會）。 */
    private void selectAll() {
        final PlayerInventory inventory = player.getInventory();
        int added = 0;
        for (int slot = 0; slot < MIRROR_SLOTS; slot++) {
            final int inventorySlot = SalvageMenu.inventorySlot(slot);
            if (selected.containsKey(inventorySlot)) {
                continue;
            }
            if (flaskSlot(inventorySlot, inventory.getItem(inventorySlot))) {
                continue; // 2026-10-31：藥劑欄裡正在用的藥劑、護符不放（跟拆解台一樣）
            }
            final String uuid = eligibleUuid(inventory.getItem(inventorySlot));
            if (uuid != null && !selected.containsValue(uuid)) {
                selected.put(inventorySlot, uuid);
                added++;
            }
        }
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(added == 0 ? "<gray>沒有能放的傳奇了" : "<green>放進來 " + added + " 件"));
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
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(locked ? "<#ffd27f>🔒 已鎖定，不會被拿去換" : "<gray>已解除鎖定"));
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
        if (groups(inputs).isEmpty()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<yellow>還不夠一組（每組 " + settings.inputs() + " 件）"));
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
        final List<List<Input>> groups = groups(inputs);
        for (final List<Input> group : groups) {
            if (outcomes(group).isEmpty()) {
                player.sendMessage(plugin.text().prefixed("<red>有一組換不到東西：放進去的傳奇物品等級太低（最高 " + itemLevel(group) + "）；先拿出來"));
                return;
            }
        }
        final List<String> body = new ArrayList<>();
        body.add("<gray>拿走這 " + grouped(groups) + " 件，換 " + groups.size() + " 件：");
        int shown = 0;
        for (int i = 0; i < groups.size(); i++) {
            final List<Input> group = groups.get(i);
            for (final Input input : group) {
                if (shown++ < LIST_MAX * 2) {
                    body.add("<white>· " + ItemRenderer.escape(Wallet.describe(input.stack())) + (groups.size() > 1 ? " <dark_gray>（第 " + (i + 1) + " 組）" : ""));
                }
            }
        }
        if (shown > LIST_MAX * 2) {
            body.add("<dark_gray>…還有 " + (shown - LIST_MAX * 2) + " 件");
        }
        body.add("");
        for (int i = 0; i < groups.size() && i < LIST_MAX; i++) {
            final List<Input> group = groups.get(i);
            body.add("<gray>" + (groups.size() > 1 ? "第 " + (i + 1) + " 組：" : "") + "換一件隨機" + poolLabel(group) + "<gray>傳奇："
                    + outcomes(group).size() + " 種裡抽一種" + (settings.excludeInputs() && !chaseMode(group) ? "（不會是放進去的那幾種）" : "")
                    + "，物品等級 " + itemLevel(group));
        }
        final int left = inputs.size() - grouped(groups);
        if (left > 0) {
            body.add("<dark_gray>剩 " + left + " 件湊不滿一組：不會被拿走");
        }
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
        final String counter = settings.countText() + "換一";
        final String opId = TradeLogRepository.newTradeId();
        final List<TradeLogRepository.Entry> log = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<String> gotNames = new ArrayList<>();
        final List<ItemData> chaseResults = new ArrayList<>();
        int uberGroups = 0;
        final PlayerInventory inventory = player.getInventory();
        for (final List<Input> group : groups(inputs)) {
            // 2026-10：先擲一次跟小怪一樣的極低機率（maps.yml 的 drops.chase_mob_chance）：換到追逐級傳奇
            // （放的是追逐級：outcomes 本來就是追逐級的池子，不用再擲）
            final boolean chaseMode = chaseMode(group);
            if (uberMode(group) && !chaseMode) {
                uberGroups++;
            }
            UniqueItem pick = null;
            final List<UniqueItem> chase = chaseMode ? List.of() : plugin.items().generator().chaseUniques(itemLevel(group));
            if (!chase.isEmpty() && plugin.items().random().nextDouble() < ItemGenerator.chaseChance()) {
                pick = ItemGenerator.pickUnique(chase, plugin.items().random());
            }
            if (pick == null) {
                pick = ItemGenerator.pickUnique(outcomes(group), plugin.items().random());
            }
            if (pick == null) {
                continue; // 換不到（物品等級太低）：這一組不動
            }
            final ItemData result = plugin.items().generator().generateUnique(pick, itemLevel(group), plugin.items().random());
            result.setIdentified(true);
            final ItemStack item = plugin.items().toStack(result);
            for (final Input input : group) {
                final String name = Wallet.describe(input.stack());
                names.add(name);
                inventory.setItem(input.slot(), null);
                log.add(new TradeLogRepository.Entry(opId, TradeLogRepository.RECIPE, input.data().uuid(), name, 1,
                        player.getUniqueId(), player.getName(), null, counter));
            }
            final String got = Wallet.describe(item);
            gotNames.add(got);
            log.add(new TradeLogRepository.Entry(opId, TradeLogRepository.RECIPE, result.uuid(), got, 1,
                    null, counter, player.getUniqueId(), player.getName()));
            // 放進這一組第一件空出來的那一格：一定放得下，不會因為背包滿了掉在地上
            inventory.setItem(group.getFirst().slot(), item);
            if (pick.chase() && !chaseMode) {
                chaseResults.add(result);
            }
        }
        selected.clear();
        if (gotNames.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>換不到東西：放進去的傳奇物品等級太低"));
            return;
        }
        plugin.players().markDirty(player);
        final TradeLogRepository tradeLog = plugin.economy().tradeLog();
        plugin.characters().commit(List.of(player), connection -> tradeLog.insert(connection, log), opId,
                settings.title() + "（" + player.getName() + "）");
        // 2026-10：紀錄寫幾組、其中幾組有極境專屬（批量換之後方便查有沒有被拿來大量洗）
        plugin.getSLF4JLogger().info("[recipe] {} 換了 {} 組（極境 {} 組）：用 {} 換到 {}", player.getName(), gotNames.size(), uberGroups,
                String.join("、", names), String.join("、", gotNames));
        if (gotNames.size() == 1) {
            player.sendMessage(plugin.text().prefixed("<gray>" + settings.title() + "：用 " + ItemRenderer.escape(String.join("、", names))
                    + " 換到 <#af6025>" + ItemRenderer.escape(gotNames.getFirst())));
        } else {
            player.sendMessage(plugin.text().prefixed("<gray>" + settings.title() + "：用 " + names.size() + " 件換到 " + gotNames.size() + " 件："));
            for (final String got : gotNames) {
                player.sendMessage(plugin.text().parse("<dark_gray>　· <#af6025>" + ItemRenderer.escape(got)));
            }
        }
        player.playSound(player.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 0.8f, 1.0f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        if (plugin.loot() != null) {
            for (final ItemData result : chaseResults) {
                plugin.loot().broadcastChase(player, result); // 一般傳奇換到追逐級：全服廣播（追逐級換追逐級不廣播）
            }
        }
    }
}
