package tw.exilecore.sponsor;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.CommunityLinks;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static tw.exilecore.sponsor.SponsorService.CARD_COLOR;
import static tw.exilecore.sponsor.SponsorService.COIN;
import static tw.exilecore.sponsor.SponsorService.FASHION_COLOR;
import static tw.exilecore.sponsor.SponsorService.format;

/** 贊助的選單：主選單、累積贊助一覽、外觀特效、抽獎箱、月卡幣兌換。 */
final class SponsorMenus {

    private SponsorMenus() {
    }

    static void open(final ExileCore plugin, final Player player, final SponsorService service) {
        new Main(plugin, player, service).open();
    }

    /** 共用：花錢的按鈕點兩下才算（第一下變「再點一次確認」，5 秒內有效）。 */
    abstract static class Base extends Menu {
        final SponsorService service;
        private int confirmKey = -1;
        private long confirmUntil;

        Base(final ExileCore plugin, final Player player, final SponsorService service, final int rows, final String title) {
            super(plugin, player, rows, title);
            this.service = service;
        }

        /** 這個按鈕是不是第二下（是就做）；第一下回傳 false 並重畫。 */
        boolean confirm(final int key) {
            final long now = System.currentTimeMillis();
            if (confirmKey == key && now < confirmUntil) {
                confirmKey = -1;
                return true;
            }
            confirmKey = key;
            confirmUntil = now + 5000;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
            redraw();
            return false;
        }

        boolean pending(final int key) {
            return confirmKey == key && System.currentTimeMillis() < confirmUntil;
        }

        /** 等著確認的按鈕：說明最後加一行、發光。 */
        ItemStack confirmable(final int key, final ItemStack stack) {
            if (!pending(key)) {
                return stack;
            }
            appendLore(stack, "", "<#ffd27f>▶ 再點一次確認（5 秒內）");
            return glowing(stack);
        }

        void result(final String problem) {
            confirmKey = -1;
            if (problem != null) {
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            }
            redraw();
        }

        String balances() {
            return COIN + "星幣 " + format(service.coins(player)) + "</#f5b942><gray>・" + CARD_COLOR + "月卡幣 " + service.cardCoins(player)
                    + "</#bcd4ff><gray>・" + FASHION_COLOR + "時裝幣 " + service.fashionCoins(player) + "</#ff9ad5>";
        }

        void back() {
            new Main(plugin, player, service).open();
        }

        ItemStack buyIcon(final Material material, final String name, final long bought, final int max, final long price,
                                  final String about, final String now) {
            final boolean full = SponsorConfig.atMax(bought, max);
            return icon(material, "<white>" + name, about, now, "<gray>用星幣買了 " + bought + (max > 0 ? "／" + max : "（沒有上限）"),
                    full ? "<dark_gray>買到上限了" : "<gray>價格：" + COIN + format(price) + " 星幣", "",
                    full ? "" : "<yellow>左鍵：購買");
        }
    }

    // ---- 主選單 ----

    static final class Main extends Base {
        private static final int HEADER = 4;
        /** 星幣商城（2026-10：角色欄位、倉庫頁、背包頁、上架格、通貨袋、地圖頁、倉庫通貨頁、改名卡、全服加倍、時裝抽獎箱收進一個分類）。 */
        private static final int SLOT_EXPAND = 10;
        private static final int SLOT_CARD = 15;
        private static final int SLOT_TIERS = 16;
        private static final int SLOT_FLY = 24;
        /** 名字顏色（2026-10：隨時換、可以漸層）。 */
        private static final int SLOT_NAME_COLOR = 23;
        private static final int SLOT_TITLE = 25;
        private static final int SLOT_COSMETICS = 29;
        private static final int SLOT_TRANSMOG = 30;
        /** 動作（2026-10）。 */
        private static final int SLOT_EMOTES = 32;
        /** 星幣券（2026-10）。 */
        private static final int SLOT_VOUCHER = 39;

        private static final int SLOT_CARD_SHOP = 33;
        /** 月卡免費抽獎箱（2026-10：放在月卡旁邊，顯眼一點；沒開的累積）。 */
        private static final int SLOT_FREE_BOX = 14;
        private static final int SLOT_STORE = 45;
        private static final int SLOT_CLOSE = 53;


        /**
         * 套了介面資源包的人看「圖示＋中文」版面（2026-10，跟主選單一樣）：圖示在第 1、3、5 排，下面一排寫名稱
         * （web/menu/sponsor_menu.png，tools/menu_labels.py sponsor 產生；位置改了要重跑）。按鈕代號照舊，這張表換成版面的格子。
         */
        private static final Map<Integer, Integer> LABELLED = Map.ofEntries(
                Map.entry(HEADER, 0), Map.entry(SLOT_CARD, 1), Map.entry(SLOT_CARD_SHOP, 2), Map.entry(SLOT_TIERS, 3),
                Map.entry(SLOT_STORE, 4), Map.entry(SLOT_FREE_BOX, 5), Map.entry(SLOT_NAME_COLOR, 6), Map.entry(SLOT_TITLE, 7), Map.entry(SLOT_FLY, 8),
                Map.entry(SLOT_EXPAND, 18), Map.entry(SLOT_VOUCHER, 19),
                Map.entry(SLOT_COSMETICS, 36), Map.entry(SLOT_TRANSMOG, 37), Map.entry(SLOT_EMOTES, 38));
        private static final Map<Integer, Integer> LABELLED_BACK = invert(LABELLED);

        private final boolean labelled;

        Main(final ExileCore plugin, final Player player, final SponsorService service) {
            super(plugin, player, service, 6, labelled(plugin, player)
                    ? "<white><font:" + tw.exilecore.web.UiPack.MENU_FONT + ">" + tw.exilecore.web.UiPack.MENU_SHIFT_START
                    + tw.exilecore.web.UiPack.MENU_SPONSOR + tw.exilecore.web.UiPack.MENU_SHIFT_BACK + "</font></white><dark_gray>贊助"
                    : "<dark_gray>贊助");
            this.labelled = labelled(plugin, player);
        }

        private static boolean labelled(final ExileCore plugin, final Player player) {
            return plugin.web() != null && plugin.web().hasPack(player)
                    && SponsorMenus.class.getResource("/web/menu/sponsor_menu.png") != null;
        }

        private static Map<Integer, Integer> invert(final Map<Integer, Integer> map) {
            final Map<Integer, Integer> back = new HashMap<>();
            for (final Map.Entry<Integer, Integer> entry : map.entrySet()) {
                back.put(entry.getValue(), entry.getKey());
            }
            return Map.copyOf(back);
        }

        /** 「圖示＋中文」版面：按鈕代號換成版面的格子（關閉鍵不放，Esc 就關了）。 */
        @Override
        protected void set(final int slot, final ItemStack stack) {
            if (!labelled) {
                super.set(slot, stack);
            } else if (LABELLED.containsKey(slot)) {
                super.set(LABELLED.get(slot), stack);
            }
        }

