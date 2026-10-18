package tw.exilecore.gui;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerSettings;
import tw.exilecore.character.PlayerState;

import java.util.List;

/**
 * 個人設定（選單的「設定」）。2026-10 改成統一版面：【畫面】傷害數字、受傷數字、戰鬥數據、任務追蹤；
 * 【其他】交易邀請。開著的名字前面「✔」（套用資源包的人看到綠框），關著的「✘」（紅框）。存在角色進度裡。
 */
public final class SettingsMenu extends Menu {

    private static final int ROW_SCREEN = 1;
    private static final int ROW_OTHER = 2;
    private static final int SLOT_DAMAGE = 10;
    private static final int SLOT_TAKEN = 11;
    private static final int SLOT_COMBAT = 12;
    private static final int SLOT_TRACKER = 13;
    private static final int SLOT_OTHERS_FX = 14;
    /** 戰鬥數據的技能明細（2026-10；預設關）。 */
    private static final int SLOT_COMBAT_SKILLS = 15;
    /** 自己的技能音效（2026-10）。 */
    private static final int SLOT_SKILL_SOUNDS = 16;
    private static final int SLOT_TRADE = 19;
    /** 網頁角色頁公不公開（2026-10）。 */
    private static final int SLOT_PROFILE = 20;
    /** 撿到的星圖直接進倉庫的地圖頁（2026-10；預設關）。 */
    private static final int SLOT_MAP_STASH = 21;
    /** 畫面左邊的隊伍框（2026-10-18）。 */
    private static final int SLOT_PARTY_FRAMES = 22;
    /** 按鍵（2026-10）：生命、魔力藥劑各放 1–8 的哪一格。 */
    private static final int ROW_KEYS = 3;
    private static final int SLOT_LIFE_FLASK = 28;
    private static final int SLOT_MANA_FLASK = 29;
    private static final int SLOT_LAYOUT_RESET = 30;
    private static final int SLOT_CLOSE = SLOT_CLOSE_BAR;

