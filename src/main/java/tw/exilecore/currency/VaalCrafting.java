package tw.exilecore.currency;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.map.AtlasTablets;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 2026-11-07 照站長「星脈深坑掉 PoE2 Fate of the Vaal 的通貨」：不是直接改裝備詞綴的那幾顆（{@link CurrencyListener} 轉過來）。
 * <ul>
 *   <li>核心擾亂器（Core Destabiliser）：用在一顆魂核符文上——複製一顆、變成別的魂核、變成一顆遠古增幅（假說）、或碎掉。</li>
 *   <li>遠古灌注器（Ancient Infuser）：用在一片先驅石板上——碎掉、變成別種石板、多一片同樣的、或變成傳奇石板殘酷霸權。</li>
 *   <li>萃取石（Orb of Extraction）：毀掉一件裝備，嵌在上面的符文、魂核還給你（遠古增幅嵌死了，不還）。</li>
 * </ul>
 * poe2db 沒有寫各種結果的機率，下面的機率是估的（寫在常數，之後可以調）。
 */
public final class VaalCrafting {

    private VaalCrafting() {
    }

    /** 策匠石碎掉的機率（PoE2：一半一半，社群幾個來源都一樣）。 */
    public static final double ARCHITECT_DESTROY = 0.5;

    /** 核心擾亂器：碎掉、複製、變成別的魂核、變成假說的機率（poe2db 沒寫，估的；加起來 1）。 */
    static final double DESTABILISE_DESTROY = 0.30;
    static final double DESTABILISE_DUPLICATE = 0.15;
    static final double DESTABILISE_THESIS = 0.10;

    /** 遠古灌注器：碎掉、多一片、變成殘酷霸權的機率（其餘變成別種石板；poe2db 沒寫，估的）。 */
    static final double ANCIENT_DESTROY = 0.25;
    static final double ANCIENT_DOUBLE = 0.25;
    static final double ANCIENT_HEGEMONY = 0.10;

    /** 魂核符文的符文代號前綴（runes.yml 的 core_…）。 */
    static final String CORE_PREFIX = "core_";
    /** 2026-11-07：遠古增幅（假說）的符文代號前綴（runes.yml 的 thesis_…）：嵌上去拿不下來。 */
    public static final String THESIS_PREFIX = "thesis_";

    /** 這顆通貨是不是魂核符文（一般魂核，不含假說）。 */
    static boolean isSoulCore(final CurrencyType type) {
        return type.operation() == CurrencyOperation.RUNE && type.affix() != null && type.affix().startsWith(CORE_PREFIX);
    }

    /** 這顆通貨是不是先驅石板。 */
    static boolean isTablet(final CurrencyType type) {
        return AtlasTablets.Kind.ofCurrency(type.id()) != null;
    }

    /** 2026-11-07：這個符文代號是不是遠古增幅（假說；嵌上去拿不下來，萃取石也不還）。 */
    /** 2026-11-08 照站長「增幅系統」：增幅的符文代號開頭（runes.yml 的 augment_＊）。 */
    public static final String AUGMENT_PREFIX = "augment_";

    public static boolean socketBound(final String runeId) {
        return runeId != null && (runeId.startsWith(THESIS_PREFIX) || runeId.startsWith(AUGMENT_PREFIX)); // 2026-11-08：增幅（梅德偉的照料…）也嵌死
    }

