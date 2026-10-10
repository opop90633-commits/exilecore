package tw.exilecore.bounty;

import tw.exilecore.map.MapConfig;
import tw.exilecore.zone.ZoneConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 每週回響的「地圖」（純邏輯）：打倒過的區域 Boss 的強化版，放在地圖世界的一個格子裡（短短的版面、那個區域的怪）。
 */
public final class Echoes {

    private Echoes() {
    }

    /**
     * 回響的怪物等級：那個區域的最高等級與角色等級取高的，最高到 cap（地圖最高階的怪物等級：
     * 跟 PoE 一樣怪物最高 84 級左右，100 級的角色打的回響也是 84 級）。
     */
    public static int level(final int zoneMaxLevel, final int playerLevel, final int cap) {
        return Math.max(zoneMaxLevel, Math.min(cap, playerLevel));
    }

    /**
     * 每日委託、回響跟怪物比等級時用的角色等級：最高到 cap（地圖最高階的怪物等級），
     * 不然 95 級以上的角色打最高階的地圖（84 級）也會被當成「怪物等級太低」。
     */
    public static int comparedLevel(final int playerLevel, final int cap) {
        return Math.min(playerLevel, cap);
    }

    /**
     * 每日委託、每月挑戰「怪物（地圖）等級夠不夠」的下限：比較用的角色等級（{@link #comparedLevel}）減掉 gap，最低 1。
     * 選單與提示直接寫「要 Lv.74 以上才算」給玩家看，所以算在這裡（單元測試）。
     */
    public static int minMonsterLevel(final int playerLevel, final int cap, final int gap) {
        return Math.max(1, comparedLevel(playerLevel, cap) - Math.max(0, gap));
    }

    /**
     * 回響的地圖定義。
     *
     * @param typeLife   區域 Boss 那種生物本身的生命倍率（monsters.yml；地圖 Boss 生出來時會再扣掉）
     * @param typeDamage 同上，傷害倍率
     */
    public static MapConfig.MapDef map(final BountyConfig.Weekly weekly, final ZoneConfig.ZoneDefinition zone, final double typeLife,
                                       final double typeDamage, final int level) {
        final ZoneConfig.BossDefinition boss = zone.boss();
        final Set<String> mods = new LinkedHashSet<>(boss.mods());
        mods.addAll(weekly.mods());
        final List<MapConfig.Phase> phases = new ArrayList<>();
        final BountyConfig.PhaseDef phase = weekly.phase();
        if (phase != null) {
            phases.add(new MapConfig.Phase(phase.at(), phase.name(), phase.message(), summonType(zone), phase.summon(), phase.stats(),
                    phase.speed()));
        }
        // 地圖 Boss 的倍率是「最後的倍率」（生出來時扣掉生物本身的倍率）；區域 Boss 的倍率還要再乘上生物本身的倍率
        final MapConfig.Boss echo = new MapConfig.Boss(weekly.prefix() + boss.name(),
                boss.vanillaType() == null ? "ZOMBIE" : boss.vanillaType(), boss.mythicId(), true,
                boss.lifeMultiplier() * typeLife * weekly.life(), boss.damageMultiplier() * typeDamage * weekly.damage(),
                boss.scale() * weekly.scale(), List.copyOf(mods), boss.intro(), boss.defeat(), phases, boss.skills());
        return new MapConfig.MapDef("echo_" + zone.id(), "回響：" + zone.name(), null, 0, level, weekly.theme(zone.id()), 0, 0,
                List.of(), false, zone.monsters(), echo, weekly.lore(), false);
    }

    /** 半血時叫出來的怪：那個區域的第一種原版怪物（沒有就用 Boss 的種類）。 */
    static String summonType(final ZoneConfig.ZoneDefinition zone) {
        for (final ZoneConfig.SpawnEntry entry : zone.monsters()) {
            if (!entry.isMythic()) {
                return entry.id();
            }
        }
        return zone.boss().vanillaType();
    }
}
