package tw.exilecore.guild;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import tw.exilecore.ExileCore;
import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.economy.GoldService;
import tw.exilecore.gui.Dialogs;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 公會選單（/guild，或選單裡的「公會」）。五個分頁：
 * <ul>
 *   <li>總覽：公會資料、公會倉庫、捐獻、邀請、公告、倉庫權限、離開／解散；沒有公會時是建立與收到的邀請。</li>
 *   <li>成員：階級、在不在線、貢獻；會長、幹部在這裡升降階、請出。</li>
 *   <li>加成：每一級的公會加成與倉庫頁數。</li>
 *   <li>紀錄：最近誰加入、離開、捐獻、存取倉庫。</li>
 *   <li>排行：所有公會（等級、經驗）。</li>
 * </ul>
 */
public final class GuildMenu extends Menu {

    /** 分頁。 */
    public enum Tab {
        OVERVIEW("總覽", Material.WHITE_BANNER, "公會資料、倉庫、捐獻、邀請"),
        MEMBERS("成員", Material.PLAYER_HEAD, "階級、貢獻、升降階、請出"),
        APPLY("申請", Material.BELL, "入會申請：會長、幹部在這裡審核；沒有公會的人看自己送出的申請"),
        BONUSES("加成", Material.ENCHANTED_BOOK, "每一級的公會加成與倉庫頁數"),
        LOG("紀錄", Material.WRITABLE_BOOK, "誰加入、離開、捐獻、存取倉庫"),
        TOP("排行", Material.GOLD_INGOT, "所有公會的等級排行");

        final String title;
        final Material icon;
        final String about;

        Tab(final String title, final Material icon, final String about) {
            this.title = title;
            this.icon = icon;
            this.about = about;
        }
    }

    private static final int PER_PAGE = CONTENT_END - CONTENT_START;
    private static final int SLOT_INFO = slot(1, 4);
    private static final int SLOT_STASH = slot(2, 1);
    private static final int SLOT_DONATE = slot(2, 3);
    private static final int SLOT_INVITE = slot(2, 5);
    private static final int SLOT_MOTD = slot(2, 7);
    private static final int SLOT_WITHDRAW = slot(3, 1);
    private static final int SLOT_MINE = slot(3, 3);
    private static final int SLOT_LEAVE = slot(3, 5);
    private static final int SLOT_RENAME = slot(3, 7);
    private static final int SLOT_CREATE = slot(2, 3);
    private static final int SLOT_INVITES = slot(2, 5);
    private static final int SLOT_APPLIED = slot(2, 7);

    private final GuildService service;
    private Tab tab;
    private int page;
    /** 「成員」頁：格子 → 成員；「申請」頁：格子 → 申請的人。 */
    private final List<UUID> shown = new ArrayList<>();
    /** 「排行」頁、「申請」頁（沒有公會）：格子 → 公會。 */
    private final List<Long> shownGuilds = new ArrayList<>();
    /** 「紀錄」頁：讀好的紀錄（null = 還在讀）。 */
    private List<GuildRepository.LogRow> log;
    private boolean logRequested;

    public GuildMenu(final ExileCore plugin, final Player player, final GuildService service, final Tab tab, final int page) {
        super(plugin, player, 6, GuildService.COLOR + "公會");
        this.service = service;
        this.tab = tab;
        this.page = page;
    }

    private Guild guild() {
        return service.guildOf(player.getUniqueId());
    }

    private void reopen() {
        new GuildMenu(plugin, player, service, tab, page).open();
    }

    @Override
    protected void draw() {
        frame();
        shown.clear();
        shownGuilds.clear();
        final Guild guild = guild();
        final Tab[] tabs = Tab.values();
        final int[] slots = tabSlots(tabs.length);
        for (int i = 0; i < tabs.length; i++) {
            String title = tabs[i].title;
            if (tabs[i] == Tab.APPLY) {
                final int count = guild == null ? service.myApplications(player.getUniqueId()).size()
                        : guild.rank(player.getUniqueId()).atLeast(GuildRank.OFFICER) ? service.applicationsOf(guild).size() : 0;
                title += count > 0 ? "（" + count + "）" : "";
            }
            set(slots[i], tab(tabs[i].icon, title, tabs[i] == tab, List.of("<gray>" + tabs[i].about)));
        }
        int pages = 1;
        switch (tab) {
            case OVERVIEW -> drawOverview(guild);
            case MEMBERS -> pages = drawMembers(guild);
            case APPLY -> pages = drawApply(guild);
            case BONUSES -> drawBonuses(guild);
            case LOG -> drawLog(guild);
            case TOP -> pages = drawTop(guild);
        }
        if (pages > 1) {
            set(SLOT_PREV, pageButton(false, page, pages));
            set(SLOT_NEXT, pageButton(true, page, pages));
        }
        set(SLOT_HELP, helpButton(List.of(
                "<white>/guild create 標籤 名稱 <gray>建立（Lv." + service.config().createLevel() + "、"
                        + GoldService.format(service.config().createCost()) + " 金幣）",
                "<white>/guild invite 名字 <gray>邀請（會長、幹部）",
                "<white>/guild accept｜decline [公會] <gray>接受、拒絕邀請",
                "<white>/guild apply 公會 [留言] <gray>申請加入；<white>/guild unapply <gray>撤回",
                "<white>/guild approve｜reject 名字 <gray>批准、拒絕申請（會長、幹部）",
                "<white>/guild leave <gray>離開",
                "<white>/guild kick 名字 <gray>請出（會長、幹部）",
                "<white>/guild promote｜demote 名字 <gray>升成幹部、降成成員（會長）",
                "<white>/guild leader 名字 <gray>交出會長",
                "<white>/guild motd 文字 <gray>改公告（會長、幹部）",
                "<white>/guild donate 金幣 <gray>捐金幣換公會經驗",
                "<white>/guild stash <gray>公會倉庫（營地）",
                "<white>/guild info [公會]<gray>、<white>/guild top <gray>資料、排行",
                "<white>/guild disband <gray>解散（會長；倉庫要先清空）",
                "<white>/guild rename 標籤 名稱 <gray>改名（會長；要公會改名卡）",
                "<white>/g 訊息 <gray>公會頻道")));
        set(SLOT_CLOSE_BAR, closeButton());
    }

