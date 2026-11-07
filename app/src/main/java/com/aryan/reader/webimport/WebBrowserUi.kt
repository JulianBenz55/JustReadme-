package com.aryan.reader.webimport

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.aryan.reader.R
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/** A website the user saved in "From web browser" so it can be opened again later. */
internal class WebSource(val name: String, val url: String)

internal object WebSourcesStore {
    private const val PREFS = "web_sources_prefs"
    private const val KEY = "sources"

    fun load(context: Context): List<WebSource> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url")
                if (url.isBlank()) null else WebSource(item.optString("name").ifBlank { url }, url)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, sources: List<WebSource>) {
        val array = JSONArray()
        sources.forEach { array.put(JSONObject().put("name", it.name).put("url", it.url)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}

private fun defaultSourceName(url: String): String {
    val parsed = try {
        URL(url)
    } catch (e: Exception) {
        return url
    }
    val lastSegment = parsed.path.trim('/').substringAfterLast('/')
    return lastSegment.ifBlank { parsed.host.removePrefix("www.") }
}

/**
 * "From web browser": saved sources plus an in-app browser. Open a site (log in if needed),
 * navigate to a chapter or page and choose "Clip page" in the menu to save it as a book.
 * Files the browser downloads are imported as well.
 */
@Composable
internal fun WebSourcesDialog(
    onDismiss: () -> Unit,
    onImported: (File) -> Unit,
) {
    val context = LocalContext.current
    val sources = remember { mutableStateListOf<WebSource>().apply { addAll(WebSourcesStore.load(context)) } }
    var urlInput by rememberSaveable { mutableStateOf("") }
    var nameInput by rememberSaveable { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var browsingUrl by rememberSaveable { mutableStateOf<String?>(null) }

    fun normalized(): String? {
        return try {
            WebImporters.normalizeUrl(urlInput)
        } catch (e: IllegalArgumentException) {
            inputError = e.message
            null
        }
    }

    val openUrl = browsingUrl
    if (openUrl != null) {
        WebBrowserScreen(startUrl = openUrl, onClose = { browsingUrl = null }, onImported = onImported)
        return
    }
    val errorMessage = inputError

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 640.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.add_from_browser), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.web_sources_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                if (sources.isNotEmpty()) {
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sources.toList(), key = { it.url }) { source ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.fillMaxWidth().clickable { browsingUrl = source.url },
                            ) {
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(source.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            source.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(onClick = {
                                        sources.remove(source)
                                        WebSourcesStore.save(context, sources.toList())
                                    }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.web_import_close)) }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it; inputError = null },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = if (errorMessage != null) ({ Text(errorMessage) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { normalized()?.let { browsingUrl = it } }),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = { Text(stringResource(R.string.web_sources_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.web_import_close)) }
                    TextButton(
                        enabled = urlInput.isNotBlank(),
                        onClick = {
                            normalized()?.let { url ->
                                if (sources.none { it.url == url }) {
                                    sources.add(WebSource(nameInput.trim().ifBlank { defaultSourceName(url) }, url))
                                    WebSourcesStore.save(context, sources.toList())
                                }
                                urlInput = ""
                                nameInput = ""
                            }
                        },
                    ) { Text(stringResource(R.string.web_sources_add)) }
                    Button(
                        enabled = urlInput.isNotBlank(),
                        onClick = { normalized()?.let { browsingUrl = it } },
                    ) { Text(stringResource(R.string.web_sources_browse)) }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebBrowserScreen(
    startUrl: String,
    onClose: () -> Unit,
    onImported: (File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf(startUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun clipPage() {
        val view = webView ?: return
        if (busy) return
        busy = true
        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
            scope.launch {
                try {
                    val html = JSONTokener(raw).nextValue() as? String ?: throw IOException("The page could not be read")
                    val file = WebImporters.clipHtml(context.applicationContext, html, view.url ?: currentUrl)
                    onImported(file)
                    toast(context.getString(R.string.web_browser_clipped))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast(e.message ?: e.javaClass.simpleName)
                } finally {
                    busy = false
                }
            }
        }
    }

    fun downloadAndImport(url: String, userAgent: String?, disposition: String?, mimeType: String?) {
        if (!url.startsWith("http")) {
            toast(context.getString(R.string.web_browser_unsupported_download))
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            try {
                val fileName = URLUtil.guessFileName(url, disposition, mimeType)
                val cookies = CookieManager.getInstance().getCookie(url)
                val file = WebImporters.downloadFile(context.applicationContext, url, fileName, cookies, userAgent)
                onImported(file)
                toast(context.getString(R.string.web_browser_downloaded, file.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (canGoBack) webView?.goBack() else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.web_import_close))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(
                            pageTitle.ifBlank { currentUrl },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            currentUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.web_browser_clip)) },
                                leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null) },
                                enabled = !busy,
                                onClick = {
                                    menuOpen = false
                                    clipPage()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.web_browser_reload)) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    webView?.reload()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.web_browser_open_external)) },
                                leadingIcon = { Icon(Icons.Default.OpenInBrowser, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView?.url ?: currentUrl)))
                                    } catch (e: Exception) {
                                        toast(e.message ?: e.javaClass.simpleName)
                                    }
                                },
                            )
                        }
                    }
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    if (url != null) currentUrl = url
                                    canGoBack = view?.canGoBack() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (url != null) currentUrl = url
                                    pageTitle = view?.title.orEmpty()
                                    canGoBack = view?.canGoBack() == true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    if (!title.isNullOrBlank()) pageTitle = title
                                }
                            }
                            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                                downloadAndImport(url, userAgent, contentDisposition, mimeType)
                            }
                            loadUrl(startUrl)
                            webView = this
                        }
                    },
                    onRelease = { view ->
                        view.stopLoading()
                        view.destroy()
                    },
                )
            }
        }
    }
}
