package com.bubbleladder.mhsunweightedv1;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutoDrawService extends Service{
    public static final String CHANNEL_ID="bubble_autofind_v13_live";
    public static final int NOTI_ID=5211;
    private final Handler h=new Handler(Looper.getMainLooper());
    private final ExecutorService ex=Executors.newSingleThreadExecutor();
    private volatile boolean syncing=false,destroyed=false;
    private int retry=0;
    private static final int MAX_LATE_RETRY=12;
    private static final long LATE_RETRY_MS=10000L;

    private final Runnable notificationTick=new Runnable(){@Override public void run(){
        if(destroyed)return;
        updateNotificationSafe();
        long left=FlowCore.millisToNextDraw();
        h.postDelayed(this,left<=30000L?1000L:5000L);
    }};
    private final Runnable fetchTask=new Runnable(){@Override public void run(){if(!destroyed)doSync();}};

    @Override public void onCreate(){
        super.onCreate();
        try{
            createChannel();
            // startForeground는 서비스 생성 직후 즉시 호출해 Android 14~16 제한에 안전하게 대응한다.
            startForeground(NOTI_ID,buildNotification());
            h.post(notificationTick);
            h.post(fetchTask);
        }catch(Throwable e){
            FlowCore.prefs(this).edit().putBoolean(FlowCore.K_AUTO,false).apply();
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!FlowCore.prefs(this).getBoolean(FlowCore.K_AUTO,false)){
            stopSelf();
            return START_NOT_STICKY;
        }
        if(!syncing&&!destroyed){h.removeCallbacks(fetchTask);h.post(fetchTask);}
        // 시스템이 임의 재시작하면서 백그라운드 FGS 제한에 걸리지 않도록 자동 재생성하지 않는다.
        return START_NOT_STICKY;
    }

    private void doSync(){
        if(syncing||destroyed)return;
        syncing=true;
        try{
            ex.execute(()->{
                boolean advanced=false;
                try{advanced=FlowCore.sync(this).newRoundResolved;}catch(Throwable ignored){}
                final boolean ok=advanced;
                h.post(()->{
                    if(destroyed)return;
                    syncing=false;
                    try{sendBroadcast(new Intent(FlowCore.ACTION_UPDATED).setPackage(getPackageName()));}catch(Throwable ignored){}
                    updateNotificationSafe();
                    h.removeCallbacks(fetchTask);
                    if(ok){retry=0;scheduleAtNextDraw();}
                    else if(retry<MAX_LATE_RETRY){retry++;h.postDelayed(fetchTask,LATE_RETRY_MS);}
                    else{retry=0;scheduleAtNextDraw();}
                });
            });
        }catch(Throwable e){
            syncing=false;
            scheduleAtNextDraw();
        }
    }

    private void scheduleAtNextDraw(){
        if(destroyed)return;
        long delay=Math.max(15000L,FlowCore.millisToNextDraw()+7000L);
        h.postDelayed(fetchTask,delay);
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"AUTO FIND V1.3 자동추첨",NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("보글사다리 AUTO FIND 자동추천 픽과 다음 추첨 시간을 표시합니다.");
            NotificationManager nm=getSystemService(NotificationManager.class);
            if(nm!=null)nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        android.content.SharedPreferences sp=FlowCore.prefs(this);
        String pick=sp.getString(FlowCore.K_LAST_PICK,"분석 대기");
        String text="다음 "+FlowCore.countdownText()+" · AUTO FIND "+pick+" · 실전 "+FlowCore.liveRate(this);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("보글사다리 AUTO FIND V1.3 · 백그라운드 ON")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build();
    }

    private void updateNotificationSafe(){
        try{
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm!=null)nm.notify(NOTI_ID,buildNotification());
        }catch(Throwable ignored){}
    }

    // Android 15+ dataSync FGS 시간 제한에 도달하면 시스템 예외 대신 정상 종료한다.
    @Override public void onTimeout(int startId,int fgsType){
        FlowCore.prefs(this).edit().putBoolean(FlowCore.K_AUTO,false).apply();
        try{stopForeground(STOP_FOREGROUND_REMOVE);}catch(Throwable ignored){}
        stopSelf();
    }

    @Override public void onDestroy(){
        destroyed=true;
        h.removeCallbacksAndMessages(null);
        try{ex.shutdownNow();}catch(Throwable ignored){}
        try{stopForeground(STOP_FOREGROUND_REMOVE);}catch(Throwable ignored){}
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
