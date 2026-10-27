package tw.exilecore.party;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Dialogs;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.map.MapInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 隊伍選單（/party，或選單裡的「隊伍」）：隊員（隊長、在線、職業等級、在哪裡）、邀請、接受邀請、離開；
 * 隊長對隊員 Shift＋左鍵交出隊長、Shift＋右鍵請出隊伍。
 */
public final class PartyMenu extends Menu {

    private static final int SLOT_HEADER = 4;
    private static final int FIRST_MEMBER = 10;
    private static final int SLOT_INVITE = 19;
    private static final int SLOT_INVITES = 21;
    private static final int SLOT_LEAVE = 23;
    private static final int SLOT_HELP = 25;
    private static final int SLOT_CLOSE = 26;

    private final PartyService service;
    /** 格子 → 隊員。 */
    private final List<UUID> shown = new ArrayList<>();

    public PartyMenu(final ExileCore plugin, final Player player, final PartyService service) {
        super(plugin, player, 3, "<#7fd4ff>隊伍");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        shown.clear();
        final PartyRoster.Party party = service.party(player.getUniqueId());
        final boolean leader = party == null || party.leader().equals(player.getUniqueId());
        final int max = service.config().maxSize();
        if (party == null) {
            set(SLOT_HEADER, icon(Material.PLAYER_HEAD, "<gray>你沒有隊伍",
                    "<gray>邀請別人：下面的「邀請玩家」，或 /party invite 玩家",
                    "<gray>最多 " + max + " 人；隊友共用地圖的傳送門，" + rangeLine(),
                    lootLine() + "，進自己的背包"));
        } else {
            set(SLOT_HEADER, glowing(icon(Material.PLAYER_HEAD, "<#7fd4ff>隊伍（" + party.size() + " / " + max + " 人）",
                    "<gray>隊長：<white>" + ItemRenderer.escape(service.name(party.leader())),
                    "<gray>/p 訊息：隊伍聊天",
                    "<gray>隊友共用地圖的傳送門，" + rangeLine(),
                    lootLine())));
            int slot = FIRST_MEMBER;
            for (final UUID member : party.members()) {
                if (slot > FIRST_MEMBER + 6) {
                    break;
                }
                shown.add(member);
                set(slot++, memberIcon(member, party, leader));
            }
        }
        if (leader && (party == null || party.size() < max)) {
            set(SLOT_INVITE, icon(Material.WRITABLE_BOOK, "<green>邀請玩家", "<gray>點一下，從在線玩家裡選（或輸入名字）",
                    "<dark_gray>也可以 /party invite 玩家"));
        } else if (!leader) {
            set(SLOT_INVITE, icon(Material.BOOK, "<gray>邀請玩家", "<dark_gray>只有隊長能邀請"));
        }
        final List<String> inviters = service.inviterNames(player);
        if (!inviters.isEmpty()) {
            final List<String> lore = new ArrayList<>();
            for (final String name : inviters) {
                lore.add("<white>" + ItemRenderer.escape(name) + "<gray> 邀請你");
            }
            lore.add("");
            lore.add(party == null ? "<yellow>左鍵：接受最新的邀請　右鍵：拒絕" : "<red>你已經在隊伍裡了，先離開才能接受");
            set(SLOT_INVITES, glowing(icon(Material.PAPER, "<#f5b942>收到的邀請（" + inviters.size() + "）", lore)));
        }
        if (party != null) {
            set(SLOT_LEAVE, icon(Material.OAK_DOOR, "<red>離開隊伍", "<gray>點一下離開",
                    leader && party.size() > 1 ? "<dark_gray>你是隊長：最早加入的隊員會接任" : "<dark_gray>/party leave"));
        }
        set(SLOT_HELP, icon(Material.KNOWLEDGE_BOOK, "<white>指令",
                "<white>/party invite 玩家 <gray>邀請",
                "<white>/party accept <gray>接受　<white>/party decline <gray>拒絕",
                "<white>/party leave <gray>離開",
                "<white>/party kick 玩家 <gray>請出（隊長）",
                "<white>/party leader 玩家 <gray>交出隊長（隊長）",
                "<white>/p 訊息 <gray>隊伍聊天"));
        set(SLOT_CLOSE, closeButton());
    }

