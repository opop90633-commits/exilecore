package tw.exilecore.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.UniqueArt;
import tw.exilecore.item.UniqueItem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 傳奇的專屬外觀：每件都有圖、掛在對的原版物品上、原版的部分照抄、每次產生一樣。 */
class UniqueSkinTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static List<UiPack.UniqueSkin> skins;
    static UiPack.Built pack;
    static Map<String, byte[]> files;

    @BeforeAll
    static void build() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        skins = UiPack.skins(repository);
        pack = UiPack.build(skins);
        files = UiPackTest.unzip(pack.zip());
    }

    static JsonObject json(final String path) {
        final byte[] bytes = files.get(path);
        assertNotNull(bytes, "資源包裡沒有 " + path);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void everyUniqueHasArt() {
        final Set<String> uniques = new HashSet<>();
        for (final UniqueItem unique : repository.uniques()) {
            uniques.add(unique.id());
        }
        // 2026-10 PoE2 改版：退役的傳奇（retired: true）不在 uniques() 裡，但定義與圖都留著（還沒換成兌換券的舊物品）
        final Set<String> missing = new java.util.TreeSet<>(uniques);
        missing.removeAll(UniqueArt.ids());
        assertEquals(Set.of(), missing, "uniques.yml 的每一件都要有圖");
        for (final String id : UniqueArt.ids()) {
            assertNotNull(repository.unique(id), "圖不能指到不存在的傳奇：" + id);
        }
        assertEquals(uniques.size(), skins.size());
        assertTrue(UniqueArt.has("tomb_oath"));
        assertNotNull(repository.unique("tomb_oath"), "退役的傳奇定義還在");
        assertFalse(uniques.contains("tomb_oath"), "退役的傳奇不會產生外觀");
        assertFalse(UniqueArt.has("hawk_eye_pulling_1"), "拉弓的那幾張不是獨立的傳奇");
        assertFalse(UniqueArt.has(null));
        assertEquals("unique:tomb_oath", UniqueArt.modelKey("tomb_oath"));
    }

    @Test
    void skinsFollowTheBase() {
        final Map<String, UiPack.UniqueSkin> byId = new java.util.HashMap<>();
        for (final UiPack.UniqueSkin skin : skins) {
            byId.put(skin.id(), skin);
        }
        // 2026-10 PoE2 改版：舊的傳奇（tomb_oath、hawk_eye…）退役，換成新的這幾件
        assertEquals(new UiPack.UniqueSkin("abyss_maul", "mace", UiPack.UniqueSkin.Kind.HANDHELD), byId.get("abyss_maul"));
        assertEquals(new UiPack.UniqueSkin("abyss_string_bow", "bow", UiPack.UniqueSkin.Kind.BOW), byId.get("abyss_string_bow"));
        assertEquals(new UiPack.UniqueSkin("barrage_crossbow", "crossbow", UiPack.UniqueSkin.Kind.CROSSBOW), byId.get("barrage_crossbow"));
        assertEquals(new UiPack.UniqueSkin("abyss_fang_spear", "iron_spear", UiPack.UniqueSkin.Kind.SPEAR), byId.get("abyss_fang_spear"), "矛的外觀掛在 item_model 指的原版定義上");
        assertEquals(new UiPack.UniqueSkin("thunder_spine", "bamboo", UiPack.UniqueSkin.Kind.HANDHELD), byId.get("thunder_spine"));
        assertEquals(new UiPack.UniqueSkin("celestial_pivot", "amethyst_shard", UiPack.UniqueSkin.Kind.GENERATED), byId.get("celestial_pivot"));
        assertEquals(new UiPack.UniqueSkin("stoneguard_plate", "iron_chestplate", UiPack.UniqueSkin.Kind.GENERATED), byId.get("stoneguard_plate"));
        assertEquals(new UiPack.UniqueSkin("colossus_heart", "iron_chestplate", UiPack.UniqueSkin.Kind.GENERATED, true), byId.get("colossus_heart"), "追逐級");
        assertEquals(UiPack.UniqueSkin.Kind.HANDHELD, byId.get("voidfont").kind());
        assertEquals("blaze_rod", byId.get("marrowblight").vanillaItem(), "法杖掛在同一個原版物品上");
        assertEquals("blaze_rod", byId.get("prayerbead_wand").vanillaItem(), "法杖掛在同一個原版物品上");
    }

    @Test
    void packHasTexturesModelsAndOverrides() throws IOException {
        for (final UiPack.UniqueSkin skin : skins) {
            final String texture = "assets/exilecore/textures/item/unique/" + skin.id() + ".png";
            assertTrue(files.containsKey(texture), texture);
            final JsonObject model = json("assets/exilecore/models/item/unique/" + skin.id() + ".json");
            assertEquals("exilecore:item/unique/" + skin.id(), model.getAsJsonObject("textures").get("layer0").getAsString());
            final String parent = model.get("parent").getAsString();
            switch (skin.kind()) {
                case HANDHELD -> assertEquals("minecraft:item/handheld", parent);
                case GENERATED -> assertEquals("minecraft:item/generated", parent);
                case BOW -> {
                    assertEquals("minecraft:item/bow", parent);
                    for (int i = 0; i < 3; i++) {
                        assertTrue(files.containsKey("assets/exilecore/textures/item/unique/" + skin.id() + "_pulling_" + i + ".png"));
                    }
                }
                case CROSSBOW -> {
                    assertEquals("minecraft:item/crossbow", parent);
                    assertTrue(files.containsKey("assets/exilecore/textures/item/unique/" + skin.id() + "_arrow.png"));
                }
                case SPEAR -> {
                    assertEquals("minecraft:item/generated", parent);
                    final BufferedImage inHand = ImageIO.read(new ByteArrayInputStream(files.get("assets/exilecore/textures/item/unique/" + skin.id() + "_in_hand.png")));
                    assertEquals(32, inHand.getWidth(), "拿在手上的矛是 32×32");
                    assertEquals("minecraft:item/spear_in_hand", json("assets/exilecore/models/item/unique/" + skin.id() + "_in_hand.json").get("parent").getAsString());
                }
            }
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(files.get(texture)));
            assertEquals(16, image.getWidth(), texture);
            assertEquals(16, image.getHeight(), texture);
            // 原版物品的定義：custom_model_data 字串 0 選我們的，其他照原版
            final JsonObject override = json("assets/minecraft/items/" + skin.vanillaItem() + ".json");
            final JsonObject select = UiPackTest.vanillaSelect(override);
            assertEquals("minecraft:select", select.get("type").getAsString());
            assertEquals("minecraft:custom_model_data", select.get("property").getAsString());
            assertEquals(0, select.get("index").getAsInt());
            boolean found = false;
            for (final JsonElement entry : select.getAsJsonArray("cases")) {
                if (entry.getAsJsonObject().get("when").getAsString().equals(UniqueArt.modelKey(skin.id()))) {
                    found = true;
                }
            }
            assertTrue(found, skin.id() + " 要在 " + skin.vanillaItem() + " 的定義裡");
            final JsonObject vanilla = JsonParser.parseString(new String(UiPack.class.getResourceAsStream("/web/vanilla_items/" + skin.vanillaItem() + ".json")
                    .readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(vanilla.get("model"), select.get("fallback"), skin.vanillaItem() + " 原版的部分要照抄");
        }
        // 矛的定義保留 swap_animation_scale
        assertTrue(json("assets/minecraft/items/iron_spear.json").has("swap_animation_scale"));
        // 弓：拉弓的結構跟原版一樣
        final JsonObject bow = json("assets/minecraft/items/bow.json");
        // 2026-10：格子裡（gui）多一圈傳奇顏色的外框，其他地方（手上、地上）照專屬外觀本身
        final JsonObject framed = UiPackTest.vanillaSelect(bow).getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonObject("model");
        assertEquals("minecraft:select", framed.get("type").getAsString());
        assertEquals("minecraft:display_context", framed.get("property").getAsString());
        assertEquals("gui", framed.getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonArray("when").get(0).getAsString());
        final JsonObject ours = framed.getAsJsonObject("fallback");
        assertEquals("minecraft:condition", ours.get("type").getAsString());
        assertEquals("minecraft:using_item", ours.get("property").getAsString());
        assertEquals("minecraft:range_dispatch", ours.getAsJsonObject("on_true").get("type").getAsString());
    }

    @Test
    void deterministicAndSmall() throws IOException {
        final UiPack.Built again = UiPack.build(skins);
        assertEquals(pack.sha1(), again.sha1());
        assertTrue(pack.zip().length < UiPackTest.MAX_PACK_BYTES, "資源包要小（2026-10 多了招式預警的地面範圍、幻化、3D 時裝）：" + pack.zip().length);
        assertTrue(pack.files() > UiPack.build().files(), "多了傳奇的貼圖、模型與物品定義");
    }
}
