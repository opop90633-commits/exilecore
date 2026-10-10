package tw.exilecore.ladder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.ladder.Ladder.Board;
import tw.exilecore.ladder.Ladder.Entry;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 排行榜：全部角色（含離線的）照等級、地圖、不死排。資料每 {@value #REFRESH_SECONDS} 秒從資料庫重讀一次（背景執行緒），
 * 聊天的 {@code /ladder} 與網頁的 {@code /ladder} 都用這份快取。沒有賽季重置，所以這就是長期的目標。
 */
public final class LadderService {

    public static final int REFRESH_SECONDS = 60;
    /** 最多排幾個（資料庫取前幾名）。 */
    static final int LIMIT = 200;

    private final ExileCore plugin;
    private volatile List<Entry> entries = List.of();
    /** 金錢榜（2026-10，只在網頁）：帳號的金幣（帳號共用），配上那個帳號等級最高的角色。 */
    private volatile List<GoldEntry> gold = List.of();

    record GoldEntry(String account, long gold, String character, String classId, int level) {
    }
    private volatile long refreshedMillis;
    private volatile boolean refreshing;

    public LadderService(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 快取的全部角色（照等級）。 */
    public List<Entry> entries() {
        return entries;
    }

    public long refreshedMillis() {
        return refreshedMillis;
    }

    /** 某個榜的前 limit 名。 */
    public List<Entry> top(final Board board, final int limit) {
        return sorted(board).subList(0, Math.min(limit, sorted(board).size()));
    }

    /** 某個角色在某個榜的名次（1 起；沒上榜是 0）。 */
    public int rank(final Board board, final String characterName) {
        final List<Entry> list = sorted(board);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().equals(characterName)) {
                return i + 1;
            }
        }
        return 0;
    }

    private List<Entry> sorted(final Board board) {
        return Ladder.sorted(entries, board);
    }

    /** 每分鐘：背景重讀（前一次還沒完就跳過）。 */
    public void tick() {
        if (System.currentTimeMillis() - refreshedMillis >= REFRESH_SECONDS * 1000L) {
            refresh();
        }
    }

    /** 從資料庫重讀（背景執行緒）。 */
    public void refresh() {
        if (refreshing || plugin.database() == null || !plugin.database().isConnected()) {
            return;
        }
        refreshing = true;
        plugin.database().async(connection -> {
            final List<Entry> list = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT c.id, c.name, a.name AS account, c.class_id, c.char_level, c.experience, c.deaths, c.playtime_seconds,
                           (SELECT COUNT(*) FROM progress p WHERE p.character_id = c.id AND p.progress_key LIKE 'atlas.%') AS maps,
                           (SELECT COUNT(*) FROM progress p WHERE p.character_id = c.id AND p.progress_key LIKE 'boss.%') AS bosses,
                           (SELECT p.progress_value FROM progress p WHERE p.character_id = c.id AND p.progress_key = 'mapbest') AS best
                    FROM characters c JOIN accounts a ON a.uuid = c.account_uuid
                    WHERE c.deleted_at IS NULL
                    ORDER BY c.char_level DESC, c.experience DESC
                    LIMIT ?""")) {
                statement.setInt(1, LIMIT);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        int best = 0;
                        final String bestText = rows.getString("best");
                        if (bestText != null) {
                            try {
                                best = Integer.parseInt(bestText.trim());
                            } catch (final NumberFormatException ignored) {
                                best = 0;
                            }
                        }
                        list.add(new Entry(rows.getLong("id"), rows.getString("name"), rows.getString("account"), rows.getString("class_id"),
                                rows.getInt("char_level"), rows.getLong("experience"), rows.getInt("deaths"), rows.getLong("playtime_seconds"),
                                rows.getInt("maps"), best, rows.getInt("bosses")));
                    }
                }
            }
            final List<GoldEntry> golds = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT a.name, a.gold,
                           (SELECT c.name FROM characters c WHERE c.account_uuid = a.uuid AND c.deleted_at IS NULL
                            ORDER BY c.char_level DESC, c.experience DESC LIMIT 1) AS cname,
                           (SELECT c.class_id FROM characters c WHERE c.account_uuid = a.uuid AND c.deleted_at IS NULL
                            ORDER BY c.char_level DESC, c.experience DESC LIMIT 1) AS cclass,
                           (SELECT MAX(c.char_level) FROM characters c WHERE c.account_uuid = a.uuid AND c.deleted_at IS NULL) AS clevel
                    FROM accounts a
                    WHERE a.gold > 0
                    ORDER BY a.gold DESC, a.name
                    LIMIT 100""")) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if (rows.getString("cname") == null) {
                            continue; // 沒有角色（全刪了）的帳號不上榜
                        }
                        golds.add(new GoldEntry(rows.getString(1), rows.getLong(2), rows.getString("cname"), rows.getString("cclass"),
                                rows.getInt("clevel")));
                    }
                }
            }
            gold = List.copyOf(golds);
            return list;
        }).whenComplete((list, error) -> {
            refreshing = false;
            if (error != null) {
                plugin.getSLF4JLogger().warn("排行榜讀取失敗", error);
                return;
            }
            entries = List.copyOf(list);
            refreshedMillis = System.currentTimeMillis();
        });
    }

    /** 給聊天欄：某個榜的前 count 名，加上看的人自己的名次。 */
    public List<String> lines(final Board board, final int count, final Player viewer) {
        final List<String> lines = new ArrayList<>();
        final List<Entry> list = sorted(board);
        lines.add("<#f5b942><bold>排行榜・" + board.title() + "</bold> <dark_gray>" + board.description() + "　"
                + (refreshedMillis == 0 ? "還在讀取" : "每 " + REFRESH_SECONDS + " 秒更新"));
        if (list.isEmpty()) {
            lines.add("<gray>  還沒有人上榜");
        }
        for (int i = 0; i < Math.min(count, list.size()); i++) {
            lines.add(line(i + 1, list.get(i), board, viewer));
        }
        if (viewer != null) {
            final String mine = plugin.players().get(viewer).character() == null ? null : plugin.players().get(viewer).character().name();
            final int rank = mine == null ? 0 : rank(board, mine);
            if (rank > count) {
                lines.add("<dark_gray>  …");
                lines.add(line(rank, list.get(rank - 1), board, viewer));
            } else if (rank == 0 && mine != null) {
                lines.add("<dark_gray>  你的角色還沒上這個榜" + (board == Board.DEATHLESS ? "（要 " + Ladder.DEATHLESS_MIN_LEVEL + " 級以上）"
                        : board == Board.MAPS ? "（完成一張地圖就會上）" : "（存檔後下一次更新就會出現）"));
            }
        }
        lines.add("<dark_gray>  /ladder、/ladder maps、/ladder deathless；網頁版在 /ladder 頁");
        return lines;
    }

    private String line(final int rank, final Entry entry, final Board board, final Player viewer) {
        final boolean mine = viewer != null && plugin.players().get(viewer).character() != null
                && plugin.players().get(viewer).character().name().equals(entry.name());
        final String medal = rank == 1 ? "<#f5b942>①" : rank == 2 ? "<#c0c0c0>②" : rank == 3 ? "<#cd7f32>③" : "<gray>" + rank + ".";
        final String detail = switch (board) {
            case LEVEL -> String.format(Locale.ROOT, "<white>Lv.%d <dark_gray>%s　地圖 %d 張%s", entry.level(), Ladder.playtime(entry.playtimeSeconds()),
                    entry.maps(), entry.bestTier() > 0 ? "・最高 T" + entry.bestTier() : "");
            case MAPS -> String.format(Locale.ROOT, "<white>%d 張 <dark_gray>最高 T%d　Lv.%d", entry.maps(), entry.bestTier(), entry.level());
            case DEATHLESS -> String.format(Locale.ROOT, "<white>死亡 %d <dark_gray>Lv.%d　地圖 %d 張", entry.deaths(), entry.level(), entry.maps());
        };
        return String.format(Locale.ROOT, "  %s %s%s%s <dark_gray>%s <gray>%s　%s", medal, mine ? "<green>➜ " : "", entry.classColor(),
                tw.exilecore.item.ItemRenderer.escape(entry.name()), entry.className(), detail, mine ? "<green>（你）" : "");
    }

    /** 給網頁：三個榜的前 100 名。 */
    public String webJson() {
        final JsonObject root = new JsonObject();
        root.addProperty("refreshed", refreshedMillis);
        root.addProperty("deathlessMinLevel", Ladder.DEATHLESS_MIN_LEVEL);
        final Set<String> online = new TreeSet<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final tw.exilecore.character.CharacterRecord character = plugin.players().get(player).character();
            if (character != null) {
                online.add(character.name());
            }
        }
        for (final Board board : Board.values()) {
            final JsonArray array = new JsonArray();
            int rank = 0;
            for (final Entry entry : top(board, 100)) {
                final JsonObject object = new JsonObject();
                object.addProperty("rank", ++rank);
                object.addProperty("name", entry.name());
                object.addProperty("account", entry.account());
                object.addProperty("class", entry.className());
                object.addProperty("classColor", entry.classColor().replace("<", "").replace(">", ""));
                object.addProperty("level", entry.level());
                object.addProperty("experience", entry.experience());
                object.addProperty("deaths", entry.deaths());
                object.addProperty("playtime", entry.playtimeSeconds());
                object.addProperty("maps", entry.maps());
                object.addProperty("bestTier", entry.bestTier());
                object.addProperty("bosses", entry.bosses());
                object.addProperty("online", online.contains(entry.name()));
                array.add(object);
            }
            root.add(board.name().toLowerCase(Locale.ROOT), array);
        }
        // 金錢榜（帳號的金幣）
        final Set<String> onlineAccounts = new TreeSet<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            onlineAccounts.add(player.getName());
        }
        final JsonArray golds = new JsonArray();
        int rank = 0;
        for (final GoldEntry entry : gold) {
            final tw.exilecore.character.CharacterClass characterClass = tw.exilecore.character.CharacterClass.parse(entry.classId(), null);
            final JsonObject object = new JsonObject();
            object.addProperty("rank", ++rank);
            object.addProperty("name", entry.character());
            object.addProperty("account", entry.account());
            object.addProperty("class", characterClass == null ? entry.classId() : characterClass.displayName());
            object.addProperty("classColor", (characterClass == null ? "gray" : characterClass.color()).replace("<", "").replace(">", ""));
            object.addProperty("level", entry.level());
            object.addProperty("gold", entry.gold());
            object.addProperty("online", onlineAccounts.contains(entry.account()));
            golds.add(object);
        }
        root.add("gold", golds);
        return root.toString();
    }
}
