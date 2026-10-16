package tw.exilecore.character;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.DamageNumbers;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.DefenceEstimates;
import tw.exilecore.stats.Formulas;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 玩家屬性的文字摘要（MiniMessage），給 /stats 指令與角色面板共用。
 * <p>
 * 兩種版面：{@link #all} 是聊天欄的版本（分區塊、滑鼠移上去有說明、點一下開天賦樹與角色面板）；
 * 其他方法回傳各區塊的行，給角色面板的物品說明用（物品說明沒有 hover，說明直接寫在旁邊）。
 * 顏色慣例：標籤灰、數值白，生命紅、魔力藍、能量護盾淡藍，抗性與傷害照元素色，力量紅、敏捷綠、智慧藍（跟 PoE 一樣）。
 */
public final class StatsReport {

    static final String GOLD = "<#f5b942>";
    static final String LIFE = "<#ff5555>";
    static final String MANA = "<#5fa8ff>";
    static final String ES = "<#8fd3ff>";
    static final String STR = "<#ff7070>";
    static final String DEX = "<#7fe07f>";
    static final String INT = "<#7fb2ff>";
    static final String BLOCK = "<#ffd27f>";
    private static final String SEP = " <dark_gray>│ ";
    private static final String RULE = "━━━━━━━━━━━━━━━━━━━━━━━━";
    private static final String INDENT = "  ";

    private StatsReport() {
    }

    // ---- 區塊（角色面板的物品說明；/stats 也用其中一部分） ----

    /** 物品掉落數量、稀有度（2026-10）：裝備、稱號、個人加倍藥……加上全服加倍的總數。沒有角色是空的。 */
    public static List<String> drops(final ExileCore plugin, final Player player) {
        final List<String> lines = new ArrayList<>();
        if (plugin.players().get(player).character() == null) {
            return lines;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        // 2026-10：合併成一個總數（自己的＋全服加倍，實際就是加起來算），全服加倍開著時括號寫出含多少
        final double ownQuantity = sheet.increased(StatKeys.DROP_QUANTITY, Set.of()) * 100;
        final double ownRarity = sheet.increased(StatKeys.DROP_RARITY, Set.of()) * 100;
        final double serverQuantity = tw.exilecore.sponsor.Boosts.quantity() * 100;
        final double serverRarity = tw.exilecore.sponsor.Boosts.rarity() * 100;
        // 2026-10-16：個人加倍藥（在屬性表裡，已經算在自己的裡面）也寫出含多少
        double personalQuantity = 0;
        double personalRarity = 0;
        if (plugin.sponsors() != null) {
            for (final tw.exilecore.sponsor.SponsorConfig.BoostType type : plugin.sponsors().personalBoosts(player)) {
                personalQuantity += type.quantity() * 100;
                personalRarity += type.rarity() * 100;
            }
        }
        lines.add(String.format(Locale.ROOT, "<gray>物品掉落數量 <white>%+.0f%%%s" + SEP + "<gray>物品稀有度 <white>%+.0f%%%s",
                ownQuantity + serverQuantity, included(serverQuantity, personalQuantity), ownRarity + serverRarity, included(serverRarity, personalRarity)));
        return lines;
    }

    /** 「（含全服 +100%、個人加倍 +100%）」；都沒有是空的。 */
    private static String included(final double server, final double personal) {
        if (server <= 0 && personal <= 0) {
            return "";
        }
        final List<String> parts = new ArrayList<>();
        if (server > 0) {
            parts.add(String.format(Locale.ROOT, "全服 %+.0f%%", server));
        }
        if (personal > 0) {
            parts.add(String.format(Locale.ROOT, "個人加倍 %+.0f%%", personal));
        }
        return " <#ffd27f>（含" + String.join("、", parts) + "）";
    }

    /**
     * 打怪實際的經驗倍數（2026-10-16）：全服（character.yml、全服加倍、在線人數）×（1 ＋ 累積贊助＋個人加倍藥＋種族），
     * 跟 KillCredit 給經驗的算法一樣。
     */
    public static double experienceMultiplier(final ExileCore plugin, final Player player) {
        final double personal = (plugin.sponsors() == null ? 0 : plugin.sponsors().experienceBonus(player) + plugin.sponsors().personalExperience(player))
                + (plugin.ascendancy() == null ? 0 : plugin.ascendancy().race().experienceBonus(player));
        return plugin.players().config().experienceBonus() * (1 + personal);
    }

    /** 經驗那一行後面的括號：全服倍數；有個人加倍藥（或累積贊助、種族）時寫實際倍數。 */
    private static String experienceNote(final ExileCore plugin, final Player player, final String serverText) {
        final boolean personal = plugin.sponsors() != null && plugin.sponsors().personalExperience(player) > 0;
        final double actual = experienceMultiplier(plugin, player);
        final String actualText = String.format(Locale.ROOT, "×%s", actual == Math.rint(actual) ? String.valueOf((long) actual)
                : String.format(Locale.ROOT, "%.2f", actual).replaceAll("0+$", "").replaceAll("\\.$", ""));
        if (personal) {
            return " <#ffd27f>（" + (serverText == null ? "" : "全服經驗 " + serverText + "＋") + "個人加倍：實際 " + actualText + "）";
        }
        if (serverText == null) {
            return Math.abs(actual - 1) > 1e-9 ? " <#ffd27f>（經驗 " + actualText + "）" : "";
        }
        return " <#ffd27f>（全服經驗 " + serverText + (Math.abs(actual - plugin.players().config().experienceBonus()) > 1e-9 ? "，實際 " + actualText : "") + "）";
    }

    public static List<String> header(final ExileCore plugin, final Player player) {
        return header(plugin, player, true);
    }

    /**
     * 角色那一區。
     *
     * @param self 是不是本人在看：別人看不到金幣
     */
    public static List<String> header(final ExileCore plugin, final Player player, final boolean self) {
        final PlayerState state = plugin.players().get(player);
        final List<String> lines = new ArrayList<>();
        final CharacterRecord character = state.character();
        if (character == null) {
            lines.add(self ? "<red>還沒有角色<gray>（/char 建立）　等級 1" : "<red>還沒有角色<gray>　等級 1");
        } else {
            final long need = plugin.players().config().experienceToNext(character.level());
            final String bonus = plugin.players().config().experienceBonusText();
            lines.add(character.characterClass().color() + escape(character.name()) + " <gray>" + character.characterClass().displayName()
                    + " <white>Lv." + character.level()
                    + (need > 0 ? String.format(Locale.ROOT, "%s<gray>經驗 <white>%,d<dark_gray>/%,d %s <gray>%.1f%%",
                    SEP, character.experience(), need, bar(character.experience(), need), 100.0 * character.experience() / need)
                    + experienceNote(plugin, player, bonus)
                    : SEP + "<gray>滿級"));
            if (self) {
                lines.add(String.format(Locale.ROOT, "<gray>金幣 %s%,d <dark_gray>（帳號共用）", GOLD, state.gold()));
            }
        }
        return lines;
    }

    public static List<String> attributes(final ExileCore plugin, final Player player) {
        final PlayerState state = plugin.players().get(player);
        plugin.players().sheet(player);
        final double str = state.strength();
        final double dex = state.dexterity();
        final double intelligence = state.intelligence();
        // 2026-10：每一種屬性額外給了什麼（跟 PlayerStateService.rebuild 同一套公式）
        return List.of(String.format(Locale.ROOT, "%s力量 <white>%d%s%s敏捷 <white>%d%s%s智慧 <white>%d",
                        STR, Math.round(str), SEP, DEX, Math.round(dex), SEP, INT, Math.round(intelligence)),
                String.format(Locale.ROOT, "%s力量<dark_gray>：<gray>生命 <white>+%d", STR, Math.round(tw.exilecore.stats.Formulas.lifeFromStrength(str))),
                String.format(Locale.ROOT, "%s敏捷<dark_gray>：<gray>命中 <white>+%d", DEX, Math.round(tw.exilecore.stats.Formulas.accuracyFromDexterity(dex))),
                String.format(Locale.ROOT, "%s智慧<dark_gray>：<gray>魔力 <white>+%d", INT, Math.round(tw.exilecore.stats.Formulas.manaFromIntelligence(intelligence))),
                "<dark_gray>每點力量 +2 生命、每點敏捷 +6 命中、每點智慧 +2 魔力。裝備的屬性需求也看這三個");
    }

    public static List<String> defence(final ExileCore plugin, final Player player) {
        return defence(plugin, player, false);
    }

    /** @param chat 聊天欄版本：說明放在 hover，不另外佔行 */
    private static List<String> defence(final ExileCore plugin, final Player player, final boolean chat) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final DefenceEstimates estimates = estimates(plugin, state, sheet);
        final List<String> lines = new ArrayList<>();
        final double lifeRegen = sheet.get(StatKeys.LIFE_REGEN) + state.maxLife() * sheet.get(StatKeys.LIFE_REGEN_PERCENT) / 100.0;
        lines.add(String.format(Locale.ROOT, "%s❤ 生命 <white>%d <dark_gray>回復 %.1f/秒%s%s◆ 魔力 <white>%d <dark_gray>回復 %.1f/秒",
                LIFE, Math.round(state.maxLife()), lifeRegen, SEP, MANA, Math.round(state.maxMana()), sheet.get(StatKeys.MANA_REGEN)));
        // 精魂一律顯示（0 也顯示，才知道要去哪裡拿）
        lines.add(String.format(Locale.ROOT, "<#c9a7ff>✧ 精魂 <white>%.0f</white><gray>（已用 %.0f，剩 %.0f）%s",
                state.maxSpirit(), state.reservedSpirit(), Math.max(0, state.maxSpirit() - state.reservedSpirit()),
                chat ? "" : " <dark_gray>精魂技能、捷光環用；劇情 Boss、權杖、胸甲與項鍊給"));
        final double block = sheet.get(StatKeys.BLOCK_CHANCE);
        lines.add(String.format(Locale.ROOT, "%s✦ 能量護盾 <white>%d%s%s⛨ 格擋 <white>%d%%%s",
                ES, Math.round(state.maxEnergyShield()), SEP, BLOCK, Math.round(Math.min(StatKeys.BLOCK_CAP, block)),
                block > StatKeys.BLOCK_CAP ? String.format(Locale.ROOT, " <dark_gray>（上限 %d%%，堆了 %d%%）", Math.round(StatKeys.BLOCK_CAP), Math.round(block)) : ""));
        if (state.maxEnergyShield() > 0) {
            lines.add(String.format(Locale.ROOT, "<dark_gray>　護盾被打後 %.1f 秒開始回充，每秒回 %,d",
                    PlayerStateService.esRechargeDelay(sheet) / 20.0,
                    Math.round(Formulas.esRechargePerSecond(state.maxEnergyShield(), PlayerStateService.esRechargeSpeed(sheet)))));
        }
        final long armour = Math.round(sheet.get(StatKeys.ARMOUR));
        final long evasion = Math.round(sheet.get(StatKeys.EVASION));
        if (estimates == null) {
            lines.add(String.format(Locale.ROOT, "<gray>護甲 <white>%d%s<gray>閃避 <white>%d", armour, SEP, evasion));
        } else {
            final String armourText = String.format(Locale.ROOT, "<gray>護甲 <white>%d <dark_gray>＝減傷 <white>%d%%",
                    armour, Math.round(estimates.armourReduction() * 100));
            final String evasionText = String.format(Locale.ROOT, "<gray>閃避 <white>%d <dark_gray>＝閃避率 <white>%d%%",
                    evasion, Math.round(estimates.evadeChance() * 100));
            final String armourHelp = String.format(Locale.ROOT, "同等級怪物的一下（約 %d 物理）減免 %d%%<newline>Boss 重擊這種大約普通怪 3 倍以上傷害的一下，減免只剩 %d%%<newline><dark_gray>護甲擋小傷害比擋大傷害有效",
                    Math.round(estimates.monsterHit()), Math.round(estimates.armourReduction() * 100), Math.round(estimates.armourReductionBigHit() * 100));
            final String evasionHelp = String.format(Locale.ROOT, "躲開同等級怪物攻擊的機率 %d%%<newline><dark_gray>法術躲不開", Math.round(estimates.evadeChance() * 100));
            if (chat) {
                lines.add(hover(armourText, armourHelp) + SEP + hover(evasionText, evasionHelp));
            } else {
                lines.add(armourText + SEP + evasionText);
                lines.add("<dark_gray>　" + armourHelp.replace("<newline>", "；").replace("<dark_gray>", ""));
                lines.add("<dark_gray>　" + evasionHelp.replace("<newline>", "；").replace("<dark_gray>", ""));
            }
        }
        // 2026-10：偏斜（沒閃掉的擊中再擲一次，偏斜的傷害更少 40%）
        final double deflection = sheet.get(StatKeys.DEFLECTION)
                + sheet.get(StatKeys.EVASION) * Math.max(0, sheet.flat(StatKeys.DEFLECTION_FROM_EVASION, java.util.Set.of())) / 100.0;
        if (deflection > 0) {
            final double accuracy = plugin.monsters() == null ? 0 : plugin.monsters().config().accuracyAt(Math.max(1, state.level()));
            lines.add(String.format(Locale.ROOT, "<gray>偏斜 <white>%d <dark_gray>＝偏斜率 <white>%d%%<dark_gray>（偏斜的擊中傷害更少 %d%%）",
                    Math.round(deflection), Math.round(tw.exilecore.stats.DamageCalculator.deflectChance(accuracy, deflection) * 100),
                    Math.round(StatKeys.DEFLECT_LESS * 100)));
        }
        lines.add(String.format(Locale.ROOT, "<gray>抗性 %s %s %s %s <dark_gray>（上限 %d%%）",
                resist(sheet, DamageType.FIRE), resist(sheet, DamageType.COLD), resist(sheet, DamageType.LIGHTNING), resist(sheet, DamageType.CHAOS),
                Math.round(sheet.get(StatKeys.RESIST_MAX))));
        // 2026-10：最大承受一擊（生命＋能量護盾，扣掉抗性、護甲；沒算格擋、閃避、受到傷害的增減）
        final List<String> hits = new ArrayList<>();
        for (final DamageType type : DamageType.values()) {
            hits.add("<" + DamageNumbers.color(type) + ">" + shortName(type) + " <white>" + maxHitText(state, sheet, type));
        }
        final String maxHit = "<gray>最大承受一擊 " + String.join("<dark_gray>・", hits);
        final String maxHitHelp = "多大的一下會直接把你打死（生命＋能量護盾，混沌不過護盾）<newline>"
                + "已經扣掉抗性與護甲；沒算格擋、閃避、受到傷害增加或減少<newline><dark_gray>越高越不容易被 Boss 的大招秒掉";
        lines.add(chat ? hover(maxHit, maxHitHelp) : maxHit);
        lines.addAll(ailmentDefence(sheet));
        if (plugin.pvp() != null && plugin.pvp().inArena(player)) {
            lines.add(String.format(Locale.ROOT, "<#ff6b6b>⚔ 競技場<gray>：玩家打你的傷害減少 <white>%d%%<dark_gray>（專屬的 PvP 減傷，你打別人也一樣）",
                    Math.round(plugin.pvp().config().reduction())));
        }
        return lines;
    }

    /** 避免異常的屬性與名稱（/stats 照這個順序列）。 */
    private static final String[][] AVOIDS = {
            {StatKeys.AVOID_IGNITE, "點燃"}, {StatKeys.AVOID_CHILL, "冰緩"}, {StatKeys.AVOID_FREEZE, "凍結"}, {StatKeys.AVOID_SHOCK, "感電"},
            {StatKeys.AVOID_ELECTROCUTE, "電擊"}, {StatKeys.AVOID_BLEED, "流血"}, {StatKeys.AVOID_POISON, "中毒"}, {StatKeys.AVOID_STUN, "暈眩"}};

    /**
     * 異常的防禦（有才列）：避免（上限 75%，堆超過的寫出來）、無法被凍結；元素異常門檻、暈眩門檻、身上異常持續時間（最多減到 10%）。
     */
    static List<String> ailmentDefence(final StatSheet sheet) {
        final List<String> lines = new ArrayList<>();
        final boolean elementalImmune = StatKeys.flag(sheet, StatKeys.FLAG_ELEMENTAL_AILMENT_IMMUNE);
        final boolean freezeImmune = elementalImmune || StatKeys.flag(sheet, StatKeys.FLAG_FREEZE_IMMUNE);
        final List<String> avoid = new ArrayList<>();
        if (elementalImmune) {
            avoid.add("<white>免疫元素異常");
        } else if (freezeImmune) {
            avoid.add("<white>無法被凍結");
        }
        for (final String[] row : AVOIDS) {
            final double value = sheet.get(row[0]);
            final boolean elemental = row[0].equals(StatKeys.AVOID_IGNITE) || row[0].equals(StatKeys.AVOID_CHILL)
                    || row[0].equals(StatKeys.AVOID_FREEZE) || row[0].equals(StatKeys.AVOID_SHOCK);
            if (value <= 0 || freezeImmune && row[0].equals(StatKeys.AVOID_FREEZE) || elementalImmune && elemental) {
                continue;
            }
            avoid.add("<gray>" + row[1] + " <white>" + Math.round(Math.min(StatKeys.AVOID_CAP, value)) + "%"
                    + (value > StatKeys.AVOID_CAP ? "<dark_gray>（上限，堆了 " + Math.round(value) + "%）" : ""));
        }
        if (!avoid.isEmpty()) {
            lines.add("<gray>避免異常 " + String.join("<dark_gray>・", avoid) + " <dark_gray>（每種最多 " + Math.round(StatKeys.AVOID_CAP) + "%）");
        }
        final List<String> other = new ArrayList<>();
        final double threshold = sheet.increased(StatKeys.AILMENT_THRESHOLD, Set.of());
        if (threshold != 0) {
            other.add("<gray>元素異常門檻 <white>" + signedPercent(threshold));
        }
        final double stun = sheet.increased(StatKeys.STUN_THRESHOLD, Set.of());
        if (stun != 0) {
            other.add("<gray>暈眩門檻 <white>" + signedPercent(stun));
        }
        final double self = sheet.increased(StatKeys.SELF_AILMENT_DURATION, Set.of());
        if (self != 0) {
            other.add("<gray>身上異常持續時間 <white>" + signedPercent(Math.max(-0.9, self))
                    + (self < -0.9 ? "<dark_gray>（最多減到 10%，堆了 " + signedPercent(self) + "）" : ""));
        }
        if (!other.isEmpty()) {
            lines.add(String.join("<dark_gray>・", other));
        }
        return lines;
    }

    /** 「移動速度 +12%」（0 不列）。 */
    private static void addPercent(final List<String> into, final String name, final double fraction) {
        if (Math.abs(fraction) >= 0.005) {
            into.add("<gray>" + name + " <white>" + signedPercent(fraction));
        }
    }

    /** 0.4 → 「+40%」、−0.3 → 「−30%」。 */
    private static String signedPercent(final double fraction) {
        final long value = Math.round(fraction * 100);
        return (value >= 0 ? "+" : "−") + Math.abs(value) + "%";
    }

    /** 一種抗性：元素色的名字＋數值；到上限時數值變綠（超過的寫「堆了多少」）、負的變紅。 */
    private static String resist(final StatSheet sheet, final DamageType type) {
        final long value = Math.round(sheet.get(type.mitigationStat()));
        final long max = Math.round(sheet.get(StatKeys.RESIST_MAX));
        final String color = value < 0 ? "<red>" : value >= max ? "<green>" : "<white>";
        return String.format(Locale.ROOT, "<%s>%s %s%d%%", DamageNumbers.color(type), shortName(type), color, Math.min(value, max))
                + (value > max ? String.format(Locale.ROOT, "<dark_gray>(堆%d)", value) : "");
    }

    /** 這一種傷害最大能吃多大的一下（減免前的傷害）：二分搜尋，因為護甲對大傷害比較沒效。 */
    private static String maxHitText(final PlayerState state, final StatSheet sheet, final DamageType type) {
        final double hit = maxHit(sheet, type);
        if (Double.isNaN(hit)) {
            return "免疫";
        }
        return Double.isInfinite(hit) ? "∞" : String.format(Locale.ROOT, "%,d", Math.round(hit));
    }

    /**
     * 這一種傷害最大能吃多大的一下（減免前；生命＋能量護盾，混沌不過護盾）。免疫回傳 NaN，大到算不完回傳無限大。
     * 只看屬性表（2026-10：網頁計算器的虛擬角色也用）。
     */
    public static double maxHit(final StatSheet sheet, final DamageType type) {
        if (type == DamageType.CHAOS && StatKeys.flag(sheet, StatKeys.FLAG_CHAOS_IMMUNE)) {
            return Double.NaN;
        }
        final double life = PlayerState.maxLife(sheet);
        final double es = PlayerState.maxEnergyShield(sheet);
        final double pool = Math.max(1, life + (type == DamageType.CHAOS ? es / tw.exilecore.stats.DamageCalculator.CHAOS_ES_MULTIPLIER : es));
        double low = 0;
        double high = pool;
        final double limit = pool * 1000;
        while (taken(sheet, type, high) <= pool) {
            high *= 2;
            if (high > limit) {
                return Double.POSITIVE_INFINITY;
            }
        }
        for (int i = 0; i < 40; i++) {
            final double mid = (low + high) / 2;
            if (taken(sheet, type, mid) <= pool) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * 屬性表的主要數字（2026-10，網頁計算器）：鍵 → 數值。跟 /stats 同一套算法，只看屬性表與等級。
     * 抗性是封頂後的；另外給 resist.*.raw（堆了多少）。最大承受一擊：免疫是 -1、無限大是 -2。
     */
    public static java.util.LinkedHashMap<String, Double> numbers(final ExileCore plugin, final StatSheet sheet, final int level) {
        final java.util.LinkedHashMap<String, Double> out = new java.util.LinkedHashMap<>();
        final double life = PlayerState.maxLife(sheet);
        out.put("life", life);
        out.put("life.regen", sheet.get(StatKeys.LIFE_REGEN) + life * sheet.get(StatKeys.LIFE_REGEN_PERCENT) / 100.0);
        out.put("mana", PlayerState.maxMana(sheet));
        out.put("mana.regen", sheet.get(StatKeys.MANA_REGEN));
        out.put("es", PlayerState.maxEnergyShield(sheet));
        out.put("spirit", Math.max(0, sheet.get(StatKeys.SPIRIT_MAX)));
        out.put("spirit.reserved", Math.max(0, sheet.get(StatKeys.SPIRIT_RESERVED)));
        out.put("str", sheet.get(StatKeys.STR));
        out.put("dex", sheet.get(StatKeys.DEX));
        out.put("int", sheet.get(StatKeys.INT));
        out.put("armour", sheet.get(StatKeys.ARMOUR));
        out.put("evasion", sheet.get(StatKeys.EVASION));
        out.put("block", Math.min(StatKeys.BLOCK_CAP, sheet.get(StatKeys.BLOCK_CHANCE)));
        out.put("accuracy", sheet.get(StatKeys.ACCURACY));
        out.put("movement", sheet.increased(StatKeys.MOVEMENT_SPEED, Set.of()) * 100); // 跟 /stats 一樣：增加幾 %
        final double max = sheet.get(StatKeys.RESIST_MAX);
        out.put("resist.max", max);
        for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING, DamageType.CHAOS}) {
            final String key = "resist." + shortKey(type);
            final double raw = sheet.get(type.mitigationStat());
            out.put(key, Math.min(raw, max));
            out.put(key + ".raw", raw);
        }
        final DefenceEstimates estimates = plugin == null ? null : estimatesAt(plugin, level, sheet);
        if (estimates != null) {
            out.put("armour.reduction", estimates.armourReduction() * 100);
            out.put("evade.chance", estimates.evadeChance() * 100);
        }
        for (final DamageType type : DamageType.values()) {
            final double hit = maxHit(sheet, type);
            out.put("maxhit." + shortKey(type), Double.isNaN(hit) ? -1 : Double.isInfinite(hit) ? -2 : hit);
        }
        return out;
    }

    private static String shortKey(final DamageType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private static double taken(final StatSheet sheet, final DamageType type, final double raw) {
        final DamagePacket packet = new DamagePacket();
        packet.set(type, raw);
        return DamageCalculator.mitigate(packet, sheet).total();
    }

    private static String shortName(final DamageType type) {
        return switch (type) {
            case FIRE -> "火";
            case COLD -> "冰";
            case LIGHTNING -> "雷";
            case CHAOS -> "混沌";
            default -> "物理";
        };
    }

    public static List<String> offence(final ExileCore plugin, final Player player) {
        return offence(plugin, player, false);
    }

    private static List<String> offence(final ExileCore plugin, final Player player, final boolean chat) {
        final PlayerState state = plugin.players().get(player);
        // 2026-10：含怒氣這種擊中時才算的加成，面板上的數字才看得出增益有沒有生效
        final StatSheet sheet = BuffImpact.liveSheet(plugin, player);
        final SkillProfile attack = SkillProfile.defaultAttack(state.weaponTags());
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, attack);
        final double aps = DamageCalculator.attacksPerSecond(sheet, attack.tags());
        final DefenceEstimates estimates = estimates(plugin, state, sheet);
        // 2026-10：含亞馬遜命中率溢出轉的暴擊（對同等級怪物）
        final double crit = DamageCalculator.critChance(sheet, attack, monsterEvasion(plugin, state));
        final double amazonCrit = DamageCalculator.amazonCritBonus(attack.effective(sheet), attack, monsterEvasion(plugin, state));
        // 暴擊傷害照這一下的標籤算（項鍊、天賦的「攻擊暴擊傷害」只有攻擊吃得到）
        final double critMulti = DamageCalculator.critMultiplier(sheet, attack.tags(), null) * 100;
        final List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "<gray>⚔ 普攻 <dark_gray>（%s・%s）<gray> 平均 %s%s<gray>每秒 <white>%.2f <gray>次",
                attack.tags().contains(Tags.PROJECTILE) ? "遠程" : "近戰", weaponText(state.weaponTags()), damageText(expected), SEP, aps));
        final String accuracyText = String.format(Locale.ROOT, "<gray>命中 <white>%d", Math.round(sheet.get(StatKeys.ACCURACY)));
        final String critText = String.format(Locale.ROOT, "<gray>暴擊 <white>%.1f%% <dark_gray>× <white>%.0f%%", crit * 100, critMulti)
                + (amazonCrit > 0 && crit > 0 ? String.format(Locale.ROOT, " <dark_gray>（含命中溢出 +%.1f%%）", amazonCrit * 100) : "");
        if (estimates == null) {
            lines.add(accuracyText + SEP + critText);
        } else {
            final String hitText = accuracyText + String.format(Locale.ROOT, " <dark_gray>＝命中率 <white>%d%%", Math.round(estimates.hitChance() * 100));
            final String hitHelp = String.format(Locale.ROOT, "攻擊打中同等級怪物的機率 %d%%<newline><dark_gray>法術必中", Math.round(estimates.hitChance() * 100));
            if (chat) {
                lines.add(hover(hitText, hitHelp) + SEP + hover(critText, "暴擊時傷害 ×" + Math.round(critMulti) + "%"));
            } else {
                lines.add(hitText + SEP + critText);
                lines.add("<dark_gray>　" + hitHelp.replace("<newline>", "；").replace("<dark_gray>", ""));
            }
        }
        final long dps = Math.round(expected.total() * aps * (1 + crit * (critMulti / 100.0 - 1)));
        final String dpsText = String.format(Locale.ROOT, "<gray>估計每秒傷害 <white>%,d", dps);
        lines.add(chat ? hover(dpsText, "普攻平均傷害 × 每秒次數 × 暴擊期望<newline><dark_gray>沒算怪物的護甲與抗性；技能的傷害看 /skill")
                : dpsText + " <dark_gray>（未減免、含暴擊）");
        // 2026-10：技能欄上每個技能的每秒傷害（大家打怪都是用技能，不是普攻）
        if (plugin.skills() != null) {
            for (final String[] row : plugin.skills().skillDps(player)) {
                final long base = Long.parseLong(row[2]);
                final long full = row.length > 3 ? Long.parseLong(row[3]) : base;
                // 2026-10：戰吼、戰旗、詛咒、印記都開著的話（比平常多 5% 以上才顯示）
                final String text = String.format(Locale.ROOT, "<gray>✦ <dark_gray>[%s]</dark_gray> %s 每秒 <white>%,d", row[0],
                        escape(row[1]), base) + (full > base * 1.05 ? String.format(Locale.ROOT, " <dark_gray>（全開 <white>%,d</white>）", full) : "");
                lines.add(chat ? hover(text, "平均一下 × 暴擊期望 × 每秒次數（有冷卻照冷卻算；含連射的每一波與餘震）<newline>"
                        + "＋點燃、流血、中毒的持續傷害，再乘感電的增傷（照同等級一般怪物的門檻算機率與覆蓋率）<newline>"
                        + "<dark_gray>召喚技能：一隻召喚物的每秒傷害 × 同時最多幾隻<newline>"
                        + "<dark_gray>全開：技能欄上的戰吼、戰旗的增益都開著、強化攻擊照冷卻平均，詛咒、印記都掛在敵人身上<newline>"
                        + "<dark_gray>沒算怪物的減免、曝露、怒氣與蓄力這些打起來才有的加成、打到幾個敵人<newline>"
                        + "<dark_gray>實際輸出去營地打訓練假人最準") : text);
            }
            // 2026-10：打訓練假人的實測（估計值跟實際可以差很多）
            final tw.exilecore.training.DummyRecord measured = tw.exilecore.training.DummyRecord.decode(
                    state.progress().get(tw.exilecore.training.DummyRecord.KEY));
            if (measured != null) {
                final String text = String.format(Locale.ROOT, "<gray>✦ 假人實測 每秒 <white>%,d <dark_gray>（%s・%.1f 秒%s）", measured.dps(),
                        escape(measured.dummy()), measured.seconds(),
                        measured.dotPercent() > 0 ? "・持續傷害佔 " + measured.dotPercent() + "%" : "");
                lines.add(text); // 2026-10：不再顯示滑鼠提示
            }
        }
        // 2026-10：其他常用的數值
        final List<String> misc = new ArrayList<>();
        addPercent(misc, "移動速度", sheet.increased(StatKeys.MOVEMENT_SPEED, Set.of()));
        addPercent(misc, "技能範圍", sheet.increased(StatKeys.SKILL_AREA, Set.of()));
        addPercent(misc, "持續時間", sheet.increased(StatKeys.SKILL_DURATION, Set.of()));
        addPercent(misc, "冷卻時間", sheet.increased(StatKeys.SKILL_COOLDOWN, Set.of()));
        if (!misc.isEmpty()) {
            lines.add(String.join(SEP, misc));
        }
        final List<String> sustain = new ArrayList<>();
        final double leech = sheet.get(StatKeys.LEECH_LIFE);
        if (leech > 0) {
            sustain.add(String.format(Locale.ROOT, "<gray>物理偷取生命 <white>%.1f%%", leech));
        }
        final double leechElemental = sheet.get(StatKeys.LEECH_LIFE_ELEMENTAL);
        if (leechElemental > 0) {
            sustain.add(String.format(Locale.ROOT, "<gray>元素偷取生命 <white>%.1f%%", leechElemental));
        }
        final double leechMana = sheet.get(StatKeys.LEECH_MANA);
        if (leechMana > 0) {
            sustain.add(String.format(Locale.ROOT, "<gray>偷取魔力 <white>%.1f%%", leechMana));
        }
        final double onHit = sheet.get(StatKeys.LIFE_ON_HIT);
        if (onHit > 0) {
            sustain.add(String.format(Locale.ROOT, "<gray>擊中回復 <white>%d<gray> 生命", Math.round(onHit)));
        }
        final double onKill = sheet.get(StatKeys.LIFE_ON_KILL);
        if (onKill > 0) {
            sustain.add(String.format(Locale.ROOT, "<gray>擊殺回復 <white>%d<gray> 生命", Math.round(onKill)));
        }
        if (!sustain.isEmpty()) {
            lines.add(String.join(SEP, sustain));
        }
        // 法術的暴擊（攻擊跟法術的暴擊分開算：通用的兩邊都吃，「法術暴擊」只有法術吃）
        final SkillProfile spell = new SkillProfile("stats_spell", Set.of(Tags.SPELL), 1.0, null);
        final String spellText = String.format(Locale.ROOT, "<gray>✦ 法術 暴擊 <white>%.1f%% <dark_gray>× <white>%.0f%%",
                DamageCalculator.critChance(sheet, spell) * 100, DamageCalculator.critMultiplier(sheet, spell.tags(), null) * 100);
        final String spellHelp = "每個法術有自己的基礎暴擊率（5%～15%，寫在寶石說明）；這裡用 "
                + Math.round(DamageCalculator.BASE_SPELL_CRIT) + "% 算，吃通用的與法術的暴擊率、暴擊傷害"
                + "<newline><dark_gray>每個技能自己的數字看 /skill";
        lines.add(chat ? hover(spellText, spellHelp) : spellText + " <dark_gray>（以基礎 " + Math.round(DamageCalculator.BASE_SPELL_CRIT) + "% 算；每個法術不同）");
        return lines;
    }

    /** 各類型的平均傷害，照元素上色：「物理 4」「物理 3 火焰 2」；空的是「0」。 */
    static String damageText(final DamagePacket packet) {
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<DamageType, Double> entry : packet.asMap().entrySet()) {
            if (entry.getValue() < 0.5) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append('<').append(DamageNumbers.color(entry.getKey())).append('>').append(entry.getKey().displayName())
                    .append(" <white>").append(Math.round(entry.getValue()));
        }
        return text.isEmpty() ? "<white>0" : text.toString();
    }

    /** 特殊效果（傳奇裝備、基石）：「+1 法術技能等級」「擊殺時回復 4% 最大生命」…；沒有就是空的。 */
    public static List<String> specials(final ExileCore plugin, final Player player) {
        final StatSheet sheet = plugin.players().sheet(player);
        final List<String> lines = new ArrayList<>();
        for (final String line : SpecialEffectLines.of(sheet)) {
            lines.add("<#d6a5ff>" + escape(line));
        }
        if (plugin.combat() != null && plugin.combat().killEffects() != null && plugin.combat().killEffects().hasOnslaught(player)) {
            lines.add("<#ffd27f>疾速中<gray>（攻擊、施法、移動 +20%）");
        }
        return lines;
    }

    /** 身上的球、開著的光環、隊友給的光環、戰吼的增益與強化；沒有就是空的。 */
    public static List<String> buffs(final ExileCore plugin, final Player player) {
        final List<String> lines = new ArrayList<>();
        if (plugin.combat() != null && plugin.combat().charges() != null) {
            lines.addAll(plugin.combat().charges().lines(player));
        }
        if (plugin.skills() != null) {
            lines.addAll(plugin.skills().statusLines(player));
            // 2026-10：怒氣（打中時才算，但現在就看得出來給了多少）
            final int rage = plugin.skills().resources().rage(player);
            if (rage > 0) {
                lines.add(String.format(Locale.ROOT, "<#ff6b4a>怒氣 <white>%d<dark_gray>/%d<gray>：攻擊傷害增加 <white>%d%%",
                        rage, plugin.skills().resources().rageMax(plugin.players().sheet(player)),
                        Math.round(BuffImpact.rageBonus(plugin, player) * 100)));
            }
        }
        return lines;
    }

    /** 增益讓面板上哪些數值變了（2026-10）：跟沒有增益時比。 */
    public static BuffImpact.Lines impact(final ExileCore plugin, final Player player) {
        return BuffImpact.of(plugin, player);
    }

    /** 「效果」那一區的增益比較（聊天欄、網頁用）：有變的才列。 */
    private static List<String> impactSection(final ExileCore plugin, final Player player) {
        final BuffImpact.Lines impact = impact(plugin, player);
        if (impact.isEmpty()) {
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        lines.add("<#ffd27f>▲ 增益讓數值變成<dark_gray>（上面的數字已經含增益）：");
        for (final String line : impact.all()) {
            lines.add("<dark_gray>　" + line);
        }
        return lines;
    }

    /** 給召喚物的加成（天賦、裝備、昇華的「召喚物…」）與目前有幾隻；沒有就是空的。 */
    public static List<String> minions(final ExileCore plugin, final Player player) {
        final List<String> lines = new ArrayList<>();
        for (final String line : tw.exilecore.skill.MinionSheets.summary(plugin.players().sheet(player))) {
            lines.add(ES + escape(line));
        }
        if (plugin.skills() != null) {
            final int count = plugin.skills().minions().count(player.getUniqueId());
            if (count > 0) {
                lines.add("<gray>目前的召喚物：<white>" + count + "<gray> 隻");
            }
        }
        return lines;
    }

    /** 對同等級普通怪物的估計值；怪物模組沒啟用時是 null。 */
    /** 同等級普通怪物的閃避（亞馬遜的命中溢出暴擊用）；沒有怪物設定回傳 0。 */
    public static double monsterEvasion(final ExileCore plugin, final PlayerState state) {
        return plugin.monsters() == null ? 0 : plugin.monsters().config().evasionAt(Math.max(1, state.level()));
    }

    static DefenceEstimates estimates(final ExileCore plugin, final PlayerState state, final StatSheet sheet) {
        return estimatesAt(plugin, state.level(), sheet);
    }

    static DefenceEstimates estimatesAt(final ExileCore plugin, final int characterLevel, final StatSheet sheet) {
        if (plugin.monsters() == null) {
            return null;
        }
        final tw.exilecore.monster.MonsterConfig monsters = plugin.monsters().config();
        final int level = Math.max(1, characterLevel);
        final double[] damage = monsters.damageAt(level);
        return DefenceEstimates.of(damage[0], damage[1], monsters.accuracyAt(level), monsters.evasionAt(level),
                sheet.get(StatKeys.ARMOUR), sheet.get(StatKeys.EVASION), sheet.get(StatKeys.ACCURACY));
    }

    public static List<String> passives(final ExileCore plugin, final Player player) {
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state.character();
        if (character == null) {
            return List.of("<gray>沒有角色");
        }
        final long cost = plugin.players().config().respecCost(character.level());
        final int available = plugin.players().availablePoints(state);
        final List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "<gray>可用 %s%d%s<gray>已配置 <white>%d%s<gray>退一點 %s",
                available > 0 ? "<green>" : "<white>", available, SEP, state.passives().size(), SEP,
                character.refundPoints() > 0 ? "<white>免費 <dark_gray>（剩 " + character.refundPoints() + " 次）"
                        : cost <= 0 ? "<white>免費" : String.format(Locale.ROOT, "%s%,d 金幣", GOLD, cost)));
        if (!state.jewels().isEmpty()) {
            lines.add("<gray>天賦樹的珠寶 <#c77dff>" + state.jewels().size() + " <dark_gray>顆（在天賦樹的珠寶孔裡）");
        }
        return lines;
    }

    public static List<String> inactive(final ExileCore plugin, final Player player) {
        final PlayerState state = plugin.players().get(player);
        plugin.players().sheet(player);
        final List<String> lines = new ArrayList<>();
        for (final String line : state.inactiveEquipment()) {
            lines.add("<red>" + escape(line));
        }
        return lines;
    }

    // ---- 聊天欄版本 ----

    /** 全部的行，給 /stats：分區塊、說明放在 hover、最後一行可以點。 */
    /** 網頁角色頁用（2026-10）：分段的屬性（段落標題 → 那一段的行，MiniMessage）。 */
    public static java.util.LinkedHashMap<String, List<String>> sections(final ExileCore plugin, final Player player) {
        final java.util.LinkedHashMap<String, List<String>> map = new java.util.LinkedHashMap<>();
        final List<String> attributes = attributes(plugin, player);
        map.put("屬性", attributes.subList(0, Math.max(0, attributes.size() - 2)));
        map.put("防禦", defence(plugin, player, true));
        map.put("攻擊", offence(plugin, player, true));
        final List<String> effects = new ArrayList<>();
        effects.addAll(specials(plugin, player));
        effects.addAll(buffs(plugin, player));
        effects.addAll(impactSection(plugin, player));
        effects.addAll(minions(plugin, player));
        if (!effects.isEmpty()) {
            map.put("效果", effects);
        }
        map.put("天賦", passives(plugin, player));
        return map;
    }

    public static List<String> all(final ExileCore plugin, final Player player) {
        return all(plugin, player, true);
    }

    /**
     * 全部的行。
     *
     * @param self 是不是本人在看：看別人的（/stats 名字）標題寫是誰的、沒有金幣、最後沒有開天賦樹那排按鈕
     */
    public static List<String> all(final ExileCore plugin, final Player player, final boolean self) {
        final List<String> lines = new ArrayList<>();
        lines.add(title(self ? "角色" : escape(player.getName()) + " 的角色"));
        indent(lines, header(plugin, player, self));
        indent(lines, drops(plugin, player)); // 2026-10：物品掉落數量、稀有度
        // 2026-10：聊天欄的屬性只列力量、敏捷、智慧各多少（不列各給了什麼；角色面板的「屬性」還看得到）
        lines.add(title("屬性"));
        indent(lines, attributes(plugin, player).subList(0, 1));
        lines.add(title("防禦"));
        indent(lines, defence(plugin, player, true));
        lines.add(title("攻擊"));
        indent(lines, offence(plugin, player, true));
        final List<String> specials = specials(plugin, player);
        final List<String> buffs = buffs(plugin, player);
        final List<String> minions = minions(plugin, player);
        final List<String> impact = impactSection(plugin, player);
        if (!specials.isEmpty() || !buffs.isEmpty() || !minions.isEmpty() || !impact.isEmpty()) {
            lines.add(title("效果"));
            indent(lines, specials);
            indent(lines, buffs);
            indent(lines, impact);
            indent(lines, minions);
        }
        final List<String> inactive = inactive(plugin, player);
        if (!inactive.isEmpty()) {
            lines.add(title("<red>未生效的裝備"));
            indent(lines, inactive);
            lines.add(INDENT + "<dark_gray>提升等級或屬性後會自動生效");
        }
        lines.add(title("天賦"));
        indent(lines, passives(plugin, player));
        if (self) {
            lines.add(INDENT + button("天賦樹", "/tree", "打開天賦樹（/tree）") + " "
                    + button("角色面板", "/panel", "飾品欄位、切換角色（/panel）") + " "
                    + button("技能", "/skill", "技能寶石與按鍵（/skill）"));
        }
        return lines;
    }

    /** 區塊標題：金色字＋一條線。 */
    private static String title(final String name) {
        return GOLD + "<bold>" + name + "</bold> <dark_gray>" + RULE;
    }

    private static void indent(final List<String> into, final List<String> lines) {
        for (final String line : lines) {
            into.add(INDENT + line);
        }
    }

    /** 經驗條：十格，滿的綠、空的暗。 */
    private static String bar(final long value, final long max) {
        final int filled = (int) Math.max(0, Math.min(10, Math.floor(10.0 * value / Math.max(1, max))));
        return "<green>" + "▮".repeat(filled) + "<dark_gray>" + "▮".repeat(10 - filled);
    }

    /** 滑鼠移上去顯示說明。 */
    private static String hover(final String text, final String help) {
        return "<hover:show_text:'<gray>" + help.replace("'", "’") + "'>" + text + "</hover>";
    }

    /** 可以點的按鈕。 */
    private static String button(final String label, final String command, final String help) {
        return "<click:run_command:'" + command + "'><hover:show_text:'<gray>" + help + "'><yellow>[" + label + "]</yellow></hover></click>";
    }

    private static String weaponText(final Set<String> tags) {
        if (tags.isEmpty()) {
            return "徒手";
        }
        for (final String tag : List.of(Tags.SWORD, Tags.AXE, Tags.MACE, Tags.DAGGER, Tags.CLAW, Tags.WAND, Tags.STAFF, Tags.BOW,
                Tags.CROSSBOW, Tags.QUARTERSTAFF, Tags.SPEAR, Tags.TALISMAN)) {
            if (tags.contains(tag)) {
                return switch (tag) {
                    case Tags.SWORD -> "劍";
                    case Tags.AXE -> "斧";
                    case Tags.MACE -> "錘";
                    case Tags.DAGGER -> "匕首";
                    case Tags.CLAW -> "爪";
                    case Tags.WAND -> "法杖";
                    case Tags.STAFF -> "長杖";
                    case Tags.CROSSBOW -> "弩";
                    case Tags.QUARTERSTAFF -> "長棍";
                    case Tags.SPEAR -> "矛";
                    case Tags.TALISMAN -> "獸魂符";
                    default -> "弓";
                } + (tags.contains(Tags.TWO_HAND) ? "（雙手）" : "");
            }
        }
        return String.join(",", tags);
    }

    private static String escape(final String text) {
        return text.replace("<", "\\<");
    }
}
