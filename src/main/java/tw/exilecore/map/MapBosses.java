package tw.exilecore.map;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.AffixMod;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.Modifier;
import tw.exilecore.zone.Zone;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 地圖 Boss：玩家走進 Boss 房就出現（MythicMobs 有這隻就用它，不然用原版生物），頭上有血條；
 * 血量掉到門檻時換階段（喊話、召喚小怪、加屬性、變快；一次一個，見 {@link PhaseStep}）。附近沒人 30 秒就回滿血、回到第一階段；
 * 跑出 Boss 房太遠會被拉回來。死亡時：地圖完成（星圖）、必掉地圖（跟這張圖同一階）、機率掉傳奇、星圖最外圈的地圖掉天穹碎片、首殺獎勵；
 * 終局開到夠高的階有機率掉開極境的鑰匙，極境有自己的掉落（{@link MapConfig.UberDrops}）。
 * <p>
 * 特殊機制：階段的護盾（Boss 無敵、限時打掉結晶，不然回血）、狂暴（開打太久傷害變高）；場地危害由招式引擎照 {@code hazards} 落下。
 */
final class MapBosses {

    /** 附近沒人多久就重置（tick）。 */
    private static final long RESET_TICKS = 30 * 20;
    /** 離 Boss 房中心超過這麼遠、而且身邊沒有人在打它，就拉回來（有人在打就一路追，站遠射箭也會被追上）。 */
    private static final double LEASH = 30;
    /** Boss 身邊這麼近有玩家就算「有人在打」。 */
    private static final double ENGAGED = 24;
    /** Boss 的追蹤範圍（原版生物大多只有 16–35 格，站遠一點就不理人）。 */
    private static final double FOLLOW_RANGE = 64;

    private final ExileCore plugin;
    private final MapService service;
    private final NamespacedKey speedKey;
    private final NamespacedKey enrageKey;
    /** 護盾結晶離 Boss 房中心幾格。 */
    private static final double CRYSTAL_RING = 7;

    MapBosses(final ExileCore plugin, final MapService service) {
        this.plugin = plugin;
        this.service = service;
        this.speedKey = new NamespacedKey(plugin, "map_boss_phase");
        this.enrageKey = new NamespacedKey(plugin, "map_boss_enrage");
    }

    /** 每 10 tick。 */
    void tick() {
        final long now = plugin.getServer().getCurrentTick();
        for (final MapInstance instance : service.instances()) {
            if (instance.state() != MapInstance.State.OPEN || instance.bossKilled()) {
                continue;
            }
            final Location center = center(instance);
            final LivingEntity boss = instance.boss();
            if (boss == null || !boss.isValid()) {
                if (boss != null) {
                    // 區塊卸載或被移除了（Paper 把移除的實體也當成死亡；真的被打死時 bossKilled 已經是 true）：下次有人走近再找回來或重生
                    instance.setBoss(null);
                    detachSkills(boss);
                }
                if (instance.boss() == null && !nearbyPlayers(instance, center, service.config().settings().bossRadius()).isEmpty()) {
                    // 區塊重新載入時舊的 Boss 會跟著回來：先找找看，找不到才生新的
                    final LivingEntity existing = findExisting(instance, center);
                    if (existing != null) {
                        final boolean stale = now - instance.bossLastSeenTick() > RESET_TICKS;
                        instance.setBoss(existing);
                        attachSkills(instance, existing);
                        if (stale) {
                            // 區塊卸載前大家都走了（全倒回營地）：跟附近沒人一樣重置（回滿血、第一階段、狂暴的計時重來、護盾收掉）
                            reset(instance, existing, center);
                        }
                        instance.setBossLastSeenTick(now);
                    } else {
                        spawn(instance, center);
                    }
                }
                continue;
            }
            // 「附近」以 Boss 現在的位置算：追著人跑出 Boss 房也不算沒人
            final List<Player> near = nearbyPlayers(instance, boss.getLocation(), 48);
            if (instance.lateEntryRule()) {
                trackRoster(instance, boss);
            }
            if (!near.isEmpty()) {
                instance.setBossLastSeenTick(now);
                // 2026-10：有人走進 Boss 房（或從門口打到它）才開打；狂暴也從這一刻開始算
                if (instance.fightStartTick() < 0 && (anyInRoom(instance, near) || hurt(boss))) {
                    instance.setFightStartTick(now);
                    service.events().twists().onBossFight(instance, boss); // 地圖變化：雙生、守護之柱、援軍
                }
            } else if (now - instance.bossLastSeenTick() > RESET_TICKS) {
                reset(instance, boss, center);
                continue;
            }
            if (instance.fightStartTick() < 0) {
                // 還沒開打：待在 Boss 房裡、不盯人（隔壁房間、走廊上的人不會把它引出來；原版 AI 找的目標由 MapListener 擋掉）
                if (boss instanceof Mob mob && mob.getTarget() != null) {
                    mob.setTarget(null);
                }
                if (!inRoom(instance, boss.getLocation())) {
                    boss.teleport(center);
                }
                plugin.monsters().ensureVanillaHealth(boss);
                updateBar(instance, boss, near);
                continue;
            }
            updateShield(instance, boss, now);
            checkEnrage(instance, boss, now);
            // 跑太遠又沒人在打才拉回 Boss 房；盯著最近的玩家（北極熊、伏守者這種不會主動打人的也會打）
            if (boss.getLocation().distanceSquared(center) > LEASH * LEASH && nearbyPlayers(instance, boss.getLocation(), ENGAGED).isEmpty()) {
                boss.teleport(center);
            }
            if (boss instanceof Mob mob && !near.isEmpty() && !(mob.getTarget() instanceof Player)) {
                final Player target = nearest(boss.getLocation(), near);
                // 16 格內一定盯；更遠的要看得到（隔壁房間探險的人不算，站遠射箭的算）
                if (target.getLocation().distanceSquared(boss.getLocation()) <= 16 * 16 || boss.hasLineOfSight(target)) {
                    mob.setTarget(target);
                    if (boss instanceof Warden warden) {
                        warden.setAnger(target, 150);
                    }
                }
            }
            plugin.monsters().ensureVanillaHealth(boss); // 別的插件改了原版血量上限就改回來
            updateBar(instance, boss, near);
            checkPhases(instance, boss);
        }
    }

