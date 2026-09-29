package tw.exilecore.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一件裝備的完整資料，存在物品的 PersistentDataContainer 裡（JSON）。
 * 玩家看到的名稱與描述全部由這筆資料重新產生，所以資料一改就重繪，永遠不會不同步。
 * <p>
 * {@code version} 是資料格式版本；日後詞綴改名或刪除時，載入時依版本轉換。
 */
public final class ItemData {

    public static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private String uuid;
    private String base;
    private Rarity rarity = Rarity.NORMAL;
    private int ilvl = 1;
    private int quality;
    private boolean identified = true;
    private String name;                                   // 稀有物品的隨機名字
    private Map<String, Integer> implicitVars = new LinkedHashMap<>();
    private List<RolledAffix> affixes = new ArrayList<>();
    private int sockets;

    public ItemData() {
    }

    public ItemData(final String base, final Rarity rarity, final int ilvl) {
        this.uuid = UUID.randomUUID().toString();
        this.base = base;
        this.rarity = rarity;
        this.ilvl = Math.max(1, ilvl);
    }

    public int version() {
        return version;
    }

    public String uuid() {
        return uuid;
    }

    public String base() {
        return base;
    }

    public Rarity rarity() {
        return rarity;
    }

    public void setRarity(final Rarity rarity) {
        this.rarity = rarity;
    }

    public int ilvl() {
        return ilvl;
    }

    public int quality() {
        return quality;
    }

    public void setQuality(final int quality) {
        this.quality = Math.max(0, Math.min(20, quality));
    }

    public boolean identified() {
        return identified;
    }

    public void setIdentified(final boolean identified) {
        this.identified = identified;
    }

    public String name() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public Map<String, Integer> implicitVars() {
        return implicitVars;
    }

    public void setImplicitVars(final Map<String, Integer> vars) {
        this.implicitVars = new LinkedHashMap<>(vars);
    }

    public List<RolledAffix> affixes() {
        return affixes;
    }

    public int sockets() {
        return sockets;
    }

    public void setSockets(final int sockets) {
        this.sockets = Math.max(0, sockets);
    }

    /** 換一個新的 uuid（複製物品時用，讓兩件不同）。 */
    public void regenerateUuid() {
        this.uuid = UUID.randomUUID().toString();
    }

    public ItemData copy() {
        final ItemData copy = new ItemData();
        copy.version = version;
        copy.uuid = uuid;
        copy.base = base;
        copy.rarity = rarity;
        copy.ilvl = ilvl;
        copy.quality = quality;
        copy.identified = identified;
        copy.name = name;
        copy.implicitVars = new LinkedHashMap<>(implicitVars);
        for (final RolledAffix affix : affixes) {
            copy.affixes.add(affix.copy());
        }
        copy.sockets = sockets;
        return copy;
    }
}
