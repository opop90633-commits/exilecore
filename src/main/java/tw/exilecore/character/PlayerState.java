package tw.exilecore.character;

import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.Set;
import java.util.UUID;

/**
 * 一個在線玩家的即時狀態：等級、生命／魔力／護盾的目前值、屬性表、攻擊計時。
 * 階段 2 會加上職業、經驗、配點與持久化；現在等級存在玩家的 PDC。
 */
public final class PlayerState {

    private final UUID uuid;
    private int level;
    private double life;
    private double mana;
    private double energyShield;
    private final StatSheet sheet = new StatSheet();
    private boolean dirty = true;
    private long lastDamageTick = Long.MIN_VALUE;
    private long nextAttackTick;
    private boolean debug;
    private Set<String> weaponTags = Set.of();

    public PlayerState(final UUID uuid, final int level) {
        this.uuid = uuid;
        this.level = Math.max(1, level);
    }

    public UUID uuid() {
        return uuid;
    }

    public int level() {
        return level;
    }

    public void setLevel(final int level) {
        this.level = Math.max(1, Math.min(100, level));
        this.dirty = true;
    }

    public StatSheet sheet() {
        return sheet;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    void markClean() {
        this.dirty = false;
    }

    public double life() {
        return life;
    }

    public double maxLife() {
        return Math.max(1, sheet.get(StatKeys.LIFE_MAX));
    }

    public double mana() {
        return mana;
    }

    public double maxMana() {
        return Math.max(0, sheet.get(StatKeys.MANA_MAX));
    }

    public double energyShield() {
        return energyShield;
    }

    public double maxEnergyShield() {
        return Math.max(0, sheet.get(StatKeys.ES_MAX));
    }

    public void setLife(final double value) {
        this.life = Math.max(0, Math.min(maxLife(), value));
    }

    public void setMana(final double value) {
        this.mana = Math.max(0, Math.min(maxMana(), value));
    }

    public void setEnergyShield(final double value) {
        this.energyShield = Math.max(0, Math.min(maxEnergyShield(), value));
    }

    /** 全部回滿（登入、重生）。 */
    public void restoreAll() {
        life = maxLife();
        mana = maxMana();
        energyShield = maxEnergyShield();
    }

    /** 重算後把目前值夾在新上限內。 */
    public void clampToMax() {
        life = Math.min(life, maxLife());
        mana = Math.min(mana, maxMana());
        energyShield = Math.min(energyShield, maxEnergyShield());
    }

    public boolean isDead() {
        return life <= 0;
    }

    public long lastDamageTick() {
        return lastDamageTick;
    }

    public void setLastDamageTick(final long tick) {
        this.lastDamageTick = tick;
    }

    public long nextAttackTick() {
        return nextAttackTick;
    }

    public void setNextAttackTick(final long tick) {
        this.nextAttackTick = tick;
    }

    public boolean debug() {
        return debug;
    }

    public void setDebug(final boolean debug) {
        this.debug = debug;
    }

    /** 目前主手武器的種類標籤（徒手為空），重算時更新。 */
    public Set<String> weaponTags() {
        return weaponTags;
    }

    void setWeaponTags(final Set<String> tags) {
        this.weaponTags = Set.copyOf(tags);
    }

    /** 屬性值的簡寫。 */
    public double strength() {
        return sheet.get(StatKeys.STR);
    }

    public double dexterity() {
        return sheet.get(StatKeys.DEX);
    }

    public double intelligence() {
        return sheet.get(StatKeys.INT);
    }
}
