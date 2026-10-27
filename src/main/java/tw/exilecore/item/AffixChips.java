package tw.exilecore.item;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 詞綴的標籤（2026-10）：裝備詞綴那一行後面的小方塊（傷害、火焰、攻擊、護甲…），讓玩家一眼看出這條詞綴跟什麼有關。
 * <p>
 * 有套介面資源包的人看到的是彩色的小方塊（tools/gen_affix_chips.py 畫的 web/chips/*.png，放進 minecraft:default 字型的私用字元，
 * 用 lang 檔的翻譯換成那個字元）；沒套的人看到翻譯的備用文字「[護甲]」。純邏輯（不碰 Bukkit）。
 */
public final class AffixChips {

    /** 一種標籤；順序就是顯示的順序（跟 tools/gen_affix_chips.py 的 CHIPS 一樣）。 */
    public enum Chip {
        DAMAGE("damage", "傷害"), PHYSICAL("physical", "物理"), ELEMENTAL("elemental", "元素"), FIRE("fire", "火焰"),
        COLD("cold", "冰冷"), LIGHTNING("lightning", "閃電"), CHAOS("chaos", "混沌"), ATTACK("attack", "攻擊"),
        CASTER("caster", "法術"), SPEED("speed", "速度"), CRITICAL("critical", "暴擊"), LIFE("life", "生命"),
        MANA("mana", "魔力"), ARMOUR("armour", "護甲"), EVASION("evasion", "閃避"), ENERGY_SHIELD("energy_shield", "能量護盾"),
        BLOCK("block", "格擋"), RESISTANCE("resistance", "抗性"), ATTRIBUTE("attribute", "屬性"), MINION("minion", "召喚物"),
        AILMENT("ailment", "異常"), GEM("gem", "技能"), AURA("aura", "光環"), SPIRIT("spirit", "精魂"), CURSE("curse", "詛咒"),
        FLASK("flask", "藥劑"), CHARM("charm", "護符"), PROJECTILE("projectile", "投射物"),
        // 2026-10：技能寶石的標籤（接在後面：前面的私用字元不變）
        AREA("area", "範圍"), MELEE("melee", "近戰"), STRIKE("strike", "打擊"), SLAM("slam", "猛擊"), DURATION("duration", "持續"),
        COOLDOWN("cooldown", "冷卻"), MOVEMENT("movement", "移動"), TRAVEL("travel", "突進"), NOVA("nova", "新星"), CHAIN("chain", "連鎖"),
        MARK("mark", "印記"), GRENADE("grenade", "榴彈"), CORPSE("corpse", "屍體"), TOTEM("totem", "圖騰"), BELL("bell", "鐘"),
        TRAP("trap", "陷阱"), HERALD("herald", "捷光環"), WARCRY("warcry", "戰吼"), PERSISTENT("persistent", "常駐"),
        COMMAND("command", "指令"), CRYSTAL("crystal", "冰晶"), SHAPESHIFT("shapeshift", "變身"), BEAR("bear", "熊形態"),
        WEREWOLF("werewolf", "狼人形態"), WYVERN("wyvern", "飛龍形態"), SUMMON("summon", "召喚"),
        // 2026-10：遠程召喚物（骸骨射手、術士）的標籤；加在最後面，前面的私用字元不會跑掉
        RANGED("ranged", "遠程");

        private final String id;
        private final String label;

        Chip(final String id, final String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        /** 資源包裡代表這個標籤的私用字元（minecraft:default 字型）。 */
        public char glyph() {
            return (char) (FIRST_GLYPH + ordinal());
        }

        /** lang 檔的翻譯鍵。 */
        public String key() {
            return KEY_PREFIX + id;
        }
    }

    public static final char FIRST_GLYPH = '';
    public static final String KEY_PREFIX = "exilecore.chip.";
    private static final Pattern KEY = Pattern.compile("exilecore\\.chip\\.([a-z_]+)");

    private AffixChips() {
    }

