package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 照 PoE2 做的幾個技能的專屬機制（2026-10）：
 * <ul>
 *   <li>蓄力放開（{@link ChargeSpec}）：穿雲箭、蓄勁一擊的完美時機，炎核爆的疊層</li>
 *   <li>whirl：旋矛擊每揮一次讓旋風升一階（最多 3 階），離開旋風範圍就崩散，階數越高越痛</li>
 *   <li>lance：霆落矛（2026-10-31 照 PoE2：貫穿的投射物，飛到準心插著）插 6 秒，每 0.8 秒朝附近的敵人放一道連鎖閃電，時間到消失；同時最多 3 支（第 4 支插下時最舊的那支引爆）。
 *       2026-10-15 照 PoE2 的風暴之槍：有怒星就消耗一顆，這支只插 1.5 秒但每 0.2 秒放電、物理全部轉閃電，時間到自動引爆並留下感電地面；
 *       你的其他技能打在矛附近（2.5 格）會把它提早引爆</li>
 *   <li>bolt_spear：擲雷槍（PoE2 的閃電長矛）有怒星就消耗一顆，矛分裂成 3 支</li>
 *   <li>ember：星火連珠每放一次在頭上叫出一顆火星（最多 6～16 顆），最後一次施放 1.3 秒後一顆接一顆射向敵人，每一顆更多 5% 傷害</li>
 * </ul>
 */
final class Signatures {

    /** 旋風最多幾階。 */
    static final int WHIRL_MAX = 3;
    /** 旋風幾 tick 沒再揮就崩散。2026-10-26 照 PoE2：旋風不會自己散掉，離開它才崩散（這次之前 60 tick＝3 秒）。 */
    static final long WHIRL_LIFE = Long.MAX_VALUE / 4;
    /** 旋風一階的半徑（格）與每多一階加多少。2026-10-26 照 PoE2：1.8 格、每多一階 +0.3（這次之前 2.2、+0.6）。 */
    static final double WHIRL_RADIUS = tw.exilecore.core.Units.metres(1.8); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final double WHIRL_RADIUS_PER_STAGE = tw.exilecore.core.Units.metres(0.3); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 崩散傷害：原技能一下的幾成（skills.yml 的 whirling_slash_collapse 寫 parent_damage: own 時照它自己的傷害表），每多一階更多 150%（PoE2）。 */
    static final double WHIRL_SHARE = 0.6;
    /** 2026-10-26 照 PoE2：崩散擊退 2.5 格、每多一階 +10%（這次之前 0.5 ＋ 每階 0.15 的速度）。 */
    static final double WHIRL_KNOCKBACK = 0.5;
    static final double WHIRL_KNOCKBACK_PER_STAGE = 0.1;
    static final double WHIRL_MORE_PER_STAGE = 1.5;
    /**
     * 2026-10-28 照 PoE2（poe2db Whirling Slash 品質：Collapse deals +(0—40)% more damage per additional stage）：
     * FLAT（百分點），加在每多一階更多 150% 上（gem_quality.yml 每 1% 品質 +2）。這次之前品質照通用規則增加傷害。
     */
    static final String WHIRL_QUALITY_MORE = "whirl.collapse_more_per_stage";
    /** 2026-10-28 照 PoE2：元素旋風獲得 50% 傷害為那種元素的額外傷害（windstorm_gain_all_damage_%_as_corresponding_element_if_empowered）。 */
    static final double WHIRL_ELEMENT_GAIN = 50;

    /** 霆落矛插多久、多久放電一次、同時幾支、放電與炸開是原技能一下的幾成。 */
    static final long LANCE_LIFE = 120;
    static final long LANCE_ZAP = 16;
    static final int LANCE_MAX = 3;
    /** 2026-10-31：消耗怒星擲出的那一支（技能描述的區域修飾器，值是怒星算幾顆）。 */
    static final String LANCE_CHARGED_MARK = "lance.charged_throw";
    static final double LANCE_ZAP_RANGE = tw.exilecore.core.Units.metres(2);   // 2026-10-26 照 PoE2：放電打 2 格內的敵人（這次之前 6）；2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final double LANCE_ZAP_SHARE = 0.25;
    static final double LANCE_BURST_SHARE = 0.8;
    /** 2026-10-15：消耗怒星的那一支：插多久、多久放電一次；炸開留下的帶電地是原技能一下的幾成；其他技能打在幾格內會引爆。 */
    static final long LANCE_CHARGED_LIFE = 30;
    static final long LANCE_CHARGED_ZAP = 4;
    static final double LANCE_GROUND_SHARE = 0.2;
    static final double LANCE_DETONATE_RANGE = tw.exilecore.core.Units.metres(3);   // 2026-10-26 照 PoE2：3 格（這次之前 2.5）；2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 2026-10-26 照 PoE2：消耗怒星的那一支放電打 3 格內；炸開時往 3 格內的敵人射 16 道閃電（隨機分給每個敵人）。 */
    static final double LANCE_CHARGED_ZAP_RANGE = tw.exilecore.core.Units.metres(3); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final int LANCE_BURST_BOLTS = 16;
    static final double LANCE_BURST_RANGE = tw.exilecore.core.Units.metres(3); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 擲雷槍消耗怒星時分裂成幾支。 */
    static final int BOLT_SPEAR_COPIES = 3;

    /** 火星懸浮多久（最後一次施放之後）、一顆接一顆隔幾 tick、每顆更多幾 %。 */
    static final long EMBER_HOVER = 26;
    static final long EMBER_GAP = 2;
    static final double EMBER_MORE_PER = 0.05;

    private final ExileCore plugin;
    private final SkillService service;

    private static final class Whirl {
        Location center;
        int stage;
        long expires;
        SkillBuild build;
        final String skillId;
        /** 2026-10-28：範圍倍率（不含揮矛那一下照階數放大的部分）。 */
        double area;
        /** 2026-10-28 照 PoE2：捲在元素地面上的是元素旋風（null = 一般的）。 */
        tw.exilecore.stats.DamageType element;
        /** 2026-10-28 照 PoE2（貝雷克的戒指、元素之章）：視為受到哪幾種元素地面強化。 */
        WindGround wind = WindGround.NONE;