        @Override
        protected void draw() {
            if (labelled) {
                for (int i = 0; i < 54; i++) {
                    super.set(i, null); // 名稱那幾排要空著，背景的中文才看得到
                }
            } else {
                for (int i = 0; i < 54; i++) {
                    set(i, filler());
                }
            }
            final SponsorConfig config = service.config();
            if (!service.loaded(player)) {
                set(22, icon(Material.CLOCK, "<gray>帳號資料載入中…", "<dark_gray>等一下再打開"));
                set(SLOT_CLOSE, closeButton());
                return;
            }
            final long total = service.total(player);
            final SponsorConfig.Tier tier = config.tierFor(total);
            final SponsorConfig.Tier next = config.nextTier(total);
            final List<String> header = new ArrayList<>();
            header.add(balances());
            header.add("<gray>累積贊助 <white>" + format(total) + " 星幣<gray>：" + (tier == null ? "還沒有階級"
                    : "<" + tier.color() + ">【" + ItemRenderer.escape(tier.title()) + "】"));
            if (next != null) {
                header.add("<gray>下一階 <" + next.color() + ">【" + ItemRenderer.escape(next.title()) + "】<gray>還差 <white>" + format(next.amount() - total));
            }
            final String card = service.cardText(player);
            header.add(card == null ? "<gray>月卡：沒有" : "<gray>" + card);
            header.add("<gray>登入天數 <white>" + service.loginDays(player) + "<gray> 天（今天在線 " + service.minutesToday(player) + "／"
                    + config.loginMinutes() + " 分鐘）");
            header.add("");
            header.add("<dark_gray>1 星幣 = 1 元；贊助的東西都不影響戰鬥");
            set(HEADER, glowing(icon(Material.NETHER_STAR, "<#f5b942>贊助", header)));

            set(SLOT_EXPAND, icon(Material.CHEST, COIN + "星幣商城",
                    "<gray>角色欄位、倉庫頁、隨身背包頁、上架格、改名卡",
                    "<gray>通貨袋、倉庫通貨頁、地圖頁加大",
                    "<gray>全服加倍、時裝抽獎箱",
                    "", "<yellow>左鍵：打開"));
            set(SLOT_VOUCHER, StarVouchers.withArt(icon(Material.PAPER, COIN + "星幣券",
                    "<gray>把星幣換成一張券（物品）：可以交易、上架給別人",
                    "<gray>拿到的人右鍵就換回星幣（存進帳號）",
                    "<gray>" + StarVouchers.rules() + "；每張只能兌換一次（複製的券換不了）",
                    "<gray>你有 " + COIN + format(service.coins(player)) + " 星幣", "", "<yellow>左鍵：開券（輸入數量）")));
            final long left = service.cardDaysLeft(player);
            set(SLOT_CARD, confirmable(SLOT_CARD, icon(Material.CLOCK, CARD_COLOR + "月卡（" + config.cardDays() + " 天）",
                    "<gray>每天換日自動發 " + CARD_COLOR + config.dailyCardCoins() + " 月卡幣</#bcd4ff><gray>、" + FASHION_COLOR
                            + config.dailyFashionCoins() + " 時裝幣</#ff9ad5><gray> 到倉庫的贊助頁",
                    "<gray>沒上線的日子下次上線補發；買了今天就先發",
                    "",
                    CARD_COLOR + "月卡福利：",
                    "<gray>・開卡禮：" + FASHION_COLOR + config.welcomeFashionCoins() + " 時裝幣</#ff9ad5><gray>＋" + CARD_COLOR
                            + config.welcomeCardCoins() + " 月卡幣</#bcd4ff>",
                    "<gray>・連續續卡：第 2 個月多送 " + config.renewalBonus(2) + "、第 3 個月起多送 " + config.renewalBonus(3) + " 月卡幣",
                    "<gray>・每天送一箱免費的時裝抽獎箱（沒開的會累積）",
                    "<gray>・名字旁的月亮 " + CARD_COLOR + "☾",
                    "<gray>・傳送點免費",
                    "<gray>・市集上架、改價、購買、交易所掛單、面對面交易免手續費",
                    "<gray>・裝備、天賦（含珠寶）、技能各 5 組隨時切換",
                    "<gray>・隨時更換昇華職業；天賦、昇華天賦退點免費",
                    "",
                    left > 0 ? "<green>月卡剩 " + left + " 天<gray>（到 " + LocalDate.ofEpochDay(service.today() + left - 1) + "；再買接著延長）"
                            : "<gray>現在沒有月卡",
                    "<gray>價格：" + COIN + format(config.cardPrice()) + " 星幣", "", "<yellow>左鍵：購買")));
            final SponsorConfig.Box freeBox = config.boxes().get(config.freeBox());
            if (freeBox != null) {
                final long free = service.freeBoxes(player);
                final List<String> freeLore = new ArrayList<>();
                freeLore.add("<gray>月卡每天送一箱「" + ItemRenderer.escape(freeBox.name()) + "」，沒開的會累積到之後");
                freeLore.add(free > 0 ? CARD_COLOR + "還有 " + free + " 箱可以免費開" : left > 0 ? "<dark_gray>開完了，明天再送一箱" : "<dark_gray>你沒有月卡");
                freeLore.add("");
                freeLore.add(free > 0 ? "<yellow>左鍵：免費開一箱" : left > 0 ? "<dark_gray>明天換日再來" : "<yellow>左鍵：看月卡");
                final ItemStack freeIcon = icon(Material.ENDER_CHEST, CARD_COLOR + "月卡免費抽獎箱" + (free > 0 ? "<white>（" + free + "）" : ""), freeLore);
                if (free > 0) {
                    freeIcon.setAmount((int) Math.min(64, free));
                }
                set(SLOT_FREE_BOX, free > 0 ? glowing(freeIcon) : freeIcon);
            }
            set(SLOT_TIERS, icon(Material.BEACON, "<#ffd27f>累積贊助的稱號與功能",
                    "<gray>累積到 1,000、3,000…星幣：稱號、名字顏色、",
                    "<gray>倉庫頁、隨身背包頁、上架格、滿人也能進、聊天上色、自訂稱號、營地飛行",
                    "", "<yellow>左鍵：看每一階"));

            if (tier != null && tier.campFly()) {
                final boolean on = service.flyEnabled(player);
                set(SLOT_FLY, icon(Material.FEATHER, "<white>營地飛行：" + (on ? "<green>開" : "<red>關"), "<gray>在營地按兩下空白鍵飛",
                        "", "<yellow>左鍵：" + (on ? "關掉" : "打開")));
            } else {
                final SponsorConfig.Tier need = service.firstTier(SponsorConfig.Tier::campFly);
                set(SLOT_FLY, icon(Material.FEATHER, "<gray>營地飛行", need == null ? "<dark_gray>沒有開放"
                        : "<dark_gray>累積贊助 " + format(need.amount()) + " 星幣開放"));
            }
            // 名字顏色（2026-10）：累積到的那幾階的顏色隨時換
            final List<SponsorConfig.Tier> colorTiers = service.colorTiers(player);
            final List<String> colorLore = new ArrayList<>();
            colorLore.add("<gray>累積贊助到的每一階的名字顏色都可以換著用");
            final SponsorConfig.Tier chosenTier = service.chosenColorTier(player);
            for (int i = 0; i < colorTiers.size(); i++) {
                final SponsorConfig.Tier each = colorTiers.get(i);
                final boolean using = chosenTier == null ? i == colorTiers.size() - 1 : chosenTier == each;
                final String style = each.nameColor();
                final String close = style.startsWith("gradient:") ? "gradient" : style;
                colorLore.add((using ? "<#f5b942>▶ " : "<gray>　") + "<" + style + ">" + ItemRenderer.escape(service.displayName(player)) + "</" + close + ">"
                        + " <dark_gray>（" + ItemRenderer.escape(each.title()) + "）");
            }
            if (colorTiers.isEmpty()) {
                colorLore.add("<dark_gray>累積贊助後開放");
            } else {
                colorLore.add("");
                colorLore.add("<yellow>左鍵：下一個　右鍵：上一個");
            }
            set(SLOT_NAME_COLOR, icon(Material.PINK_DYE, "<white>名字顏色", colorLore));
            final String[] custom = service.customTitle(player);
            final SponsorConfig.Tier titleTier = service.firstTier(SponsorConfig.Tier::customTitle);
            set(SLOT_TITLE, icon(Material.NAME_TAG, "<white>自訂稱號",
                    custom == null ? "<gray>還沒設定" : "<gray>現在：<" + custom[1] + ">【" + ItemRenderer.escape(custom[2]) + "】",
                    "<gray>/sponsor title 文字 [#顏色]（最多 " + SponsorService.CUSTOM_TITLE_MAX + " 個字）", "<gray>　漸層：後面寫好幾個顏色 #ff5555 #ffd24a #55ffff；彩虹：rainbow",
                    "<gray>/sponsor title clear 拿掉",
                    "<gray>可以<white>無限次</white>更改，不用花錢",
                    tier != null && tier.customTitle() ? "<green>可以用" : "<dark_gray>累積贊助 "
                            + (titleTier == null ? "—" : format(titleTier.amount())) + " 星幣開放",
                    "", "<yellow>左鍵：複製指令"));

            final SponsorConfig.Cosmetic worn = service.worn(player);
            set(SLOT_COSMETICS, icon(Material.BLAZE_POWDER, FASHION_COLOR + "3D 時裝",
                    "<gray>立體的武器、副手、頭飾、翅膀、寵物（只有好看）",
                    "<gray>看全部、用時裝幣兌換；擁有的去「裝備幻化」套上", "", "<yellow>左鍵：打開"));
            set(SLOT_TRANSMOG, icon(Material.NETHERITE_CHESTPLATE, FASHION_COLOR + "裝備幻化",
                    "<gray>套上 3D 時裝：武器、副手、頭飾、背飾、寵物（別人也看得到）", "<gray>只改外觀，數值不變；換裝備也會留著",
                    "<gray>幻化外觀：時裝幣兌換、抽獎箱抽", "", "<yellow>左鍵：打開"));
            set(SLOT_EMOTES, icon(Material.FEATHER, FASHION_COLOR + "動作",
                    "<gray>閃身步、浪子踢球、狗熊哆嗦毛…", "<gray>綁到 F、Q 或 Shift＋Q，按了就做（別人也看得到）",
                    "<gray>時裝幣兌換", "", "<yellow>左鍵：打開"));
            set(SLOT_CARD_SHOP, icon(Material.AMETHYST_CLUSTER, CARD_COLOR + "月卡幣兌換", "<gray>月卡稱號、限定特效、時裝幣、角色欄位",
                    "<gray>你有 " + CARD_COLOR + service.cardCoins(player) + " 月卡幣", "", "<yellow>左鍵：打開"));

            set(SLOT_STORE, icon(Material.GOLD_NUGGET, COIN + "儲值星幣（贊助商城）", "<gray>1 星幣 = 1 元",
                    CommunityLinks.url(plugin, CommunityLinks.Link.STORE).isEmpty() ? "<dark_gray>商城即將開放（先找管理員）" : "<yellow>左鍵：在聊天欄送出連結"));
            set(SLOT_HELP, helpButton(List.of(
                    "星幣：贊助商城儲值（1 星幣 = 1 元），或管理員發",
                    "花錢的按鈕要點兩下確認",
                    "月卡幣、時裝幣放在倉庫的贊助頁（倉庫的全部頁籤 → 贊助）",
                    "累積贊助（花掉也算）到一定的量開啟稱號、名字顏色與功能",
                    "登入天數：一天在線 " + config.loginMinutes() + " 分鐘以上算一天，滿 7、14、21、30…天有稱號",
                    "這裡賣的都不影響戰鬥（全服加倍是全服一起吃的）")));
            set(SLOT_CLOSE, closeButton());
        }


