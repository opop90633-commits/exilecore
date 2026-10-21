package tw.exilecore.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.BaseArt;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-21：一般裝備、地圖、藥劑、護符、珠寶的圖（web/base_art.txt）：每個在用的基底都有圖、掛在對的原版物品上、每次產生一樣。 */
class BaseSkinTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static List<UiPack.BaseSkin> skins;
    static UiPack.Built pack;
    static Map<String, byte[]> files;

    @BeforeAll
    static void build() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        skins = UiPack.baseSkins(repository);
        pack = UiPack.build(List.of(), List.of(), Set.of(), skins);
        files = UiPackTest.unzip(pack.zip());
    }

    @Test
    void everyActiveBaseHasArt() {
        final Set<String> missing = new TreeSet<>();
        for (final ItemBase base : repository.bases()) {
            if (base.dropWeight() > 0 && !BaseArt.has(base.id())) {
                missing.add(base.id());
            }
        }
        assertTrue(missing.isEmpty(), "沒有圖的基底（跑 tools/gen_base_art.py）：" + missing);
    }

    @Test
    void artIsHookedOnTheVanillaItem() {
        assertTrue(skins.size() > 100, "有圖的基底：" + skins.size());
        for (final UiPack.BaseSkin skin : skins) {
            assertNotNull(files.get("assets/exilecore/textures/item/base/" + skin.id() + ".png"), skin.id() + " 的貼圖");
            assertNotNull(files.get("assets/exilecore/models/item/base/" + skin.id() + ".json"), skin.id() + " 的模型");
            final byte[] definition = files.get("assets/minecraft/items/" + skin.vanillaItem() + ".json");
            assertNotNull(definition, skin.vanillaItem() + " 的物品定義");
            final String text = new String(definition, StandardCharsets.UTF_8);
            assertTrue(text.contains("\"" + BaseArt.modelKey(skin.id()) + "\""), skin.vanillaItem() + " 沒有 " + skin.id() + " 的挑選");
            final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            assertTrue(root.has("oversized_in_gui"), skin.vanillaItem() + " 的外框比格子大");
            if (skin.kind() == UiPack.UniqueSkin.Kind.BOW) {
                assertNotNull(files.get("assets/exilecore/textures/item/base/" + skin.id() + "_pulling_2.png"), skin.id() + " 拉弓的圖");
            }
        }
        assertTrue(UiPack.isArtKey(BaseArt.modelKey("x")), "選取外框裡畫基底的圖");
    }

    @Test
    void sameEveryTime() throws IOException {
        assertEquals(pack.sha1(), UiPack.build(List.of(), List.of(), Set.of(), UiPack.baseSkins(repository)).sha1());
    }
}
