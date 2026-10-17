package tw.exilecore.item;

import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 裝備比較（PoE 按 Alt 看的那幾行）：把一件裝備濃縮成玩家最在意的幾個數字，跟身上同欄位那件相減，寫成「護甲 +30、生命 −12」。
 * 純計算，不依賴 Bukkit；要把哪幾行加到物品說明上是 {@link ItemComparer} 的事。
 */
public final class ItemCompare {

    /** 一件裝備的摘要（都是這件自己給的：基底數值乘上這件的 % 詞綴，再加這件的固定詞綴）。 */
    public record Summary(double dps, double physicalDps, double aps, double crit, double armour, double evasion, double energyShield,
                          double block, double life, double mana, double resistFire, double resistCold, double resistLightning, double resistChaos,
                          double strength, double dexterity, double intelligence, double damageIncreased, double attackSpeed, double castSpeed,
                          double movementSpeed, double accuracy, double lifeRegen, double critIncreased,
                          double attackCritIncreased, double spellCritIncreased, double critMulti, double attackCritMulti, double spellCritMulti) {
    }

    private ItemCompare() {
    }

    /** 這件裝備的摘要。 */
    public static Summary summarize(final ItemRepository repository, final ItemData data) {
        final List<Modifier> mods = ItemStats.modifiers(repository, data);
        final double[] weaponMin = new double[DamageType.values().length];
        final double[] weaponMax = new double[DamageType.values().length];
        final double[] addedMin = new double[DamageType.values().length];
        final double[] addedMax = new double[DamageType.values().length];
        double aps = 0;
        double crit = 0;
        double armour = 0;
        double armourInc = 0;
        double evasion = 0;
        double evasionInc = 0;
        double es = 0;
        double esInc = 0;
        double block = 0;
        double life = 0;
        double mana = 0;
        double fire = 0;
        double cold = 0;
        double lightning = 0;
        double chaos = 0;
        double str = 0;
        double dex = 0;
        double intel = 0;
        double damageInc = 0;
        double physicalInc = 0;
        double attackSpeed = 0;
        double castSpeed = 0;
        double movement = 0;
        double accuracy = 0;
        double lifeRegen = 0;
        double critInc = 0;
        double attackCritInc = 0;
        double spellCritInc = 0;
        double critMulti = 0;
        double attackCritMulti = 0;
        double spellCritMulti = 0;
        for (final Modifier mod : mods) {
            final double value = mod.value();
            switch (mod.stat()) {
                case StatKeys.WEAPON_MIN -> addTyped(weaponMin, mod);
                case StatKeys.WEAPON_MAX -> addTyped(weaponMax, mod);
                case StatKeys.DAMAGE_ADDED_MIN -> {
                    if (!mod.tags().contains("spell")) {
                        addTyped(addedMin, mod);
                    }
                }
                case StatKeys.DAMAGE_ADDED_MAX -> {
                    if (!mod.tags().contains("spell")) {
                        addTyped(addedMax, mod);
                    }
                }
                case StatKeys.WEAPON_APS -> aps += value;
                case StatKeys.WEAPON_CRIT -> crit += value;
                case StatKeys.ARMOUR -> {
                    if (mod.type() == ModType.FLAT) {
                        armour += value;
                    } else {
                        armourInc += value;
                    }
                }
                case StatKeys.EVASION -> {
                    if (mod.type() == ModType.FLAT) {
                        evasion += value;
                    } else {
                        evasionInc += value;
                    }
                }
                case StatKeys.ES_MAX -> {
                    if (mod.type() == ModType.FLAT) {
                        es += value;
                    } else {
                        esInc += value;
                    }
                }
                case StatKeys.BLOCK_CHANCE -> block += value;
                case StatKeys.LIFE_MAX -> {
                    if (mod.type() == ModType.FLAT) {
                        life += value;
                    }
                }
                case StatKeys.MANA_MAX -> {
                    if (mod.type() == ModType.FLAT) {
                        mana += value;
                    }
                }
                case StatKeys.RESIST_FIRE -> fire += value;
                case StatKeys.RESIST_COLD -> cold += value;
                case StatKeys.RESIST_LIGHTNING -> lightning += value;
                case StatKeys.RESIST_CHAOS -> chaos += value;
                case StatKeys.STR -> str += value;
                case StatKeys.DEX -> dex += value;
                case StatKeys.INT -> intel += value;
                case StatKeys.DAMAGE -> {
                    if (mod.type() != ModType.FLAT) {
                        if (mod.tags().contains("physical") && !mod.tags().contains("spell")) {
                            physicalInc += value;
                        } else {
                            damageInc += value;
                        }
                    }
                }
                case StatKeys.ATTACK_SPEED -> attackSpeed += value;
                case StatKeys.CAST_SPEED -> castSpeed += value;
                case StatKeys.MOVEMENT_SPEED -> movement += value;
                case StatKeys.ACCURACY -> accuracy += value;
                case StatKeys.LIFE_REGEN -> lifeRegen += value;
                // 暴擊分三種：通用的（攻擊、法術都吃）、只算攻擊的、只算法術的（項鍊、施法武器、珠寶的專用詞綴）
                case StatKeys.CRIT_CHANCE -> {
                    if (mod.tags().contains("spell")) {
                        spellCritInc += value;
                    } else if (mod.tags().contains("attack")) {
                        attackCritInc += value;
                    } else {
                        critInc += value;
                    }
                }
                case StatKeys.CRIT_MULTI -> {
                    if (mod.type() == ModType.FLAT) {
                        if (mod.tags().contains("spell")) {
                            spellCritMulti += value;
                        } else if (mod.tags().contains("attack")) {
                            attackCritMulti += value;
                        } else {
                            critMulti += value;
                        }
                    }
                }
                // 2026-10-17：裝備的暴擊傷害改成「增加 x% 暴擊傷害加成」（武器以外）：一樣算進暴擊傷害比較（照百分比）
                case StatKeys.CRIT_BONUS -> {
                    if (mod.type() == ModType.INCREASED) {
                        if (mod.tags().contains("spell")) {
                            spellCritMulti += value * 100;
                        } else if (mod.tags().contains("attack")) {
                            attackCritMulti += value * 100;
                        } else {
                            critMulti += value * 100;
                        }
                    }
                }
                default -> {
                }
            }
        }
        double dps = 0;
        double physicalDps = 0;
        if (aps > 0) {
            final double speed = aps * (1 + attackSpeed);
            for (final DamageType type : DamageType.values()) {
                final int index = type.ordinal();
                double average = (weaponMin[index] + weaponMax[index] + addedMin[index] + addedMax[index]) / 2.0;
                if (type == DamageType.PHYSICAL) {
                    average *= 1 + physicalInc;
                    physicalDps = average * speed;
                }
                dps += average * speed;
            }
            aps = speed;
        }
        return new Summary(dps, physicalDps, aps, crit, armour * (1 + armourInc), evasion * (1 + evasionInc), es * (1 + esInc), block,
                life, mana, fire, cold, lightning, chaos, str, dex, intel, damageInc, attackSpeed, castSpeed, movement, accuracy, lifeRegen, critInc,
                attackCritInc, spellCritInc, critMulti, attackCritMulti, spellCritMulti);
    }

