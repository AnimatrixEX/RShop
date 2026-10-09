package com.rshop.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.rshop.BuildConfig
import com.rshop.di.ApplicationScope
import com.rshop.domain.repository.SettingsRepository
import com.rshop.download.IntegrityVerifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class UpdateErrorKind {
    /** No connection, DNS or timeout. */
    Network,

    /** GitHub refused the request (rate limit) or answered with an error. */
    Server,

    /** The repository has no published release yet. */
    NoRelease,

    /** The latest release has no usable APK. */
    BadRelease,

    /** "Wi-Fi only" is on and the connection is metered. */
    Metered,

    /** Size or SHA-256 differs from what the release announced. */
    Corrupt,

    /** The file is not an RShop APK. */
    NotRShop,

    /** Android does not let RShop install apps yet ("Install unknown apps"). */
    InstallPermission,

    /** The system installer refused the file (signature, downgrade, space…). */
    InstallFailed,
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val bytes: Long) : UpdateState
    data class Verifying(val release: AppRelease) : UpdateState

    /** The system installer has the file; the user confirms there. */
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val kind: UpdateErrorKind, val detail: String? = null, val release: AppRelease? = null) : UpdateState
}

/**
 * Looks for a newer RShop in the GitHub releases of the project, downloads its APK and hands it
 * to Android's installer. Nothing is checked in the background: it only runs when the user
 * asks. The installer itself verifies that the APK carries the same signature as the installed
 * app, so a file from anywhere else could not replace it; RShop also checks the SHA-256 GitHub
 * publishes for the file, and the package name, before bothering the installer.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    // Releases are read fresh, and a 190 MB file has no business in the HTTP cache.
    private val client = okHttpClient.newBuilder().cache(null).build()
    private val directory = File(context.cacheDir, "update")

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    /** The package an update must be for (the debug build updates the release package). */
    private val targetPackage = BuildConfig.APPLICATION_ID.removeSuffix(".debug")

    /** Asks GitHub for the latest release. */
    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading || _state.value is UpdateState.Verifying) return
        launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val release = fetchLatest()
                val current = AppVersion.parse(BuildConfig.VERSION_NAME)
                val latest = AppVersion.parse(release.version)
                if (current != null && latest != null && latest > current) {
                    UpdateState.Available(release)
                } else {
                    clearCache()
                    UpdateState.UpToDate(BuildConfig.VERSION_NAME)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                UpdateState.Failed(e.kind, e.message)
            } catch (e: ReleaseParseException) {
                Timber.w(e, "Unusable release")
                UpdateState.Failed(UpdateErrorKind.BadRelease, e.message)
            } catch (e: IOException) {
                UpdateState.Failed(UpdateErrorKind.Network, e.message)
            }
        }
    }

    /** Downloads the release (resuming a partial file) and starts the installation. */
    fun install(release: AppRelease) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Verifying) return
        launch {
            try {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    _state.value = UpdateState.Failed(UpdateErrorKind.InstallPermission, release = release)
                    return@launch
                }
                val file = download(release)
                _state.value = UpdateState.Installing(release)
                commit(file, release)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(release)
                throw e
            } catch (e: UpdateException) {
                Timber.w(e, "Update failed")
                _state.value = UpdateState.Failed(e.kind, e.message, release)
            } catch (e: IOException) {
                Timber.w(e, "Update download failed")
                _state.value = UpdateState.Failed(UpdateErrorKind.Network, e.message, release)
            }
        }
    }

    /** Stops the download; the part already received is kept for the next try. */
    fun cancel() {
        val current = _state.value
        job?.cancel()
        val release = when (current) {
            is UpdateState.Downloading -> current.release
            is UpdateState.Verifying -> current.release
            is UpdateState.Installing -> current.release
            else -> return
        }
        _state.value = UpdateState.Available(release)
    }

    /** Back to the idle state (e.g. after reading an error). */
    fun dismiss() {
        if (_state.value is UpdateState.Failed || _state.value is UpdateState.UpToDate) _state.value = UpdateState.Idle
    }

    private fun launch(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) { block() }
    }

    private suspend fun fetchLatest(): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> throw UpdateException(UpdateErrorKind.NoRelease, "no release")
                response.code == 403 || response.code == 429 -> throw UpdateException(UpdateErrorKind.Server, "HTTP ${response.code} (too many requests, try again later)")
                !response.isSuccessful -> throw UpdateException(UpdateErrorKind.Server, "HTTP ${response.code}")
            }
            ReleaseParser.parse(response.body.string())
        }
    }

    // --- Download ---------------------------------------------------------------------

    private suspend fun download(release: AppRelease): File = withContext(Dispatchers.IO) {
        if (settings.settings.first().wifiOnly && isMetered()) {
            throw UpdateException(UpdateErrorKind.Metered, "metered connection")
        }
        directory.mkdirs()
        // Files of other versions are of no use any more.
        directory.listFiles()?.filter { !it.name.startsWith("RShop-${release.version}.apk") }?.forEach { it.delete() }
        val target = File(directory, "RShop-${release.version}.apk")
        val part = File(directory, "RShop-${release.version}.apk.part")

        // A complete, checked file from an earlier try is reused (the user cancelled the installer).
        if (target.isFile && target.length() == release.sizeBytes && matchesHash(target, release)) return@withContext target
        target.delete()

        var have = if (part.isFile && part.length() < release.sizeBytes) part.length() else 0L
        if (have == 0L) part.delete()
        _state.value = UpdateState.Downloading(release, have)

        val request = Request.Builder().url(release.apkUrl).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UpdateException(UpdateErrorKind.Server, "HTTP ${response.code}")
            // 200 to a Range request: the server restarts from the beginning.
            val append = response.code == 206 && have > 0
            if (!append) have = 0
            val body = response.body
            java.io.FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var lastReport = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        have += n
                        val now = System.nanoTime()
                        if (now - lastReport > REPORT_INTERVAL_NANOS) {
                            lastReport = now
                            _state.value = UpdateState.Downloading(release, have)
                        }
                    }
                }
            }
        }
        _state.value = UpdateState.Verifying(release)
        if (part.length() != release.sizeBytes) {
            // Cut short: keep the part so the next try resumes; too long: it is wrong, start over.
            if (part.length() > release.sizeBytes) part.delete()
            throw UpdateException(UpdateErrorKind.Corrupt, "${part.length()} of ${release.sizeBytes} bytes")
        }
        if (!matchesHash(part, release)) {
            part.delete()
            throw UpdateException(UpdateErrorKind.Corrupt, "SHA-256 differs from the published one")
        }
        if (!part.renameTo(target)) throw IOException("Cannot move ${part.name}")
        target
    }

    private suspend fun matchesHash(file: File, release: AppRelease): Boolean =
        release.sha256 == null || IntegrityVerifier.sha256(file).equals(release.sha256, ignoreCase = true)

    private fun isMetered(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.isActiveNetworkMetered
    }

    private fun clearCache() {
        directory.listFiles()?.forEach { it.delete() }
    }

    // --- Installation -------------------------------------------------------------------

    private fun commit(file: File, release: AppRelease) {
        val archive = context.packageManager.getPackageArchiveInfo(file.path, 0)
        if (archive == null || archive.packageName != targetPackage) {
            file.delete()
            throw UpdateException(UpdateErrorKind.NotRShop, archive?.packageName)
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(targetPackage)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val action = "${context.packageName}.UPDATE_INSTALL_RESULT.$sessionId"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onInstallStatus(this, c, intent, release)
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("rshop.apk", 0, file.length()).use { out ->
                        input.copyTo(out, 128 * 1024)
                        session.fsync(out)
                    }
                }
                // The result carries an Intent the installer fills in: the PendingIntent must be mutable.
                val callback = PendingIntent.getBroadcast(
                    context, sessionId, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
            throw if (e is IOException || e is SecurityException) UpdateException(UpdateErrorKind.InstallFailed, e.message) else e
        }
    }

    private fun onInstallStatus(receiver: BroadcastReceiver, c: Context, intent: Intent, release: AppRelease) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system's own confirmation screen.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The new version replaces this process; nothing more to do.
                runCatching { c.unregisterReceiver(receiver) }
                clearCache()
            }
            else -> {
                runCatching { c.unregisterReceiver(receiver) }
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Timber.w("Update install ended with status %d: %s", status, message)
                // The user closing the confirmation is not an error: the file stays ready.
                _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    UpdateState.Available(release)
                } else {
                    UpdateState.Failed(UpdateErrorKind.InstallFailed, message, release)
                }
            }
        }
    }

    private class UpdateException(val kind: UpdateErrorKind, detail: String?) : Exception(detail)

    private companion object {
        const val REPORT_INTERVAL_NANOS = 250_000_000L
    }
}
