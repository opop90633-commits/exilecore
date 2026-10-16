package tw.exilecore.life;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 木筏管理（2026-10-16）：共用成員（加、拿掉）、現在在筏上的人（加成員、請離開）、可以去的筏（自己的、共用的）、
 * 線上的人（邀請、加成員）。跟 /sea invite／visit／kick／member 做的事一樣，只是不用打指令。
 */
final class LifeRaftMenu extends Menu {

    private static final int SLOT_INFO = 4;
    private static final int ROW_MEMBERS = 9;
    private static final int ROW_CREW = 18;
    private static final int ROW_RAFTS = 27;
    private static final int ROW_ONLINE = 36;
    private static final int PER_ROW = 8;
    private static final int MAX_MEMBERS = 8;

    private final LifeService service;
    private final Map<Integer, Runnable> left = new HashMap<>();
    private final Map<Integer, Runnable> right = new HashMap<>();
    private final Map<Integer, Runnable> shiftLeft = new HashMap<>();
    private final Map<Integer, Runnable> shiftRight = new HashMap<>();
    private int page;

    LifeRaftMenu(final ExileCore plugin, final Player player, final LifeService service) {
        super(plugin, player, 6, "<dark_gray>木筏管理");
        this.service = service;
    }

    private String color() {
        return service.config().color();
    }

    private static ItemStack head(final OfflinePlayer owner, final String name, final List<String> lore) {
        final ItemStack stack = icon(Material.PLAYER_HEAD, name, lore);
        stack.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        return stack;
    }

