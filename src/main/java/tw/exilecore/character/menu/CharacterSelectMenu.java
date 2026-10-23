package tw.exilecore.character.menu;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.CharacterService;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 角色選擇：一列格子，每格一個角色（或空格）。左鍵使用、右鍵刪除（會再確認）、空格建立。
 */
public final class CharacterSelectMenu extends Menu {

    /** 一頁最多幾格（超過 18 格時用 6 排的選單、一頁 36 格、◀ ▶ 換頁；2026-10 贊助的角色欄位沒有上限）。 */
    private static final int PAGED = 36;

    private final CharacterService characters;
    private final int count;
    private final int[] slotPositions;
    private final int rows;
    private final int slotClose;
    private final boolean paged;
    private int page;

    public CharacterSelectMenu(final ExileCore plugin, final Player player) {
        this(plugin, player, plugin.characters().slotsFor(player));
    }

    private CharacterSelectMenu(final ExileCore plugin, final Player player, final int count) {
        super(plugin, player, count <= 9 ? 3 : count <= 18 ? 4 : 6, "<dark_gray>選擇角色");
        this.characters = plugin.characters();
        this.count = count;
        this.paged = count > 18;
        this.rows = count <= 9 ? 3 : count <= 18 ? 4 : 6;
        this.slotClose = rows * 9 - 1;
        final int shown = paged ? PAGED : count;
        this.slotPositions = new int[shown];
        // 置中排在第二列（超過 9 格再排第三列）：5 格以內每格隔一格，更多就連著排
        final int perRow = Math.min(9, shown);
        final int gap = shown <= 5 ? 2 : 1;
        final int width = (perRow - 1) * gap + 1;
        final int startColumn = Math.max(0, (9 - width) / 2);
        for (int i = 0; i < shown; i++) {
            final int row = 1 + i / 9;
            final int column = i % 9;
            slotPositions[i] = row * 9 + Math.min(8, startColumn + column * gap);
        }
    }

    private int pages() {
        return paged ? (count + PAGED - 1) / PAGED : 1;
    }

    /** 畫面上第 i 格是第幾個角色格（1 起算；超過總數回傳 0）。 */
    private int characterSlot(final int i) {
        final int slot = (paged ? page * PAGED : 0) + i + 1;
        return slot <= count ? slot : 0;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < rows * 9; i++) {
            set(i, filler());
        }
        final PlayerState state = plugin.players().get(player);
        final long activeId = state.hasCharacter() ? state.character().id() : -1;
        final boolean canBuy = plugin.bounties() != null && !plugin.bounties().config().shop().characterSlots().isEmpty()
                && plugin.bounties().extraCharacterSlots(player) < plugin.bounties().config().shop().characterSlots().size();
        set(4, icon(Material.WRITABLE_BOOK, "<#f5b942>你的角色",
                "<gray>角色數 <white>" + characters.roster(player).size() + " / " + count,
                "<gray>每個角色有自己的等級、背包、天賦與飾品",
                "<gray>倉庫與金幣是帳號共用的：通貨、裝備可以在角色間流通",
                canBuy ? "<dark_gray>想要更多格：功勳兌換（/bounty）可以換角色格" : "",
                plugin.sponsors() != null ? "<dark_gray>贊助（/sponsor）也可以加角色欄位" : "",
                paged ? "<dark_gray>第 " + (page + 1) + "／" + pages() + " 頁" : ""));
        for (int i = 0; i < slotPositions.length; i++) {
            final int slot = characterSlot(i);
            if (slot == 0) {
                continue;
            }
            final Optional<CharacterRecord> record = characters.rosterEntry(player, slot);
            if (record.isEmpty()) {
                set(slotPositions[i], uiIcon("slot_character", Material.GRAY_DYE, "<gray>空的角色格 " + slot, "<yellow>左鍵：建立新角色"));
            } else {
                set(slotPositions[i], characterIcon(record.get(), record.get().id() == activeId));
            }
        }
        if (paged) {
            set(SLOT_PREV, pageButton(false, page, pages()));
            set(SLOT_NEXT, pageButton(true, page, pages()));
        }
        set(slotClose, closeButton()); // 2026-10：「關閉」改成「← 返回選單」
    }

    private ItemStack characterIcon(final CharacterRecord record, final boolean active) {
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>" + record.characterClass().displayName() + " <white>Lv." + record.level());
        final long need = characters.config().experienceToNext(record.level());
        if (need > 0) {
            lore.add(String.format(Locale.ROOT, "<gray>經驗 <white>%,d / %,d", record.experience(), need));
        } else {
            lore.add("<gray>已達等級上限");
        }
        lore.add(String.format(Locale.ROOT, "<gray>遊玩 <white>%d 小時 %d 分<gray>　死亡 <white>%d 次",
                record.playtimeSeconds() / 3600, record.playtimeSeconds() % 3600 / 60, record.deaths()));
        lore.add("");
        if (active) {
            lore.add("<green>使用中");
            lore.add("<gray>右鍵：無法刪除使用中的角色");
        } else {
            lore.add("<yellow>左鍵：使用這個角色");
            lore.add("<red>右鍵：刪除（會再確認一次）");
        }
        final ItemStack stack = icon(material(record.characterClass().icon(), Material.IRON_SWORD),
                record.characterClass().color() + ItemRenderer.escape(record.name()), lore);
        return active ? highlight(stack) : stack; // 使用中的角色：外框（沒資源包：發光）
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == slotClose) {
            goBack();
            return;
        }
        if (paged && (slot == SLOT_PREV || slot == SLOT_NEXT)) {
            page = Math.floorMod(page + (slot == SLOT_NEXT ? 1 : -1), pages());
            redraw();
            return;
        }
        for (int i = 0; i < slotPositions.length; i++) {
            if (slotPositions[i] != slot) {
                continue;
            }
            final int characterSlot = characterSlot(i);
            if (characterSlot == 0) {
                return;
            }
            final Optional<CharacterRecord> record = characters.rosterEntry(player, characterSlot);
            if (record.isEmpty()) {
                if (click.isLeftClick()) {
                    plugin.characterMenus().openClassSelect(player, characterSlot);
                }
                return;
            }
            if (click.isRightClick()) {
                final PlayerState state = plugin.players().get(player);
                if (state.hasCharacter() && state.character().id() == record.get().id()) {
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>不能刪除使用中的角色"));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                    return;
                }
                new ConfirmDeleteMenu(plugin, player, record.get()).open();
                return;
            }
            if (click.isLeftClick()) {
                player.closeInventory();
                characters.switchTo(player, record.get().id(),
                        error -> player.sendMessage(plugin.text().prefixed("<red>" + error)));
            }
            return;
        }
    }
}
