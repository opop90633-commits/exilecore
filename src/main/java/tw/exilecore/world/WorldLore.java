package tw.exilecore.world;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * data/world.yml：伺服器名稱、伺服器列表說明（MOTD）、新角色登場的標題與旁白。
 * 文字都是 MiniMessage。純資料，不依賴 Bukkit。
 */
public final class WorldLore {

    private static final String DEFAULT_NAME = "星骸";

    private String name = DEFAULT_NAME;
    private List<String> motd = List.of();
    private String arrivalTitle = "";
    private String arrivalSubtitle = "";
    private List<String> arrivalLines = List.of();
    private boolean campaignEnabled = true;
    private boolean campaignAutoSetup = true;
    private long campaignTime = 4000;
    /** 2026-10-22 主城分流：營地分幾條線（1 = 不分流）。 */
    private int campaignChannels = 1;
    /** 2026-10-22：角色等級到這裡的玩家派到第 2 線起（第 1 線留給低等級）；0 = 關掉。 */
    private int campaignHighLevel = 70;
    /** 2026-10-22：每次啟動都從劇情世界重新複製分流世界的營地。 */
    private boolean campaignRefreshOnStart = true;
    private long mapTime = 6000;
    /** 2026-10-22 效能：每個世界的視距（區塊）；0 = 照 server.properties。 */
    private int viewDistance = DEFAULT_VIEW_DISTANCE;
    /** 2026-10-22 效能：每個世界的模擬距離（區塊）；0 = 照 server.properties。 */
    private int simulationDistance = DEFAULT_SIMULATION_DISTANCE;

    /** 預設視距：虛空地圖、營地都不大，7 就看得到整片場地（spark：區塊系統兩成多跟「人數 × 載入的區塊」成正比）。 */
    public static final int DEFAULT_VIEW_DISTANCE = 7;
    /** 預設模擬距離：怪物本來就只在 24 格內活動（entity-activation-range），4 區塊（64 格）綽綽有餘。 */
    public static final int DEFAULT_SIMULATION_DISTANCE = 4;

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("world.yml");
        name = DEFAULT_NAME;
        motd = List.of();
        arrivalTitle = "";
        arrivalSubtitle = "";
        arrivalLines = List.of();
        campaignEnabled = true;
        campaignAutoSetup = true;
        campaignTime = 4000;
        campaignChannels = 1;
        campaignHighLevel = 70;
        campaignRefreshOnStart = true;
        mapTime = 6000;
        viewDistance = DEFAULT_VIEW_DISTANCE;
        simulationDistance = DEFAULT_SIMULATION_DISTANCE;
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);
        name = root.string("name", DEFAULT_NAME);
        motd = List.copyOf(root.strings("motd"));
        final YamlNode arrival = root.node("arrival");
        arrivalTitle = arrival.string("title", "");
        arrivalSubtitle = arrival.string("subtitle", "");
        arrivalLines = List.copyOf(arrival.strings("lines"));
        final YamlNode campaign = root.node("campaign");
        campaignEnabled = campaign.bool("enabled", true);
        campaignAutoSetup = campaign.bool("auto_setup", true);
        campaignTime = campaign.integer("time", 4000);
        campaignChannels = Math.max(1, Math.min(TownChannels.MAX_CHANNELS, campaign.integer("channels", 1)));
        campaignHighLevel = Math.max(0, campaign.integer("high_level", 70));
        campaignRefreshOnStart = campaign.bool("refresh_on_start", true);
        mapTime = root.node("maps").integer("time", 6000);
        final YamlNode performance = root.node("performance");
        viewDistance = distance(performance.integer("view_distance", DEFAULT_VIEW_DISTANCE));
        simulationDistance = distance(performance.integer("simulation_distance", DEFAULT_SIMULATION_DISTANCE));
    }

    /** 0 = 不改；其他夾在原版允許的 2–32。 */
    static int distance(final int value) {
        return value <= 0 ? 0 : Math.max(2, Math.min(32, value));
    }

    /** 2026-10-22 效能：每個世界的視距（區塊）；0 = 照 server.properties。 */
    public int viewDistance() {
        return viewDistance;
    }

    /** 2026-10-22 效能：每個世界的模擬距離（區塊）；0 = 照 server.properties。 */
    public int simulationDistance() {
        return simulationDistance;
    }

    /** 要不要產生、載入劇情世界（海邊營地＋五個區域）。 */
    public boolean campaignEnabled() {
        return campaignEnabled;
    }

    /** 第一次產生劇情世界時自動擺好區域版面與營地 NPC。 */
    public boolean campaignAutoSetup() {
        return campaignAutoSetup;
    }

    /** 劇情世界固定的時間（−1 = 照常日夜循環）。 */
    public long campaignTime() {
        return campaignTime;
    }

    /** 主城分流：營地分幾條線（1 = 不分流）。 */
    public int campaignChannels() {
        return campaignChannels;
    }

    /** 2026-10-22：角色等級到這裡的玩家派到第 2 線起（第 1 線留給低等級）；0 = 關掉。 */
    public int campaignHighLevel() {
        return campaignHighLevel;
    }

    /** 2026-10-22：每次啟動都把分流世界刪掉、從劇情世界重新複製營地（主城改了重開就同步）。 */
    public boolean campaignRefreshOnStart() {
        return campaignRefreshOnStart;
    }

    /** 地圖世界固定的時間（−1 = 照常日夜循環）。 */
    public long mapTime() {
        return mapTime;
    }

    /** 伺服器名稱（純文字）。 */
    public String name() {
        return name;
    }

    /** 伺服器列表的說明，最多兩行（空的表示不改原版的 motd）。 */
    public List<String> motd() {
        return motd.size() > 2 ? motd.subList(0, 2) : motd;
    }

    public String arrivalTitle() {
        return arrivalTitle;
    }

    public String arrivalSubtitle() {
        return arrivalSubtitle;
    }

    public List<String> arrivalLines() {
        return arrivalLines;
    }
}
