package com.ericpdf.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * No Firebase / no server. Users grant access to a document-provider folder
 * via ACTION_OPEN_DOCUMENT_TREE. Snapshots are ZIP files in that chosen folder,
 * not app-private storage. They survive app uninstallation on providers that
 * keep documents when an app is removed.
 *
 * A new installation must select the folder again (persisted URI grants and
 * SharedPreferences do NOT survive uninstall).
 */
object AutoBackup {
    private const val PREF="ericpdf_auto_backup"
    private const val TREE="tree_uri"
    private const val LAST_SUCCESS="last_success"
    private const val LAST_FAILURE="last_failure"
    private const val LAST_ATTEMPT="last_schedule_elapsed"
    private const val RECORDING_ACTIVE="recording_active"
    private const val RECORDING_HEARTBEAT="recording_heartbeat"
    private const val ONE_TIME="ericpdf_backup_on_change"
    private const val PERIODIC="ericpdf_backup_periodic"
    private const val PREFIX="EricPDF-auto-"
    private const val MAX_ARCHIVES=3

    private fun prefs(ctx:Context)=ctx.applicationContext.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    fun lastSuccess(ctx:Context):Long=prefs(ctx).getLong(LAST_SUCCESS,0L)
    fun lastError(ctx:Context):String=prefs(ctx).getString(LAST_FAILURE,"").orEmpty()

    fun configuredFolder(ctx:Context):Uri? {
        val uri=prefs(ctx).getString(TREE,null)?.let(Uri::parse) ?: return null
        val allowed=ctx.contentResolver.persistedUriPermissions.any {
            it.uri==uri && it.isReadPermission && it.isWritePermission
        }
        return if(allowed)uri else null
    }
    fun hasFolder(ctx:Context)=configuredFolder(ctx)!=null

    fun chooseFolder(ctx:Context,uri:Uri) {
        val flags=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ctx.contentResolver.takePersistableUriPermission(uri,flags)
        val folder=DocumentFile.fromTreeUri(ctx,uri)
        require(folder!=null && folder.isDirectory && folder.canWrite()) {
            "Choose a writable Documents folder"
        }
        prefs(ctx).edit().putString(TREE,uri.toString())
            .remove(LAST_FAILURE).apply()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(6,TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .build()
        )
        changed(ctx,now=true)
    }

    fun disconnect(ctx:Context) {
        val original=prefs(ctx).getString(TREE,null)?.let(Uri::parse)
        WorkManager.getInstance(ctx).cancelUniqueWork(ONE_TIME)
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        prefs(ctx).edit().remove(TREE).apply()
        if(original!=null) {
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(original,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    fun changed(ctx:Context,now:Boolean=false) {
        if(!hasFolder(ctx))return
        // Don't run a WorkManager enqueue operation on every typed character.
        // The one scheduled snapshot captures all subsequent edits as well.
        val elapsed=SystemClock.elapsedRealtime()
        val p=prefs(ctx)
        val prev=p.getLong(LAST_ATTEMPT,0L)
        if(!now && prev!=0L && elapsed>=prev && elapsed-prev<60000L)return
        p.edit().putLong(LAST_ATTEMPT,elapsed).apply()
        val job=OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInitialDelay(if(now)0L else 90L,TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,5,TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONE_TIME,
            if(now)ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,job)
    }

    fun recording(ctx:Context,active:Boolean) {
        prefs(ctx).edit().putBoolean(RECORDING_ACTIVE,active)
            .putLong(RECORDING_HEARTBEAT,System.currentTimeMillis()).apply()
        if(!active)changed(ctx,now=true)
    }
    fun heartbeat(ctx:Context) {
        if(hasFolder(ctx))prefs(ctx).edit().putLong(RECORDING_HEARTBEAT,System.currentTimeMillis()).apply()
    }
    fun isRecording(ctx:Context):Boolean {
        val p=prefs(ctx)
        val age=System.currentTimeMillis()-p.getLong(RECORDING_HEARTBEAT,0L)
        return p.getBoolean(RECORDING_ACTIVE,false) && age in 0..120000L
    }

    fun newest(ctx:Context):DocumentFile? = archives(ctx).maxByOrNull { it.name.orEmpty() }
    fun archives(ctx:Context):List<DocumentFile> {
        val uri=configuredFolder(ctx) ?: return emptyList()
        val tree=DocumentFile.fromTreeUri(ctx,uri) ?: return emptyList()
        return tree.listFiles().filter {
            it.isFile && it.name.orEmpty().startsWith(PREFIX) &&
                it.name.orEmpty().endsWith(".zip")
        }.sortedByDescending { it.name.orEmpty() }
    }

    internal fun snapshot(ctx:Context) {
        // Avoid exporting a partial live WAV while the lecture recorder writes.
        if(isRecording(ctx))error("Recording active; will retry backup after recording stops")
        if(LocalLibrary(ctx).list().isEmpty())return // do not overwrite good backups with an empty library.
        val uri=configuredFolder(ctx) ?: error("Backup folder permission was removed")
        val folder=DocumentFile.fromTreeUri(ctx,uri)
            ?: error("Backup folder is no longer accessible")
        require(folder.isDirectory && folder.canWrite()){"Backup folder is not writable"}
        val stamp=SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(Date())
        val target=folder.createFile("application/zip","$PREFIX$stamp.zip")
            ?: error("Unable to create external backup")
        try {
            LocalBackup.export(ctx,target.uri)
            // A successful export finished closing its ZIP before older backups are pruned.
            // Keep 3 previous points in time including the new snapshot.
            prefs(ctx).edit().putLong(LAST_SUCCESS,System.currentTimeMillis())
                .remove(LAST_FAILURE).apply()
            archives(ctx).drop(MAX_ARCHIVES).forEach { old ->
                runCatching {old.delete()} // provider may refuse deletion; don't invalidate good backup
            }
        } catch(e:Exception) {
            runCatching {target.delete()} // don't present an incomplete ZIP as restorable
            throw e
        }
    }
    internal fun failure(ctx:Context,message:String) {
        prefs(ctx).edit().putString(LAST_FAILURE,message.take(200)).apply()
    }
}

class AutoBackupWorker(ctx:Context,params:WorkerParameters):CoroutineWorker(ctx,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        if(!AutoBackup.hasFolder(applicationContext))return@withContext Result.success()
        try {
            AutoBackup.snapshot(applicationContext)
            Result.success()
        } catch(ex:Exception) {
            AutoBackup.failure(applicationContext,ex.message ?: "Backup unavailable")
            if(runAttemptCount<4)Result.retry() else Result.failure()
        }
    }
}