    // ---- 總覽 ----

    private void drawOverview(final Guild guild) {
        final GuildConfig config = service.config();
        if (guild == null) {
            set(SLOT_INFO, icon(Material.WHITE_BANNER, "<gray>你沒有公會",
                    "<gray>公會：最多 " + config.maxMembers() + " 人，有自己的頻道（/g）、倉庫、等級與加成。",
                    "<gray>殺怪、捐金幣都會讓公會升級；每一級的加成全公會的人都有。",
                    "<gray>名字旁邊會多一個公會標籤，例如 " + GuildService.COLOR + "[星骸]",
                    "",
                    "<gray>加入：請會長或幹部邀請你，或到「排行」頁點公會<white>申請加入"));
            final boolean level = plugin.players().get(player).level() >= config.createLevel();
            final boolean gold = plugin.gold().balance(player) >= config.createCost();
            set(SLOT_CREATE, icon(Material.ANVIL, "<green>建立公會",
                    (level ? "<green>✔" : "<red>✘") + " <gray>角色 Lv." + config.createLevel() + " 以上",
                    (gold ? "<green>✔" : "<red>✘") + " <gray>" + GoldService.format(config.createCost()) + " 金幣",
                    "<gray>名稱 " + config.nameMin() + "–" + config.nameMax() + " 個字、標籤 " + config.tagMin() + "–" + config.tagMax()
                            + " 個字（中英文、數字）",
                    "",
                    level && gold ? "<yellow>左鍵：輸入名稱與標籤" : "<red>條件還不夠"));
            final List<String> invites = service.inviteNames(player);
            if (invites.isEmpty()) {
                set(SLOT_INVITES, icon(Material.PAPER, "<gray>收到的邀請", "<dark_gray>目前沒有"));
            } else {
                final List<String> lore = new ArrayList<>();
                for (final String name : invites) {
                    lore.add("<white>" + ItemRenderer.escape(name) + "<gray> 邀請你");
                }
                lore.add("");
                lore.add("<yellow>左鍵：接受最新的邀請　右鍵：全部拒絕");
                set(SLOT_INVITES, glowing(icon(Material.PAPER, "<#f5b942>收到的邀請（" + invites.size() + "）", lore)));
            }
            final List<GuildService.Application> mine = service.myApplications(player.getUniqueId());
            final List<String> appliedLore = new ArrayList<>();
            for (final GuildService.Application application : mine) {
                final Guild target = service.guildById(application.guildId());
                if (target != null) {
                    appliedLore.add(GuildService.label(target) + " <dark_gray>" + GuildService.dateTime(application.createdAt()) + " 送出");
                }
            }
            if (appliedLore.isEmpty()) {
                appliedLore.add("<dark_gray>目前沒有");
            }
            appliedLore.add("");
            appliedLore.add("<yellow>左鍵：到「排行」挑公會申請" + (mine.isEmpty() ? "" : "　右鍵：全部撤回"));
            final ItemStack applied = icon(Material.BELL, mine.isEmpty() ? "<gray>我的入會申請" : "<#f5b942>我的入會申請（" + mine.size() + "）", appliedLore);
            set(SLOT_APPLIED, mine.isEmpty() ? applied : glowing(applied));
            return;
        }
        final GuildRank rank = guild.rank(player.getUniqueId());
        final boolean officer = rank.atLeast(GuildRank.OFFICER);
        final boolean leader = rank == GuildRank.LEADER;
        final List<String> describe = service.describe(guild);
        set(SLOT_INFO, glowing(icon(Material.WHITE_BANNER, describe.getFirst(), describe.subList(1, describe.size()))));

        final boolean inTown = player.hasPermission(ExileCore.PERMISSION_ADMIN)
                || plugin.zones() != null && plugin.zones().inTown(player.getLocation());
        set(SLOT_STASH, icon(Material.CHEST, "<#f5b942>公會倉庫",
                "<gray>" + service.stashPages(guild) + " 頁，每頁 45 格；大家共用"
                        + (guild.boughtPages() > 0 ? "（其中 " + guild.boughtPages() + " 頁是星幣買的）" : ""),
                "<gray>放：每個成員都可以　拿：<white>" + GuildService.withdrawText(guild.withdrawRank()),
                "",
                inTown ? "<yellow>左鍵：打開<dark_gray>（/guild stash）" : "<red>只能在營地打開"));
        set(SLOT_DONATE, icon(Material.GOLD_INGOT, "<#f5b942>捐獻",
                "<gray>金幣換公會經驗：每 " + GoldService.format(Math.round(1 / Math.max(1e-9, config.donateXpPerGold()))) + " 金幣 = 1 經驗",
                "<gray>一次最少 " + GoldService.format(config.donateMin()) + " 金幣；你有 <#f5b942>" + GoldService.format(plugin.gold().balance(player)),
                "<gray>公會經驗：" + service.experienceText(guild),
                "",
                "<yellow>左鍵：輸入要捐多少<dark_gray>（/guild donate 金幣）"));
        final int pendingApplications = officer ? service.applicationsOf(guild).size() : 0;
        final ItemStack inviteIcon = icon(Material.WRITABLE_BOOK, "<green>邀請玩家", "<gray>" + guild.size() + " / " + config.maxMembers() + " 人",
                pendingApplications > 0 ? "<#f5b942>有 " + pendingApplications + " 筆入會申請<gray>（右鍵：去審核）" : "<dark_gray>入會申請在「申請」頁",
                "", guild.size() < config.maxMembers() ? "<yellow>左鍵：從在線的玩家裡選（或輸入名字）" : "<red>公會滿了");
        set(SLOT_INVITE, officer
                ? (pendingApplications > 0 ? glowing(inviteIcon) : inviteIcon)
                : icon(Material.BOOK, "<gray>邀請玩家", "<dark_gray>只有會長與幹部能邀請"));
        final List<String> motd = new ArrayList<>();
        motd.add(guild.motd().isEmpty() ? "<dark_gray>（沒有公告）" : "<white>" + ItemRenderer.escape(guild.motd()));
        motd.add("");
        motd.add(officer ? "<yellow>左鍵：改公告　右鍵：清掉" : "<dark_gray>只有會長與幹部能改");
        set(SLOT_MOTD, icon(Material.OAK_SIGN, "<#f5b942>公告", motd));
        set(SLOT_WITHDRAW, icon(Material.TRIPWIRE_HOOK, "<#f5b942>倉庫權限",
                "<gray>誰能從公會倉庫拿東西：<white>" + GuildService.withdrawText(guild.withdrawRank()),
                "<gray>放東西進去每個成員都可以",
                "",
                leader ? "<yellow>左鍵：換成下一種（所有成員 → 幹部以上 → 只有會長）" : "<dark_gray>只有會長能改"));
        final Guild.Member me = guild.member(player.getUniqueId());
        set(SLOT_MINE, icon(Material.EXPERIENCE_BOTTLE, "<white>我的階級：" + rankText(rank),
                "<gray>貢獻的公會經驗：<white>" + GoldService.format(me.contribution()),
                "<gray>加入：<white>" + GuildService.date(me.joinedAt()),
                "<dark_gray>殺怪（掉落有份就算）、捐獻都會加貢獻"));
        if (leader) {
            final long cards = plugin.sponsors() == null ? 0 : plugin.sponsors().guildRenameCards(player);
            final boolean free = plugin.sponsors() == null || player.hasPermission(ExileCore.PERMISSION_ADMIN);
            set(SLOT_RENAME, icon(Material.NAME_TAG, "<#f5b942>公會改名",
                    "<gray>名稱與標籤一起改（可以只改其中一個）",
                    free ? "<gray>管理員不用改名卡" : "<gray>要用一張公會改名卡；你有 <white>" + cards + "<gray> 張",
                    free || cards > 0 ? "" : "<dark_gray>星幣商城（/sponsor）可以買",
                    free || cards > 0 ? "<yellow>左鍵：輸入新的名稱與標籤" : "<red>沒有公會改名卡"));
            set(SLOT_LEAVE, icon(Material.TNT, "<red>解散公會",
                    "<gray>等級、經驗、紀錄全部消失，救不回來",
                    "<gray>公會倉庫要先清空",
                    guild.size() > 1 ? "<dark_gray>只是想離開：先 /guild leader 名字 把會長交出去" : "",
                    "<yellow>左鍵：解散（會再問一次）"));
        } else {
            set(SLOT_LEAVE, icon(Material.OAK_DOOR, "<red>離開公會", "<gray>貢獻不會跟著你走", "", "<yellow>左鍵：離開（會再問一次）"));
        }
    }

