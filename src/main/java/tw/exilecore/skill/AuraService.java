package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 光環（含捷）：綁在上方按鍵格就開著（2026-10；以前是放一次開、再放一次關）。開著的時候：
 * <ul>
 *   <li>保留一部分最大魔力（全部光環加總，寫進屬性表的 {@code mana.reserved}；魔力池變小、回復速度不變）；</li>
 *   <li>效果加在自己身上（慷慨輔助除外）、範圍內的隊友身上（每半秒看一次誰在範圍內）、自己的召喚物身上（{@link #effectsFor}；只給自己的增益不給召喚物）；</li>
 *   <li>捷：擊殺時從屍體的位置放一個隱藏技能（用捷的等級與輔助）；2026-10 照 PoE2 加上觸發條件
 *       （{@link AuraSpec.KillCondition}：要用攻擊打死、屍體身上要有哪種異常、要過量擊殺）。</li>
 *   <li>2026-10 代價與獸性：{@link AuraSpec.LifeCost}（赤怒：每秒失去生命）、{@link AuraSpec.Fury}（獸血沸：攻擊累積，滿了爆發成狂暴）。</li>
 * </ul>
 * 開著的光環記在角色進度（換角色、重新登入會自動開回來）；寶石拿出面板、保留超過 100% 就自動關掉。
 * 數值每 2 秒照目前的屬性表重算（換裝、升級寶石馬上跟上）。
 */
final class AuraService implements Listener {

    /** 角色進度的鍵：開著的光環代號（逗號分隔）。 */
    static final String PROGRESS_KEY = "aura.active";
    /** 慷慨：隊友得到的效果倍率。 */
    static final double GENEROSITY_ALLY_BONUS = 1.4;
    /** 捷的擊殺效果找多遠內的下一個目標當方向。 */
    private static final double HERALD_SEARCH = 8;

    /** 一個開著的光環。 */
    static final class Active {
        final String skillId;
        SkillBuild build;
        /** 給自己的效果。 */
        List<Modifier> effects = List.of();
        /** 給隊友的效果（慷慨時更強）。 */
        List<Modifier> allyEffects = List.of();
        double reserve;
        double radius;
        /** 常駐技能（焚心業火）暫停中：生命只剩 1 點時停下來（不燒敵人、不燒自己、效果不算），回到 1 點以上就繼續。 */
        boolean paused;
        /** 上次提示暫停是哪個 tick（回復一點、燒掉一點會一直來回切，提示不要洗版）。 */
        long pauseNotice = Long.MIN_VALUE;
        final long since;
        /** 夥伴（2026-10）：死掉之後哪個 tick 才能再出來。 */
        long companionReady;
        int hadCompanion;
        /** 照隻數算精魂的召喚（2026-10）：現在的精魂養得起幾隻（其他的是不限）。 */
        int minionCap = Integer.MAX_VALUE;

        Active(final String skillId, final long since) {
            this.skillId = skillId;
            this.since = since;
        }

        String name() {
            return build.definition().name();
        }
    }

    private final ExileCore plugin;
    private final SkillConfig config;
    private final SkillService service;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final SkillExecutor executor;
    /** 焚燒光環打敵人用。 */
    private final tw.exilecore.combat.SkillDamage damage;
    private final Map<UUID, LinkedHashMap<String, Active>> active = new HashMap<>();
    /** 從隊友的光環得到的效果（每半秒重算；沒變就不動屬性表）。 */
    private final Map<UUID, List<Modifier>> received = new HashMap<>();
    private final Map<UUID, String> receivedKey = new HashMap<>();

    // ---- 2026-10（照 PoE2）：捷的觸發條件、開著的代價、獸性 ----

    /** {@link #tick} 多久跑一次（秒）：每秒失去的生命照這個比例扣。 */
    static final double TICK_SECONDS = 0.5;
    /** 最後一擊的紀錄留多久（tick）；怪物死的時候只認同一個 tick 打的。 */
    private static final long LAST_HIT_KEEP = 100;
    /** 獸性：同一次技能打中好幾個只算 1 點（跟怒氣一樣隔 4 tick）。 */
    private static final long FURY_GAIN_TICKS = 4;
    /** 狂暴的增益代號（後面接技能代號）。 */
    private static final String FURY_BUFF = "fury.";

    /**
     * 怪物最近挨的一下（捷的觸發條件用）。
     *
     * @param ownAttack 是這個玩家自己的攻擊擊中，而且不是捷的效果打的（法術、召喚物、反彈、屍體爆炸都不算）
     * @param overkill  這一下多打出來的傷害是牠最大生命的幾 %（沒打死 = 0）
     */
    private record LastHit(long tick, UUID player, boolean ownAttack, double overkill) {
    }

    private final Map<UUID, LastHit> lastHits = new HashMap<>();
    /** 怪物死的那一刻身上的異常（死亡事件一開始先記下來：異常模組等一下就清掉了）。 */
    private final Map<UUID, Set<AilmentType>> deathAilments = new HashMap<>();

    /** 一個玩家的獸性。 */
    private static final class FuryState {
        int points;
        long lastGain = -FURY_GAIN_TICKS;
        /** 狂暴到哪個 tick（0 = 沒有在狂暴）。 */
        long frenzyUntil;
        /** 狂暴是哪個技能開的（增益代號用）。 */
        String skillId;
        /** 上次提示「要變身才會爆發」是哪個 tick。 */
        long formNotice = -1000;
    }

    private final Map<UUID, FuryState> furies = new HashMap<>();

    AuraService(final ExileCore plugin, final SkillConfig config, final SkillService service, final SkillTargets targets,
                final SkillVisuals visuals, final SkillExecutor executor, final tw.exilecore.combat.SkillDamage damage) {
        this.plugin = plugin;
        this.damage = damage;
        this.config = config;
        this.service = service;
        this.targets = targets;
        this.visuals = visuals;
        this.executor = executor;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    // ---- 查詢 ----

    boolean isActive(final UUID player, final String skillId) {
        final Map<String, Active> mine = active.get(player);
        return mine != null && mine.containsKey(skillId);
    }

    /** 給動作列（HUD）：開著的光環名字，像「✦ 凜冬 熾焰」；超過三個只列三個再加「+N」；沒開是空字串。 */
    /** 開著的光環的名字（快捷欄左邊；2026-10）。 */
    java.util.List<String> hudNames(final UUID player) {
        final Map<String, Active> mine = active.get(player);
        if (mine == null || mine.isEmpty()) {
            return java.util.List.of();
        }
        final java.util.List<String> names = new java.util.ArrayList<>();
        for (final Active aura : mine.values()) {
            names.add(aura.name());
        }
        return names;
    }

    /** 動作列上的一小段（跟怒氣、連擊排在一起）：「獸性 12/20」或「狂暴 6s」；沒有回傳空字串。 */
    String furyHud(final UUID player) {
        final FuryState state = furies.get(player);
        final Active aura = state == null ? null : furyAura(active.get(player));
        if (aura == null) {
            return "";
        }
        final long now = now();
        if (now < state.frenzyUntil) {
            return String.format(Locale.ROOT, "<#ff7a3a>狂暴 %.0fs", Math.ceil((state.frenzyUntil - now) / 20.0));
        }
        return state.points > 0 ? "<#ff7a3a>獸性 " + state.points + "/" + aura.build.definition().aura().fury().max() : "";
    }

    String hudText(final UUID player) {
        final Map<String, Active> mine = active.get(player);
        if (mine == null || mine.isEmpty()) {
            return "";
        }
        final StringBuilder text = new StringBuilder("<#8fd3ff>✦");
        int shown = 0;
        for (final Active aura : mine.values()) {
            if (shown == 3) {
                text.append(" +").append(mine.size() - shown);
                break;
            }
            text.append(' ').append(tw.exilecore.item.ItemRenderer.escape(aura.name()));
            shown++;
        }
        return text.toString();
    }

    /** 這個玩家全部光環保留的百分比。 */
    /** 光環保留生命（元素的庇護）：保留的是生命。 */
    static boolean lifeReserved(final tw.exilecore.stats.StatSheet sheet) {
        return tw.exilecore.stats.StatKeys.flag(sheet, tw.exilecore.stats.StatKeys.FLAG_AURA_LIFE);
    }

    /**
     * 最多能保留幾 %（2026-10）：保留魔力 100%；保留生命的話要留至少 1 點生命——
     * 生命只有 1（混沌免疫）就一點都不能保留，跟 PoE 一樣開不了。
     */
    static double capacity(final tw.exilecore.stats.StatSheet sheet) {
        return Math.max(0, sheet.get(tw.exilecore.stats.StatKeys.SPIRIT_MAX)); // 2026-10：精魂（PoE2）
    }

    /** 一個光環實際保留多少、照隻數算精魂的召喚養得起幾隻。 */
    record Fit(double reserve, int minions) {
    }

    /**
     * 還剩 available 精魂時這個光環怎麼開（2026-10）：一般的光環固定保留 reserve；
     * 精魂召喚是「每一隻保留 minionReserve」（前 freeMinions 隻不用，照 PoE2），付得起幾隻就叫幾隻（最多到技能的上限）。
     * 連一隻都養不起（或固定的部分不夠）回傳 null。
     */
    static Fit fit(final SkillBuild build, final double available) {
        if (build.minionReserve() <= 0) {
            return build.reserve() > 0 && build.reserve() > available + 1e-9 ? null : new Fit(build.reserve(), Integer.MAX_VALUE);
        }
        final double left = available - build.reserve();
        if (build.reserve() > 0 && left < -1e-9) {
            return null;
        }
        final int max = Math.max(1, build.minions());
        final int free = Math.min(max, build.definition().aura().freeMinions());
        final int paid = (int) Math.floor((Math.max(0, left) + 1e-9) / build.minionReserve());
        final int count = Math.min(max, free + paid);
        if (count < 1) {
            return null;
        }
        return new Fit(build.reserve() + Math.max(0, count - free) * build.minionReserve(), count);
    }

    /** 開著的精魂召喚現在養得起幾隻；沒開著回傳 -1，不是照隻數算的回傳 Integer.MAX_VALUE。 */
    int minionCap(final UUID player, final String skillId) {
        final Map<String, Active> mine = active.get(player);
        final Active aura = mine == null ? null : mine.get(skillId);
        return aura == null ? -1 : aura.minionCap;
    }

    /** 開著的這個光環實際保留多少（沒開回傳 0）。 */
    double reserveOf(final UUID player, final String skillId) {
        final Map<String, Active> mine = active.get(player);
        final Active aura = mine == null ? null : mine.get(skillId);
        return aura == null ? 0 : aura.reserve;
    }

    double totalReserve(final UUID player) {
        final Map<String, Active> mine = active.get(player);
        if (mine == null) {
            return 0;
        }
        double total = 0;
        for (final Active aura : mine.values()) {
            total += aura.reserve;
        }
        return total;
    }

    /** 開著的光環名稱（顯示用）。 */
    List<String> activeNames(final UUID player) {
        final Map<String, Active> mine = active.get(player);
        if (mine == null) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        for (final Active aura : mine.values()) {
            names.add(aura.name());
        }
        return names;
    }

    /** 這個玩家身上收到幾個隊友的光環效果條目（顯示用）。 */
    int receivedCount(final UUID player) {
        final List<Modifier> list = received.get(player);
        return list == null ? 0 : list.size();
    }

    // ---- 開關 ----

    /** 放一次：開或關。回傳要顯示的訊息。 */
    String toggle(final Player player, final SkillBuild build) {
        final String id = build.definition().id();
        final LinkedHashMap<String, Active> mine = active.computeIfAbsent(player.getUniqueId(), uuid -> new LinkedHashMap<>());
        final Active existing = mine.remove(id);
        if (existing != null) {
            afterChange(player);
            visuals.auraOff(build.definition().visual(), player);
            subtitle(player, "<gray>" + tw.exilecore.item.ItemRenderer.escape(build.definition().name()) + " 已關閉");
            if (build.definition().aura().persistent()) {
                return "<gray>關閉：" + tw.exilecore.item.ItemRenderer.escape(build.definition().name());
            }
            return "<gray>關閉光環：" + tw.exilecore.item.ItemRenderer.escape(build.definition().name())
                    + (totalReserve(player.getUniqueId()) > 0 ? String.format(Locale.ROOT, "（還保留 %.0f 精魂）", totalReserve(player.getUniqueId())) : "");
        }
        final tw.exilecore.stats.StatSheet toggleSheet = plugin.players().sheet(player);
        final Fit fit = fit(build, capacity(toggleSheet) - totalReserve(player.getUniqueId()));
        if (fit == null) {
            return String.format(Locale.ROOT, "<red>精魂不夠：已經保留 %.0f／%.0f，%s 要 %.0f",
                    totalReserve(player.getUniqueId()), capacity(toggleSheet), tw.exilecore.item.ItemRenderer.escape(build.definition().name()),
                    build.reserve() + build.minionReserve());
        }
        final double total = totalReserve(player.getUniqueId()) + fit.reserve();
        final Active aura = new Active(id, now());
        compute(aura, build, fit);
        mine.put(id, aura);
        afterChange(player);
        visuals.auraOn(build.definition().visual(), player);
        subtitle(player, "<#8fd3ff>✦ " + tw.exilecore.item.ItemRenderer.escape(build.definition().name()) + " 開啟中");
        if (build.definition().aura().persistent()) {
            return "<#ffb070>常駐：" + tw.exilecore.item.ItemRenderer.escape(build.definition().name()) + " 生效中";
        }
        return String.format(Locale.ROOT, "<#8fd3ff>開啟光環：%s<gray>（保留 %.0f 精魂，共 %.0f）",
                tw.exilecore.item.ItemRenderer.escape(build.definition().name()), build.reserve(), total);
    }

    /** 畫面中間下方閃一下（開關光環時，比動作列的訊息明顯）。 */
    private void subtitle(final Player player, final String miniMessage) {
        player.showTitle(net.kyori.adventure.title.Title.title(net.kyori.adventure.text.Component.empty(), plugin.text().parse(miniMessage),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1400), java.time.Duration.ofMillis(400))));
    }

    private void compute(final Active aura, final SkillBuild build, final Fit fit) {
        compute(aura, build);
        aura.reserve = fit.reserve();
        aura.minionCap = fit.minions();
    }

    private void compute(final Active aura, final SkillBuild build) {
        aura.build = build;
        final List<Modifier> own = new ArrayList<>(build.generosity() ? List.of() : build.auraModifiers());
        own.addAll(build.grantModifiers()); // 2026-10：精魂輔助（只給自己）
        aura.effects = own;
        final List<Modifier> ally = new ArrayList<>();
        final AuraSpec spec = build.definition().aura();
        if (spec.allies() || build.generosity()) {
            for (final ScaledMod mod : spec.effects()) {
                ally.add(mod.toModifier(build.level(), build.auraEffect() * (build.generosity() ? GENEROSITY_ALLY_BONUS : 1.0),
                        "光環 " + build.definition().name()));
            }
        }
        aura.allyEffects = List.copyOf(ally);
        aura.reserve = build.reserve();
        aura.radius = spec.radius() * build.areaScale();
    }

    /** 開關或數值變了：存進角色進度、屬性表重算、魔力夾在新的上限內。 */
    private void afterChange(final Player player) {
        final Map<String, Active> mine = active.getOrDefault(player.getUniqueId(), new LinkedHashMap<>());
        if (plugin.characters() != null) {
            plugin.characters().setProgress(player, PROGRESS_KEY, String.join(",", mine.keySet()));
        }
        plugin.players().markDirty(player);
        final PlayerState state = plugin.players().get(player);
        plugin.players().sheet(player); // 重算（也把魔力夾進新的上限）
        plugin.players().display(player, state);
    }

    /** 夥伴召回的等待時間（死掉之後）。 */
    static final long COMPANION_RESPAWN = 160;

    /** 夥伴（2026-10）：開著的夥伴光環沒有活著的夥伴就叫一隻出來（死掉的等 8 秒）；關掉的收掉。 */
    private void companions(final long now) {
        final MinionService minions = service.minions();
        if (minions == null) {
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final Map<String, Active> mine = active.get(player.getUniqueId());
            final java.util.Set<String> keep = new java.util.HashSet<>();
            if (mine != null && !player.isDead()) {
                final boolean eligible = service.eligibleForCompanion(player);
                for (final Active aura : mine.values()) {
                    if (aura.build.definition().summon() == null
                            || SkillExecutor.hasFlag(aura.build.definition(), tw.exilecore.stats.StatKeys.FLAG_SUMMON_ON_FIRE)) {
                        if (aura.build.definition().summon() != null && eligible) {
                            keep.add(aura.skillId); // 焚魂：放火焰法術才叫，不自動補
                        }
                        continue;
                    }
                    if (!eligible) {
                        aura.hadCompanion = 0; // 營地裡收起來：出營地馬上出來
                        continue;
                    }
                    keep.add(aura.skillId);
                    // 2026-10：精魂召喚物可以好幾隻（骸骨兵…）：少了一隻就等一下補回滿
                    final int max = Math.max(1, Math.min(aura.build.minions(), aura.minionCap));
                    if (minions.count(player.getUniqueId(), aura.skillId) > max) {
                        minions.trim(player.getUniqueId(), aura.skillId, max); // 精魂或上限變少了：多的收掉
                    }
                    final int alive = minions.count(player.getUniqueId(), aura.skillId);
                    if (alive < aura.hadCompanion) {
                        aura.companionReady = now + COMPANION_RESPAWN; // 剛死掉：等一下再出來
                    }
                    aura.hadCompanion = alive;
                    if (alive < max && now >= aura.companionReady) {
                        int guard = 0;
                        while (minions.count(player.getUniqueId(), aura.skillId) < max && guard++ < max) {
                            minions.summon(player, aura.build);
                        }
                        aura.hadCompanion = minions.count(player.getUniqueId(), aura.skillId);
                    }
                }
            }
            minions.dismissCompanions(player.getUniqueId(), keep);
        }
    }

    /** 焚魂這類（2026-10 PoE2）：自己放了火焰法術，開著的這種精魂召喚各叫 count 隻出來。 */
    void fireSpellCast(final Player player, final int count) {
        final Map<String, Active> mine = active.get(player.getUniqueId());
        final MinionService minions = service.minions();
        if (mine == null || minions == null || !service.eligibleForCompanion(player)) {
            return;
        }
        for (final Active aura : mine.values()) {
            if (aura.build.definition().summon() != null
                    && SkillExecutor.hasFlag(aura.build.definition(), tw.exilecore.stats.StatKeys.FLAG_SUMMON_ON_FIRE)) {
                for (int i = 0; i < count; i++) {
                    minions.summonOrRefresh(player, aura.build); // 2026-10：滿了補最舊那隻的時間，不刪一隻生一隻
                }
            }
        }
    }

    /**
     * 這個光環現在開不開得起來：要綁在按鍵格；寫了 weapons 的還要拿著那種武器（2026-10：雷縛長棍、崩滅心咒要長棍、銀月庇要獸魂符、
     * 拾晶裝要弩、馭星訣要長棍或弩。以前光環不看武器，說明寫著「需要長棍」卻什麼武器都開著）。換掉武器就關，換回來自己開。
     */
    static boolean wearable(final PlayerState state, final SkillDefinition definition) {
        return boundToKey(state, definition.id()) && definition.usableWith(state.weaponTags());
    }

    /**
     * 2026-10：光環、精魂召喚要放在上方的綁鍵格（滑鼠四格＋快捷列六格）才生效（常駐開著，不用按）；
     * 只放在技能面板、沒綁鍵的不開。綁鍵格只有 10 格，所以主動技能加光環最多 10 個（照 PoE2 的技能數量限制）。
     */
    static boolean boundToKey(final PlayerState state, final String skillId) {
        final tw.exilecore.character.SkillLoadout loadout = state.skills();
        final int position = loadout.find(skillId);
        if (position < 0) {
            return false;
        }
        for (int slot = 0; slot < tw.exilecore.character.SkillLoadout.BIND_SLOTS; slot++) {
            if (loadout.bound(slot) == position) {
                return true;
            }
        }
        return false;
    }

    /** 換角色、登入：把進度裡記的光環開回來（寶石還在面板上、保留夠的才開）。 */
    void restore(final Player player, final PlayerState state) {
        active.remove(player.getUniqueId());
        dropDecals(player.getUniqueId()); // 換角色：上一隻角色的圈先收掉，這隻開著的下一次會重畫
        final String saved = state.progress().get(PROGRESS_KEY);
        if (saved == null || saved.isBlank()) {
            return;
        }
        final LinkedHashMap<String, Active> mine = new LinkedHashMap<>();
        final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
        double total = 0;
        for (final String id : saved.split(",")) {
            final SkillDefinition definition = config.skill(id.trim());
            final int position = state.skills().find(id.trim());
            if (definition == null || definition.aura() == null || position < 0 || !wearable(state, definition)) {
                continue;
            }
            final SkillBuild build = service.build(state, sheet, definition, state.skills().skill(position));
            final Fit fit = fit(build, capacity(sheet) - total);
            if (fit == null) {
                continue;
            }
            final Active aura = new Active(definition.id(), now());
            compute(aura, build, fit);
            mine.put(definition.id(), aura);
            total += fit.reserve();
        }
        if (!mine.isEmpty()) {
            active.put(player.getUniqueId(), mine);
            plugin.players().markDirty(player);
        }
    }

    // ---- 屬性表 ----

    /** 屬性表的來源：自己的光環效果、全部的保留、隊友給的。 */
    List<Modifier> modifiers(final Player player) {
        final Map<String, Active> mine = active.get(player.getUniqueId());
        final List<Modifier> fromAllies = received.get(player.getUniqueId());
        if ((mine == null || mine.isEmpty()) && (fromAllies == null || fromAllies.isEmpty())) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>();
        double reserve = 0;
        if (mine != null) {
            for (final Active aura : mine.values()) {
                if (!aura.paused) {
                    list.addAll(aura.effects);
                }
                reserve += aura.reserve;
            }
        }
        if (reserve > 0) {
            list.add(Modifier.flat(StatKeys.SPIRIT_RESERVED, reserve, "光環保留")); // 2026-10：保留的是精魂
        }
        if (fromAllies != null) {
            list.addAll(fromAllies);
        }
        return list;
    }

    /**
     * 這個玩家的召喚物也吃到的光環效果：自己開的「給隊友的」光環（慷慨的也算）加上隊友給的。
     * 2026-10：只給自己的常駐增益（allies: false）不再給召喚物，見 {@link SkillBuild#minionAuraModifiers}。
     */
    List<Modifier> effectsFor(final UUID owner) {
        final List<Modifier> list = new ArrayList<>();
        final Map<String, Active> mine = active.get(owner);
        if (mine != null) {
            for (final Active aura : mine.values()) {
                if (!aura.paused) {
                    list.addAll(aura.build.minionAuraModifiers());
                }
            }
        }
        final List<Modifier> fromAllies = received.get(owner);
        if (fromAllies != null) {
            list.addAll(fromAllies);
        }
        return list;
    }

    // ---- 每 tick ----

    /** 腳下平面圈（{@link GroundDecals}）目前畫的是哪幾個光環（顏色照順序）；換了就重畫。 */
    private final Map<UUID, String> decalSignature = new HashMap<>();
    /** 光環法陣有幾格寬（符文在半徑的 87.5% 上繞）。 */
    private static final double SIGIL_SIZE = 2.2;
    private GroundDecals decals;

    void decals(final GroundDecals decals) {
        this.decals = decals;
    }

    /** 上次照面板開光環時，面板上的光環（代號照順序）；一樣就不用重排。 */
    private final Map<UUID, String> signature = new HashMap<>();
    /** 上次因為保留不夠沒開的光環（一樣就不再提醒）。 */
    private final Map<UUID, String> lastSkipped = new HashMap<>();

    /**
     * 光環放在技能面板上、而且綁在按鍵格就開著（2026-10，不用按鍵開關）：照按鍵的順序（數字鍵 3→8，再來左、右、蹲左、蹲右）開，保留的魔力加起來不超過 100%，
     * 放不下的那幾個不開；面板上拿掉的關掉。面板上的光環沒變就不做事（每半秒呼叫）。
     */
    void sync(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final List<String> desired = new ArrayList<>();
        if (state.isLoaded() && state.hasCharacter() && !player.isDead()) {
            for (final tw.exilecore.character.SkillLoadout.Entry entry : state.skills().entriesInKeyOrder()) {
                final SkillDefinition definition = config.skill(entry.skillId());
                if (definition != null && definition.aura() != null && !definition.hidden() && wearable(state, definition)) {
                    desired.add(entry.skillId() + "@" + entry.level() + ":" + entry.encodeSupports());
                }
            }
        }
        String key = String.join("|", desired);
        if (lastSkipped.containsKey(player.getUniqueId())) {
            key += "#" + now() / 40; // 有光環因為保留不夠沒開：每 2 秒重試一次（降低保留的天賦、裝備換上之後就會開）
        }
        if (key.equals(signature.get(player.getUniqueId()))) {
            return;
        }
        signature.put(player.getUniqueId(), key);
        final LinkedHashMap<String, Active> mine = active.computeIfAbsent(player.getUniqueId(), uuid -> new LinkedHashMap<>());
        final LinkedHashMap<String, Active> next = new LinkedHashMap<>();
        final List<String> opened = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        double total = 0;
        if (!desired.isEmpty()) {
            final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
            // 2026-10：照按鍵順序開（數字鍵 3→8，再來左、右、蹲左、蹲右）：精魂不夠時前面的先召喚
            for (final tw.exilecore.character.SkillLoadout.Entry entry : state.skills().entriesInKeyOrder()) {
                final SkillDefinition definition = config.skill(entry.skillId());
                if (definition == null || definition.aura() == null || definition.hidden() || next.containsKey(definition.id())
                        || !wearable(state, definition)) {
                    continue;
                }
                final SkillBuild build = service.build(state, sheet, definition, entry);
                final Fit fit = fit(build, capacity(sheet) - total);
                if (fit == null) {
                    skipped.add(definition.name());
                    continue;
                }
                Active aura = mine.get(definition.id());
                if (aura == null) {
                    aura = new Active(definition.id(), now());
                    opened.add(definition.name());
                    visuals.auraOn(definition.visual(), player);
                }
                compute(aura, build, fit);
                next.put(definition.id(), aura);
                total += fit.reserve();
            }
        }
        final List<String> closed = new ArrayList<>();
        for (final Active aura : mine.values()) {
            if (!next.containsKey(aura.skillId)) {
                closed.add(aura.name());
                visuals.auraOff(aura.build.definition().visual(), player);
            }
        }
        mine.clear();
        mine.putAll(next);
        if (mine.isEmpty()) {
            active.remove(player.getUniqueId());
        }
        final String skippedText = String.join("、", skipped);
        final boolean skipNews = !skipped.isEmpty() && !skippedText.equals(lastSkipped.get(player.getUniqueId()));
        if (skipped.isEmpty()) {
            lastSkipped.remove(player.getUniqueId());
        } else {
            lastSkipped.put(player.getUniqueId(), skippedText);
        }
        if (opened.isEmpty() && closed.isEmpty() && !skipNews) {
            afterChange(player); // 等級、輔助變了：數值重算
            return;
        }
        afterChange(player);
        final List<String> parts = new ArrayList<>();
        if (!opened.isEmpty()) {
            parts.add("<#8fd3ff>✦ 光環開啟：" + tw.exilecore.item.ItemRenderer.escape(String.join("、", opened)));
        }
        if (!closed.isEmpty()) {
            parts.add("<gray>光環關閉：" + tw.exilecore.item.ItemRenderer.escape(String.join("、", closed)));
        }
        if (skipNews || !skipped.isEmpty() && (!opened.isEmpty() || !closed.isEmpty())) {
            parts.add("<red>精魂不夠，沒開：" + tw.exilecore.item.ItemRenderer.escape(String.join("、", skipped)));
        }
        state.notice(String.join("<dark_gray>　", parts), now() + 80);
        plugin.players().display(player, state);
    }

    /** 每 10 tick：照技能面板開關光環；檢查數值有沒有變（每 40 tick）、保留有沒有超過；算隊友；畫光環（每 40 tick）。 */
    void tick(final long now) {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                sync(player);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("{} 的光環出錯", player.getName(), e);
            }
        }
        if (active.isEmpty() && received.isEmpty() && decalSignature.isEmpty()) {
            return; // 腳下還有圈的（剛換角色、剛全關）要往下走到最後收掉（2026-10 修正：以前最後一個光環關掉時圈會留著）
        }
        final boolean periodic = (now / 10) % 4 == 0;
        for (final UUID uuid : new ArrayList<>(active.keySet())) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) {
                active.remove(uuid);
                continue;
            }
            try {
                validate(player, now, periodic);
            } catch (final RuntimeException e) {
                // 2026-10：一個人的屬性表算不出來（例如身上有壞掉的物品）不能拖累全部的人——
                // 以前這裡一丟錯，後面的隊友光環、夥伴／精魂召喚、焚燒、代價全都不跑，所有人都召喚不出來
                plugin.getSLF4JLogger().error("{} 的光環檢查出錯", player.getName(), e);
            }
        }
        try {
            refreshAllies();
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("隊友光環更新出錯", e);
        }
        try {
            companions(now);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("夥伴、精魂召喚更新出錯", e);
        }
        // 2026-10：捷的最後一擊紀錄過期的清掉；開著的代價（赤怒）、獸性（獸血沸）
        lastHits.values().removeIf(hit -> now - hit.tick() > LAST_HIT_KEEP);
        deathAilments.clear();
        try {
            lifeCosts(now);
            furyTick(now);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("光環的代價／獸性出錯", e);
        }
        // 焚燒光環（焚心業火）：每半秒燒周圍的敵人，自己也燒
        for (final Map.Entry<UUID, LinkedHashMap<String, Active>> entry : new ArrayList<>(active.entrySet())) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || player.isDead()) {
                continue;
            }
            for (final Active aura : new ArrayList<>(entry.getValue().values())) {
                if (aura.build.definition().aura().burns()) {
                    try {
                        burn(player, aura);
                    } catch (final RuntimeException e) {
                        plugin.getSLF4JLogger().error("焚燒光環 {} 出錯", aura.skillId, e);
                    }
                }
            }
        }
        // 2026-10：對敵人的光環（威壓）：範圍內的敵人受到的傷害增加，待久了更多
        for (final Map.Entry<UUID, LinkedHashMap<String, Active>> entry : new ArrayList<>(active.entrySet())) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || player.isDead()) {
                continue;
            }
            for (final Active aura : new ArrayList<>(entry.getValue().values())) {
                if (aura.build.definition().aura().enemy() != null) {
                    try {
                        oppress(player, aura, now);
                    } catch (final RuntimeException e) {
                        plugin.getSLF4JLogger().error("對敵人的光環 {} 出錯", aura.skillId, e);
                    }
                }
            }
        }
        // 腳下一直轉的光圈（每半秒畫一次，一個光環一圈）：讓人一眼看得出光環開著。
        // 套了資源包的人看腳下的法陣（生一次就好，跟著人走、慢慢轉）；粒子只送給沒套的人（2026-10 減少粒子）
        for (final Map.Entry<UUID, LinkedHashMap<String, Active>> entry : active.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && !entry.getValue().isEmpty() && !player.isDead()) {
                // 常駐技能（焚心業火）不算光環：法陣只畫真的光環（它自己有橘色的燃燒範圍圈）
                final List<String> styles = entry.getValue().values().stream().filter(aura -> !aura.build.definition().aura().persistent())
                        .map(aura -> aura.build.definition().visual()).toList();
                final String prefix = "aura:" + player.getUniqueId() + ":";
                final String signature = String.join(",", styles);
                if (!signature.equals(decalSignature.get(player.getUniqueId()))) {
                    decals.removePrefix(prefix); // 光環換了：圈重畫
                    decalSignature.put(player.getUniqueId(), signature);
                }
                // 一個法陣（慢慢轉）＋每個光環一顆元素符文沿著外圈一起轉（2026-10，取代一圈套一圈的細線）
                decals.follow(prefix + "sigil", player, "aura_sigil", SIGIL_SIZE, SkillVisuals.sigilColor(styles), 0, true);
                for (int i = 0; i < styles.size(); i++) {
                    decals.follow(prefix + "rune:" + i, player, "rune_" + SkillVisuals.auraRune(styles.get(i)), SIGIL_SIZE,
                            SkillVisuals.auraColor(styles.get(i)), Math.PI * 2 * i / styles.size(), true);
                }
                visuals.auraPulse(decals.viewers(player.getLocation()), player, styles);
                if (entry.getValue().values().stream().noneMatch(aura -> aura.build.definition().aura().burns())) {
                    decals.remove("burn:" + player.getUniqueId()); // 焚燒光環關了
                }
            }
        }
        // 光環全關了、下線的：圈收掉
        for (final UUID uuid : new ArrayList<>(decalSignature.keySet())) {
            final LinkedHashMap<String, Active> mine = active.get(uuid);
            if (mine == null || mine.isEmpty() || plugin.getServer().getPlayer(uuid) == null) {
                decals.removePrefix("aura:" + uuid + ":");
                decals.remove("burn:" + uuid);
                decalSignature.remove(uuid);
            }
        }
    }

    /**
     * 焚燒光環的半秒：用技能自己的傷害（法術、火焰，吃法術與火焰的加成、會暴擊、會點燃）打一下範圍內每個敵人；
     * 自己受到最大生命＋能量護盾 selfBurn% ÷ 2 的火焰傷害（火焰抗性減免，先扣護盾）。在營地裡不燒自己，也不會被自己燒死以外的東西擋。
     */
    /** 焚燒光環每秒燒自己多少（照目前的生命、護盾、火焰抗性；給說明用）。 */
    double selfBurnPerSecond(final Player player, final AuraSpec spec) {
        final PlayerState state = plugin.players().get(player);
        final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
        final double resist = tw.exilecore.stats.Formulas.resistReduction(sheet.get(StatKeys.RESIST_FIRE), sheet.get(StatKeys.RESIST_MAX));
        return (state.maxLife() + Math.max(0, state.maxEnergyShield())) * spec.selfBurn() / 100.0 * (1 - resist);
    }

    /** 每個（玩家, 光環）→ 敵人 → 第一次進到範圍的 tick（待滿幾秒提升用）。 */
    private final Map<String, Map<UUID, Long>> oppressed = new HashMap<>();

    /** 對敵人的光環：範圍內的敵人加上「受到的傷害增加」（每 10 tick 補一次，持續 15 tick：離開範圍很快就沒了）。 */
    private void oppress(final Player player, final Active aura, final long now) {
        if (plugin.combat() == null) {
            return;
        }
        final AuraSpec.Enemy enemy = aura.build.definition().aura().enemy();
        final String key = player.getUniqueId() + ":" + aura.skillId;
        final Map<UUID, Long> since = oppressed.computeIfAbsent(key, k -> new HashMap<>());
        final java.util.Set<UUID> seen = new java.util.HashSet<>();
        final int level = aura.build.level();
        final double scale = aura.build.auraEffect();
        final long tick = plugin.getServer().getCurrentTick();
        for (final org.bukkit.entity.LivingEntity target : targets.around(player.getLocation(), aura.radius, player)) {
            final UUID id = target.getUniqueId();
            seen.add(id);
            final long first = since.computeIfAbsent(id, k -> now);
            final boolean ramped = enemy.rampSeconds() > 0 && now - first >= enemy.rampSeconds() * 20;
            final double taken = (ramped ? enemy.rampTaken() : enemy.taken()).at(level) * scale / 100.0;
            if (taken > 0) {
                plugin.combat().debuffs().apply(id, new tw.exilecore.combat.Debuffs.Debuff("aura." + aura.skillId + "." + player.getUniqueId(),
                        player.getUniqueId(), tick + 15, taken, false, enemy.tag()));
            }
        }
        since.keySet().retainAll(seen); // 離開範圍的重新計時
    }

    private void burn(final Player player, final Active aura) {
        final AuraSpec spec = aura.build.definition().aura();
        final Location at = player.getLocation();
        // 2026-10：生命只剩 1 點（能量護盾也沒了）就停下來，回到 1 點以上自己繼續
        final PlayerState owner = plugin.players().get(player);
        final boolean pause = owner.hasCharacter() && owner.life() <= 1 + 1e-6 && owner.energyShield() <= 0;
        if (pause != aura.paused) {
            aura.paused = pause;
            plugin.players().sheet(player); // 法術傷害的效果跟著拿掉／加回來
            plugin.players().display(player, owner);
            if (pause && now() - aura.pauseNotice >= 200) {
                aura.pauseNotice = now();
                player.sendActionBar(plugin.text().parse("<gray>" + tw.exilecore.item.ItemRenderer.escape(aura.build.definition().name())
                        + " 暫停：生命只剩 1 點，回到 1 點以上自動繼續"));
            }
        }
        if (pause) {
            decals.remove("burn:" + player.getUniqueId());
            return;
        }
        // 營地不燒自己；營地的練習場（訓練假人旁邊）算戰鬥區域，照燒（2026-10 修正：之前在練習場試不會燒）
        final boolean town = plugin.zones() != null && plugin.zones().inTown(at)
                && (plugin.training() == null || !plugin.training().inArena(at));
        // 火焰往上竄的一圈（範圍的邊緣）：套了資源包的人看騎在身上的橘色平面圈，粒子只送給沒套的人
        decals.follow("burn:" + player.getUniqueId(), player, aura.radius, 0xFF7A1A);
        final List<Player> viewers = decals.viewers(at);
        final int points = Math.max(8, (int) Math.round(aura.radius * 3));
        for (int i = 0; i < points && !viewers.isEmpty(); i++) {
            final double angle = Math.PI * 2 * i / points + (now() % 20) * 0.05;
            GroundDecals.particle(viewers, org.bukkit.Particle.FLAME, new Location(at.getWorld(), at.getX() + Math.cos(angle) * aura.radius,
                    at.getY() + 0.2, at.getZ() + Math.sin(angle) * aura.radius), 1, 0, 0.15, 0, 0.01, null);
        }
        tw.exilecore.skill.Fx.spawn(at.getWorld(), org.bukkit.Particle.FLAME, at.clone().add(0, 1.0, 0), 6, 0.35, 0.6, 0.35, 0.01); // 身上的火（每個人都看得到）
        if (damage != null) {
            for (final org.bukkit.entity.LivingEntity target : targets.around(at, aura.radius, player)) {
                damage.hit(player, target, aura.build.profile(), null);
            }
        }
        if (spec.selfBurn() <= 0 || town) {
            return;
        }
        final PlayerState state = owner;
        if (!state.hasCharacter() || state.isDead()) {
            return;
        }
        final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
        final double resist = tw.exilecore.stats.Formulas.resistReduction(sheet.get(StatKeys.RESIST_FIRE), sheet.get(StatKeys.RESIST_MAX));
        double rest = (state.maxLife() + Math.max(0, state.maxEnergyShield())) * spec.selfBurn() / 100.0 / 2.0 * (1 - resist);
        if (rest <= 0) {
            return;
        }
        final double shield = state.energyShield();
        if (shield > 0) {
            final double absorbed = Math.min(shield, rest);
            state.setEnergyShield(shield - absorbed);
            rest -= absorbed;
        }
        // 2026-10：燒不死自己，最多燒到剩 1 點生命
        state.setLife(Math.max(Math.min(1, state.life()), state.life() - rest));
        plugin.players().display(player, state);
    }

    private void validate(final Player player, final long now, final boolean periodic) {
        final LinkedHashMap<String, Active> mine = active.get(player.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        boolean changed = false;
        final List<String> closed = new ArrayList<>();
        double running = 0; // 照按鍵順序（3→8、左、右、蹲左、蹲右），前面的先拿精魂（照隻數算的召喚要知道還剩多少）
        final double capacityNow = capacity(plugin.players().sheet(player));
        for (final Active aura : new ArrayList<>(mine.values())) {
            final int position = state.skills().find(aura.skillId);
            final SkillDefinition definition = config.skill(aura.skillId);
            if (position < 0 || definition == null || definition.aura() == null || !wearable(state, definition)) {
                mine.remove(aura.skillId);
                closed.add(aura.name());
                changed = true;
                continue;
            }
            if (periodic) {
                // 數值照目前的屬性表重算（換裝、升級寶石、天賦）
                final SkillBuild build = service.build(state, plugin.players().sheet(player), definition, state.skills().skill(position));
                final List<Modifier> before = aura.effects;
                final double reserveBefore = aura.reserve;
                final int capBefore = aura.minionCap;
                Fit fit = fit(build, capacityNow - running);
                if (fit == null) {
                    // 精魂不夠了：照隻數算的召喚先留一隻的份（下面「超過 100%」會照按鍵順序把後面的關掉）
                    fit = new Fit(build.reserve() + Math.max(0, 1 - build.definition().aura().freeMinions()) * build.minionReserve(),
                            build.minionReserve() > 0 ? 1 : Integer.MAX_VALUE);
                }
                compute(aura, build, fit);
                if (!before.equals(aura.effects) || Math.abs(reserveBefore - aura.reserve) > 1e-9 || capBefore != aura.minionCap) {
                    changed = true;
                }
            }
            running += aura.reserve;
        }
        // 保留超過 100%（保留增加的裝備、天賦）：按鍵順序排在最後的先關（照按鍵的順序開）
        double total = totalReserve(player.getUniqueId());
        final double limit = capacity(plugin.players().sheet(player));
        while (total > limit + 1e-9 && !mine.isEmpty()) {
            Active last = null;
            for (final Active aura : mine.values()) {
                last = aura;
            }
            mine.remove(last.skillId);
            closed.add(last.name());
            total = totalReserve(player.getUniqueId());
            changed = true;
        }
        if (changed) {
            afterChange(player);
        }
        if (!closed.isEmpty()) {
            signature.remove(player.getUniqueId()); // 下次照面板重排（保留又夠了就再開）
            state.notice("<gray>光環關閉：" + tw.exilecore.item.ItemRenderer.escape(String.join("、", closed)), now + 60);
            plugin.players().display(player, state);
        }
    }

    /** 隊友在範圍內就得到效果；誰收到什麼變了才讓他的屬性表重算。 */
    private void refreshAllies() {
        final Map<UUID, List<Modifier>> next = new HashMap<>();
        final Map<UUID, StringBuilder> keys = new HashMap<>();
        if (plugin.party() != null) {
            for (final Map.Entry<UUID, LinkedHashMap<String, Active>> entry : active.entrySet()) {
                final Player caster = plugin.getServer().getPlayer(entry.getKey());
                if (caster == null) {
                    continue;
                }
                final List<Active> sharing = entry.getValue().values().stream().filter(aura -> !aura.allyEffects.isEmpty()).toList();
                if (sharing.isEmpty()) {
                    continue;
                }
                for (final UUID member : plugin.party().groupMembers(entry.getKey())) {
                    if (member.equals(entry.getKey())) {
                        continue;
                    }
                    final Player ally = plugin.getServer().getPlayer(member);
                    if (ally == null || !ally.getWorld().equals(caster.getWorld())) {
                        continue;
                    }
                    final double distance = ally.getLocation().distanceSquared(caster.getLocation());
                    for (final Active aura : sharing) {
                        if (distance <= aura.radius * aura.radius) {
                            next.computeIfAbsent(member, uuid -> new ArrayList<>()).addAll(aura.allyEffects);
                            keys.computeIfAbsent(member, uuid -> new StringBuilder()).append(entry.getKey()).append(':')
                                    .append(aura.skillId).append(':').append(aura.build.level()).append(':').append(aura.allyEffects.size()).append(':')
                                    .append(Math.round(aura.build.auraEffect() * 1000)).append(';');
                        }
                    }
                }
            }
        }
        final java.util.Set<UUID> affected = new java.util.HashSet<>(received.keySet());
        affected.addAll(next.keySet());
        for (final UUID uuid : affected) {
            final String newKey = keys.containsKey(uuid) ? keys.get(uuid).toString() : "";
            if (newKey.equals(receivedKey.getOrDefault(uuid, ""))) {
                continue;
            }
            if (newKey.isEmpty()) {
                received.remove(uuid);
                receivedKey.remove(uuid);
            } else {
                received.put(uuid, List.copyOf(next.get(uuid)));
                receivedKey.put(uuid, newKey);
            }
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                plugin.players().markDirty(player);
            }
        }
    }

    // ---- 2026-10：開著的代價（赤怒）----

    /** 營地不扣（練習場算戰鬥區域，照扣）——跟焚心業火燒自己的規則一樣。 */
    private boolean safeZone(final Location at) {
        return plugin.zones() != null && plugin.zones().inTown(at) && (plugin.training() == null || !plugin.training().inArena(at));
    }

    /** 失去最大生命 percent% 之後剩多少：直接扣生命（不經過能量護盾與抗性），扣不死，最低留 1 點（本來就不到 1 點的不動）。 */
    static double lifeAfterLoss(final double life, final double maxLife, final double percent) {
        return Math.max(Math.min(1, life), life - Math.max(0, maxLife) * Math.max(0, percent) / 100.0);
    }

    /** 扣生命；回傳是不是已經扣到底（只剩 1 點）。 */
    private boolean loseLife(final Player player, final PlayerState state, final double percent) {
        final double next = lifeAfterLoss(state.life(), state.maxLife(), percent);
        if (next != state.life()) {
            state.setLife(next);
            plugin.players().display(player, state);
        }
        return next <= 1 + 1e-6;
    }

    /** 暫停、恢復之後屬性表重算（效果拿掉／加回來）。 */
    private void recalc(final Player player, final PlayerState state) {
        plugin.players().markDirty(player);
        plugin.players().sheet(player);
        plugin.players().display(player, state);
    }

    /**
     * 開著的代價（{@link AuraSpec.LifeCost}）：每半秒扣一次生命。怒氣的那一份只在怒氣沒有在消退的時候扣（照 PoE2 的 Berserk）。
     * 扣到只剩 1 點就暫停（效果不算），生命回到最大生命的 {@value AuraSpec.LifeCost#RESUME_PERCENT}% 以上自己繼續。
     */
    private void lifeCosts(final long now) {
        for (final Map.Entry<UUID, LinkedHashMap<String, Active>> entry : new ArrayList<>(active.entrySet())) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || player.isDead()) {
                continue;
            }
            final PlayerState state = plugin.players().get(player);
            if (!state.hasCharacter() || state.isDead()) {
                continue;
            }
            for (final Active aura : new ArrayList<>(entry.getValue().values())) {
                final AuraSpec.LifeCost cost = aura.build.definition().aura().lifeCost();
                if (cost == null) {
                    continue;
                }
                if (aura.paused) {
                    if (state.life() >= state.maxLife() * AuraSpec.LifeCost.RESUME_PERCENT / 100.0) {
                        aura.paused = false;
                        recalc(player, state);
                    }
                    continue;
                }
                if (safeZone(player.getLocation())) {
                    continue;
                }
                final SkillResources resources = service.resources();
                final double percent = cost.percentPerSecond(resources == null ? 0 : resources.rage(player),
                        resources != null && resources.rageDecaying(player));
                if (percent <= 0 || !loseLife(player, state, percent * TICK_SECONDS)) {
                    continue;
                }
                aura.paused = true;
                recalc(player, state);
                if (now - aura.pauseNotice >= 200) {
                    aura.pauseNotice = now;
                    player.sendActionBar(plugin.text().parse(String.format(Locale.ROOT, "<gray>%s 暫停：生命只剩 1 點，回到 %.0f%% 以上自動繼續",
                            tw.exilecore.item.ItemRenderer.escape(aura.name()), AuraSpec.LifeCost.RESUME_PERCENT)));
                }
            }
        }
    }

    // ---- 2026-10：獸性（獸血沸）----

    /** 這個玩家開著的獸性光環（沒有回傳 null）。 */
    private static Active furyAura(final Map<String, Active> mine) {
        if (mine != null) {
            for (final Active aura : mine.values()) {
                if (aura.build.definition().aura().fury() != null) {
                    return aura;
                }
            }
        }
        return null;
    }

    /** 自己的攻擊擊中敵人：累積 1 點獸性（同一次技能只算一次；狂暴中不累積）。 */
    private void gainFury(final Player player, final Map<String, Active> mine) {
        final Active aura = furyAura(mine);
        if (aura == null) {
            return;
        }
        final FuryState state = furies.computeIfAbsent(player.getUniqueId(), uuid -> new FuryState());
        final long now = now();
        if (now < state.frenzyUntil || now - state.lastGain < FURY_GAIN_TICKS) {
            return;
        }
        state.lastGain = now;
        state.points = Math.min(aura.build.definition().aura().fury().max(), state.points + 1);
    }

    /** 現在是不是獸形；是的話變身的增益至少撐到 until（狂暴中一直維持獸形）。 */
    private boolean holdForm(final Player player, final long until) {
        final long now = now();
        for (final SkillBuffs.Buff buff : service.buffs().active(player.getUniqueId())) {
            if (buff.id().equals(SkillService.FORM_BUFF)) {
                if (buff.untilTick() < until) {
                    service.buff(player, SkillService.FORM_BUFF, buff.name(), (int) (until - now), buff.modifiers());
                }
                return true;
            }
        }
        return false;
    }

    /** 每半秒：獸性滿了（而且在獸形）就爆發；狂暴中扣生命、維持獸形；光環關了就收掉。 */
    private void furyTick(final long now) {
        for (final UUID uuid : new ArrayList<>(furies.keySet())) {
            final FuryState state = furies.get(uuid);
            final Player player = plugin.getServer().getPlayer(uuid);
            final Active aura = furyAura(active.get(uuid));
            if (player == null || aura == null) {
                if (player != null) {
                    endFrenzy(player, state, null);
                }
                furies.remove(uuid);
                continue;
            }
            final PlayerState playerState = plugin.players().get(player);
            if (player.isDead() || !playerState.hasCharacter() || playerState.isDead()) {
                endFrenzy(player, state, null);
                state.points = 0;
                continue;
            }
            final AuraSpec.Fury fury = aura.build.definition().aura().fury();
            if (state.frenzyUntil > 0 && now >= state.frenzyUntil) {
                endFrenzy(player, state, "<gray>" + tw.exilecore.item.ItemRenderer.escape(aura.name()) + "：狂暴結束");
            }
            if (now < state.frenzyUntil) {
                if (fury.form() && !holdForm(player, state.frenzyUntil)) {
                    endFrenzy(player, state, "<gray>" + tw.exilecore.item.ItemRenderer.escape(aura.name()) + "：回到人形，狂暴結束");
                } else if (fury.lifeLoss() > 0 && !safeZone(player.getLocation())
                        && loseLife(player, playerState, fury.lifeLoss() * TICK_SECONDS)) {
                    endFrenzy(player, state, "<gray>" + tw.exilecore.item.ItemRenderer.escape(aura.name()) + "：生命只剩 1 點，狂暴結束");
                }
                continue;
            }
            if (state.points < fury.max()) {
                continue;
            }
            if (fury.form() && !holdForm(player, now)) {
                if (now - state.formNotice >= 200) {
                    state.formNotice = now;
                    player.sendActionBar(plugin.text().parse("<gray>" + tw.exilecore.item.ItemRenderer.escape(aura.name())
                            + "：獸性滿了，變成獸形（熊、狼人、飛龍）就會爆發"));
                }
                continue;
            }
            startFrenzy(player, aura, fury, state, now);
        }
    }

    private void startFrenzy(final Player player, final Active aura, final AuraSpec.Fury fury, final FuryState state, final long now) {
        final int ticks = fury.ticks(state.points);
        state.points = 0;
        if (ticks <= 0) {
            return;
        }
        final String name = aura.name();
        final List<Modifier> mods = new ArrayList<>();
        for (final ScaledMod mod : fury.effects()) {
            mods.add(mod.toModifier(aura.build.level(), aura.build.auraEffect(), name));
        }
        if (fury.onslaught()) {
            final double speed = tw.exilecore.combat.KillEffects.ONSLAUGHT_SPEED;
            mods.add(Modifier.increased(StatKeys.ATTACK_SPEED, speed, "疾速"));
            mods.add(Modifier.increased(StatKeys.CAST_SPEED, speed, "疾速"));
            mods.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, speed, "疾速"));
        }
        state.frenzyUntil = now + ticks;
        state.skillId = aura.skillId;
        service.buff(player, FURY_BUFF + aura.skillId, name, ticks, mods);
        if (fury.form()) {
            holdForm(player, state.frenzyUntil);
        }
        player.sendActionBar(plugin.text().parse(String.format(Locale.ROOT, "<#ff7a3a>✦ %s：狂暴 %.0f 秒",
                tw.exilecore.item.ItemRenderer.escape(name), ticks / 20.0)));
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_RAVAGER_ROAR, org.bukkit.SoundCategory.PLAYERS, 0.5f, 1.3f);
    }

    /** 狂暴結束（時間到、回到人形、生命見底、光環關了）：增益拿掉。 */
    private void endFrenzy(final Player player, final FuryState state, final String message) {
        if (state.frenzyUntil <= 0) {
            return;
        }
        state.frenzyUntil = 0;
        if (state.skillId != null) {
            service.buffs().remove(player, FURY_BUFF + state.skillId);
        }
        if (message != null) {
            player.sendActionBar(plugin.text().parse(message));
        }
    }

    // ---- 捷：擊殺時觸發 ----

    /** 最後一擊多打出來的傷害是最大生命的幾 %（沒打死 = 0）。 */
    static double overkillPercent(final double damage, final double lifeLeft, final double maxLife) {
        return maxLife <= 0 ? 0 : Math.max(0, damage - Math.max(0, lifeLeft)) / maxLife * 100.0;
    }

    private double overkill(final LivingEntity defender, final double damage) {
        final tw.exilecore.monster.MonsterData data = plugin.monsters() == null ? null : plugin.monsters().data(defender).orElse(null);
        final org.bukkit.attribute.AttributeInstance max = defender.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (data == null || max == null) {
            return 0;
        }
        final double scale = plugin.monsters().healthScale(defender, data); // 原版血量 ÷ scale = 我們的生命
        return scale <= 0 ? 0 : overkillPercent(damage, defender.getHealth() / scale, max.getValue() / scale);
    }

    /**
     * 打中怪物（傷害還沒寫進去）：記下這一下是誰、用什麼打的、過量多少——怪物這一下死了的話，捷的觸發條件照這個看；
     * 自己的攻擊擊中順便累積獸性。召喚物打的算主人的擊中，但不是「自己的攻擊」。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        final Player credit = event.credit();
        if (credit == null || active.isEmpty() || event.skill() == null || !event.result().hit() || event.result().blocked()) {
            return;
        }
        final Map<String, Active> mine = active.get(credit.getUniqueId());
        final LivingEntity defender = event.defender();
        if (mine == null || mine.isEmpty() || defender instanceof Player) {
            return;
        }
        final boolean ownAttack = event.attacker() instanceof Player attacker && attacker.getUniqueId().equals(credit.getUniqueId())
                && event.skill().isAttack() && !event.skill().tags().contains(Tags.HERALD);
        lastHits.put(defender.getUniqueId(), new LastHit(now(), credit.getUniqueId(), ownAttack,
                ownAttack ? overkill(defender, event.result().total()) : 0));
        if (ownAttack) {
            gainFury(credit, mine);
        }
    }

    /** 怪物死了，其他模組還沒收拾之前：先記下牠身上的異常（捷的觸發條件用）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeathEarly(final EntityDeathEvent event) {
        if (active.isEmpty() || plugin.ailments() == null || event.getEntity() instanceof Player) {
            return;
        }
        final EnumSet<AilmentType> ailments = EnumSet.noneOf(AilmentType.class);
        for (final AilmentType type : AilmentType.values()) {
            if (plugin.ailments().has(event.getEntity(), type)) {
                ailments.add(type);
            }
        }
        if (!ailments.isEmpty()) {
            deathAilments.put(event.getEntity().getUniqueId(), ailments);
        }
    }

    /**
     * 怪物死了：算誰殺的（最後一擊的玩家，不然是傷害最多的），他開著的捷從屍體的位置放效果。
     * 2026-10（照 PoE2）：捷有觸發條件的要符合才放（{@link AuraSpec.KillCondition}）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final Set<AilmentType> ailed = deathAilments.remove(event.getEntity().getUniqueId());
        final LastHit last = lastHits.remove(event.getEntity().getUniqueId());
        if (active.isEmpty()) {
            return;
        }
        final LivingEntity entity = event.getEntity();
        if (entity instanceof Player || plugin.monsters() == null || !plugin.monsters().isMonster(entity)) {
            return;
        }
        Player credit = entity.getKiller();
        if (credit == null) {
            UUID best = null;
            double most = 0;
            for (final Map.Entry<UUID, Double> entry : plugin.monsters().ledger().damageTo(entity.getUniqueId()).entrySet()) {
                if (entry.getValue() > most) {
                    most = entry.getValue();
                    best = entry.getKey();
                }
            }
            credit = best == null ? null : plugin.getServer().getPlayer(best);
        }
        if (credit == null) {
            return;
        }
        final Map<String, Active> mine = active.get(credit.getUniqueId());
        if (mine == null || mine.isEmpty() || !credit.getWorld().equals(entity.getWorld())) {
            return;
        }
        final Location at = entity.getLocation().add(0, 0.2, 0);
        for (final Active aura : new ArrayList<>(mine.values())) {
            final AuraSpec spec = aura.build.definition().aura();
            if (!spec.herald()) {
                continue;
            }
            if (!spec.condition().always()) {
                // 最後一擊：同一個 tick、同一個玩家打的才認（持續傷害燒死、毒死的沒有「最後一擊」）
                final boolean struck = last != null && last.player().equals(credit.getUniqueId()) && now() - last.tick() <= 1;
                if (!spec.condition().met(struck && last.ownAttack(), ailed == null ? Set.of() : ailed, struck ? last.overkill() : 0)) {
                    continue;
                }
            }
            if (plugin.random().nextDouble() * 100 >= spec.onKillChance()) {
                continue;
            }
            final SkillDefinition sub = config.skill(spec.onKill());
            if (sub == null) {
                continue;
            }
            final PlayerState state = plugin.players().get(credit);
            final SkillBuild subBuild = SkillBuild.of(config, sub, aura.build.gemLevel(), state.level(), false, aura.build.supports(),
                    state.weaponTags(), plugin.players().sheet(credit));
            // 方向：朝屍體附近最近的另一隻怪，沒有就照主人的面向
            LivingEntity target = null;
            for (final LivingEntity candidate : targets.around(at, HERALD_SEARCH)) {
                if (candidate != entity) {
                    target = candidate;
                    break;
                }
            }
            final Vector direction = target != null
                    ? SkillTargets.center(target).toVector().subtract(at.toVector()).normalize()
                    : SkillTargets.facing(credit);
            visuals.herald(aura.build.definition().visual(), at);
            executor.executeFrom(Origin.at(credit, at, direction.lengthSquared() < 1e-6 ? SkillTargets.facing(credit) : direction), subBuild, target);
        }
    }

    // ---- 收掉 ----

    /** 登出、換角色（進度裡的紀錄留著，下次載入會開回來）。 */
    void forget(final UUID player) {
        oppressed.keySet().removeIf(key -> key.startsWith(player + ":")); // 2026-10-17：以前只增不減
        active.remove(player);
        furies.remove(player);
        dropDecals(player);
        signature.remove(player);
        lastSkipped.remove(player);
        received.remove(player);
        receivedKey.remove(player);
    }

    /** 這個玩家腳下的光環圈、焚燒圈收掉。 */
    private void dropDecals(final UUID player) {
        decalSignature.remove(player);
        if (decals != null) {
            decals.removePrefix("aura:" + player + ":");
            decals.remove("burn:" + player);
        }
    }

    void clear() {
        active.clear();
        furies.clear();
        lastHits.clear();
        deathAilments.clear();
        decalSignature.clear();
        received.clear();
        receivedKey.clear();
    }

    /** 給說明：技能面板上這個光環的狀態。 */
    static String stateText(final boolean on, final boolean persistent) {
        if (persistent) {
            return on ? "<#ffb070>目前：生效中<gray>（常駐技能：綁在上方按鍵格就一直有效，不用按、不保留精魂）"
                    : "<gray>常駐技能：綁在上方按鍵格就一直生效（不保留精魂，不算光環）<red>（現在沒綁）";
        }
        return stateText(on);
    }

    static String stateText(final boolean on) {
        return on ? "<#8fd3ff>目前：開著<gray>（綁在上方按鍵格就一直有效，不用按）"
                : "<gray>綁在上方按鍵格就一直開著，不用按<red>（沒綁鍵、或精魂不夠時不開）";
    }

    /** 給面板：技能格有沒有光環開著。 */
    boolean anyActive(final UUID player) {
        final Map<String, Active> mine = active.get(player);
        return mine != null && !mine.isEmpty();
    }

    /** 給 /stats。 */
    List<String> lines(final Player player) {
        final List<String> lines = new ArrayList<>();
        final Map<String, Active> mine = active.get(player.getUniqueId());
        if (mine != null && !mine.isEmpty()) {
            // 2026-10：分類列（以前全部叫「光環」：召喚、捷光環、精魂技能混在一起）
            final Map<String, List<String>> groups = new LinkedHashMap<>();
            for (final String group : List.of("光環", "捷光環", "召喚", "精魂技能")) {
                groups.put(group, new ArrayList<>());
            }
            for (final Active aura : mine.values()) {
                final SkillDefinition definition = aura.build.definition();
                final java.util.Set<String> tags = definition.tags();
                final String group = definition.summon() != null ? "召喚"
                        : tags.contains(tw.exilecore.stats.Tags.HERALD) ? "捷光環"
                        : tags.contains(tw.exilecore.stats.Tags.AURA) ? "光環" : "精魂技能";
                groups.get(group).add(tw.exilecore.item.ItemRenderer.escape(aura.name()));
            }
            lines.add(String.format(Locale.ROOT, "<#8fd3ff>精魂<gray>：保留 <white>%.0f</white>／%.0f",
                    totalReserve(player.getUniqueId()), capacity(plugin.players().sheet(player))));
            groups.forEach((group, names) -> {
                if (!names.isEmpty()) {
                    lines.add("<gray>　" + group + "：<white>" + String.join("<gray>、<white>", names));
                }
            });
        }
        final List<Modifier> fromAllies = received.get(player.getUniqueId());
        if (fromAllies != null && !fromAllies.isEmpty()) {
            final java.util.Set<String> sources = new java.util.LinkedHashSet<>();
            for (final Modifier modifier : fromAllies) {
                sources.add(modifier.source().replace("光環 ", ""));
            }
            lines.add("<#8fd3ff>隊友的光環<gray>：" + tw.exilecore.item.ItemRenderer.escape(String.join("、", sources)));
        }
        return lines;
    }

}
