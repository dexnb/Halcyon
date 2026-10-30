package com.ella.music.ui.online

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ella.music.R
import com.ella.music.data.SettingsManager
import com.ella.music.data.bilibili.BilibiliAccountStore
import com.ella.music.data.bilibili.BilibiliFavoriteFolder
import com.ella.music.data.bilibili.BilibiliLibraryStore
import com.ella.music.ui.components.EllaMiuixBottomSheet
import com.ella.music.ui.components.EllaMiuixSheetActions
import com.ella.music.ui.components.EllaSheetCardGroup
import com.ella.music.ui.folder.WebDavTextField
import com.ella.music.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BilibiliAccountScreen(onDismiss: () -> Unit, mainViewModel: MainViewModel?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BilibiliLibraryStore.getInstance(context) }
    val accounts = remember { BilibiliAccountStore.getInstance(context) }
    val account by accounts.account.collectAsState()
    val status by store.status.collectAsState()
    val folders by store.folders.collectAsState()
    var cookie by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var browser by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }

    var mainLang by remember { mutableStateOf(store.subtitleMainLang()) }
    var secondaryLang by remember { mutableStateOf(store.subtitleSecondaryLang()) }
    var pronunciationLang by remember { mutableStateOf(store.subtitlePronunciationLang()) }
    var preferNonAi by remember { mutableStateOf(store.subtitlePreferNonAi()) }
    var dolby by remember { mutableStateOf(store.dolbyEnabled()) }
    var hiRes by remember { mutableStateOf(store.hiResEnabled()) }
    var selectedFolders by remember { mutableStateOf(store.selectedFolderIds()) }

    fun connect(session: String) {
        if (busy || session.isBlank()) return
        scope.launch {
            busy = true
            try {
                store.login(session)
                cookie = ""
                store.refreshFolders()
                store.refresh(true)
                if (mainViewModel != null) mainViewModel.setLibrarySource(SettingsManager.LIBRARY_SOURCE_BILIBILI)
                else SettingsManager.getInstance(context).setLibrarySource(SettingsManager.LIBRARY_SOURCE_BILIBILI)
                message = context.getString(R.string.bilibili_connected)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = context.getString(R.string.bilibili_login_failed) }
            finally { busy = false }
        }
    }

    val mainLangOptions = listOf(
        "auto" to "\u81ea\u52a8", "zh" to "\u4e2d\u6587", "en" to "\u82f1\u6587",
        "ja" to "\u65e5\u6587", "ko" to "\u97e9\u6587", "other" to "\u5176\u4ed6"
    )
    val secondaryLangOptions = listOf(
        "none" to "\u65e0", "zh" to "\u4e2d\u6587", "en" to "\u82f1\u6587",
        "ja" to "\u65e5\u6587", "ko" to "\u97e9\u6587", "auto" to "\u81ea\u52a8"
    )
    val pronunciationLangOptions = listOf(
        "none" to "\u65e0", "ja" to "\u65e5\u6587", "zh" to "\u4e2d\u6587",
        "en" to "\u82f1\u6587", "ko" to "\u97e9\u6587", "auto" to "\u81ea\u52a8"
    )

    EllaMiuixBottomSheet(
        show = true,
        title = stringResource(R.string.bilibili_title),
        onDismissRequest = onDismiss
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = androidx.compose.ui.platform.LocalWindowInfo.current.containerDpSize.height * .72f)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.bilibili_summary),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )

            if (account.loggedIn) {
                Text(
                    text = account.name + " \u00b7 " + account.mid,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
                EllaSheetCardGroup {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.bilibili_sync_playlists),
                            summary = if (selectedFolders.isEmpty())
                                stringResource(R.string.bilibili_no_favorite_selected)
                            else stringResource(R.string.bilibili_selected_count, selectedFolders.size),
                            onClick = {
                                scope.launch {
                                    store.refreshFolders()
                                    showPlaylists = true
                                }
                            }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.bilibili_create_favorite),
                            summary = stringResource(R.string.bilibili_favorite_name),
                            onClick = { showCreate = true }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.bilibili_sync),
                            summary = stringResource(R.string.bilibili_connected),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        store.refresh(true)
                                        mainViewModel?.setLibrarySource(SettingsManager.LIBRARY_SOURCE_BILIBILI)
                                        message = context.getString(R.string.bilibili_connected)
                                    } catch (cancel: CancellationException) { throw cancel }
                                    catch (_: Exception) { message = context.getString(R.string.bilibili_sync_failed) }
                                    finally { busy = false }
                                }
                            }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.bilibili_logout),
                            summary = stringResource(R.string.bilibili_session_expired),
                            onClick = {
                                scope.launch {
                                    store.logout()
                                    clearBilibiliWebSession()
                                    selectedFolders = emptySet()
                                    mainViewModel?.setLibrarySource(SettingsManager.LIBRARY_SOURCE_LOCAL)
                                    if (mainViewModel == null) SettingsManager.getInstance(context).setLibrarySource(SettingsManager.LIBRARY_SOURCE_LOCAL)
                                    message = ""
                                }
                            }
                        )
                    }
                }
            } else {
                Button(
                    enabled = !busy,
                    onClick = { browser = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.bilibili_web_login)) }
            }

            Button(
                enabled = !busy,
                onClick = { advanced = !advanced },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.bilibili_cookie_login)) }

            if (advanced) {
                EllaSheetCardGroup {
                    Column {
                        WebDavTextField(
                            stringResource(R.string.bilibili_cookie_hint),
                            cookie,
                            visualTransformation = PasswordVisualTransformation(),
                            onValueChange = { cookie = it }
                        )
                        Button(
                            enabled = !busy && cookie.isNotBlank(),
                            onClick = { connect(cookie) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.bilibili_connect)) }
                    }
                }
            }

            EllaSheetCardGroup {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.bilibili_dolby),
                        summary = stringResource(R.string.bilibili_dolby_summary),
                        checked = dolby,
                        onCheckedChange = { value -> dolby = value; scope.launch { store.setDolbyEnabled(value) } }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.bilibili_hires),
                        summary = stringResource(R.string.bilibili_hires_summary),
                        checked = hiRes,
                        onCheckedChange = { value -> hiRes = value; scope.launch { store.setHiResEnabled(value) } }
                    )
                }
            }

            EllaSheetCardGroup {
                Column {
                    WindowSpinnerPreference(
                        title = stringResource(R.string.bilibili_subtitle_main),
                        summary = stringResource(R.string.bilibili_subtitle_main_summary),
                        items = mainLangOptions.map { DropdownItem(title = it.second) },
                        selectedIndex = mainLangOptions.indexOfFirst { it.first == mainLang }.coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            val value = mainLangOptions.getOrNull(index)?.first ?: "auto"
                            mainLang = value
                            scope.launch { store.setSubtitleMainLang(value) }
                        }
                    )
                    WindowSpinnerPreference(
                        title = stringResource(R.string.bilibili_subtitle_secondary),
                        summary = stringResource(R.string.bilibili_subtitle_secondary_summary),
                        items = secondaryLangOptions.map { DropdownItem(title = it.second) },
                        selectedIndex = secondaryLangOptions.indexOfFirst { it.first == secondaryLang }.coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            val value = secondaryLangOptions.getOrNull(index)?.first ?: "zh"
                            secondaryLang = value
                            scope.launch { store.setSubtitleSecondaryLang(value) }
                        }
                    )
                    WindowSpinnerPreference(
                        title = stringResource(R.string.bilibili_subtitle_pronunciation),
                        summary = stringResource(R.string.bilibili_subtitle_pronunciation_summary),
                        items = pronunciationLangOptions.map { DropdownItem(title = it.second) },
                        selectedIndex = pronunciationLangOptions.indexOfFirst { it.first == pronunciationLang }.coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            val value = pronunciationLangOptions.getOrNull(index)?.first ?: "none"
                            pronunciationLang = value
                            scope.launch { store.setSubtitlePronunciationLang(value) }
                        }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.bilibili_subtitle_prefer_non_ai),
                        summary = stringResource(R.string.bilibili_subtitle_prefer_non_ai_summary),
                        checked = preferNonAi,
                        onCheckedChange = { value ->
                            preferNonAi = value
                            scope.launch { store.setSubtitlePreferNonAi(value) }
                        }
                    )
                }
            }

            Text(
                text = if (busy) stringResource(R.string.bilibili_loading) else status.ifBlank { message },
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                text = stringResource(R.string.bilibili_playback_note),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }

    BilibiliPlaylistSelectionSheet(
        show = showPlaylists,
        folders = folders,
        initialSelected = selectedFolders,
        onDismiss = { showPlaylists = false },
        onConfirm = { ids ->
            selectedFolders = ids
            store.selectFolders(ids)
            scope.launch {
                busy = true
                try { store.refresh(true) } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { message = context.getString(R.string.bilibili_sync_failed) }
                finally { busy = false }
            }
        }
    )

    BilibiliCreateFolderSheet(
        show = showCreate,
        onDismiss = { showCreate = false },
        onConfirm = { name ->
            scope.launch {
                busy = true
                try { store.createFolder(name) } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { message = context.getString(R.string.bilibili_sync_failed) }
                finally { busy = false }
            }
        }
    )

    if (browser) {
        BilibiliBrowserLogin(
            onDismiss = { browser = false },
            onSession = { session ->
                browser = false
                if (session.isNotBlank()) connect(session)
                else message = context.getString(R.string.bilibili_login_failed)
            }
        )
    }
}