    private static String rankText(final GuildRank rank) {
        return switch (rank) {
            case LEADER -> "<#f5b942>★ 會長";
            case OFFICER -> "<#7fd4ff>◆ 幹部";
            case MEMBER -> "<white>成員";
        };
    }

    // ---- 成員 ----

    private int drawMembers(final Guild guild) {
        if (guild == null) {
            set(SLOT_INFO, icon(Material.PLAYER_HEAD, "<gray>你沒有公會"));
            return 1;
        }
        final List<Guild.Member> members = new ArrayList<>(guild.members());
        members.sort(Comparator.comparingInt((Guild.Member member) -> member.rank().code()).reversed()
                .thenComparing(Comparator.comparingLong(Guild.Member::contribution).reversed())
                .thenComparingLong(Guild.Member::joinedAt));
        final int pages = Math.max(1, (members.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        final GuildRank mine = guild.rank(player.getUniqueId());
        for (int i = 0; i < PER_PAGE; i++) {
            final int index = page * PER_PAGE + i;
            if (index >= members.size()) {
                break;
            }
            final Guild.Member member = members.get(index);
            shown.add(member.uuid());
            set(CONTENT_START + i, memberIcon(member, mine));
        }
        return pages;
    }

    private ItemStack memberIcon(final Guild.Member member, final GuildRank mine) {
        final Player online = plugin.getServer().getPlayer(member.uuid());
        final boolean self = member.uuid().equals(player.getUniqueId());
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>階級：" + rankText(member.rank()));
        lore.add(online != null && (self || player.canSee(online)) ? "<green>在線" : "<dark_gray>離線");
        lore.add("<gray>貢獻：<white>" + GoldService.format(member.contribution()));
        lore.add("<gray>加入：<white>" + GuildService.date(member.joinedAt()));
        if (!self && mine == GuildRank.LEADER) {
            lore.add("");
            lore.add(member.rank() == GuildRank.OFFICER ? "<yellow>Shift＋左鍵：降成成員" : "<yellow>Shift＋左鍵：升成幹部");
            lore.add("<red>Shift＋右鍵：請出公會");
            lore.add("<dark_gray>交出會長：/guild leader " + ItemRenderer.escape(member.name()));
        } else if (!self && mine == GuildRank.OFFICER && member.rank() == GuildRank.MEMBER) {
            lore.add("");
            lore.add("<red>Shift＋右鍵：請出公會");
        }
        final String mark = member.rank() == GuildRank.LEADER ? "<#f5b942>★ " : member.rank() == GuildRank.OFFICER ? "<#7fd4ff>◆ " : "<white>";
        final ItemStack head = icon(Material.PLAYER_HEAD, mark + ItemRenderer.escape(member.name()) + (self ? " <dark_gray>（你）" : ""), lore);
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(plugin.getServer().getOfflinePlayer(member.uuid())));
        return online == null ? head : glowing(head);
    }

    // ---- 申請（2026-10） ----

    private int drawApply(final Guild guild) {
        if (guild == null) {
            final List<GuildService.Application> mine = service.myApplications(player.getUniqueId());
            if (mine.isEmpty()) {
                set(SLOT_INFO, icon(Material.BELL, "<gray>你沒有送出的入會申請",
                        "<gray>到「排行」頁點公會就能申請加入（最多同時 " + GuildService.APPLY_MAX + " 個）",
                        "<gray>也可以打 <white>/guild apply 公會 留言"));
                return 1;
            }
            for (int i = 0; i < mine.size() && i < PER_PAGE; i++) {
                final GuildService.Application application = mine.get(i);
                final Guild target = service.guildById(application.guildId());
                if (target == null) {
                    continue;
                }
                shownGuilds.add(target.id());
                final List<String> lore = new ArrayList<>();
                lore.add("<gray>送出：<white>" + GuildService.dateTime(application.createdAt()));
                lore.add("<gray>留言：<white>" + (application.message().isEmpty() ? "（沒有）" : ItemRenderer.escape(application.message())));
                lore.add("<gray>" + target.size() + " / " + service.config().maxMembers() + " 人");
                lore.add("");
                lore.add("<yellow>左鍵：改留言　<red>Shift＋右鍵：撤回");
                set(CONTENT_START + i, icon(Material.WHITE_BANNER, GuildService.label(target) + " <#f5b942>Lv." + target.level(), lore));
            }
            return 1;
        }
        if (!guild.rank(player.getUniqueId()).atLeast(GuildRank.OFFICER)) {
            set(SLOT_INFO, icon(Material.BELL, "<gray>入會申請", "<gray>只有會長與幹部能審核",
                    "<gray>想找人進來：請他打 <white>/guild apply " + ItemRenderer.escape(guild.name())));
            return 1;
        }
        final List<GuildService.Application> list = service.applicationsOf(guild);
        if (list.isEmpty()) {
            set(SLOT_INFO, icon(Material.BELL, "<gray>目前沒有入會申請",
                    "<gray>沒有公會的人可以在「排行」頁點你的公會申請，",
                    "<gray>或打 <white>/guild apply " + ItemRenderer.escape(guild.name()) + " 留言",
                    "<dark_gray>" + GuildService.APPLY_MILLIS / 86_400_000L + " 天沒處理的申請會自動取消"));
            return 1;
        }
        final int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        final boolean full = guild.size() >= service.config().maxMembers();
        for (int i = 0; i < PER_PAGE; i++) {
            final int index = page * PER_PAGE + i;
            if (index >= list.size()) {
                break;
            }
            final GuildService.Application application = list.get(index);
            shown.add(application.uuid());
            final Player online = plugin.getServer().getPlayer(application.uuid());
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>申請時 <white>Lv." + application.level());
            lore.add(online != null && player.canSee(online) ? "<green>在線" : "<dark_gray>離線（批准了也會直接加入）");
            lore.add("<gray>送出：<white>" + GuildService.dateTime(application.createdAt()));
            lore.add("<gray>留言：<white>" + (application.message().isEmpty() ? "（沒有）" : ItemRenderer.escape(application.message())));
            lore.add("");
            lore.add(full ? "<red>公會滿了，沒辦法批准" : "<green>左鍵：批准");
            lore.add("<red>Shift＋右鍵：拒絕");
            final ItemStack head = icon(Material.PLAYER_HEAD, "<white>" + ItemRenderer.escape(application.name()), lore);
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(plugin.getServer().getOfflinePlayer(application.uuid())));
            set(CONTENT_START + i, online == null ? head : glowing(head));
        }
        return pages;
    }

