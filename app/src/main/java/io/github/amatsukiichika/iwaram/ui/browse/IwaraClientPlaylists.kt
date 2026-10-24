package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.amatsukiichika.iwaram.DownloadService
import io.github.amatsukiichika.iwaram.IwaraApp
import io.github.amatsukiichika.iwaram.data.IwaraAccount
import io.github.amatsukiichika.iwaram.data.IwaraClient
import io.github.amatsukiichika.iwaram.data.IwaraComment
import io.github.amatsukiichika.iwaram.data.IwaraCommentBody
import io.github.amatsukiichika.iwaram.data.IwaraFollowEdge
import io.github.amatsukiichika.iwaram.data.IwaraImageItem
import io.github.amatsukiichika.iwaram.data.IwaraPlaylist
import io.github.amatsukiichika.iwaram.data.IwaraPlaylistBody
import io.github.amatsukiichika.iwaram.data.IwaraPostItem
import io.github.amatsukiichika.iwaram.data.IwaraProfileData
import io.github.amatsukiichika.iwaram.data.IwaraSession
import io.github.amatsukiichika.iwaram.data.IwaraSign
import io.github.amatsukiichika.iwaram.data.IwaraTag
import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.describeIwaraError
import io.github.amatsukiichika.iwaram.data.ensureIwaraSuccess
import io.github.amatsukiichika.iwaram.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaram.data.parseIwaraDate
import io.github.amatsukiichika.iwaram.data.loadPlaylistsCache
import io.github.amatsukiichika.iwaram.data.savePlaylistsCache
import io.github.amatsukiichika.iwaram.ui.DiagLog
import io.github.amatsukiichika.iwaram.ui.QualityOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/* ---------- 播放列表域（原 IwaraClientViewModel 方法，扩展函数形式） ---------- */
fun IwaraClientViewModel.loadPlaylists(force: Boolean = false) {
        val current = _playlists.value
        if (current.loading || (!force && current.loaded)) return
        _playlists.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val items = IwaraClient.call {
                    it.playlists(_session.value.userId, 0, 100).results.orEmpty()
                }
                repo.savePlaylistsCache(gson.toJson(items))
                _playlists.value = IwaraClientViewModel.PlaylistsState(items = items, loaded = true)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // 断网兜底：列表为空且本地有缓存时显示缓存内容，并明确提示。
                // 缓存读取与 JSON 解析放在 IO 线程（缓存大时主线程会卡顿）。
                val cached = if (_playlists.value.items.isEmpty()) {
                    withContext(Dispatchers.IO) { repo.loadPlaylistsCache() }
                } else null
                if (cached != null) {
                    val items = runCatching {
                        withContext(Dispatchers.IO) {
                            gson.fromJson<List<IwaraPlaylist>>(cached, playlistsCacheType)
                        }
                    }.getOrDefault(emptyList())
                    _playlists.value = IwaraClientViewModel.PlaylistsState(
                        items = items,
                        loaded = true,
                        error = "网络不可用，正在显示缓存内容"
                    )
                } else {
                    _playlists.value = _playlists.value.copy(loading = false, error = describeIwaraError(t))
                }
            }
        }
    }

fun IwaraClientViewModel.createPlaylist(title: String, scope: CoroutineScope? = null, onDone: (String?) -> Unit) {
        (scope ?: viewModelScope).launch {
            try {
                val created = IwaraClient.call { it.createPlaylist(IwaraPlaylistBody(title.trim())) }
                _playlists.value = _playlists.value.copy(
                    items = listOf(created) + _playlists.value.items
                )
                onDone(null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.addToPlaylist(playlistId: String, videoId: String, scope: CoroutineScope? = null, onDone: (String?) -> Unit) {
        (scope ?: viewModelScope).launch {
            try {
                IwaraClient.call { it.addToPlaylist(playlistId, videoId) }.ensureIwaraSuccess()
                onDone(null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.removeFromPlaylist(playlistId: String, videoId: String, scope: CoroutineScope? = null, onDone: (String?) -> Unit) {
        (scope ?: viewModelScope).launch {
            try {
                IwaraClient.call { it.removeFromPlaylist(playlistId, videoId) }.ensureIwaraSuccess()
                onDone(null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.deletePlaylist(playlistId: String, scope: CoroutineScope? = null, onDone: (String?) -> Unit) {
        (scope ?: viewModelScope).launch {
            try {
                IwaraClient.call { it.deletePlaylist(playlistId) }.ensureIwaraSuccess()
                _playlists.value = _playlists.value.copy(
                    items = _playlists.value.items.filterNot { it.id == playlistId }
                )
                onDone(null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(describeIwaraError(t))
            }
        }
    }

fun IwaraClientViewModel.loadPlaylistDetail(playlistId: String, scope: CoroutineScope? = null, onDone: (IwaraPlaylist?, String?) -> Unit) {
        (scope ?: viewModelScope).launch {
            try {
                val detail = IwaraClient.call { it.playlist(playlistId) }
                onDone(detail, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(null, describeIwaraError(t))
            }
        }
    }
