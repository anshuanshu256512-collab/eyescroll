package com.eyescroll;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private TextView statusCamera,statusOverlay,statusAccessibility;
    private Button btnLaunch,btnStop;

    private final ActivityResultLauncher<String> cameraLauncher=
        registerForActivityResult(new ActivityResultContracts.RequestPermission(),
            granted->updateStatuses());
    private final ActivityResultLauncher<Intent> overlayLauncher=
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result->updateStatuses());
    private final ActivityResultLauncher<Intent> accessibilityLauncher=
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result->updateStatuses());

    @Override
    protected void onCreate(Bundle s){
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        statusCamera=findViewById(R.id.status_camera);
        statusOverlay=findViewById(R.id.status_overlay);
        statusAccessibility=findViewById(R.id.status_accessibility);
        btnLaunch=findViewById(R.id.btn_launch);
        btnStop=findViewById(R.id.btn_stop);
        btnLaunch.setOnClickListener(v->onLaunch());
        btnStop.setOnClickListener(v->stopEyeScroll());

        // Check if returning from calibration done
        if("com.eyescroll.CALIB_DONE".equals(getIntent().getAction())){
            Toast.makeText(this,
                "Calibration done! Open YouTube and control with eyes!",
                Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume(){
        super.onResume();
        updateStatuses();
    }

    private void updateStatuses(){
        boolean cam=ContextCompat.checkSelfPermission(this,
            Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
        boolean ovl=Build.VERSION.SDK_INT<Build.VERSION_CODES.M
            ||Settings.canDrawOverlays(this);
        boolean acc=EyeAccessibilityService.isEnabled(this);
        boolean run=EyeOverlayService.isRunning();
        setStatus(statusCamera,cam);
        setStatus(statusOverlay,ovl);
        setStatus(statusAccessibility,acc);
        btnLaunch.setVisibility(run?View.GONE:View.VISIBLE);
        btnStop.setVisibility(run?View.VISIBLE:View.GONE);
    }

    private void setStatus(TextView tv,boolean ok){
        tv.setText(ok?"Granted":"Tap to grant");
        tv.setTextColor(getColor(ok?R.color.green:R.color.amber));
    }

    private void onLaunch(){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)
                !=PackageManager.PERMISSION_GRANTED){
            cameraLauncher.launch(Manifest.permission.CAMERA);return;
        }
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M
                &&!Settings.canDrawOverlays(this)){
            overlayLauncher.launch(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:"+getPackageName())));return;
        }
        if(!EyeAccessibilityService.isEnabled(this)){
            new AlertDialog.Builder(this)
                .setTitle("Enable Accessibility")
                .setMessage("Find EyeScroll Gesture Control and enable it.")
                .setPositiveButton("Open Settings",(d,w)->
                    accessibilityLauncher.launch(
                        new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .show();return;
        }
        // Start service - it handles camera + WebGazer + cursor
        Intent i=new Intent(this,EyeOverlayService.class);
        i.setAction(EyeOverlayService.ACTION_START);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)
            startForegroundService(i);
        else startService(i);
        Toast.makeText(this,
            "EyeScroll starting - calibrate your eyes then open YouTube!",
            Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
        updateStatuses();
    }

    private void stopEyeScroll(){
        Intent i=new Intent(this,EyeOverlayService.class);
        i.setAction(EyeOverlayService.ACTION_STOP);
        startService(i);
        updateStatuses();
    }
    }
