package tw.exilecore.item;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 普通／魔法／稀有裝備在格子裡的簡圖（2026-10-24，{@code web/plain_art.txt}，tools/gen_plain_art.py 產生）：
 * 照種類一張（戒指、頭盔、單手錘、弓…；防具與盾再照屬性分，戒指、項鍊、箭袋照固定詞綴換顏色），跟傳奇的細緻圖明顯分開。物品上用 custom_model_data 的字串 0 {@code plain:種類}
 * 選到它（字串 1 是魔法、稀有的外框 {@code rarity:…}）；只畫在格子裡，拿在手上、穿在身上、掉在地上照原版。
 * 沒套資源包的玩家看到的還是原本的物品。純資料，不依賴 Bukkit。
 */
public final class PlainArt {

    public static final String RESOURCE = "/web/plain_art.txt";
    /** 物品上 custom_model_data 字串的前綴。 */
    public static final String KEY_PREFIX = "plain:";

    private static volatile Map<String, UniqueArt.Sprite> cached;

    private PlainArt() {
    }

    /** 內建的那份（讀不到、格式不對就是空的：照舊用原版物品）。 */
    public static Map<String, UniqueArt.Sprite> sprites() {
        Map<String, UniqueArt.Sprite> result = cached;
        if (result == null) {
            try (InputStream in = PlainArt.class.getResourceAsStream(RESOURCE)) {
                result = in == null ? Map.of() : UniqueArt.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (final IOException | IllegalArgumentException e) {
                result = Map.of();
            }
            cached = result;
        }
        return result;
    }

    /** 這個基底用哪張簡圖（種類）；不是能穿戴的裝備、或認不出種類回傳 null。 */
    public static String category(final ItemBase base) {
        if (base == null || !base.isWearable()) {
            return null;
        }
        final String category = classify(base);
        if (category == null) {
            return null;
        }
        // 2026-10-27：法杖、長杖、權杖每個基底各一張（杆子材質、寶石顏色、頭的樣子不同），簡圖的代號就是基底代號；沒有那張用通用的
        if (("wand".equals(category) || "staff".equals(category) || "sceptre".equals(category)) && sprites().containsKey(base.id())) {
            return base.id();
        }
        // 2026-10-24：戒指照固定詞綴的抗性換寶石顏色（ring_fire、ring_cold_lightning、ring_all…）
        if ("ring".equals(category)) {
            final String resist = ringResist(base);
            if (resist != null && sprites().containsKey("ring_" + resist)) {
                return "ring_" + resist;
            }
        }
        // 2026-10-24：項鍊照固定詞綴換墜子（屬性菱形、抗性三角、其他圓形）
        if ("amulet".equals(category)) {
            final String kind = amuletKind(base);
            if (kind != null && sprites().containsKey("amulet_" + kind)) {
                return "amulet_" + kind;
            }
        }
        // 2026-10-24：腰帶照固定詞綴換扣環（生命、護盾、藥劑回復是小藥瓶、護甲鉚釘、施法、力量、閃電、暈眩大扣環）
        if ("belt".equals(category)) {
            final String kind = beltKind(base);
            if (kind != null && sprites().containsKey("belt_" + kind)) {
                return "belt_" + kind;
            }
        }
        // 2026-10-24：箭袋照固定詞綴換箭羽顏色
        if ("quiver".equals(category)) {
            final String kind = quiverKind(base);
            if (kind != null && sprites().containsKey("quiver_" + kind)) {
                return "quiver_" + kind;
            }
        }
        // 2026-10-24：防具、盾照屬性分（形狀看主屬性、顏色看屬性）：helmet_str、body_dex_int、shield_str_dex…；沒有那張用通用的
        final String family = family(base);
        if (family != null && sprites().containsKey(category + "_" + family)) {
            return category + "_" + family;
        }
        return sprites().containsKey(category) ? category : null;
    }

    /**
     * 戒指固定詞綴給的抗性：fire、cold、lightning、chaos、fire_cold、fire_lightning、cold_lightning、all；
     * 不是抗性的：phys、life、mana、accuracy、cast、rarity、chaosdmg（圓形寶石）；都不是回傳 null。
     */
    static String ringResist(final ItemBase base) {
        if (base.implicit() == null) {
            return null;
        }
        boolean fire = false;
        boolean cold = false;
        boolean lightning = false;
        boolean chaos = false;
        for (final AffixMod mod : base.implicit().mods()) {
            switch (mod.stat()) {
                case "resist.fire" -> fire = true;
                case "resist.cold" -> cold = true;
                case "resist.lightning" -> lightning = true;
                case "resist.chaos" -> chaos = true;
                default -> {
                }
            }
        }
        if (fire && cold && lightning) {
            return "all";
        }
        final StringBuilder name = new StringBuilder();
        for (final String[] element : new String[][] {{fire ? "fire" : null}, {cold ? "cold" : null}, {lightning ? "lightning" : null}}) {
            if (element[0] != null) {
                name.append(name.isEmpty() ? "" : "_").append(element[0]);
            }
        }
        if (!name.isEmpty()) {
            return name.toString();
        }
        if (chaos) {
            return "chaos";
        }
        // 2026-10-24：不是抗性的（圓形寶石）：物理、生命、魔力、命中、施法速度、掉落稀有度、混沌傷害
        for (final AffixMod mod : base.implicit().mods()) {
            final String kind = switch (mod.stat()) {
                case "damage.added.min", "damage.added.max" -> mod.tags().contains("physical") ? "phys" : null;
                case "life.max" -> "life";
                case "mana.max" -> "mana";
                case "accuracy" -> "accuracy";
                case "cast.speed" -> "cast";
                case "drop.rarity" -> "rarity";
                case "damage" -> mod.tags().contains("chaos") ? "chaosdmg" : null;
                default -> null;
            };
            if (kind != null) {
                return kind;
            }
        }
        return null;
    }

    /** 項鍊固定詞綴的種類：str、dex、int、attr_all、res_all、life、mana、es、spirit、rarity；都不是回傳 null。 */
    static String amuletKind(final ItemBase base) {
        if (base.implicit() == null) {
            return null;
        }
        final java.util.Set<String> stats = new java.util.HashSet<>();
        for (final AffixMod mod : base.implicit().mods()) {
            stats.add(mod.stat());
        }
        if (stats.containsAll(java.util.List.of("attr.str", "attr.dex", "attr.int"))) {
            return "attr_all";
        }
        if (stats.containsAll(java.util.List.of("resist.fire", "resist.cold", "resist.lightning"))) {
            return "res_all";
        }
        if (stats.contains("attr.str")) {
            return "str";
        }
        if (stats.contains("attr.dex")) {
            return "dex";
        }
        if (stats.contains("attr.int")) {
            return "int";
        }
        if (stats.contains("life.regen") || stats.contains("life.max")) {
            return "life";
        }
        if (stats.contains("mana.regen") || stats.contains("mana.max")) {
            return "mana";
        }
        if (stats.contains("es.max")) {
            return "es";
        }
        if (stats.contains("spirit.max")) {
            return "spirit";
        }
        return stats.contains("drop.rarity") ? "rarity" : null;
    }

    /** 腰帶固定詞綴的種類：life、es、flask_life、flask_mana、armour、cast、str、lightning、stun；都不是回傳 null。 */
    static String beltKind(final ItemBase base) {
        if (base.implicit() == null) {
            return null;
        }
        for (final AffixMod mod : base.implicit().mods()) {
            final String kind = switch (mod.stat()) {
                case "life.max" -> "life";
                case "es.max" -> "es";
                case "flask.life_recovery" -> "flask_life";
                case "flask.mana_recovery" -> "flask_mana";
                case "armour" -> "armour";
                case "cast.speed" -> "cast";
                case "attr.str" -> "str";
                case "damage.added.min", "damage.added.max" -> mod.tags().contains("lightning") ? "lightning" : null;
                case "stun.threshold" -> "stun";
                default -> null;
            };
            if (kind != null) {
                return kind;
            }
        }
        return null;
    }

    /** 箭袋固定詞綴的種類：phys、fire、crit、speed、pierce、life、poison、bleed、accuracy、stun、projspeed；都不是回傳 null。 */
    static String quiverKind(final ItemBase base) {
        if (base.implicit() == null) {
            return null;
        }
        for (final AffixMod mod : base.implicit().mods()) {
            final String kind = switch (mod.stat()) {
                case "damage.added.min", "damage.added.max" -> mod.tags().contains("fire") ? "fire"
                        : mod.tags().contains("physical") ? "phys" : null;
                case "crit.chance" -> "crit";
                case "attack.speed" -> "speed";
                case "skill.pierce" -> "pierce";
                case "life.on_hit" -> "life";
                case "poison.chance" -> "poison";
                case "bleed.chance" -> "bleed";
                case "accuracy" -> "accuracy";
                case "stun.buildup" -> "stun";
                case "skill.projectile_speed" -> "projspeed";
                default -> null;
            };
            if (kind != null) {
                return kind;
            }
        }
        return null;
    }

    /** 防具、盾的屬性組合（str、dex、int、str_dex、str_int、dex_int）；不是防具或盾回傳 null。 */
    static String family(final ItemBase base) {
        if (base.hasTag("str_dex_armour") || base.hasTag("str_dex_shield")) {
            return "str_dex";
        }
        if (base.hasTag("str_int_armour") || base.hasTag("str_int_shield")) {
            return "str_int";
        }
        if (base.hasTag("dex_int_armour")) {
            return "dex_int";
        }
        if (base.hasTag("str_only") || base.hasTag("str_shield")) {
            return "str";
        }
        if (base.hasTag("dex_only")) {
            return "dex";
        }
        if (base.hasTag("int_only")) {
            return "int";
        }
        return null;
    }

    static String classify(final ItemBase base) {
        if (base.hasTag("ring")) {
            return "ring";
        }
        if (base.hasTag("amulet")) {
            return "amulet";
        }
        if (base.hasTag("belt")) {
            return "belt";
        }
        if (base.hasTag("helmet")) {
            return "helmet";
        }
        if (base.hasTag("body")) {
            return "body";
        }
        if (base.hasTag("leggings")) {
            return "leggings";
        }
        if (base.hasTag("boots")) {
            return "boots";
        }
        if (base.hasTag("quiver")) {
            return "quiver";
        }
        if (base.hasTag("focus")) {
            return "focus";
        }
        if (base.hasTag("shield")) {
            return base.hasTag("dex_only") ? "buckler" : "shield";
        }
        for (final String kind : new String[] {"crossbow", "bow", "wand", "quarterstaff", "staff", "spear", "talisman", "claw",
                "dagger", "sword", "axe", "sceptre"}) {
            if (base.hasTag(kind)) {
                return kind;
            }
        }
        if (base.hasTag("mace")) {
            return base.hasTag("melee_2h") || base.hasTag("two_hand") ? "hammer" : "mace";
        }
        return null;
    }

    /** 物品上 custom_model_data 的字串。 */
    public static String modelKey(final String category) {
        return KEY_PREFIX + category;
    }
}
