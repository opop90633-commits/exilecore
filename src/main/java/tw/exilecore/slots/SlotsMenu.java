package tw.exilecore.slots;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.economy.GoldService;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 拉霸機的選單（2026-11-02 照站長；這次之前沒有）。六排：
 * <ul>
 *   <li>第一排：金幣餘額、押注額（點一下選，寫這個押注中頭獎給哪個稱號）、拉霸稱號一覽（2026-11-02 照站長：每個押注額一個，
 *       列出 5 個和各自有沒有拿到；這次之前只有一個稱號和保底進度）。</li>
 *   <li>第二到四排：三個（或五個）滾輪，中間那一排是中獎線。每 tick 每個還在轉的滾輪往下捲一格，約 1.5 秒從左到右依序停。</li>
 *   <li>第五排：這一把的結果。</li>
 *   <li>最下面一排：本次戰績、賠率表、拉一次、說明、自動連拉（可以停）、加速、返回選單。</li>
 * </ul>
 * 結果在按下去時就決定了（{@link SlotsService#start}），滾輪只是動畫；中途關掉選單馬上結算。
 */
final class SlotsMenu extends Menu {

    private static final int SLOT_BALANCE = 0;
    private static final int SLOT_TITLE = 8;
    private static final int SLOT_RESULT = 40;
    private static final int SLOT_STATS = 45;
    private static final int SLOT_TABLE = 47;
    private static final int SLOT_PULL = 48;
    private static final int SLOT_AUTO = 50;
    /** 2026-11-02 照站長：加速鈕（自動連拉旁邊）；這次之前沒有。 */
    private static final int SLOT_SPEED = 51;
    private static final Material[] BET_ICONS = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK, Material.RAW_GOLD_BLOCK,
            Material.GOLD_BLOCK, Material.GOLD_BLOCK, Material.GOLD_BLOCK};

    private final SlotsService service;
    private final SlotsConfig config;
    private final ItemStack[] symbols;
    private final int[] columns;
    private final int[] betSlots;
    /** 每個滾輪看得到的三格（上、中＝中獎線、下）。 */
    private final int[][] cells;
    private long bet;
    private BukkitTask task;
    private SlotsService.Spin spinning;
    private int tick;
    /** 這一把轉多久（tick；開始時照加速算好，轉到一半換速度不影響這一把）。 */
    private int spinLength;
    private int autoLeft;
    private boolean auto;
    private String result;

    SlotsMenu(final ExileCore plugin, final Player player, final SlotsService service) {
        super(plugin, player, 6, "<dark_gray>拉霸機");
        this.service = service;
        this.config = service.config();
        final List<SlotsConfig.Outcome> outcomes = config.outcomes();
        this.symbols = new ItemStack[outcomes.size()];
        for (int i = 0; i < outcomes.size(); i++) {
            final SlotsConfig.Outcome outcome = outcomes.get(i);
            symbols[i] = icon(material(outcome.symbol(), Material.GOLD_NUGGET), "<white>" + ItemRenderer.escape(outcome.name()), "<gray>" + payText(outcome));
        }
        this.columns = config.reels() == 5 ? new int[] {2, 3, 4, 5, 6} : new int[] {2, 4, 6};
        this.betSlots = tabSlots(config.bets().size());
        this.cells = new int[config.reels()][3];
        for (final int[] reel : cells) {
            for (int row = 0; row < 3; row++) {
                reel[row] = randomSymbol();
            }
        }
        final Long remembered = service.chosenBet(player);
        this.bet = remembered != null && config.bets().contains(remembered) ? remembered : config.bets().getFirst();
    }

    private int randomSymbol() {
        return plugin.random().nextInt(symbols.length);
    }

    // ---- 畫 ----

    @Override
    protected void draw() {
        frame();
        for (int i = CONTENT_START; i < CONTENT_END; i++) {
            set(i, filler());
        }
        drawBalance();
        for (int i = 0; i < betSlots.length; i++) {
            set(betSlots[i], betIcon(i));
        }
        drawTitle();
        final ItemStack line = icon(Material.LIME_STAINED_GLASS_PANE, "<green>中獎線", "<gray>中間這一排全部一樣就中獎");
        set(slot(2, 1), line);
        set(slot(2, 7), line);
        for (int reel = 0; reel < columns.length; reel++) {
            drawReel(reel, false);
        }
        drawResult();
        drawStats();
        set(SLOT_TABLE, table());
        drawPull();
        set(SLOT_HELP, helpButton(List.of(
                "選上面的押注額，按「拉一次」。",
                "中間那一排（中獎線）全部一樣就中獎，賠率看「賠率表」。",
                "結果在按下去的那一刻就決定了，滾輪只是動畫；",
                "中途關掉選單、斷線也照常結算。",
                "",
                "自動連拉：一次拉 " + config.autoSpins() + " 把，中途可以停。")));
        // 2026-11-02 照站長：拿掉冷卻與「記在全服的經濟報表」那幾行；這次之前說明裡有寫
        drawAuto();
        drawSpeed();
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private void drawBalance() {
        set(SLOT_BALANCE, icon(Material.SUNFLOWER, "<" + SlotsService.GOLD_COLOR + ">金幣：" + GoldService.format(plugin.gold().balance(player)),
                "<gray>帳號共用（所有角色同一個餘額）"));
    }

    private ItemStack betIcon(final int index) {
        final long amount = config.bets().get(index);
        final boolean current = amount == bet;
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>一把押 <" + SlotsService.GOLD_COLOR + ">" + GoldService.format(amount) + "</" + SlotsService.GOLD_COLOR + "><gray> 金幣");
        for (final SlotsConfig.Outcome outcome : config.outcomes()) {
            if (outcome.kind() == SlotsConfig.Kind.JACKPOT) {
                lore.add("<gray>頭獎：<" + SlotsService.GOLD_COLOR + ">" + GoldService.format(SlotsConfig.goldPrize(outcome.multiplier(), amount)) + " 金幣");
            }
        }
        final SlotsConfig.Title title = config.titleFor(amount);
        if (title != null) {
            lore.add(service.ownsTitle(player, title)
                    ? "<dark_gray>這個押注的稱號「" + ItemRenderer.escape(title.name()) + "」已擁有（金幣掉落 +" + SlotsService.number(title.goldFindPercent()) + "%）"
                    : "<gray>這個押注中頭獎可得稱號 " + SlotsService.titleTag(title) + "<gray>（金幣掉落 +" + SlotsService.number(title.goldFindPercent()) + "%）");
        }
        lore.add("");
        lore.add(current ? "<green>選了這個" : "<yellow>左鍵：押這個");
        final ItemStack stack = icon(BET_ICONS[Math.min(index, BET_ICONS.length - 1)], (current ? "<" + SlotsService.GOLD_COLOR + ">▶ " : "<white>")
                + "押 " + GoldService.format(amount) + " 金幣", lore);
        return current ? highlight(stack) : stack;
    }

    private void drawTitle() {
        final List<SlotsConfig.Title> titles = config.titles();
        if (titles.isEmpty()) {
            return;
        }
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>用那個押注額中頭獎，就拿到那一個（押更大的不會順便給）：");
        int owned = 0;
        double total = 0;
        for (final SlotsConfig.Title title : titles) {
            final boolean has = service.ownsTitle(player, title);
            if (has) {
                owned++;
                total += title.goldFindPercent();
            }
            lore.add((has ? "<green>✔ " : "<dark_gray>✘ ") + SlotsService.titleTag(title) + "<gray> 押 " + GoldService.format(title.bet())
                    + "　金幣掉落 +" + SlotsService.number(title.goldFindPercent()) + "%" + (has ? "　<green>已擁有" : "　<dark_gray>還沒有"));
        }
        lore.add("");
        lore.add("<gray>擁有就有加成（帳號共用，不用戴），全部疊加");
        lore.add("<gray>已擁有 <white>" + owned + "／" + titles.size() + "</white><gray>，怪物掉落金幣 <" + SlotsService.GOLD_COLOR + ">+"
                + SlotsService.number(total) + "%");
        final ItemStack stack = icon(Material.NAME_TAG, "<white>拉霸限定稱號", lore);
        set(SLOT_TITLE, owned == titles.size() ? glowing(stack) : stack);
    }

    /** 一個滾輪的三格；win = 停下來而且中了（中獎線發光）。 */
    private void drawReel(final int reel, final boolean win) {
        for (int row = 0; row < 3; row++) {
            final ItemStack stack = symbols[Math.floorMod(cells[reel][row], symbols.length)].clone(); // /ec reload 改了圖案數也不會出錯
            set(slot(row + 1, columns[reel]), win && row == 1 ? glowing(stack) : stack);
        }
    }

    private void drawResult() {
        if (spinning != null) {
            set(SLOT_RESULT, icon(Material.CLOCK, "<yellow>轉動中…", "<gray>押 " + GoldService.format(spinning.bet) + " 金幣"));
        } else if (result != null) {
            set(SLOT_RESULT, icon(result.startsWith("<gray>沒中") ? Material.GRAY_DYE : Material.GOLD_INGOT, "<white>這一把：" + result));
        } else {
            set(SLOT_RESULT, icon(Material.GRAY_DYE, "<gray>還沒拉", "<gray>選押注額，按「拉一次」"));
        }
    }

    private void drawPull() {
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>押 <" + SlotsService.GOLD_COLOR + ">" + GoldService.format(bet) + "</" + SlotsService.GOLD_COLOR + "><gray> 金幣");
        lore.add("");
        lore.add(spinning != null ? "<dark_gray>轉動中…" : auto ? "<dark_gray>自動連拉中" : "<yellow>左鍵：拉一次");
        set(SLOT_PULL, icon(Material.LEVER, "<" + SlotsService.GOLD_COLOR + ">拉一次", lore));
    }

    private void drawAuto() {
        if (auto) {
            set(SLOT_AUTO, icon(Material.BARRIER, "<red>停止自動連拉", "<gray>還剩 " + autoLeft + " 把", "", "<yellow>左鍵：停下來（這一把照常結算）"));
            return;
        }
        final long wait = service.autoWaitSeconds(player);
        set(SLOT_AUTO, icon(Material.REPEATER, "<white>自動連拉 " + config.autoSpins() + " 把",
                "<gray>每把押 " + GoldService.format(bet) + " 金幣（最多 " + GoldService.format(bet * config.autoSpins()) + "）",
                "<gray>金幣不夠、關掉選單就停", "",
                wait > 0 ? "<dark_gray>冷卻中：" + wait + " 秒後可以再開" : "<yellow>左鍵：開始"));
    }

    /** 加速鈕：現在幾倍、按了換下一段。 */
    private void drawSpeed() {
        final int speed = service.speed(player);
        set(SLOT_SPEED, icon(speed == 1 ? Material.CLOCK : Material.SUGAR, "<white>轉速：" + (speed == 1 ? "一般" : "<yellow>" + speed + " 倍"),
                "<gray>滾輪轉 " + String.format(Locale.ROOT, "%.1f", spinTicks() / 20.0) + " 秒" + (speed > 1 ? "，自動連拉每把之間也縮短" : ""),
                "<gray>結果在按下去時就決定了，轉快轉慢機率都一樣", "",
                "<yellow>左鍵：換成 " + (speed == 1 ? "2 倍" : speed == 2 ? "4 倍" : "一般")));
    }

    /** 這一把轉多久（tick）：設定的 spin_ticks ÷ 加速倍數，至少 4（每個滾輪還看得到停下來）。 */
    private int spinTicks() {
        return Math.max(4, config.spinTicks() / service.speed(player));
    }

    private void drawStats() {
        final SlotsService.Session session = service.session(player);
        final long net = session.returned - session.wagered;
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>拉了 <white>" + session.spins + "</white> 把，中了 <white>" + session.wins + "</white> 把");
        lore.add("<gray>押了 <" + SlotsService.GOLD_COLOR + ">" + GoldService.format(session.wagered) + "</" + SlotsService.GOLD_COLOR + "><gray> 金幣");
        lore.add("<gray>拿回 <" + SlotsService.GOLD_COLOR + ">" + GoldService.format(session.returned) + "</" + SlotsService.GOLD_COLOR + "><gray> 金幣");
        lore.add("<gray>輸贏：" + (net >= 0 ? "<green>+" : "<red>") + GoldService.format(net));
        lore.add("<gray>最大一把：<white>" + GoldService.format(session.best));
        if (session.jackpots > 0) {
            lore.add("<gradient:#ffe066:#ff9f1c>頭獎 " + session.jackpots + " 次");
        }
        lore.add("<dark_gray>這次登入的紀錄（下線就清掉）");
        set(SLOT_STATS, icon(Material.WRITABLE_BOOK, "<white>本次戰績", lore));
    }

    /** 賠率表：每一種結果的獎與機率、期望回報率。 */
    private ItemStack table() {
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>中間那一排 " + config.reels() + " 個一樣：");
        for (final SlotsConfig.Outcome outcome : config.outcomes()) {
            final double chance = config.chance(outcome);
            lore.add("<white>" + ItemRenderer.escape(outcome.name()) + " ×" + config.reels() + " <gray>→ " + payText(outcome)
                    + "　<dark_gray>" + percent(chance) + (chance > 0 ? "（約 1/" + GoldService.format(Math.round(1 / chance)) + "）" : ""));
        }
        lore.add("<dark_gray>沒中 " + percent((double) config.loseWeight() / Math.max(1, config.totalWeight())));
        lore.add("");
        // 2026-11-02 照站長：賠率表不顯示期望回報率；這次之前最後一行寫「期望回報率：85.5%」
        if (!config.cosmetics().isEmpty()) {
            final List<String> names = new ArrayList<>();
            for (final String id : config.cosmetics()) {
                names.add(ItemRenderer.escape(service.cosmeticName(id)));
            }
            lore.add("<gray>限定時裝：<#ff9ad5>" + String.join("、", names));
        }
        if (!config.titles().isEmpty()) {
            lore.add("<gray>限定稱號：每個押注額一個，用那個押注額中頭獎才拿到（右上角看）");
        }
        return icon(Material.MAP, "<white>賠率表", lore);
    }

    private static String payText(final SlotsConfig.Outcome outcome) {
        final String times = SlotsService.number(outcome.multiplier());
        return switch (outcome.kind()) {
            case GOLD -> "金幣 ×" + times;
            case CURRENCY -> "通貨（約 ×" + times + " 的價值）";
            case COSMETIC -> "拉霸限定時裝（都有了改給金幣 ×" + times + "）";
            case JACKPOT -> "頭獎：金幣 ×" + times + (outcome.bonus() == null ? "" : "＋高價通貨（約 ×" + SlotsService.number(outcome.bonus().multiplier()) + "）");
        };
    }

    private static String percent(final double chance) {
        return chance >= 0.01 ? String.format(Locale.ROOT, "%.1f%%", chance * 100) : String.format(Locale.ROOT, "%.2f%%", chance * 100);
    }

    // ---- 點擊 ----

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        for (int i = 0; i < betSlots.length; i++) {
            if (betSlots[i] == slot) {
                if (spinning != null || auto) {
                    player.sendMessage(plugin.text().prefixed("<red>轉動中、自動連拉中不能換押注額"));
                    return;
                }
                bet = config.bets().get(i);
                service.choose(player, bet);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
                for (int j = 0; j < betSlots.length; j++) {
                    set(betSlots[j], betIcon(j));
                }
                drawPull();
                drawAuto();
                return;
            }
        }
        if (slot == SLOT_SPEED) {
            if (acceptAction(click)) {
                service.cycleSpeed(player); // 轉動中也可以換：下一把起生效
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.6f);
                drawSpeed();
            }
            return;
        }
                if (slot == SLOT_PULL) {
            if (acceptAction(click) && spinning == null && !auto) {
                pull();
            }
            return;
        }
        if (slot == SLOT_AUTO) {
            if (!acceptAction(click)) {
                return;
            }
            if (auto) {
                autoLeft = 0; // 這一把轉完就停
                if (spinning == null) {
                    endAuto();
                }
                drawAuto();
                return;
            }
            if (spinning != null) {
                return;
            }
            final long wait = service.autoWaitSeconds(player);
            if (wait > 0) {
                player.sendMessage(plugin.text().prefixed("<red>自動連拉冷卻中，" + wait + " 秒後可以再開"));
                return;
            }
            auto = true;
            autoLeft = config.autoSpins();
            drawAuto();
            pull();
        }
    }

    /** 拉一把：服務扣錢抽好結果，選單開始轉。 */
    private void pull() {
        final SlotsService.Started started = service.start(player, bet);
        if (started.problem() != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(started.problem())));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            if (auto) {
                endAuto();
            }
            return;
        }
        if (auto) {
            autoLeft--;
        }
        spinning = started.spin();
        tick = 0;
        spinLength = spinTicks();
        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.8f, 1.0f);
        drawBalance();
        drawResult();
        drawPull();
        drawAuto();
        drawStats();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::animate, 1L, 1L);
    }

    /** 動畫的一格：還在轉的滾輪往下捲一格，到時間的停在結果上。 */
    private void animate() {
        if (spinning == null || !player.isConnected() || player.getOpenInventory().getTopInventory().getHolder() != this) {
            stop();
            return;
        }
        tick++;
        final int reels = cells.length;
        for (int reel = 0; reel < reels; reel++) {
            final int stopAt = SlotsConfig.stopTick(reel, reels, spinLength);
            if (tick < stopAt) {
                cells[reel][2] = cells[reel][1];
                cells[reel][1] = cells[reel][0];
                cells[reel][0] = randomSymbol();
                drawReel(reel, false);
            } else if (tick == stopAt) {
                cells[reel][0] = randomSymbol();
                cells[reel][1] = reel < spinning.line.length ? spinning.line[reel] : randomSymbol();
                cells[reel][2] = randomSymbol();
                drawReel(reel, false);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.8f, 0.8f + reel * 0.15f);
            }
        }
        if (tick % 2 == 0 && tick < spinLength) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.35f, 1.2f + (tick % 6) * 0.1f);
        }
        if (tick >= spinLength) {
            finish();
        }
    }

    /** 轉完：結算、顯示結果、自動連拉的下一把。 */
    private void finish() {
        cancelTask();
        final SlotsService.Spin spin = spinning;
        spinning = null;
        spin.shown = true;
        service.settle(player);
        result = service.prizeText(spin);
        for (int reel = 0; reel < cells.length; reel++) {
            drawReel(reel, spin.win());
        }
        if (!spin.win()) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
        } else if (spin.outcome.kind() == SlotsConfig.Kind.JACKPOT) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.0f);
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1f, 1.0f);
        } else if (spin.outcome.value() >= 10 || spin.outcome.kind() != SlotsConfig.Kind.GOLD) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
        }
        if (spin.win() && (spin.outcome.kind() != SlotsConfig.Kind.GOLD || spin.outcome.multiplier() >= 10)) {
            player.sendMessage(plugin.text().prefixed("<" + SlotsService.GOLD_COLOR + ">🎰 拉霸機：" + result));
        }
        drawBalance();
        drawResult();
        drawStats();
        drawTitle();
        if (spin.title != null) {
            for (int i = 0; i < betSlots.length; i++) {
                set(betSlots[i], betIcon(i)); // 拿到稱號了：押注額的說明改成已擁有
            }
        }
        if (auto && autoLeft > 0) {
            drawPull();
            drawAuto();
            task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                task = null;
                if (auto && player.isConnected() && player.getOpenInventory().getTopInventory().getHolder() == this) {
                    pull();
                }
            }, Math.max(1, config.autoGapTicks() / service.speed(player)));
            return;
        }
        if (auto) {
            endAuto();
        }
        drawPull();
        drawAuto();
    }

    private void endAuto() {
        if (auto) {
            auto = false;
            autoLeft = 0;
            service.autoEnded(player);
        }
        drawPull();
        drawAuto();
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 動畫停掉（選單關了、下線）：還在轉的那一把馬上結算。 */
    private void stop() {
        cancelTask();
        if (spinning != null) {
            spinning = null;
            service.settle(player);
        }
        if (auto) {
            auto = false;
            autoLeft = 0;
            service.autoEnded(player);
        }
    }

    @Override
    protected void onClose() {
        stop();
    }
}
