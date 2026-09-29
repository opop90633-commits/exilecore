package tw.exilecore.item;

import java.util.LinkedHashMap;
import java.util.Map;

/** 物品身上的一條詞綴：哪條、第幾階、擲出的變數。存進物品的 JSON。 */
public final class RolledAffix {

    private String id;
    private int tier;
    private Map<String, Integer> vars;

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

    public RolledAffix copy() {
        return new RolledAffix(id, tier, vars);
    }
}
