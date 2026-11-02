package tw.exilecore.map;

import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.skill.Fx;
import tw.exilecore.stats.DamageType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 2026-11-02 照站長：結晶以外的鎖血機制（站位圈、倒數大招、元素弱點；{@link PhaseMechanic}）跑起來的部分，
 * 地圖 Boss（{@link MapBosses}：終局、極境、巔峰、事件巔峰、金庫之主）與星骸之海的淵鯨、渦齒鯊王共用同一套。
 * 每隻 Boss 一份 {@link State}；Boss 所在的場地由呼叫的那邊給（{@link Arena}：中心、地板、收縮圈、在場的玩家）。
 * 結晶還是各自的寫法（地圖 Boss 的結晶、淵鯨的潮核）。這次之前只有 MapBosses 裡的結晶。
 */
public final class PhaseLocks {

    /** Boss 所在的場地。 */
    public interface Arena {
        LivingEntity boss();

        String bossName();

        /** 場地中心（y 是站的高度）。 */
        Location center();

        /** 這一格站得上去（圈要落在地板上）。 */
        boolean floor(double x, double z);

        /** 場地正在收縮時的圈 [x, z, 半徑]；沒有 null。 */
        double[] bounds();

        /** 圈離中心最遠幾格。 */
        double ring();

        /** 活著、會挨打的玩家（觀戰、創造模式、剛復活的保護時間不算）。 */
        List<Player> fighters();

        /** 收訊息的人。 */
        Collection<Player> audience();

        /** 照人數乘的時候算幾個人（跟結晶一樣）。 */
        int players();
    }

    /** 一隻 Boss 的狀態：進行中的機制、倒數大招之後的虛弱、站位圈疊的易傷。 */
    public static final class State {
        PhaseMechanic.Active lock;
        long weakUntilTick;
        double weakTaken;
        final Map<UUID, Double> vulnerability = new HashMap<>();
        PhaseMechanic.Zones vulnRules;
        long vulnTick;
        long vulnShown = Long.MIN_VALUE / 4;
        UUID boss;

        /** 有機制在進行。 */
        public boolean active() {
            return lock != null;
        }

        /** 這段時間 Boss 打不動（站位圈、倒數大招；元素弱點不是）。 */
        public boolean invulnerable() {
            return lock != null && lock.spec.invulnerable();
        }

        /** 進行中的種類；沒有 null。 */
        public PhaseMechanic.Kind kind() {
            return lock == null ? null : lock.spec.kind();
        }
    }

    /** 這次更新的結果。 */
    public enum Outcome {
        /** 沒有機制、或還在進行。 */
        NONE,
        /** 完成，破盾。 */
        PASSED,
        /** 沒完成，Boss 回血。 */
        FAILED
    }

    /** 站位圈疊的易傷（減益的代號）。 */
    public static final String ZONE_VULN_ID = "boss.zone_vuln";

