package tw.exilecore.pvp;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 競技場（/pvp）：進入或離開（不能進會寫為什麼）、規則（PvP 減傷多少、倒下怎麼算）、場上的人與這次的戰績。
 */
public final class PvpMenu extends Menu {

    private static final int SLOT_HEADER = 4;
    private static final int SLOT_ENTER = 11;
    private static final int SLOT_RULES = 13;
    private static final int SLOT_PLAYERS = 15;
    private static final int SLOT_CLOSE = 22;
    private static final int SLOT_WATCH = 20;
    /** 場上的人最多列幾個。 */
    private static final int LIST_LIMIT = 12;

    private final PvpService service;

    public PvpMenu(final ExileCore plugin, final Player player, final PvpService service) {
        super(plugin, player, 3, "<#ff6b6b>⚔ 競技場");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        final PvpConfig config = service.config();
        final List<Player> inside = service.players();
        final boolean here = service.inArena(player);
        final List<String> header = new ArrayList<>();
        header.add("<gray>場上 <white>" + inside.size() + "<gray> 人・PvP 減傷 <white>" + Math.round(config.reduction()) + "%");
        final PvpService.Session session = service.session(player);
        if (session != null) {
            header.add("<gray>你這次：擊倒 <white>" + session.kills + "<gray>、倒下 <white>" + session.deaths
                    + "<gray>、連續擊倒 <white>" + session.streak + "<dark_gray>（最多 " + session.bestStreak + "）");
        }
        set(SLOT_HEADER, icon(Material.IRON_SWORD, "<#ff6b6b>⚔ 競技場", header));
        if (here) {
            set(SLOT_ENTER, icon(Material.OAK_DOOR, "<white>離開競技場", "<gray>回到進來之前的地方（不在劇情世界的話回營地）",
                    "<dark_gray>也可以用 /pvp leave、/town", "", "<yellow>左鍵：離開"));
        } else {
            final String problem = service.joinProblem(player);
            if (problem == null) {
                set(SLOT_ENTER, glowing(icon(Material.NETHERITE_SWORD, "<#ff6b6b>進入競技場",
                        "<gray>傳送到離其他人最遠的出生點（有入場保護：移動或攻擊才解除）",
                        "<gray>進去時生命、魔力、護盾、藥劑補滿", "", "<yellow>左鍵：進入")));
            } else {
                set(SLOT_ENTER, icon(Material.GRAY_DYE, "<gray>進入競技場", "<red>" + ItemRenderer.escape(problem)));
            }
        }
        set(SLOT_RULES, icon(Material.BOOK, "<white>規則",
                "<gray>只有競技場裡能打玩家；<white>同一隊的人不會互打",
                "<gray>專屬的 PvP 減傷：玩家打玩家的傷害減少 <white>" + Math.round(config.reduction()) + "%",
                "<dark_gray>  這一下造成的元素異常、持續傷害、暈眩也跟著變小",
                "<dark_gray>  召喚物、圖騰、陷阱、地雷打玩家也算",
                "<gray>倒下<white>不扣經驗、不掉東西、不算死亡次數",
                config.respawnInArena() ? "<gray>倒下後重生在競技場（離其他人最遠的出生點）" : "<gray>倒下後回營地",
                config.flaskCharges() > 0 ? "<gray>擊倒一個人：藥劑充能 " + config.flaskCharges() + " 次" : "<gray>擊倒不給藥劑充能",
                "<gray>掩體上有<white>梯子<gray>：中央高台、箱子、L 形牆、出生點前的矮牆都爬得上去，",
                "<gray>  箱子跟 L 形牆的頂之間有橋",
                config.minLevel() > 0 ? "<gray>要 " + config.minLevel() + " 級才能進" : "<gray>幾級都能進"));
        final List<String> lines = new ArrayList<>();
        if (inside.isEmpty()) {
            lines.add("<dark_gray>現在沒有人");
        }
        for (int i = 0; i < Math.min(LIST_LIMIT, inside.size()); i++) {
            final Player other = inside.get(i);
            final PvpService.Session stats = service.session(other);
            lines.add((other.equals(player) ? "<#f5b942>" : "<white>") + ItemRenderer.escape(service.name(other))
                    + " <gray>Lv." + plugin.players().get(other).level()
                    + (stats == null ? "" : "<dark_gray>・擊倒 " + stats.kills + "、倒下 " + stats.deaths));
        }
        if (inside.size() > LIST_LIMIT) {
            lines.add("<dark_gray>…還有 " + (inside.size() - LIST_LIMIT) + " 人");
        }
        set(SLOT_PLAYERS, icon(Material.PLAYER_HEAD, "<white>場上的人（" + inside.size() + "）", lines));
        if (!here) {
            set(SLOT_WATCH, icon(Material.SPYGLASS, "<white>觀看席", "<gray>到圍牆外面的看台看大家打（南北兩邊）",
                    "<gray>打不到場內的人、也不會被打；想下場再按「進入競技場」", "", "<yellow>左鍵：去觀看席<dark_gray>（/pvp watch）"));
        } else if (service.watching(player)) {
            set(SLOT_WATCH, icon(Material.NETHERITE_SWORD, "<#ff6b6b>下場打", "<gray>從觀看席傳送到場內的出生點", "", "<yellow>左鍵：下場"));
        }
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_ENTER -> {
                if (!acceptAction(click)) {
                    return;
                }
                final String problem = service.inArena(player) ? service.leave(player) : service.join(player);
                if (problem != null) {
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
                    player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                    redraw();
                    return;
                }
                player.closeInventory();
            }
            case SLOT_WATCH -> {
                if (!acceptAction(click)) {
                    return;
                }
                final String problem = service.watching(player) ? service.join(player) : service.inArena(player) ? null : service.watch(player);
                if (problem != null) {
                    player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                    redraw();
                    return;
                }
                player.closeInventory();
            }
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }
}
