package com.ella.music.ui.online

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ella.music.R
import com.ella.music.data.SettingsManager
import com.ella.music.data.bilibili.BilibiliAccountStore
import com.ella.music.data.bilibili.BilibiliApiClient
import com.ella.music.data.bilibili.BilibiliLibraryStore
import com.ella.music.data.bilibili.BilibiliQrCode
import com.ella.music.ui.folder.WebDavTextField
import com.ella.music.viewmodel.MainViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference

@Composable
fun BilibiliAccountScreen(onDismiss: () -> Unit, mainViewModel: MainViewModel?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BilibiliLibraryStore.getInstance(context) }
    val accounts = remember { BilibiliAccountStore.getInstance(context) }
    val settingsManager = remember(context) { SettingsManager.getInstance(context) }
    val account by accounts.account.collectAsState()
    val status by store.status.collectAsState()
    val folders by store.folders.collectAsState()
    var cookie by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var selectedFolderId by remember { mutableStateOf(store.selectedFolderId()) }

    fun connect(session: String) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                store.login(session)
                cookie = ""
                store.refreshFolders()
                if (mainViewModel != null) mainViewModel.setLibrarySource(SettingsManager.LIBRARY_SOURCE_BILIBILI)
                else SettingsManager.getInstance(context).setLibrarySource(SettingsManager.LIBRARY_SOURCE_BILIBILI)
                message = context.getString(R.string.bilibili_connected)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = context.getString(R.string.bilibili_login_failed) }
            finally { busy = false }
        }
    }

    LaunchedEffect(account.loggedIn) {
        if (account.loggedIn) {
            store.refreshFolders()
            selectedFolderId = store.selectedFolderId()
        }
    }

    com.ella.music.ui.components.EllaMiuixBottomSheet(
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
            Text(stringResource(R.string.bilibili_summary))

            if (account.loggedIn) {
                Text("${account.name} · ${account.mid}")

                // 收藏夹选择（作为音乐库）
                val folderIds = listOf(0L) + folders.map { it.id }
                val folderItems = listOf(DropdownItem(title = context.getString(R.string.bilibili_no_favorite_selected))) +
                    folders.map { DropdownItem(title = "${it.title}（${it.mediaCount}）") }
                val selectedIndex = folderIds.indexOfFirst { it == selectedFolderId }.coerceAtLeast(0)

                com.ella.music.ui.components.EllaSheetCardGroup {
                    WindowSpinnerPreference(
                        title = stringResource(R.string.bilibili_favorite_folder),
                        summary = stringResource(R.string.bilibili_favorite_folder_summary),
                        items = folderItems,
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = { index ->
                            val id = folderIds.getOrNull(index) ?: 0L
                            scope.launch {
                                store.selectFolder(id)
                                selectedFolderId = id
                                if (id > 0) store.refresh(true)
                                message = context.getString(R.string.bilibili_connected)
                            }
                        }
                    )
                }

                // 字幕歌词配置（主行/副行/注音的语言优先级）
                val mainLangOptions = listOf(
                    "auto" to "自动", "zh" to "中文", "en" to "英文", "ja" to "日文", "ko" to "韩文", "other" to "其他"
                )
                val secondaryLangOptions = listOf(
                    "none" to "无", "zh" to "中文", "en" to "英文", "ja" to "日文", "ko" to "韩文", "auto" to "自动"
                )
                val pronunciationLangOptions = listOf(
                    "none" to "无", "ja" to "日文", "zh" to "中文", "en" to "英文", "ko" to "韩文", "auto" to "自动"
                )
                var mainLang by remember { mutableStateOf(store.subtitleMainLang()) }
                var secondaryLang by remember { mutableStateOf(store.subtitleSecondaryLang()) }
                var pronunciationLang by remember { mutableStateOf(store.subtitlePronunciationLang()) }
                var preferNonAi by remember { mutableStateOf(store.subtitlePreferNonAi()) }

                com.ella.music.ui.components.EllaSheetCardGroup {
                    WindowSpinnerPreference(
                        title = stringResource(R.string.bilibili_subtitle_main),
                        summary = stringResource(R.string.bilibili_subtitle_main_summary),
                        items = mainLangOptions.map { DropdownItem(title = it.second) },
                        selectedIndex = mainLangOptions.indexOfFirst { it.first == mainLang }.coerceAtLeast(0),
                        onSelectedIndexChange = { idx ->
                            val v = mainLangOptions.getOrNull(idx)?.first ?: "auto"
                            mainLang = v
                            scope.launch { store.setSubtitleMainLang(v) }
                        }
                    )
                    WindowSpinnerPreference(
                        title = stringResource(R.string.bilibili_subtitle_secondary),
                        summary = stringResource(R.string.bilibili_subtitle_secondary_summary),
                        items = secondaryLangOptions.map { DropdownItem(title = it.second) },
                        selectedIndex = secondaryLangOptions.indexOfFirst { it.first == secondaryLang }.coerceAtLeast(0),
                        onSelectedIndexChange = { idx ->
                            val v = secondaryLangOptions.getOrNull(idx)?.first ?: "zh"
                            secondaryLang = v
                            scope.launch { store.setSubtitleSecondaryLang(v) }
                        }
                    )
                    WindowSpinnerPreference(
                        title = stringResource(R.string.bilibili_subtitle_pronunciation),
                        summary = stringResource(R.string.bilibili_subtitle_pronunciation_summary),
                        items = pronunciationLangOptions.map { DropdownItem(title = it.second) },
                        selectedIndex = pronunciationLangOptions.indexOfFirst { it.first == pronunciationLang }.coerceAtLeast(0),
                        onSelectedIndexChange = { idx ->
                            val v = pronunciationLangOptions.getOrNull(idx)?.first ?: "none"
                            pronunciationLang = v
                            scope.launch { store.setSubtitlePronunciationLang(v) }
                        }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.bilibili_subtitle_prefer_non_ai),
                        summary = stringResource(R.string.bilibili_subtitle_prefer_non_ai_summary),
                        checked = preferNonAi,
                        onCheckedChange = { v ->
                            preferNonAi = v
                            scope.launch { store.setSubtitlePreferNonAi(v) }
                        }
                    )
                }

                Button(enabled = !busy, onClick = { showCreateFolder = true }) {
                    Text(stringResource(R.string.bilibili_create_favorite))
                }
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            store.refreshFolders()
                            store.refresh(true)
                            mainViewModel?.setLibrarySource(SettingsManager.LIBRARY_SOURCE_BILIBILI)
                            message = context.getString(R.string.bilibili_connected)
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { message = context.getString(R.string.bilibili_sync_failed) }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.bilibili_sync)) }
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        store.logout()
                        mainViewModel?.setLibrarySource(SettingsManager.LIBRARY_SOURCE_LOCAL)
                        if (mainViewModel == null) SettingsManager.getInstance(context).setLibrarySource(SettingsManager.LIBRARY_SOURCE_LOCAL)
                        selectedFolderId = 0L
                        message = ""
                    }
                }) { Text(stringResource(R.string.bilibili_logout)) }
            } else {
                Button(enabled = !busy, onClick = { showQr = true }) { Text(stringResource(R.string.bilibili_qr_login)) }
                Button(enabled = !busy, onClick = { advanced = !advanced }) { Text(stringResource(R.string.bilibili_cookie_login)) }
                if (advanced) {
                    WebDavTextField(
                        stringResource(R.string.bilibili_cookie_hint),
                        cookie,
                        visualTransformation = PasswordVisualTransformation(),
                        onValueChange = { cookie = it }
                    )
                    Button(enabled = !busy && cookie.isNotBlank(), onClick = { connect(cookie) }) {
                        Text(stringResource(R.string.bilibili_connect))
                    }
                }
            }

            Text(if (busy) stringResource(R.string.bilibili_loading) else status.ifBlank { message })
            Text(stringResource(R.string.bilibili_playback_note))
        }
    }

    if (showQr) BilibiliQrLoginDialog(
        onDismiss = { showQr = false },
        onSuccess = { session ->
            showQr = false
            if (session.isNotBlank()) connect(session) else message = context.getString(R.string.bilibili_login_failed)
        }
    )

    if (showCreateFolder) BilibiliCreateFolderDialog(
        onDismiss = { showCreateFolder = false },
        onCreate = { title ->
            showCreateFolder = false
            scope.launch {
                busy = true
                try {
                    val id = store.createFolder(title)
                    store.selectFolder(id)
                    selectedFolderId = id
                    store.refresh(true)
                    message = context.getString(R.string.bilibili_connected)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { message = context.getString(R.string.bilibili_sync_failed) }
                finally { busy = false }
            }
        }
    )
}

