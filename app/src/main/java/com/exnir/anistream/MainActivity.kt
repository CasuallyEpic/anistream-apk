package com.exnir.anistream

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.exnir.anistream.ui.theme.AniStreamTheme
import com.google.gson.Gson
import okhttp3.*
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AniStreamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    var showUpdateDialog by remember { mutableStateOf(false) }
                    var updateUrl by remember { mutableStateOf("") }
                    var newVersionName by remember { mutableStateOf("") }

                    LaunchedEffect(Unit) {
                        checkForUpdates(context) { version, url ->
                            newVersionName = version
                            updateUrl = url
                            showUpdateDialog = true
                        }
                    }

                    if (showUpdateDialog) {
                        AlertDialog(
                            onDismissRequest = { showUpdateDialog = false },
                            title = { Text("New Update Available") },
                            text = { Text("Version $newVersionName is available. Would you like to update?") },
                            confirmButton = {
                                Button(onClick = {
                                    showUpdateDialog = false
                                    downloadAndInstallApk(context, updateUrl)
                                }) {
                                    Text("Update")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showUpdateDialog = false }) {
                                    Text("Later")
                                }
                            }
                        )
                    }

                    WebViewScreen("https://anistream.qzz.io/")
                }
            }
        }
    }

    private fun checkForUpdates(context: Context, onUpdateAvailable: (String, String) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://api.github.com/repos/CasuallyEpic/anistream-apk/releases/latest")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { json ->
                    val release = Gson().fromJson(json, GithubRelease::class.java)
                    val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    
                    if (release.tag_name != null && release.tag_name != "v$currentVersion" && release.tag_name != currentVersion) {
                        val apkAsset = release.assets.find { it.name.endsWith(".apk") }
                        if (apkAsset != null) {
                            (context as MainActivity).runOnUiThread {
                                onUpdateAvailable(release.tag_name, apkAsset.browser_download_url)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun downloadAndInstallApk(context: Context, url: String) {
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading AniStream Update")
            .setDescription("Please wait...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == downloadId) {
                    installApk(context, destination)
                    context.unregisterReceiver(this)
                }
            }
        }
        context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show()
    }

    private fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

data class GithubRelease(val tag_name: String?, val assets: List<GithubAsset>)
data class GithubAsset(val name: String, val browser_download_url: String)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(url: String) {
    val webViewStateBundle = rememberSaveable { Bundle() }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = canGoBack && errorMessage == null) {
        webViewRef?.goBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (errorMessage != null) {
            ErrorScreen(
                message = errorMessage!!,
                onRetry = {
                    errorMessage = null
                    webViewRef?.reload()
                }
            )
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                factory = { context ->
                    WebView(context).apply {
                        webViewRef = this
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        setBackgroundColor(Color.TRANSPARENT)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(
                                    "(function() { " +
                                            "var style = document.createElement('style');" +
                                            "style.innerHTML = '::-webkit-scrollbar { display: none; }';" +
                                            "document.head.appendChild(style);" +
                                            "})()", null
                                )
                                view?.saveState(webViewStateBundle)
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (request?.isForMainFrame == true) {
                                    errorMessage = "No internet connection or site is down."
                                }
                            }

                            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                                if (request?.isForMainFrame == true) {
                                    errorMessage = "Server Error: ${errorResponse?.statusCode}"
                                }
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                canGoBack = view?.canGoBack() ?: false
                                view?.saveState(webViewStateBundle)
                            }
                        }

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            allowFileAccess = true
                            allowContentAccess = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setOffscreenPreRaster(true)
                            loadsImagesAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                        
                        if (webViewStateBundle.isEmpty) {
                            loadUrl(url)
                        } else {
                            restoreState(webViewStateBundle)
                        }
                    }
                },
                update = { it.saveState(webViewStateBundle) }
            )
        }
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Oops! Connection Problem",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRetry,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.6f)
        ) {
            Text("Try Again")
        }
    }
}