    /** 用在一顆通貨（魂核、石板）上：一疊只動一顆，結果進背包。 */
    static void useOnCurrency(final ExileCore plugin, final Player player, final InventoryClickEvent event, final CurrencyType tool,
                              final CurrencyType onto, final ItemStack target) {
        final CurrencyService currencies = plugin.currencies();
        final RandomGenerator random = plugin.items().random();
        final List<CurrencyType> out = new ArrayList<>();
        final String result;
        if (tool.operation() == CurrencyOperation.DESTABILISE) {
            final Outcome outcome = destabilise(currencies.repository(), onto, random);
            out.addAll(outcome.items());
            result = outcome.text();
        } else {
            final Outcome outcome = ancientInfuse(currencies.repository(), onto, random);
            out.addAll(outcome.items());
            result = outcome.text();
        }
        // 用掉一顆工具、一顆目標
        final ItemStack cursor = event.getCursor();
        cursor.setAmount(cursor.getAmount() - 1);
        event.getView().setCursor(cursor.getAmount() <= 0 ? ItemStack.empty() : cursor);
        event.setCurrentItem(target.getAmount() <= 1 ? ItemStack.empty() : target.asQuantity(target.getAmount() - 1));
        final List<ItemStack> stacks = new ArrayList<>();
        for (final CurrencyType type : out) {
            stacks.add(currencies.create(type, 1));
        }
        give(player, stacks);
        plugin.economy().ledger().of(player).consumed(tool.id(), tw.exilecore.economy.EconomyLedger.CRAFT, 1);
        player.sendMessage(plugin.text().prefixed("<#d20000>" + ItemRenderer.escape(tool.name()) + "<gray>用在" + ItemRenderer.escape(onto.name())
                + "上：<white>" + ItemRenderer.escape(result)));
        player.playSound(player.getLocation(), out.isEmpty() ? Sound.BLOCK_GLASS_BREAK : Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1f);
        plugin.getSLF4JLogger().info("[currency] {} 對 {} 使用 {}：{}", player.getName(), onto.id(), tool.id(), result);
    }

    /** 一次的結果：拿到哪些通貨（空的 = 碎掉）、說明。 */
    record Outcome(List<CurrencyType> items, String text) {
    }

    /** 核心擾亂器的結果（純邏輯，測試用）。 */
    static Outcome destabilise(final CurrencyRepository repository, final CurrencyType core, final RandomGenerator random) {
        final double roll = random.nextDouble();
        if (roll < DESTABILISE_DESTROY) {
            return new Outcome(List.of(), "碎掉了");
        }
        if (roll < DESTABILISE_DESTROY + DESTABILISE_DUPLICATE) {
            return new Outcome(List.of(core, core), "複製成兩顆");
        }
        if (roll < DESTABILISE_DESTROY + DESTABILISE_DUPLICATE + DESTABILISE_THESIS) {
            final List<CurrencyType> theses = runesWithPrefix(repository, THESIS_PREFIX, null);
            if (!theses.isEmpty()) {
                final CurrencyType thesis = theses.get(random.nextInt(theses.size()));
                return new Outcome(List.of(thesis), "變成了遠古增幅「" + thesis.name() + "」");
            }
        }
        final List<CurrencyType> others = runesWithPrefix(repository, CORE_PREFIX, core.id());
        if (others.isEmpty()) {
            return new Outcome(List.of(core), "沒有變化");
        }
        final CurrencyType other = others.get(random.nextInt(others.size()));
        return new Outcome(List.of(other), "變成了" + other.name());
    }

    /** 遠古灌注器的結果（純邏輯，測試用）。 */
    static Outcome ancientInfuse(final CurrencyRepository repository, final CurrencyType tablet, final RandomGenerator random) {
        final AtlasTablets.Kind kind = AtlasTablets.Kind.ofCurrency(tablet.id());
        final double roll = random.nextDouble();
        if (roll < ANCIENT_DESTROY) {
            return new Outcome(List.of(), "碎掉了");
        }
        if (roll < ANCIENT_DESTROY + ANCIENT_DOUBLE) {
            // 原作是「最多 10 次使用」；我們的石板一片固定 3 次，改成多一片同樣的
            return new Outcome(List.of(tablet, tablet), "多了一片（兩片" + tablet.name() + "）");
        }
        if (roll < ANCIENT_DESTROY + ANCIENT_DOUBLE + ANCIENT_HEGEMONY && kind != AtlasTablets.Kind.HEGEMONY) {
            // 原作是「多一條超過上限的詞綴」；我們的石板沒有詞綴，改成變成傳奇石板
            final CurrencyType hegemony = repository.get(AtlasTablets.Kind.HEGEMONY.currencyId());
            if (hegemony != null) {
                return new Outcome(List.of(hegemony), "變成了傳奇石板「" + hegemony.name() + "」");
            }
        }
        final List<CurrencyType> others = new ArrayList<>();
        for (final AtlasTablets.Kind other : AtlasTablets.towerKinds()) {
            final CurrencyType type = repository.get(other.currencyId());
            if (other != kind && type != null) {
                others.add(type);
            }
        }
        if (others.isEmpty()) {
            return new Outcome(List.of(tablet), "沒有變化");
        }
        final CurrencyType other = others.get(random.nextInt(others.size()));
        return new Outcome(List.of(other), "變成了" + other.name());
    }

