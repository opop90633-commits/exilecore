package tw.exilecore.character.menu;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.Gear;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.StatsReport;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 角色面板：屬性摘要、裝備欄（武器、副手、頭盔、胸甲、褲子、鞋子、戒指×2、項鍊、腰帶）、通往天賦樹與角色選擇的按鈕。
 * 點自己背包裡的裝備裝上（同一欄有東西就換下來，戒指兩格都滿換掉第一格），點欄位卸下回背包。
 * 裝在這裡就生效，手上不用拿、身上不用穿（快捷列第 9 格、防具欄只是顯示，見 {@link Gear}）。
 */
public final class CharacterPanelMenu extends Menu {

    /**
     * 裝備欄在選單裡的格子，順序對應 PlayerState.JEWELLERY_SLOTS（戒指、戒指、項鍊、腰帶、武器、副手、頭、身、腿、腳）：
     * <pre>
     *         頭 項鍊
     *   武器 戒指 身 戒指 副手
     *         腿 腰帶 腳
     *       護符 護符 護符
     * </pre>
     */
    private static final int[] JEWELLERY_POSITIONS = {21, 23, 14, 31, 20, 24, 13, 22, 30, 32};
    private static final int SLOT_CHARACTER = 0;
    private static final int SLOT_ATTRIBUTES = 2;
    private static final int SLOT_DEFENCE = 3;
    private static final int SLOT_OFFENCE = 4;
    private static final int SLOT_PASSIVES = 5;
    private static final int SLOT_INACTIVE = 6;
    private static final int SLOT_SELECT = 8;
    /** 月卡福利：裝備存檔（2026-10）。 */
    private static final int SLOT_PRESETS = 7;
    /** 網頁角色頁（2026-10，像 poe.ninja）。 */
    private static final int SLOT_WEB = 1;
    /** 護符欄（2026-10）：腰帶下面那一排。 */
    private static final int[] CHARM_POSITIONS = {39, 40, 41};
    private static final int SLOT_HELP = 44;
    /** 增益效果（2026-10）：身上的增益，與它們讓面板哪些數值變了。 */
    private static final int SLOT_BUFFS = 18;
    /** 開著的時候多久檢查一次要不要重畫（增益加上、過期、秒數倒數）。 */
    private static final long REFRESH_TICKS = 10;

    private org.bukkit.scheduler.BukkitTask refresher;
    /** 上次畫的時候屬性表的樣子：變了就重畫。 */
    private int drawnSignature;
    private long lastDrawTick;
    private static final int SLOT_CLOSE = 49;