    private void report(final String problem, final String done) {
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
        } else if (done != null) {
            player.sendMessage(plugin.text().prefixed("<green>" + ItemRenderer.escape(done)));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        }
    }

    @Override
    protected void draw() {
        frame();
        left.clear();
        right.clear();
        shiftLeft.clear();
        shiftRight.clear();
        final LifeService.Profile profile = service.profile(player);
        final int own = profile == null ? -1 : profile.plot;
        final int here = service.activePlot(player);
        final boolean onSea = service.world() != null && service.world().equals(player.getWorld());
        final boolean canManageHere = onSea && here >= 0 && service.canBuild(player, profile, here);
        final Map<UUID, String> members = own < 0 ? Map.of() : service.membersOf(own);

        // ---- 最上面：這塊筏 ----
        final List<String> info = new ArrayList<>();
        if (own < 0) {
            info.add("<gray>你還沒有木筏：先到「前往我的木筏」去一次");
        } else {
            final int size = service.config().raftSize(service.plotLevel(own));
            info.add("<gray>你的木筏：<white>" + size + "×" + size);
            info.add("<gray>共用成員 <white>" + members.size() + "</white>／" + MAX_MEMBERS);
        }
        if (onSea && here >= 0) {
            info.add("<gray>現在在 <white>" + ItemRenderer.escape(service.ownerName(here)) + "</white> 的木筏上"
                    + (here == own ? "" : canManageHere ? " <dark_gray>（你是共用成員）" : " <dark_gray>（訪客）"));
            info.add("<gray>筏上 <white>" + service.crewOn(here).size() + "</white> 個人");
        } else {
            info.add("<dark_gray>你現在不在海上");
        }
        info.add("");
        info.add("<gray>共用成員：可以蓋、拆、收網、種田、掌舵、邀請別人");
        info.add("<gray>訪客：只能看、撈東西，不能動筏");
        set(SLOT_INFO, icon(Material.OAK_BOAT, "<" + color() + ">木筏管理", info));

        // ---- 共用成員 ----
        set(ROW_MEMBERS, rowLabel(Material.WRITABLE_BOOK, "共用成員", "你的木筏的共用成員（最多 " + MAX_MEMBERS + " 個）",
                "左鍵：去他現在待的筏", "Shift＋右鍵：拿掉"));
        int slot = ROW_MEMBERS + 1;
        for (final Map.Entry<UUID, String> member : members.entrySet()) {
            if (slot >= ROW_MEMBERS + 1 + PER_ROW) {
                break;
            }
            final UUID id = member.getKey();
            final String name = member.getValue();
            final Player online = plugin.getServer().getPlayer(id);
            set(slot, head(plugin.getServer().getOfflinePlayer(id), "<white>" + ItemRenderer.escape(name),
                    List.of(online == null ? "<dark_gray>離線" : "<green>線上", "", "<yellow>左鍵：去他現在待的筏", "<red>Shift＋右鍵：拿掉共用成員")));
            left.put(slot, () -> {
                player.closeInventory();
                report(service.visit(player, id, name), null);
            });
            shiftRight.put(slot, () -> {
                final String problem = service.removeMember(player, id, name);
                report(problem, problem == null ? name + " 不再是共用成員了" : null);
                if (problem == null && online != null) {
                    online.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(player.getName()) + " 把你從木筏的共用成員拿掉了"));
                }
                redraw();
            });
            slot++;
        }
        if (members.isEmpty()) {
            set(ROW_MEMBERS + 1, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>還沒有共用成員", "<gray>在下面「線上的人」Shift＋左鍵加"));
        }

        // ---- 現在在筏上的人 ----
        set(ROW_CREW, rowLabel(Material.SPYGLASS, "在這塊筏上", "現在跟你在同一塊筏上的人",
                "Shift＋左鍵：加成共用成員（筏主）", "Shift＋右鍵：請他離開（筏主、共用成員）"));
        slot = ROW_CREW + 1;
        if (onSea && here >= 0) {
            for (final Player other : service.crewOn(here)) {
                if (other.equals(player) || slot >= ROW_CREW + 1 + PER_ROW) {
                    continue;
                }
                final LifeService.Profile otherProfile = service.profile(other);
                final boolean ownerOfHere = otherProfile != null && otherProfile.plot == here;
                final boolean member = service.membersOf(here).containsKey(other.getUniqueId());
                final List<String> lore = new ArrayList<>();
                lore.add(ownerOfHere ? "<" + color() + ">筏主" : member ? "<green>共用成員" : "<gray>訪客");
                lore.add("");
                if (here == own && !ownerOfHere && !member) {
                    lore.add("<yellow>Shift＋左鍵：加成共用成員");
                    shiftLeft.put(slot, () -> addMember(other));
                }
                if (canManageHere && !ownerOfHere) {
                    lore.add("<red>Shift＋右鍵：請他離開這塊筏");
                    shiftRight.put(slot, () -> {
                        report(service.kick(player, other), other.getName() + " 離開這塊筏了");
                        redraw();
                    });
                }
                set(slot, head(other, "<white>" + ItemRenderer.escape(other.getName()), lore));
                slot++;
            }
        }
        if (slot == ROW_CREW + 1) {
            set(slot, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>沒有別人", onSea ? "<gray>筏上只有你" : "<gray>你現在不在海上"));
        }

        // ---- 可以去的筏 ----
        set(ROW_RAFTS, rowLabel(Material.COMPASS, "可以去的筏", "自己的、你是共用成員的", "左鍵：前往"));
        slot = ROW_RAFTS + 1;
        set(slot, icon(Material.OAK_BOAT, "<#7fd4ff>我的木筏", own < 0 ? "<gray>還沒去過：左鍵建立" : "<gray>回自己的筏", "", "<yellow>左鍵：前往"));
        left.put(slot, () -> {
            player.closeInventory();
            report(service.travel(player, false), null);
        });
        slot++;
        for (final int plot : service.memberOf(player.getUniqueId())) {
            if (slot >= ROW_RAFTS + 1 + PER_ROW) {
                break;
            }
            final UUID owner = service.ownerOf(plot);
            if (owner == null) {
                continue;
            }
            final String name = service.ownerName(plot);
            final int size = service.config().raftSize(service.plotLevel(plot));
            set(slot, head(plugin.getServer().getOfflinePlayer(owner), "<white>" + ItemRenderer.escape(name) + " 的木筏",
                    List.of("<gray>" + size + "×" + size + "；你是共用成員", "", "<yellow>左鍵：前往")));
            left.put(slot, () -> {
                player.closeInventory();
                report(service.travelTo(player, plot, null), null);
            });
            slot++;
        }

        // ---- 線上的人（邀請、加成員） ----
        final List<Player> online = new ArrayList<>();
        for (final Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(player) && service.canEnter(other)) {
                online.add(other);
            }
        }
        online.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        final int pages = Math.max(1, (online.size() + PER_ROW - 1) / PER_ROW);
        page = Math.max(0, Math.min(page, pages - 1));
        set(ROW_ONLINE, rowLabel(Material.PLAYER_HEAD, "線上的人", "能進" + service.config().name() + "的線上玩家",
                "左鍵：邀請到你待的這塊筏", "Shift＋左鍵：加成你的木筏的共用成員"));
        slot = ROW_ONLINE + 1;
        for (int i = page * PER_ROW; i < Math.min(online.size(), (page + 1) * PER_ROW); i++) {
            final Player other = online.get(i);
            final boolean member = members.containsKey(other.getUniqueId());
            final List<String> lore = new ArrayList<>();
            if (member) {
                lore.add("<green>已經是你的共用成員");
            }
            lore.add("");
            if (canManageHere) {
                lore.add("<yellow>左鍵：邀請到這塊筏");
                left.put(slot, () -> report(service.invite(player, other), "邀請送出了：" + other.getName() + " 點訊息裡的 [前往] 就會過來"));
            } else {
                lore.add("<dark_gray>要在自己的筏（或共用的筏）上才能邀請");
            }
            if (!member && own >= 0) {
                lore.add("<yellow>Shift＋左鍵：加成共用成員");
                shiftLeft.put(slot, () -> addMember(other));
            }
            set(slot, head(other, "<white>" + ItemRenderer.escape(other.getName()), lore));
            slot++;
        }
        if (online.isEmpty()) {
            set(ROW_ONLINE + 1, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>沒有別人在線上"));
        }
        if (pages > 1) {
            set(SLOT_PREV, pageButton(false, page, pages));
            set(SLOT_NEXT, pageButton(true, page, pages));
        }
        set(SLOT_BACK, backButton(service.config().name()));
        set(SLOT_HELP, helpButton(List.of(
                "<gray>同樣的事也可以打指令：",
                "<gray>/sea invite 玩家　邀請別人來你待的筏",
                "<gray>/sea visit 玩家　去隊友、共用、邀請你的人的筏",
                "<gray>/sea kick 玩家　請人離開這塊筏",
                "<gray>/sea member add|remove 玩家　共用成員",
                "<gray>/sea members　列出共用成員",
                "<gray>同一隊的人可以直接去彼此的筏")));
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private void addMember(final Player other) {
        final String problem = service.addMember(player, other.getUniqueId(), other.getName());
        report(problem, problem == null ? other.getName() + " 成了你的木筏的共用成員：可以蓋、拆、收網、種田、掌舵" : null);
        if (problem == null) {
            other.sendMessage(plugin.text().prefixed("<" + color() + ">" + ItemRenderer.escape(player.getName())
                    + " 把你加成木筏的共用成員<gray>（/sea visit " + ItemRenderer.escape(player.getName()) + " 直接去）"));
        }
        redraw();
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        if (slot == SLOT_BACK) {
            new LifeMenu(plugin, player, service).open();
            return;
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            page += slot == SLOT_NEXT ? 1 : -1;
            redraw();
            return;
        }
        if (!acceptAction(click)) {
            return;
        }
        final Runnable action = click.isShiftClick()
                ? (click.isLeftClick() ? shiftLeft.get(slot) : click.isRightClick() ? shiftRight.get(slot) : null)
                : (click.isLeftClick() ? left.get(slot) : click.isRightClick() ? right.get(slot) : null);
        if (action != null) {
            action.run();
        }
    }
}
