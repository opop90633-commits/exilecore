#!/usr/bin/env python3
"""2026-10-27：站長「PoE2 0.5 還沒有的一般輔助寶石（不含血脈）全部補上」。
來源：poe2db.tw/us/Support_Gems 與各寶石頁、RePoE poe2（data/skill_gems.json 的 crafting_level、color、is_lineage；data/skills.json 的 stat、cost_multiplier）。
有 I／II／III 的照站長「只留最高階」用最高那一階的數值。階級換算跟 apply_poe2_supports.py 一樣：PoE2 第 1 階 → 1、第 2–3 階 → 2、第 4–5 階 → 3；
第 0 階（不從未切割寶石切、0.5 的特殊來源）一律放第 2 階。
做法：
  - 照 SPEC 在 supports.yml 最後面（ADDED_BEGIN／ADDED_END 兩行註解中間）寫輔助，重跑會整段換掉；
  - 照 SPEC、SKIPPED、REMOVED 寫 tools/poe2_supports/missing_0.5.tsv（全部缺的一般輔助與狀態：exact／approx／skipped／removed）；
  - 2026-10-27 照站長刪掉的（REMOVED）不再產生，supports.yml 的 retired 把玩家身上的換成同階未切割輔助寶石；
  - 印出各顏色的代號（貼進 SupportColours.java）。
機制在 tw.exilecore.skill.SupportExtras（鍵見 tw.exilecore.stats.SupportExtraKeys），防呆在 SupportGuards。
status：exact＝照原作做；approx＝原作的機制在 Minecraft 做不出一模一樣，做成平衡等效（說明寫在 note 與 supports.yml 的註解）。"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
F = ROOT / 'src/main/resources/data/supports.yml'
TSV = ROOT / 'tools/poe2_supports/missing_0.5.tsv'
BEGIN = '  # ---- ADDED_BEGIN 2026-10-27：照 PoE2 0.5 補上的一般輔助寶石（tools/apply_poe2_missing_supports.py 產生，不要手改；名稱是自己的）----\n'
END = '  # ---- ADDED_END ----\n'


def m(stat, typ, value, tags=None):
    t = ', tags: [%s]' % ', '.join(tags) if tags else ''
    return '{ stat: %s, type: %s, value: %s%s }' % (stat, typ, value, t)


def tier(p):
    return 2 if p == 0 else (1 if p == 1 else 2 if p <= 3 else 3)


# 代號: (PoE2 名稱（最高階）, PoE2 階級, 顏色, 魔耗倍率, 名稱, 圖示, requires, excludes, modifiers, 說明, status, note)
SPEC = {
    'acrimony': ('Acrimony', 2, 'b', 1.1, '怨蝕', 'FERMENTED_SPIDER_EYE', [], ['minion'],
                 [m('dot.enemy_regen', 'FLAT', 50)],
                 '受到被輔助的技能持續傷害（地面效果、混沌減益）的敵人，生命再生減少 50%。不能輔助召喚物的技能。', 'exact', ''),
    'alignment_iii': ('Alignment III', 5, 'g', 1.2, '正向 III', 'FEATHER', ['attack'], ['channelling'],
                      [m('alignment.projectiles', 'FLAT', 4)],
                      '被輔助的弓攻擊指示四個方向之一（東南西北）：朝那個方向攻擊時多射 4 發；對準之後過 1 秒指示新的方向。', 'exact',
                      '2026-10-27 改成原作：對準之後 1 秒才指示新的方向（瞄準延遲）；原作鏡頭固定，畫面的上下左右就是世界上固定的四個方向，用東南西北一樣'),
    'ambrosia_ii': ('Ambrosia II', 3, 'b', 1.0, '瓊漿 II', 'GLOW_INK_SAC', ['attack', 'spell'], ['persistent', 'aura'],
                    [m('flask.consume_mana', 'FLAT', 35), m('flask.consume_mana_gain', 'FLAT', 1)],
                    '被輔助的技能放的時候用掉魔力藥劑最大充能的 35%，每用掉一點，這一下獲得 1% 傷害為額外閃電傷害。', 'exact', ''),
    'ancestral_aid': ('Ancestral Aid', 0, 'r', 1.0, '祖援', 'SHIELD_PLACEHOLDER', ['strike'], ['channelling'],
                      [m('ancestral.aid_blocks', 'FLAT', 3)],
                      '被輔助的打擊要先格擋 3 次擊中才能用，用的時候先祖強化（多打 2 個敵人）；用完重新計算格擋。', 'exact', ''),
    'ancestral_call_iii': ('Ancestral Call III', 0, 'r', 1.2, '祖喚 III', 'BONE', ['strike'], ['channelling'],
                           [m('ancestral.call_seconds', 'FLAT', 1), m('damage', 'MORE', -30)],
                           '被輔助的打擊每 1 秒一次先祖強化（多打 2 個敵人），但傷害更少 30%。', 'exact', ''),
    'astral_projection': ('Astral Projection', 3, 'b', 1.0, '星投', 'ENDER_PEARL_PLACEHOLDER', [], [],
                          [m('nova.at_target', 'FLAT', 1), m('skill.area', 'MORE', -25)],
                          '被輔助的新星技能改在準心指的地方放（不是在你身邊），但範圍更少 25%。', 'exact', ''),
    'barbs_iii': ('Barbs III', 5, 'r', 1.2, '倒鉤 III', 'IRON_NUGGET', ['strike'], [],
                  [m('thorns.barbs_hits', 'FLAT', 1)],
                  '你用荊棘反彈之後，被輔助的打擊下一次擊中的敵人再受到兩次你的荊棘傷害。', 'exact', ''),
    'behead_ii': ('Behead II', 3, 'r', 1.0, '斬首 II', 'IRON_INGOT', ['strike'], [],
                  [m('kill.behead_seconds', 'FLAT', 20)],
                  '被輔助的打擊打死稀有怪時，偷走牠兩個詞綴 20 秒。', 'exact', ''),
    'biting_frost_ii': ('Biting Frost II', 5, 'b', 1.2, '噬寒 II', 'PRISMARINE_CRYSTALS', ['attack', 'spell'], [],
                        [m('damage.consume_freeze', 'FLAT', 40), m('damage.consume_freeze_unique', 'FLAT', 20), m('freeze.buildup', 'MORE', -100)],
                        '被輔助的技能擊中凍結中的敵人時用掉凍結（改成冰緩），這一下更多 40% 傷害（傳奇再更多 20%）；但不會累積凍結。', 'exact', ''),
    'blindside': ('Blindside', 1, 'g', 1.2, '暗襲', 'INK_SAC_PLACEHOLDER', ['attack', 'spell'], [],
                  [m('crit.vs_blinded', 'FLAT', 15), m('flag.cannot_blind', 'FLAT', 1)],
                  '被輔助的技能對致盲的敵人暴擊率與暴擊傷害加成各更多 15%，但自己不會致盲。', 'exact', ''),
    'branching_fissures_ii': ('Branching Fissures II', 5, 'r', 1.2, '岔裂 II', 'FLINT', [], [],
                              [m('fissure.branches', 'FLAT', 2), m('fissure.branch_damage', 'FLAT', 30), m('fissure.branch_area', 'FLAT', 35)],
                              '被輔助的裂地技能的裂縫上岔出 2 道分岔裂縫（從主裂縫中段往兩旁岔出去），分岔的傷害更少 30%、範圍更少 35%。', 'exact',
                              '2026-10-27 改成原作：分岔從主裂縫上岔出去（這次之前從起點往兩旁各裂一道）'),
    'brink_ii': ('Brink II', 3, 'r', 1.0, '臨界 II', 'QUARTZ', ['melee'], [],
                 [m('flag.stun_cannot_reach', 'FLAT', 1), m('stun.buildup', 'MORE', 50)],
                 '被輔助的近戰技能暈眩累積更多 50%，但累積到不了 100%（停在差一點，留給別的技能收尾）。', 'exact', ''),
    'catharsis': ('Catharsis', 2, 'b', 1.0, '淨化', 'AMETHYST_SHARD', [], ['persistent', 'aura'],
                  [m('volatility.required', 'FLAT', 20)],
                  '身上要有 20 層易爆才能用被輔助的技能；用的時候引爆隨機層數的易爆（爆炸後獲得易爆之力）。', 'exact', ''),
    'chaotic_freeze': ('Chaotic Freeze', 3, 'b', 1.0, '混凍', 'PRISMARINE_SHARD', ['spell'], [],
                       [m('flag.chaotic_freeze', 'FLAT', 1)],
                       '對受到持續傷害（混沌減益、中毒）的敵人，被輔助的混沌法術的混沌傷害也累積凍結。', 'exact', ''),
    'charged_shots_ii': ('Charged Shots II', 5, 'g', 1.2, '充射 II', 'COPPER_INGOT', ['attack'], [],
                         [m('charged_shots.gain', 'FLAT', 60)],
                         '被輔助的弓攻擊每 3 發一發充能射擊：獲得 60% 傷害為額外閃電傷害。', 'exact', ''),
    'cold_exposure': ('Cold Exposure', 4, 'b', 1.2, '霜曝', 'PRISMARINE_CRYSTALS', ['attack', 'spell'], [],
                      [m('exposure.on_cold_crit', 'FLAT', 8)],
                      '被輔助的技能用冰冷傷害暴擊時，讓敵人冰冷曝露 8 秒（冰冷抗性 −20%）。', 'exact', ''),
    'fire_exposure': ('Fire Exposure', 4, 'r', 1.2, '焰曝', 'MAGMA_CREAM', ['attack', 'spell'], [],
                      [m('exposure.on_ignite', 'FLAT', 8)],
                      '被輔助的技能點燃敵人時，讓牠火焰曝露 8 秒（火焰抗性 −20%）。', 'exact', ''),
    'lightning_exposure': ('Lightning Exposure', 4, 'g', 1.2, '雷曝', 'GLOWSTONE_DUST', ['attack', 'spell'], [],
                           [m('exposure.on_shock', 'FLAT', 8)],
                           '被輔助的技能讓敵人感電時，讓牠閃電曝露 8 秒（閃電抗性 −20%）。', 'exact', ''),
    'commandment': ('Commandment', 0, 'b', 1.0, '號令', 'GOLD_INGOT', ['minion'], [],
                    [m('minion.commandment', 'FLAT', 1)],
                    '被輔助的召喚物用指令以外的技能（普攻、自己的招式）不造成傷害；牠們的指令冷卻回復速度增加 100%。只能掛在有指令的召喚物上。', 'exact',
                    '2026-10-27 改成原作：召喚物自己的指令冷卻（這次之前給你的指令技能、召喚物所有傷害更少 100%）'),
    'commiserate': ('Commiserate', 2, 'g', 1.0, '同病', 'FERMENTED_SPIDER_EYE', ['attack', 'spell'], [],
                    [m('ailment.commiserate', 'FLAT', 40)],
                    '你身上有同一種異常時，被輔助的技能施加的那種異常更多 40%，並移除你身上的。', 'exact', ''),
    'concoct_ii': ('Concoct II', 4, 'r', 1.0, '調劑 II', 'GLISTERING_MELON_SLICE', ['attack', 'spell'], ['persistent', 'aura'],
                   [m('flask.consume_life', 'FLAT', 30), m('flask.consume_life_bleed', 'FLAT', 3)],
                   '被輔助的技能放的時候用掉生命藥劑最大充能的 30%，每用掉一點，這一下的流血更多 3%。', 'exact', ''),
    'concussive_spells': ('Concussive Spells', 4, 'b', 1.0, '震法', 'QUARTZ', ['spell'], [],
                          [m('daze.on_physical_crit', 'FLAT', 1)],
                          '被輔助的法術用物理傷害暴擊時讓敵人眩暈（受到的暈眩累積更多 50%，8 秒）。', 'exact', ''),
    'corpse_conservation': ('Corpse Conservation', 3, 'b', 1.0, '惜屍', 'BONE', ['corpse'], [],
                            [m('corpse.save_chance', 'FLAT', 25)],
                            '被輔助的技能用掉屍體時有 25% 機率不毀掉屍體。', 'exact', ''),
    'crackling_barrier': ('Crackling Barrier', 2, 'b', 1.0, '電屏', 'COPPER_INGOT', [], ['minion', 'totem'],
                          [m('thorns.mana_lightning', 'FLAT', 15)],
                          '引導被輔助的技能時，你獲得最大魔力 15% 的閃電荊棘傷害（被近戰擊中時反彈）。', 'exact', ''),
    'crazed_minions': ('Crazed Minions', 4, 'b', 1.15, '狂僕', 'ROTTEN_FLESH_PLACEHOLDER', ['minion'], [],
                       [m('minion.crazed_more', 'FLAT', 30)],
                       '被輔助的常駐召喚物最近（4 秒內）復活過時更多 30% 傷害。', 'exact',
                       '2026-10-27 改成原作：召喚物自己的屬性表更多傷害（這次之前每一下另外打 30%）'),
    'crescendo_iii': ('Crescendo III', 5, 'b', 1.2, '漸強 III', 'NOTE_BLOCK_PLACEHOLDER', ['strike'], [],
                      [m('ancestral.final_strike', 'FLAT', 1)],
                      '被輔助的打擊連段的最後一擊先祖強化（多打 2 個敵人）：快速連用時寒冰震波、原獸連擊第 3 下，風暴連擊、撕咬第 4 下。', 'exact',
                      '2026-10-27 改成原作：補上打擊連段的最後一擊（快速連用、2 秒內、中間沒用別的技能；這次之前每 3 下一次）'),
    'cursed_ground': ('Cursed Ground', 3, 'b', 1.0, '咒地', 'SOUL_SAND_PLACEHOLDER', [], [],
                      [m('curse.ground_zones', 'FLAT', 2)],
                      '被輔助的詛咒落下時改成留下一塊詛咒地面（詛咒的範圍與持續時間，最多 2 塊）：站在裡面的敵人才被詛咒，出來就沒了。', 'exact',
                      '2026-10-27 改成原作：詛咒改成地面、站在裡面才被詛咒（這次之前另外留一塊每秒在中心重放一次詛咒的地面）'),
    'dauntless': ('Dauntless', 0, 'r', 1.1, '無畏', 'IRON_INGOT', ['attack', 'spell'], ['persistent', 'aura'],
                  [m('damage.dauntless', 'FLAT', 3)],
                  '要先靜止 1.5 秒才能用被輔助的技能；每靜止 0.25 秒擊中更多 3%（最多 45%），一移動就重來。', 'exact', ''),
    'deadly_resolve': ('Deadly Resolve', 3, 'r', 1.0, '決殺', 'IRON_INGOT', [], [],
                       [m('channel.deadly_resolve', 'FLAT', 3)],
                       '引導被輔助的技能時每 1 秒獲得一層必殺決心（最多 3 層，每層 10 秒）；敵人擊中你時用掉一層，朝牠斬出 3 格、120 度的一刀（不用武器的攻擊傷害；每 5～108 點護甲更多 1%（照技能等級），最多更多 200%），最多每 0.3 秒一次。', 'exact',
                       '2026-10-27 改成原作：斬擊照 TriggeredDeadlyResolvePlayer（3 格 120 度、逐級傷害與護甲換算；這次之前每 200 點護甲更多 1%、最多 100%，身邊一圈）'),
    'defy_ii': ('Defy II', 5, 'r', 1.0, '抗命 II', 'NETHERITE_SCRAP', ['attack'], ['persistent', 'aura'],
                [m('flag.lucky_when_surrounded', 'FLAT', 1), m('flag.mana_free_when_surrounded', 'FLAT', 1)],
                '被包圍時，被輔助的攻擊擊中傷害幸運（擲兩次取高），魔力不夠也能用。', 'exact', ''),
    'delayed_gratification': ('Delayed Gratification', 2, 'g', 1.0, '延樂', 'CLOCK', [], [],
                              [m('conditional.harder', 'FLAT', 150)],
                              '被輔助的技能的使用條件（要的榮耀、連擊）更難 150%；達成條件用了之後，10 秒內可以不用條件再用一次。', 'exact',
                              '2026-10-27 改成原作：要連擊的（驚雷之鐘）也算（這次之前只有要榮耀的）'),
    'delayed_reaction': ('Delayed Reaction', 4, 'g', 1.0, '遲爆', 'GUNPOWDER', ['hazard'], [],
                         [m('hazard.delayed', 'FLAT', 15)],
                         '被輔助的危險物件（寒凝彈的冰晶、蒺藜步的鐵蒺、矛陣的矛）持續時間更少 70%，敵人碰到不會觸發，只在放下的那一刻（那時就有敵人）或時間到時觸發，更多 15% 傷害。', 'exact',
                         '2026-10-27 改成原作：矛陣改成照 PoE2 Spearfield 插在地上、敵人碰到才炸的矛，跟冰晶、鐵蒺同一套（這次之前矛陣是持續打的範圍，做成持續時間更少 70%、每一下更多 283%）'),
    'derange': ('Derange', 4, 'b', 1.0, '狂亂', 'ENDER_EYE_PLACEHOLDER', [], ['channelling', 'persistent', 'aura'],
                [m('damage.dot_per_100_int', 'FLAT', 8)],
                '被輔助的技能放的時候扣等同你智慧的能量護盾；每 100 智慧，非異常的持續傷害（地面效果、混沌減益）更多 8%。', 'exact', ''),
    'desperation': ('Desperation', 0, 'r', 1.0, '絕境', 'REDSTONE_PLACEHOLDER', ['melee'], ['persistent', 'aura'],
                    [m('flag.mana_free_when_surrounded', 'FLAT', 1)],
                    '被包圍時，被輔助的近戰攻擊魔力不夠也能用。', 'exact', ''),
    'direstrike_ii': ('Direstrike II', 4, 'r', None, '絕擊 II', 'REDSTONE_PLACEHOLDER', ['persistent'], [],
                      [m('support.spirit', 'FLAT', 20), m('persistent.direstrike', 'FLAT', 70)],
                      '被輔助的常駐增益開著時，你在低血（35% 以下）時攻擊傷害增加 70%。多保留 20 精魂。', 'exact', ''),
    'drain_ailments': ('Drain Ailments', 3, 'b', 1.0, '汲蝕', 'FERMENTED_SPIDER_EYE', ['spell'], [],
                       [m('damage.dot_drain_ailment', 'FLAT', 35)],
                       '被輔助的法術施加持續傷害時用掉敵人身上隨機一種異常，這次的持續傷害更多 35%。', 'exact', ''),
    'durability': ('Durability', 2, 'g', 1.0, '堅物', 'PRISMARINE_SHARD', [], [],
                   [m('objects.durability', 'FLAT', 1), m('crystal.hits', 'FLAT', 2)],
                   '被輔助的技能留下的冰晶要多打 2 下才碎。', 'exact', '我們會被打碎的物件是冰晶'),
    'encroaching_ground': ('Encroaching Ground', 2, 'b', 1.1, '蔓地', 'SLIME_BALL', [], [],
                           [m('ground.encroach_per_second', 'FLAT', 20), m('ground.encroach_max', 'FLAT', 100)],
                           '被輔助的技能留下的地面效果每秒範圍增加 20%（最多增加 100%）。', 'exact', ''),
    'energy_barrier': ('Energy Barrier', 0, 'b', 1.0, '能障', 'PRISMARINE_CRYSTALS', [], ['persistent', 'aura'],
                       [m('es.recharge_on_stunned', 'FLAT', 2)],
                       '放被輔助的技能的那段時間被暈眩（輕暈、重暈）時，能量護盾立刻開始回充，而且 2 秒內不會被傷害打斷。', 'exact',
                       '2026-10-27 改成原作：放的時候被暈眩才觸發（這次之前放的那段時間回充都不等待）'),
    'exploit_weakness': ('Exploit Weakness', 2, 'r', 1.3, '乘虛', 'IRON_NUGGET', ['attack', 'spell'], [],
                         [m('damage.consume_broken_armour', 'FLAT', 40), m('armour.break', 'MORE', -100)],
                         '被輔助的技能擊中完全破甲的敵人時用掉破甲（護甲恢復），這一下更多 40% 傷害；但自己不會破甲。', 'exact', ''),
    'fist_of_war_iii': ('Fist of War III', 5, 'r', 2.0, '戰拳 III', 'IRON_INGOT', ['slam'], ['persistent', 'aura'],
                        [m('ancestral.fist_seconds', 'FLAT', 14)],
                        '被輔助的猛擊先祖強化（最多每 14 秒一次），而且先祖強化的效果加倍。', 'exact', ''),
    'font_of_blood': ('Font of Blood', 2, 'r', 1.0, '血泉', 'GLISTERING_MELON_SLICE', ['totem'], [],
                      [m('totem.font_blood', 'FLAT', 12)],
                      '被輔助的圖騰四周 4 格是血之泉：站在裡面的你與隊友偷取圖騰物理傷害的 12% 為生命。', 'exact', ''),
    'font_of_mana': ('Font of Mana', 2, 'b', 1.0, '魔泉', 'LAPIS_LAZULI', ['totem'], [],
                     [m('totem.font_mana', 'FLAT', 12)],
                     '被輔助的圖騰四周 4 格是魔之泉：站在裡面的你與隊友偷取圖騰物理傷害的 12% 為魔力。', 'exact', ''),
    'fortress_ii': ('Fortress II', 3, 'b', 1.0, '環壁 II', 'BRICK', [], [],
                    [m('wall.fortress', 'FLAT', 1), m('skill.area', 'MORE', -20), m('damage.hit', 'MORE', -30)],
                    '被輔助的牆改成在準心指的地方圍成一圈（長度加倍），但範圍更少 20%、擊中傷害更少 30%。', 'exact', ''),
    'frenzied_riposte': ('Frenzied Riposte', 2, 'g', 1.0, '反刺', 'FEATHER', ['attack'], ['persistent', 'aura'],
                         [m('charge.frenzy_consume_parried', 'FLAT', 1)],
                         '被輔助的攻擊擊中被招架的敵人時用掉招架，給你一顆{charge:frenzy}。', 'exact', ''),
    'fresh_clip_ii': ('Fresh Clip II', 4, 'r', 1.0, '新匣 II', 'IRON_NUGGET', [], [],
                      [m('damage.per_reloaded_bolt', 'FLAT', 1)],
                      '被輔助的弩技能過去 8 秒每裝填一顆弩矢（這個技能自己的彈匣），更多 1% 傷害。', 'exact',
                      '2026-10-27 改成原作：照這個技能裝填的弩矢算（這次之前照所有弩技能射出幾發）'),
    'gambleshot': ('Gambleshot', 3, 'b', 1.0, '賭射', 'RABBIT_FOOT', ['projectile'], [],
                   [m('projectile.gambleshot', 'FLAT', 1), m('damage', 'MORE', -15, ['projectile'])],
                   '被輔助的投射物每次射出時隨機多分裂、多連鎖或多穿透一次，但投射物傷害更少 15%。', 'exact', ''),
    'gorge': ('Gorge', 2, 'b', 1.0, '饕屍', 'BONE', ['corpse'], [],
              [m('corpse.flask_charges', 'FLAT', 10)],
              '被輔助的技能每用掉一具屍體，給 10 點生命與魔力藥劑充能。', 'exact', ''),
    'harmonic_remnants_ii': ('Harmonic Remnants II', 3, 'b', 1.0, '和骸 II', 'AMETHYST_SHARD', [], [],
                             [m('remnant_support.range', 'FLAT', 35), m('remnant_support.extra', 'FLAT', 20)],
                             '被輔助的技能產生殘骸時 20% 機率多產生一個；它產生的殘骸能從遠 35% 的地方撿。', 'exact',
                             '2026-10-27 改成原作：跟著這個技能產生的每一個殘骸走、真的多產生一個（這次之前「放技能後 12 秒內撿的殘骸」20% 機率給兩次）'),
    'magnetic_remnants': ('Magnetic Remnants', 0, 'b', 1.1, '磁骸', 'AMETHYST_SHARD', [], [],
                          [m('remnant_support.range', 'FLAT', 35)],
                          '被輔助的技能產生的殘骸能從遠 35% 的地方撿。', 'exact',
                          '2026-10-27 改成原作：跟著這個技能產生的每一個殘骸走（這次之前「放技能後 12 秒內撿的殘骸」都算）'),
    'remnant_potency_iii': ('Remnant Potency III', 4, 'r', 1.0, '骸效 III', 'AMETHYST_SHARD', [], [],
                            [m('remnant_support.effect', 'FLAT', 40), m('remnant_support.delay', 'FLAT', 3)],
                            '被輔助的技能產生的殘骸效果增加 40%，但撿到 3 秒後才生效。', 'exact',
                            '2026-10-27 改成原作：撿到 3 秒後才生效、跟著這個技能產生的每一個殘骸走；殘骸效果照全遊戲的算法（灌注殘骸：多給一層的機率）'),
    'hex_bloom': ('Hex Bloom', 3, 'b', 1.2, '咒綻', 'FERMENTED_SPIDER_EYE', [], [],
                  [m('curse.bloom_radius', 'FLAT', 3)],
                  '被輔助的詛咒影響的敵人死掉時，詛咒傳給 3 格內的敵人。', 'exact', ''),
    'holy_descent': ('Holy Descent', 5, 'r', 1.0, '聖降', 'GLOWSTONE_DUST', [], [],
                     [m('leap.consecrated_seconds', 'FLAT', 4)],
                     '被輔助的跳躍技能落地時留下 2.2 格的聖化地面 4 秒：站在上面的你與隊友每秒回復 5% 最大生命，詛咒效果減少 50%。', 'exact', ''),
    'impale': ('Impale', 4, 'g', 1.2, '刺釘', 'IRON_NUGGET', ['attack'], [],
               [m('impale.on_hit', 'FLAT', 100), m('flag.cannot_extract_impale', 'FLAT', 1)],
               '被輔助的攻擊擊中時穿刺（存下物理傷害的 30%，你下一次別的攻擊擊中牠時加上去），但自己不會抽出穿刺。', 'exact', ''),
    'incision': ('Incision', 4, 'r', 1.15, '切口', 'SHEARS_PLACEHOLDER', ['attack'], [],
                 [m('incision.chance', 'FLAT', 100), m('incision.bleed_more', 'FLAT', 3)],
                 '被輔助的攻擊擊中時給一層切割（每層 10% 流血機率，上了流血全部拿掉）；最近每用掉一層切割，流血更多 3%（最多 30%）。', 'exact', ''),
    'inexorable_critical_ii': ('Inexorable Critical II', 4, 'b', 1.2, '必然 II', 'FERMENTED_SPIDER_EYE', ['attack', 'spell'], ['persistent', 'aura'],
                               [m('crit.inexorable', 'FLAT', 40), m('crit.inexorable_bonus', 'FLAT', 10)],
                               '被輔助的技能每秒增加 40% 暴擊率（最多 200%）、暴擊傷害加成更少 10%（最多 50%）；暴擊時兩者歸零。', 'exact', ''),
    'last_gasp': ('Last Gasp', 1, 'b', 1.0, '迴光', 'GHAST_TEAR', ['minion'], [],
                  [m('minion.last_gasp_seconds', 'FLAT', 4)],
                  '被輔助的常駐召喚物生命歸零後再撐 4 秒才死（這段時間不會再掉血）。', 'exact', ''),
    'leverage': ('Leverage', 0, 'g', 1.0, '槓桿', 'STICK', ['attack', 'spell'], [],
                 [m('crit.vs_immobilised', 'INCREASED', 50)],
                 '被輔助的技能對被定住的敵人（凍結、重暈、定身、電擊）暴擊率增加 50%。', 'exact', ''),
    'loyalty': ('Loyalty', 2, 'b', 1.0, '忠僕', 'BONE', [], [],
                [m('minion.loyalty', 'FLAT', 10), m('life.max', 'MORE', -30, ['minion'])],
                '被輔助的夥伴替你承受 10% 的擊中傷害（先扣牠的生命），但召喚物最大生命更少 30%。', 'exact', ''),
    'malady': ('Malady', 0, 'g', 1.0, '病變', 'FERMENTED_SPIDER_EYE', ['attack', 'spell'], [],
               [m('flag.malady', 'FLAT', 1)],
               '被輔助的技能的流血基礎機率改用中毒的，中毒基礎機率改用流血的。', 'exact', ''),
    'mark_of_siphoning_ii': ('Mark of Siphoning II', 4, 'b', 1.4, '汲印 II', 'LAPIS_LAZULI', ['mark'], [],
                             [m('mark.siphon_leech', 'FLAT', 8)],
                             '被輔助的印記標記的敵人受到你的物理攻擊傷害，8% 偷取為生命、8% 偷取為魔力。', 'exact', ''),
    'minion_splash_ii': ('Minion Splash II', 5, 'r', 1.1, '僕濺 II', 'SLIME_BALL', ['minion'], [],
                         [m('minion.splash', 'FLAT', 1), m('skill.area', 'INCREASED', 20, ['minion'])],
                         '被輔助的召喚物近戰打中時，目標周圍 2 格的敵人受到這一下 60% 的傷害；召喚物的範圍增加 20%。', 'exact', ''),
    'minion_pact_ii': ('Minion Pact II', 4, 'b', 1.0, '僕契 II', 'GHAST_TEAR', ['attack', 'spell'], ['minion', 'persistent', 'aura'],
                       [m('minion_pact.life', 'FLAT', 150), m('minion_pact.more', 'FLAT', 35)],
                       '被輔助的技能放的時候，附近（6 格）一隻你的召喚物失去等同你最大生命 150% 的生命，這一下更多 35% 傷害。', 'exact', ''),
    'mobility': ('Mobility', 3, 'g', 1.0, '輕身', 'FEATHER', [], ['persistent', 'aura'],
                 [m('attack.speed', 'INCREASED', 6), m('cast.speed', 'INCREASED', 6)],   # 2026-10-27 照站長「放技能減速全部移除」；這次之前 movement.action_penalty MORE -25
                 '被輔助的技能攻擊與施法速度增加 6%（原作：放被輔助的技能時移動速度的懲罰更少 25%；這裡放技能不會變慢，平衡等效）。', 'approx',
                 '放技能不會變慢（站長 2026-10-27 移除），移動懲罰更少 25% 換成攻擊／施法速度增加 6%'),
    'neural_overload': ('Neural Overload', 3, 'g', 1.0, '電載', 'COPPER_INGOT', ['attack', 'spell'], [],
                        [m('flag.electrocute_primed', 'FLAT', 1)],
                        '被輔助的技能擊中蓄勢待電的敵人（電擊條到門檻）時直接電擊。', 'exact', ''),
    'opening_move': ('Opening Move', 1, 'r', 1.0, '先手', 'GOLD_NUGGET', ['attack', 'spell', 'travel'], [],
                     [m('daze.vs_full_life', 'FLAT', 1)],
                     '被輔助的技能擊中滿血的敵人時讓牠眩暈（受到的暈眩累積更多 50%，8 秒）。', 'exact', ''),
    'outmaneuver': ('Outmaneuver', 0, 'g', 1.15, '巧破', 'IRON_NUGGET', ['attack', 'spell'], ['persistent', 'aura'],
                    [m('armour.break_vs_parried', 'FLAT', 25)],
                    '被輔助的技能擊中被招架的敵人時破甲，等同物理傷害的 25%。', 'exact', ''),
    'perfection': ('Perfection', 4, 'g', 1.0, '完美', 'NETHER_STAR', [], [],
                   [m('perfection.on_perfect_timing', 'FLAT', 1)],
                   '被輔助的技能抓到完美時機時獲得一層完美（每層更多 5% 傷害，最多 4 層，10 秒）；沒抓到完美時機時全部失去。', 'exact', ''),
    'potential': ('Potential', 0, 'b', 1.0, '潛力', 'LAPIS_LAZULI', ['attack', 'spell'], ['persistent', 'aura'],
                  [m('charge.power_crit', 'FLAT', 40)],
                  '被輔助的技能放的時候消耗一顆{charge:power}，暴擊率更多 40%；這個技能不會產生銳星。', 'exact', ''),
    'practical_magic_ii': ('Practical Magic II', 5, 'b', 1.0, '實咒 II', 'ENCHANTED_BOOK', ['attack', 'spell'], [],
                           [m('stun.spell_damage_buff', 'FLAT', 40)],
                           '被輔助的技能重暈敵人時，8 秒內你的法術傷害增加 40%。', 'exact', ''),
    'profanity_ii': ('Profanity II', 5, 'b', 1.0, '褻屍 II', 'BONE', ['attack', 'spell'], ['aura', 'persistent'],
                     [m('profanity.corpses', 'FLAT', 10), m('profanity.more', 'FLAT', 5)],
                     '被輔助的技能放的時候用掉 5 格內最多 10 具屍體，每具這一下混沌更多 5% 傷害。', 'exact', ''),
    'punch_through': ('Punch Through', 3, 'g', 1.15, '擊穿', 'SLIME_BALL', ['melee'], [],
                      [m('easy_target.per_metre', 'FLAT', 3)],
                      '被輔助的近戰技能擊退敵人時讓牠成為好目標：下一次投射物攻擊擊中牠時，每格擊退距離增加 3% 傷害。', 'exact', ''),
    'pursuit_iii': ('Pursuit III', 5, 'g', 1.2, '追擊 III', 'FEATHER', ['melee'], [],
                    [m('damage.melee_after_projectile', 'FLAT', 30), m('damage', 'MORE', -100, ['projectile'])],
                    '最近 2 秒你的投射物攻擊擊中過敵人時，被輔助的技能近戰更多 30% 傷害；但不會造成投射物傷害。', 'exact', ''),
    'retreat_iii': ('Retreat III', 5, 'g', 1.2, '撤擊 III', 'FEATHER', ['projectile'], [],
                    [m('damage.projectile_after_melee', 'FLAT', 30), m('damage', 'MORE', -100, ['melee'])],
                    '最近 2 秒你近戰擊中過敵人時，被輔助的技能投射物更多 30% 傷害；但不會造成近戰傷害。', 'exact', ''),
    'quill_burst': ('Quill Burst', 3, 'r', 1.0, '刺爆', 'PRISMARINE_SHARD', ['melee'], [],
                    [m('thorns.quill_hits', 'FLAT', 3)],
                    '你用荊棘反彈之後，被輔助的近戰攻擊接下來 3 次擊中各觸發刺爆（往四周噴出尖刺，物理傷害）。', 'exact', ''),
    'rally': ('Rally', 0, 'r', 1.0, '重整', 'IRON_INGOT', ['melee'], ['persistent', 'aura'],
              [m('charge.endurance_life', 'FLAT', 10)],
              '被輔助的近戰技能放的時候消耗全部{charge:endurance}，每顆回復 10% 最大生命；這個技能不會產生磐星。', 'exact', ''),
    'rearm_ii': ('Rearm II', 5, 'g', 1.1, '再布 II', 'TRIPWIRE_PLACEHOLDER', ['hazard'], [],
                 [m('hazard.rearm', 'FLAT', 30)],
                 '被輔助的危險物件（寒凝彈的冰晶、蒺藜步的鐵蒺、矛陣的矛）觸發後 30% 機率過 1 秒在原地重新布置。', 'exact',
                 '2026-10-27 改成原作：矛陣改成照 PoE2 Spearfield 插在地上、敵人碰到才炸的矛，觸發後一樣會重新布置（這次之前矛陣是持續打的範圍，做成持續時間更多 30%）'),
    'refraction_iii': ('Refraction III', 5, 'r', 1.0, '折光 III', 'PRISMARINE_CRYSTALS', ['banner'], [],
                       [m('banner.refraction', 'FLAT', 2)],
                       '被輔助的戰旗插下之後（旗子的範圍與持續時間），範圍內的敵人獲得折光消融：每 1000 點你的護甲 2% 元素曝露（最多 80%）。', 'exact',
                       '2026-10-27 改成原作：範圍用插下的戰旗（這次之前用你身邊、臨在 1.5 倍）'),
    'reinforced_totems_ii': ('Reinforced Totems II', 5, 'r', 1.0, '固樁 II', 'IRON_INGOT', ['totem'], [],
                             [m('totem.resist_elemental', 'FLAT', 40), m('totem.resist_max', 'FLAT', 10)],
                             '被輔助的圖騰元素抗性 +40%、最大元素抗性 +10%。', 'exact', ''),
    'relentless_rage': ('Relentless Rage', 0, 'r', 1.15, '不息怒', 'BLAZE_POWDER', ['attack', 'spell'], ['persistent', 'aura'],
                        [m('rage.relentless_spend', 'FLAT', 30), m('rage.relentless_gain', 'FLAT', 10)],
                        '你花掉 30 點怒氣之後，下一次用被輔助的技能時獲得 10 點怒氣。', 'exact', ''),
    'rending_apex': ('Rending Apex', 3, 'r', 1.0, '裂巔', 'IRON_NUGGET', ['strike'], [],
                     [m('final_strike.armour_break', 'FLAT', 75)],
                     '被輔助的打擊連段的最後一擊破甲，等同造成的攻擊傷害的 75%：快速連用時寒冰震波、原獸連擊第 3 下，風暴連擊、撕咬第 4 下。', 'exact',
                     '2026-10-27 改成原作：補上打擊連段的最後一擊（這次之前平均成每一下物理傷害的 25%）'),
    'ricochet_iii': ('Ricochet III', 0, 'g', 1.0, '跳彈 III', 'SLIME_BALL', ['projectile'], [],
                     [m('projectile.ricochet', 'FLAT', 1), m('skill.pierce', 'FLAT', 20)],
                     '被輔助的投射物穿透所有目標；多出來的投射物不另外射，改成投射物從地形連鎖的次數。', 'exact', ''),
    'rime': ('Rime', 0, 'b', 1.0, '霧凇', 'SNOWBALL_PLACEHOLDER', [], [],
             [m('ground.rime_freeze', 'FLAT', 20), m('chill.effect', 'MORE', -30)],
             '站在被輔助的技能留下的冰緩地面上的敵人被擊中時，凍結累積像多 20% 傷害；但被輔助的技能造成的冰緩效果更少 30%。', 'exact',
             '2026-10-27 改成原作：照「站在冰緩地面上」、任何擊中都算（這次之前照冰緩中的敵人、只算這個技能的擊中）'),
    'rip': ('Rip', 3, 'r', 1.1, '撕血', 'REDSTONE_PLACEHOLDER', ['melee'], [],
            [m('remnant.blood_regen', 'FLAT', 10)],
            '被輔助的近戰攻擊打死流血中的敵人時留下血之殘骸：撿到 5 秒內生命再生速度增加 10%（最多 100%）。', 'exact', ''),
    'rupture': ('Rupture', 3, 'r', 1.0, '破脈', 'REDSTONE_PLACEHOLDER', ['attack', 'spell'], [],
                [m('bleed.aggravate_heavy_stunned', 'FLAT', 1)],
                '被輔助的技能擊中重暈中的敵人時加劇牠身上的流血（流血傷害加倍）。', 'exact', ''),
    'rusted_spikes': ('Rusted Spikes', 0, 'r', 1.1, '鏽刺', 'IRON_NUGGET', ['attack', 'spell'], [],
                      [m('flag.aggravate_bleed_pinned', 'FLAT', 1)],
                      '被輔助的技能定身敵人時（或擊中被定身的敵人時）加劇牠身上的流血。', 'exact', ''),
    'second_wind_iii': ('Second Wind III', 5, 'g', 1.5, '再起 III', 'FEATHER', ['cooldown'], [],
                        [m('cooldown.second_wind', 'FLAT', 1), m('cooldown.recovery', 'MORE', -40)],
                        '被輔助的技能冷卻次數加倍，但冷卻回復速度更少 40%；用了進入冷卻時，每秒冷卻回復 1% 最大生命。', 'exact', ''),
    'shock_conduction_ii': ('Shock Conduction II', 0, 'b', 1.2, '傳雷 II', 'COPPER_INGOT', ['attack', 'spell'], [],
                            [m('shock.conduct_nearby', 'FLAT', 50), m('flag.shock_drenched', 'FLAT', 1)],
                            '被輔助的技能讓敵人感電時，50% 機率讓 2.5 格內的敵人也感電；擊中濕透的敵人（積雷雲範圍內）一定感電。', 'exact',
                            '2026-10-27 改成原作：補上濕透（積雷雲範圍內的敵人）；感電傳給範圍內所有敵人、照原本的感電幅度（這次之前只傳一隻、固定 20%）'),
    'shock_siphon': ('Shock Siphon', 0, 'b', 1.2, '汲雷', 'COPPER_INGOT', ['attack', 'spell'], [],
                     [m('es.on_kill_shocked', 'FLAT', 4)],
                     '被輔助的技能打死感電中的敵人時回復 4% 最大能量護盾。', 'exact', ''),
    'slow_potency': ('Slow Potency', 1, 'g', 1.0, '緩勁', 'COBWEB_PLACEHOLDER', [], [],
                     [m('debuff.slow_potency', 'FLAT', 1), m('slow.magnitude', 'INCREASED', 15)],
                     '被輔助的技能施加的緩速（冰緩、詛咒、癱瘓、阻礙）幅度增加 15%。', 'exact', ''),
    'spar': ('Spar', 0, 'r', 1.0, '切磋', 'STICK', ['melee'], [],
             [m('stun.from_poise', 'FLAT', 100)],
             '被輔助的近戰攻擊的暈眩像多造成「你重暈條的百分比」的傷害（你越接近被重暈，越容易暈眩敵人）。', 'exact', ''),
    'retaliate_ii': ('Retaliate II', 4, 'r', 1.15, '還擊 II', 'IRON_INGOT', ['melee'], ['persistent', 'aura'],
                     [m('stun.from_poise', 'FLAT', 100), m('damage.from_poise', 'FLAT', 40)],
                     '被輔助的近戰攻擊照你的重暈條：攻擊傷害最多更多 40%，暈眩像多造成「重暈條百分比」的傷害。', 'exact', ''),
    'spectral_volley': ('Spectral Volley', 4, 'g', 1.2, '光影', 'END_ROD_PLACEHOLDER', ['attack'], ['channelling', 'persistent', 'aura'],
                        [m('projectile.spectral_volley', 'FLAT', 1), m('projectile.spectral_more', 'FLAT', 20)],
                        '被輔助的弓、長矛攻擊每次存一發光影投射物（最多 5 發），3 秒沒有再存時全部射出去（更多 20% 傷害）；一般的那一下更少 60% 傷害。', 'exact', ''),
    'spell_cascade': ('Spell Cascade', 1, 'b', 1.0, '連瀑', 'PRISMARINE_SHARD', ['spell'], [],
                      [m('spell.cascade', 'FLAT', 1), m('spell.cascade_area', 'FLAT', 20), m('skill.area', 'MORE', -20), m('damage', 'MORE', -30)],
                      '被輔助的範圍法術往目標區域兩側（隨機一條軸）各連瀑一次，但範圍更少 20%（連瀑再少 20%）、傷害更少 30%。', 'exact', ''),
    'splinter_totem_ii': ('Splinter Totem II', 5, 'r', 1.2, '裂樁 II', 'STICK', ['totem'], [],
                          [m('totem.only_one', 'FLAT', 1), m('totem.splinter', 'FLAT', 2), m('totem.splinter_damage', 'FLAT', 15),
                           m('totem.splinter_life', 'FLAT', 15), m('totem.splinter_area', 'FLAT', 15), m('totem.splinter_speed', 'FLAT', 7)],
                          '被輔助的技能同時只能有一根圖騰；圖騰用一次技能後分裂成兩根更小的圖騰，分裂出來的還能再分裂，最多 2 次（同時只有一根在分裂）；每分裂一次，傷害、最大生命、範圍各更少 15%，技能速度更少 7%。', 'exact',
                          '2026-10-27 改成原作：圖騰真的會分裂（這次之前平衡等效成同時多 2 根）；原作沒寫一次分成幾根，照「分裂成更小的自己」做成一根變兩根'),
    'steadfast_ii': ('Steadfast II', 3, 'r', 1.1, '穩守 II', 'IRON_INGOT', [], ['persistent', 'aura'],
                     [m('channelling.ailment.threshold', 'MORE', 100), m('channelling.stun.threshold', 'MORE', 50)],
                     '引導被輔助的技能時，你的異常門檻更多 100%、暈眩門檻更多 50%。', 'exact', ''),
    'stoicism_ii': ('Stoicism II', 5, 'r', 1.0, '堅忍 II', 'IRON_INGOT', ['attack'], ['persistent', 'aura'],
                    [m('damage.stoicism', 'FLAT', 2)],
                    '被輔助的攻擊每秒更多 2% 傷害（最多 20%）；翻滾或用突進技能時歸零。', 'exact', ''),
    'stomping_ground': ('Stomping Ground', 1, 'r', 1.0, '踏震', 'FLINT', ['travel'], [],
                        [m('travel.stomping_ground', 'FLAT', 1)],
                        '用被輔助的突進技能時每走一步（1.5 格）震出一陣 2 格的震波（武器攻擊傷害的 50%）；跳躍技能起跳、落地各一步。', 'exact',
                        '2026-10-27 改成原作：每走一步一陣、震波是攻擊（武器傷害 50%、2 格；這次之前起點、終點各一陣法術震波）；我們沒有走路動畫，一步照跑步的步幅 1.5 格'),
    'stormfire': ('Stormfire', 3, 'b', 1.0, '雷焚', 'BLAZE_POWDER', ['attack', 'spell'], [],
                  [m('ignite.stormfire', 'FLAT', 20)],
                  '被輔助的技能感電的敵人受到點燃時，另外受到點燃傷害 20% 的閃電傷害（吃閃電抗性）。', 'exact',
                  '2026-10-27 改成原作：點燃每一跳另外算閃電傷害（這次之前平衡等效成點燃更多 20%）'),
    'syzygy': ('Syzygy', 4, 'r', 1.0, '合軌', 'NETHER_STAR', ['slam'], ['persistent', 'aura'],
               [m('flag.syzygy', 'FLAT', 1)],
               '被輔助的猛擊對點燃中而且完全破甲的敵人有重壓擊（蓄勢待暈直接重暈），重暈牠們時永久恫嚇（受到傷害增加 10%）。', 'exact', ''),
    'tear': ('Tear', 3, 'r', 1.1, '裂創', 'REDSTONE_PLACEHOLDER', ['melee'], [],
             [m('kill.twice_vs_bleeding', 'FLAT', 50)],
             '被輔助的近戰攻擊打死流血中的敵人時，50% 機率擊殺效果（擊殺回復、藥劑充能、戰星）發生兩次。', 'exact', ''),
    'thrill_of_the_kill_ii': ('Thrill of the Kill II', 3, 'b', 1.0, '殺意 II', 'BLAZE_ROD', ['attack', 'spell'], ['persistent', 'aura'],
                              [m('cull.thrill_lightning', 'FLAT', 25), m('cull.thrill_shock', 'FLAT', 40)],
                              '被輔助的技能撲殺敵人時，8 秒內你的攻擊獲得 25% 傷害為額外閃電傷害、感電機率增加 40%（技能要能撲殺，例如掛斷命）。', 'exact', ''),
    'tumult': ('Tumult', 0, 'g', 1.0, '騷動', 'FEATHER', ['attack', 'spell'], ['persistent', 'aura'],
               [m('charge.frenzy_armour_break', 'FLAT', 50)],
               '被輔助的技能放的時候消耗一顆{charge:frenzy}，這一下破甲，等同物理傷害的 50%。', 'exact', ''),
    'unabating': ('Unabating', 0, 'r', 1.2, '不撓', 'IRON_INGOT', ['attack'], ['channelling', 'persistent', 'aura'],
                  [m('using_qs.armour', 'INCREASED', 25)],
                  '放被輔助的攻擊的那段時間，每 0.25 秒攻擊時間你的護甲增加 25%。', 'exact', ''),
    'unbending': ('Unbending', 0, 'b', 1.2, '不屈', 'LAPIS_LAZULI', ['spell'], ['persistent', 'aura'],
                  [m('using_qs.mana.recoup', 'FLAT', 8)],
                  '放被輔助的法術的那段時間，每 0.25 秒施法時間你受到的傷害有 8% 補償為魔力。', 'exact', ''),
    'unbreakable': ('Unbreakable', 0, 'r', 1.1, '不破', 'IRON_INGOT', [], ['persistent', 'aura'],
                    [m('using.stun.light_threshold', 'MORE', 200), m('using.stun.heavy_threshold', 'MORE', 30)],
                    '放被輔助的技能的那段時間，你的輕暈門檻更多 200%、重暈門檻更多 30%。', 'exact',
                    '2026-10-27 改成原作：輕暈門檻更多 200%（這次之前做成不會被輕暈）；更多暈眩門檻這次之前沒有作用，一起修好'),
    'undermine': ('Undermine', 0, 'r', 1.0, '掘弱', 'IRON_NUGGET', ['attack'], [],
                  [m('armour.break_consume_maim', 'FLAT', 35), m('flag.cannot_maim', 'FLAT', 1)],
                  '被輔助的攻擊擊中癱瘓中的敵人時用掉癱瘓，破掉牠 35% 的護甲；但自己不會癱瘓。', 'exact', ''),
    'untouchable': ('Untouchable', 0, 'g', 1.2, '無影', 'FEATHER', ['attack'], ['channelling', 'persistent', 'aura'],
                    [m('using_qs.evasion', 'INCREASED', 25)],
                    '放被輔助的攻擊的那段時間，每 0.25 秒攻擊時間你的閃避值增加 25%。', 'exact', ''),
    'unyielding': ('Unyielding', 0, 'r', 1.2, '不退', 'IRON_INGOT', ['melee'], ['persistent', 'aura'],
                   [m('using_qs.life.recoup', 'FLAT', 8)],
                   '放被輔助的近戰攻擊的那段時間，每 0.25 秒攻擊時間你受到的傷害有 8% 補償為生命。', 'exact', ''),
    'upheaval_ii': ('Upheaval II', 5, 'r', 1.0, '隆起 II', 'FLINT', [], [],
                    [m('fissure.additional', 'FLAT', 2), m('damage', 'MORE', -40), m('attack.speed', 'MORE', -20), m('cast.speed', 'MORE', -20)],
                    '被輔助的裂地技能多裂開 2 道，但傷害更少 40%、速度更少 20%。', 'exact', ''),
    'vanguard_ii': ('Vanguard II', 2, 'r', 1.1, '先鋒 II', 'IRON_INGOT', ['attack'], [],
                    [m('guard.on_stun_per_10_str', 'FLAT', 2), m('guard.on_heavy_stun_per_10_str', 'FLAT', 6)],
                    '被輔助的盾牌技能暈眩敵人（輕暈、重暈）時，每 10 點力量獲得 2 守護；重暈時每 10 點力量另外獲得 6 守護（4 秒）。', 'exact',
                    '2026-10-27 改成原作：補上怪物被輕暈的事件（這次之前 2＋6 合併在重暈時給）'),
    'volatile_power': ('Volatile Power', 0, 'b', 1.0, '爆能', 'GUNPOWDER', ['attack'], ['persistent', 'aura'],
                       [m('volatility.on_empowered_use', 'FLAT', 5)],
                       '被輔助的攻擊被強化時使用，給你 5 層易爆。', 'exact', ''),
    'volatility': ('Volatility', 3, 'b', 1.0, '不穩', 'GUNPOWDER', ['attack', 'spell'], ['persistent', 'aura'],
                   [m('volatility.on_crit', 'FLAT', 100)],
                   '被輔助的技能暴擊時給你一層易爆（4 秒後爆炸傷害你，之後獲得易爆之力：每層 1% 傷害獲得為混沌）。', 'exact', ''),
    'wildfire': ('Wildfire', 2, 'b', 1.2, '野火', 'BLAZE_POWDER', ['attack', 'spell'], [],
                 [m('ignite.spread_radius', 'FLAT', 1.5)],
                 '被輔助的技能造成的點燃擴散到 1.5 格內、待滿 1 秒的敵人。', 'exact', ''),
    'wildshards_ii': ('Wildshards II', 5, 'b', 1.0, '狂片 II', 'PRISMARINE_SHARD', ['spell'], [],
                      [m('projectile.wildshards', 'FLAT', 25)],
                      '被輔助的投射物法術 25% 機率多射 8 發，繞一圈射出。', 'exact', ''),
    'window_of_opportunity_ii': ('Window of Opportunity II', 5, 'g', 1.0, '良機 II', 'CLOCK', [], [],
                                 [m('perfect_timing.window_shorter', 'FLAT', 35), m('damage.perfect_timing', 'FLAT', 35),
                                  m('deflection.on_perfect_timing', 'FLAT', 15)],
                                 '被輔助的技能完美時機的區間更短 35%；抓到完美時機時更多 35% 傷害，8 秒內獲得閃避值 15% 的偏斜值。', 'exact',
                                 '2026-10-27 改成原作：補上完美時機的區間更短 35%'),
    'momentum_placeholder': None,
}
SPEC.pop('momentum_placeholder')

# 圖示：上面寫 *_PLACEHOLDER 的換成真的物品（只用輔助寶石已經在用、右鍵沒有動作的）
ICON_FIX = {'SHIELD_PLACEHOLDER': 'IRON_INGOT', 'ENDER_PEARL_PLACEHOLDER': 'ECHO_SHARD', 'INK_SAC_PLACEHOLDER': 'COAL',
            'ROTTEN_FLESH_PLACEHOLDER': 'BONE', 'NOTE_BLOCK_PLACEHOLDER': 'GOLD_NUGGET', 'SOUL_SAND_PLACEHOLDER': 'NETHER_BRICK',
            'ENDER_EYE_PLACEHOLDER': 'ECHO_SHARD', 'REDSTONE_PLACEHOLDER': 'NETHER_BRICK', 'ARROW_PLACEHOLDER': 'FEATHER',
            'SHEARS_PLACEHOLDER': 'FLINT', 'TRIPWIRE_PLACEHOLDER': 'STICK', 'SNOWBALL_PLACEHOLDER': 'PRISMARINE_CRYSTALS',
            'COBWEB_PLACEHOLDER': 'PHANTOM_MEMBRANE', 'END_ROD_PLACEHOLDER': 'GLOWSTONE_DUST'}

# 沒做的（原因）
SKIPPED = {
    'Burning Inscription': ('T4', 'b', '我們沒有地面符文（Ground Rune）技能可以觸發'),
    'Healing Runes': ('T0', 'w', '我們沒有符文護盾（Runic Ward，0.4 卡爾葛聯盟的防禦資源）'),
    'Runic Extraction': ('T0', 'w', '我們沒有符文護盾（Runic Ward）與瓦瑞西姆殘骸'),
    'Runic Infusion': ('T0', 'w', '我們沒有符文護盾（Runic Ward）'),
    'Fist Of Kalguur': ('T0', 'w', '我們沒有符文護盾（Runic Ward）與符文強化'),
    'Catalysing Elements': ('T5', 'b', '我們的元素地面不會強化技能（沒有「被元素地面強化」這件事可以觸發）'),
    'Skittering Stone': ('T4', 'r', '我們沒有會被打碎的岩石物件（原作是石化元素的召喚）'),
    'Soulbreaker': ('T0', 'b', '我們的怪物沒有能量護盾（只有少數詞綴的護盾），打破護盾幾乎不會發生'),
    'Controlled Hazard': ('T4', 'g', '我們的危險區域是持續打的範圍，不是踩到才觸發的物件；「3 秒內不會觸發」只剩壞處'),
    'Unsteady Tempo': ('T0', 'r', '0.5 資料沒有任何數值（poe2db 也沒有），做不出原作的強度'),
    'Font of Rage': ('T0', 'r', '0.5 資料只有半徑，沒有怒氣偷取的比例'),
    'Nadir': ('T0', 'b', '0.5 資料標成 DNT-UNUSED（還沒開放），也沒有數值'),
    'Invocation': ('T0', 'b', '0.5 資料沒有任何數值（poe2db 也沒有說明）'),
    'Lasting Ground': ('T0', 'r', '跟已經有的久地（Persistent Ground I：地面持續時間增加 50%）完全一樣'),
    'Selfless Remnants': ('T2', 'r', '我們的殘骸是給撿的人自己技能用的元素灌注，改給隊友沒有作用'),
    'Sacrificial Lamb': ('T3', 'b', '我們沒有會引爆召喚物或指定召喚物為目標的技能'),
}

# 2026-10-27 照站長刪掉的（supports.yml 的 retired 換成同階未切割輔助寶石）：不再產生，tsv 標 removed
#   家族: (PoE2 最高階, PoE2 階級, 顏色, 原本的代號, 原本的名稱, 原因)
REMOVED = {
    'Fusillade': ('Fusillade', 'T0', 'g', 'fusillade', '箭幕', '0.5 資料標成 DNT-UNUSED（還沒開放），做不出原作射上天落下的箭雨'),
    'Hobble': ('Hobble', 'T0', 'g', 'hobble', '跛行', '0.5 資料沒有任何數值，做不出原作的強度'),
    'Living Lightning': ('Living Lightning II', 'T4', 'b', 'living_lightning_ii', '活雷 II', '原作的活雷是不會被打、會瞬移的召喚物，我們做不出來'),
    'Momentum': ('Momentum', 'T2', 'g', 'momentum', '衝勢', '我們的技能大多按一下就瞬間出手，沒有「使用的那段時間」可以邊走邊用'),
}

# 已經有（代號對不上英文名，但其實有）的家族：不算缺
ALREADY = {
    'Break Endurance': 'break_endurance', 'Charge Profusion': 'charge_profusion', 'Culling Strike': 'culling_strike_ii',
    'Enraged Warcry': 'enraged_warcry_ii', 'Ice Bite': 'ice_bite_ii', 'Infernal Legion': 'infernal_legion_ii（III 跟 II 數值一樣）',
    'Mark for Death': 'mark_for_death_ii', 'Swift Affliction': 'swift_affliction_iii', 'Urgent Totems': 'urgent_totems_iii',
}


def block(sid, spec):
    poe, ptier, colour, mm, name, icon, req, exc, mods, desc, status, note = spec
    icon = ICON_FIX.get(icon, icon)
    lines = ['  %s:%s# %s\n' % (sid, ' ' * max(1, 24 - len(sid)), poe),
             '    name: %s\n' % name,
             '    description: %s\n' % desc,
             '    icon: %s\n' % icon,
             '    tier: %d%s\n' % (tier(ptier), '   # PoE2 第 0 階（不從未切割寶石切；我們放第 2 階）' if ptier == 0 else '')]
    if req:
        lines.append('    requires: [%s]\n' % ', '.join(req))
    if exc:
        lines.append('    excludes: [%s]\n' % ', '.join(exc))
    if mm is not None:
        lines.append('    mana_multiplier: %s\n' % mm)
    extra = '；' + note if note else ''
    kind = '平衡等效' if status == 'approx' else '照原作'
    lines.append('    # 2026-10-27 照 PoE2 %s（第 %d 階；poe2db／RePoE；%s%s）；這次之前沒有這顆\n' % (poe, ptier, kind, extra))
    lines.append('    modifiers:\n')
    for mod in mods:
        lines.append('      - %s\n' % mod)
    return ''.join(lines)


def main():
    text = F.read_text(encoding='utf-8')
    if BEGIN in text:
        start = text.index(BEGIN)
        end = text.index(END, start) + len(END)
        text = text[:start].rstrip('\n') + '\n' + text[end:].lstrip('\n')
    existing = set(re.findall(r'^    name: (.+)$', text, re.M))
    materials = set((ROOT / 'src/test/resources/materials.txt').read_text(encoding='utf-8').split())
    out = [BEGIN]
    names = set()
    for sid, spec in SPEC.items():
        name, icon = spec[4], ICON_FIX.get(spec[5], spec[5])
        assert name not in existing and name not in names, ('撞名', sid, name)
        assert icon in materials, ('圖示不是原版物品', sid, icon)
        assert re.search(r'^  %s:' % sid, text, re.M) is None, ('代號已經有了', sid)
        names.add(name)
        out.append('\n' + block(sid, spec))
    out.append(END)
    text = text.rstrip('\n') + '\n\n' + ''.join(out)
    F.write_text(text, encoding='utf-8')

    rows = ['# 2026-10-27 PoE2 0.5 缺的一般輔助寶石（不含血脈；tools/apply_poe2_missing_supports.py 產生）\n',
            '# PoE2 家族\tPoE2 最高階\tPoE2 階級\t顏色\t我們的代號\t名稱\t我們的階級\t狀態\t說明\n']
    for sid, (poe, ptier, colour, mm, name, icon, req, exc, mods, desc, status, note) in SPEC.items():
        family = re.sub(r' (I|II|III|IV)$', '', poe)
        rows.append('\t'.join([family, poe, 'T%d' % ptier, colour, sid, name, str(tier(ptier)), status, note]) + '\n')
    for family, (ptier, colour, why) in SKIPPED.items():
        rows.append('\t'.join([family, family, ptier, colour, '', '', '', 'skipped', why]) + '\n')
    for family, (poe, ptier, colour, sid, name, why) in REMOVED.items():
        rows.append('\t'.join([family, poe, ptier, colour, sid, name, '', 'removed', '2026-10-27 照站長刪掉（' + why + '）']) + '\n')
    for family, ours in ALREADY.items():
        rows.append('\t'.join([family, family, '', '', ours.split('（')[0], '', '', 'already', '代號沒寫英文名，其實已經有：' + ours]) + '\n')
    TSV.write_text(''.join(rows), encoding='utf-8')

    by = {'r': [], 'g': [], 'b': []}
    for sid, spec in SPEC.items():
        by[spec[2]].append(sid)
    for colour, ids in by.items():
        print(colour, len(ids), ' '.join(ids))
    print('加了', len(SPEC), '顆；exact', sum(1 for s in SPEC.values() if s[10] == 'exact'), '；approx',
          sum(1 for s in SPEC.values() if s[10] == 'approx'), '；skipped', len(SKIPPED), '；removed', len(REMOVED))


if __name__ == '__main__':
    main()
