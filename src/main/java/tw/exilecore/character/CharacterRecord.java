package tw.exilecore.character;

import java.util.UUID;

/**
 * 一個角色在資料庫裡的那一列（characters 資料表）。可變，由 {@link PlayerState} 持有並在存檔時寫回。
 * 背包與天賦配點另外存（character_inventory、passive_allocations）；金幣是帳號共用的（accounts.gold，見 {@link PlayerState#gold()}）。
 */
public final class CharacterRecord {

    private long id;
    private final UUID account;
    private final int slot;
    private String name;
    private CharacterClass characterClass;
    private int level = 1;
    private long experience;
    private int bonusPoints;
    private int refundPoints;
    private int deaths;
    private long playtimeSeconds;
    private double life = -1;
    private double mana = -1;
    private double energyShield = -1;
    private String lastZone;
    private String lootFilter;

    public CharacterRecord(final long id, final UUID account, final int slot, final String name, final CharacterClass characterClass) {
        this.id = id;
        this.account = account;
        this.slot = slot;
        this.name = name;
        this.characterClass = characterClass;
    }

    public long id() {
        return id;
    }

    /** 換職業（只有混沌術士拆成混沌術士與元素法師時用；資料庫另外寫 {@link CharacterRepository#updateClass}）。 */
    void setCharacterClass(final CharacterClass characterClass) {
        this.characterClass = characterClass;
    }

    void setId(final long id) {
        this.id = id;
    }

    public UUID account() {
        return account;
    }

    /** 帳號裡的格子編號（1 起算）。 */
    public int slot() {
        return slot;
    }

    public String name() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public CharacterClass characterClass() {
        return characterClass;
    }

    public int level() {
        return level;
    }

    public void setLevel(final int level) {
        this.level = Math.max(1, level);
    }

    /** 目前等級內已累積的經驗（升級時歸零重算）。 */
    public long experience() {
        return experience;
    }

    public void setExperience(final long experience) {
        this.experience = Math.max(0, experience);
    }

    /** 升級以外的天賦點（Boss 首殺等）。 */
    public int bonusPoints() {
        return bonusPoints;
    }

    public void setBonusPoints(final int bonusPoints) {
        this.bonusPoints = Math.max(0, bonusPoints);
    }

    /** 免費退點次數（任務獎勵、管理指令給的；有的話退點先用這個，不花金幣）。 */
    public int refundPoints() {
        return refundPoints;
    }

    public void setRefundPoints(final int refundPoints) {
        this.refundPoints = Math.max(0, refundPoints);
    }

    public int deaths() {
        return deaths;
    }

    public void setDeaths(final int deaths) {
        this.deaths = deaths;
    }

    public long playtimeSeconds() {
        return playtimeSeconds;
    }

    public void setPlaytimeSeconds(final long playtimeSeconds) {
        this.playtimeSeconds = playtimeSeconds;
    }

    /** 上次存檔時的生命（-1 = 沒存過，載入時回滿）。 */
    public double life() {
        return life;
    }

    public double mana() {
        return mana;
    }

    public double energyShield() {
        return energyShield;
    }

    public void setResources(final double life, final double mana, final double energyShield) {
        this.life = life;
        this.mana = mana;
        this.energyShield = energyShield;
    }

    public String lastZone() {
        return lastZone;
    }

    public void setLastZone(final String lastZone) {
        this.lastZone = lastZone;
    }

    /** 掉落過濾器的存檔字串（loot 模組的 LootFilter.encode；null = 沒設定過，用預設）。 */
    public String lootFilter() {
        return lootFilter;
    }

    public void setLootFilter(final String lootFilter) {
        this.lootFilter = lootFilter;
    }

    /** 存檔用的不可變快照，可以安全地丟到背景執行緒。 */
    public CharacterRecord snapshot() {
        final CharacterRecord copy = new CharacterRecord(id, account, slot, name, characterClass);
        copy.level = level;
        copy.experience = experience;
        copy.bonusPoints = bonusPoints;
        copy.refundPoints = refundPoints;
        copy.deaths = deaths;
        copy.playtimeSeconds = playtimeSeconds;
        copy.life = life;
        copy.mana = mana;
        copy.energyShield = energyShield;
        copy.lastZone = lastZone;
        copy.lootFilter = lootFilter;
        return copy;
    }
}