    // ---- 加成 ----

    private void drawBonuses(final Guild guild) {
        final GuildConfig config = service.config();
        final int level = guild == null ? 0 : guild.level();
        int slot = CONTENT_START;
        final List<String> head = new ArrayList<>();
        if (guild != null) {
            head.add("<gray>公會經驗：" + service.experienceText(guild));
        }
        head.add("<gray>怎麼拿公會經驗：");
        head.add("<gray>　殺怪（掉落有份的每個成員各算一次）");
        head.add("<gray>　　普通 " + config.killXp(tw.exilecore.monster.MonsterRarity.NORMAL, false) + "、魔法 "
                + config.killXp(tw.exilecore.monster.MonsterRarity.MAGIC, false) + "、稀有 "
                + config.killXp(tw.exilecore.monster.MonsterRarity.RARE, false) + "、傳奇 "
                + config.killXp(tw.exilecore.monster.MonsterRarity.UNIQUE, false) + "、Boss "
                + config.killXp(tw.exilecore.monster.MonsterRarity.UNIQUE, true));
        if (config.killLevelGap() > 0) {
            head.add("<gray>　　比自己低超過 " + config.killLevelGap() + " 級的怪不算");
        }
        head.add("<gray>　捐獻金幣（總覽的「捐獻」）");
        set(slot++, glowing(icon(Material.EXPERIENCE_BOTTLE, guild == null ? "<white>公會等級（最高 " + config.maxLevel() + " 級）"
                : "<#f5b942>公會 Lv." + level + " <gray>／ " + config.maxLevel(), head)));
        for (int at = 1; at <= config.maxLevel() && slot < CONTENT_END; at++) {
            final List<String> lore = new ArrayList<>();
            for (final GuildConfig.Bonus bonus : config.bonuses()) {
                if (bonus.level() == at) {
                    for (final String line : StatLines.describeAll(bonus.stats())) {
                        lore.add("<white>" + line);
                    }
                }
            }
            if (at > 1 && config.stashPages(at) > config.stashPages(at - 1)) {
                lore.add("<white>公會倉庫多一頁<gray>（等級給的共 " + config.stashPages(at) + " 頁）");
            }
            if (lore.isEmpty()) {
                continue;
            }
            final boolean reached = level >= at;
            lore.add("");
            lore.add(reached ? "<green>已經有了" : guild == null ? "<dark_gray>公會到 " + at + " 級就有" : "<dark_gray>還差 " + (at - level) + " 級");
            final ItemStack stack = withCount(icon(reached ? Material.ENCHANTED_BOOK : Material.BOOK,
                    (reached ? "<#f5b942>" : "<gray>") + "公會 Lv." + at, lore), at);
            set(slot++, reached ? onMark(stack) : stack);
        }
    }