        Whirl(final Location center, final SkillBuild build, final long now, final double area) {
            this.center = center;
            this.stage = 1;
            this.expires = now + WHIRL_LIFE;
            this.build = build;
            this.skillId = build.definition().id();
            this.area = area;
        }

        double radius() {
            return whirlRadius(stage, area);
        }
    }

    /** 旋風的半徑：1.8 格、每多一階 +0.3（PoE2），乘上範圍倍率（最少算 0.5）。 */
    static double whirlRadius(final int stage, final double area) {
        return (WHIRL_RADIUS + WHIRL_RADIUS_PER_STAGE * (Math.max(1, stage) - 1)) * Math.max(0.5, area);
    }

    /**
     * 2026-10-28 照 PoE2（sandstorm_swipe_nova_radius_+_per_stage）：揮矛那一圈跟旋風一樣大——這一下之後旋風是第幾階，
     * 揮的範圍就是那一階旋風的半徑（1.8 → 2.1 → 2.4 格）；回傳要乘在範圍上的倍率。這次之前固定 3.0 → 3.8 格（隨等級）。
     */
    static double slashScale(final int stage) {
        return whirlRadius(stage, 1) / WHIRL_RADIUS;
    }

    /** 這一下揮完旋風變幾階：身邊沒有自己的旋風是 1，有的話多一階（最多 3 階）。 */
    static int nextWhirlStage(final int current) {
        return Math.min(WHIRL_MAX, Math.max(0, current) + 1);
    }

    /** 2026-10-28 照 PoE2：崩散的傷害倍率：每多一階更多 (150 + 品質的 x)%。 */
    static double collapseMore(final int stage, final double qualityPerStage) {
        return 1 + (WHIRL_MORE_PER_STAGE + Math.max(0, qualityPerStage) / 100.0) * (Math.max(1, stage) - 1);
    }

    /** 2026-10-28：旋風矛用掉一個旋風：那個旋風幾階、是不是元素旋風。 */
    record ConsumedWhirl(int stage, tw.exilecore.stats.DamageType element) {
    }

    private record Lance(Location at, SkillBuild build, long until, long[] nextZap, long zapEvery) {
    }

    private static final class Embers {
        final Deque<SkillBuild> queue = new ArrayDeque<>();
        long fireAt;
        long nextShot;
        int fired;
        UUID target;
    }

    private final Map<UUID, Whirl> whirls = new HashMap<>();
    private final Map<UUID, Deque<Lance>> lances = new HashMap<>();
    private final Map<UUID, Embers> embers = new HashMap<>();
    /** 下一支霆落矛有沒有消耗怒星（beforeCast 記、lodge 用）。 */
    private final java.util.Map<UUID, Integer> chargedLance = new java.util.HashMap<>(); // 2026-10-24：值 = 這次的怒星算幾顆（倍星 2）
    private final java.util.Map<UUID, SkillBuild> unstagedLance = new java.util.HashMap<>(); // 2026-10-28：消耗怒星那一支換表之前的技能
    /** 2026-10-28 照 PoE2：旋風矛的旋風（Twisters）。 */
    private final Twisters twisters;

    Signatures(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
        this.twisters = new Twisters(plugin, service, this);
    }

    /** 2026-10-28 照 PoE2 Twister：旋風矛放出旋風（ProjectileEngine.launch 取代一般的投射物；圖騰、幻影也走這裡）。 */
    void twister(final Origin origin, final SkillBuild build) {
        twisters.launch(origin, build);
    }

    // ---- 蓄力放開 ----

    /** 這一招的速度倍率（法術：基礎施法時間 ÷ 現在的施法間隔；攻擊不算）。 */
    private static double speed(final SkillDefinition definition, final double intervalTicks) {
        return definition.isAttack() || definition.castTime() <= 0 || intervalTicks <= 0 ? 1.0 : definition.castTime() * 20 / intervalTicks;
    }

    /**
     * 按了一下（或按住中又收到一次）蓄力技能：還沒在蓄就開始蓄；同一招按住中就記下最後一次收到的時間。
     * 數字鍵沒辦法按住：按一下開始、再按一下放出。
     * 2026-10-26：回傳這一下是不是「開始新的引導」（SkillService 接著扣引導的第一次消耗）。
     */
    boolean press(final Player player, final SkillRuntime runtime, final PlayerState state, final int key, final SkillDefinition definition,
               final SkillBuild build, final ChargeSpec spec, final LivingEntity preferred, final StatSheet sheet, final long now) {
        final SkillRuntime.Charging charging = runtime.charging;
        if (charging != null && charging.skillId.equals(definition.id()) && charging.key == key) {
            if (!SkillLoadout.mouseKey(key) && now - charging.last > 3) {
                charging.releaseNow = true; // 數字鍵：再按一下放出
            }
            charging.last = now;
            return false;
        }
        final double interval = build.intervalTicks(sheet);
        final SkillRuntime.Charging started = new SkillRuntime.Charging(definition.id(), key, now,
                spec.perfect() ? spec.perfectTick(interval) : -1, preferred == null ? null : preferred.getUniqueId());
        started.interval = interval;
        // 2026-10-26 照 PoE2（爆焰矢的 GainsStages）：每一層是一次攻擊時間，吃攻擊速度
        started.speed = spec.channel().perAttack() && interval > 0 ? spec.stageTime() * 20 / interval : speed(definition, interval);
        runtime.charging = started;
        final boolean channel = spec.mode() != ChargeSpec.Mode.RELEASE;
        state.notice("<#ffd27f>" + ItemRenderer.escape(definition.name()) + (channel ? " 引導中…" : " 蓄力中…") + (SkillLoadout.mouseKey(key)
                ? (channel ? "（放開結束）" : "（放開出手）") : (channel ? "（再按一次結束）" : "（再按一次出手）")), now + 20);
        plugin.players().display(player, state);
        player.playSound(player.getLocation(), Sound.ITEM_CROSSBOW_LOADING_START, SoundCategory.PLAYERS, 0.6f, 1.2f);
        return true;
    }

