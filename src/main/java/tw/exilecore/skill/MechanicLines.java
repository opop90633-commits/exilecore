package tw.exilecore.skill;

import java.util.Locale;

/**
 * 2026-11-04 照站長「這種特殊效果能寫的更明確一點嗎」：寫不出一般說明的機制鍵（怪物詞綴的機制、輔助的旗標）在這裡寫成一句話，
 * 技能說明、圖鑑、網頁都用；這次之前顯示成「特殊效果（1）」。數字照 monsters.yml、supports.yml、HitEffects／MonsterMechanics 的實作。
 */
final class MechanicLines {

    private MechanicLines() {
    }

    /** 寫不出來回傳 null。value 是修飾器的原始數字（INCREASED 是小數）。 */
    static String describe(final String stat, final double value) {
        final long n = Math.round(value);
        final String pct = ModifierText.number(Math.abs(value) * 100);
        return switch (stat) {
            // ---- 輔助 ----
            case "flag.blood_magic" -> "技能的消耗改扣生命，不扣魔力";
            case "attack.speed.cadence" -> "這個技能 4 秒內每用過一次，攻擊速度更多 " + n + "%（4 秒內用了 6 次以上：10 秒內傷害更少 50%）";
            case "blind.chance" -> (n >= 100 ? "擊中時致盲敵人" : "擊中時 " + n + "% 機率致盲敵人") + "（4 秒內牠的命中與閃避更少 20%）";
            case "blind.effect" -> "致盲的效果" + (value < 0 ? "減少 " : "增加 ") + pct + "%";
            case "flag.projectile_speed_to_damage" -> "投射物速度的增加與減少也套用在投射物傷害上";
            case "grenade.twice_chance" -> "丟出的榴彈 " + n + "% 機率再爆一次";
            case "resist.invert_chance" -> "擊中時 " + n + "% 機率把敵人的元素抗性反轉（正的變負的）";
            case "slam.aftershock_chance" -> "猛擊砸下時 " + n + "% 機率 0.5 秒後在同樣的範圍再打一次（餘震，傷害跟原本一樣）";
            case "jagged.aftershock_seconds" -> "餘震落下時在落點留下 " + ModifierText.number(value) + " 秒的碎石地（裡面的敵人減速 20%）";
            case "mana.recoup" -> "受到的傷害有 " + n + "% 之後回復為魔力"; // 前綴（using_qs.）由 SupportExtraText 加
            // ---- 怪物詞綴 ----
            case "corrupted_blood.chance" -> "打中你 " + n + "% 機率上一層腐化之血（物理持續傷害，每層每秒那一下的 5%，最多 10 層）";
            case "maim.chance" -> "打中你 " + n + "% 機率癱瘓（4 秒內移動速度慢 30%）";
            case "slow.taken" -> "身上的減速效果（冰緩、詛咒、癱瘓…）" + (value < 0 ? "減少 " : "增加 ") + pct + "%";
            case "flag.cc_immune" -> "免疫所有控場（暈眩、凍結、冰緩、電擊、釘地、癱瘓、阻礙、致盲、緩速、嘲諷、擊退）";
            case "monster.corpse_explode" -> "引爆你附近的屍體（1 秒後炸，物理傷害）";
            case "monster.cullable" -> "打牠的擊中都有撲殺（生命 10% 以下直接倒）";
            case "monster.death_blast_fire" -> "死掉時地上先畫圈，1 秒後炸開（3 格、牠普通一下 3.8 倍的火焰傷害）";
            case "monster.death_blast_cold" -> "死掉時地上先畫圈，1 秒後炸開（3 格、牠普通一下 3.8 倍的冰冷傷害）";
            case "monster.death_blast_lightning" -> "死掉時地上先畫圈，1 秒後炸開（3 格、牠普通一下 3.8 倍的閃電傷害）";
            case "monster.death_ground_fire" -> "每 5 秒放一圈火焰新星、留下點燃的地面（2.5 格、6 秒），死掉時也一次";
            case "monster.death_ground_cold" -> "每 5 秒放一圈冰冷新星、留下冰緩的地面（2.5 格、6 秒），死掉時也一次";
            case "monster.death_ground_lightning" -> "每 5 秒放一圈閃電新星、留下感電的地面（2.5 格、6 秒，受到的傷害增加 30%），死掉時也一次";
            case "monster.flask_siphon" -> "12 格內的玩家每 20 秒每瓶藥劑少 1 點充能";
            case "monster.gigantic" -> "體型 1.2 倍";
            case "monster.heal_allies" -> "每 6 秒治療 10 格內的怪物 " + n + "% 生命";
            case "monster.hinder_aura" -> ModifierText.number(value) + " 格內的玩家一直被阻礙（移動速度慢 30%）";
            case "monster.invulnerability_aura" -> "每 12 秒一圈：" + n + " 格內的友軍（不含自己）5 秒內不會受傷（牠死了就沒了）";
            case "monster.lightning_mirage" -> "被打中 25% 機率放出追人的閃電幻影（碰到就炸）";
            case "monster.magma_barrier" -> "熔岩護盾：先吸收 " + n + "% 生命的傷害，打破時爆炸（火焰），8 秒後回來";
            case "monster.mana_siphon" -> "3～7 格那一圈：吸魔力、不能回魔、閃電傷害（貼近牠反而安全）";
            case "monster.minion_shield" -> "受到的傷害 " + n + "% 先由 10 格內的小怪（普通、魔法怪）扛";
            case "monster.periodic_enrage" -> "每 10 秒狂暴 5 秒（傷害增加 30%、技能與移動速度 +25%、受到的傷害少 33%）";
            case "monster.powerful_minions" -> "叫出來的小怪傷害增加 25%、生命更多 50%";
            case "monster.proximal" -> "只有 " + n + " 格內的攻擊打得到牠";
            case "monster.raise_after_death" -> "死掉 2 秒後原地爬起來一次（爬起來的不掉東西、不給經驗）";
            case "monster.recovery_cap" -> "12 格內的玩家生命回復不能超過 " + n + "%";
            case "monster.regen_percent" -> "每秒回復 " + ModifierText.number(value) + "% 最大生命";
            case "monster.revive_minions" -> "每 8 秒讓附近最多兩具屍體爬起來";
            case "monster.revive_empowered" -> "爬起來的更強（傷害增加 25%、生命更多 50%）";
            case "monster.shield_percent" -> "多一層等於 " + n + "% 生命的護盾（先扣護盾；4 秒沒被打開始回充）";
            case "monster.soul_eater" -> "15 格內的怪物死掉時吞魂：每層招式快 1%、受到的傷害少 1%（最多 50 層）";
            case "monster.temporal_bubble" -> ModifierText.number(value) + " 格的時間泡泡：裡面動作慢 25%、冷卻回復慢 60%、身上的減益消退慢 40%";
            case "monster.trail_fire" -> "走過留下點燃的地面 3 秒";
            case "monster.trail_cold" -> "走過留下冰緩的地面 4 秒";
            case "monster.trail_lightning" -> "走過留下感電的地面 4 秒";
            case "monster.volatile_crag" -> "每 10 秒在你腳邊落一塊爆岩" + (value >= 150 ? "（強化：一次兩塊）" : "") + "，有人走近就炸（火焰）";
            case "monster.volatile_crystals" -> "被打中時長出晶體（最多 6 顆），牠死掉時晶體全部炸開（混沌）";
            case "monster.volatile_plants" -> "每 8 秒在你附近長一朵爆花" + (value >= 200 ? "（強化：一次兩朵）" : "") + "，放出追人的光球，靠近就炸（混沌，"
                    + String.format(Locale.ROOT, "%.1f", value / 100) + " 倍）";
            default -> null;
        };
    }
}
