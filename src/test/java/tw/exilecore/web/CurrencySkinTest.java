package tw.exilecore.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyArt;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemRepository;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通貨的貼圖：物品上掛 custom_model_data 的字串 currency:代號，資源包把通貨用的原版物品改成「看到這個字串就畫我們的圖」。
 * 不看 resource-pack.enabled（那個開關以前讓通貨一直沒貼圖）；跟傳奇、空藥劑欄共用同一個原版物品時，三邊的挑選都要留著。
 */
class CurrencySkinTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static CurrencyRepository currencies;
    static ItemRepository items;
    static List<UiPack.CurrencySkin> currencySkins;
    static List<UiPack.UniqueSkin> uniqueSkins;
    static UiPack.Built pack;
    static Map<String, byte[]> files;

    @BeforeAll
    static void build() throws IOException {
        currencies = new CurrencyRepository();
        currencies.load(DATA);
        items = new ItemRepository();
        items.load(DATA);
        currencySkins = UiPack.currencySkins(currencies);
        uniqueSkins = UiPack.skins(items);
        pack = UiPack.build(uniqueSkins, currencySkins);
        files = UiPackTest.unzip(pack.zip());
    }

    static JsonObject json(final String path) {
        final byte[] bytes = files.get(path);
        assertNotNull(bytes, "資源包裡沒有 " + path);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** 一個原版物品定義裡的挑選：when → 模型。 */
    static Map<String, String> cases(final String vanillaItem) {
        final JsonObject select = json("assets/minecraft/items/" + vanillaItem + ".json").getAsJsonObject("model");
        assertEquals("minecraft:select", select.get("type").getAsString());
        assertEquals("minecraft:custom_model_data", select.get("property").getAsString());
        assertEquals(0, select.get("index").getAsInt(), "字串 0 決定畫哪一張");
        final Map<String, String> result = new HashMap<>();
        for (final JsonElement entry : select.getAsJsonArray("cases")) {
            final JsonObject object = entry.getAsJsonObject();
            final String key = object.get("when").getAsString();
            assertFalse(result.containsKey(key), vanillaItem + " 的挑選重複了：" + key);
            final JsonObject model = object.getAsJsonObject("model");
            result.put(key, model.has("model") ? model.get("model").getAsString() : model.get("type").getAsString());
        }
        return result;
    }

    @Test
    void everyArtCurrencyHasASkinOnItsOwnMaterial() {
        final Set<String> arts = new HashSet<>();
        for (final CurrencyType type : currencies.all()) {
            final String art = CurrencyArt.artOf(type);
            if (art == null) {
                continue;
            }
            arts.add(art);
            final String material = type.material().toLowerCase(java.util.Locale.ROOT);
            assertTrue(currencySkins.contains(new UiPack.CurrencySkin(art, material)),
                    type.id() + " 的圖要掛在 " + material + " 上");
        }
        assertEquals(CurrencyArt.sprites().keySet(), arts, "每張通貨的圖都要有通貨在用");
        assertEquals(currencySkins.size(), new HashSet<>(currencySkins).size());
        assertTrue(currencySkins.size() >= 22, "目前有 22 種通貨有圖：" + currencySkins.size());
    }

    @Test
    void everyCurrencyCaseShowsItsOwnModel() throws IOException {
        for (final UiPack.CurrencySkin skin : currencySkins) {
            final Map<String, String> cases = cases(skin.vanillaItem());
            assertEquals("exilecore:item/currency/" + skin.artId(), cases.get(CurrencyArt.modelKey(skin.artId())),
                    skin.artId() + " 要在 " + skin.vanillaItem() + " 的定義裡");
            final JsonObject model = json("assets/exilecore/models/item/currency/" + skin.artId() + ".json");
            assertEquals("minecraft:item/generated", model.get("parent").getAsString());
            assertEquals("exilecore:item/currency/" + skin.artId(), model.getAsJsonObject("textures").get("layer0").getAsString());
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(files.get("assets/exilecore/textures/item/currency/" + skin.artId() + ".png")));
            assertEquals(16, image.getWidth(), skin.artId());
            assertEquals(16, image.getHeight(), skin.artId());
        }
    }

    @Test
    void otherItemsOfTheSameKindStayVanilla() throws IOException {
        // 原版的部分：有 web/vanilla_items/ 就照抄它，沒有就是 minecraft:item/<名稱>；而且通貨用的原版物品都得是單純的 item/<名稱>
        final Set<String> plain = new HashSet<>();
        for (final String line : Files.readAllLines(Path.of("src/main/resources/web/vanilla_item_models.txt"), StandardCharsets.UTF_8)) {
            if (!line.isBlank() && !line.startsWith("#") && !line.contains(" ")) {
                plain.add(line.trim());
            }
        }
        for (final UiPack.CurrencySkin skin : currencySkins) {
            final JsonObject select = json("assets/minecraft/items/" + skin.vanillaItem() + ".json").getAsJsonObject("model");
            final JsonObject fallback = select.getAsJsonObject("fallback");
            final java.io.InputStream override = UiPack.class.getResourceAsStream("/web/vanilla_items/" + skin.vanillaItem() + ".json");
            if (override != null) {
                final JsonObject vanilla = JsonParser.parseString(new String(override.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(vanilla.get("model"), fallback, skin.vanillaItem() + " 原版的部分照抄");
            } else {
                assertTrue(plain.contains(skin.vanillaItem()),
                        skin.vanillaItem() + " 不是單純的平面物品（或不是原版物品）：要在 web/vanilla_items/ 補它的原版定義");
                assertEquals("minecraft:item/" + skin.vanillaItem(), fallback.get("model").getAsString());
            }
        }
    }

    @Test
    void sharedVanillaItemsKeepEveryonesCases() {
        // 空藥劑欄的空瓶提示跟蒸餾通貨都用玻璃瓶
        final Map<String, String> bottle = cases("glass_bottle");
        assertEquals("exilecore:item/ui/flask_slot_life", bottle.get("life"));
        assertEquals("exilecore:item/ui/flask_slot_mana", bottle.get("mana"));
        for (final String distilled : List.of("distilled_ire", "distilled_guilt", "distilled_greed", "distilled_paranoia")) {
            assertEquals("exilecore:item/currency/" + distilled, bottle.get(CurrencyArt.modelKey(distilled)), distilled);
        }
        assertEquals(6, bottle.size());
        // 傳奇飾品跟通貨用同一個原版物品（火花＝紫水晶碎片、護甲片＝鐵粒）：兩邊都要有
        final Set<String> shared = new HashSet<>();
        for (final UiPack.CurrencySkin currency : currencySkins) {
            for (final UiPack.UniqueSkin unique : uniqueSkins) {
                if (unique.vanillaItem().equals(currency.vanillaItem())) {
                    shared.add(currency.vanillaItem());
                    final Map<String, String> cases = cases(currency.vanillaItem());
                    assertTrue(cases.containsKey(CurrencyArt.modelKey(currency.artId())), currency.artId() + " 被傳奇蓋掉了");
                    assertTrue(cases.containsKey(tw.exilecore.item.UniqueArt.modelKey(unique.id())), unique.id() + " 被通貨蓋掉了");
                }
            }
        }
        assertFalse(shared.isEmpty(), "測試要真的碰到共用的情形：" + shared);
    }

    @Test
    void selectedItemsKeepTheirArtInsideTheFrame() {
        // 選單裡「選到的」物品外面畫一圈框，框裡面畫物品：通貨與傳奇要畫它們的圖，不能被換回原版的樣子（交易所選到的通貨、倉庫搜尋到的傳奇）
        final JsonObject select = json("assets/exilecore/items/highlight.json").getAsJsonObject("model")
                .getAsJsonArray("models").get(1).getAsJsonObject();
        final Map<String, String> inFrame = new HashMap<>();
        for (final JsonElement entry : select.getAsJsonArray("cases")) {
            final JsonObject object = entry.getAsJsonObject();
            final JsonObject model = object.getAsJsonObject("model");
            // 原版的有些是特殊繪製（旗幟、箱子…），model 欄位是物件；這裡只看單純指到模型的
            inFrame.put(object.get("when").getAsString(), model.has("model") && model.get("model").isJsonPrimitive() ? model.get("model").getAsString() : "?");
        }
        for (final UiPack.CurrencySkin skin : currencySkins) {
            assertEquals("exilecore:item/currency/" + skin.artId(), inFrame.get(CurrencyArt.modelKey(skin.artId())), skin.artId());
        }
        for (final UiPack.UniqueSkin skin : uniqueSkins) {
            assertEquals("exilecore:item/unique/" + skin.id(), inFrame.get(tw.exilecore.item.UniqueArt.modelKey(skin.id())), skin.id());
        }
        assertTrue(inFrame.containsKey("paper") && inFrame.containsKey("emerald"), "原版物品的照舊");
        assertTrue(UiPack.isArtKey(CurrencyArt.modelKey("spark_orb")));
        assertTrue(UiPack.isArtKey(tw.exilecore.item.UniqueArt.modelKey("tomb_oath")));
        assertFalse(UiPack.isArtKey("paper"));
        assertFalse(UiPack.isArtKey("life"));
        assertFalse(UiPack.isArtKey(null));
    }

    @Test
    void noCurrencySkinsMeansNoCurrencyCases() throws IOException {
        // 不給通貨掛載點（舊的呼叫方式）：還是有貼圖與模型，但沒有任何原版物品會用到、玻璃瓶還是只有兩個空瓶提示
        final Map<String, byte[]> bare = UiPackTest.unzip(UiPack.build(uniqueSkins).zip());
        final JsonObject bottle = JsonParser.parseString(new String(bare.get("assets/minecraft/items/glass_bottle.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(2, bottle.getAsJsonObject("model").getAsJsonArray("cases").size());
        assertFalse(bare.containsKey("assets/minecraft/items/paper.json"));
        assertTrue(bare.containsKey("assets/exilecore/models/item/currency/spark_orb.json"));
    }

    @Test
    void packStaysSmallAndDeterministic() throws IOException {
        assertTrue(pack.zip().length < 512 * 1024, "資源包要小（2026-10 多了招式預警的地面範圍）：" + pack.zip().length);
        assertEquals(pack.sha1(), UiPack.build(uniqueSkins, UiPack.currencySkins(currencies)).sha1(), "每次產生都一樣，玩家才不用重新下載");
        assertFalse(pack.sha1().equals(UiPack.build(uniqueSkins).sha1()), "有掛通貨的包跟沒有的不一樣");
        final List<String> all = new ArrayList<>(files.keySet());
        assertTrue(all.contains("assets/minecraft/items/paper.json"), "鑑定卷軸用紙：" + all.size());
    }

    @Test
    void artFollowsTheModelFieldOrTheCurrencyId() {
        final String spark = "spark_orb";
        assertEquals("currency:" + spark, CurrencyArt.modelKey(spark));
        assertEquals(spark, CurrencyArt.artOf(type(spark, "exilecore:currency/" + spark)));
        assertEquals(spark, CurrencyArt.artOf(type(spark, null)), "沒寫 model、有跟自己同代號的圖：照代號");
        assertEquals(spark, CurrencyArt.artOf(type(spark, " ")));
        assertEquals("annul_orb", CurrencyArt.artOf(type(spark, "exilecore:currency/annul_orb")), "model 指到哪張就是哪張");
        assertNull(CurrencyArt.artOf(type(spark, "myserver:custom/spark")), "伺服器自己的模型：不管（有它自己的資源包）");
        assertNull(CurrencyArt.artOf(type(spark, "exilecore:currency/no_such_art")), "指到沒有的圖：不掛（不要變成紫黑方塊）");
        assertNull(CurrencyArt.artOf(type("essence_body", null)), "沒有圖的通貨：不掛");
        assertNull(CurrencyArt.artOf(null));
    }

    private static CurrencyType type(final String id, final String model) {
        return new CurrencyType(id, id, "PAPER", model, 20, CurrencyOperation.IDENTIFY, Set.of(), 1, "", "", 1, 0, 0);
    }
}
