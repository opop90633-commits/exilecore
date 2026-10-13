package tw.exilecore.core.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /ec help（2026-10）：所有指令與功能的一覽，分類列出。新增指令時在這裡加一行
 * （單元測試 HelpCoverage 會檢查 /ec 底下的每個子指令都有寫到）。
 * <pre>
 * /ec help            分類目錄（遊戲裡可以點；主控台直接全部印出來）
 * /ec help 分類        那一類的指令
 * /ec help all        全部
 * </pre>
 * 用法裡 &lt;…&gt; 是一定要填的，[…] 是可以不填的。
 */
public final class CommandHelp {

    record Entry(String usage, String about) {
    }

    record Group(String id, String title, List<Entry> entries) {
    }

    private static Entry e(final String usage, final String about) {
        return new Entry(usage, about);
    }

    static final List<Group> GROUPS = List.of(
            new Group("basic", "基本", List.of(
                    e("/ec help [分類|all]", "這份指令說明"),
                    e("/ec ping", "伺服器是否活著、TPS 與每 tick 毫秒"),
                    e("/ec version", "插件、Paper、Java 版本與已啟用的模組"),
                    e("/ec db", "資料庫連線狀態與來回時間"),
                    e("/ec reload", "重新載入 config.yml 與資料檔（data/ 底下）"),
                    e("/ec maintenance", "維護模式現在開著沒有（主控台也能用）"),
                    e("/ec maintenance on [說明]", "開維護模式：只有管理員進得來，在線的玩家請出去；說明會顯示在伺服器列表與踢出畫面"),
                    e("/ec maintenance off", "關掉維護模式"),
                    e("/ec data [status]", "資料檔體檢：跟預設一樣、改過、有 .new 還沒合併、缺了新版的哪些鍵"),
                    e("/ec data diff <檔>", "一個資料檔缺的鍵全部列出來"),
                    e("/ec data accept <檔|all>", "把 .new 換上來（你的那份改名 .bak 留著），之後 /ec reload"),
                    e("/ec save", "立刻存檔（所有在線玩家）"),
                    e("/ec debug", "切換命中除錯訊息（傷害、經驗）"))),
            new Group("item", "物品", List.of(
                    e("/ec give <基底> [稀有度] [物品等級]", "給自己一件裝備（normal／magic／rare）"),
                    e("/ec giveunique <傳奇> [物品等級]", "給自己一件傳奇"),
                    e("/ec givecurrency <通貨> [數量]", "給自己通貨"),
                    e("/ec identify", "鑑定手上的物品"),
                    e("/ec affix <詞綴> [階級]", "把一條詞綴加到手上的物品（同群組的換掉；沒寫階級 = 最強的一階）"),
                    e("/ec item", "印出手上物品的資料"),
                    e("/ec gem uncut <等級> [數量]", "未切割技能寶石"),
                    e("/ec gem uncutsupport <階級> [數量]", "未切割輔助寶石"),
                    e("/ec gem skill <技能> [等級]", "技能寶石"),
                    e("/ec gem support <輔助> [數量]", "輔助寶石"),
                    e("/ec flask list", "所有藥劑基底"),
                    e("/ec flask give <基底>", "給自己一瓶藥劑（滿充能）"),
                    e("/ec flask refill", "背包裡的藥劑補滿"),
                    e("/ec refreshitems", "把離線角色與倉庫的物品照新版資料補正（傳奇改詞綴、畫法改版後用）"),
                    e("/ec inv <玩家>", "打開在線玩家的背包看（只能看）"))),
            new Group("char", "角色", List.of(
                    e("/ec level <等級> [玩家]", "設定角色等級（經驗歸零）"),
                    e("/ec addlevel <級數> <玩家>", "讓某個玩家升幾級"),
                    e("/ec xp <經驗> [玩家]", "給經驗（會升級）"),
                    e("/ec points <點數>", "給目前角色額外天賦點（負數扣）"),
                    e("/ec refund <次數>", "給目前角色免費退點次數"),
                    e("/ec gold <金幣>", "給自己的帳號金幣（負數扣；所有角色共用）"),
                    e("/ec resetpassives", "把目前角色的天賦全部退掉"),
                    e("/ec setclass <職業> [玩家]", "換職業"),
                    e("/ec stats [玩家]", "看自己或在線玩家的屬性表"),
                    e("/ec skill list", "列出所有技能與代號"),
                    e("/ec skill give <技能> [等級]", "加到自己的技能面板（已經有就改等級）"),
                    e("/ec skill remove <技能>", "從自己的技能面板移除"),
                    e("/ec skill reset", "清空自己的技能面板（下次載入會再送起始技能）"))),
            new Group("progress", "進度", List.of(
                    e("/ec quest list", "自己每個任務的狀態（改別人的：/execute as 玩家 run ec quest …）"),
                    e("/ec quest start <任務>", "直接開始任務（不管前置）"),
                    e("/ec quest stage <任務> <第幾步>", "跳到第幾步"),
                    e("/ec quest complete <任務> [選項]", "直接完成任務"),
                    e("/ec quest reset <任務|all>", "清掉任務（變回沒開始過）"),
                    e("/ec bounty", "自己的委託與功勳"),
                    e("/ec bounty reroll", "今天的委託重抽"),
                    e("/ec bounty complete <1-6>", "直接完成第幾個委託（給獎勵）"),
                    e("/ec bounty weekly", "這週的回響變回沒完成"),
                    e("/ec bounty merits <數量>", "給功勳（負數扣）；merits set <數量> 直接設定"),
                    e("/ec bounty title <代號>", "直接給一個稱號"),
                    e("/ec bounty untitle <代號>", "拿掉一個稱號"),
                    e("/ec ascend", "自己的昇華狀態"),
                    e("/ec ascend trial <試煉>", "直接完成一個試煉（給昇華點）"),
                    e("/ec ascend untrial <試煉>", "試煉變回沒完成"),
                    e("/ec ascend reset", "清掉昇華職業與配點（可以重選；試煉不動）"),
                    e("/ec ascend wipe", "昇華連試煉一起清掉"),
                    e("/ec map give <地圖> [稀有度] [階級]", "給自己一張地圖（T1–T16）"),
                    e("/ec map open <地圖|final> [階級]", "不用物品直接開一張地圖"),
                    e("/ec map list", "開著的地圖實例"),
                    e("/ec map tp <編號>", "直接進入地圖實例"),
                    e("/ec map close <編號|all>", "關閉地圖實例（裡面的人送回營地）"),
                    e("/ec map complete <地圖|all>", "把自己的星圖標成完成"),
                    e("/ec map reset", "清掉自己的星圖進度（含星圖天賦）"),
                    e("/ec map best <階級>", "設定自己完成過的最高階（0 = 清掉）"),
                    e("/ec map fragments [數量]", "給自己天穹碎片"),
                    e("/ec map uber <終局>", "直接開那個終局的極境"),
                    e("/ec map cores [數量]", "給自己開極境的鑰匙"))),
            new Group("world", "世界與區域", List.of(
                    e("/ec world", "劇情世界與地圖世界的狀態"),
                    e("/ec world tp", "傳送到劇情世界的營地"),
                    e("/ec world device", "傳送到地圖裝置前面"),
                    e("/ec world setup", "重新擺區域版面與營地 NPC（舊版面先備份）"),
                    e("/ec zone list", "列出區域與版面狀態"),
                    e("/ec zone here", "你在哪個區域、怪物等級、防刷掉落倍率"),
                    e("/ec zone fatigue reset", "清掉你的防刷狀態"),
                    e("/ec zone pos1 | pos2", "選取範圍的兩個角落（腳下的方塊）"),
                    e("/ec zone define <區域>", "用 pos1、pos2 設定區域範圍"),
                    e("/ec zone entry <區域>", "用你的位置當區域落點"),
                    e("/ec zone arena <區域>", "用你的位置當 Boss 場地中心"),
                    e("/ec zone checkpoint add <區域> <代號> [名稱]", "在你的位置放檢查點"),
                    e("/ec zone checkpoint remove <區域> <代號>", "移除檢查點"),
                    e("/ec zone tp <區域> [檢查點]", "傳送到區域"),
                    e("/ec zone boss spawn <區域>", "立刻生成（或重新生成）區域 Boss"),
                    e("/ec zone boss reset <區域>", "移除 Boss、清掉重生冷卻"),
                    e("/ec zone clear", "移除所有區域刷出來的怪物"),
                    e("/ec zone autolayout [confirm]", "以你為營地中心一鍵產生整套版面與 NPC"),
                    e("/ec npc spawn <種類>", "在面前放一個功能 NPC（鑑定、倉庫、拆解、傳送、地圖裝置、商人、市集）"),
                    e("/ec npc story [代號]", "列出劇情人物／在面前放一個"),
                    e("/ec npc remove", "移除身邊 5 格內最近的 NPC"),
                    e("/ec npc list", "列出載入中的 NPC"),
                    e("/ec spawn <生物> <等級> [稀有度]", "在面前生成一隻等級化的怪物"),
                    e("/ec worldevent", "不定時活動的狀態"),
                    e("/ec worldevent start <boss|invasion>", "馬上開始活動"),
                    e("/ec worldevent stop", "結束進行中的活動"),
                    e("/ec worldevent here", "用你的位置加一個活動地點"),
                    e("/ec worldevent arena [半徑]", "用你的位置設定活動場地"),
                    e("/ec worldevent clearspots", "清掉指令加的活動地點"),
                    e("/ec pvp rebuild", "重蓋競技場"),
                    e("/ec dummywatch", "打靶觀看：別人打完靶的統計也送給你"))),
            new Group("ops", "維運", List.of(
                    e("/ec perf", "效能：TPS、各部分花多少時間、資料庫排隊、實體數"),
                    e("/ec stress <數量> [稀有度] [秒]", "壓力測試：腳邊刷一圈怪、印效能；stress clear 清掉"),
                    e("/ec balance [等級]", "平衡報表：擊殺秒數、Boss、建議錨點（完整的寫到 balance-report.txt）"),
                    e("/ec economy [天數]", "交易系統與經濟報表（產出、消耗、存量、參考價值）"),
                    e("/ec economy flow <通貨|gold> [天數]", "一種東西從哪裡來、到哪裡去"),
                    e("/ec economy players [天數]", "賺最多的帳號（找機器人、複製）"),
                    e("/ec economy values", "通貨參考價值"),
                    e("/ec economy update", "馬上做每天的經濟工作"),
                    e("/ec trace <物品UUID>", "一件裝備的交易與通貨紀錄"),
                    e("/ec trace player <名字>", "一個人最近的交易"),
                    e("/ec dupes", "找同一個 UUID 出現在兩個地方的裝備"),
                    e("/ec backup", "馬上備份（資料庫＋劇情世界）"),
                    e("/ec backup list", "列出備份"),
                    e("/ec backup verify [檔名]", "檢查備份是不是好的（不動現在的資料）"),
                    e("/ec backup restore <檔名>", "【主控台】還原資料庫（踢所有人，還原完要重開）"),
                    e("/ec wipe", "【主控台】清檔預覽：清角色與進度，留帳號與贊助；給確認碼"),
                    e("/ec wipe all", "【主控台】全部清空預覽（連帳號、贊助都清；開服前清測試資料）"),
                    e("/ec wipe confirm <確認碼>", "【主控台】真的清檔：先自動備份，清完要重開"),
                    e("/ec wipe cancel", "【主控台】取消還沒確認的清檔"),
                    e("/ec restart [分鐘 [原因]]", "幾分鐘後重新啟動（倒數公告）；不寫 = 看現在排了什麼"),
                    e("/ec restart cancel", "取消排好的重新啟動"),
                    e("/ec web", "管理網頁的一次性登入連結（發物資、改設定、公告、維護模式）"),
                    e("/ec web logout", "管理網頁全部登出"),
                    e("/mute <玩家> <分鐘> [原因]", "禁言（0 分鐘 = 永久）"),
                    e("/unmute <玩家>", "解除禁言"),
                    e("/ticket panel <面板> <頻道ID>", "把客服單面板發到 Discord 頻道"))),
            new Group("guild", "公會", List.of(
                    e("/guild admin", "公會管理員指令一覽（公會寫名稱或標籤都可以）"),
                    e("/guild admin list", "全部的公會：等級、人數、會長、倉庫頁數"),
                    e("/guild admin info <公會>", "詳細資料與成員名單（階級、帳號、貢獻、加入日期、在不在線）"),
                    e("/guild admin log <公會>", "最近的公會紀錄（加入、離開、倉庫存取、設定）"),
                    e("/guild admin stash <公會>", "打開那個公會的倉庫（只能看）"),
                    e("/guild admin leader <成員>", "把會長換成這個成員（原本的會長變成幹部）"),
                    e("/guild admin promote|demote <成員>", "升成幹部、降成成員"),
                    e("/guild admin kick <成員>", "請出公會（會長要先換人）"),
                    e("/guild admin add <公會> <玩家>", "把在線的玩家直接放進公會"),
                    e("/guild admin rename <公會> <新標籤> <新名稱>", "改名稱與標籤（不用改名卡）"),
                    e("/guild admin motd <公會> [文字]", "改公告；不寫文字 = 清掉"),
                    e("/guild admin xp <數量> <公會>", "加公會經驗"),
                    e("/guild admin level <等級> <公會>", "直接設公會等級"),
                    e("/guild admin pages <頁數> <公會>", "設定星幣買的倉庫頁是幾頁（補償）"),
                    e("/guild admin disband|forcedisband <公會>", "解散（force：倉庫裡的東西一起刪）"))),
            new Group("sponsor", "贊助", List.of(
                    e("/ec sponsor give <玩家> <數量>", "發儲值星幣（離線的下次登入發；算累積贊助）"),
                    e("/ec sponsor give <玩家> <數量> bonus", "發贈送星幣（不算累積贊助）"),
                    e("/ec sponsor give <玩家> <數量> card|fashion", "發月卡幣／時裝幣"),
                    e("/ec sponsor give <玩家> cosmetic <時裝|title:稱號|bundle:組>", "發時裝、限定稱號"),
                    e("/ec sponsor revoke <玩家> <時裝|title:稱號|bundle:組>", "收回時裝、限定稱號（戴著的一起拿下；離線的下次登入收）"),
                    e("/ec sponsor info <玩家>", "在線玩家的贊助狀態"),
                    e("/ec sponsor take <玩家> <數量> [refund]", "扣星幣（refund = 退款，連累積贊助一起扣）"))),
            new Group("player", "玩家指令", List.of(
                    e("/menu", "主選單"),
                    e("/char", "角色選擇；char panel 面板、char select <格>、char rename <名稱>、char profile [on|off]"),
                    e("/panel", "角色面板（屬性、飾品）"),
                    e("/tree", "天賦樹；tree find <關鍵字>、tree web（規劃器）、tree import [代碼]"),
                    e("/stats [名字]", "文字版屬性"),
                    e("/dps", "這一段戰鬥每個技能打了多少、各是什麼傷害類型"),
                    e("/skills", "技能面板"),
                    e("/codex [關鍵字]", "圖鑑（傳奇、技能、輔助、詞綴）；寫關鍵字直接搜尋"),
                    e("/guide", "新手引導"),
                    e("/wp", "傳送點"),
                    e("/town", "回營地"),
                    e("/stash", "倉庫"),
                    e("/bag", "隨身背包"),
                    e("/filter", "掉落過濾"),
                    e("/trash", "垃圾桶"),
                    e("/lock", "鎖定手上的裝備（防止誤拆、誤賣）"),
                    e("/atlas", "星圖；atlas tree 星圖天賦"),
                    e("/spectate leave", "離開觀戰"),
                    e("/quests", "任務日誌（/q）"),
                    e("/bounty", "委託板（/daily）"),
                    e("/title", "稱號（/titles）"),
                    e("/ascend", "昇華與試煉；ascend tree、ascend choose、ascend preview、/trials"),
                    e("/party", "隊伍：invite、accept、decline、leave、kick、leader、chat；/p 隊伍聊天"),
                    e("/guild", "公會：create 標籤 名稱、invite、accept、apply 公會 [留言]（申請加入）、unapply、applications、approve｜reject 名字（審核）、leave、kick、promote、demote、leader、motd、donate、stash、info、top、rename 標籤 名稱（要公會改名卡）、disband；/g 公會頻道"),
                    e("/trade <玩家>", "面對面交易；trade accept|decline"),
                    e("/market", "市集；market shop 自己的商店、market exchange 通貨交易所、market collect 領收入"),
                    e("/claim", "領管理員發的物資"),
                    e("/ladder [level|maps|deathless|gold|guild]", "排行榜（等級、地圖、不死、金錢、公會）"),
                    e("/deaths", "死亡回顧"),
                    e("/pvp", "競技場；pvp join、leave、watch"),
                    e("/event", "每月活動"),
                    e("/sponsor", "贊助；sponsor title <文字> [#顏色]、title clear、odds"),
                    e("/emote [動作]", "動作（/em）"),
                    e("/w <玩家> <訊息>", "私訊（/msg、/tell、/whisper）；/r 回覆"),
                    e("/report <玩家> <原因>", "檢舉"),
                    e("/ticket <內容>", "開客服單；ticket close 關掉"),
                    e("/discord", "Discord 連結"),
                    e("/store", "贊助商城連結"))));

