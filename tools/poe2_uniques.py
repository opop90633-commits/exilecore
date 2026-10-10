# 2026-10：74 件傳奇照 PoE2 同部位的傳奇重做（名字、文字是自己的；效果照引擎有的屬性）。
# 用法：python3 poe2_uniques.py <data 資料夾>   —— 換掉 uniques.yml 裡這些傳奇的 mods，version +1、拿掉 legacy。
import re, sys, yaml

D = sys.argv[1]

def S(stat, typ='FLAT', value='{value}', tags=None):
    return (stat, typ, value, tags)

def M(text, stats, **r):
    return (text, r, stats)

def inc(stat, lo, hi, text, tags=None, neg=False):
    return M(text, [S(stat, 'INCREASED', '-{value}' if neg else '{value}', tags)], value=[lo, hi])

def flat(stat, lo, hi, text, tags=None, neg=False):
    return M(text, [S(stat, 'FLAT', '-{value}' if neg else '{value}', tags)], value=[lo, hi])

def more(stat, lo, hi, text, tags=None, neg=False):
    return M(text, [S(stat, 'MORE', '-{value}' if neg else '{value}', tags)], value=[lo, hi])

def flag(stat, text, value=1, tags=None, typ='FLAT'):
    return M(text, [S(stat, typ, value, tags)])

def added(kind, mn, mx, tags_extra=('attack',)):
    names = {'physical': '物理', 'fire': '火焰', 'cold': '冰冷', 'lightning': '閃電', 'chaos': '混沌'}
    t = list(tags_extra) + [kind]
    return M('附加 {min} 到 {max} ' + names[kind] + '傷害',
             [S('damage.added.min', 'FLAT', '{min}', t), S('damage.added.max', 'FLAT', '{max}', t)], min=mn, max=mx)

def phys(lo, hi):
    return inc('damage', lo, hi, '增加 {value}% 物理傷害', ['physical'])

def aspd(lo, hi, neg=False):
    return inc('attack.speed', lo, hi, ('減少' if neg else '增加') + ' {value}% 攻擊速度', neg=neg)

def crit_base(lo, hi):
    return M('+{value/100}% 暴擊率', [S('local.crit_base', 'FLAT', '{value/100}')], value=[lo, hi])

def life(lo, hi): return flat('life.max', lo, hi, '+{value} 最大生命')
def mana(lo, hi): return flat('mana.max', lo, hi, '+{value} 最大魔力')
def es(lo, hi): return flat('es.max', lo, hi, '+{value} 最大能量護盾')
def es_inc(lo, hi): return inc('es.max', lo, hi, '增加 {value}% 最大能量護盾')
def arm_inc(lo, hi): return inc('armour', lo, hi, '增加 {value}% 護甲')
def eva_inc(lo, hi): return inc('evasion', lo, hi, '增加 {value}% 閃避')
def attr(a, lo, hi):
    n = {'str': '力量', 'dex': '敏捷', 'int': '智慧'}[a]
    return flat('attr.' + a, lo, hi, '+{value} ' + n)
def res(e, lo, hi, neg=False):
    n = {'fire': '火焰', 'cold': '冰冷', 'lightning': '閃電', 'chaos': '混沌'}[e]
    return M(('-' if neg else '+') + '{value}% ' + n + '抗性', [S('resist.' + e, 'FLAT', '-{value}' if neg else '{value}')], value=[lo, hi])
def allres(lo, hi, neg=False):
    v = '-{value}' if neg else '{value}'
    return M(('-' if neg else '+') + '{value}% 全部元素抗性', [S('resist.fire', 'FLAT', v), S('resist.cold', 'FLAT', v), S('resist.lightning', 'FLAT', v)], value=[lo, hi])
def allattr(lo, hi):
    return M('+{value} 全部屬性', [S('attr.str', 'FLAT'), S('attr.dex', 'FLAT'), S('attr.int', 'FLAT')], value=[lo, hi])
def ae(lo, hi, what='護甲與閃避', stats=('armour', 'evasion')):
    return M('增加 {value}% ' + what, [S(s, 'INCREASED') for s in stats], value=[lo, hi])
def move(lo, hi): return inc('movement.speed', lo, hi, '增加 {value}% 移動速度')
def spell(lo, hi, tags=('spell',)): return inc('damage', lo, hi, '增加 {value}% 法術傷害', list(tags))
def cast(lo, hi, neg=False): return inc('cast.speed', lo, hi, ('減少' if neg else '增加') + ' {value}% 施法速度', neg=neg)
def lvl(n, what, tags):
    return flag('skill.level', '+' + str(n) + ' 全部' + what + '技能等級', n, tags)
def rarity(lo, hi): return inc('drop.rarity', lo, hi, '增加 {value}% 物品掉落稀有度')

U = {}

