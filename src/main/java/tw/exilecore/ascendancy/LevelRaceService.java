package tw.exilecore.ascendancy;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.bounty.BountyConfig;
import tw.exilecore.bounty.BountyService;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemRenderer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 衝等活動（2026-10）：每個昇華職業第一個升到 {@code level_race.level}（100）級的角色，帳號拿到那個昇華的專屬稱號
 * （「巔峰」＋縮寫的昇華名稱，沒有加成）。每個稱號全服只有一個，拿了就沒了。
 * <p>
 * 誰拿走了記在資料表 {@code level_race}（昇華代號是主鍵：兩個人同時到也只有先寫進去的那個算）。
 * 檢查的時機：升級、選昇華（先到 100 級才昇華的也算）、登入（拿到時剛好下線、或稱號沒寫進帳號的補發）。
 */
public final class LevelRaceService {

    /** 一筆紀錄：哪個昇華被誰拿走。 */
    public record Claim(String ascendancy, UUID account, long characterId, String characterName, String playerName, Instant at) {
    }

    /** 角色進度：這個角色到衝等等級時是哪個昇華（{@link #NONE} = 還沒昇華，之後第一次選的算）。 */
    static final String REACHED_AS = "race.reached_as";
    private static final String NONE = "-";

    private final ExileCore plugin;
    private final AscendancyConfig config;
    private final Map<String, Claim> claims = new LinkedHashMap<>();
    /** 正在寫資料庫的昇華（同一個 tick 好幾個人到也只送一次）。 */
    private final Set<String> pending = new HashSet<>();
    private boolean loaded;
    private boolean listening;

