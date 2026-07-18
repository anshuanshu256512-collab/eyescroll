package com.eyescroll;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.provider.Settings;
public class EyeAccessibilityService extends AccessibilityService {
    public static final int GESTURE_SWIPE_DOWN=1,GESTURE_SWIPE_UP=2,
        GESTURE_TAP=3,GESTURE_DOUBLE_TAP=4,GESTURE_SUBSCRIBE=5;
    private static EyeAccessibilityService instance;
    public static EyeAccessibilityService getInstance(){return instance;}
    public static boolean isEnabled(Context ctx){
        String s=Settings.Secure.getString(
            ctx.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s!=null&&s.contains(
            ctx.getPackageName()+"/"+
            EyeAccessibilityService.class.getName());
    }
    @Override public void onServiceConnected(){
        super.onServiceConnected();instance=this;
    }
    @Override public void onDestroy(){
        super.onDestroy();instance=null;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){}
    public void performGesture(int type){
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N)return;
        int[]sz=getScreenSize();
        int W=sz[0],H=sz[1],cx=W/2,cy=H/2;
        switch(type){
            case GESTURE_SWIPE_DOWN:
                doSwipe(cx,(int)(H*.75f),cx,(int)(H*.25f),350);break;
            case GESTURE_SWIPE_UP:
                doSwipe(cx,(int)(H*.25f),cx,(int)(H*.75f),350);break;
            case GESTURE_TAP:doTap(cx,cy);break;
            case GESTURE_DOUBLE_TAP:doDoubleTap(cx,cy);break;
            case GESTURE_SUBSCRIBE:doTap(cx,(int)(H*.85f));break;
        }
    }
    private void doSwipe(int x1,int y1,int x2,int y2,long d){
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N)return;
        Path p=new Path();p.moveTo(x1,y1);p.lineTo(x2,y2);
        dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p,0,d))
            .build(),null,null);
    }
    private void doTap(int x,int y){
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N)return;
        Path p=new Path();p.moveTo(x,y);
        dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p,0,100))
            .build(),null,null);
    }
    private void doDoubleTap(int x,int y){
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N)return;
        Path p1=new Path();p1.moveTo(x,y);
        Path p2=new Path();p2.moveTo(x,y);
        dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p1,0,100))
            .addStroke(new GestureDescription.StrokeDescription(p2,250,100))
            .build(),null,null);
    }
    private int[]getScreenSize(){
        WindowManager wm=(WindowManager)getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm=new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels,dm.heightPixels};
    }
}
