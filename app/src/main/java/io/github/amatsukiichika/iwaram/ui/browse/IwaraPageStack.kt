package io.github.amatsukiichika.iwaram.ui.browse

/**
 * 「播放页点作者」的导航计划（纯逻辑，见 `AuthorPageStackTest`）。
 *
 * 播放页不在覆盖层栈 [overlays] 里（它只有一个实例，宿主用单独的 [showingPlayer] / 序号表示），
 * 所以"作者页在播放页上面还是下面"不能只看栈序 —— 还要比较两者的打开序号。
 *
 * 规则（与播放页自身的层序语义一致：序号大 = 后打开 = 画在上面）：
 * - 栈里**没有**同一个作者 → 压一层新作者页在播放页之上，**不关播放页**（播放页在下面继续存在，
 *   被盖住时暂停，返回即恢复）；
 * - 栈里**已有**同一个作者 → 回退到那一层（把上面的页面都弹掉）。此时若播放页的序号更大，
 *   说明播放页正盖着那个作者页（作者页 → 进视频），必须**一并关掉播放页**才能看到作者页；
 *   否则播放页在作者页下面（播放页 → 点作者），保持它，只弹掉上面的页面。
 *
 * 命中判据沿用 [profileRevertIndex] / [sameProfileKey]：userId、username、本地作者键任一相同即同一作者。
 * 禁止改写已有作者页的 authorId（复用原条目）、禁止重复压同一作者页 —— 都由 [PopTo] 保证。
 */
sealed interface AuthorNavPlan {
    /** 回退到栈内第 [targetIndex] 层作者页；[closesPlayer] = 还要不要先关掉播放页。 */
    data class PopTo(val targetIndex: Int, val closesPlayer: Boolean) : AuthorNavPlan

    /** 栈里没有该作者页：压到栈顶（播放页之上）。 */
    data object PushOnTop : AuthorNavPlan
}

/**
 * 计算"点作者"的导航计划。
 *
 * @param overlays 当前覆盖层栈（含动作面板，面板不是页面但会随回退一起弹掉）
 * @param playerSeq 当前播放页的打开序号（与覆盖层栈共用同一个进程级计数器）
 * @param showingPlayer 播放页是否正打开着
 */
fun authorNavPlan(
    overlays: List<OverlayEntry>,
    playerSeq: Long,
    showingPlayer: Boolean,
    incoming: IwaraOverlay.Profile
): AuthorNavPlan {
    val index = profileRevertIndex(overlays.map { it.overlay }, incoming)
    if (index < 0) return AuthorNavPlan.PushOnTop
    // 播放页序号更大 ⇒ 它画在作者页之上（作者页 → 进视频）。要看到作者页，必须关播放页。
    // 反过来（播放页 → 点作者）作者页本来就在播放页之上，播放页留在下面，返回还能继续看。
    val closesPlayer = showingPlayer && playerSeq > overlays[index].seq
    return AuthorNavPlan.PopTo(index, closesPlayer)
}