    private CommandHelp() {
    }

    static List<String> ids() {
        final List<String> ids = new ArrayList<>();
        for (final Group group : GROUPS) {
            ids.add(group.id());
        }
        ids.add("all");
        return ids;
    }

    /** /ec help [分類|all]。 */
    static void show(final ExileCore plugin, final CommandSender sender, final String topic) {
        final boolean console = !(sender instanceof Player);
        final String key = topic == null ? "" : topic.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() && !console) {
            int total = 0;
            for (final Group group : GROUPS) {
                total += group.entries().size();
            }
            sender.sendMessage(plugin.text().prefixed("<gold>指令說明</gold> <gray>（共 " + total + " 條，點分類看；/ec help all 全部）"));
            for (final Group group : GROUPS) {
                sender.sendMessage(plugin.text().parse("  <click:run_command:'/ec help " + group.id() + "'><hover:show_text:'<gray>點一下看這類指令'>"
                        + "<yellow>" + group.title() + "</yellow> <dark_gray>/ec help " + group.id() + "</dark_gray> <gray>"
                        + group.entries().size() + " 條</hover></click>"));
            }
            sender.sendMessage(plugin.text().parse("<dark_gray>\\<…> 一定要填，[…] 可以不填；【主控台】只能在伺服器主控台用"));
            return;
        }
        boolean any = false;
        for (final Group group : GROUPS) {
            if (key.isEmpty() || key.equals("all") || key.equals(group.id()) || key.equals(group.title())) {
                print(plugin, sender, group, console);
                any = true;
            }
        }
        if (!any) {
            sender.sendMessage(plugin.text().prefixed("<red>沒有這個分類：" + ItemRenderer.escape(topic) + "<gray>（有：" + String.join("、", ids()) + "）"));
        }
    }

    private static void print(final ExileCore plugin, final CommandSender sender, final Group group, final boolean console) {
        sender.sendMessage(plugin.text().parse("<gold>── " + group.title() + " ──"));
        for (final Entry entry : group.entries()) {
            final String usage = ItemRenderer.escape(entry.usage());
            final String about = ItemRenderer.escape(entry.about());
            if (console) {
                sender.sendMessage(plugin.text().parse("<white>" + usage + "</white> <gray>" + about));
            } else {
                // 點一下把指令放到聊天欄（填到第一個參數前）
                final String suggest = suggestion(entry.usage()).replace("'", "");
                sender.sendMessage(plugin.text().parse("<click:suggest_command:'" + suggest + "'><hover:show_text:'<gray>點一下填到聊天欄'>"
                        + "<white>" + usage + "</white></hover></click> <gray>" + about));
            }
        }
    }

    /** 用法裡第一個參數（&lt; 或 [）之前的部分，結尾留一個空白。 */
    static String suggestion(final String usage) {
        int cut = usage.length();
        for (final char mark : new char[] {'<', '[', '|'}) {
            final int index = usage.indexOf(mark);
            if (index >= 0 && index < cut) {
                cut = index;
            }
        }
        final String head = usage.substring(0, cut).trim();
        return cut < usage.length() ? head + " " : head;
    }

    /** 測試用：用法裡寫到的 /ec 第一層子指令。 */
    static java.util.Set<String> adminLiterals() {
        final java.util.Set<String> literals = new java.util.TreeSet<>();
        for (final Group group : GROUPS) {
            for (final Entry entry : group.entries()) {
                if (entry.usage().startsWith("/ec ")) {
                    final String rest = entry.usage().substring(4);
                    literals.add(rest.split("[ \\[<|]")[0]);
                }
            }
        }
        return literals;
    }
}
