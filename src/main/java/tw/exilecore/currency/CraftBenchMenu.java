package tw.exilecore.currency;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 洗裝台（2026-10-19，/craft）：左邊選星籤、中間放裝備、右邊選通貨，按「使用」＝先把星籤掛上去，再用那顆通貨。
 * 選好之後一直按「使用」就一直洗（星籤、通貨每次各扣一顆）。
 * <ul>
 *   <li>裝備：點自己背包裡的一件（不會拿進選單，直接改背包裡那一件；換了格子或拿走就要重選）。</li>
 *   <li>星籤、通貨：列出通貨袋＋背包＋倉庫通貨頁裡有的（{@link tw.exilecore.economy.Wallet}），扣的時候也照這個順序扣。</li>
 *   <li>先檢查整套能不能做（星籤會不會被這顆通貨觸發、掛上去之後通貨用不用得上去），不行的話什麼都不扣。</li>
 *   <li>不收的通貨：僥倖石（會碎掉）、符文（孔滿要選覆蓋哪顆）、映星鏡、蒸餾情緒、共鳴石、技能用的，照原本拿在游標上點。</li>
 * </ul>
 */
public final class CraftBenchMenu extends Menu {

    /** 右邊列得出來的通貨（用在裝備上、一次一顆、不會多出或弄壞物品的）。 */
    static final Set<CurrencyOperation> BENCH = EnumSet.of(
            CurrencyOperation.IDENTIFY, CurrencyOperation.UPGRADE_TO_MAGIC, CurrencyOperation.ADD_AFFIX_MAGIC, CurrencyOperation.REROLL_MAGIC,
            CurrencyOperation.MAGIC_TO_RARE, CurrencyOperation.UPGRADE_TO_RARE, CurrencyOperation.REMOVE_AND_ADD, CurrencyOperation.REMOVE_AFFIX,
            CurrencyOperation.REROLL_RARE, CurrencyOperation.ADD_AFFIX_RARE, CurrencyOperation.SCOUR, CurrencyOperation.REROLL_VALUES,
            CurrencyOperation.REROLL_IMPLICIT, CurrencyOperation.CORRUPT, CurrencyOperation.QUALITY_WEAPON, CurrencyOperation.QUALITY_ARMOUR,
            CurrencyOperation.QUALITY_MAP, CurrencyOperation.ESSENCE, CurrencyOperation.CATALYST,
            CurrencyOperation.UNVEIL, // 2026-10-19：揭露石（加一條隱匿詞綴，之後找盜賊首領揭露）
            CurrencyOperation.FRACTURE, // 2026-10-21：定星石（PoE2 的破裂石）
            CurrencyOperation.QUALITY_CASTER); // 2026-10-26：星紋蝕刻器（預見鎖要先看結果，照原本拿在游標上點；工匠石照站長拿掉了）

    /** 左邊星籤（第 2～5 排的第 1～3 格）、右邊通貨（第 2～5 排的第 7～9 格）。 */
    private static final int[] OMEN_SLOTS = grid(0);
    private static final int[] CURRENCY_SLOTS = grid(6);
    private static final int SLOT_OMEN_LABEL = 1;
    private static final int SLOT_ITEM_LABEL = 4;
    private static final int SLOT_CURRENCY_LABEL = 7;
    private static final int SLOT_OMEN = 13;
    private static final int SLOT_ITEM = 22;
    private static final int SLOT_CURRENCY = 31;
    private static final int SLOT_USE = 40; // 2026-10-23：使用鈕在上、說明在最下排中間（跟其他選單一樣）
    private static final int SLOT_HELP = 52; // 2026-10-23：說明放關閉鈕旁邊，最下排中間只剩翻頁
    private static final int SLOT_CLEAR = 46;

    private String omenId;
    private String currencyId;
    /** 選的裝備：背包第幾格、那件的 uuid（換了格子或換了一件就不算）。 */
    private int itemSlot = -1;
    private String itemUuid;
    /** 2026-10-28：左邊星籤、右邊通貨一起翻頁（同一個頁碼）；這次之前只有通貨翻頁，星籤超過 12 種後面的選不到。 */
    private int page;
    /** 從哪裡打開的（左下角「← 返回」）；null＝直接打 /craft，沒有返回鍵。 */
    private final String backName;
    private final Runnable back;

