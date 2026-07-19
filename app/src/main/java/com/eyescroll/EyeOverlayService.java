package com.eyescroll;
import android.app.*;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.View;
import android.view.WindowManager;
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
    private View cursorView;  // Simple View instead of WebView
    private android.graphics.Canvas canvas;
    private android.graphics.Paint paint;
    private float cursorX=100, cursorY=100;
    private long lastGesture=0;
    private static final long COOLDOWN=1200;

    // Gaze zone tracking
    private float UP_ZONE=0.28f, DOWN_ZONE=0.72f;
    private long zoneEnteredTime=0;
    private String currentZone="centre";
    private boolean zoneFired=false;
    private long lastScroll=0;
    private static final long DWELL_MS=600;
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
        String action=i.getAction();
        if(ACTION_START.equals(action)&&!running){
            startFg();
            createOverlay();
            running=true;
        } else if(ACTION_STOP.equals(action)){
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
            .setContentTitle("EyeScroll Active")
            .setContentText("Eye tracking active - tap Stop to exit")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete,"Stop",pi)
            .setOngoing(true).build());
    }

    private void createOverlay(){
        // Use a simple custom View instead of WebView
        // This GUARANTEES no touch interception
        cursorView = new View(this){
            @Override
            protected void onDraw(android.graphics.Canvas c){
                super.onDraw(c);
                drawCursor(c);
            }
        };

        // Setup paint for arrow cursor
        paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        int type=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O?
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:
            WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p=new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // These flags GUARANTEE touch passthrough
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);

        wm.addView(cursorView, p);
    }

    private void drawCursor(android.graphics.Canvas c){
        float x=cursorX, y=cursorY;

        // Draw arrow cursor
        android.graphics.Path arrow = new android.graphics.Path();
        arrow.moveTo(x, y);
        arrow.lineTo(x+14f, y+38f);
        arrow.lineTo(x+19f, y+23f);
        arrow.lineTo(x+32f, y+18f);
        arrow.close();

        // Shadow
        paint.setColor(0x88000000);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        c.save();
        c.translate(1.5f, 2f);
        c.drawPath(arrow, paint);
        c.restore();

        // White fill
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        c.drawPath(arrow, paint);

        // Cyan outline
        paint.setColor(0xCC00C3FF);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        c.drawPath(arrow, paint);
    }

    // Called from MainActivity to move cursor
    public void moveCursor(float x, float y){
        cursorX = x;
        cursorY = y;
        if(cursorView != null){
            cursorView.postInvalidate();
        }
        // Check gaze zones for gestures
        processZone(x, y);
    }

    private void processZone(float x, float y){
        int H = getResources().getDisplayMetrics().heightPixels;
        String zone = y < H*UP_ZONE ? "up" : y > H*DOWN_ZONE ? "down" : "centre";
        long now = System.currentTimeMillis();

        if(!zone.equals(currentZone)){
            currentZone = zone;
            zoneEnteredTime = now;
            zoneFired = false;
        }

        if(!zoneFired && !zone.equals("centre")){
            if(now - zoneEnteredTime >= DWELL_MS &&
               now - lastScroll > SCROLL_CD){
                zoneFired = true;
                lastScroll = now;
                if(zone.equals("up")) performGesture(2);
                else performGesture(1);
                new Handler(Looper.getMainLooper()).postDelayed(()->{
                    currentZone="centre"; zoneFired=false;
                }, 200);
            }
        }
    }

    private void performGesture(int code){
        EyeAccessibilityService svc=EyeAccessibilityService.getInstance();
        if(svc!=null && Build.VERSION.SDK_INT>=Build.VERSION_CODES.N){
            svc.performGesture(code);
        }
        vibrate(code);
        // Show toast
        String label = code==1?"Next Reel":code==2?"Prev Reel":
                       code==3?"Pause":"Like";
        android.widget.Toast.makeText(this, label,
            android.widget.Toast.LENGTH_SHORT).show();
    }

    // Called from JS bridge for blink gestures
    public void onBlinkGesture(int code){
        long now=System.currentTimeMillis();
        if(now-lastGesture<COOLDOWN)return;
        lastGesture=now;
        performGesture(code);
    }

    private void vibrate(int code){
        try{
            Vibrator v;
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
                VibratorManager vm=(VibratorManager)
                    getSystemService(VIBRATOR_MANAGER_SERVICE);
                v=vm.getDefaultVibrator();
            } else {
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
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
                v.vibrate(VibrationEffect.createWaveform(pat,-1));
            } else {
                v.vibrate(pat,-1);
            }
        }catch(Exception e){}
    }

    private void teardown(){
        running=false;
        instance=null;
        if(cursorView!=null){
            if(wm!=null){
                try{wm.removeView(cursorView);}catch(Exception e){}
            }
            cursorView=null;
        }
        stopForeground(true);
        stopSelf();
    }
}