    /** 每 tick：蓄力中的提示、完美時機的叮一聲、放開的偵測。 */
    private void tickCharge(final Player player, final SkillRuntime runtime, final long now) {
        final SkillRuntime.Charging charging = runtime.charging;
        if (charging == null) {
            return;
        }
        final ChargeSpec spec = ChargeSpec.withQuality(service.config().charge(charging.skillId), charging.build); // 2026-10-28：品質
        if (spec == null || !service.eligible(player) || player.isDead()) {
            runtime.charging = null;
            return;
        }
        final long held = now - charging.start;
        final boolean left = charging.key == SkillLoadout.KEY_LEFT || charging.key == SkillLoadout.KEY_SNEAK_LEFT;
        final boolean mouse = SkillLoadout.mouseKey(charging.key);
        final boolean pressedAgain = charging.releaseNow;
        charging.releaseNow = false;
        boolean released = pressedAgain || mouse && now - charging.last > (left ? 3 : 7);
        if (spec.mode() != ChargeSpec.Mode.RELEASE) {
            // 2026-10-26 照 PoE2：衝鋒（盾衝、蠻衝）、持續作用（凋蝕術）：按住期間一直有效，放開（過了最短引導時間）結束
            if (service.channelling().tick(player, charging, spec, released, now)) {
                runtime.charging = null;
                service.buffs().remove(player, "channel_block");
                service.buffs().remove(player, "channel_slow");
            }
            return;
        }
        if (!released && !service.channelling().drain(player, charging, spec, held)) {
            released = true; // 2026-10-26：魔力不夠撐下一秒，照現在的蓄力放出
        }
        final long limit = spec.perfect() ? charging.perfectAt + ChargeSpec.OVERHOLD
                : Math.round(spec.stageTicks(charging.speed) * spec.stages()) + ChargeSpec.OVERHOLD;
        if (held > limit) {
            released = true; // 蓄太久：自動放出
        }
        final PlayerState state = plugin.players().get(player);
        final SkillDefinition definition = service.config().skill(charging.skillId);
        final String name = definition == null ? charging.skillId : ItemRenderer.escape(definition.name());
        if (!released) {
            if (spec.perfect()) {
                if (!charging.cued && held >= charging.perfectAt) {
                    charging.cued = true;
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 1.0f, 2.0f);
                    player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1.2, 0), 18, 0.4, 0.5, 0.4, 0.05);
                }
                if (held % 2 == 0) {
                    state.notice(perfectBar(name, held, charging.perfectAt), now + 6);
                    plugin.players().display(player, state);
                }
            }
            if (spec.stages() > 0) {
                final int stage = spec.stagesAt(held, charging.speed);
                if (stage != charging.shownStage) {
                    charging.shownStage = stage;
                    state.notice(String.format(Locale.ROOT, "<#ff9a4d>%s 蓄力 %d／%d", name, stage, spec.stages()), now + 20);
                    plugin.players().display(player, state);
                    player.playSound(player.getLocation(), Sound.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, 0.7f,
                            0.6f + 1.2f * stage / spec.stages());
                }
                final Location eye = player.getEyeLocation();
                player.getWorld().spawnParticle(Particle.FLAME, eye.add(eye.getDirection().multiply(1.2)), 2 + charging.shownStage, 0.15, 0.15, 0.15, 0.01);
            }
            return;
        }
        runtime.charging = null;
        final long releaseHeld = (pressedAgain ? now : Math.min(now, charging.last + 1)) - charging.start;
        final StatSheet windowSheet = charging.build == null ? plugin.players().sheet(player)
                : charging.build.profile().effective(plugin.players().sheet(player)); // 2026-10-28 照 PoE2：技能自己的（穿雲箭的品質）也算；這次之前只看角色
        final boolean perfect = spec.isPerfect(releaseHeld, charging.interval, Math.max(0.05, 1 + windowSheet
                .increased(tw.exilecore.item.unique.UniqueStats.PERFECT_WINDOW, java.util.Set.of()) // 2026-10-26（虛望之盔）
                - service.supportFlat(player, charging.skillId, tw.exilecore.stats.SupportExtraKeys.PERFECT_WINDOW_SHORTER) / 100.0) // 2026-10-27 照 PoE2 良機：區間更短 35%
                * service.supportMechanics().lineage().perfectWindow(player, charging.skillId)); // 2026-10-27 照 PoE2 律勁：區間長 100%、每層更短 30%
        if (spec.perfect()) {
            service.supportMechanics().lineage().perfectResult(player, charging.skillId, perfect); // 2026-10-27 照 PoE2 律勁：成功疊一層、失敗全部失去
        }
        runtime.releasing = new SkillRuntime.Release(charging.skillId, releaseHeld, perfect, charging.speed);
        final LivingEntity target = charging.target == null ? null
                : plugin.getServer().getEntity(charging.target) instanceof LivingEntity living && living.isValid() ? living : null;
        try {
            service.perform(player, runtime, charging.key, target, now);
        } finally {
            runtime.releasing = null;
        }
    }

    /** 完美時機的蓄力條：▮▮▮▯▯，到了變金色的「★ 放開！」。 */
    private static String perfectBar(final String name, final long held, final long perfectAt) {
        if (held >= perfectAt - ChargeSpec.WINDOW_BEFORE && held <= perfectAt + ChargeSpec.WINDOW_AFTER) {
            return "<#ffd700><bold>★ 放開！</bold> <white>" + name;
        }
        if (held > perfectAt + ChargeSpec.WINDOW_AFTER) {
            return "<gray>" + name + "：錯過完美時機（放開照一般的出手）";
        }
        final int total = 10;
        final int filled = (int) Math.min(total, Math.round(total * held / (double) Math.max(1, perfectAt)));
        return "<#ffd27f>" + name + " <white>" + "▮".repeat(filled) + "<dark_gray>" + "▯".repeat(total - filled);
    }

    /** 放開的那一下：完美時機（更多傷害、代號接 .perfect，打中時觸發 perfect_hit）、疊層（更多傷害、更大範圍）。 */
    SkillBuild applyRelease(final Player player, final PlayerState state, final SkillBuild build, final ChargeSpec spec,
                            final SkillRuntime.Release release, final long now) {
        final String name = ItemRenderer.escape(build.definition().name());
        if (spec.perfect()) {
            if (release.perfect()) {
                state.notice("<#ffd700><bold>★ 完美時機！</bold> <white>" + name, now + 30);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1.0f, 1.4f);
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.6f);
            } else {
                state.notice("<gray>" + name + "：沒抓到完美時機", now + 30);
            }
        }
        final int stages = spec.stages() > 0 ? spec.stagesAt(release.held(), release.speed()) : 1;
        if (spec.stages() > 0) {
            state.notice(String.format(Locale.ROOT, "<#ff9a4d>%s 放出：%d／%d 層", name, stages, spec.stages()), now + 30);
        }
        final SkillBuild result = released(build, spec, stages, release.perfect(), state.sheet(), release.held() / 20.0);
        plugin.players().display(player, state);
        return result;
    }

    /** 引導／蓄力一層要幾 tick 的速度係數（跟玩家按住時一樣：爆焰矢每層一次攻擊時間）。 */
    static double stageSpeed(final SkillDefinition definition, final ChargeSpec spec, final double interval) {
        return spec.channel().perAttack() && interval > 0 ? spec.stageTime() * 20 / interval : speed(definition, interval);
    }

    /**
     * 2026-10-28：放開那一下的數值（純計算；這次之前寫在 applyRelease 裡，幻影銳眼的幻影引導也要用）：
     * 完美時機（更多傷害、代號接 .perfect，打中時觸發 perfect_hit）、疊層（更多傷害、更大範圍、更多投射物、每層屬性、疊滿才爆）、漸熾。
     */
    static SkillBuild released(final SkillBuild build, final ChargeSpec spec, final int stages, final boolean perfect, final StatSheet sheet,
                               final double heldSeconds) {
        SkillBuild result = build;
        if (spec.perfect() && perfect) {
            // 2026-10-28 照 PoE2：有完美時機那一段自己的表（stage.perfect）就換成它；這次之前一律乘 perfect_more（20 級的比例）
            result = result.withProfile(StageAttacks.perfect(result.profile(), 1 + spec.perfectMore() / 100.0, "完美時機")
                    .derive(result.profile().id() + SupportProcs.PERFECT_SUFFIX, Set.of(), Set.of()));
        }
        if (spec.stages() > 0) {
            final double more = spec.stageMore() * (stages - 1) / 100.0;
            if (more > 0) {
                result = result.withProfile(result.profile().withLocal(List.of(Modifier.more(StatKeys.DAMAGE, more, "蓄力"))));
            }
            final double area = spec.stageArea() * (stages - 1) / 100.0;
            if (area > 0) {
                result = result.withAreaAndProjectiles(result.areaScale() * Math.sqrt(1 + area), result.projectiles());
            }
            if (spec.stageProjectiles() > 0 && stages > 1) { // 2026-10-26：引導越久骨刺越多（Bonestorm）
                result = result.withAreaAndProjectiles(result.areaScale(),
                        Math.min(SkillBuild.MAX_PROJECTILES, result.projectiles() + spec.stageProjectiles() * (stages - 1)));
            }
            // 2026-10-26 照 PoE2 的引導：每一層給的屬性（爆焰矢每層額外獲得 120% 火焰）、只有疊滿才爆炸（爆焰矢）
            final ChargeSpec.Channel channel = spec.channel();
            if (channel.stageStat() != null && channel.stageValue() != 0) {
                result = result.withProfile(result.profile().withLocal(List.of(Modifier.flat(channel.stageStat(), channel.stageValue() * stages, "引導層數"))));
            }
            if (channel.explodeAtMax() && stages < spec.stages()) {
                result = result.withExplodeAndRange(0, result.range());
            }
        }
        // 2026-10-26 照 PoE2（引霆突）：完美時機放開衝得比較遠、轉換比較多閃電
        if (spec.perfect() && perfect) {
            final ChargeSpec.Channel channel = spec.channel();
            if (channel.perfectRange() > 0) {
                result = result.withExplodeAndRange(result.explode(), channel.perfectRange());
            }
            if (channel.perfectStat() != null && channel.perfectValue() != 0) {
                result = result.withProfile(result.profile().withLocal(List.of(Modifier.flat(channel.perfectStat(), channel.perfectValue(), "完美時機"))));
            }
        }
        // 2026-10-27 照 PoE2 Burgeon（漸熾）：引導越久越痛（放開型照按住多久；連續出手型照從開始引導到這一下）
        return Burgeon.apply(result, sheet, heldSeconds);
    }

    // ---- 放之前：消耗怒星（2026-10-15，照 PoE2 的風暴之槍、閃電長矛） ----

    /** 放之前（SkillService 呼叫）：霆落矛、擲雷槍有怒星就消耗一顆，回傳這一次要用的技能。 */
    SkillBuild beforeCast(final Player player, final SkillDefinition definition, final SkillBuild build, final PlayerState state, final long now) {
        final String signature = service.config().signature(definition.id());
        if ("whirl".equals(signature)) {
            // 2026-10-28 照 PoE2：揮矛那一圈跟這一下之後的旋風一樣大（每多一階 +0.3 格）
            return build.withAreaAndProjectiles(build.areaScale() * slashScale(nextWhirlStage(whirlHere(player))), build.projectiles());
        }
        if (!"lance".equals(signature) && !"bolt_spear".equals(signature)) {
            return build;
        }
        final int benefit = plugin.combat() == null ? 0
                : plugin.combat().charges().spend(player, "frenzy", build.profile().effective(state.sheet()), build.profile().tags()); // 2026-10-24：戰星輔助
        if (benefit <= 0) {
            return build;
        }
        if ("lance".equals(signature)) {
            chargedLance.put(player.getUniqueId(), benefit);
            state.notice("<#ffe14a>" + tw.exilecore.item.ItemRenderer.escape(definition.name()) + " 消耗怒星：插 1.5 秒、每 " + (benefit > 1 ? "0.1" : "0.2")
                    + " 秒放電" + (benefit > 1 ? "（倍星）" : ""), now + 30);
            plugin.players().display(player, state);
            // 2026-10-28 照 PoE2 Storm Lance 的 Frenzy-Charged：消耗怒星的那一支擲出去的傷害照自己的表（25% → 91% → 329%）、
            // 物理全部轉閃電（SkillStages 的 infused）；這次之前照一般那支的表、多轉 30% 閃電。
            // 插在地上的那支（放電、引爆）照一般的表換算，所以記下沒換過的那個
            unstagedLance.put(player.getUniqueId(), build);
            // 2026-10-31：矛改成投射物、落地才插，標記這一支是消耗怒星的（落地時才拿 chargedLance）
            return build.withProfile(SkillStages.profile(build, build.profile(), "infused")
                    .withLocal(List.of(tw.exilecore.stats.Modifier.flat(LANCE_CHARGED_MARK, benefit, definition.name()))));
        }
        final int copies = BOLT_SPEAR_COPIES * benefit; // 倍星：分裂的支數加倍
        state.notice("<#ffe14a>" + tw.exilecore.item.ItemRenderer.escape(definition.name()) + " 消耗怒星：分裂成 " + copies + " 支", now + 30);
        plugin.players().display(player, state);
        return build.withAreaAndProjectiles(build.areaScale(), build.projectiles() + copies - 1);
    }

    /** 你的其他技能打在插著的霆落矛附近：把它提早引爆（2026-10-15，照 PoE2「被其他技能引爆」）。 */
    int detonateNear(final Player player, final SkillDefinition definition, final Location at) {
        if (at == null || "lance".equals(service.config().signature(definition.id()))) {
            return 0;
        }
        final Deque<Lance> mine = lances.get(player.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            return 0;
        }
        int count = 0;
        final Iterator<Lance> it = mine.iterator();
        while (it.hasNext()) {
            final Lance lance = it.next();
            if (lance.at().getWorld() == at.getWorld() && lance.at().distance(at) <= LANCE_DETONATE_RANGE) {
                it.remove();
                detonate(player, lance);
                count++;
            }
        }
        return count; // 2026-10-28：引爆了幾支（雷躍的品質）
    }

    // ---- 放完之後（旋矛擊的旋風、霆落矛插地）／取代施放（星火連珠） ----

    /** 這一招放完了（SkillService 呼叫）：旋矛擊升旋風、霆落矛插在落點。 */
    void afterCast(final Player player, final SkillDefinition definition, final SkillBuild build, final Location at, final long now) {
        final String signature = service.config().signature(definition.id());
        if ("whirl".equals(signature)) {
            whirl(player, build, now);
        }
        // 2026-10-31 照 PoE2：霆落矛是投射物，落地（lanceLanded）才插；這次之前放完馬上插在準心處
    }

    /**
     * 2026-10-31 照 PoE2 Storm Lance：擲出的矛停下來（飛到準心或撞牆）：插在那裡（不另外炸）。
     * 消耗怒星的那一支（帶 {@link #LANCE_CHARGED_MARK}）拿這次的怒星；幻影擲的、沒消耗的照一般的插。
     */
    void lanceLanded(final Player player, final SkillBuild build, final tw.exilecore.stats.SkillProfile profile, final Location at, final long now) {
        if (!"lance".equals(service.config().signature(build.definition().id()))) {
            return;
        }
        final Location ground = at.clone();
        // 2026-10-31 照 PoE2（RePoE StormLancePlayer：擲出的矛只有投射物的那一下，沒有落地範圍傷害）：只插地；
        // 同一天稍早落地還炸 2 公尺（沿用以前 impact 的落地傷害）
        final boolean own = profile != null && SkillZones.localFlat(profile, LANCE_CHARGED_MARK) > 0;
        lodge(player, build, ground, now, own);
    }

    /** 玩家現在站在自己的旋風裡的話是幾階（沒有、不在裡面是 0）。 */
    private int whirlHere(final Player player) {
        final Whirl old = whirls.get(player.getUniqueId());
        return old != null && old.center.getWorld() == player.getWorld() && old.center.distance(player.getLocation()) <= old.radius() + 0.5
                ? old.stage : 0;
    }

    private void whirl(final Player player, final SkillBuild build, final long now) {
        final UUID uuid = player.getUniqueId();
        final Whirl old = whirls.get(uuid);
        // build 的範圍已經照這一下之後的階數放大過（beforeCast），旋風本身的範圍倍率要除回來
        final double area = build.areaScale() / slashScale(nextWhirlStage(whirlHere(player)));
        if (old != null && old.center.getWorld() == player.getWorld() && old.center.distance(player.getLocation()) <= old.radius() + 0.5) {
            old.stage = Math.min(WHIRL_MAX, old.stage + 1);
            old.expires = now + WHIRL_LIFE;
            old.build = build;
            old.area = area;
            old.wind = WindGround.of(plugin.players().sheet(player));
            elementalize(old); // 2026-10-28 照 PoE2：在元素地面上疊階也會變成元素旋風
            if (old.stage > 1) {
                player.playSound(player.getLocation(), Sound.ITEM_ELYTRA_FLYING, SoundCategory.PLAYERS, 0.4f, 0.8f + 0.3f * old.stage);
                final PlayerState state = plugin.players().get(player);
                state.notice(String.format(Locale.ROOT, "<#c7f0ff>旋風 %d／%d 階", old.stage, WHIRL_MAX), now + 20);
                plugin.players().display(player, state);
            }
            return;
        }
        if (old != null) {
            collapse(player, old);
        }
        final Whirl created = new Whirl(player.getLocation(), build, now, area);
        created.wind = WindGround.of(plugin.players().sheet(player)); // 2026-10-28 照 PoE2（貝雷克的戒指、元素之章）
        elementalize(created);
        whirls.put(uuid, created);
    }

    /** 2026-10-28 照 PoE2：旋風捲在元素地面（點燃、冰緩、感電地面）上就變成那種元素的旋風（已經是的不變）。 */
    private void elementalize(final Whirl whirl) {
        if (whirl.element == null) {
            whirl.element = ElementalGround.at(plugin, whirl.center, whirl.radius());
        }
        if (whirl.element == null) {
            whirl.element = whirl.wind.primary(); // 2026-10-28 照 PoE2（貝雷克的戒指）：沒踩到也視為受到裝備寫的那種地面強化
        }
    }

    /**
     * 2026-10-28 照 PoE2 Twister：旋風矛的旋風碰到 owner 的旋風（at 的 reach 格內）就把它用掉——不崩散、直接消失，
     * 回傳它的階數與元素；沒碰到回傳 null。
     */
    ConsumedWhirl consumeWhirl(final UUID owner, final Location at, final double reach) {
        final Whirl whirl = whirls.get(owner);
        if (whirl == null || whirl.center.getWorld() != at.getWorld()
                || whirl.center.distanceSquared(at) > (whirl.radius() + reach) * (whirl.radius() + reach)) {
            return null;
        }
        whirls.remove(owner);
        whirl.center.getWorld().spawnParticle(Particle.CLOUD, whirl.center.clone().add(0, 0.8, 0), 8 + 4 * whirl.stage, whirl.radius() * 0.4, 0.4,
                whirl.radius() * 0.4, 0.05);
        return new ConsumedWhirl(whirl.stage, whirl.element);
    }

    /** 旋風崩散：在中心炸一圈（階數越高越痛、越大），把敵人往外推。 */
    private void collapse(final Player player, final Whirl whirl) {
        // 2026-10-28 照 PoE2：每多一階更多 150%＋品質（這次之前品質不影響崩散）
        final double share = share("whirling_slash_collapse", whirl.build, WHIRL_SHARE)
                * collapseMore(whirl.stage, SkillZones.localFlat(whirl.build.profile(), WHIRL_QUALITY_MORE));
        SkillBuild sub = subBuild(player, "whirling_slash_collapse", whirl.build, share);
        if (sub == null) {
            return;
        }
        // 2026-10-28 照 PoE2：元素旋風獲得 50% 傷害為那種元素的額外傷害；這次之前沒有元素旋風
        // 2026-10-28 照 PoE2（元素之章）：可以同時被好幾種元素地面強化時每一種各獲得
        final List<Modifier> gains = whirl.wind.gains(whirl.element, WHIRL_ELEMENT_GAIN, "元素旋風");
        if (!gains.isEmpty()) {
            sub = sub.withProfile(sub.profile().withLocal(gains));
        }
        final double radius = whirl.radius();
        final SkillDefinition def = sub.definition();
        final double base = def.params() == null || def.params().radius() == null ? WHIRL_RADIUS : def.params().radius().first();
        final SkillBuild sized = sub.withAreaAndProjectiles(radius / Math.max(0.5, base), sub.projectiles());
        service.procCast(Origin.at(player, whirl.center.clone().add(0, 0.2, 0), SkillTargets.facing(player)), sized, null);
        for (final LivingEntity target : service.targets().around(whirl.center, radius, player)) {
            final Vector push = target.getLocation().toVector().subtract(whirl.center.toVector()).setY(0);
            if (push.lengthSquared() > 1e-4) {
                target.setVelocity(target.getVelocity().add(push.normalize()
                        .multiply(WHIRL_KNOCKBACK * (1 + WHIRL_KNOCKBACK_PER_STAGE * (whirl.stage - 1))).setY(0.25)));
            }
        }
        tw.exilecore.skill.Fx.disk(whirl.center.getWorld(), Particle.SWEEP_ATTACK, whirl.center.clone().add(0, 1, 0), 6 * whirl.stage, radius, 0.4, 0);
        whirl.center.getWorld().playSound(whirl.center, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 0.8f, 1.0f);
    }

    /**
     * 2026-10-30：別的地方放的霆落矛（幻影銳眼的幻影）也插在落點、照常放電與炸開；不碰你自己這一支的怒星。
     * 這次之前幻影放的霆落矛只有落地那一下，不會插地。
     */
    void proxyLanded(final Player player, final SkillBuild build, final Location at, final long now) {
        if (at != null && "lance".equals(service.config().signature(build.definition().id()))) {
            lodge(player, build, at, now, false);
        }
    }

    private void lodge(final Player player, final SkillBuild build, final Location at, final long now) {
        lodge(player, build, at, now, true);
    }

    private void lodge(final Player player, final SkillBuild build, final Location at, final long now, final boolean own) {
        final Deque<Lance> mine = lances.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayDeque<>());
        // 2026-10-28 照 PoE2 Storm Lance 的品質：超載的矛上限 +(0—1)（無條件捨去）
        // 2026-10-28 照 PoE2：「上限 3 支插著的矛」吃 +x 上限（過剩）
        while (mine.size() >= LANCE_MAX + OriginalSkills.qualityInt(build.qualityFlat(tw.exilecore.stats.GemQualityKeys.OVERCHARGED_LIMIT))
                + SkillLimits.bonus(plugin.players().sheet(player), build)) {
            // 2026-10-31 照站長：超過上限時最舊的那支引爆（原作是直接消失：Limit：the oldest effect will disappear）
            final Lance oldest = mine.pollFirst();
            if (oldest != null && oldest.at().getWorld() == player.getWorld()) {
                detonate(player, oldest);
            }
        }
        final Integer chargedBenefit = own ? chargedLance.remove(player.getUniqueId()) : null;
        final boolean charged = chargedBenefit != null;
        final SkillBuild unstaged = own ? unstagedLance.remove(player.getUniqueId()) : null;
        final SkillBuild lodged = charged && unstaged != null ? unstaged : build;
        // 2026-10-24：倍星觸發（算兩顆）：放電間隔減半 = 放電次數加倍
        final long zap = charged ? Math.max(1, LANCE_CHARGED_ZAP / Math.max(1, chargedBenefit)) : LANCE_ZAP;
        mine.addLast(new Lance(at.clone(), lodged, now + (charged ? LANCE_CHARGED_LIFE : LANCE_LIFE),
                new long[] {now + (charged ? 4 : 10) + zap}, zap));
    }

    private void detonate(final Player player, final Lance lance) {
        if (lance == null) {
            return;
        }
        final SkillBuild sub = subBuild(player, "storm_lance_burst", lance.build(), share("storm_lance_burst", lance.build(), LANCE_BURST_SHARE));
        if (sub != null) {
            // 2026-10-31 照站長：引爆的 3 格吃範圍加成（半徑 × 範圍倍率）
            // 2026-10-26 照 PoE2：不是一圈爆炸，是往 3 格內的敵人射 16 道閃電（每道隨機挑一個敵人；這次之前是一圈 nova）
            final double burstRange = LANCE_BURST_RANGE * Math.max(sub.areaScale(), lance.build().areaScale());
            final List<LivingEntity> near = new java.util.ArrayList<>(service.targets().around(lance.at(), burstRange, player));
            // 2026-10-31：引爆時畫出實際範圍（吃範圍加成），看得出範圍變大
            final int ringPoints = (int) Math.max(12, Math.min(60, Math.round(burstRange * 8)));
            for (int i = 0; i < ringPoints; i++) {
                final double angle = Math.PI * 2 * i / ringPoints;
                Fx.spawn(lance.at().getWorld(), Particle.ELECTRIC_SPARK,
                        lance.at().clone().add(Math.cos(angle) * burstRange, 0.3, Math.sin(angle) * burstRange), 1, 0.05, 0.05, 0.05, 0.01);
            }
            Fx.disk(lance.at().getWorld(), Particle.ELECTRIC_SPARK, lance.at().clone().add(0, 0.6, 0), (int) Math.min(40, 8 * burstRange), burstRange, 0.4, 0.05);
            final Location from = lance.at().clone().add(0, 1.0, 0);
            for (int i = 0; i < LANCE_BURST_BOLTS && !near.isEmpty(); i++) {
                final LivingEntity target = near.get(plugin.random().nextInt(near.size()));
                if (!target.isValid() || target.isDead()) {
                    near.remove(target);
                    i--;
                    continue;
                }
                final Vector direction = SkillTargets.center(target).toVector().subtract(from.toVector());
                service.procCast(Origin.at(player, from, direction.lengthSquared() < 1e-6 ? SkillTargets.facing(player) : direction.normalize()),
                        sub, target);
            }
        }
        // 2026-10-15（PoE2）：炸開的地方留下帶電地。2026-10-26 照 PoE2：只有消耗怒星的那一支炸開才留（感電地面，不造成傷害）
        final SkillBuild ground = lance.zapEvery() >= LANCE_ZAP ? null : subBuild(player, "storm_lance_ground", lance.build(), LANCE_GROUND_SHARE);
        if (ground != null) {
            service.procCast(Origin.at(player, lance.at().clone().add(0, 0.1, 0), SkillTargets.facing(player)), ground, null);
        }
        lance.at().getWorld().playSound(lance.at(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.6f, 1.4f);
    }

    /** 星火連珠最多幾顆火星（2026-10-26 照 PoE2 逐級：6→10→16 顆）；這次之前 6～10 內插。 */
    private static final int[] EMBER_MAX = {6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 9, 9, 9, 9, 10,
            10, 10, 10, 11, 11, 11, 12, 12, 12, 13, 13, 13, 14, 14, 14, 15, 15, 15, 16, 16};

    static int emberMax(final int level) {
        return EMBER_MAX[Math.max(1, Math.min(EMBER_MAX.length, level)) - 1];
    }

    /** 星火連珠：叫出一顆火星懸在頭上（取代原本直接射出）；所有火星的懸浮時間重算。 */
    void conjureEmber(final Player player, final SkillBuild build, final LivingEntity preferred, final long now) {
        final Embers mine = embers.computeIfAbsent(player.getUniqueId(), uuid -> new Embers());
        if (mine.fired > 0) {
            mine.fired = 0; // 上一輪還在射：新的一輪重新算
        }
        final int max = emberMax(build.level());
        if (mine.queue.size() < max) {
            mine.queue.addLast(build);
        }
        mine.fireAt = now + EMBER_HOVER;
        mine.nextShot = mine.fireAt;
        if (preferred != null) {
            mine.target = preferred.getUniqueId();
        }
        final PlayerState state = plugin.players().get(player);
        state.notice(String.format(Locale.ROOT, "<#ff9a4d>火星 %d／%d", mine.queue.size(), max), now + 20);
        plugin.players().display(player, state);
    }

    // ---- 每 tick ----

    void tick(final Player player, final SkillRuntime runtime, final long now) {
        tickCharge(player, runtime, now);
        final UUID uuid = player.getUniqueId();
        final Whirl whirl = whirls.get(uuid);
        if (whirl != null) {
            tickWhirl(player, whirl, now);
        }
        final Deque<Lance> mine = lances.get(uuid);
        if (mine != null) {
            tickLances(player, mine, now);
            if (mine.isEmpty()) {
                lances.remove(uuid);
            }
        }
        final Embers ember = embers.get(uuid);
        if (ember != null) {
            tickEmbers(player, ember, now);
            if (ember.queue.isEmpty()) {
                embers.remove(uuid);
            }
        }
        twisters.tick(player, now); // 2026-10-28 照 PoE2：旋風矛的旋風
    }

    private void tickWhirl(final Player player, final Whirl whirl, final long now) {
        final boolean away = whirl.center.getWorld() != player.getWorld()
                || whirl.center.distance(player.getLocation()) > whirl.radius() + 0.5;
        if (away || now >= whirl.expires || !service.eligible(player)) {
            whirls.remove(player.getUniqueId());
            if (whirl.center.getWorld() == player.getWorld() && service.eligible(player)) {
                collapse(player, whirl);
            }
            return;
        }
        final double radius = whirl.radius();
        if (now % 2 == 0) {
            final double spin = now * 0.6;
            // 2026-10-28：元素旋風換成那種元素的粒子
            final Particle particle = whirl.element == tw.exilecore.stats.DamageType.FIRE ? Particle.FLAME
                    : whirl.element == tw.exilecore.stats.DamageType.COLD ? Particle.SNOWFLAKE
                    : whirl.element == tw.exilecore.stats.DamageType.LIGHTNING ? Particle.ELECTRIC_SPARK : Particle.CLOUD;
            for (int i = 0; i < 3 + whirl.stage; i++) {
                final double angle = spin + i * Math.PI * 2 / (3 + whirl.stage);
                final Location point = whirl.center.clone().add(Math.cos(angle) * radius, 0.3 + 0.4 * (i % 3), Math.sin(angle) * radius);
                Fx.spawn(player.getWorld(), particle, point, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }
        if (now % 5 == 0 && plugin.combat() != null) {
            // 2026-10-28 照 PoE2：旋風範圍內的敵人移動變慢 30%、被致盲（跟颶風槍的旋風一樣）；
            // 這次之前用原版緩速 I（只慢 15%）、沒有致盲
            for (final LivingEntity target : service.targets().around(whirl.center, radius, player)) {
                if (!(target instanceof Player)) {
                    plugin.combat().debuffs().apply(target.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff("whirlwind_slow", player.getUniqueId(),
                            now + 10, 0, false, null, List.of(), StageLeftovers.WHIRL_SLOW));
                    plugin.combat().debuffs().apply(target.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff("blind", player.getUniqueId(), now + 80, 0,
                            false, null, tw.exilecore.combat.HitEffects.blindModifiers(0), 0));
                }
            }
        }
    }

    private void tickLances(final Player player, final Deque<Lance> mine, final long now) {
        final Iterator<Lance> it = mine.iterator();
        while (it.hasNext()) {
            final Lance lance = it.next();
            if (lance.at().getWorld() != player.getWorld() || !service.eligible(player)) {
                it.remove();
                continue;
            }
            if (now >= lance.until()) {
                it.remove();
                // 2026-10-31 照 PoE2 Storm Lance：只有消耗怒星的那一支時間到自動引爆；一般的時間到就消失。這次之前每一支時間到都炸開
                if (lance.zapEvery() < LANCE_ZAP) {
                    detonate(player, lance);
                }
                continue;
            }
            if (now % 4 == 0) {
                Fx.spawn(player.getWorld(), Particle.ELECTRIC_SPARK, lance.at().clone().add(0, 1.0 + (now % 8) / 8.0, 0), 3, 0.1, 0.5, 0.1, 0.02);
            }
            if (now >= lance.nextZap()[0]) {
                lance.nextZap()[0] = now + lance.zapEvery();
                final List<LivingEntity> near = service.targets().around(lance.at(),
                        (lance.zapEvery() < LANCE_ZAP ? LANCE_CHARGED_ZAP_RANGE : LANCE_ZAP_RANGE) * lance.build().areaScale(), player);
                if (!near.isEmpty()) {
                    final SkillBuild sub = subBuild(player, "storm_lance_bolt", lance.build(), share("storm_lance_bolt", lance.build(), LANCE_ZAP_SHARE));
                    if (sub != null) {
                        final Location from = lance.at().clone().add(0, 1.5, 0);
                        final LivingEntity target = near.get(0);
                        final Vector direction = SkillTargets.center(target).toVector().subtract(from.toVector());
                        service.procCast(Origin.at(player, from, direction.lengthSquared() < 1e-6 ? SkillTargets.facing(player)
                                : direction.normalize()), sub, target);
                    }
                }
            }
        }
    }

    private void tickEmbers(final Player player, final Embers ember, final long now) {
        if (!service.eligible(player)) {
            ember.queue.clear();
            return;
        }
        final Location head = player.getLocation().add(0, 2.6, 0);
        if (now % 2 == 0) {
            final int count = ember.queue.size();
            for (int i = 0; i < count; i++) {
                final double angle = now * 0.15 + i * Math.PI * 2 / Math.max(1, count);
                Fx.spawn(player.getWorld(), Particle.FLAME, head.clone().add(Math.cos(angle) * 0.7, 0, Math.sin(angle) * 0.7), 1, 0, 0, 0, 0);
            }
        }
        if (now < ember.fireAt || now < ember.nextShot || ember.queue.isEmpty()) {
            return;
        }
        ember.nextShot = now + EMBER_GAP;
        final SkillBuild build = ember.queue.pollFirst();
        final int index = ember.fired++;
        // 目標：上一次指著的敵人優先，不然找附近的（每一顆輪著挑不同的）
        LivingEntity target = null;
        if (ember.target != null && plugin.getServer().getEntity(ember.target) instanceof LivingEntity living && living.isValid()
                && !living.isDead() && living.getWorld() == player.getWorld() && living.getLocation().distanceSquared(player.getLocation()) < 30 * 30) {
            target = living;
        }
        final List<LivingEntity> near = service.targets().around(player.getLocation(), 24, player);
        if (!near.isEmpty() && (target == null || index > 0)) {
            target = near.get(index % near.size());
        }
        final Location from = head.clone().add(SkillTargets.facing(player).multiply(0.5));
        final Vector direction = target != null ? SkillTargets.center(target).toVector().subtract(from.toVector())
                : player.getEyeLocation().getDirection();
        // 2026-10-28 照 PoE2 Ember Fusillade 的品質：每一顆之前射過的火星再更多 (0—2)%（加在 5% 上）
        final double perEmber = EMBER_MORE_PER + build.qualityFlat(tw.exilecore.stats.GemQualityKeys.MORE_PER_EMBER) / 100.0;
        final SkillBuild shot = index > 0 ? build.withProfile(build.profile().scaled(1 + perEmber * index, "火星連射")) : build;
        service.procCast(Origin.at(player, from, direction.lengthSquared() < 1e-6 ? SkillTargets.facing(player) : direction.normalize()),
                shot, target);
    }

    /** 2026-10-26：隱藏技能寫了 parent_damage: own 就照它自己的傷害表換算成原技能一下的倍數，沒寫用 fallback（舊的固定倍數）。 */
    private double share(final String subId, final SkillBuild parent, final double fallback) {
        if (service.config().parentDamage(subId) >= 0) {
            return fallback;
        }
        final double own = service.config().parentShare(subId, parent.definition(), parent.level());
        return own > 0 ? own : fallback;
    }

    /** 隱藏技能的數值：用原技能那一下的傷害（含輔助、武器）乘 share，範圍、形狀照隱藏技能。 */
    private SkillBuild subBuild(final Player player, final String subId, final SkillBuild parent, final double share) {
        final SkillDefinition sub = service.config().skill(subId);
        if (sub == null) {
            return null;
        }
        final PlayerState state = plugin.players().get(player);
        final SkillBuild base = SkillBuild.of(service.config(), sub, parent.level(), state.level(), false, List.of(), state.weaponTags(),
                plugin.players().sheet(player));
        return base.withProfile(parent.profile().scaled(share, sub.name()).derive(sub.id(), sub.tags(), Set.of()))
                .withAreaAndProjectiles(base.areaScale() * Math.max(0.5, parent.areaScale()), base.projectiles());
    }

    /** 登出、換角色：清掉這個人的旋風、插著的矛、火星。 */
    void forget(final UUID uuid) {
        whirls.remove(uuid);
        twisters.forget(uuid);
        lances.remove(uuid);
        embers.remove(uuid);
        chargedLance.remove(uuid);
    }

    /** 測試用：現在的旋風階數（沒有 0）。 */
    int whirlStage(final UUID uuid) {
        final Whirl whirl = whirls.get(uuid);
        return whirl == null ? 0 : whirl.stage;
    }
}
