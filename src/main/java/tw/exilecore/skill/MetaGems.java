package tw.exilecore.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 主要寶石的執行期（2026-10，照 PoE2 的 Meta Gem；設定見 {@link MetaSpec}）。
 * <ul>
 *   <li>插槽（2026-10-22）：主要寶石自己的第 1 孔放一顆法術的技能寶石（{@link SkillLoadout.Entry#inner}），第 2～5 孔的輔助都套在那個法術上
 *       （跟英靈圖騰放錘子技能一樣的做法）。以前是「面板上它後面那一格」。</li>
 *   <li>能量：常駐的主要寶石（暴擊時施放這類）開著（綁在按鍵格、精魂付得起）時，條件成立就累積能量；
 *       到插槽法術的上限（基礎施法時間每 0.1 秒 10 點）就觸發一次、扣掉上限。能量不存檔（登出、換角色歸零）。</li>
 *   <li>被觸發的法術不花魔力；它打出來的暴擊不會再幫暴擊時施放充能（避免自己觸發自己）。</li>
 * </ul>
 */
public final class MetaGems {

    /** 被主要寶石觸發的法術，技能描述的代號後面接這個（打中時認得出來：不再充能、傷害統計分開）。 */
    public static final String TRIGGERED_SUFFIX = ".meta";
    /** 同一顆主要寶石兩次觸發至少隔幾 tick（能量一次給很多時不要同一刻連放好幾發）。 */
    static final long MIN_GAP = 4;

    /** 插槽：主要寶石在面板的第幾格、插槽裡的法術。 */
    record Socket(int metaPosition, SkillLoadout.Entry metaEntry, MetaSpec spec, SkillLoadout.Entry spellEntry, SkillDefinition spell) {
    }

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, Map<String, Double>> energy = new HashMap<>();
    private final Map<UUID, Map<String, Long>> lastFired = new HashMap<>();

    MetaGems(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    // ---- 插槽 ----

    /** 面板第 position 格的主要寶石的插槽；那一格不是主要寶石、或下一格的法術不合格，回傳 null。 */
    Socket socket(final PlayerState state, final int position) {
        final SkillLoadout loadout = state.skills();
        final SkillLoadout.Entry metaEntry = loadout.skill(position);
        final MetaSpec spec = metaEntry == null ? null : service.config().meta(metaEntry.skillId());
        if (spec == null) {
            return null;
        }
        final SkillLoadout.Entry spellEntry = SkillService.innerEntry(metaEntry); // 2026-10-22：第 1 孔裡的技能寶石
        final SkillDefinition spell = spellEntry == null ? null : service.config().skill(spellEntry.skillId());
        return spell != null && problem(spec, spell) == null ? new Socket(position, metaEntry, spec, spellEntry, spell) : null;
    }

    /** 這個主要寶石（面板第 position 格）的插槽有什麼問題；沒問題回傳 null（說明、按下去時的提示用）。 */
    String socketProblem(final PlayerState state, final int position) {
        final SkillLoadout loadout = state.skills();
        final SkillLoadout.Entry metaEntry = loadout.skill(position);
        final MetaSpec spec = metaEntry == null ? null : service.config().meta(metaEntry.skillId());
        if (spec == null) {
            return null;
        }
        final SkillLoadout.Entry spellEntry = SkillService.innerEntry(metaEntry);
        final SkillDefinition spell = spellEntry == null ? null : service.config().skill(spellEntry.skillId());
        final SkillDefinition metaDefinition = service.config().skill(metaEntry.skillId());
        final String name = metaDefinition == null ? metaEntry.skillId() : metaDefinition.name();
        if (spell == null) {
            return name + "：第 1 孔要放一個法術的技能寶石（/skills 點它，拿技能寶石點第 1 孔）";
        }
        final String why = problem(spec, spell);
        return why == null ? null : name + "：插槽裡的" + spell.name() + why;
    }

    /** 這個法術能不能插進這種主要寶石；可以回傳 null，不行回傳原因（接在法術名字後面）。 */
    static String problem(final MetaSpec spec, final SkillDefinition spell) {
        if (spell.hidden() || spell.type() != SkillType.SPELL || !spell.tags().contains(tw.exilecore.stats.Tags.SPELL)) {
            return "不是法術（只能插法術）";
        }
        final Mechanic mechanic = spell.mechanic();
        if (mechanic == Mechanic.AURA || mechanic == Mechanic.SUMMON || mechanic == Mechanic.WARCRY || mechanic == Mechanic.DASH
                || mechanic == Mechanic.LEAP || mechanic == Mechanic.FLICKER || mechanic == Mechanic.RALLY || mechanic == Mechanic.GUARD
                || mechanic == Mechanic.META || mechanic == Mechanic.DESECRATE) {
            return "不能插（常駐、召喚、戰吼、位移、增益、主要寶石不行）";
        }
        if (spell.tags().contains("command")) {
            return "不能插（指令召喚物的技能要有召喚物才能放）"; // 2026-10-17：以前觸發時沒有召喚物也從自己身上放出來
        }
        if (spec.totem() && (spell.cooldown() > 0 || !SkillBuild.deployable(spell) || spell.deploy() != null)) {
            return "不能交給法術圖騰放（有冷卻、本來就是圖騰／陷阱的不行）";
        }
        return null;
    }

    // ---- 能量 ----

    /** 怪物強度（照 PoE2 的 power）：一般 1、魔法 2、稀有 5、傳奇 10。不是怪物回傳 1。 */
    double power(final LivingEntity target) {
        if (target == null || plugin.monsters() == null) {
            return 1;
        }
        return plugin.monsters().data(target).map(data -> power(data.rarity())).orElse(1.0);
    }

    static double power(final MonsterRarity rarity) {
        return switch (rarity) {
            case NORMAL -> 1;
            case MAGIC -> 2;
            case RARE -> 5;
            case UNIQUE -> 10;
        };
    }

    /**
     * 條件成立：這種的主要寶石（開著的）各得到 base × 怪物強度…的能量，滿了就觸發插槽法術。
     *
     * @param multiplier 照種類乘上去的倍數（怪物強度、冰凍 ×3…）
     */
    void gain(final Player player, final MetaSpec.Kind kind, final double multiplier, final LivingEntity target) {
        if (!service.eligible(player) || service.inTown(player)) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final SkillLoadout loadout = state.skills();
        final long now = plugin.getServer().getCurrentTick();
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = loadout.skill(i);
            final MetaSpec spec = entry == null ? null : service.config().meta(entry.skillId());
            if (spec == null || spec.kind() != kind || !service.auras().isActive(player.getUniqueId(), entry.skillId())) {
                continue; // 主要寶石要開著（綁在按鍵格、精魂付得起）才會充能
            }
            final Socket socket = socket(state, i);
            if (socket == null) {
                warnProblem(player, state, i, now); // 2026-10：插槽放錯（不是法術、增益技能…）以前完全沒反應，看不出為什麼不觸發
                continue;
            }
            final int level = usableLevel(state, entry);
            final double gained = spec.energy() * multiplier * (1 + spec.energyMore().at(level) / 100.0);
            final double max = MetaSpec.maxEnergy(socket.spell());
            final Map<String, Double> mine = energy.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
            // 能量記在「主要寶石:插槽法術」上：換了插槽法術就從 0 開始
            final String key = entry.skillId() + ":" + socket.spell().id();
            mine.keySet().removeIf(k -> k.startsWith(entry.skillId() + ":") && !k.equals(key));
            final double raw = mine.getOrDefault(key, 0.0) + gained;
            final Map<String, Long> fired = lastFired.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
            if (raw >= max - 1e-9 && now - fired.getOrDefault(entry.skillId(), Long.MIN_VALUE / 2) >= MIN_GAP) {
                mine.put(key, Math.min(raw - max, max * 0.99)); // 多出來的留著（但一次最多觸發一發）
                fired.put(entry.skillId(), now);
                final double scale = (1 - spec.lessDamage() / 100.0) * (1 + spec.moreDamage() / 100.0);
                final SkillDefinition metaDefinition = service.config().skill(entry.skillId());
                service.metaCast(player, socket.spell(), socket.spellEntry(), target != null && target.isValid() && !target.isDead() ? target : null,
                        scale, metaDefinition == null ? "主要寶石" : metaDefinition.name());
            } else {
                mine.put(key, Math.min(raw, max)); // 還在間隔裡：存到滿為止
            }
        }
    }

    /** 主要寶石實際用幾級算能量（2026-10-17：Lv.21 寶石照 21 級算，跟 SkillBuild 一樣：角色到 20 級寶石的需求等級就能用 21 級）。 */
    int usableLevel(final PlayerState state, final SkillLoadout.Entry entry) {
        final int usable = entry.level() > SkillLoadout.MAX_LEVEL
                && state.level() >= service.config().requiredLevel(SkillLoadout.MAX_LEVEL)
                ? Math.min(entry.level(), SkillLoadout.GEM_CAP) : service.config().maxLevelFor(state.level());
        return Math.max(1, Math.min(entry.level(), usable));
    }

    /** 上次提醒插槽有問題的時間（每 15 秒最多提醒一次）。 */
    private final Map<UUID, Long> warned = new HashMap<>();

    private void warnProblem(final Player player, final PlayerState state, final int position, final long now) {
        final String problem = socketProblem(state, position);
        if (problem == null || now - warned.getOrDefault(player.getUniqueId(), Long.MIN_VALUE / 2) < 300) {
            return;
        }
        warned.put(player.getUniqueId(), now);
        state.notice("<red>" + ItemRenderer.escape(problem), now + 60);
        plugin.players().display(player, state);
    }

    /** 這顆主要寶石現在累積的能量（說明用）。 */
    double energyOf(final UUID player, final String metaId, final String spellId) {
        final Map<String, Double> mine = energy.get(player);
        return mine == null ? 0 : mine.getOrDefault(metaId + ":" + spellId, 0.0);
    }

    void forget(final UUID player) {
        energy.remove(player);
        lastFired.remove(player);
        warned.remove(player);
    }

    // ---- 法術圖騰 ----

    /**
     * 法術圖騰放下去的那個法術的數值：插槽法術（含它自己的輔助）加上法術圖騰的效果——
     * 由圖騰施放、每消耗一顆銳星增加傷害、每消耗一顆磐星圖騰更多生命與持續時間、施法速度更少、圖騰上限照寶石等級。
     */
    SkillBuild totemBuild(final PlayerState state, final tw.exilecore.stats.StatSheet sheet, final Socket socket, final int metaLevel,
                          final int powerUsed, final int enduranceUsed) {
        final MetaSpec spec = socket.spec();
        final SkillDefinition metaDefinition = service.config().skill(socket.metaEntry().skillId());
        final String source = metaDefinition == null ? "法術圖騰" : metaDefinition.name();
        final List<Modifier> mods = new ArrayList<>();
        mods.add(Modifier.flat(StatKeys.FLAG_SPELL_TOTEM, 1, source));
        if (powerUsed > 0 && spec.perPower() > 0) {
            // 2026-10-22：照 PoE2 改成「增加」（以前是更多，3 顆 = ×1.3，比 PoE2 強很多）
            mods.add(Modifier.increased(StatKeys.DAMAGE, spec.perPower() * powerUsed / 100.0, source));
        }
        if (enduranceUsed > 0 && spec.perEndurance() > 0) {
            mods.add(Modifier.more(StatKeys.TOTEM_LIFE, spec.perEndurance() * enduranceUsed / 100.0, source));
            mods.add(Modifier.more(StatKeys.SKILL_DURATION, spec.perEndurance() * enduranceUsed / 100.0, source));
        }
        if (spec.lessCastSpeed() > 0) {
            mods.add(Modifier.more(StatKeys.CAST_SPEED, -spec.lessCastSpeed() / 100.0, source));
        }
        final int extraTotems = Math.max(0, spec.totems().intAt(metaLevel) - 1);
        if (extraTotems > 0) {
            mods.add(Modifier.flat(StatKeys.SKILL_TOTEMS, extraTotems, source));
        }
        // 2026-10-22：寶石第 2～5 孔的輔助全部套在插槽法術上（spellEntry 的輔助就是它們；要圖騰的疾樁、樁怒也在裡面）
        final SupportDefinition carrier = new SupportDefinition("meta." + socket.metaEntry().skillId(), source, "", "PAPER", 1,
                java.util.Set.of(), java.util.Set.of(), 1.0, mods, List.of());
        final List<SupportDefinition> supports = new ArrayList<>();
        supports.add(carrier); // 放第一個：圖騰施法優先（插槽法術自己掛了陷阱施法的話那顆不算）
        supports.addAll(service.supportsOf(socket.spellEntry()));
        return SkillBuild.of(service.config(), socket.spell(), socket.spellEntry().level(), state.level(), false, supports,
                state.weaponTags(), sheet, SkillService.resonantOf(socket.spellEntry())); // 2026-10-18：共鳴的專屬輔助不佔名額
    }

    // ---- 說明 ----

    /** 主要寶石的說明多幾行：插槽裡是什麼、能量上限、現在的能量。 */
    List<String> lines(final Player player, final SkillDefinition meta) {
        final MetaSpec spec = service.config().meta(meta.id());
        if (spec == null) {
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(meta.id());
        lines.add("<#c9a7ff>主要寶石<gray>：把法術的技能寶石放進它的<white>第 1 孔</white>，第 2～5 孔的輔助都套在那個法術上");
        if (spec.totem()) {
            lines.add(String.format(Locale.ROOT, "<gray>放的時候消耗 <white>%d</white> 顆銳星或磐星（銳星先用）：每顆銳星插槽法術增加 <white>%s%%</white> 傷害、"
                    + "每顆磐星圖騰更多 <white>%s%%</white> 生命與持續時間", spec.charges(), number(spec.perPower()), number(spec.perEndurance())));
            lines.add("<gray>插槽法術施法速度更少 <white>" + number(spec.lessCastSpeed()) + "%</white>；不能插有冷卻的法術");
        } else {
            lines.add("<gray>開著時，" + spec.kind().condition() + "獲得能量；能量到插槽法術的上限（基礎施法時間每 0.1 秒 10 點）就觸發它一次");
            if (spec.lessDamage() > 0) {
                lines.add("<gray>插槽法術更少 <white>" + number(spec.lessDamage()) + "%</white> 傷害");
            }
            if (spec.moreDamage() > 0) {
                lines.add("<gray>插槽法術更多 <white>" + number(spec.moreDamage()) + "%</white> 傷害");
            }
        }
        if (position < 0) {
            return lines;
        }
        final String problem = socketProblem(state, position);
        if (problem != null) {
            lines.add("<red>" + ItemRenderer.escape(problem));
            return lines;
        }
        final Socket socket = socket(state, position);
        if (socket != null) {
            if (spec.totem()) {
                lines.add("<gray>插槽：<white>" + ItemRenderer.escape(socket.spell().name()));
                // 2026-10-21：圖騰替你放插槽法術的速度（以前只寫放圖騰的速度，看不出圖騰放得多快）
                final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
                final SkillBuild metaBuild = service.build(state, sheet, meta, socket.metaEntry()); // 跟真的放的時候一樣用實際等級（含裝備加的）
                final SkillBuild build = totemBuild(state, sheet, socket, metaBuild.level(), spec.charges(), 0);
                if (build.deploy() != null && build.deploy().interval() > 0) {
                    final double rate = 20.0 / SkillDeployables.totemTicks(build.deploy().interval(), SkillDeployables.totemSpeed(build, sheet, false));
                    lines.add(String.format(Locale.ROOT, "<gray>圖騰施放%s：每根每秒 <white>%.2f</white> 次（最多 %d 根，合計 <white>%.2f</white> 次）",
                            ItemRenderer.escape(socket.spell().name()), rate, build.totems(), rate * build.totems()));
                }
                if (build.deploy() != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>圖騰持續 <white>%s</white> 秒", number(build.durationTicks(build.deploy().duration()) / 20.0)));
                }
                lines.add("<dark_gray>第 2～5 孔的輔助都套在插槽法術上；要圖騰的（疾樁、繁置、樁怒…）、加施法速度與持續時間的也套到圖騰本身");
            } else {
                lines.add(String.format(Locale.ROOT, "<gray>插槽：<white>%s</white>（能量 %s／%s）", ItemRenderer.escape(socket.spell().name()),
                        number(Math.floor(energyOf(player.getUniqueId(), meta.id(), socket.spell().id()))), number(MetaSpec.maxEnergy(socket.spell()))));
            }
        }
        return lines;
    }

    private static String number(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