# ---------------- 雙手錘 ----------------
U['meteor_anvil'] = [  # 信仰之錘＋爆屍：終局
    phys(300, 350), aspd(10, 10, neg=True), allres(10, 15),
    inc('mana.regen', 40, 50, '增加 {value}% 魔力回復速度'),
    flat('kill.explode', 8, 10, '你殺死的敵人爆炸，對周圍造成牠最大生命 {value}% 的火焰傷害'),
    flat('life.per_kill', 25, 35, '擊殺時回復 {value} 生命'),
]
U['gravewarden_maul'] = [  # 巨力圖騰棒：慢而極重、打滿血的敵人更痛
    phys(450, 550), aspd(30, 35, neg=True), inc('stun.buildup', 40, 60, '增加 {value}% 暈眩累積'),
    flag('flag.stun_full_life', '擊中滿血的敵人時直接重暈牠'),
    inc('warcry.effect', 20, 30, '增加 {value}% 戰吼效果'),
]
U['forgefather'] = [  # 霜鐵頌歌：猛擊與暈眩
    phys(150, 200), attr('str', 15, 25), inc('stun.buildup', 30, 50, '增加 {value}% 暈眩累積'),
    flat('life.per_kill', 20, 30, '擊殺時回復 {value} 生命'),
    inc('skill.area', 20, 30, '猛擊技能的範圍增加 {value}%', ['slam']),
]
U['sanguine_idol'] = [  # 空嚎：擴散、偷血、流血
    added('physical', [25, 35], [40, 50]),
    flat('skill.melee_splash', 40, 50, '近戰打擊打中時，目標周圍的敵人也受到這一下 {value}% 的傷害'),
    flat('leech.life', 8, 10, '物理攻擊傷害的 {value}% 偷取為生命', ['attack', 'physical']),
    flat('bleed.chance', 15, 25, '擊中時 {value}% 機率造成流血'),
    inc('bleed.magnitude', 20, 30, '增加 {value}% 流血傷害'),
]
U['cleaver_of_kings'] = [  # 碎甲棒：粉碎、暈眩、對重暈的敵人必殺
    added('physical', [30, 40], [55, 70]), aspd(10, 15),
    inc('stun.buildup', 30, 50, '增加 {value}% 暈眩累積'),
    flat('armour.break', 30, 30, '打中時破甲（物理傷害的 {value}%）'),
    more('damage.vs_stunned', 50, 70, '對重暈中的敵人造成的傷害更多 {value}%'),
]
U['worldsplitter'] = [  # 破潮者：近戰等級、暈眩、所有傷害算進冰緩
    phys(250, 300), lvl(3, '近戰', ['melee']), attr('int', 20, 30),
    inc('stun.buildup', 150, 200, '增加 {value}% 暈眩累積'),
    flag('flag.all_damage_chills', '所有傷害都算進冰緩'),
    inc('skill.area', 15, 25, '增加 {value}% 範圍效果'),
]
# ---------------- 單手錘 ----------------
U['prybar'] = [  # 雕刻苦難：破甲、完全破甲的敵人受到所有擊中更痛
    inc('crit.bonus', 10, 20, '增加 {value}% 暴擊傷害加成'),
    added('physical', [21, 26], [30, 38]), aspd(10, 15),
    flat('armour.break', 40, 40, '打中時破甲（物理傷害的 {value}%）'),
    flat('kill.shatter', 15, 20, '你用擊中殺死完全破甲的敵人時，牠碎裂爆開，對周圍造成牠最大生命 {value}% 的物理傷害'),
]
# ---------------- 矛 ----------------
U['skypierce'] = [  # 天裂片：沒有物理、只擲最大或最小
    flag('damage.convert.lightning', '物理傷害全部轉換為閃電傷害', 100),
    added('lightning', [1, 1], [200, 260]), aspd(15, 25),
    inc('shock.chance', 50, 100, '增加 {value}% 感電機率'),
    flag('damage.random', '每一下傷害大幅隨機（最低 40%、最高 190%）', 60),
]
U['turncoat_spear'] = [  # 賽莎之矛：流血與火焰、流血加劇
    added('physical', [14, 26], [27, 32]), added('fire', [33, 41], [47, 53]),
    flat('bleed.chance', 15, 25, '擊中時 {value}% 機率造成流血'),
    inc('bleed.magnitude', 20, 30, '增加 {value}% 流血傷害'),
    flag('flag.crimson_assault', '你造成的流血會加劇：更痛、時間更短'),
]
U['reverie_spear'] = [  # 分舌：暴擊、分裂
    added('physical', [30, 38], [60, 72]), crit_base(500, 800), attr('int', 15, 25),
    flag('skill.fork', '投射物第一次打中時分裂', 1, ['projectile']),
    inc('crit.bonus', 20, 30, '增加 {value}% 暴擊傷害加成'),
]
# ---------------- 長棍 ----------------
U['thunder_spine'] = [  # 摩西亞：冰雷、精魂保留減半
    added('cold', [12, 18], [20, 30]), added('lightning', [1, 1], [40, 60]), crit_base(300, 500), aspd(15, 20),
    inc('mana.regen', 25, 40, '增加 {value}% 魔力回復速度'),
    flag('aura.reservation', '光環的精魂保留減少 50%', -50, None, 'INCREASED'),
]
U['nightmare_pole'] = [  # 納西爾的審判：擊暈、恍惚、對重暈更痛
    added('physical', [30, 36], [75, 81]), flat('accuracy', 50, 100, '+{value} 命中'),
    inc('stun.duration', 10, 20, '增加 {value}% 暈眩時間'),
    flag('daze.chance', '擊中時讓敵人恍惚', 100),
    more('damage.vs_stunned', 35, 50, '對重暈中的敵人造成的傷害更多 {value}%'),
]
U['serpent_staff'] = [  # 二元：連擊
    phys(150, 200), inc('crit.bonus', 20, 30, '增加 {value}% 暴擊傷害加成'), aspd(12, 18),
    flat('combo.extra_chance', 25, 40, '打出連擊時有 {value}% 機率多累積一層'),
    flat('damage.per_combo', 2, 3, '每層連擊讓傷害更多 {value}%'),
]
# ---------------- 法杖（wand） ----------------
U['astrolabe_wand'] = [  # 雅多尼亞的自負：法術等級、銳星
    mana(100, 150), lvl(3, '法術', ['spell']), cast(15, 30),
    flag('charge.max.power', '+1 銳星上限', 1),
    allres(10, 10, neg=True),
]
U['marrowblight'] = [  # 閾縈：詛咒越多越痛
    spell(70, 110), cast(7, 13),
    flag('skill.level', '+2 全部詛咒技能等級', 2, ['curse']),
    more('damage.vs_cursed', 30, 40, '對被詛咒的敵人造成的傷害更多 {value}%'),
    flat('damage.extra.chaos', 15, 25, '法術獲得 {value}% 傷害為額外的混沌傷害', ['spell']),
]
U['prayerbead_wand'] = [  # 生命嫩枝＋恩澤：法術等級、擊殺回魔
    mana(30, 50), M('+{value} 全部法術技能等級', [S('skill.level', 'FLAT', '{value}', ['spell'])], value=[1, 2]),
    cast(5, 10), flat('mana.per_kill', 10, 15, '擊殺時回復 {value} 魔力'),
    inc('crit.chance', 30, 50, '增加 {value}% 法術暴擊率', ['spell']),
]
# ---------------- 長杖（staff） ----------------
U['pyre_staff'] = [  # 灼觸：點燃
    inc('damage', 100, 140, '增加 {value}% 火焰傷害', ['fire']), cast(10, 20),
    inc('ignite.chance', 100, 100, '增加 {value}% 點燃機率'),
    inc('ignite.magnitude', 100, 100, '增加 {value}% 點燃傷害'),
    lvl(2, '火焰法術', ['spell', 'fire']),
]
U['dirge_staff'] = [  # 鴉群：召喚物傷害、精魂效率
    cast(8, 16), attr('int', 20, 30),
    inc('damage', 80, 120, '召喚物增加 {value}% 傷害', ['minion']),
    lvl(1, '召喚物', ['minion']),
    flag('aura.reservation', '精魂保留減少 30%', -30, None, 'INCREASED'),
]
U['solar_sceptre'] = [  # 低語寒冰（火）：極境
    lvl(4, '火焰法術', ['spell', 'fire']), cast(10, 20, neg=True), attr('int', 25, 35),
    flag('exposure.chance', '擊中時讓敵人曝露（打出的元素抗性降低）', 100),
    inc('exposure.effect', 100, 150, '增加 {value}% 曝露效果'),
    flat('penetration', 10, 15, '火焰傷害穿透 {value}% 火焰抗性', ['fire']),
]
U['voidfont'] = [  # 力量之泉：精魂、魔力
    flat('spirit.max', 40, 60, '+{value} 最大精魂'), mana(40, 60),
    inc('mana.regen', 20, 30, '增加 {value}% 魔力回復速度'),
    inc('aura.effect', 10, 20, '增加 {value}% 光環效果'),
    res('chaos', 13, 19),
]
# ---------------- 弓 ----------------
U['horizon_cleaver'] = [  # 銀舌（追逐級）：分裂、穿透、偷取
    added('physical', [40, 48], [65, 72]), crit_base(400, 600),
    flat('leech.life', 5, 8, '物理攻擊傷害的 {value}% 偷取為生命', ['attack', 'physical']),
    flat('leech.mana', 4, 7, '物理攻擊傷害的 {value}% 偷取為魔力', ['attack', 'physical']),
    flag('skill.fork', '投射物第一次打中時分裂', 1, ['projectile']),
    flag('skill.pierce', '投射物穿透 5 個敵人', 5, ['projectile']),
]
U['falconer_bow'] = [  # 獅眼：命中與必中，溢出的命中變暴擊
    phys(150, 240), flat('accuracy', 300, 500, '+{value} 命中'), aspd(10, 10), attr('dex', 20, 30),
    flag('flag.amazon_crit', '命中率超過 100% 的部分，25% 變成暴擊率'),
]
U['bandit_longbow'] = [  # 死亡豎琴：多一支箭、擊殺回復
    inc('crit.bonus', 20, 25, '增加 {value}% 暴擊傷害加成'),
    flat('life.per_kill', 20, 30, '擊殺時回復 {value} 生命'),
    flat('mana.per_kill', 12, 18, '擊殺時回復 {value} 魔力'),
    flag('skill.projectiles', '+1 投射物', 1, ['projectile']), phys(80, 120),
]
# ---------------- 弩 ----------------
U['siegebreaker'] = [  # 壁壘猛禽：攻速、不耗彈
    phys(150, 200), aspd(30, 40), inc('skill.projectile_speed', 20, 30, '增加 {value}% 投射物速度'),
    inc('crossbow.reload_speed', 30, 30, '減少 {value}% 裝填速度', neg=True),
    flat('crossbow.ammo_save', 30, 40, '弩的攻擊有 {value}% 機率不消耗彈藥'),
]
U['raincaller'] = [  # 霧語（雷）：連鎖
    added('lightning', [1, 1], [120, 160]), inc('shock.chance', 50, 80, '增加 {value}% 感電機率'),
    inc('shock.effect', 20, 30, '增加 {value}% 感電效果'), flat('mana.per_kill', 5, 10, '擊殺時回復 {value} 魔力'),
    flag('skill.chains', '攻擊多連鎖 2 次', 2, ['attack']),
]
U['null_repeater'] = [  # 混沌連弩：物理生混沌、混沌也偷取
    phys(150, 200), flat('damage.gain.chaos', 30, 40, '物理傷害的 {value}% 額外獲得為混沌傷害', ['attack']),
    flat('poison.chance', 25, 25, '擊中時 {value}% 機率造成中毒'),
    flat('leech.life', 3, 5, '物理攻擊傷害的 {value}% 偷取為生命', ['attack', 'physical']),
    flag('flag.leech_chaos', '混沌傷害也照物理的偷取率偷取生命'),
]
U['oathblood'] = [  # 最後的哀歌：偷血、以命換彈
    phys(250, 300), aspd(10, 20),
    inc('crossbow.reload_speed', 40, 60, '減少 {value}% 裝填速度', neg=True),
    flat('leech.life', 5, 10, '物理攻擊傷害的 {value}% 偷取為生命', ['attack', 'physical']),
    flat('crossbow.ammo_save', 20, 30, '弩的攻擊有 {value}% 機率不消耗彈藥'),
    flag('flag.blood_magic', '血償契：沒有魔力，技能改扣生命'),
]
U['lucid_repeater'] = [  # 雙重視野：多裝一發
    flag('crossbow.clip', '弩多裝填 1 發彈藥', 1),
    added('physical', [30, 40], [55, 75]), added('cold', [20, 28], [36, 48]), crit_base(500, 500),
    inc('crossbow.reload_speed', 15, 25, '增加 {value}% 裝填速度'),
]
U['barrage_crossbow'] = [  # 霧語（冰）：凍結、+2 投射物
    added('cold', [30, 40], [55, 70]), flat('mana.per_kill', 5, 10, '擊殺時回復 {value} 魔力'),
    inc('freeze.buildup', 30, 50, '增加 {value}% 凍結累積'),
    inc('chill.duration', 30, 30, '增加 {value}% 冰緩時間'),
    flag('skill.projectiles', '+2 投射物', 2, ['projectile']),
]
# ---------------- 盾、圓盾、法器 ----------------
U['prism_wall'] = [  # 稜鏡守護：精魂、全抗
    arm_inc(150, 200), flat('spirit.max', 40, 50, '+{value} 最大精魂'), attr('dex', 20, 30), allres(10, 20),
    M('+1% 全部元素抗性上限', [S('resist.max.fire', 'FLAT', 1), S('resist.max.cold', 'FLAT', 1), S('resist.max.lightning', 'FLAT', 1)]),
]
U['insurgent_shield'] = [  # 狼殤：暈眩門檻、磐星
    inc('block.chance', 10, 15, '增加 {value}% 格擋率'), arm_inc(60, 100), attr('str', 20, 30),
    flat('stun.threshold', 100, 150, '+{value} 暈眩門檻'),
    flat('charge.chance.endurance', 15, 25, '擊殺時 {value}% 機率獲得一顆磐星'),
    flat('presence.weaken', 8, 12, '你身邊的敵人造成的傷害更少 {value}%'),
]
U['floe_buckler'] = [  # 寒絕：冰抗上限、冰緩
    eva_inc(80, 120), M('+5% 冰冷抗性上限', [S('resist.max.cold', 'FLAT', 5)]), res('cold', 40, 40),
    inc('chill.effect', 20, 30, '增加 {value}% 冰緩效果'),
    inc('block.chance', 20, 30, '增加 {value}% 格擋率'),
]
U['styxguard'] = [  # 腐鳴（極境）：召喚物
    es(80, 120), inc('life.max', 30, 50, '增加 {value}% 召喚物最大生命', ['minion']),
    inc('damage', 40, 60, '召喚物增加 {value}% 傷害', ['minion']),
    flag('skill.minions', '召喚技能同時最多多 1 隻召喚物', 1, ['minion']),
    flat('spirit.max', 20, 30, '+{value} 最大精魂'),
]
# ---------------- 頭 ----------------
U['hollow_diadem'] = [  # 阿雅的面容（追逐級）：靈能電池
    es_inc(150, 200), rarity(10, 15), inc('crit.chance', 20, 30, '增加 {value}% 暴擊率'), res('lightning', 10, 20),
    lvl(2, '法術', ['spell']),
    flag('flag.eldritch_battery', '魂池逆流：能量護盾全部變成最大魔力'),
]
U['rime_halo'] = [  # 熱顫：冰緩越強越痛
    es_inc(50, 70), mana(60, 100), res('fire', 20, 30), res('cold', 20, 30),
    inc('chill.effect', 20, 30, '增加 {value}% 冰緩效果'),
    more('damage.vs_chilled', 15, 25, '對冰緩中的敵人造成的傷害更多 {value}%'),
]
U['quiet_mind'] = [  # 責罵之轡／靛藍：滿魔更痛
    es(75, 150), spell(60, 100), mana(80, 100), cast(15, 15, neg=True),
    more('damage.high_mana', 15, 20, '魔力在 90% 以上時，傷害更多 {value}%'),
]
U['genesis_crown'] = [  # 勝利者之冠：全部技能等級
    es_inc(80, 120), rarity(10, 15),
    flag('skill.level', '+1 全部技能等級', 1),
    flat('life.per_kill', 15, 20, '擊殺時回復 {value} 生命'),
    flat('mana.per_kill', 15, 20, '擊殺時回復 {value} 魔力'),
]
U['sanctum_helm'] = [  # 艾茲麥峰：不動如山
    arm_inc(80, 120), life(60, 90), flat('life.regen', 6, 10, '每秒回復 {value} 生命'),
    inc('skill.area', 10, 20, '增加 {value}% 範圍效果'),
    flag('flag.unwavering_stance', '磐石不移：不會被輕暈，不能翻滾'),
]
U['voidgaze_helm'] = [  # 鎖護：命中、暴擊
    ae(200, 300), flat('accuracy', 200, 400, '+{value} 命中'), inc('crit.chance', 30, 50, '增加 {value}% 暴擊率'),
    res('chaos', 13, 19),
]
U['dreamglass_mask'] = [  # 阿薩克之瞳：暴擊與中毒
    ae(100, 150, '閃避與能量護盾', ('evasion', 'es.max')), inc('crit.chance', 30, 40, '增加 {value}% 暴擊率'),
    attr('dex', 10, 20), attr('int', 10, 20), flat('poison.chance', 25, 35, '擊中時 {value}% 機率造成中毒'),
]
U['goldleaf_hood'] = [  # 金環式：新手的全抗帽
    flat('evasion', 30, 50, '+{value} 閃避'), rarity(10, 10), allres(25, 35),
]
# ---------------- 身體 ----------------
U['colossus_heart'] = [  # 卡翁之心（追逐級）
    inc('stun.threshold', 30, 40, '增加 {value}% 暈眩門檻'), life(800, 1000),
    flag('spirit.max', '你沒有精魂', -100, None, 'MORE'),
]
U['molten_aegis'] = [  # 黑辮：護甲也擋元素
    arm_inc(120, 160), life(80, 120), allres(10, 10),
    flat('armour.elemental', 50, 70, '護甲的 {value}% 也套用在元素傷害上'),
    flat('stun.threshold', 60, 100, '+{value} 暈眩門檻'),
    M('+3% 火焰抗性上限', [S('resist.max.fire', 'FLAT', 3)]),
]
U['stoneguard_plate'] = [  # 黃銅穹頂：暴擊不加傷
    arm_inc(500, 600), M('-{value}% 全部元素抗性上限', [S('resist.max.fire', 'FLAT', '-{value}'), S('resist.max.cold', 'FLAT', '-{value}'), S('resist.max.lightning', 'FLAT', '-{value}')], value=[1, 5]),
    flat('stun.threshold', 200, 300, '+{value} 暈眩門檻'),
    flag('crit.bonus_taken', '受到的暴擊不會多受傷害', -100, None, 'INCREASED'),
]
U['windrunner_coat'] = [  # 輕裹：藥劑、偏斜
    eva_inc(60, 100), inc('flask.life_recovery', 40, 60, '增加 {value}% 生命藥劑回復量'),
    inc('flask.mana_recovery', 40, 60, '增加 {value}% 魔力藥劑回復量'), attr('dex', 20, 30), res('cold', 20, 30),
    flat('deflection.from_evasion', 20, 30, '獲得閃避的 {value}% 為偏斜值'),
    more('evasion@nohit', 40, 60, '最近 4 秒沒被擊中時，閃避更多 {value}%'),
]
U['cinder_robe'] = [  # 火焰斗篷：火抗、點燃抗性、火法
    es(80, 120), res('fire', 30, 50),
    M('你身上的點燃持續時間減少 {value}%', [S('ailment.self_duration.ignite', 'INCREASED', '-{value}')], value=[30, 50]),
    lvl(2, '火焰法術', ['spell', 'fire']),
    flat('damage.extra.fire', 10, 15, '獲得 {value}% 傷害為額外的火焰傷害'),
]
U['hierophant_robe'] = [  # 柯斯普里之意志：詛咒
    ae(150, 200, '閃避與能量護盾', ('evasion', 'es.max')), move(5, 5), res('chaos', 23, 29),
    inc('curse.effect', 20, 30, '增加 {value}% 詛咒效果'),
    flag('skill.level', '+1 全部詛咒技能等級', 1, ['curse']),
]
U['outlaws_vest'] = [  # 野獸之腹：荊棘、暈眩門檻
    ae(100, 150), life(100, 150), flat('stun.threshold', 100, 150, '+{value} 暈眩門檻'),
    M('{min} 到 {max} 物理荊棘傷害', [S('thorns.min', 'FLAT', '{min}'), S('thorns.max', 'FLAT', '{max}')], min=[25, 30], max=[35, 40]),
    flat('charge.chance.frenzy', 10, 15, '擊殺時 {value}% 機率獲得一顆怒星'),
]
# ---------------- 腿（PoE2 的手套） ----------------
U['rampart_greaves'] = [  # 帝國之握
    arm_inc(150, 200), attr('str', 20, 30), flat('life.per_kill', 30, 50, '擊殺時回復 {value} 生命'),
    inc('damage', 10, 20, '增加 {value}% 物理傷害', ['physical']),
]
U['sages_wraps'] = [  # 多德瑞之任：法傷換施法速度
    es(40, 60), spell(80, 100), cast(15, 25, neg=True), attr('int', 10, 15),
]
U['fugitive_greaves'] = [  # 致命一擊：斷命、擊殺回復
    ae(100, 150), aspd(5, 10), flat('life.per_kill', 20, 30, '擊殺時回復 {value} 生命'),
    flat('mana.per_kill', 20, 30, '擊殺時回復 {value} 魔力'),
    flag('flag.culling', '斷命：擊中後生命太低的敵人直接倒下'),
]
# ---------------- 鞋 ----------------
U['glacial_tread'] = [  # 歐伯恩的堡壘
    move(10, 10), ae(150, 200), flat('life.regen', 20, 25, '每秒回復 {value} 生命'),
    M('你身上的冰緩、凍結、感電持續時間減少 {value}%', [S('ailment.self_duration.chill', 'INCREASED', '-{value}'), S('ailment.self_duration.freeze', 'INCREASED', '-{value}'), S('ailment.self_duration.shock', 'INCREASED', '-{value}')], value=[30, 50]),
]
U['roamers_boots'] = [  # 浪遊：不被冰緩
    move(20, 20), flat('evasion', 20, 40, '+{value} 閃避'), attr('dex', 5, 10),
    flag('flag.unslowable', '不會被冰緩'),
]
U['somnambulist'] = [  # 風嘯：詛咒
    move(10, 20), es_inc(50, 100), attr('int', 10, 20),
    inc('cast.speed', 10, 20, '詛咒技能增加 {value}% 施法速度', ['curse']),
    inc('curse.effect', 10, 15, '增加 {value}% 詛咒效果'),
]
U['ashwalker_boots'] = [  # 怒火之生（極境）
    move(20, 20), life(60, 90), res('fire', 20, 30),
    M('+2% 火焰抗性上限', [S('resist.max.fire', 'FLAT', 2)]),
    flat('presence.burn', 3, 5, '你身邊的敵人每秒受到你最大生命 {value}% 的火焰傷害'),
    inc('ignite.magnitude', 30, 50, '增加 {value}% 點燃傷害'),
]
U['phaseboots'] = [  # 鬼行：相位
    move(15, 15), ae(100, 150, '閃避與能量護盾', ('evasion', 'es.max')), mana(30, 50), res('chaos', 17, 23),
    flag('flag.phased_form', '相位：受到的傷害更少 30%，4 秒後再受到'),
]
# ---------------- 腰帶 ----------------
U['trophy_girdle'] = [  # 獵首（追逐級）
    inc('stun.threshold', 20, 30, '增加 {value}% 暈眩門檻'), life(40, 60), attr('str', 20, 40), attr('dex', 20, 40),
    flag('steal.mods', '殺死稀有怪物時，獲得牠的詞綴 30 秒', 30),
]
U['bone_cinch'] = [  # 不朽臍帶：召喚物
    attr('int', 20, 30), inc('life.max', 20, 30, '增加 {value}% 召喚物最大生命', ['minion']),
    flag('skill.minions', '召喚技能同時最多多 1 隻召喚物', 1, ['minion']),
    inc('flask.life_recovery', 20, 30, '增加 {value}% 生命藥劑回復量'),
]
U['ironclad_belt'] = [  # 血腹帶：雙倍護甲防禦
    flat('armour', 140, 180, '+{value} 護甲'), attr('str', 20, 30), flat('life.regen', 10, 20, '每秒回復 {value} 生命'),
    more('armour', 40, 50, '護甲更多 {value}%'),
]
U['offering_chain'] = [  # 九尾鞭：大量生命與回復
    life(120, 180), M('最近 4 秒被擊中過時，每秒回復 {value/10}% 最大生命', [S('life.regen_percent@hit', 'FLAT', '{value/10}')], value=[30, 50]),
    inc('flask.life_recovery', 25, 50, '增加 {value}% 生命藥劑回復量'),
]
U['ascetic_sash'] = [  # 午夜辮：傷害先從魔力扣
    mana(30, 50), allres(5, 10), inc('flask.mana_recovery', 20, 30, '增加 {value}% 魔力藥劑回復量'),
    flat('damage.from_mana', 10, 15, '受到的傷害有 {value}% 先從魔力扣除'),
]
# ---------------- 戒指 ----------------
U['undertow_band'] = [  # 貝雷克之握
    M('+{value}% 冰冷與閃電抗性', [S('resist.cold', 'FLAT'), S('resist.lightning', 'FLAT')], value=[25, 35]),
    attr('dex', 10, 20), attr('int', 10, 20),
    inc('crit.bonus_taken', 20, 30, '擊中你的暴擊傷害加成減少 {value}%', neg=True),
]
U['crimson_coil'] = [  # 靜脈穿刺：流血
    added('physical', [5, 7], [9, 13]), attr('str', 20, 30),
    flat('bleed.chance', 10, 20, '擊中時 {value}% 機率造成流血'),
    inc('bleed.magnitude', 15, 25, '增加 {value}% 流血傷害'),
]
U['keymasters_ring'] = [  # 安德瓦流斯：極大稀有度，代價是抗性
    rarity(50, 70), attr('dex', 10, 10), allres(20, 20, neg=True),
]
U['tally_ring'] = [  # 竊賊之痛：攻擊回血
    rarity(30, 40), allres(10, 15),
    flat('life.on_hit', 15, 25, '攻擊每擊中一個敵人回復 {value} 生命', ['attack']),
]
U['fracture_loop'] = [  # 明之心：額外混沌，代價是生命與防禦
    inc('life.max', 20, 20, '減少 {value}% 最大生命', neg=True),
    flat('damage.extra.chaos', 30, 40, '獲得 {value}% 傷害為額外的混沌傷害'),
    M('減少 {value}% 護甲、閃避與能量護盾', [S('armour', 'INCREASED', '-{value}'), S('evasion', 'INCREASED', '-{value}'), S('es.max', 'INCREASED', '-{value}')], value=[30, 30]),
    res('chaos', 7, 13),
]
U['blaze_loop'] = [  # 劈啪蔓延：火焰、點燃
    res('fire', 20, 30), inc('damage', 20, 30, '增加 {value}% 火焰傷害', ['fire']),
    flat('life.regen', 3, 6, '每秒回復 {value} 生命'), inc('mana.regen', 20, 30, '增加 {value}% 魔力回復速度'),
    inc('ignite.magnitude', 20, 30, '增加 {value}% 點燃傷害'),
]
U['winterbound_ring'] = [  # 極圈（極境）
    res('cold', 20, 30), inc('damage', 30, 40, '增加 {value}% 冰冷傷害', ['cold']), mana(40, 60), attr('str', 10, 15),
    flat('damage.extra.cold', 10, 15, '獲得 {value}% 傷害為額外的冰冷傷害'),
    M('+3% 冰冷抗性上限', [S('resist.max.cold', 'FLAT', 3)]),
]
# ---------------- 項鍊 ----------------
U['celestial_pivot'] = [  # 星辰圖（終局）：大量全屬性
    allattr(60, 80), flat('spirit.max', 20, 30, '+{value} 最大精魂'), allres(10, 15),
]
U['husk_amulet'] = [  # 利古林護符＋無垢之軀
    es(30, 40), flat('spirit.max', 25, 35, '+{value} 最大精魂'), attr('int', 20, 30),
    flag('flag.chaos_immune', '無垢之軀：免疫混沌傷害，最大生命變成 1'),
    inc('es.recharge', 30, 50, '增加 {value}% 能量護盾回充速度'),
]
U['votive_flame'] = [  # 索佛之血
    inc('life.max', 10, 20, '增加 {value}% 最大生命'), res('fire', 50, 100),
    flat('presence.resist.fire', 20, 25, '你身邊的敵人火焰抗性 -{value}%'),
]
U['censer_charm'] = [  # 永恆凝視＋拉茲瓦之石
    mana(50, 80), inc('mana.regen', 40, 60, '增加 {value}% 魔力回復速度'), cast(10, 15),
    flat('spirit.max', 15, 25, '+{value} 最大精魂'),
]
# ---------------- 箭袋 ----------------
U['linked_quiver'] = [  # 窒息之怒：冰緩的敵人受到更多傷害
    added('cold', [6, 8], [10, 16]), flat('damage.extra.cold', 5, 10, '攻擊獲得 {value}% 傷害為額外的冰冷傷害', ['attack']),
    aspd(5, 10), res('cold', 5, 10),
    more('damage.vs_chilled', 10, 20, '對冰緩中的敵人造成的傷害更多 {value}%'),
]
U['tailwind_quiver'] = [  # 鑽頸：穿透、偏斜
    flag('skill.pierce', '投射物穿透 2 個敵人', 2, ['projectile']), life(60, 90), aspd(8, 12),
    flat('deflection.from_evasion', 24, 32, '獲得閃避的 {value}% 為偏斜值'),
    inc('damage', 30, 50, '增加 {value}% 投射物傷害', ['projectile']),
]

