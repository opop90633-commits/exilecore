package tw.exilecore.item;

import java.util.LinkedHashMap;
import java.util.Map;

/** 物品身上的一條詞綴：哪條、第幾階、擲出的變數。存進物品的 JSON。 */
public final class RolledAffix {

    private String id;
    private int tier;
    private Map<String, Integer> vars;
    /**
     * 2026-10-26：擲出（或上次照新資料換算）時這一階每個變數的範圍；詞綴資料改了以後照它把數值按比例換到新範圍（{@link AffixRescale}）。
     * null = 這次之前做的舊物品（沒記）。
     */
    private Map<String, int[]> ranges;
    /** 2026-10-26：擲出時這條詞綴的群組與前後綴（prefix／suffix）：詞綴整條從資料拿掉時，用來找同類的換上。null = 沒記。 */
    private String group;
    private String side;

    public RolledAffix() {
        this.vars = new LinkedHashMap<>();
    }

    public RolledAffix(final String id, final int tier, final Map<String, Integer> vars) {
        this.id = id;
        this.tier = tier;
        this.vars = new LinkedHashMap<>(vars);
    }

    public String id() {
        return id;
    }

    public int tier() {
        return tier;
    }

    public Map<String, Integer> vars() {
        return vars;
    }

    public void setVars(final Map<String, Integer> newVars) {
        this.vars = new LinkedHashMap<>(newVars);
    }

    /** 2026-10-26：擲出（或上次換算）時的範圍；舊物品沒記回傳 null。 */
    public Map<String, int[]> ranges() {
        return ranges;
    }

    /** 2026-10-26：記下現在的階級、範圍、群組與前後綴（{@link AffixRescale}）。 */
    void record(final int newTier, final Map<String, int[]> newRanges, final String newGroup, final String newSide) {
        this.tier = newTier;
        this.ranges = copyRanges(newRanges);
        this.group = newGroup;
        this.side = newSide;
    }

    public String group() {
        return group;
    }

    public String side() {
        return side;
    }

    static Map<String, int[]> copyRanges(final Map<String, int[]> source) {
        if (source == null) {
            return null;
        }
        final Map<String, int[]> copy = new LinkedHashMap<>();
        source.forEach((key, range) -> copy.put(key, range == null ? null : range.clone()));
        return copy;
    }

    public RolledAffix copy() {
        final RolledAffix copy = new RolledAffix(id, tier, vars);
        copy.ranges = copyRanges(ranges);
        copy.group = group;
        copy.side = side;
        return copy;
    }
}