    /** 一條詞綴的標籤（照修飾器的屬性與標籤判斷）。 */
    public static Set<Chip> of(final List<AffixMod> mods) {
        final Set<Chip> chips = EnumSet.noneOf(Chip.class);
        for (final AffixMod mod : mods) {
            add(chips, mod.stat(), mod.tags());
        }
        return chips;
    }

    static void add(final Set<Chip> chips, final String rawStat, final Set<String> tags) {
        String lower = rawStat.toLowerCase(Locale.ROOT).replace("grant.", "");
        if (lower.startsWith("monster:") || lower.startsWith("boss:") || lower.startsWith("map.")) {
            return; // 地圖詞綴：不標
        }
        if (lower.startsWith("player:")) {
            lower = lower.substring("player:".length());
        }
        if (lower.startsWith("radius:")) {
            lower = lower.substring("radius:".length()); // 2026-10：遺時珠寶「範圍內的小天賦也各給」——標籤照給的那條屬性判斷（以前整條沒標籤）
        } else if (lower.startsWith("radius_notable:")) {
            lower = lower.substring("radius_notable:".length()); // 2026-10-26：「範圍內的核心天賦也各給」一樣
        }
        if (lower.equals("local.physical")) {
            chips.addAll(List.of(Chip.DAMAGE, Chip.PHYSICAL, Chip.ATTACK));
            return;
        }
        if (lower.startsWith("local.")) {
            // 武器自己的攻速、暴擊。2026-10-17：「這件物品上揭露詞綴的幅度」不是武器屬性，不標攻擊
            if (!lower.startsWith("local.unveiled")) {
                chips.add(Chip.ATTACK);
            }
            lower = lower.substring("local.".length()).replace("attack_speed", "attack.speed").replace("crit_base", "crit");
        }
        if (lower.startsWith("daze") || lower.startsWith("physical.reduction")) {
            chips.add(lower.startsWith("daze") ? Chip.AILMENT : Chip.PHYSICAL);
        }
        final String stat = lower;
        for (final String tag : tags) {
            switch (tag) {
                case "fire" -> chips.addAll(List.of(Chip.ELEMENTAL, Chip.FIRE));
                case "cold" -> chips.addAll(List.of(Chip.ELEMENTAL, Chip.COLD));
                case "lightning" -> chips.addAll(List.of(Chip.ELEMENTAL, Chip.LIGHTNING));
                case "elemental" -> chips.add(Chip.ELEMENTAL);
                case "chaos" -> chips.add(Chip.CHAOS);
                case "physical" -> chips.add(Chip.PHYSICAL);
                case "attack" -> chips.add(Chip.ATTACK);
                case "spell" -> chips.add(Chip.CASTER);
                case "minion" -> chips.add(Chip.MINION);
                case "projectile" -> chips.add(Chip.PROJECTILE);
                case "curse" -> chips.add(Chip.CURSE);
                case "aura" -> chips.add(Chip.AURA);
                // 2026-10-17：技能類的標籤也要顯示（以前「圖騰傷害」只顯示傷害、「戰吼冷卻」整條沒有標籤）
                case "melee" -> chips.add(Chip.MELEE);
                case "strike" -> chips.add(Chip.STRIKE);
                case "slam" -> chips.add(Chip.SLAM);
                case "area" -> chips.add(Chip.AREA);
                case "totem" -> chips.add(Chip.TOTEM);
                case "warcry" -> chips.add(Chip.WARCRY);
                case "mark" -> chips.add(Chip.MARK);
                case "herald" -> chips.add(Chip.HERALD);
                case "grenade" -> chips.add(Chip.GRENADE);
                case "shapeshift" -> chips.add(Chip.SHAPESHIFT);
                case "bear" -> chips.addAll(List.of(Chip.SHAPESHIFT, Chip.BEAR));
                case "werewolf" -> chips.addAll(List.of(Chip.SHAPESHIFT, Chip.WEREWOLF));
                case "wyvern" -> chips.addAll(List.of(Chip.SHAPESHIFT, Chip.WYVERN));
                default -> {
                }
            }
        }
        // 屬性名稱裡的元素（resist.fire、damage.gain.cold、damage.extra.lightning…）
        if (stat.endsWith(".fire") || stat.contains(".fire.")) {
            chips.addAll(List.of(Chip.ELEMENTAL, Chip.FIRE));
        } else if (stat.endsWith(".cold") || stat.contains(".cold.")) {
            chips.addAll(List.of(Chip.ELEMENTAL, Chip.COLD));
        } else if (stat.endsWith(".lightning") || stat.contains(".lightning.")) {
            chips.addAll(List.of(Chip.ELEMENTAL, Chip.LIGHTNING));
        } else if (stat.endsWith(".chaos") || stat.contains(".chaos.")) {
            chips.add(Chip.CHAOS);
        } else if (stat.endsWith(".elemental") || stat.endsWith(".all_elemental")) {
            chips.add(Chip.ELEMENTAL);
        }
        if (stat.startsWith("damage.from_mana")) {
            chips.add(Chip.MANA);
        } else if (stat.startsWith("armour.break")) {
            chips.add(Chip.PHYSICAL);
        } else if (stat.startsWith("skill.projectile") || stat.startsWith("skill.pierce") || stat.startsWith("skill.chains")) {
            chips.add(Chip.PROJECTILE);
            if (stat.endsWith("speed")) {
                chips.add(Chip.SPEED);
            }
        } else if (stat.startsWith("leech")) {
            chips.add(stat.contains("mana") ? Chip.MANA : Chip.LIFE);
            if (stat.contains("spell")) {
                chips.add(Chip.CASTER); // 2026-10-17：「法術傷害偷取」
            }
        } else if (stat.startsWith("penetration")) {
            chips.add(Chip.DAMAGE); // 2026-10-17：穿透（珠寶手寫的都有標傷害）
        } else if (stat.startsWith("aura.reservation")) {
            chips.add(Chip.SPIRIT);
        } else if (stat.startsWith("thorns")) {
            chips.add(Chip.DAMAGE);
        } else if (stat.startsWith("damage") || stat.startsWith("onkill.damage") || stat.startsWith("dot.")) {
            chips.add(Chip.DAMAGE);
        } else if (stat.startsWith("resist")) {
            chips.add(Chip.RESISTANCE);
        } else if (stat.startsWith("attack.speed")) {
            chips.addAll(List.of(Chip.SPEED, Chip.ATTACK));
        } else if (stat.startsWith("cast.speed")) {
            chips.addAll(List.of(Chip.SPEED, Chip.CASTER));
        } else if (stat.startsWith("movement.speed") || stat.endsWith(".speed") || stat.startsWith("skill.speed")) {
            chips.add(Chip.SPEED);
        } else if (stat.startsWith("crit")) {
            chips.add(Chip.CRITICAL);
        } else if (stat.startsWith("life") || stat.startsWith("leech.life") || stat.endsWith(".life")) {
            chips.add(Chip.LIFE);
        } else if (stat.startsWith("mana") || stat.startsWith("leech.mana") || stat.endsWith(".mana")) {
            chips.add(Chip.MANA);
        } else if (stat.startsWith("es.") || stat.equals("es") || stat.startsWith("energy_shield")) {
            chips.add(Chip.ENERGY_SHIELD);
        } else if (stat.startsWith("armour")) {
            chips.add(Chip.ARMOUR);
        } else if (stat.startsWith("accuracy")) {
            chips.add(Chip.ATTACK);
        } else if (stat.startsWith("evasion") || stat.startsWith("deflection")) {
            chips.add(Chip.EVASION);
        } else if (stat.startsWith("block")) {
            chips.add(Chip.BLOCK);
        } else if (stat.startsWith("attr.")) {
            chips.add(Chip.ATTRIBUTE);
        } else if (stat.startsWith("skill.level")) {
            chips.add(Chip.GEM);
        } else if (stat.startsWith("spirit")) {
            chips.add(Chip.SPIRIT);
        } else if (stat.startsWith("aura")) {
            chips.add(Chip.AURA);
        } else if (stat.startsWith("curse")) {
            chips.add(Chip.CURSE);
        } else if (stat.startsWith("flask")) {
            chips.add(Chip.FLASK);
        } else if (stat.startsWith("charm")) {
            chips.add(Chip.CHARM);
        } else if (stat.startsWith("minion")) {
            chips.add(Chip.MINION);
        } else if (stat.startsWith("projectile")) {
            chips.add(Chip.PROJECTILE);
        }
        // 2026-10-17：屬性名稱裡帶召喚物（藥劑的「召喚物也回復」）、對被詛咒的敵人
        if (stat.contains("minion")) {
            chips.add(Chip.MINION);
        }
        if (stat.contains("cursed")) {
            chips.add(Chip.CURSE);
        }
        if (stat.startsWith("ignite") || stat.startsWith("chill") || stat.startsWith("freeze") || stat.startsWith("shock")
                || stat.startsWith("bleed") || stat.startsWith("poison") || stat.startsWith("stun") || stat.startsWith("ailment")
                || stat.startsWith("electrocute") || stat.startsWith("avoid.")) {
            chips.add(Chip.AILMENT);
        }
    }

