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

    /**
     * 資源包大小的上限：2026-10 多了招式預警的地面範圍（256×256）、裝備幻化、3D 時裝（web/pack3d.zip），
     * 已經不是 512 KB 以內了；還是一次下載、之後有快取，只是不能無限長大。
     */
    static final int MAX_PACK_BYTES = 12 * 1024 * 1024; // 2026-10：加了背景音樂（music/*.ogg）之後約 9.8 MB，上限 8 → 12 MB

    /**
     * 改寫的原版物品定義（assets/minecraft/items/）最外層是鎖定的小鎖頭（2026-10）：custom_model_data 的 flag 0
     * 是 true 才疊鎖頭，false 是原本的挑選（字串 0 選我們的圖，不然照原版）。回傳那個挑選。
     */
    static JsonObject vanillaSelect(final JsonObject root) {
        final JsonObject lock = root.getAsJsonObject("model");
        assertEquals("minecraft:condition", lock.get("type").getAsString(), "最外層是鎖頭的條件");
        assertEquals("minecraft:custom_model_data", lock.get("property").getAsString());
        assertEquals(0, lock.get("index").getAsInt(), "flag 0 是鎖定");
        return lock.getAsJsonObject("on_false");
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
        // 2026-10 多了招式預警的地面範圍（256×256 的圖）、裝備幻化、3D 時裝：還是一次下載、之後有快取
        assertTrue(pack.zip().length < MAX_PACK_BYTES, "資源包要小：" + pack.zip().length);
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
        // 稀有度、追逐級傳奇的外框（2026-10）：一定會產生，但要有裝備（gearItems）、傳奇的外觀才會被物品定義用到
        queue.add("exilecore:item/ui/rarity_frame");
        // 2026-10-27：傳奇、追逐級的外框改成發光底＋實線外框兩張（這次之前追逐級是一張 chase_frame）
        queue.add("exilecore:item/ui/unique_glow");
        queue.add("exilecore:item/ui/unique_border");
        queue.add("exilecore:item/ui/chase_glow");
        queue.add("exilecore:item/ui/chase_border");
        // 專屬寶石的實線外框（2026-10）：一定會產生，但要有專屬寶石的外觀才會被物品定義用到
        queue.add("exilecore:item/ui/unique_outline");
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
        // 穿在身上的裝備幻化（2026-10，assets/*/equipment/）：每一層的 texture 指到 textures/entity/equipment/<層>/
        for (final String path : files.keySet()) {
            if (!path.matches("assets/[a-z_]+/equipment/.*\\.json")) {
                continue;
            }
            for (final Map.Entry<String, JsonElement> layer : json(path).getAsJsonObject("layers").entrySet()) {
                for (final JsonElement entry : layer.getValue().getAsJsonArray()) {
                    final String[] id = entry.getAsJsonObject().get("texture").getAsString().split(":", 2);
                    final String texturePath = "assets/" + id[0] + "/textures/entity/equipment/" + layer.getKey() + "/" + id[1] + ".png";
                    assertTrue(files.containsKey(texturePath), path + " 的貼圖不見了：" + texturePath);
                    seenTextures.add(texturePath);
                }
            }
        }
        // 字型（選單背景、掉落物圖示、快捷欄、詞綴標籤）：bitmap 的 file 指到 textures/
        for (final String path : files.keySet()) {
            if (!path.matches("assets/[a-z_]+/font/.*\\.json")) {
                continue;
            }
            for (final JsonElement provider : json(path).getAsJsonArray("providers")) {
                final JsonObject object = provider.getAsJsonObject();
                if (!object.has("file")) {
                    continue;
                }
                final String[] id = object.get("file").getAsString().split(":", 2);
                final String texturePath = "assets/" + id[0] + "/textures/" + id[1];
                assertTrue(files.containsKey(texturePath), path + " 的貼圖不見了：" + texturePath);
                seenTextures.add(texturePath);
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
        int small = 0;
        int armour = 0;
        for (final Map.Entry<String, byte[]> file : files.entrySet()) {
            final String path = file.getKey();
            if (!path.startsWith("assets/") || !path.endsWith(".png")
                    || path.startsWith("assets/minecraft/textures/gui/sprites/")
                    || path.startsWith("assets/exilecore/textures/item/currency/")
                    || path.startsWith("assets/exilecore/textures/item/mark/")
                    || path.startsWith("assets/exilecore/textures/item/skin3d/")
                    || path.startsWith("assets/exilecore/textures/font/")) {
                // 原版介面的圖照原版的大小、通貨的圖示是 16×16、地上的範圍另外測、
                // 3D 時裝的貼圖照模型（web/pack3d.zip）、字型的圖照字的大小
                continue;
            }
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(file.getValue()));
            assertNotNull(image, path);
            if (path.startsWith("assets/exilecore/textures/entity/equipment/")) {
                // 穿在身上的裝備幻化：原版 64×32 的盔甲貼圖格式
                assertEquals(64, image.getWidth(), path);
                assertEquals(32, image.getHeight(), path);
                armour++;
                continue;
            }
            if (path.startsWith("assets/exilecore/textures/item/ui/icon/slot_") || path.equals("assets/exilecore/textures/item/ui/icon/community.png")
                    || path.equals("assets/exilecore/textures/item/ui/lock.png")) {
                // 空裝備欄的剪影、社群按鈕、鎖定的小鎖頭：一般物品的 16×16
                assertEquals(16, image.getWidth(), path);
                assertEquals(16, image.getHeight(), path);
                small++;
                continue;
            }
            assertEquals(32, image.getWidth(), path);
            if (files.containsKey(path + ".mcmeta")) {
                // 2026-10-27：動畫貼圖（追逐級傳奇的外框）是 32 寬、幾張 32×32 直排
                assertTrue(image.getHeight() > 32 && image.getHeight() % 32 == 0, path);
            } else {
                assertEquals(32, image.getHeight(), path);
            }
            count++;
        }
        final int smallIcons = (int) UiPack.ICONS.stream().filter(icon -> icon.startsWith("slot_") || icon.equals("community")).count();
        assertEquals(smallIcons + 1, small, "空裝備欄的剪影、社群按鈕、鎖頭");
        assertEquals(2 * transmogArmourThemes(), armour, "每一套幻化：身上、腿上各一張");
        assertEquals(UiPack.BACKGROUNDS + 4 + 1 + UiPack.Cell.NODES.size() + 1 + UiPack.ICONS.size() - smallIcons + 1 + UiPack.FLASK_SLOTS.size()
                        + 6 + transmogItemTextures() // 2026-10-27：稀有度外框、實線外框＋傳奇、追逐級各兩張（這次之前 3：追逐級一張）
                        + UiPack.AtlasCell.EDGES.size() + UiPack.AtlasCell.KINDS.size() + 1
                        + UiPack.AtlasCell.EDGES.size() // 2026-10-24：塔範圍外框的六邊
                        + MobHats.styles().size(), count, // 2026-10-31：地圖怪頭飾的色票
                "背景、四條線、發光、節點（含珠寶孔兩種）、標記、按鍵圖示、選取外框、藥劑欄的空瓶輪廓、稀有度與追逐級的外框、專屬寶石的實線外框、幻化的物品圖、"
                        + "星圖格子的六條連線、六種格子、腐化圈（2026-10-21）");
    }

    /** web/skins/index.txt 列的圖（tools/gen_skin_art.py 產生）。 */
    private static List<String> transmogNames() throws IOException {
        final List<String> names = new ArrayList<>();
        try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/skins/index.txt")) {
            assertNotNull(in, "web/skins/index.txt");
            for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                final String name = line.strip();
                if (!name.isEmpty() && !name.startsWith("#") && name.endsWith(".png")) {
                    names.add(name.substring(0, name.length() - 4));
                }
            }
        }
        return names;
    }

    private static int transmogArmourThemes() throws IOException {
        return (int) transmogNames().stream().filter(name -> name.endsWith("_humanoid")).count();
    }

    private static int transmogItemTextures() throws IOException {
        return (int) transmogNames().stream().filter(name -> !name.endsWith("_humanoid") && !name.endsWith("_humanoid_leggings")).count();
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
        assertEquals(UiPack.framableCount(), cases.size()); // 2026-10-24：沒給裝備（plainItems）時不放簡圖
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
        assertEquals("zombie", cases.get("zombie_head").getAsJsonObject("model").get("kind").getAsString(), "怪物頭顱也畫得出框");
        assertEquals("skeleton", cases.get("skeleton_skull").getAsJsonObject("model").get("kind").getAsString());
        for (final String id : List.of("emerald", "iron_sword", "lime_stained_glass_pane", "ender_eye", "chest", "lime_banner", "book")) {
            assertTrue(UiPack.framable(id), id);
        }
        assertTrue(!UiPack.framable("red_bed") && !UiPack.framable("no_such_item"), "畫不出來的：只發光");
    }

    @Test
    void emptyFlaskSlotsAreColouredBottles() {
        final JsonObject select = vanillaSelect(json("assets/minecraft/items/glass_bottle.json"));
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
        // 2026-10：原版的背景音樂也一起靜音（我們自己放音樂）
        assertEquals(muted.size() + tw.exilecore.music.Music.VANILLA_MUSIC.size(), sounds.size());
        assertEquals(0, sounds.getAsJsonObject("music.game").getAsJsonArray("sounds").size());
        assertFalse(sounds.has("music.menu"), "主選單的音樂留著");
        final JsonObject stone = sounds.getAsJsonObject("block.stone.hit");
        assertTrue(stone.get("replace").getAsBoolean());
        assertEquals(0, stone.getAsJsonArray("sounds").size(), "換成空的：播的時候什麼都沒有");
        assertTrue(files.keySet().stream().filter(path -> path.endsWith("sounds.json")).allMatch(path -> path.equals("assets/minecraft/sounds.json")
                || path.equals("assets/exilecore/sounds.json")), "原版的靜音＋我們自己的背景音樂");
        // 背景音樂：7 首都有 ogg 與事件（串流）
        final JsonObject music = json("assets/exilecore/sounds.json");
        for (final tw.exilecore.music.Music.Track track : tw.exilecore.music.Music.TRACKS) {
            assertTrue(files.containsKey("assets/exilecore/sounds/music/" + track.id() + ".ogg"), track.id());
            assertTrue(music.getAsJsonObject(track.event()).getAsJsonArray("sounds").get(0).getAsJsonObject().get("stream").getAsBoolean());
        }
        for (final tw.exilecore.music.Music.Mood mood : tw.exilecore.music.Music.Mood.values()) {
            assertEquals(1, tw.exilecore.music.Music.tracks(mood).size(), mood + "：每種情境一首");
        }
        // 長度照 ogg 檔讀（Moon City 約 79 秒、Escape from Micikplay 約 4 分鐘）
        assertEquals(78.8, tw.exilecore.music.Music.tracks(tw.exilecore.music.Music.Mood.MAP).getFirst().seconds(), 0.5);
        assertEquals(240.1, tw.exilecore.music.Music.tracks(tw.exilecore.music.Music.Mood.UBER_BOSS).getFirst().seconds(), 0.5);
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
            assertEquals(UiPack.MARK_SIZE, image.getWidth(), name);
            assertEquals(UiPack.MARK_SIZE, image.getHeight(), name);
        }
        assertEquals(256, UiPack.MARK_SIZE, "2026-10：128 → 256（外框可以畫得更細）");
        // 下面的座標照 128×128 的圖寫，乘上 k 換到現在的解析度
        final int k = UiPack.MARK_SIZE / 128;
        final BufferedImage disc = UiPack.markImage("disc");
        final int middle = disc.getRGB(64 * k, 64 * k) >>> 24;
        assertTrue(middle > 30 && middle < 120, "圓：裡面半透明 " + middle);
        assertTrue((disc.getRGB(64 * k, 2 * k) >>> 24) > 200, "圓：外框不透明");
        assertEquals(0, disc.getRGB(1, 1) >>> 24, "圓：角落透明");
        assertEquals(0xFFFFFF, disc.getRGB(64 * k, 64 * k) & 0xFFFFFF, "白色（著色後才是招式的顏色）");
        assertEquals(0, UiPack.markImage("ring").getRGB(64 * k, 64 * k) >>> 24, "外框圓：中間透明");
        final BufferedImage sector = UiPack.markImage("sector_90");
        assertTrue((sector.getRGB(64 * k, 30 * k) >>> 24) > 30, "扇形往圖的上方張開");
        assertEquals(0, sector.getRGB(64 * k, 100 * k) >>> 24, "下方是空的");
        assertEquals(0, sector.getRGB(15 * k, 64 * k) >>> 24, "左右（90 度的扇形以外）是空的");
        final BufferedImage rect = UiPack.markImage("rect");
        assertTrue((rect.getRGB(2 * k, 64 * k) >>> 24) > 200, "長條：左右兩條邊");
        assertTrue((rect.getRGB(64 * k, 64 * k) >>> 24) < 120, "長條：中間半透明");
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
        assertTrue(modelled >= 22, "寫了 model 的通貨：" + modelled);
        // 反過來：每張圖（通貨，2026-10 起還有技能、輔助寶石的圖示）都進了資源包。現在大部分通貨不寫 model、照代號找圖，
        // 「每張通貨的圖都要有通貨在用」改在 CurrencySkinTest 測
        for (final String id : tw.exilecore.currency.CurrencyArt.sprites().keySet()) {
            assertTrue(files.containsKey("assets/exilecore/items/currency/" + id + ".json"), id + " 的物品定義");
            assertTrue(files.containsKey("assets/exilecore/models/item/currency/" + id + ".json"), id + " 的模型");
            assertTrue(files.containsKey("assets/exilecore/textures/item/currency/" + id + ".png"), id + " 的貼圖");
        }
    }

    /** 2026-10-21：背景音樂拆成另一包：介面那包沒有音樂，音樂那包只有音樂和 pack.mcmeta，拆兩次一模一樣（雜湊不變）。 */
    @Test
    void splitsMusicIntoItsOwnPack() throws IOException {
        final UiPack.Split split = UiPack.splitMusic(pack);
        final Map<String, byte[]> ui = unzip(split.ui().zip());
        assertTrue(ui.containsKey("pack.mcmeta"));
        for (final String path : ui.keySet()) {
            assertTrue(!UiPack.isMusicFile(path), "介面那包不該有 " + path);
        }
        if (split.music() == null) {
            return; // 測試的 classpath 沒有音樂
        }
        final Map<String, byte[]> music = unzip(split.music().zip());
        assertTrue(music.containsKey("pack.mcmeta"));
        assertTrue(music.containsKey("assets/exilecore/sounds.json"));
        for (final String path : music.keySet()) {
            assertTrue(path.equals("pack.mcmeta") || path.equals(UiPack.VANILLA_SOUNDS) || UiPack.isMusicFile(path), "音樂那包不該有 " + path);
        }
        // 2026-10-23：原版背景音樂的靜音在音樂那包（關掉背景音樂的人聽得到原版的）；挖方塊的靜音留在介面那包
        final String musicMutes = new String(music.get(UiPack.VANILLA_SOUNDS), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(musicMutes.contains("\"music.game\""), musicMutes);
        if (ui.containsKey(UiPack.VANILLA_SOUNDS)) {
            final String uiMutes = new String(ui.get(UiPack.VANILLA_SOUNDS), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(!uiMutes.contains("\"music."), "介面那包不該靜音原版的背景音樂");
        }
        assertEquals(files.size() + 1 + (ui.containsKey(UiPack.VANILLA_SOUNDS) ? 1 : 0), ui.size() + music.size());
        assertEquals(split.ui().sha1(), UiPack.splitMusic(pack).ui().sha1());
    }
}
