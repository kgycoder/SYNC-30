package com.sync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.lang.ref.WeakReference;

public class MusicService extends Service {

    static final String CH  = "sync_music";
    static final int    NID = 1;

    /** MainActivity → Service 콜백 */
    static Runnable cbPlay, cbPause, cbNext, cbPrev;
    /** 다른 클래스에서 Service 인스턴스 접근용 */
    static WeakReference<MusicService> inst;

    private MediaSessionCompat sess;
    private PowerManager.WakeLock wakeLock;
    private String  title    = "SYNC";
    private String  artist   = "";
    private boolean playing  = false;

    /* ── 생명주기 ── */

    @Override
    public void onCreate() {
        super.onCreate();
        inst = new WeakReference<>(this);
        createChannel();

        // CPU WakeLock: 화면 꺼져도 JS 실행 유지
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SYNC:playback");

        // MediaSession 초기화
        sess = new MediaSessionCompat(this, "SYNCSession");
        sess.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        sess.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()           { run(cbPlay);  }
            @Override public void onPause()          { run(cbPause); }
            @Override public void onSkipToNext()     { run(cbNext);  }
            @Override public void onSkipToPrevious() { run(cbPrev);  }
        });
        sess.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
                .setActions(PlaybackStateCompat.ACTION_PLAY |
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .build());
        sess.setActive(true);

        startForeground(NID, buildNotif());
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) {
        // 알림 버튼 → KeyEvent 처리
        if (i != null) {
            KeyEvent ke = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (ke != null && ke.getAction() == KeyEvent.ACTION_DOWN) {
                switch (ke.getKeyCode()) {
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        if (playing) run(cbPause); else run(cbPlay); break;
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:   run(cbPause); break;
                    case KeyEvent.KEYCODE_MEDIA_NEXT:    run(cbNext);  break;
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:run(cbPrev);  break;
                }
            }
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 앱을 최근 앱에서 스와이프 제거 시 서비스도 중단
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sess != null) { sess.setActive(false); sess.release(); }
        super.onDestroy();
    }

    /* ── 공개 API: JS → 네이티브 정보 업데이트 ── */

    public void update(String t, String a, boolean p, long posMs, long durMs) {
        title = t; artist = a; playing = p;

        // 재생 중 → WakeLock 획득, 정지 → 해제
        if (p && !wakeLock.isHeld())    wakeLock.acquire(3_600_000L);
        else if (!p && wakeLock.isHeld()) wakeLock.release();

        sess.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  t)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, a)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durMs)
                .build());

        sess.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(p ? PlaybackStateCompat.STATE_PLAYING
                            : PlaybackStateCompat.STATE_PAUSED, posMs, 1f)
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE |
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .build());

        NotificationManagerCompat.from(this).notify(NID, buildNotif());
    }

    /* ── 내부 헬퍼 ── */

    private Notification buildNotif() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(artist.isEmpty() ? "SYNC Music" : artist)
                .setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSilent(true)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(sess.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .addAction(R.drawable.ic_prev,  "이전", mediaPI(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                           playing ? "일시정지" : "재생",
                           mediaPI(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                .addAction(R.drawable.ic_next,  "다음", mediaPI(KeyEvent.KEYCODE_MEDIA_NEXT))
                .setOngoing(playing)
                .build();
    }

    private PendingIntent mediaPI(int code) {
        Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(new ComponentName(this, MusicService.class))
                .putExtra(Intent.EXTRA_KEY_EVENT,
                          new KeyEvent(KeyEvent.ACTION_DOWN, code));
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "음악 재생", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
    }

    private static void run(Runnable r) { if (r != null) r.run(); }
}
