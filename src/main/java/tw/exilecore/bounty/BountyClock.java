package tw.exilecore.bounty;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * 每日、每週的重置（純函式，伺服器時區）：每天 0 點換一批每日委託，每週一 0 點換一週（ISO 週）。
 * 日期一律寫成字串（「2026-10-03」「2026-W40」），跟經濟報表一樣，備份還原、換資料庫都不會因為時區差一天。
 */
public final class BountyClock {

    private BountyClock() {
    }

    /** 「2026-10-03」。 */
    public static String day(final LocalDate date) {
        return date.toString();
    }

    /** 「2026-W40」（ISO 週：星期一開始）。 */
    public static String week(final LocalDate date) {
        return String.format(Locale.ROOT, "%d-W%02d", date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** 到明天 0 點還有多久。 */
    public static Duration untilNextDay(final ZonedDateTime now) {
        final ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());
        return Duration.between(now, next);
    }

    /** 到下週一 0 點還有多久。 */
    public static Duration untilNextWeek(final ZonedDateTime now) {
        final LocalDate monday = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return Duration.between(now, monday.atStartOfDay(now.getZone()));
    }

    /** 「3 天 4 小時」「5 小時 12 分」「12 分」（不到一分鐘算一分）。 */
    public static String describe(final Duration duration) {
        final long minutes = Math.max(1, (duration.getSeconds() + 59) / 60);
        final long days = minutes / (24 * 60);
        final long hours = minutes / 60 % 24;
        final long rest = minutes % 60;
        if (days > 0) {
            return days + " 天" + (hours > 0 ? " " + hours + " 小時" : "");
        }
        if (hours > 0) {
            return hours + " 小時" + (rest > 0 ? " " + rest + " 分" : "");
        }
        return rest + " 分";
    }
}
