package com.cafshare.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.cafshare.app.adapter.ApkAdapter
import com.cafshare.app.adapter.AppAdapter
import com.cafshare.app.databinding.ActivityMainBinding
import com.cafshare.app.model.ApkEntry
import com.cafshare.app.model.AppEntry
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var allApps: List<AppEntry> = emptyList()
    private var allApks: List<ApkEntry> = emptyList()
    private var tab = 0
    private var pendingSave: AppEntry? = null

    private val appAdapter = AppAdapter(
        onShare = { shareApp(it) },
        onSave = { saveApp(it) },
        onClick = { shareApp(it) }
    )
    private val apkAdapter = ApkAdapter(
        onInstall = { installApk(it.file) },
        onShare = { shareFile(it.file) }
    )

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importUri(it, installAfter = true) }
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            loadApks()
            pendingSave?.let { saveApp(it) }
        } else {
            toast(getString(R.string.storage_permission))
        }
        pendingSave = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)
        b.toolbar.inflateMenu(R.menu.main_menu)
        b.toolbar.setOnMenuItemClickListener { onMenu(it) }

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = appAdapter
        b.list.itemAnimator = null

        b.tabs.addTab(b.tabs.newTab().setText(R.string.tab_apps))
        b.tabs.addTab(b.tabs.newTab().setText(R.string.tab_apk))
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab?) {
                tab = t?.position ?: 0
                render()
            }

            override fun onTabUnselected(t: TabLayout.Tab?) = Unit
            override fun onTabReselected(t: TabLayout.Tab?) = Unit
        })

        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = applyFilter()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        b.fabPick.setOnClickListener { chooseApk() }
        b.emptyPick.setOnClickListener { chooseApk() }

        loadApps()
        loadApks()
        requestStorageIfNeeded()
        handleIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private fun render() {
        val appsTab = tab == 0
        b.list.adapter = if (appsTab) appAdapter else apkAdapter
        b.fabPick.visibility = if (appsTab) View.GONE else View.VISIBLE
        b.emptyPick.visibility = if (appsTab) View.GONE else View.VISIBLE
        applyFilter()
    }

    private fun applyFilter() {
        val query = b.searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val count = if (tab == 0) {
            val items = if (query.isEmpty()) allApps else allApps.filter {
                it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
            appAdapter.submitList(items)
            b.emptyText.text = getString(if (query.isEmpty()) R.string.empty_apps else R.string.empty_apps_search)
            items.size
        } else {
            val items = if (query.isEmpty()) allApks else allApks.filter {
                it.label.lowercase().contains(query)
            }
            apkAdapter.submitList(items)
            b.emptyText.text = getString(if (query.isEmpty()) R.string.empty_apk else R.string.empty_apk_search)
            items.size
        }
        b.empty.visibility = if (count == 0) View.VISIBLE else View.GONE
    }

    private fun loadApps() {
        b.empty.visibility = View.GONE
        showProgress(true)
        io.execute {
            val pm = packageManager
            val result = ArrayList<AppEntry>()
            val installed = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList<ApplicationInfo>()
            }
            for (ai in installed) {
                try {
                    val info = pm.getPackageInfo(ai.packageName, 0)
                    var size = File(ai.sourceDir).length()
                    ai.splitSourceDirs?.forEach { size += File(it).length() }
                    result.add(
                        AppEntry(
                            packageName = ai.packageName,
                            label = pm.getApplicationLabel(ai).toString(),
                            versionName = info.versionName ?: "?",
                            sizeBytes = size,
                            isSystem = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                            icon = ai.loadIcon(pm) ?: pm.defaultActivityIcon
                        )
                    )
                } catch (_: Exception) {
                }
            }
            result.sortBy { it.label.lowercase() }
            main.post {
                allApps = result
                showProgress(false)
                applyFilter()
            }
        }
    }

    private fun loadApks() {
        io.execute {
            val found = LinkedHashMap<String, File>()
            collect(found, File(cacheDir, "apks"), 1)
            if (Build.VERSION.SDK_INT <= 32 && hasReadPermission()) {
                try {
                    collect(
                        found,
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        1
                    )
                } catch (_: Exception) {
                }
            }
            val entries = found.values.map {
                ApkEntry(it, it.name, it.length(), it.lastModified())
            }.sortedByDescending { it.modified }
            main.post {
                allApks = entries
                if (tab == 1) applyFilter()
            }
        }
    }

    private fun collect(out: MutableMap<String, File>, dir: File?, depth: Int) {
        if (dir == null || depth < 0) return
        val files = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return
        for (f in files) {
            if (f.isDirectory && depth > 0) {
                collect(out, f, depth - 1)
            } else if (f.isFile && f.name.endsWith(".apk", ignoreCase = true)) {
                out[f.absolutePath] = f
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { importUri(it, installAfter = true) }
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                uri?.let { importUri(it, installAfter = true) }
            }
        }
    }

    private fun importUri(uri: Uri, installAfter: Boolean) {
        showProgress(true)
        io.execute {
            try {
                val display = queryName(uri) ?: "app-${System.currentTimeMillis()}.apk"
                val withExt = if (display.lowercase().endsWith(".apk")) display else "$display.apk"
                val safe = withExt.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app.apk" }
                val dir = File(cacheDir, "apks").apply { mkdirs() }
                var out = File(dir, safe)
                if (out.exists()) out = File(dir, "${System.currentTimeMillis()}-$safe")
                contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("open failed")
                val file = out
                main.post {
                    showProgress(false)
                    loadApks()
                    b.tabs.getTabAt(1)?.select()
                    if (installAfter) askInstall(file) else toast(getString(R.string.imported_fmt, file.name))
                }
            } catch (_: Exception) {
                main.post {
                    showProgress(false)
                    toast(getString(R.string.import_failed))
                }
            }
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun askInstall(file: File) {
        if (!file.exists()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.install_title)
            .setMessage(file.name)
            .setPositiveButton(R.string.btn_install) { _, _ -> installApk(file) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun installApk(file: File) {
        if (!file.exists()) {
            toast(getString(R.string.install_failed))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.install_title)
                .setMessage(R.string.install_blocked)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.install_failed))
        } catch (_: Exception) {
            toast(getString(R.string.install_failed))
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
                .setType(APK_MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(file.name, uri)
            startActivity(Intent.createChooser(intent, getString(R.string.share_via)))
        } catch (_: Exception) {
            toast(getString(R.string.share_failed))
        }
    }

    private fun shareApp(app: AppEntry) {
        showProgress(true)
        io.execute {
            try {
                val src = sourceFile(app.packageName)
                val dir = File(cacheDir, "apks/share").apply { mkdirs() }
                val name = safeName("${app.label}-${app.versionName}") + ".apk"
                var out = File(dir, name)
                if (out.exists()) out = File(dir, "${System.currentTimeMillis()}-$name")
                src.copyTo(out, overwrite = true)
                val file = out
                main.post {
                    showProgress(false)
                    shareFile(file)
                }
            } catch (_: Exception) {
                main.post {
                    showProgress(false)
                    toast(getString(R.string.share_failed))
                }
            }
        }
    }

    private fun saveApp(app: AppEntry) {
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = app
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        showProgress(true)
        io.execute {
            try {
                val src = sourceFile(app.packageName)
                val name = safeName("${app.label}-${app.versionName}") + ".apk"
                val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToDownloads(src, name)
                } else {
                    saveLegacy(src, name)
                }
                main.post {
                    showProgress(false)
                    toast(getString(R.string.saved_fmt, path))
                }
            } catch (_: Exception) {
                main.post {
                    showProgress(false)
                    toast(getString(R.string.save_failed))
                }
            }
        }
    }

    private fun saveToDownloads(src: File, name: String): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/cafShare")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("insert failed")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                src.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("open failed")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "Downloads/cafShare/$name"
    }

    private fun saveLegacy(src: File, name: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "cafShare"
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("mkdir failed")
        src.copyTo(File(dir, name), overwrite = true)
        return "Downloads/cafShare/$name"
    }

    private fun sourceFile(pkg: String): File {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        val base = File(ai.sourceDir)
        if (!base.exists()) throw IOException("no base apk")
        return base
    }

    private fun safeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "app" }

    private fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestStorageIfNeeded() {
        when {
            Build.VERSION.SDK_INT <= 28 -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            Build.VERSION.SDK_INT <= 32 -> {
                if (!hasReadPermission()) {
                    storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private fun chooseApk() {
        pickApk.launch(arrayOf("*/*"))
    }

    private fun onMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            loadApps()
            loadApks()
            toast(getString(R.string.updated))
            true
        }
        R.id.action_about -> {
            showAbout()
            true
        }
        else -> false
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_text, version))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showProgress(show: Boolean) {
        b.progress.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}