    private static final Particle.DustOptions ZONE_EMPTY = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 200, 70), 1.4f);
    private static final Particle.DustOptions ZONE_CHARGING = new Particle.DustOptions(org.bukkit.Color.fromRGB(90, 220, 255), 1.4f);
    private static final Particle.DustOptions ZONE_FULL = new Particle.DustOptions(org.bukkit.Color.fromRGB(80, 255, 120), 1.4f);
    private static final Particle.DustOptions SAFE_GREEN = new Particle.DustOptions(org.bukkit.Color.fromRGB(69, 255, 106), 1.6f);
    private static final Particle.DustOptions DANGER_RED = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 60, 60), 1.4f);
    /** 換階段的大字顯示多久（tick）：倒數的大字等這段時間過了才跳。 */
    private static final long TITLE_TICKS = 50;

    private final ExileCore plugin;
    /** 2026-11-03：地面圖現在標的顏色（代號 → 顏色；顏色變了才重標）。 */
    private final Map<String, org.bukkit.Material> marked = new HashMap<>();
    private final java.util.random.RandomGenerator random;

    public PhaseLocks(final ExileCore plugin, final java.util.random.RandomGenerator random) {
        this.plugin = plugin;
        this.random = random;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    // ---- 開始 ----

    /**
     * 開始一個機制（站位圈、倒數大招、元素弱點；結晶不在這裡，回傳 false）。
     *
     * @param required 元素弱點要打掉的血（最大生命的比例，{@link PhaseMechanic#requiredFraction}）
     */
    public boolean start(final Arena arena, final State state, final PhaseMechanic.Spec spec, final double required) {
        if (spec == null || state.lock != null) {
            return false;
        }
        final LivingEntity boss = arena.boss();
        state.boss = boss.getUniqueId();
        switch (spec.kind()) {
            case ZONES -> startZones(arena, state, spec);
            case COUNTDOWN -> startCountdown(arena, state, spec);
            case ELEMENT -> startElement(arena, state, spec, required);
            default -> {
                return false;
            }
        }
        state.lock.lastTick = now();
        if (spec.invulnerable()) {
            boss.setInvulnerable(true);
        }
        return true;
    }

    private void startZones(final Arena arena, final State state, final PhaseMechanic.Spec spec) {
        final PhaseMechanic.Zones zones = spec.zones();
        // 2026-11-02 照站長第二次：圈數 = 在場的人數（1～6，之後跟著人數變）；這次之前每人份、至少 2 個最多 8 個
        final int count = PhaseMechanic.zoneCount(arena.fighters().size());
        final PhaseMechanic.Active lock = new PhaseMechanic.Active(spec, now());
        lock.spots.addAll(placeSpots(arena, count, arena.ring(), zones.radius()));
        lock.charges = new double[lock.spots.size()];
        state.lock = lock;
        if (state.vulnRules == null) {
            state.vulnTick = now();
        }
        state.vulnRules = zones;
        broadcast(arena, String.format(Locale.ROOT,
                "<#7fe0ff>◎ %s 架起了 %d 個光圈（一人一個）：%d 秒內全部充滿才破盾——有人站在圈裡就充能（一個圈 %s 秒），沒人站會慢慢退。"
                        + "<#ffb347>站在圈裡受到的傷害每秒增加 %s%%（最多 %s%%），離開後慢慢消退。<#7fe0ff>沒完成牠會回復 %d%% 生命",
                ItemRenderer.escape(arena.bossName()), count, Math.round(zones.duration()), trim(zones.charge()), trim(zones.vulnPerSecond()),
                trim(zones.vulnMax()), Math.round(zones.heal())));
        arena.boss().getWorld().playSound(arena.boss().getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.2f);
        for (final double[] xz : lock.spots) {
            final Location at = spot(arena, xz, 0.2);
            Fx.important(() -> Fx.spawn(at.getWorld(), Particle.END_ROD, at, 30, zones.radius() * 0.4, 0.8, zones.radius() * 0.4, 0.05));
        }
    }

    private void startCountdown(final Arena arena, final State state, final PhaseMechanic.Spec spec) {
        final PhaseMechanic.Countdown countdown = spec.countdown();
        // 安全區 1～2 個：一個人只放一個（不用選），兩個人以上照設定
        final int safe = arena.players() <= 1 ? 1 : countdown.safe();
        final PhaseMechanic.Active lock = new PhaseMechanic.Active(spec, now());
        lock.spots.addAll(placeSpots(arena, safe, Math.min(arena.ring(), 6), countdown.safeRadius()));
        state.lock = lock;
        broadcast(arena, String.format(Locale.ROOT,
                "<#ff5555>⚠ %s 開始蓄力「%s」：%d 秒後全場重擊，綠色安全區外的人受到最大生命 %d%% 的傷害——躲進安全區！"
                        + (countdown.weak() > 0 && countdown.weakTaken() > 0 ? "<gold>放完之後牠會虛弱 " + trim(countdown.weak())
                        + " 秒（受到的傷害更多 " + Math.round(countdown.weakTaken() * 100) + "%%）" : ""),
                ItemRenderer.escape(arena.bossName()), ItemRenderer.escape(countdown.name()), Math.round(countdown.delay()),
                Math.round(countdown.damage() * 100)));
        arena.boss().getWorld().playSound(arena.boss().getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 0.7f);
        for (final double[] xz : lock.spots) {
            final Location at = spot(arena, xz, 0.2);
            Fx.important(() -> Fx.spawn(at.getWorld(), Particle.HAPPY_VILLAGER, at, 30, countdown.safeRadius() * 0.4, 0.5, countdown.safeRadius() * 0.4, 0));
        }
    }

    private void startElement(final Arena arena, final State state, final PhaseMechanic.Spec spec, final double required) {
        final PhaseMechanic.Element element = spec.element();
        final LivingEntity boss = arena.boss();
        final PhaseMechanic.Active lock = new PhaseMechanic.Active(spec, now());
        lock.allowed = PhaseMechanic.allowedElements(bossResists(boss));
        lock.element = PhaseMechanic.nextElement(lock.allowed, null, random);
        // 換階段的 2.5 秒打不動：第一次換元素從那之後才開始算
        lock.swapTick = now() + PhaseStep.TRANSITION_TICKS + Math.round(element.rotate() * 20);
        lock.startFraction = healthFraction(boss);
        lock.required = required;
        state.lock = lock;
        broadcast(arena, String.format(Locale.ROOT,
                "<#c58cff>✦ %s 的元素弱點：現在只吃 %s<#c58cff> 傷害（其他傷害只打 %d%%）——%d 秒內打掉 %d%% 生命就破盾；"
                        + "每 %s 秒換一種元素（換之前 %s 秒會提示）。沒完成牠會回復 %d%% 生命",
                ItemRenderer.escape(arena.bossName()), PhaseMechanic.elementLabel(lock.element), Math.round(element.other() * 100),
                Math.round(element.duration()), Math.round(required * 100), trim(element.rotate()), trim(element.warn()), Math.round(element.heal())));
        boss.getWorld().playSound(boss.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 0.8f);
        elementBurst(boss, lock.element, 40);
    }

    // ---- 每次更新（地圖 Boss、淵鯨每 10 tick，鯊王每 2 tick；照實際過了多久算） ----

    public Outcome update(final Arena arena, final State state) {
        final LivingEntity boss = arena.boss();
        final long now = now();
        Outcome outcome = Outcome.NONE;
        final PhaseMechanic.Active lock = state.lock;
        if (lock != null && boss != null && boss.isValid()) {
            final double seconds = Math.max(0, Math.min(1, (now - lock.lastTick) / 20.0));
            lock.lastTick = now;
            outcome = switch (lock.spec.kind()) {
                case ZONES -> updateZones(arena, state, lock, now, seconds);
                case COUNTDOWN -> updateCountdown(arena, state, lock, now);
                case ELEMENT -> updateElement(arena, state, lock, now);
                default -> {
                    end(state, boss);
                    yield Outcome.NONE;
                }
            };
        }
        updateVulnerability(arena, state, now);
        return outcome;
    }

    /** 收掉（Boss 重置、死掉、下一門、場地關掉）：機制、虛弱、易傷都清掉。 */
    public void clear(final State state, final LivingEntity boss) {
        end(state, boss);
        state.weakUntilTick = 0;
        state.weakTaken = 0;
        if (plugin.combat() != null) {
            for (final UUID uuid : state.vulnerability.keySet()) {
                plugin.combat().debuffs().removeAll(uuid, ZONE_VULN_ID);
            }
        }
        state.vulnerability.clear();
        state.vulnRules = null;
    }

    /** 2026-11-03 照站長：能踩的圈也標地面圖（跟 Boss 預警同一套材質包；沒套的人看到地板變色），顏色變了才重標。 */
    private void mark(final PhaseMechanic.Active lock, final int index, final Location at, final double radius, final org.bukkit.Material color) {
        if (plugin.skills() == null) {
            return;
        }
        final String id = lock.markId(index);
        if (marked.get(id) != color) {
            marked.put(id, color);
            plugin.skills().bossSkills().showSafeMark(id, at, radius, color);
        }
    }

    private void unmark(final PhaseMechanic.Active lock, final int count) {
        for (int i = 0; i < count; i++) {
            final String id = lock.markId(i);
            if (marked.remove(id) != null && plugin.skills() != null) {
                plugin.skills().bossSkills().hideMark(id);
            }
        }
    }

    private void end(final State state, final LivingEntity boss) {
        final PhaseMechanic.Active lock = state.lock;
        if (lock == null) {
            return;
        }
        for (int i = 0; i < lock.spots.size(); i++) {
            Fx.zoneStop(lock.key(i));
        }
        unmark(lock, lock.spots.size()); // 2026-11-03
        state.lock = null;
        if (boss != null && boss.isValid() && lock.spec.invulnerable()) {
            boss.setInvulnerable(false);
        }
    }

    private Outcome fail(final Arena arena, final State state, final PhaseMechanic.Active lock, final String why) {
        final LivingEntity boss = arena.boss();
        final double heal = lock.spec.heal();
        final AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && heal > 0) {
            boss.setHealth(Math.min(max.getValue(), boss.getHealth() + max.getValue() * heal / 100.0));
        }
        end(state, boss);
        broadcast(arena, "<red>⛨ 來不及了：" + why + "，" + ItemRenderer.escape(arena.bossName()) + " 回復 " + Math.round(heal) + "% 生命");
        alert(arena, BossMechanicText.lockFailed(lock.spec));
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.0f, 0.6f);
        Fx.important(() -> Fx.spawn(boss.getWorld(), Particle.HEART, boss.getLocation().add(0, boss.getHeight(), 0), 12, 0.6, 0.4, 0.6, 0));
        return Outcome.FAILED;
    }

    private Outcome pass(final Arena arena, final State state, final PhaseMechanic.Active lock) {
        final LivingEntity boss = arena.boss();
        end(state, boss);
        broadcast(arena, "<green>⛨ " + lock.spec.kind().label() + "完成！" + ItemRenderer.escape(arena.bossName()) + " 的護盾破了");
        alert(arena, BossMechanicText.shieldBroken());
        boss.getWorld().playSound(boss.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.2f, 0.6f);
        Fx.important(() -> Fx.spawn(boss.getWorld(), Particle.TOTEM_OF_UNDYING, boss.getLocation().add(0, boss.getHeight() / 2, 0), 40, 0.8, 1, 0.8, 0.3));
        return Outcome.PASSED;
    }

    // -- 站位圈 --

    private Outcome updateZones(final Arena arena, final State state, final PhaseMechanic.Active lock, final long now, final double seconds) {
        final PhaseMechanic.Zones zones = lock.spec.zones();
        final List<Player> players = arena.fighters();
        resizeZones(arena, lock, PhaseMechanic.zoneCount(players.size()));
        final double floorY = arena.center().getY();
        final boolean pulse = now - lock.lastPulse >= 20;
        if (pulse) {
            lock.lastPulse = now;
        }
        for (int i = 0; i < lock.spots.size(); i++) {
            final double[] xz = lock.spots.get(i);
            boolean occupied = false;
            for (final Player player : players) {
                final Location at = player.getLocation();
                if (Math.abs(at.getY() - floorY) <= 3 && PhaseMechanic.inside(at.getX(), at.getZ(), List.of(xz), zones.radius())) {
                    occupied = true;
                    break;
                }
            }
            final double before = lock.charges[i];
            lock.charges[i] = PhaseMechanic.charge(before, occupied, seconds, zones.charge(), zones.drain());
            final Location at = spot(arena, xz, 0.1);
            if (before < 1 && lock.charges[i] >= 1) {
                at.getWorld().playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 1.4f);
                Fx.important(() -> Fx.spawn(at.getWorld(), Particle.TOTEM_OF_UNDYING, at.clone().add(0, 0.5, 0), 25, zones.radius() * 0.4, 0.6,
                        zones.radius() * 0.4, 0.2));
            } else if (occupied && lock.charges[i] < 1 && pulse) {
                at.getWorld().playSound(at, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, (float) (0.6 + lock.charges[i]));
            }
            // 外框照充能變色（黃 → 藍 → 綠），裡面一圈照充能長大；圈心一道光柱，越滿越高
            final double charge = lock.charges[i];
            final Particle.DustOptions color = charge >= 1 ? ZONE_FULL : charge > 0 ? ZONE_CHARGING : ZONE_EMPTY;
            Fx.safeZone(lock.key(i), at, zones.radius(), Fx.Look.filled(Particle.DUST, color), (int) Math.max(0, lock.untilTick - now));
            mark(lock, i, at, zones.radius(), charge >= 1 ? org.bukkit.Material.LIME_CONCRETE : charge > 0 ? org.bukkit.Material.LIGHT_BLUE_CONCRETE
                    : org.bukkit.Material.YELLOW_CONCRETE); // 2026-11-03 照站長：地面圖黃 → 藍 → 綠 // 2026-11-02 照站長：能踩的，模組不染紅；裡面也撒點（沒裝模組的也看得清楚），這次之前只有外框
            if (charge > 0.05 && charge < 1 && pulse) {
                Fx.ring(at.getWorld(), at, zones.radius() * charge, Particle.DUST, ZONE_FULL, null, true);
            }
            if (pulse) {
                Fx.important(() -> Fx.spawn(at.getWorld(), Particle.END_ROD, at.getX(), at.getY() + 0.3 + charge * 2.5, at.getZ(), 3, 0.05, 0.3, 0.05, 0));
            }
        }
        if (PhaseMechanic.allCharged(lock.charges)) {
            return pass(arena, state, lock);
        }
        if (now >= lock.untilTick) {
            return fail(arena, state, lock, "光圈沒有全部充滿");
        }
        if (now - lock.lastNotice >= 40) {
            lock.lastNotice = now;
            final String head = "<#7fe0ff>◎ Boss 無敵中：站滿光圈 " + charged(lock) + "/" + lock.charges.length + "（剩 " + secondsLeft(lock) + " 秒）";
            notice(arena, player -> {
                final double vuln = state.vulnerability.getOrDefault(player.getUniqueId(), 0.0);
                return vuln > 0.5 ? head + " <#ffb347>｜ 你的易傷 +" + Math.round(vuln) + "%" : head;
            }, now);
        }
        return Outcome.NONE;
    }

    /**
     * 站位圈的易傷：站在圈裡每秒疊、離開（或機制結束）後慢慢消退；疊在玩家身上是「受到的傷害增加」的減益
     * （{@link tw.exilecore.combat.Debuffs}，跟 Boss 的詛咒同一套，擊中都會乘）。
     */
    private void updateVulnerability(final Arena arena, final State state, final long now) {
        final PhaseMechanic.Zones rules = state.vulnRules;
        if (rules == null || plugin.combat() == null || arena.boss() == null) {
            return;
        }
        final double seconds = Math.max(0, Math.min(1, (now - state.vulnTick) / 20.0));
        state.vulnTick = now;
        final PhaseMechanic.Active lock = state.lock;
        final boolean active = lock != null && lock.spec.kind() == PhaseMechanic.Kind.ZONES;
        final List<Player> fighters = arena.fighters();
        for (final Player player : fighters) {
            state.vulnerability.putIfAbsent(player.getUniqueId(), 0.0);
        }
        final tw.exilecore.combat.Debuffs debuffs = plugin.combat().debuffs();
        final boolean show = now - state.vulnShown >= 20;
        if (show) {
            state.vulnShown = now;
        }
        for (final Iterator<Map.Entry<UUID, Double>> it = state.vulnerability.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Double> entry = it.next();
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            final boolean inside = active && player != null && fighters.contains(player)
                    && PhaseMechanic.inside(player.getLocation().getX(), player.getLocation().getZ(), lock.spots, rules.radius());
            final double before = entry.getValue();
            final double value = PhaseMechanic.vulnerability(before, inside, seconds, rules);
            if (value <= 0) {
                if (before > 0) {
                    debuffs.removeAll(entry.getKey(), ZONE_VULN_ID);
                }
                it.remove();
                continue;
            }
            entry.setValue(value);
            debuffs.apply(entry.getKey(), new tw.exilecore.combat.Debuffs.Debuff(ZONE_VULN_ID, arena.boss().getUniqueId(), now + 25, value / 100.0, false));
            if (inside && show) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#ffb347>◎ 站在光圈裡：受到的傷害 +" + Math.round(value) + "%"
                        + (value >= rules.vulnMax() ? "（滿了，快換人！）" : "")));
            }
        }
        if (state.vulnerability.isEmpty() && !active) {
            state.vulnRules = null;
        }
    }

    /**
     * 2026-11-02 照站長第二次：圈數跟著在場的人數變——人多了加圈（放在離現有的圈最遠的地方），人少了收掉充能最少的圈。
     */
    private void resizeZones(final Arena arena, final PhaseMechanic.Active lock, final int wanted) {
        final int have = lock.spots.size();
        if (wanted == have) {
            return;
        }
        for (int i = 0; i < have; i++) {
            Fx.zoneStop(lock.key(i)); // 圈的編號會變：先全部收掉，下面重畫
        }
        unmark(lock, have); // 2026-11-03：地面圖也收掉，下面重標
        final List<double[]> spots = new ArrayList<>(lock.spots);
        final List<Double> charges = new ArrayList<>();
        for (final double value : lock.charges) {
            charges.add(value);
        }
        while (spots.size() > wanted) {
            int lowest = 0;
            for (int i = 1; i < charges.size(); i++) {
                if (charges.get(i) < charges.get(lowest)) {
                    lowest = i;
                }
            }
            spots.remove(lowest);
            charges.remove(lowest);
        }
        final double radius = lock.spec.zones().radius();
        while (spots.size() < wanted) {
            double[] best = null;
            double bestGap = -1;
            for (final double[] candidate : placeSpots(arena, 8, arena.ring(), radius)) {
                double gap = Double.MAX_VALUE;
                for (final double[] spot : spots) {
                    gap = Math.min(gap, Math.hypot(candidate[0] - spot[0], candidate[1] - spot[1]));
                }
                if (gap > bestGap) {
                    bestGap = gap;
                    best = candidate;
                }
            }
            spots.add(best);
            charges.add(0.0);
            final Location at = spot(arena, best, 0.2);
            Fx.important(() -> Fx.spawn(at.getWorld(), Particle.END_ROD, at, 30, radius * 0.4, 0.8, radius * 0.4, 0.05));
            at.getWorld().playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.4f);
        }
        lock.spots.clear();
        lock.spots.addAll(spots);
        lock.charges = new double[charges.size()];
        for (int i = 0; i < charges.size(); i++) {
            lock.charges[i] = charges.get(i);
        }
        broadcast(arena, "<#7fe0ff>◎ 在場的人數變了：光圈現在 " + lock.spots.size() + " 個");
    }

    // -- 倒數大招 --

    private Outcome updateCountdown(final Arena arena, final State state, final PhaseMechanic.Active lock, final long now) {
        final PhaseMechanic.Countdown countdown = lock.spec.countdown();
        final LivingEntity boss = arena.boss();
        // 安全區：綠色的外框＋裡面撒點；Boss 身上蓄力的火；場地一圈紅色的外框（整個場地都會被打到）
        for (int i = 0; i < lock.spots.size(); i++) {
            Fx.safeZone(lock.key(i), spot(arena, lock.spots.get(i), 0.1), countdown.safeRadius(), // 2026-11-02 照站長：安全區，模組不染紅
                    Fx.Look.of(Particle.DUST, SAFE_GREEN, Particle.HAPPY_VILLAGER, null), (int) Math.max(0, lock.untilTick - now));
            mark(lock, i, spot(arena, lock.spots.get(i), 0.1), countdown.safeRadius(), org.bukkit.Material.LIME_CONCRETE); // 2026-11-03：綠色地面圖
        }
        if (now - lock.lastPulse >= 10) {
            lock.lastPulse = now;
            final Location feet = boss.getLocation();
            Fx.important(() -> {
                Fx.spawn(feet.getWorld(), Particle.SOUL_FIRE_FLAME, feet.clone().add(0, boss.getHeight() / 2, 0), 18, boss.getWidth(),
                        boss.getHeight() / 2, boss.getWidth(), 0.04);
                final Location room = arena.center().add(0, 0.1, 0);
                Fx.ring(room.getWorld(), room, arena.ring() + 4, Particle.DUST, DANGER_RED, null, true);
            });
        }
        if (now >= lock.untilTick) {
            slam(arena, state, lock);
            return Outcome.PASSED;
        }
        final long left = secondsLeft(lock);
        if (left != lock.shownSecond) {
            lock.shownSecond = left;
            boss.getWorld().playSound(boss.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1.6f, (float) Math.min(2.0, 0.6 + 1.4 / Math.max(1, left)));
            if (now - lock.startTick >= TITLE_TICKS) {
                // 換階段的大字先看完，接著每秒跳倒數
                for (final Player player : arena.fighters()) {
                    final Location at = player.getLocation();
                    final boolean safe = PhaseMechanic.inside(at.getX(), at.getZ(), lock.spots, countdown.safeRadius());
                    // 2026-11-02 照站長（C 組）：裝了模組的改送預警（倒數、綠色箭頭指最近的安全區），不送大標題
                    final double[] near = tw.exilecore.clientmod.AlertFeed.nearest(at.getX(), at.getZ(), lock.spots);
                    tw.exilecore.clientmod.FeedProtocol.Alert alert = tw.exilecore.clientmod.FeedProtocol.Alert.top("lock:" + boss.getUniqueId(),
                            safe ? 1 : 3, "<#ff5555><bold>⚠ " + ItemRenderer.escape(countdown.name()),
                            safe ? "<#45ff6a>你在安全區裡，別出去" : "<#45ff6a><bold>躲進綠色安全區！", 25)
                            .countdown((int) Math.max(0, lock.untilTick - now));
                    if (near != null && !safe) {
                        alert = alert.at(near[0], at.getY(), near[1], true);
                    }
                    if (tw.exilecore.clientmod.AlertFeed.show(plugin, player, alert)) {
                        continue;
                    }
                    tw.exilecore.gui.Titles.show(player, Title.title(
                            plugin.text().parse("<#ff5555><bold>⚠ " + ItemRenderer.escape(countdown.name()) + " " + left),
                            plugin.text().parse(safe ? "<#45ff6a>你在安全區裡，別出去" : "<#45ff6a><bold>躲進綠色安全區！"),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(100))));
                }
            }
        }
        return Outcome.NONE;
    }

    /** 倒數結束：安全區外的人挨打（最大生命的比例），破盾，Boss 虛弱幾秒。 */
    private void slam(final Arena arena, final State state, final PhaseMechanic.Active lock) {
        final PhaseMechanic.Countdown countdown = lock.spec.countdown();
        final LivingEntity boss = arena.boss();
        final long now = now();
        int hit = 0;
        for (final Player player : arena.fighters()) {
            final Location at = player.getLocation();
            if (!PhaseMechanic.inside(at.getX(), at.getZ(), lock.spots, countdown.safeRadius()) && hurtSlam(arena, player, countdown, now)) {
                hit++;
            }
        }
        final Location feet = boss.getLocation();
        feet.getWorld().playSound(feet, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.5f);
        feet.getWorld().playSound(feet, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 0.8f);
        Fx.important(() -> {
            Fx.spawn(feet.getWorld(), Particle.EXPLOSION_EMITTER, feet, 2, 1, 0.5, 1, 0);
            final Location room = arena.center().add(0, 0.2, 0);
            for (double r = 2; r <= arena.ring() + 4; r += 2.5) {
                Fx.ring(room.getWorld(), room, r, Particle.FLAME, null, null, true);
            }
        });
        end(state, boss);
        lock.slamHits = hit;
        final boolean weakens = countdown.weak() > 0 && countdown.weakTaken() > 0; // 2026-11-02 照站長：預設不虛弱
        if (weakens) {
            state.weakUntilTick = now + Math.round(countdown.weak() * 20);
            state.weakTaken = countdown.weakTaken();
        }
        broadcast(arena, "<gold>💥 " + ItemRenderer.escape(countdown.name()) + " 落下了" + (hit > 0 ? "（" + hit + " 人沒躲進安全區）" : "")
                + "！" + ItemRenderer.escape(arena.bossName()) + " 破盾" + (weakens ? "、虛弱 " + trim(countdown.weak()) + " 秒：受到的傷害更多 "
                + Math.round(countdown.weakTaken() * 100) + "%" : ""));
        alert(arena, BossMechanicText.weakened(countdown));
    }

    /**
     * 倒數大招打到一個人：（沒保留的最大生命＋最大能量護盾）× damage，先扣能量護盾再扣生命（滿血的人不會被直接秒殺；
     * 已經掉血的人可能會死）。照持續傷害的寫法直接扣（不吃抗性、閃避、格擋：要躲的是安全區），死亡回顧也記一筆。
     */
    private boolean hurtSlam(final Arena arena, final Player player, final PhaseMechanic.Countdown countdown, final long now) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter() || state.isDead() || state.isSafe()) {
            return false;
        }
        final double amount = PhaseMechanic.slamDamage(state.unreservedLife(), state.maxEnergyShield(), countdown.damage());
        final double shield = state.energyShield();
        final double absorbed = Math.min(shield, amount);
        state.setEnergyShield(shield - absorbed);
        final double rest = amount - absorbed;
        state.setLife(state.life() - rest);
        state.setLastDamageTick(now);
        if (plugin.combat() != null && plugin.combat().recap() != null) {
            final Map<DamageType, Double> dealt = Map.of(DamageType.PHYSICAL, amount);
            plugin.combat().recap().record(player, new tw.exilecore.combat.DeathRecap.Hit(now, arena.bossName(), countdown.name(),
                    dealt, dealt, absorbed, rest, state.life(), state.maxLife(), false));
        }
        player.sendMessage(plugin.text().parse("<red>💥 你沒有躲進安全區：「" + ItemRenderer.escape(countdown.name()) + "」打掉 "
                + Math.round(amount) + "（最大生命的 " + Math.round(countdown.damage() * 100) + "%）"));
        if (state.isDead()) {
            player.setHealth(0); // 交給原版處理死亡（死亡懲罰、重生點）
        } else {
            plugin.players().display(player, state);
        }
        return true;
    }

    // -- 元素弱點 --

    private Outcome updateElement(final Arena arena, final State state, final PhaseMechanic.Active lock, final long now) {
        final PhaseMechanic.Element element = lock.spec.element();
        final LivingEntity boss = arena.boss();
        if (progress(boss, lock) >= 1) {
            return pass(arena, state, lock);
        }
        if (now >= lock.untilTick) {
            return fail(arena, state, lock, "沒有及時打掉弱點那一段血");
        }
        if (!lock.warned && element.warn() > 0 && now >= lock.swapTick - Math.round(element.warn() * 20)) {
            lock.warned = true;
            lock.upcoming = PhaseMechanic.nextElement(lock.allowed, lock.element, random);
            alert(arena, new String[] {"<#c58cff><bold>✦ 元素要換了", "<white>" + trim(element.warn()) + " 秒後只吃 " + PhaseMechanic.elementLabel(lock.upcoming)});
            boss.getWorld().playSound(boss.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f, 0.7f);
        }
        if (now >= lock.swapTick) {
            lock.element = lock.upcoming != null ? lock.upcoming : PhaseMechanic.nextElement(lock.allowed, lock.element, random);
            lock.upcoming = null;
            lock.warned = false;
            lock.swapTick = now + Math.round(element.rotate() * 20);
            alert(arena, new String[] {"<bold>" + PhaseMechanic.elementLabel(lock.element), "<white>現在只吃這種元素的傷害"});
            boss.getWorld().playSound(boss.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 1.2f);
            elementBurst(boss, lock.element, 40);
        } else if (now - lock.lastPulse >= 10) {
            lock.lastPulse = now;
            elementBurst(boss, lock.element, 10);
        }
        if (now - lock.lastNotice >= 40) {
            lock.lastNotice = now;
            final String text = "<#c58cff>✦ 只吃 " + PhaseMechanic.elementLabel(lock.element) + "<#c58cff> 傷害：打掉 "
                    + Math.round(progress(boss, lock) * 100) + "%（剩 " + secondsLeft(lock) + " 秒）";
            notice(arena, player -> text, now);
        }
        return Outcome.NONE;
    }

    private static void elementBurst(final LivingEntity boss, final DamageType type, final int count) {
        final Particle particle = type == DamageType.FIRE ? Particle.FLAME : type == DamageType.COLD ? Particle.SNOWFLAKE : Particle.ELECTRIC_SPARK;
        final Location at = boss.getLocation().add(0, boss.getHeight() / 2, 0);
        Fx.important(() -> Fx.spawn(boss.getWorld(), particle, at, count, boss.getWidth() * 0.7, boss.getHeight() / 2, boss.getWidth() * 0.7, 0.03));
    }

    private static double progress(final LivingEntity boss, final PhaseMechanic.Active lock) {
        return boss == null ? 0 : PhaseMechanic.elementProgress(lock.startFraction, healthFraction(boss), lock.required);
    }

    private static double healthFraction(final LivingEntity boss) {
        final AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? 1 : boss.getHealth() / Math.max(1, max.getValue());
    }

    /** Boss 的火、冰、電抗性（屬性表；拿不到算 0）。 */
    private Map<DamageType, Double> bossResists(final LivingEntity boss) {
        final tw.exilecore.monster.MonsterData data = plugin.monsters().data(boss).orElse(null);
        if (data == null) {
            return PhaseMechanic.resists(0, 0, 0);
        }
        final tw.exilecore.stats.StatSheet sheet = plugin.monsters().sheet(boss, data);
        return PhaseMechanic.resists(sheet.get(tw.exilecore.stats.StatKeys.RESIST_FIRE), sheet.get(tw.exilecore.stats.StatKeys.RESIST_COLD),
                sheet.get(tw.exilecore.stats.StatKeys.RESIST_LIGHTNING));
    }

    // ---- 傷害、血條 ----

    /**
     * 打進這隻 Boss 的傷害倍率（MapService 的 Boss 傷害修正、原本每秒傷害遞減的位置）：元素弱點時只有那種元素照算、其他的打 other 成；
     * 倒數大招之後的虛弱乘 1 + weakTaken。
     */
    public double damageFactor(final State state, final Map<DamageType, Double> dealt) {
        if (state == null) {
            return 1;
        }
        double factor = 1;
        final PhaseMechanic.Active lock = state.lock;
        if (lock != null && lock.spec.kind() == PhaseMechanic.Kind.ELEMENT) {
            factor *= PhaseMechanic.elementFactor(dealt, lock.element, lock.spec.element().other());
        }
        if (now() < state.weakUntilTick) {
            factor *= 1 + state.weakTaken;
        }
        return factor;
    }

    /**
     * 血條名稱前面的字：站位圈、倒數大招（「🔒 無敵：…」）、元素弱點（只吃哪一種、打掉多少）、虛弱；都沒有 null。
     */
    public String barPrefix(final State state, final LivingEntity boss) {
        final PhaseMechanic.Active lock = state.lock;
        if (lock != null && lock.spec.kind() == PhaseMechanic.Kind.ZONES) {
            return "<white>🔒 無敵：站滿光圈 " + charged(lock) + "/" + lock.charges.length + "（剩 " + secondsLeft(lock) + " 秒）<dark_gray>｜ ";
        }
        if (lock != null && lock.spec.kind() == PhaseMechanic.Kind.COUNTDOWN) {
            return "<white>🔒 無敵：<#ff5555>" + ItemRenderer.escape(lock.spec.countdown().name()) + " " + secondsLeft(lock)
                    + " 秒<white>，躲進綠色安全區 <dark_gray>｜ ";
        }
        if (lock != null && lock.spec.kind() == PhaseMechanic.Kind.ELEMENT) {
            return "<white>✦ 只吃 " + PhaseMechanic.elementLabel(lock.element) + " <white>" + Math.round(progress(boss, lock) * 100)
                    + "%（剩 " + secondsLeft(lock) + " 秒）<dark_gray>｜ ";
        }
        final long now = now();
        if (now < state.weakUntilTick) {
            return "<gold>💥 虛弱：受到的傷害 +" + Math.round(state.weakTaken * 100) + "%（剩 " + Math.max(1, (state.weakUntilTick - now + 19) / 20)
                    + " 秒）<dark_gray>｜ ";
        }
        return null;
    }

    // ---- 小工具 ----

    /**
     * 圈的位置（世界座標 x、z）：count 個平均分在場地中心周圍 ring 格；場地正在收縮（萬界崩解）時以收縮圈的中心為準、
     * 整個圈都在收縮圈裡面（跟結晶一樣）。落點要是地板（圈心與四個方向都是）：不是的話往中心拉近再找。
     */
    private List<double[]> placeSpots(final Arena arena, final int count, final double ring, final double radius) {
        final Location center = arena.center();
        double cx = center.getX();
        double cz = center.getZ();
        double reach = ring;
        final double[] bounds = arena.bounds();
        if (bounds != null) {
            cx = bounds[0];
            cz = bounds[1];
            reach = Math.max(0, Math.min(ring, bounds[2] - radius - 0.5));
        }
        final double offset = random.nextDouble() * Math.PI * 2;
        final List<double[]> spots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final double angle = offset + Math.PI * 2 * i / Math.max(1, count);
            double[] found = null;
            for (final double scale : new double[] {1.0, 0.8, 0.6, 0.4, 0.2}) {
                final double x = cx + Math.cos(angle) * reach * scale;
                final double z = cz + Math.sin(angle) * reach * scale;
                final double r = radius * 0.7;
                if (arena.floor(x, z) && arena.floor(x + r, z) && arena.floor(x - r, z) && arena.floor(x, z + r) && arena.floor(x, z - r)) {
                    found = new double[] {x, z};
                    break;
                }
            }
            spots.add(found != null ? found : new double[] {cx + Math.cos(angle) * Math.min(1.5, reach), cz + Math.sin(angle) * Math.min(1.5, reach)});
        }
        return spots;
    }

    private static Location spot(final Arena arena, final double[] xz, final double lift) {
        final Location center = arena.center();
        return new Location(center.getWorld(), xz[0], center.getY() + lift, xz[1]);
    }

    private long secondsLeft(final PhaseMechanic.Active lock) {
        return Math.max(0, (lock.untilTick - now() + 19) / 20);
    }

    private static int charged(final PhaseMechanic.Active lock) {
        int full = 0;
        for (final double charge : lock.charges) {
            if (charge >= 1) {
                full++;
            }
        }
        return full;
    }

    private void broadcast(final Arena arena, final String miniMessage) {
        for (final Player player : arena.audience()) {
            player.sendMessage(plugin.text().parse(miniMessage));
        }
    }

    private void alert(final Arena arena, final String[] lines) {
        final Title title = Title.title(plugin.text().parse(lines[0]), plugin.text().parse(lines.length > 1 ? lines[1] : ""),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2600), Duration.ofMillis(400)));
        for (final Player player : arena.audience()) {
            // 2026-11-02（C 組）：裝了模組的人改送預警（上面那排、留 3 秒）
            if (!tw.exilecore.clientmod.AlertFeed.banner(plugin, player, "lock-msg:" + arena.bossName(), lines, 60)) {
                tw.exilecore.gui.Titles.show(player, title);
            }
        }
    }

    /** 每個人的戰鬥提示列（約 2 秒更新一次）。 */
    private void notice(final Arena arena, final Function<Player, String> text, final long now) {
        for (final Player player : arena.audience()) {
            final tw.exilecore.character.PlayerState state = plugin.players().get(player);
            state.notice(text.apply(player), now + 45);
            plugin.players().display(player, state);
        }
    }

    /** 活著、會挨打（生存、冒險模式）的玩家：給 {@link Arena#fighters} 用。 */
    public static List<Player> hittable(final Collection<Player> players) {
        final List<Player> list = new ArrayList<>();
        for (final Player player : players) {
            if (!player.isDead() && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) {
                list.add(player);
            }
        }
        return list;
    }

    /** 小數去掉多餘的 0（5.0 → 5、2.5 → 2.5）。 */
    static String trim(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(Math.round(value * 10) / 10.0);
    }
}