@Composable
private fun BilibiliQrLoginDialog(onDismiss: () -> Unit, onSuccess: (String) -> Unit) {
    val context = LocalContext.current
    val client = remember { BilibiliApiClient(context) }
    var qrUrl by remember { mutableStateOf("") }
    var qrKey by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf(context.getString(R.string.bilibili_qr_generating)) }
    var expired by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val qr = client.generateLoginQr()
            qrUrl = qr.url
            qrKey = qr.qrcodeKey
            statusText = context.getString(R.string.bilibili_qr_waiting)
        } catch (_: Exception) {
            statusText = context.getString(R.string.bilibili_login_failed)
        }
    }

    LaunchedEffect(qrKey) {
        if (qrKey.isBlank()) return@LaunchedEffect
        while (true) {
            delay(2000)
            try {
                val poll = client.pollLoginQr(qrKey)
                when (poll.code) {
                    BilibiliQrCode.SUCCESS -> { onSuccess(poll.cookies); break }
                    BilibiliQrCode.SCANNED_NOT_CONFIRMED -> statusText = context.getString(R.string.bilibili_qr_scanned)
                    BilibiliQrCode.EXPIRED -> { statusText = context.getString(R.string.bilibili_qr_expired); expired = true; break }
                }
            } catch (_: Exception) { /* 继续轮询 */ }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        com.ella.music.ui.components.EllaSheetCardGroup {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.bilibili_qr_login))
                val bitmap = remember(qrUrl) { if (qrUrl.isNotBlank() && !expired) qrUrl.toQrBitmap() else null }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(240.dp).background(androidx.compose.ui.graphics.Color.White)
                    )
                }
                Text(statusText)
                Button(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        }
        }
    }
}

@Composable
private fun BilibiliCreateFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        com.ella.music.ui.components.EllaSheetCardGroup {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.bilibili_create_favorite))
                WebDavTextField(context.getString(R.string.bilibili_favorite_name), title, onValueChange = { title = it })
                Button(enabled = title.isNotBlank(), onClick = { onCreate(title.trim()) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bilibili_connect))
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(android.R.string.cancel)) }
            }
        }
        }
    }
}

private fun String.toQrBitmap(size: Int = 512): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(this, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}
