package tw.exilecore.sponsor;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-23：累積贊助每一階的主打（sponsor.yml 的 tiers 底下；都不影響戰鬥）：
 * 免費的全服加倍（每天／每週／每月幾次）、每週的免費抽獎箱、到階就送的時裝、自訂上線訊息、升級煙火、
 * 所有時裝都能用、終身月卡、送的角色欄位。照「用到過的最高一階」（到階送的時裝是每一階到了都送）。
 */
final class TierPerks {

    /** 帳號進度：免費加倍「週期代號|用了幾次」。 */
    static final String FREE_BOOST = "sp.fboost";
    /** 帳號進度：每週免費箱上次發的是哪一週。 */
    static final String WEEKLY_BOX = "sp.wbox";
    /** 帳號進度：自訂的上線訊息。 */
    static final String LOGIN_MESSAGE = "sp.login";
    /** 帳號進度：每月改名卡上次發的是哪一月。 */
    static final String MONTHLY_RENAME = "sp.mrename";
    /** 帳號進度：送過公會倉庫頁的公會（公會代號）。 */
    static final String GUILD_GIFT = "sp.ggift";
    /** 自訂上線訊息最長幾個字。 */
    static final int LOGIN_MAX = 40;

    private final ExileCore plugin;
    private final SponsorService service;