    public CharacterPanelMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 6, "<dark_gray>角色");
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state.character();

        final List<String> characterLore = new ArrayList<>(StatsReport.header(plugin, player));
        if (character != null) {
            characterLore.add(String.format(Locale.ROOT, "<gray>遊玩 <white>%d 小時 %d 分<gray>　死亡 <white>%d 次",
                    (character.playtimeSeconds() + state.sessionSeconds()) / 3600,
                    (character.playtimeSeconds() + state.sessionSeconds()) % 3600 / 60, character.deaths()));
        }
        set(SLOT_CHARACTER, glowing(icon(character == null ? Material.PLAYER_HEAD : material(character.characterClass().icon(), Material.IRON_SWORD),
                character == null ? "<red>沒有角色" : character.characterClass().color() + ItemRenderer.escape(character.name()), characterLore)));
        set(SLOT_ATTRIBUTES, icon(Material.BOOK, "<#f5b942>屬性", StatsReport.attributes(plugin, player)));
        // 2026-10：增益（光環、戰吼、球、護符……）讓哪些數值變了：直接寫在防禦、攻擊的最下面，標題也標出來
        final tw.exilecore.character.BuffImpact.Lines impact = StatsReport.impact(plugin, player);
        final List<String> defenceLore = new ArrayList<>(StatsReport.defence(plugin, player));
        addImpact(defenceLore, impact.defence());
        set(SLOT_DEFENCE, icon(Material.SHIELD, "<#f5b942>防禦" + impactMark(impact.defence()), defenceLore));
        final List<String> offenceLore = new ArrayList<>(StatsReport.offence(plugin, player));
        final List<String> specials = StatsReport.specials(plugin, player);
        if (!specials.isEmpty()) {
            offenceLore.add("");
            offenceLore.add("<gray>特殊效果：");
            offenceLore.addAll(specials);
        }
        addImpact(offenceLore, impact.offence());
        final List<String> buffs = StatsReport.buffs(plugin, player);
        drawBuffs(buffs, impact);
        final List<String> minions = StatsReport.minions(plugin, player);
        if (!minions.isEmpty()) {
            offenceLore.add("");
            offenceLore.add("<gray>召喚物：");
            offenceLore.addAll(minions);
        }
        set(SLOT_OFFENCE, icon(Material.IRON_SWORD, "<#f5b942>攻擊" + impactMark(impact.offence()), offenceLore));
        final List<String> passiveLore = new ArrayList<>(StatsReport.passives(plugin, player));
        passiveLore.add("<yellow>左鍵：打開天賦樹（/tree）");
        set(SLOT_PASSIVES, icon(Material.NETHER_STAR, "<#f5b942>天賦樹", passiveLore));
        final List<String> inactive = StatsReport.inactive(plugin, player);
        if (!inactive.isEmpty()) {
            final List<String> lore = new ArrayList<>(inactive);
            lore.add("<gray>提升等級或屬性後會自動生效");
            set(SLOT_INACTIVE, icon(Material.BARRIER, "<red>未生效的裝備", lore));
        }
        set(SLOT_SELECT, icon(Material.CHEST, "<#f5b942>切換角色", "<yellow>左鍵：打開角色選擇（/char）"));
        final tw.exilecore.web.Profiles profiles = plugin.web() == null ? null : plugin.web().profiles();
        if (profiles != null && character != null) {
            final boolean open = profiles.isPublic(player);
            set(SLOT_WEB, icon(Material.FILLED_MAP, "<#77ccff>網頁角色頁",
                    "<gray>在網頁上看你的裝備、天賦、技能與屬性",
                    "<gray>也可以看別人公開的角色",
                    open ? "<green>現在公開<gray>（設定裡可以改成不公開）" : "<red>現在不公開<gray>（只有你自己有連結也看不到；設定裡可以打開）",
                    "", "<yellow>左鍵：在聊天欄送出連結"));
        }
        set(SLOT_PRESETS, icon(Material.CLOCK, "<#bcd4ff>裝備組（月卡）　<white>第 " + (state.activeGearSet() + 1) + " 組",
                "<gray>5 組裝備，每一組的裝備放在自己那一組裡，切換時一起換",
                "<gray>只有正在用的那一組有效果",
                plugin.sponsors() != null && plugin.sponsors().cardDaysLeft(player) > 0 ? "<green>月卡：可以使用" : "<dark_gray>要有月卡才能使用",
                "", "<yellow>左鍵：打開"));

        for (int i = 0; i < JEWELLERY_POSITIONS.length; i++) {
            final ItemSlot slot = PlayerState.JEWELLERY_SLOTS[i];
            final ItemStack equipped = state.jewellery(i);
            if (equipped == null) {
                // 2026-10：套了資源包的人看到那一欄的剪影（戒指、項鍊、頭盔…），比較好認
                set(JEWELLERY_POSITIONS[i], uiIcon(slotShape(slot), emptyIcon(slot), "<gray>" + slot.displayName() + "欄",
                        "<dark_gray>點下方自己背包裡的" + slot.displayName() + "裝上"));
            } else {
                final ItemStack shown = equipped.clone();
                shown.editMeta(meta -> {
                    final List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                    lore.add(ItemRenderer.line(""));
                    lore.add(ItemRenderer.line("<yellow>左鍵：卸下回背包"));
                    meta.lore(lore);
                });
                set(JEWELLERY_POSITIONS[i], shown);
            }
        }
        drawCharms(state);
        set(SLOT_HELP, uiIcon("help", Material.PAPER, "<white>裝備欄",
                "<gray>武器、副手、防具、戒指 ×2、項鍊、腰帶都裝在這裡",
                "<gray>裝上就生效：手上不用拿、身上不用穿",
                "<gray>快捷列第 9 格、防具欄顯示的是裝在這裡的東西（只是外觀）",
                "<gray>點下方自己背包裡的裝備就會裝上（原本的換回背包）",
                "<gray>需求（等級、屬性）不符的裝備不會生效",
                "<gray>腰帶下面那排是護符欄：護符裝在這裡就生效（不佔背包）"));
        set(SLOT_CLOSE, closeButton());
        drawnSignature = signature();
        lastDrawTick = plugin.getServer().getCurrentTick();
    }

    // ---- 增益（2026-10） ----

    /** 標題後面的標記：這一區有數值被增益改到。 */
    private static String impactMark(final List<String> changed) {
        return changed.isEmpty() ? "" : " <green>▲ 含增益";
    }

    private static void addImpact(final List<String> lore, final List<String> changed) {
        if (changed.isEmpty()) {
            return;
        }
        lore.add("");
        lore.add("<green>▲ 增益生效中<gray>（沒有增益時 → 現在）：");
        for (final String line : changed) {
            lore.add("<dark_gray>　" + line);
        }
    }

    /** 增益效果那一格：身上有什麼增益、讓數值變了多少；沒有增益時告訴玩家這裡會顯示什麼。 */
    private void drawBuffs(final List<String> buffs, final tw.exilecore.character.BuffImpact.Lines impact) {
        final List<String> lore = new ArrayList<>();
        if (buffs.isEmpty() && impact.isEmpty()) {
            lore.add("<gray>現在身上沒有增益");
            lore.add("<dark_gray>開光環、放戰吼、拿到球、用護符、喝藥劑時的條件詞綴……");
            lore.add("<dark_gray>生效後會列在這裡，並寫出它讓面板哪些數值變了");
            set(SLOT_BUFFS, icon(Material.GLASS_BOTTLE, "<gray>增益效果 <dark_gray>（沒有）", lore));
            return;
        }
        if (!buffs.isEmpty()) {
            lore.add("<gray>身上的增益：");
            lore.addAll(buffs);
        }
        lore.add("");
        if (impact.isEmpty()) {
            lore.add("<gray>這些增益沒有改到面板上的數值");
            lore.add("<dark_gray>（只在打中時才算的效果，例如戰吼強化，要看實際傷害）");
        } else {
            lore.add("<green>▲ 增益讓數值變成<gray>（沒有增益時 → 現在）：");
            lore.addAll(impact.all());
            lore.add("");
            lore.add("<dark_gray>防禦、攻擊兩格的數字都已經含增益");
        }
        lore.add("<dark_gray>這個視窗開著時會自動更新");
        set(SLOT_BUFFS, glowing(icon(Material.BEACON, "<#ffd27f>增益效果 <green>（生效中）", lore)));
    }

    /** 屬性表的樣子（含增益與怒氣）：拿來判斷要不要重畫。 */
    private int signature() {
        int hash = plugin.players().sheet(player).allModifiers().hashCode();
        if (plugin.skills() != null) {
            hash = 31 * hash + plugin.skills().resources().rage(player);
        }
        return hash;
    }

    @Override
    protected void onOpen() {
        if (refresher != null) {
            refresher.cancel();
        }
        refresher = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, REFRESH_TICKS, REFRESH_TICKS);
    }

    @Override
    protected void onClose() {
        if (refresher != null) {
            refresher.cancel();
            refresher = null;
        }
    }

    /** 開著時：增益加上、過期就重畫；身上有增益時每秒重畫一次（剩幾秒的倒數）。 */
    private void refresh() {
        if (!player.isConnected() || player.getOpenInventory().getTopInventory().getHolder() != this) {
            onClose();
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        final boolean changed = signature() != drawnSignature;
        final boolean countdown = now - lastDrawTick >= 20 && !StatsReport.buffs(plugin, player).isEmpty();
        if (changed || countdown) {
            redraw();
        }
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_CLOSE -> goBack();
            case SLOT_PASSIVES -> plugin.characterMenus().openTree(player);
            case SLOT_SELECT -> plugin.characterMenus().openSelect(player);
            case SLOT_WEB -> {
                final tw.exilecore.web.Profiles profiles = plugin.web() == null ? null : plugin.web().profiles();
                if (profiles != null && plugin.players().get(player).hasCharacter()) {
                    player.closeInventory();
                    profiles.sendLink(player);
                }
            }
            case SLOT_PRESETS -> new tw.exilecore.sponsor.GearSetMenu(plugin, player, () -> new CharacterPanelMenu(plugin, player).open()).open();
            default -> {
                for (int i = 0; i < JEWELLERY_POSITIONS.length; i++) {
                    if (JEWELLERY_POSITIONS[i] == slot) {
                        unequip(i);
                        return;
                    }
                }
                for (int i = 0; i < CHARM_POSITIONS.length; i++) {
                    if (CHARM_POSITIONS[i] == slot) {
                        final tw.exilecore.flask.CharmService charms = charms();
                        if (charms != null) {
                            result(charms.unequip(player, i), Sound.ENTITY_ITEM_PICKUP);
                        }
                        return;
                    }
                }
            }
        }
    }

    private tw.exilecore.flask.CharmService charms() {
        return plugin.flasks() == null ? null : plugin.flasks().charms();
    }

    /** 護符欄：開著的格子（基本格數加「+x 護符欄位」）、沒開的格子。 */
    private void drawCharms(final PlayerState state) {
        final tw.exilecore.flask.CharmService charms = charms();
        if (charms == null) {
            return;
        }
        final int open = charms.activeCount(player);
        for (int i = 0; i < CHARM_POSITIONS.length && i < PlayerState.CHARM_SLOTS; i++) {
            final ItemStack charm = tw.exilecore.flask.CharmService.charmAt(state, i);
            if (charm != null) {
                final boolean active = i < open;
                final ItemStack shown = charm.clone();
                shown.editMeta(meta -> {
                    final List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                    lore.add(ItemRenderer.line(""));
                    if (!active) {
                        lore.add(ItemRenderer.line("<red>這一格的護符欄沒開：不會生效<gray>（要更多「+1 護符欄位」）"));
                    }
                    lore.add(ItemRenderer.line("<yellow>左鍵：卸下回背包"));
                    meta.lore(lore);
                });
                set(CHARM_POSITIONS[i], shown);
            } else if (i < open) {
                set(CHARM_POSITIONS[i], uiIcon("slot_charm", Material.PURPLE_STAINED_GLASS_PANE, "<#c9a0ff>護符欄",
                        "<dark_gray>點下方自己背包裡的護符裝上", "<dark_gray>（或把護符拿在手上按右鍵）",
                        "<gray>裝在這裡就生效，不佔背包"));
            } else {
                set(CHARM_POSITIONS[i], uiIcon("slot_charm_locked", Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>護符欄（沒開）",
                        "<gray>現在開 " + open + " 格；腰帶的「+1 護符欄位」可以多開"));
            }
        }
    }

    private void result(final String problem, final Sound success) {
        if (problem != null) {
            player.sendActionBar(plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        player.playSound(player.getLocation(), success, 0.6f, 1.2f);
        redraw();
    }

    /** 空的裝備欄畫的剪影（UiPack.ICONS 的 slot_*；管理員看別人的裝備也用）。 */
    public static String slotShape(final ItemSlot slot) {
        return switch (slot) {
            case RING -> "slot_ring";
            case AMULET -> "slot_amulet";
            case BELT -> "slot_belt";
            case MAIN_HAND -> "slot_main";
            case OFF_HAND -> "slot_off";
            case HEAD -> "slot_head";
            case CHEST -> "slot_chest";
            case LEGS -> "slot_legs";
            default -> "slot_feet";
        };
    }

    private static Material emptyIcon(final ItemSlot slot) {
        return switch (slot) {
            case RING -> Material.LIGHT_GRAY_STAINED_GLASS_PANE;
            case AMULET -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case BELT -> Material.ORANGE_STAINED_GLASS_PANE;
            case MAIN_HAND, OFF_HAND -> Material.RED_STAINED_GLASS_PANE;
            default -> Material.GRAY_STAINED_GLASS_PANE;
        };
    }

    private void unequip(final int index) {
        final String problem = plugin.characters().gear().unequip(player, index);
        if (problem != null) {
            player.sendActionBar(plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.0f);
        redraw();
    }

    @Override
    protected boolean acceptsGearDisplay() {
        return true;
    }

    @Override
    protected void onBottomClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        final ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.isEmpty()) {
            return;
        }
        if (Gear.isDisplay(clicked)) {
            // 點顯示用的格子（第 9 格、防具、副手）：卸下那一件
            final int index = Gear.indexAt(event.getSlot());
            if (index >= 0) {
                unequip(index);
            }
            return;
        }
        final tw.exilecore.flask.CharmService charms = charms();
        if (charms != null && charms.isCharm(clicked)) {
            result(charms.equip(player, event.getSlot()), Sound.BLOCK_AMETHYST_BLOCK_CHIME);
            return;
        }
        final Optional<ItemData> data = plugin.items().read(clicked);
        if (data.isEmpty()) {
            return;
        }
        final String problem = plugin.characters().gear().equip(player, event.getSlot());
        if (problem != null) {
            player.sendActionBar(plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            return;
        }
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.6f, 1.2f);
        redraw();
    }
}
