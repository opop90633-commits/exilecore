package tw.exilecore.currency;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.economy.EconomyLedger;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.UniqueItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 2026-11-11 照站長「映星鏡新增可自選追逐級傳奇功能」：用一顆映星鏡換一件自己選的追逐級傳奇（正常的傳奇，數值在範圍裡隨機擲，
 * 跟掉出來的一樣可以再改造、腐化、被複製）。入口：鑑定師的選單、/starmirror。這次之前映星鏡只能複製裝備（複製照舊）。
 * 映星鏡從背包、通貨袋扣；換到的照追逐級傳奇一樣全服廣播，市場報告記在「傳奇兌換」。
 */
public final class StarMirrorExchangeMenu extends Menu {

    /** 換到的物品等級：至少這麼高（照那件傳奇的最低掉落等級，比這個高就照它的）。 */
    static final int ITEM_LEVEL = 82;
    private static final int PER_PAGE = CONTENT_END - CONTENT_START;

    private final Runnable back;
    private final List<UniqueItem> shown = new ArrayList<>();
    private int page;

    public StarMirrorExchangeMenu(final ExileCore plugin, final Player player, final Runnable back) {
        super(plugin, player, 6, "<dark_gray>映星鏡：選一件追逐級傳奇");
        this.back = back;
    }

