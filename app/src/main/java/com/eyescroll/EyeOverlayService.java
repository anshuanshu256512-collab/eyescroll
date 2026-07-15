package com.eyescroll;
import android.app.*;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import android.webkit.*;
import androidx.core.app.NotificationCompat;
public class EyeOverlayService extends Service {
    public static final String ACTION_START="com.eyescroll.START",ACTION_STOP="com.eyescroll.STOP";
    private static final String CH_ID="eyescroll_fg";
    private static final int NID=1001;
    private static boolean running=false;
    public static boolean isRunning(){return running;}
    private WindowManager wm;
    private View root;
    private WebView wv;
    private long lastGesture=0;
    private static final long COOLDOWN=1200;
    @Override public void onCreate(){super.onCreate();wm=(WindowManager)getSystemService(WINDOW_SERVICE);}
    @Override public int onStartCommand(Intent i,int f,int id){
        if(i==null)return START_NOT_STICKY;
        if(ACTION_START.equals(i.getAction())&&!running){startFg();createOverlay();running=true;}
        else if(ACTION_STOP.equals(i.getAction()))teardown();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){teardown();super.onDestroy();}
    private void startFg(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
            NotificationChannel c=new NotificationChannel(CH_ID,"EyeScroll",NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
        Intent si=new Intent(this,EyeOverlayService.class);si.setAction(ACTION_STOP);
        PendingIntent pi=PendingIntent.getService(this,0,si,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(NID,new NotificationCompat.Builder(this,CH_ID)
            .setContentTitle("EyeScroll Active")
            .setContentText("Eye tracking running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_delete,"Stop",pi)
            .setOngoing(true).build());
    }
    private void createOverlay(){
        wv=new WebView(this);
        WebSettings ws=wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setDomStorageEnabled(true);
        wv.setBackgroundColor(0x00000000);
        wv.addJavascriptInterface(new Bridge(),"EyeScroll");
        wv.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest r){r.grant(r.getResources());}
        });
        int type=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
            type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        wm.addView(wv,p);
        wv.loadUrl("file:///android_asset/eyetracker.html");
    }
    private class Bridge {
        @JavascriptInterface public void onGesture(int code){
            long now=System.currentTimeMillis();
            if(now-lastGesture<COOLDOWN)return;
            lastGesture=now;
            EyeAccessibilityService svc=EyeAccessibilityService.getInstance();
            if(svc!=null&&Build.VERSION.SDK_INT>=Build.VERSION_CODES.N)svc.performGesture(code);
        }
        @JavascriptInterface public void onCalibrationDone(){
            if(wv==null||wm==null)return;
            wv.post(()->{WindowManager.LayoutParams lp=(WindowManager.LayoutParams)wv.getLayoutParams();lp.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;wm.updateViewLayout(wv,lp);});
        }
        @JavascriptInterface public void onCalibrationStart(){
            if(wv==null||wm==null)return;
            wv.post(()->{WindowManager.LayoutParams lp=(WindowManager.LayoutParams)wv.getLayoutParams();lp.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;wm.updateViewLayout(wv,lp);});
        }
        @JavascriptInterface public void log(String m){android.util.Log.d("EyeScroll",m);}
    }
    private void teardown(){
        running=false;
        if(wv!=null){wv.loadUrl("about:blank");wv.destroy();wv=null;}
        if(root!=null){try{wm.removeView(root);}catch(Exception e){}root=null;}
        stopForeground(true);stopSelf();
    }
    }
