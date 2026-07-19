package com.eyescroll;
import android.app.*;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.Gravity;
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
    private static EyeOverlayService instance=null;

    public static boolean isRunning(){return running;}
    public static EyeOverlayService getInstance(){return instance;}

    private WindowManager wm;
    private WebView wv;           // WebView - fullscreen during calib, tiny after
    private View cursorView;      // Arrow cursor - shown after calibration
    private WindowManager.LayoutParams wvParams;  // WebView window params
    private android.graphics.Paint paint;
    private float cursorX=200, cursorY=400;
    private boolean calibrationComplete=false;
    private long lastGesture=0;
    private static final long COOLDOWN=1200;
    private float UP_ZONE=0.28f, DOWN_ZONE=0.72f;
    private long zoneEnteredTime=0;
    private String currentZone="centre";
    private boolean zoneFired=false;
    private long lastScroll=0;
    private static final long DWELL_MS=700;
    private static final long SCROLL_CD=1500;

    @Override
    public void onCreate(){
        super.onCreate();
        instance=this;
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent i,int f,int id){
        if(i==null)return START_NOT_STICKY;
        if(ACTION_START.equals(i.getAction())&&!running){
            startFg();
            createViews();
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
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(NID,new NotificationCompat.Builder(this,CH_ID)
            .setContentTitle("EyeScroll - Calibrating...")
            .setContentText("Look at the dots to calibrate")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_delete,"Stop",pi)
            .setOngoing(true).build());
    }

    private void createViews(){
        int type=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O?
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:
            WindowManager.LayoutParams.TYPE_PHONE;

        // ── Step 1: Create FULLSCREEN WebView for calibration ──
        wv=new WebView(this);
        WebSettings ws=wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        wv.setLayerType(View.LAYER_TYPE_HARDWARE,null);
        wv.setBackgroundColor(0xFF040D1A);
        wv.addJavascriptInterface(new EyeBridge(),"EyeScroll");
        wv.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest r){
                r.grant(r.getResources());
            }
        });

        // Fullscreen + TOUCHABLE during calibration
        wvParams=new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE);
        wm.addView(wv,wvParams);
        wv.loadUrl("file:///android_asset/eyetracker.html");

        // ── Step 2: Create transparent cursor view (hidden until calib done) ──
        paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        cursorView=new View(this){
            @Override protected void onDraw(android.graphics.Canvas c){
                super.onDraw(c);
                drawArrow(c);
            }
        };
        cursorView.setVisibility(View.INVISIBLE);

        WindowManager.LayoutParams cp=new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);
        wm.addView(cursorView,cp);
    }

    private void onCalibrationComplete(){
        calibrationComplete=true;

        // Update notification
        updateNotification("EyeScroll Active - Eye tracking running");

        // Shrink WebView to 1x1 invisible - keeps WebGazer running!
        new Handler(Looper.getMainLooper()).post(()->{
            if(wv==null||wm==null)return;
            try{
                wvParams.width=1;
                wvParams.height=1;
                wvParams.gravity=Gravity.TOP|Gravity.START;
                wvParams.x=0; wvParams.y=0;
                // Make it touch-transparent now
                wvParams.flags=
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                wv.setBackgroundColor(0x00000000);
                wm.updateViewLayout(wv,wvParams);
            }catch(Exception e){}

            // Show cursor
            if(cursorView!=null)
                cursorView.setVisibility(View.VISIBLE);
        });
    }

    private void updateNotification(String text){
        Intent si=new Intent(this,EyeOverlayService.class);
        si.setAction(ACTION_STOP);
        PendingIntent pi=PendingIntent.getService(this,0,si,
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new NotificationCompat.Builder(this,CH_ID)
            .setContentTitle("EyeScroll Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_delete,"Stop",pi)
            .setOngoing(true).build();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.notify(NID,n);
    }

    private void drawArrow(android.graphics.Canvas c){
        float x=cursorX,y=cursorY;
        android.graphics.Path arrow=new android.graphics.Path();
        arrow.moveTo(x,y);
        arrow.lineTo(x+14f,y+38f);
        arrow.lineTo(x+19f,y+23f);
        arrow.lineTo(x+32f,y+18f);
        arrow.close();
        paint.setColor(0x88000000);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        c.save();c.translate(1.5f,2f);c.drawPath(arrow,paint);c.restore();
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        c.drawPath(arrow,paint);
        paint.setColor(0xCC00C3FF);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        c.drawPath(arrow,paint);
    }

    private class EyeBridge{

        @JavascriptInterface
        public void onGaze(float x,float y){
            if(!calibrationComplete)return;
            cursorX=x; cursorY=y;
            if(cursorView!=null) cursorView.postInvalidate();
            processZone(x,y);
        }

        @JavascriptInterface
        public void onCalibrationDone(){
            onCalibrationComplete();
            // Tell user to open YouTube
            new Handler(Looper.getMainLooper()).post(()->
                android.widget.Toast.makeText(
                    EyeOverlayService.this,
                    "Calibration done! Open YouTube or Instagram now.",
                    android.widget.Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void onGesture(int code){
            if(!calibrationComplete)return;
            long now=System.currentTimeMillis();
            if(now-lastGesture<COOLDOWN)return;
            lastGesture=now;
            performGesture(code);
        }

        @JavascriptInterface
        public void onCalibrationStart(){}

        @JavascriptInterface
        public void stopService(){teardown();}

        @JavascriptInterface
        public void log(String m){android.util.Log.d("EyeScroll",m);}

        @JavascriptInterface
        public void launchOverlay(){ onCalibrationComplete(); }
    }

    private void processZone(float x,float y){
        int H=getResources().getDisplayMetrics().heightPixels;
        String zone=y<H*UP_ZONE?"up":y>H*DOWN_ZONE?"down":"centre";
        long now=System.currentTimeMillis();
        if(!zone.equals(currentZone)){
            currentZone=zone;zoneEnteredTime=now;zoneFired=false;
        }
        if(!zoneFired&&!zone.equals("centre")){
            if(now-zoneEnteredTime>=DWELL_MS&&now-lastScroll>SCROLL_CD){
                zoneFired=true;lastScroll=now;
                if(zone.equals("up"))performGesture(2);
                else performGesture(1);
                new Handler(Looper.getMainLooper()).postDelayed(()->{
                    currentZone="centre";zoneFired=false;
                },200);
            }
        }
    }

    private void performGesture(int code){
        EyeAccessibilityService svc=EyeAccessibilityService.getInstance();
        if(svc!=null&&Build.VERSION.SDK_INT>=Build.VERSION_CODES.N)
            svc.performGesture(code);
        vibrate(code);
        String label=code==1?"Next Reel":code==2?"Prev Reel":
                     code==3?"Pause/Play":"Like";
        new Handler(Looper.getMainLooper()).post(()->
            android.widget.Toast.makeText(this,label,
                android.widget.Toast.LENGTH_SHORT).show());
    }

    private void vibrate(int code){
        try{
            Vibrator v;
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
                VibratorManager vm=(VibratorManager)
                    getSystemService(VIBRATOR_MANAGER_SERVICE);
                v=vm.getDefaultVibrator();
            }else{
                v=(Vibrator)getSystemService(VIBRATOR_SERVICE);
            }
            if(v==null)return;
            long[]pat;
            switch(code){
                case 1:case 2:pat=new long[]{0,60};break;
                case 3:pat=new long[]{0,80};break;
                case 4:pat=new long[]{0,60,80,60};break;
                default:pat=new long[]{0,60,60,60,60,60};break;
            }
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createWaveform(pat,-1));
            else v.vibrate(pat,-1);
        }catch(Exception e){}
    }

    private void teardown(){
        running=false;instance=null;
        if(wv!=null){
            try{wm.removeView(wv);wv.destroy();}catch(Exception e){}
            wv=null;
        }
        if(cursorView!=null){
            try{wm.removeView(cursorView);}catch(Exception e){}
            cursorView=null;
        }
        stopForeground(true);stopSelf();
    }
    }
