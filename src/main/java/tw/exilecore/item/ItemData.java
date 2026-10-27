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
    /** 新手裝備（建角色時送的）：拆解沒有東西。null = 不是（不寫進 JSON，舊物品不受影響）。 */
    private Boolean starter;
    /** 傳奇物品的代號（uniques.yml）；不是傳奇為 null。 */
    private String unique;
    /** 這件傳奇是照 uniques.yml 的第幾版做的（{@link UniqueItem#version()}）；null = 第 1 版（舊物品；不寫進 JSON）。 */
    private Integer uniqueVersion;
    /** 地圖的階級（T1–T16，PoE2 換界石的做法）；null = 照基底的 map_tN（舊的地圖、不是地圖；不寫進 JSON）。 */
    private Integer tier;
    /** 玩家鎖定的（不能拆解、上架、交易、丟進垃圾桶，避免誤拆）；null = 沒鎖（不寫進 JSON）。 */
    private Boolean locked;
    /** 已腐化（2026-10，蝕變石）：之後不能再用通貨改造。 */
    private Boolean corrupted;
    /** 飾品上打的催化劑種類（{@link Catalyst}）：品質變成那一類詞綴的加成；null = 沒有（不寫進 JSON）。 */
    private String catalyst;
    /** 掛在這件上的星籤（通貨代號）：下一顆對應的通貨照星籤的規則用；null = 沒有（不寫進 JSON）。 */
    private String omen;
    /** 注入在這條項鍊上的天賦核心（tree.json 的節點代號，PoE2 的注入）：那個核心的效果加在身上；null = 沒有（不寫進 JSON）。 */
    private String instilled;
    /** 嵌著的符文（2026-10，runes.yml 的代號）；null = 沒有（不寫進 JSON）。 */
    private List<String> runes;
    /** 映星鏡照出來的複製品（2026-10）：不能再改造、不能再複製；null = 不是（不寫進 JSON）。 */
    private Boolean mirrored;
    /** 破裂的詞綴（2026-10-21，定星石，PoE2 的破裂石）：這條詞綴的代號，通貨移除、重骰都動不到它；null = 沒有（不寫進 JSON）。 */
    private String fractured;
    /** 蝕變石多開的符文孔（2026-10-21，PoE2 的瓦爾寶珠：可以超過上限）；null = 沒有（不寫進 JSON）。 */
    private Integer corruptSockets;
    /**
     * 2026-10-26 照 PoE2 的工匠石（Artificer's Orb）：這件現在有幾個符文孔（不算蝕變石多開的）；
     * null = 舊物品（這次之前做出來的），照基底的孔數全開。新做出來的裝備從 0 開始，用工匠石一個一個加到基底的上限。
     */
    private Integer runeSockets;
    /** 2026-10-26 照 PoE2 的 Hinekora's Lock（預見鎖）：下一顆通貨的結果用這個種子擲，先看得到；改過就拿掉。null = 沒有。 */
    private Long foresight;
    /** 2026-10-26 照 PoE2 的聖化（Omen of Sanctification＋神聖石）：數值變動過、之後不能再改造。null = 沒有。 */
    private Boolean sanctified;
    /** 2026-10-26：固有詞綴擲出（或上次換算）時的範圍（{@link AffixRescale}）；null = 舊物品沒記（不寫進 JSON）。 */
    private Map<String, int[]> implicitRanges;

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

    /** 2026-10-26：換基底（PoE2 的 Omen of the Ancients：僥倖石變成同一類的別件傳奇）。 */
    public void setBase(final String base) {
        this.base = base;
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

    /** 腐化給的品質上限（2026-10-23，照 PoE2：蝕變石讓法杖、長杖的品質最多到 23%）。 */
    public static final int CORRUPTED_QUALITY_MAX = 23;

    /** 蝕變石加品質：可以超過 20，最多 {@link #CORRUPTED_QUALITY_MAX}。 */
    public void setCorruptedQuality(final int quality) {
        this.quality = Math.max(0, Math.min(CORRUPTED_QUALITY_MAX, quality));
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

    /** 2026-10-26：固有詞綴上次記下的範圍；沒記回傳 null。 */
    public Map<String, int[]> implicitRanges() {
        return implicitRanges;
    }

    public void setImplicitRanges(final Map<String, int[]> ranges) {
        this.implicitRanges = RolledAffix.copyRanges(ranges);
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

    /** 建角色時送的新手裝備（拆解沒有東西，避免建角色、拆掉、刪角色一直循環刷金幣）。 */
    public boolean starter() {
        return Boolean.TRUE.equals(starter);
    }

    public void markStarter() {
        this.starter = Boolean.TRUE;
    }

    /** 玩家鎖定了這件（/lock 或拆解台右鍵）：拆解台、市集、交易、垃圾桶都不收。 */
    public boolean corrupted() {
        return Boolean.TRUE.equals(corrupted);
    }

    public void setCorrupted(final boolean corrupted) {
        this.corrupted = corrupted ? Boolean.TRUE : null;
    }

    public boolean locked() {
        return Boolean.TRUE.equals(locked);
    }

    /** 映星鏡照出來的複製品：通貨、符文、注入、揭露都不能再用在它身上，也不能再被複製。 */
    public boolean mirrored() {
        return Boolean.TRUE.equals(mirrored);
    }

    public void setMirrored(final boolean mirrored) {
        this.mirrored = mirrored ? Boolean.TRUE : null;
    }

    /** 破裂的詞綴代號（定星石）；沒有回傳 null。 */
    public String fractured() {
        return fractured;
    }

    public void setFractured(final String affixId) {
        this.fractured = affixId == null || affixId.isBlank() ? null : affixId;
    }

    /** 這條詞綴是不是破裂的（通貨移除、重骰都不能動）。 */
    public boolean isFractured(final RolledAffix rolled) {
        return fractured != null && rolled != null && fractured.equals(rolled.id());
    }

    /** 蝕變石多開的符文孔。 */
    public int corruptSockets() {
        return corruptSockets == null ? 0 : corruptSockets;
    }

    public void setCorruptSockets(final int count) {
        this.corruptSockets = count <= 0 ? null : count;
    }

    /** 2026-10-26：工匠石開過的符文孔數；舊物品（沒記）回傳 null。 */
    public Integer runeSockets() {
        return runeSockets;
    }

    public void setRuneSockets(final Integer count) {
        this.runeSockets = count == null ? null : Math.max(0, count);
    }

    /** 2026-10-26：預見鎖的種子；沒有回傳 null。 */
    public Long foresight() {
        return foresight;
    }

    public void setForesight(final Long seed) {
        this.foresight = seed;
    }

    /** 2026-10-26：聖化過（不能再改造）。 */
    public boolean sanctified() {
        return Boolean.TRUE.equals(sanctified);
    }

    public void setSanctified(final boolean sanctified) {
        this.sanctified = sanctified ? Boolean.TRUE : null;
    }

    public void setLocked(final boolean locked) {
        this.locked = locked ? Boolean.TRUE : null;
    }

    /** 傳奇物品的代號（uniques.yml）；不是傳奇回傳 null。 */
    public String unique() {
        return unique;
    }

    public void setUnique(final String unique) {
        this.unique = unique;
    }

    /** 這件傳奇是照 uniques.yml 的第幾版做的（舊物品沒記 = 1）。 */
    public int uniqueVersion() {
        return uniqueVersion == null ? 1 : uniqueVersion;
    }

    public void setUniqueVersion(final int version) {
        this.uniqueVersion = version <= 1 ? null : version;
    }

    /** 地圖物品上寫的階級；沒寫回傳 null（舊的地圖照基底，見 {@link ItemStats#mapTier}）。 */
    public Integer mapTier() {
        return tier;
    }

    public void setMapTier(final Integer tier) {
        this.tier = tier == null ? null : Math.max(1, tier);
    }

    /** 飾品的催化劑種類；沒有是 null。 */
    public String catalyst() {
        return catalyst;
    }

    public void setCatalyst(final String catalyst) {
        this.catalyst = catalyst == null || catalyst.isBlank() ? null : catalyst;
    }

    /** 掛著的星籤（通貨代號）；沒有是 null。 */
    public String omen() {
        return omen;
    }

    public void setOmen(final String omen) {
        this.omen = omen == null || omen.isBlank() ? null : omen;
    }

    /** 注入的天賦核心（節點代號）；沒有為 null。 */
    public String instilled() {
        return instilled;
    }

    public void setInstilled(final String instilled) {
        this.instilled = instilled == null || instilled.isBlank() ? null : instilled;
    }

    /** 嵌著的符文代號（沒有是空的）。 */
    public List<String> runes() {
        return runes == null ? List.of() : java.util.Collections.unmodifiableList(runes);
    }

    public void addRune(final String rune) {
        if (runes == null) {
            runes = new ArrayList<>();
        }
        runes.add(rune);
    }

    /** 2026-10-27：符文只留前 max 個（孔變少時多出來的符文毀掉；這次之前孔變少符文照留、照樣生效）。 */
    public void trimRunes(final int max) {
        if (runes == null) {
            return;
        }
        while (runes.size() > Math.max(0, max)) {
            runes.removeLast();
        }
        if (runes.isEmpty()) {
            runes = null;
        }
    }

    /** 把第 index 個孔的符文換成新的（2026-10：符文孔滿了時選一個覆蓋）。 */
    public void replaceRune(final int index, final String rune) {
        if (runes != null && index >= 0 && index < runes.size()) {
            runes.set(index, rune);
        }
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
        copy.starter = starter;
        copy.unique = unique;
        copy.uniqueVersion = uniqueVersion;
        copy.tier = tier;
        copy.locked = locked;
        copy.corrupted = corrupted;
        copy.catalyst = catalyst;
        copy.omen = omen;
        copy.instilled = instilled;
        copy.runes = runes == null ? null : new ArrayList<>(runes);
        copy.mirrored = mirrored;
        copy.fractured = fractured;
        copy.corruptSockets = corruptSockets;
        copy.runeSockets = runeSockets;
        copy.foresight = foresight;
        copy.sanctified = sanctified;
        copy.implicitRanges = RolledAffix.copyRanges(implicitRanges);
        return copy;
    }
}