    public SettingsMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 6, "<dark_gray>設定");
    }

    @Override
    protected void draw() {
        frame();
        final PlayerState state = plugin.players().get(player);
        set(slot(ROW_SCREEN, 0), rowLabel(Material.SPYGLASS, "畫面", "畫面上顯示什麼"));
        set(SLOT_DAMAGE, toggle(Material.IRON_SWORD, "傷害數字", PlayerSettings.enabled(state, PlayerSettings.DAMAGE_NUMBERS),
                "打中怪物時在它頭上冒出數字（只有你看得到）"));
        set(SLOT_TAKEN, toggle(Material.SHIELD, "受傷數字", PlayerSettings.enabled(state, PlayerSettings.DAMAGE_TAKEN),
                "被打到時眼前冒出「-123 火焰」：數字與屬性（物理白、火紅、冰藍、雷黃、混沌紫），閃掉、格擋也會寫"));
        set(SLOT_COMBAT, toggle(Material.CLOCK, "戰鬥數據", PlayerSettings.enabled(state, PlayerSettings.COMBAT_METER),
                "打怪時畫面右邊顯示：每秒打出多少傷害、每秒受到多少、各是什麼類型、受到的最大一擊（停手 5 秒收起來）"));
        set(SLOT_TRACKER, toggle(Material.WRITABLE_BOOK, "任務追蹤", PlayerSettings.enabled(state, PlayerSettings.QUEST_TRACKER),
                "畫面右邊列出進行中的任務與現在這一步，還有每日委託、每月活動的進度"));
        set(SLOT_OTHERS_FX, toggle(Material.BLAZE_POWDER, "別人的技能特效", PlayerSettings.enabled(state, PlayerSettings.OTHERS_FX),
                "看得到別的玩家放技能的粒子、圖騰與陷阱、召喚物，也聽得到他們技能的聲音（人多時關掉比較不眼花、不吵、也比較不卡）；"
                        + "自己的技能與召喚物、Boss 的警告圈、時裝照常；競技場裡一定看得到"));
        set(SLOT_SKILL_SOUNDS, toggle(Material.NOTE_BLOCK, "技能音效", PlayerSettings.enabled(state, PlayerSettings.SKILL_SOUNDS),
                "自己放技能的聲音（施放、命中、爆炸）。關掉就完全靜音；別人的技能本來就只有三分之一的音量，"
                        + "要完全不聽把「別人的技能特效」關掉。怪物與 Boss 的聲音不受影響"));
        set(SLOT_COMBAT_SKILLS, toggle(Material.COMPARATOR, "戰鬥數據：技能明細", PlayerSettings.on(state, PlayerSettings.COMBAT_SKILLS),
                "戰鬥數據改成列出每個技能：每秒打多少、佔幾成、各是什麼傷害類型（最多 4 個技能；召喚物算在召喚技能上，"
                        + "點燃、流血、中毒各算一項）。整場的統計隨時可以打 /dps 看"));
        // 2026-10：介面資源包的開關拿掉了（登入時一律詢問，要不要套用由客戶端決定）
        set(slot(ROW_OTHER, 0), rowLabel(Material.EMERALD, "其他", "跟別的玩家有關的"));
        set(SLOT_TRADE, toggle(Material.EMERALD, "交易邀請", PlayerSettings.enabled(state, PlayerSettings.TRADE_REQUESTS),
                "別人可以邀請你面對面交易（/trade）"));
        if (plugin.stash() != null) {
            set(SLOT_MAP_STASH, toggle(Material.FILLED_MAP, "星圖直接進倉庫", PlayerSettings.on(state, PlayerSettings.MAP_TO_STASH),
                    "撿到的星圖直接收進倉庫的地圖頁，不佔背包（地圖頁滿了才放背包）；"
                            + "地圖裝置可以直接從地圖頁挑地圖"));
        }
        set(SLOT_PARTY_FRAMES, toggle(Material.PLAYER_HEAD, "隊伍框", PlayerSettings.enabled(state, PlayerSettings.PARTY_FRAMES),
                "組隊時畫面左上角列出隊友：頭像、名字、等級、生命（護盾是血條上面薄薄的一層）、魔力；"
                        + "死了名字變紅、離線變灰（要套用介面資源包）"));
        final tw.exilecore.web.Profiles profiles = plugin.web() == null ? null : plugin.web().profiles();
        if (profiles != null && state.hasCharacter()) {
            set(SLOT_PROFILE, toggle(Material.FILLED_MAP, "網頁角色頁公開", profiles.isPublic(player),
                    "別人可以在網頁上看到這個角色的裝備、天賦、技能與屬性（角色面板有連結）"));
        }
        if (state.hasCharacter() && plugin.flasks() != null) {
            final tw.exilecore.character.HotbarLayout layout = tw.exilecore.character.HotbarLayout.of(state);
            final int[] flasks = plugin.flasks().slots(player);
            set(slot(ROW_KEYS, 0), rowLabel(Material.LEVER, "按鍵", "快捷列怎麼排：藥劑放哪兩格，剩下的是技能鍵"));
            final String now = "<gray>現在：生命 <white>" + (flasks[0] + 1) + "</white>、魔力 <white>" + (flasks[1] + 1)
                    + "</white>，技能鍵 <white>" + layout.skillKeysText() + "</white>，<white>9</white> 是武器";
            set(SLOT_LIFE_FLASK, flaskButton(Material.RED_DYE, "<#ff6b6b>生命藥劑：數字鍵 " + (flasks[0] + 1), now));
            set(SLOT_MANA_FLASK, flaskButton(Material.LAPIS_LAZULI, "<#6bb5ff>魔力藥劑：數字鍵 " + (flasks[1] + 1), now));
            if (layout.custom()) {
                set(SLOT_LAYOUT_RESET, icon(Material.BARRIER, "<gray>恢復預設", "<gray>藥劑放 1、2，技能鍵 3–8", "", "<yellow>左鍵：恢復"));
            }
        }
        // 2026-10：左下角的「回選單」跟右下角的「← 返回選單」重複，拿掉
        set(SLOT_HELP, helpButton(List.of("每個角色自己的設定，點一下切換", "<dark_gray>開著：名字前面 ✔　關著：名字前面 ✘")));
        set(SLOT_CLOSE, closeButton());
    }

    private ItemStack toggle(final Material material, final String name, final boolean on, final String description) {
        final ItemStack icon = icon(material, (on ? "<green>✔ " : "<red>✘ ") + name + "：" + (on ? "開" : "關"),
                "<gray>" + description, "", "<yellow>左鍵：" + (on ? "關掉" : "打開"));
        return on ? onMark(icon) : offMark(icon);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_DAMAGE -> flip(PlayerSettings.DAMAGE_NUMBERS);
            case SLOT_TAKEN -> flip(PlayerSettings.DAMAGE_TAKEN);
            case SLOT_TRADE -> flip(PlayerSettings.TRADE_REQUESTS);
            case SLOT_PROFILE -> {
                final tw.exilecore.web.Profiles profiles = plugin.web() == null ? null : plugin.web().profiles();
                if (profiles != null && plugin.players().get(player).hasCharacter()) {
                    final boolean open = profiles.setPublic(player, !profiles.isPublic(player));
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, open ? 1.4f : 0.8f);
                    redraw();
                }
            }
            case SLOT_COMBAT -> flip(PlayerSettings.COMBAT_METER);
            case SLOT_PARTY_FRAMES -> flip(PlayerSettings.PARTY_FRAMES);
            case SLOT_SKILL_SOUNDS -> flip(PlayerSettings.SKILL_SOUNDS);
            case SLOT_COMBAT_SKILLS -> flipOff(PlayerSettings.COMBAT_SKILLS);
            case SLOT_MAP_STASH -> {
                if (plugin.stash() != null) {
                    flipOff(PlayerSettings.MAP_TO_STASH);
                }
            }
            case SLOT_OTHERS_FX -> {
                if (flip(PlayerSettings.OTHERS_FX) && plugin.skills() != null) {
                    plugin.skills().refreshDeployableVisibility(player); // 圖騰、陷阱馬上藏起來／顯示
                }
            }
            case SLOT_TRACKER -> {
                if (flip(PlayerSettings.QUEST_TRACKER) && plugin.quests() != null) {
                    plugin.quests().updateTracker(player);
                }
            }
            case SLOT_LIFE_FLASK -> moveFlask(0, click);
            case SLOT_MANA_FLASK -> moveFlask(1, click);
            case SLOT_LAYOUT_RESET -> switchLayout(tw.exilecore.character.HotbarLayout.CLASSIC);
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }

    /** 預設關的設定：沒設定過算關。 */
    private void flipOff(final String key) {
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return;
        }
        final boolean on = PlayerSettings.on(state, key);
        plugin.characters().setProgress(player, key, on ? "0" : "1");
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, on ? 0.8f : 1.4f);
        redraw();
    }

    private ItemStack flaskButton(final Material material, final String name, final String now) {
        return icon(material, name, now, "",
                "<dark_gray>1–8 任選一格（9 是武器）；剩下的 6 格由左到右是技能鍵，",
                "<dark_gray>技能綁定照「第幾個技能鍵」跟著換，不用重綁；藥劑會自動搬過去",
                "<dark_gray>只能在營地換",
                "", "<yellow>左鍵：往右一格　右鍵：往左一格");
    }

    /** 生命（which 0）或魔力（1）藥劑往右／往左移一格（跳過另一種藥劑那一格）。 */
    private void moveFlask(final int which, final ClickType click) {
        if (plugin.flasks() == null) {
            return;
        }
        final int[] slots = plugin.flasks().slots(player);
        final int step = click.isRightClick() ? -1 : 1;
        int target = slots[which];
        do {
            target = Math.floorMod(target + step, tw.exilecore.character.HotbarLayout.SLOTS);
        } while (target == slots[1 - which]);
        slots[which] = target;
        switchLayout(tw.exilecore.character.HotbarLayout.custom(slots[0], slots[1]));
    }

    private void switchLayout(final tw.exilecore.character.HotbarLayout target) {
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter() || plugin.flasks() == null) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return;
        }
        if (plugin.skills() != null && plugin.skills().eligible(player) && !plugin.skills().inTown(player)) {
            player.sendMessage(plugin.text().prefixed("<red>回營地再換（戰鬥區域裡快捷列擺著技能圖示）"));
            return;
        }
        if (plugin.flasks().switchLayout(player, target)) {
            final tw.exilecore.character.HotbarLayout layout = tw.exilecore.character.HotbarLayout.of(state);
            player.sendMessage(plugin.text().prefixed("<#ffd27f>快捷列換好了：<white>藥劑 " + plugin.flasks().slotText(player)
                    + "，技能鍵 " + layout.skillKeysText() + "，9 是武器"));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        }
        redraw();
    }

    /** @return 有沒有切換（還沒選角色時不能切） */
    private boolean flip(final String key) {
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return false;
        }
        final boolean on = PlayerSettings.enabled(state, key);
        plugin.characters().setProgress(player, key, on ? "0" : "1");
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, on ? 0.8f : 1.4f);
        redraw();
        return true;
    }
}
