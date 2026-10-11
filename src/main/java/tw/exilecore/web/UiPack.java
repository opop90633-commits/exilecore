package tw.exilecore.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tw.exilecore.currency.CurrencyArt;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.UniqueArt;
import tw.exilecore.item.UniqueItem;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 介面資源包：插件啟動時用程式畫出來（不用另外準備美術檔），透過內建的網頁伺服器給玩家下載。純 Java，不依賴 Bukkit。
 * <p>
 * 天賦樹的格子（{@value #TREE_CELL}）：一個物品模型由幾層疊起來——星空背景、往上下左右的連線、
 * 節點（小點／核心／基石／起點）、發光、搜尋標記。哪幾層要畫、什麼顏色，由物品的 custom_model_data 決定
 * （見 {@link Cell}），所以同一個物品可以畫出所有格子，顏色也不用每種都準備一張圖。
 * <p>
 * 格子畫成 18×18（剛好蓋住箱子的一格，連格子的框線也蓋掉），一格一格接起來就是一整片星空。
 * 另外有介面小圖示（{@value #UI_ICON}）：技能面板上的滑鼠按鍵標籤。
 * <p>
 * 選取外框（{@value #HIGHLIGHT}）：選單裡「選到的、可以放這裡」的格子外面一圈亮框。框畫在後面，前面照原版的樣子畫原本的物品
 * （原版物品在格子裡用哪個模型：{@code web/vanilla_item_models.txt}，複雜一點的照 {@link #rules} 寫），
 * custom_model_data 的字串 0 是原本的物品（原版名稱）、顏色 0 是框的顏色。
 * <p>
 * 同樣的內容每次產生出來的檔案一模一樣（固定的亂數種子、固定的時間戳），SHA-1 不變，玩家不用重新下載。
 */
public final class UiPack {

    /** 天賦樹格子的物品模型。 */
    public static final String TREE_CELL = "exilecore:tree_cell";
    /** 介面小圖示的物品模型（custom_model_data 的字串 0 選哪一個，見 {@link #ICONS}）。 */
    public static final String UI_ICON = "exilecore:ui_icon";
    /** 選取外框的物品模型（見類別說明）。 */
    public static final String HIGHLIGHT = "exilecore:highlight";
    /**
     * 介面小圖示：滑鼠左鍵、右鍵、潛行＋左鍵、潛行＋右鍵（技能面板的按鍵標籤，順序不能改：照按鍵的編號取）；
     * 往上、下、左、右的箭頭（天賦樹捲動）；選單統一版面的返回、上一頁、下一頁、說明。
     */
    public static final List<String> ICONS = java.util.stream.Stream.concat(
            java.util.stream.Stream.of("key_left", "key_right", "key_sneak_left", "key_sneak_right",
                    "arrow_up", "arrow_down", "arrow_left", "arrow_right", "nav_back", "nav_prev", "nav_next", "help", "community",
                    // 角色面板空著的裝備欄（2026-10）
                    "slot_main", "slot_off", "slot_head", "slot_chest", "slot_legs", "slot_feet", "slot_ring", "slot_amulet", "slot_belt",
                    // 2026-10：護符欄、技能面板的空格、輔助孔、空的角色格（_locked = 還沒開：暗一點、右下角一個鎖）
                    "slot_charm", "slot_charm_locked", "slot_skill", "slot_support", "slot_support_locked", "slot_character"),
            // 倉庫地圖頁的階級頁籤（2026-10）：格子上直接寫 T1–T16
            java.util.stream.IntStream.rangeClosed(1, 16).mapToObj(tier -> "map_t" + tier)).toList();
    /** Minecraft 26.2 的資源包格式。 */
    static final int PACK_FORMAT = 88;
    /** 背景有幾種（依格子座標挑，捲動時星星跟著樹走）。 */
    public static final int BACKGROUNDS = 4;

    /**
     * 招式預警平貼在地上的範圍（2026-10）：物品模型 exilecore:mark/圖名（disc 圓、rect 長條、sector_度數 扇形），
     * 一片水平的平面，白色的圖用 custom_model_data 的顏色 0 著色。見 {@code skill/DangerZones}。
     */
    public static final String MARK_MODEL = "exilecore:mark/";
    /** 扇形每幾度一張圖。 */
    public static final int MARK_SECTOR_STEP = 5;
    /** 地上範圍的貼圖解析度。 */
    static final int MARK_SIZE = 256; // 2026-10：128 → 256（外框可以畫得更細）
    /** 範圍裡面的底色有多不透明（0–255）、外框有多不透明。 */
    static final int MARK_FILL_ALPHA = 72;
    static final int MARK_RIM_ALPHA = 235;

    /** 貼圖解析度（16 個模型單位畫成幾個像素）。 */
    private static final int SIZE = 32;
    private static final String TEX = "exilecore:item/ui/tree/";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * custom_model_data 的格式（字串、旗標、顏色各自的索引）。
     * <ul>
     *   <li>字串 0：背景（"0"～"3"）；1：節點（small／notable／keystone／start／socket 空的珠寶孔／jewel 放了珠寶的珠寶孔，
     *       空字串＝沒有）；2："glow" 發光；3："ring" 搜尋標記</li>
     *   <li>旗標 0～3：往上、右、下、左有沒有連線</li>
     *   <li>顏色 0～3：四條連線的顏色；4：節點；5：發光；6：搜尋標記</li>
     * </ul>
     */
    public static final class Cell {
        public static final int STRING_BACKGROUND = 0;
        public static final int STRING_NODE = 1;
        public static final int STRING_GLOW = 2;
        public static final int STRING_RING = 3;
        public static final int COLOR_NODE = 4;
        public static final int COLOR_GLOW = 5;
        public static final int COLOR_RING = 6;
        /** 上、右、下、左。 */
        public static final List<String> EDGES = List.of("n", "e", "s", "w");
        public static final List<String> NODES = List.of("small", "notable", "keystone", "start", "socket", "jewel");

        private Cell() {
        }
    }

    /** 產生好的資源包。 */
    public record Built(byte[] zip, String sha1, int files) {
    }

    /**
     * 一件傳奇的專屬外觀要掛在哪個原版物品定義上（{@code assets/minecraft/items/<vanillaItem>.json}）、用哪種畫法。
     * 物品定義改成「custom_model_data 字串 0 是 unique:代號 就畫我們的圖，不然照原版」，原版的部分照抄客戶端的定義
     * （{@code web/vanilla_items/}），所以其他同種物品不受影響。
     */
    public record UniqueSkin(String id, String vanillaItem, Kind kind, boolean chase) {
        /** 不是追逐級的（舊的呼叫法）。 */
        public UniqueSkin(final String id, final String vanillaItem, final Kind kind) {
            this(id, vanillaItem, kind, false);
        }

        public enum Kind {
            /** 拿在手上斜握的武器（劍、錘、法杖…）。 */
            HANDHELD,
            /** 平的（飾品、防具）。 */
            GENERATED,
            /** 弓：拉弓三張。 */
            BOW,
            /** 弩：上弦三張＋上好的。 */
            CROSSBOW,
            /** 矛：介面裡一張、拿在手上一張（32×32）。 */
            SPEAR
        }
    }

    /**
     * 一種通貨的貼圖要掛在哪個原版物品定義上（跟傳奇一樣：{@code assets/minecraft/items/<vanillaItem>.json}，
     * 「custom_model_data 字串 0 是 currency:圖的代號 就畫我們的圖，不然照原版」）。同一個原版物品上傳奇、通貨、空藥劑欄的挑選合在同一份定義裡。
     *
     * @param artId       圖的代號（currency_art.txt）
     * @param vanillaItem 通貨實際是哪個原版物品（materials 小寫，例如 glowstone_dust）
     */
    public record CurrencySkin(String artId, String vanillaItem) {
    }

    private UiPack() {
    }

    /** 有圖的通貨：照通貨的材質決定掛在哪個原版物品上（同一種圖掛在同一個物品上只算一次）。 */
    public static List<CurrencySkin> currencySkins(final CurrencyRepository repository) {
        final java.util.Set<CurrencySkin> skins = new java.util.LinkedHashSet<>();
        for (final CurrencyType type : repository.all()) {
            final String art = CurrencyArt.artOf(type);
            if (art == null || type.material() == null || type.material().isBlank()) {
                continue;
            }
            String item = type.material().toLowerCase(Locale.ROOT);
            if (item.startsWith("minecraft:")) {
                item = item.substring("minecraft:".length());
            }
            skins.add(new CurrencySkin(art, item));
        }
        final List<CurrencySkin> sorted = new java.util.ArrayList<>(skins);
        sorted.sort(java.util.Comparator.comparing(CurrencySkin::vanillaItem).thenComparing(CurrencySkin::artId));
        return sorted;
    }

    /** 有專屬外觀的傳奇：照基底的材質決定掛在哪個原版物品上。 */
    public static List<UniqueSkin> skins(final ItemRepository repository) {
        final List<UniqueSkin> skins = new java.util.ArrayList<>();
        for (final UniqueItem unique : repository.uniques()) {
            if (!UniqueArt.has(unique.id())) {
                continue;
            }
            final ItemBase base = repository.base(unique.base());
            if (base == null || base.material() == null) {
                continue;
            }
            final String vanillaItem = base.model() != null && base.model().startsWith("minecraft:")
                    ? base.model().substring("minecraft:".length()) : base.material().toLowerCase(Locale.ROOT);
            final UniqueSkin.Kind kind = vanillaItem.equals("bow") ? UniqueSkin.Kind.BOW
                    : vanillaItem.equals("crossbow") ? UniqueSkin.Kind.CROSSBOW
                    : vanillaItem.endsWith("_spear") ? UniqueSkin.Kind.SPEAR
                    : base.isWeapon() ? UniqueSkin.Kind.HANDHELD : UniqueSkin.Kind.GENERATED;
            skins.add(new UniqueSkin(unique.id(), vanillaItem, kind, unique.chase()));
        }
        skins.sort(java.util.Comparator.comparing(UniqueSkin::id));
        return skins;
    }

    public static Built build() throws IOException {
        return build(List.of());
    }

    public static Built build(final List<UniqueSkin> skins) throws IOException {
        return build(skins, List.of());
    }

    /**
     * @param skins         有專屬外觀的傳奇
     * @param currencySkins 有圖的通貨掛在哪個原版物品上（沒給＝只有貼圖與模型、沒有物品會用到）
     */
    public static Built build(final List<UniqueSkin> skins, final List<CurrencySkin> currencySkins) throws IOException {
        return build(skins, currencySkins, java.util.Set.of());
    }

    /**
     * 裝備會用到的原版物品（基底的 material，或基底寫的 minecraft: 模型）：魔法、稀有裝備在格子裡畫淺淺的同色漸層外框（{@link #RARITY_KEYS}）。
     */
    public static java.util.Set<String> gearItems(final tw.exilecore.item.ItemRepository repository) {
        final java.util.Set<String> items = new java.util.TreeSet<>();
        for (final tw.exilecore.item.ItemBase base : repository.bases()) {
            final String model = base.model();
            if (model != null && model.startsWith("minecraft:")) {
                items.add(model.substring("minecraft:".length()));
            } else if (base.material() != null) {
                items.add(base.material().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return items;
    }

    /** 魔法、稀有裝備的外框：custom_model_data 字串 0 寫這個（傳奇用 unique:代號，不在這裡）。 */
    public static final Map<String, Integer> RARITY_KEYS = Map.of("rarity:magic", 0x8888FF, "rarity:rare", 0xFFFF77, "rarity:unique", 0xAF6025);
    /** 傳奇的外框顏色（跟傳奇名字的顏色一樣）。 */
    static final int UNIQUE_FRAME = 0xAF6025;

    public static Built build(final List<UniqueSkin> skins, final List<CurrencySkin> currencySkins, final java.util.Set<String> gearItems)
            throws IOException {
        final Map<String, byte[]> files = new TreeMap<>();
        // 原版物品定義的挑選：同一個原版物品（例如玻璃瓶）上的傳奇、通貨、空藥劑欄合在一份，最後一起寫出去，不會互相蓋掉
        final Map<String, JsonArray> cases = new TreeMap<>();
        files.put("pack.mcmeta", json(packMeta()));
        files.put("pack.png", png(icon()));

        // 共用的父模型：一片正面朝前的平面，GUI 放大成 18×18；四層的深度不同，後面的層蓋在前面的上面
        files.put("assets/exilecore/models/item/ui/layer_bg.json", json(layerModel(8.0, false)));
        files.put("assets/exilecore/models/item/ui/layer_1.json", json(layerModel(8.02, true)));
        files.put("assets/exilecore/models/item/ui/layer_2.json", json(layerModel(8.04, true)));
        files.put("assets/exilecore/models/item/ui/layer_3.json", json(layerModel(8.06, true)));
        files.put("assets/exilecore/models/item/ui/layer_4.json", json(layerModel(8.08, true)));

        for (int i = 0; i < BACKGROUNDS; i++) {
            texture(files, "bg_" + i, background(i), "layer_bg");
        }
        for (final String edge : Cell.EDGES) {
            texture(files, "edge_" + edge, edge(edge), "layer_1");
        }
        texture(files, "glow", glow(), "layer_2");
        texture(files, "node_small", nodeSmall(), "layer_3");
        texture(files, "node_notable", nodeNotable(), "layer_3");
        texture(files, "node_keystone", nodeKeystone(), "layer_3");
        texture(files, "node_start", nodeStart(), "layer_3");
        texture(files, "node_socket", nodeSocket(), "layer_3");
        texture(files, "node_jewel", nodeJewel(), "layer_3");
        texture(files, "ring", ring(), "layer_4");

        files.put("assets/exilecore/items/tree_cell.json", json(treeCellDefinition()));

        // 介面小圖示：一般的平面物品（item/generated），16×16 的格子裡
        for (final String icon : ICONS) {
            if (icon.equals("community")) {
                // 2026-10：選單的社群（Discord）按鈕。web/menu/community.png 有圖就用那張（例如自己放的官方 logo，16×16），
                // 沒有就畫一個通用的對話框
                byte[] custom = null;
                try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/menu/community.png")) {
                    custom = in == null ? null : in.readAllBytes();
                }
                files.put("assets/exilecore/textures/item/ui/icon/community.png", custom != null ? custom : png(communityIcon()));
                final JsonObject model = new JsonObject();
                model.addProperty("parent", "minecraft:item/generated");
                final JsonObject textures = new JsonObject();
                textures.addProperty("layer0", "exilecore:item/ui/icon/community");
                model.add("textures", textures);
                files.put("assets/exilecore/models/item/ui/icon/community.json", json(model));
                continue;
            }
            files.put("assets/exilecore/textures/item/ui/icon/" + icon + ".png", png(icon.startsWith("arrow_") ? arrowIcon(icon)
                    : icon.startsWith("map_t") ? mapTierIcon(Integer.parseInt(icon.substring(5)))
                    : icon.startsWith("slot_") ? slotIcon(icon)
                    : icon.startsWith("nav_") || icon.equals("help") ? navIcon(icon) : keyIcon(icon)));
            final JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:item/generated");
            final JsonObject textures = new JsonObject();
            textures.addProperty("layer0", "exilecore:item/ui/icon/" + icon);
            model.add("textures", textures);
            files.put("assets/exilecore/models/item/ui/icon/" + icon + ".json", json(model));
        }
        files.put("assets/exilecore/items/ui_icon.json", json(iconDefinition()));

        // 選取外框：一片放在最後面的平面（著色），前面照原版的樣子畫原本的物品
        files.put("assets/exilecore/models/item/ui/layer_frame.json", json(layerModel(0.0, true)));
        files.put("assets/exilecore/textures/item/ui/highlight.png", png(highlightFrame()));
        final JsonObject frame = new JsonObject();
        frame.addProperty("parent", "exilecore:item/ui/layer_frame");
        final JsonObject frameTextures = new JsonObject();
        frameTextures.addProperty("t", "exilecore:item/ui/highlight");
        frame.add("textures", frameTextures);
        files.put("assets/exilecore/models/item/ui/highlight.json", json(frame));
        files.put("assets/exilecore/items/highlight.json", json(highlightDefinition(skins, currencySkins)));

        // 空的藥劑欄的空瓶提示：原版空玻璃瓶的定義加上「custom_model_data 字串 0 是 life／mana 就畫紅色／藍色的瓶子輪廓」
        for (final String kind : FLASK_SLOTS.keySet()) {
            files.put("assets/exilecore/textures/item/ui/flask_slot_" + kind + ".png", png(flaskSlot(FLASK_SLOTS.get(kind))));
            final JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:item/generated");
            final JsonObject textures = new JsonObject();
            textures.addProperty("layer0", "exilecore:item/ui/flask_slot_" + kind);
            model.add("textures", textures);
            files.put("assets/exilecore/models/item/ui/flask_slot_" + kind + ".json", json(model));
            cases.computeIfAbsent("glass_bottle", key -> new JsonArray()).add(selectCase(kind, plain("exilecore:item/ui/flask_slot_" + kind)));
        }

        // 對話框標題旁的「自訂畫面」按鈕：客戶端一定會畫（Minecraft 標示「這是伺服器提供的畫面」的安全提示，拿不掉），
        // 換成小小的、不搶眼的「i」；滑過去、按下去還是原版的說明
        for (final Map.Entry<String, Integer> sprite : DIALOG_NOTICE.entrySet()) {
            files.put("assets/minecraft/textures/gui/sprites/dialog/" + sprite.getKey() + ".png", png(dialogNotice(sprite.getValue())));
        }

        // 按住左鍵連續施放時，客戶端以為在挖準星指著的方塊（skill/HoldToCast）：把「挖方塊」的敲擊聲都換成沒有聲音
        final List<String> muted = mutedSounds();
        if (!muted.isEmpty()) {
            files.put("assets/minecraft/sounds.json", json(mutedSoundsJson(muted)));
        }
        bossBars(files); // 2026-10：Boss 血條的樣式與換階段的刻度
        menuFont(files);
        lootFont(files);
        rarityFrameModel(files);
        uniques(files, skins, cases);
        transmogSkins(files); // 2026-10：裝備幻化的美術（web/skins/）
        pack3d(files); // 2026-10：3D 時裝（web/pack3d.zip，tools/gen_models3d.py 產生）
        currencies(files, currencySkins, cases);
        marks(files);
        HudFont.build(files, HudFont.texts()); // 2026-10：快捷欄兩側的光環、召喚物
        affixChips(files); // 2026-10：詞綴標籤的小方塊
        final java.util.Set<String> framed = rarityFrames(files, gearItems, cases);
        for (final Map.Entry<String, JsonArray> entry : cases.entrySet()) {
            final JsonObject root = vanillaOverride(entry.getKey(), entry.getValue());
            if (framed.contains(entry.getKey()) || UNIQUE_ITEMS.contains(entry.getKey())) {
                // 外框比格子大一點（18×18 蓋住格子的框線）
                root.addProperty("oversized_in_gui", true);
            }
            root.add("model", lockOverlay(root.getAsJsonObject("model")));
            files.put("assets/minecraft/items/" + entry.getKey() + ".json", json(root));
        }
        lockModel(files);
        return zip(files);
    }

    // ---- 選單的「圖示＋中文」背景（2026-10，先只有主選單） ----

    /** 選單標題用的字型：{@value #MENU_SHIFT_START} 往左退 8、{@value #MENU_MAIN} 主選單的背景、{@value #MENU_SHIFT_BACK} 退回標題的位置。 */
    public static final String MENU_FONT = "exilecore:menu";
    public static final String MENU_SHIFT_START = "\uE000";
    /**
     * 主選單的背景：左半、退 1 格（字距）、右半。一個字最大只能 256×256（字型的貼圖集），
     * 2 倍解析度的整張圖 352 寬放不進去，所以切成兩個 176×252 的字（同一張圖，chars 一列兩個）。
     */
    public static final String MENU_MAIN = "\uE010\uE002\uE011";
    public static final String MENU_SHIFT_BACK = "\uE001";
    /** 贊助選單的背景（2026-10，web/menu/sponsor_menu.png）：跟主選單一樣的畫法。 */
    public static final String MENU_SPONSOR = "\uE012\uE002\uE013";

    /**
     * 主選單的背景圖（{@code web/menu/main_menu.png}，tools/menu_labels.py 產生）當成字畫在箱子介面上：
     * 圖示那幾排照常，名稱那幾排蓋成一片底色、寫上中文。標題從 x = 8 開始寫，先退 8 格到介面左上角，
     * 左半（88＋字距 1）、退 1、右半（88＋字距 1）畫完正好在 x = 169，再退 161 格回到 x = 8 寫「選單」。
     * ascent 13 讓圖的上緣對齊介面上緣。
     */
    /**
     * 「最近撿到」卡片的字型（2026-10，{@link LootCards}）：框（白色，照文字顏色著色）、位移、物品圖示（16×16 的像素圖縮成 8 像素高）。
     */
    static void lootFont(final Map<String, byte[]> files) throws IOException {
        final JsonArray providers = new JsonArray();
        final JsonObject space = new JsonObject();
        space.addProperty("type", "space");
        final JsonObject advances = new JsonObject();
        for (int bit = 0; bit < LootCards.SHIFT_BITS; bit++) {
            advances.addProperty(String.valueOf((char) (LootCards.NEG + bit)), -(1 << bit));
            advances.addProperty(String.valueOf((char) (LootCards.POS + bit)), 1 << bit);
        }
        space.add("advances", advances);
        providers.add(space);
        // 框：9 像素高；上下兩條實線，中間淡淡的底色（著色後就是稀有度的顏色）
        final int height = 9;
        final BufferedImage left = new BufferedImage(2, height, BufferedImage.TYPE_INT_ARGB);
        final BufferedImage right = new BufferedImage(2, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            final boolean edge = y == 0 || y == height - 1;
            left.setRGB(0, y, edge ? 0 : 0xFFFFFFFF);
            left.setRGB(1, y, edge ? 0xFFFFFFFF : 0x50FFFFFF);
            right.setRGB(1, y, edge ? 0 : 0xFFFFFFFF);
            right.setRGB(0, y, edge ? 0xFFFFFFFF : 0x50FFFFFF);
        }
        lootGlyph(files, providers, "loot_left", left, LootCards.LEFT, height, 8);
        lootGlyph(files, providers, "loot_right", right, LootCards.RIGHT, height, 8);
        for (int bit = 0; bit < LootCards.MID_BITS; bit++) {
            final int width = 1 << bit;
            final BufferedImage mid = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    mid.setRGB(x, y, y == 0 || y == height - 1 ? 0xFFFFFFFF : 0x50FFFFFF);
                }
            }
            lootGlyph(files, providers, "loot_mid_" + width, mid, (char) (LootCards.MID + bit), height, 8);
        }
        // 圖示：一張 16 欄的大圖，每格 16×16
        final List<String> icons = LootCards.icons();
        final int columns = 16;
        final int rows = (icons.size() + columns - 1) / columns;
        final BufferedImage atlas = new BufferedImage(columns * 16, rows * 16, BufferedImage.TYPE_INT_ARGB);
        final Map<String, UniqueArt.Sprite> currency = tw.exilecore.currency.CurrencyArt.sprites();
        final Map<String, UniqueArt.Sprite> uniques = UniqueArt.sprites();
        final JsonArray chars = new JsonArray();
        for (int row = 0; row < rows; row++) {
            final StringBuilder line = new StringBuilder();
            for (int column = 0; column < columns; column++) {
                final int index = row * columns + column;
                line.append(index < icons.size() ? (char) (LootCards.ICON_START + index) : '\u0000');
                if (index >= icons.size()) {
                    continue;
                }
                final String key = icons.get(index);
                final BufferedImage image = key.startsWith("c:") ? spriteImage(currency.get(key.substring(2)))
                        : key.startsWith("u:") ? spriteImage(uniques.get(key.substring(2)))
                        : key.equals(LootCards.JEWEL) ? genericJewel() : genericItem();
                final Graphics2D g = atlas.createGraphics();
                g.drawImage(image, column * 16, row * 16, 16, 16, null);
                g.dispose();
            }
            chars.add(line.toString());
        }
        files.put("assets/exilecore/textures/font/loot_icons.png", png(atlas));
        final JsonObject bitmap = new JsonObject();
        bitmap.addProperty("type", "bitmap");
        bitmap.addProperty("file", "exilecore:font/loot_icons.png");
        bitmap.addProperty("ascent", 7);
        bitmap.addProperty("height", 8);
        bitmap.add("chars", chars);
        providers.add(bitmap);
        final JsonObject root = new JsonObject();
        root.add("providers", providers);
        files.put("assets/exilecore/font/loot.json", json(root));
    }

    private static void lootGlyph(final Map<String, byte[]> files, final JsonArray providers, final String name, final BufferedImage image,
                                  final char c, final int height, final int ascent) throws IOException {
        files.put("assets/exilecore/textures/font/" + name + ".png", png(image));
        final JsonObject bitmap = new JsonObject();
        bitmap.addProperty("type", "bitmap");
        bitmap.addProperty("file", "exilecore:font/" + name + ".png");
        bitmap.addProperty("ascent", ascent);
        bitmap.addProperty("height", height);
        final JsonArray chars = new JsonArray();
        chars.add(String.valueOf(c));
        bitmap.add("chars", chars);
        providers.add(bitmap);
    }

    /** 沒有專屬圖的東西：一個閃亮的菱形。 */
    private static BufferedImage genericItem() {
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                final int d = Math.abs(x - 8) + Math.abs(y - 8);
                if (d <= 6) {
                    image.setRGB(x, y, d >= 5 ? 0xFF7a5a20 : d <= 2 ? 0xFFFFF4C0 : 0xFFE8B84A);
                }
            }
        }
        return image;
    }

    /** 珠寶：六角形的寶石。 */
    private static BufferedImage genericJewel() {
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 14; y++) {
            for (int x = 2; x < 14; x++) {
                final int inset = Math.max(0, Math.abs(y - 8) - 3);
                if (x < 2 + inset || x > 13 - inset) {
                    continue;
                }
                final boolean edge = x == 2 + inset || x == 13 - inset || y == 2 || y == 13;
                image.setRGB(x, y, edge ? 0xFF3A1A5A : x + y < 14 ? 0xFFE0C0FF : 0xFFA060E0);
            }
        }
        return image;
    }

    static void menuFont(final Map<String, byte[]> files) throws IOException {
        final byte[] image;
        try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/menu/main_menu.png")) {
            if (in == null) {
                return; // 沒有圖：主選單照舊（MainMenu 也會照舊）
            }
            image = in.readAllBytes();
        }
        files.put("assets/exilecore/textures/font/main_menu.png", image);
        final JsonObject space = new JsonObject();
        space.addProperty("type", "space");
        final JsonObject advances = new JsonObject();
        advances.addProperty(MENU_SHIFT_START, -8);
        advances.addProperty(MENU_SHIFT_BACK, -161);
        advances.addProperty("\uE002", -1); // 左右兩半中間的字距
        space.add("advances", advances);
        final JsonObject bitmap = new JsonObject();
        bitmap.addProperty("type", "bitmap");
        bitmap.addProperty("file", "exilecore:font/main_menu.png");
        bitmap.addProperty("ascent", 13);
        bitmap.addProperty("height", 126);
        final JsonArray chars = new JsonArray();
        chars.add("\uE010\uE011"); // 一列兩個：左半、右半（各 88 GUI 像素寬＋字距 1）
        bitmap.add("chars", chars);
        final JsonArray providers = new JsonArray();
        providers.add(space);
        providers.add(bitmap);
        // 2026-10：贊助選單的背景（沒有圖就跳過，SponsorMenus 也會照舊）
        try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/menu/sponsor_menu.png")) {
            if (in != null) {
                files.put("assets/exilecore/textures/font/sponsor_menu.png", in.readAllBytes());
                final JsonObject sponsor = new JsonObject();
                sponsor.addProperty("type", "bitmap");
                sponsor.addProperty("file", "exilecore:font/sponsor_menu.png");
                sponsor.addProperty("ascent", 13);
                sponsor.addProperty("height", 126);
                final JsonArray sponsorChars = new JsonArray();
                sponsorChars.add("\uE012\uE013");
                sponsor.add("chars", sponsorChars);
                providers.add(sponsor);
            }
        }
        final JsonObject root = new JsonObject();
        root.add("providers", providers);
        files.put("assets/exilecore/font/menu.json", json(root));
    }

    // ---- 招式預警：平貼在地上的範圍 ----

    /** 扇形的圖名：照最接近的 {@value #MARK_SECTOR_STEP} 度挑（至少一格）；差不多一整圈的用圓。 */
    public static String markSector(final double angle) {
        final double clamped = Math.max(MARK_SECTOR_STEP, Math.min(360, angle));
        final int degrees = (int) Math.round(clamped / MARK_SECTOR_STEP) * MARK_SECTOR_STEP;
        return degrees >= 360 ? "disc" : "sector_" + degrees;
    }

    /** 所有地上範圍的圖名：圓、只有外框的圓（大範圍的邊界）、長條、每 5 度一張扇形（5～355）。 */
    public static List<String> markNames() {
        final List<String> bases = new java.util.ArrayList<>(List.of("disc", "ring"));
        for (int degrees = MARK_SECTOR_STEP; degrees < 360; degrees += MARK_SECTOR_STEP) {
            bases.add("sector_" + degrees);
        }
        final List<String> names = new java.util.ArrayList<>(bases);
        names.add("rect");
        // 2026-10：圖是照範圍放大的，大範圍的外框會跟著變粗：照大小分三種外框粗細（_b0 小、_b1 中、_b2 大範圍）
        for (final String base : bases) {
            for (int bucket = 0; bucket < MARK_STROKES.length; bucket++) {
                names.add(base + "_b" + bucket);
            }
        }
        return names;
    }

    /**
     * 地上範圍：每張圖一個貼圖、一個模型（共用的水平平面 {@code exilecore:item/mark/plane}）、一個物品定義
     * （{@code assets/exilecore/items/mark/圖名.json}，custom_model_data 的顏色 0 著色）。
     */
    static void marks(final Map<String, byte[]> files) throws IOException {
        files.put("assets/exilecore/models/item/mark/plane.json", json(markPlane()));
        for (final String name : markNames()) {
            files.put("assets/exilecore/textures/item/mark/" + name + ".png", png(markImage(name)));
            final JsonObject model = new JsonObject();
            model.addProperty("parent", "exilecore:item/mark/plane");
            final JsonObject textures = new JsonObject();
            textures.addProperty("t", "exilecore:item/mark/" + name);
            model.add("textures", textures);
            files.put("assets/exilecore/models/item/mark/" + name + ".json", json(model));
            final JsonObject tint = new JsonObject();
            tint.addProperty("type", "minecraft:custom_model_data");
            tint.addProperty("index", 0);
            tint.addProperty("default", 0xFF3B30);
            final JsonArray tints = new JsonArray();
            tints.add(tint);
            final JsonObject definition = new JsonObject();
            definition.addProperty("type", "minecraft:model");
            definition.addProperty("model", "exilecore:item/mark/" + name);
            definition.add("tints", tints);
            final JsonObject root = new JsonObject();
            root.add("model", definition);
            files.put("assets/exilecore/items/mark/" + name + ".json", json(root));
        }
        // 光環腳下的法陣與符文（2026-10）：主法陣用同一片平面，符文用偏到 +X 那一側的小平面（實體繞 Y 軸轉就沿圓弧繞圈）
        files.put("assets/exilecore/models/item/mark/rune_plane.json", json(AuraSigils.runePlane()));
        for (final String name : AuraSigils.names()) {
            files.put("assets/exilecore/textures/item/mark/" + name + ".png", png(AuraSigils.image(name)));
            final JsonObject model = new JsonObject();
            model.addProperty("parent", name.startsWith("rune_") ? "exilecore:item/mark/rune_plane" : "exilecore:item/mark/plane");
            final JsonObject textures = new JsonObject();
            textures.addProperty("t", "exilecore:item/mark/" + name);
            model.add("textures", textures);
            files.put("assets/exilecore/models/item/mark/" + name + ".json", json(model));
            final JsonObject tint = new JsonObject();
            tint.addProperty("type", "minecraft:custom_model_data");
            tint.addProperty("index", 0);
            tint.addProperty("default", 0xFFFFFF);
            final JsonArray tints = new JsonArray();
            tints.add(tint);
            final JsonObject definition = new JsonObject();
            definition.addProperty("type", "minecraft:model");
            definition.addProperty("model", "exilecore:item/mark/" + name);
            definition.add("tints", tints);
            final JsonObject root = new JsonObject();
            root.add("model", definition);
            files.put("assets/exilecore/items/mark/" + name + ".json", json(root));
        }
    }

    /**
     * 一片水平的平面（只有朝上那一面），在模型的正中間（y = 8）：物品的畫法會把模型往回移半格，所以平面剛好在實體的位置上。
     * 不吃光影（地上暗的地方一樣看得清楚；實體另外設成全亮）。
     */
    static JsonObject markPlane() {
        final JsonObject root = new JsonObject();
        root.addProperty("ambientocclusion", false);
        final JsonObject textures = new JsonObject();
        textures.addProperty("particle", "#t");
        root.add("textures", textures);
        final JsonObject face = new JsonObject();
        face.add("uv", numbers(0, 0, 16, 16));
        face.addProperty("texture", "#t");
        face.addProperty("tintindex", 0);
        final JsonObject faces = new JsonObject();
        faces.add("up", face);
        final JsonObject element = new JsonObject();
        element.add("from", numbers(0, 8, 0));
        element.add("to", numbers(16, 8, 16));
        element.addProperty("shade", false);
        element.add("faces", faces);
        final JsonArray elements = new JsonArray();
        elements.add(element);
        root.add("elements", elements);
        return root;
    }

    /**
     * 地上範圍的圖（白色，著色後是招式的顏色）：半透明的底＋不透明的外框。
     * 圓：整張圖的內切圓（ring 只有外框）。長條：左右兩條邊（長度方向是圖的上下）。扇形：頂點在正中間、往圖的上方張開，
     * 半徑是半張圖（所以放大成 2 × 長度）。
     */
    /** 外框粗細（佔整張圖的比例）：直徑 ≤ 10 格、≤ 22 格、更大（地上看起來都大約 0.3 格粗）。 */
    public static final float[] MARK_STROKES = {0.018f, 0.009f, 0.005f}; // 2026-10：再細一點（地上大約 0.15～0.3 格）
    /** 各粗細適用的最大直徑（格）。 */
    public static final double[] MARK_STROKE_SIZES = {10, 22};

    /** 這個直徑（格）用第幾種外框粗細。 */
    public static int markBucket(final double diameter) {
        for (int i = 0; i < MARK_STROKE_SIZES.length; i++) {
            if (diameter <= MARK_STROKE_SIZES[i]) {
                return i;
            }
        }
        return MARK_STROKES.length - 1;
    }

    static BufferedImage markImage(final String full) {
        final int cut = full.lastIndexOf("_b");
        final String name = cut > 0 ? full.substring(0, cut) : full;
        final float strokeRatio = cut > 0 ? MARK_STROKES[Integer.parseInt(full.substring(cut + 2))] : 0.025f;
        final BufferedImage image = new BufferedImage(MARK_SIZE, MARK_SIZE, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = smooth(image);
        final Color fill = new Color(255, 255, 255, MARK_FILL_ALPHA);
        final Color rim = new Color(255, 255, 255, MARK_RIM_ALPHA);
        final double c = MARK_SIZE / 2.0;
        final float stroke = Math.max(1f, MARK_SIZE * strokeRatio); // 2026-10：外框照範圍大小分粗細（不然大範圍的框很粗）
        if (name.equals("rect")) {
            final int border = Math.max(2, Math.round(MARK_SIZE * 0.06f));
            g.setPaint(fill);
            g.fillRect(border, 0, MARK_SIZE - border * 2, MARK_SIZE);
            g.setPaint(rim);
            g.fillRect(0, 0, border, MARK_SIZE);
            g.fillRect(MARK_SIZE - border, 0, border, MARK_SIZE);
        } else if (name.equals("disc") || name.equals("ring")) {
            final double r = c - 0.5;
            if (name.equals("disc")) {
                g.setPaint(fill);
                g.fill(new Ellipse2D.Double(c - r, c - r, r * 2, r * 2));
            }
            final double inner = r - stroke / 2.0;
            g.setPaint(rim);
            g.setStroke(new BasicStroke(stroke));
            g.draw(new Ellipse2D.Double(c - inner, c - inner, inner * 2, inner * 2));
        } else {
            final double degrees = Double.parseDouble(name.substring("sector_".length()));
            final double r = c - 0.5;
            // Java2D 的角度：0 度是右邊（3 點鐘）、逆時針為正；90 度是上方
            g.setPaint(fill);
            g.fill(new Arc2D.Double(c - r, c - r, r * 2, r * 2, 90 - degrees / 2, degrees, Arc2D.PIE));
            final double inner = r - stroke / 2.0;
            g.setPaint(rim);
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Double(c - inner, c - inner, inner * 2, inner * 2, 90 - degrees / 2, degrees, Arc2D.PIE));
        }
        g.dispose();
        return image;
    }

    // ---- 通貨的圖示 ----

    /**
     * 每一種有像素圖的通貨：貼圖、模型（一般的平面物品）與物品定義 assets/exilecore/items/currency/代號.json
     * （給 item_model 用：currencies.yml 的 model: exilecore:currency/代號）。
     * 物品上實際用的是 custom_model_data 的字串 {@code currency:代號}（{@link CurrencyArt#modelKey}）：每個 {@link CurrencySkin}
     * 在它的原版物品定義裡加一個挑選，所以不用開 resource-pack.enabled、沒套資源包的玩家也看得到原本的物品。
     */
    static void currencies(final Map<String, byte[]> files, final List<CurrencySkin> skins, final Map<String, JsonArray> cases) throws IOException {
        final Map<String, UniqueArt.Sprite> sprites = CurrencyArt.sprites();
        for (final Map.Entry<String, UniqueArt.Sprite> entry : sprites.entrySet()) {
            final String id = entry.getKey();
            files.put("assets/exilecore/textures/item/currency/" + id + ".png", png(spriteImage(entry.getValue())));
            final JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:item/generated");
            final JsonObject textures = new JsonObject();
            textures.addProperty("layer0", "exilecore:item/currency/" + id);
            model.add("textures", textures);
            files.put("assets/exilecore/models/item/currency/" + id + ".json", json(model));
            final JsonObject definition = new JsonObject();
            definition.add("model", plain("exilecore:item/currency/" + id));
            files.put("assets/exilecore/items/currency/" + id + ".json", json(definition));
        }
        for (final CurrencySkin skin : skins) {
            if (!sprites.containsKey(skin.artId())) {
                continue;
            }
            cases.computeIfAbsent(skin.vanillaItem(), key -> new JsonArray())
                    .add(selectCase(CurrencyArt.modelKey(skin.artId()), plain("exilecore:item/currency/" + skin.artId())));
        }
    }

    // ---- 傳奇的專屬外觀 ----

    private static final String UNIQUE_TEX = "exilecore:item/unique/";

    /**
     * 每件傳奇：貼圖、模型；它掛的原版物品的挑選加進 cases（寫成改寫的物品定義：照 custom_model_data 選圖，其他照原版——
     * 由 {@link #build} 把所有來源的挑選合起來一起寫）。
     */
    /** 這次產生時掛了傳奇專屬外觀的原版物品（外框比格子大，要 oversized_in_gui）。 */
    private static final java.util.Set<String> UNIQUE_ITEMS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void uniques(final Map<String, byte[]> files, final List<UniqueSkin> skins, final Map<String, JsonArray> cases) throws IOException {
        final Map<String, UniqueArt.Sprite> sprites = UniqueArt.sprites();
        for (final UniqueSkin skin : skins) {
            final UniqueArt.Sprite sprite = sprites.get(skin.id());
            if (sprite == null) {
                continue;
            }
            final JsonObject model = switch (skin.kind()) {
                case HANDHELD -> {
                    uniqueModel(files, skin.id(), sprite, "minecraft:item/handheld");
                    yield plain(UNIQUE_TEX + skin.id());
                }
                case GENERATED -> {
                    uniqueModel(files, skin.id(), sprite, "minecraft:item/generated");
                    yield plain(UNIQUE_TEX + skin.id());
                }
                case BOW -> {
                    uniqueModel(files, skin.id(), sprite, "minecraft:item/bow");
                    for (int i = 0; i < 3; i++) {
                        uniqueModel(files, skin.id() + "_pulling_" + i, sprites.getOrDefault(skin.id() + "_pulling_" + i, sprite), "minecraft:item/bow");
                    }
                    yield bowModel(skin.id());
                }
                case CROSSBOW -> {
                    uniqueModel(files, skin.id(), sprite, "minecraft:item/crossbow");
                    for (int i = 0; i < 3; i++) {
                        uniqueModel(files, skin.id() + "_pulling_" + i, sprites.getOrDefault(skin.id() + "_pulling_" + i, sprite), "minecraft:item/crossbow");
                    }
                    uniqueModel(files, skin.id() + "_arrow", sprites.getOrDefault(skin.id() + "_arrow", sprite), "minecraft:item/crossbow");
                    yield crossbowModel(skin.id());
                }
                case SPEAR -> {
                    uniqueModel(files, skin.id(), sprite, "minecraft:item/generated");
                    uniqueModel(files, skin.id() + "_in_hand", sprite.scaled(2), "minecraft:item/spear_in_hand");
                    yield spearModel(skin.id());
                }
            };
            // 2026-10：專屬外觀的傳奇在格子裡也有淺淺的同色外框（手上、地上照專屬外觀本身）；追逐級的外框是淡淡的彩色漸層
            cases.computeIfAbsent(skin.vanillaItem(), key -> new JsonArray()).add(selectCase(UniqueArt.modelKey(skin.id()),
                    skin.chase() ? framedInGui(plain("exilecore:item/ui/chase_frame"), plain(UNIQUE_TEX + skin.id()), model)
                            : framedInGui(UNIQUE_FRAME, plain(UNIQUE_TEX + skin.id()), model)));
            UNIQUE_ITEMS.add(skin.vanillaItem());
        }
    }

    // ---- 裝備幻化（2026-10，tools/gen_skin_art.py 畫的 web/skins/）----

    private static final String SKIN_TEX = "exilecore:item/skin/";

    /**
     * 每一套：物品圖（32×32）的貼圖與模型、物品定義 {@code exilecore:skin/<套>_<部位>}（弓、弩有拉弓的那幾張）、
     * 穿在身上的 {@code exilecore:skin_<套>}（equipment，原版 64×32 的盔甲貼圖格式）。
     */
    /** 3D 時裝（武器、副手、弓、弩、戰甲、頭飾、背飾、寵物）：web/pack3d.zip（tools/gen_models3d.py 產生）裡的檔案照路徑放進去。 */
    static void pack3d(final Map<String, byte[]> files) throws IOException {
        try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/pack3d.zip")) {
            if (in == null) {
                return;
            }
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(in)) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    final String rel = entry.getName();
                    if (!entry.isDirectory() && rel.startsWith("assets/") && !rel.contains("..")) {
                        files.put(rel, zip.readAllBytes());
                    }
                }
            }
        }
    }

    static void transmogSkins(final Map<String, byte[]> files) throws IOException {
        final List<String> names = new java.util.ArrayList<>();
        try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/skins/index.txt")) {
            if (in == null) {
                return;
            }
            for (final String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                final String name = line.strip();
                if (!name.isEmpty() && !name.startsWith("#") && name.endsWith(".png")) {
                    names.add(name.substring(0, name.length() - 4));
                }
            }
        }
        final java.util.Set<String> themes = new java.util.LinkedHashSet<>();
        for (final String name : names) {
            final byte[] bytes;
            try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/skins/" + name + ".png")) {
                if (in == null) {
                    continue;
                }
                bytes = in.readAllBytes();
            }
            if (name.endsWith("_humanoid_leggings")) {
                final String theme = name.substring(0, name.length() - "_humanoid_leggings".length());
                files.put("assets/exilecore/textures/entity/equipment/humanoid_leggings/skin_" + theme + ".png", bytes);
                continue;
            }
            if (name.endsWith("_humanoid")) {
                final String theme = name.substring(0, name.length() - "_humanoid".length());
                files.put("assets/exilecore/textures/entity/equipment/humanoid/skin_" + theme + ".png", bytes);
                themes.add(theme);
                continue;
            }
            files.put("assets/exilecore/textures/item/skin/" + name + ".png", bytes);
            final String parent = name.endsWith("_blade") || name.endsWith("_staff") ? "minecraft:item/handheld"
                    : name.contains("_bow") ? "minecraft:item/bow"
                    : name.contains("_crossbow") ? "minecraft:item/crossbow"
                    : "minecraft:item/generated";
            final JsonObject model = new JsonObject();
            model.addProperty("parent", parent);
            final JsonObject textures = new JsonObject();
            textures.addProperty("layer0", SKIN_TEX + name);
            model.add("textures", textures);
            files.put("assets/exilecore/models/item/skin/" + name + ".json", json(model));
        }
        for (final String theme : themes) {
            for (final String part : List.of("blade", "staff", "shield", "quiver", "helmet", "chestplate", "leggings", "boots")) {
                final JsonObject definition = new JsonObject();
                definition.add("model", plain(SKIN_TEX + theme + "_" + part));
                files.put("assets/exilecore/items/skin/" + theme + "_" + part + ".json", json(definition));
            }
            final JsonObject bow = new JsonObject();
            bow.add("model", bowModel(SKIN_TEX, theme + "_bow"));
            files.put("assets/exilecore/items/skin/" + theme + "_bow.json", json(bow));
            final JsonObject crossbow = new JsonObject();
            crossbow.add("model", crossbowModel(SKIN_TEX, theme + "_crossbow"));
            files.put("assets/exilecore/items/skin/" + theme + "_crossbow.json", json(crossbow));
            // 穿在身上
            final JsonObject equipment = new JsonObject();
            final JsonObject layers = new JsonObject();
            for (final String layer : List.of("humanoid", "humanoid_leggings")) {
                final JsonArray list = new JsonArray();
                final JsonObject texture = new JsonObject();
                texture.addProperty("texture", "exilecore:skin_" + theme);
                list.add(texture);
                layers.add(layer, list);
            }
            equipment.add("layers", layers);
            files.put("assets/exilecore/equipment/skin_" + theme + ".json", json(equipment));
        }
    }

    /** 貼圖 + 模型（parent 決定拿在手上的樣子）。 */
    private static void uniqueModel(final Map<String, byte[]> files, final String name, final UniqueArt.Sprite sprite, final String parent) throws IOException {
        files.put("assets/exilecore/textures/item/unique/" + name + ".png", png(spriteImage(sprite)));
        final JsonObject model = new JsonObject();
        model.addProperty("parent", parent);
        final JsonObject textures = new JsonObject();
        textures.addProperty("layer0", UNIQUE_TEX + name);
        model.add("textures", textures);
        files.put("assets/exilecore/models/item/unique/" + name + ".json", json(model));
    }

    static BufferedImage spriteImage(final UniqueArt.Sprite sprite) {
        final BufferedImage image = new BufferedImage(sprite.width(), sprite.height(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < sprite.height(); y++) {
            for (int x = 0; x < sprite.width(); x++) {
                image.setRGB(x, y, sprite.argb()[y][x]);
            }
        }
        return image;
    }

    /** 弓：沒在拉是本體，拉的時候照拉了多久換三張（跟原版 bow 的定義一樣）。 */
    private static JsonObject bowModel(final String id) {
        return bowModel(UNIQUE_TEX, id);
    }

    private static JsonObject bowModel(final String tex, final String id) {
        final JsonArray entries = new JsonArray();
        entries.add(threshold(0.65, plain(tex + id + "_pulling_1")));
        entries.add(threshold(0.9, plain(tex + id + "_pulling_2")));
        final JsonObject pulling = new JsonObject();
        pulling.addProperty("type", "minecraft:range_dispatch");
        pulling.addProperty("property", "minecraft:use_duration");
        pulling.addProperty("scale", 0.05);
        pulling.add("entries", entries);
        pulling.add("fallback", plain(tex + id + "_pulling_0"));
        return condition("minecraft:using_item", plain(tex + id), pulling);
    }

    /** 弩：上好弦是 _arrow，上弦中照拉的程度換三張，其他是本體（跟原版 crossbow 的定義一樣）。 */
    private static JsonObject crossbowModel(final String id) {
        return crossbowModel(UNIQUE_TEX, id);
    }

    private static JsonObject crossbowModel(final String tex, final String id) {
        final JsonArray entries = new JsonArray();
        entries.add(threshold(0.58, plain(tex + id + "_pulling_1")));
        entries.add(threshold(1.0, plain(tex + id + "_pulling_2")));
        final JsonObject pulling = new JsonObject();
        pulling.addProperty("type", "minecraft:range_dispatch");
        pulling.addProperty("property", "minecraft:crossbow/pull");
        pulling.add("entries", entries);
        pulling.add("fallback", plain(tex + id + "_pulling_0"));
        final JsonArray charged = new JsonArray();
        charged.add(selectCase("arrow", plain(tex + id + "_arrow")));
        charged.add(selectCase("rocket", plain(tex + id + "_arrow")));
        final JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:charge_type");
        select.add("cases", charged);
        select.add("fallback", condition("minecraft:using_item", plain(tex + id), pulling));
        return select;
    }

    /** 矛：介面、地上、展示框用平的那張，拿在手上用 32×32 的（跟原版 iron_spear 的定義一樣）。 */
    private static JsonObject spearModel(final String id) {
        final JsonArray contexts = new JsonArray();
        for (final String context : List.of("gui", "ground", "fixed", "on_shelf")) {
            contexts.add(context);
        }
        final JsonObject gui = new JsonObject();
        gui.add("when", contexts);
        gui.add("model", plain(UNIQUE_TEX + id));
        final JsonArray cases = new JsonArray();
        cases.add(gui);
        final JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:display_context");
        select.add("cases", cases);
        select.add("fallback", plain(UNIQUE_TEX + id + "_in_hand"));
        return select;
    }

    private static JsonObject condition(final String property, final JsonObject onFalse, final JsonObject onTrue) {
        final JsonObject condition = new JsonObject();
        condition.addProperty("type", "minecraft:condition");
        condition.addProperty("property", property);
        condition.add("on_false", onFalse);
        condition.add("on_true", onTrue);
        return condition;
    }

    private static JsonObject threshold(final double threshold, final JsonObject model) {
        final JsonObject entry = new JsonObject();
        entry.addProperty("threshold", threshold);
        entry.add("model", model);
        return entry;
    }

    /**
     * 原版物品的定義改成「custom_model_data 字串 0 符合就畫我們的，不然照原版」：原版的部分照抄 web/vanilla_items/ 裡客戶端的定義
     * （弓有拉弓、盔甲有紋飾、矛拿在手上是另一個模型…），其他鍵（swap_animation_scale 等）保留；沒有那份就用最簡單的原版模型。
     */
    /**
     * 魔法、稀有裝備的外框（2026-10）：原版物品定義加上「字串 0 是 rarity:magic／rarity:rare」的挑選，
     * 在格子裡（gui）畫一圈淺淺的同色漸層框、前面照原版的樣子畫物品；拿在手上、掉在地上照原版。
     * 原版的完整定義照抄 web/vanilla_items/（弓的拉弓、盔甲的紋飾都留著）。
     *
     * @return 加了外框的原版物品
     */
    static java.util.Set<String> rarityFrames(final Map<String, byte[]> files, final java.util.Set<String> gearItems,
                                             final Map<String, JsonArray> cases) throws IOException {
        final java.util.Set<String> framed = new java.util.TreeSet<>();
        if (gearItems.isEmpty()) {
            return framed;
        }
        for (final String item : gearItems) {
            final JsonObject gui = Vanilla.MODELS.get(item);
            final JsonObject full = vanillaModel(item);
            if (gui == null && full == null) {
                continue;
            }
            for (final Map.Entry<String, Integer> rarity : new TreeMap<>(RARITY_KEYS).entrySet()) {
                cases.computeIfAbsent(item, key -> new JsonArray()).add(selectCase(rarity.getKey(),
                        framedInGui(rarity.getValue(), gui != null ? gui : full, full != null ? full : gui)));
            }
            framed.add(item);
        }
        return framed;
    }

    /**
     * 鎖定的裝備（2026-10）：custom_model_data 的 flag 0 是 true 時，格子裡（gui）在原本的樣子前面疊一個右上角的小鎖頭；
     * 其他地方（手上、地上）照原本的樣子。
     */
    static JsonObject lockOverlay(final JsonObject original) {
        final JsonObject lock = new JsonObject();
        lock.addProperty("type", "minecraft:model");
        lock.addProperty("model", "exilecore:item/ui/lock");
        final JsonArray layers = new JsonArray();
        layers.add(original.deepCopy());
        layers.add(lock);
        final JsonObject composite = new JsonObject();
        composite.addProperty("type", "minecraft:composite");
        composite.add("models", layers);
        final JsonArray contexts = new JsonArray();
        contexts.add("gui");
        final JsonObject guiCase = new JsonObject();
        guiCase.add("when", contexts);
        guiCase.add("model", composite);
        final JsonArray contextCases = new JsonArray();
        contextCases.add(guiCase);
        final JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:display_context");
        select.add("cases", contextCases);
        select.add("fallback", original.deepCopy());
        final JsonObject condition = new JsonObject();
        condition.addProperty("type", "minecraft:condition");
        condition.addProperty("property", "minecraft:custom_model_data");
        condition.addProperty("index", 0);
        condition.add("on_true", select);
        condition.add("on_false", original);
        return condition;
    }

    /** 小鎖頭：一片在最前面的平面（z = 16），貼圖只有右上角的鎖。 */
    private static void lockModel(final Map<String, byte[]> files) throws IOException {
        files.put("assets/exilecore/models/item/ui/layer_front.json", json(layerModel(16.0, false)));
        files.put("assets/exilecore/textures/item/ui/lock.png", png(lockIcon()));
        final JsonObject model = new JsonObject();
        model.addProperty("parent", "exilecore:item/ui/layer_front");
        final JsonObject textures = new JsonObject();
        textures.addProperty("t", "exilecore:item/ui/lock");
        model.add("textures", textures);
        files.put("assets/exilecore/models/item/ui/lock.json", json(model));
    }

    /** 16×16：右上角 6×7 的金色小鎖（深色外框，亮的背景上也看得到）。 */
    static BufferedImage lockIcon() {
        final String[] rows = {
                "..........oooo..",
                ".........o####o.",
                ".........o#oo#o.",
                "........oooooooo",
                "........o######o",
                "........o##oo##o",
                "........o######o",
                "........oooooooo"};
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                final char c = rows[y].charAt(x);
                if (c == '#') {
                    image.setRGB(x, y, 0xFFFFD27F);
                } else if (c == 'o') {
                    image.setRGB(x, y, 0xFF2A1E0A);
                }
            }
        }
        return image;
    }

    /** 稀有度外框的模型（白色，物品定義裡用固定顏色著色）。 */
    private static void rarityFrameModel(final Map<String, byte[]> files) throws IOException {
        files.put("assets/exilecore/textures/item/ui/rarity_frame.png", png(rarityFrame()));
        final JsonObject frame = new JsonObject();
        frame.addProperty("parent", "exilecore:item/ui/layer_frame");
        final JsonObject frameTextures = new JsonObject();
        frameTextures.addProperty("t", "exilecore:item/ui/rarity_frame");
        frame.add("textures", frameTextures);
        files.put("assets/exilecore/models/item/ui/rarity_frame.json", json(frame));
        // 追逐級傳奇的外框（2026-10）：貼圖本身就是彩色的，不著色
        files.put("assets/exilecore/textures/item/ui/chase_frame.png", png(chaseFrame()));
        final JsonObject chase = new JsonObject();
        chase.addProperty("parent", "exilecore:item/ui/layer_frame");
        final JsonObject chaseTextures = new JsonObject();
        chaseTextures.addProperty("t", "exilecore:item/ui/chase_frame");
        chase.add("textures", chaseTextures);
        files.put("assets/exilecore/models/item/ui/chase_frame.json", json(chase));
    }

    /** 在格子裡（gui）：rgb 顏色的淺外框＋gui 的樣子；其他地方（手上、地上）照 other。 */
    static JsonObject framedInGui(final int rgb, final JsonObject gui, final JsonObject other) {
        final JsonObject tinted = new JsonObject();
        tinted.addProperty("type", "minecraft:model");
        tinted.addProperty("model", "exilecore:item/ui/rarity_frame");
        final JsonObject tint = new JsonObject();
        tint.addProperty("type", "minecraft:constant");
        tint.addProperty("value", rgb);
        final JsonArray tints = new JsonArray();
        tints.add(tint);
        tinted.add("tints", tints);
        return framedInGui(tinted, gui, other);
    }

    /** 在格子裡（gui）：frame 這個外框模型＋gui 的樣子；其他地方（手上、地上）照 other。 */
    static JsonObject framedInGui(final JsonObject frame, final JsonObject gui, final JsonObject other) {
        final JsonArray layers = new JsonArray();
        layers.add(frame);
        layers.add(gui.deepCopy());
        final JsonObject composite = new JsonObject();
        composite.addProperty("type", "minecraft:composite");
        composite.add("models", layers);
        final JsonArray contexts = new JsonArray();
        contexts.add("gui");
        final JsonObject guiCase = new JsonObject();
        guiCase.add("when", contexts);
        guiCase.add("model", composite);
        final JsonArray contextCases = new JsonArray();
        contextCases.add(guiCase);
        final JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:display_context");
        select.add("cases", contextCases);
        select.add("fallback", other.deepCopy());
        return select;
    }

    /** web/vanilla_items/ 裡客戶端的原版定義的 model 部分；沒有那份 null。 */
    private static JsonObject vanillaModel(final String vanillaItem) {
        try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/vanilla_items/" + vanillaItem + ".json")) {
            if (in == null) {
                return null;
            }
            final JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            return root.has("model") ? root.getAsJsonObject("model") : null;
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 魔法、稀有裝備的外框貼圖（白色，著色後是稀有度的顏色）：最外圈最亮，往裡面慢慢淡掉，整體很淡（不搶物品本身）。
     */
    static BufferedImage rarityFrame() {
        final BufferedImage image = canvas();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                final int edge = Math.min(Math.min(x, y), Math.min(SIZE - 1 - x, SIZE - 1 - y));
                final int alpha = switch (edge) {
                    case 0 -> 0;          // 最外一圈留給格子本身的框線
                    case 1 -> 0x9A;
                    case 2 -> 0x70;
                    case 3 -> 0x4C;
                    case 4 -> 0x30;
                    case 5 -> 0x1C;
                    case 6 -> 0x0E;
                    default -> 0x06;      // 中間一點點底色
                };
                image.setRGB(x, y, alpha << 24 | 0xFFFFFF);
            }
        }
        return image;
    }

    /**
     * 追逐級傳奇的外框貼圖（2026-10）：淡淡的彩色漸層——顏色繞著外框轉一圈（紅→黃→綠→青→藍→紫→紅），飽和度壓低成粉彩；
     * 濃淡跟一般的稀有度外框一樣（最外圈最亮，往裡面慢慢淡掉）。貼圖本身帶顏色，物品定義裡不著色。
     */
    static BufferedImage chaseFrame() {
        final BufferedImage image = canvas();
        final double centre = (SIZE - 1) / 2.0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                final int edge = Math.min(Math.min(x, y), Math.min(SIZE - 1 - x, SIZE - 1 - y));
                final int alpha = switch (edge) {
                    case 0 -> 0;          // 最外一圈留給格子本身的框線
                    case 1 -> 0xA8;
                    case 2 -> 0x7C;
                    case 3 -> 0x54;
                    case 4 -> 0x34;
                    case 5 -> 0x1E;
                    case 6 -> 0x0E;
                    default -> 0x06;
                };
                // 從左上角開始順時針轉一圈
                final double angle = Math.atan2(y - centre, x - centre);
                final float hue = (float) ((angle + Math.PI * 0.75) / (2 * Math.PI));
                final int rgb = java.awt.Color.HSBtoRGB(hue - (float) Math.floor(hue), 0.5f, 1.0f) & 0xFFFFFF;
                image.setRGB(x, y, alpha << 24 | rgb);
            }
        }
        return image;
    }

    static JsonObject vanillaOverride(final String vanillaItem, final JsonArray cases) {
        JsonObject root = null;
        try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/vanilla_items/" + vanillaItem + ".json")) {
            if (in != null) {
                root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (final IOException | RuntimeException e) {
            root = null;
        }
        if (root == null || !root.has("model")) {
            root = new JsonObject();
            root.add("model", plain("minecraft:item/" + vanillaItem));
        }
        final JsonObject fallback = root.getAsJsonObject("model");
        root.add("model", select(0, cases, fallback));
        return root;
    }

    // ---- 對話框的「自訂畫面」按鈕 ----

    /** 原版的三張圖（一般、滑過去、停用）→ 樣式。 */
    static final Map<String, Integer> DIALOG_NOTICE = Map.of(
            "warning_button", 0, "warning_button_highlighted", 1, "warning_button_disabled", 2);
    /** 原版按鈕圖的大小。 */
    static final int DIALOG_NOTICE_SIZE = 20;

    /**
     * 一個小圓點裡的「i」（直徑 9 格，畫在 20×20 的正中間，其他地方透明）：
     * 一般是半透明的暗灰底＋淺灰字，滑過去是金色底（《星骸》的金色）＋深色字，停用時更淡。
     */
    static BufferedImage dialogNotice(final int style) {
        final int size = DIALOG_NOTICE_SIZE;
        final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        final int background = switch (style) {
            case 1 -> 0xE6F5B942;
            case 2 -> 0x40000000;
            default -> 0x73000000;
        };
        final int glyph = switch (style) {
            case 1 -> 0xFF2A2116;
            case 2 -> 0x60A0A0A0;
            default -> 0xB4C8C8C8;
        };
        final double center = (size - 1) / 2.0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                final double dx = x - center;
                final double dy = y - center;
                if (dx * dx + dy * dy <= 4.6 * 4.6) {
                    image.setRGB(x, y, background);
                }
            }
        }
        // 「i」：上面一個點，下面一條（兩格寬）
        final int left = size / 2 - 1;
        for (int x = left; x < left + 2; x++) {
            image.setRGB(x, 6, glyph);
            for (int y = 8; y < 13; y++) {
                image.setRGB(x, y, glyph);
            }
        }
        return image;
    }

    // ---- 挖方塊的聲音 ----

    /** 要換成沒有聲音的原版聲音事件（web/block_hit_sounds.txt；讀不到就是空的，資源包不放 sounds.json）。 */
    static List<String> mutedSounds() {
        try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/block_hit_sounds.txt")) {
            if (in == null) {
                return List.of();
            }
            final List<String> list = new java.util.ArrayList<>();
            for (final String raw : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                final String line = raw.strip();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    list.add(line);
                }
            }
            return List.copyOf(list);
        } catch (final IOException e) {
            return List.of();
        }
    }

    /** sounds.json：每個事件 replace 成空的清單（播的時候什麼都沒有）。 */
    static JsonObject mutedSoundsJson(final List<String> events) {
        final JsonObject root = new JsonObject();
        for (final String event : events) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("replace", true);
            entry.add("sounds", new JsonArray());
            root.add(event, entry);
        }
        return root;
    }

    // ---- 空的藥劑欄 ----

    /** 空瓶提示的種類 → 輪廓的顏色（藥劑欄的空瓶提示，見 FlaskService.placeholder）。順序固定：資源包每次產生都一樣。 */
    static final Map<String, Integer> FLASK_SLOTS = flaskSlots();

    private static Map<String, Integer> flaskSlots() {
        final Map<String, Integer> slots = new java.util.LinkedHashMap<>();
        slots.put("life", 0xFF5C5C);
        slots.put("mana", 0x5C8DFF);
        return java.util.Collections.unmodifiableMap(slots);
    }

    /** 藥劑瓶的輪廓（圓肚、細頸、瓶塞），裡面淡淡的顏色：「這裡放一瓶藥劑」。 */
    static BufferedImage flaskSlot(final int rgb) {
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        final Color line = new Color(rgb);
        final Color fill = new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 70);
        final Path2D bottle = new Path2D.Double();
        bottle.moveTo(13, 9);
        bottle.lineTo(13, 13);
        bottle.curveTo(6, 15, 4, 21, 6.5, 25.5);
        bottle.curveTo(9, 29.5, 23, 29.5, 25.5, 25.5);
        bottle.curveTo(28, 21, 26, 15, 19, 13);
        bottle.lineTo(19, 9);
        bottle.closePath();
        g.setPaint(fill);
        g.fill(bottle);
        g.setPaint(line);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(bottle);
        // 瓶塞
        g.fill(new java.awt.geom.RoundRectangle2D.Double(11.5, 4.5, 9, 4.5, 2, 2));
        g.dispose();
        return image;
    }

    // ---- 選取外框 ----

    /** 外框的預設顏色（金色）；選單會用 custom_model_data 的顏色 0 換顏色。 */
    static final int FRAME_DEFAULT = 0xFFC83C;

    /** 這個原版物品（例如 iron_sword）畫得出選取外框（知道它在格子裡長什麼樣子）。 */
    public static boolean framable(final String itemId) {
        return Vanilla.MODELS.containsKey(itemId);
    }

    /**
     * 這個 custom_model_data 字串是不是專屬貼圖的標記（傳奇 {@code unique:代號}、通貨 {@code currency:代號}）。
     * 選取外框裡畫的是那張圖（外框的物品定義有對應的挑選），不是原版物品的樣子。
     */
    public static boolean isArtKey(final String key) {
        return key != null && (key.startsWith(UniqueArt.KEY_PREFIX) || key.startsWith(CurrencyArt.KEY_PREFIX));
    }

    /** 畫得出外框的原版物品有幾種（測試用）。 */
    static int framableCount() {
        return Vanilla.MODELS.size();
    }

    /** 原版物品在格子裡的模型：第一次用到才讀。 */
    private static final class Vanilla {
        static final Map<String, JsonObject> MODELS = load();

        private Vanilla() {
        }

        private static Map<String, JsonObject> load() {
            final Map<String, JsonObject> models = new TreeMap<>();
            final System.Logger log = System.getLogger(UiPack.class.getName());
            try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/vanilla_item_models.txt")) {
                if (in == null) {
                    log.log(System.Logger.Level.WARNING, "找不到 web/vanilla_item_models.txt：選單的選取外框只畫得出少數物品（其他只發光）");
                } else {
                    for (final String raw : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                        final String line = raw.strip();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        final String[] parts = line.split("\\s+", 2);
                        final String id = parts[0];
                        final String path = parts.length == 1 ? "minecraft:item/" + id
                                : parts[1].equals("b") ? "minecraft:block/" + id : parts[1];
                        models.put(id, plain(path));
                    }
                }
            } catch (final IOException e) {
                log.log(System.Logger.Level.WARNING, "讀不到 web/vanilla_item_models.txt：選單的選取外框只畫得出少數物品（其他只發光）", e);
            }
            rules(models);
            return models;
        }
    }

    /**
     * 原版物品定義比較複雜的（會依穿戴的紋飾、拿在手上、時間換樣子，或是特殊的繪製方式）：
     * 照它們在選單格子裡的樣子寫（沒有紋飾、沒在用、預設的顏色）。
     */
    private static void rules(final Map<String, JsonObject> models) {
        for (final String metal : List.of("leather", "chainmail", "copper", "iron", "golden", "diamond", "netherite")) {
            for (final String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
                final String id = metal + "_" + piece;
                models.put(id, metal.equals("leather") ? tinted("minecraft:item/" + id, tint("minecraft:dye", -6265536))
                        : plain("minecraft:item/" + id));
            }
        }
        for (final String id : List.of("turtle_helmet", "bow", "crossbow", "fishing_rod", "elytra", "trident", "spyglass", "brush",
                "goat_horn", "bundle", "wooden_spear", "stone_spear", "copper_spear", "iron_spear", "golden_spear", "diamond_spear",
                "netherite_spear")) {
            models.put(id, plain("minecraft:item/" + id));
        }
        for (final String color : DYES) {
            models.put(color + "_bundle", plain("minecraft:item/" + color + "_bundle"));
            final JsonObject banner = special("minecraft:item/template_banner", "minecraft:banner");
            banner.getAsJsonObject("model").addProperty("color", color);
            banner.add("transformation", transformation(new double[] {0, 0, 0, 1}, new double[] {0.6666667, -0.6666667, -0.6666667},
                    new double[] {0.5, 0, 0.5}));
            models.put(color + "_banner", banner);
        }
        models.put("clock", plain("minecraft:item/clock_00"));
        models.put("compass", plain("minecraft:item/compass_16"));
        models.put("recovery_compass", plain("minecraft:item/recovery_compass_16"));
        for (final String id : List.of("potion", "splash_potion", "lingering_potion", "tipped_arrow")) {
            models.put(id, tinted("minecraft:item/" + id, tint("minecraft:potion", -13083194)));
        }
        models.put("filled_map", tinted("minecraft:item/filled_map", constant(-1), tint("minecraft:map_color", 4603950)));
        models.put("firework_star", tinted("minecraft:item/firework_star", constant(-1), tint("minecraft:firework", -7697782)));
        models.put("lily_pad", tinted("minecraft:item/lily_pad", constant(-9321636)));
        final JsonObject grass = new JsonObject();
        grass.addProperty("type", "minecraft:grass");
        grass.addProperty("downfall", 1.0);
        grass.addProperty("temperature", 0.5);
        models.put("grass_block", tinted("minecraft:block/grass_block", grass));
        // 特殊繪製：箱子、盾牌、玩家頭顱
        for (final String[] chest : new String[][] {{"chest", "minecraft:normal"}, {"trapped_chest", "minecraft:trapped"},
                {"ender_chest", "minecraft:ender"}, {"copper_chest", "minecraft:copper"}}) {
            final JsonObject model = special("minecraft:item/" + chest[0], "minecraft:chest");
            model.getAsJsonObject("model").addProperty("texture", chest[1]);
            models.put(chest[0], model);
        }
        final JsonObject shield = special("minecraft:item/shield", "minecraft:shield");
        shield.add("transformation", transformation(new double[] {0, 0, 0, 1}, new double[] {1, -1, -1}, new double[] {0, 0, 0}));
        models.put("shield", shield);
        final JsonObject head = special("minecraft:item/template_skull", "minecraft:player_head");
        head.add("transformation", transformation(new double[] {1, 0, 0, 0}, new double[] {1, 1, 1}, new double[] {0.5, 0, 0.5}));
        models.put("player_head", head);
    }

    private static final List<String> DYES = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    private static JsonObject plain(final String path) {
        final JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", path);
        return model;
    }

    private static JsonObject tinted(final String path, final JsonObject... tints) {
        final JsonObject model = plain(path);
        final JsonArray array = new JsonArray();
        for (final JsonObject tint : tints) {
            array.add(tint);
        }
        model.add("tints", array);
        return model;
    }

    private static JsonObject tint(final String type, final int fallback) {
        final JsonObject tint = new JsonObject();
        tint.addProperty("type", type);
        tint.addProperty("default", fallback);
        return tint;
    }

    private static JsonObject constant(final int value) {
        final JsonObject tint = new JsonObject();
        tint.addProperty("type", "minecraft:constant");
        tint.addProperty("value", value);
        return tint;
    }

    private static JsonObject special(final String base, final String type) {
        final JsonObject inner = new JsonObject();
        inner.addProperty("type", type);
        final JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:special");
        model.addProperty("base", base);
        model.add("model", inner);
        return model;
    }

    private static JsonObject transformation(final double[] leftRotation, final double[] scale, final double[] translation) {
        final JsonObject transformation = new JsonObject();
        transformation.add("left_rotation", numbers(leftRotation));
        transformation.add("right_rotation", numbers(0, 0, 0, 1));
        transformation.add("scale", numbers(scale));
        transformation.add("translation", numbers(translation));
        return transformation;
    }

    /**
     * 選取外框的物品定義：框（顏色 0 著色）＋照字串 0 畫原本的物品。字串 0 是原版物品名稱，
     * 或專屬貼圖的標記（傳奇 unique:代號、通貨 currency:代號）：框裡畫那張圖，不會因為被選到就變回原版的樣子。
     */
    private static JsonObject highlightDefinition(final List<UniqueSkin> skins, final List<CurrencySkin> currencySkins) {
        final JsonObject frame = new JsonObject();
        frame.addProperty("type", "minecraft:model");
        frame.addProperty("model", "exilecore:item/ui/highlight");
        final JsonObject tint = new JsonObject();
        tint.addProperty("type", "minecraft:custom_model_data");
        tint.addProperty("index", 0);
        tint.addProperty("default", FRAME_DEFAULT);
        final JsonArray tints = new JsonArray();
        tints.add(tint);
        frame.add("tints", tints);
        final JsonArray cases = new JsonArray();
        for (final Map.Entry<String, JsonObject> entry : Vanilla.MODELS.entrySet()) {
            cases.add(selectCase(entry.getKey(), entry.getValue()));
        }
        // 專屬貼圖：框裡畫平面的那張（弓、弩、矛不用拉弓、拿在手上的那幾張）
        final Map<String, UniqueArt.Sprite> uniqueSprites = UniqueArt.sprites();
        for (final UniqueSkin skin : skins) {
            if (uniqueSprites.containsKey(skin.id())) {
                cases.add(selectCase(UniqueArt.modelKey(skin.id()), plain(UNIQUE_TEX + skin.id())));
            }
        }
        final java.util.Set<String> arts = new java.util.TreeSet<>();
        for (final CurrencySkin skin : currencySkins) {
            if (CurrencyArt.sprites().containsKey(skin.artId())) {
                arts.add(skin.artId());
            }
        }
        for (final String art : arts) {
            cases.add(selectCase(CurrencyArt.modelKey(art), plain("exilecore:item/currency/" + art)));
        }
        final JsonArray models = new JsonArray();
        models.add(frame);
        models.add(select(0, cases, empty()));
        final JsonObject composite = new JsonObject();
        composite.addProperty("type", "minecraft:composite");
        composite.add("models", models);
        final JsonObject root = new JsonObject();
        root.add("model", composite);
        root.addProperty("oversized_in_gui", true);
        root.addProperty("hand_animation_on_swap", false);
        final JsonObject properties = new JsonObject();
        properties.addProperty("oversized_in_gui", true);
        properties.addProperty("hand_animation_on_swap", false);
        root.add("properties", properties);
        return root;
    }

    /**
     * 選取外框的貼圖（白色，著色後變金色或綠色）：最外面一圈暗一點的邊、三圈亮的框、往裡面淡掉的光暈，中間透明。
     * 整片蓋 18×18（連格子的框線），物品畫在它前面。
     */
    static BufferedImage highlightFrame() {
        final BufferedImage image = canvas();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                final int edge = Math.min(Math.min(x, y), Math.min(SIZE - 1 - x, SIZE - 1 - y));
                final int argb = switch (edge) {
                    case 0 -> 0xFF8A8A8A;
                    case 1, 2, 3 -> 0xFFFFFFFF;
                    case 4 -> 0x90FFFFFF;
                    case 5 -> 0x48FFFFFF;
                    case 6 -> 0x1CFFFFFF;
                    default -> 0;
                };
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    // ---- JSON ----

    private static JsonObject packMeta() {
        final JsonObject pack = new JsonObject();
        pack.addProperty("description", "《星骸》介面：星盤式天賦樹、傳奇外觀、招式預警");
        pack.addProperty("min_format", PACK_FORMAT);
        pack.addProperty("max_format", 999);
        final JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    /** 一片平面（只有正面），GUI 裡放大 1.125 倍＝18×18，剛好蓋住一格。 */
    private static JsonObject layerModel(final double z, final boolean tinted) {
        final JsonObject root = new JsonObject();
        root.addProperty("gui_light", "front");
        root.addProperty("ambientocclusion", false);
        final JsonObject textures = new JsonObject();
        textures.addProperty("particle", "#t");
        root.add("textures", textures);
        final JsonObject face = new JsonObject();
        face.add("uv", numbers(0, 0, 16, 16));
        face.addProperty("texture", "#t");
        if (tinted) {
            face.addProperty("tintindex", 0);
        }
        final JsonObject faces = new JsonObject();
        faces.add("south", face);
        final JsonObject element = new JsonObject();
        element.add("from", numbers(0, 0, z));
        element.add("to", numbers(16, 16, z));
        element.addProperty("shade", false);
        element.add("faces", faces);
        final JsonArray elements = new JsonArray();
        elements.add(element);
        root.add("elements", elements);
        final JsonObject display = new JsonObject();
        display.add("gui", transform(1.125));
        display.add("fixed", transform(1.0));
        display.add("ground", transform(0.5));
        display.add("firstperson_righthand", transform(0.6));
        display.add("firstperson_lefthand", transform(0.6));
        display.add("thirdperson_righthand", transform(0.5));
        display.add("thirdperson_lefthand", transform(0.5));
        display.add("head", transform(1.0));
        root.add("display", display);
        return root;
    }

    private static JsonObject transform(final double scale) {
        final JsonObject transform = new JsonObject();
        transform.add("rotation", numbers(0, 0, 0));
        transform.add("translation", numbers(0, 0, 0));
        transform.add("scale", numbers(scale, scale, scale));
        return transform;
    }

    private static JsonObject treeCellDefinition() {
        final JsonArray models = new JsonArray();
        // 背景：字串 0 選哪一張
        final JsonArray backgrounds = new JsonArray();
        for (int i = 1; i < BACKGROUNDS; i++) {
            backgrounds.add(selectCase(Integer.toString(i), model("bg_" + i, -1)));
        }
        models.add(select(Cell.STRING_BACKGROUND, backgrounds, model("bg_0", -1)));
        // 連線：旗標 i 有沒有、顏色 i 什麼顏色
        for (int i = 0; i < Cell.EDGES.size(); i++) {
            final JsonObject condition = new JsonObject();
            condition.addProperty("type", "minecraft:condition");
            condition.addProperty("property", "minecraft:custom_model_data");
            condition.addProperty("index", i);
            condition.add("on_true", model("edge_" + Cell.EDGES.get(i), i));
            condition.add("on_false", empty());
            models.add(condition);
        }
        // 發光（在節點後面）
        final JsonArray glow = new JsonArray();
        glow.add(selectCase("glow", model("glow", Cell.COLOR_GLOW)));
        models.add(select(Cell.STRING_GLOW, glow, empty()));
        // 節點
        final JsonArray nodes = new JsonArray();
        for (final String node : Cell.NODES) {
            nodes.add(selectCase(node, model("node_" + node, Cell.COLOR_NODE)));
        }
        models.add(select(Cell.STRING_NODE, nodes, empty()));
        // 搜尋標記（最上面）
        final JsonArray ring = new JsonArray();
        ring.add(selectCase("ring", model("ring", Cell.COLOR_RING)));
        models.add(select(Cell.STRING_RING, ring, empty()));

        final JsonObject composite = new JsonObject();
        composite.addProperty("type", "minecraft:composite");
        composite.add("models", models);
        final JsonObject root = new JsonObject();
        root.add("model", composite);
        // 1.21.6 起物品預設不能畫出 16×16 的格子；兩種寫法都放，認得哪一種就用哪一種
        root.addProperty("oversized_in_gui", true);
        root.addProperty("hand_animation_on_swap", false);
        final JsonObject properties = new JsonObject();
        properties.addProperty("oversized_in_gui", true);
        properties.addProperty("hand_animation_on_swap", false);
        root.add("properties", properties);
        return root;
    }

    private static JsonObject iconDefinition() {
        final JsonArray cases = new JsonArray();
        for (final String icon : ICONS) {
            final JsonObject model = new JsonObject();
            model.addProperty("type", "minecraft:model");
            model.addProperty("model", "exilecore:item/ui/icon/" + icon);
            cases.add(selectCase(icon, model));
        }
        final JsonObject paper = new JsonObject();
        paper.addProperty("type", "minecraft:model");
        paper.addProperty("model", "minecraft:item/paper");
        final JsonObject root = new JsonObject();
        root.add("model", select(0, cases, paper));
        return root;
    }

    private static JsonObject model(final String name, final int tintColorIndex) {
        final JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", TEX + name);
        if (tintColorIndex >= 0) {
            final JsonObject tint = new JsonObject();
            tint.addProperty("type", "minecraft:custom_model_data");
            tint.addProperty("index", tintColorIndex);
            tint.addProperty("default", 0xFFFFFF);
            final JsonArray tints = new JsonArray();
            tints.add(tint);
            model.add("tints", tints);
        }
        return model;
    }

    private static JsonObject select(final int stringIndex, final JsonArray cases, final JsonObject fallback) {
        final JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:custom_model_data");
        select.addProperty("index", stringIndex);
        select.add("cases", cases);
        select.add("fallback", fallback);
        return select;
    }

    private static JsonObject selectCase(final String when, final JsonObject model) {
        final JsonObject entry = new JsonObject();
        entry.addProperty("when", when);
        entry.add("model", model);
        return entry;
    }

    private static JsonObject empty() {
        final JsonObject empty = new JsonObject();
        empty.addProperty("type", "minecraft:empty");
        return empty;
    }

    private static JsonArray numbers(final double... values) {
        final JsonArray array = new JsonArray();
        for (final double value : values) {
            if (value == Math.rint(value)) {
                array.add((long) value);
            } else {
                array.add(value);
            }
        }
        return array;
    }

    /** 一張貼圖＋用它的模型（parent 是哪一層）。 */
    private static void texture(final Map<String, byte[]> files, final String name, final BufferedImage image, final String layer)
            throws IOException {
        files.put("assets/exilecore/textures/item/ui/tree/" + name + ".png", png(image));
        final JsonObject model = new JsonObject();
        model.addProperty("parent", "exilecore:item/ui/" + layer);
        final JsonObject textures = new JsonObject();
        textures.addProperty("t", TEX + name);
        model.add("textures", textures);
        files.put("assets/exilecore/models/item/ui/tree/" + name + ".json", json(model));
    }

    // ---- 貼圖 ----

    private static BufferedImage canvas() {
        return new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
    }

    private static Graphics2D smooth(final BufferedImage image) {
        final Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /** 星空：深藍底、幾顆不同亮度的星星（每種背景固定的種子）。 */
    static BufferedImage background(final int variant) {
        final BufferedImage image = canvas();
        final SplittableRandom random = new SplittableRandom(0x5EED_0000L + variant * 7919L);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                image.setRGB(x, y, 0xFF0A0D1A);
            }
        }
        // 很淡的星塵
        for (int i = 0; i < 10; i++) {
            final int x = random.nextInt(SIZE);
            final int y = random.nextInt(SIZE);
            image.setRGB(x, y, blend(0xFF0A0D1A, 0x8090C0, 0.18 + random.nextDouble() * 0.12));
        }
        // 星星
        final int[] colors = {0xFFFFFF, 0xAAC8FF, 0xFFF0B0, 0xFFFFFF};
        final int stars = 3 + random.nextInt(4);
        for (int i = 0; i < stars; i++) {
            final int x = 1 + random.nextInt(SIZE - 2);
            final int y = 1 + random.nextInt(SIZE - 2);
            final int color = colors[random.nextInt(colors.length)];
            final double brightness = 0.45 + random.nextDouble() * 0.5;
            image.setRGB(x, y, blend(0xFF0A0D1A, color, brightness));
            if (brightness > 0.85 && random.nextBoolean()) {
                // 亮的星星有十字光芒
                for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    image.setRGB(x + d[0], y + d[1], blend(0xFF0A0D1A, color, brightness * 0.35));
                }
            }
        }
        return image;
    }

    private static int blend(final int base, final int color, final double amount) {
        final int r = (int) Math.round(((base >> 16) & 0xFF) * (1 - amount) + ((color >> 16) & 0xFF) * amount);
        final int g = (int) Math.round(((base >> 8) & 0xFF) * (1 - amount) + ((color >> 8) & 0xFF) * amount);
        final int b = (int) Math.round((base & 0xFF) * (1 - amount) + (color & 0xFF) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 從格子中心連到一邊的線（白色，著色後變金色、灰色…），兩側有一點光暈。 */
    static BufferedImage edge(final String direction) {
        final BufferedImage image = canvas();
        final int half = SIZE / 2;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                // 換算成「往上」的座標：u 是橫向離中心多遠，v 是從邊到中心的位置
                final int u;
                final int v;
                switch (direction) {
                    case "n" -> {
                        u = x;
                        v = y;
                    }
                    case "s" -> {
                        u = x;
                        v = SIZE - 1 - y;
                    }
                    case "e" -> {
                        u = y;
                        v = SIZE - 1 - x;
                    }
                    default -> {
                        u = y;
                        v = x;
                    }
                }
                if (v > half) {
                    continue;
                }
                final double distance = Math.abs(u + 0.5 - half);
                final int alpha;
                if (distance <= 1.5) {
                    alpha = 255;
                } else if (distance <= 2.5) {
                    alpha = 150;
                } else if (distance <= 3.5) {
                    alpha = 45;
                } else {
                    continue;
                }
                image.setRGB(x, y, (alpha << 24) | 0xFFFFFF);
            }
        }
        return image;
    }

    /** 已配置的節點後面的光暈。 */
    static BufferedImage glow() {
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        g.setPaint(new RadialGradientPaint(SIZE / 2f, SIZE / 2f, SIZE / 2f, new float[] {0f, 0.45f, 1f},
                new Color[] {new Color(255, 255, 255, 150), new Color(255, 255, 255, 70), new Color(255, 255, 255, 0)}));
        g.fill(new Ellipse2D.Double(0, 0, SIZE, SIZE));
        g.dispose();
        return image;
    }

    private static void disc(final Graphics2D g, final double radius, final Color fill, final Color outline, final float stroke) {
        final double c = SIZE / 2.0;
        final Ellipse2D shape = new Ellipse2D.Double(c - radius, c - radius, radius * 2, radius * 2);
        g.setPaint(fill);
        g.fill(shape);
        if (outline != null) {
            g.setPaint(outline);
            g.setStroke(new BasicStroke(stroke));
            g.draw(shape);
        }
    }

    /** 小點：實心的小圓。 */
    static BufferedImage nodeSmall() {
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        disc(g, 6.5, new Color(0xE8E8E8), new Color(0x101018), 2f);
        disc(g, 3.2, new Color(0xFFFFFF), null, 0f);
        g.dispose();
        return image;
    }

    /** 核心：大一點，有一圈裝飾。 */
    static BufferedImage nodeNotable() {
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        disc(g, 10.5, new Color(0xDADADA), new Color(0x101018), 2.5f);
        disc(g, 7.5, new Color(0xFFFFFF), new Color(0x707070), 1.2f);
        disc(g, 3.5, new Color(0xBDBDBD), null, 0f);
        g.dispose();
        return image;
    }

    /** 基石：八角形、雙層框。 */
    static BufferedImage nodeKeystone() {
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        final Path2D outer = polygon(8, 14, Math.PI / 8);
        g.setPaint(new Color(0xD0D0D0));
        g.fill(outer);
        g.setPaint(new Color(0x101018));
        g.setStroke(new BasicStroke(2.5f));
        g.draw(outer);
        final Path2D inner = polygon(8, 9.5, Math.PI / 8);
        g.setPaint(new Color(0xFFFFFF));
        g.fill(inner);
        g.setPaint(new Color(0x606060));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(inner);
        disc(g, 3.5, new Color(0xB0B0B0), null, 0f);
        g.dispose();
        return image;
    }

    /** 起點：八角星。 */
    static BufferedImage nodeStart() {
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        final Path2D star = new Path2D.Double();
        final double c = SIZE / 2.0;
        for (int i = 0; i < 16; i++) {
            final double radius = i % 2 == 0 ? 14.5 : 7.0;
            final double angle = -Math.PI / 2 + i * Math.PI / 8;
            final double x = c + Math.cos(angle) * radius;
            final double y = c + Math.sin(angle) * radius;
            if (i == 0) {
                star.moveTo(x, y);
            } else {
                star.lineTo(x, y);
            }
        }
        star.closePath();
        g.setPaint(new Color(0xF0F0F0));
        g.fill(star);
        g.setPaint(new Color(0x101018));
        g.setStroke(new BasicStroke(2f));
        g.draw(star);
        disc(g, 4, new Color(0xFFFFFF), new Color(0x707070), 1f);
        g.dispose();
        return image;
    }

    /** 空的珠寶孔：六角形的框，中間是空的（看得到背景的星空）。 */
    static BufferedImage nodeSocket() {
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        final Path2D outer = polygon(6, 13.5, -Math.PI / 2);
        g.setPaint(new Color(0x101018));
        g.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outer);
        g.setPaint(new Color(0xE8E8E8));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outer);
        final Path2D inner = polygon(6, 7, -Math.PI / 2);
        g.setPaint(new Color(255, 255, 255, 110));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(inner);
        g.dispose();
        return image;
    }

    /** 放了珠寶的珠寶孔：六角形的框，中間一顆切面的寶石（整張著成珠寶的顏色）。 */
    static BufferedImage nodeJewel() {
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        final Path2D outer = polygon(6, 13.5, -Math.PI / 2);
        g.setPaint(new Color(0xD8D8D8));
        g.fill(outer);
        g.setPaint(new Color(0x101018));
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outer);
        // 寶石：亮的上半、暗一點的下半，中間一道高光
        final Path2D gem = polygon(6, 9, -Math.PI / 2);
        g.setPaint(new Color(0xFFFFFF));
        g.fill(gem);
        final Path2D lower = new Path2D.Double();
        final double c = SIZE / 2.0;
        lower.moveTo(c - 9 * Math.cos(Math.PI / 6), c + 9 * Math.sin(Math.PI / 6));
        lower.lineTo(c, c + 9);
        lower.lineTo(c + 9 * Math.cos(Math.PI / 6), c + 9 * Math.sin(Math.PI / 6));
        lower.lineTo(c, c + 2);
        lower.closePath();
        g.setPaint(new Color(0, 0, 0, 70));
        g.fill(lower);
        g.setPaint(new Color(0x606060));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(gem);
        g.setPaint(new Color(255, 255, 255, 230));
        g.fill(new Ellipse2D.Double(c - 4.5, c - 6, 3, 3));
        g.dispose();
        return image;
    }

    /** 搜尋標記：一圈外框。 */
    static BufferedImage ring() {
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        final double c = SIZE / 2.0;
        g.setPaint(Color.WHITE);
        g.setStroke(new BasicStroke(2.6f));
        g.draw(new Ellipse2D.Double(c - 14, c - 14, 28, 28));
        g.dispose();
        return image;
    }

    private static Path2D polygon(final int sides, final double radius, final double rotation) {
        final Path2D path = new Path2D.Double();
        final double c = SIZE / 2.0;
        for (int i = 0; i < sides; i++) {
            final double angle = rotation + i * 2 * Math.PI / sides;
            final double x = c + Math.cos(angle) * radius;
            final double y = c + Math.sin(angle) * radius;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.closePath();
        return path;
    }

    /** 按鍵圖示：一隻滑鼠，按的那一邊是金色；潛行的版本右下角多一個藍色的 Shift 箭頭。 */
    static BufferedImage keyIcon(final String name) {
        final boolean sneak = name.contains("sneak");
        final boolean left = name.endsWith("left");
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        final double x = sneak ? 3 : 8;
        final double y = sneak ? 2 : 3;
        final double w = 16;
        final double h = sneak ? 24 : 26;
        final java.awt.geom.RoundRectangle2D body = new java.awt.geom.RoundRectangle2D.Double(x, y, w, h, 14, 14);
        g.setPaint(new Color(0xD8D8E2));
        g.fill(body);
        // 按的那一邊
        final java.awt.geom.Area button = new java.awt.geom.Area(body);
        button.intersect(new java.awt.geom.Area(new java.awt.geom.Rectangle2D.Double(left ? x : x + w / 2, y, w / 2, h * 0.42)));
        g.setPaint(new Color(0xF5B942));
        g.fill(button);
        g.setPaint(new Color(0x101018));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(body);
        g.draw(new java.awt.geom.Line2D.Double(x + w / 2, y, x + w / 2, y + h * 0.42));
        g.draw(new java.awt.geom.Line2D.Double(x, y + h * 0.42, x + w, y + h * 0.42));
        if (sneak) {
            // Shift：藍色圓角方塊裡一個往上的空心箭頭
            final java.awt.geom.RoundRectangle2D badge = new java.awt.geom.RoundRectangle2D.Double(17, 16, 14, 15, 5, 5);
            g.setPaint(new Color(0x3C9DFF));
            g.fill(badge);
            g.setPaint(new Color(0x101018));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(badge);
            final Path2D arrow = new Path2D.Double();
            arrow.moveTo(24, 18.5);
            arrow.lineTo(29, 24);
            arrow.lineTo(26.2, 24);
            arrow.lineTo(26.2, 28.5);
            arrow.lineTo(21.8, 28.5);
            arrow.lineTo(21.8, 24);
            arrow.lineTo(19, 24);
            arrow.closePath();
            g.setPaint(Color.WHITE);
            g.fill(arrow);
        }
        g.dispose();
        return image;
    }

    /**
     * 方向鍵圖示：深色圓角方塊裡一支粗的金色箭頭，指著要捲過去的方向（arrow_up／down／left／right）。
     * 四個長得一樣、只有方向不同，一眼就分得出來。
     */
    /** 3×5 的點陣字（T 與 0–9）：每一列 3 個位元，左邊是高位。 */
    private static final Map<Character, int[]> PIXEL_FONT = Map.ofEntries(
            Map.entry('T', new int[] {0b111, 0b010, 0b010, 0b010, 0b010}),
            Map.entry('0', new int[] {0b111, 0b101, 0b101, 0b101, 0b111}),
            Map.entry('1', new int[] {0b010, 0b110, 0b010, 0b010, 0b111}),
            Map.entry('2', new int[] {0b111, 0b001, 0b111, 0b100, 0b111}),
            Map.entry('3', new int[] {0b111, 0b001, 0b111, 0b001, 0b111}),
            Map.entry('4', new int[] {0b101, 0b101, 0b111, 0b001, 0b001}),
            Map.entry('5', new int[] {0b111, 0b100, 0b111, 0b001, 0b111}),
            Map.entry('6', new int[] {0b111, 0b100, 0b111, 0b101, 0b111}),
            Map.entry('7', new int[] {0b111, 0b001, 0b010, 0b010, 0b010}),
            Map.entry('8', new int[] {0b111, 0b101, 0b111, 0b101, 0b111}),
            Map.entry('9', new int[] {0b111, 0b101, 0b111, 0b001, 0b111}));

    /**
     * 地圖頁的階級頁籤：一張羊皮紙，中間寫「T 幾」（點陣字放大 2 倍，跟原版的像素風一致）。
     * 邊框照階級分色：T1–T5 白、T6–T10 黃、T11–T16 紅（跟地圖本身的階級顏色一樣）。
     */
    static BufferedImage mapTierIcon(final int tier) {
        final BufferedImage image = canvas();
        final Graphics2D g = image.createGraphics();
        final Color border = tier >= 11 ? new Color(0xC0392B) : tier >= 6 ? new Color(0xD4A72C) : new Color(0xE8E8E8);
        g.setColor(border);
        g.fillRect(2, 4, 28, 24);
        g.setColor(new Color(0xE9D8A6)); // 羊皮紙
        g.fillRect(4, 6, 24, 20);
        g.setColor(new Color(0xD2BC85));
        g.fillRect(4, 24, 24, 2); // 下緣陰影
        final String text = "T" + tier;
        final int scale = 2;
        final int width = text.length() * 3 * scale + (text.length() - 1) * scale;
        int x = (SIZE - width) / 2;
        final int y = (SIZE - 5 * scale) / 2;
        g.setColor(new Color(0x2B1D0E));
        for (final char c : text.toCharArray()) {
            final int[] rows = PIXEL_FONT.get(c);
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 3; col++) {
                    if ((rows[row] >> (2 - col) & 1) == 1) {
                        g.fillRect(x + col * scale, y + row * scale, scale, scale);
                    }
                }
            }
            x += 4 * scale;
        }
        g.dispose();
        return image;
    }

    static BufferedImage arrowIcon(final String name) {
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        final java.awt.geom.RoundRectangle2D key = new java.awt.geom.RoundRectangle2D.Double(1.5, 1.5, 29, 29, 9, 9);
        g.setPaint(new Color(0x262C40));
        g.fill(key);
        g.setPaint(new Color(0x8A93B8));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(key);
        // 往上的箭頭，再照方向轉
        final Path2D arrow = new Path2D.Double();
        arrow.moveTo(16, 4.5);
        arrow.lineTo(27, 16);
        arrow.lineTo(20.5, 16);
        arrow.lineTo(20.5, 27);
        arrow.lineTo(11.5, 27);
        arrow.lineTo(11.5, 16);
        arrow.lineTo(5, 16);
        arrow.closePath();
        final double turns = switch (name) {
            case "arrow_right" -> 1;
            case "arrow_down" -> 2;
            case "arrow_left" -> 3;
            default -> 0;
        };
        final java.awt.Shape shape = java.awt.geom.AffineTransform.getRotateInstance(turns * Math.PI / 2, 16, 16).createTransformedShape(arrow);
        g.setPaint(new Color(0xF5B942));
        g.fill(shape);
        g.setPaint(new Color(0x101018));
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(shape);
        g.dispose();
        return image;
    }

    /**
     * 選單統一版面的功能鍵圖示：跟方向鍵一樣的深色圓角方塊，裡面是返回（往左回轉的箭頭）、上一頁◀、下一頁▶，
     * 說明是藍色圓形裡一個白色問號。
     */
    /**
     * Boss 血條（2026-10）：紅色、紫色的底與血量換成有立體感的樣式（上亮下暗、深色外框），
     * 12 格的刻度（NOTCHED_12，Boss 用的）換成 75%、50%、25% 三條（地圖 Boss 換階段的位置）。原版圖都是 182×5。
     */
    static void bossBars(final Map<String, byte[]> files) throws IOException {
        final String dir = "assets/minecraft/textures/gui/sprites/boss_bar/";
        files.put(dir + "red_background.png", png(barImage(0x3a0a0e, 0x1a0406, false)));
        files.put(dir + "red_progress.png", png(barImage(0xff4a3a, 0xa8141e, true)));
        files.put(dir + "purple_background.png", png(barImage(0x2a1040, 0x120620, false)));
        files.put(dir + "purple_progress.png", png(barImage(0xd07aff, 0x6a28b0, true)));
        files.put(dir + "notched_12_background.png", png(barNotches(0xff0a0204)));
        files.put(dir + "notched_12_progress.png", png(barNotches(0xffffe08a)));
    }

    /** 一條 182×5：progress = 血量（左亮右深的漸層＋上面一排高光）；不是的話是底（暗色＋外框）。 */
    static BufferedImage barImage(final int light, final int dark, final boolean progress) {
        final BufferedImage image = new BufferedImage(182, 5, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 182; x++) {
            final double t = x / 181.0;
            for (int y = 0; y < 5; y++) {
                int rgb;
                if (progress) {
                    final int base = mix(light, dark, t * 0.55);
                    rgb = y == 0 ? mix(base, 0xffffff, 0.35) : y == 1 ? mix(base, 0xffffff, 0.12) : y == 4 ? mix(base, 0x000000, 0.35) : base;
                } else {
                    rgb = y == 0 || y == 4 || x == 0 || x == 181 ? 0x050203 : mix(light, dark, y / 4.0);
                }
                image.setRGB(x, y, 0xff000000 | rgb);
            }
        }
        return image;
    }

    /** 刻度：在 25%、50%、75% 的位置各一條（其他透明）。 */
    static BufferedImage barNotches(final int argb) {
        final BufferedImage image = new BufferedImage(182, 5, BufferedImage.TYPE_INT_ARGB);
        for (final int x : new int[] {45, 90, 136}) {
            for (int y = 0; y < 5; y++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static int mix(final int a, final int b, final double t) {
        final double k = Math.max(0, Math.min(1, t));
        final int r = (int) Math.round(((a >> 16) & 0xff) * (1 - k) + ((b >> 16) & 0xff) * k);
        final int g = (int) Math.round(((a >> 8) & 0xff) * (1 - k) + ((b >> 8) & 0xff) * k);
        final int bl = (int) Math.round((a & 0xff) * (1 - k) + (b & 0xff) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /** 社群按鈕的通用圖示：藍紫色的對話框，裡面三個點（不是任何品牌的標誌）。 */
    static BufferedImage communityIcon() {
        final String[] rows = {
                "................",
                "................",
                "...##########...",
                "..############..",
                ".##############.",
                ".##############.",
                ".###..##..##..#.",
                ".###..##..##..#.",
                ".##############.",
                ".##############.",
                "..############..",
                "...#########....",
                "....###.........",
                "....##..........",
                "....#...........",
                "................"};
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                final char c = rows[y].charAt(x);
                if (c == '#') {
                    image.setRGB(x, y, y < 8 ? 0xFF7A85F0 : 0xFF5865F2);
                } else if (c == '.' && y >= 6 && y <= 7 && x >= 4 && x <= 13) {
                    image.setRGB(x, y, 0xFFFFFFFF); // 對話框裡的三個點
                }
            }
        }
        return image;
    }

    static BufferedImage navIcon(final String name) {
        final BufferedImage image = canvas();
        final Graphics2D g = smooth(image);
        if (name.equals("help")) {
            final Ellipse2D circle = new Ellipse2D.Double(2, 2, 28, 28);
            g.setPaint(new Color(0x3C7DFF));
            g.fill(circle);
            g.setPaint(new Color(0x101018));
            g.setStroke(new BasicStroke(1.6f));
            g.draw(circle);
            // 問號：上面的弧、往下的一筆、底下一點
            g.setPaint(Color.WHITE);
            g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            final Path2D hook = new Path2D.Double();
            hook.moveTo(11.5, 12.5);
            hook.curveTo(11.5, 7.5, 20.5, 7.5, 20.5, 12.5);
            hook.curveTo(20.5, 16.0, 16.0, 16.0, 16.0, 19.5);
            g.draw(hook);
            g.fill(new Ellipse2D.Double(14.0, 22.3, 4.0, 4.0));
            g.dispose();
            return image;
        }
        final java.awt.geom.RoundRectangle2D key = new java.awt.geom.RoundRectangle2D.Double(1.5, 1.5, 29, 29, 9, 9);
        g.setPaint(new Color(0x262C40));
        g.fill(key);
        g.setPaint(new Color(0x8A93B8));
        g.setStroke(new BasicStroke(1.6f));
        g.draw(key);
        final java.awt.Shape shape;
        if (name.equals("nav_back")) {
            // 往左回轉的箭頭：右邊一段弧，下面的箭頭往左指
            final Path2D arc = new Path2D.Double();
            arc.moveTo(9, 8.5);
            arc.lineTo(18.5, 8.5);
            arc.curveTo(28, 8.5, 28, 22.5, 18.5, 22.5);
            arc.lineTo(12, 22.5);
            g.setPaint(new Color(0x101018));
            g.setStroke(new BasicStroke(6.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(arc);
            g.setPaint(new Color(0xF5B942));
            g.setStroke(new BasicStroke(3.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(arc);
            final Path2D head = new Path2D.Double();
            head.moveTo(3.5, 22.5);
            head.lineTo(13, 15);
            head.lineTo(13, 30);
            head.closePath();
            shape = head;
        } else {
            final Path2D triangle = new Path2D.Double();
            triangle.moveTo(10, 7);
            triangle.lineTo(24, 16);
            triangle.lineTo(10, 25);
            triangle.closePath();
            shape = name.equals("nav_prev")
                    ? java.awt.geom.AffineTransform.getRotateInstance(Math.PI, 16, 16).createTransformedShape(triangle) : triangle;
        }
        g.setPaint(new Color(0xF5B942));
        g.fill(shape);
        g.setPaint(new Color(0x101018));
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(shape);
        g.dispose();
        return image;
    }

    /** 資源包清單上的小圖：星空裡一顆金色的星。 */
    /** 角色面板空著的裝備欄畫的剪影（2026-10，16×16，# = 有顏色）。 */
    private static final Map<String, String[]> SLOT_SHAPES = Map.ofEntries(
            Map.entry("slot_main", new String[] {
                    "..............##",
                    ".............###",
                    "............###.",
                    "...........###..",
                    "..........###...",
                    ".........###....",
                    "........###.....",
                    ".......###......",
                    "..#...###.......",
                    "..##.###........",
                    "...####.........",
                    "....##..........",
                    "...####.........",
                    "..##..##........",
                    ".##.............",
                    "##.............."}),
            Map.entry("slot_off", new String[] {
                    "................",
                    "..############..",
                    "..############..",
                    "..############..",
                    "..############..",
                    "..############..",
                    "..############..",
                    "..############..",
                    "...##########...",
                    "...##########...",
                    "....########....",
                    ".....######.....",
                    "......####......",
                    ".......##.......",
                    "................",
                    "................"}),
            Map.entry("slot_head", new String[] {
                    "................",
                    "................",
                    "....########....",
                    "...##########...",
                    "..############..",
                    "..############..",
                    "..####....####..",
                    "..###......###..",
                    "..###......###..",
                    "..###......###..",
                    "................",
                    "................",
                    "................",
                    "................",
                    "................",
                    "................"}),
            Map.entry("slot_chest", new String[] {
                    "................",
                    "..####....####..",
                    ".######..######.",
                    ".##############.",
                    ".##############.",
                    "..############..",
                    "...##########...",
                    "...##########...",
                    "...##########...",
                    "...##########...",
                    "...##########...",
                    "...##########...",
                    "...##########...",
                    "................",
                    "................",
                    "................"}),
            Map.entry("slot_legs", new String[] {
                    "................",
                    "...##########...",
                    "...##########...",
                    "...##########...",
                    "...####..####...",
                    "...####..####...",
                    "...####..####...",
                    "...####..####...",
                    "...####..####...",
                    "...####..####...",
                    "...####..####...",
                    "...####..####...",
                    "................",
                    "................",
                    "................",
                    "................"}),
            Map.entry("slot_feet", new String[] {
                    "................",
                    "................",
                    "................",
                    "................",
                    "................",
                    "................",
                    "...###....###...",
                    "...###....###...",
                    "...###....###...",
                    "...###....###...",
                    "..####...####...",
                    ".#####..#####...",
                    ".#####..#####...",
                    "................",
                    "................",
                    "................"}),
            Map.entry("slot_ring", new String[] {
                    "................",
                    "......####......",
                    ".....######.....",
                    "......####......",
                    "....##....##....",
                    "...#........#...",
                    "..#..........#..",
                    "..#..........#..",
                    "..#..........#..",
                    "..#..........#..",
                    "...#........#...",
                    "....##....##....",
                    "......####......",
                    "................",
                    "................",
                    "................"}),
            Map.entry("slot_amulet", new String[] {
                    "..#..........#..",
                    "...#........#...",
                    "....#......#....",
                    ".....#....#.....",
                    "......#..#......",
                    ".......##.......",
                    "......####......",
                    ".....######.....",
                    "....########....",
                    "....########....",
                    ".....######.....",
                    "......####......",
                    ".......##.......",
                    "................",
                    "................",
                    "................"}),
            Map.entry("slot_belt", new String[] {
                    "................",
                    "................",
                    "................",
                    "................",
                    "................",
                    "################",
                    "################",
                    "#####....#######",
                    "#####.##.#######",
                    "#####....#######",
                    "################",
                    "................",
                    "................",
                    "................",
                    "................",
                    "................"}),
            Map.entry("slot_charm", new String[] {
                    ".......##.......",
                    "......#..#......",
                    "......#..#......",
                    ".......##.......",
                    ".....######.....",
                    "....########....",
                    "...##########...",
                    "...###.##.###...",
                    "...##.####.##...",
                    "...###.##.###...",
                    "....########....",
                    ".....######.....",
                    "......####......",
                    ".......##.......",
                    "................",
                    "................"}),
            Map.entry("slot_skill", new String[] {
                    "................",
                    "................",
                    "....########....",
                    "...##########...",
                    "..############..",
                    ".##############.",
                    ".##############.",
                    "..############..",
                    "...##########...",
                    "....########....",
                    ".....######.....",
                    "......####......",
                    ".......##.......",
                    "................",
                    "................",
                    "................"}),
            Map.entry("slot_support", new String[] {
                    "................",
                    "................",
                    "................",
                    ".....######.....",
                    "....########....",
                    "...###....###...",
                    "...##......##...",
                    "...##..##..##...",
                    "...##..##..##...",
                    "...##......##...",
                    "...###....###...",
                    "....########....",
                    ".....######.....",
                    "................",
                    "................",
                    "................"}),
            Map.entry("slot_character", new String[] {
                    "................",
                    "......####......",
                    ".....######.....",
                    ".....######.....",
                    ".....######.....",
                    "......####......",
                    "................",
                    "....########....",
                    "...##########...",
                    "..############..",
                    "..############..",
                    "..############..",
                    "..############..",
                    "..############..",
                    "................",
                    "................"}));

    /** 還沒開的欄位（_locked）右下角的鎖（6×7）。 */
    private static final String[] LOCK_SHAPE = {
            ".####.",
            "#....#",
            "#....#",
            "######",
            "##..##",
            "######",
            "######"};

    /** 空裝備欄的剪影：跟原版空防具欄一樣淡淡的灰色。 */
    static BufferedImage slotIcon(final String name) {
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        // 2026-10：「_locked」= 還沒開的欄位：同一個剪影畫暗一點，右下角加一個鎖
        final boolean locked = name.endsWith("_locked");
        final String[] rows = SLOT_SHAPES.get(locked ? name.substring(0, name.length() - "_locked".length()) : name);
        final int color = locked ? 0xFF4E4E4E : 0xFF6E6E6E; // 物品貼圖半透明會被裁掉：用不透明、比格子底色（#8B8B8B）深一點的灰
        for (int y = 0; y < 16 && rows != null; y++) {
            for (int x = 0; x < 16; x++) {
                if (rows[y].charAt(x) == '#') {
                    image.setRGB(x, y, color);
                }
            }
        }
        if (locked) {
            for (int y = 0; y < LOCK_SHAPE.length; y++) {
                for (int x = 0; x < LOCK_SHAPE[y].length(); x++) {
                    if (LOCK_SHAPE[y].charAt(x) == '#') {
                        image.setRGB(10 + x, 9 + y, 0xFFC8C8C8);
                    }
                }
            }
        }
        return image;
    }

    private static BufferedImage icon() {
        final BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = smooth(image);
        g.setPaint(new Color(0x0A0D1A));
        g.fillRect(0, 0, 64, 64);
        g.setPaint(new RadialGradientPaint(32f, 32f, 30f, new float[] {0f, 1f},
                new Color[] {new Color(245, 185, 66, 120), new Color(245, 185, 66, 0)}));
        g.fillRect(0, 0, 64, 64);
        final Path2D star = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            final double radius = i % 2 == 0 ? 26 : 11;
            final double angle = -Math.PI / 2 + i * Math.PI / 5;
            final double x = 32 + Math.cos(angle) * radius;
            final double y = 33 + Math.sin(angle) * radius;
            if (i == 0) {
                star.moveTo(x, y);
            } else {
                star.lineTo(x, y);
            }
        }
        star.closePath();
        g.setPaint(new Color(0xF5B942));
        g.fill(star);
        g.dispose();
        return image;
    }

    // ---- 打包 ----

    /**
     * 詞綴標籤（2026-10）：web/chips/*.png（tools/gen_affix_chips.py 畫的）放進 minecraft:default 字型的私用字元，
     * lang 檔把 exilecore.chip.代號 翻成那個字元（物品說明用 lang_or，沒套資源包的人看到備用文字）。
     */
    private static void affixChips(final Map<String, byte[]> files) {
        final JsonArray providers = new JsonArray();
        final JsonObject lang = new JsonObject();
        for (final tw.exilecore.item.AffixChips.Chip chip : tw.exilecore.item.AffixChips.Chip.values()) {
            final byte[] png;
            try (java.io.InputStream in = UiPack.class.getResourceAsStream("/web/chips/" + chip.id() + ".png")) {
                if (in == null) {
                    continue;
                }
                png = in.readAllBytes();
            } catch (final java.io.IOException e) {
                continue;
            }
            files.put("assets/exilecore/textures/font/chip/" + chip.id() + ".png", png);
            final JsonObject bitmap = new JsonObject();
            bitmap.addProperty("type", "bitmap");
            bitmap.addProperty("file", "exilecore:font/chip/" + chip.id() + ".png");
            bitmap.addProperty("ascent", 7);
            bitmap.addProperty("height", 8);
            final JsonArray chars = new JsonArray();
            chars.add(String.valueOf(chip.glyph()));
            bitmap.add("chars", chars);
            providers.add(bitmap);
            lang.addProperty(chip.key(), String.valueOf(chip.glyph()));
        }
        if (providers.isEmpty()) {
            return;
        }
        final JsonObject font = new JsonObject();
        font.add("providers", providers);
        files.put("assets/minecraft/font/default.json", json(font));
        for (final String code : new String[] {"en_us", "zh_tw", "zh_cn", "zh_hk"}) {
            files.put("assets/exilecore/lang/" + code + ".json", json(lang));
        }
    }

    private static byte[] json(final JsonObject object) {
        return GSON.toJson(object).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] png(final BufferedImage image) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("沒有 PNG 編碼器");
        }
        return out.toByteArray();
    }

    /**
     * 把別的插件產生的資源包檔案併進來（2026-10，BetterModel）：pack.png 不要；pack.mcmeta 只把它的 overlays 加進我們的；
     * 同一個檔案兩邊都有時留我們的（記在 conflicts）。
     */
    public static Built merge(final Built base, final Map<String, Map<String, byte[]>> extras, final List<String> conflicts) throws IOException {
        if (extras.isEmpty()) {
            return base;
        }
        final Map<String, byte[]> files = new TreeMap<>();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(base.zip()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        final JsonObject meta = com.google.gson.JsonParser.parseString(new String(files.get("pack.mcmeta"), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (final Map.Entry<String, Map<String, byte[]>> source : extras.entrySet()) {
            for (final Map.Entry<String, byte[]> file : source.getValue().entrySet()) {
                final String path = file.getKey();
                if (path.equals("pack.png")) {
                    continue;
                }
                if (path.equals("pack.mcmeta")) {
                    try {
                        final JsonObject theirs = com.google.gson.JsonParser.parseString(new String(file.getValue(),
                                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        if (theirs.has("overlays") && theirs.getAsJsonObject("overlays").has("entries")) {
                            final JsonObject overlays = meta.has("overlays") ? meta.getAsJsonObject("overlays") : new JsonObject();
                            final JsonArray entries = overlays.has("entries") ? overlays.getAsJsonArray("entries") : new JsonArray();
                            entries.addAll(theirs.getAsJsonObject("overlays").getAsJsonArray("entries"));
                            overlays.add("entries", entries);
                            meta.add("overlays", overlays);
                        }
                    } catch (final RuntimeException e) {
                        conflicts.add(source.getKey() + ":pack.mcmeta 讀不懂（" + e.getMessage() + "）");
                    }
                    continue;
                }
                if (files.containsKey(path)) {
                    conflicts.add(source.getKey() + ":" + path);
                    continue;
                }
                files.put(path, file.getValue());
            }
        }
        files.put("pack.mcmeta", json(meta));
        return zip(files);
    }

    /** 檔名照字母排序、時間固定：內容一樣，zip 就一模一樣。 */
    private static Built zip(final Map<String, byte[]> files) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (final Map.Entry<String, byte[]> file : files.entrySet()) {
                final ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTimeLocal(LocalDateTime.of(2026, 1, 1, 0, 0));
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        final byte[] data = bytes.toByteArray();
        return new Built(data, sha1(data), files.size());
    }

    static String sha1(final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
