# 資料檔

第一次啟動時會複製到 `plugins/ExileCore/data/`，之後改那裡的檔案再 `/ec reload` 即可；
jar 裡的這份只是預設值。管理網頁（`/ec web`）改的設定存在 `plugins/ExileCore/admin-overrides.yml`，讀資料檔時蓋在同一個鍵上
（資料檔本身不動，照常自動更新；網頁上按「恢復」或刪掉那個檔就回到資料檔的值）。升級時你沒改過的檔案自動換新版；改過的不動，新版放在旁邊的 `.new`，
啟動時主控台會列出你的檔案缺了新版的哪些鍵（新功能的段落沒讀到只會照預設值跑、不會報錯）：
`/ec data` 看狀態、`/ec data diff 檔` 看缺的鍵、`/ec data accept 檔` 換成新版（舊的留 `.bak-日期時間`）。

| 檔案 | 內容 |
| --- | --- |
| items.yml | 物品等級縮放與品質加成 |
| names.yml | 稀有物品／稀有怪的隨機名字 |
| currencies.yml | 通貨定義（含開技能輔助孔的鑿星石、地圖用的星盤刻刀、開終局的天穹碎片、開極境的深淵星核、普通物品變傳奇或碎掉的僥倖石）；地圖機制的特殊通貨：星魄（`operation: ESSENCE`＋`affix`，保證詞綴的改造）、催化劑（`CATALYST`＋`catalyst`，飾品品質＝某類詞綴的加成）、星籤（`OMEN`＋`omen.triggers/effect`，改下一顆通貨的規則）、蒸餾情緒（`INSTIL`，拿著點項鍊打開注入選單，3 顆把一個天賦核心注入項鍊；哪一種看核心離天賦樹中心多遠，順序在 map_events.yml 的 delirium.emotions） |
| monsters.yml | 怪物等級、生命與傷害表（錨點之間等比內插，到 100 級；地圖的怪最高 84 級）、經驗表（PoE 的怪物經驗表，每一級都寫）與 `experience_multiplier`（再乘幾倍：出貨的檔案寫 2，沒寫 = 1）、稀有度、原版生物對照、怪物詞綴（含光環、召喚，要夠高等才會出現）；生物種類的招式（types.*.skills、skill_chance、hand）與帶招式的詞綴（mods.*.skills、hand，一隻最多兩條；招式代號見 boss_skills.yml） |
| drops.yml | 掉落表（每隻怪掉幾件；通貨、裝備、未切割寶石、藥劑的比例——2026-10 寶石、藥劑、四種低階通貨掉少一點，裝備與其他通貨不變）、金幣掉落（到 100 級；商人、賭博、補給的價格也照這張表算）、全服掉落加成 `server_bonus`（活動用：物品數量 +%、稀有度 +%、金幣倍數；管理網頁可以直接改） |
| bases/*.yml | 基底物品（武器、防具、盾、飾品；maps.yml 是地圖，slot: MAP，標籤 map_t1… 是在星圖的第幾圈；地圖物品的階級 T1–T16 寫在物品上；jewels.yml 是珠寶，slot: JEWEL，放進天賦樹的珠寶孔，顏色標籤 ruby／emerald／sapphire 決定詞綴池、drop_weight 是掉落權重） |
| affixes/*.yml | 前綴與後綴（maps.yml 是地圖詞綴：monster:／boss:／player: 前綴決定作用在誰身上，每條都帶物品數量與稀有度；jewels.yml 是珠寶詞綴，只出現在珠寶上，前後綴各最多 2 條，三個階級）。階級代號越小越強，0、-1 是後來加的更強階級（物品等級 55–78；地圖詞綴與珠寶的 0 要 75）；遊戲裡顯示的 T 幾是排名。`{value/10}` 是小數（擲出 12 顯示 1.2%）、`"-{value}"` 是「減少 x%」；stat 以 `local.` 開頭的是武器本身的詞綴（見下面「裝備詞綴的屬性」） |
| uniques.yml | 傳奇物品（37 件）：固定名字、基底、詞綴範圍、世界觀文字、誰會掉（`sources`：終局 Boss、地圖 Boss、某張地圖的 Boss、追逐級的 chase 池、地圖事件 shrine／strongbox／breach／exile／ritual／delirium、極境 `uber_終局代號`）、能不能用僥倖石做出來（chance）、能不能賭出來（gamble）；可以用下面的「特殊效果」屬性。改詞綴時 `version` 加一（順序有變再寫 `legacy`），已經掉出來的舊傳奇會自動補正 |
| maps.yml | 地圖與終局：設定（T1–T16 每一階的怪物等級與階級加成、實例上限、傳送門次數、關閉時間）、掉落（地圖的階級與上限、稀有度隨階級變（T16 大多是普通、魔法，要自己洗）、地圖 Boss 與具名 Boss 的傳奇 5%、追逐級傳奇 `chase_chance`（所有地圖 Boss 固定 0.034%，不吃任何加成）、終局另外算的共通傳奇 `final_common_chance` 與專屬輔助寶石 `final_support_chance`、天穹碎片、劇情最後的 Boss 掉 T1）、首殺獎勵、14 張地圖（主題、版面樣式 `layout`、星圖位置、相連、怪物、Boss 與階段）、終局 final（開的時候選階級）與其他終局 finals（同一種寫法，多了 requires 要先打贏誰、points 首殺點數；地圖裝置選 Boss 與階級）。Boss 的特殊機制：phases 的 `shield`（護盾：限時打掉結晶，不然 Boss 回血）、`enrage`（開打幾秒後狂暴）、`hazards`（場地危害：每隔幾秒自己落下的招）；終局底下的 `uber`（極境：倍率、多的招式與階段、開門的通貨與掉落機率、極境的掉落：專屬傳奇 `exclusive_chance`、`extra` 裡的通貨與專屬輔助寶石），寫法見檔案裡的說明。主題（theme）：beach、forest、ruins、swamp、snow、cave、crypt、star、desert（沙漠）、volcano（火山，水池是岩漿）、fungal（菌窟）；版面樣式（layout，不寫是 halls）：halls 廳堂、open 曠野（大片空地＋岩石樹叢＋小水池）、cavern 洞窟（不規則洞室、S 形彎道、地下水池）、islands 群島（水上的島、窄堤道）、city 城區（方形廣場、直街、繞路）。試煉與回響的版面主題也能用新的三種 |
| character.yml | 等級上限（100）、角色格數、全服經驗加倍（`experience_bonus`，出貨 2；打怪、委託的經驗都乘）、經驗表（跟 PoE2 一樣，總共 4,250,334,444）、等級差懲罰（`monster_level_cap` 怪物最高算 80 級、`high_level` 95 級以上的減少）、死亡懲罰、退點金幣、新手裝備（七個職業能選的武器、防具、盾、起始技能；起始技能的規則可以寫 `class:` 只給一個職業） |
| passives/tree.json | 被動天賦樹（第 2 版：七個職業的起點、834 個節點，含召喚物、光環、戰吼、元素異常的分支，外圈四組暴擊分支（元素法師、混沌術士那邊是法術暴擊，弓箭手、長矛獵手那邊是攻擊暴擊），18 個珠寶孔；節點座標、修飾器、說明；相鄰格子即相連。`version` 改了的話，舊角色登入時天賦點全部退回） |
| zones.yml | 區域（名稱、等級、刷怪清單、區域 Boss 與生命倍率）、刷怪群與 Boss 的共用設定；區域的位置在 `plugins/ExileCore/zone-layout.yml`（用 `/ec zone` 擺） |
| salvage.yml | 拆解台給的金幣與品質通貨 |
| world.yml | 伺服器名稱《星骸》、伺服器列表說明（MOTD）、新角色登場的標題與旁白；劇情世界（要不要產生、第一次自動擺版面、固定時間）與地圖世界的時間 |
| skills.yml | 技能（69 個主動技能、7 種普攻：弓、弩、法杖、長杖、長棍、矛、其他近戰，與 3 個使者的隱藏擊殺效果）：種類、標籤、運作方式（打擊、投射物、新星、扇形、跳躍、連鎖、位移、延遲落下、地面效果、印記、骸爆、召喚物、光環、戰吼，各自的參數：榴彈、濺射、散射、連射、漩渦、閃現、處決、餘震…）、交給圖騰／陷阱／鐘放（totem／trap／bell 區塊）、召喚物（summon 區塊：生物種類、幾隻、多久、同等級怪物的幾倍生命與傷害、要不要屍體、吼聲引怪、給主人的增益）、光環（aura 區塊：保留、範圍、隨等級的效果、使者的 on_kill 隱藏技能）、戰吼（warcry 區塊：回復、增益、敵人受到的傷害增加、強化幾次攻擊、嘲諷）、冷卻時間、各等級的傷害與魔力、外觀、起始技能、寶石階級、技能自己帶的修飾器（點燃機率…）；寶石各等級的需求等級（19、20 級要 64、80 級）、怪物掉的未切割技能寶石是最高等級／低 1 級／低 2 級的權重（`uncut_level_weights`）、法術成長、超過 20 級（傳奇的 +技能等級）每級多幾 % 法術傷害（extra_level_damage） |
| supports.yml | 輔助寶石（50 個：範圍、投射物、速度、元素異常、流血、中毒、暈眩、電擊、持續時間、冷卻、暴擊、歧射、回歸、雙詠、複擊、圖騰施法、陷阱施法、圖騰列陣、兇猛召喚、堅韌召喚、迅捷召喚、強效光環、慷慨、強勢戰吼、聚怒、聚銳、聚磐…）：階級、能輔助的技能（requires 看技能標籤，含自動補上的 duration、cooldown、movement、travel 突進；excludes 有其中一個標籤就不行，寶石說明的「可以輔助」會寫「（召喚物、光環除外）」，只列真的有技能被擋的）、說明裡的 `{charge:代號}` 換成戰星的名字與每顆的效果（照 charges.yml）、修飾器、魔力倍率；`source`：專屬掉落（寫終局代號＝那隻終局 Boss 掉、`uber`＝只有極境掉；現在 6 個，未切割輔助寶石切不出來）；未切割輔助寶石各階從幾級開始掉；同一種輔助能不能掛在好幾個技能上（once_per_character） |
| map_events.yml | 地圖事件：每張地圖放幾個、各種事件的權重、各事件的專屬傳奇機率（`unique_chance`，預設 2%）；神龕（增益種類與秒數、守衛幾隻）、寶箱（跳出幾隻、幾種寶箱各掉什麼）、裂隙（幾秒、幾秒一波、半徑長到多大、物品數量加成）、流寇（生命倍率、詞綴、多掉幾件稀有、名字）；PoE2 的祭壇（ritual：機率、幾座、圓多大、波數、貢品、獎勵種類與價格、重擲費用）與幻霧（delirium：機率、秒數、難度爬升、幻霧怪、獎勵進度門檻與種類、蒸餾情緒的階級） |
| atlas_tree.yml | 星圖天賦（地圖天賦樹）：天賦點怎麼來（每完成一張不同的地圖、每打贏一個終局、每打贏一個極境、最高完成階級的里程碑）、九條分支（地圖續航、豐饒、群聚、首領、神龕、寶箱、裂隙、流寇、祭儀與迷霧）各四個節點，效果鍵寫在檔案開頭；效果算開地圖的人點的，整張地圖都吃 |
| pvp.yml | PvP 競技場（/pvp）：開關、專屬的 PvP 減傷 `damage_reduction`（玩家打玩家的傷害減少幾 %，召喚物、圖騰、陷阱也算）、進場等級、擊倒給的藥劑充能、擊倒訊息、倒下後重生在場內還是回營地。場地在自己的世界 exilecore:arena，第一次啟動自動蓋好（八方向對稱的掩體，柱子以外都有梯子） |
| boss_skills.yml | Boss 招式：招式庫（slam 延遲落下、nova 新星、cone 扇形、volley 齊射、zone 地面效果、charge 衝鋒、summon 召喚、curse 詛咒、blink 閃現／撲擊；特殊機制 beam 光束掃射、ring 擴散環（綠色缺口）、pull 引力漩渦、orbs 追蹤光球、sanctuary 全場大招（綠色安全區）；場地危害用的 hazard_*；傷害是 Boss 普通一下的幾倍、類型、冷卻、距離、前搖…）、各種生物預設的招式組（defaults）、通用設定（兩招的間隔、出現後多久開始、預警距離、追擊：目標在幾格外多久打不到就用哪招跳過去、換階段後停幾秒與新階段的招幾秒後才用）。maps.yml／zones.yml 的 boss 可以寫 `skills:` 自訂（含 phase 幾階段起才用）。同一招打同一個人不會疊（齊射、落點 0.5 秒內只算一下，光球一次只被一顆打到，地面效果疊在一起只算一塊）。Boss 一次只做一件事：場地危害等 Boss 手上這一招放完才落、落下之前不出新的招，齊射還在飛、光球剛放出去也算這一招還沒完；放招時 Boss 原版的動作（近戰、射箭、伏守者的音波）暫停 |
| flasks.yml | 藥劑：藥劑欄（快捷欄 1、2）、殺怪充能、檢查點補滿、生命與魔力藥劑各 11 種基底（68、80 級兩種給地圖）、起始藥劑 |
| loot.yml | 掉落：自動撿取開關、過濾器的預設組合（寬鬆、標準、嚴格；進階規則的地圖是 `hide_maps: none／normal／magic`，依稀有度）、被過濾的東西換成多少金幣、哪些東西撿到要提示（`notable.quiet_currencies` 這些通貨不提示） |
| charges.yml | 球：銳星、怒星、磐星（什麼時候有機會獲得：暴擊／擊殺／被擊中、基礎上限、每顆幾秒、每顆給的屬性）；要有詞綴、天賦或輔助寶石給 `charge.chance.代號`、`charge.on_hit.代號`（擊中時），或放聚星戰吼（直接給）才會拿到球，`charge.max.代號`、`charge.duration` 加上限與時間；瞬影擊冷卻中消耗怒星直接放（skills.yml 的 `cooldown_bypass`） |
| ailments.yml | 異常狀態：點燃、冰緩、凍結、感電、流血、中毒、暈眩、電擊的門檻、機率、效果、持續時間 |
| economy.yml | 經濟：每種通貨值多少金幣（手續費、市集排序用）、面對面交易（距離、確認延遲）、市集（商店頁格數、上架與購買的金幣手續費、上架後多久不能下架）、通貨交易所（手續費、掛單數）、商人（一批幾件、多久換、價格＝同等級普通怪的金幣 × `kills` × 稀有度倍率、馬上換一批 × `refresh_kills`）、賭博（價格＝同等級普通怪的金幣 × `kills`、稀有度權重、地圖 Boss 也會掉的傳奇用 `boss_unique`） |
| dummies.yml | 營地的訓練假人：名字、等級（跟打它的人／最高階地圖／固定）、護甲與閃避（一般怪物的幾倍）、抗性、位置、外觀；練習場的範圍、停手幾秒給總結 |
| party.yml | 組隊與多人：隊伍人數與邀請期限、掉落怎麼分（傷害 5% 以上各拿一份）、經驗怎麼分（附近隊員照等級分）、人多時怪物生命加多少 |
| quests.yml | 劇情與任務：劇情人物（名字、站的位置、閒聊）、主線與支線（誰給、前置、每一步的目標與台詞、永久獎勵、幾選一） |
| events.yml | 每月活動：主題照月份輪替（對地圖的影響：物品數量、稀有度、稀有怪的詞綴、事件權重、祭壇與幻霧機率）、每月挑戰（種類、數量、功勳）、限定稱號（`title.stats`：擁有就有的永久加成）與全部完成的功勳 |
| bounties.yml | 每日委託（每天抽幾個、每一種的目標、功勳與經驗；`settings.experience_level_cap` 超過幾級照那一級的升級經驗算）、每週回響（打倒過的區域 Boss 強化版：倍率、詞綴、掉落加成、版面主題、半血的階段）、功勳兌換（倉庫頁、藥劑、天穹碎片、稱號的價格；稱號的 `stats` 是擁有就有的永久加成，像分身獎勵：帳號裡每個角色都有、不用戴、好幾個會疊） |
| ascendancy.yml | 昇華：試煉（解鎖條件、怪物等級、第一次完成給的昇華點、版面、守門的 Boss 與階段；Boss 也能寫 maps.yml 的護盾、狂暴、場地危害）、昇華職業（每個職業兩個；昇華天賦的分支、點數、屬性）、分身獎勵（帳號裡每種職業最高的角色到 60／75／90 級給整個帳號的加成） |
| balance.yml | 平衡的參考角色（中等裝備的攻擊與法術角色，裝備最高到物品等級 84）與目標（1 到 100 級的擊殺秒數、被打一下扣幾 % 生命、法術對攻擊的比例、Boss 要打幾秒）；`/ec balance` 用它算報表與建議值（怪物生命／傷害錨點、法術成長、Boss 生命倍率） |

## 元素異常的屬性

技能（skills.yml 的 `modifiers`）、輔助寶石、裝備詞綴、怪物詞綴、天賦都可以用：

| 屬性 | 型態 | 意思 |
| --- | --- | --- |
| `ignite.chance`、`shock.chance` | FLAT／INCREASED | 點燃／感電機率 +x%（直接加）／增加 x% 機率 |
| `ignite.magnitude` | INCREASED／MORE | 點燃的傷害 |
| `chill.effect`、`shock.effect` | INCREASED | 冰緩的減速幅度、感電的「受到的傷害增加」 |
| `freeze.buildup` | INCREASED／MORE | 凍結累積 |
| `ignite.duration`、`chill.duration`、`freeze.duration`、`shock.duration` | INCREASED | 各自的持續時間 |
| `bleed.chance`、`poison.chance` | FLAT／INCREASED | 流血（只有攻擊；tags 寫 [attack]）／中毒機率 +x%／增加 x% 機率；流血與中毒預設只靠機率，不靠傷害比例 |
| `bleed.magnitude`、`poison.magnitude` | INCREASED／MORE | 流血、中毒的傷害 |
| `bleed.duration`、`poison.duration` | INCREASED | 流血、中毒的持續時間 |
| `stun.buildup` | INCREASED／MORE | 暈眩累積（攻擊方；任何傷害都累積，物理算 100%、元素與混沌算一半） |
| `stun.threshold` | INCREASED | 自己的暈眩門檻（防禦方；越高越不容易被暈；跟元素異常門檻互不影響） |
| `electrocute.chance` | FLAT／INCREASED | 電擊機率（閃電傷害擊中時有多少機率累積電擊；沒有就不累積） |
| `electrocute.buildup` | INCREASED／MORE | 電擊累積 |
| `stun.duration`、`electrocute.duration` | INCREASED | 暈眩、電擊的持續時間 |
| `ailment.duration` | INCREASED | 全部異常狀態的持續時間（含流血、中毒、暈眩、電擊） |
| `flag.no_elemental_ailments` | FLAT 1 | 不能造成元素異常（純化輔助） |
| `ailment.threshold` | INCREASED | 自己的元素異常門檻（越高越不容易被點燃、冰緩、凍結、感電、電擊；不影響暈眩） |
| `avoid.ignite`、`avoid.chill`、`avoid.freeze`、`avoid.shock`、`avoid.bleed`、`avoid.poison`、`avoid.stun`、`avoid.electrocute` | FLAT | 避免被點燃／冰緩／凍結／感電／流血／中毒／暈眩／電擊的機率（%，每種最多 75%；要免疫用下面的開關） |
| `flag.freeze_immune` | FLAT 1 | 無法被凍結（傳奇「永凍之冠」「霜嚙」；避免有上限，免疫用這個） |
| `ailment.self_duration` | INCREASED | 自己身上的元素異常持續時間（負的 = 縮短；最多縮到基礎的 10%，/stats 會寫） |

## 昇華、分身獎勵與稱號的屬性

ascendancy.yml 的昇華天賦與分身獎勵、bounties.yml／events.yml 的稱號加成只能用下面這些（寫法遊戲真的會讀的才收；說明文字由程式照屬性產生，跟天賦樹同一種寫法）。
`type` 不寫是 FLAT；INCREASED／MORE 的 `value` 寫百分比（30 = 30%）。`tags` 只有傷害、攻擊速度、施法速度、暴擊率、暴擊傷害能加。

| 屬性 | 型態 | 意思 |
| --- | --- | --- |
| `life.max`、`mana.max`、`es.max`、`armour`、`evasion` | FLAT／INCREASED／MORE | 最大生命、魔力、能量護盾、護甲、閃避 |
| `life.regen` | FLAT | 每秒回復多少生命 |
| `mana.regen` | FLAT／INCREASED | 每秒回復多少魔力／增加魔力回復 |
| `block.chance` | FLAT | 格擋率（%，最多 75%：堆再多也只擋 75%） |
| `resist.fire`、`resist.cold`、`resist.lightning`、`resist.chaos` | FLAT | 抗性（%；火冰電一樣多時說明寫成「所有元素抗性」） |
| `resist.max` | FLAT | 所有抗性上限（%） |
| `damage` | INCREASED／MORE | 傷害；`tags` 決定哪一種（melee、projectile、area、strike、slam、attack、spell、physical、elemental、fire、cold、lightning、chaos、武器種類） |
| `attack.speed`、`cast.speed` | INCREASED／MORE | 攻擊速度、施法速度（加武器標籤 = 「使用弓時」） |
| `crit.chance` | INCREASED | 暴擊率 |
| `crit.multi` | FLAT | 暴擊傷害（%；基礎 150%） |
| `accuracy` | FLAT／INCREASED | 命中值 |
| `movement.speed` | INCREASED | 移動速度 |
| `drop.rarity` | INCREASED | 物品掉落稀有度（擊殺的人吃得到） |
| `attr.str`、`attr.dex`、`attr.int` | FLAT | 力量、敏捷、智慧 |
| `flask.life_recovery`、`flask.mana_recovery` | INCREASED | 生命／魔力藥劑回復 |
| `skill.area`、`skill.projectile_speed`、`skill.cost` | INCREASED／MORE | 範圍效果、投射物速度、魔力消耗（負的 = 減少） |
| `skill.projectiles`、`skill.pierce`、`skill.chains` | FLAT | 額外的投射物、投射物多穿透幾個敵人、多連鎖幾次 |
| `skill.duration`、`skill.cooldown` | INCREASED／MORE | 持續時間（地面效果、漩渦、印記）、冷卻時間（負的 = 更短）；`tags` 照技能標籤 |
| `leech.life`、`leech.mana` | FLAT | 造成的物理傷害有 x% 偷取為生命／魔力 |
| `es.recharge` | INCREASED／MORE | 能量護盾回充速度 |
| `es.delay` | INCREASED | 能量護盾開始回充前的等待（負的 = 縮短，最短剩兩成） |
| `damage.gain.fire`、`damage.gain.cold`、`damage.gain.lightning`、`damage.gain.chaos` | FLAT | 物理傷害的 x% 額外附加為那種傷害 |
| 上一節的元素異常屬性（`flag.no_elemental_ailments` 除外） | 同上一節 | 點燃、冰緩、凍結、感電的機率、效果、持續時間、門檻、避免機率 |
| 下一節的特殊效果 | FLAT | +技能等級、雙倍傷害、擊中／擊殺回復、疾速、爆炸、心靈勝過物質、回復 |

## 裝備詞綴的屬性（續戰、能量護盾、荊棘、球、武器本身）

詞綴（affixes/*.yml）除了上面那些，還可以用下面這些；寫法跟其他詞綴一樣（`{value}` 是擲出來的數字）：

| 屬性 | 型態 | 意思 |
| --- | --- | --- |
| `leech.life`、`leech.mana` | FLAT | 造成的物理傷害有 x% 偷取為生命／魔力（先存起來再慢慢回）；`tags: [attack]` = 只算攻擊 |
| `life.on_hit` | FLAT | 每次擊中回復 x 生命（`tags: [attack]`） |
| `life.on_kill`、`mana.on_kill`、`es.on_kill` | FLAT | 擊殺時回復最大生命／魔力／能量護盾的 x%（用 `{value/10}` 寫小數） |
| `life.regen_percent` | FLAT | 每秒回復最大生命的 x%（`tags: [minion]` = 給召喚物） |
| `es.recharge` | INCREASED／MORE | 能量護盾回充速度（基礎：被打後 2 秒開始，每秒回最大值的三分之一） |
| `es.delay` | INCREASED | 開始回充前的等待（`"-{value}"` = 縮短 x%，最短剩兩成） |
| `thorns.min`、`thorns.max` | FLAT | 荊棘：被近戰攻擊擊中時對攻擊者反彈 x 到 y 物理傷害（吃增加物理傷害、`[thorns]` 的增加；不吃附加傷害、不會暴擊；遠程與法術不會觸發） |
| `charge.chance.代號` | FLAT | 觸發時（charges.yml 的 trigger）x% 機率獲得一顆那種球 |
| `charge.on_hit.代號` | FLAT | 擊中敵人時 x% 機率獲得一顆那種球（每打中一個敵人擲一次；召喚物、荊棘、爆炸不算；輔助寶石給的只對那個技能；可以加 melee、attack 這種技能標籤） |
| `charge.max.代號` | FLAT | 那種球的上限 +x |
| `charge.duration` | INCREASED | 全部球的持續時間 |
| `skill.level` | FLAT | +x 技能等級（`tags` 照技能標籤：`[spell]`、`[attack, melee]`、`[projectile]`、`[minion]`、`[fire]`…） |
| `skill.area`、`skill.projectile_speed`、`skill.duration` | INCREASED | 範圍、投射物速度、持續時間 |
| `skill.cost`、`aura.reservation` | INCREASED | 魔力消耗、光環保留（`"-{value}"` = 減少） |
| `skill.pierce`、`skill.chains`、`skill.totems`、`warcry.exerts` | FLAT | 多穿透、多連鎖、多一個圖騰、戰吼多強化幾次攻擊 |
| `damage.gain.類型` | FLAT | 物理傷害額外附加 x% 為那種傷害（fire、cold、lightning、chaos） |
| `flask.life_recovery`、`flask.mana_recovery` | INCREASED | 生命／魔力藥劑回復量 |
| `crit.chance`、`crit.multi` | INCREASED／FLAT | 暴擊率、暴擊傷害：不寫 `tags` 是通用的（攻擊、法術都吃）；`tags: [spell]` 只算法術、`tags: [attack]` 只算攻擊（施法武器、項鍊、珠寶的專用暴擊詞綴）。飾品上的催化劑：法術的算法術類，其他的算攻擊類 |
| `local.physical`、`local.attack_speed`、`local.crit` | INCREASED | **武器本身**：只改這把武器的物理傷害、每秒攻擊次數、基礎暴擊率（武器面板的數字變藍），跟角色的全域增加相乘；只能出在武器上（載入時會檢查），未鑑定不算 |

小數：tiers 的範圍寫整數，text 與 mods 都寫 `{value/10}`（或 `/100`），擲出 12 就顯示 1.2%、進引擎也是 1.2。

## 特殊效果的屬性（傳奇裝備；昇華、天賦也能用）

一律用 FLAT，數值是點數或百分比點數。角色面板的「攻擊」和 `/stats` 最下面會列出身上有的特殊效果。

| 屬性 | 意思 |
| --- | --- |
| `skill.level` | +x 技能等級。`tags` 照技能自己的標籤：`[spell]` 法術、`[chaos]` 混沌、`[spell, fire]` 火焰法術，不寫 = 全部技能（普攻不吃；武器標籤沒用）。可以超過 20 級，最高 40 級：法術每多一級基礎傷害多 `skills.yml` 的 `extra_level_damage`（預設 10%），其他數值照 1 → 20 級的趨勢往上加 |
| `damage.double` | 擊中時 x% 機率造成雙倍傷害（暴擊之後再乘；`tags` 照技能標籤） |
| `life.on_hit` | 每次擊中敵人回復 x 生命（`tags: [attack]` = 只有攻擊） |
| `life.on_kill`、`mana.on_kill`、`es.on_kill` | 擊殺時回復最大生命／魔力／能量護盾的 x% |
| `onslaught.on_kill` | 擊殺時 x% 機率獲得疾速：4 秒內增加 20% 攻擊、施法與移動速度 |
| `kill.explode` | 你殺死的敵人爆炸，對半徑 3 格內的怪物造成牠最大生命 x% 的火焰傷害（吃增加火焰、範圍傷害與暴擊；被炸死的也會爆，一個 tick 炸一圈） |
| `damage.from_mana` | 受到的傷害（扣生命的部分）有 x% 先從魔力扣（PoE 的心靈勝過物質；魔力不夠的照扣生命） |
| `life.recoup` | 受到的傷害（含扣護盾、扣魔力的）有 x% 之後慢慢回復為生命（存進生命偷取一起回，每秒最多 10% 最大生命；生命滿了剩下的就沒了） |
| `steal.mods` | 殺死稀有怪物時，獲得牠的詞綴 x 秒（追逐級傳奇「逐星者」）：詞綴的 stats 直接變成你的增益，「附加 X% 物理為某元素」變成額外獲得，極速的變成增加移動速度，光環詞綴給光環裡的數值；同一種詞綴再殺一隻重新計時 |
| `flag.chaos_immune`、`flag.always_hit`、`flag.iron_reflexes` | 基石的效果（FLAT 1）：混沌免疫（最大生命 1）、攻擊必定命中、閃避全部轉為護甲 |

天賦樹是 `tools/gen_passives.py` 產生的（`python tools/gen_passives.py src/main/resources/data/passives/tree.json`，加 `--map` 印出地圖），要大改版面請改產生器（既有節點的位置或檔案順序變了就把 `VERSION` 加一：舊角色會全部退點、舊的規劃代碼不能用；只在檔案最後面加節點——像珠寶孔那樣——不用加）；小改（數值、名稱）直接改 JSON 即可。
珠寶孔（`type: socket`，18 個）本身沒有 stats：配了之後玩家在遊戲裡把珠寶放進去，珠寶的詞綴加在角色身上；退掉孔位、匯入規劃、樹改版時珠寶會自動拿回背包。
改完 `/ec reload` 後，連不到起點的舊配點會在玩家下次載入時自動退回。

設定檔寫錯時啟動會失敗並在主控台指出檔名與欄位。
