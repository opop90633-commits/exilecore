package tw.exilecore.combat;

import tw.exilecore.stats.KeystoneOriginKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 2026-10-27 照 PoE2 穿刺（Impale）：穿刺的擊中在敵人身上存下「減免前物理傷害 30%」（再乘穿刺效果）；
 * 之後同一個玩家的一下攻擊擊中抽出最強的那一個，加在那一下減免前的物理傷害（護甲照擋，不再吃增加、更多）。
 * 一隻敵人最多 {@link KeystoneOriginKeys#IMPALE_MAX} 個（超過丟掉最弱的），怪物身上的穿刺不會自己消失。純邏輯，不依賴 Bukkit。
 * 這次之前我們沒有穿刺，「法術擊中時穿刺」「法術的穿刺效果」平衡等效成法術物理傷害。
 */
public final class Impales {

    private static final Impales SHARED = new Impales();

    /** 全伺服器共用的一份（CombatListener 抽、KeystoneEffects 存）。 */
    public static Impales shared() {
        return SHARED;
    }

    /** 一個穿刺：誰造成的、存了多少。 */
    public record Impale(UUID owner, double amount) {
    }

    private final Map<UUID, List<Impale>> entries = new HashMap<>();

    /** 擊中穿刺了這隻：存下 amount（超過上限丟掉最弱的）。 */
    public void add(final UUID target, final UUID owner, final double amount) {
        if (amount <= 0) {
            return;
        }
        final List<Impale> list = entries.computeIfAbsent(target, uuid -> new ArrayList<>());
        list.add(new Impale(owner, amount));
        while (list.size() > KeystoneOriginKeys.IMPALE_MAX) {
            Impale weakest = list.getFirst();
            for (final Impale impale : list) {
                if (impale.amount() < weakest.amount()) {
                    weakest = impale;
                }
            }
            list.remove(weakest);
        }
    }

    /** 這個玩家在這隻身上最強的穿刺（沒有 0）；不拿掉。 */
    public double strongest(final UUID target, final UUID owner) {
        final Impale impale = find(target, owner);
        return impale == null ? 0 : impale.amount();
    }

    /** 抽出這個玩家在這隻身上最強的穿刺（沒有 0）。 */
    public double extract(final UUID target, final UUID owner) {
        final Impale impale = find(target, owner);
        if (impale == null) {
            return 0;
        }
        final List<Impale> list = entries.get(target);
        list.remove(impale);
        if (list.isEmpty()) {
            entries.remove(target);
        }
        return impale.amount();
    }

    private Impale find(final UUID target, final UUID owner) {
        final List<Impale> list = entries.get(target);
        if (list == null) {
            return null;
        }
        Impale best = null;
        for (final Impale impale : list) {
            if (impale.owner().equals(owner) && (best == null || impale.amount() > best.amount())) {
                best = impale;
            }
        }
        return best;
    }

    /** 這隻身上有幾個穿刺。 */
    public int count(final UUID target) {
        final List<Impale> list = entries.get(target);
        return list == null ? 0 : list.size();
    }

    /** 這隻死了、不見了。 */
    public void forget(final UUID target) {
        entries.remove(target);
    }

    /** 拿掉不在了的目標（每秒）。 */
    public void prune(final Predicate<UUID> alive) {
        entries.keySet().removeIf(uuid -> !alive.test(uuid));
    }
}
