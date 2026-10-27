package tw.exilecore.map;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 一個開著的地圖實例：哪張圖、在地圖世界的哪個格子、誰開的（他和隊友走得進去）、還能進幾次、誰在裡面、怪物群刷了沒、Boss 的狀態。
 * 只在主執行緒用。
 */
public final class MapInstance {

    public enum State { BUILDING, OPEN, CLOSING }

    private final int id;
    /** 幻霧的難度（0 = 沒有）：之後刷出來的怪生命與傷害乘 (1 + 它)；由 DeliriumEvents 每 10 tick 更新。 */
    private double delirium;
    /** 每個實例獨一無二的代號（編號重開機會從 1 開始，格子也會重用）。 */
    private final UUID token = UUID.randomUUID();
    private final MapConfig.MapDef map;
    private final MapStats stats;
    private final MapLayout layout;
    private final World world;
    private final int cell;
    private final int originX;
    private final int originZ;
    private final UUID owner;
    private final String ownerName;
    private final long createdTick;
    private State state = State.BUILDING;
    private int portalsLeft;
    // 2026-10-18：開圖時的門數（關服退費要知道門用過了沒）
    private final int portalsTotal;
    private long lastOccupiedTick;
    private long emptySinceTick = -1;
    /** 在裡面的玩家（最後一次檢查時）。 */
    private final Set<UUID> inside = new HashSet<>();
    /**
     * 終局以上的 Boss（2026-10-15）：Boss 生命還在 {@link #LATE_ENTRY_LIFE} 以上時在這張圖裡的人；
     * 生命掉到那以下才進來的人，打死時沒有掉落（防殘血才進來撿）。Boss 重置時清掉。
     */
    private final Set<UUID> fightRoster = new HashSet<>();
    /** 已經提醒過「來晚了」的人（每場一次）。 */
    private final Set<UUID> lateWarned = new HashSet<>();
    /** Boss 生命低於這個比例之後才進來的人沒有掉落。 */
    public static final double LATE_ENTRY_LIFE = 0.75;
    /** 進來過的玩家（完成時一起算）。 */
    private final Set<UUID> visitors = new LinkedHashSet<>();
    /** 每個怪物群有沒有刷過（索引對 {@link MapLayout#packSpots()}）。 */
    private final boolean[] packSpawned;
    /** 每個怪物群刷出來、還活著的怪物。 */
    private final List<List<LivingEntity>> packs = new ArrayList<>();
    /** 別的模組加進來的怪（劫盜封鎖時的增援）：關閉時一起移除。 */
    private final List<LivingEntity> extras = new ArrayList<>();
    private int kills;
    private LivingEntity boss;
    private BossBar bossBar;
    private int bossPhase;
    /** 上一次換階段的 tick（{@link PhaseStep}：階段之間至少隔一下）。 */
    private long phaseTick = PhaseStep.NEVER;
    /** 血量已經到下一個階段、在等上一個階段的 3 秒：這段時間 Boss 打不動（{@link PhaseStep.Step#WAIT}）。 */
    private boolean phaseHold;
    private boolean bossKilled;
    private long bossLastSeenTick;
    /** 特別的實例（每週回響、昇華試煉）；一般地圖是 null。 */
    private MapService.Special special;
    /** Uber：這張圖的 Boss 用 Uber 的版本（null = 一般的）。 */
    private MapConfig.Uber uber;
    /** 巔峰（2026-10）：集齊碎片才能開的最終 Boss；跟極境一樣只有 Boss 房、每個人只能進一次。 */
    private MapConfig.Pinnacle pinnacle;
    /** 開圖的人點的星圖天賦（開圖時算一次；地圖本身的部分已經加進 {@link #stats}）。 */
    private AtlasBonus atlas = AtlasBonus.NONE;
    /** 2026-10-21：這張圖對應開圖的人星圖上的哪一格（別人給的、沒解鎖種類的地圖是 null）。 */
    private AtlasGrid.Cell atlasCell;
    /** 2026-10-21：這張圖照 Boss 格掉（Boss 格、首領石板；第一次完成那格才有）。 */
    private boolean atlasBossCell;
    /** 2026-10-21：第一次完成的腐化區（Boss 有機率掉蝕變石）／淨化區（有機率掉定星石）。 */
    private boolean firstCorrupted;
    private boolean firstPurified;
    /** Boss 實際的定義（Uber 合併過的）；null = 地圖原本的。 */
    private MapConfig.Boss bossOverride;
    /** Uber：走進來過的人（每個人只能進一次）。 */
    private final Set<UUID> uberEntered = new HashSet<>();
    /** 巔峰的門（2026-10）：這一門進去過的人、這一門還在不在進行、用掉幾道。 */
    private final Set<UUID> attemptEntered = new HashSet<>();
    private boolean attemptActive;
    /** 2026-10-17：開門花掉的東西怎麼退（蓋地圖失敗、重開伺服器時終局沒打完）；退過一次就清掉。 */
    private Runnable paidRefund;
    private int attemptsUsed;
    /** 階段護盾：還活著的結晶、什麼時候到期、哪一個護盾（沒有護盾時是空的、null）。 */
    private final Set<UUID> shieldCrystals = new LinkedHashSet<>();
    private long shieldUntilTick;
    private MapConfig.Shield shield;
    /** 這一場從哪個 tick 開打（狂暴計時用；-1 = 還沒有人跟 Boss 打）。 */
    private long fightStartTick = -1;
    private boolean enraged;
    /** 地圖變化（2026-10，{@link MapTwists}）：岔路、詛咒寶匣、Boss 變體改到的數值。 */
    private final Twist twist = new Twist();

