package tw.exilecore.zone;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 傳送選單（/wp、傳送師 NPC）：第一列是營地、回到剛才的位置；下面每一列是一個區域，
 * 最左邊是區域資訊，右邊是它的檢查點（發現過的才能傳送）。
 * 2026-10：傳送到檢查點、區域入口要金幣（角色等級 × {@value #PRICE_PER_LEVEL}）；有月卡免費。回營地、回到剛才的位置一樣免費。
 */
public final class WaypointMenu extends Menu {

    private static final int SLOT_CAMP = 0;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_RETURN = 7;
    private static final int SLOT_CLOSE = 8;
    private static final int MAX_ZONES = 5;
    private static final int MAX_CHECKPOINTS = 8;
    /** 傳送到檢查點、區域入口：每一級角色等級多少金幣。 */
    static final long PRICE_PER_LEVEL = 100;

    private final ZoneService zones;
    private final Map<Integer, Runnable> actions = new HashMap<>();

    public WaypointMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 6, "<dark_gray>傳送點");
        this.zones = plugin.zones();
    }

    @Override
    protected void draw() {
        actions.clear();
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        final PlayerState state = plugin.players().get(player);
        final String last = zones.lastCheckpoint(player);

        // 營地
        final Optional<Zone> camp = zones.camp();
        if (camp.isPresent()) {
            final boolean here = zones.inTown(player.getLocation());
            set(SLOT_CAMP, icon(material(camp.get().definition().icon(), Material.CAMPFIRE),
                    "<#f5b942>" + ItemRenderer.escape(camp.get().name()),
                    "<gray>安全區・進來生命與魔力回滿",
                    "<gray>倉庫、鑑定師、拆解台都在這裡",
                    "",
                    here ? "<dark_gray>你在這裡" : "<yellow>左鍵：傳送（免費）"));
            if (!here) {
                actions.put(SLOT_CAMP, () -> zones.goTown(player));
            }
        } else {
            set(SLOT_CAMP, icon(Material.BARRIER, "<red>還沒有營地", "<gray>管理員：/ec zone autolayout"));
        }

        // 目前位置
        final Optional<Zone> here = zones.zoneAt(player.getLocation());
        final List<String> info = new ArrayList<>();
        if (here.isPresent()) {
            info.add("<gray>現在在 <white>" + ItemRenderer.escape(here.get().name()));
            if (!here.get().town()) {
                final Location location = player.getLocation();
                info.add("<gray>這裡的怪物等級 <white>" + here.get().levelAt(location.getX(), location.getZ()));
            }
        } else {
            info.add("<gray>現在不在任何區域");
        }
        info.add("<gray>重生點：<white>" + checkpointName(last));
        info.add("");
        info.add("<dark_gray>走到檢查點就會記錄，之後可以從這裡傳送");
        info.add(card() ? "<#bcd4ff>傳送：月卡免費"
                : "<gray>傳送到檢查點、區域入口要花金幣<gray>（有月卡免費）");
        info.add("<dark_gray>回營地、回到剛才的位置都免費");
        info.add("<dark_gray>受到攻擊後 3 秒內不能傳送到檢查點（回營地隨時可以）");
        set(SLOT_INFO, icon(Material.COMPASS, "<#7fd4ff>傳送點", info));

        // 回到剛才的位置（/town 之前）
        final Location back = zones.returnPoint(player);
        if (back != null) {
            final String where = zones.zoneAt(back).map(Zone::name).orElse("野外");
            set(SLOT_RETURN, glowing(icon(Material.ENDER_PEARL, "<#7fd4ff>回到剛才的位置",
                    String.format(Locale.ROOT, "<gray>%s（%d, %d, %d）", ItemRenderer.escape(where),
                            back.getBlockX(), back.getBlockY(), back.getBlockZ()),
                    "",
                    "<yellow>左鍵：傳送（用一次就消失）")));
            actions.put(SLOT_RETURN, () -> zones.returnFromTown(player));
        }
        set(SLOT_CLOSE, closeButton());
        actions.put(SLOT_CLOSE, this::goBack);

        // 各區域
        int row = 1;
        for (final Zone zone : zones.combatZones()) {
            if (row > MAX_ZONES) {
                break;
            }
            drawZone(row * 9, zone, state, last);
            row++;
        }
    }

    private void drawZone(final int base, final Zone zone, final PlayerState state, final String last) {
        final List<ZoneLayout.Checkpoint> checkpoints = new ArrayList<>(zone.checkpoints());
        final int found = zones.discoveredCount(player, zone);
        final List<String> lore = new ArrayList<>();
        final String intro = zone.definition().intro();
        if (intro != null && !intro.isBlank()) {
            lore.add("<dark_gray><i>" + ItemRenderer.escape(intro));
        }
        lore.add("<gray>" + zone.levelText());
        if (zone.boss() != null) {
            lore.add("<gray>Boss：<#af6025>" + ItemRenderer.escape(zone.boss().name())
                    + (zones.bossDefeated(player, zone) ? " <green>✔ 已擊敗" : " <dark_gray>未擊敗"));
        }
        lore.add("<gray>檢查點 <white>" + found + "</white> / " + checkpoints.size());
        if (!zone.placed()) {
            lore.add("<red>管理員還沒擺放這個區域");
        } else if (state.hasCharacter() && state.level() < zone.minLevel() - 3) {
            lore.add("<red>你的等級偏低");
        }
        final boolean canEnter = found > 0 && zone.layout().entry() != null;
        if (canEnter) {
            lore.add("");
            lore.add("<yellow>左鍵：傳送到入口");
            final ZoneLayout.Point entry = zone.layout().entry();
            final int entryLevel = zone.levelAt(entry.x(), entry.z());
            lore.add(priceLine(entryLevel));
            actions.put(base, () -> paid(entryLevel, () -> zones.teleport(player, zones.toLocation(entry), zone.name() + "入口")));
        }
        set(base, icon(material(zone.definition().icon(), Material.FILLED_MAP), "<#e07a1f>" + ItemRenderer.escape(zone.name()), lore));

        for (int i = 0; i < Math.min(MAX_CHECKPOINTS, checkpoints.size()); i++) {
            final ZoneLayout.Checkpoint checkpoint = checkpoints.get(i);
            final int slot = base + 1 + i;
            if (!zones.discovered(player, zone, checkpoint)) {
                set(slot, icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>未發現的檢查點", "<gray>走到那裡就會記錄"));
                continue;
            }
            final boolean respawn = (zone.id() + "." + checkpoint.id()).equals(last);
            ItemStack stack = icon(Material.LODESTONE, "<#7fd4ff>◆ " + ItemRenderer.escape(checkpoint.name()),
                    "<gray>" + ItemRenderer.escape(zone.name()) + "・怪物等級 " + zone.levelAt(checkpoint.point().x(), checkpoint.point().z()),
                    respawn ? "<green>目前的重生點" : "",
                    priceLine(zone.levelAt(checkpoint.point().x(), checkpoint.point().z())));
            if (respawn) {
                stack = highlight(stack); // 目前的重生點：外框（沒資源包：發光）
            }
            set(slot, stack);
            final int level = zone.levelAt(checkpoint.point().x(), checkpoint.point().z());
            actions.put(slot, () -> paid(level, () -> zones.teleport(player, zones.toLocation(checkpoint.point()), checkpoint.name())));
        }
    }

    /** 有月卡：傳送免費。 */
    private boolean card() {
        return plugin.sponsors() != null && plugin.sponsors().cardActive(player);
    }

    /** 沒有月卡時傳送到這裡的價格（2026-10：照目的地的怪物等級 × 100）。 */
    private static long normalPrice(final int monsterLevel) {
        return Math.max(1, monsterLevel) * PRICE_PER_LEVEL;
    }

    /** 這次傳送要多少金幣（有月卡 0）。 */
    private long price(final int monsterLevel) {
        return card() ? 0 : normalPrice(monsterLevel);
    }

    private String priceLine(final int monsterLevel) {
        final String normal = String.format(Locale.ROOT, "%,d", normalPrice(monsterLevel));
        return card() ? "<yellow>左鍵：傳送 <#bcd4ff>（月卡：免費）<dark_gray>　沒月卡 " + normal + " 金幣"
                : "<yellow>左鍵：傳送 <gray>（<#f5b942>" + normal + " 金幣</#f5b942>；有月卡免費）";
    }

    /** 先扣錢再傳送；傳送沒成功（剛被打、目的地不安全…，teleport 會說原因）退錢。 */
    private void paid(final int monsterLevel, final java.util.function.BooleanSupplier teleport) {
        final long price = price(monsterLevel);
        if (price > 0) {
            if (plugin.gold() == null || !plugin.gold().take(player, price, "waypoint")) {
                player.sendMessage(plugin.text().prefixed("<red>金幣不夠：傳送要 " + String.format(Locale.ROOT, "%,d", price) + " 金幣"));
                return;
            }
        }
        if (!teleport.getAsBoolean() && price > 0) {
            plugin.gold().refund(player, price, "waypoint");
        }
    }

    private String checkpointName(final String key) {
        if (key == null) {
            return zones.camp().map(Zone::name).orElse("（原版出生點）");
        }
        final int dot = key.indexOf('.');
        if (dot > 0) {
            final Optional<Zone> zone = zones.zone(key.substring(0, dot));
            if (zone.isPresent()) {
                final ZoneLayout.Checkpoint checkpoint = zone.get().layout().checkpoint(key.substring(dot + 1));
                if (checkpoint != null) {
                    return ItemRenderer.escape(checkpoint.name()) + "（" + ItemRenderer.escape(zone.get().name()) + "）";
                }
            }
        }
        return zones.camp().map(Zone::name).orElse("（原版出生點）");
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        final Runnable action = actions.get(slot);
        if (action == null) {
            return;
        }
        player.closeInventory();
        action.run();
    }
}
