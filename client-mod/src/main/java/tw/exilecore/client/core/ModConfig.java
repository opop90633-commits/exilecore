package tw.exilecore.client.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 設定檔 config/exilecore_client.json（玩家可以直接改；遊戲裡按「切換 HUD」的鍵也會寫回去）。純 Java（測試用）。
 * 位置都是「相對錨點的偏移」，單位是 GUI 像素；scale 是整個 HUD 的縮放。
 */
public final class ModConfig {

    /** 全部 HUD（技能列、增益、目標框、生命護盾）。 */
    public boolean hudEnabled = true;
    public boolean showSkillBar = true;
    public boolean showBuffs = true;
    public boolean showTarget = true;
    /** 用模組畫的生命／能量護盾條取代原版的愛心（只在連上星骸伺服器時）。 */
    public boolean replaceHearts = true;
    /**
     * 2026-11-02 照站長：生命、魔力畫成快捷欄兩側的球（PoE 風格；能量護盾是生命球外面一圈），原版的飢餓條也藏起來。
     * false = 原本的樣子（愛心位置的生命條＋護盾條）。遊戲裡按 F9 也會改這個。
     */
    public boolean orbs = true;
    /** 技能格下面寫綁的按鍵。 */
    public boolean showKeyLabels = true;
    public float scale = 1.0f;
    /** 特效改由模組繪製（1.1）：伺服器不送技能／範圍的粒子，模組自己畫（地上的圈、預警閃紅）。關掉 = 照舊收伺服器的粒子。 */
    public boolean drawEffects = true;

    /** 技能列：畫面下方正中間為錨點；預設在快捷欄右邊。 */
    public int skillBarX = 100;
    public int skillBarY = -23;
    /** 增益列：畫面下方正中間為錨點、往左長；預設在快捷欄左邊（副手格再左）。 */
    public int buffsX = -122;
    public int buffsY = -12;
    /** 目標框：畫面上方正中間為錨點。 */
    public int targetX = 0;
    public int targetY = 32;

    /**
     * 2026-11-02 照站長（1.4）：提示列（伺服器的提示疊在動作列上方、會淡出；關掉 = 照原版動作列）、側邊面板（關掉 = 照原版記分板）、
     * 魔力球旁的戰鬥資源（關掉 = 照原版 Boss 血條）、生命球旁的異常與守護（關掉 = 照原版動作列）、經驗條上的百分比與每小時經驗、
     * 光環／召喚物／撿到的東西（光環併進增益列、其他是面板；關掉 = 照原版動作列）。後四個只在有球的時候。
     */
    public boolean showNotices = true;
    public boolean showPanels = true;
    public boolean showResources = true;
    public boolean showStatus = true;
    public boolean showXp = true;
    public boolean showSides = true;
    /** 側邊面板全部收起來（只剩標題；遊戲裡按「收起／展開側邊面板」，預設 F10，也會改這個）。 */
    public boolean panelsCollapsed = false;
    /** 這些面板收起來（combat、status、minions、guide、quest、drops…；收起的只剩標題）。 */
    public java.util.List<String> collapsedPanels = new java.util.ArrayList<>();
    /** 2026-11-02 照站長：這些面板整個不畫（設定畫面「面板：任務」那些開關；標題也不畫）。這次之前只能收起、留著標題。 */
    public java.util.List<String> hiddenPanels = new java.util.ArrayList<>();
    /** 側邊面板群的位置：x 是往左移幾格（畫面右邊為準）、y 是往下移幾格。 */
    public int panelsX = 0;
    public int panelsY = 0;

    // ---- 2026-11-02 照站長（C 組）：預警、小地圖、死亡回顧、說明欄、設定畫面；這次之前只能改 json 檔 ----

    /** 模組畫 Boss 預警與換階段的機制清單（伺服器就不送那些大標題）。 */
    public boolean showAlerts = true;
    /** 預警那一排的中心：畫面上方正中間往下幾格（Boss 血條下面）。 */
    public int alertsY = 44;
    /** 小地圖（右上角最上面；B 組的地圖資訊框接在它下面，HudDrag.bottomOf("minimap")）。 */
    public boolean showMinimap = true;
    /** 小地圖的邊長（GUI 像素）。 */
    public int minimapSize = 72;
    /** 小地圖離右上角幾格（右邊、上面）。 */
    public int minimapMarginX = 4;
    public int minimapMarginY = 4;
    /** 死亡回顧面板（伺服器就不印在聊天欄）。 */
    public boolean showDeathPanel = true;
    /** 裝備、寶石說明平常顯示精簡版（Shift 完整、Alt 詞綴階級）；false = 一直是完整的。 */
    public boolean compactTooltips = true;
    /** 可以拖動的 HUD 元件被拖過的偏移（代號 → {dx, dy}，GUI 像素；HudDrag）。 */
    public java.util.Map<String, int[]> layout = new java.util.LinkedHashMap<>();

    /** 自動更新（關掉就只提示有新版，不下載）。 */
    public boolean autoUpdate = true;
    /** 自己指定的更新說明檔網址（空白 = 用伺服器告訴我們的）。 */
    public String manifestUrl = "";
    /** 上次伺服器告訴我們的更新說明檔網址（下次開遊戲時先檢查一次）。 */
    public String lastManifestUrl = "";
    /** 下載好、等著換上的新版（版本與 SHA-256；開遊戲時用來確認 mods 裡的 .pending 是我們下載的）。 */
    public String updatePendingVersion = "";
    public String updatePendingSha256 = "";

