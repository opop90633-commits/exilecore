package tw.exilecore.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 介面資源包：檔案齊全、JSON 讀得懂、引用的模型與貼圖都在、每次產生的一模一樣。 */
class UiPackTest {

    static UiPack.Built pack;
    static Map<String, byte[]> files;

    @BeforeAll
    static void build() throws IOException {
        pack = UiPack.build();
        files = unzip(pack.zip());
    }

    static Map<String, byte[]> unzip(final byte[] zip) throws IOException {
        final Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                result.put(entry.getName(), in.readAllBytes());
            }
        }
        return result;
    }

    static JsonObject json(final String path) {
        final byte[] bytes = files.get(path);
        assertNotNull(bytes, "資源包裡沒有 " + path);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void sameContentSameHash() throws IOException {
        final UiPack.Built again = UiPack.build();
        assertEquals(pack.sha1(), again.sha1(), "雜湊一樣，玩家才不用每次重新下載");
        assertArrayEquals(pack.zip(), again.zip());
        assertEquals(40, pack.sha1().length());
        assertEquals(files.size(), pack.files());
    }

    @Test
    void packMetaTargets262() {
        final JsonObject meta = json("pack.mcmeta").getAsJsonObject("pack");
        assertEquals(UiPack.PACK_FORMAT, meta.get("min_format").getAsInt());
        assertTrue(meta.get("max_format").getAsInt() >= UiPack.PACK_FORMAT);
        assertTrue(files.containsKey("pack.png"));
        // 2026-10 多了招式預警的地面範圍（74 張 128×128 的圖）：還是一次下載、之後有快取
        assertTrue(pack.zip().length < 512 * 1024, "資源包要小：" + pack.zip().length);
    }

    @Test
    void everyJsonParses() {
        for (final String path : files.keySet()) {
            if (path.endsWith(".json") || path.endsWith(".mcmeta")) {
                json(path);
            }
        }
    }

    @Test
    void everyReferenceResolves() {
        // 物品定義引用的模型（我們自己的命名空間要在資源包裡；minecraft: 的是原版的）
        final List<String> models = new ArrayList<>();
        for (final String path : files.keySet()) {
            if (path.matches("assets/[a-z_]+/items/.*\\.json")) {
                collect(json(path), "model", models);
            }
        }
        assertTrue(models.size() >= 14 + UiPack.ICONS.size(), "背景 4＋連線 4＋發光＋節點 4＋標記＋圖示：" + models);
        final Set<String> seenTextures = new HashSet<>();
        final List<String> queue = new ArrayList<>(models);
        final Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            final String model = queue.removeLast();
            if (model.startsWith("minecraft:") || !visited.add(model)) {
                continue;
            }
            final JsonObject definition = json(modelPath(model));
            if (definition.has("textures")) {
                for (final Map.Entry<String, JsonElement> texture : definition.getAsJsonObject("textures").entrySet()) {
                    final String value = texture.getValue().getAsString();
                    if (value.startsWith("#") || value.startsWith("minecraft:")) {
                        continue;
                    }
                    final String texturePath = "assets/" + value.replace(":", "/textures/") + ".png";
                    assertTrue(files.containsKey(texturePath), model + " 的貼圖不見了：" + texturePath);
                    seenTextures.add(texturePath);
                }
            }
            if (definition.has("parent")) {
                queue.add(definition.get("parent").getAsString());
            }
        }
        for (final String path : files.keySet()) {
            // 原版介面的圖（gui/sprites）是直接蓋掉原版的，不經過模型
            if (path.endsWith(".png") && !path.equals("pack.png") && !path.startsWith("assets/minecraft/textures/gui/sprites/")) {
                assertTrue(seenTextures.contains(path), "沒用到的貼圖：" + path);
            }
        }
        // 天賦樹格子的父模型（一層一層）
        for (final String layer : List.of("layer_bg", "layer_1", "layer_2", "layer_3", "layer_4")) {
            final JsonObject parent = json("assets/exilecore/models/item/ui/" + layer + ".json");
            assertEquals("#t", parent.getAsJsonObject("textures").get("particle").getAsString());
            assertEquals(1, parent.getAsJsonArray("elements").size());
        }
    }

    @Test
    void everySlotIconHasASilhouette() {
        // 2026-10：每個空欄位的圖示都畫得出東西（剪影沒寫到、名字打錯會是一張透明的圖）；_locked 比較暗、右下角有鎖
        for (final String icon : UiPack.ICONS) {
            if (!icon.startsWith("slot_")) {
                continue;
            }
            final BufferedImage image = UiPack.slotIcon(icon);
            int painted = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    painted += (image.getRGB(x, y) >>> 24) > 0 ? 1 : 0;
                }
            }
            assertTrue(painted >= 20, icon + " 幾乎是空的（" + painted + " 格）");
            if (icon.endsWith("_locked")) {
                assertEquals(0xFFC8C8C8, image.getRGB(11, 9), icon + " 右下角要有鎖");
            }
        }
    }

    @Test
    void iconsHaveOneCaseEach() {
        final JsonObject select = json("assets/exilecore/items/ui_icon.json").getAsJsonObject("model");
        assertEquals("minecraft:select", select.get("type").getAsString());
        assertEquals(0, select.get("index").getAsInt());
        final Set<String> cases = new HashSet<>();
        for (final JsonElement entry : select.getAsJsonArray("cases")) {
            cases.add(entry.getAsJsonObject().get("when").getAsString());
        }
        assertEquals(Set.copyOf(UiPack.ICONS), cases);
    }

    @Test
    void definitionUsesTheCellLayout() {
        final JsonObject root = json("assets/exilecore/items/tree_cell.json");
        assertTrue(root.get("oversized_in_gui").getAsBoolean(), "要能畫出格子外（18×18）");
        final var layers = root.getAsJsonObject("model").getAsJsonArray("models");
        // 背景、四條線、發光、節點、標記
        assertEquals(8, layers.size());
        assertEquals(UiPack.Cell.STRING_BACKGROUND, layers.get(0).getAsJsonObject().get("index").getAsInt());
        for (int i = 0; i < 4; i++) {
            final JsonObject edge = layers.get(1 + i).getAsJsonObject();
            assertEquals("minecraft:condition", edge.get("type").getAsString());
            assertEquals(i, edge.get("index").getAsInt());
            assertEquals(i, edge.getAsJsonObject("on_true").getAsJsonArray("tints").get(0).getAsJsonObject().get("index").getAsInt());
        }
        assertEquals(UiPack.Cell.STRING_GLOW, layers.get(5).getAsJsonObject().get("index").getAsInt());
        final JsonObject nodes = layers.get(6).getAsJsonObject();
        assertEquals(UiPack.Cell.STRING_NODE, nodes.get("index").getAsInt());
        final Set<String> cases = new HashSet<>();
        for (final JsonElement entry : nodes.getAsJsonArray("cases")) {
            cases.add(entry.getAsJsonObject().get("when").getAsString());
        }
        assertEquals(Set.copyOf(UiPack.Cell.NODES), cases);
        assertEquals(UiPack.Cell.STRING_RING, layers.get(7).getAsJsonObject().get("index").getAsInt());
    }

    @Test
    void texturesAre32SquarePng() throws IOException {
        int count = 0;
        for (final Map.Entry<String, byte[]> file : files.entrySet()) {
            if (!file.getKey().startsWith("assets/") || !file.getKey().endsWith(".png")
                    || file.getKey().startsWith("assets/minecraft/textures/gui/sprites/dialog/")
                    || file.getKey().startsWith("assets/exilecore/textures/item/currency/")
                    || file.getKey().startsWith("assets/exilecore/textures/item/mark/")) {
                continue; // 通貨的圖示是 16×16、地上的範圍是 128×128，另外測
            }
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(file.getValue()));
            assertNotNull(image, file.getKey());
            assertEquals(32, image.getWidth(), file.getKey());
            assertEquals(32, image.getHeight(), file.getKey());
            count++;
        }
        assertEquals(UiPack.BACKGROUNDS + 4 + 1 + UiPack.Cell.NODES.size() + 1 + UiPack.ICONS.size() + 1 + UiPack.FLASK_SLOTS.size(), count,
                "背景、四條線、發光、節點（含珠寶孔兩種）、標記、按鍵圖示、選取外框、藥劑欄的空瓶輪廓");
    }

    @Test
    void highlightFramesTheOriginalItem() {
        final JsonObject root = json("assets/exilecore/items/highlight.json");
        assertTrue(root.get("oversized_in_gui").getAsBoolean(), "框要畫到格子外（18×18）");
        final JsonObject composite = root.getAsJsonObject("model");
        assertEquals("minecraft:composite", composite.get("type").getAsString());
        final var layers = composite.getAsJsonArray("models");
        assertEquals(2, layers.size(), "先畫框，再畫原本的物品");
        final JsonObject frame = layers.get(0).getAsJsonObject();
        assertEquals("exilecore:item/ui/highlight", frame.get("model").getAsString());
        final JsonObject tint = frame.getAsJsonArray("tints").get(0).getAsJsonObject();
        assertEquals("minecraft:custom_model_data", tint.get("type").getAsString());
        assertEquals(0, tint.get("index").getAsInt(), "顏色 0 是框的顏色");
        final JsonObject select = layers.get(1).getAsJsonObject();
        assertEquals("minecraft:select", select.get("type").getAsString());
        assertEquals(0, select.get("index").getAsInt(), "字串 0 是原本的物品");
        final Map<String, JsonObject> cases = new LinkedHashMap<>();
        for (final JsonElement entry : select.getAsJsonArray("cases")) {
            cases.put(entry.getAsJsonObject().get("when").getAsString(), entry.getAsJsonObject().getAsJsonObject("model"));
        }
        assertEquals(UiPack.framableCount(), cases.size());
        assertTrue(cases.size() > 1300, "原版物品幾乎都畫得出框：" + cases.size());
        // 一般的物品、方塊、名稱跟模型不一樣的
        assertEquals("minecraft:item/emerald", cases.get("emerald").get("model").getAsString());
        assertEquals("minecraft:block/lodestone", cases.get("lodestone").get("model").getAsString());
        assertEquals("minecraft:block/oak_fence_inventory", cases.get("oak_fence").get("model").getAsString());
        // 規則：盔甲（沒有紋飾）、染色的皮甲、藥水的顏色、旗幟／箱子／頭顱的特殊繪製
        assertEquals("minecraft:item/iron_chestplate", cases.get("iron_chestplate").get("model").getAsString());
        assertEquals("minecraft:dye", cases.get("leather_boots").getAsJsonArray("tints").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("minecraft:potion", cases.get("potion").getAsJsonArray("tints").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("lime", cases.get("lime_banner").getAsJsonObject("model").get("color").getAsString());
        assertEquals("minecraft:ender", cases.get("ender_chest").getAsJsonObject("model").get("texture").getAsString());
        assertEquals("minecraft:player_head", cases.get("player_head").getAsJsonObject("model").get("type").getAsString());
        for (final String id : List.of("emerald", "iron_sword", "lime_stained_glass_pane", "ender_eye", "chest", "lime_banner", "book")) {
            assertTrue(UiPack.framable(id), id);
        }
        assertTrue(!UiPack.framable("red_bed") && !UiPack.framable("no_such_item"), "畫不出來的：只發光");
    }

    @Test
    void emptyFlaskSlotsAreColouredBottles() {
        final JsonObject select = json("assets/minecraft/items/glass_bottle.json").getAsJsonObject("model");
        assertEquals("minecraft:select", select.get("type").getAsString());
        assertEquals("minecraft:custom_model_data", select.get("property").getAsString());
        assertEquals(0, select.get("index").getAsInt(), "字串 0 是 life／mana");
        final Set<String> cases = new HashSet<>();
        for (final JsonElement entry : select.getAsJsonArray("cases")) {
            cases.add(entry.getAsJsonObject().get("when").getAsString());
        }
        assertEquals(Set.of("life", "mana"), cases);
        assertEquals("minecraft:item/glass_bottle", select.getAsJsonObject("fallback").get("model").getAsString(),
                "其他的玻璃瓶照原版");
        final BufferedImage life = UiPack.flaskSlot(UiPack.FLASK_SLOTS.get("life"));
        assertEquals(0, life.getRGB(0, 0) >>> 24, "角落透明");
        assertEquals(0xFF5C5C, life.getRGB(16, 28) & 0xFFFFFF, "瓶底的輪廓是紅色");
    }

    @Test
    void dialogNoticeButtonIsSmallAndKeepsItsShape() throws IOException {
        for (final String name : List.of("warning_button", "warning_button_highlighted", "warning_button_disabled")) {
            final String path = "assets/minecraft/textures/gui/sprites/dialog/" + name + ".png";
            final byte[] bytes = files.get(path);
            assertNotNull(bytes, "資源包裡沒有 " + path);
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            assertEquals(20, image.getWidth(), "跟原版一樣 20×20（按鈕的大小是客戶端決定的）");
            assertEquals(20, image.getHeight());
            int visible = 0;
            for (int y = 0; y < 20; y++) {
                for (int x = 0; x < 20; x++) {
                    if ((image.getRGB(x, y) >>> 24) != 0) {
                        visible++;
                    }
                }
            }
            assertTrue(visible > 30, name + "：還是看得到（安全提示不能整個藏起來）");
            assertTrue(visible < 100, name + "：只佔中間一小塊，四周透明");
            assertEquals(0, image.getRGB(0, 0) >>> 24, "角落透明（沒有原版的按鈕框）");
        }
        assertFalse(files.containsKey("assets/minecraft/lang/zh_tw.json"), "說明文字照原版");
    }

    @Test
    void miningHitSoundsAreMuted() {
        // 按住左鍵連續施放時客戶端以為在挖方塊（skill/HoldToCast）：挖方塊的敲擊聲全部換成沒有聲音
        final List<String> muted = UiPack.mutedSounds();
        assertEquals(112, muted.size(), "Minecraft 26.2 的方塊敲擊聲");
        assertTrue(muted.contains("block.stone.hit"));
        assertTrue(muted.contains("block.grass.hit"));
        for (final String event : muted) {
            assertTrue(event.startsWith("block.") && event.endsWith(".hit"), event);
        }
        final JsonObject sounds = json("assets/minecraft/sounds.json");
        assertEquals(muted.size(), sounds.size());
        final JsonObject stone = sounds.getAsJsonObject("block.stone.hit");
        assertTrue(stone.get("replace").getAsBoolean());
        assertEquals(0, stone.getAsJsonArray("sounds").size(), "換成空的：播的時候什麼都沒有");
        assertTrue(files.keySet().stream().filter(path -> path.endsWith("sounds.json")).count() == 1, "只動原版的 sounds.json");
    }

    @Test
    void frameIsARingWithAClearMiddle() {
        final BufferedImage frame = UiPack.highlightFrame();
        assertEquals(0xFF, frame.getRGB(2, 16) >>> 24, "左邊的框");
        assertEquals(0xFF, frame.getRGB(16, 29) >>> 24, "下面的框");
        assertEquals(0, frame.getRGB(16, 16) >>> 24, "中間透明（物品畫在這裡）");
        assertEquals(0, frame.getRGB(10, 10) >>> 24);
        assertEquals(frame.getRGB(1, 16), frame.getRGB(30, 16), "左右對稱");
    }

    @Test
    void backgroundsAreOpaqueAndOverlaysAreNot() {
        for (int i = 0; i < UiPack.BACKGROUNDS; i++) {
            final BufferedImage image = UiPack.background(i);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    assertEquals(0xFF, image.getRGB(x, y) >>> 24, "背景要完全不透明（蓋掉箱子的格子）");
                }
            }
        }
        // 往上的線只在上半部
        final BufferedImage north = UiPack.edge("n");
        assertEquals(0, north.getRGB(16, 30) >>> 24);
        assertEquals(0xFF, north.getRGB(16, 2) >>> 24);
        final BufferedImage east = UiPack.edge("e");
        assertEquals(0xFF, east.getRGB(30, 16) >>> 24);
        assertEquals(0, east.getRGB(2, 16) >>> 24);
        // 四個方向接起來是對稱的
        assertEquals(UiPack.edge("w").getRGB(2, 16), east.getRGB(29, 16));
        assertEquals(UiPack.edge("s").getRGB(16, 29), north.getRGB(16, 2));
    }

    private static String modelPath(final String id) {
        final String[] parts = id.split(":", 2);
        return "assets/" + parts[0] + "/models/" + parts[1] + ".json";
    }

    private static void collect(final JsonElement element, final String key, final List<String> out) {
        if (element.isJsonObject()) {
            final JsonObject object = element.getAsJsonObject();
            if (object.has("type") && object.get("type").getAsString().equals("minecraft:model") && object.has(key)) {
                out.add(object.get(key).getAsString());
            }
            for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
                collect(entry.getValue(), key, out);
            }
        } else if (element.isJsonArray()) {
            for (final JsonElement child : element.getAsJsonArray()) {
                collect(child, key, out);
            }
        }
    }

    @Test
    void groundMarksAreFlatTintedPlanes() throws IOException {
        final JsonObject plane = json("assets/exilecore/models/item/mark/plane.json");
        final var elements = plane.getAsJsonArray("elements");
        assertEquals(1, elements.size(), "一片平面");
        final JsonObject element = elements.get(0).getAsJsonObject();
        assertEquals(8, element.getAsJsonArray("from").get(1).getAsInt(), "在模型正中間的高度（畫的時候往回移半格）");
        assertEquals(8, element.getAsJsonArray("to").get(1).getAsInt(), "沒有厚度");
        final JsonObject faces = element.getAsJsonObject("faces");
        assertEquals(Set.of("up"), faces.keySet(), "只有朝上那一面");
        assertEquals(0, faces.getAsJsonObject("up").get("tintindex").getAsInt(), "著色");
        for (final String name : UiPack.markNames()) {
            final JsonObject definition = json("assets/exilecore/items/mark/" + name + ".json").getAsJsonObject("model");
            assertEquals("exilecore:item/mark/" + name, definition.get("model").getAsString());
            final JsonObject tint = definition.getAsJsonArray("tints").get(0).getAsJsonObject();
            assertEquals("minecraft:custom_model_data", tint.get("type").getAsString(), name);
            assertEquals(0, tint.get("index").getAsInt(), "顏色 0 是招式的顏色");
            assertEquals("exilecore:item/mark/plane", json("assets/exilecore/models/item/mark/" + name + ".json").get("parent").getAsString());
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(files.get("assets/exilecore/textures/item/mark/" + name + ".png")));
            assertEquals(128, image.getWidth(), name);
            assertEquals(128, image.getHeight(), name);
        }
        final BufferedImage disc = UiPack.markImage("disc");
        final int middle = disc.getRGB(64, 64) >>> 24;
        assertTrue(middle > 30 && middle < 120, "圓：裡面半透明 " + middle);
        assertTrue((disc.getRGB(64, 2) >>> 24) > 200, "圓：外框不透明");
        assertEquals(0, disc.getRGB(1, 1) >>> 24, "圓：角落透明");
        assertEquals(0xFFFFFF, disc.getRGB(64, 64) & 0xFFFFFF, "白色（著色後才是招式的顏色）");
        assertEquals(0, UiPack.markImage("ring").getRGB(64, 64) >>> 24, "外框圓：中間透明");
        final BufferedImage sector = UiPack.markImage("sector_90");
        assertTrue((sector.getRGB(64, 30) >>> 24) > 30, "扇形往圖的上方張開");
        assertEquals(0, sector.getRGB(64, 100) >>> 24, "下方是空的");
        assertEquals(0, sector.getRGB(15, 64) >>> 24, "左右（90 度的扇形以外）是空的");
        final BufferedImage rect = UiPack.markImage("rect");
        assertTrue((rect.getRGB(2, 64) >>> 24) > 200, "長條：左右兩條邊");
        assertTrue((rect.getRGB(64, 64) >>> 24) < 120, "長條：中間半透明");
    }

    @Test
    void everyCurrencyModelHasAnIcon() throws IOException {
        // currencies.yml 寫 model: exilecore:currency/代號 的每一種，資源包裡都要有定義、模型、16×16 的貼圖；反過來每張圖也要有通貨用
        final tw.exilecore.currency.CurrencyRepository currencies = new tw.exilecore.currency.CurrencyRepository();
        currencies.load(java.nio.file.Path.of("src/main/resources/data"));
        int modelled = 0;
        for (final tw.exilecore.currency.CurrencyType type : currencies.all()) {
            final String id = tw.exilecore.currency.CurrencyArt.idOf(type.model());
            if (id == null) {
                continue;
            }
            modelled++;
            assertEquals(type.id(), id, type.id() + " 的 model 要是 exilecore:currency/自己的代號");
            assertTrue(tw.exilecore.currency.CurrencyArt.has(id), type.id() + " 沒有像素圖（currency_art.txt）");
            assertTrue(files.containsKey("assets/exilecore/items/currency/" + id + ".json"), id + " 的物品定義");
            final JsonObject definition = json("assets/exilecore/items/currency/" + id + ".json");
            assertEquals("exilecore:item/currency/" + id, definition.getAsJsonObject("model").get("model").getAsString());
            final JsonObject model = json("assets/exilecore/models/item/currency/" + id + ".json");
            assertEquals("minecraft:item/generated", model.get("parent").getAsString());
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(files.get("assets/exilecore/textures/item/currency/" + id + ".png")));
            assertEquals(16, image.getWidth(), id);
            assertEquals(16, image.getHeight(), id);
        }
        assertEquals(tw.exilecore.currency.CurrencyArt.sprites().size(), modelled, "每張通貨的圖都要有通貨在用");
        assertTrue(modelled >= 22);
    }
}
