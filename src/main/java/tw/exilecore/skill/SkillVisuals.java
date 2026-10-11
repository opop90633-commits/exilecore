package tw.exilecore.skill;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 技能的外觀：粒子與音效（skills.yml 的 visual 選這裡的名稱，見 {@link SkillConfig#VISUALS}）。
 * 只用不需要額外資料的粒子（加上 DUST 顏色與 BLOCK 方塊碎屑），避免版本差異造成錯誤。
 */
final class SkillVisuals {

    private static final Particle.DustOptions WAND = new Particle.DustOptions(Color.fromRGB(0xB266FF), 1.0f);
    private static final Particle.DustOptions STAFF = new Particle.DustOptions(Color.fromRGB(0x66A8FF), 1.4f);
    private static final Particle.DustOptions SPARK = new Particle.DustOptions(Color.fromRGB(0xFFF27A), 0.8f);
    private static final Particle.DustOptions ARC = new Particle.DustOptions(Color.fromRGB(0xA8E4FF), 1.0f);
    private static final Particle.DustOptions FROST = new Particle.DustOptions(Color.fromRGB(0xBFEFFF), 1.2f);
    private static final Particle.DustOptions VOID = new Particle.DustOptions(Color.fromRGB(0x5B1E7A), 1.3f);
    private static final Particle.DustOptions BLIGHT = new Particle.DustOptions(Color.fromRGB(0x3E7A2C), 1.2f);

    private static final Particle.DustOptions FIRE = new Particle.DustOptions(Color.fromRGB(0xFF7A1A), 1.2f);
    private static final Particle.DustOptions GAS = new Particle.DustOptions(Color.fromRGB(0x7FBF3F), 1.5f);
    private static final Particle.DustOptions STEEL = new Particle.DustOptions(Color.fromRGB(0xD8D8E8), 0.9f);
    private static final Particle.DustOptions WIND = new Particle.DustOptions(Color.fromRGB(0xCFF5E8), 1.0f);
    private static final Particle.DustOptions BLOOD = new Particle.DustOptions(Color.fromRGB(0xB3121F), 1.2f);
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(0xF5C542), 1.0f);
    private static final Particle.DustOptions ROT = new Particle.DustOptions(Color.fromRGB(0x5C7A3A), 1.1f);
    /** 瞬影擊：淡紫的殘影。 */
    private static final Particle.DustOptions AFTERIMAGE = new Particle.DustOptions(Color.fromRGB(0xC3A6FF), 1.0f);
    /** 聚星戰吼：星光的淡金白。 */
    private static final Particle.DustOptions STARLIGHT = new Particle.DustOptions(Color.fromRGB(0xFFF1C9), 1.2f);

    private static final org.bukkit.block.data.BlockData ICE = Material.ICE.createBlockData();

    /**
     * 新的外觀用同一套樣式表：主色、主粒子、施放與打中的聲音。舊的外觀（熾星彈、鏈雷…）保留各自手寫的效果。
     */
    private record Style(Particle.DustOptions dust, Particle particle, Sound cast, float castPitch, Sound hit, float hitPitch) {
    }

    private static final Map<String, Style> STYLES = Map.ofEntries(
            Map.entry("dash", new Style(WIND, Particle.CLOUD, Sound.ENTITY_BREEZE_WIND_BURST, 1.4f, Sound.ENTITY_BREEZE_IDLE_AIR, 1.5f)),
            Map.entry("whirling_blades", new Style(STEEL, Particle.SWEEP_ATTACK, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.3f, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f)),
            Map.entry("flame_dash", new Style(FIRE, Particle.FLAME, Sound.ITEM_FIRECHARGE_USE, 1.2f, Sound.BLOCK_FIRE_AMBIENT, 1.0f)),
            Map.entry("frostblink", new Style(FROST, Particle.SNOWFLAKE, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.4f, Sound.BLOCK_GLASS_BREAK, 1.5f)),
            Map.entry("blink_arrow", new Style(WIND, Particle.END_ROD, Sound.ENTITY_ARROW_SHOOT, 1.3f, Sound.ENTITY_ENDERMAN_TELEPORT, 1.4f)),
            Map.entry("rain_of_arrows", new Style(STEEL, Particle.CRIT, Sound.ENTITY_ARROW_SHOOT, 0.8f, Sound.ENTITY_ARROW_HIT, 1.0f)),
            Map.entry("lightning_arrow", new Style(ARC, Particle.ELECTRIC_SPARK, Sound.ENTITY_ARROW_SHOOT, 1.1f, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.9f)),
            Map.entry("tornado_shot", new Style(WIND, Particle.CLOUD, Sound.ENTITY_ARROW_SHOOT, 0.9f, Sound.ENTITY_BREEZE_SHOOT, 1.3f)),
            Map.entry("ice_shot", new Style(FROST, Particle.SNOWFLAKE, Sound.ENTITY_ARROW_SHOOT, 1.0f, Sound.BLOCK_GLASS_BREAK, 1.7f)),
            Map.entry("earthquake", new Style(GOLD, Particle.CLOUD, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 0.8f, Sound.ENTITY_GENERIC_EXPLODE, 0.7f)),
            Map.entry("comet", new Style(FROST, Particle.END_ROD, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, Sound.ENTITY_GENERIC_EXPLODE, 1.1f)),
            Map.entry("blizzard", new Style(FROST, Particle.SNOWFLAKE, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.1f, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.3f)),
            Map.entry("flame_wall", new Style(FIRE, Particle.FLAME, Sound.ENTITY_BLAZE_SHOOT, 0.8f, Sound.BLOCK_FIRE_AMBIENT, 1.0f)),
            Map.entry("explosive_grenade", new Style(FIRE, Particle.SMOKE, Sound.ITEM_CROSSBOW_SHOOT, 0.8f, Sound.ENTITY_GENERIC_EXPLODE, 1.0f)),
            Map.entry("gas_grenade", new Style(GAS, Particle.SNEEZE, Sound.ITEM_CROSSBOW_SHOOT, 0.9f, Sound.BLOCK_BREWING_STAND_BREW, 0.7f)),
            Map.entry("rapid_fire", new Style(STEEL, Particle.CRIT, Sound.ITEM_CROSSBOW_SHOOT, 1.4f, Sound.ENTITY_ARROW_HIT, 1.1f)),
            Map.entry("storm_wave", new Style(ARC, Particle.ELECTRIC_SPARK, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.8f)),
            Map.entry("killing_palm", new Style(BLOOD, Particle.CRIT, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.7f, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f)),
            Map.entry("whirlwind_lance", new Style(WIND, Particle.CLOUD, Sound.ITEM_TRIDENT_THROW, 1.0f, Sound.ENTITY_BREEZE_WIND_BURST, 1.1f)),
            Map.entry("lunge", new Style(STEEL, Particle.CRIT, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.3f, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f)),
            Map.entry("hunters_mark", new Style(GOLD, Particle.END_ROD, Sound.ENTITY_EVOKER_CAST_SPELL, 1.6f, Sound.ENTITY_ARROW_HIT_PLAYER, 1.3f)),
            Map.entry("void_rift", new Style(VOID, Particle.PORTAL, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.7f, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.9f)),
            // 異常狀態的技能（2026-10）：血刺（血）、蛇牙擊與蝕毒箭（毒）、碎脊擊（錘）
            Map.entry("puncture", new Style(BLOOD, Particle.CRIT, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.4f, Sound.ENTITY_PLAYER_HURT, 0.6f)),
            Map.entry("viper_strike", new Style(GAS, Particle.ITEM_SLIME, Sound.ENTITY_SPIDER_AMBIENT, 1.5f, Sound.ENTITY_SPIDER_HURT, 1.3f)),
            Map.entry("boneshatter", new Style(STEEL, Particle.CRIT, Sound.ITEM_MACE_SMASH_AIR, 0.9f, Sound.ITEM_MACE_SMASH_GROUND, 1.1f)),
            Map.entry("caustic_arrow", new Style(GAS, Particle.SNEEZE, Sound.ENTITY_ARROW_SHOOT, 0.9f, Sound.BLOCK_BREWING_STAND_BREW, 0.8f)),
            // 圖騰（2026-10：靜音）：圖騰自己一直打，每一下都有聲音太吵——只留放下去那一聲，攻擊、打中都不出聲
            Map.entry("ancestral_totem", new Style(GOLD, Particle.CRIT, null, 0.8f, null, 0.9f)),
            Map.entry("flame_totem", new Style(FIRE, Particle.FLAME, null, 1.0f, null, 1.4f)),
            Map.entry("shockwave_totem", new Style(GOLD, Particle.CLOUD, null, 0.9f, null, 0.9f)),
            Map.entry("fire_trap", new Style(FIRE, Particle.FLAME, Sound.BLOCK_TRIPWIRE_ATTACH, 1.0f, Sound.ENTITY_GENERIC_EXPLODE, 1.3f)),
            Map.entry("lightning_trap", new Style(ARC, Particle.ELECTRIC_SPARK, Sound.BLOCK_TRIPWIRE_ATTACH, 1.3f, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.9f)),
            Map.entry("bear_trap", new Style(STEEL, Particle.CRIT, Sound.BLOCK_TRIPWIRE_ATTACH, 0.8f, Sound.BLOCK_CHAIN_HIT, 0.7f)),
            Map.entry("tempest_bell", new Style(GOLD, Particle.ELECTRIC_SPARK, Sound.BLOCK_ANVIL_PLACE, 1.4f, Sound.BLOCK_BELL_USE, 1.0f)),
            Map.entry("detonate_dead", new Style(BLOOD, Particle.FLAME, Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, Sound.ENTITY_GENERIC_EXPLODE, 0.8f)),
            // 第三部分（三）：召喚物
            Map.entry("desecrate", new Style(ROT, Particle.SOUL, Sound.BLOCK_SOUL_SOIL_BREAK, 0.6f, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.4f)),
            Map.entry("rally", new Style(ROT, Particle.SOUL_FIRE_FLAME, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.3f, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f)),
            Map.entry("raise_zombie", new Style(ROT, Particle.SOUL, Sound.ENTITY_ZOMBIE_AMBIENT, 0.7f, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.3f)),
            Map.entry("summon_skeletons", new Style(STEEL, Particle.WHITE_ASH, Sound.ENTITY_SKELETON_AMBIENT, 0.9f, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f)),
            Map.entry("raging_spirits", new Style(FIRE, Particle.FLAME, Sound.ENTITY_VEX_CHARGE, 1.2f, Sound.ENTITY_BLAZE_HURT, 1.4f)),
            Map.entry("flame_golem", new Style(FIRE, Particle.LAVA, Sound.BLOCK_ANVIL_LAND, 0.6f, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.9f)),
            // 第三部分（四）：光環、捷、戰吼
            Map.entry("aura_cold", new Style(FROST, Particle.SNOWFLAKE, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, Sound.BLOCK_GLASS_BREAK, 1.4f)),
            Map.entry("aura_fire", new Style(FIRE, Particle.FLAME, Sound.ITEM_FIRECHARGE_USE, 0.9f, Sound.BLOCK_FIRE_AMBIENT, 1.0f)),
            Map.entry("aura_lightning", new Style(ARC, Particle.ELECTRIC_SPARK, Sound.BLOCK_BEACON_ACTIVATE, 1.4f, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.8f)),
            Map.entry("aura_armour", new Style(GOLD, Particle.CRIT, Sound.BLOCK_ANVIL_USE, 0.9f, Sound.BLOCK_ANVIL_LAND, 1.0f)),
            Map.entry("aura_evasion", new Style(WIND, Particle.CLOUD, Sound.ENTITY_BREEZE_IDLE_AIR, 1.3f, Sound.ENTITY_BREEZE_WIND_BURST, 1.4f)),
            Map.entry("aura_es", new Style(STAFF, Particle.ENCHANT, Sound.BLOCK_BEACON_POWER_SELECT, 1.3f, Sound.BLOCK_BEACON_AMBIENT, 1.5f)),
            Map.entry("aura_haste", new Style(SPARK, Particle.END_ROD, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.3f, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.6f)),
            Map.entry("aura_purity", new Style(new Particle.DustOptions(Color.fromRGB(0xE8F3FF), 1.1f), Particle.END_ROD, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f)),
            Map.entry("aura_life", new Style(BLOOD, Particle.HEART, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, Sound.ENTITY_PLAYER_LEVELUP, 0.7f)),
            Map.entry("aura_mana", new Style(WAND, Particle.ENCHANT, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f)),
            Map.entry("aura_precision", new Style(GOLD, Particle.END_ROD, Sound.ENTITY_ARROW_HIT_PLAYER, 1.2f, Sound.ENTITY_ARROW_HIT_PLAYER, 1.5f)),
            Map.entry("herald_ash", new Style(FIRE, Particle.FLAME, Sound.ITEM_FIRECHARGE_USE, 1.1f, Sound.ENTITY_GENERIC_EXPLODE, 1.3f)),
            Map.entry("herald_thunder", new Style(ARC, Particle.ELECTRIC_SPARK, Sound.BLOCK_BEACON_ACTIVATE, 1.6f, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.9f)),
            Map.entry("herald_ice", new Style(FROST, Particle.SNOWFLAKE, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.3f, Sound.BLOCK_GLASS_BREAK, 1.6f)),
            Map.entry("enduring_cry", new Style(GOLD, Particle.CRIT, Sound.ENTITY_RAVAGER_ROAR, 0.9f, Sound.BLOCK_ANVIL_LAND, 1.1f)),
            Map.entry("rallying_cry", new Style(GOLD, Particle.END_ROD, Sound.ENTITY_RAVAGER_ROAR, 1.2f, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f)),
            Map.entry("intimidating_cry", new Style(BLOOD, Particle.SMOKE, Sound.ENTITY_RAVAGER_ROAR, 0.6f, Sound.ENTITY_WARDEN_ROAR, 1.4f)),
            // 2026-10：瞬影擊、聚星戰吼
            Map.entry("flicker", new Style(AFTERIMAGE, Particle.REVERSE_PORTAL, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.5f, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.3f)),
            Map.entry("star_cry", new Style(STARLIGHT, Particle.END_ROD, Sound.ENTITY_RAVAGER_ROAR, 1.3f, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f)),
            // Boss 招式（照傷害類型）
            Map.entry("boss_physical", new Style(STEEL, Particle.CRIT, Sound.ENTITY_RAVAGER_ROAR, 0.8f, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 0.8f)),
            Map.entry("boss_fire", new Style(FIRE, Particle.FLAME, Sound.ENTITY_BLAZE_SHOOT, 0.7f, Sound.ENTITY_GENERIC_EXPLODE, 0.9f)),
            Map.entry("boss_cold", new Style(FROST, Particle.SNOWFLAKE, Sound.ENTITY_PLAYER_HURT_FREEZE, 0.8f, Sound.BLOCK_GLASS_BREAK, 0.9f)),
            Map.entry("boss_lightning", new Style(ARC, Particle.ELECTRIC_SPARK, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.6f, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.4f)),
            Map.entry("boss_chaos", new Style(VOID, Particle.SOUL, Sound.ENTITY_WITHER_SHOOT, 0.7f, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8f)));

    /** Boss 的警告：紅色（大顆一點，遠遠就看得到）。 */
    private static final Particle.DustOptions WARNING = new Particle.DustOptions(Color.fromRGB(0xFF2A2A), 1.7f);

    private final RandomGenerator random;

    /**
     * 同一區每 tick 最多送幾個「畫形狀」的粒子封包（警告圈、擴散環、光束、新星的圈…；2026-10 打 Boss 時畫面偶爾卡一下）。
     * 超過的打薄，每個形狀至少留幾點看得出樣子；地板變色才是主要的警告。
     */
    static final int SHAPE_BUDGET = 150;
    private final ParticleBudget budget = new ParticleBudget(SHAPE_BUDGET);
    /**
     * 投射物軌跡另外一份預算（2026-10，減少粒子）：同一區每 tick 最多幾個軌跡點，超過的投射物軌跡變稀（每段至少 2 點）。
     * 以前每 0.35 格一點、沒有上限，多重投射＋連鎖時一個 tick 可以送出好幾百個封包。
     */
    static final int TRAIL_BUDGET = 90;
    /** 軌跡點的間距（格）。 */
    static final double TRAIL_STEP = 0.5;
    private final ParticleBudget trailBudget = new ParticleBudget(TRAIL_BUDGET);

    /** 看地上平面標示的玩家（套了介面資源包）：地上警告的粒子不用再送給他們。 */
    private java.util.function.Predicate<Player> marked = player -> false;
    /** 地上警告的粒子送給幾格內的玩家。 */
    private static final double WARN_VIEW = 48;

    SkillVisuals(final RandomGenerator random) {
        this.random = random;
    }

    /** 誰看地上的平面標示（套了介面資源包的人）。 */
    void marks(final java.util.function.Predicate<Player> marked) {
        this.marked = marked == null ? player -> false : marked;
    }

    /**
     * 地上警告的粒子要送給誰：附近沒套資源包的玩家（套了的看地上的平面標示，粒子一點一個封包，不用再送）。
     * 空的就不用畫。
     */
    /** Boss 招式的預警要不要畫粒子（2026-10 關掉：只看地上的範圍；沒套資源包的人看地板變色）。 */
    static final boolean WARNING_PARTICLES = false;

    private List<Player> warned(final Location at) {
        final List<Player> viewers = new java.util.ArrayList<>(2);
        for (final Player player : at.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(at) <= WARN_VIEW * WARN_VIEW && !marked.test(player)) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    private static void dust(final List<Player> viewers, final Location at, final Particle.DustOptions dust) {
        for (final Player player : viewers) {
            player.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
        }
    }

    /** 在 at 這一區想畫 wanted 點：照預算這次可以畫幾點（至少 floor 點）。 */
    private int allow(final Location at, final int wanted, final int floor) {
        return budget.allow(org.bukkit.Bukkit.getCurrentTick(),
                ParticleBudget.area(at.getWorld().getUID().hashCode(), at.getBlockX(), at.getBlockZ()), wanted, floor);
    }

    /** 想畫 wanted 點、預算只給 granted 點時，間距要放大幾倍（不會小於 1）。 */
    private static double thin(final int wanted, final int granted) {
        return granted >= wanted || granted <= 0 ? 1.0 : wanted / (double) granted;
    }

    private static Style style(final String visual) {
        return STYLES.get(visual);
    }

    // ---- 施放 ----

    /** 放出去那一下的聲音（投射物、法術）。 */
    void cast(final String visual, final Player player) {
        cast(visual, player.getLocation());
    }

    void cast(final String visual, final Location at) {
        final Style style = style(visual);
        if (style != null) {
            sound(at, style.cast(), 0.8f, style.castPitch());
            return;
        }
        switch (visual) {
            case "arrow" -> sound(at, Sound.ENTITY_ARROW_SHOOT, 0.8f, 1.1f);
            case "burning_arrow" -> {
                sound(at, Sound.ENTITY_ARROW_SHOOT, 0.8f, 0.9f);
                sound(at, Sound.ITEM_FIRECHARGE_USE, 0.4f, 1.4f);
            }
            case "wand_bolt" -> sound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.6f);
            case "staff_bolt" -> sound(at, Sound.ENTITY_BREEZE_SHOOT, 0.6f, 1.3f);
            case "fireball" -> sound(at, Sound.ENTITY_BLAZE_SHOOT, 0.7f, 1.1f);
            case "spark" -> sound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.6f);
            case "crossbow_bolt" -> sound(at, Sound.ITEM_CROSSBOW_SHOOT, 0.8f, 1.1f);
            case "explosive_bolt" -> {
                sound(at, Sound.ITEM_CROSSBOW_SHOOT, 0.8f, 0.9f);
                sound(at, Sound.ITEM_FIRECHARGE_USE, 0.4f, 1.3f);
            }
            case "permafrost_bolt" -> {
                sound(at, Sound.ITEM_CROSSBOW_SHOOT, 0.8f, 1.2f);
                sound(at, Sound.ENTITY_PLAYER_HURT_FREEZE, 0.3f, 1.6f);
            }
            case "lightning_spear" -> {
                sound(at, Sound.ITEM_TRIDENT_THROW, 0.9f, 1.1f);
                sound(at, Sound.ITEM_TRIDENT_THUNDER, 0.12f, 1.8f);
            }
            case "chaos_bolt" -> sound(at, Sound.ENTITY_EVOKER_CAST_SPELL, 0.6f, 1.4f);
            default -> {
            }
        }
    }

    /** 原版箭矢（弓、弩的技能）飛行時每 tick 的軌跡；一般的箭靠原版的暴擊粒子。 */
    void arrowTrail(final String visual, final Location at) {
        final World world = at.getWorld();
        switch (visual) {
            case "lightning_arrow" -> tw.exilecore.skill.Fx.spawn(world, Particle.ELECTRIC_SPARK, at, 1, 0.03, 0.03, 0.03, 0.01);
            case "ice_shot" -> tw.exilecore.skill.Fx.spawn(world, Particle.SNOWFLAKE, at, 1, 0.03, 0.03, 0.03, 0.005);
            case "tornado_shot" -> tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0.03, 0.03, 0.03, 0, WIND);
            case "blink_arrow" -> tw.exilecore.skill.Fx.spawn(world, Particle.END_ROD, at, 1, 0.01, 0.01, 0.01, 0);
            case "explosive_bolt" -> tw.exilecore.skill.Fx.spawn(world, Particle.SMOKE, at, 1, 0.02, 0.02, 0.02, 0.01);
            case "permafrost_bolt" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.SNOWFLAKE, at, 1, 0.03, 0.03, 0.03, 0.005);
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0.02, 0.02, 0.02, 0, FROST);
            }
            default -> {
            }
        }
    }

    /** 失敗（魔力不足、營地裡）的提示音。 */
    void fail(final Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.6f, 0.7f);
    }

    // ---- 投射物 ----

    /** 魔法彈飛過的軌跡。 */
    void trail(final String visual, final World world, final Vector from, final Vector to) {
        final Vector step = to.clone().subtract(from);
        final double length = step.length();
        if (length < 1e-6) {
            return;
        }
        final int wanted = Math.max(1, (int) Math.ceil(length / TRAIL_STEP));
        final int points = trailBudget.allow(org.bukkit.Bukkit.getCurrentTick(),
                ParticleBudget.area(world.getUID().hashCode(), to.getBlockX(), to.getBlockZ()), wanted, 2);
        if (points <= 0) {
            return;
        }
        step.multiply(1.0 / points);
        final Vector point = from.clone();
        for (int i = 0; i < points; i++) {
            point.add(step);
            final Location at = point.toLocation(world);
            switch (visual) {
                case "wand_bolt" -> {
                    tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0.03, 0.03, 0.03, 0, WAND);
                    if (i % 3 == 0) {
                        tw.exilecore.skill.Fx.spawn(world, Particle.WITCH, at, 1, 0.02, 0.02, 0.02, 0);
                    }
                }
                case "staff_bolt" -> {
                    tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 2, 0.06, 0.06, 0.06, 0, STAFF);
                    if (i % 2 == 0) {
                        tw.exilecore.skill.Fx.spawn(world, Particle.END_ROD, at, 1, 0.02, 0.02, 0.02, 0);
                    }
                }
                case "fireball" -> {
                    tw.exilecore.skill.Fx.spawn(world, Particle.FLAME, at, 2, 0.08, 0.08, 0.08, 0.01);
                    if (i % 2 == 0) {
                        tw.exilecore.skill.Fx.spawn(world, Particle.SMOKE, at, 1, 0.05, 0.05, 0.05, 0.01);
                    }
                }
                case "spark" -> {
                    tw.exilecore.skill.Fx.spawn(world, Particle.ELECTRIC_SPARK, at, 1, 0.05, 0.05, 0.05, 0.02);
                    tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0.04, 0.04, 0.04, 0, SPARK);
                }
                case "lightning_spear" -> {
                    tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0.02, 0.02, 0.02, 0, ARC);
                    if (i % 2 == 0) {
                        tw.exilecore.skill.Fx.spawn(world, Particle.ELECTRIC_SPARK, at, 1, 0.06, 0.06, 0.06, 0.02);
                    } else {
                        tw.exilecore.skill.Fx.spawn(world, Particle.END_ROD, at, 1, 0.01, 0.01, 0.01, 0);
                    }
                }
                case "chaos_bolt" -> {
                    tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 2, 0.05, 0.05, 0.05, 0, VOID);
                    if (i % 3 == 0) {
                        tw.exilecore.skill.Fx.spawn(world, Particle.SQUID_INK, at, 1, 0.02, 0.02, 0.02, 0.005);
                    }
                }
                case "storm_wave" -> {
                    // 一道貼地的電牆：左右各一格也畫
                    tw.exilecore.skill.Fx.spawn(world, Particle.ELECTRIC_SPARK, at, 2, 0.6, 0.3, 0.6, 0.02);
                    tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 2, 0.5, 0.2, 0.5, 0, ARC);
                }
                case "whirlwind_lance" -> {
                    tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0.03, 0.03, 0.03, 0, WIND);
                    if (i % 2 == 0) {
                        tw.exilecore.skill.Fx.spawn(world, Particle.CLOUD, at, 1, 0.05, 0.05, 0.05, 0.01);
                    }
                }
                case "explosive_grenade" -> {
                    tw.exilecore.skill.Fx.spawn(world, Particle.SMOKE, at, 1, 0.02, 0.02, 0.02, 0.01);
                    if (i % 2 == 0) {
                        tw.exilecore.skill.Fx.spawn(world, Particle.FLAME, at, 1, 0.02, 0.02, 0.02, 0.005);
                    }
                }
                case "gas_grenade" -> tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0.03, 0.03, 0.03, 0, GAS);
                default -> {
                    final Style style = style(visual);
                    if (style != null) {
                        tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0.03, 0.03, 0.03, 0, style.dust());
                    } else {
                        tw.exilecore.skill.Fx.spawn(world, Particle.CRIT, at, 1, 0, 0, 0, 0);
                    }
                }
            }
        }
    }

    /** 投射物打中東西。 */
    void impact(final String visual, final Location at) {
        final World world = at.getWorld();
        switch (visual) {
            case "arrow" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.CRIT, at, 6, 0.15, 0.15, 0.15, 0.2);
                sound(at, Sound.ENTITY_ARROW_HIT, 0.6f, 1.2f);
            }
            case "burning_arrow" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.FLAME, at, 8, 0.2, 0.2, 0.2, 0.03);
                sound(at, Sound.ENTITY_ARROW_HIT, 0.6f, 0.9f);
            }
            case "wand_bolt" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 8, 0.2, 0.2, 0.2, 0, WAND);
                tw.exilecore.skill.Fx.spawn(world, Particle.ENCHANTED_HIT, at, 4, 0.1, 0.1, 0.1, 0.1);
            }
            case "staff_bolt" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 10, 0.25, 0.25, 0.25, 0, STAFF);
                tw.exilecore.skill.Fx.spawn(world, Particle.END_ROD, at, 3, 0.1, 0.1, 0.1, 0.02);
            }
            case "spark" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.ELECTRIC_SPARK, at, 8, 0.2, 0.2, 0.2, 0.1);
                sound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.4f, 2.0f);
            }
            case "crossbow_bolt" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.CRIT, at, 6, 0.15, 0.15, 0.15, 0.2);
                sound(at, Sound.ENTITY_ARROW_HIT, 0.6f, 1.0f);
            }
            case "explosive_bolt" -> tw.exilecore.skill.Fx.spawn(world, Particle.FLAME, at, 6, 0.15, 0.15, 0.15, 0.03);
            case "permafrost_bolt" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.SNOWFLAKE, at, 8, 0.2, 0.2, 0.2, 0.03);
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 6, 0.2, 0.2, 0.2, 0, FROST);
                sound(at, Sound.BLOCK_GLASS_BREAK, 0.4f, 1.8f);
            }
            case "lightning_spear" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.ELECTRIC_SPARK, at, 10, 0.25, 0.3, 0.25, 0.12);
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 6, 0.2, 0.2, 0.2, 0, ARC);
                sound(at, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.25f, 1.9f);
            }
            case "chaos_bolt" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 10, 0.25, 0.25, 0.25, 0, VOID);
                tw.exilecore.skill.Fx.spawn(world, Particle.PORTAL, at, 14, 0.2, 0.2, 0.2, 0.4);
                sound(at, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.35f, 1.6f);
            }
            default -> {
                final Style style = style(visual);
                if (style != null) {
                    tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 8, 0.2, 0.2, 0.2, 0, style.dust());
                    tw.exilecore.skill.Fx.spawn(world, style.particle(), at, 5, 0.15, 0.15, 0.15, 0.05);
                    sound(at, style.hit(), 0.5f, style.hitPitch());
                } else {
                    tw.exilecore.skill.Fx.spawn(world, Particle.CRIT, at, 4, 0.1, 0.1, 0.1, 0.1);
                }
            }
        }
    }

    /** 爆炸（熾星彈、爆裂弩箭）。 */
    void explosion(final String visual, final Location at, final double radius) {
        final World world = at.getWorld();
        if ("ice_shot".equals(visual)) {
            tw.exilecore.skill.Fx.spawn(world, Particle.SNOWFLAKE, at, (int) Math.min(50, 14 * radius), radius / 2, radius / 3, radius / 2, 0.04);
            tw.exilecore.skill.Fx.spawn(world, Particle.BLOCK, at, (int) Math.min(30, 8 * radius), radius / 2, 0.3, radius / 2, 0.1, ICE);
            ring(world, at, radius, Particle.DUST, FROST);
            sound(at, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.3f);
            sound(at, Sound.ENTITY_PLAYER_HURT_FREEZE, 0.6f, 1.2f);
            return;
        }
        tw.exilecore.skill.Fx.spawn(world, Particle.EXPLOSION, at, 1, 0, 0, 0, 0);
        tw.exilecore.skill.Fx.spawn(world, Particle.FLAME, at, (int) Math.min(40, 10 * radius), radius / 2, radius / 3, radius / 2, 0.05);
        tw.exilecore.skill.Fx.spawn(world, Particle.LARGE_SMOKE, at, 4, radius / 3, radius / 4, radius / 3, 0.01);
        final float pitch = switch (visual) {
            case "explosive_bolt" -> 1.2f;
            case "explosive_grenade" -> 0.9f;
            default -> 1.5f;
        };
        sound(at, Sound.ENTITY_GENERIC_EXPLODE, "explosive_grenade".equals(visual) ? 0.8f : 0.5f, pitch);
    }

    /** 榴彈彈了一下。 */
    void bounce(final String visual, final Location at) {
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.CLOUD, at, 2, 0.05, 0.05, 0.05, 0.01);
        sound(at, Sound.BLOCK_METAL_HIT, 0.4f, "gas_grenade".equals(visual) ? 1.6f : 1.2f);
    }

    /** 飛到盡頭消失。 */
    void fizzle(final String visual, final Location at) {
        if ("fireball".equals(visual)) {
            tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SMOKE, at, 4, 0.1, 0.1, 0.1, 0.01);
        }
    }

    // ---- 近戰與範圍 ----

    /** 近戰打擊：打中的目標冒火花，橫劈畫一道弧。 */
    void strike(final String visual, final Origin origin, final List<LivingEntity> hits, final double reach, final double angle) {
        final World world = origin.world();
        final Location at = origin.feet();
        final Style style = style(visual);
        if (style != null) {
            sound(at, style.cast(), 0.8f, style.castPitch());
            if (!hits.isEmpty()) {
                sound(at, style.hit(), 0.5f, style.hitPitch());
            }
            if ("killing_palm".equals(visual)) {
                final Vector facing = origin.facing();
                final Location palm = at.clone().add(facing.clone().multiply(1.4)).add(0, 1.2, 0);
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, palm, 12, 0.25, 0.25, 0.25, 0, BLOOD);
                tw.exilecore.skill.Fx.spawn(world, Particle.SWEEP_ATTACK, palm, 1, 0, 0, 0, 0);
            }
            for (final LivingEntity target : hits) {
                tw.exilecore.skill.Fx.spawn(world, Particle.CRIT, SkillTargets.center(target), 10, 0.25, 0.3, 0.25, 0.25);
            }
            return;
        }
        switch (visual) {
            case "heavy_strike" -> {
                sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 0.8f);
                if (!hits.isEmpty()) {
                    sound(at, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.35f, 1.3f);
                }
            }
            case "cleave" -> {
                sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f, 0.9f);
                final Vector facing = origin.facing();
                final int points = Math.max(3, (int) (angle / 25));
                for (int i = 0; i < points; i++) {
                    final double degrees = -angle / 2 + angle * i / (points - 1);
                    final Vector direction = facing.clone().rotateAroundY(Math.toRadians(degrees)).multiply(reach * 0.7);
                    tw.exilecore.skill.Fx.spawn(world, Particle.SWEEP_ATTACK, at.clone().add(direction).add(0, 1.0, 0), 1, 0, 0, 0, 0);
                }
            }
            case "staff_sweep" -> {
                sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.3f);
                arc(world, origin, reach, Math.min(angle, 120));
            }
            case "tempest" -> {
                sound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.5f);
                if (!hits.isEmpty()) {
                    sound(at, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.2f, 2.0f);
                }
                arc(world, origin, reach, Math.min(angle, 120));
            }
            case "spear_thrust" -> {
                sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.5f);
                final Vector facing = origin.facing();
                for (double distance = 1.0; distance <= reach; distance += 0.5) {
                    tw.exilecore.skill.Fx.spawn(world, Particle.CRIT, at.clone().add(facing.clone().multiply(distance)).add(0, 1.2, 0), 1, 0.02, 0.02, 0.02, 0);
                }
            }
            default -> sound(at, hits.isEmpty() ? Sound.ENTITY_PLAYER_ATTACK_WEAK : Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.1f);
        }
        for (final LivingEntity target : hits) {
            final Location center = SkillTargets.center(target);
            tw.exilecore.skill.Fx.spawn(world, Particle.CRIT, center, "heavy_strike".equals(visual) ? 14 : 6, 0.25, 0.3, 0.25, 0.25);
            if ("slash".equals(visual)) {
                tw.exilecore.skill.Fx.spawn(world, Particle.SWEEP_ATTACK, center, 1, 0, 0, 0, 0);
            }
            if ("tempest".equals(visual)) {
                tw.exilecore.skill.Fx.spawn(world, Particle.ELECTRIC_SPARK, center, 10, 0.3, 0.4, 0.3, 0.15);
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, center, 4, 0.25, 0.3, 0.25, 0, ARC);
            }
        }
    }

    /** 處決：目標身上噴一下血色。 */
    void execute(final Location center) {
        tw.exilecore.skill.Fx.spawn(center.getWorld(), Particle.DUST, center, 16, 0.35, 0.4, 0.35, 0, BLOOD);
        tw.exilecore.skill.Fx.spawn(center.getWorld(), Particle.CRIT, center, 10, 0.3, 0.3, 0.3, 0.3);
        sound(center, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.6f);
    }

    /** 揮出一道弧（長棍）：面前幾個橫掃的粒子。 */
    private static void arc(final World world, final Origin origin, final double reach, final double angle) {
        final Location at = origin.feet();
        final Vector facing = origin.facing();
        final int points = Math.max(3, (int) (angle / 30));
        for (int i = 0; i < points; i++) {
            final double degrees = -angle / 2 + angle * i / (points - 1);
            final Vector direction = facing.clone().rotateAroundY(Math.toRadians(degrees)).multiply(reach * 0.65);
            tw.exilecore.skill.Fx.spawn(world, Particle.SWEEP_ATTACK, at.clone().add(direction).add(0, 1.0, 0), 1, 0, 0, 0, 0);
        }
    }

    /** 以自己為中心的一圈：霜星爆（幾圈冰霜）、旋矛擊（一圈橫掃）、枯萎新星（幾圈腐化的混沌）。 */
    void nova(final String visual, final Location center, final double radius) {
        final World world = center.getWorld();
        final Style style = style(visual);
        if (style != null) {
            switch (visual) {
                case "earthquake" -> {
                    tw.exilecore.skill.Fx.spawn(world, Particle.EXPLOSION, center.clone().add(0, 0.3, 0), 1, 0, 0, 0, 0);
                    for (double ring = 1.0; ring <= radius + 1e-6; ring += 1.0) {
                        for (int i = 0; i < 12; i++) {
                            final double angle = Math.PI * 2 * i / 12;
                            debris(world, center.clone().add(Math.cos(angle) * ring, 0.2, Math.sin(angle) * ring), 2);
                        }
                    }
                    sound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0f, 0.7f);
                }
                case "frostblink" -> {
                    ring(world, center, radius, Particle.DUST, FROST);
                    for (double ring = 1.0; ring < radius - 0.5; ring += 1.5) {
                        ring(world, center, ring, Particle.SNOWFLAKE, null);
                    }
                    sound(center, Sound.BLOCK_GLASS_BREAK, 0.7f, 1.5f);
                }
                default -> {
                    ring(world, center, radius, Particle.DUST, style.dust());
                    tw.exilecore.skill.Fx.spawn(world, style.particle(), center.clone().add(0, 0.5, 0), (int) Math.min(30, 6 * radius), radius / 3, 0.2, radius / 3, 0.03);
                    sound(center, style.hit(), 0.7f, style.hitPitch());
                }
            }
            return;
        }
        switch (visual) {
            case "whirl" -> {
                final int points = Math.max(8, (int) (radius * 3));
                for (int i = 0; i < points; i++) {
                    final double angle = Math.PI * 2 * i / points;
                    final Location at = center.clone().add(Math.cos(angle) * radius * 0.7, 1.0, Math.sin(angle) * radius * 0.7);
                    tw.exilecore.skill.Fx.spawn(world, Particle.SWEEP_ATTACK, at, 1, 0, 0, 0, 0);
                }
                tw.exilecore.skill.Fx.spawn(world, Particle.CLOUD, center.clone().add(0, 0.3, 0), 8, radius / 3, 0.05, radius / 3, 0.02);
                sound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.8f);
                sound(center, Sound.ENTITY_BREEZE_WIND_BURST, 0.4f, 1.3f);
            }
            case "blight_nova" -> {
                ring(world, center, radius, Particle.SQUID_INK, null);
                for (double ring = 1.0; ring <= radius + 1e-6; ring += 1.5) {
                    ring(world, center, ring, Particle.DUST, ring % 3 < 1.5 ? VOID : BLIGHT);
                }
                tw.exilecore.skill.Fx.spawn(world, Particle.SOUL, center.clone().add(0, 0.4, 0), 8, radius / 3, 0.1, radius / 3, 0.02);
                sound(center, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.6f, 1.2f);
                sound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.4f, 0.8f);
            }
            default -> {
                ring(world, center, radius, Particle.DUST, FROST);
                for (double ring = 1.0; ring < radius - 0.5; ring += 1.5) {
                    ring(world, center, ring, Particle.SNOWFLAKE, null);
                }
                tw.exilecore.skill.Fx.spawn(world, Particle.CLOUD, center.clone().add(0, 0.3, 0), 12, radius / 3, 0.1, radius / 3, 0.02);
                sound(center, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.6f);
                sound(center, Sound.ENTITY_PLAYER_HURT_FREEZE, 0.8f, 1.0f);
            }
        }
    }

    /** 前方扇形：崩土擊（沿著幾條線炸開地面）、寒冰震波（往前散開的冰霜）。 */
    void cone(final String visual, final Origin from, final double length, final double angle) {
        final World world = from.world();
        final Location origin = from.feet();
        final Vector facing = from.facing();
        final boolean ice = "ice_cone".equals(visual);
        final int rays = 5;
        for (int r = 0; r < rays; r++) {
            final double degrees = -angle / 2 + angle * r / (rays - 1);
            final Vector direction = facing.clone().rotateAroundY(Math.toRadians(degrees));
            for (double distance = 1.0; distance <= length; distance += 0.9) {
                final Location at = origin.clone().add(direction.clone().multiply(distance)).add(0, 0.2, 0);
                if (ice) {
                    tw.exilecore.skill.Fx.spawn(world, Particle.SNOWFLAKE, at, 2, 0.15, 0.1, 0.15, 0.02);
                    tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0.1, 0.1, 0.1, 0, FROST);
                    tw.exilecore.skill.Fx.spawn(world, Particle.BLOCK, at, 2, 0.15, 0.1, 0.15, 0.1, ICE);
                } else {
                    debris(world, at, 3);
                }
            }
        }
        if (ice) {
            sound(origin, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.2f);
            sound(origin, Sound.ENTITY_PLAYER_HURT_FREEZE, 0.6f, 1.0f);
        } else {
            sound(origin, Sound.ITEM_MACE_SMASH_GROUND, 1.0f, 0.8f);
            sound(origin, Sound.ENTITY_GENERIC_EXPLODE, 0.35f, 0.8f);
        }
    }

    /** 起跳。 */
    void leapStart(final Player player) {
        final Location at = player.getLocation();
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.CLOUD, at, 10, 0.3, 0.05, 0.3, 0.05);
        sound(at, Sound.ENTITY_GOAT_LONG_JUMP, 0.9f, 0.9f);
    }

    /** 落地重擊。 */
    void leapLand(final Location at, final double radius) {
        final World world = at.getWorld();
        tw.exilecore.skill.Fx.spawn(world, Particle.EXPLOSION, at.clone().add(0, 0.3, 0), 1, 0, 0, 0, 0);
        for (double ring = 1.0; ring <= radius + 1e-6; ring += 1.0) {
            for (int i = 0; i < 12; i++) {
                final double angle = Math.PI * 2 * i / 12;
                debris(world, at.clone().add(Math.cos(angle) * ring, 0.2, Math.sin(angle) * ring), 2);
            }
        }
        sound(at, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0f, 1.0f);
    }

    /** 鏈雷：一段一段折線的閃電。 */
    void chain(final List<Location> points) {
        for (int i = 0; i + 1 < points.size(); i++) {
            bolt(points.get(i), points.get(i + 1));
        }
        if (!points.isEmpty()) {
            sound(points.get(0), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.35f, 1.8f);
        }
        for (int i = 1; i < points.size(); i++) {
            tw.exilecore.skill.Fx.spawn(points.get(i).getWorld(), Particle.ELECTRIC_SPARK, points.get(i), 8, 0.2, 0.3, 0.2, 0.1);
        }
    }

    private void bolt(final Location from, final Location to) {
        final World world = from.getWorld();
        final Vector delta = to.toVector().subtract(from.toVector());
        final double length = delta.length();
        if (length < 1e-6) {
            return;
        }
        final int segments = Math.max(2, (int) (length / 1.2));
        Vector previous = from.toVector();
        for (int s = 1; s <= segments; s++) {
            final Vector next = from.toVector().add(delta.clone().multiply(s / (double) segments));
            if (s < segments) {
                next.add(new Vector(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5).multiply(0.6));
            }
            final Vector step = next.clone().subtract(previous);
            final int dots = Math.max(1, (int) (step.length() / 0.25));
            for (int d = 1; d <= dots; d++) {
                final Location at = previous.clone().add(step.clone().multiply(d / (double) dots)).toLocation(world);
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0, 0, 0, 0, ARC);
                if (d % 3 == 0) {
                    tw.exilecore.skill.Fx.spawn(world, Particle.ELECTRIC_SPARK, at, 1, 0.02, 0.02, 0.02, 0);
                }
            }
            previous = next;
        }
    }

    // ---- 位移、地面效果、延遲落下、印記 ----

    /** 位移：起點一團、沿路一道、終點一團。 */
    void dash(final String visual, final Location from, final Location to) {
        final World world = from.getWorld();
        final Style style = style(visual);
        final Particle.DustOptions dust = style == null ? WIND : style.dust();
        final Particle particle = style == null ? Particle.CLOUD : style.particle();
        final Vector delta = to.toVector().subtract(from.toVector());
        final int points = Math.max(2, (int) (delta.length() / 0.6));
        for (int i = 0; i <= points; i++) {
            final Location at = from.clone().add(delta.clone().multiply(i / (double) points)).add(0, 1.0, 0);
            tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 2, 0.15, 0.3, 0.15, 0, dust);
            if (i % 2 == 0) {
                tw.exilecore.skill.Fx.spawn(world, particle, at, 1, 0.1, 0.2, 0.1, 0.01);
            }
        }
        tw.exilecore.skill.Fx.spawn(world, Particle.CLOUD, from.clone().add(0, 0.2, 0), 8, 0.3, 0.05, 0.3, 0.03);
        tw.exilecore.skill.Fx.spawn(world, Particle.CLOUD, to.clone().add(0, 0.2, 0), 8, 0.3, 0.05, 0.3, 0.03);
        sound(from, style == null ? Sound.ENTITY_BREEZE_WIND_BURST : style.cast(), 0.8f, style == null ? 1.4f : style.castPitch());
    }

    /** 閃現（瞬影箭的瞬移）。 */
    void blink(final Location from, final Location to) {
        tw.exilecore.skill.Fx.spawn(from.getWorld(), Particle.END_ROD, from.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
        tw.exilecore.skill.Fx.spawn(to.getWorld(), Particle.END_ROD, to.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
        sound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.4f);
    }

    /** 延遲落下的預警：地上一圈越來越密、越亮。 */
    void impactWarning(final String visual, final Location center, final double radius, final double progress) {
        final World world = center.getWorld();
        final Style style = style(visual);
        final Particle.DustOptions dust = style == null ? GOLD : style.dust();
        final int points = allow(center, (int) Math.max(8, Math.min(48, radius * 6 * (0.4 + progress))), 8);
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2 * i / points + progress * 2;
            tw.exilecore.skill.Fx.spawn(world, Particle.DUST, center.clone().add(Math.cos(angle) * radius, 0.2, Math.sin(angle) * radius), 1, 0, 0, 0, 0, dust);
        }
        if ("comet".equals(visual) || "rain_of_arrows".equals(visual)) {
            // 從天上掉下來：上方一條亮線
            final double height = 10 * (1 - progress);
            tw.exilecore.skill.Fx.spawn(world, style == null ? Particle.END_ROD : style.particle(), center.clone().add(0, height, 0), 3, 0.3, 0.3, 0.3, 0.01);
        }
    }

    /** 延遲落下炸開。 */
    void blast(final String visual, final Location center, final double radius) {
        final World world = center.getWorld();
        switch (visual) {
            case "rain_of_arrows" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.CRIT, center.clone().add(0, 1, 0), (int) Math.min(80, 16 * radius), radius / 2, 0.8, radius / 2, 0.3);
                for (int i = 0; i < 6; i++) {
                    debris(world, center.clone().add((random.nextDouble() * 2 - 1) * radius, 0.1, (random.nextDouble() * 2 - 1) * radius), 2);
                }
                sound(center, Sound.ENTITY_ARROW_HIT, 1.0f, 0.9f);
                sound(center, Sound.ENTITY_ARROW_HIT, 1.0f, 1.3f);
            }
            case "comet" -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.EXPLOSION_EMITTER, center.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
                tw.exilecore.skill.Fx.spawn(world, Particle.SNOWFLAKE, center, (int) Math.min(80, 16 * radius), radius / 2, 0.5, radius / 2, 0.08);
                tw.exilecore.skill.Fx.spawn(world, Particle.BLOCK, center, (int) Math.min(40, 8 * radius), radius / 2, 0.3, radius / 2, 0.15, ICE);
                ring(world, center, radius, Particle.DUST, FROST);
                sound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.9f);
                sound(center, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);
            }
            default -> {
                nova(visual, center, radius);
            }
        }
    }

    /** 地面效果平常的樣子（每 2 tick 一次，稀疏一點）。 */
    void zoneAmbient(final String visual, final List<Location> points, final double radius, final long now) {
        final World world = points.getFirst().getWorld();
        final Style style = style(visual);
        final Particle.DustOptions dust = style == null ? GOLD : style.dust();
        final Particle particle = style == null ? Particle.CRIT : style.particle();
        for (final Location point : points) {
            final int count = allow(point, Math.max(2, Math.min(10, (int) Math.round(radius * 1.6))), 2);
            for (int i = 0; i < count; i++) {
                final double angle = random.nextDouble() * Math.PI * 2;
                final double distance = Math.sqrt(random.nextDouble()) * radius;
                final Location at = point.clone().add(Math.cos(angle) * distance, 0.15 + random.nextDouble() * 0.6, Math.sin(angle) * distance);
                if ((i + now) % 3 == 0) {
                    tw.exilecore.skill.Fx.spawn(world, particle, at, 1, 0, 0, 0, visual.equals("void_rift") ? 0.3 : 0.01);
                } else {
                    tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0, 0, 0, 0, dust);
                }
            }
        }
        if (points.size() == 1 && now % 8 == 0) {
            ring(world, points.getFirst(), radius, Particle.DUST, dust);
        }
    }

    /** 地面效果打一波。 */
    void zonePulse(final String visual, final List<Location> points, final double radius) {
        final World world = points.getFirst().getWorld();
        final Style style = style(visual);
        for (final Location point : points) {
            tw.exilecore.skill.Fx.spawn(world, style == null ? Particle.CRIT : style.particle(), point.clone().add(0, 0.4, 0),
                    (int) Math.max(3, radius * 3), radius / 2, 0.2, radius / 2, 0.03);
        }
        if (style != null) {
            sound(points.getFirst(), style.hit(), 0.35f, style.hitPitch());
        }
    }

    /** 濺射（雷鳴箭）：從目標電到其他敵人。 */
    void splash(final String visual, final List<Location> points) {
        for (int i = 1; i < points.size(); i++) {
            bolt(points.get(0), points.get(i));
        }
        if (!points.isEmpty()) {
            sound(points.get(0), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.3f, 1.9f);
        }
    }

    /** 散射（裂空箭）。 */
    void scatter(final String visual, final Location at) {
        final World world = at.getWorld();
        tw.exilecore.skill.Fx.spawn(world, Particle.CLOUD, at.clone().add(0, 0.5, 0), 16, 0.4, 0.4, 0.4, 0.08);
        tw.exilecore.skill.Fx.spawn(world, Particle.SWEEP_ATTACK, at.clone().add(0, 0.8, 0), 2, 0.3, 0.2, 0.3, 0);
        sound(at, Sound.ENTITY_BREEZE_SHOOT, 0.7f, 1.3f);
    }

    /** 印記施加在目標上。 */
    void mark(final String visual, final LivingEntity target) {
        final Location head = target.getLocation().add(0, target.getHeight() + 0.5, 0);
        final World world = head.getWorld();
        final Style style = style(visual);
        final Particle.DustOptions dust = style == null ? GOLD : style.dust();
        for (int i = 0; i < 12; i++) {
            final double angle = Math.PI * 2 * i / 12;
            tw.exilecore.skill.Fx.spawn(world, Particle.DUST, head.clone().add(Math.cos(angle) * 0.5, 0, Math.sin(angle) * 0.5), 1, 0, 0, 0, 0, dust);
        }
        tw.exilecore.skill.Fx.spawn(world, Particle.END_ROD, head, 6, 0.2, 0.2, 0.2, 0.02);
        sound(head, style == null ? Sound.ENTITY_EVOKER_CAST_SPELL : style.cast(), 0.7f, style == null ? 1.6f : style.castPitch());
    }

    /** 雙詠：再放一次的那一下。 */
    void echo(final Location at) {
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.ENCHANT, at.clone().add(0, 1.2, 0), 20, 0.4, 0.5, 0.4, 0.5);
        sound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6f, 1.5f);
    }

    /** 印記持續中：頭上一小圈（每 10 tick）。 */
    void markTick(final LivingEntity target) {
        final Location head = target.getLocation().add(0, target.getHeight() + 0.4, 0);
        for (int i = 0; i < 6; i++) {
            final double angle = Math.PI * 2 * i / 6;
            tw.exilecore.skill.Fx.spawn(head.getWorld(), Particle.DUST, head.clone().add(Math.cos(angle) * 0.4, 0, Math.sin(angle) * 0.4), 1, 0, 0, 0, 0, GOLD);
        }
    }

    // ---- 圖騰、陷阱、鐘、骸爆 ----

    /** 放下去。 */
    void deploy(final String visual, final DeploySpec.Kind kind, final Location at) {
        final World world = at.getWorld();
        switch (kind) {
            case TOTEM -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.CLOUD, at.clone().add(0, 0.2, 0), 10, 0.3, 0.1, 0.3, 0.02);
                debris(world, at.clone().add(0, 0.1, 0), 6);
                sound(at, Sound.BLOCK_WOOD_PLACE, 1.0f, 0.7f);
                sound(at, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.4f, 1.4f);
            }
            case TRAP -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.CRIT, at.clone().add(0, 0.3, 0), 6, 0.2, 0.1, 0.2, 0.05);
                sound(at, Sound.BLOCK_TRIPWIRE_ATTACH, 0.8f, 1.0f);
            }
            case BELL -> {
                tw.exilecore.skill.Fx.spawn(world, Particle.CLOUD, at.clone().add(0, 0.2, 0), 8, 0.3, 0.1, 0.3, 0.02);
                sound(at, Sound.BLOCK_ANVIL_PLACE, 0.6f, 1.4f);
            }
        }
    }

    /** 平常的樣子（每 10 tick）：圖騰腳邊一圈、陷阱一閃、鐘一圈。 */
    void deployIdle(final String visual, final DeploySpec.Kind kind, final Location feet, final double range) {
        final World world = feet.getWorld();
        final Style style = style(visual);
        final Particle.DustOptions dust = style == null ? GOLD : style.dust();
        switch (kind) {
            case TOTEM -> {
                ring(world, feet, 0.8, Particle.DUST, dust);
                tw.exilecore.skill.Fx.spawn(world, style == null ? Particle.ENCHANT : style.particle(), feet.clone().add(0, 1.6, 0), 2, 0.2, 0.3, 0.2, 0.02);
            }
            case TRAP -> tw.exilecore.skill.Fx.spawn(world, Particle.DUST, feet.clone().add(0, 0.3, 0), 2, 0.3, 0.05, 0.3, 0, dust);
            case BELL -> ring(world, feet, range, Particle.DUST, GOLD);
        }
    }

    /** 圖騰放了一次。 */
    void deployCast(final String visual, final Location feet) {
        final Style style = style(visual);
        tw.exilecore.skill.Fx.spawn(feet.getWorld(), style == null ? Particle.ENCHANT : style.particle(), feet.clone().add(0, 1.8, 0), 6, 0.2, 0.2, 0.2, 0.05);
    }

    /** 陷阱可以觸發了。 */
    void deployArmed(final Location feet) {
        tw.exilecore.skill.Fx.spawn(feet.getWorld(), Particle.END_ROD, feet.clone().add(0, 0.4, 0), 4, 0.15, 0.1, 0.15, 0.01);
        sound(feet, Sound.BLOCK_TRIPWIRE_CLICK_ON, 0.6f, 1.4f);
    }

    /** 陷阱觸發。 */
    void deployTrigger(final String visual, final Location feet) {
        final Style style = style(visual);
        tw.exilecore.skill.Fx.spawn(feet.getWorld(), Particle.EXPLOSION, feet.clone().add(0, 0.4, 0), 1, 0, 0, 0, 0);
        tw.exilecore.skill.Fx.spawn(feet.getWorld(), style == null ? Particle.CRIT : style.particle(), feet.clone().add(0, 0.6, 0), 14, 0.4, 0.3, 0.4, 0.08);
        sound(feet, Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.8f, 0.8f);
        sound(feet, Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.6f);
    }

    /** 被定住。 */
    void root(final LivingEntity target) {
        final Location at = target.getLocation().add(0, 0.2, 0);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.BLOCK, at, 20, 0.4, 0.1, 0.4, 0.05, Material.IRON_BARS.createBlockData());
        sound(at, Sound.BLOCK_CHAIN_PLACE, 0.9f, 0.8f);
    }

    /** 鐘響一次。 */
    void bellRing(final String visual, final Location feet, final double radius, final int ringsLeft) {
        final World world = feet.getWorld();
        ring(world, feet, radius, Particle.DUST, GOLD);
        ring(world, feet, radius * 0.6, Particle.ELECTRIC_SPARK, null);
        tw.exilecore.skill.Fx.spawn(world, Particle.SONIC_BOOM, feet.clone().add(0, 1.0, 0), 1, 0, 0, 0, 0);
        sound(feet, Sound.BLOCK_BELL_USE, 1.0f, ringsLeft <= 1 ? 0.7f : 1.0f);
        sound(feet, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.25f, 1.8f);
    }

    /** 收掉（時間到、響完、被新的擠掉）。 */
    void deployGone(final String visual, final DeploySpec.Kind kind, final Location feet, final boolean expired) {
        final World world = feet.getWorld();
        tw.exilecore.skill.Fx.spawn(world, Particle.CLOUD, feet.clone().add(0, 0.8, 0), 8, 0.3, 0.4, 0.3, 0.02);
        if (kind == DeploySpec.Kind.TOTEM) {
            sound(feet, Sound.BLOCK_WOOD_BREAK, 0.7f, 0.8f);
        } else if (kind == DeploySpec.Kind.BELL) {
            sound(feet, Sound.BLOCK_BELL_RESONATE, 0.5f, 0.6f);
        }
    }

    /** 骸爆。 */
    void detonate(final String visual, final Location center, final double radius) {
        final World world = center.getWorld();
        tw.exilecore.skill.Fx.spawn(world, Particle.EXPLOSION_EMITTER, center, 1, 0, 0, 0, 0);
        tw.exilecore.skill.Fx.spawn(world, Particle.FLAME, center, (int) Math.min(50, 12 * radius), radius / 2, 0.4, radius / 2, 0.06);
        tw.exilecore.skill.Fx.spawn(world, Particle.ITEM, center, 16, 0.4, 0.4, 0.4, 0.15, new org.bukkit.inventory.ItemStack(Material.BONE));
        tw.exilecore.skill.Fx.spawn(world, Particle.DUST, center, 12, 0.5, 0.5, 0.5, 0, BLOOD);
        ring(world, center, radius, Particle.DUST, FIRE);
        sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.8f);
        sound(center, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.5f, 0.6f);
    }

    // ---- 召喚物 ----

    /** 召出來：屍體裡爬出來（殭屍）是土塊碎屑，其他是一團靈魂粒子。 */
    void summon(final String visual, final Location at, final SummonSpec spec) {
        final World world = at.getWorld();
        final Style style = style(visual);
        final Particle.DustOptions dust = style == null ? ROT : style.dust();
        if (spec.corpse()) {
            tw.exilecore.skill.Fx.spawn(world, Particle.BLOCK, at.clone().add(0, 0.3, 0), 24, 0.4, 0.2, 0.4, 0.1, Material.ROOTED_DIRT.createBlockData());
            tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at.clone().add(0, 0.8, 0), 10, 0.3, 0.4, 0.3, 0, dust);
            sound(at, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.0f, 0.7f);
        } else {
            tw.exilecore.skill.Fx.spawn(world, Particle.SOUL, at.clone().add(0, 0.6, 0), 12, 0.3, 0.5, 0.3, 0.03);
            tw.exilecore.skill.Fx.spawn(world, style == null ? Particle.ENCHANT : style.particle(), at.clone().add(0, 1.0, 0), 14, 0.3, 0.5, 0.3, 0.04);
            sound(at, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.5f, spec.flying() ? 1.6f : 1.1f);
        }
        if (style != null) {
            sound(at, style.cast(), 0.8f, style.castPitch());
        }
    }

    /** 會飛的召喚物（焚魂）身後的火光（每 2 tick）。 */
    void minionTrail(final String visual, final Location at) {
        final Style style = style(visual);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), style == null ? Particle.SOUL : style.particle(), at, 1, 0.1, 0.1, 0.1, 0.01);
    }

    /** 召喚物打了一下（召喚物靜音：只有粒子）。 */
    void minionAttack(final String visual, final LivingEntity minion, final LivingEntity target) {
        final Style style = style(visual);
        final Location at = SkillTargets.center(target);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), style == null ? Particle.CRIT : style.particle(), at, 5, 0.2, 0.2, 0.2, 0.05);
    }

    /** 魔像吼一聲把怪引過來（召喚物靜音：只有光圈）。 */
    void taunt(final String visual, final Location at, final double radius) {
        final Style style = style(visual);
        ring(at.getWorld(), at, radius, Particle.DUST, style == null ? GOLD : style.dust());
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SONIC_BOOM, at.clone().add(0, 1.5, 0), 1, 0, 0, 0, 0);
    }

    /** 瞬移回主人身邊。 */
    void minionBlink(final Location at) {
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SOUL, at.clone().add(0, 0.8, 0), 8, 0.3, 0.4, 0.3, 0.02);
    }

    /** 收掉（時間到、被換掉、死了）：召喚物靜音（2026-10），只有煙。 */
    void minionGone(final String visual, final Location at, final SummonSpec spec, final boolean died) {
        final World world = at.getWorld();
        final Style style = style(visual);
        tw.exilecore.skill.Fx.spawn(world, Particle.SMOKE, at.clone().add(0, 0.8, 0), 10, 0.3, 0.5, 0.3, 0.02);
        tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at.clone().add(0, 0.8, 0), 8, 0.3, 0.5, 0.3, 0, style == null ? ROT : style.dust());
    }

    // ---- 光環、捷、戰吼 ----

    /** 光環開啟：腳邊一圈往外擴、往上飄。 */
    void auraOn(final String visual, final Player player) {
        final Style style = style(visual);
        final Location at = player.getLocation();
        ring(at.getWorld(), at, 1.5, Particle.DUST, style == null ? GOLD : style.dust());
        tw.exilecore.skill.Fx.spawn(at.getWorld(), style == null ? Particle.ENCHANT : style.particle(), at.clone().add(0, 1.0, 0), 20, 0.4, 0.6, 0.4, 0.03);
        sound(at, style == null ? Sound.BLOCK_BEACON_ACTIVATE : style.cast(), 0.8f, style == null ? 1.2f : style.castPitch());
    }

    /** 光環關閉。 */
    void auraOff(final String visual, final Player player) {
        final Location at = player.getLocation();
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SMOKE, at.clone().add(0, 1.0, 0), 10, 0.3, 0.5, 0.3, 0.02);
        sound(at, Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.2f);
    }

    /** 開著的光環（每 2 秒）：腳邊一圈，每個光環一種顏色、半徑錯開。 */
    /** 光環腳下的光圈半徑（第 index 個光環）。 */
    static double auraRingRadius(final int index) {
        return 0.9 + 0.25 * index;
    }

    /** 光環的顏色（腳下的光圈用）。 */
    /** 光環腳下法陣的符文（資源包的 exilecore:mark/rune_這個）：照光環的外觀挑。 */
    static String auraRune(final String visual) {
        if (visual == null) {
            return "star";
        }
        return switch (visual) {
            case "aura_cold" -> "cold";
            case "aura_fire" -> "fire";
            case "aura_lightning" -> "lightning";
            case "aura_armour" -> "armour";
            case "aura_evasion" -> "evasion";
            case "aura_es" -> "es";
            case "aura_haste" -> "haste";
            case "aura_purity" -> "purity";
            case "aura_life" -> "life";
            case "aura_mana" -> "mana";
            case "aura_precision" -> "precision";
            case "herald_ash" -> "ash";
            case "herald_thunder" -> "thunder";
            case "herald_ice" -> "ice";
            default -> "star";
        };
    }

    /** 主法陣的顏色：只有一個光環就是它的顏色；好幾個是各顏色的平均再調亮一點（不會偏向哪一個）。 */
    static int sigilColor(final List<String> visuals) {
        if (visuals.size() == 1) {
            return auraColor(visuals.getFirst());
        }
        double r = 0;
        double g = 0;
        double b = 0;
        for (final String visual : visuals) {
            final int rgb = auraColor(visual);
            r += rgb >> 16 & 0xFF;
            g += rgb >> 8 & 0xFF;
            b += rgb & 0xFF;
        }
        final int n = Math.max(1, visuals.size());
        final int rr = (int) Math.round(r / n * 0.6 + 255 * 0.4);
        final int gg = (int) Math.round(g / n * 0.6 + 255 * 0.4);
        final int bb = (int) Math.round(b / n * 0.6 + 255 * 0.4);
        return rr << 16 | gg << 8 | bb;
    }

    static int auraColor(final String visual) {
        final Style style = style(visual);
        return (style == null ? GOLD : style.dust()).getColor().asRGB();
    }

    /** 光環腳下的光圈（粒子）：只送給 viewers（沒套資源包的人；套了的看 {@link GroundDecals} 的平面圈）。 */
    void auraPulse(final List<Player> viewers, final Player player, final List<String> visuals) {
        if (viewers.isEmpty()) {
            return;
        }
        final Location at = player.getLocation().add(0, 0.1, 0);
        int index = 0;
        for (final String visual : visuals) {
            final Style style = style(visual);
            final double radius = auraRingRadius(index++);
            final int points = 10;
            final double offset = (at.getWorld().getFullTime() % 20) / 20.0 * Math.PI * 2 / points;
            for (int i = 0; i < points; i++) {
                final double angle = offset + Math.PI * 2 * i / points;
                GroundDecals.particle(viewers, Particle.DUST, new Location(at.getWorld(), at.getX() + Math.cos(angle) * radius, at.getY(),
                        at.getZ() + Math.sin(angle) * radius), 1, 0, 0, 0, 0, style == null ? GOLD : style.dust());
            }
        }
    }

    /** 捷的擊殺效果在屍體的位置觸發。 */
    void herald(final String visual, final Location at) {
        final Style style = style(visual);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), style == null ? Particle.FLAME : style.particle(), at.clone().add(0, 0.8, 0), 12, 0.3, 0.4, 0.3, 0.05);
        if (style != null) {
            sound(at, style.cast(), 0.6f, style.castPitch());
        }
    }

    /** 戰吼：音波擴開一圈。 */
    void warcry(final String visual, final Player player, final double radius) {
        final Style style = style(visual);
        final Location at = player.getLocation();
        final World world = at.getWorld();
        tw.exilecore.skill.Fx.spawn(world, Particle.SONIC_BOOM, at.clone().add(0, 1.2, 0), 1, 0, 0, 0, 0);
        ring(world, at, radius * 0.5, Particle.DUST, style == null ? GOLD : style.dust());
        ring(world, at, radius, Particle.DUST, style == null ? GOLD : style.dust());
        tw.exilecore.skill.Fx.spawn(world, style == null ? Particle.CRIT : style.particle(), at.clone().add(0, 1.0, 0), 16, 0.6, 0.4, 0.6, 0.08);
        sound(at, style == null ? Sound.ENTITY_RAVAGER_ROAR : style.cast(), 1.0f, style == null ? 0.9f : style.castPitch());
    }

    /** 被戰吼強化的那一下攻擊。 */
    void exert(final Player player) {
        final Location at = player.getLocation().add(0, 1.2, 0);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.CRIT, at, 8, 0.3, 0.3, 0.3, 0.1);
        sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.4f, 0.7f);
    }

    /** 屍體的記號（有骸爆、喚屍的人才看得到附近有沒有材料）。 */
    void corpseMarker(final Location at) {
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.ASH, at.clone().add(0, 0.5, 0), 3, 0.25, 0.3, 0.25, 0.01);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.DUST, at.clone().add(0, 0.2, 0), 1, 0.2, 0.05, 0.2, 0, ROT);
    }

    // ---- 小工具 ----

    /** 地上一圈（間距大約 0.9 格，最多 72 點；照預算打薄）。 */
    private void ring(final World world, final Location center, final double radius, final Particle particle,
                      final Particle.DustOptions dust) {
        final int points = allow(center, Math.max(8, Math.min(72, (int) Math.round(Math.PI * 2 * radius / 0.9))), 8);
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2 * i / points;
            final Location at = center.clone().add(Math.cos(angle) * radius, 0.3, Math.sin(angle) * radius);
            if (dust != null) {
                tw.exilecore.skill.Fx.spawn(world, particle, at, 1, 0, 0, 0, 0, dust);
            } else {
                tw.exilecore.skill.Fx.spawn(world, particle, at, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }
    }

    // ---- Boss 招式 ----

    /** 前搖：Boss 身上冒出蓄力的粒子（照招式的元素色），開始時吼一聲；loud = false 是一般怪物（小聲、不加低音）。 */
    void bossCharge(final String visual, final LivingEntity boss, final boolean start, final boolean loud) {
        final Style style = style(visual);
        final Location at = boss.getLocation().add(0, boss.getHeight() * 0.6, 0);
        final World world = at.getWorld();
        tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, loud ? 6 : 3, boss.getWidth() * 0.6, boss.getHeight() * 0.4, boss.getWidth() * 0.6, 0, style == null ? WARNING : style.dust());
        if (start) {
            tw.exilecore.skill.Fx.spawn(world, style == null ? Particle.CRIT : style.particle(), at, loud ? 16 : 8, 0.5, 0.6, 0.5, 0.05);
            sound(boss.getLocation(), style == null ? Sound.ENTITY_RAVAGER_ROAR : style.cast(), loud ? 1.0f : 0.5f, style == null ? 0.9f : style.castPitch());
            if (loud) {
                sound(boss.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
            }
        }
    }

    /** 地上的紅色警告圈：progress 0 → 1 越來越密。 */
    void bossWarningRing(final Location center, final double radius, final double progress) {
        if (!WARNING_PARTICLES) {
            return; // 2026-10：招式預警只看地上的範圍（平面標示／地板變色），不畫粒子
        }
        final List<Player> viewers = warned(center);
        if (viewers.isEmpty()) {
            return; // 附近的人都看地上的平面標示
        }
        // 間距從 1.4 格縮到 0.9 格（越快落下越密），最多 56 點；照預算打薄
        final int points = allow(center, (int) Math.max(10, Math.min(56, Math.PI * 2 * radius / (1.4 - 0.5 * Math.min(1, progress)))), 10);
        final double spin = progress * 3;
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2 * i / points + spin;
            dust(viewers, center.clone().add(Math.cos(angle) * radius, 0.15, Math.sin(angle) * radius), WARNING);
        }
        if (progress > 0.5) {
            // 快落下了：裡面也補幾點（地板的紅色標示是主要的警告，這些是給半磚、水面這種標不到的地方）
            final int inner = allow(center, (int) Math.max(3, Math.min(10, radius)), 0);
            for (int i = 0; i < inner; i++) {
                final double angle = random.nextDouble() * Math.PI * 2;
                final double distance = Math.sqrt(random.nextDouble()) * radius;
                dust(viewers, center.clone().add(Math.cos(angle) * distance, 0.15, Math.sin(angle) * distance), WARNING);
            }
        }
    }

    /** 地上的紅色扇形警告：兩條邊、尾端一段弧，中間幾條線。 */
    void bossWarningCone(final Location feet, final Vector facing, final double length, final double angle, final double progress) {
        if (!WARNING_PARTICLES) {
            return; // 2026-10：招式預警只看地上的範圍（平面標示／地板變色），不畫粒子
        }
        final List<Player> viewers = warned(feet);
        if (viewers.isEmpty()) {
            return; // 附近的人都看地上的平面標示
        }
        final int rays = angle >= 60 ? 5 : 3;
        final int arcWanted = (int) Math.max(6, Math.min(24, angle / 8));
        final int wanted = (int) (2 * length / 1.0 + (rays - 2) * length / 4.0) + arcWanted;
        final double thin = thin(wanted, allow(feet, wanted, 16));
        for (int r = 0; r < rays; r++) {
            final double degrees = -angle / 2 + angle * r / (rays - 1);
            final Vector direction = facing.clone().rotateAroundY(Math.toRadians(degrees));
            final boolean edge = r == 0 || r == rays - 1;
            for (double distance = 0.8; distance <= length; distance += (edge ? 1.0 : 2.0) * thin) {
                if (!edge && (distance + progress * 4) % 4.0 > 2.0) {
                    continue; // 中間的線往前流動
                }
                dust(viewers, feet.clone().add(direction.clone().multiply(distance)).add(0, 0.15, 0), WARNING);
            }
        }
        final int arc = Math.max(4, (int) (arcWanted / thin));
        for (int i = 0; i <= arc; i++) {
            final double degrees = -angle / 2 + angle * i / arc;
            final Vector direction = facing.clone().rotateAroundY(Math.toRadians(degrees));
            dust(viewers, feet.clone().add(direction.clone().multiply(length)).add(0, 0.15, 0), WARNING);
        }
    }

    /** 地上的紅色路線警告（衝鋒）：兩條平行線。 */
    void bossWarningLine(final Location from, final Vector facing, final double length, final double width, final double progress) {
        if (!WARNING_PARTICLES) {
            return; // 2026-10：招式預警只看地上的範圍（平面標示／地板變色），不畫粒子
        }
        final List<Player> viewers = warned(from);
        if (viewers.isEmpty()) {
            return; // 附近的人都看地上的平面標示
        }
        final Vector side = new Vector(-facing.getZ(), 0, facing.getX()).normalize().multiply(width);
        final int wanted = (int) (length / 1.0 * 2 * 0.67) + 2;
        final double step = 1.0 * thin(wanted, allow(from, wanted, 8));
        for (double distance = 0.5; distance <= length; distance += step) {
            if ((distance + progress * 5) % 3.0 > 2.0) {
                continue; // 虛線往前流動
            }
            final Location point = from.clone().add(facing.clone().multiply(distance)).add(0, 0.15, 0);
            dust(viewers, point.clone().add(side), WARNING);
            dust(viewers, point.clone().subtract(side), WARNING);
        }
    }

    /** 前方扇形炸開（吐息）：沿幾條線噴元素粒子。 */
    void bossCone(final String visual, final Location feet, final Vector facing, final double length, final double angle) {
        final World world = feet.getWorld();
        final Style style = style(visual);
        final Particle particle = style == null ? Particle.CRIT : style.particle();
        final Particle.DustOptions dust = style == null ? STEEL : style.dust();
        final int rays = 5;
        final int wanted = (int) (rays * length / 1.4 * 2);
        final double step = 1.4 * thin(wanted, allow(feet, wanted, 20));
        for (int r = 0; r < rays; r++) {
            final double degrees = -angle / 2 + angle * r / (rays - 1);
            final Vector direction = facing.clone().rotateAroundY(Math.toRadians(degrees));
            for (double distance = 1.0; distance <= length; distance += step) {
                final Location at = feet.clone().add(direction.clone().multiply(distance)).add(0, 0.3 + distance * 0.05, 0);
                tw.exilecore.skill.Fx.spawn(world, particle, at, 2, 0.2, 0.2, 0.2, 0.03);
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0.15, 0.15, 0.15, 0, dust);
            }
        }
        if (style != null) {
            sound(feet, style.hit(), 1.0f, style.hitPitch());
        }
    }

    /** 齊射的魔法彈每 tick 的樣子。 */
    void bossBolt(final String visual, final Location at) {
        final Style style = style(visual);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.DUST, at, 4, 0.12, 0.12, 0.12, 0, style == null ? STEEL : style.dust()); // 2026-10：2 → 4，更明顯
        if (at.getWorld().getFullTime() % 2 == 0) {
            tw.exilecore.skill.Fx.spawn(at.getWorld(), style == null ? Particle.CRIT : style.particle(), at, 1, 0.05, 0.05, 0.05, 0.01);
        }
    }

    /** 魔法彈打中（或炸開）。 */
    void bossBoltHit(final String visual, final Location at, final double explode) {
        final Style style = style(visual);
        if (explode > 0) {
            nova(visual, at, explode);
        } else {
            tw.exilecore.skill.Fx.spawn(at.getWorld(), style == null ? Particle.CRIT : style.particle(), at, 8, 0.2, 0.2, 0.2, 0.05);
            if (style != null) {
                sound(at, style.hit(), 0.6f, style.hitPitch());
            }
        }
    }

    /** 召喚小怪的位置。 */
    void bossSummon(final Location at) {
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SOUL, at.clone().add(0, 0.5, 0), 12, 0.3, 0.6, 0.3, 0.03);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SMOKE, at.clone().add(0, 0.3, 0), 8, 0.3, 0.3, 0.3, 0.02);
        sound(at, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.8f, 0.9f);
    }

    /** 詛咒：Boss 腳下擴開一圈紫黑，被詛咒的人頭上畫印記。 */
    void bossCurse(final String visual, final Location center, final double radius, final List<LivingEntity> targets) {
        final World world = center.getWorld();
        final Style style = style(visual);
        final Particle.DustOptions dust = style == null ? VOID : style.dust();
        // 三圈就看得出擴開（以前每 1.5 格一圈，大範圍時一次好幾百點）
        ring(world, center, radius, Particle.DUST, dust);
        ring(world, center, radius * 2 / 3, Particle.DUST, dust);
        ring(world, center, radius / 3, Particle.DUST, dust);
        tw.exilecore.skill.Fx.spawn(world, Particle.SOUL, center.clone().add(0, 0.5, 0), 20, radius / 3, 0.3, radius / 3, 0.02);
        sound(center, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.6f, 1.3f);
        for (final LivingEntity target : targets) {
            mark(visual, target);
        }
    }

    /** 撲擊落地。 */
    void bossLand(final String visual, final Location at, final double radius) {
        nova(visual, at, radius);
        debris(at.getWorld(), at, 12);
        sound(at, Sound.ITEM_MACE_SMASH_GROUND, 1.0f, 0.9f);
    }

    // ---- Boss 的特殊招式（光束、擴散環、漩渦、光球、安全區） ----

    /** 安全區（綠色）。 */
    private static final Particle.DustOptions SAFE = new Particle.DustOptions(Color.fromRGB(0x45FF6A), 1.5f);

    /** 光束：從 from 朝 direction 一條粗線（沿線每 0.6 格一點，兩側各一條細線）。 */
    void bossBeam(final String visual, final Location from, final Vector direction, final double length, final double width) {
        final World world = from.getWorld();
        final Style style = style(visual);
        final Particle.DustOptions dust = style == null ? WARNING : style.dust();
        final Vector side = new Vector(-direction.getZ(), 0, direction.getX()).normalize().multiply(width);
        final int wanted = (int) (length / 1.2 * 2);
        final double step = 1.2 * thin(wanted, allow(from, wanted, 10));
        int index = 0;
        for (double distance = 0.8; distance <= length; distance += step) {
            final Location point = from.clone().add(direction.clone().multiply(distance)).add(0, 1.0, 0);
            tw.exilecore.skill.Fx.spawn(world, Particle.DUST, point, 1, 0.05, 0.05, 0.05, 0, dust);
            if (index++ % 3 == 0) {
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, point.clone().add(side), 1, 0, 0, 0, 0, dust);
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, point.clone().subtract(side), 1, 0, 0, 0, 0, dust);
                if (style != null) {
                    tw.exilecore.skill.Fx.spawn(world, style.particle(), point, 1, 0.1, 0.1, 0.1, 0.01);
                }
            }
        }
    }

    /** 擴散環的一波：半徑 radius 的一圈（缺口的地方不畫）。 */
    void bossRingWave(final String visual, final Location center, final double radius, final double[] gaps, final double gapAngle) {
        final World world = center.getWorld();
        final Style style = style(visual);
        final Particle.DustOptions dust = style == null ? WARNING : style.dust();
        final int points = allow(center, Math.max(12, Math.min(96, (int) (radius * Math.PI * 2 / 1.2))), 12);
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2 * i / points;
            if (BossGeometry.inGap(angle, gaps, gapAngle)) {
                continue;
            }
            final Location at = center.clone().add(Math.cos(angle) * radius, 0.4, Math.sin(angle) * radius);
            tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0, 0.1, 0, 0, dust);
            if (style != null && i % 6 == 0) {
                tw.exilecore.skill.Fx.spawn(world, style.particle(), at, 1, 0.05, 0.2, 0.05, 0.01);
            }
        }
    }

    /** 綠色的安全區（地上一圈＋往上飄的光點）。 */
    void safeZone(final Location center, final double radius) {
        if (!WARNING_PARTICLES) {
            return; // 2026-10：招式預警只看地上的範圍（平面標示／地板變色），不畫粒子
        }
        final List<Player> viewers = warned(center);
        if (viewers.isEmpty()) {
            return; // 附近的人都看地上的平面標示（綠色的安全區）
        }
        final int points = allow(center, Math.max(8, Math.min(72, (int) Math.round(Math.PI * 2 * radius / 0.9))), 8);
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2 * i / points;
            dust(viewers, center.clone().add(Math.cos(angle) * radius, 0.3, Math.sin(angle) * radius), SAFE);
        }
        for (final Player player : viewers) {
            player.spawnParticle(Particle.HAPPY_VILLAGER, center.clone().add(0, 0.4, 0), 2, radius / 2, 0.2, radius / 2, 0);
        }
    }

    /** 安全的缺口：從 center 朝 angle 的方向畫一排綠點到 length。 */
    void safeLane(final Location center, final double angle, final double length) {
        if (!WARNING_PARTICLES) {
            return; // 2026-10：招式預警只看地上的範圍（平面標示／地板變色），不畫粒子
        }
        final List<Player> viewers = warned(center);
        if (viewers.isEmpty()) {
            return; // 附近的人都看地上的平面標示（綠色的缺口）
        }
        final int wanted = (int) (length / 1.6) + 1;
        final double step = 1.6 * thin(wanted, allow(center, wanted, 4));
        for (double distance = 1.5; distance <= length; distance += step) {
            dust(viewers, center.clone().add(Math.cos(angle) * distance, 0.2, Math.sin(angle) * distance), SAFE);
        }
    }

    /** 引力漩渦：一圈一圈往中間縮的粒子。 */
    void bossPull(final String visual, final Location center, final double radius, final long now) {
        final World world = center.getWorld();
        final Style style = style(visual);
        final Particle.DustOptions dust = style == null ? VOID : style.dust();
        for (int layer = 0; layer < 3; layer++) {
            final double r = radius * (1 - ((now + layer * 7) % 20) / 20.0);
            final int points = allow(center, Math.max(8, Math.min(40, (int) (r * 1.6))), 6);
            final double spin = now * 0.15 + layer;
            for (int i = 0; i < points; i++) {
                final double angle = spin + Math.PI * 2 * i / points;
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, center.clone().add(Math.cos(angle) * r, 0.3 + layer * 0.3, Math.sin(angle) * r), 1, 0, 0, 0, 0, dust);
            }
        }
        if (now % 10 == 0) {
            sound(center, Sound.ENTITY_BREEZE_INHALE, 0.8f, 0.6f);
        }
    }

    /** 追蹤光球每 tick 的樣子。 */
    void bossOrb(final String visual, final Location at) {
        final World world = at.getWorld();
        final Style style = style(visual);
        // 2026-10：光球畫大一點、亮一點（一眼看得到在哪）
        tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 8, 0.28, 0.28, 0.28, 0, style == null ? VOID : style.dust());
        tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 3, 0.1, 0.1, 0.1, 0, ORB_CORE);
        tw.exilecore.skill.Fx.spawn(world, Particle.END_ROD, at, 2, 0.08, 0.08, 0.08, 0.005);
    }

    private static final Particle.DustOptions ORB_CORE = new Particle.DustOptions(Color.fromRGB(0xFFFFFF), 2.0f);
    private static final Particle.DustOptions TETHER = new Particle.DustOptions(Color.fromRGB(0xFF2A2A), 0.9f);
    private static final Particle.DustOptions TARGET = new Particle.DustOptions(Color.fromRGB(0xFF2A2A), 1.6f);

    /**
     * 追蹤招式在追誰（2026-10）：光球到目標拉一條紅線，目標頭上一圈紅點。
     */
    void bossTether(final Location from, final Location to, final Location head) {
        final World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) {
            return;
        }
        final Vector line = to.toVector().subtract(from.toVector());
        final double length = line.length();
        if (length > 1e-3) {
            final int points = (int) Math.min(40, Math.ceil(length / 0.5));
            final Vector step = line.multiply(1.0 / points);
            final Location at = from.clone();
            for (int i = 0; i < points; i++) {
                at.add(step);
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 1, 0, 0, 0, 0, TETHER);
            }
        }
        for (int i = 0; i < 8; i++) {
            final double angle = Math.PI * 2 * i / 8 + (world.getFullTime() % 40) * Math.PI / 20;
            tw.exilecore.skill.Fx.spawn(world, Particle.DUST, head.clone().add(Math.cos(angle) * 0.45, 0.55, Math.sin(angle) * 0.45),
                    1, 0, 0, 0, 0, TARGET);
        }
    }

    /** 全場大招落下：以 center 為中心一大片爆開（不畫安全區裡面的）。 */
    void bossCataclysm(final String visual, final Location center, final double radius) {
        final World world = center.getWorld();
        final Style style = style(visual);
        final Particle particle = style == null ? Particle.EXPLOSION : style.particle();
        // 一圈一圈往外（間隔 3.5 格，每圈最多 30 點、每點一小團）：以前每 2.5 格一圈、一點一個，全場大招一次好幾百個
        for (double r = 2; r <= radius; r += 3.5) {
            final int points = allow(center, Math.max(8, Math.min(30, (int) (r * 1.2))), 6);
            for (int i = 0; i < points; i++) {
                final double angle = Math.PI * 2 * i / points + r;
                tw.exilecore.skill.Fx.spawn(world, particle, center.clone().add(Math.cos(angle) * r, 0.5, Math.sin(angle) * r), 2, 0.4, 0.3, 0.4, 0.02);
            }
        }
        tw.exilecore.skill.Fx.spawn(world, Particle.EXPLOSION_EMITTER, center, 1);
        sound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.6f);
        if (style != null) {
            sound(center, style.hit(), 1.2f, style.hitPitch() * 0.8f);
        }
    }

    /** 地面碎屑：腳下是實心方塊就噴它的碎片，不然用雲。 */
    private static void debris(final World world, final Location at, final int count) {
        final Block below = at.getBlock().getRelative(0, -1, 0);
        if (below.getType().isSolid()) {
            tw.exilecore.skill.Fx.spawn(world, Particle.BLOCK, at, count, 0.2, 0.1, 0.2, 0.1, below.getBlockData());
        } else {
            tw.exilecore.skill.Fx.spawn(world, Particle.CLOUD, at, 1, 0.1, 0.05, 0.1, 0.01);
        }
    }

    private static void sound(final Location at, final Sound sound, final float volume, final float pitch) {
        if (sound == null) {
            return; // 靜音的外觀（圖騰）
        }
        Fx.sound(at, sound, SoundCategory.PLAYERS, volume, pitch); // 2026-10：別人的技能小聲、可以靜音
    }
}
