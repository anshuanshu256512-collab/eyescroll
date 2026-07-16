package com.eyescroll;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private TextView statusCamera, statusOverlay, statusAccessibility;
    private Button btnLaunch, btnStop;
    private FrameLayout calibContainer;
    private WebView calibWebView;

    private final ActivityResultLauncher<String> cameraLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(),
            granted -> updateStatuses());
    private final ActivityResultLauncher<Intent> overlayLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result -> updateStatuses());
    private final ActivityResultLauncher<Intent> accessibilityLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result -> updateStatuses());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusCamera = findViewById(R.id.status_camera);
        statusOverlay = findViewById(R.id.status_overlay);
        statusAccessibility = findViewById(R.id.status_accessibility);
        btnLaunch = findViewById(R.id.btn_launch);
        btnStop = findViewById(R.id.btn_stop);
        calibContainer = findViewById(R.id.calib_container);
        btnLaunch.setOnClickListener(v -> onLaunch());
        btnStop.setOnClickListener(v -> stopEyeScroll());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatuses();
    }

    private void updateStatuses() {
        boolean cam = ContextCompat.checkSelfPermission(this,
            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean ovl = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || Settings.canDrawOverlays(this);
        boolean acc = EyeAccessibilityService.isEnabled(this);
        boolean run = EyeOverlayService.isRunning();
        setStatus(statusCamera, cam);
        setStatus(statusOverlay, ovl);
        setStatus(statusAccessibility, acc);
        btnLaunch.setVisibility(run ? View.GONE : View.VISIBLE);
        btnStop.setVisibility(run ? View.VISIBLE : View.GONE);
    }

    private void setStatus(TextView tv, boolean ok) {
        tv.setText(ok ? "✓ Granted" : "Tap to grant");
        tv.setTextColor(getColor(ok ? R.color.green : R.color.amber));
    }

    private void onLaunch() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraLauncher.launch(Manifest.permission.CAMERA);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
            return;
        }
        if (!EyeAccessibilityService.isEnabled(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Enable Accessibility")
                .setMessage("Find EyeScroll Gesture Control and enable it.")
                .setPositiveButton("Open Settings", (d, w) ->
                    accessibilityLauncher.launch(
                        new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .show();
            return;
        }
        // All permissions granted — show calibration WebView
        showCalibrationWebView();
    }

    private void showCalibrationWebView() {
        // Hide the main UI
        btnLaunch.setVisibility(View.GONE);
        calibContainer.setVisibility(View.VISIBLE);

        calibWebView = new WebView(this);
        WebSettings ws = calibWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setDomStorageEnabled(true);

        calibWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        // JS Bridge — when calibration done, start overlay
        calibWebView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void onCalibrationDone() {
                runOnUiThread(() -> {
                    // Start the overlay service (cursor only)
                    startOverlayService();
                    // Hide calibration WebView
                    calibContainer.setVisibility(View.GONE);
                    // Minimize app so user can open YouTube/Instagram
                    moveTaskToBack(true);
                });
            }

            @android.webkit.JavascriptInterface
            public void onCalibrationStart() {}

            @android.webkit.JavascriptInterface
            public void log(String msg) {
                android.util.Log.d("EyeScroll", msg);
            }

            @android.webkit.JavascriptInterface
            public void onGesture(int code) {}

            @android.webkit.JavascriptInterface
            public void stopService() {
                runOnUiThread(() -> {
                    calibContainer.setVisibility(View.GONE);
                    btnLaunch.setVisibility(View.VISIBLE);
                });
            }
        }, "EyeScroll");

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT);
        calibContainer.addView(calibWebView, lp);
        calibWebView.loadUrl("file:///android_asset/eyetracker.html");
    }

    private void startOverlayService() {
        Intent i = new Intent(this, EyeOverlayService.class);
        i.setAction(EyeOverlayService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        Toast.makeText(this,
            "EyeScroll active! Open YouTube or Instagram.",
            Toast.LENGTH_LONG).show();
    }

    private void stopEyeScroll() {
        Intent i = new Intent(this, EyeOverlayService.class);
        i.setAction(EyeOverlayService.ACTION_STOP);
        startService(i);
        updateStatuses();
    }
}
