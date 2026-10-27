package tw.exilecore.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28：權杖的 100 精魂是基底本身給的，固有詞綴不能再給一次（這次之前變成 200）。 */
class SceptreSpiritTest {

    @Test
    void sceptresDoNotGrantSpiritTwice() throws Exception {
        final ItemRepository items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        int sceptres = 0;
        for (final ItemBase base : items.bases()) {
            if (!base.tags().contains("sceptre")) {
                continue;
            }
            sceptres++;
            assertFalse(base.implicit() != null && base.implicit().toString().contains("spirit.max"), base.id() + " 的固有詞綴又給精魂");
        }
        assertTrue(sceptres >= 16);
    }
}
