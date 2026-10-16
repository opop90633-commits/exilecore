package tw.exilecore.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.StatsReport;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.skill.SkillMenu;
import tw.exilecore.zone.WaypointMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 選單（/menu，或按 Shift＋F）：所有功能的入口。2026-10 改成統一版面、分組：
 * <pre>
 * 第 1 排：角色（名字、職業、等級、金幣、功勳、天賦點）
 * （2026-10：拿掉每排最左邊不能按的分類名稱，每排置中）
 * 第 2 排【角色】：角色面板、天賦樹、技能、稱號、切換角色（2026-10：屬性改成點第 1 排的角色；昇華在天賦樹的總覽、鎖定在拆解台）
 * 第 3 排【物品】：隨身背包、倉庫、掉落過濾、拆解
 * 第 4 排【冒險】：傳送、回營地、任務、委託、每月固定活動、競技場（星圖在地圖裝置）
 * 第 5 排【多人】：公會、隊伍、市集、Discord、贊助商城（2026-10：交易、說明拿掉；交易改成蹲下右鍵對方或 /trade）
 * 第 6 排：圖鑑、設定、關閉
 * </pre>
 */
public final class MainMenu extends Menu {

    // 2026-10：拿掉星圖、昇華、鎖定、屬性（屬性改成點最上面的角色）；每排置中
    private static final int SLOT_HEADER = 4;
    private static final int SLOT_PANEL = 11;
    private static final int SLOT_TREE = 12;
    private static final int SLOT_SKILLS = 13;
    private static final int SLOT_TITLES = 14;
    private static final int SLOT_CHARACTERS = 15;
    private static final int SLOT_BAG = 20;
    private static final int SLOT_STASH = 21;
    private static final int SLOT_FILTER = 22;
    private static final int SLOT_TRASH = 23;
    private static final int SLOT_WAYPOINTS = 28;
    private static final int SLOT_TOWN = 29;
    private static final int SLOT_QUESTS = 30;
    private static final int SLOT_BOUNTY = 31;
    private static final int SLOT_EVENT = 32;
    /** 不定時活動（2026-10，開服活動）。 */
    private static final int SLOT_WORLD_EVENT = 34;
    private static final int SLOT_FESTIVAL = 35; // 2026-10 節慶活動（萬燈夜）：活動到兌換結束才出現
    private static final int SLOT_SEA = 24; // 2026-10 生活玩法「星骸之海」
    private static final int SLOT_PVP = 33;
    private static final int SLOT_PARTY = 39;
    /** 公會（2026-10）。 */
    private static final int SLOT_GUILD = 38;
    private static final int SLOT_MARKET = 40;
    private static final int SLOT_DISCORD = 41;
    private static final int SLOT_STORE = 42;
    /** 新手教學（2026-10）：網誌的連結。 */
    private static final int SLOT_TUTORIAL = 16;
    private static final int SLOT_CODEX = 47;
    private static final int SLOT_SETTINGS = 51;
    private static final int SLOT_CLOSE = SLOT_CLOSE_BAR;

