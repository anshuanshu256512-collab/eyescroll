package com.eyescroll;
import android.app.*;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import androidx.core.app.NotificationCompat;

public class EyeOverlayService extends Service {

    public static final String ACTION_START="com.eyescroll.START";
    public static final String ACTION_STOP="com.eyescroll.STOP";
    private static final String CH_ID="eyescroll_fg";
    private static final int NID=1001;
    private static boolean running=false;
    public static boolean isRunning(){return running;}

    private WindowManager wm;
    private WebView wv;
    private long lastGesture=0;
    private static final long COOLDOWN=1200;

    @Override
    public void onCreate(){
        super.onCreate();
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent i,int f,int id){
        if(i==null)return START_NOT_STICKY;
        if(ACTION_START.equals(i.getAction())&&!running){
            startFg();
            createOverlay();
            running=true;
        } else if(ACTION_STOP.equals(i.getAction())){
            teardown();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){teardown();super.onDestroy();}

    private void startFg(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
            NotificationChannel c=new NotificationChannel(
                CH_ID,"EyeScroll",NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class)
                .createNotificationChannel(c);
        }
        Intent si=new Intent(this,EyeOverlayService.class);
        si.setAction(ACTION_STOP);
        PendingIntent pi=PendingIntent.getService(this,0,si,
            PendingIntent.FLAG_IMMUTABLE|
            PendingIntent.FLAG_UPDATE_CURRENT);

        Intent open=new Intent(this,MainActivity.class);
        PendingIntent openPi=PendingIntent.getActivity(this,1,open,
            PendingIntent.FLAG_IMMUTABLE);

        startForeground(NID,new NotificationCompat.Builder(this,CH_ID)
            .setContentTitle("👁 EyeScroll Active")
            .setContentText("Eye control running — tap to open app")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete,"Stop",pi)
            .setOngoing(true)
            .build());
    }

    private void createOverlay(){
        wv=new WebView(this);
        WebSettings ws=wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setDomStorageEnabled(true);

        // Transparent background
        wv.setBackgroundColor(0x00000000);
        wv.setLayerType(View.LAYER_TYPE_HARDWARE,null);

        wv.addJavascriptInterface(new CursorBridge(),"EyeScroll");

        wv.setWebChromeClient(new WebChromeClient(){
            @Override
            public void onPermissionRequest(PermissionRequest r){
                r.grant(r.getResources());
            }
        });

        int type=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O?
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:
            WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p=new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // KEY: Touch passes through to YouTube/Instagram
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);

        wm.addView(wv,p);

        // Load cursor-only overlay (no WebGazer, no internet needed)
        wv.loadUrl("file:///android_asset/cursor_overlay.html");
    }

    // Move cursor from gaze data
    public void moveCursor(float x, float y){
        if(wv==null)return;
        wv.post(()->wv.evaluateJavascript(
            "window.moveCursor("+x+","+y+")",null));
    }

    private class CursorBridge{

        @JavascriptInterface
        public void onGesture(int code){
            long now=System.currentTimeMillis();
            if(now-lastGesture<COOLDOWN)return;
            lastGesture=now;
            EyeAccessibilityService svc=
                EyeAccessibilityService.getInstance();
            if(svc!=null&&
               Build.VERSION.SDK_INT>=Build.VERSION_CODES.N){
                svc.performGesture(code);
            }
            vibrate(code);
        }

        @JavascriptInterface
        public void stopService(){ teardown(); }

        @JavascriptInterface
        public void log(String m){
            android.util.Log.d("EyeScroll",m);
        }
    }

    private void vibrate(int code){
        try{
            android.os.Vibrator v;
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
                android.os.VibratorManager vm=
                    (android.os.VibratorManager)
                    getSystemService(VIBRATOR_MANAGER_SERVICE);
                v=vm.getDefaultVibrator();
            } else {
                v=(android.os.Vibrator)getSystemService(VIBRATOR_SERVICE);
            }
            if(v==null)return;
            long[]pat;
            switch(code){
                case 1:case 2: pat=new long[]{0,60}; break;
                case 3: pat=new long[]{0,80}; break;
                case 4: pat=new long[]{0,60,80,60}; break;
                default: pat=new long[]{0,60,60,60,60,60}; break;
            }
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
                v.vibrate(android.os.VibrationEffect
                    .createWaveform(pat,-1));
            } else {
                v.vibrate(pat,-1);
            }
        }catch(Exception e){}
    }

    private void teardown(){
        running=false;
        if(wv!=null){
            try{
                wv.loadUrl("about:blank");
                wv.destroy();
            }catch(Exception e){}
            if(wm!=null){
                try{wm.removeView(wv);}catch(Exception e){}
            }
            wv=null;
        }
        stopForeground(true);
        stopSelf();
    }
}
