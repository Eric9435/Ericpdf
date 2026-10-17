package com.ericpdf.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.text.DateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class LectureRecordingPlan(val startsAt:Long,val durationMinutes:Int)

object LectureScheduler {
    const val OPEN_DOC="com.ericpdf.app.schedule.doc"
    const val OPEN_DURATION="com.ericpdf.app.schedule.duration"
    private fun name(id:String)="lecture-reminder-$id"
    private fun prefs(ctx:Context)=ctx.getSharedPreferences("lecture_plans",Context.MODE_PRIVATE)
    fun read(ctx:Context,id:String):LectureRecordingPlan? {
        val at=prefs(ctx).getLong("$id.at",0L)
        val duration=prefs(ctx).getInt("$id.duration",0)
        return if(at>System.currentTimeMillis() && duration in 1..480) LectureRecordingPlan(at,duration) else null
    }
    fun set(ctx:Context,id:String,title:String,at:Long,minutes:Int) {
        require(at>System.currentTimeMillis()+5000) {"Choose a future time"}
        require(minutes in 1..480) {"Duration must be 1–480 minutes"}
        val work=OneTimeWorkRequestBuilder<LectureReminderWorker>()
            .setInitialDelay(at-System.currentTimeMillis(),TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("docId" to id,"title" to title,"minutes" to minutes)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(name(id),ExistingWorkPolicy.REPLACE,work)
        prefs(ctx).edit().putLong("$id.at",at).putInt("$id.duration",minutes).apply()
    }
    fun cancel(ctx:Context,id:String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(name(id))
        prefs(ctx).edit().remove("$id.at").remove("$id.duration").apply()
    }
}

/** No hidden microphone startup: a visible notification opens the app for consent. */
class LectureReminderWorker(ctx:Context,params:WorkerParameters):Worker(ctx,params) {
    override fun doWork():Result {
        val id=inputData.getString("docId")?:return Result.failure()
        val title=inputData.getString("title")?:"Lecture"
        val mins=inputData.getInt("minutes",180)
        val ctx=applicationContext
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(
                ctx,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        val manager=ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("lecture_reminders",
            "Lecture reminders",NotificationManager.IMPORTANCE_HIGH))
        val open=Intent(ctx,MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(LectureScheduler.OPEN_DOC,id)
            putExtra(LectureScheduler.OPEN_DURATION,mins*60000L)
        }
        val pending=PendingIntent.getActivity(ctx,id.hashCode(),open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(id.hashCode(),NotificationCompat.Builder(ctx,"lecture_reminders")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("EricPDF • Lecture reminder")
            .setContentText("$title • tap to start recording ($mins min auto-stop)")
            .setContentIntent(pending).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build())
        return Result.success()
    }
}

@Composable
fun RecordingScheduleDialog(title:String,initialMinutes:Int,existing:LectureRecordingPlan?,
    onDismiss:()->Unit,onSchedule:(Long,Int)->Unit,onCancel:()->Unit
) {
    val ctx=LocalContext.current
    var start by remember { mutableLongStateOf(existing?.startsAt?:System.currentTimeMillis()+3600000L) }
    var minutes by remember { mutableIntStateOf(existing?.durationMinutes?:initialMinutes) }
    var denied by remember { mutableStateOf(false) }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){
        if(it) onSchedule(start,minutes) else denied=true
    }
    AlertDialog(
        onDismissRequest=onDismiss,title={Text("Lecture audio scheduler")},
        text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall)
            Text("Set a start reminder and recording length. Android will notify you to open the PDF and tap Record. Audio stops automatically after the selected duration.",
                style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={
                val cal=Calendar.getInstance().apply {timeInMillis=start}
                DatePickerDialog(ctx,{_,y,m,d->
                    val selected=Calendar.getInstance().apply {timeInMillis=start;set(y,m,d)}
                    TimePickerDialog(ctx,{_,h,min->
                        selected.set(Calendar.HOUR_OF_DAY,h)
                        selected.set(Calendar.MINUTE,min)
                        selected.set(Calendar.SECOND,0)
                        start=selected.timeInMillis
                    },selected.get(Calendar.HOUR_OF_DAY),selected.get(Calendar.MINUTE),true).show()
                },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show()
            }) {Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(start)))}
            Text("Auto-stop after",style=MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                listOf(30,60,120,180,240).forEach { x->
                    FilterChip(selected=minutes==x,onClick={minutes=x},
                        label={Text(if(x<60) "$x min" else "${x/60} hr")})
                }
            }
            if(denied) Text("Enable notifications in Android Settings to receive the reminder.",
                color=MaterialTheme.colorScheme.error)
            if(existing!=null) TextButton(onClick=onCancel){Text("Cancel scheduled reminder")}
        }},
        confirmButton={Button(enabled=start>System.currentTimeMillis()+5000L,onClick={
            if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(
                    ctx,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
            ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else onSchedule(start,minutes)
        }){Text("Schedule")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Close")}}
    )
}
