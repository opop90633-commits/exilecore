package tw.exilecore.loot;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.StatKeys;

import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 物品數量與物品稀有度是全域的（2026-10）：一般怪的掉落表以外，Boss 的特殊掉落（傳奇、專屬、鑰匙、碎片、額外通貨）、
 * 金庫、地圖事件、世界 Boss 的活動獎勵也都吃得到。
 * <ul>
 *   <li>來源：這個人屬性表的物品數量／稀有度（裝備、天賦、月卡、加倍藥、稱號…）＋全服加成（drops.yml 的 server_bonus）
 *       ＋這個月的主題。地圖裡另外加地圖本身的（呼叫的地方用 {@link Bonus#plus} 加）。</li>
 *   <li>怪物稀有度給的加成（稀有怪 +150% 數量那種）不在這裡：那是一般掉落表專用的，不然 Boss 的傳奇機率會翻好幾倍。</li>
 *   <li>用法：「一件東西掉不掉」——傳奇、專屬、寶石這種稀有的照稀有度（{@link Bonus#rare}），
 *       通貨、鑰匙、碎片、地圖這種照數量（{@link Bonus#more}）；「掉幾個」照數量放大（{@link Bonus#amount}）。</li>
 * </ul>
 */
public final class LootBonus {

    private LootBonus() {
    }

    /** 物品數量、物品稀有度（小數：0.5 = 增加 50%）。 */
    public record Bonus(double quantity, double rarity) {

        public static final Bonus NONE = new Bonus(0, 0);

        public Bonus plus(final double moreQuantity, final double moreRarity) {
            return new Bonus(quantity + moreQuantity, rarity + moreRarity);
        }

        /** 稀有的東西（傳奇、專屬、寶石）掉不掉的機率：照物品稀有度放大，最多 1。 */
        public double rare(final double chance) {
            return Math.min(1, Math.max(0, chance) * (1 + Math.max(0, rarity)));
        }

        /** 數量型的東西（通貨、鑰匙、碎片、地圖）掉不掉的機率：照物品數量放大，最多 1。 */
        public double more(final double chance) {
            return Math.min(1, Math.max(0, chance) * (1 + Math.max(0, quantity)));
        }

        /** 掉幾個：照物品數量放大，小數的部分照機率多一個（期望值剛好是 base ×（1 + 數量））。 */
        public int amount(final int base, final RandomGenerator random) {
            if (base <= 0) {
                return 0;
            }
            final double value = base * (1 + Math.max(0, quantity));
            final int whole = (int) Math.floor(value);
            return whole + (random.nextDouble() < value - whole ? 1 : 0);
        }
    }

    /** 全服加成＋這個月的主題（沒有人的那份也有）。 */
    public static Bonus global(final ExileCore plugin) {
        double quantity = 0;
        double rarity = 0;
        if (plugin.monsterModule() != null && plugin.monsterModule().drops() != null) {
            quantity += plugin.monsterModule().drops().serverQuantity();
            rarity += plugin.monsterModule().drops().serverRarity();
        }
        if (plugin.events() != null) {
            quantity += plugin.events().quantityBonus();
            rarity += plugin.events().rarityBonus();
        }
        return new Bonus(quantity, rarity);
    }

    /** 這個人的物品數量、稀有度（自己的屬性表，不含全服與主題；稀有度照 {@link #personalRarity} 遞減）。 */
    public static Bonus personal(final ExileCore plugin, final Player player) {
        if (player == null || plugin.players() == null) {
            return Bonus.NONE;
        }
        return new Bonus(personalQuantity(plugin, player), personalRarity(plugin, player));
    }

    /** 2026-10-19：這個人自己的物品數量（小數）；剛換上去不到 {@link SwapGuard} 的秒數不算。 */
    public static double personalQuantity(final ExileCore plugin, final Player player) {
        if (player == null || plugin.players() == null) {
            return 0;
        }
        return SwapGuard.quantity(player.getUniqueId(), plugin.players().sheet(player).increased(StatKeys.DROP_QUANTITY, Set.of()));
    }

    /**
     * 2026-10-19：這個人自己的物品稀有度（小數），照 drops.yml 的 rarity_curve 遞減。
     * 遞減的是裝備、天賦、稱號這些；個人加倍（贊助的加倍藥）不遞減，直接加在後面。
     */
    public static double personalRarity(final ExileCore plugin, final Player player) {
        if (player == null || plugin.players() == null) {
            return 0;
        }
        // 2026-10-19：剛換上去的稀有度裝要穿滿 SwapGuard 的秒數才算（防打 Boss 最後一下換裝）
        final double raw = SwapGuard.rarity(player.getUniqueId(), plugin.players().sheet(player).increased(StatKeys.DROP_RARITY, Set.of()));
        final double boost = boostRarity(plugin, player);
        if (plugin.monsterModule() == null || plugin.monsterModule().drops() == null) {
            return raw;
        }
        return plugin.monsterModule().drops().diminishRarity(raw - boost) + boost;
    }

    /** 個人加倍藥給的物品稀有度（小數；屬性表裡已經含這個）。 */
    public static double boostRarity(final ExileCore plugin, final Player player) {
        if (plugin.sponsors() == null) {
            return 0;
        }
        double boost = 0;
        for (final tw.exilecore.sponsor.SponsorConfig.BoostType type : plugin.sponsors().personalBoosts(player)) {
            boost += type.rarity();
        }
        return boost;
    }

    /** 這個人拿獎勵時的全部加成：自己的＋全服＋主題（player 是 null 就只有全服＋主題）。 */
    public static Bonus of(final ExileCore plugin, final Player player) {
        final Bonus mine = personal(plugin, player);
        final Bonus all = global(plugin);
        return new Bonus(mine.quantity() + all.quantity(), mine.rarity() + all.rarity());
    }
}