    /**
     * 套用了介面資源包的人看「圖示＋中文」版面（2026-10）：圖示在第 1、3、5 排，下面一排寫中文名稱
     * （資源包把 web/menu/main_menu.png 當成標題的一個字畫在介面上，名稱跟這裡的位置要對得上，改了要重跑 tools/menu_labels.py）。
     * 按鈕的代號照舊（上面那些 SLOT_*），這張表把代號換成這個版面的格子。
     */
    // 2026-10：第 1 排角色（左）＋圖鑑、設定（右）；第 2 排物品（左）＋交易與社群（右）；第 3 排冒險。拿掉「返回」（Esc 就關了）
    private static final java.util.Map<Integer, Integer> LABELLED = java.util.Map.ofEntries(
            java.util.Map.entry(SLOT_HEADER, 0), java.util.Map.entry(SLOT_PANEL, 1), java.util.Map.entry(SLOT_TREE, 2),
            java.util.Map.entry(SLOT_SKILLS, 3), java.util.Map.entry(SLOT_TITLES, 4), java.util.Map.entry(SLOT_CHARACTERS, 5),
            java.util.Map.entry(SLOT_TUTORIAL, 6), java.util.Map.entry(SLOT_CODEX, 7), java.util.Map.entry(SLOT_SETTINGS, 8),
            java.util.Map.entry(SLOT_BAG, 18), java.util.Map.entry(SLOT_STASH, 19), java.util.Map.entry(SLOT_FILTER, 20),
            java.util.Map.entry(SLOT_TRASH, 21), java.util.Map.entry(SLOT_GUILD, 22), java.util.Map.entry(SLOT_MARKET, 23),
            java.util.Map.entry(SLOT_PARTY, 24),
            java.util.Map.entry(SLOT_DISCORD, 25), java.util.Map.entry(SLOT_STORE, 26),
            java.util.Map.entry(SLOT_TOWN, 36), java.util.Map.entry(SLOT_WAYPOINTS, 37), java.util.Map.entry(SLOT_QUESTS, 38),
            java.util.Map.entry(SLOT_BOUNTY, 39), java.util.Map.entry(SLOT_EVENT, 40), java.util.Map.entry(SLOT_PVP, 41),
            java.util.Map.entry(SLOT_WORLD_EVENT, 42), java.util.Map.entry(SLOT_FESTIVAL, 43), java.util.Map.entry(SLOT_SEA, 44));
    /** 反過來：「圖示＋中文」版面的格子 → 按鈕代號。 */
    private static final java.util.Map<Integer, Integer> LABELLED_BACK = invert(LABELLED);

    /** 這個人看的是「圖示＋中文」版面。 */
    private final boolean labelled;