        private static String boostText(final SponsorConfig.BoostType type) {
            final List<String> parts = new ArrayList<>();
            if (type.experience() > 1) {
                parts.add("經驗 ×" + trim(type.experience()));
            }
            if (type.quantity() > 0) {
                parts.add("掉落數量 +" + Math.round(type.quantity() * 100) + "%");
            }
            if (type.rarity() > 0) {
                parts.add("稀有度 +" + Math.round(type.rarity() * 100) + "%");
            }
            if (type.gold() > 1) {
                parts.add("金幣 ×" + trim(type.gold()));
            }
            return "<white>" + String.join("、", parts);
        }

        private static String trim(final double value) {
            return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }

        @Override
        protected void onClick(final int clicked, final ClickType click, final InventoryClickEvent event) {
            final int slot = labelled ? LABELLED_BACK.getOrDefault(clicked, -1) : clicked;
            if (slot == SLOT_CLOSE) {
                goBack();
                return;
            }
            if (!acceptAction(click) || !service.loaded(player)) {
                return;
            }
            switch (slot) {
                case SLOT_CARD -> {
                    if (confirm(slot)) {
                        result(service.buyCard(player));
                    }
                }
                case SLOT_TIERS -> new Tiers(plugin, player, service).open();
                case SLOT_FREE_BOX -> {
                    if (service.freeBoxReady(player)) {
                        result(service.openBox(player, service.config().freeBox(), false, true)); // 不花錢：不用確認
                    } else if (service.cardDaysLeft(player) <= 0) {
                        player.sendMessage(plugin.text().prefixed(CARD_COLOR + "月卡<gray>每天送一箱免費的時裝抽獎箱，沒開的會累積（左上的月卡可以買）"));
                    }
                }
                case SLOT_EXPAND -> new Expand(plugin, player, service).open();
                case SLOT_VOUCHER -> {
                    if (service.vouchers == null) {
                        return;
                    }
                    plugin.dialogs().form(player, "<dark_gray>開星幣券",
                            List.of("<gray>要把多少星幣換成一張券？（" + StarVouchers.rules() + "）",
                                    "<gray>你有 " + format(service.coins(player)) + " 星幣"),
                            List.of(new tw.exilecore.gui.Dialogs.Text("amount", "星幣", "100", 7)), "開券", answers -> {
                                long amount;
                                try {
                                    amount = Long.parseLong(answers.get("amount").trim());
                                } catch (final NumberFormatException e) {
                                    amount = -1;
                                }
                                final String problem = service.vouchers.issue(player, amount);
                                if (problem != null) {
                                    player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                                }
                                open();
                            }, this::open);
                }
                case SLOT_NAME_COLOR -> {
                    if (!service.colorTiers(player).isEmpty()) {
                        result(service.cycleNameColor(player, click.isRightClick() ? -1 : 1));
                    }
                }
                case SLOT_TITLE -> {
                    // 2026-10：按了關掉選單，聊天欄給一串可以點的指令（伺服器不能直接寫玩家的剪貼簿，要玩家點一下）
                    final SponsorConfig.Tier tier = service.tier(player);
                    if (tier == null || !tier.customTitle()) {
                        final SponsorConfig.Tier need = service.firstTier(SponsorConfig.Tier::customTitle);
                        player.sendMessage(plugin.text().prefixed("<gray>自訂稱號要累積贊助 "
                                + (need == null ? "（沒有開放）" : format(need.amount()) + " 星幣")));
                        return;
                    }
                    player.closeInventory();
                    player.sendMessage(plugin.text().prefixed("<gray>自訂稱號指令："
                            + "<click:copy_to_clipboard:'/sponsor title '><hover:show_text:'<gray>複製 /sponsor title 到剪貼簿'>"
                            + "<#77ccff><u>[點我複製]</u></hover></click>　"
                            + "<click:suggest_command:'/sponsor title '><hover:show_text:'<gray>直接填進聊天框'>"
                            + "<#77ccff><u>[點我填入聊天框]</u></hover></click>"));
                    player.sendMessage(plugin.text().prefixed("<gray>寫法：/sponsor title 文字 [#顏色]（最多 " + SponsorService.CUSTOM_TITLE_MAX
                            + " 個字；漸層寫好幾個顏色、彩虹寫 rainbow；無限次更改）"));
                }
                case SLOT_FLY -> {
                    final SponsorConfig.Tier tier = service.tier(player);
                    if (tier != null && tier.campFly()) {
                        service.toggleFly(player);
                        result(null);
                    }
                }
                case SLOT_COSMETICS -> new Cosmetics(plugin, player, service).open();
                case SLOT_TRANSMOG -> new Transmog(plugin, player, service).open();
                case SLOT_EMOTES -> new EmoteMenu(plugin, player, service).open();
                case SLOT_CARD_SHOP -> new CardShop(plugin, player, service).open();
                case SLOT_STORE -> {
                    player.closeInventory();
                    CommunityLinks.send(plugin, player, CommunityLinks.Link.STORE);
                }
                default -> {
                }
            }
        }
    }

    // ---- 擴充（2026-10）----

    static final class Expand extends Base {
        private static final int SLOT_CHAR = 10;
        private static final int SLOT_STASH = 11;
        private static final int SLOT_BAG = 12;
        private static final int SLOT_POUCH = 13;
        private static final int SLOT_STASH_CURRENCY = 14;
        private static final int SLOT_MAPS = 15;
        private static final int SLOT_LISTING = 16;
        private static final int SLOT_RENAME = 19;
        private static final int SLOT_BOXES = 20;
        private static final int SLOT_GUILD_STASH = 22;
        private static final int SLOT_GUILD_RENAME = 23;
        private static final int[] SLOT_BOOSTS = {28, 29, 30, 31};
        /** 2026-10 個人加倍藥。 */
        private static final int[] SLOT_PERSONAL = {32, 33, 34, 35};
        private final Map<Integer, String> personalSlots = new HashMap<>();
        private static final int SLOT_BACK = 36;
        private static final int SLOT_CLOSE = 44;
        private final Map<Integer, String> boostSlots = new HashMap<>();

        Expand(final ExileCore plugin, final Player player, final SponsorService service) {
            super(plugin, player, service, 5, "<dark_gray>星幣商城");
        }

