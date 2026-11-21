package io.github.amatsukiichika.iwaram.ui.components

/**
 * 搜索框的三态 —— 「展开」与「持有焦点」是两件事。
 *
 * - [COLLAPSED]：收起，只剩那颗放大镜小球；点它才展开。
 * - [EDITING]：展开且持有焦点（软键盘在屏）。只有用户点输入框才会进入这一态。
 * - [EXPANDED_IDLE]：展开但不持有焦点；query 与结果都留着，只是不弹键盘。
 *
 * 三态是纯数据：谁在什么事件下转到哪一态只由 [reduceSearchPhase] 决定，UI 只把平台事件
 * （焦点、IME、切 tab、提交）翻译成 [SearchFocusEvent]。见
 * `docs/decisions/0035-search-focus-lifecycle.md`。
 */
enum class SearchPhase {
    COLLAPSED,
    EDITING,
    EXPANDED_IDLE
}

/** 搜索框收到的交互事件。 */
sealed interface SearchFocusEvent {
    /** 点小球或点已展开的输入框：展开并请求焦点。 */
    data object TapField : SearchFocusEvent

    /** 输入框真的拿到焦点。 */
    data object FieldFocused : SearchFocusEvent

    /** 输入框失焦（点空白、点了别的可聚焦控件）。 */
    data object FieldLostFocus : SearchFocusEvent

    /** 软键盘被显示出来（IME 高度 0 → 非 0）。 */
    data object ImeShown : SearchFocusEvent

    /** 软键盘被收起（IME 高度下降沿）：返回键收键盘、拖拽收键盘都走这里。 */
    data object ImeHidden : SearchFocusEvent

    /** 切走主 tab：去焦点但不收框。 */
    data object TabSwitch : SearchFocusEvent

    /** 键盘「搜索」键：提交 query，结算成「展开、无焦点、结果在屏」。 */
    data object Submit : SearchFocusEvent

    /** 点了 tag 联想：结果已按 tag 刷新，去焦点让结果露出来。 */
    data object TagPick : SearchFocusEvent

    /** 点 ✕：清词、清结果、收成小球。 */
    data object Clear : SearchFocusEvent
}

/**
 * 搜索框状态机：唯一的状态转移表。
 *
 * 「展开」与「请求焦点」被拆开：切 tab、失焦、关键盘都只去掉焦点（→ [SearchPhase.EXPANDED_IDLE]），
 * 不收回小球；只有 [SearchFocusEvent.Clear]（✕）与分片切换（沿用 diff 前的 collapse 路径）回到
 * [SearchPhase.COLLAPSED]。所以切 tab 再切回、旋转屏幕都不会突然拉起输入法。
 *
 * [SearchFocusEvent.ImeShown] 只在 [SearchPhase.EXPANDED_IDLE] 时回到编辑态：
 * [SearchPhase.COLLAPSED] 下即便系统报 IME 可见（别的页面/控件在用键盘）也不能把搜索框撑开。
 */
fun reduceSearchPhase(phase: SearchPhase, event: SearchFocusEvent): SearchPhase = when (event) {
    SearchFocusEvent.TapField, SearchFocusEvent.FieldFocused -> SearchPhase.EDITING
    SearchFocusEvent.ImeShown ->
        if (phase == SearchPhase.EXPANDED_IDLE) SearchPhase.EDITING else phase
    SearchFocusEvent.FieldLostFocus, SearchFocusEvent.ImeHidden, SearchFocusEvent.Submit,
    SearchFocusEvent.TagPick, SearchFocusEvent.TabSwitch ->
        if (phase == SearchPhase.EDITING) SearchPhase.EXPANDED_IDLE else phase
    SearchFocusEvent.Clear -> SearchPhase.COLLAPSED
}

/**
 * 失焦 / 关键盘时该不该把当前输入提交成一次搜索。
 *
 * 只在「编辑中且输入确实变了」时提交：空输入不提交（否则会闪一下空结果态），
 * 已经提交过的 query 不重复提交（提交后失焦只是收键盘，不该再打一次网络）。
 */
fun shouldCommitOnDefocus(phase: SearchPhase, input: String, committedQuery: String): Boolean =
    phase == SearchPhase.EDITING &&
        input.trim().isNotEmpty() &&
        input.trim() != committedQuery
