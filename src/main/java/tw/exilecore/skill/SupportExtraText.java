package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SupportExtraKeys;

/** 2026-10-27：照 PoE2 0.5 補上的一般輔助寶石的修飾器說明（{@link ModifierText} 呼叫；鍵見 {@link SupportExtraKeys}）。 */
final class SupportExtraText {

    private SupportExtraText() {
    }

    private static String n(final double value) {
        return ModifierText.number(value);
    }

    /** 這條的說明；不是這裡的鍵回傳 null。 */
    static String describe(final Modifier modifier, final double value, final String percent) {
        final String stat = modifier.stat();
        for (final String[] prefix : new String[][] {{SupportExtraKeys.USING_PREFIX, "放這個技能的那段時間："},
                {SupportExtraKeys.USING_QUARTER_PREFIX, "放這個技能的那段時間，每 0.25 秒攻擊／施法時間："},
                {SupportExtraKeys.CHANNELLING_PREFIX, "引導這個技能時："}}) {
            if (stat.startsWith(prefix[0])) {
                return prefix[1] + ModifierText.describe(new Modifier(stat.substring(prefix[0].length()), modifier.type(), value,
                        modifier.tags(), modifier.source()));
            }
        }
        return switch (stat) {
            case SupportExtraKeys.CRIT_VS_BLINDED -> "對致盲的敵人暴擊率與暴擊傷害加成更多 " + n(value) + "%";
            case SupportExtraKeys.FLAG_CANNOT_BLIND -> "不會致盲";
            case SupportExtraKeys.FLAG_CANNOT_MAIM -> "不會癱瘓";
            case SupportExtraKeys.CONSUME_FREEZE -> "擊中凍結中的敵人時用掉凍結（留下冰緩），這一下更多 " + n(value) + "% 傷害";
            case SupportExtraKeys.CONSUME_FREEZE_UNIQUE -> "用掉凍結時對傳奇怪物與 Boss 再更多 " + n(value) + "%";
            case SupportExtraKeys.CONSUME_BROKEN -> "擊中完全破甲的敵人時用掉破甲，這一下更多 " + n(value) + "% 傷害";
            case SupportExtraKeys.AGGRAVATE_HEAVY_STUNNED -> "擊中重暈中的敵人時加劇牠身上的流血";
            case SupportExtraKeys.PURSUIT -> "最近 2 秒投射物攻擊擊中過敵人時，近戰更多 " + n(value) + "% 傷害";
            case SupportExtraKeys.RETREAT -> "最近 2 秒近戰擊中過敵人時，投射物更多 " + n(value) + "% 傷害";
            case SupportExtraKeys.INEXORABLE -> "每秒增加 " + n(value) + "% 暴擊率（最多 " + n(SupportExtraKeys.INEXORABLE_CAP) + "%），暴擊時歸零";
            case SupportExtraKeys.INEXORABLE_BONUS -> "每秒暴擊傷害加成更少 " + n(value) + "%（最多 " + n(SupportExtraKeys.INEXORABLE_BONUS_CAP) + "%），暴擊時歸零";
            case SupportExtraKeys.STOICISM -> "每秒更多 " + n(value) + "% 傷害（最多 " + n(SupportExtraKeys.STOICISM_CAP) + "%），翻滾或用突進技能時歸零";
            case SupportExtraKeys.DAUNTLESS -> "要靜止 1.5 秒才能用；每靜止 0.25 秒擊中更多 " + n(value) + "%（最多 " + n(SupportExtraKeys.DAUNTLESS_CAP) + "%）";
            case SupportExtraKeys.FLAG_LUCKY_SURROUNDED -> "被包圍時攻擊的擊中傷害幸運（擲兩次取高）";
            case SupportExtraKeys.FLAG_FREE_SURROUNDED -> "被包圍時魔力不夠也能用";
            case SupportExtraKeys.DERANGE -> "放的時候扣等同你智慧的能量護盾；每 100 智慧非異常的持續傷害更多 " + n(value) + "%";
            case SupportExtraKeys.PERFECT_MORE -> "完美時機放開的那一下更多 " + n(value) + "% 傷害";
            case SupportExtraKeys.PERFECT_DEFLECT -> "抓到完美時機時 8 秒內獲得閃避值 " + n(value) + "% 的偏斜值";
            case SupportExtraKeys.PERFECTION -> "抓到完美時機時獲得 " + n(value) + " 層完美（每層更多 5% 傷害，最多 4 層，10 秒）；沒抓到時全部失去";
            case SupportExtraKeys.FRESH_CLIP -> "這個技能過去 8 秒每裝填一顆弩矢，更多 " + n(value) + "% 傷害";
            case SupportExtraKeys.PERFECT_WINDOW_SHORTER -> "完美時機的區間更短 " + n(value) + "%";
            case SupportExtraKeys.REMNANT_RANGE -> "這個技能產生的殘骸能從遠 " + n(value) + "% 的地方撿";
            case SupportExtraKeys.REMNANT_EXTRA -> "產生殘骸時 " + n(value) + "% 機率多產生一個";
            case SupportExtraKeys.REMNANT_EFFECT -> "這個技能產生的殘骸效果增加 " + n(value) + "%";
            case SupportExtraKeys.REMNANT_DELAY -> "這個技能產生的殘骸撿到 " + n(value) + " 秒後才生效";
            case SupportExtraKeys.CHARGED_SHOTS -> "每 3 發一發充能射擊：獲得 " + n(value) + "% 傷害為額外閃電傷害";
            case SupportExtraKeys.ALIGNMENT -> "指示四個方向之一（東南西北）：朝那個方向攻擊時多射 " + n(value) + " 發；對準之後 1 秒指示新的方向";
            case SupportExtraKeys.MINION_PACT_LIFE -> "放的時候附近一隻召喚物失去等同你最大生命 " + n(value) + "% 的生命";
            case SupportExtraKeys.MINION_PACT_MORE -> "有召喚物付出生命時這一下更多 " + n(value) + "% 傷害";
            case SupportExtraKeys.PROFANITY -> "放的時候用掉 5 格內最多 " + n(value) + " 具屍體";
            case SupportExtraKeys.PROFANITY_MORE -> "每用掉一具屍體，混沌更多 " + n(value) + "% 傷害";
            case SupportExtraKeys.AMBROSIA -> "放的時候用掉魔力藥劑最大充能的 " + n(value) + "%";
            case SupportExtraKeys.AMBROSIA_GAIN -> "每用掉一點魔力藥劑充能，獲得 " + n(value) + "% 傷害為額外閃電傷害";
            case SupportExtraKeys.CONCOCT -> "放的時候用掉生命藥劑最大充能的 " + n(value) + "%";
            case SupportExtraKeys.CONCOCT_BLEED -> "每用掉一點生命藥劑充能，流血更多 " + n(value) + "%";
            case SupportExtraKeys.TUMULT -> "放的時候消耗一顆怒星：這一下破甲，等同物理傷害的 " + n(value) + "%";
            case SupportExtraKeys.POTENTIAL -> "放的時候消耗一顆銳星：暴擊率更多 " + n(value) + "%；這個技能不會產生銳星";
            case SupportExtraKeys.RALLY -> "放的時候消耗全部磐星，每顆回復 " + n(value) + "% 最大生命；這個技能不會產生磐星";
            case SupportExtraKeys.RELENTLESS -> "花掉 " + n(value) + " 點怒氣之後，下一次用這個技能時";
            case SupportExtraKeys.RELENTLESS_GAIN -> "獲得 " + n(value) + " 點怒氣";
            case SupportExtraKeys.CATHARSIS -> "要有 " + n(value) + " 層易爆才能用；用的時候引爆隨機層數的易爆";
            case SupportExtraKeys.VOLATILITY_ON_CRIT -> "暴擊時 " + n(value) + "% 機率獲得一層易爆";
            case SupportExtraKeys.VOLATILITY_ON_EMPOWERED -> "被強化時使用獲得 " + n(value) + " 層易爆";
            case SupportExtraKeys.GRATIFICATION -> "使用條件更難 " + n(value) + "%；達成條件用了之後 10 秒內可以不用條件再用一次";
            case SupportExtraKeys.ANCESTRAL_AID -> "格擋 " + n(value) + " 次之後才能用，用的時候先祖強化";
            case SupportExtraKeys.ANCESTRAL_CALL -> "每 " + n(value) + " 秒一次先祖強化（打擊多打 2 個敵人）";
            case SupportExtraKeys.FIST_OF_WAR -> "攻擊先祖強化，最多每 " + n(value) + " 秒一次；先祖強化的效果加倍";
            case SupportExtraKeys.CRESCENDO -> "連段的最後一擊先祖強化（多打 2 個敵人）";
            case SupportExtraKeys.RENDING_APEX -> "連段的最後一擊破甲，等同造成的攻擊傷害的 " + n(value) + "%";
            case SupportExtraKeys.FINAL_STRIKE_MARK -> "這一下是連段的最後一擊";
            case SupportExtraKeys.DAZE_FULL_LIFE -> "擊中滿血的敵人時讓牠眩暈";
            case SupportExtraKeys.DAZE_PHYSICAL_CRIT -> "用物理傷害暴擊時讓敵人眩暈";
            case SupportExtraKeys.EXPOSURE_ON_IGNITE -> "點燃敵人時讓牠火焰曝露 " + n(value) + " 秒";
            case SupportExtraKeys.EXPOSURE_ON_COLD_CRIT -> "冰冷傷害暴擊時讓敵人冰冷曝露 " + n(value) + " 秒";
            case SupportExtraKeys.EXPOSURE_ON_SHOCK -> "感電敵人時讓牠閃電曝露 " + n(value) + " 秒";
            case SupportExtraKeys.IMPALE_ON_HIT -> "擊中時 " + n(value) + "% 機率穿刺（存下物理傷害的 30%，你下一次攻擊擊中牠時加上去）";
            case SupportExtraKeys.FLAG_NO_EXTRACT -> "不會抽出穿刺";
            case SupportExtraKeys.INCISION_BLEED -> "最近每用掉一層切割，流血更多 " + n(value) + "%（最多 " + n(SupportExtraKeys.INCISION_BLEED_CAP) + "%）";
            case SupportExtraKeys.FLAG_OVERLOAD -> "擊中蓄勢待電的敵人時直接電擊";
            case SupportExtraKeys.FLAG_SYZYGY -> "對點燃中而且完全破甲的敵人有重壓擊（蓄勢待暈直接重暈），重暈牠們時永久恫嚇";
            case SupportExtraKeys.FLAG_STUN_CANNOT_REACH -> "暈眩累積到不了 100%（停在差一點）";
            case SupportExtraKeys.FLAG_MALADY -> "流血與中毒的基礎機率互換";
            case SupportExtraKeys.STORMFIRE -> "被這個技能感電的敵人受到點燃時，另外受到點燃傷害 " + n(value) + "% 的閃電傷害";
            case SupportExtraKeys.DRAIN_AILMENTS -> "施加持續傷害時用掉敵人身上一種異常，持續傷害更多 " + n(value) + "%";
            case SupportExtraKeys.COMMISERATE -> "你身上有同一種異常時，施加的異常更多 " + n(value) + "%，並移除你身上的";
            case SupportExtraKeys.FLAG_CHAOTIC_FREEZE -> "對受到持續傷害的敵人，混沌傷害也累積凍結";
            case SupportExtraKeys.SHOCK_NEARBY -> "感電敵人時 " + n(value) + "% 機率讓 2.5 格內的敵人也感電";
            case SupportExtraKeys.FLAG_SHOCK_DRENCHED -> "擊中濕透的敵人一定感電";
            case SupportExtraKeys.FLAG_DRENCHED -> "濕透";
            case SupportExtraKeys.SHOCK_CHANCE_TAKEN -> "被感電的機率" + (value < 0 ? "更少 " : "更多 ") + n(Math.abs(value)) + "%";
            case SupportExtraKeys.FREEZE_BUILDUP_TAKEN -> "受到的凍結累積" + (value < 0 ? "更少 " : "更多 ") + n(Math.abs(value)) + "%";
            case SupportExtraKeys.SHOCK_SIPHON -> "打死感電中的敵人時回復 " + n(value) + "% 最大能量護盾";
            case SupportExtraKeys.TEAR -> "打死流血中的敵人時 " + n(value) + "% 機率擊殺效果發生兩次";
            case SupportExtraKeys.RIP -> "打死流血中的敵人時留下血之殘骸：撿到 5 秒內生命再生速度增加 " + n(value) + "%（最多 100%）";
            case SupportExtraKeys.THRILL -> "撲殺時 8 秒內攻擊獲得 " + n(value) + "% 傷害為額外閃電傷害";
            case SupportExtraKeys.THRILL_SHOCK -> "撲殺的增益另外增加 " + n(value) + "% 感電機率";
            case SupportExtraKeys.PRACTICAL_MAGIC -> "重暈敵人時 8 秒內法術傷害增加 " + n(value) + "%";
            case SupportExtraKeys.VANGUARD -> "暈眩敵人（輕暈、重暈）時每 10 點力量獲得 " + n(value) + " 守護（4 秒）";
            case SupportExtraKeys.VANGUARD_HEAVY -> "重暈時每 10 點力量另外獲得 " + n(value) + " 守護";
            case SupportExtraKeys.WILDFIRE -> "點燃擴散到 " + n(value) + " 格內、待滿 1 秒的敵人";
            case SupportExtraKeys.UNDERMINE -> "擊中癱瘓中的敵人時用掉癱瘓，破掉牠 " + n(value) + "% 的護甲";
            case SupportExtraKeys.OUTMANEUVER -> "對被招架的敵人破甲，等同物理傷害的 " + n(value) + "%";
            case SupportExtraKeys.RIPOSTE -> "擊中被招架的敵人時用掉招架，獲得 " + n(value) + " 顆怒星";
            case SupportExtraKeys.PUNCH_THROUGH -> "擊退敵人時讓牠成為好目標：下一次投射物攻擊擊中牠時，每格擊退距離增加 " + n(value) + "% 傷害";
            case SupportExtraKeys.BEHEAD -> "打擊打死稀有怪時，偷走牠兩個詞綴 " + n(value) + " 秒";
            case SupportExtraKeys.BARBS -> "荊棘反彈之後，下 " + n(value) + " 次擊中再對牠造成兩次你的荊棘傷害";
            case SupportExtraKeys.QUILL_BURST -> "荊棘反彈之後，接下來 " + n(value) + " 次擊中觸發刺爆";
            case SupportExtraKeys.CRACKLING -> "引導時獲得最大魔力 " + n(value) + "% 的閃電荊棘傷害";
            case SupportExtraKeys.MARK_SIPHON -> "被這個印記標記的敵人受到你的物理攻擊傷害，" + n(value) + "% 偷取為生命與魔力";
            case SupportExtraKeys.ACRIMONY -> "受到這個技能持續傷害的敵人生命再生減少 " + n(value) + "%";
            case SupportExtraKeys.REFRACTION -> "插下的戰旗範圍內的敵人，每 1000 點你的護甲獲得 " + n(value) + "% 元素曝露（最多 80%）";
            case SupportExtraKeys.DEADLY_RESOLVE -> "引導時每秒獲得一層必殺決心（最多 " + n(value) + " 層，10 秒）；敵人擊中你時用掉一層，朝牠斬出 3 格 120 度的一刀"
                    + "（照護甲更多傷害，最多更多 200%；最多每 0.3 秒一次）";
            case SupportExtraKeys.ENERGY_BARRIER -> "放這個技能時被暈眩，能量護盾立刻開始回充，" + n(value) + " 秒內不會被傷害打斷";
            case SupportExtraKeys.LIGHT_STUN_THRESHOLD -> "輕暈門檻" + (value < 0 ? "更少 " : "更多 ") + n(Math.abs(value)) + "%";
            case SupportExtraKeys.HEAVY_STUN_THRESHOLD -> "重暈門檻" + (value < 0 ? "更少 " : "更多 ") + n(Math.abs(value)) + "%";
            case SupportExtraKeys.STOMPING -> "用突進技能時每走一步（1.5 格）震出一陣 2 格的震波（武器攻擊傷害的 50%）；跳躍是起跳、落地各一步";
            case SupportExtraKeys.HOLY_DESCENT -> "落地時留下 2.2 格的聖化地面 " + n(value) + " 秒（你與隊友每秒回復 5% 最大生命、詛咒效果減少 50%）";
            case SupportExtraKeys.FLAG_GAMBLESHOT -> "每次射出時隨機：投射物多分裂、多連鎖或多穿透一次";
            case SupportExtraKeys.FLAG_RICOCHET -> "多出來的投射物改成投射物從地形連鎖的次數";
            case SupportExtraKeys.WILDSHARDS -> n(value) + "% 機率多射 8 發，繞一圈射出";
            case SupportExtraKeys.FLAG_SPECTRAL -> "每次攻擊存一發光影投射物（最多 5 發），3 秒沒有再存時全部射出去；一般的那一下更少 60% 傷害";
            case SupportExtraKeys.SPECTRAL_MORE -> "光影投射物更多 " + n(value) + "% 傷害";
            case SupportExtraKeys.CASCADE -> "往目標區域兩側各連瀑 " + n(value) + " 次";
            case SupportExtraKeys.CASCADE_AREA -> "連瀑的範圍更少 " + n(value) + "%";
            case SupportExtraKeys.FLAG_ASTRAL -> "改在準心指的地方放，不是在你身邊";
            case SupportExtraKeys.FISSURE_BRANCHES -> "裂地分岔出 " + n(value) + " 道";
            case SupportExtraKeys.FISSURE_BRANCH_DAMAGE -> "分岔的裂地更少 " + n(value) + "% 傷害";
            case SupportExtraKeys.FISSURE_BRANCH_AREA -> "分岔的裂地更少 " + n(value) + "% 範圍";
            case SupportExtraKeys.FISSURE_EXTRA -> "多裂開 " + n(value) + " 道";
            case SupportExtraKeys.ENCROACH -> "地面效果每秒範圍增加 " + n(value) + "%";
            case SupportExtraKeys.ENCROACH_MAX_KEY -> "地面效果範圍最多增加 " + n(value) + "%";
            case SupportExtraKeys.RIME -> "站在這個技能的冰緩地面上的敵人被擊中時，凍結累積像多 " + n(value) + "% 傷害";
            case SupportExtraKeys.CURSED_GROUND -> "詛咒改成留下一塊詛咒地面：站在裡面的敵人才被詛咒（最多 " + n(value) + " 塊）";
            case SupportExtraKeys.HEX_BLOOM -> "被你詛咒的敵人死掉時，詛咒傳給 " + n(value) + " 格內的敵人";
            case SupportExtraKeys.FLAG_MINION_SPLASH -> "召喚物的近戰打中時，目標周圍 2 格的敵人受到這一下 60% 的傷害";
            case SupportExtraKeys.LAST_GASP -> "召喚物生命歸零後再撐 " + n(value) + " 秒才死";
            case SupportExtraKeys.CRAZED -> "召喚物最近復活過時更多 " + n(value) + "% 傷害";
            case SupportExtraKeys.FLAG_COMMANDMENT -> "召喚物用指令以外的技能不造成傷害；牠們的指令冷卻回復速度增加 100%";
            case SupportExtraKeys.LOYALTY -> "你受到的擊中傷害有 " + n(value) + "% 先由夥伴承受";
            case "corpse.save_chance" -> "用掉屍體時有 " + n(value) + "% 機率不毀掉屍體";
            case SupportExtraKeys.FONT_BLOOD -> "圖騰四周 4 格是血之泉：站在裡面偷取圖騰物理傷害的 " + n(value) + "% 為生命";
            case SupportExtraKeys.FONT_MANA -> "圖騰四周 4 格是魔之泉：站在裡面偷取圖騰物理傷害的 " + n(value) + "% 為魔力";
            case "cut_wound.chance" -> "擊中時有 " + n(value) + "% 機率給一層切割（每層 10% 流血機率）";
            case "totem.resist_max" -> "圖騰最大元素抗性 +" + n(value) + "%";
            case "flag.aggravate_bleed_pinned" -> "定身敵人或擊中被定身的敵人時加劇牠身上的流血";
            case "cooldown.recovery" -> "冷卻回復速度" + (modifier.type() == tw.exilecore.stats.ModType.MORE
                    ? (value < 0 ? "更少 " : "更多 ") : (value < 0 ? "減少 " : "增加 "))
                    + (modifier.type() == tw.exilecore.stats.ModType.FLAT ? n(Math.abs(value)) : percent) + "%"; // 2026-10-28：增加／更多的值是比例（0.1 = 10%）；這次之前直接寫 0.1%
            case SupportExtraKeys.GORGE -> "用掉屍體時給 " + n(value) + " 點生命與魔力藥劑充能";
            case SupportExtraKeys.CULMINATION -> "你用其他近戰攻擊擊中敵人時，這個技能獲得連擊；用的時候用掉全部連擊，每用掉 1 層更多 " + n(value) + "% 傷害"; // 2026-10-28
            case SupportExtraKeys.CULMINATION_DECAY -> n(value) + " 秒沒有獲得連擊就失去全部連擊";
            case SupportExtraKeys.CULMINATION_MAX -> "最多 " + n(value) + " 層連擊";
            case SupportExtraKeys.SECOND_WIND -> "冷卻次數加倍；用了進入冷卻時，每秒冷卻回復 " + n(value) + "% 最大生命";
            case SupportExtraKeys.SPLINTER -> "圖騰用一次技能後分裂成兩根更小的圖騰，最多分裂 " + n(value) + " 次（同時只有一根在分裂）";
            case SupportExtraKeys.SPLINTER_DAMAGE -> "圖騰每分裂一次更少 " + n(value) + "% 傷害";
            case SupportExtraKeys.SPLINTER_LIFE -> "圖騰每分裂一次更少 " + n(value) + "% 最大生命";
            case SupportExtraKeys.SPLINTER_AREA -> "圖騰每分裂一次更少 " + n(value) + "% 範圍";
            case SupportExtraKeys.SPLINTER_SPEED -> "圖騰每分裂一次更少 " + n(value) + "% 技能速度";
            case SupportExtraKeys.TOTEM_ONLY_ONE -> "同時只能有一根圖騰";
            case SupportExtraKeys.REARM -> "危險物件（冰晶、鐵蒺、矛陣的矛）觸發後 " + n(value) + "% 機率過 1 秒在原地重新布置";
            case SupportExtraKeys.DELAYED_REACTION -> "危險物件（冰晶、鐵蒺、矛陣的矛）持續時間更少 70%，敵人碰到不會觸發，只在放下的那一刻（那時就有敵人）或時間到時觸發，更多 "
                    + n(value) + "% 傷害";
            case SupportExtraKeys.FLAG_FORTRESS -> "牆改成在準心指的地方圍成一圈（長度加倍）";
            case SupportExtraKeys.DIRESTRIKE -> "這個增益開著時，你在低血時攻擊傷害增加 " + n(value) + "%";
            case SupportExtraKeys.POISE_STUN -> "擊中的暈眩像多造成「你重暈條百分比 × " + n(value / 100) + "」的傷害";
            case SupportExtraKeys.POISE_DAMAGE -> "攻擊傷害照你的重暈條最多更多 " + n(value) + "%";
            case SupportExtraKeys.DURABILITY -> "留下的物件多用幾次才消失"; // 2026-10-30 照 PoE2
            case SupportExtraKeys.SLOW_POTENCY -> "施加的緩速效果更強";
            default -> null;
        };
    }
}