        @Override
        protected void draw() {
            for (int i = 0; i < 45; i++) {
                set(i, filler());
            }
            if (!service.loaded(player)) {
                set(22, icon(Material.CLOCK, "<gray>帳號資料載入中…"));
                set(SLOT_CLOSE, closeButton());
                return;
            }
            final SponsorConfig config = service.config();
            set(4, icon(Material.CHEST, COIN + "星幣商城", balances(), "<dark_gray>花錢的按鈕要點兩下確認"));
            set(SLOT_RENAME, confirmable(SLOT_RENAME, icon(Material.NAME_TAG, "<white>改名卡",
                    "<gray>一張改一次角色名字（/char rename 新名字）",
                    "<gray>你有 <white>" + service.renameCards(player) + "<gray> 張",
                    "<gray>價格：" + COIN + format(config.renamePrice()) + " 星幣", "", "<yellow>左鍵：購買")));
            // 公會（2026-10）：倉庫頁是買給公會的，改名卡是會長用的
            final tw.exilecore.guild.GuildService guilds = plugin.guilds();
            final tw.exilecore.guild.Guild guild = guilds == null ? null : guilds.guildOf(player.getUniqueId());
            final ItemStack guildStash = buyIcon(Material.BARREL, "公會倉庫頁 +1", guild == null ? 0 : guild.boughtPages(),
                    config.guildStashMax(), config.guildStashPrice(), "<gray>你的公會的倉庫多一頁（45 格，整個公會共用）",
                    guild == null ? "<red>你沒有公會" : "<gray>" + ItemRenderer.escape("[" + guild.tag() + "] " + guild.name()) + "：現在 <white>"
                            + guilds.stashPages(guild) + "<gray> 頁");
            appendLore(guildStash, "<dark_gray>頁是公會的：你退出不會帶走，公會解散就沒了（不退）");
            set(SLOT_GUILD_STASH, confirmable(SLOT_GUILD_STASH, guildStash));
            set(SLOT_GUILD_RENAME, confirmable(SLOT_GUILD_RENAME, icon(Material.OAK_SIGN, "<white>公會改名卡",
                    "<gray>一張改一次公會的名稱與標籤",
                    "<gray>會長用：/guild rename 新標籤 新名稱（或公會選單的「公會改名」）",
                    "<gray>你有 <white>" + service.guildRenameCards(player) + "<gray> 張",
                    "<gray>價格：" + COIN + format(config.guildRenamePrice()) + " 星幣", "", "<yellow>左鍵：購買")));
            set(SLOT_BOXES, icon(Material.ENDER_CHEST, FASHION_COLOR + "時裝抽獎箱", "<gray>隨機一個 3D 時裝；機率寫在裡面",
                    "<gray>重複的退回時裝幣；有保底", "", "<yellow>左鍵：打開"));
            boostSlots.clear();
            int index = 0;
            final Map<String, Boosts.Active> active = service.boosts();
            for (final SponsorConfig.BoostType type : config.boosts().values()) {
                if (index >= SLOT_BOOSTS.length) {
                    break;
                }
                final int slot = SLOT_BOOSTS[index++];
                boostSlots.put(slot, type.id());
                final Boosts.Active running = active.get(type.id());
                final long seconds = running == null ? 0 : Math.max(0, (running.until() - System.currentTimeMillis()) / 1000);
                final ItemStack stack = icon(material(type.icon(), Material.EXPERIENCE_BOTTLE), "<#ffd27f>" + ItemRenderer.escape(type.name()),
                        "<gray>" + config.boostMinutes() + " 分鐘，<white>全服一起<gray>加倍：" + Main.boostText(type),
                        "<gray>公告寫你的名字；同一種再買接著延長（最多 " + config.boostMaxMinutes() + " 分鐘）",
                        running == null ? "<dark_gray>現在沒有" : String.format(java.util.Locale.ROOT, "<green>進行中：剩 %d:%02d（%s 贊助）",
                                seconds / 60, seconds % 60, ItemRenderer.escape(running.sponsor())),
                        "<gray>價格：" + COIN + format(config.boostPrice()) + " 星幣", "", "<yellow>左鍵：購買");
                set(slot, confirmable(slot, running == null ? stack : glowing(stack)));
            }
            personalSlots.clear();
            index = 0;
            for (final SponsorConfig.BoostType type : config.boosts().values()) {
                if (index >= SLOT_PERSONAL.length) {
                    break;
                }
                final int slot = SLOT_PERSONAL[index++];
                personalSlots.put(slot, type.id());
                final long seconds = Math.max(0, (service.personalBoostUntil(player, type.id()) - System.currentTimeMillis()) / 1000);
                final ItemStack stack = icon(material(type.icon(), Material.EXPERIENCE_BOTTLE), "<#9fe8ff>" + ItemRenderer.escape(SponsorService.personalName(type)),
                        "<gray>" + config.personalBoostMinutes() + " 分鐘，<white>只有你自己<gray>：" + Main.boostText(type),
                        "<gray>跟全服加倍加起來算（全服 2 倍＋個人 2 倍 = 3 倍）",
                        "<gray>同一種再買接著延長（最多 " + config.personalBoostMaxMinutes() + " 分鐘；下線也在計時）",
                        seconds <= 0 ? "<dark_gray>現在沒有" : String.format(java.util.Locale.ROOT, "<green>進行中：剩 %d:%02d", seconds / 60, seconds % 60),
                        "<gray>價格：" + COIN + format(config.personalBoostPrice()) + " 星幣", "", "<yellow>左鍵：購買");
                set(slot, confirmable(slot, seconds <= 0 ? stack : glowing(stack)));
            }
            set(SLOT_CHAR, confirmable(SLOT_CHAR, icon(Material.ARMOR_STAND, "<white>角色欄位 +1",
                    "<gray>現在 <white>" + plugin.characters().slotsFor(player) + "<gray> 格（贊助買了 " + service.extraCharacterSlots(player) + " 格）",
                    "<gray>價格：" + COIN + format(config.slotPrice()) + " 星幣", "", "<yellow>左鍵：購買")));
            set(SLOT_STASH, confirmable(SLOT_STASH, buyIcon(Material.CHEST, "倉庫頁 +1", service.number(player, SponsorService.STASH),
                    config.stashMax(), config.stashPrice(), "<gray>倉庫多一頁（45 格，帳號共用）",
                    "<gray>現在贊助多 <white>" + service.extraStashPages(player) + "<gray> 頁（含累積贊助送的）")));
            set(SLOT_BAG, confirmable(SLOT_BAG, buyIcon(Material.BUNDLE, "隨身背包頁 +1", service.number(player, SponsorService.BAG),
                    config.bagMax(), config.bagPrice(), "<gray>隨身背包多一頁（45 格，帳號裡每個角色都有）",
                    "<gray>現在贊助多 <white>" + service.extraBagPages(player) + "<gray> 頁（含累積贊助送的）")));
            final int listings = plugin.market() == null ? 0 : plugin.market().shopSlots(player);
            set(SLOT_LISTING, confirmable(SLOT_LISTING, buyIcon(Material.EMERALD, "上架格 +1", service.number(player, SponsorService.LISTING),
                    config.listingMax(), config.listingPrice(), "<gray>市集商店頁多放一件",
                    "<gray>現在最多上架 <white>" + listings + "<gray> 件（通貨掛單也一起多）")));

            final int extraPouch = service.extraPouch(player);
            set(SLOT_POUCH, confirmable(SLOT_POUCH, icon(Material.BUNDLE, "<white>通貨袋加大",
                    "<gray>隨身背包的通貨袋：每種通貨的上限 +" + format(config.pouchStep()),
                    "<gray>現在每種最多 <white>" + format(tw.exilecore.character.CurrencyPouch.CAP + extraPouch) + "<gray>（加大過 "
                            + service.number(player, SponsorService.POUCH) + "／" + config.pouchMax() + " 次）",
                    "<gray>帳號裡每個角色都有",
                    "<gray>價格：" + COIN + format(config.pouchPrice()) + " 星幣", "", "<yellow>左鍵：購買")));
            set(SLOT_MAPS, confirmable(SLOT_MAPS, icon(Material.FILLED_MAP, "<white>地圖頁加大",
                    "<gray>倉庫的地圖頁多放 " + format(config.mapStep()) + " 張",
                    "<gray>現在最多 <white>" + format(tw.exilecore.stash.Stash.MAP_CAP + service.extraMapSlots(player)) + "<gray> 張（加大過 "
                            + service.number(player, SponsorService.MAPS) + "／" + config.mapMax() + " 次）",
                    "<gray>價格：" + COIN + format(config.mapPrice()) + " 星幣", "", "<yellow>左鍵：購買")));
            final int extraStashCurrency = service.extraStashCurrency(player);
            set(SLOT_STASH_CURRENCY, confirmable(SLOT_STASH_CURRENCY, icon(Material.CHEST, "<white>倉庫通貨頁加大",
                    "<gray>倉庫的通貨頁：每種通貨的上限 +" + format(config.stashCurrencyStep()),
                    "<gray>現在每種最多 <white>" + format(tw.exilecore.stash.Stash.CURRENCY_CAP + extraStashCurrency) + "<gray>（加大過 "
                            + service.number(player, SponsorService.STASH_CURRENCY) + "／" + config.stashCurrencyMax() + " 次）",
                    "<gray>帳號共用（倉庫是整個帳號的）",
                    "<gray>價格：" + COIN + format(config.stashCurrencyPrice()) + " 星幣", "", "<yellow>左鍵：購買")));
            set(SLOT_BACK, backButton("贊助"));
            set(SLOT_CLOSE, closeButton());
        }