    /** /starmirror：打開兌換選單。 */
    public static LiteralCommandNode<CommandSourceStack> command(final ExileCore plugin) {
        return Commands.literal("starmirror")
                .executes(context -> {
                    if (context.getSource().getExecutor() instanceof Player player) {
                        open(plugin, player, null);
                    } else {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    /** 檢查模組、角色之後打開（back：返回鍵回到哪裡，null = 沒有返回鍵）。 */
    public static void open(final ExileCore plugin, final Player player, final Runnable back) {
        if (plugin.currencies() == null || plugin.items() == null) {
            player.sendMessage(plugin.text().prefixed("<red>通貨模組沒有啟用"));
        } else if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色"));
        } else if (plugin.currencies().mirrorType() == null) {
            player.sendMessage(plugin.text().prefixed("<red>currencies.yml 裡沒有映星鏡（operation: MIRROR）"));
        } else {
            new StarMirrorExchangeMenu(plugin, player, back).open();
        }
    }

    /** 換得到的：全部現役的追逐級傳奇（權重 0 的停用了，不給換）。 */
    static List<UniqueItem> candidates(final tw.exilecore.item.ItemGenerator generator, final tw.exilecore.item.ItemRepository repository) {
        final List<UniqueItem> list = new ArrayList<>(generator.chaseUniques(Integer.MAX_VALUE));
        list.sort(Comparator.<UniqueItem>comparingInt(unique -> repository.base(unique.base()) == null ? 99
                        : repository.base(unique.base()).slot().ordinal()).thenComparing(UniqueItem::name));
        return list;
    }

    static int itemLevel(final UniqueItem unique) {
        return Math.max(ITEM_LEVEL, unique.dropLevel());
    }

    private CurrencyType mirror() {
        return plugin.currencies().mirrorType();
    }

    /** 身上（背包＋通貨袋）有幾顆映星鏡。 */
    private int have(final CurrencyType mirror) {
        int count = 0;
        for (final ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && !stack.isEmpty() && plugin.currencies().read(stack).map(t -> t.id().equals(mirror.id())).orElse(false)) {
                count += stack.getAmount();
            }
        }
        return count + plugin.players().get(player).bag().pouch().amount(mirror.id());
    }

    /** 從背包、通貨袋扣一顆（同 CurrencyListener 複製時的扣法：先背包、再通貨袋）。 */
    private boolean takeOne(final CurrencyType mirror) {
        final ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            final ItemStack stack = contents[i];
            if (stack != null && !stack.isEmpty() && plugin.currencies().read(stack).map(t -> t.id().equals(mirror.id())).orElse(false)) {
                stack.setAmount(stack.getAmount() - 1);
                player.getInventory().setItem(i, stack.getAmount() <= 0 ? null : stack);
                return true;
            }
        }
        if (plugin.players().get(player).bag().pouch().withdraw(mirror.id(), 1) > 0) {
            plugin.players().get(player).markDirty();
            return true;
        }
        return false;
    }

    private int pageCount(final int size) {
        return Math.max(1, (size + PER_PAGE - 1) / PER_PAGE);
    }

    @Override
    protected void draw() {
        frame();
        final CurrencyType mirror = mirror();
        final List<UniqueItem> all = candidates(plugin.items().generator(), plugin.items().repository());
        page = Math.max(0, Math.min(page, pageCount(all.size()) - 1));
        final int have = mirror == null ? 0 : have(mirror);
        final String mirrorName = mirror == null ? "映星鏡" : ItemRenderer.escape(mirror.name());
        set(4, icon(Material.NAUTILUS_SHELL, "<#9fd8ff>" + mirrorName,
                "<gray>你身上有 <white>" + have + "</white> 顆（背包＋通貨袋）",
                "<gray>一顆換一件追逐級傳奇"));
        shown.clear();
        for (int i = page * PER_PAGE; i < all.size() && shown.size() < PER_PAGE; i++) {
            shown.add(all.get(i));
        }
        for (int i = 0; i < shown.size(); i++) {
            set(CONTENT_START + i, preview(shown.get(i), have > 0));
        }
        final int pages = pageCount(all.size());
        if (pages > 1) {
            set(SLOT_PREV, page > 0 ? pageButton(false, page, pages) : bar());
            set(SLOT_NEXT, page < pages - 1 ? pageButton(true, page, pages) : bar());
        }
        set(SLOT_HELP, helpButton(List.of(
                "點一件追逐級傳奇、確認：用掉一顆" + mirrorName + "換成它",
                "換到的是正常的傳奇：數值在範圍裡隨機擲，",
                "可以再改造、腐化、被複製（跟掉出來的一樣）",
                "物品等級 " + ITEM_LEVEL + "（那件的最低掉落等級更高就照它的）",
                "映星鏡從背包、通貨袋扣；拿著它點裝備還是複製")));
        if (back != null) {
            set(SLOT_BACK, backButton("上一頁"));
        }
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private ItemStack preview(final UniqueItem unique, final boolean affordable) {
        final ItemData data;
        try {
            data = plugin.items().generator().generateUnique(unique, itemLevel(unique), new Random(unique.id().hashCode()));
        } catch (final RuntimeException e) {
            return icon(Material.BARRIER, "<red>" + ItemRenderer.escape(unique.name()), "<gray>做不出預覽");
        }
        data.setIdentified(true);
        final ItemStack stack = plugin.items().renderer().create(data); // 只畫外觀，不寫物品資料
        return appendLore(stack, "", "<dark_gray>（數值是範例，換到的會在範圍裡重擲）",
                affordable ? "<yellow>點一下：用一顆映星鏡換這件" : "<red>身上沒有映星鏡");
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE_BAR) {
            player.closeInventory();
            return;
        }
        if (slot == SLOT_BACK && back != null) {
            back.run();
            return;
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            page += slot == SLOT_PREV ? -1 : 1;
            redraw();
            return;
        }
        if (slot < CONTENT_START || slot >= CONTENT_START + shown.size() || !acceptAction(click)) {
            return;
        }
        final UniqueItem unique = shown.get(slot - CONTENT_START);
        final CurrencyType mirror = mirror();
        if (mirror == null || have(mirror) <= 0) {
            fail("身上沒有映星鏡（背包、通貨袋都沒有）");
            return;
        }
        player.closeInventory();
        final String mirrorName = ItemRenderer.escape(mirror.name());
        plugin.dialogs().confirm(player, "<dark_gray>" + mirrorName,
                List.of("<gray>用一顆" + mirrorName + "換這件追逐級傳奇？", "<#af6025>" + ItemRenderer.escape(unique.name()), "",
                        "<gray>數值在範圍裡隨機擲（跟掉出來的一樣）。",
                        "<red>用掉一顆" + mirrorName + "（拿不回來）"),
                "兌換", () -> redeem(mirror, unique), () -> new StarMirrorExchangeMenu(plugin, player, back).open());
    }

    private void redeem(final CurrencyType mirror, final UniqueItem unique) {
        if (!plugin.players().get(player).hasCharacter()) {
            fail("先選一個角色");
            return;
        }
        if (!takeOne(mirror)) {
            fail("身上沒有" + mirror.name() + "了");
            return;
        }
        final tw.exilecore.item.ItemService items = plugin.items();
        final ItemData data = items.generator().generateUnique(unique, itemLevel(unique), items.random());
        data.setIdentified(true);
        final ItemStack made = items.toStack(data);
        plugin.economy().wallet().dropFor(player, plugin.economy().wallet().give(player, made)); // 放不下的進隨身背包，再不行掉在腳下只有自己撿得到
        // 紀錄：通貨紀錄（查帳）、市場報告記在「傳奇兌換」（映星鏡的消耗、追逐級的產出），全服廣播
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        plugin.currencies().recordAudit(state.hasCharacter() ? state.character().id() : null, player.getName(), data.uuid(), mirror.id(),
                "{}", items.toJson(data));
        plugin.economy().ledger().of(player).consumed(mirror.id(), EconomyLedger.UNIQUE_RECIPE, 1);
        if (plugin.loot() != null) {
            plugin.loot().broadcastChase(player, data, EconomyLedger.UNIQUE_RECIPE);
        }
        plugin.getSLF4JLogger().info("[映星鏡兌換] {} 用 {} 換了 {}（{}，{}）", player.getName(), mirror.id(), unique.name(), unique.id(), data.uuid());
        plugin.players().markDirty(player);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        player.sendMessage(plugin.text().prefixed("<#9fd8ff>" + ItemRenderer.escape(mirror.name()) + "<gray>換成了 ").append(made.effectiveName()));
    }

    private void fail(final String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.8f, 0.6f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(message)));
    }
}
