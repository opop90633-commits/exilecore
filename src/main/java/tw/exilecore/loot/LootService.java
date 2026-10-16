package tw.exilecore.loot;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import tw.exilecore.ExileCore;
import tw.exilecore.character.Bag;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.flask.FlaskBase;
import tw.exilecore.flask.FlaskService;
import tw.exilecore.item.Rarity;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 掉落（階段 3 批次 3）：玩家殺的怪物掉的東西先過濾（隱藏的換成金幣），顯示的直接收進背包
 * （通貨進通貨袋，其他先放背包主要格子、再放隨身背包，都滿了才掉在地上），金幣直接加進角色。
 * 撿到幾件、多少金幣寫在動作列；值得注意的東西列在畫面右邊側欄的「最近撿到」（不洗聊天欄）。
 * 也管隨身背包與過濾器的視窗。
 */
public final class LootService {

    /** 提示在動作列上停多久（tick）。 */
    private static final long NOTICE_TICKS = 50;
    /** 背包主要格子（快捷列以外）。 */
    private static final int MAIN_START = 9;
    private static final int MAIN_END = 36;

    private final ExileCore plugin;
    private final LootConfig config;
    private final Map<UUID, BagMenu> openBags = new HashMap<>();
    /** 這次登入過濾器隱藏了幾件、換了多少金幣（過濾器選單上看；下線就清）。 */
    private final Map<UUID, long[]> filterStats = new HashMap<>();