    /**
     * 這張圖被「地圖變化」改到的數值（純資料，{@link MapTwists} 寫、刷怪與掉落讀）。
     * <ul>
     *   <li>quantity：路上的怪多幾成物品數量（小數，岔路「血路」）</li>
     *   <li>packSize：之後刷的怪物群大小 +%（岔路「血路」）</li>
     *   <li>monsterPower：之後刷的怪生命、傷害 ×（1 + 這個）（詛咒寶匣）</li>
     *   <li>bossPower：Boss 生命、傷害 ×（1 + 這個）（岔路「王道」、詛咒）</li>
     *   <li>bossLoot：Boss 掉落多幾成物品數量與稀有度（岔路「王道」、強化變體）</li>
     * </ul>
     */
    static final class Twist {
        double quantity;
        double packSize;
        double monsterPower;
        double bossPower;
        double bossLoot;
        /** 2026-10-27：Boss 掉落只多稀有度（小數；腐化區）。 */
        double bossRarity;
        /** 2026-10-27：路上的怪（不是 Boss）掉落多的稀有度（小數；腐化區，取代原本的物品數量）。 */
        double rarity;
        /** Boss 變體（null = 一般）。 */
        MapTwists.BossVariant bossVariant;
        /** Boss 房的干擾（null = 沒有）。 */
        MapTwists.BossRoom bossRoom;
    }

    Twist twist() {
        return twist;
    }

    MapInstance(final int id, final MapConfig.MapDef map, final MapStats stats, final MapLayout layout, final World world, final int cell,
                final int originX, final int originZ, final UUID owner, final String ownerName, final int portals, final long now) {
        this.id = id;
        this.map = map;
        this.stats = stats;
        this.layout = layout;
        this.world = world;
        this.cell = cell;
        this.originX = originX;
        this.originZ = originZ;
        this.owner = owner;
        this.ownerName = ownerName;
        this.portalsLeft = portals;
        this.portalsTotal = portals;
        this.createdTick = now;
        this.lastOccupiedTick = now;
        this.packSpawned = new boolean[layout.packSpots().size()];
        for (int i = 0; i < packSpawned.length; i++) {
            packs.add(new ArrayList<>());
        }
    }

    public int id() {
        return id;
    }

    public UUID token() {
        return token;
    }

    public MapConfig.MapDef map() {
        return map;
    }

    public MapStats stats() {
        return stats;
    }

    public MapLayout layout() {
        return layout;
    }

    public World world() {
        return world;
    }