    /** 符文代號開頭是 prefix 的符文通貨（排除 exclude 這顆）。 */
    static List<CurrencyType> runesWithPrefix(final CurrencyRepository repository, final String prefix, final String exclude) {
        final List<CurrencyType> list = new ArrayList<>();
        for (final CurrencyType type : repository.all()) {
            if (type.operation() == CurrencyOperation.RUNE && type.affix() != null && type.affix().startsWith(prefix)
                    && !type.id().equals(exclude)) {
                list.add(type);
            }
        }
        list.sort(java.util.Comparator.comparing(CurrencyType::id));
        return list;
    }

    /** 這個符文代號的通貨（嵌之前的那顆）；找不到回傳 null。 */
    static CurrencyType runeCurrency(final CurrencyRepository repository, final String runeId) {
        for (final CurrencyType type : repository.all()) {
            if (type.operation() == CurrencyOperation.RUNE && runeId.equals(type.affix())) {
                return type;
            }
        }
        return null;
    }

    /** 萃取石：裝備毀掉，符文、魂核還給玩家（遠古增幅不還）。 */
    static void extract(final ExileCore plugin, final Player player, final InventoryClickEvent event, final CurrencyType orb,
                        final ItemStack target, final String why) {
        final ItemData data = plugin.items().read(target).orElse(null);
        // 2026-11-07 修正（檢查發現）：鏡像複製品不能萃取（複製品帶著同樣的符文，毀掉拿符文等於用映星鏡複製符文）；已腐化的照原作可以
        final String blocked = data != null && data.mirrored() ? "鏡像複製品不能再改造" : null;
        if (data == null || blocked != null || !orb.operation().canApply(data, plugin.items().generator())) {
            final String reason = blocked != null ? blocked : why;
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(orb.name()) + "："
                    + (reason != null ? ItemRenderer.escape(reason) : data != null && data.locked() ? "鎖定的物品不能毀掉" : "這件沒有嵌符文")));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        if (event.getClickedInventory() != player.getInventory()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(orb.name()) + "：裝備要放在自己的背包裡"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        final List<ItemStack> back = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        int bound = 0;
        for (final String rune : data.runes()) {
            if (socketBound(rune)) {
                bound++;
                continue;
            }
            final CurrencyType type = runeCurrency(plugin.currencies().repository(), rune);
            if (type != null) {
                back.add(plugin.currencies().create(type, 1));
                names.add(type.name());
            }
        }
        final ItemStack cursor = event.getCursor();
        cursor.setAmount(cursor.getAmount() - 1);
        event.getView().setCursor(cursor.getAmount() <= 0 ? ItemStack.empty() : cursor);
        event.setCurrentItem(ItemStack.empty());
        give(player, back);
        plugin.economy().ledger().of(player).consumed(orb.id(), tw.exilecore.economy.EconomyLedger.CRAFT, 1);
        player.sendMessage(plugin.text().prefixed("<#d20000>" + ItemRenderer.escape(orb.name()) + "<gray>：裝備毀掉了，拿回 <white>"
                + (names.isEmpty() ? "（沒有）" : ItemRenderer.escape(String.join("、", names))) + (bound > 0 ? "<dark_gray>（遠古增幅嵌死了，不還）" : "")));
        player.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.8f, 1f);
        plugin.getSLF4JLogger().info("[currency] {} 用 {} 毀掉 {}：拿回 {}", player.getName(), orb.id(), data.uuid(), names);
    }

    private static void give(final Player player, final List<ItemStack> stacks) {
        for (final ItemStack stack : stacks) {
            for (final ItemStack left : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
    }
}
