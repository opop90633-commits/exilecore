package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 網頁圖鑑的詞綴（2026-10）：藥劑、護符、珠寶的詞綴也要列出來；規劃器沒有的傳奇（珠寶、藥劑、護符）另外給。 */
class CodexAffixTest {

    @Test
    void flaskCharmAndJewelAffixesAreListed() throws Exception {
        final ItemRepository repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
        final JsonObject root = new JsonObject();
        CodexData.affixes(root, repository);
        final JsonArray affixes = root.getAsJsonArray("affixes");
        final Set<String> slots = new HashSet<>();
        for (final JsonElement each : affixes) {
            each.getAsJsonObject().getAsJsonArray("slots").forEach(slot -> slots.add(slot.getAsString()));
            assertTrue(each.getAsJsonObject().getAsJsonArray("tiers").size() > 0);
        }
        for (final String slot : new String[] {"flask", "charm", "jewel", "main_hand", "chest", "ring"}) {
            assertTrue(slots.contains(slot), "詞綴裡要有部位 " + slot + "：" + slots);
        }
        final String dump = System.getProperty("codex.dump");
        if (dump != null) {
            Files.writeString(Path.of(dump), root.toString());
        }
    }
}
