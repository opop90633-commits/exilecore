package tw.exilecore.stats;

/**
 * 2026-11-01 照站長「這些輔助的機制跟原作不一樣（用近似的），照原作重做」：重做的輔助用到的屬性鍵與原作常數
 * （不放進 {@link StatKeys}／{@link SupportExtraKeys}，方便跟同時在改別顆輔助的分支合併）。
 * 機制在 {@code tw.exilecore.skill.SupportRemakes}（純邏輯）與各個掛勾的地方（寫在每個鍵的說明裡）。
 */
public final class SupportRemakeKeys {

    private SupportRemakeKeys() {
    }

    /** MORE：分叉出來的投射物的傷害（PoE2 Fork：support_fork_forked_projectile_damage_+%_final −30；ProjectileEngine.fork）。 */
    public static final String FORKED_DAMAGE = "projectile.forked_damage";

    /** MORE：對 {@link #OVERREACH_METRES} 公尺內的目標的命中（PoE2 Overreach：support_reach_accuracy_within_2m_+%_final −80；CombatListener）。 */
    public static final String ACCURACY_NEARBY = "accuracy.within_2m";
    public static final double OVERREACH_METRES = 2.0;

    /** FLAT 旗標：造成重暈時讓敵人完全破甲（PoE2 Devastate；SupportProcs 收到 stun_applied 時）。 */
    public static final String FULL_BREAK_ON_HEAVY_STUN = "armour.full_break_on_heavy_stun";

    /**
     * PoE2 Tremors：30% 機率多 1 次、15% 機率多 2 次、10% 機率多 3 次餘震（互斥，合計 55%）。supports.yml 寫
     * {@code proc.use 55 [proc_tremor]}，SupportProcs 照這三段擲一次決定幾次（{@code SupportRemakes.tremors}）。
     */
    public static final String TREMOR_PROC = "proc_tremor";
    public static final double TREMOR_ONE = 30;
    public static final double TREMOR_TWO = 15;
    public static final double TREMOR_THREE = 10;
    /** 多次餘震之間隔幾 tick（跟 SkillExecutor 的鑄父之錘餘震一樣 0.5 秒）。 */
    public static final long TREMOR_GAP_TICKS = 10;

    /**
     * FLAT %：PoE2 Reverberate（chance_to_aftershock_+%_per_250_ms_attack_time）：有餘震機率的技能，攻擊時間每 0.25 秒，
     * 餘震機率增加 x%（只放大既有的機率，本身不給餘震）。SupportProcs（餘震、連震）、SkillExecutor.extraAftershocks（天賦、品質、傳奇的機率）讀。
     */
    public static final String AFTERSHOCK_PER_QUARTER = "slam.aftershock_chance_per_250ms";
    /** 會被 Reverberate 放大的「機率餘震」隱藏技能（輔助觸發的）。 */
    public static final java.util.Set<String> AFTERSHOCK_PROCS = java.util.Set.of("proc_aftershock", TREMOR_PROC);

    /** FLAT %：PoE2 Ruthless：每第 {@link #RUTHLESS_EVERY} 次使用是重擊，那一次暈眩傷害更多 x%（SupportExtras.beforeUse）。 */
    public static final String RUTHLESS = "stun.ruthless_more";
    public static final int RUTHLESS_EVERY = 5;

    /** FLAT 旗標：PoE2 Hulking Minions：召喚物變巨型（體型大 20%；LineageMechanics.minionModifiers）。只能掛持續型召喚（SupportGuards）。 */
    public static final String MINIONS_GIGANTIC = "minion.gigantic";

    /** PoE2 Caltrops 觸發的隱藏技能（supports.yml 寫 proc.projectile_end；SupportGuards 擋「要長矛的投射物攻擊」）。 */
    public static final String CALTROPS_PROC = "proc_caltrops";
    /** 投射物飛行結束的觸發事件（ProjectileEngine.stopEffects → SkillService.procEvent）。 */
    public static final String EVENT_PROJECTILE_END = "projectile_end";
    /** 詛咒落到每一個敵人身上的觸發事件（SkillExecutor.applyCurseAt；PoE2 Decaying Hex「對每個被詛咒的目標」）。 */
    public static final String EVENT_CURSE_APPLIED = "curse_applied";
    /** 讓敵人感電的觸發事件（AilmentService；PoE2 Coursing Current、Enervation「Shocking an enemy」）。 */
    public static final String EVENT_SHOCK = "shock";
    /** 不受觸發冷卻限制的事件（每個目標、每一發各觸發一次）。 */
    public static final java.util.Set<String> UNCAPPED_EVENTS = java.util.Set.of(EVENT_CURSE_APPLIED, EVENT_PROJECTILE_END);

    /** Decaying Hex 觸發的隱藏技能（凋朽）：直接在被詛咒的敵人身上掛混沌持續傷害的減益（SupportProcs）。 */
    public static final String DECAY_PROC = "proc_decaying_hex";

    /** Coursing Current 觸發的隱藏技能：從感電的敵人出發，只連到附近濕透的敵人（SupportProcs、SkillExecutor.chainFrom）。 */
    public static final String COURSING_PROC = "proc_coursing_current";

    /**
     * PoE2 Crystalline Shards：被輔助的技能留下的冰晶爆炸 100% 穿刺敵人（冰冷傷害也能穿刺；這一下不會抽出穿刺）。supports.yml 照舊寫
     * {@link StatKeys#FLAG_CRYSTAL_SHARDS}；IceCrystals 碎掉時在爆炸的技能描述加這個記號，打中時存穿刺。
     */
    public static final String CRYSTAL_IMPALE_MARK = "crystal.impale_burst";
}
