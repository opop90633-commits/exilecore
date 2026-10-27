package tw.exilecore.item;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 2026-10-27 效能（微基準）：整份讀一件裝備（解 JSON、傳奇補正、詞綴換算、補變數）跟只看基底代號差多少。
 * 天賦樹條件（拿斧頭、拿盾、雙持…）以前每次判斷都整份讀；現在只讀 PDC 的基底代號（{@link ItemService#base}）。
 * 數字印在測試輸出，不拿來判定（雲端機器慢、會抖）；判定只看兩種方法拿到的基底一樣。
 */
class ItemReadCostTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @Test
    void fullReadVersusBaseIdOnly() throws IOException {
        final ItemRepository repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
        final ItemGenerator generator = new ItemGenerator(repository);
        final SplittableRandom random = new SplittableRandom(7);
        final List<String> jsons = new ArrayList<>();
        final List<String> baseIds = new ArrayList<>();
        for (final ItemBase base : repository.bases()) {
            if (base.slot() == null || jsons.size() >= 200) {
                continue;
            }
            final ItemData data = generator.generate(base, Rarity.RARE, 80, random);
            jsons.add(GSON.toJson(data));
            baseIds.add(data.base());
        }
        assertFalse(jsons.isEmpty());
        final int rounds = 20;
        long full = 0;
        long cheap = 0;
        for (int round = 0; round < rounds; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < jsons.size(); i++) {
                final ItemData data = GSON.fromJson(jsons.get(i), ItemData.class);
                UniqueUpgrade.apply(repository, data);
                AffixRescale.apply(repository, data);
                AffixUpgrade.apply(repository, data);
                if (round == 0) {
                    assertEquals(repository.base(baseIds.get(i)), repository.base(data.base()), "兩種方法拿到同一個基底");
                }
            }
            full += System.nanoTime() - start;
            start = System.nanoTime();
            for (final String id : baseIds) {
                if (repository.base(id) == null) {
                    throw new AssertionError(id);
                }
            }
            cheap += System.nanoTime() - start;
        }
        final double perFull = full / 1e3 / (rounds * (double) jsons.size());
        final double perCheap = cheap / 1e3 / (rounds * (double) jsons.size());
        System.out.printf("[微基準] 整份讀一件裝備 %.1f 微秒；只看基底代號 %.3f 微秒（%d 件 × %d 輪）%n", perFull, perCheap, jsons.size(), rounds);
    }
}
