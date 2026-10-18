package tw.exilecore.loot;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 個人掉落倍率（2026-10-18）：處理工作室、外掛、洗資源的帳號。名單存在 plugins/ExileCore/loot_penalty.yml
 * （不在資料檔、不進管理網頁），用 {@code /ec droppenalty} 改，馬上生效；伺服器 log 記管理員改了什麼。
 * <p>
 * 不碰屬性表（角色面板的物品數量、稀有度照常顯示）。只套在兩個地方：
 * 殺怪的那份（MonsterListener.rollAndDeliver，含地圖 Boss 的特殊掉落與金幣），
 * 地圖事件與劫盜（MapRewards.deliver：寶箱、流寇、裂隙、劫盜）。
 * 每一件各自照倍率擲留不留，金幣直接乘，所以期望值剛好是原本的 × 倍率。
 * <p>
 * 主執行緒讀寫；沒載入（測試、模組沒開）時每個人都是 1（不影響）。
 */
public final class LootPenalty {

    private static final String FILE = "loot_penalty.yml";
    private static final Map<UUID, Double> FACTORS = new LinkedHashMap<>();
    private static Path file;
    private static Logger logger;

    private LootPenalty() {
    }

    /** 啟動時載入（ExileCore.onEnable）。 */
    public static synchronized void load(final Path dataFolder, final Logger log) {
        file = dataFolder.resolve(FILE);
        logger = log;
        FACTORS.clear();
        if (!Files.exists(file)) {
            return;
        }
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        for (final String key : yaml.getKeys(false)) {
            try {
                final double value = yaml.getDouble(key, 1.0);
                if (value < 1.0) {
                    FACTORS.put(UUID.fromString(key), clamp(value));
                }
            } catch (final IllegalArgumentException e) {
                log.warn("{}：不認得的 UUID {}（略過）", FILE, key);
            }
        }
        if (!FACTORS.isEmpty()) {
            log.info("{}：{} 個人有個人掉落倍率", FILE, FACTORS.size());
        }
    }

    /** 這個人的掉落倍率（0–1；1 = 正常）。 */
    public static synchronized double factor(final UUID player) {
        return player == null ? 1.0 : FACTORS.getOrDefault(player, 1.0);
    }

    public static double factor(final Player player) {
        return player == null ? 1.0 : factor(player.getUniqueId());
    }

    /** 設定倍率（1 以上 = 拿掉）並存檔；回傳有沒有存成功。 */
    public static synchronized boolean set(final UUID player, final String name, final double value) {
        if (value >= 1.0) {
            FACTORS.remove(player);
        } else {
            FACTORS.put(player, clamp(value));
        }
        if (logger != null) {
            logger.info("[掉落倍率] {}（{}）→ {}", name, player, value >= 1.0 ? "正常" : String.format(java.util.Locale.ROOT, "×%.2f", clamp(value)));
        }
        return save();
    }

    /** 目前的名單（UUID → 倍率）。 */
    public static synchronized Map<UUID, Double> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(FACTORS));
    }

    /** 機率型的東西（掉不掉）：機率乘上倍率。 */
    public static double chance(final Player player, final double chance) {
        final double factor = factor(player);
        return factor >= 1.0 ? chance : chance * factor;
    }

    /** 掉幾個：每一個各自照倍率擲留不留（期望值 = 原本 × 倍率）。 */
    public static int amount(final Player player, final int amount, final RandomGenerator random) {
        final double factor = factor(player);
        if (factor >= 1.0 || amount <= 0) {
            return amount;
        }
        if (amount > 1000) {
            final double value = amount * factor;
            final int whole = (int) Math.floor(value);
            return whole + (random.nextDouble() < value - whole ? 1 : 0);
        }
        int kept = 0;
        for (int i = 0; i < amount; i++) {
            if (random.nextDouble() < factor) {
                kept++;
            }
        }
        return kept;
    }

    /** 金幣：直接乘倍率。 */
    public static long gold(final Player player, final long gold) {
        final double factor = factor(player);
        return factor >= 1.0 || gold <= 0 ? gold : Math.round(gold * factor);
    }

    /** 一份掉落：每一件各自照倍率擲留不留（回傳新的清單；倍率 1 回傳原本那份）。 */
    public static <T> List<T> thin(final Player player, final List<T> drops, final RandomGenerator random) {
        final double factor = factor(player);
        if (factor >= 1.0 || drops.isEmpty()) {
            return drops;
        }
        final List<T> kept = new ArrayList<>(drops.size());
        for (final T drop : drops) {
            if (random.nextDouble() < factor) {
                kept.add(drop);
            }
        }
        return kept;
    }

    private static double clamp(final double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static boolean save() {
        if (file == null) {
            return false;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("隱藏的個人掉落倍率（/ec droppenalty 改）：UUID: 倍率（0–1）。不要給玩家看。"));
        for (final Map.Entry<UUID, Double> entry : FACTORS.entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            Files.createDirectories(file.getParent());
            yaml.save(file.toFile());
            return true;
        } catch (final IOException e) {
            if (logger != null) {
                logger.error("{} 存檔失敗", FILE, e);
            }
            return false;
        }
    }
}
