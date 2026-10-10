package tw.exilecore.gui;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import tw.exilecore.ExileCore;
import tw.exilecore.economy.TradeLogRepository;
import tw.exilecore.economy.Wallet;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.Rarity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 垃圾桶（選單 Shift＋F，或 /trash）：把背包裡不要的東西刪掉（東西不能丟在地上）。
 * <p>
 * 點背包裡的東西（或指著它按 Q）→ 放進垃圾桶（上面四排）；點上面的 → 拿回來；按「刪除」才真的刪掉。
 * 東西在按刪除之前都還在背包裡（這裡只記「背包第幾格的哪一件」），關掉視窗什麼都不會少。
 * 刪除時再核對一次每一格（同一件、數量沒有變少）；稀有以上、地圖、寶石、值錢的通貨先跳確認框。
 * 刪掉的東西寫進交易紀錄（/ec trace 查得到），跟存檔在同一個資料庫交易寫入。
 */
public final class TrashMenu extends Menu {

    /** 上面四排：放進垃圾桶的東西。 */
    private static final int CELLS = 36;
    private static final int SLOT_INFO = 36;
    private static final int SLOT_RETURN = 38;
    private static final int SLOT_DELETE = 40;
    private static final int SLOT_BACK = 42;
    private static final int SLOT_CLOSE = 44;
    /** 背包：快捷列＋主背包。 */
    private static final int BACKPACK = 36;
    /** 確認框裡最多列幾樣。 */
    private static final int LIST_MAX = 8;

    /**
     * 放進垃圾桶的一格。
     *
     * @param slot     背包格（0–35）
     * @param snapshot 放進來那一刻的樣子（刪除時核對：同一件、數量沒有變少；只刪這麼多）
     */
    private record Mark(int slot, ItemStack snapshot) {
    }

    private final List<Mark> marks = new ArrayList<>();

