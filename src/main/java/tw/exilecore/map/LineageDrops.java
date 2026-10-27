package tw.exilecore.map;

import tw.exilecore.ExileCore;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.skill.LineageRules;
import tw.exilecore.skill.SupportDefinition;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 2026-10-27 照站長：血脈輔助寶石的掉落（設定在 supports_lineage.yml 的 drops，分級見那個檔的說明）。
 * <ul>
 *   <li>一般（lineage）：地圖怪物每隻 = 普通怪的機率 ×（1 + monsters.yml 那種稀有度的 quantity），平均每隻約 1/500,000（追逐級傳奇的 2 倍）；
 *       地圖 Boss 每次擊殺 0.068%（追逐級 ×2）</li>
 *   <li>稀有（lineage_rare）：同上，平均每隻約 1/2,000,000；地圖 Boss 0.017%</li>
 *   <li>巔峰（lineage_pinnacle）：2026-10-27 站長改成地圖怪物平均每隻約 1/4,000,000、地圖 Boss 0.0085%（終局、極境、巔峰 Boss 也一樣）；
 *       這次之前只有終局以上的 Boss 0.5%，站長要求拿掉（pinnacle_chance 0）</li>
 * </ul>
 * 每個有份的人各擲一次（MapService.rollLoot 每人呼叫一次）；物品數量、稀有度、星圖天賦都不影響（跟追逐級一樣）。
 */
public final class LineageDrops {

    private LineageDrops() {
    }

    /** 這隻怪物三種血脈掉一顆的機率 {一般, 稀有, 巔峰}（純函式，測試用）。 */
    public static double[] chances(final LineageRules.Drops settings, final int monsterLevel, final double rarityQuantity,
                                   final boolean mapBoss, final boolean endgameBoss) {
        if (monsterLevel < settings.minLevel()) {
            return new double[3];
        }
        if (mapBoss) {
            final double pinnacle = endgameBoss ? 1 - (1 - settings.pinnacleChance()) * (1 - settings.bossPinnacleChance())
                    : settings.bossPinnacleChance();
            return new double[] {settings.bossChance(), settings.bossRareChance(), pinnacle};
        }
        return new double[] {LineageRules.Drops.scaled(settings.mobChance(), rarityQuantity),
                LineageRules.Drops.scaled(settings.mobRareChance(), rarityQuantity),
                LineageRules.Drops.scaled(settings.mobPinnacleChance(), rarityQuantity)};
    }

    /**
     * 擲一次（MapService.rollLoot：一個人的那份）：中了從那一級隨機抽一顆，等級照怪物等級。
     *
     * @param mapBoss 這張圖現在的 Boss
     */
    static void roll(final ExileCore plugin, final MapInstance instance, final MonsterData data, final boolean mapBoss,
                     final List<DropTable.Drop> drops, final RandomGenerator random) {
        if (plugin.skills() == null || plugin.monsters() == null || data == null) {
            return;
        }
        final boolean endgame = mapBoss && (instance.map().isFinal() || instance.isUber() || instance.isPinnacle());
        final double quantity = plugin.monsters().config().rarity(data.rarity()).quantityBonus();
        final double[] chances = chances(plugin.skills().config().lineageDrops(), data.level(), quantity, mapBoss, endgame);
        for (int tier = 0; tier < chances.length; tier++) {
            if (chances[tier] <= 0 || random.nextDouble() >= chances[tier]) {
                continue;
            }
            final List<SupportDefinition> pool = plugin.skills().config().supportsFrom(LineageRules.SOURCES.get(tier));
            if (!pool.isEmpty()) {
                drops.add(DropTable.Drop.support(pool.get(random.nextInt(pool.size())).id(), data.level()));
            }
        }
    }
}