@Composable
private fun BilibiliPlaylistSelectionSheet(
    show: Boolean,
    folders: List<BilibiliFavoriteFolder>,
    initialSelected: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit
) {
    var selected by remember(show, initialSelected) { mutableStateOf(initialSelected) }
    val allSelected = folders.isNotEmpty() && folders.all { it.id in selected }
    EllaMiuixBottomSheet(
        show = show,
        title = stringResource(R.string.bilibili_sync_playlists),
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item {
                    PlaylistSelectionRow(
                        title = stringResource(R.string.bilibili_select_all),
                        summary = null,
                        checked = allSelected,
                        onClick = {
                            selected = if (allSelected) emptySet() else folders.map { it.id }.toSet()
                        }
                    )
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(0.5.dp)
                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    )
                }
                items(folders, key = { it.id }) { folder ->
                    PlaylistSelectionRow(
                        title = folder.title,
                        summary = stringResource(R.string.bilibili_video_count, folder.mediaCount),
                        checked = folder.id in selected,
                        onClick = {
                            selected = if (folder.id in selected) selected - folder.id else selected + folder.id
                        }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            EllaMiuixSheetActions(
                cancelText = stringResource(R.string.common_cancel),
                confirmText = stringResource(R.string.bilibili_connect),
                onCancel = onDismiss,
                onConfirm = {
                    onConfirm(selected)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun PlaylistSelectionRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SelectionCheckbox(checked)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface
            )
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun SelectionCheckbox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (checked) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.surfaceContainer
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = MiuixIcons.Regular.Ok,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun BilibiliCreateFolderSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(show) { mutableStateOf("") }
    EllaMiuixBottomSheet(
        show = show,
        title = stringResource(R.string.bilibili_create_favorite),
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WebDavTextField(
                stringResource(R.string.bilibili_favorite_name),
                name,
                onValueChange = { name = it }
            )
            EllaMiuixSheetActions(
                cancelText = stringResource(R.string.common_cancel),
                confirmText = stringResource(R.string.bilibili_connect),
                onCancel = onDismiss,
                onConfirm = {
                    if (name.isNotBlank()) onConfirm(name.trim())
                    onDismiss()
                }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BilibiliBrowserLogin(onDismiss: () -> Unit, onSession: (String) -> Unit) {
    val context = LocalContext.current
    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host.orEmpty()
                    return request.url.scheme != "https" || !(host == "bilibili.com" || host.endsWith(".bilibili.com"))
                }
            }
            loadUrl("https://passport.bilibili.com/login")
        }
    }
    DisposableEffect(web) {
        onDispose {
            web.stopLoading()
            clearBilibiliWebSession()
            web.destroy()
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BackHandler(onBack = onDismiss)
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                Button(onClick = {
                    val cookieManager = CookieManager.getInstance()
                    val parts = mutableListOf<String>()
                    for (url in listOf("https://www.bilibili.com", "https://passport.bilibili.com", "https://api.bilibili.com")) {
                        cookieManager.getCookie(url)?.takeIf { it.isNotBlank() }?.let { parts += it }
                    }
                    onSession(parts.joinToString("; "))
                }) { Text(stringResource(R.string.bilibili_finish_login)) }
            }
            AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

private fun clearBilibiliWebSession() {
    val manager = CookieManager.getInstance()
    for (domain in listOf(".bilibili.com", "passport.bilibili.com", "www.bilibili.com")) {
        for (name in listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid")) {
            manager.setCookie("https://www.bilibili.com", name + "=; Domain=" + domain + "; Path=/; Max-Age=0; Secure")
        }
    }
    manager.flush()
}