    public TrashMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 5, "<dark_gray>垃圾桶");
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 45; i++) {
            set(i, null);
        }
        for (int i = 0; i < marks.size() && i < CELLS; i++) {
            set(i, appendLore(marks.get(i).snapshot().clone(), "", "<yellow>左鍵：拿回來（不刪）"));
        }
        for (int slot = CELLS; slot < 45; slot++) {
            set(slot, filler());
        }
        set(SLOT_INFO, icon(Material.BOOK, "<white>垃圾桶怎麼用",
                "<gray>點你背包裡的東西（或指著它按 Q）：放進來",
                "<gray>點上面的東西：拿回來",
                "<gray>按「刪除」才會真的刪掉，<red>刪掉就沒有了",
                "<gray>關掉視窗：什麼都不會少",
                "",
                "<dark_gray>裝備可以用選單的「拆解」換金幣和通貨"));
        if (marks.isEmpty()) {
            set(SLOT_DELETE, icon(Material.CAULDRON, "<gray>垃圾桶是空的", "<gray>點你背包裡不要的東西放進來"));
        } else {
            set(SLOT_RETURN, icon(Material.HOPPER, "<white>全部拿回來", "<gray>清空垃圾桶，什麼都不刪", "", "<yellow>左鍵：全部拿回來"));
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>刪掉就沒有了，不能復原");
            final int valuable = valuable().size();
            if (valuable > 0) {
                lore.add("<#f5b942>其中 " + valuable + " 樣比較值錢，會再問你一次");
            }
            lore.add("");
            lore.add("<yellow>左鍵：刪除");
            set(SLOT_DELETE, icon(Material.LAVA_BUCKET, "<red>刪除 " + marks.size() + " 樣東西", lore));
        }
        // 2026-10：左下角的「回選單」跟右下角的「← 返回選單」重複，拿掉
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot < CELLS) {
            if (slot < marks.size() && acceptAction(click)) {
                marks.remove(slot);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
                redraw();
            }
            return;
        }
        switch (slot) {
            case SLOT_RETURN -> {
                if (!marks.isEmpty()) {
                    marks.clear();
                    redraw();
                }
            }
            case SLOT_DELETE -> {
                if (!marks.isEmpty() && acceptAction(click)) {
                    requestDelete();
                }
            }
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }

    /** 點自己背包（任何點法，包括指著按 Q）：放進垃圾桶。 */
    @Override
    protected void onBottomClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot < 0 || slot >= BACKPACK || click == ClickType.DOUBLE_CLICK) {
            return;
        }
        final ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (tw.exilecore.flask.FlaskService.isPlaceholder(stack)) {
            player.sendActionBar(plugin.text().parse("<gray>這是空的藥劑欄（提示），不是東西"));
            return;
        }
        if (plugin.items().read(stack).map(ItemData::locked).orElse(false)) {
            player.sendActionBar(plugin.text().parse("<#ffd27f>🔒 這件鎖定了，不會丟進垃圾桶（/lock 解除）"));
            return;
        }
        for (final Mark mark : marks) {
            if (mark.slot() == slot) {
                player.sendActionBar(plugin.text().parse("<gray>已經在垃圾桶裡了（點上面的拿回來）"));
                return;
            }
        }
        if (marks.size() >= CELLS) {
            player.sendActionBar(plugin.text().parse("<red>垃圾桶滿了：先刪掉這些"));
            return;
        }
        marks.add(new Mark(slot, stack.clone()));
        player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.5f, 1.0f);
        redraw();
    }

    // ---- 刪除 ----

    /** 按了刪除：有值錢的先問一次。 */
    private void requestDelete() {
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            return;
        }
        if (!refresh()) {
            return;
        }
        final List<Mark> valuable = valuable();
        if (valuable.isEmpty()) {
            delete();
            return;
        }
        final List<String> body = new ArrayList<>();
        body.add("<gray>垃圾桶裡有 <white>" + marks.size() + "</white> 樣東西，這些比較值錢：");
        for (int i = 0; i < valuable.size() && i < LIST_MAX; i++) {
            body.add("<white>· " + ItemRenderer.escape(Wallet.describe(valuable.get(i).snapshot())));
        }
        if (valuable.size() > LIST_MAX) {
            body.add("<gray>…還有 " + (valuable.size() - LIST_MAX) + " 樣");
        }
        body.add("");
        body.add("<red>刪掉就沒有了，不能復原。");
        // 確認框下一個 tick 才關掉垃圾桶：這中間又放進來的東西確認框上沒列出來，所以內容變了就不刪、請他再按一次
        final List<Mark> asked = List.copyOf(marks);
        plugin.dialogs().confirm(player, "<dark_gray>刪除東西", body, "刪除", () -> {
            if (marks.equals(asked)) {
                delete();
            } else {
                player.sendMessage(plugin.text().prefixed("<yellow>垃圾桶裡的東西變了，請再按一次刪除"));
            }
            open();
        }, this::open);
    }

    /** 核對每一格還是放進來的那件、數量沒有變少；變了的拿出垃圾桶。都沒變回傳 true。 */
    private boolean refresh() {
        final PlayerInventory inventory = player.getInventory();
        final int before = marks.size();
        marks.removeIf(mark -> {
            final ItemStack current = inventory.getItem(mark.slot());
            return current == null || current.isEmpty() || !current.isSimilar(mark.snapshot())
                    || current.getAmount() < mark.snapshot().getAmount();
        });
        if (marks.size() == before) {
            return true;
        }
        player.sendMessage(plugin.text().prefixed("<yellow>背包裡有東西變了（移動、用掉或數量變少），已經從垃圾桶拿出來；請再看一次"));
        redraw();
        return false;
    }

    /** 真的刪掉：從背包拿走放進來時的數量，寫紀錄、跟存檔一起寫進資料庫。 */
    private void delete() {
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            return;
        }
        if (marks.isEmpty() || !refresh()) {
            return;
        }
        final PlayerInventory inventory = player.getInventory();
        final String opId = UUID.randomUUID().toString();
        final List<TradeLogRepository.Entry> log = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (final Mark mark : marks) {
            final ItemStack current = inventory.getItem(mark.slot());
            final int amount = mark.snapshot().getAmount();
            inventory.setItem(mark.slot(), current.getAmount() > amount ? current.asQuantity(current.getAmount() - amount) : null);
            final String uuid = plugin.items().read(mark.snapshot()).map(ItemData::uuid).orElse(null);
            final String name = Wallet.describe(mark.snapshot());
            plugin.currencies().read(mark.snapshot()).ifPresent(type ->
                    plugin.economy().ledger().consumed(type.id(), tw.exilecore.economy.EconomyLedger.TRASH, amount));
            names.add(name);
            log.add(new TradeLogRepository.Entry(opId, TradeLogRepository.TRASH, uuid, name, amount,
                    player.getUniqueId(), player.getName(), null, "垃圾桶"));
        }
        final int count = marks.size();
        marks.clear();
        if (plugin.flasks() != null) {
            plugin.flasks().ensureSlots(player); // 刪掉的是藥劑欄的藥劑：那格馬上換成空瓶
        }
        plugin.players().markDirty(player);
        final TradeLogRepository tradeLog = plugin.economy().tradeLog();
        plugin.characters().commit(List.of(player), connection -> tradeLog.insert(connection, log), opId,
                "垃圾桶（" + player.getName() + "）");
        plugin.getSLF4JLogger().info("[trash] {} 刪掉 {} 樣：{}", player.getName(), count, String.join("、", names));
        player.sendMessage(plugin.text().prefixed("<gray>垃圾桶：刪掉了 <white>" + count + "</white> 樣東西"));
        player.playSound(player.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.5f, 1.2f);
        if (player.getOpenInventory().getTopInventory().getHolder() == this) {
            redraw();
        }
    }

    /** 比較值錢、刪之前要再問一次的：稀有以上的裝備、地圖、寶石、能標價的通貨。 */
    private List<Mark> valuable() {
        final List<Mark> list = new ArrayList<>();
        for (final Mark mark : marks) {
            if (valuable(mark.snapshot())) {
                list.add(mark);
            }
        }
        return list;
    }

    private boolean valuable(final ItemStack stack) {
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isPresent()) {
            if (data.get().rarity() == Rarity.RARE || data.get().rarity() == Rarity.UNIQUE) {
                return true;
            }
            final ItemBase base = plugin.items().repository().base(data.get().base());
            return base != null && base.isMap();
        }
        if (plugin.skills() != null && plugin.skills().gems().isGem(stack)) {
            return true;
        }
        return plugin.currencies().read(stack).map(type -> plugin.economy().config().priceable(type.id())).orElse(false);
    }
}
