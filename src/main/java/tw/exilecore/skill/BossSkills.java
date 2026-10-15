package tw.exilecore.skill;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.combat.MinionHooks;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Formulas;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;
import tw.exilecore.zone.Zone;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Boss 招式引擎（data/boss_skills.yml）：地圖 Boss、試煉 Boss、區域 Boss 掛上來（{@link #attach}）之後，
 * 每 tick 看要不要出招——先前搖（Boss 站著蓄力、地上畫紅色警告），再照招式的種類執行：
 * 延遲落下、新星、扇形、齊射、地面效果、衝鋒、召喚、詛咒、閃現。傷害走戰鬥模組的管道（{@link SkillDamage#hitAs}），
 * 跟怪物普攻一樣吃 Boss 的屬性表與玩家的防禦，只是倍率與類型照招式。
 * <p>
 * 追擊：目標在 {@code chase_distance} 格外連續 {@code chase_seconds} 秒都打不到（站遠射箭），就用 {@code gap_closer} 那招（撲擊）跳過去。
 * <p>
 * 預警除了粒子，危險範圍的地板會暫時變成有顏色的方塊（{@link DangerZones}，只有客戶端看得到），快落下時閃紅；
 * 站在裡面的玩家畫面中間會跳「離開紅色區域」。
 * <p>
 * 不會一瞬間疊死人：同一招打到同一個人有最短間隔（{@link BossSkill#hitGapTicks}：齊射的好幾發、光球、疊在一起的地面效果只算一下）；
 * Boss 換階段時先停一下不出招，新階段的招晚一點才用（boss_skills.yml 的 phase_pause、phase_unlock）；
 * 附近一陣子沒人之後重新看到人（走進 Boss 房、死掉跑回來），先等 boss_first_delay 秒才出招。
 * <p>
 * 一次只做一件事（2026-10，{@link BossPacing}）：場地危害等 Boss 這一招放完才落、落下之前不出新的招；齊射還在飛、
 * 光球剛放出去也算這一招還沒完。放招的時候（前搖到放完）原版的 AI 暫停：近戰、射箭、伏守者的音波、喚魔者的法術
 * 都不會跟招式同時出現；掛了 Boss 招式的伏守者整場不用原版音波（{@link SonicBoomLock}）。
 * 凍結、暈眩中，或伏守者在吼叫、鑽出地面的時候不出新的招。
 */
public final class BossSkills implements Listener {

    /** 掛上 Boss 的地方（地圖、區域）提供的東西。 */
    public interface Host {
        /** Boss 的名字（警告用）。 */
        String name();

        /** 目前第幾階段（0 = 一開始；地圖 Boss 進入第 n 個 phase 後是 n）。 */
        int phase();

        /** 幫 Boss 生一隻小怪（照地圖／區域的規矩標記、等級化）；生不出來回傳 null。 */
        LivingEntity summon(String entityType, Location at);

        /**
         * 是不是 Boss：Boss 出招時會喊話、吼聲大、打不到人會追擊；
         * 一般怪物（詞綴或種類給的招式）只有地上的警告與小聲的音效，也不追擊。
         */
        default boolean boss() {
            return true;
        }

        /** 場地危害（照時間自己落下的招；等 Boss 手上這一招放完才落，見 {@link BossPacing}）；沒有就是空的。 */
        default List<BossHazard> hazards() {
            return List.of();
        }

        /**
         * 特殊招式要不要說清楚（終局與試煉 Boss）：放特殊招式時畫面中間跳大字與怎麼應對、聊天室寫一行，
         * 場地危害第一次落下時說明一次；站在紅圈、安全區外的大字也帶招式名稱（{@link BossAlerts}）。
         */
        default boolean alerts() {
            return false;
        }

        /**
         * 出招節奏的種類（2026-10；boss_skills.yml 的 settings.tempo：final 終局、uber 極境、trial 試煉、vault 金庫之主、world 世界 Boss）；
         * null = 照一般的 Boss。只影響兩招之間的空檔與招式冷卻，前搖不變。
         */
        default String tempoKind() {
            return null;
        }

        /** 還沒開打（2026-10：地圖 Boss 要有人走進 Boss 房才開打）：不找目標、不出招、場地危害不落。 */
        default boolean dormant() {
            return false;
        }
    }

    /** 目標最遠從幾格外找（Boss）。 */
    private static final double SEARCH = 48;
    /** 一般怪物找目標的距離。 */
    private static final double SEARCH_MINION = 24;
    /** 這麼近的玩家就算看不到也算目標；更遠的要看得到（隔壁房間探險的人不算，站遠射箭的算）。 */
    private static final double CLOSE = 16;
    /** 齊射的魔法彈碰撞檢查的高度範圍。 */
    private static final double HEIGHT = 3.0;
    /** 沒目標的怪幾 tick 找一次目標（錯開，不會同一個 tick 一起找）。 */
    private static final int SEARCH_EVERY = 4;
    /** 齊射地上扇形警示的長度（格；2026-10：30 太大一片，改回 16）。 */
    private static final double VOLLEY_MARK = 16;
    /** 撲擊落地到震一下的 tick（0.7 秒：看到腳下變紅、走出去剛好來得及）。 */
    private static final int LAND_TICKS = 14;
    /** 閃現、撲擊的前搖：跟著目標標地板，目標移動超過這麼多格就重標。 */
    private static final double REMARK_DISTANCE = 0.8;
    /** Boss 附近一個目標都沒有超過這麼久（5 秒），重新看到人時先等 boss_first_delay 秒才出招。 */
    private static final long IDLE_TICKS = 5 * 20;

    /** 一隻 Boss 的腦袋：招式組、冷卻、現在在放的招、留下的東西。 */
    private final class Brain {
        final LivingEntity boss;
        final Host host;
        final List<BossSkillUse> configured;
        List<BossSkillConfig.Entry> kit;
        BossSkill closer;
        final Map<String, Long> readyAt = new HashMap<>();
        long globalReadyAt;
        Cast cast;
        final List<ZoneArea> zones = new ArrayList<>();
        final List<Bolt> bolts = new ArrayList<>();
        /** 擴散環（一波一波往外跑）。 */
        final List<RingWave> rings = new ArrayList<>();
        /** 追蹤光球。 */
        final List<Orb> orbs = new ArrayList<>();
        /** 場地危害：還沒落下的。 */
        final List<Pending> pendings = new ArrayList<>();
        /** 一次只做一件事：場地危害的計時。 */
        final BossPacing pacing = new BossPacing();
        /** 最近一次放出光球的 tick（光球剛放出去也算這一招還沒完）。 */
        long lastOrb = BossPacing.NEVER;
        /** 放招時是這裡把原版 AI 暫停的（放完要恢復）。 */
        boolean aiPaused;
        /** 放招時移動速度歸零（AI 暫停前最後一次算出的速度是 0，暫停後才不會照原本的方向滑）。 */
        boolean anchored;
        /** 這隻 Boss 叫出來的小怪 → 到期的 tick（0 = 不限）。 */
        final Map<UUID, Long> summons = new HashMap<>();
        /** 目標從哪個 tick 起一直在追擊距離外（-1 = 沒有）。 */
        long farSince = -1;
        /** 上次記錯誤的 tick（一直出錯時不洗版）。 */
        long lastError = Long.MIN_VALUE / 2;
        /** 找目標、畫粒子的錯開量（同一個 tick 不要幾十隻一起）。 */
        final int offset;
        /** 同一招打同一個人的間隔（齊射的好幾發、光球、疊在一起的地面效果只算一下）。 */
        final HitGate gate = new HitGate();
        /** 上一個 tick 看到的階段（換階段時停一下、新的招晚一點才用）。 */
        int phase;
        /** 上次有目標的 tick（重置不清掉）：一陣子沒有目標之後重新看到人，先等一下才出招。 */
        long lastTarget;
        /** 特殊招式上次在聊天室說明的 tick（同一招 30 秒內只寫一次；重置時清掉）。 */
        final Map<String, Long> chatAt = new HashMap<>();
        /** 這一場已經說明過的場地危害（重置時清掉）。 */
        final Set<String> hazardsAnnounced = new HashSet<>();
        /** 點名標記（2026-10）：跟著被點名的人走、時間到就炸。 */
        final List<Marked> marks = new ArrayList<>();
        /** 最近一次點名的 tick（剛點名也算這一招還沒完，跟光球一樣先讓人看清楚）。 */
        long lastMark = BossPacing.NEVER;
        /** 場地收縮（2026-10）：放過之後維持到這場打完，重置才消失。 */
        Arena arena;
        /** 跟場地收縮一起放的招（with:）：收縮期間 at: arena 的地面效果照各自的 cooldown 一直落（2026-10）。 */
        List<BossSkill> arenaWith = List.of();

        Brain(final LivingEntity boss, final Host host, final List<BossSkillUse> configured) {
            this.boss = boss;
            this.host = host;
            this.configured = configured;
            this.offset = Math.floorMod(boss.getUniqueId().hashCode(), 64);
            this.phase = host.phase();
            this.lastTarget = BossSkills.this.now(); // 剛生出來的 Boss 已經有 boss_first_delay，不要再等一次
            resolve();
        }

        void resolve() {
            final BossSkillConfig config = BossSkills.this.config.bossSkills();
            kit = config.kitFor(boss.getType().name(), configured);
            closer = config.gapCloser();
        }

        void reset(final long now) {
            readyAt.clear();
            cast = null;
            zones.clear();
            bolts.clear();
            rings.clear();
            orbs.clear();
            pendings.clear();
            pacing.clear();
            lastOrb = BossPacing.NEVER;
            gate.clear();
            chatAt.clear();
            hazardsAnnounced.clear();
            marks.clear();
            lastMark = BossPacing.NEVER;
            arena = null;
            arenaWith = List.of();
            phase = host.phase();
            farSince = -1;
            // 一般怪物：出場後的等待多一點隨機，一群怪才不會同時出招
            globalReadyAt = now + Math.round(settings().firstDelayFor(host.boss()) * 20) + (host.boss() ? 0 : random.nextInt(40));
        }
    }

    /** 正在放的招。 */
    private static final class Cast {
        final BossSkill skill;
        final long start;
        final long telegraphEnd;
        final LivingEntity target;
        final Vector facing;
        final List<Location> points;
        boolean fired;
        int done;
        long nextTick;
        double travelled;
        Location landAt;
        long landTick;
        /** 閃現、撲擊的前搖：目前標在地上的那一圈的中心（跟著目標走）。 */
        Location markAt;
        /** 衝鋒已經撞到的人。 */
        final Set<UUID> hit = new HashSet<>();
        /** 地面標示的代號前綴（每個落點是 前綴#i）。 */
        String mark;
        /** 光束：第一道的起始角度（弧度）、轉的方向（+1／-1）。 */
        double beamStart;
        int beamSign = 1;
        /** 光束：每個人上次被掃到的 tick。 */
        final Map<UUID, Long> lastHit = new HashMap<>();
        /** 擴散環：缺口的中心角度。 */
        double[] gaps = new double[0];
        /** 全場大招：安全區的中心。 */
        List<Location> safeSpots = List.of();
        /** 標示地板時 Boss 的位置：新星、扇形、全場大招的傷害照這裡算（2026-10）。 */
        Location anchor;
        /** 光束、擴散環、漩渦：結束的 tick。 */
        long channelEnd;
        /** 殘影（2026-10）：每個殘影的那一槍，照出手的順序。 */
        List<EchoLance> lances = List.of();

        Cast(final BossSkill skill, final long start, final long telegraphTicks, final LivingEntity target, final Vector facing,
             final List<Location> points) {
            this.skill = skill;
            this.start = start;
            this.telegraphEnd = start + telegraphTicks;
            this.target = target;
            this.facing = facing;
            this.points = points;
        }
    }

    /** 牆（solid 的地面效果）每 tick 把人往外推幾格（跑步每 tick 約 0.28 格：推得比跑快，硬走穿不過）。 */
    private static final double WALL_PUSH = 0.45;

    /** 留在地上的區域。 */
    private static final class ZoneArea {
        final BossSkill skill;
        final Location center;
        final long untilTick;
        long nextPulse;
        /** 地面標示的代號。 */
        final String mark;

        ZoneArea(final BossSkill skill, final Location center, final long now, final String mark) {
            this.skill = skill;
            this.center = center;
            this.untilTick = now + Math.round(skill.duration() * 20);
            this.nextPulse = now + Math.round(skill.interval() * 20);
            this.mark = mark;
        }
    }

    /** 齊射的一發。 */
    private static final class Bolt {
        final BossSkill skill;
        final Location position;
        final Vector velocity;
        double travelled;
        /** 地上的扇形警示（齊射的彈都飛完才收；2026-10）。 */
        String mark;

        Bolt(final BossSkill skill, final Location position, final Vector velocity) {
            this.skill = skill;
            this.position = position;
            this.velocity = velocity;
        }
    }

    /** 擴散環的一波。 */
    private static final class RingWave {
        final BossSkill skill;
        final Location center;
        final double[] gaps;
        final long startTick;
        final Set<UUID> hit = new HashSet<>();
        /** 地上跟著往外跑的那一圈（2026-10：不靠粒子，地上的圈就是打得到的地方）。 */
        String mark;

        RingWave(final BossSkill skill, final Location center, final double[] gaps, final long startTick) {
            this.skill = skill;
            this.center = center;
            this.gaps = gaps;
            this.startTick = startTick;
        }

        double radius(final long now) {
            return skill.radius() + Math.max(0, now - startTick) * skill.speed();
        }
    }

    /** 追蹤光球。 */
    private static final class Orb {
        final BossSkill skill;
        final Location position;
        UUID target;
        final long untilTick;
        /** 光球底下地板的圈（爆炸範圍，跟著光球走；2026-10）。 */
        String mark;
        /** 上次告訴「光球在追你」的人（換目標才再說一次）。 */
        UUID told;

        Orb(final BossSkill skill, final Location position, final UUID target, final long untilTick) {
            this.skill = skill;
            this.position = position;
            this.target = target;
            this.untilTick = untilTick;
        }
    }

    /** 點名標記（2026-10）：跟著被點名的人走的紅圈，fireTick 時在他腳下爆炸。 */
    private static final class Marked {
        final BossSkill skill;
        final UUID target;
        /** 被點名的人最後的位置（下線、死掉、換世界就炸在這裡）。 */
        Location last;
        final long startTick;
        final long fireTick;
        final String mark;
        /** 地上的圈現在標在哪（人走超過 REMARK_DISTANCE 才重標）。 */
        Location shownAt;

        Marked(final BossSkill skill, final UUID target, final Location last, final long startTick, final long fireTick, final String mark) {
            this.skill = skill;
            this.target = target;
            this.last = last;
            this.startTick = startTick;
            this.fireTick = fireTick;
            this.mark = mark;
        }
    }

    /** 場地收縮（2026-10）：安全的圈從 reach 縮到 radius，圈外的人每 interval 秒挨一下。 */
    private static final class Arena {
        final BossSkill skill;
        final Location center;
        final long startTick;
        final long shrinkTicks;
        long nextPulse;
        final String mark;
        /** 地上的圈上次標的半徑（縮超過半格才重標）。 */
        double shown = -1;
        /** 跟收縮一起放的地面效果下次落的 tick（招式代號 → tick）。 */
        final Map<String, Long> nextExtra = new HashMap<>();

        Arena(final BossSkill skill, final Location center, final long startTick, final String mark) {
            this.skill = skill;
            this.center = center;
            this.startTick = startTick;
            this.shrinkTicks = Math.max(1, Math.round(skill.duration() * 20));
            this.nextPulse = startTick + Math.max(2, Math.round(skill.interval() * 20));
            this.mark = mark;
        }

        double radius(final long now) {
            return BossGeometry.arenaRadius(skill.reach(), skill.radius(), now - startTick, shrinkTicks);
        }
    }

    /** 殘影的一槍（2026-10）：showTick 時朝某個玩家標出紅線，hitTick 時射穿。 */
    private static final class EchoLance {
        final Location from;
        final long showTick;
        final long hitTick;
        final String mark;
        Vector direction;
        boolean shown;
        boolean done;

        EchoLance(final Location from, final long showTick, final long hitTick, final String mark) {
            this.from = from;
            this.showTick = showTick;
            this.hitTick = hitTick;
            this.mark = mark;
        }
    }

    /** 場地危害還沒落下的一顆（延遲落下）或一塊（地面效果）。 */
    private static final class Pending {
        final BossSkill skill;
        final Location point;
        final long startTick;
        final long fireTick;
        final String mark;

        Pending(final BossSkill skill, final Location point, final long startTick, final long fireTick, final String mark) {
            this.skill = skill;
            this.point = point;
            this.startTick = startTick;
            this.fireTick = fireTick;
            this.mark = mark;
        }
    }

    private final ExileCore plugin;
    private final SkillConfig config;
    private final SkillVisuals visuals;
    private final SkillDamage damage;
    private final RandomGenerator random;
    private final DangerZones danger;
    private final Map<UUID, Brain> brains = new HashMap<>();
    /** 正在放全場大招（綠色安全區，sanctuary）的 Boss（2026-10-15）：附近的 Boss 都等它落下才出招。 */
    private final java.util.Set<Brain> channeling = new java.util.HashSet<>();
    /** 安全區大招範圍再往外幾格內的 Boss 也要等。 */
    static final double SANCTUARY_HOLD_MARGIN = 16;
    private final Map<String, SkillProfile> profiles = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    /** 放招時暫停了原版 AI 的記號：萬一來不及恢復（區塊卸載、當機），下次載入時 {@link #recover} 恢復。 */
    private final NamespacedKey pausedKey;
    /** 放招時移動速度歸零的修飾器（暫時的，不會存檔）。 */
    private final NamespacedKey anchorKey;
    private final SonicBoomLock sonicBoom;

    BossSkills(final ExileCore plugin, final SkillConfig config, final SkillVisuals visuals, final SkillDamage damage, final RandomGenerator random) {
        this.plugin = plugin;
        this.config = config;
        this.visuals = visuals;
        this.damage = damage;
        this.random = random;
        this.danger = new DangerZones(plugin);
        this.pausedKey = new NamespacedKey(plugin, "boss_cast_paused");
        this.anchorKey = new NamespacedKey(plugin, "boss_cast_anchor");
        this.sonicBoom = new SonicBoomLock(plugin.getSLF4JLogger());
    }

    private BossSkillConfig.Settings settings() {
        return config.bossSkills().settings();
    }

    /** 這隻 Boss 出招的節奏倍率（終局、極境、試煉、金庫、世界 Boss 比較緊；其他是 1）。 */
    private double tempo(final Brain brain) {
        return config.bossSkills().tempo(brain.host.tempoKind());
    }

    // ---- 給地圖、區域 ----

    /**
     * 把招式掛到一隻 Boss 身上（已經掛過就換掉）。沒有任何招式（boss_skills.yml 不存在、這種生物沒有預設又沒寫 skills）就不掛。
     *
     * @param configured maps.yml／zones.yml 寫的 skills（空的 = 用這種生物的預設）
     */
    public void attach(final LivingEntity boss, final List<BossSkillUse> configured, final Host host) {
        final Brain brain = new Brain(boss, host, configured == null ? List.of() : configured);
        final Brain old = brains.get(boss.getUniqueId());
        if (old != null) {
            danger.hidePrefix(boss.getUniqueId() + ":"); // 換掉的腦袋留在地上的東西一起消失
        }
        if (brain.kit.isEmpty() && (brain.closer == null || !host.boss())) {
            brains.remove(boss.getUniqueId());
            if (old != null) {
                release(old);
            }
            return;
        }
        brain.reset(now());
        if (old != null) {
            // 舊的腦袋放招時暫停的 AI、歸零的速度交給新的（下一 tick 照新的狀態恢復）
            brain.aiPaused = old.aiPaused;
            brain.anchored = old.anchored;
        }
        brains.put(boss.getUniqueId(), brain);
        lockSonicBoom(brain);
    }

    /** Boss 死了、被移除、戰鬥結束：拿掉，留在地上的東西一起消失；放招時暫停的原版 AI 恢復。 */
    public void detach(final UUID boss) {
        final Brain brain = brains.remove(boss);
        if (brain != null) {
            danger.hidePrefix(boss + ":");
            release(brain);
        }
    }

    /** Boss 重置（附近沒人、回滿血）：冷卻清掉、正在放的招取消。 */
    public void reset(final UUID boss) {
        final Brain brain = brains.get(boss);
        if (brain != null) {
            brain.reset(now());
            danger.hidePrefix(boss + ":");
        }
    }

    /** 招式引擎帶著的 Boss（地圖、試煉、區域 Boss；活著的）。 */
    public List<LivingEntity> bosses() {
        final List<LivingEntity> list = new ArrayList<>();
        for (final Brain brain : brains.values()) {
            if (brain.host.boss() && brain.boss.isValid() && !brain.boss.isDead()) {
                list.add(brain.boss);
            }
        }
        return list;
    }

    public boolean attached(final UUID boss) {
        return brains.containsKey(boss);
    }

    /** 這隻是不是由招式引擎帶的 Boss（地圖、試煉、區域 Boss；詞綴或種類給招式的一般怪物不算）。 */
    /**
     * 這隻 Boss 的場地收縮（2026-10）：{x, z, 最後會縮到的半徑}；沒有收縮回傳 null。
     * 護盾結晶等「要玩家走過去」的東西放在這個圈裡（照最後的半徑，整段時間都不會被圈吃掉）。
     */
    public double[] arenaBounds(final UUID boss) {
        final Brain brain = brains.get(boss);
        if (brain == null || brain.arena == null) {
            return null;
        }
        final Arena arena = brain.arena;
        final double radius = Math.min(arena.radius(now()), Math.max(4, arena.skill.radius()));
        return new double[] {arena.center.getX(), arena.center.getZ(), radius};
    }

    /** 這隻怪正在放招（2026-10；放招時本來就站著不動，卡住偵測要略過）。 */
    public boolean casting(final UUID entity) {
        final Brain brain = brains.get(entity);
        return brain != null && brain.cast != null;
    }

    public boolean drivesBoss(final UUID boss) {
        final Brain brain = brains.get(boss);
        return brain != null && brain.host.boss();
    }

    /** /ec reload 之後：每隻 Boss 的招式組照新的設定重算。 */
    public void refresh() {
        for (final Brain brain : brains.values()) {
            brain.resolve();
        }
    }

    public int count() {
        return brains.size();
    }

    void clear() {
        for (final Brain brain : brains.values()) {
            release(brain); // 插件關閉（伺服器關機時插件先關、世界後存）：暫停的 AI 先恢復
        }
        brains.clear();
        danger.clear();
    }

    /** 這些 skills 裡不存在的招式（地圖、區域設定檔載入時警告用）。 */
    public List<String> unknown(final List<BossSkillUse> uses) {
        return config.bossSkills().unknown(uses);
    }

    // ---- 每 tick ----

    void tick() {
        danger.tick();
        if (brains.isEmpty()) {
            return;
        }
        final long now = now();
        final Iterator<Brain> iterator = brains.values().iterator();
        while (iterator.hasNext()) {
            final Brain brain = iterator.next();
            if (!brain.boss.isValid() || brain.boss.isDead()) {
                iterator.remove();
                danger.hidePrefix(brain.boss.getUniqueId() + ":");
                continue;
            }
            try {
                tickBrain(brain, now);
            } catch (final RuntimeException e) {
                // 出錯的這隻：正在放的招與留在地上的東西都收掉（地上的標示也收了，不能留著沒有預警的傷害）
                brain.cast = null;
                brain.bolts.clear();
                brain.zones.clear();
                brain.rings.clear();
                brain.orbs.clear();
                brain.pendings.clear();
                brain.marks.clear();
                brain.arena = null;
                danger.hidePrefix(brain.boss.getUniqueId() + ":");
                release(brain); // 放招時暫停的原版 AI 也恢復
                if (now - brain.lastError >= 20 * 30) {
                    brain.lastError = now; // 同一隻 30 秒內只記一次（一直出錯不要洗版）
                    plugin.getSLF4JLogger().error("Boss {} 的招式出錯", brain.host.name(), e);
                }
            }
        }
    }

    private void tickBrain(final Brain brain, final long now) {
        syncAi(brain, now);
        tickBolts(brain, now);
        tickZones(brain, now);
        tickRings(brain, now);
        tickOrbs(brain, now);
        tickPendings(brain, now);
        tickMarks(brain, now);
        tickArena(brain, now);
        if (now % 20 == 0) {
            expireSummons(brain, now);
            brain.gate.prune(now);
            lockSonicBoom(brain);
        }
        checkPhase(brain, now);
        if (brain.host.boss() && hasTarget(brain)) {
            brain.lastTarget = now; // 放招、等空檔的時候也算（只看有沒有目標；一般怪物不用，省得每 tick 都查）
        }
        if (incapacitated(brain.boss)) {
            // 2026-10：控住的時候空檔一直往後推：控制結束後一樣要等 boss_gap 才出下一招
            // （打斷招式之後不會因為少放了半招，控制一結束就馬上接下一招，反而變快）
            brain.globalReadyAt = Math.max(brain.globalReadyAt,
                    now + Math.round(settings().gapFor(brain.host.boss()) * 20 * cooldownFactor(brain.boss) * tempo(brain)));
        }
        if (brain.cast != null) {
            if (incapacitated(brain.boss)) {
                // 2026-10：暈眩、電擊、凍結打斷正在放的招（地上的警示一起收掉；已經飛出去的光球、擴散環照走）
                finish(brain, now);
                return;
            }
            advance(brain, brain.cast, now);
            return;
        }
        if (brain.host.dormant()) {
            return;
        }
        if (underSanctuary(brain)) {
            // 2026-10-15：別的 Boss（雙生、援軍…）在放綠色安全區的大招：等它落下，再隔一個空檔才出招（場地危害也不落）
            brain.globalReadyAt = Math.max(brain.globalReadyAt,
                    now + Math.round(settings().gapFor(brain.host.boss()) * 20 * cooldownFactor(brain.boss) * tempo(brain)));
            return;
        }
        // 一次只做一件事：上一件還沒完（齊射還在飛、光球剛放出去、場地危害還沒落下）什麼都不開始；
        // 放完一招後的空檔裡只有到時間的場地危害可以落
        if (BossPacing.unsettled(brain.bolts.size(), brain.rings.size(), brain.pendings.size(), Math.max(brain.lastOrb, brain.lastMark), now)) {
            return;
        }
        final boolean hazards = !brain.host.hazards().isEmpty();
        final BossPacing.Step step = BossPacing.step(now, brain.globalReadyAt, hazards ? Math.round(settings().gapFor(brain.host.boss()) * 20 * tempo(brain)) : 0);
        if (step == BossPacing.Step.WAIT) {
            return;
        }
        if (!hasTarget(brain) && (now + brain.offset) % SEARCH_EVERY != 0) {
            return; // 沒目標時每 SEARCH_EVERY tick 才找一次（找人要射線檢查，幾十隻一起找很花）
        }
        final LivingEntity target = pickTarget(brain);
        if (target == null) {
            brain.farSince = -1;
            // 躲在 16 格外看不到的地方：Boss 沒有目標，但剛剛還在打（5 秒內）——場地危害照落（落在 48 格內的某個人附近）
            if (hazards && now - brain.lastTarget < IDLE_TICKS && !held(brain.boss)) {
                tryHazard(brain, now);
            }
            return;
        }
        final boolean wasIdle = now - brain.lastTarget >= IDLE_TICKS;
        brain.lastTarget = now;
        if (wasIdle && brain.host.boss()) {
            // 一陣子沒人（剛走進 Boss 房、死掉跑回來、重置過）：重新看到人先等 boss_first_delay 秒，不會一照面就接一招；場地危害也一樣
            // （伏守者剛發現人時的吼叫也算在這段等待裡：吼完就出招，不會吼完再等）
            final long ready = now + Math.round(settings().firstDelayFor(true) * 20);
            brain.globalReadyAt = ready;
            brain.pacing.delayHazards(ready);
            return;
        }
        if (held(brain.boss)) {
            return; // 凍結、暈眩中，或伏守者在吼叫、鑽出地面：等它做完
        }
        if (hazards && tryHazard(brain, now)) {
            return; // 這一 tick 落了場地危害：落下之前不出新的招
        }
        if (step != BossPacing.Step.ANY) {
            return;
        }
        final double distance = target.getLocation().distance(brain.boss.getLocation());
        // 追擊：一直打不到就跳過去
        final BossSkillConfig.Settings settings = settings();
        if (brain.closer != null && brain.host.boss() && distance > settings.chaseDistance()) {
            if (brain.farSince < 0) {
                brain.farSince = now;
            } else if (now - brain.farSince >= settings.chaseSeconds() * 20 && ready(brain, brain.closer, now)
                    && distance <= brain.closer.range()) {
                brain.farSince = -1;
                start(brain, brain.closer, target, now);
                return;
            }
        } else {
            brain.farSince = -1;
        }
        final List<BossSkillConfig.Entry> candidates = new ArrayList<>();
        final int phase = brain.host.phase();
        for (final BossSkillConfig.Entry entry : brain.kit) {
            final BossSkill skill = entry.skill();
            if (entry.fromPhase() > phase || !ready(brain, skill, now) || distance > skill.range()) {
                continue;
            }
            if (skill.kind() == BossSkill.Kind.SUMMON && aliveSummons(brain) >= skill.summonMax()) {
                continue;
            }
            if (skill.kind() == BossSkill.Kind.ARENA && brain.arena != null) {
                continue; // 場地已經在收縮了（維持到這場打完）
            }
            if (skill.kind() == BossSkill.Kind.SANCTUARY && (!brain.orbs.isEmpty() || !brain.marks.isEmpty())) {
                continue; // 2026-10-15：追人的光球、點名標記還在：綠色安全區的大招等它們結束才放（安全區期間只有這一招）
            }
            candidates.add(entry);
        }
        if (candidates.isEmpty()) {
            return;
        }
        BossSkillConfig.Entry chosen = candidates.get(random.nextInt(candidates.size()));
        for (final BossSkillConfig.Entry entry : candidates) {
            if (entry.skill().kind() == BossSkill.Kind.ARENA) {
                chosen = entry; // 場地收縮能放就先放（換到那個階段一開始就收縮，不要打到一半才縮）
                break;
            }
        }
        if (chosen.skill().kind() == BossSkill.Kind.ARENA) {
            brain.arenaWith = chosen.with(); // 收縮期間照 cooldown 一直落（tickArena）
        }
        start(brain, chosen.skill(), target, now);
        if (!chosen.with().isEmpty()) {
            combo(brain, chosen.with(), target, now);
        }
    }

    /**
     * 兩招同時放（2026-10，招式組的 {@code with:}）：Boss 放手上這一招的同時，這些招一起落下——
     * 延遲落下、地面效果照場地危害的方式落（地上先標、時間到才炸），光球、點名標記直接放出去。
     * 它們都不用 Boss 站著，所以 Boss 照樣把手上這一招放完；落下之前不出下一招（{@link BossPacing}）。
     */
    private void combo(final Brain brain, final List<BossSkill> with, final LivingEntity target, final long now) {
        for (final BossSkill extra : with) {
            switch (extra.kind()) {
                case ORBS -> spawnOrbs(brain, extra, target, now);
                case MARK -> spawnMarks(brain, extra, target, now);
                case SLAM, ZONE -> dropPending(brain, fitArena(brain, extra, now), target, now, "combo");
                default -> {
                }
            }
        }
    }

    /**
     * Boss 換到下一個階段（地圖 Boss 的 phases）：先停 phase_pause 秒不出新招（喊話、召喚、護盾先出來，看得到發生什麼事），
     * 這個階段才會用的招再晚一點（phase_unlock 秒）才開始用——以前一換階段，新學會的大招全部同時可以用，常常馬上接一招。
     * 正在放的招照常放完；重置回第一階段時 {@link Brain#reset} 已經處理。
     */
    private void checkPhase(final Brain brain, final long now) {
        final int phase = brain.host.phase();
        if (phase == brain.phase) {
            return;
        }
        final int from = brain.phase;
        brain.phase = phase;
        if (phase < from || !brain.host.boss()) {
            return;
        }
        final BossSkillConfig.Settings settings = settings();
        brain.globalReadyAt = Math.max(brain.globalReadyAt, now + Math.round(settings.phasePause() * 20));
        brain.pacing.delayHazards(brain.globalReadyAt); // 換階段的停頓裡場地危害也不落（停頓比 gap 長，不然空檔的規則會讓它落）
        final long unlock = now + Math.round(settings.phaseUnlock() * 20);
        for (final String id : BossSkillConfig.unlockedBetween(brain.kit, from, phase)) {
            brain.readyAt.merge(id, unlock, Math::max);
        }
    }

    private boolean ready(final Brain brain, final BossSkill skill, final long now) {
        return now >= brain.readyAt.getOrDefault(skill.id(), 0L);
    }

    private int aliveSummons(final Brain brain) {
        int alive = 0;
        for (final UUID uuid : brain.summons.keySet()) {
            final Entity entity = plugin.getServer().getEntity(uuid);
            if (entity != null && entity.isValid() && !entity.isDead()) {
                alive++;
            }
        }
        return alive;
    }

    private void expireSummons(final Brain brain, final long now) {
        final Iterator<Map.Entry<UUID, Long>> iterator = brain.summons.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Long> entry = iterator.next();
            final Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (entity == null || !entity.isValid() || entity.isDead()) {
                iterator.remove();
            } else if (entry.getValue() > 0 && now >= entry.getValue()) {
                visuals.minionBlink(entity.getLocation());
                entity.remove();
                iterator.remove();
            }
        }
    }

    // ---- 原版 AI：放招時暫停 ----

    /**
     * 放招的時候（前搖到放完）原版的 AI 暫停：近戰、射箭、伏守者的音波、喚魔者的法術都不會跟招式同時出現。
     * 出招那一 tick 只把速度歸零、停下尋路（見 {@link #start}），下一 tick 才暫停；放完（或取消）下一 tick 恢復（{@link #release}）。
     * 異常狀態（凍結、暈眩）也會暫停 AI：AI 已經停了就不搶；放完時正被凍結、暈眩的，交給異常狀態等控制結束才恢復。
     */
    private void syncAi(final Brain brain, final long now) {
        if (!(brain.boss instanceof Mob mob)) {
            return;
        }
        if (brain.cast != null && brain.cast.start < now) {
            if (!brain.aiPaused && mob.isAware()) {
                mob.setAware(false);
                mob.getPersistentDataContainer().set(pausedKey, PersistentDataType.BYTE, (byte) 1);
                brain.aiPaused = true;
            }
        } else if (brain.cast == null && (brain.aiPaused || brain.anchored)) {
            release(brain);
        }
    }

    /**
     * 放招時歸零的速度、暫停的原版 AI 恢復。正被凍結、暈眩的交給異常狀態（{@link tw.exilecore.ailment.AilmentService#adoptPause}），
     * 控制結束時才恢復。實體已經不在就只清旗標：記號留著，下次載入時 {@link #recover} 恢復（速度的修飾器不會存檔）。
     */
    private void release(final Brain brain) {
        if (brain.anchored) {
            brain.anchored = false;
            anchor(brain.boss, false);
        }
        if (!brain.aiPaused) {
            return;
        }
        brain.aiPaused = false;
        if (brain.boss instanceof Mob mob && mob.isValid()) {
            mob.getPersistentDataContainer().remove(pausedKey);
            if (plugin.ailments() == null || !plugin.ailments().adoptPause(mob)) {
                mob.setAware(true);
            }
        }
    }

    /** 放招時移動速度（走路、飛行）歸零；放完拿掉。 */
    private void anchor(final LivingEntity boss, final boolean on) {
        if (!boss.isValid()) {
            return;
        }
        for (final Attribute attribute : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.FLYING_SPEED}) {
            final AttributeInstance instance = boss.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            instance.removeModifier(anchorKey);
            if (on) {
                instance.addTransientModifier(new AttributeModifier(anchorKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
    }

    /** 上次放招時暫停、沒來得及恢復的（區塊卸載、當機）：載入時恢復。現在正在放招的那隻不動。 */
    void recover(final Mob mob) {
        if (!mob.getPersistentDataContainer().has(pausedKey)) {
            return;
        }
        final Brain brain = brains.get(mob.getUniqueId());
        if (brain != null && brain.aiPaused && brain.boss == mob) {
            return;
        }
        mob.getPersistentDataContainer().remove(pausedKey);
        mob.setAware(true);
    }

    @EventHandler
    public void onAdd(final EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            recover(mob);
        }
    }

    /** 技能模組啟用時：已經載入的怪物裡有沒有上次沒恢復的。 */
    void recoverLoaded() {
        for (final World world : plugin.getServer().getWorlds()) {
            for (final LivingEntity living : world.getLivingEntities()) {
                if (living instanceof Mob mob) {
                    recover(mob);
                }
            }
        }
    }

    /** 被凍結、暈眩、電擊（異常狀態暫停了 AI）。 */
    private boolean incapacitated(final LivingEntity boss) {
        return plugin.ailments() != null && plugin.ailments().isIncapacitated(boss);
    }

    /** 被控住，或正在做原版的大動作（伏守者吼叫、鑽出地面、鑽回地底）：不出新的招，場地危害也等。 */
    private boolean held(final LivingEntity boss) {
        if (incapacitated(boss)) {
            return true;
        }
        if (!(boss instanceof Warden)) {
            return false;
        }
        final Pose pose = boss.getPose();
        return pose == Pose.ROARING || pose == Pose.EMERGING || pose == Pose.DIGGING;
    }

    /** 掛了 Boss 招式的伏守者不用原版音波（{@link SonicBoomLock}）：每秒把冷卻再設長一點。 */
    private void lockSonicBoom(final Brain brain) {
        if (brain.host.boss() && brain.boss instanceof Warden warden) {
            sonicBoom.lock(warden, 100);
        }
    }

    // ---- 目標 ----

    /** 走技能系統的玩家才算目標：有角色、活著、生存或冒險模式、跟 Boss 同一個世界。 */
    private boolean validPlayer(final Player player, final World world) {
        if (!player.isOnline() || player.isDead() || !player.getWorld().equals(world)) {
            return false;
        }
        final GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return false;
        }
        final PlayerState state = plugin.players().get(player);
        return state.hasCharacter() && !state.isDead() && !state.isSafe(); // 入場保護中的人不是目標
    }

    /** 現在盯著的是不是一個有效的玩家目標（是的話每 tick 都能出招，不用再找）。 */
    private boolean hasTarget(final Brain brain) {
        return brain.boss instanceof Mob mob && mob.getTarget() instanceof Player current && validPlayer(current, brain.boss.getWorld());
    }

    /** Boss 原本盯著的玩家（夠近）或最近的玩家（遠的要看得到）。 */
    private LivingEntity pickTarget(final Brain brain) {
        final LivingEntity boss = brain.boss;
        final double search = brain.host.boss() ? SEARCH : SEARCH_MINION;
        if (boss instanceof Mob mob && mob.getTarget() instanceof Player current && validPlayer(current, boss.getWorld())
                && current.getLocation().distanceSquared(boss.getLocation()) <= search * search) {
            return current;
        }
        Player best = null;
        double bestDistance = search * search;
        for (final Player player : boss.getWorld().getPlayers()) {
            if (!validPlayer(player, boss.getWorld())) {
                continue;
            }
            final double distance = player.getLocation().distanceSquared(boss.getLocation());
            if (distance < bestDistance && (distance <= CLOSE * CLOSE || boss.hasLineOfSight(player))) {
                bestDistance = distance;
                best = player;
            }
        }
        if (best != null && boss instanceof Mob mob) {
            mob.setTarget(best);
        }
        return best;
    }

    /** 範圍內會被打到的人：玩家與玩家的召喚物（距離量到碰撞箱，高度差 HEIGHT 內）。 */
    private List<LivingEntity> victims(final Brain brain, final Location center, final double radius) {
        final List<LivingEntity> list = new ArrayList<>();
        final MinionHooks minions = plugin.combat() == null ? null : plugin.combat().minions();
        for (final Entity entity : center.getWorld().getNearbyEntities(center, radius + 2, HEIGHT + 2, radius + 2)) {
            if (!(entity instanceof LivingEntity living) || living == brain.boss) {
                continue;
            }
            final boolean ok = living instanceof Player player ? validPlayer(player, center.getWorld())
                    : minions != null && minions.isMinion(living) && living.isValid() && !living.isDead();
            if (!ok) {
                continue;
            }
            if (SkillTargets.boxDistanceSquared(center, living.getBoundingBox()) <= radius * radius
                    && living.getBoundingBox().getMaxY() >= center.getY() - HEIGHT && living.getBoundingBox().getMinY() <= center.getY() + HEIGHT) {
                list.add(living);
            }
        }
        return list;
    }

    private List<Player> playersNear(final Location center, final double radius) {
        final List<Player> list = new ArrayList<>();
        for (final Player player : center.getWorld().getPlayers()) {
            if (validPlayer(player, center.getWorld()) && player.getLocation().distanceSquared(center) <= radius * radius) {
                list.add(player);
            }
        }
        return list;
    }

    // ---- 出招 ----

    private void start(final Brain brain, final BossSkill picked, final LivingEntity target, final long now) {
        final LivingEntity boss = brain.boss;
        final BossSkill skill = fitArena(brain, picked, now);
        // Boss 的招式冷卻再乘 boss_cooldown（2026-10：0.8，Boss 出招快一點；一般怪物照寫的）
        brain.readyAt.put(skill.id(), now + Math.round(skill.cooldown() * 20 * cooldownFactor(boss) * settings().cooldownFor(brain.host.boss())
                * tempo(brain)));
        final Vector facing = facing(boss, target);
        final List<Location> points = skill.kind() == BossSkill.Kind.SLAM || skill.kind() == BossSkill.Kind.ZONE
                ? points(brain, skill, target) : List.of();
        final Cast cast = new Cast(skill, now, settings().telegraphTicks(skill, brain.host.boss()), target, facing, points);
        cast.mark = boss.getUniqueId() + ":" + skill.id() + ":" + now;
        switch (skill.kind()) {
            case BEAM -> {
                cast.beamStart = Math.atan2(facing.getZ(), facing.getX());
                cast.beamSign = random.nextBoolean() ? 1 : -1;
            }
            case RING -> cast.gaps = BossGeometry.gapCenters(skill.gaps(), random.nextDouble() * Math.PI * 2);
            case SANCTUARY -> {
                cast.safeSpots = safeSpots(brain, skill);
                channeling.add(brain); // 2026-10-15：安全區還在的時候，附近的 Boss 不出招、圈裡的荒蕪不落
            }
            default -> {
            }
        }
        brain.cast = cast;
        if (boss instanceof Mob mob) {
            mob.setTarget(target);
            // 先把移動速度歸零、停下尋路：AI 這個 tick 再跑一次，算出來的速度是 0（射箭時的左右橫移也是），
            // 下一 tick 才暫停 AI——不然暫停後會照最後的速度與方向一直滑（地上的警告畫在原地，Boss 卻滑走了）
            if (!brain.anchored) {
                anchor(boss, true);
                brain.anchored = true;
            }
            mob.getPathfinder().stopPathfinding();
        }
        markGround(brain, cast);
        visuals.bossCharge(skill.visual(), boss, true, brain.host.boss());
        if (brain.host.boss()) {
            // 附近的玩家：喊話（一般怪物只有地上的警告）。動作列不寫招式（2026-10 照使用者的意思拿掉：
            // 特殊招式有畫面中間的大字與聊天室，其他的看地板的顏色）
            if (skill.shout() != null && !skill.shout().isBlank()) {
                for (final Player player : playersNear(boss.getLocation(), settings().warnRange())) {
                    player.sendMessage(plugin.text().parse(Zone.loreLine(brain.host.name(), skill.shout())));
                }
            }
            alert(brain, skill, now);
        }
        if (cast.telegraphEnd <= now) {
            advance(brain, cast, now);
        }
    }

    /**
     * 目標還在這隻 Boss 的世界裡（放招到一半目標死了、回營地或換世界：不能再對著他轉頭、量距離，
     * 不然 {@code lookAt}、{@code distance} 會丟例外，這一招就整個出錯取消）。
     */
    private static boolean here(final LivingEntity boss, final LivingEntity target) {
        return target != null && target.isValid() && !target.isDead() && target.getWorld().equals(boss.getWorld());
    }

    /** 從 Boss 看向目標的水平方向。 */
    private static Vector facing(final LivingEntity boss, final LivingEntity target) {
        final Vector to = target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (to.lengthSquared() < 1e-6) {
            final double yaw = Math.toRadians(boss.getLocation().getYaw());
            return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        return to.normalize();
    }

    /**
     * 場地收縮中（萬界崩解、天穹收攏，2026-10）：落在腳下的延遲落下、地面效果（滅世殞星、荒蕪、殞星）照落下那一刻的圈縮小，
     * 不然半徑 8 的圈被一顆 6.5 的殞星和 13 秒的荒蕪佔掉大半，沒地方躲。傷害不變；圖形招（迷宮、走廊…）和圈裡的荒蕪另外處理。
     */
    private BossSkill fitArena(final Brain brain, final BossSkill skill, final long now) {
        final Arena arena = brain.arena;
        if (arena == null || skill.kind() != BossSkill.Kind.SLAM && skill.kind() != BossSkill.Kind.ZONE) {
            return skill;
        }
        switch (skill.at()) {
            case SPIRAL, CROSS, LINE, CAGE, MAZE, WALLS, ARENA, OUTSIDE -> {
                return skill;
            }
            default -> {
            }
        }
        final long landing = now + settings().telegraphTicks(skill, brain.host.boss())
                + (skill.kind() == BossSkill.Kind.SLAM ? Math.round(skill.delay() * 20) : 0);
        final double[] fit = BossGeometry.fitArena(skill.radius(), skill.count(), skill.scatter(), skill.duration(),
                skill.kind() == BossSkill.Kind.ZONE, arena.radius(landing));
        if (fit[0] == skill.radius() && fit[1] == skill.scatter() && fit[2] == skill.duration()) {
            return skill;
        }
        return skill.sized(fit[0], fit[1], fit[2]);
    }

    /** 圈外的荒蕪（at: outside）一次最多落幾個。 */
    static final int OUTSIDE_MAX = 32;

    /** 延遲落下、地面效果的落點。 */
    private List<Location> points(final Brain brain, final BossSkill skill, final LivingEntity target) {
        switch (skill.at()) {
            case SPIRAL, CROSS, LINE, CAGE, MAZE, WALLS -> {
                return patternPoints(brain, skill, target);
            }
            default -> {
            }
        }
        final List<Location> points = new ArrayList<>();
        if (skill.at() == BossSkill.Target.OUTSIDE) {
            // 2026-10-15：圈外鋪滿荒蕪（萬界崩解）：落下那一刻的圈邊到一開始的圈再外 2 格之間，平均灑 count 個
            final Arena arena = brain.arena;
            if (arena == null) {
                return points;
            }
            final long landing = now() + settings().telegraphTicks(skill, brain.host.boss());
            final double[] band = BossGeometry.outsideBand(arena.radius(landing), arena.skill.reach(), skill.radius());
            if (band == null) {
                return points;
            }
            final int total = Math.min(OUTSIDE_MAX, Math.max(1, skill.count()));
            for (int i = 0; i < total; i++) {
                final double angle = random.nextDouble() * Math.PI * 2;
                final double distance = BossGeometry.inBand(band[0], band[1], random.nextDouble());
                points.add(ground(arena.center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance)));
            }
            return points;
        }
        if (skill.at() == BossSkill.Target.ARENA && brain.arena != null) {
            // 2026-10：落在收縮中的圈裡（整個都在圈內），每個在場的人 count 個（最多 12 個）
            final Arena arena = brain.arena;
            final double inner = Math.max(0.5, arena.radius(now()) - skill.radius() - 0.5);
            final int people = Math.max(1, playersNear(brain.boss.getLocation(), SEARCH).size());
            final int total = Math.min(12, Math.max(1, skill.count()) * people);
            for (int i = 0; i < total; i++) {
                final double angle = random.nextDouble() * Math.PI * 2;
                final double distance = Math.sqrt(random.nextDouble()) * inner;
                points.add(ground(arena.center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance)));
            }
            return points;
        }
        final List<Player> players = skill.at() == BossSkill.Target.RANDOM ? playersNear(brain.boss.getLocation(), skill.range() + 4) : List.of();
        for (int i = 0; i < skill.count(); i++) {
            Location base = switch (skill.at()) {
                case SELF -> brain.boss.getLocation();
                case TARGET -> target.getLocation();
                case RANDOM -> players.isEmpty() ? target.getLocation() : players.get(random.nextInt(players.size())).getLocation();
                default -> brain.boss.getLocation(); // 圖形的落點在上面另外算
            };
            base = base.clone();
            if (skill.scatter() > 0 && (i > 0 || skill.count() == 1 || skill.at() == BossSkill.Target.RANDOM)) {
                final double angle = random.nextDouble() * Math.PI * 2;
                final double distance = Math.sqrt(random.nextDouble()) * skill.scatter();
                base.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            }
            points.add(ground(base));
        }
        return points;
    }

    /**
     * 2026-10（世界 Boss 的招式）：照圖形排的落點，照順序一顆一顆落（stagger）——
     * 螺旋從腳下轉出去、十字往四個方向炸出去、直線往目標追過去、星牢圍著目標一圈。reach 是最遠幾格（沒寫 14）。
     */
    private List<Location> patternPoints(final Brain brain, final BossSkill skill, final LivingEntity target) {
        final List<Location> points = new ArrayList<>();
        final Location center = brain.boss.getLocation();
        final double reach = skill.reach() > 0 ? skill.reach() : 14;
        final int count = Math.max(1, skill.count());
        switch (skill.at()) {
            case SPIRAL -> {
                final double start = random.nextDouble() * Math.PI * 2;
                final double turn = random.nextBoolean() ? 0.6 : -0.6;
                for (int i = 0; i < count; i++) {
                    final double angle = start + i * turn;
                    final double distance = 2 + (reach - 2) * i / Math.max(1, count - 1);
                    points.add(ground(center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance)));
                }
            }
            case CROSS -> {
                final double start = random.nextBoolean() ? 0 : Math.PI / 4;
                final int steps = Math.max(1, count / 4);
                for (int k = 0; k < steps; k++) {
                    final double distance = 2.5 + (reach - 2.5) * k / Math.max(1, steps - 1);
                    for (int arm = 0; arm < 4; arm++) {
                        final double angle = start + arm * Math.PI / 2;
                        points.add(ground(center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance)));
                    }
                }
            }
            case LINE -> {
                final Vector dir = facing(brain.boss, target);
                for (int i = 0; i < count; i++) {
                    final double distance = 2 + (reach - 2) * i / Math.max(1, count - 1);
                    points.add(ground(center.clone().add(dir.clone().multiply(distance))));
                }
            }
            case CAGE -> {
                final Location middle = here(brain.boss, target) ? target.getLocation() : center;
                final double radius = skill.scatter() > 0 ? skill.scatter() : 6;
                final double start = random.nextDouble() * Math.PI * 2;
                for (int i = 0; i < count; i++) {
                    final double angle = start + i * Math.PI * 2 / count;
                    points.add(ground(middle.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius)));
                }
            }
            case MAZE -> {
                // 2026-10（賽勒斯的殞星迷宮）：count 圈同心的風暴牆，每圈 gaps 個缺口、各圈錯開；一塊接一塊照距離鋪
                // 萬界崩解收縮中：以收縮圈的中心為準、牆縮進圈裡（牆消失那一刻的圈 ×0.8）、最多兩圈、每圈三個缺口
                final Arena arena = brain.arena;
                final double arenaRadius = arena == null ? 0
                        : arena.radius(now() + settings().telegraphTicks(skill, brain.host.boss()) + Math.round(skill.duration() * 20));
                final double[] fit = BossGeometry.mazeInArena(reach, Math.max(1, Math.min(5, count)), Math.max(1, skill.gaps()), arenaRadius);
                final Location middle = arena != null ? arena.center.clone() : center;
                final double mazeReach = fit[0];
                final int rings = (int) fit[1];
                final int gaps = (int) fit[2];
                // 相鄰兩塊的間距：一定比一塊的直徑小（重疊），牆才連得起來（2026-10-15：牆調細之後原本至少 1.5 格會出現縫）
                final double step = Math.max(1.0, skill.radius() * 1.6);
                final double gapAngle = Math.toRadians(skill.gapAngle());
                double offset = random.nextDouble() * Math.PI * 2;
                for (int ring = 1; ring <= rings; ring++) {
                    final double distance = mazeReach * ring / rings;
                    final double[] centers = BossGeometry.gapCenters(gaps, offset);
                    final int slots = Math.max(6, (int) Math.round(Math.PI * 2 * distance / step));
                    for (int i = 0; i < slots; i++) {
                        final double angle = i * Math.PI * 2 / slots;
                        if (BossGeometry.inGap(angle, centers, gapAngle)) {
                            continue; // 缺口：留路
                        }
                        points.add(ground(middle.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance)));
                    }
                    offset += Math.PI / gaps; // 下一圈的缺口錯開半格：要繞著走
                }
            }
            case WALLS -> {
                // 2026-10（賽勒斯的風暴走廊）：朝目標的方向，左右各 scatter 格兩排牆，從 2 格鋪到 reach 格
                final Vector dir = facing(brain.boss, target);
                final Vector side = new Vector(-dir.getZ(), 0, dir.getX());
                final double half = skill.scatter() > 0 ? skill.scatter() : 4;
                final double step = Math.max(1.5, skill.radius() * 1.7);
                for (double distance = 2; distance <= reach; distance += step) {
                    final Location along = center.clone().add(dir.clone().multiply(distance));
                    points.add(ground(along.clone().add(side.clone().multiply(half))));
                    points.add(ground(along.clone().add(side.clone().multiply(-half))));
                }
            }
            default -> {
            }
        }
        return points;
    }

    /** 往下（最多 6 格）或往上（最多 3 格）找地面，落點放在地面上。 */
    private static Location ground(final Location at) {
        final Location probe = at.clone();
        probe.setY(Math.floor(at.getY()) + 0.5);
        if (!probe.getBlock().isPassable()) {
            for (int up = 1; up <= 3; up++) {
                final Location above = probe.clone().add(0, up, 0);
                if (above.getBlock().isPassable()) {
                    above.setY(Math.floor(above.getY()));
                    return above;
                }
            }
            return at;
        }
        for (int down = 0; down <= 6; down++) {
            final Location below = probe.clone().add(0, -down - 1, 0);
            if (!below.getBlock().isPassable()) {
                final Location result = probe.clone().add(0, -down, 0);
                result.setY(Math.floor(result.getY()));
                return result;
            }
        }
        return at;
    }

    private void advance(final Brain brain, final Cast cast, final long now) {
        final LivingEntity boss = brain.boss;
        final BossSkill skill = cast.skill;
        if (!cast.fired) {
            if (now < cast.telegraphEnd) {
                hold(brain, cast);
                // 警告的粒子 Boss 每 2 tick、小怪每 3 tick 畫一次（地板變色才是主要的警告；粒子一點一個封包，
                // 每 tick 畫的話幾招疊在一起時客戶端處理不完，畫面會卡一下）
                final boolean draw = drawTick(brain, now, 0);
                if (draw) {
                    visuals.bossCharge(skill.visual(), boss, false, brain.host.boss());
                }
                final double progress = (now - cast.start) / (double) Math.max(1, cast.telegraphEnd - cast.start);
                warn(brain, cast, progress, now, draw);
                if (skill.kind() != BossSkill.Kind.SLAM && skill.kind() != BossSkill.Kind.ZONE && skill.kind() != BossSkill.Kind.BEAM
                        && skill.kind() != BossSkill.Kind.SANCTUARY && skill.kind() != BossSkill.Kind.RING) {
                    flash(cast.mark, skill, progress, now);
                    warnInside(brain, skill, cast.mark, now);
                }
                return;
            }
            cast.fired = true;
            fire(brain, cast, now);
            if (brain.cast != cast) {
                return; // 已經結束
            }
        }
        switch (skill.kind()) {
            case SLAM -> {
                while (cast.done < cast.points.size() && now >= cast.nextTick) {
                    danger.hide(cast.mark + "#" + cast.done);
                    impact(brain, skill, cast.points.get(cast.done));
                    cast.done++;
                    cast.nextTick += Math.max(1, Math.round(skill.stagger() * 20));
                }
                for (int i = cast.done; i < cast.points.size(); i++) {
                    final long land = cast.nextTick + (i - cast.done) * Math.max(1, Math.round(skill.stagger() * 20));
                    final long shownSince = cast.start;
                    final double progress = Math.min(1, (now - shownSince) / (double) Math.max(1, land - shownSince));
                    if (drawTick(brain, now, i)) {
                        visuals.bossWarningRing(cast.points.get(i), skill.radius(), progress);
                    }
                    flash(cast.mark + "#" + i, skill, progress, now);
                    warnInside(brain, skill, cast.mark + "#" + i, now);
                }
                if (cast.done >= cast.points.size()) {
                    finish(brain, now);
                }
            }
            case NOVA, CONE -> {
                while (cast.done < skill.count() && now >= cast.nextTick) {
                    wave(brain, cast);
                    cast.done++;
                    cast.nextTick += Math.max(1, Math.round(skill.stagger() * 20));
                }
                if (cast.done < skill.count()) {
                    hold(brain, cast);
                } else {
                    finish(brain, now);
                }
            }
            case CHARGE -> {
                if (!charge(brain, cast)) {
                    finish(brain, now);
                }
            }
            case BLINK -> {
                if (cast.landAt == null) {
                    finish(brain, now);
                } else if (now >= cast.landTick) {
                    visuals.bossLand(skill.visual(), cast.landAt, skill.radius());
                    for (final LivingEntity victim : victims(brain, cast.landAt, skill.radius())) {
                        hurt(brain, skill, victim, Tags.AREA);
                    }
                    finish(brain, now);
                } else {
                    hold(brain, cast);
                    final double progress = (now - cast.telegraphEnd) / (double) LAND_TICKS;
                    if (drawTick(brain, now, 0)) {
                        visuals.bossWarningRing(cast.landAt, skill.radius(), progress);
                    }
                    flash(cast.mark, skill, progress, now);
                    warnInside(brain, skill, cast.mark, now);
                }
            }
            case BEAM -> sweep(brain, cast, now);
            case RING -> {
                while (cast.done < skill.count() && now >= cast.nextTick) {
                    final RingWave wave = new RingWave(skill, ground(boss.getLocation()), cast.gaps, now);
                    wave.mark = boss.getUniqueId() + ":wave:" + now + "#" + cast.done;
                    brain.rings.add(wave);
                    cast.done++;
                    cast.nextTick += Math.max(1, Math.round(skill.stagger() * 20));
                }
                hold(brain, cast);
                if (now % 2 == 0) {
                    for (final double gap : cast.gaps) {
                        visuals.safeLane(boss.getLocation(), gap, skill.reach());
                    }
                }
                if (now >= cast.channelEnd) {
                    finish(brain, now);
                }
            }
            case PULL -> pull(brain, cast, now);
            case ECHOES -> echoes(brain, cast, now);
            default -> finish(brain, now);
        }
    }

    /**
     * 前搖、多波之間：站著不動、盯著目標。原版 AI 暫停時（{@link #syncAi}）看向目標的控制不會動，直接轉身：
     * 扇形、衝鋒、光束的方向出招時就決定了，面向那個方向；其他的面向目標。
     */
    private void hold(final Brain brain, final Cast cast) {
        final LivingEntity boss = brain.boss;
        if (boss instanceof Mob mob) {
            mob.getPathfinder().stopPathfinding();
            if (brain.aiPaused) {
                face(boss, cast);
                if (boss.isJumping()) {
                    boss.setJumping(false); // 暫停前那一 tick 要跨上一格的話，跳的旗標會一直留著（原地一直跳）
                }
            } else if (here(boss, cast.target)) {
                mob.lookAt(cast.target);
            }
        }
        final Vector velocity = boss.getVelocity();
        boss.setVelocity(new Vector(0, Math.min(0, velocity.getY()), 0));
    }

    /** 轉身面向這一招的方向（扇形、衝鋒、光束）或目標。 */
    private static void face(final LivingEntity boss, final Cast cast) {
        final Vector direction = switch (cast.skill.kind()) {
            case CONE, CHARGE, BEAM -> cast.facing.clone();
            default -> here(boss, cast.target)
                    ? cast.target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0) : null;
        };
        if (direction == null || direction.lengthSquared() < 1e-6) {
            return;
        }
        final float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
        if (Math.abs(Location.normalizeYaw(boss.getLocation().getYaw() - yaw)) < 2) {
            return; // 已經面向那裡
        }
        boss.setRotation(yaw, boss.getLocation().getPitch());
        boss.setBodyYaw(yaw);
    }

    /**
     * 前搖時地上的警告。
     *
     * @param draw 這個 tick 要不要畫粒子（地板標示與「你在範圍裡」每個 tick 都做）
     */
    private void warn(final Brain brain, final Cast cast, final double progress, final long now, final boolean draw) {
        final BossSkill skill = cast.skill;
        final Location feet = brain.boss.getLocation();
        switch (skill.kind()) {
            case SLAM, ZONE -> {
                for (int i = 0; i < cast.points.size(); i++) {
                    final double pointProgress = skill.kind() == BossSkill.Kind.ZONE ? progress
                            : Math.min(1, (now - cast.start) / (double) Math.max(1, cast.telegraphEnd + Math.round(skill.delay() * 20) - cast.start));
                    if (drawTick(brain, now, i)) { // 好幾個圈錯開 tick 畫
                        visuals.bossWarningRing(cast.points.get(i), skill.radius(), pointProgress);
                    }
                    flash(cast.mark + "#" + i, skill, pointProgress, now);
                    warnInside(brain, skill, cast.mark + "#" + i, now);
                }
            }
            case NOVA, CURSE -> {
                if (draw) {
                    visuals.bossWarningRing(feet, skill.radius(), progress);
                }
            }
            case CONE -> {
                if (draw) {
                    visuals.bossWarningCone(feet, cast.facing, skill.length(), skill.angle(), progress);
                }
            }
            case CHARGE -> {
                if (draw) {
                    visuals.bossWarningLine(feet, cast.facing, skill.reach(), skill.width(), progress);
                }
            }
            case VOLLEY -> {
                if (draw) {
                    visuals.bossWarningCone(feet, cast.facing, Math.min(8, skill.reach()), Math.max(10, skill.spread()), progress);
                }
            }
            case BEAM -> {
                if (skill.track() && cast.target != null && cast.target.isValid() && cast.target.getWorld() == feet.getWorld()) {
                    // 2026-10（賽勒斯的「死」）：前搖時紅線一直跟著目標轉——站著不動一定被打到，要在放出來那一刻往旁邊走
                    final Vector to = facing(brain.boss, cast.target);
                    cast.beamStart = Math.atan2(to.getZ(), to.getX());
                    if ((now + brain.offset) % 4 == 0) {
                        for (int i = 0; i < skill.count(); i++) {
                            final double angle = BossGeometry.beamAngle(cast.beamStart, i, skill.count(), 0, cast.beamSign, 0);
                            danger.show(cast.mark + "#b" + i, feet, DangerZones.strip(feet, new Vector(Math.cos(angle), 0, Math.sin(angle)),
                                    skill.length(), skill.width()), DangerZones.colorFor(skill.type()));
                        }
                    }
                }
                if (draw) {
                    for (int i = 0; i < skill.count(); i++) {
                        final double angle = BossGeometry.beamAngle(cast.beamStart, i, skill.count(), 0, cast.beamSign, 0);
                        visuals.bossWarningLine(feet, new Vector(Math.cos(angle), 0, Math.sin(angle)), skill.length(), skill.width(), progress);
                    }
                }
                for (int i = 0; i < skill.count(); i++) {
                    flash(cast.mark + "#b" + i, skill, progress, now);
                    warnInside(brain, skill, cast.mark + "#b" + i, now);
                }
            }
            case RING -> {
                if (draw) {
                    // 大圈的粒子隔一個 tick 畫一次（地板的綠色缺口才是主要的提示）
                    visuals.bossWarningRing(feet, skill.reach(), progress);
                    for (final double gap : cast.gaps) {
                        visuals.safeLane(feet, gap, skill.reach());
                    }
                }

            }
            case PULL -> {
                if (draw) {
                    visuals.bossWarningRing(feet, skill.radius(), progress);
                    visuals.bossWarningRing(feet, skill.explode(), progress);
                }
                flash(cast.mark, skill, progress, now);
            }
            case SANCTUARY -> {
                // 範圍很大：外圈、安全區各每 4 tick 畫一次，錯開兩個 tick（地板的綠色才是主要的提示）
                if ((now + brain.offset) % 4 == 0) {
                    visuals.bossWarningRing(feet, skill.radius(), progress);
                }
                if ((now + brain.offset) % 4 == 2) {
                    for (final Location spot : cast.safeSpots) {
                        visuals.safeZone(spot, skill.safe());
                    }
                }
                warnOutsideSafety(brain, cast, now);
            }
            case ECHOES -> {
                if (draw) {
                    visuals.bossWarningRing(feet, skill.scatter(), progress); // 殘影會出現在這一圈上
                }
            }
            case ARENA -> {
                if (draw) {
                    visuals.bossWarningRing(feet, skill.reach(), progress);
                    visuals.bossWarningRing(feet, skill.radius(), progress); // 最後會縮到這麼小
                }
            }
            case BLINK -> {
                if (here(brain.boss, cast.target)) {
                    // 要跳到你身邊：你腳下畫圈、地板變色，圈跟著你走（跑不掉的是「牠會來」，落地那一下才用走的躲）
                    final Location at = cast.target.getLocation();
                    final double radius = Math.max(1.5, skill.radius());
                    if (draw) {
                        visuals.bossWarningRing(at, radius, progress);
                    }
                    if (cast.markAt == null || !cast.markAt.getWorld().equals(at.getWorld()) || cast.markAt.distanceSquared(at) > REMARK_DISTANCE * REMARK_DISTANCE) {
                        cast.markAt = at.clone();
                        // 已經變紅了就照紅色標（不然跟著跑時整塊先換回原色再換紅，地板多重畫一次）
                        danger.show(cast.mark, at, DangerZones.disc(at, radius),
                                progress >= 0.6 ? DangerZones.FLASH : DangerZones.colorFor(skill.type()));
                    }
                    flash(cast.mark, skill, progress, now);
                }
            }
            default -> {
            }
        }
    }

    /** 前搖結束：各種招式真正發動。 */
    private void fire(final Brain brain, final Cast cast, final long now) {
        final LivingEntity boss = brain.boss;
        final BossSkill skill = cast.skill;
        switch (skill.kind()) {
            case SLAM -> cast.nextTick = now + Math.round(skill.delay() * 20);
            case NOVA, CONE -> cast.nextTick = now;
            case VOLLEY -> {
                // 2026-10：警示留在地上直到彈都飛完（換一個代號，finish 收的是前搖那一個）
                final String flying = boss.getUniqueId() + ":volley:" + now;
                final Location from = cast.anchor != null ? cast.anchor : boss.getLocation();
                danger.show(flying, from, DangerZones.sector(from, cast.facing, Math.min(VOLLEY_MARK, skill.reach()), Math.max(10, skill.spread() + 6)),
                        DangerZones.FLASH);
                danger.hide(cast.mark);
                volley(brain, cast, flying);
                finish(brain, now);
            }
            case ZONE -> {
                for (int i = 0; i < cast.points.size(); i++) {
                    danger.hide(cast.mark + "#" + i);
                    final ZoneArea zone = new ZoneArea(skill, cast.points.get(i), now, brain.boss.getUniqueId() + ":zone:" + now + "#" + i);
                    brain.zones.add(zone);
                    // 留在地上的這段時間地板一直是那個顏色（不閃）
                    danger.show(zone.mark, zone.center, DangerZones.disc(zone.center, skill.radius()), DangerZones.colorFor(skill.type()));
                }
                visuals.zonePulse(skill.visual(), cast.points, skill.radius());
                finish(brain, now);
            }
            case CHARGE -> cast.travelled = 0;
            case SUMMON -> {
                summon(brain, cast);
                finish(brain, now);
            }
            case CURSE -> {
                danger.hide(cast.mark);
                curse(brain, cast, now);
                finish(brain, now);
            }
            case BEAM -> {
                cast.channelEnd = now + Math.max(1, Math.round(skill.duration() * 20));
                cast.nextTick = now;
            }
            case RING -> {
                cast.nextTick = now;
                final long travel = (long) Math.ceil((skill.reach() - skill.radius()) / Math.max(0.05, skill.speed()));
                cast.channelEnd = now + Math.round((skill.count() - 1) * skill.stagger() * 20) + travel;
            }
            case PULL -> cast.channelEnd = now + Math.max(1, Math.round(skill.duration() * 20));
            case ORBS -> {
                spawnOrbs(brain, skill, cast.target, now);
                finish(brain, now);
            }
            case SANCTUARY -> {
                cataclysm(brain, cast);
                finish(brain, now);
            }
            case MARK -> {
                spawnMarks(brain, skill, cast.target, now);
                finish(brain, now);
            }
            case ARENA -> {
                danger.hidePrefix(cast.mark);
                startArena(brain, cast, now);
                finish(brain, now);
            }
            case ECHOES -> {
                cast.lances = lances(brain, cast, now);
                cast.channelEnd = cast.lances.isEmpty() ? now : cast.lances.getLast().hitTick;
            }
            case BLINK -> {
                danger.hide(cast.mark);
                final Location to = blink(brain, cast);
                if (to == null || !skill.pounce()) {
                    finish(brain, now);
                } else {
                    cast.landAt = to;
                    cast.landTick = now + LAND_TICKS;
                    danger.show(cast.mark, to, DangerZones.disc(to, skill.radius()), DangerZones.colorFor(skill.type()));
                }
            }
        }
    }

    /**
     * 附近有別的 Boss 正在放綠色安全區的大招（2026-10-15）：這隻先不出招。順便清掉已經放完的。
     */
    private boolean underSanctuary(final Brain brain) {
        if (channeling.isEmpty()) {
            return false;
        }
        boolean held = false;
        final Iterator<Brain> iterator = channeling.iterator();
        while (iterator.hasNext()) {
            final Brain other = iterator.next();
            if (other.cast == null || other.cast.skill.kind() != BossSkill.Kind.SANCTUARY || !other.boss.isValid()
                    || brains.get(other.boss.getUniqueId()) != other) {
                iterator.remove();
                continue;
            }
            if (other == brain || !other.boss.getWorld().equals(brain.boss.getWorld())) {
                continue;
            }
            final Location center = other.cast.anchor != null ? other.cast.anchor : other.boss.getLocation();
            final double reach = other.cast.skill.radius() + SANCTUARY_HOLD_MARGIN;
            if (center.distanceSquared(brain.boss.getLocation()) <= reach * reach) {
                held = true;
            }
        }
        return held;
    }

    private void finish(final Brain brain, final long now) {
        if (brain.cast != null && brain.cast.mark != null) {
            danger.hidePrefix(brain.cast.mark);
        }
        brain.cast = null;
        // 放完一招至少隔 gap 秒（Boss 是 boss_gap）；換階段的停頓（比較久）不會被放完的這一招蓋掉
        brain.globalReadyAt = Math.max(brain.globalReadyAt,
                now + Math.round(settings().gapFor(brain.host.boss()) * 20 * cooldownFactor(brain.boss) * tempo(brain)));
    }

    /**
     * 這隻怪出招的冷卻倍率：地圖詞綴「怪物技能冷卻時間縮短」（屬性 monster:skill.cooldown，進怪物的屬性表）算進來，
     * 招式的冷卻與兩招之間的空檔一起縮；最快縮到兩成五。沒有等級化的怪物、沒有加成都是 1。
     */
    private double cooldownFactor(final LivingEntity boss) {
        if (plugin.monsters() == null) {
            return 1.0;
        }
        final java.util.Optional<tw.exilecore.monster.MonsterData> data = plugin.monsters().data(boss);
        if (data.isEmpty()) {
            return 1.0;
        }
        tw.exilecore.stats.StatSheet sheet = plugin.monsters().sheet(boss, data.get());
        if (plugin.combat() != null) {
            // 2026-10：遲滯詛咒（招式冷卻變長）
            final java.util.List<tw.exilecore.stats.Modifier> cursed = plugin.combat().debuffs().modifiers(boss.getUniqueId(), null, plugin.getServer().getCurrentTick());
            if (!cursed.isEmpty()) {
                sheet = sheet.overlay(cursed);
            }
        }
        return Formulas.monsterCooldownFactor(sheet.apply(StatKeys.SKILL_COOLDOWN, 1.0, Set.of()));
    }

    // ---- 地面標示與「你在範圍裡」 ----

    /** 出招時把危險範圍的地板標起來（照招式的形狀；閃現、撲擊是在前搖時跟著目標標，見 {@link #warn}）。 */
    private void markGround(final Brain brain, final Cast cast) {
        final BossSkill skill = cast.skill;
        final Location feet = brain.boss.getLocation();
        cast.anchor = feet.clone(); // 傷害照這裡算（跟標示一樣）
        final Material color = DangerZones.colorFor(skill.type());
        switch (skill.kind()) {
            case SLAM, ZONE -> {
                for (int i = 0; i < cast.points.size(); i++) {
                    danger.show(cast.mark + "#" + i, cast.points.get(i), DangerZones.disc(cast.points.get(i), skill.radius()), color);
                }
            }
            case NOVA -> danger.show(cast.mark, feet, DangerZones.disc(feet, skill.radius()), color);
            case CURSE -> danger.show(cast.mark, feet, DangerZones.disc(feet, skill.radius()), Material.PURPLE_CONCRETE);
            case CONE -> danger.show(cast.mark, feet, DangerZones.sector(feet, cast.facing, skill.length(), skill.angle()), color);
            case CHARGE -> danger.show(cast.mark, feet, DangerZones.strip(feet, cast.facing, skill.reach(), skill.width()), color);
            case VOLLEY -> danger.show(cast.mark, feet, DangerZones.sector(feet, cast.facing, Math.min(VOLLEY_MARK, skill.reach()), Math.max(10, skill.spread() + 6)), color);
            case BEAM -> {
                for (int i = 0; i < skill.count(); i++) {
                    final double angle = BossGeometry.beamAngle(cast.beamStart, i, skill.count(), 0, cast.beamSign, 0);
                    danger.show(cast.mark + "#b" + i, feet, DangerZones.strip(feet, new Vector(Math.cos(angle), 0, Math.sin(angle)),
                            skill.length(), skill.width()), color);
                }
            }
            case RING -> {
                danger.show(cast.mark + "#o", feet, DangerZones.ring(feet, skill.reach()), color); // 平面標示：整個範圍的邊界
                // 缺口是安全的：整條扇形標成綠色
                for (int i = 0; i < cast.gaps.length; i++) {
                    final double angle = cast.gaps[i];
                    danger.show(cast.mark + "#g" + i, feet, DangerZones.sector(feet, new Vector(Math.cos(angle), 0, Math.sin(angle)),
                            skill.reach(), skill.gapAngle()), DangerZones.SAFE);
                }
            }
            case PULL -> {
                danger.show(cast.mark + "#o", feet, DangerZones.ring(feet, skill.radius()), color); // 平面標示：會被拉的範圍
                danger.show(cast.mark, feet, DangerZones.disc(feet, Math.max(1, skill.explode())), color);
            }
            case ARENA -> danger.show(cast.mark + "#o", feet, DangerZones.ring(feet, skill.reach()), color); // 一開始的圈
            case SANCTUARY -> {
                danger.show(cast.mark + "#o", feet, DangerZones.ring(feet, skill.radius()), color); // 平面標示：整個範圍的邊界
                for (int i = 0; i < cast.safeSpots.size(); i++) {
                    final Location spot = cast.safeSpots.get(i);
                    danger.show(cast.mark + "#s" + i, spot, DangerZones.safeDisc(spot, skill.safe()), DangerZones.SAFE);
                }
            }
            default -> {
            }
        }
    }

    /**
     * 快落下（進度超過六成）時標示變成紅色（2026-10 改成一直紅、不再一亮一暗：每換一次顏色，客戶端就要重畫那一大片地板，
     * 範圍大的招式閃起來畫面會卡一下）。套了資源包的人看的是地上的平面，另外跟著進度慢慢填滿。
     */
    private void flash(final String mark, final BossSkill skill, final double progress, final long now) {
        if (!danger.has(mark)) {
            return;
        }
        danger.recolor(mark, progress >= 0.6 ? DangerZones.FLASH : DangerZones.colorFor(skill.type()));
        danger.progress(mark, progress); // 套了資源包的人：地上那一片從中間長滿就落下
    }

    /**
     * 這個 tick 要不要畫警告的粒子：Boss 每 2 tick、小怪每 3 tick；index 讓同一招的好幾個圈錯開 tick。
     * 粒子留在畫面上的時間比這個長，看起來一樣是連續的。
     */
    private static boolean drawTick(final Brain brain, final long now, final int index) {
        return (now + brain.offset + index) % (brain.host.boss() ? 2 : 3) == 0;
    }

    /**
     * 站在標示裡的玩家：畫面中間跳「離開紅色區域」（每 5 tick 重送一次，看起來是一直亮著）；
     * 終局與試煉 Boss 的大字寫招式名稱（{@link Host#alerts}）。
     */
    private void warnInside(final Brain brain, final BossSkill skill, final String mark, final long now) {
        if (now % 5 != 0 || !danger.has(mark)) {
            return;
        }
        final Component main = brain.host.alerts() ? plugin.text().parse("<red><bold>⚠ " + ItemRenderer.escape(skill.name())) : Component.empty();
        for (final Player player : playersNear(brain.boss.getLocation(), 48)) {
            if (danger.inside(mark, player)) {
                player.showTitle(Title.title(main, plugin.text().parse("<red><bold>⚠ 離開紅色區域"),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ofMillis(150))));
            }
        }
    }

    // ---- 各種招式 ----

    /** 延遲落下的一顆。 */
    private void impact(final Brain brain, final BossSkill skill, final Location point) {
        visuals.blast(skill.visual(), point, skill.radius());
        for (final LivingEntity victim : victims(brain, point, skill.radius())) {
            hurt(brain, skill, victim, Tags.AREA);
        }
    }

    /** 新星、扇形的一波。 */
    private void wave(final Brain brain, final Cast cast) {
        final BossSkill skill = cast.skill;
        // 2026-10：從標示的地方算（Boss 前搖時被推動了，傷害還是落在標示的範圍）
        final Location feet = cast.anchor != null ? cast.anchor : brain.boss.getLocation();
        if (skill.kind() == BossSkill.Kind.NOVA) {
            visuals.nova(skill.visual(), feet, skill.radius());
            for (final LivingEntity victim : victims(brain, feet, skill.radius())) {
                hurt(brain, skill, victim, Tags.AREA);
            }
            return;
        }
        visuals.bossCone(skill.visual(), feet, cast.facing, skill.length(), skill.angle());
        for (final LivingEntity victim : victims(brain, feet, skill.length())) {
            final double dx = victim.getLocation().getX() - feet.getX();
            final double dz = victim.getLocation().getZ() - feet.getZ();
            if (SkillMath.inCone(cast.facing.getX(), cast.facing.getZ(), dx, dz, skill.angle()) && brain.boss.hasLineOfSight(victim)) {
                hurt(brain, skill, victim, Tags.AREA);
            }
        }
    }

    /** 齊射：往目標現在的位置射出一排魔法彈。 */
    private void volley(final Brain brain, final Cast cast, final String mark) {
        final BossSkill skill = cast.skill;
        final LivingEntity boss = brain.boss;
        final Location eye = boss.getEyeLocation();
        final LivingEntity target = here(boss, cast.target) ? cast.target : null;
        // 2026-10：照前搖標在地上的方向射（不再出手時重新瞄準），彈只會飛在警示的扇形裡；上下照目標的高度
        Vector direction = new Vector(cast.facing.getX(), 0, cast.facing.getZ());
        if (direction.lengthSquared() < 1e-6) {
            direction = new Vector(0, 0, 1);
        }
        direction.normalize();
        if (target != null) {
            final Vector to = SkillTargets.center(target).toVector().subtract(eye.toVector());
            final double flat = Math.hypot(to.getX(), to.getZ());
            if (flat > 1e-3) {
                direction.setY(Math.max(-0.6, Math.min(0.6, to.getY() / flat)));
                direction.normalize();
            }
        }
        final int count = skill.count();
        for (int i = 0; i < count; i++) {
            final double degrees = count == 1 ? 0 : -skill.spread() / 2 + skill.spread() * i / (count - 1);
            final Vector velocity = direction.clone().rotateAroundY(Math.toRadians(degrees)).multiply(skill.speed());
            final Bolt bolt = new Bolt(skill, eye.clone().add(direction.clone().multiply(0.8)), velocity);
            bolt.mark = mark;
            brain.bolts.add(bolt);
        }
        visuals.bossBolt(skill.visual(), eye); // 聲音在前搖時放過了，這裡只要彈的尾跡
    }

    private void tickBolts(final Brain brain, final long now) {
        if (brain.bolts.isEmpty()) {
            return;
        }
        final Iterator<Bolt> iterator = brain.bolts.iterator();
        while (iterator.hasNext()) {
            final Bolt bolt = iterator.next();
            final BossSkill skill = bolt.skill;
            final World world = bolt.position.getWorld();
            final double step = bolt.velocity.length();
            // 撞方塊
            final RayTraceResult blocked = world.rayTraceBlocks(bolt.position, bolt.velocity.clone().normalize(), step, FluidCollisionMode.NEVER, true);
            Location end = blocked != null && blocked.getHitPosition() != null ? blocked.getHitPosition().toLocation(world) : bolt.position.clone().add(bolt.velocity);
            // 撞人：這一段路附近的人
            LivingEntity struck = null;
            double best = Double.MAX_VALUE;
            for (final LivingEntity candidate : victims(brain, bolt.position.clone().add(bolt.velocity.clone().multiply(0.5)), step / 2 + skill.size() + 1)) {
                final Location at = SkillTargets.center(candidate);
                final double distance = SkillMath.pointSegmentDistance(at.getX(), at.getZ(), bolt.position.getX(), bolt.position.getZ(), end.getX(), end.getZ());
                final double dy = Math.abs(at.getY() - bolt.position.getY());
                final double along = at.distanceSquared(bolt.position);
                if (distance <= skill.size() + candidate.getWidth() / 2 && dy <= candidate.getHeight() / 2 + skill.size() + 0.5 && along < best) {
                    best = along;
                    struck = candidate;
                }
            }
            if (struck != null) {
                end = SkillTargets.center(struck);
            }
            visuals.bossBolt(skill.visual(), bolt.position);
            bolt.travelled += step;
            if (struck != null || blocked != null || bolt.travelled >= skill.reach()) {
                if (skill.explode() > 0) {
                    visuals.bossBoltHit(skill.visual(), end, skill.explode());
                    for (final LivingEntity victim : victims(brain, end, skill.explode())) {
                        hurt(brain, skill, victim, Tags.AREA);
                    }
                } else {
                    if (struck != null) {
                        hurt(brain, skill, struck, Tags.PROJECTILE);
                    }
                    visuals.bossBoltHit(skill.visual(), end, 0);
                }
                iterator.remove();
                if (bolt.mark != null && brain.bolts.stream().noneMatch(other -> bolt.mark.equals(other.mark))) {
                    danger.hide(bolt.mark); // 這一輪的彈都飛完了
                }
                continue;
            }
            bolt.position.add(bolt.velocity);
        }
    }

    private void tickZones(final Brain brain, final long now) {
        if (brain.zones.isEmpty()) {
            return;
        }
        final Iterator<ZoneArea> iterator = brain.zones.iterator();
        while (iterator.hasNext()) {
            final ZoneArea zone = iterator.next();
            if (now >= zone.untilTick) {
                danger.hide(zone.mark);
                iterator.remove();
                continue;
            }
            if (now % 2 == 0) {
                visuals.zoneAmbient(zone.skill.visual(), List.of(zone.center), zone.skill.radius(), now);
            }
            if (zone.skill.solid()) {
                // 2026-10（賽勒斯的風暴牆）：這塊是牆——站進來的人每 tick 往外推（從中心往外），穿不過去，只能找缺口
                for (final LivingEntity victim : victims(brain, zone.center, zone.skill.radius())) {
                    final Vector out = victim.getLocation().toVector().subtract(zone.center.toVector()).setY(0);
                    if (out.lengthSquared() < 1e-4) {
                        out.setX(1);
                    }
                    final Vector push = out.normalize().multiply(WALL_PUSH);
                    final Vector current = victim.getVelocity();
                    victim.setVelocity(new Vector(push.getX(), Math.max(0, Math.min(0.1, current.getY())), push.getZ()));
                }
            }
            if (now >= zone.nextPulse) {
                zone.nextPulse = now + Math.max(2, Math.round(zone.skill.interval() * 20));
                final List<LivingEntity> victims = victims(brain, zone.center, zone.skill.radius());
                if (!victims.isEmpty()) {
                    visuals.zonePulse(zone.skill.visual(), List.of(zone.center), zone.skill.radius());
                    for (final LivingEntity victim : victims) {
                        hurt(brain, zone.skill, victim, Tags.AREA);
                    }
                }
            }
        }
    }

    /** 衝鋒的一 tick：沿鎖定的方向前進，撞到的人受傷；回傳還要不要繼續。 */
    private boolean charge(final Brain brain, final Cast cast) {
        final LivingEntity boss = brain.boss;
        final BossSkill skill = cast.skill;
        if (cast.travelled >= skill.reach()) {
            return false;
        }
        final Location from = boss.getLocation();
        final double step = Math.min(skill.speed(), skill.reach() - cast.travelled);
        Location to = from.clone().add(cast.facing.clone().multiply(step));
        to.setDirection(cast.facing);
        // 樓梯、斜坡：前面擋住就試著上一格；前面懸空就下一格
        if (!passable(to, boss.getHeight())) {
            final Location up = to.clone().add(0, 1, 0);
            if (passable(up, boss.getHeight()) && !up.clone().add(0, -1, 0).getBlock().isPassable()) {
                to = up;
            } else {
                visuals.bossLand(skill.visual(), from, 1.5);
                return false; // 撞牆
            }
        } else if (to.clone().add(0, -1, 0).getBlock().isPassable() && !to.clone().add(0, -2, 0).getBlock().isPassable()) {
            to.add(0, -1, 0);
        }
        boss.teleport(to);
        visuals.dash(skill.visual(), from, to);
        cast.travelled += step;
        for (final LivingEntity victim : victims(brain, to.clone().add(from).multiply(0.5), step / 2 + skill.width() + 1)) {
            if (cast.hit.contains(victim.getUniqueId())) {
                continue;
            }
            final Location at = victim.getLocation();
            if (SkillMath.pointSegmentDistance(at.getX(), at.getZ(), from.getX(), from.getZ(), to.getX(), to.getZ()) <= skill.width() + victim.getWidth() / 2) {
                cast.hit.add(victim.getUniqueId());
                hurt(brain, skill, victim, Tags.AREA);
            }
        }
        // 衝到目標身邊就停（目標不在了就衝到底）
        return !here(boss, cast.target) || cast.target.getLocation().distanceSquared(to) > 1.5 * 1.5;
    }

    private static boolean passable(final Location feet, final double height) {
        final int blocks = (int) Math.ceil(Math.max(1, height));
        for (int y = 0; y < blocks; y++) {
            final Block block = feet.clone().add(0, y, 0).getBlock();
            if (!block.isPassable()) {
                return false;
            }
        }
        return true;
    }

    /** 召喚小怪：圍著 Boss 一圈。 */
    private void summon(final Brain brain, final Cast cast) {
        final BossSkill skill = cast.skill;
        final LivingEntity boss = brain.boss;
        final int room = skill.summonMax() - aliveSummons(brain);
        final int count = Math.min(skill.count(), room);
        if (count <= 0) {
            return;
        }
        final Location base = boss.getLocation();
        final double offset = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < count; i++) {
            final double angle = offset + Math.PI * 2 * i / count;
            final Location spot = ground(base.clone().add(Math.cos(angle) * 2.5, 0.2, Math.sin(angle) * 2.5));
            final LivingEntity minion;
            try {
                minion = brain.host.summon(skill.summonType(), spot);
            } catch (final RuntimeException e) {
                if (warned.add("summon:" + skill.summonType())) {
                    plugin.getSLF4JLogger().warn("boss_skills.yml：{} 的 summon 類型 {} 生不出來", skill.id(), skill.summonType(), e);
                }
                return;
            }
            if (minion == null) {
                if (warned.add("summon:" + skill.summonType())) {
                    plugin.getSLF4JLogger().warn("boss_skills.yml：{} 的 summon 類型 {} 生不出來（要寫原版生物代號，例如 ZOMBIE）", skill.id(), skill.summonType());
                }
                return;
            }
            brain.summons.put(minion.getUniqueId(), skill.duration() > 0 ? now() + Math.round(skill.duration() * 20) : 0L);
            if (minion instanceof Mob mob && cast.target instanceof Player player && here(boss, player)) {
                mob.setTarget(player);
            }
            visuals.bossSummon(spot);
        }
    }

    /** 詛咒：範圍內的玩家（與召喚物）一段時間受到的傷害增加。 */
    private void curse(final Brain brain, final Cast cast, final long now) {
        final BossSkill skill = cast.skill;
        final Debuffs debuffs = plugin.combat() == null ? null : plugin.combat().debuffs();
        if (debuffs == null) {
            return;
        }
        final List<LivingEntity> victims = victims(brain, brain.boss.getLocation(), skill.radius());
        final long until = now + Math.round(skill.duration() * 20);
        for (final LivingEntity victim : victims) {
            debuffs.apply(victim.getUniqueId(), new Debuffs.Debuff("boss." + skill.id(), brain.boss.getUniqueId(), until, skill.taken() / 100.0, false));
        }
        visuals.bossCurse(skill.visual(), brain.boss.getLocation(), skill.radius(), victims);
    }

    /** 閃現到目標背後；回傳落點（沒地方站就 null）。 */
    private Location blink(final Brain brain, final Cast cast) {
        final LivingEntity boss = brain.boss;
        final LivingEntity target = cast.target;
        if (target == null || !target.isValid() || target.isDead() || !target.getWorld().equals(boss.getWorld())) {
            return null;
        }
        final double yaw = Math.toRadians(target.getLocation().getYaw());
        final Vector facing = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        final double gap = 1.2 + boss.getWidth() / 2 + target.getWidth() / 2;
        Location to = null;
        for (final double degrees : new double[] {180, 135, -135, 90, -90, 45, -45, 0}) {
            final Vector direction = facing.clone().rotateAroundY(Math.toRadians(degrees));
            final Location candidate = ground(target.getLocation().clone().add(direction.multiply(gap)));
            if (passable(candidate, boss.getHeight()) && !candidate.clone().add(0, -1, 0).getBlock().isPassable()) {
                to = candidate;
                break;
            }
        }
        if (to == null) {
            return null;
        }
        final Location from = boss.getLocation();
        to.setDirection(target.getLocation().toVector().subtract(to.toVector()).setY(0));
        boss.teleport(to);
        visuals.blink(from, to);
        if (boss instanceof Mob mob) {
            mob.setTarget(target);
            mob.lookAt(target);
        }
        return to;
    }

    // ---- 特殊招式（光束、擴散環、漩渦、光球、全場大招）與場地危害 ----

    /** 光束掃射的一 tick：每道光束照進度轉到新的角度，掃到的人每 interval 秒挨一下；地上的紅條每 3 tick 跟著轉。 */
    private void sweep(final Brain brain, final Cast cast, final long now) {
        final LivingEntity boss = brain.boss;
        final BossSkill skill = cast.skill;
        if (now >= cast.channelEnd) {
            finish(brain, now);
            return;
        }
        hold(brain, cast);
        final Location feet = boss.getLocation();
        final double progress = (now - cast.telegraphEnd) / (double) Math.max(1, cast.channelEnd - cast.telegraphEnd);
        final double sweep = skill.track() ? 0 : Math.toRadians(skill.angle());
        if (skill.track() && cast.target != null && cast.target.isValid() && cast.target.getWorld() == feet.getWorld()) {
            // 2026-10：追著目標的光束放出來之後還會慢慢轉過去（每秒最多 angle 度）——要一直往旁邊跑，不能停
            final Vector to = facing(boss, cast.target);
            final double want = Math.atan2(to.getZ(), to.getX());
            double diff = Math.atan2(Math.sin(want - cast.beamStart), Math.cos(want - cast.beamStart));
            final double step = Math.toRadians(skill.angle()) / 20;
            diff = Math.max(-step, Math.min(step, diff));
            cast.beamStart += diff;
        }
        final long gap = Math.max(1, Math.round(skill.interval() * 20));
        final Material color = DangerZones.colorFor(skill.type());
        final List<LivingEntity> near = victims(brain, feet, skill.length() + skill.width() + 1);
        for (int i = 0; i < skill.count(); i++) {
            final double angle = BossGeometry.beamAngle(cast.beamStart, i, skill.count(), sweep, cast.beamSign, progress);
            final Vector direction = new Vector(Math.cos(angle), 0, Math.sin(angle));
            if (drawTick(brain, now, i)) {
                visuals.bossBeam(skill.visual(), feet, direction, skill.length(), skill.width());
            }
            if ((now + brain.offset) % 4 == 0) {
                // 地板跟著光束轉：只送變了的那幾格（以前整條收掉再整條重送，地板一直重畫）
                danger.show(cast.mark + "#b" + i, feet, DangerZones.strip(feet, direction, skill.length(), skill.width()), color);
            }
            for (final LivingEntity victim : near) {
                final Location at = victim.getLocation();
                if (!BossGeometry.inBeam(feet.getX(), feet.getZ(), angle, skill.length(), skill.width(), at.getX(), at.getZ(), victim.getWidth() / 2)) {
                    continue;
                }
                final Long last = cast.lastHit.get(victim.getUniqueId());
                if (last != null && now - last < gap) {
                    continue;
                }
                cast.lastHit.put(victim.getUniqueId(), now);
                hurt(brain, skill, victim, Tags.AREA);
            }
        }
        if (now % 6 == 0) {
            visuals.bossCharge(skill.visual(), boss, false, brain.host.boss());
        }
    }

    /** 引力漩渦的一 tick：把範圍內的人往 Boss 拉；時間到在腳下爆炸。 */
    private void pull(final Brain brain, final Cast cast, final long now) {
        final LivingEntity boss = brain.boss;
        final BossSkill skill = cast.skill;
        final Location feet = boss.getLocation();
        if (now >= cast.channelEnd) {
            visuals.nova(skill.visual(), feet, skill.explode());
            for (final LivingEntity victim : victims(brain, feet, skill.explode())) {
                hurt(brain, skill, victim, Tags.AREA);
            }
            finish(brain, now);
            return;
        }
        hold(brain, cast);
        if (now % 2 == 0) {
            visuals.bossPull(skill.visual(), feet, skill.radius(), now);
            visuals.bossWarningRing(feet, skill.explode(), 1);
        }
        flash(cast.mark, skill, 1, now);
        warnInside(brain, skill, cast.mark, now);
        for (final LivingEntity victim : victims(brain, feet, skill.radius())) {
            final Location at = victim.getLocation();
            final double[] step = BossGeometry.pullStep(feet.getX(), feet.getZ(), at.getX(), at.getZ(), skill.speed());
            if (step[0] == 0 && step[1] == 0) {
                continue;
            }
            final Vector velocity = victim.getVelocity();
            victim.setVelocity(new Vector(step[0], Math.min(velocity.getY(), 0.1), step[1]));
        }
    }

    /** 叫出追蹤光球：繞著 Boss 一圈，各自追一個玩家（人不夠就重複追）。 */
    private void spawnOrbs(final Brain brain, final BossSkill skill, final LivingEntity mainTarget, final long now) {
        final Location eye = brain.boss.getEyeLocation();
        final List<Player> players = playersNear(brain.boss.getLocation(), SEARCH);
        final long until = now + skill.orbTicks();
        for (int i = 0; i < skill.count(); i++) {
            final double angle = Math.PI * 2 * i / skill.count();
            final Location at = eye.clone().add(Math.cos(angle) * 1.5, 0, Math.sin(angle) * 1.5);
            final UUID target = !players.isEmpty() ? players.get(i % players.size()).getUniqueId()
                    : mainTarget == null ? null : mainTarget.getUniqueId();
            final Orb orb = new Orb(skill, at, target, until);
            orb.mark = brain.boss.getUniqueId() + ":orb:" + now + "#" + i;
            brain.orbs.add(orb);
        }
        brain.lastOrb = now; // 光球剛放出去：先讓人看清楚往哪飛，BossPacing.ORB_SETTLE_TICKS 之後才出下一招
        visuals.bossSummon(brain.boss.getLocation());
    }

    /** 光球每 tick：往目標飄，碰到人（或時間到）就爆炸。 */
    private void tickOrbs(final Brain brain, final long now) {
        if (brain.orbs.isEmpty()) {
            return;
        }
        final Iterator<Orb> iterator = brain.orbs.iterator();
        while (iterator.hasNext()) {
            final Orb orb = iterator.next();
            final BossSkill skill = orb.skill;
            boolean explode = now >= orb.untilTick;
            Entity target = orb.target == null ? null : plugin.getServer().getEntity(orb.target);
            if (!explode && (!(target instanceof Player player) || !validPlayer(player, orb.position.getWorld()))) {
                final List<Player> players = playersNear(orb.position, SEARCH);
                target = players.isEmpty() ? null : players.get(random.nextInt(players.size()));
                orb.target = target == null ? null : target.getUniqueId();
            }
            if (!explode && target != null) {
                final Location aim = SkillTargets.center((LivingEntity) target);
                final Vector to = aim.toVector().subtract(orb.position.toVector());
                if (to.lengthSquared() > 1e-6) {
                    orb.position.add(to.normalize().multiply(Math.min(skill.speed(), Math.sqrt(aim.distanceSquared(orb.position)))));
                }
            }
            if (!explode) {
                for (final LivingEntity candidate : victims(brain, orb.position, skill.size() + 1.5)) {
                    if (SkillTargets.center(candidate).distanceSquared(orb.position) <= Math.pow(skill.size() + candidate.getWidth() / 2 + 0.3, 2)) {
                        explode = true;
                        break;
                    }
                }
            }
            if (explode) {
                danger.hide(orb.mark);
                visuals.bossBoltHit(skill.visual(), orb.position, Math.max(1, skill.explode()));
                for (final LivingEntity victim : victims(brain, orb.position, skill.explode())) {
                    hurt(brain, skill, victim, Tags.AREA);
                }
                iterator.remove();
                continue;
            }
            visuals.bossOrb(skill.visual(), orb.position);
            if (target instanceof Player chased) {
                if (now % 3 == 0) {
                    visuals.bossTether(orb.position, SkillTargets.center(chased), chased.getEyeLocation()); // 在追誰：紅線＋頭上紅圈
                }
                if (!chased.getUniqueId().equals(orb.told)) {
                    orb.told = chased.getUniqueId();
                    chased.showTitle(Title.title(Component.empty(), plugin.text().parse("<red><bold>⚠ 光球在追你"),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(250))));
                }
            }
            if (now % 2 == 0) {
                // 光球底下的地板標出爆炸範圍（跟著光球走）
                final Location under = ground(orb.position);
                danger.show(orb.mark, under, DangerZones.disc(under, Math.max(1, skill.explode())), DangerZones.colorFor(skill.type()));
            }
        }
    }

    /** 擴散環每 tick：每一波往外長，碰到的人（不在缺口裡、這一波還沒打過）挨一下。 */
    private void tickRings(final Brain brain, final long now) {
        if (brain.rings.isEmpty()) {
            return;
        }
        final Iterator<RingWave> iterator = brain.rings.iterator();
        while (iterator.hasNext()) {
            final RingWave wave = iterator.next();
            final BossSkill skill = wave.skill;
            final double radius = wave.radius(now);
            if (radius > skill.reach() + skill.width()) {
                danger.hide(wave.mark);
                iterator.remove();
                continue;
            }
            if (now % 2 == 0) {
                // 地上跟著往外跑的一圈（亮紅）：碰到這一圈就挨打
                danger.show(wave.mark, wave.center, DangerZones.ring(wave.center, radius), DangerZones.FLASH);
            }
            final double gapAngle = Math.toRadians(skill.gapAngle());
            if (now % 2 == 0) {
                visuals.bossRingWave(skill.visual(), wave.center, radius, wave.gaps, gapAngle); // 隔一個 tick 畫一次（一圈的粒子很多）
            }
            for (final LivingEntity victim : victims(brain, wave.center, radius + skill.width() + 1)) {
                if (wave.hit.contains(victim.getUniqueId())) {
                    continue;
                }
                final Location at = victim.getLocation();
                final double distance = Math.hypot(at.getX() - wave.center.getX(), at.getZ() - wave.center.getZ());
                if (!BossGeometry.onRing(distance, radius, skill.width() + victim.getWidth() / 2)) {
                    continue;
                }
                final double angle = BossGeometry.angleTo(wave.center.getX(), wave.center.getZ(), at.getX(), at.getZ());
                // 2026-10：缺口放寬：身體邊緣在缺口裡就算（換算成這個距離的角度），再多一點給延遲
                if (BossGeometry.inGap(angle, wave.gaps, gapAngle + 2 * Math.atan2(victim.getWidth() / 2 + 0.6, Math.max(1, distance)))) {
                    continue;
                }
                wave.hit.add(victim.getUniqueId());
                hurt(brain, skill, victim, Tags.AREA);
            }
        }
    }

    /**
     * 全場大招的安全區：count 個平均分在 Boss 周圍，離 Boss scatter 格（再隨機轉一個角度），放在站得上去的地面上
     * （Boss 靠牆時那個方向轉一點、近一點找；都找不到就放在 Boss 腳下：要貼著 Boss）。
     */
    private List<Location> safeSpots(final Brain brain, final BossSkill skill) {
        final Location feet = brain.boss.getLocation();
        final double offset = random.nextDouble() * Math.PI * 2;
        final List<Location> spots = new ArrayList<>();
        // 2026-10：看人數多放安全區（範圍內活著的玩家，每多一個 +count_per_player；最多 10 個）
        int players = 0;
        if (skill.countPerPlayer() > 0) {
            for (final Player player : feet.getWorld().getPlayers()) {
                if (!player.isDead() && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                        && player.getLocation().distanceSquared(feet) <= skill.radius() * skill.radius()) {
                    players++;
                }
            }
        }
        final int count = Math.min(10, skill.count() + skill.countPerPlayer() * Math.max(0, players - 1));
        // 2026-10：場地正在收縮（arena）時，安全區一定整個在圈裡面——以收縮圈的中心為準，
        // 距離不超過「大招落下那一刻的圈半徑 − 安全區半徑」（不然綠圈畫在圈外，躲進去也會被場地打）
        final Arena arena = brain.arena;
        final Location origin = arena != null ? arena.center.clone() : feet;
        double scatter = skill.scatter();
        double limit = Double.MAX_VALUE;
        if (arena != null) {
            final long landing = now() + settings().telegraphTicks(skill, brain.host.boss());
            limit = Math.max(0, arena.radius(landing) - skill.safe() - 0.5);
            scatter = Math.min(scatter, limit);
        }
        for (int i = 0; i < count; i++) {
            Location found = null;
            for (final double[] candidate : BossGeometry.safeSpotCandidates(i, count, scatter, skill.safe(), offset)) {
                if (Math.hypot(candidate[0], candidate[1]) > limit) {
                    continue; // 會超出收縮圈
                }
                final Location spot = ground(origin.clone().add(candidate[0], 0.2, candidate[1]));
                if (standable(spot) && Math.abs(spot.getY() - feet.getY()) <= 3 && clearPath(origin, spot)) {
                    found = spot;
                    break;
                }
            }
            if (found == null && arena != null) {
                // 圈太小、候選都超出：照角度平均放在圈裡（必要時疊在一起，總比畫在圈外好）
                final double angle = offset + Math.PI * 2 * i / Math.max(1, count);
                final double d = Math.min(limit, Math.max(0, scatter));
                found = ground(origin.clone().add(Math.cos(angle) * d, 0.2, Math.sin(angle) * d));
            }
            spots.add(found != null ? found : ground(feet.clone()));
        }
        return spots;
    }

    /** 從 Boss 腳下到那裡中間沒有牆（腰的高度看得到）：安全區不會落在隔壁房間、柱子後面。 */
    private static boolean clearPath(final Location from, final Location to) {
        final Location start = from.clone().add(0, 0.6, 0);
        final Vector direction = to.clone().add(0, 0.6, 0).toVector().subtract(start.toVector());
        final double distance = direction.length();
        if (distance < 0.5) {
            return true;
        }
        final RayTraceResult hit = start.getWorld().rayTraceBlocks(start, direction.normalize(), distance, FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null;
    }

    /** 站得上去：腳下是實心的、身體兩格是空的（不是水、岩漿）。 */
    private static boolean standable(final Location at) {
        final Block body = at.getBlock();
        return body.isPassable() && !body.isLiquid() && body.getRelative(0, 1, 0).isPassable()
                && !body.getRelative(0, -1, 0).isPassable();
    }

    /** 全場大招落下：範圍內不在任何安全區裡的人都挨打。 */
    /** 安全區的判定放寬多少（格）：綠色地板的格子角落、網路延遲。 */
    static final double SAFE_SLACK = 1.5; // 2026-10：0.85 → 1.5（身體有一部分在綠色裡就算，判定太嚴）

    private void cataclysm(final Brain brain, final Cast cast) {
        final BossSkill skill = cast.skill;
        final Location feet = cast.anchor != null ? cast.anchor : brain.boss.getLocation();
        visuals.bossCataclysm(skill.visual(), feet, skill.radius());
        final List<double[]> centers = new ArrayList<>();
        for (final Location spot : cast.safeSpots) {
            centers.add(new double[] {spot.getX(), spot.getZ()});
        }
        for (final LivingEntity victim : victims(brain, feet, skill.radius())) {
            final Location at = victim.getLocation();
            // 2026-10：站在任何一格綠色的地板上都算安全（格子的角離圈心最多多 0.71 格），再多一點點給延遲
            if (!BossGeometry.insideAny(at.getX(), at.getZ(), centers, skill.safe() + SAFE_SLACK)) {
                hurt(brain, skill, victim, Tags.AREA);
            }
        }
    }

    /** 全場大招的前搖：範圍內、不在安全區裡的玩家畫面中間跳「躲進綠色安全區」。 */
    private void warnOutsideSafety(final Brain brain, final Cast cast, final long now) {
        if (now % 5 != 0) {
            return;
        }
        final List<double[]> centers = new ArrayList<>();
        for (final Location spot : cast.safeSpots) {
            centers.add(new double[] {spot.getX(), spot.getZ()});
        }
        final Component main = brain.host.alerts() ? plugin.text().parse("<#ff5555><bold>⚠ " + ItemRenderer.escape(cast.skill.name()))
                : Component.empty();
        for (final Player player : playersNear(brain.boss.getLocation(), cast.skill.radius())) {
            final Location at = player.getLocation();
            if (!BossGeometry.insideAny(at.getX(), at.getZ(), centers, cast.skill.safe() + SAFE_SLACK)) {
                player.showTitle(Title.title(main, plugin.text().parse("<#45ff6a><bold>⚠ 躲進綠色安全區"),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ofMillis(150))));
            }
        }
    }

    /** 終局與試煉 Boss 放特殊招式：附近的玩家畫面中間跳大字（招式名稱＋怎麼應對）、聊天室寫一行（同一招 30 秒內只寫一次）。 */
    private void alert(final Brain brain, final BossSkill skill, final long now) {
        if (!brain.host.alerts() || !BossAlerts.special(skill)) {
            return;
        }
        final String color = BossAlerts.deadly(skill) ? "<#ff5555>" : "<#ffb347>";
        final String hint = ItemRenderer.escape(BossAlerts.hint(skill));
        final Title title = Title.title(plugin.text().parse(color + "<bold>⚠ " + ItemRenderer.escape(skill.name())),
                plugin.text().parse("<white>" + hint),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(BossAlerts.titleTicks(skill) * 50L), Duration.ofMillis(250)));
        final Long last = brain.chatAt.get(skill.id());
        final boolean chat = last == null || now - last >= BossAlerts.CHAT_GAP_TICKS;
        if (chat) {
            brain.chatAt.put(skill.id(), now);
        }
        final Component line = chat ? plugin.text().parse(color + "⚠ <#af6025>" + ItemRenderer.escape(brain.host.name())
                + "<dark_gray>・" + color + ItemRenderer.escape(skill.name()) + "<gray>：" + hint) : null;
        for (final Player player : playersNear(brain.boss.getLocation(), settings().warnRange())) {
            player.showTitle(title);
            if (line != null) {
                player.sendMessage(line);
            }
        }
    }

    /** 終局與試煉 Boss 的場地危害第一次落下：畫面中間跳大字、聊天室說明（這一場只說一次）。 */
    private void announceHazard(final Brain brain, final BossSkill skill, final double everySeconds) {
        final String hint = ItemRenderer.escape(BossAlerts.hazardHint(skill, everySeconds));
        final Title title = Title.title(plugin.text().parse("<#ffb347><bold>⚠ 場地危害：" + ItemRenderer.escape(skill.name())),
                plugin.text().parse("<white>" + hint),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(2500), Duration.ofMillis(250)));
        final Component line = plugin.text().parse("<#ffb347>⚠ 場地危害「" + ItemRenderer.escape(skill.name()) + "」<gray>：" + hint);
        for (final Player player : playersNear(brain.boss.getLocation(), SEARCH)) {
            player.showTitle(title);
            player.sendMessage(line);
        }
    }

    /**
     * 場地危害：到時間的挑一個附近的玩家當目標落下（{@link BossPacing}：等 Boss 這一招放完才落，一次一條；
     * 落下之前不出新的招）。回傳這個 tick 有沒有落。
     */
    private boolean tryHazard(final Brain brain, final long now) {
        final List<BossHazard> hazards = brain.host.hazards();
        final int phase = brain.host.phase();
        final List<String> keys = new ArrayList<>();
        for (int index = 0; index < hazards.size(); index++) {
            final BossHazard hazard = hazards.get(index);
            if (hazard.fromPhase() > phase) {
                continue;
            }
            final BossSkill skill = config.bossSkills().skill(hazard.skill());
            if (skill == null || !skill.kind().hazardCapable()) {
                continue; // 載入時已經警告過
            }
            keys.add(index + ":" + hazard.skill()); // 每一條各自計時（同一招寫兩次、間隔不同也各算各的）
        }
        if (keys.isEmpty()) {
            return false;
        }
        final String key = brain.pacing.due(keys, k -> hazardTicks(brain, hazards, k), now);
        if (key == null) {
            return false;
        }
        brain.pacing.fired(key, now, hazardTicks(brain, hazards, key));
        final BossSkill skill = config.bossSkills().skill(hazards.get(hazardIndex(key)).skill());
        final List<Player> players = playersNear(brain.boss.getLocation(), SEARCH);
        if (skill == null || players.isEmpty()) {
            return false; // 附近沒人：這次跳過，照間隔再等下一次
        }
        if (brain.host.alerts() && brain.hazardsAnnounced.add(skill.id())) {
            announceHazard(brain, skill, hazards.get(hazardIndex(key)).every());
        }
        final Player target = players.get(random.nextInt(players.size()));
        if (skill.kind() == BossSkill.Kind.ORBS) {
            spawnOrbs(brain, skill, target, now);
            return true;
        }
        if (skill.kind() == BossSkill.Kind.MARK) {
            spawnMarks(brain, skill, target, now);
            return true;
        }
        return dropPending(brain, skill, target, now, "hazard");
    }

    /**
     * 延遲落下、地面效果「自己落下」（場地危害、兩招同時放的那一招）：地上先標，前搖（＋落下的延遲）之後才炸或留下地面效果。
     * 回傳有沒有落點。
     */
    private boolean dropPending(final Brain brain, final BossSkill skill, final LivingEntity target, final long now, final String tag) {
        final List<Location> points = points(brain, skill, target);
        if ("hazard".equals(tag) && brain.arena != null) {
            // 2026-10：場地收縮中，場地危害不落在圈裡（圈裡只有跟收縮一起放的那些）
            final Arena arena = brain.arena;
            final double limit = arena.radius(now) + skill.radius();
            points.removeIf(point -> point.distanceSquared(arena.center) < limit * limit);
        }
        final long fire = now + settings().telegraphTicks(skill, brain.host.boss())
                + (skill.kind() == BossSkill.Kind.SLAM ? Math.round(skill.delay() * 20) : 0);
        final Material color = DangerZones.colorFor(skill.type());
        for (int i = 0; i < points.size(); i++) {
            final String mark = brain.boss.getUniqueId() + ":" + tag + ":" + skill.id() + ":" + now + "#" + i;
            final long land = fire + (skill.kind() == BossSkill.Kind.SLAM ? i * Math.max(1, Math.round(skill.stagger() * 20)) : 0);
            brain.pendings.add(new Pending(skill, points.get(i), now, land, mark));
            danger.show(mark, points.get(i), DangerZones.disc(points.get(i), skill.radius()), color);
        }
        return !points.isEmpty();
    }

    /** 場地危害的代號「第幾條:招式」→ 第幾條。 */
    private static int hazardIndex(final String key) {
        return Integer.parseInt(key.substring(0, key.indexOf(':')));
    }

    /** 這一條場地危害的間隔（tick；地圖詞綴的「怪物技能冷卻時間縮短」一起算）。 */
    private long hazardTicks(final Brain brain, final List<BossHazard> hazards, final String key) {
        return Math.max(1, Math.round(hazards.get(hazardIndex(key)).every() * 20 * cooldownFactor(brain.boss)));
    }

    /** 場地危害還沒落下的：畫警告圈、快落下時閃紅；時間到就炸（延遲落下）或留下地面效果。 */
    private void tickPendings(final Brain brain, final long now) {
        if (brain.pendings.isEmpty()) {
            return;
        }
        final Iterator<Pending> iterator = brain.pendings.iterator();
        int index = 0;
        while (iterator.hasNext()) {
            final Pending pending = iterator.next();
            final BossSkill skill = pending.skill;
            if (now >= pending.fireTick) {
                danger.hide(pending.mark);
                if (skill.kind() == BossSkill.Kind.ZONE) {
                    final ZoneArea zone = new ZoneArea(skill, pending.point, now, pending.mark + ":zone");
                    brain.zones.add(zone);
                    danger.show(zone.mark, zone.center, DangerZones.disc(zone.center, skill.radius()), DangerZones.colorFor(skill.type()));
                    visuals.zonePulse(skill.visual(), List.of(pending.point), skill.radius());
                } else {
                    impact(brain, skill, pending.point);
                }
                iterator.remove();
                continue;
            }
            final double progress = (now - pending.startTick) / (double) Math.max(1, pending.fireTick - pending.startTick);
            if (drawTick(brain, now, index++)) {
                visuals.bossWarningRing(pending.point, skill.radius(), progress);
            }
            flash(pending.mark, skill, progress, now);
            warnInside(brain, skill, pending.mark, now);
        }
    }

    // ---- 2026-10：點名標記、殘影、場地收縮 ----

    /** 點名：附近隨機 count 個玩家（不重複；人不夠就全部），各掛一個跟著走的標記。 */
    private void spawnMarks(final Brain brain, final BossSkill skill, final LivingEntity mainTarget, final long now) {
        final List<Player> players = new ArrayList<>(playersNear(brain.boss.getLocation(), SEARCH));
        if (players.isEmpty() && mainTarget instanceof Player player && validPlayer(player, brain.boss.getWorld())) {
            players.add(player);
        }
        final long fuse = Math.max(20, Math.round(skill.duration() * 20));
        final Title title = Title.title(plugin.text().parse("<#ff5555><bold>⚠ " + ItemRenderer.escape(skill.name())),
                plugin.text().parse("<white>標記在你身上：離開隊友"),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(250)));
        for (int i = 0; i < skill.count() && !players.isEmpty(); i++) {
            final Player chosen = players.remove(random.nextInt(players.size()));
            final String mark = brain.boss.getUniqueId() + ":mark:" + skill.id() + ":" + now + "#" + i;
            brain.marks.add(new Marked(skill, chosen.getUniqueId(), chosen.getLocation(), now, now + fuse, mark));
            chosen.showTitle(title);
            visuals.bossTether(brain.boss.getEyeLocation(), SkillTargets.center(chosen), chosen.getEyeLocation());
        }
        brain.lastMark = now;
    }

    /** 標記每 tick：跟著被點名的人走（地上的圈、粒子），時間到在他腳下爆炸，圈裡的人都挨打。 */
    private void tickMarks(final Brain brain, final long now) {
        if (brain.marks.isEmpty()) {
            return;
        }
        final Iterator<Marked> iterator = brain.marks.iterator();
        int index = 0;
        while (iterator.hasNext()) {
            final Marked marked = iterator.next();
            final BossSkill skill = marked.skill;
            final Player player = plugin.getServer().getPlayer(marked.target);
            final boolean present = player != null && validPlayer(player, brain.boss.getWorld());
            if (present) {
                marked.last = player.getLocation();
            }
            final Location under = ground(marked.last);
            if (now >= marked.fireTick || !under.getWorld().equals(brain.boss.getWorld())) {
                danger.hide(marked.mark);
                if (under.getWorld().equals(brain.boss.getWorld())) {
                    visuals.blast(skill.visual(), under, skill.explode());
                    for (final LivingEntity victim : victims(brain, under, skill.explode())) {
                        hurt(brain, skill, victim, Tags.AREA);
                    }
                }
                iterator.remove();
                continue;
            }
            final double progress = (now - marked.startTick) / (double) Math.max(1, marked.fireTick - marked.startTick);
            if (marked.shownAt == null || marked.shownAt.distanceSquared(under) > REMARK_DISTANCE * REMARK_DISTANCE) {
                marked.shownAt = under.clone();
                danger.show(marked.mark, under, DangerZones.disc(under, skill.explode()),
                        progress >= 0.6 ? DangerZones.FLASH : DangerZones.colorFor(skill.type()));
            }
            flash(marked.mark, skill, progress, now);
            if (drawTick(brain, now, index++)) {
                visuals.bossWarningRing(under, skill.explode(), progress);
            }
            if (now % 5 == 0) {
                final long seconds = Math.max(1, (marked.fireTick - now + 19) / 20);
                for (final Player near : playersNear(under, skill.explode() + 2)) {
                    if (near.getUniqueId().equals(marked.target)) {
                        near.showTitle(Title.title(plugin.text().parse("<#ff5555><bold>⚠ " + ItemRenderer.escape(skill.name()) + " " + seconds),
                                plugin.text().parse("<white>標記在你身上：離開隊友"),
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ofMillis(150))));
                    } else if (danger.inside(marked.mark, near)) {
                        near.showTitle(Title.title(Component.empty(), plugin.text().parse("<red><bold>⚠ 離開隊友身上的標記"),
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ofMillis(150))));
                    }
                }
            }
        }
    }

    /** 殘影出現：Boss 周圍一圈（照出手的順序排好每一槍的時間）。 */
    private List<EchoLance> lances(final Brain brain, final Cast cast, final long now) {
        final BossSkill skill = cast.skill;
        final Location center = cast.anchor != null ? cast.anchor : brain.boss.getLocation();
        final List<double[]> offsets = BossGeometry.echoOffsets(skill.count(), skill.scatter(), random.nextDouble() * Math.PI * 2);
        final int[] order = BossGeometry.echoOrder(skill.count());
        final long step = Math.max(1, Math.round(skill.stagger() * 20));
        final long warning = Math.max(1, Math.round(skill.delay() * 20));
        final List<EchoLance> lances = new ArrayList<>();
        for (int k = 0; k < order.length; k++) {
            final double[] offset = offsets.get(order[k]);
            final Location from = ground(center.clone().add(offset[0], 0, offset[1]));
            final long show = now + k * step;
            lances.add(new EchoLance(from, show, show + warning, cast.mark + "#e" + k)); // 掛在這一招底下：被打斷時一起收掉
            visuals.bossSummon(from);
        }
        return lances;
    }

    /** 殘影每 tick：輪到的殘影朝某個玩家標出紅線，時間到射穿；全部射完這一招才結束。 */
    private void echoes(final Brain brain, final Cast cast, final long now) {
        final BossSkill skill = cast.skill;
        hold(brain, cast);
        final Material color = DangerZones.colorFor(skill.type());
        boolean pending = false;
        for (int i = 0; i < cast.lances.size(); i++) {
            final EchoLance lance = cast.lances.get(i);
            if (lance.done) {
                continue;
            }
            pending = true;
            if (now < lance.showTick) {
                if ((now + brain.offset + i) % 6 == 0) {
                    visuals.bossOrb(skill.visual(), lance.from.clone().add(0, 1.2, 0)); // 還沒出手的殘影在原地閃著
                }
                continue;
            }
            if (!lance.shown) {
                lance.shown = true;
                lance.direction = aimEcho(brain, lance.from, cast.anchor != null ? cast.anchor : brain.boss.getLocation());
                danger.show(lance.mark, lance.from, DangerZones.strip(lance.from, lance.direction, skill.reach(), skill.width()), color);
                visuals.blink(lance.from, lance.from);
            }
            if (now < lance.hitTick) {
                final double progress = (now - lance.showTick) / (double) Math.max(1, lance.hitTick - lance.showTick);
                if (drawTick(brain, now, i)) {
                    visuals.bossWarningLine(lance.from, lance.direction, skill.reach(), skill.width(), progress);
                }
                flash(lance.mark, skill, progress, now);
                warnInside(brain, skill, lance.mark, now);
                continue;
            }
            lance.done = true;
            danger.hide(lance.mark);
            visuals.bossBeam(skill.visual(), lance.from, lance.direction, skill.reach(), skill.width());
            final double angle = Math.atan2(lance.direction.getZ(), lance.direction.getX());
            final Location middle = lance.from.clone().add(lance.direction.clone().multiply(skill.reach() / 2));
            for (final LivingEntity victim : victims(brain, middle, skill.reach() / 2 + skill.width() + 1)) {
                final Location at = victim.getLocation();
                if (BossGeometry.inBeam(lance.from.getX(), lance.from.getZ(), angle, skill.reach(), skill.width(),
                        at.getX(), at.getZ(), victim.getWidth() / 2)) {
                    hurt(brain, skill, victim, Tags.AREA);
                }
            }
        }
        if (!pending) {
            finish(brain, now);
        }
    }

    /** 殘影瞄準誰：附近隨機一個玩家（站在殘影腳下的不算，換一個）；沒人就朝中間射。回傳水平的單位向量。 */
    private Vector aimEcho(final Brain brain, final Location from, final Location center) {
        final List<Player> players = playersNear(brain.boss.getLocation(), SEARCH);
        Location aim = center;
        while (!players.isEmpty()) {
            final Player player = players.remove(random.nextInt(players.size()));
            if (player.getLocation().distanceSquared(from) > 2 * 2) {
                aim = player.getLocation();
                break;
            }
        }
        final Vector direction = aim.toVector().subtract(from.toVector()).setY(0);
        if (direction.lengthSquared() < 1e-6) {
            direction.setX(center.getX() - from.getX()).setZ(center.getZ() - from.getZ());
        }
        return direction.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : direction.normalize();
    }

    /** 場地開始收縮：以 Boss 現在的位置為中心。 */
    private void startArena(final Brain brain, final Cast cast, final long now) {
        final Location center = ground(cast.anchor != null ? cast.anchor : brain.boss.getLocation());
        brain.arena = new Arena(cast.skill, center, now, brain.boss.getUniqueId() + ":arena:" + now);
    }

    /** 場地收縮每 tick：圈慢慢縮小（地上的圈、粒子），圈外的人每 interval 秒挨一下、畫面中間提醒回到圈內。 */
    private void tickArena(final Brain brain, final long now) {
        final Arena arena = brain.arena;
        if (arena == null) {
            return;
        }
        final BossSkill skill = arena.skill;
        final double radius = arena.radius(now);
        if (arena.shown < 0 || arena.shown - radius >= 0.5) {
            arena.shown = radius;
            danger.show(arena.mark, arena.center, DangerZones.ring(arena.center, radius), DangerZones.FLASH);
        }
        if ((now + brain.offset) % 10 == 0) {
            visuals.bossWarningRing(arena.center, radius, 1);
        }
        // 2026-10：跟收縮一起放的地面效果（荒蕪）：照各自的 cooldown 在圈裡一直落，數量看在場人數（points 的 at: arena）
        // 2026-10-15：放綠色安全區的大招時先不落（等安全區結束），不會蓋住綠色
        final boolean holdExtras = brain.cast != null && brain.cast.skill.kind() == BossSkill.Kind.SANCTUARY || underSanctuary(brain);
        for (final BossSkill extra : holdExtras ? List.<BossSkill>of() : brain.arenaWith) {
            if (extra.kind() != BossSkill.Kind.ZONE || extra.at() != BossSkill.Target.ARENA && extra.at() != BossSkill.Target.OUTSIDE) {
                continue;
            }
            final long every = Math.max(20, Math.round((extra.cooldown() > 0 ? extra.cooldown() : 6) * 20));
            final long due = arena.nextExtra.getOrDefault(extra.id(), arena.startTick + every);
            if (now >= due) {
                arena.nextExtra.put(extra.id(), now + every);
                dropPending(brain, extra, null, now, "arena");
            }
        }
        if (now < arena.nextPulse) {
            return;
        }
        arena.nextPulse = now + Math.max(2, Math.round(skill.interval() * 20));
        final Title title = Title.title(Component.empty(), plugin.text().parse("<red><bold>⚠ 回到圈內（靠近 Boss）"),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(600), Duration.ofMillis(150)));
        for (final LivingEntity victim : victims(brain, arena.center, SEARCH)) {
            final Location at = victim.getLocation();
            if (!BossGeometry.outsideArena(arena.center.getX(), arena.center.getZ(), radius, at.getX(), at.getZ(), victim.getWidth() / 2)) {
                continue;
            }
            hurt(brain, skill, victim, Tags.AREA);
            if (victim instanceof Player player) {
                player.showTitle(title);
            }
        }
    }

    // ---- 傷害 ----

    /** 這個招式打一下：同一招剛打過這個人就不算（{@link BossSkill#hitGapTicks}）；走戰鬥模組的管道，之後緩速。 */
    private void hurt(final Brain brain, final BossSkill skill, final LivingEntity victim, final String shapeTag) {
        if (!skill.dealsDamage()) {
            return;
        }
        if (!brain.gate.allow(skill.id(), victim.getUniqueId(), skill.hitGapTicks(), now())) {
            return; // 同一招剛打過這個人（齊射的另一發、另一顆光球、疊在一起的地面效果）：只算一下
        }
        damage.hitAs(brain.boss, victim, profile(skill, shapeTag), null);
        if (skill.ruin() > 0 && victim instanceof Player player && player.isOnline()) {
            plugin.players().get(player).ruin(now() + Math.round(skill.ruin() * 20)); // 2026-10：荒蕪（黑水）——禁療
        }
        if (skill.slow() > 0 && victim.isValid()) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) Math.round(skill.slow() * 20), 1, false, true, true));
        }
    }

    /** 招式的技能描述：攻擊、範圍或投射物、物理轉成招式的類型，倍率 = damage。 */
    private SkillProfile profile(final BossSkill skill, final String shapeTag) {
        return profiles.computeIfAbsent(skill.id() + ":" + shapeTag + ":" + skill.damage() + ":" + skill.type() + ":" + skill.convert(), key -> {
            final Set<String> tags = new HashSet<>(Set.of(Tags.ATTACK, shapeTag));
            tags.add(skill.type().tag());
            for (final DamageType to : skill.convert().keySet()) {
                tags.add(to.tag()); // 2026-10：物理轉成好幾種（convert）：每一種都算這招的標籤
            }
            if (skill.kind() == BossSkill.Kind.SANCTUARY) {
                tags.add(tw.exilecore.combat.DodgeRoll.UNDODGEABLE_TAG); // 2026-10：全場大招翻滾躲不掉，要躲進安全區
            }
            return new SkillProfile("boss." + skill.id(), tags, skill.damage(), null, null, skill.conversion(), List.of());
        });
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }
}