    // ---- 紀錄 ----

    private void drawLog(final Guild guild) {
        if (guild == null) {
            set(SLOT_INFO, icon(Material.WRITABLE_BOOK, "<gray>你沒有公會"));
            return;
        }
        if (!logRequested) {
            logRequested = true;
            final long id = guild.id();
            service.loadLog(id, rows -> {
                log = rows;
                final Guild current = guild();
                if (tab == Tab.LOG && current != null && current.id() == id
                        && player.getOpenInventory().getTopInventory().getHolder() == this) {
                    redraw();
                }
            });
        }
        if (log == null) {
            set(SLOT_INFO, icon(Material.CLOCK, "<gray>讀取中…"));
            return;
        }
        if (log.isEmpty()) {
            set(SLOT_INFO, icon(Material.WRITABLE_BOOK, "<gray>還沒有紀錄"));
            return;
        }
        int slot = CONTENT_START;
        for (final GuildRepository.LogRow row : log) {
            if (slot >= CONTENT_END) {
                break;
            }
            final Material material = switch (row.action()) {
                case "存入" -> Material.HOPPER;
                case "取出" -> Material.CHEST_MINECART;
                case "捐獻" -> Material.GOLD_NUGGET;
                case "升級" -> Material.EXPERIENCE_BOTTLE;
                case "加入", "建立" -> Material.LIME_DYE;
                case "離開", "請出" -> Material.RED_DYE;
                default -> Material.PAPER;
            };
            set(slot++, icon(material, "<white>" + ItemRenderer.escape(row.action()) + " <gray>" + ItemRenderer.escape(row.actor()),
                    "<gray>" + ItemRenderer.escape(row.detail()), "<dark_gray>" + GuildService.dateTime(row.at())));
        }
    }

