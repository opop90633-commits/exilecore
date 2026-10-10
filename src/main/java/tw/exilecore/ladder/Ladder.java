package tw.exilecore.ladder;

import tw.exilecore.character.CharacterClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 排行榜的純邏輯：榜的種類、一個角色的資料、排序。不依賴 Bukkit（測試用）。 */
public final class Ladder {

    /** 不死榜：至少幾級才上榜。 */
    public static final int DEATHLESS_MIN_LEVEL = 30;

    private Ladder() {
    }

    public enum Board {
        LEVEL("等級", "等級、經驗"),
        MAPS("地圖", "完成過幾張不同的地圖、最高階"),
        DEATHLESS("不死", DEATHLESS_MIN_LEVEL + " 級以上死得最少、等級最高");

        private final String title;
        private final String description;

        Board(final String title, final String description) {
            this.title = title;
            this.description = description;
        }

        public String title() {
            return title;
        }

        public String description() {
            return description;
        }

        public static Board parse(final String text) {
            if (text == null) {
                return LEVEL;
            }
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "maps", "map", "地圖", "atlas", "星圖" -> MAPS;
                case "deathless", "deaths", "不死", "hc", "硬核" -> DEATHLESS;
                default -> LEVEL;
            };
        }
    }

    /**
     * 一個角色。
     *
     * @param maps     完成過幾張不同的地圖
     * @param bestTier 完成過的最高階（0 = 沒打過地圖）
     * @param bosses   殺過幾隻劇情區域 Boss（首殺）
     */
    public record Entry(long id, String name, String account, String classId, int level, long experience, int deaths, long playtimeSeconds,
                        int maps, int bestTier, int bosses) {

        public String className() {
            final CharacterClass characterClass = CharacterClass.parse(classId, null);
            return characterClass == null ? classId : characterClass.displayName();
        }

        public String classColor() {
            final CharacterClass characterClass = CharacterClass.parse(classId, null);
            return characterClass == null ? "<gray>" : characterClass.color();
        }
    }

    /** 等級榜：等級高、經驗多的在前。 */
    public static final Comparator<Entry> BY_LEVEL = Comparator.comparingInt(Entry::level).reversed()
            .thenComparing(Comparator.comparingLong(Entry::experience).reversed()).thenComparing(Entry::name);
    /** 地圖榜：完成的地圖多、最高階高、等級高的在前。 */
    public static final Comparator<Entry> BY_MAPS = Comparator.comparingInt(Entry::maps).reversed()
            .thenComparing(Comparator.comparingInt(Entry::bestTier).reversed()).thenComparing(BY_LEVEL);
    /** 不死榜：死得少、等級高的在前。 */
    public static final Comparator<Entry> BY_DEATHLESS = Comparator.comparingInt(Entry::deaths).thenComparing(BY_LEVEL);

    /** 某個榜的排序（不死榜只收 {@link #DEATHLESS_MIN_LEVEL} 級以上、地圖榜只收打過地圖的）。 */
    public static List<Entry> sorted(final List<Entry> entries, final Board board) {
        final List<Entry> list = new ArrayList<>();
        for (final Entry entry : entries) {
            if (board == Board.DEATHLESS && entry.level() < DEATHLESS_MIN_LEVEL) {
                continue;
            }
            if (board == Board.MAPS && entry.maps() <= 0) {
                continue;
            }
            list.add(entry);
        }
        list.sort(switch (board) {
            case LEVEL -> BY_LEVEL;
            case MAPS -> BY_MAPS;
            case DEATHLESS -> BY_DEATHLESS;
        });
        return list;
    }

    /** 遊玩時間：3 小時 20 分。 */
    public static String playtime(final long seconds) {
        final long hours = seconds / 3600;
        final long minutes = seconds % 3600 / 60;
        return hours > 0 ? hours + " 小時 " + minutes + " 分" : minutes + " 分";
    }

}