    private static void addTyped(final double[] into, final Modifier mod) {
        for (final DamageType type : DamageType.values()) {
            if (mod.tags().contains(type.tag())) {
                into[type.ordinal()] += mod.value();
                return;
            }
        }
        into[DamageType.PHYSICAL.ordinal()] += mod.value();
    }

    /**
     * 比較的那幾行（MiniMessage）：第一行寫跟誰比，後面每行最多三個差異；沒有差異寫「差不多」。
     *
     * @param equippedName 身上那件的名稱（顯示用）
     */
    public static List<String> lines(final ItemRepository repository, final ItemData candidate, final ItemData equipped, final String equippedName) {
        final Summary mine = summarize(repository, equipped);
        final Summary theirs = summarize(repository, candidate);
        final List<String> parts = new ArrayList<>();
        final ItemBase base = repository.base(candidate.base());
        if (base != null && base.isWeapon()) {
            delta(parts, "每秒傷害", theirs.dps(), mine.dps(), 1, "", false);
            if (Math.abs(theirs.physicalDps() - mine.physicalDps()) >= 0.05 && Math.abs(theirs.dps() - theirs.physicalDps()) >= 0.05) {
                delta(parts, "物理每秒", theirs.physicalDps(), mine.physicalDps(), 1, "", false);
            }
            delta(parts, "每秒攻擊", theirs.aps(), mine.aps(), 2, "", false);
            delta(parts, "暴擊率", theirs.crit(), mine.crit(), 1, "%", false);
        }
        delta(parts, "護甲", theirs.armour(), mine.armour(), 0, "", false);
        delta(parts, "閃避", theirs.evasion(), mine.evasion(), 0, "", false);
        delta(parts, "能量護盾", theirs.energyShield(), mine.energyShield(), 0, "", false);
        delta(parts, "格擋", theirs.block(), mine.block(), 0, "%", false);
        delta(parts, "生命", theirs.life(), mine.life(), 0, "", false);
        delta(parts, "魔力", theirs.mana(), mine.mana(), 0, "", false);
        delta(parts, "火抗", theirs.resistFire(), mine.resistFire(), 0, "%", false);
        delta(parts, "冰抗", theirs.resistCold(), mine.resistCold(), 0, "%", false);
        delta(parts, "雷抗", theirs.resistLightning(), mine.resistLightning(), 0, "%", false);
        delta(parts, "混沌抗", theirs.resistChaos(), mine.resistChaos(), 0, "%", false);
        delta(parts, "力量", theirs.strength(), mine.strength(), 0, "", false);
        delta(parts, "敏捷", theirs.dexterity(), mine.dexterity(), 0, "", false);
        delta(parts, "智慧", theirs.intelligence(), mine.intelligence(), 0, "", false);
        delta(parts, "傷害", theirs.damageIncreased(), mine.damageIncreased(), 0, "%", true);
        if (base == null || !base.isWeapon()) {
            delta(parts, "攻速", theirs.attackSpeed(), mine.attackSpeed(), 0, "%", true);
        }
        delta(parts, "施法速度", theirs.castSpeed(), mine.castSpeed(), 0, "%", true);
        delta(parts, "移動速度", theirs.movementSpeed(), mine.movementSpeed(), 0, "%", true);
        delta(parts, "命中", theirs.accuracy(), mine.accuracy(), 0, "", false);
        delta(parts, "每秒回復", theirs.lifeRegen(), mine.lifeRegen(), 1, "", false);
        delta(parts, "暴擊率", theirs.critIncreased(), mine.critIncreased(), 0, "%", true);
        delta(parts, "攻擊暴擊率", theirs.attackCritIncreased(), mine.attackCritIncreased(), 0, "%", true);
        delta(parts, "法術暴擊率", theirs.spellCritIncreased(), mine.spellCritIncreased(), 0, "%", true);
        delta(parts, "暴擊傷害", theirs.critMulti(), mine.critMulti(), 0, "%", false);
        delta(parts, "攻擊暴擊傷害", theirs.attackCritMulti(), mine.attackCritMulti(), 0, "%", false);
        delta(parts, "法術暴擊傷害", theirs.spellCritMulti(), mine.spellCritMulti(), 0, "%", false);
        final List<String> lines = new ArrayList<>();
        lines.add("<dark_gray>比身上的 <gray>" + escape(equippedName) + "<dark_gray>：" + (parts.isEmpty() ? "差不多" : ""));
        for (int i = 0; i < parts.size() && i < 9; i += 3) {
            lines.add("<dark_gray>  " + String.join(" <dark_gray>", parts.subList(i, Math.min(parts.size(), i + 3))));
        }
        if (parts.size() > 9) {
            lines.add("<dark_gray>  …還有 " + (parts.size() - 9) + " 項");
        }
        return lines;
    }

    /** 跟 ItemRenderer.escape 一樣（這裡不碰 ItemRenderer：它要伺服器的類別）。 */
    private static String escape(final String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("<", "\\<");
    }

    /**
     * 一個差異：有差才加。
     *
     * @param percent 這個數字是小數的百分比（0.3 = 30%）
     */
    private static void delta(final List<String> parts, final String label, final double theirs, final double mine, final int decimals,
                              final String unit, final boolean percent) {
        double difference = theirs - mine;
        if (percent) {
            difference *= 100;
        }
        final double threshold = decimals == 0 ? 0.5 : Math.pow(10, -decimals) / 2;
        if (Math.abs(difference) < threshold) {
            return;
        }
        final String number = decimals == 0 ? String.format(Locale.ROOT, "%,d", Math.round(Math.abs(difference)))
                : String.format(Locale.ROOT, "%." + decimals + "f", Math.abs(difference));
        parts.add((difference > 0 ? "<green>" : "<red>") + label + " " + (difference > 0 ? "+" : "−") + number + unit);
    }
}