    // ---- 排行 ----

    private int drawTop(final Guild mine) {
        final List<Guild> list = service.top();
        if (list.isEmpty()) {
            set(SLOT_INFO, icon(Material.GOLD_INGOT, "<gray>還沒有公會"));
            return 1;
        }
        final int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < PER_PAGE; i++) {
            final int index = page * PER_PAGE + i;
            if (index >= list.size()) {
                break;
            }
            final Guild guild = list.get(index);
            final int rank = index + 1;
            final List<String> lore = new ArrayList<>(service.describe(guild).subList(1, service.describe(guild).size()));
            shownGuilds.add(guild.id());
            if (mine == null) {
                lore.add("");
                if (service.applied(player.getUniqueId(), guild)) {
                    lore.add("<green>已經申請了<gray>（左鍵：改留言　Shift＋右鍵：撤回）");
                } else if (guild.size() >= service.config().maxMembers()) {
                    lore.add("<red>公會滿了");
                } else {
                    lore.add("<yellow>左鍵：申請加入");
                }
            }
            final Material material = rank == 1 ? Material.YELLOW_BANNER : rank == 2 ? Material.LIGHT_GRAY_BANNER
                    : rank == 3 ? Material.ORANGE_BANNER : Material.WHITE_BANNER;
            final ItemStack stack = withCount(icon(material, "<gray>#" + rank + " " + GuildService.label(guild) + " <#f5b942>Lv." + guild.level()
                    + (guild == mine ? " <green>（你的公會）" : ""), lore), Math.min(64, rank));
            set(CONTENT_START + i, guild == mine ? highlight(stack) : stack);
        }
        return pages;
    }

    // ---- 點擊 ----

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        final Tab[] tabs = Tab.values();
        final int[] tabSlots = tabSlots(tabs.length);
        for (int i = 0; i < tabs.length; i++) {
            if (slot == tabSlots[i]) {
                if (tab != tabs[i]) {
                    tab = tabs[i];
                    page = 0;
                    log = null;
                    logRequested = false;
                    redraw();
                }
                return;
            }
        }
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            final int pages = tab == Tab.MEMBERS && guild() != null ? Math.max(1, (guild().size() + PER_PAGE - 1) / PER_PAGE)
                    : tab == Tab.APPLY && guild() != null ? Math.max(1, (service.applicationsOf(guild()).size() + PER_PAGE - 1) / PER_PAGE)
                    : tab == Tab.TOP ? Math.max(1, (service.top().size() + PER_PAGE - 1) / PER_PAGE) : 1;
            if (pages > 1) {
                page = stepPage(page, pages, slot == SLOT_NEXT, click);
                redraw();
            }
            return;
        }
        switch (tab) {
            case OVERVIEW -> clickOverview(slot, click);
            case MEMBERS -> clickMember(slot, click);
            case APPLY -> clickApply(slot, click);
            case TOP -> clickTop(slot, click);
            default -> {
            }
        }
    }

    private void clickOverview(final int slot, final ClickType click) {
        final Guild guild = guild();
        if (guild == null) {
            if (slot == SLOT_CREATE) {
                promptCreate();
            } else if (slot == SLOT_INVITES && !service.inviteNames(player).isEmpty() && acceptAction(click)) {
                if (click.isRightClick()) {
                    service.decline(player, null);
                } else {
                    service.accept(player, null);
                }
                redraw();
            } else if (slot == SLOT_APPLIED) {
                if (click.isRightClick()) {
                    if (acceptAction(click) && !service.myApplications(player.getUniqueId()).isEmpty()) {
                        service.withdraw(player, null);
                        redraw();
                    }
                } else {
                    tab = Tab.TOP;
                    page = 0;
                    redraw();
                }
            }
            return;
        }
        final GuildRank rank = guild.rank(player.getUniqueId());
        if (slot == SLOT_STASH) {
            service.openStash(player, 0);
        } else if (slot == SLOT_DONATE) {
            promptDonate();
        } else if (slot == SLOT_INVITE && rank.atLeast(GuildRank.OFFICER)) {
            if (click.isRightClick()) {
                tab = Tab.APPLY;
                page = 0;
                redraw();
            } else {
                promptInvite(guild);
            }
        } else if (slot == SLOT_MOTD && rank.atLeast(GuildRank.OFFICER)) {
            if (click.isRightClick()) {
                if (acceptAction(click) && !guild.motd().isEmpty()) {
                    service.motd(player, "");
                    redraw();
                }
            } else {
                plugin.dialogs().text(player, "<dark_gray>公會公告", List.of("<gray>成員上線時會看到；最多 " + service.config().motdLength() + " 個字"),
                        "公告", guild.motd(), service.config().motdLength(), text -> {
                            service.motd(player, text);
                            reopen();
                        }, this::reopen);
            }
        } else if (slot == SLOT_WITHDRAW && rank == GuildRank.LEADER) {
            if (acceptAction(click)) {
                service.cycleWithdrawRank(player);
                redraw();
            }
        } else if (slot == SLOT_RENAME && rank == GuildRank.LEADER) {
            final GuildConfig config = service.config();
            plugin.dialogs().form(player, "<dark_gray>公會改名", List.of(
                            "<gray>標籤 " + config.tagMin() + "–" + config.tagMax() + " 個字、名稱 " + config.nameMin() + "–" + config.nameMax()
                                    + " 個字；只能用中英文與數字。",
                            "<gray>改成功才會用掉公會改名卡。"),
                    List.of(new Dialogs.Text("tag", "標籤", guild.tag(), config.tagMax()), new Dialogs.Text("name", "公會名稱", guild.name(), config.nameMax())),
                    "改名", answers -> {
                        service.rename(player, answers.get("tag"), answers.get("name"));
                        reopen();
                    }, this::reopen);
        } else if (slot == SLOT_LEAVE) {
            if (rank == GuildRank.LEADER) {
                plugin.dialogs().confirm(player, "<dark_red>解散公會", List.of("<white>" + ItemRenderer.escape(guild.name()),
                                "<gray>等級、經驗、紀錄全部消失，救不回來。", "<gray>公會倉庫要先清空才能解散。"), "解散",
                        () -> {
                            service.disbandNow(player);
                            reopen();
                        }, this::reopen);
            } else {
                plugin.dialogs().confirm(player, "<dark_gray>離開公會", List.of("<white>" + ItemRenderer.escape(guild.name()),
                                "<gray>你貢獻的公會經驗留在公會，不會跟著你走。"), "離開",
                        () -> {
                            service.leave(player);
                            reopen();
                        }, this::reopen);
            }
        }
    }

    private void clickMember(final int slot, final ClickType click) {
        final int index = slot - CONTENT_START;
        final Guild guild = guild();
        if (guild == null || index < 0 || index >= shown.size()) {
            return;
        }
        final Guild.Member member = guild.member(shown.get(index));
        if (member == null || member.uuid().equals(player.getUniqueId()) || !acceptAction(click)) {
            return;
        }
        if (click == ClickType.SHIFT_RIGHT) {
            service.kick(player, member.name());
            redraw();
        } else if (click == ClickType.SHIFT_LEFT && guild.rank(player.getUniqueId()) == GuildRank.LEADER) {
            service.setRank(player, member.name(), member.rank() == GuildRank.OFFICER ? GuildRank.MEMBER : GuildRank.OFFICER);
            redraw();
        }
    }

    /** 「申請」頁：會長、幹部批准或拒絕；沒有公會的人改留言、撤回。 */
    private void clickApply(final int slot, final ClickType click) {
        final int index = slot - CONTENT_START;
        final Guild guild = guild();
        if (guild == null) {
            if (index >= 0 && index < shownGuilds.size()) {
                applyTo(service.guildById(shownGuilds.get(index)), click);
            }
            return;
        }
        if (index < 0 || index >= shown.size() || !acceptAction(click)) {
            return;
        }
        final UUID uuid = shown.get(index);
        GuildService.Application chosen = null;
        for (final GuildService.Application application : service.applicationsOf(guild)) {
            if (application.uuid().equals(uuid)) {
                chosen = application;
            }
        }
        if (chosen == null) {
            redraw();
            return;
        }
        if (click == ClickType.SHIFT_RIGHT) {
            service.reject(player, chosen.name());
            redraw();
        } else if (click.isLeftClick()) {
            service.approve(player, chosen.name());
            redraw();
        }
    }

    /** 「排行」頁：沒有公會的人點公會申請加入。 */
    private void clickTop(final int slot, final ClickType click) {
        final int index = slot - CONTENT_START;
        if (guild() != null || index < 0 || index >= shownGuilds.size()) {
            return;
        }
        applyTo(service.guildById(shownGuilds.get(index)), click);
    }

    /** 左鍵：申請（或改留言）；Shift＋右鍵：撤回。 */
    private void applyTo(final Guild target, final ClickType click) {
        if (target == null) {
            redraw();
            return;
        }
        final boolean applied = service.applied(player.getUniqueId(), target);
        if (click == ClickType.SHIFT_RIGHT) {
            if (applied && acceptAction(click)) {
                service.withdraw(player, target.name());
                redraw();
            }
            return;
        }
        if (!click.isLeftClick() || !applied && target.size() >= service.config().maxMembers()) {
            return;
        }
        String current = "";
        for (final GuildService.Application application : service.myApplications(player.getUniqueId())) {
            if (application.guildId() == target.id()) {
                current = application.message();
            }
        }
        plugin.dialogs().text(player, "<dark_gray>申請加入 " + GuildService.label(target), List.of(
                        "<gray>寫幾句話給會長、幹部看（可以不寫）：等級、在玩什麼職業、上線時間…",
                        "<gray>最多同時申請 " + GuildService.APPLY_MAX + " 個公會；" + GuildService.APPLY_MILLIS / 86_400_000L + " 天沒處理會自動取消。"),
                "留言", current, GuildService.APPLY_MESSAGE_MAX, text -> {
                    service.apply(player, target.name(), text);
                    reopen();
                }, this::reopen);
    }

    // ---- 輸入框 ----

    private void promptCreate() {
        final GuildConfig config = service.config();
        plugin.dialogs().form(player, "<dark_gray>建立公會", List.of(
                        "<gray>要 Lv." + config.createLevel() + " 與 " + GoldService.format(config.createCost()) + " 金幣。",
                        "<gray>標籤顯示在名字旁邊（" + config.tagMin() + "–" + config.tagMax() + " 個字），名稱 " + config.nameMin() + "–"
                                + config.nameMax() + " 個字；都只能用中英文與數字，建立後不能改。"),
                List.of(new Dialogs.Text("tag", "標籤", "", config.tagMax()), new Dialogs.Text("name", "公會名稱", "", config.nameMax())),
                "建立", answers -> {
                    service.create(player, answers.get("tag"), answers.get("name"));
                    reopen();
                }, this::reopen);
    }

    private void promptDonate() {
        plugin.dialogs().text(player, "<dark_gray>捐獻給公會", List.of(
                        "<gray>金幣換成公會經驗，捐了拿不回來。",
                        "<gray>一次最少 " + GoldService.format(service.config().donateMin()) + " 金幣；你有 "
                                + GoldService.format(plugin.gold().balance(player)) + "。"),
                "金幣", String.valueOf(service.config().donateMin()), 12, text -> {
                    try {
                        service.donate(player, Long.parseLong(text.replace(",", "").trim()));
                    } catch (final NumberFormatException e) {
                        player.sendMessage(plugin.text().prefixed("<red>要輸入數字"));
                    }
                    reopen();
                }, this::reopen);
    }

    /** 邀請：列出在線、還沒有公會的玩家（近的排前面），也可以直接輸入名字。 */
    private void promptInvite(final Guild guild) {
        if (guild.size() >= service.config().maxMembers()) {
            return;
        }
        final List<Player> candidates = new ArrayList<>();
        for (final Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.equals(player) && player.canSee(other) && service.guildOf(other.getUniqueId()) == null) {
                candidates.add(other);
            }
        }
        candidates.sort(Comparator.comparingDouble(other -> other.getWorld().equals(player.getWorld())
                ? other.getLocation().distanceSquared(player.getLocation()) : Double.MAX_VALUE));
        final List<Dialogs.Field> fields = new ArrayList<>();
        final String typed = "-";
        if (!candidates.isEmpty()) {
            final List<Dialogs.Option> options = new ArrayList<>();
            options.add(new Dialogs.Option(typed, "<gray>（用下面輸入的名字）"));
            for (final Player other : candidates.subList(0, Math.min(40, candidates.size()))) {
                options.add(new Dialogs.Option(other.getName(), ItemRenderer.escape(plugin.displayName(other))));
            }
            fields.add(new Dialogs.Choice("pick", "在線、還沒有公會的玩家", options, options.get(1).id()));
        }
        fields.add(new Dialogs.Text("name", candidates.isEmpty() ? "玩家名字" : "或輸入名字", "", 32));
        plugin.dialogs().form(player, "<dark_gray>邀請加入公會", List.of("<gray>對方會在聊天欄收到邀請，按 [接受] 就加入"),
                fields, "邀請", answers -> {
                    final String name = answers.get("name").trim();
                    final String picked = answers.get("pick");
                    final String target = !name.isEmpty() || picked == null || picked.isEmpty() || picked.equals(typed) ? name : picked;
                    if (target.isEmpty()) {
                        player.sendMessage(plugin.text().prefixed("<gray>沒有選人"));
                    } else {
                        service.invite(player, target);
                    }
                    reopen();
                }, this::reopen);
    }
}