RENAME = {'linked_quiver': '寒息箭袋', 'tailwind_quiver': '穿雲箭袋'}

def fmt_val(v):
    if isinstance(v, (int, float)):
        return str(int(v)) if float(v).is_integer() else str(v)
    return '"' + v + '"'

def render(mods):
    out = ['  mods:']
    for text, ranges, stats in mods:
        out.append('    - text: "' + text + '"')
        for k, (lo, hi) in ranges.items():
            out.append(f'      {k}: [{lo}, {hi}]')
        out.append('      stats:')
        for stat, typ, value, tags in stats:
            s = f'        - {{ stat: {stat}, type: {typ}, value: {fmt_val(value)}'
            if tags:
                s += ', tags: [' + ', '.join(tags) + ']'
            out.append(s + ' }')
    return out

ONLY = set(sys.argv[2:])   # 2026-10：後面寫傳奇代號 = 只重做這幾件（版本各 +1）
if ONLY:
    U = {k: v for k, v in U.items() if k in ONLY}
text = open(D + '/uniques.yml', encoding='utf-8').read()
lines = text.split('\n')
data = yaml.safe_load(text)
out, i, done = [], 0, set()
while i < len(lines):
    m = re.match(r'^([a-z0-9_]+):\s*(#.*)?$', lines[i])
    if m and m.group(1) in U:
        uid = m.group(1)
        j = i + 1
        while j < len(lines) and not re.match(r'^[a-z0-9_]+:', lines[j]) and not (lines[j].startswith('#') and lines[j + 1:j + 2] and re.match(r'^[a-z0-9_]+:', lines[j + 1])):
            j += 1
        block = lines[i:j]
        # 尾巴的空行留著
        tail = []
        while block and block[-1].strip() == '':
            tail.insert(0, block.pop())
        head, k = [], 0
        while k < len(block) and not block[k].startswith('  mods:'):
            head.append(block[k]); k += 1
        k += 1
        # mods 之後還有別的頂層欄位（version、legacy、chase…）
        rest = []
        while k < len(block):
            if re.match(r'^  [a-z_]+:', block[k]) and not block[k].startswith('  mods:'):
                rest.append(block[k])
                k += 1
                while k < len(block) and (block[k].startswith('    ') or block[k].strip() == ''):
                    rest.append(block[k]); k += 1
            else:
                k += 1
        keep = []
        skipping = False
        for ln in head + rest:
            if re.match(r'^  (version|legacy):', ln):
                skipping = True
                continue
            if skipping and ln.startswith('    '):
                continue
            skipping = False
            keep.append(ln)
        ver = int(data[uid].get('version', 1)) + 1
        if uid in RENAME:
            keep = [re.sub(r'^  name: .*$', '  name: ' + RENAME[uid], ln) for ln in keep]
        out.extend(keep)
        out.append(f'  version: {ver}        # 2026-10：照 PoE2 同部位的傳奇重做（舊的登入時重擲）')
        out.extend(render(U[uid]))
        out.extend(tail)
        done.add(uid)
        i = j
        continue
    out.append(lines[i]); i += 1
missing = set(U) - done
print('done', len(done), 'missing', missing)
open(D + '/uniques.yml', 'w', encoding='utf-8').write('\n'.join(out))
