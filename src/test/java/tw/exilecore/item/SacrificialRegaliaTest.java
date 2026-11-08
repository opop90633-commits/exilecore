package tw.exilecore.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長「祭紋華袍跟原作比少了一條」：poe2db Sacrificial Regalia 的 +1 全部已腐化技能寶石等級。 */
class SacrificialRegaliaTest {

    @Test
    void regaliaRaisesCorruptedGemLevels() throws Exception {
        final ItemRepository repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
        final ItemBase regalia = repository.base("body_str_dex_int_01");
        assertNotNull(regalia.implicit(), "要有固定詞綴");
        assertTrue(regalia.implicit().text().contains("已腐化技能寶石等級"), regalia.implicit().text());
        assertTrue(regalia.implicit().mods().stream().anyMatch(mod -> mod.stat().equals(tw.exilecore.item.unique.VaalKeys.GEM_CORRUPTED_LEVEL)));
    }
}