    public MainMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 6, labelled(plugin, player)
                ? "<white><font:" + tw.exilecore.web.UiPack.MENU_FONT + ">" + tw.exilecore.web.UiPack.MENU_SHIFT_START
                + tw.exilecore.web.UiPack.MENU_MAIN + tw.exilecore.web.UiPack.MENU_SHIFT_BACK + "</font></white><dark_gray>選單"
                : "<dark_gray>選單");
        this.labelled = labelled(plugin, player);
        if (plugin.guide() != null) {
            plugin.guide().noteMenu(player); // 新手教學：打開選單
        }
    }

    /** 有套用介面資源包（而且資源包裡有主選單的背景圖）。 */
    private static boolean labelled(final ExileCore plugin, final Player player) {
        return plugin.web() != null && plugin.web().hasPack(player)
                && MainMenu.class.getResource("/web/menu/main_menu.png") != null;
    }

    private static java.util.Map<Integer, Integer> invert(final java.util.Map<Integer, Integer> map) {
        final java.util.Map<Integer, Integer> back = new java.util.HashMap<>();
        for (final java.util.Map.Entry<Integer, Integer> entry : map.entrySet()) {
            back.put(entry.getValue(), entry.getKey());
        }
        return java.util.Map.copyOf(back);
    }

    /** 把按鈕放到它在這個版面的格子（一般版面就是代號本身）。 */
    private void put(final int button, final org.bukkit.inventory.ItemStack stack) {
        set(labelled ? LABELLED.getOrDefault(button, button) : button, stack);
    }

    @Override
    protected void draw() {
        if (labelled) {
            for (int i = 0; i < 54; i++) {
                set(i, null); // 名稱那幾排要空著，背景的中文才看得到
            }
        } else {
            frame();
        }
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state.character();
        final boolean ready = state.isLoaded() && character != null;

        final List<String> header = new ArrayList<>();
        if (ready) {
            final String ascendancy = plugin.ascendancy() == null ? null : plugin.ascendancy().ascendancyName(state);
            header.add("<gray>" + character.characterClass().displayName() + (ascendancy == null ? "" : "<gray>・" + ascendancy + "<gray>")
                    + "　Lv." + state.level()
                    + (state.level() >= plugin.players().config().levelCap() ? "（滿級）"
                    : String.format(Locale.ROOT, "　經驗 %.1f%%", plugin.players().experienceProgress(state) * 100)));
            header.add(String.format(Locale.ROOT, "<#f5b942>金幣 %,d <dark_gray>（帳號共用）", state.gold()));
            final long merits = plugin.bounties() == null ? -1 : plugin.bounties().merits(player);
            if (merits >= 0) {
                header.add(String.format(Locale.ROOT, "<#f5b942>功勳 %,d <dark_gray>（帳號共用、不能交易）", merits));
            }
            header.add(String.format(Locale.ROOT, "<gray>可用天賦點 <white>%d", plugin.players().availablePoints(state)));
            if (plugin.bounties() != null && plugin.bounties().wornTitle(player) != null) {
                header.add("<gray>稱號：" + plugin.bounties().titleTag(player));
            }
        } else {
            header.add("<red>還沒有角色：點「切換角色」建立一個");
        }
        if (ready) {
            header.add("");
            header.add("<yellow>左鍵：在聊天欄列出完整的屬性<dark_gray>（/stats）");
        }
        put(SLOT_HEADER, glowing(icon(ready ? material(character.characterClass().icon(), Material.IRON_SWORD) : Material.PLAYER_HEAD,
                ready ? character.characterClass().color() + ItemRenderer.escape(character.name()) : "<white>" + player.getName(), header)));

        // 【角色】（2026-10：圖示換成比較一看就懂的）
        put(SLOT_PANEL, button(Material.DIAMOND_CHESTPLATE, "角色面板", "/panel", "屬性摘要、武器、防具、飾品的裝備欄"));
        put(SLOT_TREE, button(Material.OAK_SAPLING, "天賦樹", "/tree", ready
                ? "可用天賦點 " + plugin.players().availablePoints(state) + "；配點、退點、搜尋天賦；昇華在天賦總覽裡" : "配點、退點、搜尋天賦"));
        put(SLOT_SKILLS, button(Material.BLAZE_POWDER, "技能", "/skills", "技能、按鍵、輔助寶石、升級"));
        put(SLOT_TITLES, button(Material.NAME_TAG, "稱號", "/title", titleSummary(ready)));
        put(SLOT_CHARACTERS, button(Material.ARMOR_STAND, "切換角色", "/char", "建立、切換、刪除角色"));
        // 【物品】
        final int free = ready ? state.bag().freeSlots() : 0;
        put(SLOT_BAG, button(Material.BUNDLE, "隨身背包", "/bag",
                ready ? "還有 " + free + " 格空位，通貨袋 " + state.bag().pouch().kinds() + " 種通貨" : "每個角色多 90 格＋通貨袋"));
        final boolean inTown = plugin.zones() != null && plugin.zones().inTown(player.getLocation());
        put(SLOT_STASH, button(Material.CHEST, "倉庫", "/stash",
                inTown ? "帳號共用的倉庫" : "只能在營地打開"));
        put(SLOT_FILTER, button(Material.HOPPER, "掉落過濾", "/filter", "哪些東西不撿、直接換成金幣"));
        put(SLOT_TRASH, button(Material.ANVIL, "拆解", "垃圾桶改用 /trash", "不要的裝備拆成金幣與通貨；鎖定裝備也在這裡（/lock）"));
        // 【冒險】
        put(SLOT_WAYPOINTS, button(Material.ENDER_PEARL, "傳送", "/wp", "營地、檢查點、回到剛才的位置"));
        put(SLOT_TOWN, button(Material.CAMPFIRE, "回營地", "/town", "免費、隨時都可以（被打也可以），/wp 可以傳回來"));
        put(SLOT_QUESTS, button(Material.WRITABLE_BOOK, "任務", "/quests", "主線、支線、任務給的永久加成"));
        put(SLOT_BOUNTY, button(Material.TARGET, "委託", "/bounty", "每日委託、每週回響、功勳兌換"));
        put(SLOT_EVENT, button(Material.CLOCK, "每月固定活動", "/event", eventSummary()));
        put(SLOT_PVP, button(Material.DIAMOND_SWORD, "競技場", "/pvp", pvpSummary()));
        put(SLOT_WORLD_EVENT, worldEventButton());
        if (plugin.festival() != null && plugin.festival().visible()) {
            put(SLOT_FESTIVAL, festivalButton());
        }
        if (plugin.life() != null && plugin.life().config().enabled() && plugin.life().canEnter(player)) { // 2026-10：先只開給管理員與名單
            put(SLOT_SEA, button(Material.OAK_BOAT, plugin.life().config().name(), "/sea", "生活玩法：漂在海上撈東西、蓋自己的木筏"));
        }
        // 【多人】
        final var party = plugin.party() == null ? null : plugin.party().party(player.getUniqueId());
        put(SLOT_PARTY, button(Material.PLAYER_HEAD, "隊伍", "/party",
                party == null ? "邀請朋友組隊（最多 " + (plugin.party() == null ? 6 : plugin.party().config().maxSize()) + " 人）"
                        : "隊伍 " + party.size() + " 人；/p 訊息 隊伍聊天"));
        final tw.exilecore.guild.Guild guild = plugin.guilds() == null ? null : plugin.guilds().guildOf(player.getUniqueId());
        put(SLOT_GUILD, button(Material.WHITE_BANNER, "公會", "/guild",
                guild == null ? "建立或加入公會：公會頻道、公會倉庫、公會等級與加成"
                        : "[" + ItemRenderer.escape(guild.tag()) + "] " + ItemRenderer.escape(guild.name()) + "　Lv." + guild.level()
                        + "，" + guild.size() + " 人；/g 訊息 公會頻道"));
        put(SLOT_MARKET, button(Material.EMERALD, "市集", "/market",
                inTown ? "搜尋別人上架的東西、我的商店頁、通貨交易所" : "只能在營地打開"));
        // 最下面一排
        put(SLOT_CODEX, button(Material.KNOWLEDGE_BOOK, "圖鑑", "/codex", "傳奇裝備、技能與輔助寶石、各部位會出的詞綴"));
        put(SLOT_DISCORD, withUiIcon(CommunityLinks.button(plugin, CommunityLinks.Link.DISCORD), "community"));
        put(SLOT_TUTORIAL, CommunityLinks.button(plugin, CommunityLinks.Link.TUTORIAL));
        // 2026-10：有贊助模組就打開贊助（星幣、月卡、全服加倍、外觀特效…），沒有就是商城連結
        put(SLOT_STORE, plugin.sponsors() == null ? CommunityLinks.button(plugin, CommunityLinks.Link.STORE)
                : icon(Material.GOLD_NUGGET, "<#f5b942>贊助", "<gray>星幣 <white>" + String.format(java.util.Locale.ROOT, "%,d",
                        plugin.sponsors().coins(player)), "<gray>月卡、全服加倍、外觀特效、抽獎箱、角色欄位…",
                        "<dark_gray>都不影響戰鬥", "", "<yellow>左鍵：打開<dark_gray>（/sponsor）"));
        put(SLOT_SETTINGS, icon(Material.COMPARATOR, "<#f5b942>設定", "<gray>傷害數字、戰鬥數據、交易邀請、任務追蹤、介面資源包",
                "", "<yellow>左鍵：打開"));
    }

    /** 稱號按鈕的一句話：戴著哪個、擁有幾個。 */
    private String titleSummary(final boolean ready) {
        if (!ready || plugin.bounties() == null) {
            return "擁有的稱號、戴上或拿下、還沒有的怎麼拿";
        }
        final String worn = plugin.bounties().wornTitle(player);
        final int owned = plugin.bounties().ownedTitles(player).size();
        final int all = plugin.bounties().allTitles().size();
        final int bonus = plugin.bounties().titleBonus(player).size();
        return (worn == null ? "沒戴稱號" : "戴著 " + plugin.bounties().titleTag(player) + "<gray>") + "；擁有 " + owned + "／" + all
                + (bonus > 0 ? "，永久加成 " + bonus + " 種" : "");
    }

    /** 每月活動按鈕的一句話：這個月的主題與剩幾天。 */
    private String eventSummary() {
        if (plugin.events() == null || plugin.events().theme() == null) {
            return "這個月的主題、每月挑戰、限定稱號";
        }
        final tw.exilecore.event.EventConfig.Theme theme = plugin.events().theme();
        return "本月：" + theme.name() + "（剩 " + plugin.events().daysLeft() + " 天）；挑戰與限定稱號";
    }

    /** 不定時活動：有事件時發光、寫還剩多久。 */
    private org.bukkit.inventory.ItemStack worldEventButton() {
        final tw.exilecore.worldevent.WorldEventService events = plugin.worldEvents();
        if (events == null) {
            return icon(Material.BELL, "<#f5b942>不定時活動", "<dark_gray>沒有啟用");
        }
        if (events.running()) {
            final long left = events.secondsLeft();
            return glowing(icon(Material.BELL, "<#f5b942>不定時活動：" + events.config().name(),
                    "<green>進行中：" + events.runningKind().displayName() + String.format(Locale.ROOT, "（還剩 %d:%02d）", left / 60, left % 60),
                    "<gray>傳送過去、素材兌換", "", "<yellow>左鍵：打開"));
        }
        final long minutes = events.minutesUntilNext();
        return icon(Material.BELL, "<#f5b942>不定時活動：" + events.config().name(),
                "<gray>活動場地整點出現世界 Boss、入侵怪潮",
                minutes < 0 ? "<gray>等待下一次事件" : events.nextClock() != null && events.config().onTheHour()
                        ? "<gray>下一次 <white>" + events.nextClock() + "</white>（約 " + minutes + " 分鐘後）"
                        : "<gray>下一次大約 " + minutes + " 分鐘後", "<gray>素材兌換限定稱號、極境鑰匙、傳奇",
                "", "<yellow>左鍵：打開");
    }

    /** 節慶活動（2026-10 萬燈夜）：南瓜王在場時發光。 */
    private org.bukkit.inventory.ItemStack festivalButton() {
        final tw.exilecore.festival.FestivalService festival = plugin.festival();
        final tw.exilecore.festival.FestivalConfig config = festival.config();
        final String name = "<" + config.color() + ">🎃 " + ItemRenderer.escape(config.name());
        if (!festival.running()) {
            return icon(Material.CARVED_PUMPKIN, name, "<gray>活動結束了；兌換開到 " + config.format(config.shopUntil()), "",
                    "<yellow>左鍵：打開<dark_gray>（/festival）");
        }
        final String king = festival.kingRunning() ? "<green>南瓜王在幽燈墓園！"
                : festival.nextKingClock() == null ? "<gray>南瓜王：每天固定時段" : "<gray>南瓜王：下一次 <white>" + festival.nextKingClock();
        final org.bukkit.inventory.ItemStack stack = icon(Material.JACK_O_LANTERN, name,
                "<gray>活動到 " + config.format(config.end()) + "：點燈、南瓜王、不給糖就搗蛋", king,
                "<gray>糖果、燈芯兌換限定時裝與稱號", "", "<yellow>左鍵：打開<dark_gray>（/festival）");
        return festival.kingRunning() ? glowing(stack) : stack;
    }

    private static org.bukkit.inventory.ItemStack button(final Material material, final String name, final String command,
                                                         final String description) {
        return icon(material, "<#f5b942>" + name, "<gray>" + description, "", "<yellow>左鍵：打開<dark_gray>（" + command + "）");
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        final PlayerState state = plugin.players().get(player);
        final boolean ready = state.isLoaded() && state.hasCharacter();
        final int button = labelled ? LABELLED_BACK.getOrDefault(slot, -1) : slot;
        switch (button) {
            case SLOT_PANEL -> plugin.characterMenus().openPanel(player);
            case SLOT_TREE -> plugin.characterMenus().openTree(player);
            case SLOT_SKILLS -> {
                if (plugin.skills() == null) {
                    unavailable("技能");
                } else if (!ready) {
                    needCharacter();
                } else {
                    new SkillMenu(plugin, player, plugin.skills()).open();
                }
            }
            case SLOT_BAG -> {
                if (plugin.loot() == null) {
                    unavailable("掉落");
                } else {
                    plugin.loot().openBag(player);
                }
            }
            case SLOT_FILTER -> {
                if (plugin.loot() == null) {
                    unavailable("掉落");
                } else {
                    plugin.loot().openFilter(player);
                }
            }
            case SLOT_STASH -> {
                if (plugin.stash() == null) {
                    unavailable("倉庫");
                } else {
                    player.closeInventory();
                    plugin.stash().open(player, false);
                }
            }
            case SLOT_WAYPOINTS -> {
                if (plugin.zones() == null) {
                    unavailable("區域");
                } else {
                    new WaypointMenu(plugin, player).open();
                }
            }
            case SLOT_TOWN -> {
                if (plugin.zones() == null) {
                    unavailable("區域");
                } else {
                    player.closeInventory();
                    plugin.zones().goTown(player);
                }
            }
            case SLOT_PVP -> {
                if (plugin.pvp() == null) {
                    unavailable("競技場");
                } else {
                    new tw.exilecore.pvp.PvpMenu(plugin, player, plugin.pvp()).open();
                }
            }
            case SLOT_HEADER -> {
                player.closeInventory();
                for (final String line : StatsReport.all(plugin, player)) {
                    player.sendMessage(plugin.text().parse(line));
                }
            }
            case SLOT_PARTY -> {
                if (plugin.party() == null) {
                    unavailable("隊伍");
                } else {
                    new tw.exilecore.party.PartyMenu(plugin, player, plugin.party()).open();
                }
            }
            case SLOT_GUILD -> {
                if (plugin.guilds() == null) {
                    unavailable("公會");
                } else if (!ready) {
                    needCharacter();
                } else {
                    plugin.guilds().openMenu(player);
                }
            }
            case SLOT_CHARACTERS -> plugin.characterMenus().openSelect(player);
            case SLOT_MARKET -> tw.exilecore.market.MarketModule.open(plugin, player);
            case SLOT_QUESTS -> {
                if (plugin.quests() == null) {
                    unavailable("任務");
                } else if (!ready) {
                    needCharacter();
                } else {
                    new tw.exilecore.quest.QuestMenu(plugin, player, plugin.quests()).open();
                }
            }
            case SLOT_SEA -> {
                if (plugin.life() != null && plugin.life().config().enabled() && plugin.life().canEnter(player)) {
                    tw.exilecore.life.LifeModule.open(plugin, player);
                }
            }
            case SLOT_FESTIVAL -> {
                if (plugin.festival() != null && plugin.festival().visible()) {
                    tw.exilecore.festival.FestivalModule.open(plugin, player);
                }
            }
            case SLOT_WORLD_EVENT -> {
                if (plugin.worldEvents() == null) {
                    unavailable("不定時活動");
                } else {
                    new tw.exilecore.worldevent.WorldEventMenu(plugin, player, plugin.worldEvents()).open();
                }
            }
            case SLOT_EVENT -> {
                if (plugin.events() == null) {
                    unavailable("每月固定活動");
                } else if (!ready) {
                    needCharacter();
                } else {
                    new tw.exilecore.event.EventMenu(plugin, player, plugin.events()).open();
                }
            }
            case SLOT_BOUNTY -> {
                if (plugin.bounties() == null) {
                    unavailable("委託");
                } else if (!ready) {
                    needCharacter();
                } else {
                    plugin.bounties().open(player);
                }
            }
            case SLOT_TITLES -> {
                if (plugin.bounties() == null) {
                    unavailable("委託");
                } else if (!ready) {
                    needCharacter();
                } else {
                    plugin.bounties().openTitles(player);
                }
            }
            case SLOT_TRASH -> {
                if (!ready) {
                    needCharacter();
                } else {
                    new tw.exilecore.salvage.SalvageMenu(plugin, player).open(); // 2026-10：銷毀改成拆解（垃圾桶還是 /trash）
                }
            }
            case SLOT_CODEX -> CodexMenu.open(plugin, player);
            case SLOT_SETTINGS -> new SettingsMenu(plugin, player).open();
            case SLOT_DISCORD -> {
                player.closeInventory();
                CommunityLinks.send(plugin, player, CommunityLinks.Link.DISCORD);
            }
            case SLOT_TUTORIAL -> {
                player.closeInventory();
                CommunityLinks.send(plugin, player, CommunityLinks.Link.TUTORIAL);
            }
            case SLOT_STORE -> {
                if (plugin.sponsors() != null) {
                    tw.exilecore.sponsor.SponsorModule.openMenu(plugin, player);
                    return;
                }
                player.closeInventory();
                CommunityLinks.send(plugin, player, CommunityLinks.Link.STORE);
            }
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }

    /** 競技場按鈕的一句話：場上幾個人、PvP 減傷。 */
    private String pvpSummary() {
        final tw.exilecore.pvp.PvpService pvp = plugin.pvp();
        if (pvp == null) {
            return "玩家互相攻擊的場地";
        }
        return "玩家互相攻擊・場上 " + pvp.playerCount() + " 人・PvP 減傷 " + Math.round(pvp.config().reduction()) + "%";
    }

    private void unavailable(final String module) {
        player.sendMessage(plugin.text().prefixed("<red>" + module + "模組沒有啟用（看主控台的錯誤訊息）"));
    }

    private void needCharacter() {
        player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
    }
}