    /** 接在詞綴那一行後面的 MiniMessage（每個標籤一個小方塊，前面空一格）；沒有標籤回傳空字串。 */
    public static String miniMessage(final Set<Chip> chips) {
        if (chips.isEmpty()) {
            return "";
        }
        final StringBuilder text = new StringBuilder("<#e6e6e6>");
        for (final Chip chip : chips) {
            text.append(' ').append("<lang_or:").append(chip.key()).append(":'[").append(chip.label()).append("]'/>");
        }
        return text.append("</#e6e6e6>").toString();
    }

    /** 代號找標籤（技能的標籤用；spell 對到「法術」）；沒有是 null。 */
    public static Chip byId(final String id) {
        if ("spell".equals(id)) {
            return Chip.CASTER;
        }
        for (final Chip chip : Chip.values()) {
            if (chip.id.equals(id)) {
                return chip;
            }
        }
        return null;
    }

    /** 照給的順序排的標籤（技能寶石用：種類在最前面），前面空一格；空的回傳空字串。 */
    public static String miniMessage(final List<Chip> chips) {
        if (chips.isEmpty()) {
            return "";
        }
        final StringBuilder text = new StringBuilder("<#e6e6e6>");
        for (final Chip chip : chips) {
            text.append(' ').append("<lang_or:").append(chip.key()).append(":'[").append(chip.label()).append("]'/>");
        }
        return text.append("</#e6e6e6>").toString();
    }

    /** 純文字（Discord 這類沒有資源包的地方）：翻譯鍵換成「[護甲]」。 */
    public static String plain(final String text) {
        if (text == null || !text.contains(KEY_PREFIX)) {
            return text;
        }
        final Matcher matcher = KEY.matcher(text);
        final StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String label = matcher.group(1);
            for (final Chip chip : Chip.values()) {
                if (chip.id().equals(matcher.group(1))) {
                    label = chip.label();
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement("[" + label + "]"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 純文字裡把詞綴標籤整個拿掉（Discord 不顯示標籤，2026-10）。 */
    public static String strip(final String text) {
        if (text == null) {
            return null;
        }
        String out = KEY.matcher(text).replaceAll("");
        for (final Chip chip : Chip.values()) {
            out = out.replace(" [" + chip.label() + "]", "").replace("[" + chip.label() + "]", "");
        }
        return out;
    }

    /** 方便測試：全部的標籤代號。 */
    public static List<String> ids() {
        final List<String> ids = new ArrayList<>();
        for (final Chip chip : Chip.values()) {
            ids.add(chip.id());
        }
        return ids;
    }
}