    TierPerks(final ExileCore plugin, final SponsorService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private SponsorConfig.Perks perks(final Player player) {
        final SponsorConfig.Tier tier = service.tier(player);
        return tier == null ? SponsorConfig.Perks.NONE : tier.perks();
    }

    SponsorConfig.Perks perksOf(final Player player) {
        return perks(player);
    }

    // ---- 週期 ----

    /** 這個週期的代號（day：日、week：ISO 週、month：年月）。 */
    static String periodKey(final String per, final LocalDate date) {
        return SponsorConfig.Perks.periodKey(per, date);
    }

    private LocalDate today() {
        return LocalDate.ofEpochDay(service.today());
    }

    // ---- 免費的全服加倍 ----

    /** 這個週期還剩幾次免費加倍（沒有這個主打 0）。 */
    int freeBoostsLeft(final Player player) {
        final SponsorConfig.Perks perks = perks(player);
        if (perks.freeBoosts() <= 0) {
            return 0;
        }
        return Math.max(0, perks.freeBoosts() - freeBoostsUsed(player, perks));
    }

    private int freeBoostsUsed(final Player player, final SponsorConfig.Perks perks) {
        final String text = service.value(player, FREE_BOOST);
        if (text == null) {
            return 0;
        }
        final int bar = text.indexOf('|');
        if (bar < 0 || !text.substring(0, bar).equals(periodKey(perks.freePer(), today()))) {
            return 0; // 換週期了
        }
        return (int) SponsorService.parse(text.substring(bar + 1));
    }

    /** 用掉一次免費加倍：要寫進帳號的值（呼叫端跟加倍一起寫）；沒有剩回傳 null。 */
    Map<String, String> useFreeBoost(final Player player) {
        final SponsorConfig.Perks perks = perks(player);
        if (freeBoostsLeft(player) <= 0) {
            return null;
        }
        return Map.of(FREE_BOOST, periodKey(perks.freePer(), today()) + "|" + (freeBoostsUsed(player, perks) + 1));
    }

    // ---- 每週免費箱、到階送的時裝、終身月卡：上線、升階、換日時補 ----

    /** 上線、升階、換日：該發的發一發。 */
    void refresh(final Player player) {
        if (!service.loaded(player)) {
            return;
        }
        final SponsorConfig.Perks perks = perks(player);
        final Map<String, String> values = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();
        // 每週免費箱（跟月卡的免費箱一樣累積，/sponsor 開）
        if (perks.weeklyBoxes() > 0 && !service.config().freeBox().isEmpty()) {
            final String week = periodKey("week", today());
            if (!week.equals(service.value(player, WEEKLY_BOX))) {
                values.put(WEEKLY_BOX, week);
                values.put(SponsorService.FREE_BOXES, String.valueOf(service.freeBoxes(player) + perks.weeklyBoxes()));
                notes.add("本週的免費抽獎箱 +" + perks.weeklyBoxes() + "（現在 " + (service.freeBoxes(player) + perks.weeklyBoxes()) + " 箱）");
            }
        }
        // 到階就送的時裝（每一階到了都送）
        final long total = service.total(player);
        final List<String> givenCosmetics = new ArrayList<>();
        for (final SponsorConfig.Tier tier : service.config().tiers()) {
            if (total < tier.amount()) {
                continue;
            }
            for (final String id : tier.perks().cosmetics()) {
                final SponsorConfig.Cosmetic cosmetic = service.config().cosmetics().get(id);
                if (cosmetic != null && service.value(player, SponsorService.COSMETIC + id) == null) {
                    values.put(SponsorService.COSMETIC + id, "1");
                    givenCosmetics.add(cosmetic.name());
                }
            }
        }
        if (!givenCosmetics.isEmpty()) {
            notes.add("累積贊助送的時裝：" + String.join("、", givenCosmetics) + "（贊助 → 裝備幻化 套上去）");
        }
        // 每月改名卡（2026-10-23 晚）
        if (perks.monthlyRenameCards() > 0) {
            final String month = periodKey("month", today());
            if (!month.equals(service.value(player, MONTHLY_RENAME))) {
                values.put(MONTHLY_RENAME, month);
                values.put(SponsorService.RENAME, String.valueOf(service.renameCards(player) + perks.monthlyRenameCards()));
                notes.add("本月的改名卡 +" + perks.monthlyRenameCards() + "（/char rename 用）");
            }
        }
        // 送公會倉庫頁（一個公會送一次；換公會再送）
        String guildGift = null;
        if (perks.guildStashPages() > 0 && plugin.guilds() != null) {
            final tw.exilecore.guild.Guild guild = plugin.guilds().guildOf(player.getUniqueId());
            if (guild != null && !String.valueOf(guild.id()).equals(service.value(player, GUILD_GIFT))) {
                values.put(GUILD_GIFT, String.valueOf(guild.id()));
                guildGift = guild.name();
            }
        }
        // 終身月卡：月卡的最後一天推到很久以後（沒買過就從今天開始發）
        if (perks.lifetimeCard()) {
            final long[] card = service.card(player);
            final long forever = service.today() + 365L * 100;
            if (card == null) {
                values.put(SponsorService.CARD, service.today() + "|" + forever + "|" + (service.today() - 1));
                notes.add("終身月卡開通了：每天自動發月卡幣、時裝幣與免費抽獎箱");
            } else if (card[1] < forever) {
                values.put(SponsorService.CARD, card[0] + "|" + forever + "|" + card[2]);
                notes.add("月卡變成終身的了");
            }
        }
        if (values.isEmpty()) {
            return;
        }
        if (!service.accounts().commitAccount(player, values, "贊助：累積贊助的主打")) {
            return;
        }
        for (final String note : notes) {
            player.sendMessage(plugin.text().prefixed("<#ffd27f>✦ " + ItemRenderer.escape(note) + "<gray>　<white><click:run_command:'/sponsor'>/sponsor</click>"));
        }
        if (!givenCosmetics.isEmpty()) {
            plugin.getSLF4JLogger().info("[贊助] {} 收到累積贊助送的時裝：{}", player.getName(), String.join("、", givenCosmetics));
        }
        if (perks.lifetimeCard()) {
            service.payCard(player, false);
            plugin.players().updateListName(player);
        }
        if (guildGift != null) {
            plugin.guilds().addGiftedPages(player, perks.guildStashPages());
            player.sendMessage(plugin.text().prefixed("<#ffd27f>✦ 送了 " + perks.guildStashPages() + " 頁倉庫給你的公會「" + ItemRenderer.escape(guildGift) + "」"));
        }
    }

    // ---- 上線公告、進場特效（2026-10-23 晚）----

    boolean joinAnnounce(final Player player) {
        return perks(player).joinAnnounce();
    }

    /** 上線：全服一聲音效（公告的那一行是上線訊息）。 */
    void announceJoin(final Player player) {
        for (final Player each : plugin.getServer().getOnlinePlayers()) {
            each.playSound(each.getLocation(), org.bukkit.Sound.BLOCK_BELL_RESONATE, 0.5f, 1.4f);
        }
    }

    /** 進營地、進地圖：腳下一圈金色粒子 3 秒。 */
    void entryAura(final Player player) {
        if (!perks(player).entryAura()) {
            return;
        }
        final int[] left = {12};
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (left[0]-- <= 0 || !player.isOnline()) {
                    cancel();
                    return;
                }
            final org.bukkit.Location at = player.getLocation();
            final double angle = (12 - left[0]) * Math.PI / 6;
            for (int i = 0; i < 12; i++) {
                final double a = angle + i * Math.PI / 6;
                at.getWorld().spawnParticle(org.bukkit.Particle.DUST, at.getX() + Math.cos(a) * 1.2, at.getY() + 0.1, at.getZ() + Math.sin(a) * 1.2,
                        1, 0, 0, 0, 0, new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(0xffd24a), 1.2f));
            }
                at.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, at.getX(), at.getY() + 1, at.getZ(), 2, 0.3, 0.5, 0.3, 0.01);
            }
        }.runTaskTimer(plugin, 1, 5);
    }

    // ---- 自訂上線訊息 ----

    /** 可以自訂上線訊息。 */
    boolean canLoginMessage(final Player player) {
        return perks(player).loginMessage();
    }

    /** 設定（null = 拿掉）；回傳問題，成功 null。 */
    String setLoginMessage(final Player player, final String raw) {
        if (!service.loaded(player)) {
            return "帳號資料載入中，請稍等";
        }
        if (!canLoginMessage(player)) {
            return "自訂上線訊息要累積贊助到有這個功能的階級（/sponsor 看）";
        }
        if (raw == null) {
            service.accounts().writeProgress(player, LOGIN_MESSAGE, null);
            return null;
        }
        final String text = raw.replaceAll("[<>\\\\]", "").strip();
        if (text.isEmpty()) {
            return "要寫一句話";
        }
        if (text.codePointCount(0, text.length()) > LOGIN_MAX) {
            return "最多 " + LOGIN_MAX + " 個字";
        }
        final String bad = plugin.characters().config().nameFilter().problem(text);
        if (bad != null) {
            return bad;
        }
        service.accounts().writeProgress(player, LOGIN_MESSAGE, text);
        return null;
    }

    /** 上線時要廣播的那句（MiniMessage；沒設定、沒有這個功能 null）。最高階沒設定也有「【稱號】名字 上線了」。 */
    String loginMessage(final Player player) {
        final SponsorConfig.Perks perks = perks(player);
        final String text = perks.loginMessage() ? service.value(player, LOGIN_MESSAGE) : null;
        if ((text == null || text.isBlank()) && !perks.joinAnnounce()) {
            return null;
        }
        final String style = service.nameColor(player);
        final String name = ItemRenderer.escape(service.displayName(player));
        final String styled = service.nameBadge(player) + (style == null ? "<white>" + name + "</white>"
                : "<" + style + ">" + name + "</" + (style.startsWith("gradient:") ? "gradient" : style) + ">");
        final SponsorConfig.Tier tier = service.tier(player);
        final String title = perks.joinAnnounce() && tier != null ? "<" + tier.color() + ">【" + ItemRenderer.escape(tier.title()) + "】</" + tier.color() + ">" : "";
        return "<#ffd27f>✦ " + title + styled + " <gray>" + (text == null || text.isBlank() ? "上線了" : ItemRenderer.escape(text.strip()));
    }

    // ---- 升級煙火 ----

    boolean levelFireworks(final Player player) {
        return perks(player).levelFireworks();
    }

    /** 升級的煙火：在玩家頭上放一發（沒有這個主打不放）。 */
    void celebrateLevel(final Player player) {
        if (!levelFireworks(player)) {
            return;
        }
        final org.bukkit.Location at = player.getLocation().add(0, 1, 0);
        final java.util.random.RandomGenerator random = plugin.items().random();
        final org.bukkit.Color[] colors = {org.bukkit.Color.fromRGB(0xffd27f), org.bukkit.Color.fromRGB(0x9fd3ff), org.bukkit.Color.fromRGB(0xff9ad5),
                org.bukkit.Color.fromRGB(0x7fe0c0)};
        final org.bukkit.entity.Firework firework = at.getWorld().spawn(at, org.bukkit.entity.Firework.class, created -> {
            final org.bukkit.inventory.meta.FireworkMeta meta = created.getFireworkMeta();
            meta.addEffect(org.bukkit.FireworkEffect.builder().with(org.bukkit.FireworkEffect.Type.STAR)
                    .withColor(colors[random.nextInt(colors.length)]).withFade(colors[random.nextInt(colors.length)]).withTrail().withFlicker().build());
            meta.setPower(0);
            created.setFireworkMeta(meta);
            created.setShotAtAngle(false);
        });
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (firework.isValid()) {
                firework.detonate();
            }
        }, 1);
    }

    // ---- 所有時裝都能用 ----

    /** 這一階的「所有時裝都能用」：非限定的時裝不用抽、不用買就能套。 */
    boolean allCosmetics(final Player player, final SponsorConfig.Cosmetic cosmetic) {
        return cosmetic != null && !cosmetic.isLimited() && perks(player).allCosmetics();
    }

    /** 送的角色欄位。 */
    int characterSlots(final Player player) {
        return perks(player).characterSlots();
    }

    /** 選單上這一階主打的那幾行。 */
    static List<String> describe(final SponsorConfig.Perks perks) {
        final List<String> lines = new ArrayList<>();
        if (perks.freeBoosts() > 0) {
            lines.add("<#ffd27f>★ " + perks.freePerText() + "免費 <white>" + perks.freeBoosts() + "<#ffd27f> 次全服加倍<gray>（自己選種類，公告寫你的名字）");
        }
        if (perks.characterSlots() > 0) {
            lines.add("<#ffd27f>★ 角色欄位 <white>+" + perks.characterSlots());
        }
        if (perks.weeklyBoxes() > 0) {
            lines.add("<#ffd27f>★ 每週免費抽獎箱 <white>" + perks.weeklyBoxes() + "<#ffd27f> 箱");
        }
        if (!perks.cosmetics().isEmpty()) {
            lines.add("<#ffd27f>★ 專屬時裝 <white>" + perks.cosmetics().size() + "<#ffd27f> 件<gray>（到階就送）");
        }
        if (perks.loginMessage()) {
            lines.add("<#ffd27f>★ 自訂上線訊息<gray>（/sponsor login 一句話）");
        }
        if (perks.levelFireworks()) {
            lines.add("<#ffd27f>★ 升級放煙火");
        }
        if (perks.allCosmetics()) {
            lines.add("<#ffd27f>★ 所有非限定的時裝都能套");
        }
        if (perks.lifetimeCard()) {
            lines.add("<#ffd27f>★ 終身月卡");
        }
        if (perks.joinAnnounce()) {
            lines.add("<#ffd27f>★ 上線全服公告＋音效");
        }
        if (!perks.badge().isEmpty()) {
            lines.add("<#ffd27f>★ 名字前的徽章 <white>" + ItemRenderer.escape(perks.badge()));
        }
        if (perks.customNameColor()) {
            lines.add("<#ffd27f>★ 名字顏色自選<gray>（/sponsor color #rrggbb…、rainbow）");
        }
        if (perks.monthlyRenameCards() > 0) {
            lines.add("<#ffd27f>★ 每月改名卡 <white>" + perks.monthlyRenameCards() + "<#ffd27f> 張");
        }
        if (perks.guildStashPages() > 0) {
            lines.add("<#ffd27f>★ 送公會倉庫 <white>" + perks.guildStashPages() + "<#ffd27f> 頁");
        }
        if (perks.raftSize() > 0) {
            lines.add("<#ffd27f>★ 星骸之海的木筏可以多蓋 <white>" + perks.raftSize() + "<#ffd27f> 格");
        }
        if (perks.bedStamina() > 0) {
            lines.add("<#ffd27f>★ 吊床多回 <white>" + perks.bedStamina() + "<#ffd27f> 點精力");
        }
        if (perks.boostMinutes() > 0) {
            lines.add("<#ffd27f>★ 放的全服加倍多 <white>" + perks.boostMinutes() + "<#ffd27f> 分鐘");
        }
        if (perks.entryAura()) {
            lines.add("<#ffd27f>★ 進營地、進地圖的金色進場特效");
        }
        return lines;
    }
}