    /**
     * 終局以上（2026-10-15）：Boss 生命還在 75% 以上時在圖裡的人記進名單；掉到 75% 以下才進來的人提醒一次「這場沒有掉落」。
     */
    private void trackRoster(final MapInstance instance, final LivingEntity boss) {
        final AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        final double fraction = max == null ? 1 : boss.getHealth() / Math.max(1, max.getValue());
        final List<UUID> present = new ArrayList<>();
        for (final UUID uuid : instance.inside()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR && instance.contains(player.getLocation())) {
                present.add(uuid);
            }
        }
        for (final UUID late : instance.noteRoster(present, fraction)) {
            final Player player = plugin.getServer().getPlayer(late);
            if (player != null) {
                player.sendMessage(plugin.text().parse("<#ff9f43>⚠ Boss 的生命已經低於 " + Math.round(MapInstance.LATE_ENTRY_LIFE * 100)
                        + "%，你來晚了：這場打死時沒有你的掉落"));
            }
        }
    }

    /** 還沒開打（沒人進過 Boss 房、也沒被打到）：不盯人、不出招、不離開 Boss 房。 */
    boolean dormant(final LivingEntity entity) {
        final MapInstance instance = service.instanceOf(entity);
        return instance != null && instance.boss() == entity && !instance.bossKilled() && instance.fightStartTick() < 0;
    }

    private static boolean inRoom(final MapInstance instance, final Location at) {
        return instance.layout().boss().contains(at.getBlockX() - instance.originX(), at.getBlockZ() - instance.originZ());
    }

    private static boolean anyInRoom(final MapInstance instance, final List<Player> players) {
        for (final Player player : players) {
            if (inRoom(instance, player.getLocation())) {
                return true;
            }
        }
        return false;
    }

    /** 被打掉一點血（站在門口射它）也算開打。 */
    private static boolean hurt(final LivingEntity boss) {
        final AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        return max != null && boss.getHealth() < max.getValue() - 0.01;
    }

    private LivingEntity findExisting(final MapInstance instance, final Location center) {
        for (final LivingEntity entity : center.getNearbyLivingEntities(LEASH + 64)) {
            if (entity.isValid() && !entity.isDead() && entity.getPersistentDataContainer().has(service.bossKey())
                    && service.instanceOf(entity) == instance) {
                return entity;
            }
        }
        return null;
    }

    private static Location center(final MapInstance instance) {
        final MapLayout.Room room = instance.layout().boss();
        return new Location(instance.world(), instance.originX() + room.cx() + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + room.cz() + 0.5);
    }

    private List<Player> nearbyPlayers(final MapInstance instance, final Location center, final double radius) {
        final List<Player> list = new ArrayList<>();
        for (final UUID uuid : instance.inside()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            // inside 每秒才更新：剛死掉、回營地的人可能還在名單裡（不同世界不能量距離）
            if (player != null && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR && !plugin.players().grace().isSafe(player)
                    && instance.contains(player.getLocation()) && player.getLocation().distanceSquared(center) <= radius * radius) {
                list.add(player);
            }
        }
        return list;
    }

    private static Player nearest(final Location from, final List<Player> players) {
        Player best = players.getFirst();
        double bestDistance = Double.MAX_VALUE;
        for (final Player player : players) {
            final double distance = player.getLocation().distanceSquared(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private void spawn(final MapInstance instance, final Location center) {
        final MapConfig.Boss definition = instance.bossDef();
        LivingEntity entity = null;
        String typeName = definition.type();
        if (definition.mythic() != null && plugin.mythic().exists(definition.mythic())) {
            entity = plugin.mythic().spawn(definition.mythic(), center, instance.level());
            if (entity != null) {
                service.tag(entity, instance, true);
                typeName = entity.getType().name();
            }
        }
        if (entity == null) {
            entity = service.spawner().spawnVanilla(definition.type(), center, created -> service.tag(created, instance, true));
        }
        if (entity == null) {
            plugin.getSLF4JLogger().warn("地圖 {} 的 Boss 生不出來（type: {}）", instance.map().id(), definition.type());
            instance.setBossKilled(true); // 生不出來也不要卡住：直接算完成
            if (instance.special() == null && !instance.bossOnly()) {
                service.complete(instance);
            } else {
                // 回響、試煉、極境、巔峰：生不出來不算打倒（不給每週的獎勵、不給昇華點、不算極境首殺；極境的鑰匙、巔峰的碎片還給開的人）
                broadcast(instance, "<red>" + ItemRenderer.escape(instance.label()) + " 的 Boss 沒有出現（設定有問題，請通知管理員），這次不算");
                if (instance.isUber()) {
                    service.refundUberKey(instance);
                } else if (instance.isPinnacle()) {
                    service.refundPinnacleFragments(instance);
                } else {
                    instance.refundPaid(); // 2026-10-18：劫盜終局等其他特殊實例：開門花的通貨也退（以前 bossKilled 已設，重開也不會補退）
                }
            }
            return;
        }
        // 新的 Boss：上一隻的戰鬥狀態（狂暴的計時、還在的護盾結晶）不帶過來
        clearShield(instance, null);
        instance.setFightStartTick(-1);
        instance.clearRoster();
        instance.setEnraged(false);
        instance.setPhaseTick(PhaseStep.NEVER);
        instance.setPhaseHold(false);
        // 生命、傷害倍率扣掉 monsters.yml 裡這種生物本身的倍率（設定檔寫的是「比一般傳奇怪強幾倍」）
        final MonsterConfig.TypeSettings type = plugin.monsters().config().type(typeName);
        final double tierLife = tierLife(instance);
        // 地圖變化（2026-10）：王道、詛咒寶匣的 Boss 加成，Boss 變體的倍率
        final MapInstance.Twist twist = instance.twist();
        final MapTwists.BossVariant variant = twist.bossVariant;
        final double power = 1 + twist.bossPower;
        // 2026-10-27 照站長：一般地圖 Boss 也乘 settings.tier_parity（強度等於原作同一階）；終局、極境、巔峰、特殊實例不乘（終局維持 T16 每秒 25 萬、3 分半）
        final boolean parity = !instance.map().isFinal() && instance.special() == null && !instance.isUber() && !instance.isPinnacle();
        final int parityTier = instance.stats().tier();
        final double life = definition.life() * tierLife / Math.max(0.1, type.lifeMultiplier()) * power * (variant == null ? 1 : variant.life)
                * (parity ? service.config().parityLife(parityTier) : 1.0);
        final double damage = definition.damage() / Math.max(0.1, type.damageMultiplier()) * power * (variant == null ? 1 : variant.damage)
                * (parity ? service.config().parityDamage(parityTier) : 1.0);
        instance.setBossPhase(0);
        final String bossName = variant == MapTwists.BossVariant.EMPOWERED ? "強化的" + definition.name() : definition.name();
        plugin.monsters().decorateBoss(entity, instance.level(), bossName, definition.mods(), life, damage, definition.scale());
        MapSpawner.prepare(entity);
        final AttributeInstance follow = entity.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < FOLLOW_RANGE) {
            follow.setBaseValue(FOLLOW_RANGE);
        }
        instance.setBoss(entity);
        attachSkills(instance, entity);
        if (instance.map().isFinal()) {
            // 終局（含極境）：開打一分鐘的傷害寫進伺服器紀錄（查「一開場血掉一大截」，2026-10）
            service.bossLog().track(entity, "地圖 #" + instance.id() + " " + instance.label(), definition.name(), instance.level());
        }
        instance.setBossLastSeenTick(plugin.getServer().getCurrentTick());
        hideBar(instance);
        final BossBar bar = BossBar.bossBar(title(instance, 0), 1f, definition.named() ? BossBar.Color.PURPLE : BossBar.Color.RED,
                BossBar.Overlay.NOTCHED_12); // 2026-10：資源包把 12 格的刻度換成 75%、50%、25% 三條（換階段的位置）
        instance.setBossBar(bar);
        if (definition.intro() != null && !definition.intro().isBlank()) {
            talk(instance, Zone.loreLine(definition.name(), definition.intro()));
        }
        if (variant == MapTwists.BossVariant.EMPOWERED) {
            broadcast(instance, "<#ff7070>⚠ 這次的 Boss 是強化的：生命 ×1.5、傷害 ×1.25，掉落 +" + Math.round(variant.loot * 100) + "%");
        } else if (variant == MapTwists.BossVariant.TWIN) {
            broadcast(instance, "<#ff7070>⚠ 這次的 Boss 是雙生的：開打時會多一隻分身（兩隻都比較脆），掉落 +" + Math.round(variant.loot * 100) + "%");
        }
        if (alerts(instance)) {
            // 終局與試煉 Boss：開打前先在聊天室說明機制（幾 % 血換階段、護盾、狂暴、場地危害）
            final String overview = BossMechanicText.overview(definition, skillNames(definition.hazards().stream()
                    .map(tw.exilecore.skill.BossHazard::skill).toList()));
            if (overview != null) {
                broadcast(instance, overview);
            }
        }
        center.getWorld().playSound(center, definition.named() ? Sound.ENTITY_WITHER_SPAWN : Sound.ENTITY_EVOKER_PREPARE_WOLOLO, 0.8f, 0.8f);
        center.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, center, 60, 1.5, 1.5, 1.5, 0.05);
    }

    private net.kyori.adventure.text.Component title(final MapInstance instance, final int phase) {
        final MapConfig.Boss definition = instance.bossDef();
        String text = "<#af6025>" + ItemRenderer.escape(definition.name()) + " <gray>Lv." + instance.level();
        if (phase > 0 && phase <= definition.phases().size()) {
            text += " <dark_gray>・<red>" + ItemRenderer.escape(definition.phases().get(phase - 1).name());
        }
        if (instance.enraged()) {
            text += " <dark_gray>・<dark_red>狂暴";
        } else if (definition.enrage() != null && instance.fightStartTick() >= 0) {
            final long left = Math.max(0, Math.round(definition.enrage().after()) - (plugin.getServer().getCurrentTick() - instance.fightStartTick()) / 20);
            text += String.format(java.util.Locale.ROOT, " <dark_gray>（狂暴 %d:%02d）", left / 60, left % 60);
        }
        return plugin.text().parse(text);
    }

    private void updateBar(final MapInstance instance, final LivingEntity boss, final List<Player> near) {
        final BossBar bar = instance.bossBar();
        if (bar == null) {
            return;
        }
        final AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        final double fraction = max == null ? 1 : boss.getHealth() / Math.max(1, max.getValue());
        bar.progress((float) Math.max(0, Math.min(1, fraction)));
        if (instance.bossDef().enrage() != null && !instance.enraged() && instance.fightStartTick() >= 0) {
            bar.name(title(instance, instance.bossPhase())); // 狂暴倒數
        }
        // 2026-10：血條常駐——在 Boss 附近（64 格）或 Boss 房裡就一直顯示，不管剛復活的保護時間、死掉觀戰、離開戰鬥範圍
        final Location center = center(instance);
        for (final UUID uuid : instance.inside()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) {
                continue;
            }
            final Location at = player.getLocation();
            final boolean show = near.contains(player) || at.getWorld() == boss.getWorld()
                    && (at.distanceSquared(boss.getLocation()) <= BAR_RADIUS * BAR_RADIUS || at.distanceSquared(center) <= BAR_RADIUS * BAR_RADIUS);
            if (show) {
                player.showBossBar(bar);
            } else {
                player.hideBossBar(bar);
            }
        }
    }

    /** 離 Boss（或 Boss 房中心）多近就一直看得到血條。 */
    private static final double BAR_RADIUS = 64;

    /**
     * 血量到了門檻就換下一個階段——一次一個（{@link PhaseStep}）：一下打掉一大段血、跨過好幾個門檻時，
     * 下一個階段隔 3 秒（有護盾的話等護盾破了）才換，召喚、護盾、加傷害不會在同一個瞬間全部疊上來；
     * 等的這段時間 Boss 打不動（不會一口氣打過去，把下一個階段的護盾跳過）。
     */
    private void checkPhases(final MapInstance instance, final LivingEntity boss) {
        final List<MapConfig.Phase> phases = instance.bossDef().phases();
        final AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        if (max == null || instance.bossPhase() >= phases.size()) {
            holdPhase(instance, boss, false);
            return;
        }
        final double fraction = boss.getHealth() / Math.max(1, max.getValue());
        final long now = plugin.getServer().getCurrentTick();
        final MapConfig.Phase phase = phases.get(instance.bossPhase());
        final PhaseStep.Step step = PhaseStep.step(phase.at(), fraction, now, instance.phaseTick(), instance.shielded());
        holdPhase(instance, boss, step == PhaseStep.Step.WAIT);
        if (step != PhaseStep.Step.ENTER) {
            return;
        }
        instance.setBossPhase(instance.bossPhase() + 1);
        instance.setPhaseTick(now);
        service.bossLog().note(boss, "進入第 " + instance.bossPhase() + " 階段：" + phase.name());
        enterPhase(instance, boss, phase);
        if (!instance.shielded() && instance.bossPhase() < phases.size() && fraction <= phases.get(instance.bossPhase()).at()) {
            holdPhase(instance, boss, true); // 血已經到下一個階段：馬上開始等（不用等到下一次檢查，中間 0.5 秒也打不動）
        }
        // 新階段的屬性：屬性表重算、原版速度同步（血量比例不變）
        plugin.monsters().refreshVanilla(boss);
        final AttributeInstance speed = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            double bonus = 0;
            for (int i = 0; i < instance.bossPhase(); i++) {
                bonus += phases.get(i).speed();
            }
            speed.removeModifier(speedKey);
            if (bonus != 0) {
                speed.addModifier(new AttributeModifier(speedKey, bonus, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
        if (instance.bossBar() != null) {
            instance.bossBar().name(title(instance, instance.bossPhase()));
            instance.bossBar().color(BossBar.Color.RED);
        }
    }

    /**
     * 換階段鎖血（2026-10）：還沒進下一個階段時，Boss 的原版血量最低只能被打到下一個門檻（不會一下打穿好幾個階段、直接打死）。
     * 回傳原版血量的下限；不是這個實例的 Boss、沒有下一個階段回傳 0。
     */
    double phaseFloor(final MapInstance instance, final LivingEntity entity) {
        // 照 UUID 比（區塊重載後是新的實體物件，instance.boss() 可能還沒換過來）
        if (instance == null || instance.boss() == null || !entity.getUniqueId().equals(instance.boss().getUniqueId())) {
            return 0;
        }
        final List<MapConfig.Phase> phases = instance.bossDef().phases();
        if (instance.bossPhase() >= phases.size()) {
            return 0;
        }
        final AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        // 比門檻再低一點點（浮點誤差：剛好卡在門檻上時「血量 ≤ 門檻」要成立）
        if (max == null) {
            return 0;
        }
        final double floor = max.getValue() * phases.get(instance.bossPhase()).at() - 0.05;
        return floor <= 0.5 ? 0 : floor; // 門檻太低（0 或接近 0）：不鎖，不然會打不死
    }

    /** 被鎖在門檻上了：馬上檢查換階段（不用等下一次檢查）。 */
    void checkNow(final MapInstance instance, final LivingEntity boss) {
        if (instance != null && instance.boss() != null && boss.getUniqueId().equals(instance.boss().getUniqueId())
                && boss.isValid() && !boss.isDead()) {
            checkPhases(instance, boss);
        }
    }

    /**
     * 等下一個階段的這段時間 Boss 打不動；等完（或不用等了）恢復——護盾還在的話照護盾。
     * 每次檢查都對一次 Boss 身上的無敵（存在實體上：區塊卸載前留下來的、重新找回來的也會恢復正確）。
     */
    private void holdPhase(final MapInstance instance, final LivingEntity boss, final boolean hold) {
        if (hold && !instance.phaseHold()) {
            broadcastActionBar(instance, "<gray>" + ItemRenderer.escape(instance.bossDef().name()) + " 正在換階段，暫時打不動");
        }
        instance.setPhaseHold(hold);
        final boolean invulnerable = hold || instance.shielded();
        if (boss.isValid() && boss.isInvulnerable() != invulnerable) {
            boss.setInvulnerable(invulnerable);
        }
    }

    private void enterPhase(final MapInstance instance, final LivingEntity boss, final MapConfig.Phase phase) {
        final Location at = boss.getLocation();
        at.getWorld().playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.2f);
        at.getWorld().spawnParticle(Particle.EXPLOSION, at, 3, 1, 1, 1, 0);
        if (phase.message() != null && !phase.message().isBlank()) {
            talk(instance, Zone.loreLine(instance.bossDef().name(), phase.message()));
        }
        // 畫面中間大字「第 N 階段・名稱」＋最要緊的一件事（護盾優先），聊天室列出這個階段的全部變化
        final int number = instance.bossPhase();
        final List<String> notes = BossMechanicText.phaseNotes(phase, newSkills(boss, instance.bossDef(), number));
        broadcastAlert(instance, new String[] {BossMechanicText.phaseTitle(number, phase), BossMechanicText.phaseSubtitle(notes)});
        broadcast(instance, BossMechanicText.phaseChat(number, phase, notes));
        if (phase.shield() != null && !instance.shielded()) {
            startShield(instance, boss, phase.shield());
        }
        if (phase.summonType() != null && phase.summonCount() > 0) {
            for (int i = 0; i < phase.summonCount(); i++) {
                final double angle = Math.PI * 2 * i / phase.summonCount();
                final Location spot = at.clone().add(Math.cos(angle) * 3, 0.2, Math.sin(angle) * 3);
                if (!instance.layout().isFloor(spot.getBlockX() - instance.originX(), spot.getBlockZ() - instance.originZ())) {
                    spot.setX(at.getX());
                    spot.setZ(at.getZ());
                }
                final LivingEntity minion = service.spawner().spawnVanilla(phase.summonType(), spot, created -> {
                    service.tag(created, instance, false);
                    plugin.monsters().markSummoned(created);
                });
                if (minion != null) {
                    plugin.monsters().decorate(minion, instance.level(), MonsterRarity.NORMAL, List.of());
                    MapSpawner.prepare(minion);
                    at.getWorld().spawnParticle(Particle.SOUL, spot, 10, 0.3, 0.6, 0.3, 0.02);
                }
            }
        }
    }

    /** 目前階段的屬性（給屬性表）；狂暴了再加更多傷害。 */
    List<Modifier> phaseModifiers(final MapInstance instance) {
        final List<MapConfig.Phase> phases = instance.bossDef().phases();
        final List<Modifier> list = new ArrayList<>();
        for (int i = 0; i < Math.min(instance.bossPhase(), phases.size()); i++) {
            for (final AffixMod stat : phases.get(i).stats()) {
                list.add(stat.toModifier(Map.of(), "Boss 階段 " + phases.get(i).name()));
            }
        }
        final MapConfig.Enrage enrage = instance.bossDef().enrage();
        if (instance.enraged() && enrage != null && enrage.damage() > 0) {
            list.add(new Modifier(tw.exilecore.stats.StatKeys.DAMAGE, tw.exilecore.stats.ModType.MORE, enrage.damage() / 100.0, Set.of(), "狂暴"));
        }
        return list;
    }

    // ---- 護盾 ----

    /**
     * Boss 的生命照開的階級再乘幾倍：
     * 終局（含極境）照 maps.yml 的 final.tier_life（T5 以下 ×1，往上每階等量增加）；
     * 劫盜終局（金庫之主）難度照極境，一樣照開的人打過的最高階再乘；
     * 一般地圖 Boss（不是終局、不是劫盜這種特殊實例）照 settings.boss_tier_life；其他特殊實例（回響、試煉、據點）不乘。
     * 護盾結晶照這個的一部分乘（2026-10，{@link MapConfig#shieldLifeMultiplier}）。
     */
    private double tierLife(final MapInstance instance) {
        final boolean heistFinale = instance.special() != null && "heist_finale".equals(instance.special().kind());
        if (instance.map().isFinal() && instance.special() == null && !instance.isUber() && !instance.isPinnacle()) {
            return service.config().finalBossLifeMultiplier(instance.stats().tier()); // 2026-10-21：一般終局照 tier_life_table
        }
        // 2026-10-27 照站長：極境、巔峰、金庫之主再乘 maps.yml 的 boss_life（極境 50 萬、巔峰 100 萬）；這次之前只照 tier_life
        if (instance.map().isFinal() && instance.special() == null && instance.isPinnacle()) {
            return service.config().pinnacleLifeMultiplier(instance.map().id());
        }
        if (instance.map().isFinal() && instance.special() == null && instance.isUber()) {
            return service.config().uberLifeMultiplier();
        }
        if (heistFinale) {
            return service.config().heistFinaleLifeMultiplier(instance.stats().tier());
        }
        return instance.map().isFinal() && instance.special() == null
                ? service.config().finalLifeMultiplier(instance.stats().tier())
                : instance.special() == null && !instance.isUber() ? service.config().bossLifeMultiplier(instance.stats().tier()) : 1.0;
    }

    /** 進入有護盾的階段：Boss 無敵，Boss 房裡出現幾顆結晶（不會動、不會打人），限時打掉。 */
    /** 地圖變化的守護之柱：開打時張一次護盾（已經有護盾就不疊）。 */
    void raiseShield(final MapInstance instance, final LivingEntity boss, final MapConfig.Shield shield) {
        if (!instance.shielded() && boss.isValid() && !instance.bossKilled()) {
            startShield(instance, boss, shield);
        }
    }

    /** 2026-10-18：護盾結晶照人數乘，最多算幾個人。 */
    static final int MAX_SHIELD_PLAYERS = 6;

    private void startShield(final MapInstance instance, final LivingEntity boss, final MapConfig.Shield shield) {
        final long now = plugin.getServer().getCurrentTick();
        instance.startShield(shield, now + Math.round(shield.duration() * 20));
        boss.setInvulnerable(true);
        Location center = center(instance);
        double ring = CRYSTAL_RING;
        // 2026-10：場地正在收縮（巔峰的萬界崩解）：結晶放在收縮圈裡面（以圈的中心為準、離圈邊至少 1.5 格），不然打不到
        final double[] arena = plugin.skills() == null ? null : plugin.skills().bossSkills().arenaBounds(boss.getUniqueId());
        if (arena != null) {
            center = new Location(boss.getWorld(), arena[0], center.getY(), arena[1]);
            ring = Math.max(1.5, Math.min(CRYSTAL_RING, arena[2] - 1.5));
        }
        final double offset = service.random().nextDouble() * Math.PI * 2;
        // 結晶只吃一部分的階級倍率（maps.yml 的 settings.shield_tier_share，預設一半）
        final double tierLife = service.config().shieldLifeMultiplier(tierLife(instance));
        // 2026-10-18：結晶數量照地圖裡的人數乘（maps.yml 寫的 count 是一個人的份：一人 3 根、兩人 6 根…，最多算 6 人）
        final int players = Math.min(MAX_SHIELD_PLAYERS, service.playersIn(boss).orElse(1));
        final int count = Math.max(1, shield.count() * players);
        for (int i = 0; i < count; i++) {
            final double angle = offset + Math.PI * 2 * i / count;
            final Location spot = center.clone().add(Math.cos(angle) * ring, 0.3, Math.sin(angle) * ring);
            if (!instance.layout().isFloor(spot.getBlockX() - instance.originX(), spot.getBlockZ() - instance.originZ())) {
                spot.setX(center.getX() + Math.cos(angle) * Math.min(3, ring));
                spot.setZ(center.getZ() + Math.sin(angle) * Math.min(3, ring));
            }
            final LivingEntity crystal = service.spawner().spawnVanilla(shield.type(), spot, created -> {
                service.tag(created, instance, false);
                plugin.monsters().markSummoned(created);
            });
            if (crystal == null) {
                continue;
            }
            // 2026-10：結晶的生命也照階級再乘（Boss 倍率多出來的部分吃 shield_tier_share 成）。以前 Boss 在 T16 乘了好幾倍、
            // 結晶沒有，伺服器紀錄裡護盾 3～4 秒就破，限時與回血等於沒有作用；全吃又太硬（輸出不夠的人一定打不破）
            plugin.monsters().decorateData(crystal, new tw.exilecore.monster.MonsterData(instance.level(), MonsterRarity.MAGIC, List.of(),
                    shield.name(), shield.life() * tierLife, 0.1, false));
            if (crystal instanceof Mob mob) {
                mob.setAI(false);
            }
            crystal.setGravity(false);
            crystal.setGlowing(true);
            crystal.setSilent(true);
            crystal.setPersistent(true);
            crystal.setRemoveWhenFarAway(false);
            final AttributeInstance knockback = crystal.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (knockback != null) {
                knockback.setBaseValue(1.0);
            }
            instance.shieldCrystals().add(crystal.getUniqueId());
            spot.getWorld().spawnParticle(Particle.END_ROD, spot.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.05);
        }
        if (instance.shieldCrystals().isEmpty()) {
            // 結晶一顆都生不出來（type 寫錯）：不要讓 Boss 永遠無敵
            plugin.getSLF4JLogger().warn("Boss {} 的護盾結晶生不出來（type: {}），護盾取消", instance.bossDef().name(), shield.type());
            instance.endShield();
            boss.setInvulnerable(false);
            return;
        }
        broadcast(instance, "<#ffd27f>⛨ " + ItemRenderer.escape(instance.bossDef().name()) + " 張開了護盾：" + (int) shield.duration()
                + " 秒內打掉 " + instance.shieldCrystals().size() + " 顆「" + ItemRenderer.escape(shield.name()) + "」，不然牠會回復 "
                + Math.round(shield.heal()) + "% 生命");
        boss.getWorld().playSound(boss.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.6f);
    }

    /** 每 10 tick：結晶都打掉了護盾就破；時間到 Boss 回血、剩下的結晶消失；中間提醒還剩幾顆幾秒。 */
    private void updateShield(final MapInstance instance, final LivingEntity boss, final long now) {
        if (!instance.shielded()) {
            return;
        }
        instance.shieldCrystals().removeIf(uuid -> {
            final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(uuid);
            return entity == null || !entity.isValid() || entity.isDead();
        });
        final MapConfig.Shield shield = instance.shield();
        if (instance.shieldCrystals().isEmpty()) {
            endShield(instance, boss);
            service.bossLog().note(boss, "護盾破了");
            broadcast(instance, "<green>⛨ 護盾破了！" + ItemRenderer.escape(instance.bossDef().name()) + " 又打得動了");
            broadcastAlert(instance, BossMechanicText.shieldBroken());
            boss.getWorld().playSound(boss.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.2f, 0.6f);
            return;
        }
        if (now >= instance.shieldUntilTick()) {
            final AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
            if (max != null && shield.heal() > 0) {
                boss.setHealth(Math.min(max.getValue(), boss.getHealth() + max.getValue() * shield.heal() / 100.0));
            }
            for (final UUID uuid : instance.shieldCrystals()) {
                final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(uuid);
                if (entity != null) {
                    entity.getWorld().spawnParticle(Particle.HEART, entity.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0);
                    entity.remove();
                }
            }
            endShield(instance, boss);
            broadcast(instance, "<red>⛨ 來不及了：" + ItemRenderer.escape(instance.bossDef().name()) + " 吸收了結晶，回復 " + Math.round(shield.heal())
                    + "% 生命");
            broadcastAlert(instance, BossMechanicText.shieldFailed(shield));
            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.0f, 0.6f);
            return;
        }
        if ((now / 10) % 4 == 0) {
            final long seconds = Math.max(0, (instance.shieldUntilTick() - now) / 20);
            for (final UUID uuid : instance.inside()) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    final tw.exilecore.character.PlayerState state = plugin.players().get(player);
                    state.notice("<#ffd27f>⛨ Boss 無敵中：打掉 " + instance.shieldCrystals().size() + " 顆「" + ItemRenderer.escape(shield.name())
                            + "」（剩 " + seconds + " 秒）", now + 45);
                    plugin.players().display(player, state);
                }
            }
        }
    }

    private void endShield(final MapInstance instance, final LivingEntity boss) {
        instance.endShield();
        if (boss != null && boss.isValid()) {
            boss.setInvulnerable(false);
        }
    }

    /** 收掉還在的結晶（Boss 重置、死掉、實例關掉）。 */
    private void clearShield(final MapInstance instance, final LivingEntity boss) {
        for (final UUID uuid : instance.shieldCrystals()) {
            final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(uuid);
            if (entity != null) {
                entity.remove();
            }
        }
        endShield(instance, boss);
    }

    // ---- 狂暴 ----

    /** 開打超過狂暴時間還沒打倒：Boss 更多傷害、變快（一場一次）。 */
    private void checkEnrage(final MapInstance instance, final LivingEntity boss, final long now) {
        final MapConfig.Enrage enrage = instance.bossDef().enrage();
        if (enrage == null || instance.enraged() || instance.fightStartTick() < 0
                || now - instance.fightStartTick() < Math.round(enrage.after() * 20)) {
            return;
        }
        instance.setEnraged(true);
        plugin.monsters().refreshVanilla(boss); // 屬性表重算（更多傷害）
        final AttributeInstance speed = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null && enrage.speed() > 0) {
            speed.removeModifier(enrageKey);
            speed.addModifier(new AttributeModifier(enrageKey, enrage.speed(), AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        if (enrage.message() != null && !enrage.message().isBlank()) {
            talk(instance, Zone.loreLine(instance.bossDef().name(), enrage.message()));
        }
        broadcast(instance, "<red>☠ " + ItemRenderer.escape(instance.bossDef().name()) + " 狂暴了：傷害更多 " + Math.round(enrage.damage()) + "%");
        broadcastAlert(instance, BossMechanicText.enraged(enrage));
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.5f);
        if (instance.bossBar() != null) {
            instance.bossBar().name(title(instance, instance.bossPhase()));
        }
    }

    /** 終局、極境、巔峰的下一門（2026-10）：Boss 回滿、第一階段、狂暴重算、護盾收掉；Boss 叫出來的小怪清掉。 */
    void resetForAttempt(final MapInstance instance) {
        final LivingEntity boss = instance.boss();
        if (boss != null && boss.isValid() && !instance.bossKilled()) {
            reset(instance, boss, center(instance));
        }
        for (final LivingEntity extra : List.copyOf(instance.extras())) {
            if (extra.isValid()) {
                extra.remove();
            }
        }
        instance.extras().clear();
    }

    private void reset(final MapInstance instance, final LivingEntity boss, final Location center) {
        instance.setBossPhase(0);
        instance.setPhaseTick(PhaseStep.NEVER);
        instance.setPhaseHold(false);
        clearShield(instance, boss); // 也把無敵拿掉（護盾、換階段的等待）
        instance.setFightStartTick(-1);
        instance.clearRoster();
        instance.setEnraged(false);
        service.events().twists().onBossReset(instance); // 地圖變化：分身、援軍收掉，下次開打再來
        final AttributeInstance enrageSpeed = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (enrageSpeed != null) {
            enrageSpeed.removeModifier(enrageKey);
        }
        if (plugin.skills() != null) {
            plugin.skills().bossSkills().reset(boss.getUniqueId());
        }
        plugin.monsters().refreshVanilla(boss);
        final AttributeInstance speed = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(speedKey);
        }
        final AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) {
            boss.setHealth(max.getValue());
        }
        boss.teleport(center);
        if (boss instanceof Mob mob) {
            mob.setTarget(null);
        }
        if (instance.bossBar() != null) {
            instance.bossBar().name(title(instance, 0));
            instance.bossBar().progress(1f);
        }
        instance.setBossLastSeenTick(plugin.getServer().getCurrentTick());
    }

    /**
     * 一個人的 Boss 掉落（傷害有到 5% 的每個人各擲一份）：地圖（照他的星圖）、傳奇、天穹碎片。
     *
     * @param player 拿這份的人；null = 沒有人有份（照任何地圖都可能掉）
     */
    void loot(final MapInstance instance, final Player player, final List<DropTable.Drop> drops) {
        final tw.exilecore.loot.LootBonus.Bonus bonus = bonus(instance, player);
        if (instance.isUber()) {
            uberLoot(instance, player, drops, bonus);
            return;
        }
        if (instance.isPinnacle()) {
            pinnacleLoot(instance, player, drops, bonus);
            return;
        }
        final MapConfig config = service.config();
        final var generator = plugin.items().generator();
        final var random = service.random();
        final Set<String> completed = player == null ? null : service.completed(player);
        // 地圖：跟這張圖同一階（終局是開的那一階），有機率高一階；星圖天賦可以多掉幾張、高一階的機率更高
        final AtlasBonus atlas = instance.atlas();
        final int tier = Math.max(1, instance.stats().tier());
        // 2026-10-21：新星圖的 Boss 格（或首領石板罩到的格子）：必掉 atlas.yml 的 boss_maps 張、傳奇機率 ×boss_unique；核心「魔王的宴席」再 ×1.5
        final AtlasGrid.Cell cell = instance.atlasCell();
        final boolean bossCell = instance.atlasBossCell(); // 2026-10-21 修正：以前看 bossPower > 0，岔路「王道」、詛咒寶匣也會被當成 Boss 格
        // 2026-10-27 照 PoE2（increased 的加成彼此相加）：Boss 格（×2 → +100%）、魔王的宴席（×1.5 → +50%）、星圖天賦 atlas.boss_unique、
        //   物品稀有度加成一項：機率 = 基礎 ×（1 + 這些相加）× 尋星者的代價。這次之前全部相乘：5% × 2 × 1.5 × 1.7 ×（1 + 稀有度）在 Boss 格可以到 100%
        final double uniqueIncreased = (bossCell ? service.atlasConfig().bossUnique() - 1 : 0) + (atlas.get("atlas.boss_double") > 0 ? AtlasBonus.BOSS_FEAST - 1 : 0)
                + atlas.increased("atlas.boss_unique") + Math.max(0, bonus.rarity());
        final double uniqueLess = Math.max(0, 1 - atlas.increased("atlas.unique_less")); // 核心「尋星者」的代價：其他傳奇少
        final int baseMaps = bossCell ? Math.max(config.drops().bossMaps(), service.atlasConfig().bossMaps()) : config.drops().bossMaps();
        // 2026-10-21：魔王的宴席 ×1.5：小數的部分照機率（1 張 → 50% 機率 2 張）
        final double feastMaps = (baseMaps + atlas.bossMaps()) * (atlas.get("atlas.boss_double") > 0 ? AtlasBonus.BOSS_FEAST : 1.0);
        final int wholeMaps = (int) Math.floor(feastMaps) + (random.nextDouble() < feastMaps - Math.floor(feastMaps) ? 1 : 0);
        // 2026-10-27：Boss 必掉的地圖張數不吃物品數量（這次之前 bonus.amount：地圖數量＋Boss 數量＋岔路的 Boss 掉落都把張數放大）
        final int mapCount = wholeMaps;
        if (cell != null && !instance.map().isFinal() && instance.special() == null && plugin.currencies() != null
                && random.nextDouble() < AtlasTablets.BOSS_DROP_CHANCE * (1 + atlas.increased("atlas.tablet.drop"))) {
            final tw.exilecore.currency.CurrencyType tablet = plugin.currencies().repository().get(AtlasTablets.Kind.BOSS.currencyId());
            if (tablet != null) {
                drops.add(DropTable.Drop.currency(tablet)); // 首領石板
            }
        }
        // 2026-10-21：第一次完成的腐化區、淨化區：蝕變石、定星石（每個有份的人各擲一次；吃物品數量）
        if (plugin.currencies() != null && instance.special() == null && !instance.map().isFinal()) {
            if (instance.firstCorrupted() && random.nextDouble() < bonus.more(service.atlasConfig().vaalChance())) {
                final CurrencyType vaal = plugin.currencies().repository().get(CORRUPT_ORB);
                if (vaal != null) {
                    drops.add(DropTable.Drop.currency(vaal));
                }
            }
            if (instance.firstPurified() && random.nextDouble() < bonus.more(service.atlasConfig().purification().fractureChance())) {
                final CurrencyType fracture = plugin.currencies().repository().get(FRACTURE_ORB);
                if (fracture != null) {
                    drops.add(DropTable.Drop.currency(fracture));
                }
            }
        }
        for (int i = 0; i < mapCount; i++) {
            final ItemData map = MapDrops.roll(config, generator, tier, completed, random, atlas.tierUp());
            if (map != null) {
                drops.add(DropTable.Drop.equipment(map));
            }
        }
        // 傳奇：終局 Boss 有機率（final_unique_chance，預設 5%）掉自己的；具名 Boss 機率（named_unique_chance）掉自己的；所有 Boss 機率掉共通的；
        // 追逐級另外擲（rollChase，固定機率）
        final MapConfig.Boss definition = instance.bossDef();
        final int itemLevel = instance.level();
        if (instance.map().isFinal()) {
            // 終局：寫著這個終局代號的傳奇（其他終局 Boss 各自的），沒有就用 final（天穹熔爐的）——2026-10-16：每一件各自擲
            eachUnique(drops, ownFinalPool(instance.map().id(), itemLevel), config.drops().finalUniqueChance(), itemLevel, random, bonus);
            rollFixed(drops, ownSource(instance.map().id(), itemLevel), itemLevel, random); // 2026-10-26：固定機率的（永恆珠寶）
        } else if (definition.named() && random.nextDouble() < Math.min(1, config.drops().namedUniqueChance() * (1 + uniqueIncreased) * uniqueLess)) { // 2026-10-27：加法
            addUnique(drops, MapDrops.unique(generator, instance.map().id(), itemLevel, random));
        }
        // 共通傳奇（map_boss 池，賭博也賭得到）：擲一次、中了抽一件。一般地圖 Boss（含具名）照 boss_unique_chance（5%，星圖天賦可以增加；
        // 跟賭博一起校準，2026-10-16 維持擲一次），終局照 final_common_chance
        final double common = instance.map().isFinal() ? config.drops().finalCommonChance()
                : Math.min(1, config.drops().bossUniqueChance() * (1 + uniqueIncreased) * uniqueLess); // 2026-10-27：加法（稀有度已經在裡面）
        if (random.nextDouble() < (instance.map().isFinal() ? bonus.rare(common) : common)) {
            addUnique(drops, MapDrops.unique(generator, "map_boss", itemLevel, random));
        }
        rollChase(drops, itemLevel, random, 1 + atlas.increased("atlas.chase_unique")); // 追逐級傳奇：所有地圖 Boss 一樣的固定機率（核心「尋星者」×2）
        if (instance.map().isFinal() && plugin.skills() != null) {
            // 終局 Boss 的專屬輔助寶石（supports.yml 的 source 寫這個終局的代號）：每一顆各自擲 final_support_chance（2026-10-16）
            for (final tw.exilecore.skill.SupportDefinition support : plugin.skills().config().supportsFrom(instance.map().id())) {
                if (random.nextDouble() < bonus.rare(config.drops().finalSupportChance())) {
                    drops.add(DropTable.Drop.support(support.id(), itemLevel));
                }
            }
        }
        // 天穹碎片
        if (config.drops().fragmentMaps().contains(instance.map().id()) && random.nextDouble() < config.fragmentChance()) { // 2026-10-24：不是必掉了
            final CurrencyType fragment = plugin.currencies().repository().get(config.drops().fragment());
            if (fragment != null) {
                for (int i = bonus.amount(1, random); i > 0; i--) { // 2026-10：天穹碎片吃物品數量
                    drops.add(DropTable.Drop.currency(fragment));
                }
            }
        }
        // 巔峰的碎片（2026-10）：某個終局（一般版本）開到夠高的階，Boss 有機率掉一個
        if (instance.map().isFinal()) {
            for (final MapConfig.MapDef pinnacleMap : config.pinnacles()) {
                for (final MapConfig.FragmentSource fragment : config.pinnacle(pinnacleMap.id()).fragments()) {
                    if (!fragment.from().equals(instance.map().id()) || instance.stats().tier() < fragment.minTier()
                            || random.nextDouble() >= bonus.more(fragment.chance())) {
                        continue;
                    }
                    final CurrencyType currency = plugin.currencies().repository().get(fragment.currency());
                    if (currency != null) {
                        drops.add(DropTable.Drop.currency(currency));
                    }
                }
            }
        }
        // 終局開到夠高的階：有機率掉開極境的鑰匙（深淵星核）
        final MapConfig.Uber uber = instance.map().isFinal() ? config.uber(instance.map().id()) : null;
        if (uber != null && instance.stats().tier() >= uber.keyMinTier()
                && random.nextDouble() < bonus.more(uber.keyChance() * (1 + atlas.increased("atlas.uber_key")))) {
            final CurrencyType key = plugin.currencies().repository().get(uber.key());
            if (key != null) {
                drops.add(DropTable.Drop.currency(key));
            }
        }
    }

    /**
     * 追逐級傳奇（uniques.yml 的 chase 池）：所有地圖 Boss（一般、具名、終局、極境）每次擊殺、每個有份的人各擲一次
     * maps.yml 的 drops.chase_chance（0.034%）。故意不吃任何加成（星圖天賦、物品數量、稀有度）；物品等級不到 75 時池子是空的。
     */
    private void rollChase(final List<DropTable.Drop> drops, final int itemLevel, final java.util.random.RandomGenerator random) {
        rollChase(drops, itemLevel, random, 1.0);
    }

    private void rollChase(final List<DropTable.Drop> drops, final int itemLevel, final java.util.random.RandomGenerator random, final double multiplier) {
        if (random.nextDouble() < service.config().drops().chaseChance() * multiplier) {
            addUnique(drops, MapDrops.unique(plugin.items().generator(), "chase", itemLevel, random));
        }
        // 映星鏡（2026-10）：另外擲一次 maps.yml 的 drops.mirror_chance（追逐級的十分之一；不看物品等級；一般怪物的那一份在 MonsterListener.rollMirror）
        if (plugin.currencies() != null && random.nextDouble() < service.config().mirrorChance()) {
            final tw.exilecore.currency.CurrencyType mirror = plugin.currencies().mirrorType();
            if (mirror != null) {
                drops.add(DropTable.Drop.currency(mirror));
            }
        }
        // 共鳴石（2026-10-18）：另外擲一次 maps.yml 的 drops.resonance_chance（追逐級的 1/1.5；不吃任何加成；一般怪物的那一份在 MapService.rollLoot）
        if (plugin.currencies() != null && itemLevel >= MapConfig.RESONANCE_MIN_LEVEL
                && random.nextDouble() < service.config().resonanceChance()) {
            final tw.exilecore.currency.CurrencyType resonance = plugin.currencies().resonanceType();
            if (resonance != null) {
                drops.add(DropTable.Drop.currency(resonance));
            }
        }
    }

    /**
     * 極境 Boss 的掉落（每個有份的人各一份）：最高階的地圖好幾張、這個終局自己的傳奇（機率比一般版本高）、
     * 極境專屬傳奇、追逐級傳奇（跟一般 Boss 一樣）、額外的通貨（maps.yml 的 uber.drops）。
     */
    private void uberLoot(final MapInstance instance, final Player player, final List<DropTable.Drop> drops,
                          final tw.exilecore.loot.LootBonus.Bonus bonus) {
        final MapConfig config = service.config();
        final MapConfig.UberDrops uberDrops = instance.uber().drops();
        final var generator = plugin.items().generator();
        final var random = service.random();
        final Set<String> completed = player == null ? null : service.completed(player);
        final int uberMaps = bonus.amount(uberDrops.maps(), random);
        for (int i = 0; i < uberMaps; i++) {
            final ItemData map = MapDrops.roll(config, generator, config.tiers().max(), completed, random);
            if (map != null) {
                drops.add(DropTable.Drop.equipment(map));
            }
        }
        final int itemLevel = instance.level();
        // 2026-10-16：自己的傳奇、極境專屬每一件各自擲；共通傳奇擲一次（common_chance，沒寫照 final_common_chance）
        eachUnique(drops, ownFinalPool(instance.map().id(), itemLevel), uberDrops.uniqueChance(), itemLevel, random, bonus);
        rollFixed(drops, ownSource(instance.map().id(), itemLevel), itemLevel, random); // 2026-10-26：固定機率的（永恆珠寶）
        rollChase(drops, itemLevel, random); // 追逐級傳奇：跟一般 Boss 一樣的固定機率
        if (random.nextDouble() < bonus.rare(uberDrops.commonChance(config.drops().finalCommonChance()))) {
            addUnique(drops, MapDrops.unique(generator, "map_boss", itemLevel, random));
        }
        eachUnique(drops, uberExclusives(MapConfig.uberSource(instance.map().id()), itemLevel), uberDrops.exclusiveChance(), itemLevel, random, bonus);
        extraDrops(drops, uberDrops.extra(), itemLevel, random, bonus);
        // 巔峰的碎片（2026-10）：這個終局的極境版本也掉（maps.yml 的 uber_chance）
        for (final MapConfig.MapDef pinnacleMap : config.pinnacles()) {
            for (final MapConfig.FragmentSource fragment : config.pinnacle(pinnacleMap.id()).fragments()) {
                if (!fragment.from().equals(instance.map().id()) || fragment.uberChance() <= 0
                        || random.nextDouble() >= bonus.more(fragment.uberChance())) {
                    continue;
                }
                final CurrencyType currency = plugin.currencies().repository().get(fragment.currency());
                if (currency != null) {
                    drops.add(DropTable.Drop.currency(currency));
                }
            }
        }
    }

    /**
     * 巔峰 Boss 的掉落（2026-10，每個有份的人各一份；寫法同極境的 drops）：最高階的地圖好幾張、自己的傳奇（沒有就終局共通的）、
     * 極境專屬的傳奇、追逐級、額外的通貨與輔助。
     * 2026-10-16：自己的傳奇、極境專屬都是「每一件各自擲」unique_chance／exclusive_chance（不是整個池子擲一次再抽一件）。
     */
    private void pinnacleLoot(final MapInstance instance, final Player player, final List<DropTable.Drop> drops,
                              final tw.exilecore.loot.LootBonus.Bonus bonus) {
        final MapConfig config = service.config();
        final MapConfig.UberDrops pinnacleDrops = instance.pinnacle().drops();
        final var generator = plugin.items().generator();
        final var random = service.random();
        final Set<String> completed = player == null ? null : service.completed(player);
        final int pinnacleMaps = bonus.amount(pinnacleDrops.maps(), random);
        for (int i = 0; i < pinnacleMaps; i++) {
            final ItemData map = MapDrops.roll(config, generator, config.tiers().max(), completed, random);
            if (map != null) {
                drops.add(DropTable.Drop.equipment(map));
            }
        }
        final int itemLevel = instance.level();
        final List<tw.exilecore.item.UniqueItem> own = plugin.items().repository().uniquesFor(instance.map().id(), itemLevel);
        if (own.isEmpty()) {
            if (random.nextDouble() < bonus.rare(pinnacleDrops.uniqueChance())) {
                addUnique(drops, MapDrops.unique(generator, "final", itemLevel, random)); // 沒有自己的：終局共通池抽一件
            }
        } else {
            for (final tw.exilecore.item.UniqueItem unique : own) {
                if (random.nextDouble() < bonus.rare(pinnacleDrops.uniqueChance())) {
                    addUnique(drops, generator.generateUnique(unique, itemLevel, random));
                }
            }
        }
        rollFixed(drops, ownSource(instance.map().id(), itemLevel), itemLevel, random); // 2026-10-26：固定機率的（永恆珠寶）
        rollChase(drops, itemLevel, random);
        if (random.nextDouble() < bonus.rare(pinnacleDrops.commonChance(config.drops().finalCommonChance()))) {
            addUnique(drops, MapDrops.unique(generator, "map_boss", itemLevel, random));
        }
        // 極境專屬：這隻巔峰自己的（uber_終局代號）；沒有就是四個極境各自的專屬（uber_ 開頭）——每一件各自擲
        for (final tw.exilecore.item.UniqueItem unique : uberExclusives(MapConfig.uberSource(instance.map().id()), itemLevel)) {
            if (random.nextDouble() < bonus.rare(pinnacleDrops.exclusiveChance())) {
                addUnique(drops, generator.generateUnique(unique, itemLevel, random));
            }
        }
        extraDrops(drops, pinnacleDrops.extra(), itemLevel, random, bonus);
    }

    /** 這個終局自己的傳奇（uniques.yml 的 sources 寫這個終局代號）；沒有就用 final（天穹熔爐的）。 */
    private List<tw.exilecore.item.UniqueItem> ownFinalPool(final String mapId, final int itemLevel) {
        final List<tw.exilecore.item.UniqueItem> own = plugin.items().repository().uniquesFor(mapId, itemLevel);
        return own.isEmpty() ? plugin.items().repository().uniquesFor("final", itemLevel) : own;
    }

    /** 2026-10-26：這個終局／巔峰的傳奇來源代號：有自己的傳奇（權重池或固定機率）就是地圖代號，沒有就是 final。 */
    private String ownSource(final String mapId, final int itemLevel) {
        final var repository = plugin.items().repository();
        return repository.uniquesFor(mapId, itemLevel).isEmpty() && repository.fixedChanceUniques(mapId, itemLevel).isEmpty() ? "final" : mapId;
    }

    /**
     * 2026-10-26 照 PoE2：固定機率的傳奇（uniques.yml 的 drop_chance，永恆珠寶 1%）——來源 Boss 每次擊殺、每個有份的人各自擲，
     * 不跟那個來源的其他傳奇搶權重池（{@link tw.exilecore.item.ItemRepository#uniquesFor} 不含它們），也不吃物品稀有度。
     */
    void rollFixed(final List<DropTable.Drop> drops, final String source, final int itemLevel, final java.util.random.RandomGenerator random) {
        final var repository = plugin.items().repository();
        for (final tw.exilecore.item.UniqueItem unique : repository.fixedChanceUniques(source, itemLevel)) {
            if (random.nextDouble() < repository.dropChance(unique.id())) {
                addUnique(drops, plugin.items().generator().generateUnique(unique, itemLevel, random));
            }
        }
    }

    /** 每一件各自擲 chance（吃物品稀有度；終局以上 bonus 是 NONE）。 */
    private void eachUnique(final List<DropTable.Drop> drops, final List<tw.exilecore.item.UniqueItem> pool, final double chance,
                            final int itemLevel, final java.util.random.RandomGenerator random, final tw.exilecore.loot.LootBonus.Bonus bonus) {
        final double rolled = bonus.rare(chance);
        for (final tw.exilecore.item.UniqueItem unique : pool) {
            if (random.nextDouble() < rolled) {
                addUnique(drops, plugin.items().generator().generateUnique(unique, itemLevel, random));
            }
        }
    }

    /** 巔峰的極境專屬池：自己的（source），沒有就所有 uber_ 開頭來源的傳奇（2026-10-16：以前找 uber 找不到，一件都不會掉）。 */
    List<tw.exilecore.item.UniqueItem> uberExclusives(final String source, final int itemLevel) {
        final List<tw.exilecore.item.UniqueItem> own = plugin.items().repository().uniquesFor(source, itemLevel);
        if (!own.isEmpty()) {
            return own;
        }
        final List<tw.exilecore.item.UniqueItem> all = new ArrayList<>();
        for (final tw.exilecore.item.UniqueItem unique : plugin.items().repository().uniques()) {
            if (unique.weight() > 0 && unique.dropLevel() <= itemLevel && unique.sources().stream().anyMatch(s -> s.startsWith("uber_"))) {
                all.add(unique);
            }
        }
        return all;
    }

    /** 極境、巔峰的額外掉落（maps.yml 的 drops.extra）。 */
    private void extraDrops(final List<DropTable.Drop> drops, final List<MapConfig.ExtraDrop> extras, final int itemLevel,
                            final java.util.random.RandomGenerator random, final tw.exilecore.loot.LootBonus.Bonus bonus) {
        for (final MapConfig.ExtraDrop extra : extras) {
            final boolean gem = "support".equals(extra.kind()) || "skill".equals(extra.kind());
            // 2026-10：寶石照物品稀有度、通貨照物品數量
            if (random.nextDouble() >= (gem ? bonus.rare(extra.chance()) : bonus.more(extra.chance()))) {
                continue;
            }
            if ("support".equals(extra.kind())) {
                for (int i = 0; i < extra.amount(); i++) {
                    addSupportId(drops, extra.id(), itemLevel);
                }
                continue;
            }
            if ("skill".equals(extra.kind())) {
                for (int i = 0; i < extra.amount(); i++) {
                    addSkillId(drops, extra.id());
                }
                continue;
            }
            final CurrencyType currency = plugin.currencies().repository().get(extra.id());
            if (currency != null) {
                for (int i = bonus.amount(extra.amount(), random); i > 0; i--) {
                    drops.add(DropTable.Drop.currency(currency));
                }
            }
        }
    }

    /**
     * 這個人在這張圖拿 Boss 特殊掉落時的物品數量、稀有度（2026-10：全域）：自己的＋全服＋這個月的主題＋地圖本身的＋星圖天賦的 Boss 加成。
     * 怪物稀有度（傳奇怪 +2400% 數量，2026-10-27 照 PoE2；之前 +300%）不算：那是一般掉落表專用的。
     * 2026-10-27：腐化區的稀有度（twist.bossRarity）只加稀有度。
     */
    private tw.exilecore.loot.LootBonus.Bonus bonus(final MapInstance instance, final Player player) {
        if (instance.usesAttempts()) {
            return tw.exilecore.loot.LootBonus.Bonus.NONE; // 2026-10-16：終局以上（終局、極境、巔峰、金庫之主）的掉落不吃物品數量、稀有度
        }
        return tw.exilecore.loot.LootBonus.of(plugin, player).plus(
                instance.stats().quantity() / 100.0 + instance.atlas().increased("atlas.boss_quantity") + instance.twist().bossLoot,
                instance.stats().rarity() / 100.0 + instance.atlas().increased("atlas.boss_rarity") + instance.twist().bossLoot + instance.twist().bossRarity);
    }

    /** 從某個來源的專屬輔助寶石裡抽一顆（supports.yml 的 source）；沒有就不掉。 */
    private void addSupport(final List<DropTable.Drop> drops, final String source, final int level) {
        if (plugin.skills() == null) {
            return;
        }
        final List<tw.exilecore.skill.SupportDefinition> own = plugin.skills().config().supportsFrom(source);
        if (!own.isEmpty()) {
            drops.add(DropTable.Drop.support(own.get(service.random().nextInt(own.size())).id(), level));
        }
    }

    /** 一顆指定的技能寶石，直接 20 級（skills.yml 沒有這個代號就不掉；巔峰 Boss 的專屬技能寶石）。 */
    private void addSkillId(final List<DropTable.Drop> drops, final String skillId) {
        if (plugin.skills() != null && plugin.skills().config().skill(skillId) != null) {
            drops.add(DropTable.Drop.skill(skillId, tw.exilecore.character.SkillLoadout.MAX_LEVEL));
        }
    }

    /** 一顆指定的輔助寶石（supports.yml 沒有這個代號就不掉）。 */
    private void addSupportId(final List<DropTable.Drop> drops, final String supportId, final int level) {
        if (plugin.skills() != null && plugin.skills().config().support(supportId) != null) {
            drops.add(DropTable.Drop.support(supportId, level));
        }
    }

    /** 蝕變石、定星石的通貨代號（2026-10-21，currencies.yml）。 */
    static final String CORRUPT_ORB = "corrupt_orb";
    static final String FRACTURE_ORB = "fracture_orb";

    /** Boss 倒下（一次）：台詞、收掉血條、完成地圖（星圖、首殺），每個人的那份掉落已經擲完了。 */
    void killed(final MapInstance instance, final LivingEntity boss) {
        if (instance.bossKilled()) {
            return;
        }
        instance.setBossKilled(true);
        service.recordFight(instance); // 2026-10-21：/ec bossdps 的統計
        if (instance.usesAttempts()) {
            instance.closePortals(); // 2026-10：通關了，門全部關起來（裡面的人照樣可以撿完再出來）
        }
        clearShield(instance, boss);
        detachSkills(boss);
        final MapConfig.Boss definition = instance.bossDef();
        if (definition.defeat() != null && !definition.defeat().isBlank()) {
            talk(instance, Zone.loreLine(definition.name(), definition.defeat()), true); // 2026-10-25：倒下的台詞也算系統訊息
        }
        end(instance);
        final org.bukkit.Location at = boss.getLocation();
        // 2026-10-21：對 Boss 打到傷害門檻的人（隊友要在裡面才推進自己的星圖）；Boss 還在世界上，傷害紀錄還沒清
        final java.util.Set<java.util.UUID> damagers = new java.util.HashSet<>(tw.exilecore.monster.DamageLedger.qualifying(
                plugin.monsters().ledger().damageTo(boss.getUniqueId()),
                plugin.party() == null ? new tw.exilecore.party.PartyConfig().shareThreshold() : plugin.party().config().shareThreshold()));
        service.complete(instance, at, damagers);
        boss.getWorld().playSound(boss.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        // 擊殺稱號（2026-10）：極境 Boss（照終局代號）與金庫之主，倒下時在 Boss 32 格內、活著、不是觀戰的人各記一次
        // 2026-10-27：還要對 Boss 打到傷害門檻（跟掉落、星圖一樣）；這次之前站在旁邊沒打也拿得到稱號與永久加成
        final String killKey = instance.special() != null && "heist_finale".equals(instance.special().kind()) ? "heist_finale"
                : instance.isUber() || instance.isPinnacle() ? instance.map().id() : null;
        if (killKey != null && plugin.bounties() != null) {
            for (final java.util.UUID uuid : instance.inside()) {
                final org.bukkit.entity.Player player = plugin.getServer().getPlayer(uuid);
                if (player != null && earnsKillCredit(damagers, uuid, instance.contains(player.getLocation()) && service.atBoss(player, at))) {
                    plugin.bounties().recordBossKill(player, killKey);
                }
            }
        }
    }

    /** 2026-10-27：Boss 倒下時這個人能不能記擊殺（稱號、永久加成）：在 Boss 旁邊，而且打到傷害門檻。 */
    static boolean earnsKillCredit(final java.util.Set<java.util.UUID> damagers, final java.util.UUID uuid, final boolean nearBoss) {
        return nearBoss && damagers.contains(uuid);
    }

    private static void addUnique(final List<DropTable.Drop> drops, final ItemData unique) {
        if (unique != null) {
            drops.add(DropTable.Drop.equipment(unique));
        }
    }

    /** 所有人的畫面上收掉這個實例的血條。 */
    private void hideBar(final MapInstance instance) {
        final BossBar bar = instance.bossBar();
        if (bar != null) {
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                player.hideBossBar(bar);
            }
            instance.setBossBar(null);
        }
    }

    /** 離開實例的玩家收掉血條。 */
    void hideFor(final MapInstance instance, final Player player) {
        final BossBar bar = instance.bossBar();
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /** 收掉血條（與護盾結晶）。 */
    void end(final MapInstance instance) {
        hideBar(instance);
        final LivingEntity boss = instance.boss();
        clearShield(instance, boss);
        if (boss != null && boss.isValid() && !boss.isDead() && instance.state() == MapInstance.State.CLOSING) {
            boss.remove();
        }
        if (boss != null) {
            detachSkills(boss);
        }
    }

    /** 掛上招式（data/boss_skills.yml：maps.yml 寫的 skills，沒寫就照生物種類的預設）。小怪照這張地圖的規矩生。 */
    private void attachSkills(final MapInstance instance, final LivingEntity boss) {
        if (plugin.skills() == null) {
            return;
        }
        final MapConfig.Boss definition = instance.bossDef();
        plugin.skills().bossSkills().attach(boss, definition.skills(), new tw.exilecore.skill.BossSkills.Host() {
            @Override
            public String name() {
                return definition.name();
            }

            @Override
            public int phase() {
                return instance.bossPhase();
            }

            @Override
            public List<tw.exilecore.skill.BossHazard> hazards() {
                final tw.exilecore.skill.BossHazard extra = service.events().twists().bossHazard(instance);
                if (extra == null) {
                    return instance.bossDef().hazards();
                }
                final List<tw.exilecore.skill.BossHazard> all = new ArrayList<>(instance.bossDef().hazards());
                all.add(extra); // 地圖變化的 Boss 房干擾（排在最後：前面的計時不受影響）
                return all;
            }

            @Override
            public boolean alerts() {
                return MapBosses.alerts(instance);
            }

            @Override
            public String tempoKind() {
                return MapBosses.tempoKind(instance);
            }

            @Override
            public boolean dormant() {
                return instance.boss() == boss && !instance.bossKilled() && instance.fightStartTick() < 0;
            }

            @Override
            public Location arenaCenter() {
                return center(instance); // 2026-10-17：萬界崩解固定在 Boss 房正中間
            }

            @Override
            public LivingEntity summon(final String type, final Location at) {
                Location spot = at;
                if (!instance.layout().isFloor(spot.getBlockX() - instance.originX(), spot.getBlockZ() - instance.originZ())) {
                    spot = boss.getLocation();
                }
                final LivingEntity minion = service.spawner().spawnVanilla(type, spot, created -> {
                    service.tag(created, instance, false);
                    plugin.monsters().markSummoned(created);
                });
                if (minion != null) {
                    plugin.monsters().decorate(minion, instance.level(), MonsterRarity.NORMAL, List.of());
                    MapSpawner.prepare(minion);
                }
                return minion;
            }
        });
    }

    /** 出招節奏的種類（boss_skills.yml 的 settings.tempo）：極境、金庫之主、昇華試煉、終局；一般地圖 Boss 回傳 null。 */
    static String tempoKind(final MapInstance instance) {
        if (instance.isUber()) {
            return "uber";
        }
        if (instance.isPinnacle()) {
            return "pinnacle";
        }
        if (instance.special() != null && "heist_finale".equals(instance.special().kind())) {
            return "vault";
        }
        if (instance.map().id().startsWith("trial_")) {
            return "trial";
        }
        return instance.map().isFinal() ? "final" : null;
    }

    /** 終局 Boss（含極境）與昇華試煉的 Boss：特殊招式、機制在畫面中間與聊天室說清楚。 */
    static boolean alerts(final MapInstance instance) {
        return instance.map().isFinal() || instance.map().id().startsWith("trial_");
    }

    /** 這個階段才開始用的招式名稱（招式組裡 phase 寫這個階段的）。 */
    private List<String> newSkills(final LivingEntity boss, final MapConfig.Boss definition, final int phase) {
        if (plugin.skills() == null) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        for (final tw.exilecore.skill.BossSkillConfig.Entry entry
                : plugin.skills().config().bossSkills().kitFor(boss.getType().name(), definition.skills())) {
            if (entry.fromPhase() == phase && !names.contains(entry.skill().name())) {
                names.add(entry.skill().name());
            }
        }
        return names;
    }

    /** 招式代號 → 名稱（找不到的略過）。 */
    private List<String> skillNames(final List<String> ids) {
        if (plugin.skills() == null) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        for (final String id : ids) {
            final tw.exilecore.skill.BossSkill skill = plugin.skills().config().bossSkills().skill(id);
            if (skill != null && !names.contains(skill.name())) {
                names.add(skill.name());
            }
        }
        return names;
    }

    private void detachSkills(final LivingEntity boss) {
        if (plugin.skills() != null) {
            plugin.skills().bossSkills().detach(boss.getUniqueId());
        }
    }

    /** 2026-10-22：Boss 的台詞（登場、換階段、狂暴、倒下）：開了「隱藏怪物對話」的人不送。 */
    private void talk(final MapInstance instance, final String miniMessage) {
        talk(instance, miniMessage, false);
    }

    /** 2026-10-25：system = 開了「過濾系統訊息」的人也不送。 */
    private void talk(final MapInstance instance, final String miniMessage, final boolean system) {
        for (final UUID uuid : instance.inside()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && !plugin.players().hidesMonsterTalk(player) && !(system && plugin.players().hidesSystem(player))) {
                player.sendMessage(plugin.text().parse(miniMessage));
            }
        }
    }

    private void broadcast(final MapInstance instance, final String miniMessage) {
        for (final UUID uuid : instance.inside()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.sendMessage(plugin.text().parse(miniMessage));
            }
        }
    }

    /**
     * 畫面中間的大字＋副標題（換階段、護盾、狂暴）。
     *
     * @param lines 大字、副標題（MiniMessage）
     */
    private void broadcastAlert(final MapInstance instance, final String[] lines) {
        final Title title = Title.title(plugin.text().parse(lines[0]), plugin.text().parse(lines.length > 1 ? lines[1] : ""),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2600), Duration.ofMillis(400)));
        for (final UUID uuid : instance.inside()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                tw.exilecore.gui.Titles.show(player, title); // 2026-10-26：沒有副標題時不送空的（會留空框）
            }
        }
    }

    private void broadcastActionBar(final MapInstance instance, final String miniMessage) {
        for (final UUID uuid : instance.inside()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(miniMessage));
            }
        }
    }
}