    LevelRaceService(final ExileCore plugin, final AscendancyConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    private AscendancyConfig.LevelRace race() {
        return config.levelRace();
    }

    /** 啟動（所有模組都啟用之後）：讀紀錄、登記稱號、登入時檢查。 */
    void start() {
        registerTitles();
        final BountyService bounties = plugin.bounties();
        if (bounties != null && !listening) {
            listening = true;
            bounties.addReadyListener(this::check);
        }
        if (plugin.database() == null) {
            return;
        }
        plugin.database().async(connection -> {
            final List<Claim> list = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ascendancy, account_uuid, character_id, character_name, player_name, claimed_at FROM level_race");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    list.add(claim(result));
                }
            }
            return list;
        }).whenComplete((list, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("衝等活動：讀不到紀錄（這次不發稱號，修好資料庫後重開）", error);
                return;
            }
            claims.clear();
            for (final Claim claim : list) {
                claims.put(claim.ascendancy(), claim);
            }
            loaded = true;
            plugin.getSLF4JLogger().info("衝等活動：{}（{} 級）；已經被拿走 {} / {} 個稱號", race().enabled() ? "開著" : "關著",
                    race().level(), claims.size() - (claims.containsKey(AscendancyConfig.LevelRace.FIRST_KEY) ? 1 : 0), config.ascendancies().size());
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                check(online);
            }
        }));
    }

    private static Claim claim(final ResultSet result) throws java.sql.SQLException {
        final Timestamp at = result.getTimestamp("claimed_at");
        return new Claim(result.getString("ascendancy"), UUID.fromString(result.getString("account_uuid")), result.getLong("character_id"),
                result.getString("character_name"), result.getString("player_name"), at == null ? Instant.EPOCH : at.toInstant());
    }

    /** 設定重讀之後：稱號名稱可能改了。 */
    void reloaded() {
        registerTitles();
        if (loaded) {
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                check(online);
            }
        }
    }

    /** 每個昇華的稱號登記到稱號系統（戴、顯示都認得；選單寫怎麼拿、被誰拿走）。 */
    private void registerTitles() {
        final BountyService bounties = plugin.bounties();
        if (bounties == null) {
            return;
        }
        final Map<String, BountyConfig.Title> titles = new LinkedHashMap<>();
        final Map<String, AscendancyConfig.Ascendancy> byTitle = new LinkedHashMap<>();
        for (final AscendancyConfig.Ascendancy ascendancy : config.ascendancies()) {
            final String id = AscendancyConfig.LevelRace.titleId(ascendancy.id());
            final String color = ascendancy.color() != null && ascendancy.color().startsWith("#") ? ascendancy.color() : "#f5b942";
            titles.put(id, new BountyConfig.Title(id, race().title(ascendancy), color, 0, List.of()));
            byTitle.put(id, ascendancy);
        }
        if (!race().firstTitle().isEmpty()) {
            titles.put(AscendancyConfig.LevelRace.FIRST_TITLE_ID,
                    new BountyConfig.Title(AscendancyConfig.LevelRace.FIRST_TITLE_ID, race().firstTitle(), FIRST_COLOR, 0, List.of()));
        }
        if (!race().reachTitle().isEmpty()) {
            titles.put(AscendancyConfig.LevelRace.REACH_TITLE_ID,
                    new BountyConfig.Title(AscendancyConfig.LevelRace.REACH_TITLE_ID, race().reachTitle(), REACH_COLOR, 0, List.of()));
        }
        bounties.registerTitles(titles);
        bounties.setTitleSource("level_race", new BountyService.TitleSource() {
            @Override
            public Set<String> owned(final Map<String, String> progress) {
                return Set.of(); // 拿到時寫進帳號（跟活動稱號一樣）
            }

            @Override
            public List<String> howTo(final Player player, final String id) {
                if (AscendancyConfig.LevelRace.FIRST_TITLE_ID.equals(id)) {
                    final Claim claim = claims.get(AscendancyConfig.LevelRace.FIRST_KEY);
                    if (claim != null) {
                        return List.of("<gray>全服第一個升到 <white>" + race().level() + "</white> 級：已經被 <white>"
                                + ItemRenderer.escape(claim.characterName()) + "</white>（" + ItemRenderer.escape(claim.playerName()) + "）拿走了",
                                "<dark_gray>全服只有一個");
                    }
                    return List.of("<gray>全服第一個升到 <white>" + race().level() + "</white> 級的角色拿到",
                            "<dark_gray>全服只有一個，拿了就沒了（沒有加成）");
                }
                if (AscendancyConfig.LevelRace.REACH_TITLE_ID.equals(id)) {
                    return List.of("<gray>任何角色升到 <white>" + race().level() + "</white> 級就拿到（不限量，帳號共用）",
                            reachBonusText());
                }
                final AscendancyConfig.Ascendancy ascendancy = byTitle.get(id);
                if (ascendancy == null) {
                    return null;
                }
                final Claim claim = claims.get(ascendancy.id());
                if (claim != null) {
                    return List.of("<gray>衝等活動：已經被 <white>" + ItemRenderer.escape(claim.characterName()) + "</white>（"
                            + ItemRenderer.escape(claim.playerName()) + "）拿走了", "<dark_gray>每個稱號全服只有一個");
                }
                return List.of("<gray>衝等活動：第一個升到 <white>" + race().level() + "</white> 級的" + ascendancy.colored() + "<gray>拿到",
                        "<dark_gray>全服只有一個，拿了就沒了（沒有加成）");
            }

            @Override
            public boolean listed(final Player player, final String id) {
                if (AscendancyConfig.LevelRace.FIRST_TITLE_ID.equals(id)) {
                    return race().enabled() || claims.containsKey(AscendancyConfig.LevelRace.FIRST_KEY);
                }
                if (AscendancyConfig.LevelRace.REACH_TITLE_ID.equals(id)) {
                    return true;
                }
                return race().enabled() || claims.containsKey(byTitle.get(id).id());
            }

            @Override
            public String sourceName(final String id) {
                if (AscendancyConfig.LevelRace.FIRST_TITLE_ID.equals(id)) {
                    return "全服第一個 " + race().level() + " 級";
                }
                if (AscendancyConfig.LevelRace.REACH_TITLE_ID.equals(id)) {
                    return "升到 " + race().level() + " 級";
                }
                final AscendancyConfig.Ascendancy ascendancy = byTitle.get(id);
                return ascendancy == null ? null : "衝等活動：第一個 " + race().level() + " 級的" + ascendancy.name();
            }
        });
    }

    /** 拿走了這個昇華的紀錄；沒有 null。 */
    public Claim claim(final String ascendancyId) {
        return claims.get(ascendancyId);
    }

    /** 升級、選昇華、登入時呼叫：夠格就搶這個昇華的稱號（已經是自己的就補發）。 */
    public void check(final Player player) {
        if (!loaded || plugin.database() == null) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state.isLoaded() ? state.character() : null;
        if (character == null) {
            return;
        }
        final String ascendancyId = state.progress().get(Ascension.CHOSEN);
        final AscendancyConfig.Ascendancy ascendancy = ascendancyId == null ? null : config.ascendancy(ascendancyId);
        // 到 100 級那一刻是哪個昇華就鎖死（還沒昇華的：鎖給之後第一次選的）——月卡換昇華不能再搶別的稱號
        String reachedAs = state.progress().get(REACHED_AS);
        if (character.level() >= race().level()) {
            final String now = ascendancy == null ? NONE : ascendancy.id();
            if (reachedAs == null || (NONE.equals(reachedAs) && !NONE.equals(now))) {
                reachedAs = now;
                plugin.characters().setProgress(player, REACHED_AS, now);
            }
        }
        if (character.level() >= race().level()) {
            grantReach(player); // 燃星者：到了就有（不限量；已經有了什麼都不做）
        }
        claimFirst(player, character);
        if (ascendancy == null) {
            return;
        }
        final Claim existing = claims.get(ascendancy.id());
        if (existing != null) {
            if (existing.account().equals(player.getUniqueId())) {
                grant(player, ascendancy, false); // 拿到時剛好下線、或寫帳號失敗：補發（已經有了什麼都不做）
            }
            return;
        }
        if (!race().enabled() || character.level() < race().level() || !ascendancy.id().equals(reachedAs)
                || !pending.add(ascendancy.id())) {
            return;
        }
        final Claim claim = new Claim(ascendancy.id(), player.getUniqueId(), character.id(), character.name(), player.getName(), Instant.now());
        plugin.database().async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO level_race (ascendancy, account_uuid, character_id, character_name, player_name, level, claimed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, claim.ascendancy());
                statement.setString(2, claim.account().toString());
                statement.setLong(3, claim.characterId());
                statement.setString(4, claim.characterName());
                statement.setString(5, claim.playerName());
                statement.setInt(6, race().level());
                statement.setTimestamp(7, Timestamp.from(claim.at()));
                statement.executeUpdate();
            }
            return claim;
        }).whenComplete((written, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(claim.ascendancy());
            if (error != null) {
                // 主鍵撞到（別台伺服器、或記憶體跟資料庫對不上）：讀回來照資料庫的；其他錯誤下次升級、登入再試
                plugin.getSLF4JLogger().warn("衝等活動：{} 的 {} 寫不進紀錄：{}", claim.characterName(), ascendancy.name(), error.toString());
                reloadOne(claim.ascendancy());
                return;
            }
            claims.put(claim.ascendancy(), claim);
            announce(claim, ascendancy);
            final Player online = plugin.getServer().getPlayer(claim.account());
            if (online != null) {
                grant(online, ascendancy, true);
            }
        }));
    }

    // ---- 2026-10：全服第一個 100 級（百星之首）、到 100 級就有（燃星者）、升到幾級全服廣播 ----

    private static final String FIRST_COLOR = "#ffd27f";
    private static final String REACH_COLOR = "#ff8a3d";

    private String reachBonusText() {
        return race().reachExperience() > 0 ? "<#5bd65b>擁有就有效（不用戴）：經驗 +" + number(race().reachExperience()) + "%" : "<dark_gray>沒有加成";
    }

    private static String number(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** 燃星者的經驗加成（0.05 = 多 5%）：帳號有這個稱號才有。 */
    public double experienceBonus(final Player player) {
        final BountyService bounties = plugin.bounties();
        if (bounties == null || race().reachTitle().isEmpty() || race().reachExperience() <= 0) {
            return 0;
        }
        return bounties.ownedTitles(player).contains(AscendancyConfig.LevelRace.REACH_TITLE_ID) ? race().reachExperience() / 100.0 : 0;
    }

    private void grantReach(final Player player) {
        final BountyService bounties = plugin.bounties();
        if (bounties == null || race().reachTitle().isEmpty() || bounties.ownedTitles(player).contains(AscendancyConfig.LevelRace.REACH_TITLE_ID)) {
            return;
        }
        final String problem = bounties.grantTitle(player, AscendancyConfig.LevelRace.REACH_TITLE_ID);
        if (problem != null) {
            plugin.getSLF4JLogger().warn("{} 的稱號 {} 發不出去：{}（下次登入再補）", player.getName(), race().reachTitle(), problem);
            return;
        }
        player.sendMessage(plugin.text().prefixed("<" + REACH_COLOR + ">升到 " + race().level() + " 級：<gray>你拿到了稱號 <" + REACH_COLOR + ">「"
                + ItemRenderer.escape(race().reachTitle()) + "」</" + REACH_COLOR + ">"
                + (race().reachExperience() > 0 ? "<gray>，整個帳號經驗 <#5bd65b>+" + number(race().reachExperience()) + "%</#5bd65b>" : "")
                + "<gray>（已經戴上，/title 可以換）"));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        plugin.players().markDirty(player);
    }

    /** 百星之首：全服第一個到 level 級的角色（跟昇華無關）。紀錄跟昇華的放在同一張表（代號 {@link AscendancyConfig.LevelRace#FIRST_KEY}）。 */
    private void claimFirst(final Player player, final CharacterRecord character) {
        if (race().firstTitle().isEmpty()) {
            return;
        }
        final String key = AscendancyConfig.LevelRace.FIRST_KEY;
        final Claim existing = claims.get(key);
        if (existing != null) {
            if (existing.account().equals(player.getUniqueId())) {
                grantFirst(player, false);
            }
            return;
        }
        if (!race().enabled() || character.level() < race().level() || !pending.add(key)) {
            return;
        }
        final Claim claim = new Claim(key, player.getUniqueId(), character.id(), character.name(), player.getName(), Instant.now());
        plugin.database().async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO level_race (ascendancy, account_uuid, character_id, character_name, player_name, level, claimed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, key);
                statement.setString(2, claim.account().toString());
                statement.setLong(3, claim.characterId());
                statement.setString(4, claim.characterName());
                statement.setString(5, claim.playerName());
                statement.setInt(6, race().level());
                statement.setTimestamp(7, Timestamp.from(claim.at()));
                statement.executeUpdate();
            }
            return claim;
        }).whenComplete((written, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(key);
            if (error != null) {
                plugin.getSLF4JLogger().warn("百星之首：{} 寫不進紀錄：{}", claim.characterName(), error.toString());
                reloadOne(key);
                return;
            }
            claims.put(key, claim);
            final String message = "<" + FIRST_COLOR + ">★ <white>" + ItemRenderer.escape(claim.characterName()) + "</white><gray>（"
                    + ItemRenderer.escape(claim.playerName()) + "）是全服第一個升到 " + race().level() + " 級的冒險者，拿走了唯一的稱號 <"
                    + FIRST_COLOR + ">「" + ItemRenderer.escape(race().firstTitle()) + "」</" + FIRST_COLOR + ">";
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                online.sendMessage(plugin.text().prefixed(message));
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 0.9f);
            }
            plugin.getSLF4JLogger().info("百星之首：{}（{}）", claim.characterName(), claim.playerName());
            if (plugin.discord() != null) {
                plugin.discord().achievement("★ " + ItemRenderer.escape(claim.characterName()) + "（" + ItemRenderer.escape(claim.playerName())
                        + "）是全服第一個升到 " + race().level() + " 級的冒險者，拿走了唯一的稱號「" + ItemRenderer.escape(race().firstTitle()) + "」");
            }
            final Player online = plugin.getServer().getPlayer(claim.account());
            if (online != null) {
                grantFirst(online, true);
            }
        }));
    }

    private void grantFirst(final Player player, final boolean fresh) {
        final BountyService bounties = plugin.bounties();
        if (bounties == null || bounties.ownedTitles(player).contains(AscendancyConfig.LevelRace.FIRST_TITLE_ID)) {
            return;
        }
        final String problem = bounties.grantTitle(player, AscendancyConfig.LevelRace.FIRST_TITLE_ID);
        if (problem != null) {
            plugin.getSLF4JLogger().warn("百星之首：{} 的稱號發不出去：{}（下次登入再補）", player.getName(), problem);
            return;
        }
        player.sendMessage(plugin.text().prefixed("<" + FIRST_COLOR + ">你拿到了全服唯一的稱號 「" + ItemRenderer.escape(race().firstTitle())
                + "」<gray>（已經戴上，/title 可以換）"));
        if (fresh) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
    }

    /** 升級（from → to）跨過 announce_levels 裡的等級：全服廣播（Discord 也發）。 */
    public void levelUp(final Player player, final int from, final int to) {
        final CharacterRecord character = plugin.players().get(player).character();
        if (character == null || from >= to) {
            return;
        }
        for (final int level : race().announceLevels()) {
            if (from < level && to >= level) {
                final String message = "<#ffd27f>✦ <white>" + ItemRenderer.escape(character.name()) + "</white><gray>（"
                        + ItemRenderer.escape(player.getName()) + "）升到了 <#ffd27f>" + level + " 級</#ffd27f><gray>！";
                for (final Player online : plugin.getServer().getOnlinePlayers()) {
                    online.sendMessage(plugin.text().prefixed(message));
                }
                if (plugin.discord() != null) {
                    plugin.discord().achievement("✦ " + ItemRenderer.escape(character.name()) + "（" + ItemRenderer.escape(player.getName())
                            + "）升到了 " + level + " 級！");
                }
            }
        }
    }

    private void reloadOne(final String ascendancyId) {
        plugin.database().async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ascendancy, account_uuid, character_id, character_name, player_name, claimed_at FROM level_race WHERE ascendancy = ?")) {
                statement.setString(1, ascendancyId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? claim(result) : null;
                }
            }
        }).whenComplete((claim, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (claim != null) {
                claims.put(claim.ascendancy(), claim);
            }
        }));
    }

    /** 發稱號（帳號共用）並戴上；fresh = 剛拿到（放音效、說一聲）。 */
    private void grant(final Player player, final AscendancyConfig.Ascendancy ascendancy, final boolean fresh) {
        final BountyService bounties = plugin.bounties();
        if (bounties == null) {
            return;
        }
        final String id = AscendancyConfig.LevelRace.titleId(ascendancy.id());
        if (bounties.ownedTitles(player).contains(id)) {
            return;
        }
        final String problem = bounties.grantTitle(player, id);
        if (problem != null) {
            plugin.getSLF4JLogger().warn("衝等活動：{} 的稱號 {} 發不出去：{}（下次登入再補）", player.getName(), id, problem);
            return;
        }
        player.sendMessage(plugin.text().prefixed("<#f5b942>衝等活動：<gray>你拿到了專屬稱號 <" + titleColor(ascendancy) + ">「"
                + ItemRenderer.escape(race().title(ascendancy)) + "」</" + titleColor(ascendancy) + "><gray>（已經戴上，/title 可以換）"));
        if (fresh) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
    }

    private static String titleColor(final AscendancyConfig.Ascendancy ascendancy) {
        return ascendancy.color() != null && ascendancy.color().startsWith("#") ? ascendancy.color() : "#f5b942";
    }

    private void announce(final Claim claim, final AscendancyConfig.Ascendancy ascendancy) {
        final String message = "<#f5b942>衝等活動：<white>" + ItemRenderer.escape(claim.characterName()) + "</white><gray>（"
                + ItemRenderer.escape(claim.playerName()) + "）是第一個升到 " + race().level() + " 級的" + ascendancy.colored()
                + "<gray>，拿走了專屬稱號 <" + titleColor(ascendancy) + ">「" + ItemRenderer.escape(race().title(ascendancy)) + "」</"
                + titleColor(ascendancy) + ">";
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(plugin.text().prefixed(message));
        }
        plugin.getSLF4JLogger().info("衝等活動：{}（{}）拿走 {} 的稱號", claim.characterName(), claim.playerName(), ascendancy.name());
        if (plugin.discord() != null) {
            plugin.discord().achievement("衝等活動：" + ItemRenderer.escape(claim.characterName()) + "（" + ItemRenderer.escape(claim.playerName())
                    + "）是第一個升到 " + race().level() + " 級的" + ascendancy.name() + "，拿走了專屬稱號「" + ItemRenderer.escape(race().title(ascendancy)) + "」");
        }
    }
}
