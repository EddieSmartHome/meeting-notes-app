package tw.eddiehsu.meetingnotes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * 錄音中的前景服務（麥克風類型）。
 *
 * Android 會讓進入背景的 App 只收到靜音；錄音時啟動這個服務，
 * 系統就把 App 視為「前景使用麥克風」，螢幕關閉或切到其他 App 時仍能收音。
 * 通知列會常駐「錄音中」，點通知回到 App。實際錄音仍由網頁層的 MediaRecorder 負責。
 */
public class RecordingService extends Service {
    static final String CHANNEL_ID = "recording";
    static final int NOTIFICATION_ID = 1001;
    static final String EXTRA_TEXT = "text";

    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;
        if (text == null || text.isEmpty()) text = "錄音中，螢幕關閉也會持續收音";
        createChannel();

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("會議速記")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type);

        // 讓 CPU 在螢幕關閉時繼續運作，網頁的切段計時才不會停
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meetingnotes:recording");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(6 * 60 * 60 * 1000L); // 最長 6 小時，避免異常時一直不放
            }
        }
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "錄音中", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("錄音時常駐的通知，讓螢幕關閉時也能持續收音");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 使用者從最近使用的 App 中滑掉 App：網頁已經不在，服務也一起結束
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