    // ---- 2026-11-02 照站長（1.4 B 組：世界中與地圖）；這次之前這些都是伺服器畫的（或沒有） ----
    /** 地圖資訊框（右上角；地圖名稱、詞綴、剩幾門、清除比例、Boss、事件）。關掉 = 伺服器照舊用血條、動作列寫事件進度。 */
    public boolean mapPanel = true;
    /** 地圖資訊框收起來（只剩名稱與一行摘要）；按 F6 切換。 */
    public boolean mapPanelCollapsed = false;
    /** 地圖資訊框：畫面右上角為錨點的偏移（GUI 像素；x 往左是負的）。 */
    public int mapPanelX = -4;
    public int mapPanelY = 4;
    /** 傷害數字改由模組畫（沒有數量上限、平滑往上飄、暴擊放大、淡出）。關掉 = 伺服器照舊生文字顯示實體。 */
    public boolean damageNumbers = true;
    /** 同一個目標短時間內的數字合併成一個。 */
    public boolean mergeDamageNumbers = false;
    /** 受到的傷害畫在哪裡：edge = 畫面邊緣（準心左下）、world = 自己眼前（跟伺服器以前一樣）、off = 不畫。 */
    public String takenNumbers = "edge";
    public float damageNumberScale = 1.0f;
    /** 怪物頭上的血條（顏色照稀有度、護盾、無敵、狀態小圖示）；原版名牌只在準心指著或很近時顯示。 */
    public boolean mobBars = true;
    /** 普通怪滿血時也畫血條（預設不畫，畫面比較乾淨）。 */
    public boolean mobBarsFullLife = false;
    /**
     * 2026-11-02 照站長（D 組）：原生天賦樹、星圖天賦左鍵整條配上時，一次配到這麼多點（含）以上先跳確認，避免手滑；0 = 不確認。
     * 這次之前（1.4 第一版）一律直接配。
     */
    public int confirmPathPoints = 5;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 讀設定檔；沒有或寫壞了用預設值（壞的檔案改名成 .broken 留著）。 */
    public static ModConfig load(final Path file) {
        if (!Files.isRegularFile(file)) {
            return new ModConfig();
        }
        try {
            final ModConfig config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ModConfig.class);
            return config == null ? new ModConfig() : config.sanitized();
        } catch (final IOException | JsonParseException e) {
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException ignored) {
                // 搬不動就算了，下次存的時候蓋掉
            }
            return new ModConfig();
        }
    }

    public void save(final Path file) throws IOException {
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 夾住不合理的值。 */
    ModConfig sanitized() {
        if (!(scale >= 0.5f && scale <= 3f)) {
            scale = 1f;
        }
        if (manifestUrl == null) {
            manifestUrl = "";
        }
        if (lastManifestUrl == null) {
            lastManifestUrl = "";
        }
        if (collapsedPanels == null) {
            collapsedPanels = new java.util.ArrayList<>();
        }
        panelsX = Math.max(0, Math.min(400, panelsX));
        panelsY = Math.max(-30, Math.min(400, panelsY));
        if (!"edge".equals(takenNumbers) && !"world".equals(takenNumbers) && !"off".equals(takenNumbers)) {
            takenNumbers = "edge";
        }
        if (!(damageNumberScale >= 0.5f && damageNumberScale <= 3f)) {
            damageNumberScale = 1f;
        }
        if (layout == null) {
            layout = new java.util.LinkedHashMap<>();
        }
        layout.values().removeIf(value -> value == null || value.length != 2);
        minimapSize = Math.max(40, Math.min(160, minimapSize));
        minimapMarginX = Math.max(0, Math.min(400, minimapMarginX));
        minimapMarginY = Math.max(0, Math.min(400, minimapMarginY));
        alertsY = Math.max(0, Math.min(300, alertsY));
        confirmPathPoints = Math.max(0, Math.min(999, confirmPathPoints));
        return this;
    }

    /**
     * 2026-11-02（C 組）：設定畫面的「重設預設值」：開關、縮放、位置都回到預設（自動更新的設定、下載好的新版留著）。
     */
    public void resetDisplay() {
        // 用反射把每個欄位換回預設值：A、B 組（與之後）加的開關、位置也一起回去，不用每次記得來這裡補
        final ModConfig fresh = new ModConfig();
        for (final java.lang.reflect.Field field : ModConfig.class.getDeclaredFields()) {
            final int modifiers = field.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(modifiers) || !java.lang.reflect.Modifier.isPublic(modifiers) || KEEP.contains(field.getName())) {
                continue;
            }
            try {
                final Object value = field.get(fresh);
                field.set(this, value instanceof java.util.Map<?, ?> map ? new java.util.LinkedHashMap<>(map)
                        : value instanceof java.util.List<?> list ? new java.util.ArrayList<>(list) : value);
            } catch (final IllegalAccessException e) {
                // 公開欄位一定讀得到
            }
        }
    }

    /** 重設預設值時不動的（自動更新、下載好的新版）。 */
    private static final java.util.Set<String> KEEP = java.util.Set.of("autoUpdate", "manifestUrl", "lastManifestUrl", "updatePendingVersion",
            "updatePendingSha256");

    public String pendingVersion() {
        return updatePendingVersion == null || updatePendingVersion.isBlank() ? null : updatePendingVersion;
    }

    public String pendingSha256() {
        return updatePendingSha256 == null || updatePendingSha256.isBlank() ? null : updatePendingSha256;
    }

    public void setPending(final String version, final String sha256) {
        updatePendingVersion = version == null ? "" : version;
        updatePendingSha256 = sha256 == null ? "" : sha256;
    }

    /** 要用的更新說明檔網址：自己指定的優先，不然上次伺服器給的。 */
    public String effectiveManifestUrl() {
        return manifestUrl != null && !manifestUrl.isBlank() ? manifestUrl.trim() : lastManifestUrl == null ? "" : lastManifestUrl.trim();
    }
}
