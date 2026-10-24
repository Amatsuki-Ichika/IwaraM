package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.amatsukiichika.iwaramanager.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaramanager.data.IwaraAccount
import io.github.amatsukiichika.iwaramanager.data.IwaraClient
import io.github.amatsukiichika.iwaramanager.data.IwaraImageItem
import io.github.amatsukiichika.iwaramanager.data.IwaraPostItem
import io.github.amatsukiichika.iwaramanager.data.IwaraProfileData
import io.github.amatsukiichika.iwaramanager.data.IwaraVideoItem
import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import io.github.amatsukiichika.iwaramanager.data.filterBy
import io.github.amatsukiichika.iwaramanager.data.localVideoKeys
import io.github.amatsukiichika.iwaramanager.data.videoKeys
import io.github.amatsukiichika.iwaramanager.ui.DiagLog
import io.github.amatsukiichika.iwaramanager.ui.components.debugAwareErrorText
import kotlin.math.roundToInt
import io.github.amatsukiichika.iwaramanager.ui.VideoCard
import io.github.amatsukiichika.iwaramanager.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关注 / 粉丝关系列表二级页（从计数点进来）。
 * 复用 `PagedAccountList` 与 `loadFollowing` / `loadUserFollowers`。
 */
@Composable
fun RelationsOverlay(
    userId: String,
    kind: RelationKind,
    cvm: IwaraClientViewModel,
    actions: IwaraNavActions,
    onClose: () -> Unit
) {
    var state by remember { mutableStateOf(PagedState<IwaraAccount>()) }
    val pageScope = rememberCoroutineScope()

    fun load(force: Boolean) {
        val st = state
        if (st.loading || (!force && st.end) || (!force && st.loaded && st.items.isNotEmpty())) return
        // 首拉必须 page 0（同 loadVideos 的说明：page 初值 0 + `page + 1` 会漏掉第一页）。
        val next = if (force || !st.loaded) 0 else st.page + 1
        state = st.copy(loading = true, error = null)
        val done: (List<IwaraAccount>, Boolean, String?) -> Unit = { list, end, err ->
            state = state.copy(
                items = if (force) list else state.items + list,
                page = next, end = end, loading = false, loaded = true, error = err
            )
        }
        when (kind) {
            RelationKind.FOLLOWING -> cvm.loadFollowing(userId, next, scope = pageScope, onDone = done)
            RelationKind.FOLLOWERS -> cvm.loadUserFollowers(userId, next, scope = pageScope, onDone = done)
        }
    }

    LaunchedEffect(userId, kind) { load(false) }

    Column(Modifier.fillMaxSize()) {
        OverlayTopBar(title = kind.label, onBack = onClose)
        PagedAccountList(
            state = state,
            emptyHint = if (kind == RelationKind.FOLLOWING) "TA 还没有关注任何人" else "TA 还没有粉丝",
            onLoadMore = { load(false) },
            onRetry = { load(true) },
            onOpen = { actions.openAccountProfile(it) }
        )
    }
}

/* ---------- 内容容器 ---------- */