    private ItemStack memberIcon(final UUID member, final PartyRoster.Party party, final boolean viewerIsLeader) {
        final Player online = plugin.getServer().getPlayer(member);
        final boolean isLeader = party.leader().equals(member);
        final List<String> lore = new ArrayList<>();
        if (online == null) {
            lore.add("<dark_gray>離線");
        } else {
            final PlayerState state = plugin.players().get(online);
            final CharacterRecord character = state.character();
            if (character != null) {
                lore.add(character.characterClass().color() + ItemRenderer.escape(character.name()) + "<gray>　"
                        + character.characterClass().displayName() + " Lv." + state.level());
            }
            lore.add("<gray>在：<white>" + ItemRenderer.escape(where(online)));
            if (!online.equals(player)) {
                lore.add(rangeTag(online)); // 2026-10-26：誰在範圍內（經驗、掉落、隊伍加成只算附近的隊員）
            }
        }
        if (viewerIsLeader && !member.equals(player.getUniqueId())) {
            lore.add("");
            lore.add("<yellow>Shift＋左鍵：交出隊長");
            lore.add("<red>Shift＋右鍵：請出隊伍");
        }
        final String name = (isLeader ? "<#f5b942>★ " : "<white>") + ItemRenderer.escape(service.name(member))
                + (member.equals(player.getUniqueId()) ? " <dark_gray>（你）" : "");
        final ItemStack head = icon(Material.PLAYER_HEAD, name, lore);
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(plugin.getServer().getOfflinePlayer(member)));
        return online == null ? head : glowing(head);
    }

    /** 這個人在哪：地圖、區域、營地、大廳。 */
    private String where(final Player online) {
        if (plugin.maps() != null) {
            final MapInstance instance = plugin.maps().instanceAt(online.getLocation());
            if (instance != null) {
                return "地圖 " + instance.label();
            }
        }
        if (plugin.zones() != null) {
            final var zone = plugin.zones().zoneAt(online.getLocation());
            if (zone.isPresent()) {
                return zone.get().name();
            }
        }
        return online.getWorld().getName();
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot >= FIRST_MEMBER && slot < FIRST_MEMBER + shown.size()) {
            final UUID member = shown.get(slot - FIRST_MEMBER);
            if (member.equals(player.getUniqueId())) {
                return;
            }
            if (click == ClickType.SHIFT_RIGHT) {
                service.kick(player, service.name(member));
                redraw();
            } else if (click == ClickType.SHIFT_LEFT) {
                service.promote(player, service.name(member));
                redraw();
            }
            return;
        }
        switch (slot) {
            case SLOT_INVITE -> {
                final PartyRoster.Party party = service.party(player.getUniqueId());
                if (party != null && !party.leader().equals(player.getUniqueId())) {
                    return;
                }
                promptInvite(party);
            }
            case SLOT_INVITES -> {
                if (service.inviterNames(player).isEmpty()) {
                    return;
                }
                if (click.isRightClick()) {
                    service.decline(player, null);
                } else {
                    service.accept(player, null);
                }
                redraw();
            }
            case SLOT_LEAVE -> {
                if (service.party(player.getUniqueId()) != null) {
                    service.leave(player);
                    redraw();
                }
            }
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }

    /** 邀請：列出在線、還不是隊員的玩家（近的排前面），也可以直接輸入名字。 */
    private void promptInvite(final PartyRoster.Party party) {
        final List<Player> candidates = new ArrayList<>();
        for (final Player other : plugin.getServer().getOnlinePlayers()) {
            // 看不到的（隱身的管理員）不列出來；知道名字的照樣可以在下面輸入
            if (!other.equals(player) && player.canSee(other) && (party == null || !party.members().contains(other.getUniqueId()))) {
                candidates.add(other);
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(other -> other.getWorld().equals(player.getWorld())
                ? other.getLocation().distanceSquared(player.getLocation()) : Double.MAX_VALUE));
        final List<Dialogs.Field> fields = new ArrayList<>();
        final String typed = "-";
        if (!candidates.isEmpty()) {
            final List<Dialogs.Option> options = new ArrayList<>();
            options.add(new Dialogs.Option(typed, "<gray>（用下面輸入的名字）"));
            for (final Player other : candidates.subList(0, Math.min(40, candidates.size()))) {
                options.add(new Dialogs.Option(other.getName(), other.getName()));
            }
            fields.add(new Dialogs.Choice("pick", "在線的玩家", options, options.get(1).id()));
        }
        fields.add(new Dialogs.Text("name", candidates.isEmpty() ? "玩家名字" : "或輸入名字", "", 16));
        plugin.dialogs().form(player, "<dark_gray>邀請加入隊伍", List.of("<gray>對方會在聊天欄收到邀請，按 [接受] 就加入"),
                fields, "邀請", answers -> {
                    final String name = answers.get("name").trim();
                    final String picked = answers.get("pick");
                    final String target = !name.isEmpty() || picked.isEmpty() || picked.equals(typed) ? name : picked;
                    if (target.isEmpty()) {
                        player.sendMessage(plugin.text().prefixed("<gray>沒有選人"));
                    } else {
                        service.invite(player, target);
                    }
                    new PartyMenu(plugin, player, service).open();
                }, () -> new PartyMenu(plugin, player, service).open());
    }

    /** 2026-10-26：「在附近」的說明（經驗、掉落、隊伍加成只算附近的隊員）。 */
    private String rangeLine() {
        return "<gray>" + Math.round(service.config().nearbyRadius()) + " 格內（約一間 Boss 房）的隊友才分經驗、算隊伍加成";
    }

    /** 2026-10-26：這個隊友跟你在不在範圍內（兩邊都要活著、不在城鎮、同一個世界、水平距離在 nearby_radius 內）。 */
    private String rangeTag(final Player other) {
        final boolean sameWorld = other.getWorld().equals(player.getWorld());
        final String distance = sameWorld ? "（" + Math.round(Math.hypot(other.getLocation().getX() - player.getLocation().getX(),
                other.getLocation().getZ() - player.getLocation().getZ())) + " 格）" : "（不同世界）";
        return service.nearby(other, player.getLocation()) && service.nearby(player, other.getLocation())
                ? "<green>● 範圍內<gray>" + distance
                : "<red>○ 範圍外<gray>" + distance + "<dark_gray> 不分經驗、掉落";
    }

    /** 掉落怎麼分（給隊伍介面的說明）。 */
    private String lootLine() {
        return "<gray>掉落：附近、傷害有到 " + Math.round(service.config().shareThreshold() * 100) + "% 的人各拿一份（Boss：同隊在附近就有）";
    }
}
