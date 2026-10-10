package tw.exilecore.world;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.slf4j.Logger;
import tw.exilecore.worldgen.B;
import tw.exilecore.worldgen.Palette;

import java.util.Arrays;

/**
 * 世界生成的方塊代號（{@link Palette}）→ BlockData。啟動時在主執行緒一次換好（寫錯的狀態字串警告後改用石頭），
 * 之後區塊生成的執行緒只讀陣列；後來才登記的代號（地圖主題）第一次用到時再補。
 */
public final class BlockPalette {

    private static volatile BlockData[] resolved = new BlockData[0];
    private static Logger logger;

    private BlockPalette() {
    }

    /** 啟動時呼叫：把目前登記的方塊都換好。 */
    public static synchronized void init(final Logger log) {
        logger = log;
        // 碰一下 B（B.AIR 是編譯期常數不會載入類別，要用一個呼叫 Palette.id 的欄位），確保所有常數都登記了
        if (B.BEDROCK <= Palette.AIR) {
            throw new IllegalStateException("方塊代號登記錯了");
        }
        resolveUpTo(Palette.size());
    }

    /** 代號對應的 BlockData（執行緒安全）。 */
    public static BlockData get(final int id) {
        final BlockData[] current = resolved;
        if (id >= 0 && id < current.length && current[id] != null) {
            return current[id];
        }
        synchronized (BlockPalette.class) {
            resolveUpTo(Math.max(id + 1, Palette.size()));
            return resolved[id];
        }
    }

    private static void resolveUpTo(final int size) {
        final BlockData[] current = resolved;
        if (current.length >= size) {
            return;
        }
        final BlockData[] next = Arrays.copyOf(current, size);
        for (int id = current.length; id < size; id++) {
            final String state = Palette.state(id);
            try {
                next[id] = Bukkit.createBlockData(state);
            } catch (final IllegalArgumentException e) {
                if (logger != null) {
                    logger.warn("世界生成：不認得的方塊狀態 {}，改用石頭（{}）", state, e.getMessage());
                }
                next[id] = Material.STONE.createBlockData();
            }
        }
        resolved = next;
    }
}
