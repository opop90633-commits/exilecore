package tw.exilecore.skill;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.character.Gear;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.Rarity;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 2026-11-12 照站長「自動量技能（B），第一版只量單體」：{@code /ec balance bench [秒數] [篩選]}，管理員站在訓練假人旁邊打這個指令，
 * 系統一個一個換技能、換標準武器，自動對最近的那隻假人放滿 N 秒，量真實的每秒傷害（只算那一隻假人，範圍打到旁邊的假人不算），
 * 跑完換回原本的技能和武器，報表寫進 plugins/ExileCore/balance/bench-日期時間.md 與 .json。
 * <ul>
 *   <li>武器：技能要什麼武器就生成那種武器裡掉落等級最高（不超過角色等級）的<b>普通</b>基底（沒有詞綴）；要盾的副手配普通的盾；
 *       不用武器的法術、召喚空手；武器賦予的技能（擲矛、法杖的法術）裝賦予它的那種基底。其他裝備照你身上的不動。
 *       測試武器不看等級、能力值需求，技能寶石不看能力值需求（{@link PlayerState#setBenchFree}）。</li>
 *   <li>技能：等級 {@link #GEM_LEVEL}、不掛輔助、綁在左鍵，系統每 tick 按著左鍵對準假人（等於玩家按住左鍵）。
 *       放開出手的蓄力技能蓄滿（有完美時機的抓完美時機）就放開再蓄；召喚技能先放 {@link #SUMMON_WARMUP} tick 叫出召喚物才開始算。
 *       魔力、生命每 tick 補滿（只比傷害，不比續航）。</li>
 *   <li>量法：固定時間窗（N 秒）裡打到那隻假人的總傷害 ÷ N（含流血、點燃、中毒的跳）；冷卻長的技能照實算（窗內放幾次就幾次）。
 *       一般的打靶紀錄、角色的假人紀錄、輸出紀錄都不記（TrainingService.HitTap）。</li>
 *   <li>每個技能之間：清掉你的技能狀態（冷卻、召喚物、地面效果、插著的矛…，SkillService.forget）、假人恢復原狀、你站回原位。</li>
 *   <li>安全：量測中存檔（自動存、下線、關伺服器）存的都是原本的技能和武器（CharacterService.SaveSwap）；
 *       下線、換角色、離開練習場、死掉、換世界都會停（還在線就換回來）。{@code /ec balance bench stop} 中途停。</li>
 * </ul>
 * 報表照武器分組，同組裡低於中位數 {@link #WEAK_SHARE} 的標「偏弱」，0 傷害的標「量不到」（多半是要條件、要別的技能配合，要人工看）；
 * 也列技能面板的估計值和實測 ÷ 估計，拿來找估計不準的技能。
 */
public final class SkillBench {

    static final int GEM_LEVEL = 20;
    static final int DEFAULT_SECONDS = 10;
    static final int MIN_SECONDS = 4;
    static final int MAX_SECONDS = 30;
    /** 換好技能、武器後等幾 tick 才開始按（屬性表、賦予技能、快捷列都更新好）。 */
    static final int SETUP_TICKS = 10;
    /** 召喚技能先放幾 tick 才開始算。 */
    static final int SUMMON_WARMUP = 60;
    /** 時間窗結束後等幾 tick 再清場（最後放出去的那一發不會打到下一個技能的時間裡）。 */
    static final int TAIL_TICKS = 10;
    /** 站在離假人幾格的地方。 */
    static final double STAND_DISTANCE = 3.0;
    /** 位移技能把你帶離原位超過這麼遠（而且沒在引導）就拉回來。 */
    static final double DRIFT = 8.0;
    /** 蓄力最多蓄幾 tick 就放開。 */
    static final long MAX_HOLD = 80;
    /** 同組中位數的幾成以下算偏弱。 */
    static final double WEAK_SHARE = 0.6;
    /** 一輪最多容許幾個技能出錯（超過就停，免得每個都出錯一直刷主控台）。 */
    static final int MAX_ERRORS = 20;
    /** 2026-11-12：有引信的技能停手後最多再記幾 tick（5 秒）。 */
    static final int MAX_FUSE_TAIL = 100;

    /** 武器的分組順序（基底的標籤 → 組名）。 */
    private static final Map<String, String> FAMILIES = new LinkedHashMap<>();

    static {
        FAMILIES.put(Tags.BOW, "弓");
        FAMILIES.put(Tags.CROSSBOW, "弩");
        FAMILIES.put(Tags.WAND, "法杖");
        FAMILIES.put(Tags.STAFF, "長杖");
        FAMILIES.put(Tags.QUARTERSTAFF, "長棍");
        FAMILIES.put(Tags.SPEAR, "矛");
        FAMILIES.put(Tags.TALISMAN, "獸魂符");
        FAMILIES.put(Tags.SWORD, "劍");
        FAMILIES.put(Tags.AXE, "斧");
        FAMILIES.put(Tags.MACE, "錘");
        FAMILIES.put(Tags.DAGGER, "匕首");
        FAMILIES.put(Tags.CLAW, "爪");
        FAMILIES.put("flail", "鏈錘");
        FAMILIES.put("sceptre", "權杖");
    }

    /** 一個技能要怎麼量：主手、副手的基底（null = 空手），分組名；量不了的寫 problem。 */
    record Plan(SkillDefinition definition, ItemBase main, ItemBase off, String group, String problem, boolean direct, String companion,
                String driver, Hold hold) {
        Plan(final SkillDefinition definition, final ItemBase main, final ItemBase off, final String group, final String problem) {
            this(definition, main, off, group, problem, false, null, null, null);
        }

        Plan(final SkillDefinition definition, final ItemBase main, final ItemBase off, final String group, final String problem,
             final boolean direct) {
            this(definition, main, off, group, problem, direct, null, null, null);
        }

        Plan withCompanion(final String id) {
            return new Plan(definition, main, off, group, problem, direct, id, driver, hold);
        }

        Plan withMain(final ItemBase weapon) {
            return new Plan(definition, weapon, off, group, problem, direct, companion, driver, hold);
        }

        /** 2026-11-13：要別的技能帶動的（召喚焚魂、祖靈現身）：左鍵放 driver，這個技能綁潛行＋右鍵，只算召喚物打的。 */
        Plan withDriver(final String id, final ItemBase weapon) {
            return new Plan(definition, weapon, off, group, problem, direct, companion, id, hold);
        }

        Plan withHold(final Hold value) {
            return new Plan(definition, main, off, group, problem, direct, companion, driver, value);
        }
    }

    /**
     * 2026-11-13 照站長「引導跟蓄力技能要如何處理」：放開出手的蓄力技能每一種放法各量一次，報表留最好的那一種（其他寫在備註）。
     * 快放 = 最短就放；一半 = 蓄到一半的段數；蓄滿 = 全部段數；完美 = 抓完美時機。引導（按住持續放）照舊一直按住。
     */
    enum Hold {
        QUICK("快放"), HALF("蓄一半"), FULL("蓄滿"), PERFECT("完美時機");

        final String label;

        Hold(final String label) {
            this.label = label;
        }
    }

    /** 這個蓄力技能要量哪幾種放法（不是放開出手的回傳空的）。純函式。 */
    static List<Hold> holds(final ChargeSpec spec) {
        // 2026-11-13 照站長選 A（蓄力自己蓄到最好的時候出手）：不用再分放法量，一直按著就是遊戲自己挑的放法；
        //   這次之前（同一天稍早）快放、蓄一半、蓄滿、完美時機各量一次
        return List.of();
    }

    /**
     * 2026-11-13 照站長「有些技能需要前置，像昇華的指令技能、藥劑要魔力藥劑充能」：前置。
     * <ul>
     *   <li>巨靈的指令（珂芮絲的…、娜瑟菈的…、羅贊的…）：同時把那位巨靈放在潛行＋右鍵開著（先叫出來 3 秒）。</li>
     *   <li>藥劑（放血、爆炸、碎裂、雷爆、蝕酸）：每 tick 補滿魔力藥劑、生命藥劑的充能（你身上要帶著魔力藥劑）。</li>
     *   <li>要屍體的（骸爆、骸棘環、屍焰爆、腐屍爆、穢陣、喚屍…）：每秒在假人旁邊放一具同等級普通怪的屍體。</li>
     *   <li>幻形兵刃：主手給一把單手近戰武器（它複製主手武器）。</li>
     *   <li>可以下指令的召喚物（骷髏、砲手、巨靈、鴉群）：開著之後每秒按一次（下指令）。</li>
     * </ul>
     * 量不了的直接標原因（{@link #UNMEASURABLE}）。
     */
    static String companionOf(final String skillId) {
        if (skillId.startsWith("sand_spirit_")) {
            return MinionAbilities.KELARI;
        }
        if (skillId.startsWith("mirage_spirit_")) {
            return MinionAbilities.NAVIRA;
        }
        if (skillId.startsWith("blade_spirit_")) {
            return MinionAbilities.RUZHAN;
        }
        return null;
    }

    /** 2026-11-13：自動量不了的（不攻擊），報表直接寫原因。 */
    static final Map<String, String> UNMEASURABLE = Map.ofEntries(
            Map.entry("summon_bone_priest", "不攻擊（治療、復活召喚物）"),
            // 2026-11-13 照站長「一個一個查量到 0 的原因」：要別的技能配合才有傷害（燼種、雷流場、土壁擊、霜壁、瘴域後來改成自己也能觸發，拿掉）的，自己放量不出來，直接標原因
            Map.entry("thunder_escape", "要敵人血量低到可撲殺才瞬移爆炸（假人一直滿血），平常只留感電地面"),
            Map.entry("gnawing_bite", "要吃屍體或可撲殺的敵人才放得出來（主要是回復、拿銳星）"),
            Map.entry("hero_spirit_totem", "輔助型圖騰：用插在裡面的錘子技能打（要插別的技能）"),
            Map.entry("shield_brace", "架盾：要先擋下敵人的攻擊才盾擊（假人不打你）"),
            Map.entry("final_mirage_spirit", "巨靈本身不打：自己的指令是防禦泡泡；傷害在娜瑟菈的指令技能（另外量）"),
            Map.entry("foul_sand_spirit", "巨靈本身不打：只在指令（珂芮絲的詭計、裁決、殘酷）時出手，那些另外量"));
    /**
     * 2026-11-13 照站長「召喚焚魂、祖靈現身有辦法處理嗎」：要別的技能帶動的——左鍵一直放帶動的技能，這個技能綁在潛行＋右鍵，
     * 只算召喚物打的（帶動的技能自己的傷害不算）。召喚焚魂：熾星彈（火焰範圍投射物法術）；祖靈現身：英靈圖騰（放下圖騰）。
     */
    static final Map<String, String> DRIVERS = Map.of(
            "summon_burning_souls", "blazing_star_shell",
            // 2026-11-13 照站長「祖靈現身如果以任意的圖騰，再扣掉圖騰傷害」：改用崩震圖騰（自己就能放）帶動，只算先祖之靈（召喚物）打的；
            //   這次之前用英靈圖騰，它要插錘子技能才會放下去，一直量不到
            "forebear_manifest", "collapse_quake_totem",
            // 2026-11-13 照站長「查量到 0 的原因」：炎刃．羅贊要你用造成傷害的技能，牠才瞬移過去狂熱斬擊（自己按只放烙印）
            "flame_blade_spirit", "blazing_star_shell");
    /** 2026-11-13：每幾 tick 在假人旁邊放一具屍體。 */
    static final int CORPSE_EVERY = 20;
    /** 2026-11-13：可以下指令的召喚物每幾 tick 按一次。 */
    static final int COMMAND_EVERY = 20;

    /** 一個技能的結果。 */
    record Result(String id, String name, String group, String weapon, double dps, double total, int hits, double estimate,
                  List<Map.Entry<String, Double>> sources, String note) {
    }

    private static final class Run {
        final UUID player;
        final long characterId;
        final UUID dummy;
        final String dummyName;
        final Location stand;
        /** 2026-11-13：這個技能站的位置（腳下範圍、面前的牆、小範圍砸地站近一點；其他照 {@link #stand}）。 */
        Location spot;
        final int seconds;
        final String filter;
        final List<Plan> plans;
        final SkillLoadout originalSkills;
        final ItemStack originalMain;
        final ItemStack originalOff;
        final Consumer<String> report;
        final List<Result> results = new ArrayList<>();
        final long startedAt = System.currentTimeMillis();
        int index = -1;
        Phase phase = Phase.SETUP;
        long phaseTick;
        long windowStart;
        boolean recording;
        double total;
        int hits;
        final Map<String, Double> sources = new HashMap<>();
        long releaseUntil;
        double estimate;
        int errors;
        /** 2026-11-13：連量的每次結果（技能代號 → 每次的每秒傷害、總傷害、打中幾下）。 */
        final Map<String, List<double[]>> repeats = new HashMap<>();
        /** 2026-11-13：蓄力的每種放法量到的數字（技能代號 → 「蓄滿 1,234、快放 567」）、目前最好的放法。 */
        final Map<String, String> holdNotes = new HashMap<>();
        final Map<String, String> holdBest = new HashMap<>();
        /** 2026-11-12：常駐召喚物開過了沒（這個技能）。 */
        boolean toggled;
        /** 2026-11-12：時間窗結束後還要再記幾 tick（有引信的：最後丟出去的還沒炸）；0 = 已經記完。 */
        int pendingTicks;

        Run(final UUID player, final long characterId, final LivingEntity dummy, final String dummyName, final Location stand,
            final int seconds, final String filter, final List<Plan> plans, final PlayerState state, final Consumer<String> report) {
            this.player = player;
            this.characterId = characterId;
            this.dummy = dummy.getUniqueId();
            this.dummyName = dummyName;
            this.stand = stand;
            this.spot = stand;
            this.seconds = seconds;
            this.filter = filter;
            this.plans = plans;
            this.originalSkills = state.skills().copy();
            this.originalMain = state.jewellery(Gear.MAIN);
            this.originalOff = state.jewellery(Gear.OFF);
            this.report = report;
        }

        Plan plan() {
            return plans.get(index);
        }
    }

    private enum Phase { SETUP, PRESS, TAIL }

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, Run> runs = new HashMap<>();
    private BukkitTask task;
    private boolean swapRegistered;

    SkillBench(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public static int defaultSeconds() {
        return DEFAULT_SECONDS;
    }

    public static int minSeconds() {
        return MIN_SECONDS;
    }

    public static int maxSeconds() {
        return MAX_SECONDS;
    }

    public boolean running(final UUID player) {
        return runs.containsKey(player);
    }

    // ---- 開始 ----

    /** 開始量；不行回傳原因。report 收進度與結果的訊息（MiniMessage）。 */
    public String start(final Player player, final int seconds, final String rawFilter, final Consumer<String> report) {
        // 2026-11-13 照站長「有辦法選擇只測試 1 次嗎」：篩選裡寫「1次」（或「2次」…，最多 5 次）就照那個次數量，其他照預設 {@link #REPEATS} 次；
        //   可以跟別的篩選一起寫（「重測 1次」「弓 1次」）
        final int repeats = repeatsIn(rawFilter);
        final String filter = withoutRepeats(rawFilter);
        if (runs.containsKey(player.getUniqueId())) {
            return "已經在量了（/ec balance bench stop 停止）";
        }
        if (plugin.training() == null || plugin.items() == null || plugin.characters() == null) {
            return "訓練假人、物品或角色模組沒有啟用";
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter() || state.character() == null) {
            return "要先選一個角色";
        }
        if (!service.eligible(player)) {
            return "要在生存模式、活著才能量（技能要能放）";
        }
        if (!plugin.training().inArena(player.getLocation())) {
            return "要站在營地的練習場（訓練假人旁邊）";
        }
        final LivingEntity dummy = nearestDummy(player);
        if (dummy == null) {
            return "附近 12 格內沒有你打得到的訓練假人";
        }
        // 2026-11-13 照站長「只單獨量那些量不到的與低於中位數的」：篩選寫「重測」（量不到＋低於全部技能中位數）、「量不到」、
        //   「低於中位數」：看上一份報表（balance/ 裡最新的 bench-*.json）挑技能
        final java.util.Set<String> retry = RETRY_WORDS.contains(filter == null ? "" : filter.trim()) ? retryIds(filter.trim()) : null;
        if (retry != null && retry.isEmpty()) {
            return "找不到上一份報表，或上一份報表裡沒有「" + filter.trim() + "」的技能";
        }
        final List<Plan> plans = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        for (final SkillDefinition definition : sortedSkills()) {
            final Plan plan = plan(definition, state.level());
            if (plan == null || (retry != null ? !retry.contains(definition.id()) : !matches(plan, filter))) {
                continue;
            }
            if (plan.problem() != null) {
                skipped.add(definition.name());
            }
            final List<Hold> holds = plan.problem() == null && plan.driver() == null ? holds(service.config().charge(definition.id())) : List.of();
            if (holds.isEmpty()) {
                // 2026-11-13 照站長「同一個技能自動量 3 次取平均」（bench 1729：召霆矢倍率沒動、差了 4 成，10 秒一次誤差太大）：
                //   量得了的連量 {@link #REPEATS} 次，報表那一列是平均（備註寫每次的數字）；量不了的只記一次
                for (int i = 0; i < (plan.problem() == null ? repeats : 1); i++) {
                    plans.add(plan);
                }
            } else {
                for (final Hold hold : holds) {
                    plans.add(plan.withHold(hold)); // 2026-11-13：每種放法各量一次
                }
            }
        }
        if (plans.isEmpty()) {
            return filter == null ? "沒有可以量的技能" : "沒有符合「" + filter + "」的技能（可以寫技能名字、代號，或組名：弓、法杖、召喚、法術…）";
        }
        final Location stand = standSpot(player.getLocation(), dummy.getLocation());
        final Run run = new Run(player.getUniqueId(), state.character().id(), dummy, plugin.training().dummyName(dummy), stand,
                Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds)), filter, plans, state, report);
        registerSwap();
        runs.put(player.getUniqueId(), run);
        plugin.training().neutral(dummy, true); // 2026-11-12 照站長：首領假人量測時沒有防禦（護甲、閃避、抗性 0）
        plugin.training().tap(player.getUniqueId(), new tw.exilecore.training.TrainingService.HitTap() {
            @Override
            public void hit(final LivingEntity target, final double damage, final boolean crit, final String source) {
                record(run, target, damage, source);
            }

            @Override
            public void dot(final LivingEntity target, final double damage) {
                record(run, target, damage, "流血、點燃、中毒");
            }
        });
        state.setBenchFree(true);
        state.setBenchBlank(1); // 2026-11-13 照站長「改成白板量」
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, (Runnable) this::tick, 1L, 1L);
        }
        final double minutes = plans.size() * (run.seconds * 20.0 + SETUP_TICKS + TAIL_TICKS) / 20.0 / 60.0;
        report.accept(String.format(Locale.ROOT, "<gray>開始量技能：<white>%d</white> 個，每個 %d 秒，打 <white>%s</white>，大約 <white>%.0f</white> 分鐘"
                        + "（召喚另外先放 %d 秒）。<dark_gray>量測中不要操作；/ec balance bench stop 停止，會換回原本的技能和武器",
                plans.size(), run.seconds, ItemRenderer.escape(run.dummyName), Math.ceil(minutes), SUMMON_WARMUP / 20));
        if (!skipped.isEmpty()) {
            report.accept("<dark_gray>找不到武器、量不了的（報表裡列為「量不到」）：" + ItemRenderer.escape(String.join("、", skipped)));
        }
        return null;
    }

    /** 存檔時換回原本的技能和武器（只登記一次；CharacterService.SaveSwap）。 */
    private void registerSwap() {
        if (swapRegistered) {
            return;
        }
        swapRegistered = true;
        plugin.characters().addSaveSwap(player -> {
            final Run run = runs.get(player.getUniqueId());
            if (run == null) {
                return null;
            }
            final PlayerState state = plugin.players().get(player);
            if (state.character() == null || state.character().id() != run.characterId) {
                return null;
            }
            final SkillLoadout testSkills = state.skills();
            final ItemStack testMain = state.jewellery(Gear.MAIN);
            final ItemStack testOff = state.jewellery(Gear.OFF);
            state.setSkills(run.originalSkills.copy());
            state.setJewellery(Gear.MAIN, run.originalMain);
            state.setJewellery(Gear.OFF, run.originalOff);
            return () -> {
                state.setSkills(testSkills);
                state.setJewellery(Gear.MAIN, testMain);
                state.setJewellery(Gear.OFF, testOff);
            };
        });
    }

    private LivingEntity nearestDummy(final Player player) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final Entity entity : player.getNearbyEntities(12, 6, 12)) {
            if (entity instanceof LivingEntity living && plugin.training().isDummy(living) && plugin.training().canTarget(living, player)) {
                final double distance = living.getLocation().distanceSquared(player.getLocation());
                if (distance < bestDistance) {
                    best = living;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    /** 2026-11-13 照站長「0 的原因可能跟技能的落點有關係」：靠近站的距離（假人中心到你腳下）。 */
    static final double CLOSE_DISTANCE = 1.4;

    /**
     * 2026-11-13：要站近一點的技能——在自己腳下的範圍（骸棘環、血祭壇、無面霧帳）、面前 2 格的牆（焰幕、雷獄迷陣）、
     * 範圍小的砸地（地動、地刺崩裂）。這次之前一律站 {@link #STAND_DISTANCE} 格，假人在範圍外量到 0 或很低。
     * 只放這幾個：其他砸地、地面效果站 3 格量起來正常，平衡倍率也照那樣算好了。
     */
    static final java.util.Set<String> CLOSE_SKILLS = java.util.Set.of(
            "bone_thorn_ring", "blood_sacrifice_dais", "faceless_mist", "flame_curtain", "thunder_prison_maze", "ground_tremor",
            "earth_spike_eruption", "miasma_zone");

    /**
     * 2026-11-13 照站長「0 的招式是不是該丟在假人腳下，而不是直射假人」：放在「準心指的地上」的（圖騰、準心落點的震波、箭雨、信號彈）
     * 找落點時只看地形、不看生物——平視假人的話那條線穿過假人落在後面最遠 8 格的地上，圖騰砸不到、震波炸不到。這幾個改成低頭看假人腳下。
     * 只放量到 0 或很低的這幾個（其他照平視量起來正常，平衡倍率也照那樣算好了）。
     */
    static final java.util.Set<String> FEET_AIM = java.util.Set.of(
            "collapse_quake_totem", "forebear_manifest", "sand_spirit_verdict", "covering_fire", "myriad_arrow_fall", "nether_puppet");

    /** 2026-11-13：站在 spot、低頭看假人腳下（眼睛高度 1.62）。純函式。 */
    static Location lookAtFeet(final Location spot, final Location dummy) {
        final Location look = spot.clone();
        final Vector eye = spot.toVector().add(new Vector(0, 1.62, 0));
        look.setDirection(dummy.toVector().add(new Vector(0, 0.1, 0)).subtract(eye));
        return look;
    }

    /**
     * 2026-11-13 照站長「冷卻超過 10 秒的是不是要拉長測試時間」：冷卻比時間窗長的，時間窗拉到冷卻的 2 倍（剛好放 2 次，平均起來
     * 跟一直打一樣，掩護火力 10 秒 → 20 秒、珂芮絲的裁決 12 秒 → 24 秒）；其他照設定的秒數。這次之前一律 N 秒，一次都不一定放得完。純函式。
     */
    static double windowSeconds(final int seconds, final double cooldown) {
        return cooldown > seconds ? cooldown * 2 : seconds;
    }

    static boolean close(final SkillDefinition definition) {
        return CLOSE_SKILLS.contains(definition.id());
    }

    /** 2026-11-13：照 base（原本站的位置）的方向，離假人 close ? {@link #CLOSE_DISTANCE} : 原本的距離。 */
    static Location standFor(final Location base, final Location dummy, final boolean close) {
        if (!close) {
            return base;
        }
        final Vector away = base.toVector().subtract(dummy.toVector()).setY(0);
        if (away.lengthSquared() < 1e-6) {
            away.setX(1);
        }
        final Location spot = dummy.clone().add(away.normalize().multiply(CLOSE_DISTANCE));
        spot.setY(base.getY());
        spot.setDirection(dummy.toVector().subtract(spot.toVector()));
        spot.setPitch(Math.max(-30f, Math.min(30f, spot.getPitch())));
        return spot;
    }

    /**
     * 2026-11-13 照站長「低傷害的可能跟落點有關」：放下去會留著的東西（地面效果、牆、圖騰）有上限的，還在就不再按——
     * 再放會把舊的拆掉，間隔還沒到就沒打（積雷雲、烈陽珠、骸棘環、冥偶、崩震圖騰…量到很低或 0）。
     */
    private boolean waitPlaced(final Player player, final SkillDefinition definition) {
        if (!WAIT_PLACED.contains(definition.id())) {
            return false;
        }
        final UUID owner = player.getUniqueId();
        final SkillParams.Zone zone = definition.mechanic() == Mechanic.ZONE ? definition.params().zone() : null;
        if (zone != null && zone.max() > 0 && service.zones().count(owner, definition.id()) >= zone.max()) {
            return true;
        }
        final DeploySpec deploy = definition.deploy();
        return deploy != null && deploy.max() > 0 && service.deployables().count(owner, definition.id()) >= deploy.max();
    }

    /**
     * 2026-11-13：要等放下去的消失才再放的技能。只放量到很低或 0 的這些；地火柱、雷雲、弩台、炎脈裂地這些一直按量起來正常、
     * 平衡倍率也照一直按量的算好了，不動（改了數字會跟著變）。
     */
    static final java.util.Set<String> WAIT_PLACED = java.util.Set.of(
            "scorching_sun_pearl", "nether_puppet", "gathered_thundercloud", "wild_whirlwind", "bone_thorn_ring", "whale_gulp_vortex",
            "blood_sacrifice_dais", "faceless_mist", "collapse_quake_totem", "flame_curtain", "thunder_prison_maze",
            "earth_wall_strike", "miasma_zone"); // 2026-11-13：自己觸發的土牆時間到崩碎、毒花自己掛毒苞，要放著等

    /**
     * 2026-11-13 照站長「確認」：要用掉敵人身上的東西才會打出真正傷害的，量的時候先幫假人準備好——噬異爆、稜光裂要異常（給 1% 的感電，
     * 幾乎不增傷）、蝕咒裂要你的詛咒（不增傷的空詛咒）。這次之前假人身上什麼都沒有，量到的是「沒有異常／詛咒時」的備用效果。
     */
    static final java.util.Set<String> PRIME_SHOCK = java.util.Set.of("anomaly_devour_blast", "facet_light_rift");
    static final java.util.Set<String> PRIME_CURSE = java.util.Set.of("erosion_curse_rift");

    private void prime(final Player player, final LivingEntity dummy, final String id, final long now) {
        if (PRIME_SHOCK.contains(id) && plugin.ailments() != null
                && !plugin.ailments().has(dummy, tw.exilecore.ailment.AilmentType.SHOCK)) {
            plugin.ailments().shockMinion(dummy, 0.01, 0, 1);
        }
        if (PRIME_CURSE.contains(id) && plugin.combat() != null) {
            final boolean cursed = plugin.combat().debuffs().active(dummy.getUniqueId(), now).stream()
                    .anyMatch(debuff -> debuff.id().contains("_curse") && player.getUniqueId().equals(debuff.source()));
            if (!cursed) {
                plugin.combat().debuffs().applyCurse(dummy.getUniqueId(),
                        new tw.exilecore.combat.Debuffs.Debuff("bench_curse", player.getUniqueId(), now + 100, 0, false), "bench_curse");
            }
        }
    }

    /** 2026-11-13：放一陣、停一陣的技能（tick）：星火連珠（停手 1.3 秒才射出去）、萬箭齊落（再放會把上一場箭雨收掉）。{放幾 tick, 停幾 tick} */
    static final Map<String, int[]> PULSE = Map.of(
            "starfire_bead_chain", new int[] {60, 40},
            "myriad_arrow_fall", new int[] {6, 60},
            // 2026-11-13：自己觸發的——燼種 3 秒自己爆（再種會換掉舊的）、雷流場施放時掛的電球 6 秒
            "cinder_sprout", new int[] {4, 66},
            "thunder_current_zone", new int[] {4, 116});

    /** 站的位置：假人往你現在的方向 {@link #STAND_DISTANCE} 格（高度照你現在的），面對假人。 */
    static Location standSpot(final Location player, final Location dummy) {
        final Vector away = player.toVector().subtract(dummy.toVector()).setY(0);
        if (away.lengthSquared() < 1e-6) {
            away.setX(1);
        }
        away.normalize().multiply(STAND_DISTANCE);
        final Location spot = dummy.clone().add(away);
        spot.setY(player.getY());
        spot.setDirection(dummy.toVector().subtract(spot.toVector()));
        spot.setPitch(Math.max(-30f, Math.min(30f, spot.getPitch())));
        return spot;
    }

    /** 要量的技能：會造成傷害的、召喚；普攻、光環、主要寶石（觸發別的技能）、昇華賦予的不量。照代號排。 */
    private List<SkillDefinition> sortedSkills() {
        final List<SkillDefinition> list = new ArrayList<>();
        for (final SkillDefinition definition : service.config().all()) {
            if (measurable(definition)) {
                list.add(definition);
            }
        }
        list.sort(Comparator.comparing(SkillDefinition::id));
        return list;
    }

    static boolean measurable(final SkillDefinition definition) {
        // 2026-11-12 照站長「能測的技能只有 200 個嗎、有包含昇華或裝備上的技能嗎」：昇華賦予的也量（直接放進面板）；
        //   常駐召喚物（骸骨兵、灰狼隨…寫成 mechanic: aura 保留精魂）也量。這次之前這兩種都跳過
        if (definition == null || definition.hidden()) {
            return false;
        }
        if (definition.mechanic() == Mechanic.AURA && definition.summon() == null || definition.mechanic() == Mechanic.META) {
            return false;
        }
        return definition.dealsDamage() || definition.summon() != null;
    }

    /** 篩選：技能名字、代號、組名有包含這段字（null = 全部）。 */
    /** 2026-11-13：挑上一份報表的技能的篩選字。 */
    static final java.util.Set<String> RETRY_WORDS = java.util.Set.of("重測", "量不到", "低於中位數");

    /** 2026-11-13：上一份報表（最新的 bench-*.json）裡要重測的技能代號；找不到報表回傳空的。 */
    private java.util.Set<String> retryIds(final String word) {
        final Path folder = plugin.dataFolder().resolve("balance");
        try (java.util.stream.Stream<Path> files = Files.exists(folder) ? Files.list(folder) : java.util.stream.Stream.empty()) {
            final Path latest = files.filter(path -> path.getFileName().toString().matches("bench-.*\\.json"))
                    .max(Comparator.comparing(path -> path.getFileName().toString())).orElse(null);
            if (latest == null) {
                return java.util.Set.of();
            }
            final JsonObject root = com.google.gson.JsonParser.parseString(Files.readString(latest, StandardCharsets.UTF_8)).getAsJsonObject();
            final Map<String, Double> dps = new LinkedHashMap<>();
            for (final com.google.gson.JsonElement element : root.getAsJsonArray("skills")) {
                final JsonObject row = element.getAsJsonObject();
                dps.put(row.get("id").getAsString(), row.get("dps").getAsDouble());
            }
            // 2026-11-13（bench 1139：上一份是重測的話，裡面全是低的，「低於中位數」越篩越少）：改成跟目標比——
            //   低於目標（全部技能中位數的 16 倍 2,544，近戰 ×1.3）的 9 成；這次之前跟那一份報表自己的中位數比
            final Map<String, Double> targets = new LinkedHashMap<>();
            for (final String id : dps.keySet()) {
                final SkillDefinition definition = service.config().skill(id);
                targets.put(id, TARGET * (definition != null && definition.tags().contains("melee") ? MELEE_TARGET : 1.0));
            }
            return retryIds(word, dps, targets);
        } catch (final IOException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("自動量技能：讀上一份報表失敗", e);
            return java.util.Set.of();
        }
    }

    /** 2026-11-13：平衡的目標（全部技能中位數 159 的 16 倍）、近戰再乘的倍率、低於目標幾成算「低」。 */
    static final double TARGET = 2544;
    static final double MELEE_TARGET = 1.3;
    static final double LOW_SHARE = 0.9;

    /** 2026-11-13：純函式版——量不到 = 0；低於中位數 = 低於目標的 9 成（targets 沒寫的照 {@link #TARGET}）；重測 = 兩種都要。 */
    static java.util.Set<String> retryIds(final String word, final Map<String, Double> dps, final Map<String, Double> targets) {
        final java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (final Map.Entry<String, Double> entry : dps.entrySet()) {
            final boolean zero = entry.getValue() <= 0;
            final boolean low = !zero && entry.getValue() < targets.getOrDefault(entry.getKey(), TARGET) * LOW_SHARE;
            if ("量不到".equals(word) ? zero : "低於中位數".equals(word) ? low : zero || low) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    static boolean matches(final Plan plan, final String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        final String needle = filter.trim().toLowerCase(Locale.ROOT);
        return plan.definition().id().toLowerCase(Locale.ROOT).contains(needle) || plan.definition().name().contains(needle)
                || plan.group().contains(needle);
    }

    /** 這個技能用什麼武器量。 */
    private Plan plan(final SkillDefinition definition, final int level) {
        final Plan plan = basePlan(definition, level);
        if (plan == null || plan.problem() != null) {
            return plan;
        }
        final String why = UNMEASURABLE.get(definition.id());
        if (why != null) {
            return new Plan(definition, plan.main(), plan.off(), plan.group(), why, plan.direct());
        }
        if ("phantom_blade".equals(definition.id()) && plan.main() == null) {
            final ItemBase melee = pickOneHandMelee(plugin.items().repository().basesDroppableAt(Math.max(1, level)));
            return melee == null ? new Plan(definition, null, null, plan.group(), "找不到單手近戰武器", plan.direct()) : plan.withMain(melee);
        }
        final String driverId = DRIVERS.get(definition.id());
        if (driverId != null) {
            final SkillDefinition driver = service.config().skill(driverId);
            if (driver == null) {
                return new Plan(definition, null, null, plan.group(), "帶動它的技能（" + driverId + "）不在了", plan.direct());
            }
            ItemBase weapon = null;
            if (!driver.weapons().isEmpty()) {
                weapon = pickWeapon(plugin.items().repository().basesDroppableAt(Math.max(1, level)), driver, false);
                if (weapon == null) {
                    return new Plan(definition, null, null, plan.group(), "找不到" + driver.name() + "能用的武器", plan.direct());
                }
            }
            return plan.withDriver(driverId, weapon);
        }
        final String companion = companionOf(definition.id());
        return companion == null ? plan : plan.withCompanion(companion);
    }

    /** 2026-11-13（幻形兵刃）：有傷害的單手近戰武器裡掉落等級最高的。 */
    static ItemBase pickOneHandMelee(final Collection<ItemBase> bases) {
        ItemBase best = null;
        for (final ItemBase base : bases) {
            if (base.slot() == ItemSlot.MAIN_HAND && base.isWeapon() && base.tags().contains(Tags.MELEE) && !base.tags().contains(Tags.TWO_HAND)
                    && base.damage() != null && !base.damage().isEmpty()
                    && (best == null || base.dropLevel() > best.dropLevel() || base.dropLevel() == best.dropLevel() && base.id().compareTo(best.id()) < 0)) {
                best = base;
            }
        }
        return best;
    }

    private Plan basePlan(final SkillDefinition definition, final int level) {
        final tw.exilecore.item.ItemRepository repository = plugin.items().repository();
        // 2026-11-12 照站長第一輪的結果：架盾是盾牌賦予（PlayerStateService.shieldSkill），副手要拿盾
        final boolean shield = SkillGear.requiresShield(definition)
                || definition.grantedOnly() && definition.mechanic() == Mechanic.GUARD;
        ItemBase main = null;
        boolean mapped = false;
        if (definition.grantedOnly()) {
            for (final ItemBase base : repository.bases()) {
                if (definition.id().equals(repository.grantedSkill(base.id())) && better(base, main, level)) {
                    main = base;
                }
            }
            mapped = main != null;
            // 2026-11-13 照站長「查量到 0 的原因」：傳奇裝備賦予、要武器的（死懼碎擊：手套；熔軀崩擊：傳奇獸符）也直接放進面板，
            //   照技能要的武器挑一把；這次之前只有不要武器的才直接放，要武器的裝普通基底等它出現，一直等不到（量不到）
            if ("ascendancy".equals(definition.source()) || !mapped && !shield) {
                // 2026-11-12：昇華、傳奇裝備賦予的（沒有對應的基底）直接放進面板量；要武器的照技能要的武器挑
                ItemBase weapon = null;
                if (!definition.weapons().isEmpty()) {
                    weapon = pickWeapon(repository.basesDroppableAt(Math.max(1, level)), definition, shield);
                    if (weapon == null) {
                        return new Plan(definition, null, null, "昇華".equals(directGroup(definition)) ? "昇華" : "裝備賦予",
                                "找不到能用的武器（" + definition.weaponText() + "）");
                    }
                }
                return new Plan(definition, weapon, shield ? pickShield(repository.basesDroppableAt(Math.max(1, level))) : null,
                        directGroup(definition), null, true);
            }
            // 2026-11-12：擲矛這種「每把矛都賦予」的不在基底對照表裡（程式裡給的）：照技能要的武器挑一把，裝上去就會出現
        }
        if (!mapped && !definition.weapons().isEmpty() && (main == null)) {
            main = pickWeapon(repository.basesDroppableAt(Math.max(1, level)), definition, shield);
            if (main == null) {
                return new Plan(definition, null, null, "其他", "找不到能用的武器（" + definition.weaponText() + "）");
            }
        }
        ItemBase off = null;
        if (shield) {
            off = pickShield(repository.basesDroppableAt(Math.max(1, level)));
            if (off == null) {
                return new Plan(definition, main, null, "盾", "找不到盾的基底");
            }
        }
        return new Plan(definition, main, off, group(definition, main, shield), null);
    }

    /** 賦予技能的基底挑哪個：掉落等級不超過角色等級的裡面最高的；都超過就挑最低的。純函式。 */
    static boolean better(final ItemBase candidate, final ItemBase current, final int level) {
        if (current == null) {
            return true;
        }
        final boolean fits = candidate.dropLevel() <= level;
        final boolean currentFits = current.dropLevel() <= level;
        if (fits != currentFits) {
            return fits;
        }
        return fits ? candidate.dropLevel() > current.dropLevel() : candidate.dropLevel() < current.dropLevel();
    }

    /** 能用這個技能的主手武器裡掉落等級最高的（同等級照代號）；要盾的只挑單手的。 */
    static ItemBase pickWeapon(final Collection<ItemBase> bases, final SkillDefinition definition, final boolean shield) {
        ItemBase best = null;
        for (final ItemBase base : bases) {
            // 2026-11-12 照站長第一輪的結果：沒有武器傷害的（權杖）不算——寫 [melee] 的攻擊挑到權杖會打不出傷害
            if (base.slot() != ItemSlot.MAIN_HAND || !base.isWeapon() || base.damage() == null || base.damage().isEmpty()
                    || !usable(definition, base.tags())
                    || shield && base.tags().contains(Tags.TWO_HAND)) {
                continue;
            }
            if (SkillGear.problem(definition, shield ? Set.of(SkillGear.SHIELD) : null, false, base.tags()) != null) {
                continue;
            }
            if (best == null || base.dropLevel() > best.dropLevel() || base.dropLevel() == best.dropLevel() && base.id().compareTo(best.id()) < 0) {
                best = base;
            }
        }
        return best;
    }

    /** usableWith 對盾技能一律回 true；這裡要盾以外的武器標籤真的對得上（只寫盾的技能：任何單手武器）。 */
    private static boolean usable(final SkillDefinition definition, final Set<String> tags) {
        boolean onlyShield = true;
        for (final String weapon : definition.weapons()) {
            if (SkillGear.SHIELD.equals(weapon)) {
                continue;
            }
            onlyShield = false;
            if (tags.contains(weapon) || Tags.MELEE.equals(weapon) && tags.contains(Tags.MELEE)) {
                return true;
            }
        }
        return onlyShield && tags.contains(Tags.MELEE);
    }

    private static ItemBase pickShield(final Collection<ItemBase> bases) {
        ItemBase best = null;
        for (final ItemBase base : bases) {
            if (base.slot() == ItemSlot.OFF_HAND && SkillGear.countsAsShield(base.tags())
                    && (best == null || base.dropLevel() > best.dropLevel() || base.dropLevel() == best.dropLevel() && base.id().compareTo(best.id()) < 0)) {
                best = base;
            }
        }
        return best;
    }

    /** 組名：召喚、法術（空手）、盾、武器種類。 */
    /** 2026-11-12：直接放進面板量的那幾個的組名：昇華賦予的寫「昇華」，其他（傳奇裝備賦予）寫「裝備賦予」。 */
    static String directGroup(final SkillDefinition definition) {
        return "ascendancy".equals(definition.source()) ? "昇華" : "裝備賦予";
    }

    static String group(final SkillDefinition definition, final ItemBase main, final boolean shield) {
        if (definition.summon() != null) {
            return "召喚";
        }
        if (shield) {
            return "盾";
        }
        if (main == null) {
            return "法術（空手）";
        }
        return family(main.tags());
    }

    static String family(final Set<String> tags) {
        for (final Map.Entry<String, String> entry : FAMILIES.entrySet()) {
            if (tags.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "其他";
    }

    // ---- 每 tick ----

    private void tick() {
        for (final Run run : List.copyOf(runs.values())) {
            try {
                tick(run);
            } catch (final RuntimeException e) {
                failed(run, e);
            }
        }
        if (runs.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick(final Run run) {
        final Player player = plugin.getServer().getPlayer(run.player);
        if (player == null) {
            drop(run); // 下線：存檔時已經換回原本的（SaveSwap），記憶體裡的狀態跟著下線丟掉
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (state.character() == null || state.character().id() != run.characterId) {
            state.setBenchFree(false);
            state.setBenchBlank(0);
            drop(run); // 換角色：舊角色存檔時已經換回原本的；不要把舊角色的東西塞進新角色
            run.report.accept("<red>換角色了，自動量技能停止（原本那個角色的技能和武器存檔時已經換回去）");
            return;
        }
        final Entity entity = plugin.getServer().getEntity(run.dummy);
        if (!(entity instanceof LivingEntity dummy) || !dummy.isValid() || !dummy.getWorld().equals(player.getWorld())) {
            finish(run, "假人不見了或你換了世界，停止");
            return;
        }
        if (service.eligible(player) && run.index >= 0 && run.index < run.plans.size()
                && run.plan().definition().tags().contains("movement")
                && player.getLocation().distanceSquared(run.spot) > DRIFT * DRIFT) {
            // 2026-11-13 照站長（bench 0636 在蠻衝停掉）：位移技能（蠻衝這種引導中一直往前衝的）衝出練習場不算離開，
            //   拉回原位繼續量；這次之前引導中不拉回，衝超過 16 格又在練習場外就整個停掉
            player.setFallDistance(0);
            player.teleport(run.spot);
        }
        if (!service.eligible(player) || !plugin.training().inArena(player.getLocation())
                && player.getLocation().distanceSquared(run.stand) > DRIFT * DRIFT * 4) {
            finish(run, "你離開練習場或不能放技能了，停止");
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        if (run.index < 0) {
            next(run, player, state, dummy, now);
            return;
        }
        switch (run.phase) {
            case SETUP -> {
                if (now - run.phaseTick < SETUP_TICKS) {
                    return;
                }
                final Plan plan = run.plan();
                if (plan.definition().grantedOnly() && !plan.direct() && !bindGranted(state, plan.definition().id())) {
                    result(run, plan, 0, "裝了賦予它的武器，面板上還是沒有這個技能");
                    next(run, player, state, dummy, now);
                    return;
                }
                run.estimate = estimate(player, plan.definition().id());
                run.phase = Phase.PRESS;
                run.phaseTick = now;
                run.windowStart = now + (plan.definition().summon() != null || plan.companion() != null || plan.driver() != null ? SUMMON_WARMUP : 0);
                run.recording = run.windowStart <= now;
                run.releaseUntil = 0;
                press(run, player, state, dummy, now);
            }
            case PRESS -> {
                if (!run.recording && now >= run.windowStart) {
                    run.recording = true;
                    run.total = 0;
                    run.hits = 0;
                    run.sources.clear();
                }
                if (now >= run.windowStart + Math.round(windowSeconds(run.seconds, run.plan().definition().cooldown()) * 20)) {
                    // 2026-11-12 照站長第二輪的結果（榴彈每秒才 5～37）：有引信的（榴彈）停手後再多記「引信時間」那麼久——
                    //   時間窗最後幾秒丟出去的還沒炸；除的秒數不變（只算在時間窗裡放出去的）。這次之前時間到就停止記錄
                    final double fuse = run.plan().definition().params().fuse();
                    run.pendingTicks = fuse > 0 ? (int) Math.min(MAX_FUSE_TAIL, Math.round(fuse * 20) + TAIL_TICKS) : 0;
                    if (run.pendingTicks <= 0) {
                        run.recording = false;
                        settle(run);
                    }
                    run.phase = Phase.TAIL;
                    run.phaseTick = now;
                    return;
                }
                press(run, player, state, dummy, now);
            }
            case TAIL -> {
                refill(state);
                if (run.pendingTicks > 0) {
                    if (now - run.phaseTick >= run.pendingTicks) {
                        run.pendingTicks = 0;
                        run.recording = false;
                        settle(run);
                        run.phaseTick = now;
                    }
                    return;
                }
                if (now - run.phaseTick >= TAIL_TICKS) {
                    next(run, player, state, dummy, now);
                }
            }
        }
    }

    /** 時間窗結束：記下這個技能的結果（總傷害 ÷ 時間窗的秒數）。 */
    private void settle(final Run run) {
        result(run, run.plan(), run.total / windowSeconds(run.seconds, run.plan().definition().cooldown()), run.total <= 0 ? "0 傷害（多半要條件或別的技能配合，要人工看）" : null);
    }

    /**
     * 2026-11-12 照站長第一次跑的結果（地刺崩裂丟例外，整輪停掉）：這個技能放的時候出錯就記成「出錯」、換下一個，不整輪停；
     * 清場、收尾本身出錯，或累積出錯超過 {@link #MAX_ERRORS} 次才停。這次之前一出錯就整輪停。
     */
    private void failed(final Run run, final RuntimeException e) {
        final boolean measuring = run.index >= 0 && run.index < run.plans.size() && (run.phase != Phase.TAIL || run.pendingTicks > 0);
        final String id = run.index >= 0 && run.index < run.plans.size() ? run.plan().definition().id() : "-";
        plugin.getSLF4JLogger().error("自動量技能出錯（{}）", id, e);
        run.errors++;
        if (!measuring || run.errors > MAX_ERRORS) {
            finish(run, "出錯停止（看主控台）");
            return;
        }
        run.recording = false;
        run.pendingTicks = 0;
        result(run, run.plan(), 0, "出錯：" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage()));
        run.phase = Phase.TAIL;
        run.phaseTick = plugin.getServer().getCurrentTick();
    }

    /** 下一個技能：清場、換武器、換技能面板；全部量完就收尾。 */
    private void next(final Run run, final Player player, final PlayerState state, final LivingEntity dummy, final long now) {
        while (true) {
            run.index++;
            run.total = 0;
            run.hits = 0;
            run.sources.clear(); // 量不了的那幾個不要帶到上一個技能的來源
            cleanup(player, dummy);
            if (run.index >= run.plans.size()) {
                finish(run, null);
                return;
            }
            final Plan plan = run.plan();
            if (plan.problem() != null) {
                result(run, plan, 0, plan.problem());
                continue;
            }
            equip(run, player, state, plan);
            run.toggled = false;
            run.phase = Phase.SETUP;
            run.phaseTick = now;
            run.total = 0;
            run.hits = 0;
            run.sources.clear();
            run.recording = false;
            if (run.index % 10 == 0 && run.index > 0) {
                run.report.accept(String.format(Locale.ROOT, "<dark_gray>自動量技能：%d／%d", run.index, run.plans.size()));
            }
            return;
        }
    }

    private void equip(final Run run, final Player player, final PlayerState state, final Plan plan) {
        final int level = Math.max(1, state.level());
        // 2026-11-13 照站長「改成白板量」：天賦樹、昇華、武器以外的裝備都不算；量昇華賦予的技能時留昇華（要昇華天賦才動得起來）
        state.setBenchBlank("ascendancy".equals(plan.definition().source()) ? 2 : 1);
        state.setJewellery(Gear.MAIN, plan.main() == null ? null : plugin.items().create(plan.main(), Rarity.NORMAL, level));
        state.setJewellery(Gear.OFF, plan.off() == null ? null : plugin.items().create(plan.off(), Rarity.NORMAL, level));
        final SkillLoadout loadout = new SkillLoadout();
        if (plan.driver() != null) {
            // 2026-11-13：帶動的技能放左鍵，量的技能綁潛行＋右鍵（光環式的綁著就開著，觸發式的在面板上就會觸發）
            loadout.bind(SkillLoadout.KEY_LEFT, loadout.put(plan.driver(), GEM_LEVEL));
            loadout.bind(SkillLoadout.KEY_SNEAK_RIGHT, loadout.put(plan.definition().id(), GEM_LEVEL));
        } else if (!plan.definition().grantedOnly() || plan.direct()) {
            final int position = loadout.put(plan.definition().id(), GEM_LEVEL);
            loadout.bind(SkillLoadout.KEY_LEFT, position);
        }
        if (plan.companion() != null) {
            // 2026-11-13：前置的巨靈綁在潛行＋右鍵（綁著就開著）
            loadout.bind(SkillLoadout.KEY_SNEAK_RIGHT, loadout.put(plan.companion(), GEM_LEVEL));
        }
        state.setSkills(loadout);
        state.markDirty();
        plugin.players().sheet(player);
        plugin.characters().gear().sync(player);
        player.setFallDistance(0);
        final Entity dummyEntity = plugin.getServer().getEntity(run.dummy);
        run.spot = dummyEntity == null ? run.stand : standFor(run.stand, dummyEntity.getLocation(), close(plan.definition()));
        if (dummyEntity != null && FEET_AIM.contains(plan.definition().id())) {
            run.spot = lookAtFeet(run.spot, dummyEntity.getLocation());
        }
        player.teleport(run.spot);
    }

    /** 武器賦予的技能：換好武器、重算之後才出現在面板上，綁到左鍵。 */
    private boolean bindGranted(final PlayerState state, final String skillId) {
        final int position = state.skills().findGranted(skillId);
        if (position < 0) {
            return false;
        }
        state.skills().bind(SkillLoadout.KEY_LEFT, position);
        return true;
    }

    /** 技能面板的估計每秒傷害（同一個技能取最大的那列；沒有 0）。 */
    private double estimate(final Player player, final String skillId) {
        double best = 0;
        try {
            for (final SkillService.DpsRow row : service.skillDpsByWeaponSet(player)) {
                if (skillId.equals(row.skillId())) {
                    // 2026-11-12：報表比的是估計式本身（拿掉 skills.yml 的 estimate_scale 校正），「實測÷估計」就是要填的校正值
                    best = Math.max(best, row.dps() / service.config().estimateScale(skillId));
                }
            }
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("自動量技能：{} 的估計值算不出來", skillId, e);
        }
        return best;
    }

    /** 按左鍵（對準假人）。放開出手的蓄力：蓄滿（或完美時機）就放開幾 tick 再蓄。 */
    private void press(final Run run, final Player player, final PlayerState state, final LivingEntity dummy, final long now) {
        refill(state);
        refillFlasks(player);
        if ((now - run.phaseTick) % CORPSE_EVERY == 0) {
            dropCorpse(dummy, now);
        }
        final SkillRuntime runtime = service.runtime(player);
        if (runtime.charging == null && player.getLocation().distanceSquared(run.spot) > DRIFT * DRIFT) {
            player.setFallDistance(0);
            player.teleport(run.spot); // 位移技能衝太遠：拉回原位（還面對假人）
        }
        // 2026-11-13：有帶動技能的按帶動技能（左鍵就是它）
        final SkillDefinition pressed = run.plan().driver() != null ? service.config().skill(run.plan().driver()) : run.plan().definition();
        final String id = pressed.id();
        // 2026-11-13 照站長「有的技能需要榮耀才能施放」：每 tick 補滿榮耀（量的技能、帶動的技能；不用榮耀的不會加）
        service.addGlory(player, id, 1_000_000);
        if (!id.equals(run.plan().definition().id())) {
            service.addGlory(player, run.plan().definition().id(), 1_000_000);
        }
        final SummonSpec summon = pressed.summon();
        if (pressed.mechanic() == Mechanic.AURA) {
            // 2026-11-12：常駐召喚物（光環式、保留精魂）綁著就開著；2026-11-13 照站長：可以下指令的（骷髏、砲手、巨靈、鴉群）每秒按一次下指令，
            //   其他的按一下就好（按了只會提示「一直開著」）。這次之前只按第一下，指令都沒下
            final boolean commandable = Commandables.commandable(id);
            if (!run.toggled || commandable && (now - run.phaseTick) % COMMAND_EVERY == 0) {
                run.toggled = true;
                service.trigger(player, SkillLoadout.KEY_LEFT, dummy);
            }
            return;
        }
        if (summon != null && summon.duration() <= 0 && run.recording && minionsFull(player)) {
            return; // 常駐的召喚物叫滿了就不再按（再按會重叫、浪費施法時間）；死掉少了才補
        }
        prime(player, dummy, pressed.id(), now);
        if (waitPlaced(player, pressed)) {
            return; // 2026-11-13：放下去的還在，不再按（再按會拆掉舊的）
        }
        final int[] pulse = PULSE.get(id);
        if (pulse != null && (now - run.phaseTick) % (pulse[0] + pulse[1]) >= pulse[0]) {
            return; // 2026-11-13：停手那段
        }
        // 2026-11-13 照站長選 A：蓄力會自己蓄到最好的時候出手——一直按著就好（這次之前蓄滿就停按幾 tick 讓它放開）
        service.trigger(player, SkillLoadout.KEY_LEFT, dummy);
    }

    private boolean minionsFull(final Player player) {
        int count = 0;
        int limit = 0;
        for (final MinionService.HudCount hud : service.minionCounts(player)) {
            count += hud.count();
            limit += hud.limit();
        }
        return count > 0 && count >= limit;
    }

    /** 蓄力要蓄幾 tick 才放開：有完美時機的抓完美時機，不然蓄滿全部的段數（要充滿才能射的照充滿）；至少 2、最多 {@link #MAX_HOLD}。純函式。 */
    static long holdTicks(final ChargeSpec spec, final long perfectAt, final double speed, final double interval, final boolean fullOnly) {
        return holdTicks(spec, null, perfectAt, speed, interval, fullOnly);
    }

    /** 2026-11-13：照放法（null = 舊的：有完美時機抓完美，不然蓄滿）。純函式。 */
    static long holdTicks(final ChargeSpec spec, final Hold hold, final long perfectAt, final double speed, final double interval,
                          final boolean fullOnly) {
        double ticks;
        if (hold == null) {
            ticks = perfectAt > 0 ? perfectAt : spec.fullStageTicks(speed);
        } else {
            ticks = switch (hold) {
                case QUICK -> Math.max(2, spec.minTicks(speed));
                case HALF -> spec.stageTicks(speed) * Math.max(1, Math.floor((spec.stages() - 1) / 2.0));
                case FULL -> spec.fullStageTicks(speed);
                case PERFECT -> perfectAt > 0 ? perfectAt : spec.fullStageTicks(speed);
            };
        }
        if (fullOnly) {
            ticks = Math.max(ticks, FullCharge.fullTicks(interval));
        }
        return Math.max(2, Math.min(MAX_HOLD, (long) Math.ceil(ticks)));
    }

    /** 2026-11-13：藥劑技能要魔力藥劑的充能——每 tick 補滿（你身上的藥劑）。 */
    private void refillFlasks(final Player player) {
        if (plugin.flasks() == null) {
            return;
        }
        plugin.flasks().addCharges(player, 1000, tw.exilecore.flask.FlaskKind.MANA);
        plugin.flasks().addCharges(player, 1000, tw.exilecore.flask.FlaskKind.LIFE);
    }

    /** 2026-11-13：要屍體的技能——在假人和你之間放一具同等級普通怪的屍體（10 秒後消失）。 */
    private void dropCorpse(final LivingEntity dummy, final long now) {
        if (plugin.combat() == null || plugin.monsters() == null) {
            return;
        }
        final Location at = dummy.getLocation();
        final double life = plugin.monsters().config().lifeAt(plugin.training().dummyLevel(dummy));
        plugin.combat().corpses().add(at.getWorld().getUID(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, life, now, "ZOMBIE");
    }

    private static void refill(final PlayerState state) {
        state.setMana(state.maxMana());
        state.setLife(state.maxLife());
    }

    /** 清場：技能狀態（冷卻、召喚物、地面效果、插著的矛…）、假人。 */
    private void cleanup(final Player player, final LivingEntity dummy) {
        service.forget(player);
        // 2026-11-13（bench 1107：上一個技能的土牆、冰晶在下一個技能量的時候炸開，算到別的技能頭上）：留在場上的也收掉
        final UUID id = player.getUniqueId();
        service.leftovers().forget(id);
        service.crystals().clear(id);
        service.stageAttacks().forgetPlaced(id);
        service.originals().forgetPlaced(id);
        plugin.training().resetDummy(dummy);
    }

    private void record(final Run run, final LivingEntity target, final double damage, final String source) {
        if (!run.recording || !target.getUniqueId().equals(run.dummy) || damage <= 0) {
            return; // 2026-11-12：只量單體——範圍打到旁邊的假人不算
        }
        if (run.index >= 0 && run.index < run.plans.size() && run.plan().driver() != null && (source == null || !source.startsWith("召喚物"))) {
            return; // 2026-11-13：有帶動技能的只算召喚物打的（召喚物的持續傷害分不出來，不算）
        }
        run.total += damage;
        if (!"流血、點燃、中毒".equals(source)) {
            run.hits++;
        }
        run.sources.merge(source == null ? "普攻／其他" : source, damage, Double::sum);
    }

    private void result(final Run run, final Plan plan, final double dps, final String note) {
        final List<Map.Entry<String, Double>> sources = new ArrayList<>(run.sources.entrySet());
        sources.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        Result row = new Result(plan.definition().id(), plan.definition().name(), plan.group(),
                plan.main() == null ? "空手" : plan.main().name() + (plan.off() == null ? "" : "＋" + plan.off().name()),
                dps, run.total, run.hits, run.estimate, List.copyOf(sources.subList(0, Math.min(3, sources.size()))), note);
        run.estimate = 0;
        if (plan.hold() != null) {
            // 2026-11-13：蓄力的每種放法：同一個技能只留最好的那一列，備註寫每種放法的數字
            final String part = plan.hold().label + " " + tw.exilecore.training.DpsMeter.number(dps);
            final String tried = run.holdNotes.merge(plan.definition().id(), part, (a, b) -> a + "、" + b);
            Result previous = null;
            for (final Result existing : run.results) {
                if (existing.id().equals(row.id())) {
                    previous = existing;
                }
            }
            final Result best = previous == null || dps > previous.dps() ? row : previous;
            final String bestLabel = best == row ? plan.hold().label : run.holdBest.getOrDefault(row.id(), "");
            if (best == row) {
                run.holdBest.put(row.id(), plan.hold().label);
            }
            row = new Result(best.id(), best.name(), best.group(), best.weapon(), best.dps(), best.total(), best.hits(), best.estimate(),
                    best.sources(), "最好：" + bestLabel + "（" + tried + "）" + (best.note() == null || best.note().startsWith("最好：") ? "" : "；" + best.note()));
            if (previous != null) {
                run.results.remove(previous);
            }
        } else if (plan.problem() == null) {
            // 2026-11-13：連量的取平均——每秒傷害、總傷害、打中幾下都平均；來源、估計照最後一次；備註寫每次的數字
            final List<double[]> trials = run.repeats.computeIfAbsent(plan.definition().id(), id -> new ArrayList<>());
            trials.add(new double[] {dps, row.total(), row.hits()});
            if (trials.size() > 1) {
                run.results.removeIf(existing -> existing.id().equals(plan.definition().id()));
                final double[] mean = mean(trials);
                final StringBuilder each = new StringBuilder();
                for (final double[] trial : trials) {
                    each.append(each.isEmpty() ? "" : "、").append(tw.exilecore.training.DpsMeter.number(trial[0]));
                }
                row = new Result(row.id(), row.name(), row.group(), row.weapon(), mean[0], mean[1], (int) Math.round(mean[2]), row.estimate(),
                        row.sources(), mean[0] <= 0 ? note : trials.size() + " 次平均（" + each + "）" + (note == null || mean[0] <= 0 ? "" : "；" + note));
            }
        }
        run.results.add(row);
    }

    /** 2026-11-13：連量要量幾次（預設；篩選寫「N次」可以改）。 */
    static final int REPEATS = 3;
    static final int MAX_REPEATS = 5;
    private static final java.util.regex.Pattern REPEAT_WORD = java.util.regex.Pattern.compile("^([1-9])次$");

    /** 2026-11-13：篩選裡的「N次」（1～5）；沒寫是 {@link #REPEATS}。純函式。 */
    static int repeatsIn(final String filter) {
        if (filter != null) {
            for (final String word : filter.trim().split("\\s+")) {
                final java.util.regex.Matcher matcher = REPEAT_WORD.matcher(word);
                if (matcher.matches()) {
                    return Math.min(MAX_REPEATS, Integer.parseInt(matcher.group(1)));
                }
            }
        }
        return REPEATS;
    }

    /** 2026-11-13：拿掉「N次」之後的篩選（只剩它的話回傳 null）。純函式。 */
    static String withoutRepeats(final String filter) {
        if (filter == null) {
            return null;
        }
        final StringBuilder rest = new StringBuilder();
        for (final String word : filter.trim().split("\\s+")) {
            if (!REPEAT_WORD.matcher(word).matches() && !word.isEmpty()) {
                rest.append(rest.isEmpty() ? "" : " ").append(word);
            }
        }
        return rest.isEmpty() ? null : rest.toString();
    }

    /** 2026-11-13：每一欄的平均。純函式。 */
    static double[] mean(final List<double[]> trials) {
        final double[] sum = new double[trials.isEmpty() ? 0 : trials.getFirst().length];
        for (final double[] trial : trials) {
            for (int i = 0; i < sum.length; i++) {
                sum[i] += trial[i];
            }
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= Math.max(1, trials.size());
        }
        return sum;
    }

    // ---- 結束 ----

    /** 中途停止（指令）；沒在量回傳 false。 */
    public boolean stop(final UUID player) {
        final Run run = runs.get(player);
        if (run == null) {
            return false;
        }
        finish(run, "手動停止");
        return true;
    }

    /** 伺服器關掉、模組重載：全部換回來。 */
    void shutdown() {
        for (final Run run : List.copyOf(runs.values())) {
            finish(run, "伺服器關閉／重載");
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void drop(final Run run) {
        if (plugin.training() != null && plugin.getServer().getEntity(run.dummy) instanceof LivingEntity living) {
            plugin.training().neutral(living, false); // 2026-11-12：恢復假人原本的防禦
        }
        runs.remove(run.player);
        if (plugin.training() != null) {
            plugin.training().tap(run.player, null);
        }
    }

    /** 收尾：換回原本的技能和武器、站回原位、寫報表。reason = null 是全部量完。 */
    private void finish(final Run run, final String reason) {
        drop(run);
        final Player player = plugin.getServer().getPlayer(run.player);
        if (player != null) {
            final PlayerState state = plugin.players().get(player);
            if (state.character() != null && state.character().id() == run.characterId) {
                service.forget(player);
                state.setSkills(run.originalSkills.copy());
                state.setJewellery(Gear.MAIN, run.originalMain);
                state.setJewellery(Gear.OFF, run.originalOff);
                state.setBenchFree(false);
                state.setBenchBlank(0);
                state.markDirty();
                plugin.players().sheet(player);
                plugin.characters().gear().sync(player);
                refill(state);
                plugin.players().display(player, state);
            }
            final Entity dummy = plugin.getServer().getEntity(run.dummy);
            if (dummy instanceof LivingEntity living) {
                plugin.training().resetDummy(living);
            }
        }
        if (run.results.isEmpty()) {
            run.report.accept("<gray>自動量技能結束" + (reason == null ? "" : "（" + ItemRenderer.escape(reason) + "）") + "，沒有量到任何技能；技能和武器已經換回來");
            return;
        }
        final String file = write(run, reason);
        for (final String line : summary(run.results)) {
            run.report.accept(line);
        }
        run.report.accept("<gray>自動量技能" + (reason == null ? "完成" : "停止（" + ItemRenderer.escape(reason) + "）") + "：量了 <white>"
                + run.results.size() + "</white> 個，技能和武器已經換回來"
                + (file == null ? "<red>（報表寫入失敗，看主控台）" : "；報表 <white>plugins/ExileCore/balance/" + file));
    }

    // ---- 報表 ----

    /** 一組的中位數（0 傷害的不算）。純函式。 */
    static double median(final List<Double> values) {
        final List<Double> sorted = new ArrayList<>();
        for (final double value : values) {
            if (value > 0) {
                sorted.add(value);
            }
        }
        if (sorted.isEmpty()) {
            return 0;
        }
        sorted.sort(Double::compare);
        final int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** 標記：0 → 量不到；同組 2 個以上而且低於中位數 {@link #WEAK_SHARE} → 偏弱。純函式。 */
    static String flag(final double dps, final double median, final int groupSize) {
        if (dps <= 0) {
            return "量不到";
        }
        return groupSize >= 2 && median > 0 && dps < median * WEAK_SHARE ? "偏弱" : "";
    }

    private static Map<String, List<Result>> grouped(final List<Result> results) {
        final Map<String, List<Result>> groups = new LinkedHashMap<>();
        final List<Result> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparing(Result::group).thenComparing(Comparator.comparingDouble(Result::dps).reversed()));
        for (final Result result : sorted) {
            groups.computeIfAbsent(result.group(), key -> new ArrayList<>()).add(result);
        }
        return groups;
    }

    private static List<Double> dpsOf(final List<Result> results) {
        final List<Double> list = new ArrayList<>();
        for (final Result result : results) {
            list.add(result.dps());
        }
        return list;
    }

    /** 聊天框的摘要：每組幾個、中位數、偏弱的名字。 */
    static List<String> summary(final List<Result> results) {
        final List<String> lines = new ArrayList<>();
        for (final Map.Entry<String, List<Result>> group : grouped(results).entrySet()) {
            final double median = median(dpsOf(group.getValue()));
            final List<String> weak = new ArrayList<>();
            int missing = 0;
            for (final Result result : group.getValue()) {
                final String flag = flag(result.dps(), median, group.getValue().size());
                if ("偏弱".equals(flag)) {
                    weak.add(result.name());
                } else if ("量不到".equals(flag)) {
                    missing++;
                }
            }
            lines.add("<#f5b942>" + ItemRenderer.escape(group.getKey()) + "<gray>（" + group.getValue().size() + " 個，中位數 <white>"
                    + tw.exilecore.training.DpsMeter.number(median) + "</white>）"
                    + (weak.isEmpty() ? "" : "<red> 偏弱：" + ItemRenderer.escape(String.join("、", weak)))
                    + (missing == 0 ? "" : "<dark_gray> 量不到 " + missing + " 個"));
        }
        return lines;
    }

    private String write(final Run run, final String reason) {
        final String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
        final Path folder = plugin.dataFolder().resolve("balance");
        final String name = "bench-" + stamp;
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(name + ".md"), markdown(run, reason), StandardCharsets.UTF_8);
            Files.writeString(folder.resolve(name + ".json"), json(run, reason), StandardCharsets.UTF_8);
            return name + ".md";
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("寫入自動量技能的報表失敗", e);
            return null;
        }
    }

    private String header(final Run run) {
        final Player player = plugin.getServer().getPlayer(run.player);
        final PlayerState state = player == null ? null : plugin.players().get(player);
        return (state == null || state.character() == null ? "?" : state.character().name() + " Lv." + state.level())
                + "｜假人：" + run.dummyName + "（量測時沒有防禦：護甲、閃避、抗性 0）｜白板（不算天賦樹、昇華、武器以外的裝備）｜每個 " + run.seconds + " 秒｜技能等級 " + GEM_LEVEL + "、不掛輔助｜"
                + (run.filter == null ? "全部" : "篩選：" + run.filter);
    }

    private String markdown(final Run run, final String reason) {
        final StringBuilder out = new StringBuilder();
        out.append("# 自動量技能（單體）\n\n").append(header(run)).append("\n\n");
        if (reason != null) {
            out.append("> 中途停止：").append(reason).append("\n\n");
        }
        out.append("- 武器：技能要的那種武器裡掉落等級最高的普通基底（沒有詞綴）；法術、召喚空手；其他裝備照角色身上的。\n")
                .append("- 每秒傷害 = 固定時間窗裡打到這隻假人的總傷害 ÷ 秒數（含持續傷害的跳；範圍打到旁邊的假人不算；冷卻比時間窗長的，時間窗拉到冷卻的 2 倍；每個技能連量幾次取平均（預設 3 次，篩選寫「1次」只量一次），標記欄寫每次的數字）。召喚技能先放 ")
                .append(SUMMON_WARMUP / 20).append(" 秒才開始算；有引信的（榴彈）停手後再多記引信那麼久（最後丟出去的還沒炸），除的秒數不變。\n")
                .append("- 「偏弱」= 同組中位數的 ").append(Math.round(WEAK_SHARE * 100)).append("% 以下；「量不到」= 0 傷害，要人工看。\n")
                .append("- 估計 = 估計式本身的每秒傷害（不算防禦；量測時假人也沒有防禦，兩邊可以直接比；不含 skills.yml 的 estimate_scale 校正）；實測 ÷ 估計 差很多的，估計式可能漏了機制，可以把這個比值填進 estimate_scale。\n\n");
        for (final Map.Entry<String, List<Result>> group : grouped(run.results).entrySet()) {
            final double median = median(dpsOf(group.getValue()));
            out.append("## ").append(group.getKey()).append("（").append(group.getValue().size()).append(" 個，中位數 ")
                    .append(tw.exilecore.training.DpsMeter.number(median)).append("）\n\n")
                    .append("| 技能 | 代號 | 武器 | 實測每秒 | ÷中位數 | 估計 | 實測÷估計 | 擊中 | 主要來源 | 標記 |\n")
                    .append("|---|---|---|---:|---:|---:|---:|---:|---|---|\n");
            for (final Result result : group.getValue()) {
                final String flag = flag(result.dps(), median, group.getValue().size());
                final StringBuilder sources = new StringBuilder();
                for (final Map.Entry<String, Double> source : result.sources()) {
                    sources.append(sources.isEmpty() ? "" : "、").append(source.getKey()).append(' ')
                            .append(Math.round(source.getValue() * 100 / Math.max(1, result.total()))).append('%');
                }
                out.append("| ").append(result.name()).append(" | ").append(result.id()).append(" | ").append(result.weapon())
                        .append(" | ").append(tw.exilecore.training.DpsMeter.number(result.dps()))
                        .append(" | ").append(median > 0 ? Math.round(result.dps() * 100 / median) + "%" : "-")
                        .append(" | ").append(result.estimate() > 0 ? tw.exilecore.training.DpsMeter.number(result.estimate()) : "-")
                        .append(" | ").append(result.estimate() > 0 && result.dps() > 0
                                ? String.format(Locale.ROOT, "%.2f", result.dps() / result.estimate()) : "-")
                        .append(" | ").append(result.hits())
                        .append(" | ").append(sources)
                        .append(" | ").append(flag).append(result.note() == null ? "" : (flag.isEmpty() ? "" : "：") + result.note())
                        .append(" |\n");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String json(final Run run, final String reason) {
        final JsonObject root = new JsonObject();
        root.addProperty("header", header(run));
        root.addProperty("seconds", run.seconds);
        root.addProperty("gem_level", GEM_LEVEL);
        root.addProperty("dummy", run.dummyName);
        root.addProperty("started_at", run.startedAt);
        if (reason != null) {
            root.addProperty("stopped", reason);
        }
        final JsonArray rows = new JsonArray();
        for (final Map.Entry<String, List<Result>> group : grouped(run.results).entrySet()) {
            final double median = median(dpsOf(group.getValue()));
            for (final Result result : group.getValue()) {
                final JsonObject row = new JsonObject();
                row.addProperty("id", result.id());
                row.addProperty("name", result.name());
                row.addProperty("group", result.group());
                row.addProperty("weapon", result.weapon());
                row.addProperty("dps", Math.round(result.dps()));
                row.addProperty("estimate", Math.round(result.estimate()));
                row.addProperty("hits", result.hits());
                row.addProperty("group_median", Math.round(median));
                row.addProperty("flag", flag(result.dps(), median, group.getValue().size()));
                if (result.note() != null) {
                    row.addProperty("note", result.note());
                }
                final JsonObject sources = new JsonObject();
                for (final Map.Entry<String, Double> source : result.sources()) {
                    sources.addProperty(source.getKey(), Math.round(source.getValue()));
                }
                row.add("sources", sources);
                rows.add(row);
            }
        }
        root.add("skills", rows);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }
}