    public CraftBenchMenu(final ExileCore plugin, final Player player) {
        this(plugin, player, null, null);
    }

    /** 從 NPC（鑑定師）打開：左下角多一個「← 返回 backName」。 */
    public CraftBenchMenu(final ExileCore plugin, final Player player, final String backName, final Runnable back) {
        super(plugin, player, 6, "<dark_gray>洗裝台");
        this.backName = backName;
        this.back = back;
    }

    /**
     * 2026-10-28：總共幾頁＝星籤、通貨哪一邊頁數多就照哪一邊（每頁各 12 格，最少 1 頁）。
     * 這次之前只看通貨，星籤（PoE2 有 26 種）超過 12 種的永遠看不到。
     */
    static int pages(final int omens, final int currencies) {
        final int per = CURRENCY_SLOTS.length;
        return Math.max(1, Math.max((omens + per - 1) / per, (currencies + per - 1) / per));
    }

    private static int[] grid(final int firstColumn) {
        final int[] slots = new int[12];
        int i = 0;
        for (int row = 1; row <= 4; row++) {
            for (int column = firstColumn; column < firstColumn + 3; column++) {
                slots[i++] = slot(row, column);
            }
        }
        return slots;
    }

    /** /craft：打開洗裝台。 */
    public static LiteralCommandNode<CommandSourceStack> command(final ExileCore plugin) {
        return Commands.literal("craft")
                .executes(context -> {
                    if (context.getSource().getExecutor() instanceof Player player) {
                        if (plugin.currencies() == null) {
                            player.sendMessage(plugin.text().prefixed("<red>通貨模組沒有啟用"));
                        } else if (!plugin.players().get(player).hasCharacter()) {
                            player.sendMessage(plugin.text().prefixed("<red>先選一個角色"));
                        } else {
                            new CraftBenchMenu(plugin, player).open();
                        }
                    } else {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    // ---- 資料 ----

    private CurrencyService currencies() {
        return plugin.currencies();
    }

    private int have(final String id) {
        return plugin.economy().wallet().available(player, id);
    }

    private CurrencyType type(final String id) {
        return id == null ? null : currencies().repository().get(id);
    }

    /** 有的星籤（照 currencies.yml 的順序）。 */
    private List<CurrencyType> omens() {
        final List<CurrencyType> list = new ArrayList<>();
        for (final CurrencyType type : currencies().repository().all()) {
            if (type.operation() == CurrencyOperation.OMEN && have(type.id()) > 0) {
                list.add(type);
            }
        }
        return list;
    }

    /** 有的、洗裝台收的通貨。 */
    private List<CurrencyType> benchCurrencies() {
        final List<CurrencyType> list = new ArrayList<>();
        for (final CurrencyType type : currencies().repository().all()) {
            if (BENCH.contains(type.operation()) && !type.isSkillCurrency() && have(type.id()) > 0) {
                list.add(type);
            }
        }
        return list;
    }

    /** 選的那件裝備（還在原來的格子才算）；不在了回傳 null 並清掉選擇。 */
    private ItemStack item() {
        if (itemSlot < 0 || itemUuid == null) {
            return null;
        }
        final ItemStack stack = player.getInventory().getItem(itemSlot);
        final ItemData data = stack == null || stack.isEmpty() ? null : plugin.items().read(stack).orElse(null);
        if (data == null || !itemUuid.equals(data.uuid())) {
            itemSlot = -1;
            itemUuid = null;
            return null;
        }
        return stack;
    }

    /** 這一套（裝備＋星籤＋通貨）現在做不做得了；做得了回傳 null，不然是原因。 */
    private String problem(final ItemStack stack) {
        if (stack == null) {
            return "先點下面背包裡的一件裝備";
        }
        final CurrencyType currency = type(currencyId);
        if (currency == null) {
            return "選一顆右邊的通貨";
        }
        if (have(currency.id()) <= 0) {
            return "沒有" + currency.name() + "了";
        }
        final ItemData data = plugin.items().read(stack).orElse(null);
        if (data == null) {
            return "這件不能洗";
        }
        final ItemData after = data.copy();
        final CurrencyType omen = type(omenId);
        if (omen != null) {
            if (have(omen.id()) <= 0) {
                return "沒有" + omen.name() + "了";
            }
            if (!Crafting.triggers(omen, currency)) {
                return omen.name() + "不會被" + currency.name() + "觸發（" + omen.description() + "）";
            }
            final CurrencyType hanging = type(data.omen());
            if (hanging != null && !hanging.id().equals(omen.id())) {
                return "這件已經掛著" + hanging.name() + "：先拿掉左邊選的星籤，用會觸發它的通貨把它用掉";
            }
            if (hanging == null) {
                if (data.corrupted() || !omen.canApply(data, plugin.items().generator())) {
                    return "這個星籤不能掛在這件上（要已鑑定、沒腐化；能掛的稀有度看星籤的說明）"; // 2026-10-26：有的星籤掛在普通、傳奇上
                }
                after.setOmen(omen.id());
            }
        }
        if (after.corrupted() && !currency.operation().allowedOnCorrupted()) {
            return "已腐化的物品不能再改造";
        }
        if (!currency.canApply(after, plugin.items().generator())) {
            final String why = Crafting.problem(currency, after, plugin.items().generator());
            return currency.name() + (why == null ? "不能用在這件上" : "：" + why);
        }
        final CurrencyType fires = type(after.omen());
        if (Crafting.triggers(fires, currency)) {
            final String why = Crafting.omenProblem(fires, currency, after, plugin.items().generator());
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    // ---- 畫面 ----

    @Override
    protected void draw() {
        frame();
        final ItemStack fill = filler();
        for (int row = 1; row <= 4; row++) {
            set(slot(row, 3), fill);
            set(slot(row, 5), fill);
            set(slot(row, 4), fill);
        }
        set(SLOT_OMEN_LABEL, rowLabel(Material.AMETHYST_SHARD, "星籤", "選一個：按「使用」時先掛上去", "再點一次取消（不用星籤）"));
        set(SLOT_ITEM_LABEL, rowLabel(Material.ANVIL, "裝備", "點下面背包裡的一件裝備"));
        set(SLOT_CURRENCY_LABEL, rowLabel(Material.EMERALD, "通貨", "選一顆：按「使用」時用在裝備上"));

        final List<CurrencyType> omens = omens();
        if (omenId != null && omens.stream().noneMatch(t -> t.id().equals(omenId))) {
            omenId = null; // 用完了
        }
        final List<CurrencyType> list = benchCurrencies();
        if (currencyId != null && list.stream().noneMatch(t -> t.id().equals(currencyId))) {
            currencyId = null;
        }
        final int pages = pages(omens.size(), list.size());
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < OMEN_SLOTS.length; i++) {
            final int index = page * OMEN_SLOTS.length + i;
            set(OMEN_SLOTS[i], index < omens.size() ? choice(omens.get(index), omens.get(index).id().equals(omenId)) : null);
        }
        if (omens.isEmpty()) {
            set(OMEN_SLOTS[0], icon(Material.GRAY_DYE, "<gray>沒有星籤", "<dark_gray>流寇、祭壇會給"));
        } else if (page * OMEN_SLOTS.length >= omens.size()) {
            set(OMEN_SLOTS[0], icon(Material.GRAY_DYE, "<gray>星籤只有 " + pagesOf(omens.size()) + " 頁", "<dark_gray>往前翻看星籤"));
        }
        for (int i = 0; i < CURRENCY_SLOTS.length; i++) {
            final int index = page * CURRENCY_SLOTS.length + i;
            set(CURRENCY_SLOTS[i], index < list.size() ? choice(list.get(index), list.get(index).id().equals(currencyId)) : null);
        }
        if (list.isEmpty()) {
            set(CURRENCY_SLOTS[0], icon(Material.GRAY_DYE, "<gray>沒有能用的通貨"));
        } else if (page * CURRENCY_SLOTS.length >= list.size()) {
            set(CURRENCY_SLOTS[0], icon(Material.GRAY_DYE, "<gray>通貨只有 " + pagesOf(list.size()) + " 頁", "<dark_gray>往前翻看通貨"));
        }
        // 2026-10-28 照站長「洗裝台要有下一頁的按鈕」：翻頁鈕一直在（跟商人賭博一樣，沒有上一頁／下一頁時變灰，看得到第幾頁）；
        // 這次之前只有通貨超過一頁才出現
        set(SLOT_PREV, benchPageButton(false, page, pages, omens.size(), list.size()));
        set(SLOT_NEXT, benchPageButton(true, page, pages, omens.size(), list.size()));

        final ItemStack stack = item();
        final CurrencyType omen = type(omenId);
        final CurrencyType currency = type(currencyId);
        set(SLOT_OMEN, omen == null ? icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>不用星籤", "<dark_gray>左邊點一個星籤")
                : appendLore(currencies().icon(omen, shown(omen)), List.of("", "<#c48bff>使用時先掛上這個星籤", "<yellow>左鍵：不用星籤")));
        set(SLOT_CURRENCY, currency == null ? icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>還沒選通貨", "<dark_gray>右邊點一顆通貨")
                : appendLore(currencies().icon(currency, shown(currency)), List.of("", "<#7fe0c8>使用時用這顆", "<yellow>左鍵：取消")));
        if (stack == null) {
            set(SLOT_ITEM, icon(Material.ITEM_FRAME, "<gray>還沒放裝備", "<dark_gray>點下面背包裡的一件裝備",
                    "<dark_gray>（裝備留在背包裡，直接改那一件）"));
        } else {
            set(SLOT_ITEM, appendLore(stack.clone(), List.of("", "<yellow>左鍵：換一件（再點背包裡的）")));
        }

        final String problem = problem(stack);
        final List<String> lore = new ArrayList<>();
        if (omen != null && currency != null) {
            lore.add("<gray>先掛上 <#c48bff>" + ItemRenderer.escape(omen.name()) + "</#c48bff>，再用 <#7fe0c8>" + ItemRenderer.escape(currency.name()));
        } else if (currency != null) {
            lore.add("<gray>用 <#7fe0c8>" + ItemRenderer.escape(currency.name()));
        }
        final ItemData data = stack == null ? null : plugin.items().read(stack).orElse(null);
        final CurrencyType hanging = data == null ? null : type(data.omen());
        if (hanging != null && omen == null && currency != null && Crafting.triggers(hanging, currency)) {
            lore.add("<#c48bff>裝備上掛著的" + ItemRenderer.escape(hanging.name()) + "這次會生效");
        }
        if (omen != null) {
            lore.add("<dark_gray>星籤剩 " + have(omen.id()) + " 個");
        }
        if (currency != null) {
            lore.add("<dark_gray>" + ItemRenderer.escape(currency.name()) + "剩 " + have(currency.id()) + " 個");
        }
        // 2026-10-29 照站長：用去劣籤時先寫出會移除哪一條（一樣差的寫「其中一條」）
        final CurrencyType firing = omen != null ? omen
                : hanging != null && currency != null && Crafting.triggers(hanging, currency) ? hanging : null;
        if (firing != null && firing.omen() != null && firing.omen().effect() == CurrencyType.Omen.Effect.REMOVE_LOWEST
                && data != null && data.identified() && !data.affixes().isEmpty()) {
            final List<tw.exilecore.item.RolledAffix> worst = Crafting.lowestAffixes(data, plugin.items().repository());
            if (!worst.isEmpty()) {
                final ItemData only = data.copy();
                only.affixes().clear();
                only.affixes().addAll(worst);
                final List<tw.exilecore.item.ItemStats.AffixLine> lines = tw.exilecore.item.ItemStats.affixLines(plugin.items().repository(), only);
                lore.add("<#c48bff>" + ItemRenderer.escape(firing.name()) + "會移除" + (lines.size() > 1 ? "其中一條（一樣差，隨機）" : "") + "：");
                for (final tw.exilecore.item.ItemStats.AffixLine each : lines) {
                    lore.add("<#ff7070>　－ <gray>" + ItemRenderer.escape(each.text()) + (each.tier() > 0 ? " <dark_gray>(T" + each.tier() + ")" : ""));
                }
            }
        }
        lore.add("");
        lore.add(problem == null ? "<yellow>左鍵：使用（可以一直按）" : "<red>" + ItemRenderer.escape(problem));
        final ItemStack use = icon(problem == null ? Material.SMITHING_TABLE : Material.BARRIER, problem == null ? "<#f5b942>使用" : "<gray>使用", lore);
        set(SLOT_USE, problem == null ? glowing(use) : use);

        set(SLOT_CLEAR, icon(Material.WATER_BUCKET, "<white>全部清掉", "<gray>星籤、通貨、裝備都不選", "<yellow>左鍵：清掉"));
        set(SLOT_HELP, helpButton(List.of(
                "左邊選星籤（可以不選）、右邊選通貨，",
                "再點下面背包裡要洗的裝備。",
                "按「使用」：先掛上星籤，再用通貨；",
                "選好之後一直按就一直洗（每次各扣一顆）。",
                "",
                "一頁放不下：下面的箭頭翻頁（星籤、通貨一起翻）。",
                "通貨從通貨袋、背包、倉庫通貨頁扣。",
                "整套做不了（星籤不會被觸發、用不上去）就什麼都不扣。",
                "<dark_gray>僥倖石、符文、映星鏡、蒸餾情緒照原本拿在游標上點")));
        if (back != null) {
            set(SLOT_BACK, backButton(backName));
        }
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private static int pagesOf(final int count) {
        return Math.max(1, (count + CURRENCY_SLOTS.length - 1) / CURRENCY_SLOTS.length);
    }

    /** 翻頁鈕：寫出星籤、通貨各有幾頁；只有一頁時變灰。 */
    private ItemStack benchPageButton(final boolean next, final int page, final int pages, final int omens, final int currencies) {
        final String count = "<dark_gray>星籤 " + omens + " 種（" + pagesOf(omens) + " 頁）、通貨 " + currencies + " 種（" + pagesOf(currencies) + " 頁）";
        if (pages <= 1) {
            return icon(Material.GRAY_DYE, "<dark_gray>" + (next ? "下一頁 ▶" : "◀ 上一頁"), List.of("<gray>第 1／1 頁", count, "<dark_gray>只有一頁"));
        }
        return appendLore(pageButton(next, page, pages), List.of(count));
    }

    /** 圖示上顯示的數量（最多一疊；真正的數量寫在說明）。 */
    private int shown(final CurrencyType type) {
        return Math.max(1, Math.min(have(type.id()), Math.max(1, type.stack())));
    }

    /** 一個選項：通貨本身的圖示＋有幾個；選到的發光／金框。 */
    private ItemStack choice(final CurrencyType type, final boolean selected) {
        final int count = have(type.id());
        final ItemStack stack = appendLore(currencies().icon(type, shown(type)), List.of("",
                "<gray>有 <white>" + count + "</white> 個（通貨袋＋背包＋倉庫）",
                selected ? "<green>已選　<yellow>左鍵：取消" : "<yellow>左鍵：選這個"));
        return selected ? highlight(stack) : stack;
    }

    // ---- 點擊 ----

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        for (int i = 0; i < OMEN_SLOTS.length; i++) {
            if (OMEN_SLOTS[i] == slot) {
                final List<CurrencyType> omens = omens();
                final int index = page * OMEN_SLOTS.length + i;
                if (index < omens.size()) {
                    omenId = omens.get(index).id().equals(omenId) ? null : omens.get(index).id();
                    redraw();
                }
                return;
            }
        }
        for (int i = 0; i < CURRENCY_SLOTS.length; i++) {
            if (CURRENCY_SLOTS[i] == slot) {
                final List<CurrencyType> list = benchCurrencies();
                final int index = page * CURRENCY_SLOTS.length + i;
                if (index < list.size()) {
                    currencyId = list.get(index).id().equals(currencyId) ? null : list.get(index).id();
                    redraw();
                }
                return;
            }
        }
        switch (slot) {
            case SLOT_OMEN -> {
                omenId = null;
                redraw();
            }
            case SLOT_CURRENCY -> {
                currencyId = null;
                redraw();
            }
            case SLOT_ITEM -> {
                itemSlot = -1;
                itemUuid = null;
                redraw();
            }
            case SLOT_USE -> {
                if (acceptAction(click)) {
                    use();
                }
            }
            case SLOT_CLEAR -> {
                omenId = null;
                currencyId = null;
                itemSlot = -1;
                itemUuid = null;
                redraw();
            }
            case SLOT_PREV, SLOT_NEXT -> {
                final int pages = pages(omens().size(), benchCurrencies().size());
                if (pages <= 1) {
                    return;
                }
                page = Math.floorMod(page + (slot == SLOT_NEXT ? 1 : -1), pages);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                redraw();
            }
            case SLOT_CLOSE_BAR -> goBack();
            case SLOT_BACK -> {
                if (back != null) {
                    back.run();
                }
            }
            default -> {
            }
        }
    }

    /** 點背包：裝備＝選它；星籤、通貨＝選那一種。 */
    @Override
    protected void onBottomClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        final ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final CurrencyType currency = currencies().read(stack).orElse(null);
        if (currency != null) {
            if (currency.operation() == CurrencyOperation.OMEN) {
                omenId = currency.id();
                final List<CurrencyType> omens = omens();
                for (int i = 0; i < omens.size(); i++) {
                    if (omens.get(i).id().equals(omenId)) {
                        page = i / OMEN_SLOTS.length; // 2026-10-28：翻到它那一頁
                    }
                }
            } else if (BENCH.contains(currency.operation()) && !currency.isSkillCurrency()) {
                currencyId = currency.id();
                final List<CurrencyType> list = benchCurrencies();
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).id().equals(currencyId)) {
                        page = i / CURRENCY_SLOTS.length; // 翻到它那一頁
                    }
                }
            } else {
                fail(currency.name() + "不能在洗裝台用：拿在游標上點裝備");
                return;
            }
            redraw();
            return;
        }
        if (!plugin.items().isEquipment(stack) || slot < 0 || slot >= 36) {
            return;
        }
        final ItemData data = plugin.items().read(stack).orElse(null);
        if (data == null) {
            return;
        }
        itemSlot = slot;
        itemUuid = data.uuid();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        redraw();
    }

    // ---- 使用 ----

    private void use() {
        final ItemStack stack = item();
        final String problem = problem(stack);
        if (problem != null) {
            fail(problem);
            redraw();
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            fail("先選一個角色");
            return;
        }
        final Long characterId = state.character().id();
        final ItemData data = plugin.items().read(stack).orElseThrow();
        final CurrencyType omen = type(omenId);
        final CurrencyType currency = type(currencyId);
        final var wallet = plugin.economy().wallet();
        // 1. 星籤（裝備上已經掛著同一個就不用再掛）
        if (omen != null && data.omen() == null) {
            if (!wallet.take(player, omen.id(), 1)) {
                fail("沒有" + omen.name() + "了");
                redraw();
                return;
            }
            final CurrencyService.Result attached = currencies().apply(currencies().create(omen, 1), stack, player.getName(), characterId);
            if (attached != CurrencyService.Result.APPLIED) {
                wallet.deposit(player, omen.id(), 1); // 上面檢查過，不會走到這裡；保險：還回去
                fail(omen.name() + "掛不上去");
                redraw();
                return;
            }
        }
        // 2. 通貨
        if (!wallet.take(player, currency.id(), 1)) {
            fail("沒有" + currency.name() + "了");
            redraw();
            return;
        }
        final CurrencyService.Result result = currencies().apply(currencies().create(currency, 1), stack, player.getName(), characterId);
        final CurrencyType fired = currencies().takeFiredOmen();
        final String changes = currencies().takeChanges().message(); // 2026-10-27 照站長：移除、新增了哪一條
        if (result == CurrencyService.Result.APPLIED && changes != null) {
            player.sendMessage(plugin.text().prefixed(changes));
        }
        switch (result) {
            case APPLIED -> {
                if (plugin.guide() != null) {
                    plugin.guide().milestone(player, "currency"); // 2026-10-20 新手教學
                }
                player.getInventory().setItem(itemSlot, stack);
                if (fired != null) {
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#c48bff>" + ItemRenderer.escape(fired.name()) + "生效<gray>，用了"
                            + ItemRenderer.escape(currency.name())));
                } else {
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>用了" + ItemRenderer.escape(currency.name())));
                }
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
            }
            case DESTROYED -> {
                player.getInventory().setItem(itemSlot, null);
                itemSlot = -1;
                itemUuid = null;
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>物品碎掉了"));
                player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.8f, 0.8f);
            }
            default -> {
                wallet.deposit(player, currency.id(), 1); // 用不上去：還回去（上面檢查過，保險）
                fail(currency.name() + "不能用在這件上");
            }
        }
        plugin.players().markDirty(player);
        redraw();
    }

    private void fail(final String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(message)));
    }
}
