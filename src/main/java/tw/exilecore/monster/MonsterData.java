package tw.exilecore.monster;

import java.util.List;

/**
 * 一隻怪物身上的資料：等級、稀有度、詞綴。存在實體的 PDC。
 *
 * @param level            等級
 * @param rarity           稀有度
 * @param mods             詞綴代號
 * @param name             顯示名稱（稀有怪的隨機名字、Boss 與 MythicMobs 怪物的名字；其他為原版名稱）
 * @param lifeMultiplier   額外的生命倍率（區域 Boss 用；一般怪物為 1）
 * @param damageMultiplier 額外的傷害倍率（同上）
 * @param boss             是不是區域 Boss
 */
public record MonsterData(int level, MonsterRarity rarity, List<String> mods, String name,
                          double lifeMultiplier, double damageMultiplier, boolean boss) {

    public MonsterData {
        mods = List.copyOf(mods);
    }

    /** 一般怪物（沒有額外倍率）。 */
    public MonsterData(final int level, final MonsterRarity rarity, final List<String> mods, final String name) {
        this(level, rarity, mods, name, 1.0, 1.0, false);
    }
}