    public int cell() {
        return cell;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public long createdTick() {
        return createdTick;
    }

    public State state() {
        return state;
    }

    void setState(final State state) {
        this.state = state;
    }

    public int portalsLeft() {
        return portalsLeft;
    }

    /** 2026-10-18：一道門都還沒用掉（關服退費用：打輸過一門就不退）。 */
    public boolean portalsUntouched() {
        return portalsLeft == portalsTotal;
    }

    void usePortal() {
        portalsLeft = Math.max(0, portalsLeft - 1);
    }

    /** 開了新的地圖：這張的傳送門收掉（還在裡面的人可以打完再出來，沒人之後關閉）。 */
    void closePortals() {
        portalsLeft = 0;
    }

    public int level() {
        return stats.monsterLevel();
    }

    /** 相對座標 → 世界座標（方塊中心，站在地板上）。 */
    public Location at(final MapLayout.Spot spot, final float yaw) {
        return new Location(world, originX + spot.x() + 0.5, MapLayout.FLOOR_Y + 1, originZ + spot.z() + 0.5, yaw, 0f);
    }

    /** 這個世界座標在不在這個實例的格子裡。 */
    public boolean contains(final Location location) {
        if (location.getWorld() == null || !location.getWorld().equals(world)) {
            return false;
        }
        final double x = location.getX() - originX;
        final double z = location.getZ() - originZ;
        return x >= -16 && z >= -16 && x < MapLayout.SIZE + 16 && z < MapLayout.SIZE + 16;
    }

    /** 這個位置在地圖的牆裡面、天花板下面（怪物該待的地方）。 */
    public boolean insideWalls(final Location location) {
        if (!contains(location)) {
            return false;
        }
        if (location.getY() >= MapLayout.FLOOR_Y + MapBuilder.CEILING - 0.5 || location.getY() < MapLayout.FLOOR_Y - 2) {
            return false;
        }
        final int x = location.getBlockX() - originX;
        final int z = location.getBlockZ() - originZ;
        return x >= layout.minX() && x <= layout.maxX() && z >= layout.minZ() && z <= layout.maxZ() && layout.isFloor(x, z);
    }

    public Set<UUID> inside() {
        return inside;
    }

    public Set<UUID> visitors() {
        return visitors;
    }

    public long lastOccupiedTick() {
        return lastOccupiedTick;
    }

    void setLastOccupiedTick(final long tick) {
        this.lastOccupiedTick = tick;
    }

    long emptySinceTick() {
        return emptySinceTick;
    }

    void setEmptySinceTick(final long tick) {
        this.emptySinceTick = tick;
    }

    boolean packSpawned(final int index) {
        return packSpawned[index];
    }

    void setPackSpawned(final int index, final boolean spawned) {
        packSpawned[index] = spawned;
    }

    List<LivingEntity> pack(final int index) {
        return packs.get(index);
    }

    List<LivingEntity> extras() {
        return extras;
    }

    int packCount() {
        return packSpawned.length;
    }

    public int kills() {
        return kills;
    }

    void addKill() {
        kills++;
    }

    /** 還活著的地圖怪（不含 Boss）。 */
    public int aliveMonsters() {
        int count = 0;
        for (final List<LivingEntity> pack : packs) {
            for (final LivingEntity entity : pack) {
                if (entity.isValid() && !entity.isDead()) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 刷過的怪物群比例（0–1，大概看清了多少）。 */
    public double explored() {
        if (packSpawned.length == 0) {
            return 1;
        }
        int spawned = 0;
        for (final boolean value : packSpawned) {
            if (value) {
                spawned++;
            }
        }
        return (double) spawned / packSpawned.length;
    }

    public LivingEntity boss() {
        return boss;
    }

    void setBoss(final LivingEntity boss) {
        this.boss = boss;
    }

    BossBar bossBar() {
        return bossBar;
    }

    void setBossBar(final BossBar bar) {
        this.bossBar = bar;
    }

    int bossPhase() {
        return bossPhase;
    }

    void setBossPhase(final int phase) {
        this.bossPhase = phase;
    }

    long phaseTick() {
        return phaseTick;
    }

    void setPhaseTick(final long tick) {
        this.phaseTick = tick;
    }

    boolean phaseHold() {
        return phaseHold;
    }

    void setPhaseHold(final boolean hold) {
        this.phaseHold = hold;
    }

    public boolean bossKilled() {
        return bossKilled;
    }

    void setBossKilled(final boolean killed) {
        this.bossKilled = killed;
    }

    long bossLastSeenTick() {
        return bossLastSeenTick;
    }

    void setBossLastSeenTick(final long tick) {
        this.bossLastSeenTick = tick;
    }

    /** 幻霧的難度（小數；0 = 沒有）。 */
    public double delirium() {
        return delirium;
    }

    void setDelirium(final double delirium) {
        this.delirium = Math.max(0, delirium);
    }

    /** 特別的實例（每週回響、昇華試煉）：不用地圖物品開、完成時不點亮星圖；一般地圖是 null。 */
    // ---- Uber、護盾、狂暴 ----

    public MapConfig.Uber uber() {
        return uber;
    }

    public boolean isUber() {
        return uber != null;
    }

    public MapConfig.Pinnacle pinnacle() {
        return pinnacle;
    }

    public boolean isPinnacle() {
        return pinnacle != null;
    }

    /** 只有 Boss 房的實例（極境、巔峰）：沒有路上的怪、沒有地圖事件，一進去下一間就是 Boss。 */
    public boolean bossOnly() {
        return isUber() || isPinnacle();
    }

    void setPinnacle(final MapConfig.Pinnacle pinnacle) {
        this.pinnacle = pinnacle;
    }

    /** 設成 Uber：Boss 換成合併過的定義。 */
    void setUber(final MapConfig.Uber uber, final MapConfig.Boss merged) {
        this.uber = uber;
        this.bossOverride = merged;
    }

    /** Boss 實際的定義（Uber 是合併過的那個）。 */
    public MapConfig.Boss bossDef() {
        return bossOverride != null ? bossOverride : map.boss();
    }

    /** 每個人只能進一次的實例：極境、劫盜終局（死了或離開就不能再進）。巔峰另外算「門」（見 {@link #attemptActive}）。 */
    public boolean singleEntry() {
        return isUber() || special != null && "heist_finale".equals(special.kind());
    }

    /** 用「門」算的實例（2026-10）：終局、極境、巔峰（特別的實例除外），還有金庫之主（劫盜終局）。 */
    public boolean usesAttempts() {
        return map.isFinal() && special == null || isHeistFinale();
    }

    /** 金庫之主（劫盜終局）。 */
    public boolean isHeistFinale() {
        return special != null && "heist_finale".equals(special.kind());
    }

    /**
     * 照「波次」扣次數的實例（2026-10）：一般地圖。第一個人走進去就開始一波，同一波還沒進來過的隊友跟進不扣次數；
     * 這一波的人全部倒下或出來才扣 1 次。同一波裡死了、回營地再進去，照舊扣 1 次。（欄位跟終局的「門」共用）
     */
    public boolean usesWaves() {
        return !usesAttempts() && !singleEntry();
    }

    // ---- 終局、極境、巔峰的「門」（2026-10）：一張有 2 道門（巔峰照 maps.yml 的 attempts），一道門 = 一次挑戰 ----
    // 第一個人走進去就開始這一門；這一門還有人在裡面的時候，還沒進來過的隊友可以跟進，死了或出去的人不能再進；
    // 裡面的人全部倒下（或出去）這一門就結束，下一個走進去的人開始下一門，Boss 回滿、從第一階段重來；打倒 Boss 門全部關掉。

    void paidRefund(final Runnable refund) {
        this.paidRefund = refund;
    }

    /** 把開門花掉的東西退回去（只會退一次；沒有記就什麼都不做）。 */
    boolean refundPaid() {
        final Runnable refund = paidRefund;
        paidRefund = null;
        if (refund == null) {
            return false;
        }
        refund.run();
        return true;
    }

    /** 巔峰：這一門是不是還在進行（有人進去了、還沒全部倒下）。 */
    public boolean attemptActive() {
        return attemptActive;
    }

    /** 巔峰：已經用掉幾道門（含進行中的這一道）。 */
    public int attemptsUsed() {
        return attemptsUsed;
    }

    /** 巔峰：開始下一門；回傳是不是第二門以後（要重置 Boss）。 */
    boolean startAttempt() {
        attemptActive = true;
        attemptEntered.clear();
        attemptsUsed++;
        return attemptsUsed > 1;
    }

    /** 巔峰：這一門結束（裡面沒有人了）：用掉一道門。 */
    void endAttempt() {
        attemptActive = false;
        attemptEntered.clear();
        usePortal();
    }

    /** 巔峰：這個人這一門進去過了嗎（死了或出去就要等下一門）。 */
    public boolean enteredThisAttempt(final UUID player) {
        return attemptEntered.contains(player);
    }

    void markAttemptEntry(final UUID player) {
        attemptEntered.add(player);
    }

    boolean markUberEntry(final UUID player) {
        return uberEntered.add(player);
    }

    boolean uberEntered(final UUID player) {
        return uberEntered.contains(player);
    }

    public Set<UUID> shieldCrystals() {
        return shieldCrystals;
    }

    public MapConfig.Shield shield() {
        return shield;
    }

    public long shieldUntilTick() {
        return shieldUntilTick;
    }

    void startShield(final MapConfig.Shield shield, final long untilTick) {
        this.shield = shield;
        this.shieldUntilTick = untilTick;
    }

    void endShield() {
        this.shield = null;
        this.shieldCrystals.clear();
    }

    public boolean shielded() {
        return shield != null;
    }

    /** 有「殘血才進來沒有掉落」的規則：終局（特別的實例除外）、極境、巔峰。 */
    public boolean lateEntryRule() {
        return map.isFinal() && special == null || isUber() || isPinnacle();
    }

    /**
     * 記一次誰在場（MapBosses 每次巡 Boss 時）：Boss 生命還在 {@link #LATE_ENTRY_LIFE} 以上就把在場的人記進名單。
     * 回傳這次第一次被判定「來晚了」的人（要提醒的）。
     */
    public List<UUID> noteRoster(final java.util.Collection<UUID> present, final double lifeFraction) {
        if (lifeFraction >= LATE_ENTRY_LIFE) {
            fightRoster.addAll(present);
            return List.of();
        }
        final List<UUID> late = new ArrayList<>();
        for (final UUID id : present) {
            if (!fightRoster.contains(id) && lateWarned.add(id)) {
                late.add(id);
            }
        }
        return late;
    }

    /** 這個人打死 Boss 時有沒有掉落（沒有這條規則、或名單是空的（一下就打死）都算有）。 */
    public boolean lootEligible(final UUID player) {
        return !lateEntryRule() || fightRoster.isEmpty() || fightRoster.contains(player);
    }

    /** Boss 重置、換一隻：名單重來。 */
    void clearRoster() {
        fightRoster.clear();
        lateWarned.clear();
    }

    public long fightStartTick() {
        return fightStartTick;
    }

    void setFightStartTick(final long tick) {
        this.fightStartTick = tick;
    }

    public boolean enraged() {
        return enraged;
    }

    void setEnraged(final boolean enraged) {
        this.enraged = enraged;
    }

    public MapService.Special special() {
        return special;
    }

    /** 這張地圖吃到的星圖天賦（開圖的人點的；特別的實例、極境是空的）。 */
    public AtlasBonus atlas() {
        return atlas;
    }

    /** 2026-10-21：對應星圖的哪一格；沒有是 null。 */
    public AtlasGrid.Cell atlasCell() {
        return atlasCell;
    }

    void setAtlasCell(final AtlasGrid.Cell cell) {
        this.atlasCell = cell;
    }

    public boolean atlasBossCell() {
        return atlasBossCell;
    }

    public boolean firstCorrupted() {
        return firstCorrupted;
    }

    void setFirstCorrupted(final boolean value) {
        this.firstCorrupted = value;
    }

    public boolean firstPurified() {
        return firstPurified;
    }

    void setFirstPurified(final boolean value) {
        this.firstPurified = value;
    }

    void setAtlasBossCell(final boolean value) {
        this.atlasBossCell = value;
    }

    void setAtlas(final AtlasBonus atlas) {
        this.atlas = atlas == null ? AtlasBonus.NONE : atlas;
    }

    void setSpecial(final MapService.Special special) {
        this.special = special;
    }

    /** 顯示用的名稱：「沉船灣 T7」「天穹熔爐 T12」「天穹熔爐・極境」「回響：潮濕海岸」。 */
    public String label() {
        if (uber != null) {
            return map.name() + "・極境";
        }
        if (pinnacle != null) {
            return map.name();
        }
        return special != null || stats.tier() <= 0 ? map.name() : map.name() + " T" + stats.tier();
    }
}