        @Override
        protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
            if (slot == SLOT_BACK) {
                back();
                return;
            }
            if (slot == SLOT_CLOSE) {
                goBack();
                return;
            }
            if (!acceptAction(click) || !service.loaded(player)) {
                return;
            }
            switch (slot) {
                case SLOT_CHAR -> {
                    if (confirm(slot)) {
                        result(service.buyCharacterSlot(player));
                    }
                }
                case SLOT_STASH -> {
                    if (confirm(slot)) {
                        result(service.buyStashPage(player));
                    }
                }
                case SLOT_BAG -> {
                    if (confirm(slot)) {
                        result(service.buyBagPage(player));
                    }
                }
                case SLOT_LISTING -> {
                    if (confirm(slot)) {
                        result(service.buyListing(player));
                    }
                }
                case SLOT_POUCH -> {
                    if (confirm(slot)) {
                        result(service.buyPouch(player));
                    }
                }
                case SLOT_MAPS -> {
                    if (confirm(slot)) {
                        result(service.buyMapSlots(player));
                    }
                }
                case SLOT_STASH_CURRENCY -> {
                    if (confirm(slot)) {
                        result(service.buyStashCurrency(player));
                    }
                }
                case SLOT_RENAME -> {
                    if (confirm(slot)) {
                        result(service.buyRenameCard(player));
                    }
                }
                case SLOT_GUILD_STASH -> {
                    if (confirm(slot)) {
                        result(service.buyGuildStashPage(player));
                    }
                }
                case SLOT_GUILD_RENAME -> {
                    if (confirm(slot)) {
                        result(service.buyGuildRenameCard(player));
                    }
                }
                case SLOT_BOXES -> new Boxes(plugin, player, service).open();
                default -> {
                    final String boost = boostSlots.get(slot);
                    if (boost != null && confirm(slot)) {
                        result(service.buyBoost(player, boost));
                    }
                    final String personal = personalSlots.get(slot);
                    if (personal != null && confirm(slot)) {
                        result(service.buyPersonalBoost(player, personal));
                    }
                }
            }
        }
    }

    // ---- 累積贊助一覽 ----

    static final class Tiers extends Base {
        Tiers(final ExileCore plugin, final Player player, final SponsorService service) {
            super(plugin, player, service, 4, "<dark_gray>累積贊助");
        }

        @Override
        protected void draw() {
            for (int i = 0; i < 36; i++) {
                set(i, filler());
            }
            final long total = service.total(player);
            set(4, icon(Material.BEACON, "<#ffd27f>累積贊助 " + format(total) + " 星幣",
                    "<gray>累積的星幣（花掉也算；管理員贈送的不算）",
                    "<gray>功能用到過的最高一階（不是每階加起來）"));
            final int[] slots = {10, 11, 12, 14, 15, 16, 19, 20, 21, 23, 24, 25};
            int index = 0;
            for (final SponsorConfig.Tier tier : service.config().tiers()) {
                if (index >= slots.length) {
                    break;
                }
                final boolean reached = total >= tier.amount();
                final List<String> lore = new ArrayList<>();
                lore.add(reached ? "<green>已經開啟" : "<gray>還差 <white>" + format(tier.amount() - total) + " 星幣");
                lore.add("<gray>稱號 <" + tier.color() + ">【" + ItemRenderer.escape(tier.title()) + "】");
                final String style = tier.nameColor();
                final String tag = style.startsWith("gradient:") ? "gradient" : style;
                lore.add("<gray>名字顏色：<" + style + ">" + ItemRenderer.escape(service.displayName(player)) + "</" + tag + ">");
                if (tier.stashPages() > 0) {
                    lore.add("<gray>倉庫頁 <white>+" + tier.stashPages());
                }
                if (tier.bagPages() > 0) {
                    lore.add("<gray>隨身背包頁 <white>+" + tier.bagPages());
                }
                if (tier.listings() > 0) {
                    lore.add("<gray>市集最多上架 <white>" + tier.listings() + "<gray> 件<dark_gray>（掛單也一起多）");
                }
                if (tier.bypassFull()) {
                    lore.add("<gray>伺服器滿了也進得來");
                }
                if (tier.chatColor()) {
                    lore.add("<gray>聊天可以上色（&a、&l、&#rrggbb）");
                }
                if (tier.customTitle()) {
                    lore.add("<gray>自訂稱號（/sponsor title，<white>無限次</white>更改）");
                }
                if (tier.campFly()) {
                    lore.add("<gray>營地飛行");
                }
                if (tier.experience() > 0) {
                    lore.add("<gray>經驗 <white>+" + percentText(tier.experience()) + "%<dark_gray>（每一階疊加，到這一階共 +"
                            + percentText(service.config().reachedExperience(tier.amount())) + "%）");
                }
                if (tier.gold() > 0) {
                    lore.add("<gray>金幣 <white>+" + percentText(tier.gold()) + "%<dark_gray>（每一階疊加，到這一階共 +"
                            + percentText(service.config().reachedGold(tier.amount())) + "%）");
                }
                if (tier.deathPenalty() >= 0) {
                    lore.add("<gray>死亡損失經驗 <white>" + percentText(tier.deathPenalty()) + "%");
                }
                final ItemStack stack = icon(reached ? Material.NAME_TAG : Material.PAPER,
                        "<" + tier.color() + ">" + format(tier.amount()) + " 星幣", lore);
                set(slots[index++], reached ? glowing(stack) : stack);
            }
            set(27, backButton("贊助"));
            set(35, closeButton());
        }

        @Override
        protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
            if (slot == 27) {
                back();
            } else if (slot == 35) {
                goBack();
            }
        }
    }

    // ---- 外觀特效 ----

    static final class Cosmetics extends Base {
        private static final int SLOT_OFF = 49;
        private static final int SLOT_PREV = 47;
        private static final int SLOT_NEXT = 51;
        private static final int PER_PAGE = 36;
        private final Map<Integer, SponsorConfig.Cosmetic> shown = new HashMap<>();
        /** 第幾頁（2026-10：外觀超過 36 個）。 */
        private int page;

        Cosmetics(final ExileCore plugin, final Player player, final SponsorService service) {
            super(plugin, player, service, 6, "<dark_gray>3D 時裝");
        }

        @Override
        protected void draw() {
            for (int i = 0; i < 54; i++) {
                set(i, filler());
            }
            shown.clear();
            final SponsorConfig.Cosmetic worn = service.worn(player);
            set(4, icon(Material.BLAZE_POWDER, FASHION_COLOR + "3D 時裝", "<gray>" + balances(),
                    worn == null ? "<gray>現在：沒戴" : "<gray>現在：" + SponsorService.rarityTag(worn),
                    "<gray>只有好看，不影響戰鬥；擁有的點一下戴上"));
            final List<SponsorConfig.Cosmetic> ordered = new ArrayList<>();
            for (final SponsorConfig.Cosmetic cosmetic : service.config().cosmetics().values()) {
                if (!cosmetic.isEmote() && service.ownsCosmetic(player, cosmetic.id())) {
                    ordered.add(cosmetic);
                }
            }
            for (final SponsorConfig.Cosmetic cosmetic : service.config().cosmetics().values()) {
                // 2026-10：限定（絕版）的時裝沒有的人看不到（商城不放）
                // 2026-10-16：節慶活動（萬燈夜）的限定時裝活動期間也列出來（只能試穿，去活動兌換）
                if (!cosmetic.isEmote() && (!cosmetic.isLimited() || service.festivalPreview(cosmetic)) && !service.ownsCosmetic(player, cosmetic.id())) {
                    ordered.add(cosmetic);
                }
            }
            final int pages = Math.max(1, (ordered.size() + PER_PAGE - 1) / PER_PAGE);
            page = Math.max(0, Math.min(page, pages - 1));
            if (pages > 1) {
                set(SLOT_PREV, pageButton(false, page, pages));
                set(SLOT_NEXT, pageButton(true, page, pages));
            }
            int slot = 9;
            for (final SponsorConfig.Cosmetic cosmetic : ordered.subList(Math.min(ordered.size(), page * PER_PAGE),
                    Math.min(ordered.size(), (page + 1) * PER_PAGE))) {
                shown.put(slot, cosmetic);
                set(slot, confirmable(slot, entry(cosmetic, worn != null && worn.id().equals(cosmetic.id()))));
                slot++;
            }
            set(45, backButton("贊助"));
            if (worn != null) {
                set(SLOT_OFF, icon(Material.SHEARS, "<white>拿下特效", "", "<yellow>左鍵：拿下"));
            }
            set(53, closeButton());
        }

        private ItemStack entry(final SponsorConfig.Cosmetic cosmetic, final boolean worn) {
            final boolean owned = service.ownsCosmetic(player, cosmetic.id());
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>" + cosmetic.rarity().displayName() + "・" + kindText(cosmetic));
            final boolean festival = service.festivalPreview(cosmetic);
            if (festival) {
                lore.add("<#ff8a1e>★ " + ItemRenderer.escape(cosmetic.limitedTag()) + " <gray>活動兌換商店用糖果、燈芯換（/festival）");
            } else if (cosmetic.isLimited()) {
                lore.add("<#ffd24a>★ " + ItemRenderer.escape(cosmetic.limitedTag()) + " <dark_gray>絕版：抽不到、也不能兌換");
            }
            if (!cosmetic.description().isEmpty()) {
                lore.add("<gray>" + ItemRenderer.escape(cosmetic.description()));
            }
            if (!owned && festival) {
                lore.add("<dark_gray>還沒有（這裡不能買）");
                lore.add("");
                lore.add("<yellow>右鍵：試穿 " + SponsorService.TRY_ON_TICKS / 20 + " 秒");
            } else if (owned && cosmetic.skin()) {
                lore.add("<gray>擁有");
                lore.add("");
                lore.add("<yellow>左鍵：去裝備幻化套上");
            } else if (owned) {
                lore.add(worn ? "<green>戴著" : "<gray>擁有");
                lore.add("");
                lore.add(worn ? "<yellow>左鍵：拿下" : "<yellow>左鍵：戴上");
            } else if (cosmetic.fashionPrice() > 0) {
                lore.add("<gray>時裝幣兌換：" + FASHION_COLOR + cosmetic.fashionPrice() + " 時裝幣</#ff9ad5><gray>（你有 " + service.fashionCoins(player) + "）");
                lore.add("<gray>抽獎箱也抽得到");
                lore.add("");
                lore.add("<yellow>左鍵：兌換");
                lore.add("<yellow>右鍵：試穿 " + SponsorService.TRY_ON_TICKS / 20 + " 秒");
            } else {
                lore.add("<dark_gray>還沒有");
                if (!cosmetic.isLimited()) {
                    lore.add("");
                    lore.add("<yellow>右鍵：試穿 " + SponsorService.TRY_ON_TICKS / 20 + " 秒");
                }
            }
            final ItemStack stack = cosmetic.skin() ? skinStack(service, cosmetic, SponsorService.rarityTag(cosmetic), lore)
                    : icon(owned ? Material.BLAZE_POWDER : Material.GUNPOWDER, SponsorService.rarityTag(cosmetic), lore);
            return worn ? highlight(stack) : stack;
        }

        @Override
        protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
            if (slot == 45) {
                back();
                return;
            }
            if (slot == 53) {
                goBack();
                return;
            }
            if (slot == SLOT_PREV || slot == SLOT_NEXT) {
                page += slot == SLOT_NEXT ? 1 : -1;
                final int count = (int) service.config().cosmetics().values().stream()
                        .filter(c -> !c.isEmote() && (!c.isLimited() || service.festivalPreview(c) || service.ownsCosmetic(player, c.id()))).count();
                final int pages = Math.max(1, (count + PER_PAGE - 1) / PER_PAGE);
                page = Math.floorMod(page, pages);
                redraw();
                return;
            }
            if (!acceptAction(click)) {
                return;
            }
            if (slot == SLOT_OFF && service.worn(player) != null) {
                result(service.wear(player, null));
                return;
            }
            final SponsorConfig.Cosmetic cosmetic = shown.get(slot);
            if (cosmetic == null) {
                return;
            }
            if (!service.ownsCosmetic(player, cosmetic.id()) && click.isRightClick()) {
                // 試穿（2026-10）：關掉選單才看得到自己（F5）
                final String problem = service.tryOn(player, cosmetic.id());
                if (problem != null) {
                    result(problem);
                    return;
                }
                player.closeInventory();
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 1.2f);
                player.sendMessage(plugin.text().prefixed("<#ff9ad5>試穿 " + SponsorService.rarityTag(cosmetic) + "<gray> "
                        + SponsorService.TRY_ON_TICKS / 20 + " 秒（" + SponsorService.tryOnHint(cosmetic) + "；按 F5 看自己）"));
                return;
            }
            if (service.ownsCosmetic(player, cosmetic.id()) && cosmetic.skin()) {
                new Transmog(plugin, player, service).open();
            } else if (service.ownsCosmetic(player, cosmetic.id())) {
                final SponsorConfig.Cosmetic worn = service.worn(player);
                result(service.wear(player, worn != null && worn.id().equals(cosmetic.id()) ? null : cosmetic.id()));
            } else if (cosmetic.fashionPrice() > 0 && click.isLeftClick() && confirm(slot)) {
                result(service.buyCosmetic(player, cosmetic.id()));
            }
        }
    }

    /** 「粒子・頭上的光環」「幻化・弓」。 */
    static String kindText(final SponsorConfig.Cosmetic cosmetic) {
        if (cosmetic.isEmote()) {
            return "動作";
        }
        if (!cosmetic.skin()) {
            return "粒子・" + cosmetic.style().displayName();
        }
        return "幻化・" + switch (cosmetic.skinSlot()) {
            case "weapon" -> "近戰武器、法杖";
            case "bow" -> "弓";
            case "crossbow" -> "弩";
            case "offhand" -> "副手（盾、箭袋）";
            case "headgear" -> "3D 頭飾（沒戴頭盔也看得到）";
            case "back" -> "3D 背飾（跟在背後）";
            case "pet" -> "3D 寵物（跟在身邊飄）";
            default -> "防具（頭、身、腿、腳一整套）";
        };
    }

    /** 幻化外觀的圖示：資源包裡那一套的樣子（沒送資源包就是原版的同類物品）。 */
    static ItemStack skinStack(final SponsorService service, final SponsorConfig.Cosmetic cosmetic, final String name, final List<String> lore) {
        final SponsorService.Look look = switch (cosmetic.skinSlot()) {
            case "bow" -> SponsorService.Look.BOW;
            case "crossbow" -> SponsorService.Look.CROSSBOW;
            case "offhand" -> SponsorService.Look.OFFHAND;
            case "armor" -> SponsorService.Look.CHEST;
            case "headgear" -> SponsorService.Look.HEAD;
            case "back" -> SponsorService.Look.BACK;
            case "pet" -> SponsorService.Look.PET;
            default -> SponsorService.Look.WEAPON;
        };
        final Material material = switch (look) {
            case BOW -> Material.BOW;
            case CROSSBOW -> Material.CROSSBOW;
            case OFFHAND -> Material.SHIELD;
            case CHEST -> Material.IRON_CHESTPLATE;
            case HEAD -> Material.GOLDEN_HELMET;
            case BACK -> Material.ELYTRA;
            case PET -> Material.ALLAY_SPAWN_EGG;
            default -> Material.IRON_SWORD;
        };
        final ItemStack stack = Menu.icon(material, name, lore);
        if (service.packSent()) {
            service.applySkin(cosmetic, look, stack);
        }
        return stack;
    }

    // ---- 動作（2026-10）----

    static final class EmoteMenu extends Base {
        private final Map<Integer, SponsorConfig.Cosmetic> shown = new HashMap<>();

        EmoteMenu(final ExileCore plugin, final Player player, final SponsorService service) {
            super(plugin, player, service, 4, "<dark_gray>動作");
        }

        @Override
        protected void draw() {
            for (int i = 0; i < 36; i++) {
                set(i, filler());
            }
            shown.clear();
            final List<String> header = new ArrayList<>();
            header.add("<gray>" + balances());
            header.add("<gray>把擁有的動作綁到按鍵，按了就做（別人也看得到）");
            header.add("<gray>營地以外只有特效、不會移動；每 3 秒一次");
            header.add("");
            for (final SponsorService.EmoteKey key : SponsorService.EmoteKey.values()) {
                final SponsorConfig.Cosmetic on = service.emoteOn(player, key);
                header.add("<gray>" + key.displayName() + "：" + (on == null ? "<dark_gray>沒綁（原本的功能）" : SponsorService.rarityTag(on)));
            }
            header.add("");
            header.add("<dark_gray>F：戰鬥中本來就沒作用；Q：手上要拿著東西才按得出來");
            header.add("<dark_gray>指令：/emote 打開這裡、/emote 動作名稱 直接做（不用綁）");
            set(4, icon(Material.FEATHER, FASHION_COLOR + "動作", header));
            int slot = 10;
            for (final SponsorConfig.Cosmetic cosmetic : service.config().cosmetics().values()) {
                if (!cosmetic.isEmote() || slot > 25) {
                    continue;
                }
                shown.put(slot, cosmetic);
                set(slot, confirmable(slot, entry(cosmetic)));
                slot += slot % 9 == 7 ? 3 : 1;
            }
            set(27, backButton("贊助"));
            set(35, closeButton());
        }

        private ItemStack entry(final SponsorConfig.Cosmetic cosmetic) {
            final boolean owned = service.ownsCosmetic(player, cosmetic.id());
            final SponsorService.EmoteKey key = owned ? service.keyOf(player, cosmetic.id()) : null;
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>" + cosmetic.rarity().displayName() + "・動作");
            if (!cosmetic.description().isEmpty()) {
                lore.add("<gray>" + ItemRenderer.escape(cosmetic.description()));
            }
            if (owned) {
                lore.add(key == null ? "<gray>擁有・沒綁按鍵" : "<green>綁在 " + key.displayName());
                lore.add("");
                lore.add("<yellow>滑鼠指著按數字鍵 1／2／3：直接綁到 F／Q／Shift＋Q（再按一次同一個鍵＝不綁）");
                lore.add("<yellow>左鍵：換按鍵（F → Q → Shift＋Q → 不綁）");
                lore.add("<yellow>右鍵：做一次看看");
            } else if (cosmetic.fashionPrice() > 0) {
                lore.add("<gray>時裝幣兌換：" + FASHION_COLOR + cosmetic.fashionPrice() + " 時裝幣</#ff9ad5><gray>（你有 " + service.fashionCoins(player) + "）");
                lore.add("");
                lore.add("<yellow>左鍵：兌換");
            } else {
                lore.add("<dark_gray>還沒有");
            }
            final Material material = switch (cosmetic.emote()) {
                case "kick_ball" -> Material.SNOWBALL;
                case "bear_shake" -> Material.BROWN_WOOL;
                case "head_bob" -> Material.NOTE_BLOCK;
                case "king_sit" -> Material.GRAY_WOOL;
                case "love_you" -> Material.PINK_PETALS;
                case "six_seven" -> Material.CLOCK;
                default -> Material.PHANTOM_MEMBRANE;
            };
            final ItemStack stack = icon(owned ? material : Material.GRAY_DYE, SponsorService.rarityTag(cosmetic), lore);
            return key != null ? highlight(stack) : stack;
        }

        @Override
        protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
            if (slot == 27) {
                back();
                return;
            }
            if (slot == 35) {
                goBack();
                return;
            }
            final SponsorConfig.Cosmetic cosmetic = shown.get(slot);
            if (cosmetic == null || !acceptAction(click)) {
                return;
            }
            if (!service.ownsCosmetic(player, cosmetic.id())) {
                if (click == ClickType.LEFT && cosmetic.fashionPrice() > 0 && confirm(slot)) {
                    result(service.buyCosmetic(player, cosmetic.id()));
                }
                return;
            }
            if (click == ClickType.RIGHT) {
                player.closeInventory();
                if (service.emotes != null) {
                    service.emotes.play(player, cosmetic, false);
                }
                return;
            }
            if (click == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0 && event.getHotbarButton() < SponsorService.EmoteKey.values().length) {
                // 2026-10：數字鍵 1／2／3 直接綁到 F／Q／Shift＋Q（已經綁在那個鍵就拿掉）
                final SponsorService.EmoteKey wanted = SponsorService.EmoteKey.values()[event.getHotbarButton()];
                final SponsorService.EmoteKey current = service.keyOf(player, cosmetic.id());
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                result(service.bindEmote(player, cosmetic.id(), wanted == current ? null : wanted));
                return;
            }
            if (click == ClickType.LEFT) {
                final SponsorService.EmoteKey current = service.keyOf(player, cosmetic.id());
                final SponsorService.EmoteKey[] keys = SponsorService.EmoteKey.values();
                final SponsorService.EmoteKey next = current == null ? keys[0]
                        : current.ordinal() + 1 < keys.length ? keys[current.ordinal() + 1] : null;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                result(service.bindEmote(player, cosmetic.id(), next));
            }
        }
    }

    // ---- 裝備幻化 ----

    static final class Transmog extends Base {
        private static final int FIRST = 10;
        /** 箭袋的幻化要不要拿在手上的開關（最下面一排中間）。 */
        private static final int SLOT_QUIVER = 31;

        /** 第 i 個外觀的格子：裝備 8 格一排，背飾、寵物在下一排中間。 */
        private static int slotOf(final int i) {
            return i < 8 ? FIRST + i : 21 + (i - 8) * 2;
        }

        Transmog(final ExileCore plugin, final Player player, final SponsorService service) {
            super(plugin, player, service, 4, "<dark_gray>裝備幻化");
        }

        @Override
        protected void draw() {
            for (int i = 0; i < 36; i++) {
                set(i, filler());
            }
            set(4, icon(Material.NETHERITE_CHESTPLATE, FASHION_COLOR + "裝備幻化",
                    "<gray>只改看起來的樣子（別人也看得到），數值不變",
                    "<gray>套在這裡：換了裝備也是這個樣子",
                    "<gray>主手分近戰／法杖、弓、弩三種，各套各的",
                    "<gray>頭可以套 3D 頭飾（沒戴頭盔也看得到）；背飾、寵物在下面",
                    "<gray>點一個選外觀"));
            final tw.exilecore.character.PlayerState state = plugin.players().get(player);
            final SponsorService.Look[] looks = SponsorService.Look.values();
            for (int i = 0; i < looks.length; i++) {
                final SponsorService.Look look = looks[i];
                final int at = slotOf(i);
                final SponsorConfig.Cosmetic skin = service.transmog(player, look);
                final List<String> lore = new ArrayList<>();
                lore.add(skin == null ? "<gray>幻化：<white>原本的樣子" : "<gray>幻化：" + SponsorService.rarityTag(skin));
                lore.add("");
                lore.add("<yellow>左鍵：選外觀");
                final ItemStack icon;
                if (skin != null) {
                    icon = skinStack(service, skin, "<white>" + look.displayName(), lore);
                    if (look.compareTo(SponsorService.Look.HEAD) >= 0 && look.equipment()) {
                        // 防具：圖示換成這個部位
                        final Material material = switch (look) {
                            case HEAD -> Material.IRON_HELMET;
                            case LEGS -> Material.IRON_LEGGINGS;
                            case FEET -> Material.IRON_BOOTS;
                            default -> Material.IRON_CHESTPLATE;
                        };
                        final ItemStack part = icon(material, "<white>" + look.displayName(), lore);
                        if (service.packSent()) {
                            service.applySkin(skin, look, part);
                        }
                        set(at, part);
                        continue;
                    }
                } else {
                    final Material material = switch (look) {
                        case WEAPON -> Material.IRON_SWORD;
                        case BOW -> Material.BOW;
                        case CROSSBOW -> Material.CROSSBOW;
                        case OFFHAND -> Material.SHIELD;
                        case HEAD -> Material.IRON_HELMET;
                        case CHEST -> Material.IRON_CHESTPLATE;
                        case LEGS -> Material.IRON_LEGGINGS;
                        case FEET -> Material.IRON_BOOTS;
                        case BACK -> Material.ELYTRA;
                        case PET -> Material.ALLAY_SPAWN_EGG;
                    };
                    icon = icon(material, "<white>" + look.displayName(), lore);
                }
                set(at, icon);
            }
            // 2026-10：箭袋平常不拿在手上；副手套了幻化的可以選要不要顯示
            final boolean quiverShown = service.quiverSkinEnabled(player);
            set(SLOT_QUIVER, icon(Material.ARROW, FASHION_COLOR + "箭袋的幻化：" + (quiverShown ? "<green>顯示在手上" : "<red>不顯示"),
                    "<gray>箭袋裝在副手時平常不會拿在手上。",
                    "<gray>副手套了幻化的話，可以選要不要把幻化的樣子拿在左手上",
                    "<gray>（沒套幻化的箭袋一律不顯示；盾、法器不受影響）",
                    "",
                    "<yellow>左鍵：" + (quiverShown ? "改成不顯示" : "改成顯示")));
            set(27, backButton("贊助"));
            set(35, closeButton());
        }

        @Override
        protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
            if (slot == 27) {
                back();
                return;
            }
            if (slot == 35) {
                goBack();
                return;
            }
            if (slot == SLOT_QUIVER) {
                if (acceptAction(click)) {
                    service.toggleQuiverSkin(player);
                    redraw();
                }
                return;
            }
            for (int i = 0; i < SponsorService.Look.values().length; i++) {
                if (slotOf(i) == slot) {
                    new SkinPick(plugin, player, service, SponsorService.Look.values()[i]).open();
                    return;
                }
            }
        }
    }

    static final class SkinPick extends Base {
        private final SponsorService.Look look;
        private final Map<Integer, SponsorConfig.Cosmetic> shown = new HashMap<>();

        SkinPick(final ExileCore plugin, final Player player, final SponsorService service, final SponsorService.Look look) {
            super(plugin, player, service, 6, "<dark_gray>幻化：" + look.displayName());
            this.look = look;
        }

        @Override
        protected void draw() {
            for (int i = 0; i < 54; i++) {
                set(i, filler());
            }
            shown.clear();
            final SponsorConfig.Cosmetic current = service.transmog(player, look);
            set(4, icon(Material.NETHERITE_CHESTPLATE, FASHION_COLOR + "幻化：" + look.displayName(), "<gray>" + balances(),
                    current == null ? "<gray>現在：原本的樣子" : "<gray>現在：" + SponsorService.rarityTag(current)));
            final ItemStack original = icon(Material.BARRIER, "<white>原本的樣子", "", "<yellow>左鍵：拿掉幻化");
            set(9, current == null ? highlight(original) : original);
            final List<SponsorConfig.Cosmetic> list = new ArrayList<>();
            for (final boolean owned : new boolean[] {true, false}) {
                for (final SponsorConfig.Cosmetic cosmetic : service.config().cosmetics().values()) {
                    if (!owned && cosmetic.isLimited()) {
                        continue; // 2026-10：限定（絕版）的沒有就不列
                    }
                    if (SponsorService.fits(cosmetic, look) && service.ownsCosmetic(player, cosmetic.id()) == owned) {
                        list.add(cosmetic);
                    }
                }
            }
            int at = 10;
            for (final SponsorConfig.Cosmetic cosmetic : list) {
                if (at > 44) {
                    break;
                }
                shown.put(at, cosmetic);
                final boolean owned = service.ownsCosmetic(player, cosmetic.id());
                final boolean on = current != null && current.id().equals(cosmetic.id());
                final List<String> lore = new ArrayList<>();
                lore.add("<gray>" + cosmetic.rarity().displayName() + "・" + kindText(cosmetic));
                if (cosmetic.isLimited()) {
                    lore.add("<#ffd24a>★ " + ItemRenderer.escape(cosmetic.limitedTag()) + " <dark_gray>絕版");
                }
                if (!cosmetic.description().isEmpty()) {
                    lore.add("<gray>" + ItemRenderer.escape(cosmetic.description()));
                }
                if (owned) {
                    lore.add(on ? "<green>套著" : "");
                    lore.add(on ? "" : "<yellow>左鍵：套上");
                } else {
                    lore.add("<dark_gray>還沒有");
                    lore.add(cosmetic.fashionPrice() > 0 ? "<gray>時裝幣兌換 " + FASHION_COLOR + cosmetic.fashionPrice() + "</#ff9ad5><gray>（3D 時裝裡）"
                            : "<gray>抽獎箱抽得到");
                }
                final ItemStack stack = skinStack(service, cosmetic, SponsorService.rarityTag(cosmetic), lore);
                set(at, on ? highlight(stack) : stack);
                at++;
            }
            set(45, backButton("裝備幻化"));
            set(53, closeButton());
        }

        @Override
        protected void onClick(final int clicked, final ClickType click, final InventoryClickEvent event) {
            if (clicked == 45) {
                new Transmog(plugin, player, service).open();
                return;
            }
            if (clicked == 53) {
                goBack();
                return;
            }
            if (!acceptAction(click)) {
                return;
            }
            if (clicked == 9) {
                result(service.setTransmog(player, look, null));
                return;
            }
            final SponsorConfig.Cosmetic cosmetic = shown.get(clicked);
            if (cosmetic != null && service.ownsCosmetic(player, cosmetic.id())) {
                final String problem = service.setTransmog(player, look, cosmetic.id());
                if (problem == null) {
                    player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.7f, 1.2f);
                }
                result(problem);
            }
        }
    }

    // ---- 抽獎箱 ----

    static final class Boxes extends Base {
        /** 從星幣商城進來的（2026-10）：返回回星幣商城。 */
        @Override
        void back() {
            new Expand(plugin, player, service).open();
        }

        private final Map<Integer, SponsorConfig.Box> shown = new HashMap<>();

        Boxes(final ExileCore plugin, final Player player, final SponsorService service) {
            super(plugin, player, service, 4, "<dark_gray>時裝抽獎箱");
        }

        @Override
        protected void draw() {
            for (int i = 0; i < 36; i++) {
                set(i, filler());
            }
            shown.clear();
            set(4, icon(Material.ENDER_CHEST, FASHION_COLOR + "時裝抽獎箱", "<gray>" + balances(),
                    "<gray>每箱隨機一個 3D 時裝（機率寫在每一箱上）",
                    "<gray>抽到已經有的：退回時裝幣（普通 " + service.config().duplicateRefund(SponsorConfig.Rarity.COMMON) + "、稀有 "
                            + service.config().duplicateRefund(SponsorConfig.Rarity.RARE) + "、史詩 "
                            + service.config().duplicateRefund(SponsorConfig.Rarity.EPIC) + "、傳說 "
                            + service.config().duplicateRefund(SponsorConfig.Rarity.LEGENDARY) + "）"));
            final int[] slots = {11, 13, 15, 20, 22, 24};
            int index = 0;
            for (final SponsorConfig.Box box : service.config().boxes().values()) {
                if (index >= slots.length) {
                    break;
                }
                final int slot = slots[index++];
                shown.put(slot, box);
                final ItemStack stack = icon(material(box.icon(), Material.CHEST), FASHION_COLOR + ItemRenderer.escape(box.name()), lore(box));
                set(slot, confirmable(slot * 2, confirmable(slot * 2 + 1, stack)));
            }
            set(27, backButton("星幣商城"));
            set(35, closeButton());
        }

        private List<String> lore(final SponsorConfig.Box box) {
            final List<String> lore = new ArrayList<>();
            if (!box.description().isEmpty()) {
                lore.add("<gray>" + ItemRenderer.escape(box.description()));
            }
            lore.add("<white>機率：");
            final double total = box.total();
            for (final Map.Entry<String, Double> entry : box.weights().entrySet()) {
                final SponsorConfig.Cosmetic cosmetic = service.config().cosmetics().get(entry.getKey());
                lore.add(" " + SponsorService.rarityTag(cosmetic) + " <white>" + Odds.percent(entry.getValue(), total)
                        + (service.ownsCosmetic(player, cosmetic.id()) ? " <dark_gray>（已有）" : ""));
            }
            if (box.pity() > 0) {
                lore.add("<gray>保底：連續 " + box.pity() + " 箱一定有一個傳說（你已經 " + service.pity(player, box.id()) + " 箱沒有）");
            }
            lore.add("");
            if (box.starPrice() > 0) {
                lore.add("<yellow>左鍵：" + COIN + format(box.starPrice()) + " 星幣</#f5b942><yellow> 開一箱");
            }
            if (box.fashionPrice() > 0) {
                lore.add("<yellow>" + (box.starPrice() > 0 ? "右鍵" : "左鍵") + "：" + FASHION_COLOR + box.fashionPrice() + " 時裝幣</#ff9ad5><yellow> 開一箱");
            }
            if (box.id().equals(service.config().freeBox())) {
                // 2026-10 月卡福利：每天免費開一次
                final long free = service.freeBoxes(player);
                lore.add(free > 0 ? CARD_COLOR + "Shift＋左鍵：免費開一箱（還有 " + free + " 箱）"
                        : service.cardDaysLeft(player) <= 0 ? CARD_COLOR + "月卡：每天送一箱免費的，沒開的會累積<dark_gray>（你沒有月卡）"
                        : "<dark_gray>月卡免費箱：開完了，明天再送一箱");
            }
            return lore;
        }

        @Override
        protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
            if (slot == 27) {
                back();
                return;
            }
            if (slot == 35) {
                goBack();
                return;
            }
            if (!acceptAction(click)) {
                return;
            }
            final SponsorConfig.Box box = shown.get(slot);
            if (box == null) {
                return;
            }
            if (click.isShiftClick() && click.isLeftClick() && box.id().equals(service.config().freeBox())) {
                result(service.openBox(player, box.id(), false, true)); // 月卡每日免費（不用確認：不花錢）
                return;
            }
            final boolean star = box.starPrice() > 0 && (click.isLeftClick() || box.fashionPrice() <= 0);
            if (confirm(slot * 2 + (star ? 0 : 1))) {
                result(service.openBox(player, box.id(), star));
            }
        }
    }

    // ---- 月卡幣兌換 ----

    static final class CardShop extends Base {
        private final Map<Integer, SponsorConfig.CardItem> shown = new HashMap<>();

        CardShop(final ExileCore plugin, final Player player, final SponsorService service) {
            super(plugin, player, service, 4, "<dark_gray>月卡幣兌換");
        }

        @Override
        protected void draw() {
            for (int i = 0; i < 36; i++) {
                set(i, filler());
            }
            shown.clear();
            set(4, icon(Material.AMETHYST_CLUSTER, CARD_COLOR + "月卡幣兌換", "<gray>你有 " + CARD_COLOR + service.cardCoins(player) + " 月卡幣",
                    "<gray>月卡每天發 " + service.config().dailyCardCoins() + " 個"));
            int slot = 10;
            for (final SponsorConfig.CardItem item : service.config().cardShop()) {
                if (item.hidden()) {
                    continue; // 下架的
                }
                if (slot > 25) {
                    break;
                }
                if (slot == 17) {
                    slot = 19;
                }
                shown.put(slot, item);
                set(slot, confirmable(slot, entry(item)));
                slot++;
            }
            set(27, backButton("贊助"));
            set(35, closeButton());
        }

        private ItemStack entry(final SponsorConfig.CardItem item) {
            final boolean owned = service.ownsCardItem(player, item);
            final List<String> lore = new ArrayList<>();
            final Material material;
            final String name;
            switch (item.kind()) {
                case TITLE -> {
                    material = Material.NAME_TAG;
                    name = "<" + item.color() + ">【" + ItemRenderer.escape(item.name()) + "】";
                    lore.add("<gray>月卡稱號（沒有加成）");
                }
                case COSMETIC -> {
                    material = Material.BLAZE_POWDER;
                    final SponsorConfig.Cosmetic cosmetic = service.config().cosmetics().get(item.cosmetic());
                    name = SponsorService.rarityTag(cosmetic);
                    lore.add("<gray>" + (cosmetic.skin() ? kindText(cosmetic) : "外觀特效・" + cosmetic.style().displayName()));
                }
                case FASHION_COINS -> {
                    material = Material.PINK_DYE;
                    name = FASHION_COLOR + "時裝幣 ×" + item.amount();
                    lore.add("<gray>抽獎箱、兌換 3D 時裝用");
                }
                case RENAME_CARD -> {
                    material = Material.WRITABLE_BOOK;
                    name = "<white>改名卡 ×" + item.amount();
                    lore.add("<gray>一張改一次角色名字：/char rename 新名字");
                    lore.add("<gray>你有 <white>" + service.renameCards(player) + "</white> 張");
                }
                default -> {
                    material = Material.ARMOR_STAND;
                    name = "<white>角色欄位 +1";
                    lore.add("<gray>帳號多一個角色格");
                }
            }
            lore.add(owned ? "<green>已經有了" : "<gray>價格：" + CARD_COLOR + item.price() + " 月卡幣");
            if (!owned) {
                lore.add("");
                lore.add("<yellow>左鍵：兌換");
            }
            return icon(material, name, lore);
        }

        @Override
        protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
            if (slot == 27) {
                back();
                return;
            }
            if (slot == 35) {
                goBack();
                return;
            }
            if (!acceptAction(click)) {
                return;
            }
            final SponsorConfig.CardItem item = shown.get(slot);
            if (item != null && !service.ownsCardItem(player, item) && confirm(slot)) {
                result(service.buyCardItem(player, item.id()));
            }
        }
    }

    /** 百分比的數字：整數不寫小數點（5、0），其他照寫（7.5）。 */
    static String percentText(final double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