    public LootService(final ExileCore plugin, final LootConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public LootConfig config() {
        return config;
    }

    // ---- 過濾器 ----

    /** 這個角色目前的過濾器（沒設定過用預設組合）。 */
    public LootFilter filter(final PlayerState state) {
        final CharacterRecord character = state.character();
        return LootFilter.decode(character == null ? null : character.lootFilter(), config).withBaseTags(this::baseTags);
    }

    /** 基底代號的標籤（「只撿的基底」看有挑的是哪幾類；不認得的基底回傳空的）。 */
    private java.util.Set<String> baseTags(final String baseId) {
        final tw.exilecore.item.ItemBase base = plugin.items() == null ? null : plugin.items().repository().base(baseId);
        return base == null ? java.util.Set.of() : base.tags();
    }

    public LootFilter filter(final Player player) {
        return filter(plugin.players().get(player));
    }

    /** 換過濾器（寫進角色，跟著下一次存檔存起來）。 */
    public void setFilter(final Player player, final LootFilter filter) {
        final CharacterRecord character = plugin.players().get(player).character();
        if (character != null) {
            character.setLootFilter(filter.encode());
        }
    }

    // ---- 擊殺掉落 ----

    /**
     * 處理玩家殺的一隻怪物的掉落。玩家沒有角色時回傳 false（呼叫端照舊掉在地上）。
     *
     * @param drops   擲出來的掉落
     * @param gold    怪物掉的金幣
     * @param toStack 掉落做成物品（模組沒啟用的種類回傳 null）
     */
    public boolean handleKill(final Player killer, final Location location, final MonsterData data,
                              final List<DropTable.Drop> drops, final long gold,
                              final Function<DropTable.Drop, ItemStack> toStack) {
        final PlayerState state = plugin.players().get(killer);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return false;
        }
        final LootFilter filter = filter(state);
        final LootFilter.Viewer viewer = viewer(killer, state);
        final double averageGold = plugin.monsterModule().drops().averageGold(data.level());
        final int bagFreeBefore = state.bag().freeSlots();
        final tw.exilecore.economy.EconomyLedger ledger = plugin.economy().ledger();
        long goldTotal = gold;
        long filterGold = 0;
        long currencyValue = 0;
        int filtered = 0;
        int picked = 0;
        boolean feedChanged = false;
        int onGround = 0;
        int soundTier = SOUND_NONE; // 2026-10：稀有掉落音效（取這次最高的那一級）
        final boolean endgameBoss = endgameBoss(location, data);
        final List<tw.exilecore.gui.SidebarLayout.Item> notable = new ArrayList<>();
        for (final DropTable.Drop drop : drops) {
            final ItemStack stack = toStack.apply(drop);
            if (stack == null) {
                continue; // 技能或藥劑模組沒啟用
            }
            // 專屬的輔助寶石（終局、極境）當成傳奇看：永遠不藏、值得提示
            final Rarity rarity = drop.isEquipment() ? drop.item().rarity() : drop.isSpecificGem() ? Rarity.UNIQUE : null;
            final CurrencyType currency = drop.isCurrency() ? drop.currency() : null;
            // 未切割寶石（2026-10 算通貨）：照通貨記流水帳、收進通貨袋（過濾與提示照寶石的規則）
            final CurrencyType asCurrency = currency != null ? currency : gemCurrency(drop, stack);
            final int weight = currency == null ? 0 : currency.dropWeight();
            final boolean upgrade = drop.kind() == DropTable.Kind.FLASK && isFlaskUpgrade(killer, stack);
            // 傳奇與珠寶永遠不會被過濾掉；地圖只看「地圖依稀有度隱藏」（LootFilter 自己判斷）。地圖與珠寶都值得提示
            final boolean map = drop.isEquipment() && (isMap(drop.item()) || isJewel(drop.item()));
            final boolean charm = drop.kind() == DropTable.Kind.FLASK && plugin.flasks() != null && plugin.flasks().charms() != null
                    && plugin.flasks().charms().isCharm(stack);
            // 映星鏡（2026-10）：永遠不會被過濾掉（就算自己把它勾成隱藏）
            final boolean mirror = currency != null && currency.operation() == tw.exilecore.currency.CurrencyOperation.MIRROR;
            if (!mirror && (charm ? filter.hidesCharms() : filter.hides(candidate(drop, stack, rarity, currency, upgrade), viewer))) {
                final long converted = config.goldValue(drop.kind(), rarity, weight, averageGold);
                goldTotal += converted;
                filterGold += converted;
                filtered++;
                continue;
            }
            if (asCurrency != null) {
                // 流水帳：撿到的（或掉在地上、只有他撿得到的）通貨都算產出
                ledger.produced(asCurrency.id(), tw.exilecore.economy.EconomyLedger.DROP, stack.getAmount());
                currencyValue += plugin.economy().config().value(asCurrency.id()) * stack.getAmount();
            }
            final boolean worthNoting = map || drop.isSpecificGem() || config.notable(drop.kind(), rarity, weight,
                    currency == null ? null : currency.id());
            soundTier = Math.max(soundTier, soundTier(drop, rarity, asCurrency, mirror, endgameBoss));
            if (drop.isEquipment() && drop.item().unique() != null) {
                broadcastChase(killer, drop.item());
            }
            if (mirror) {
                broadcastMirror(killer, currency);
            }
            if (!config.autoPickup()) {
                MonsterListener.dropOwned(plugin, location, stack, killer, MonsterListener.highlight(drop));
                continue;
            }
            final tw.exilecore.gui.SidebarLayout.Item name = sidebarItem(stack, drop, asCurrency);
            final ItemStack rest = asCurrency != null ? stowCurrency(killer, state, asCurrency, stack) : stow(killer, state, stack);
            if (rest != null && !rest.isEmpty()) {
                MonsterListener.dropOwned(plugin, location, rest, killer, true);
                onGround++;
            }
            if (rest == null || rest.getAmount() < stack.getAmount()) {
                picked++;
                // 2026-10：撿到東西的角落提示（快捷欄右邊）
                final int got = stack.getAmount() - (rest == null ? 0 : rest.getAmount());
                plugin.players().lootFeed().add(killer.getUniqueId(), net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(name.text())), got, name.icon(),
                        name.color(), plugin.getServer().getCurrentTick());
                feedChanged = true;
            }
            if (worthNoting) {
                if (!(drop.isEquipment() && isMap(drop.item()))) {
                    notable.add(name); // 2026-10：地圖不放進「最近撿到」的卡片（音效照舊）
                }
            }
        }
        if (filtered > 0) {
            final long[] stats = filterStats.computeIfAbsent(killer.getUniqueId(), uuid -> new long[2]);
            stats[0] += filtered;
            stats[1] += filterGold;
            addToday(killer, filtered, filterGold);
        }
        if (goldTotal > 0) {
            if (config.autoPickup()) {
                if (plugin.gold().add(killer, goldTotal)) {
                    // 流水帳分開記：怪物的金幣、過濾器換的金幣
                    ledger.gold(tw.exilecore.economy.EconomyLedger.DROP, goldTotal - filterGold);
                    ledger.gold(tw.exilecore.economy.EconomyLedger.FILTER, filterGold);
                }
            } else {
                plugin.gold().drop(location, goldTotal, killer); // 撿起來的時候才記（GoldListener）
            }
        }
        ledger.income(killer, 1, goldTotal, currencyValue);
        if (feedChanged) {
            plugin.players().display(killer, state); // 角落提示馬上出現
        }
        final boolean intoBag = state.bag().freeSlots() < bagFreeBefore;
        announce(killer, state, picked, intoBag, onGround, filtered, config.autoPickup() ? goldTotal : 0, filterGold, new ArrayList<>(notable),
                filter.dropSounds() ? soundTier : SOUND_NONE);
        return true;
    }

    /**
     * 不是殺怪來的獎勵（幻霧散掉時給的那一堆，DeliriumEvents）也照掉落過濾（2026-10：以前直接塞進背包，微光石之類的藏不掉）：
     * 被過濾的通貨、裝備換成金幣（跟殺怪一樣的價錢），回傳要給的。玩家自己挑的獎勵（儀式）不要用這個。
     */
    public List<ItemStack> filterRewards(final Player player, final List<ItemStack> items, final int level) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return items;
        }
        final LootFilter filter = filter(state);
        final LootFilter.Viewer viewer = viewer(player, state);
        final double averageGold = plugin.monsterModule().drops().averageGold(Math.max(1, level));
        final List<ItemStack> kept = new ArrayList<>();
        long gold = 0;
        int filtered = 0;
        for (final ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final boolean gem = plugin.skills() != null && plugin.skills().gems().read(stack).isPresent();
            final CurrencyType currency = gem || plugin.currencies() == null ? null : plugin.currencies().read(stack).orElse(null);
            if (currency != null) {
                // 映星鏡永遠不會被過濾掉（跟 handleKill 一樣）；金幣照一疊算（2026-10）
                if (currency.operation() != tw.exilecore.currency.CurrencyOperation.MIRROR && filter.hidesCurrency(currency.id())) {
                    gold += config.goldValue(DropTable.Kind.CURRENCY, null, currency.dropWeight(), averageGold);
                    filtered++;
                    continue;
                }
            } else if (!gem) {
                final tw.exilecore.item.ItemData item = plugin.items().read(stack).orElse(null);
                final tw.exilecore.item.ItemBase base = item == null ? null : plugin.items().repository().base(item.base());
                if (item != null && base != null) {
                    final LootFilter.Candidate candidate = new LootFilter.Candidate(DropTable.Kind.EQUIPMENT, item.rarity(), null, false,
                            item.ilvl(), base.tags(), base.isMap() ? tw.exilecore.item.ItemStats.mapTier(base, item) : 0, 0,
                            bestAffixRank(item), item.base());
                    if (filter.hides(candidate, viewer)) {
                        gold += config.goldValue(DropTable.Kind.EQUIPMENT, item.rarity(), 0, averageGold);
                        filtered++;
                        continue;
                    }
                }
            }
            kept.add(stack);
        }
        if (filtered > 0) {
            // 2026-10：以前只算進「今天」，「這次登入」少算了獎勵被過濾的部分
            final long[] stats = filterStats.computeIfAbsent(player.getUniqueId(), uuid -> new long[2]);
            stats[0] += filtered;
            stats[1] += gold;
            addToday(player, filtered, gold);
        }
        if (gold > 0 && plugin.gold().add(player, gold)) {
            plugin.economy().ledger().gold(tw.exilecore.economy.EconomyLedger.FILTER, gold);
            player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<gray>掉落過濾：%d 件換成 <#f5b942>%,d 金幣", filtered, gold)));
        }
        return kept;
    }

    /** 怪物掉的未切割寶石當通貨是哪一種（不是未切割寶石、專屬的輔助寶石回傳 null）。 */
    private CurrencyType gemCurrency(final DropTable.Drop drop, final ItemStack stack) {
        if ((drop.kind() != DropTable.Kind.SKILL_GEM && drop.kind() != DropTable.Kind.SUPPORT_GEM) || drop.isSpecificGem()) {
            return null;
        }
        return plugin.currencies() == null ? null : plugin.currencies().read(stack).orElse(null);
    }

    /** 動作列摘要、右邊側欄的「最近撿到」列出值得注意的東西、音效。 */
    /** 追逐級傳奇掉出來：全服都看得到是誰、掉了什麼（PoE 的伺服器習慣；極稀有才值得吵大家）。 */
    public void broadcastChase(final Player finder, final tw.exilecore.item.ItemData item) {
        final tw.exilecore.item.UniqueItem unique = plugin.items().repository().unique(item.unique());
        if (unique == null || !unique.chase()) {
            return;
        }
        final String name = plugin.chat() == null ? finder.getName() : plugin.chat().display(finder);
        final net.kyori.adventure.text.Component message = plugin.text().prefixed("<#ffb347>✦ " + tw.exilecore.item.ItemRenderer.escape(name)
                + " <gold>找到了追逐級傳奇 <#ffb347><bold>" + tw.exilecore.item.ItemRenderer.escape(unique.name()) + "</bold><gold>！");
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(message);
            online.playSound(online.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, org.bukkit.SoundCategory.PLAYERS, 0.7f, 1.2f);
        }
        plugin.getSLF4JLogger().info("追逐級傳奇：{} 掉出 {}（{}）", finder.getName(), unique.name(), unique.id());
        if (plugin.discord() != null) {
            plugin.discord().achievement(tw.exilecore.item.ItemRenderer.escape(name) + " 找到了追逐級傳奇「" + tw.exilecore.item.ItemRenderer.escape(unique.name()) + "」！");
        }
    }

    /** 映星鏡掉出來：跟追逐級傳奇一樣全服廣播。 */
    public void broadcastMirror(final Player finder, final CurrencyType mirror) {
        final String name = plugin.chat() == null ? finder.getName() : plugin.chat().display(finder);
        final net.kyori.adventure.text.Component message = plugin.text().prefixed("<#9fd8ff>✦ " + tw.exilecore.item.ItemRenderer.escape(name)
                + " <aqua>找到了 <#9fd8ff><bold>" + tw.exilecore.item.ItemRenderer.escape(mirror.name()) + "</bold><aqua>！");
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(message);
            online.playSound(online.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, org.bukkit.SoundCategory.PLAYERS, 0.7f, 1.2f);
        }
        plugin.getSLF4JLogger().info("映星鏡：{} 掉出 {}（{}）", finder.getName(), mirror.name(), mirror.id());
        if (plugin.discord() != null) {
            plugin.discord().achievement(tw.exilecore.item.ItemRenderer.escape(name) + " 找到了「" + tw.exilecore.item.ItemRenderer.escape(mirror.name()) + "」！");
        }
    }

    // ---- 稀有掉落音效（2026-10；過濾器可以關） ----

    static final int SOUND_NONE = 0;
    /** Boss 入場材料、大鑿星石、Lv.21 技能寶石。 */
    static final int SOUND_CURRENCY = 1;
    /** 傳奇（含專屬寶石）。 */
    static final int SOUND_UNIQUE = 2;
    /** 終局、極境、巔峰、金庫之主的 Boss 掉的傳奇與專屬寶石（通貨、地圖不算，照一般規則）。 */
    static final int SOUND_ENDGAME = 3;
    /** 追逐級傳奇、映星鏡。 */
    static final int SOUND_CHASE = 4;

    /** 有音效的通貨：Boss 入場材料（天穹碎片、深淵星核、萬界之巔四種碎片、金庫之主的鑰印）、劫盜藍圖、大鑿星石、Lv.21 技能寶石。 */
    static final java.util.Set<String> SOUND_CURRENCIES = java.util.Set.of("sky_fragment", "abyss_core", "fragment_crucible", "fragment_frost",
            "fragment_bone", "fragment_ember", "heist_finale_key", "heist_blueprint", "greater_socket_orb", "uncut_skill_21");

    /** 這一件掉落是哪一級的音效。 */
    private int soundTier(final DropTable.Drop drop, final Rarity rarity, final CurrencyType currency, final boolean mirror, final boolean endgameBoss) {
        if (mirror) {
            return SOUND_CHASE;
        }
        if (drop.isEquipment() && drop.item().unique() != null) {
            final tw.exilecore.item.UniqueItem unique = plugin.items().repository().unique(drop.item().unique());
            if (unique != null && unique.chase()) {
                return SOUND_CHASE;
            }
        }
        if (rarity == Rarity.UNIQUE) {
            // 專屬寶石（終局、極境、巔峰的輔助／技能寶石、劫盜的異質寶石）也當成傳奇（rarity 是 UNIQUE）
            return endgameBoss ? SOUND_ENDGAME : SOUND_UNIQUE;
        }
        return currency != null && SOUND_CURRENCIES.contains(currency.id()) ? SOUND_CURRENCY : SOUND_NONE;
    }

    /** 這隻是不是終局以上的 Boss（終局、極境、巔峰的 Boss；金庫之主的寶庫裡的掉落也算）。 */
    private boolean endgameBoss(final Location location, final MonsterData data) {
        if (plugin.maps() == null || location == null) {
            return false;
        }
        final tw.exilecore.map.MapInstance instance = plugin.maps().instanceAt(location);
        if (instance == null) {
            return false;
        }
        return instance.singleEntry() || data != null && data.boss() && (instance.map().isFinal() || instance.isPinnacle());
    }

    /** 每一級不同的音效（越稀有越響）。 */
    private static void playDropSound(final Player player, final int tier) {
        final Location at = player.getLocation();
        switch (tier) {
            case SOUND_CHASE -> {
                player.playSound(at, Sound.BLOCK_END_PORTAL_SPAWN, org.bukkit.SoundCategory.PLAYERS, 0.5f, 1.3f);
                player.playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, org.bukkit.SoundCategory.PLAYERS, 0.8f, 1.0f);
            }
            case SOUND_ENDGAME -> {
                player.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, org.bukkit.SoundCategory.PLAYERS, 0.9f, 1.1f);
                player.playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, org.bukkit.SoundCategory.PLAYERS, 0.5f, 1.2f);
            }
            case SOUND_UNIQUE -> player.playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, org.bukkit.SoundCategory.PLAYERS, 0.6f, 1.4f);
            case SOUND_CURRENCY -> {
                player.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, org.bukkit.SoundCategory.PLAYERS, 0.5f, 1.8f);
                player.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, org.bukkit.SoundCategory.PLAYERS, 0.8f, 1.4f);
            }
            default -> {
            }
        }
    }

    /** 過濾換金幣那張卡片的圖示（通貨的圖：金幣沒有自己的圖，用這個）。 */
    private static final String FILTER_ICON = "glimmer_orb";

    private void announce(final Player player, final PlayerState state, final int picked, final boolean intoBag, final int onGround,
                          final int filtered, final long gold, final long filterGold, final List<tw.exilecore.gui.SidebarLayout.Item> notable,
                          final int soundTier) {
        // 2026-10：動作列不再寫「撿到幾件」「+金幣」（值得注意的東西在右邊的卡片；過濾換的金幣也寫在卡片上），只留要注意的警告
        final StringBuilder text = new StringBuilder();
        if (intoBag && picked > 0) {
            text.append("<gray>背包滿了，放進隨身背包");
        }
        final boolean hadNotable = !notable.isEmpty();
        if (filtered > 0 && filterGold > 0) {
            // 過濾換的金幣：一張卡片一直累加（同一張卡片數量就是金幣，畫成「+N 金幣」）
            notable.add(new tw.exilecore.gui.SidebarLayout.Item("<#f5b942>過濾換成金幣", (int) Math.min(Integer.MAX_VALUE, filterGold),
                    "$c:" + FILTER_ICON, "#f5b942"));
        }
        if (onGround > 0) {
            text.append("  <red>背包滿了，").append(onGround).append(" 件掉在地上");
        }
        if (!text.isEmpty()) {
            state.notice(text.toString(), plugin.getServer().getCurrentTick() + NOTICE_TICKS);
            plugin.players().display(player, state);
        }
        // 2026-10：右邊側欄的「最近撿到」拿掉（改成快捷欄右邊的角落提示，LootFeed）；過濾換的金幣也放進角落提示
        if (filtered > 0 && filterGold > 0) {
            plugin.players().lootFeed().add(player.getUniqueId(), "過濾換成金幣", (int) Math.min(Integer.MAX_VALUE, filterGold),
                    "c:" + FILTER_ICON, "#f5b942", plugin.getServer().getCurrentTick());
            plugin.players().display(player, state);
        }
        if (soundTier > SOUND_NONE) {
            playDropSound(player, soundTier);
        } else if (hadNotable) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
        } else if (picked > 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.4f);
        } else if (gold > 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.8f);
        }
    }

    /** 側欄「最近撿到」的一行：物品的名字（稀有度的顏色，MiniMessage）、數量、卡片的圖示與框的顏色。 */
    private tw.exilecore.gui.SidebarLayout.Item sidebarItem(final ItemStack stack, final DropTable.Drop drop, final CurrencyType currency) {
        final Component name = tw.exilecore.chat.ChatService.itemName(stack);
        String icon = tw.exilecore.web.LootCards.GENERIC;
        String color = "#c8c8c8";
        if (currency != null) {
            final String art = tw.exilecore.currency.CurrencyArt.artOf(currency);
            icon = art == null ? icon : "c:" + art;
            color = currency.dropWeight() <= 50 ? "#ffb347" : "#d8c9a3"; // 很少見的通貨：橘色框
        } else if (drop.isEquipment()) {
            final tw.exilecore.item.ItemData item = drop.item();
            color = item.rarity().color();
            if (isJewel(item)) {
                icon = tw.exilecore.web.LootCards.JEWEL;
            } else if (item.unique() != null && tw.exilecore.item.UniqueArt.has(item.unique())) {
                icon = "u:" + item.unique();
            }
            if (item.unique() != null) {
                final tw.exilecore.item.UniqueItem unique = plugin.items().repository().unique(item.unique());
                if (unique != null && unique.chase()) {
                    color = "#ff7a1f"; // 追逐級：亮橘色
                }
            }
        } else if (drop.kind() == DropTable.Kind.SUPPORT_GEM || drop.kind() == DropTable.Kind.SKILL_GEM) {
            icon = "c:" + (drop.kind() == DropTable.Kind.SUPPORT_GEM ? "gem_support" : "gem_skill");
            color = "#5fd3c8";
        }
        return new tw.exilecore.gui.SidebarLayout.Item(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(name),
                stack.getAmount(), icon, color);
    }

    /** 藥劑比藥劑欄裡同一種（生命／魔力）的好（需求等級比較高）；藥劑欄沒有同一種的也算。 */
    private boolean isFlaskUpgrade(final Player player, final ItemStack stack) {
        final FlaskService flasks = plugin.flasks();
        if (flasks == null) {
            return true;
        }
        final FlaskBase base = flasks.read(stack).map(data -> flasks.config().base(data.base())).orElse(null);
        if (base == null) {
            return true;
        }
        int best = -1;
        for (final int slot : flasks.slots(player)) {
            final FlaskBase held = flasks.read(player.getInventory().getItem(slot))
                    .map(data -> flasks.config().base(data.base())).orElse(null);
            if (held != null && held.kind() == base.kind()) {
                best = Math.max(best, held.level());
            }
        }
        return base.level() > best;
    }

    // ---- 收進背包 ----

    /**
     * 把一件東西收起來：藥劑先看那一種的藥劑欄空著就直接放進去（照 PoE）；
     * 其他先疊到背包裡同樣的東西上，再放背包主要格子的空格（快捷列不動），
     * 再放隨身背包（開著的那一頁不動，視窗關掉時會用畫面蓋回去）。
     *
     * @return 放不下的部分（全部放下回傳 null）
     */
    public ItemStack stow(final Player player, final PlayerState state, final ItemStack stack) {
        if (plugin.flasks() != null && plugin.flasks().equipIfEmpty(player, stack)) {
            return null;
        }
        ItemStack rest = stack.clone();
        final PlayerInventory inventory = player.getInventory();
        rest = mergeInto(inventory, 0, MAIN_END, rest);
        rest = fillEmpty(inventory, MAIN_START, MAIN_END, rest);
        if (rest == null) {
            return null;
        }
        final Bag bag = state.bag();
        if (!bag.usable()) {
            return rest; // 隨身背包的存檔讀不出來：這次登入不放進去
        }
        final int skip = displayedPage(player);
        for (int page = 0; page < bag.pages() && rest != null; page++) {
            if (page != skip) {
                rest = mergeInto(bag.page(page), rest);
            }
        }
        for (int page = 0; page < bag.pages() && rest != null; page++) {
            if (page != skip) {
                rest = fillEmpty(bag.page(page), rest);
            }
        }
        return rest;
    }

    /**
     * 整件收得下嗎（照 {@link #stow} 的順序在複本上算一次，不會真的放）。
     * 市集、商人先用這個檢查，收得下才收錢，就不會只放進去一部分。
     */
    public boolean fits(final Player player, final PlayerState state, final ItemStack stack) {
        if (plugin.flasks() != null && plugin.flasks().canEquip(player, stack)) {
            return true; // 藥劑：藥劑欄空著就放那裡
        }
        final PlayerInventory inventory = player.getInventory();
        final ItemStack[] main = new ItemStack[MAIN_END];
        for (int slot = 0; slot < MAIN_END; slot++) {
            main[slot] = inventory.getItem(slot); // 只換陣列裡的元素、不改物品本身，所以不用複製
        }
        ItemStack rest = mergeInto(main, stack.clone());
        if (rest != null) {
            rest = fillEmpty(Arrays.copyOfRange(main, MAIN_START, MAIN_END), rest);
        }
        if (rest == null) {
            return true;
        }
        final Bag bag = state.bag();
        if (!bag.usable()) {
            return false;
        }
        final int skip = displayedPage(player);
        final ItemStack[][] pages = new ItemStack[bag.pages()][];
        for (int page = 0; page < bag.pages(); page++) {
            pages[page] = bag.page(page).clone();
        }
        for (int page = 0; page < bag.pages() && rest != null; page++) {
            if (page != skip) {
                rest = mergeInto(pages[page], rest);
            }
        }
        for (int page = 0; page < bag.pages() && rest != null; page++) {
            if (page != skip) {
                rest = fillEmpty(pages[page], rest);
            }
        }
        return rest == null;
    }

    /**
     * 通貨袋收得下多少就收多少，多的直接丟掉（萬燈糖：超過上限自動過濾，不進背包、不掉在地上；2026-10-16）。
     * 回傳收了幾個；隨身背包打不開時回傳 -1（照一般通貨收）。
     */
    public int depositOrDiscard(final Player player, final PlayerState state, final CurrencyType type, final int amount) {
        if (state == null || !state.bag().usable()) {
            return -1;
        }
        final int accepted = state.bag().pouch().deposit(type.id(), Math.max(0, amount));
        refreshCurrency(player);
        return accepted;
    }

    /** 通貨：先進通貨袋（每種最多 5000 個），滿了照一般物品收。 */
    public ItemStack stowCurrency(final Player player, final PlayerState state, final CurrencyType type, final ItemStack stack) {
        if (!state.bag().usable()) {
            return stow(player, state, stack); // 隨身背包打不開：通貨也放背包，不然拿不出來
        }
        final int accepted = state.bag().pouch().deposit(type.id(), stack.getAmount());
        refreshCurrency(player);
        if (accepted >= stack.getAmount()) {
            return null;
        }
        return stow(player, state, stack.asQuantity(stack.getAmount() - accepted));
    }

    private static ItemStack mergeInto(final PlayerInventory inventory, final int from, final int to, final ItemStack stack) {
        ItemStack rest = stack;
        for (int slot = from; slot < to && rest != null; slot++) {
            final ItemStack current = inventory.getItem(slot);
            if (current == null || current.isEmpty() || !current.isSimilar(rest)) {
                continue;
            }
            final int room = current.getMaxStackSize() - current.getAmount();
            if (room <= 0) {
                continue;
            }
            final int moved = Math.min(room, rest.getAmount());
            inventory.setItem(slot, current.asQuantity(current.getAmount() + moved));
            rest = moved >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - moved);
        }
        return rest;
    }

    private static ItemStack fillEmpty(final PlayerInventory inventory, final int from, final int to, final ItemStack stack) {
        ItemStack rest = stack;
        for (int slot = from; slot < to && rest != null; slot++) {
            final ItemStack current = inventory.getItem(slot);
            if (current != null && !current.isEmpty()) {
                continue;
            }
            final int moved = Math.min(rest.getMaxStackSize(), rest.getAmount());
            inventory.setItem(slot, rest.asQuantity(moved));
            rest = moved >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - moved);
        }
        return rest;
    }

    /** 疊到一頁裡同樣的東西上。 */
    public static ItemStack mergeInto(final ItemStack[] slots, final ItemStack stack) {
        ItemStack rest = stack;
        for (int i = 0; i < slots.length && rest != null; i++) {
            final ItemStack current = slots[i];
            if (current == null || current.isEmpty() || !current.isSimilar(rest)) {
                continue;
            }
            final int room = current.getMaxStackSize() - current.getAmount();
            if (room <= 0) {
                continue;
            }
            final int moved = Math.min(room, rest.getAmount());
            slots[i] = current.asQuantity(current.getAmount() + moved);
            rest = moved >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - moved);
        }
        return rest;
    }

    /** 放進一頁的空格。 */
    public static ItemStack fillEmpty(final ItemStack[] slots, final ItemStack stack) {
        ItemStack rest = stack;
        for (int i = 0; i < slots.length && rest != null; i++) {
            if (slots[i] != null && !slots[i].isEmpty()) {
                continue;
            }
            final int moved = Math.min(rest.getMaxStackSize(), rest.getAmount());
            slots[i] = rest.asQuantity(moved);
            rest = moved >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - moved);
        }
        return rest;
    }

    // ---- 視窗 ----

    /** 打開隨身背包。 */
    public void openBag(final Player player) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return;
        }
        if (plugin.characters().isBusy(player)) {
            player.sendMessage(plugin.text().prefixed("<gray>角色資料處理中，請稍等"));
            return;
        }
        if (!state.bag().usable()) {
            player.sendMessage(plugin.text().prefixed("<red>隨身背包的資料讀取失敗，暫停使用，請通知管理員"));
            return;
        }
        if (openBags.containsKey(player.getUniqueId()) || player.getOpenInventory().getTopInventory().getHolder() instanceof BagMenu) {
            return; // 已經開著：不開第二個，避免兩個畫面同時拿著同一個背包
        }
        new BagMenu(plugin, player, this, state.bag()).open();
    }

    /** 打開隨身背包的通貨袋、看某一組（雕琢、切割完從通貨袋回來）。 */
    public void openBagCurrency(final Player player, final tw.exilecore.currency.CurrencyGroups.Group group) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter() || plugin.characters().isBusy(player) || !state.bag().usable()
                || openBags.containsKey(player.getUniqueId()) || player.getOpenInventory().getTopInventory().getHolder() instanceof BagMenu) {
            openBag(player); // 有問題的話照一般的打開（會說原因）
            return;
        }
        new BagMenu(plugin, player, this, state.bag(), BagMenu.CURRENCY_PAGE, group).open();
    }

    /** 打開過濾器設定。 */
    public void openFilter(final Player player) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return;
        }
        if (plugin.characters().isBusy(player)) {
            player.sendMessage(plugin.text().prefixed("<gray>角色資料處理中，請稍等"));
            return;
        }
        new LootFilterMenu(plugin, player, this).open();
    }

    void opened(final Player player, final BagMenu menu) {
        openBags.put(player.getUniqueId(), menu);
    }

    void closed(final Player player, final BagMenu menu) {
        openBags.remove(player.getUniqueId(), menu);
    }

    /** 隨身背包正開著哪一頁（沒開、或開著通貨袋回傳 -1）。 */
    private int displayedPage(final Player player) {
        final BagMenu menu = openBags.get(player.getUniqueId());
        return menu == null ? -1 : menu.displayedItemPage();
    }

    /** 開著通貨袋那一頁的話重畫（數量變了）。 */
    private void refreshCurrency(final Player player) {
        final BagMenu menu = openBags.get(player.getUniqueId());
        if (menu != null) {
            menu.refreshCurrency();
        }
    }

    /** 存檔前：開著的隨身背包把畫面上的那一頁同步回背包。 */
    public void syncOpenBag(final Player player) {
        final BagMenu menu = openBags.get(player.getUniqueId());
        if (menu != null) {
            menu.syncToModel();
        }
    }

    /** 登出：不再追蹤（關閉視窗時已經同步並存檔）。 */
    void forget(final Player player) {
        openBags.remove(player.getUniqueId());
        filterStats.remove(player.getUniqueId());
        final long[] today = todayStats.remove(player.getUniqueId());
        if (today != null && (today[1] > 0 || today[2] > 0) && today[4] >= 0) {
            // 下線前把最後一分鐘的也存起來。這時角色模組已經把角色卸下、PlayerState 也拿掉了（角色的登出比這個先跑），
            // 不能再 players().get()（會替離線的人重建一個 state）：直接照角色 id 寫（2026-10）
            plugin.characters().setProgressFor(today[4], TODAY_KEY, today[0] + ":" + today[1] + ":" + today[2]);
        }
    }

    /**
     * 模組停用（關伺服器）：把開著的隨身背包同步回來再關掉。停用中的插件收不到事件，
     * 選單保留在清單裡，之後角色模組的最後一次存檔前還會再同步一次（內容相同）。
     */
    public void closeAll() {
        for (final Map.Entry<UUID, BagMenu> entry : Map.copyOf(openBags).entrySet()) {
            entry.getValue().shutdown();
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                player.closeInventory();
            }
        }
    }

    /** 金幣餘額的顯示字串。 */
    public String goldText(final Player player) {
        return String.format(Locale.ROOT, "%,d", plugin.gold().balance(player));
    }

    /** 這件裝備被過濾掉會換成多少金幣（物品等級的怪物算；2026-10 拆解台也照這個給，兩邊一樣）。 */
    public long equipmentGold(final Rarity rarity, final int itemLevel) {
        return config.goldValue(DropTable.Kind.EQUIPMENT, rarity, 0, plugin.monsterModule().drops().averageGold(Math.max(1, itemLevel)));
    }

    /** 被過濾時大約換成多少金幣（說明用，照角色等級的怪物算）。 */
    public long goldEstimate(final Player player, final DropTable.Kind kind, final Rarity rarity, final int currencyWeight) {
        final int level = plugin.players().get(player).level();
        return config.goldValue(kind, rarity, currencyWeight, plugin.monsterModule().drops().averageGold(level));
    }

    /** 通貨袋拿東西用：通貨代號找定義。 */
    Optional<CurrencyType> currency(final String id) {
        return Optional.ofNullable(plugin.currencies().repository().get(id));
    }

    // ---- 今天過濾了多少（2026-10）：存在角色進度 filter.today（「日:件數:金幣」），最多一分鐘寫一次；每個角色各自算 ----

    private static final String TODAY_KEY = "filter.today";
    /** 玩家 → {日, 件數, 金幣, 上次寫進資料庫的時間, 角色編號}。 */
    private final Map<UUID, long[]> todayStats = new HashMap<>();

    private static long today() {
        return java.time.LocalDate.now().toEpochDay();
    }

    private long[] todayEntry(final Player player) {
        final long day = today();
        final var character = plugin.players().get(player).character();
        final long characterId = character == null ? -1 : character.id();
        long[] entry = todayStats.get(player.getUniqueId());
        if (entry != null && entry[4] != characterId) {
            // 換了角色：上一個角色還沒寫進資料庫的（最多一分鐘）先存給它，再重新讀這個角色的
            if (entry[4] >= 0 && (entry[1] > 0 || entry[2] > 0)) {
                plugin.characters().setProgressFor(entry[4], TODAY_KEY, entry[0] + ":" + entry[1] + ":" + entry[2]);
            }
            entry = null;
        }
        if (entry == null) {
            entry = new long[] {day, 0, 0, 0, characterId};
            final String saved = plugin.players().get(player).progress().get(TODAY_KEY);
            if (saved != null) {
                final String[] parts = saved.split(":");
                try {
                    if (parts.length == 3 && Long.parseLong(parts[0]) == day) {
                        entry[1] = Long.parseLong(parts[1]);
                        entry[2] = Long.parseLong(parts[2]);
                    }
                } catch (final NumberFormatException ignored) {
                    // 讀不懂：從 0 開始
                }
            }
            todayStats.put(player.getUniqueId(), entry);
        }
        if (entry[0] != day) {
            entry[0] = day;
            entry[1] = 0;
            entry[2] = 0;
        }
        return entry;
    }

    private void addToday(final Player player, final long count, final long gold) {
        final long[] entry = todayEntry(player);
        entry[1] += count;
        entry[2] += gold;
        final long now = System.currentTimeMillis();
        if (now - entry[3] > 60_000) {
            entry[3] = now;
            saveToday(player, entry);
        }
    }

    private void saveToday(final Player player, final long[] entry) {
        if (plugin.players().get(player).hasCharacter()) {
            plugin.characters().setProgress(player, TODAY_KEY, entry[0] + ":" + entry[1] + ":" + entry[2]);
        }
    }

    /** 今天（這個角色）過濾器隱藏了幾件（[0]）、換了多少金幣（[1]）；打開過濾器時順便存一次。 */
    long[] todayStats(final Player player) {
        final long[] entry = todayEntry(player);
        if (entry[1] > 0) {
            saveToday(player, entry);
            entry[3] = System.currentTimeMillis();
        }
        return new long[] {entry[1], entry[2]};
    }

    /** 這次登入過濾器隱藏了幾件（[0]）、換了多少金幣（[1]）。 */
    long[] filterStats(final Player player) {
        final long[] stats = filterStats.get(player.getUniqueId());
        return stats == null ? new long[2] : stats.clone();
    }

    /** 看掉落的角色：等級、手上武器的標籤、身上防具的系（進階規則用）。 */
    /** 這個玩家目前的「我這類」：等級、手上武器的標籤、身上防具的系別（商人賭博的「只看我這類」用）。 */
    public LootFilter.Viewer viewer(final Player player) {
        return viewer(player, plugin.players().get(player));
    }

    LootFilter.Viewer viewer(final Player player, final PlayerState state) {
        final java.util.Set<String> families = new java.util.HashSet<>();
        for (final ItemStack worn : tw.exilecore.character.Gear.armour(plugin.players().get(player))) {
            if (worn == null || worn.isEmpty()) {
                continue;
            }
            final tw.exilecore.item.ItemData data = plugin.items().read(worn).orElse(null);
            final tw.exilecore.item.ItemBase base = data == null ? null : plugin.items().repository().base(data.base());
            if (base != null) {
                for (final String tag : base.tags()) {
                    if (LootFilter.ARMOUR_FAMILIES.contains(tag)) {
                        families.add(tag);
                    }
                }
            }
        }
        return new LootFilter.Viewer(state.level(), state.weaponTags(), families);
    }

    /** 一件掉落交給過濾器判斷用的資料（裝備的等級、基底標籤、地圖階級；寶石的等級／階級）。 */
    private LootFilter.Candidate candidate(final DropTable.Drop drop, final ItemStack stack, final Rarity rarity, final CurrencyType currency,
                                           final boolean upgrade) {
        int itemLevel = 0;
        java.util.Set<String> tags = java.util.Set.of();
        int mapTier = 0;
        int gemLevel = 0;
        int bestAffixRank = 0;
        if (drop.isEquipment()) {
            final tw.exilecore.item.ItemData item = drop.item();
            final tw.exilecore.item.ItemBase base = plugin.items().repository().base(item.base());
            itemLevel = item.ilvl();
            bestAffixRank = bestAffixRank(item);
            if (base != null) {
                tags = base.tags();
                mapTier = base.isMap() ? tw.exilecore.item.ItemStats.mapTier(base, item) : 0;
            }
        } else if ((drop.kind() == DropTable.Kind.SKILL_GEM || drop.kind() == DropTable.Kind.SUPPORT_GEM) && plugin.skills() != null && stack != null) {
            gemLevel = plugin.skills().gems().read(stack).map(tw.exilecore.skill.GemData::level).orElse(0);
        }
        return new LootFilter.Candidate(drop.kind(), rarity, currency == null ? null : currency.id(), upgrade, itemLevel, tags, mapTier, gemLevel,
                bestAffixRank, drop.isEquipment() ? drop.item().base() : null);
    }

    /** 這件裝備最好的詞綴是 T 幾（1 = T1；沒有詞綴 0）：掉落過濾的「保留有 T 幾以上詞綴的裝備」看這個（未鑑定的也算）。 */
    /** 詞綴至少要有幾階，「T 幾」才有意義（掉落過濾的保留好詞綴）。 */
    static final int MIN_TIERS_FOR_RANK = 3;

    private int bestAffixRank(final tw.exilecore.item.ItemData item) {
        int best = 0;
        for (final tw.exilecore.item.RolledAffix rolled : item.affixes()) {
            final tw.exilecore.item.Affix affix = plugin.items().repository().affix(rolled.id());
            if (affix == null || affix.tiers().size() < MIN_TIERS_FOR_RANK) {
                continue; // 2026-10：只有一兩階的詞綴（技能等級、遺時星晶…）擲到就一定是 T1，不算「好詞綴」，不然幾乎每件都被撿回來
            }
            final int rank = affix.rank(rolled.tier());
            if (best == 0 || rank < best) {
                best = rank;
            }
        }
        return best;
    }

    private boolean isJewel(final tw.exilecore.item.ItemData item) {
        final tw.exilecore.item.ItemBase base = item == null ? null : plugin.items().repository().base(item.base());
        return base != null && base.isJewel();
    }

    private boolean isMap(final tw.exilecore.item.ItemData item) {
        final tw.exilecore.item.ItemBase base = item == null ? null : plugin.items().repository().base(item.base());
        return base != null && base.isMap();
    }
}
