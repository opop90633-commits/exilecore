package tw.exilecore.monster;

import java.util.List;

/**
 * 一隻怪物身上的資料：等級、稀有度、詞綴。存在實體的 PDC。
 *
 * @param level  等級
 * @param rarity 稀有度
 * @param mods   詞綴代號
 * @param name   顯示名稱（稀有怪的隨機名字；其他為原版名稱）
 */
public record MonsterData(int level, MonsterRarity rarity, List<String> mods, String name) {

    public MonsterData {
        mods = List.copyOf(mods);
    }
}
