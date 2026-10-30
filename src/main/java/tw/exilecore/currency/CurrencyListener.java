package tw.exilecore.currency;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import java.util.List;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;

/**
 * 通貨的使用介面：把通貨拿在游標上，左鍵點一件裝備就套用一顆。
 * 背包、箱子、之後的倉庫與交易視窗都是同一套邏輯。
 */
public final class CurrencyListener implements Listener {

    private final ExileCore plugin;
    private final CurrencyService currencies;
    /** 2026-10-26 預見鎖：玩家看過結果、等著再點一次確認的那一次（物品代號＋通貨代號＋時間）。 */
    private final java.util.Map<java.util.UUID, Pending> pending = new java.util.HashMap<>();

    /** 2026-10-26：看過預見結果的那一次。 */
    private record Pending(String itemUuid, String currencyId, long at) {
    }

    /** 看過預見結果之後幾毫秒內再點一次才算確認。 */
    private static final long FORESEE_CONFIRM_MS = 20_000;

    public CurrencyListener(final ExileCore plugin, final CurrencyService currencies) {
        this.plugin = plugin;
        this.currencies = currencies;
    }

    /** 2026-10-27：離線就清掉他看過的預見結果（這次之前一直留在記憶體裡）。 */
    @EventHandler
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final ItemStack cursor = event.getCursor();
        final ItemStack target = event.getCurrentItem();
        if (cursor.isEmpty() || target == null || target.isEmpty()) {
            return;
        }
        if (!currencies.isCurrency(cursor)) {
            return;
        }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            return;
        }
        final CurrencyType holding = currencies.read(cursor).orElse(null);
        if (holding != null && holding.operation() == CurrencyOperation.RESONANCE) {
            // 2026-10-18：共鳴石用在背包裡的專屬輔助寶石上（不是裝備）：寶石標成共鳴，之後不佔一個技能的專屬輔助名額
            if (plugin.skills() != null && plugin.skills().gems().read(target).isPresent()) {
                event.setCancelled(true);
                resonate(player, event, holding, target);
            }
            return;
        }
        if (!plugin.items().isEquipment(target)) {
            // 通貨點到非裝備：照原版行為（交換／堆疊）
            return;
        }
        if (holding != null && holding.operation() == CurrencyOperation.NONE) {
            // 不能用在裝備上的（碎片、未切割寶石）：照原版拿起、交換，不要擋著說「不能用」
            return;
        }
        event.setCancelled(true);

        final PlayerState state = plugin.players().get(player);
        final Long characterId = state.hasCharacter() ? state.character().id() : null;
        final String why = currencies.explain(cursor, target); // 星魄、催化劑、星籤不能用的原因（先問，apply 之後資料就變了）
        final CurrencyType clicked = currencies.read(cursor).orElse(null);
        if (clicked != null && clicked.operation() == CurrencyOperation.RUNE && runesFull(target)) {
            // 2026-10：符文孔都滿了：選一個舊的符文換掉（2026-10-30 照站長：沒腐化的換下來的那顆還給你；腐化的才會消失）
            overwriteRune(player, event, clicked, target);
            return;
        }
        if (clicked != null && clicked.operation() == CurrencyOperation.MIRROR) {
            // 映星鏡：會多出一件複製品，先確認（裝備要在自己的背包裡、背包要有空位，確定了才扣）
            mirror(player, event, clicked, target, why);
            return;
        }
        if (clicked != null && clicked.operation() == CurrencyOperation.INSTIL) {
            // 蒸餾情緒：不是直接用，打開「注入」選一個核心（項鍊要在自己的背包裡，選完才扣）
            instil(player, event, clicked, target, why);
            return;
        }
        if (clicked != null && why == null && !confirmForesight(player, clicked, cursor, target)) {
            return; // 2026-10-26 預見鎖：先看結果，再點一次才用
        }
        final CurrencyService.Result result = currencies.apply(cursor, target, player.getName(), characterId);
        final CurrencyType used = currencies.read(cursor).orElse(null);
        final boolean chance = used != null && used.operation() == CurrencyOperation.CHANCE;
        switch (result) {
            case APPLIED -> {
                if (plugin.guide() != null) {
                    plugin.guide().milestone(player, "currency"); // 2026-10-20 新手教學
                    if (used != null && used.operation() == CurrencyOperation.RUNE) {
                        plugin.guide().milestone(player, tw.exilecore.guide.GuideService.RUNE); // 2026-10-30
                    }
                }
                cursor.setAmount(cursor.getAmount() - 1);
                event.getView().setCursor(cursor.getAmount() <= 0 ? ItemStack.empty() : cursor);
                event.setCurrentItem(target);
                final CurrencyType fired = currencies.takeFiredOmen();
                final String changes = currencies.takeChanges().message(); // 2026-10-27 照站長：移除、新增了哪一條
                if (changes != null) {
                    player.sendMessage(plugin.text().prefixed(changes));
                }
                if (chance && fired != null && plugin.items().read(target).map(d -> d.unique() == null).orElse(false)) {
                    // 2026-10-26（Omen of Chance）：失敗了，但星籤讓物品沒碎
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#c48bff>僥倖石失敗，" + fired.name() + "讓物品沒有碎掉（星籤用掉了）"));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.8f);
                } else if (chance) {
                    player.sendMessage(plugin.text().prefixed("<#af6025>僥倖石成功：").append(target.effectiveName()));
                    if (plugin.loot() != null) {
                        plugin.items().read(target).ifPresent(made -> plugin.loot().broadcastChase(player, made)); // 追逐級：全服廣播
                    }
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
                } else if (used != null && used.operation() == CurrencyOperation.OMEN) {
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#c48bff>掛上" + used.name() + "：" + used.description()));
                    player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.6f, 1.3f);
                } else if (fired != null) {
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#c48bff>" + fired.name() + "生效，用掉了"));
                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
                } else {
                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
                }
            }
            case DESTROYED -> {
                cursor.setAmount(cursor.getAmount() - 1);
                event.getView().setCursor(cursor.getAmount() <= 0 ? ItemStack.empty() : cursor);
                event.setCurrentItem(ItemStack.empty());
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>僥倖石失敗：物品碎掉了"));
                player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.8f, 0.8f);
            }
            case NOT_APPLICABLE -> {
                final String name = used == null ? "這顆通貨" : used.name();
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(used != null && used.isSkillCurrency()
                        ? "<red>" + name + "要用在技能上：打開技能面板（/skills），拿著它點技能"
                        : chance ? "<red>" + name + "只能用在普通物品上，而且這個基底要有能做出來的傳奇（物品等級也要夠）"
                        : why != null ? "<red>" + name + "：" + tw.exilecore.item.ItemRenderer.escape(why)
                        : "<red>" + name + " 不能用在這件物品上"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            }
            default -> {
                // 不是裝備或不是通貨：不會走到這裡
            }
        }
    }

    /**
     * 2026-10-26 照 PoE2 的 Hinekora's Lock：上了預見鎖的物品，拿通貨點它先把結果寫在聊天欄（不用掉通貨），
     * 20 秒內同一顆通貨再點同一件才真的用（結果跟看到的一樣）。沒上預見鎖、這顆不能用回傳 true（照平常）。
     *
     * @return 可以用了（true）；這次只是顯示結果（false）
     */
    private boolean confirmForesight(final Player player, final CurrencyType clicked, final ItemStack cursor, final ItemStack target) {
        final CurrencyService.Preview preview = currencies.foresee(cursor, target);
        if (preview == null) {
            return true;
        }
        final String uuid = preview.after().uuid();
        final Pending last = pending.get(player.getUniqueId());
        final long now = System.currentTimeMillis();
        if (last != null && last.itemUuid().equals(uuid) && last.currencyId().equals(clicked.id()) && now - last.at() <= FORESEE_CONFIRM_MS) {
            pending.remove(player.getUniqueId());
            return true;
        }
        pending.values().removeIf(p -> now - p.at() > FORESEE_CONFIRM_MS); // 2026-10-27：過期的順手清掉；這次之前一直留著
        pending.put(player.getUniqueId(), new Pending(uuid, clicked.id(), now));
        player.sendMessage(plugin.text().prefixed("<#e0c070>預見：用" + tw.exilecore.item.ItemRenderer.escape(clicked.name()) + "之後——"));
        if (preview.destroyed()) {
            player.sendMessage(plugin.text().parse("<red>  物品會碎掉"));
        } else {
            final tw.exilecore.item.ItemData after = preview.after();
            final tw.exilecore.item.UniqueItem unique = after.unique() == null ? null : plugin.items().repository().unique(after.unique());
            player.sendMessage(plugin.text().parse("<gray>  " + after.rarity().displayName()
                    + (unique != null ? "：<#af6025>" + tw.exilecore.item.ItemRenderer.escape(unique.name()) : "")
                    + (after.quality() > 0 ? "<gray>・品質 " + after.quality() + "%" : "")
                    + (after.corrupted() ? "<#d20000>・已腐化" : "") + (after.sanctified() ? "<#ffe08a>・已聖化" : "")));
            for (final tw.exilecore.item.RolledAffix rolled : after.affixes()) {
                final tw.exilecore.item.Affix affix = plugin.items().repository().affix(rolled.id());
                if (affix != null) {
                    player.sendMessage(plugin.text().parse("<#8888ff>  " + tw.exilecore.item.ItemRenderer.escape(affix.render(rolled.vars()))));
                }
            }
        }
        player.sendMessage(plugin.text().parse("<yellow>  20 秒內拿同一顆再點一次才會用（預見鎖會用掉）"));
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
        return false;
    }

    /**
     * 2026-10-18：共鳴石點一顆寶石：要是「專屬掉落的輔助寶石」（未切割輔助寶石切不出來的那種）、還沒共鳴。
     * 一疊只處理一顆（共鳴的跟沒共鳴的不能疊在一起），剩下的留在原格。流水帳記 CRAFT。
     */
    private void resonate(final Player player, final InventoryClickEvent event, final CurrencyType orb, final ItemStack target) {
        final tw.exilecore.skill.GemService gems = plugin.skills().gems();
        final tw.exilecore.skill.GemData gem = gems.read(target).orElse(null);
        String why = null;
        if (gem == null || gem.kind() != tw.exilecore.skill.GemData.Kind.SUPPORT) {
            why = "只能用在專屬掉落的輔助寶石上";
        } else {
            final tw.exilecore.skill.SupportDefinition support = plugin.skills().config().support(gem.id());
            if (support == null || support.cuttable() || support.lineage()) { // 2026-10-27 照站長：血脈輔助不能用共鳴石
                why = "只能用在專屬掉落的輔助寶石上（" + (support == null ? gem.id() : support.name()) + "不是）";
            } else if (gem.resonant()) {
                why = support.name() + "已經共鳴了";
            }
        }
        if (why != null) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + orb.name() + "：" + tw.exilecore.item.ItemRenderer.escape(why)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        if (target.getAmount() > 1 && event.getClickedInventory() != player.getInventory()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>一疊寶石只能共鳴一顆：先把寶石拿到自己的背包，或拆成一顆"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        final ItemStack marked = gems.create(gem.withResonant(true), 1);
        cursor(event, target.getAmount() - 1 <= 0 ? ItemStack.empty() : target.asQuantity(target.getAmount() - 1), event.getCursor());
        if (target.getAmount() > 1) {
            gems.giveAll(player, List.of(marked)); // 疊的剩下留在原格，共鳴的那顆進背包
        } else {
            event.setCurrentItem(marked);
        }
        plugin.economy().ledger().consumed(orb.id(), tw.exilecore.economy.EconomyLedger.CRAFT, 1);
        player.sendMessage(plugin.text().prefixed("<#c48bff>共鳴：").append(marked.effectiveName())
                .append(plugin.text().parse("<gray>（不佔一個技能的專屬輔助名額）")));
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.1f);
    }

    /** 2026-10-18：共鳴石用掉一顆；一疊多顆時剩下的寶石放回原格。 */
    private static void cursor(final InventoryClickEvent event, final ItemStack rest, final ItemStack cursor) {
        cursor.setAmount(cursor.getAmount() - 1);
        event.getView().setCursor(cursor.getAmount() <= 0 ? ItemStack.empty() : cursor);
        if (!rest.isEmpty()) {
            event.setCurrentItem(rest);
        }
    }

    /** 這件裝備有符文孔、已鑑定，而且孔都滿了（2026-10-29 照站長：已腐化的也能替換符文；這次之前腐化的只能嵌空孔）。 */
    private boolean runesFull(final ItemStack target) {
        final tw.exilecore.item.ItemData data = plugin.items().read(target).orElse(null);
        if (data == null || !data.identified() || data.mirrored()) {
            return false;
        }
        final tw.exilecore.item.ItemBase base = plugin.items().repository().base(data.base());
        final int slots = tw.exilecore.item.Runes.slots(plugin.items().repository(), base, data);
        return slots > 0 && data.runes().size() >= slots;
    }

    /** 符文點到孔都滿的裝備：問要覆蓋哪一顆。 */
    private void overwriteRune(final Player player, final InventoryClickEvent event, final CurrencyType rune, final ItemStack target) {
        if (event.getClickedInventory() != player.getInventory()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>符文孔滿了：先把裝備拿到自己的背包，再拿符文點它（可以選要覆蓋哪一顆）"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        final tw.exilecore.item.ItemData data = plugin.items().read(target).orElse(null);
        if (data == null) {
            return;
        }
        final tw.exilecore.item.ItemBase base = plugin.items().repository().base(data.base());
        final int slot = event.getSlot();
        final String uuid = data.uuid();
        final List<tw.exilecore.gui.Dialogs.Option> options = new java.util.ArrayList<>();
        for (int i = 0; i < data.runes().size(); i++) {
            final tw.exilecore.item.Runes.Rune old = plugin.items().repository().rune(data.runes().get(i));
            final String text = old == null ? data.runes().get(i)
                    : old.name() + "（" + String.join("、", old.on(base).stream().map(tw.exilecore.item.Runes.Line::text).toList()) + "）";
            options.add(new tw.exilecore.gui.Dialogs.Option(String.valueOf(i), "第 " + (i + 1) + " 孔：" + text));
        }
        // 游標上的符文先放回背包（對話框打開時背包會關掉；確定了才從背包、通貨袋扣一顆）
        final ItemStack cursor = event.getView().getCursor();
        event.getView().setCursor(ItemStack.empty());
        if (cursor != null && !cursor.isEmpty()) {
            // 2026-10：放不下的進隨身背包，再不行掉在腳下只有自己撿得到（以前掉成誰都能撿的）
            plugin.economy().wallet().dropFor(player, plugin.economy().wallet().give(player, cursor));
        }
        player.closeInventory();
        plugin.dialogs().form(player, "<dark_gray>覆蓋符文",
                List.of("<gray>這件的符文孔都滿了。", "<gray>選一顆舊的符文換成「<white>" + tw.exilecore.item.ItemRenderer.escape(rune.name()) + "</white>」",
                        data.corrupted() ? "<red>已腐化：被換掉的那顆會消失（拿不回來）" : "<green>被換掉的那顆會還給你"),
                List.of(new tw.exilecore.gui.Dialogs.Choice("slot", "要覆蓋哪一顆", options, "0")),
                "覆蓋", answers -> {
                    final ItemStack stack = player.getInventory().getItem(slot);
                    final tw.exilecore.item.ItemData now = stack == null ? null : plugin.items().read(stack).orElse(null);
                    if (now == null || !uuid.equals(now.uuid())) {
                        fail(player, "裝備不在原來的格子了，重新拿符文點它");
                        return;
                    }
                    final int index;
                    try {
                        index = Integer.parseInt(answers.get("slot"));
                    } catch (final NumberFormatException e) {
                        return;
                    }
                    if (index < 0 || index >= now.runes().size()) {
                        return;
                    }
                    if (!takeOne(player, rune)) {
                        fail(player, "身上沒有" + rune.name() + "了");
                        return;
                    }
                    final String before = plugin.items().toJson(now);
                    final String old = now.runes().get(index);
                    final boolean refund = !now.corrupted() && currencies.returnRune(player, plugin.economy().wallet(), old);
                    now.replaceRune(index, rune.affix());
                    if (plugin.guide() != null) {
                        plugin.guide().milestone(player, tw.exilecore.guide.GuideService.RUNE); // 2026-10-30 新手教學
                    }
                    plugin.items().update(stack, now);
                    player.getInventory().setItem(slot, stack);
                    final PlayerState state = plugin.players().get(player);
                    currencies.recordUse(state.hasCharacter() ? state.character().id() : null, player.getName(), now.uuid(), rune.id(),
                            before, plugin.items().toJson(now));
                    plugin.players().markDirty(player);
                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#7fe0c8>第 " + (index + 1) + " 孔換成了" + tw.exilecore.item.ItemRenderer.escape(rune.name())
                            + (refund ? "<gray>（換下來的那顆還給你了）" : "")));
                }, () -> { });
    }

    /** 映星鏡點裝備：確認之後多一件鏡像複製品（放進背包的空位）。 */
    private void mirror(final Player player, final InventoryClickEvent event, final CurrencyType mirror, final ItemStack target, final String why) {
        final tw.exilecore.item.ItemData data = plugin.items().read(target).orElse(null);
        if (data == null) {
            return;
        }
        if (why != null || !mirror.canApply(data, plugin.items().generator())) {
            fail(player, mirror.name() + "：" + (why == null ? "不能複製這件" : why));
            return;
        }
        if (event.getClickedInventory() != player.getInventory()) {
            fail(player, "先把裝備拿到自己的背包，再拿" + mirror.name() + "點它");
            return;
        }
        final int slot = event.getSlot();
        final String uuid = data.uuid();
        // 游標上的映星鏡先放回背包（對話框打開時背包會關掉；確定了才從背包、通貨袋扣一顆）
        final ItemStack cursor = event.getView().getCursor();
        event.getView().setCursor(ItemStack.empty());
        if (cursor != null && !cursor.isEmpty()) {
            // 2026-10：放不下的進隨身背包，再不行掉在腳下只有自己撿得到（以前掉成誰都能撿的）
            plugin.economy().wallet().dropFor(player, plugin.economy().wallet().give(player, cursor));
        }
        player.closeInventory();
        final String itemName = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(target.effectiveName());
        plugin.dialogs().confirm(player, "<dark_gray>" + tw.exilecore.item.ItemRenderer.escape(mirror.name()),
                List.of("<gray>要複製這件嗎？", itemName, "",
                        "<gray>會多出一件一模一樣的<#9fd8ff>鏡像複製品</#9fd8ff>，原本那件不變。",
                        "<gray>複製品<white>不能再改造、不能再複製</white>。",
                        "<red>用掉一顆" + tw.exilecore.item.ItemRenderer.escape(mirror.name()) + "（拿不回來）"),
                "複製", () -> {
                    final ItemStack stack = player.getInventory().getItem(slot);
                    final tw.exilecore.item.ItemData now = stack == null ? null : plugin.items().read(stack).orElse(null);
                    if (now == null || !uuid.equals(now.uuid())) {
                        fail(player, "裝備不在原來的格子了，重新拿" + mirror.name() + "點它");
                        return;
                    }
                    if (player.getInventory().firstEmpty() < 0) {
                        fail(player, "背包滿了：先空出一格放複製品");
                        return;
                    }
                    if (!mirror.canApply(now, plugin.items().generator())) {
                        fail(player, mirror.name() + "不能複製這件");
                        return;
                    }
                    if (!takeOne(player, mirror)) {
                        fail(player, "身上沒有" + mirror.name() + "了");
                        return;
                    }
                    final PlayerState state = plugin.players().get(player);
                    final ItemStack copy = currencies.mirror(mirror, stack, player.getName(), state.hasCharacter() ? state.character().id() : null);
                    if (copy == null) {
                        // 不會走到這裡（上面確認過了）；真的做不出來就把那顆還回去
                        // 2026-10：放不下的進隨身背包，再不行掉在腳下只有自己撿得到（以前掉成誰都能撿的）
                        plugin.economy().wallet().dropFor(player, plugin.economy().wallet().give(player, currencies.create(mirror, 1)));
                        fail(player, mirror.name() + "不能複製這件");
                        return;
                    }
                    for (final ItemStack left : player.getInventory().addItem(copy).values()) {
                        tw.exilecore.monster.MonsterListener.dropOwned(plugin, player.getLocation(), left, player, true); // 剛確認過有空位，保險：真的放不下就掉在腳邊、只有他撿得到
                    }
                    plugin.players().markDirty(player);
                    player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.8f);
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
                    player.sendMessage(plugin.text().prefixed("<#9fd8ff>" + tw.exilecore.item.ItemRenderer.escape(mirror.name())
                            + "<gray>照出了一件鏡像複製品：").append(copy.effectiveName()));
                }, () -> { });
    }

    /** 從背包、通貨袋扣一顆這種通貨。 */
    private boolean takeOne(final Player player, final CurrencyType type) {
        final ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            final ItemStack stack = contents[i];
            if (stack != null && !stack.isEmpty() && currencies.read(stack).map(t -> t.id().equals(type.id())).orElse(false)) {
                stack.setAmount(stack.getAmount() - 1);
                player.getInventory().setItem(i, stack.getAmount() <= 0 ? null : stack);
                return true;
            }
        }
        final tw.exilecore.character.CurrencyPouch pouch = plugin.players().get(player).bag().pouch();
        if (pouch.withdraw(type.id(), 1) > 0) {
            plugin.players().get(player).markDirty();
            return true;
        }
        return false;
    }

    private void fail(final Player player, final String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + tw.exilecore.item.ItemRenderer.escape(message)));
    }

    /** 蒸餾情緒點項鍊：打開注入的選單。 */
    private void instil(final Player player, final InventoryClickEvent event, final CurrencyType emotion, final ItemStack target, final String why) {
        if (why != null) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + emotion.name() + "：" + tw.exilecore.item.ItemRenderer.escape(why)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        if (event.getClickedInventory() != player.getInventory()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>先把項鍊拿到自己的背包再注入"));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        final tw.exilecore.passive.PassiveTree tree = plugin.passives().tree();
        if (tree == null) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>天賦樹沒有載入，不能注入"));
            return;
        }
        final String uuid = plugin.items().read(target).map(tw.exilecore.item.ItemData::uuid).orElse(null);
        if (uuid == null) {
            return;
        }
        // 選單打開時游標上的東西會自動放回背包（扣的時候從背包與通貨袋扣）
        new tw.exilecore.passive.InstilMenu(plugin, player, tree, event.getSlot(), uuid, "").open();
    }
}
