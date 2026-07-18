package com.eyescroll;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
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
    private View mainLayout;

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

        // Create layout programmatically — no XML dependency issues
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFF040D1A);

        // Main content view from XML
        View main = getLayoutInflater().inflate(R.layout.activity_main, root, false);
        root.addView(main);
        mainLayout = main;

        // Calibration container — full screen WebView
        calibContainer = new FrameLayout(this);
        calibContainer.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        calibContainer.setVisibility(View.GONE);
        calibContainer.setBackgroundColor(0xFF040D1A);
        root.addView(calibContainer);

        setContentView(root);

        statusCamera        = findViewById(R.id.status_camera);
        statusOverlay       = findViewById(R.id.status_overlay);
        statusAccessibility = findViewById(R.id.status_accessibility);
        btnLaunch           = findViewById(R.id.btn_launch);
        btnStop             = findViewById(R.id.btn_stop);

        btnLaunch.setOnClickListener(v -> onLaunch());
        btnStop.setOnClickListener(v -> stopEyeScroll());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatuses();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (calibWebView != null) {
            calibWebView.destroy();
            calibWebView = null;
        }
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
        tv.setText(ok ? "Granted ✓" : "Tap to grant");
        tv.setTextColor(getColor(ok ? R.color.green : R.color.amber));
    }

    private void onLaunch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
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
                .setMessage("Find 'EyeScroll Gesture Control' and enable it.\n\n"
                    + "It only performs gestures — reads nothing.")
                .setPositiveButton("Open Settings", (d, w) ->
                    accessibilityLauncher.launch(
                        new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .show();
            return;
        }
        // All permissions granted — show calibration
        showCalibration();
    }

    private void showCalibration() {
        if (calibWebView != null) {
            calibWebView.destroy();
            calibWebView = null;
        }

        calibWebView = new WebView(this);
        WebSettings ws = calibWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setDomStorageEnabled(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        calibWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        calibWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        calibWebView.addJavascriptInterface(new CalibBridge(), "EyeScroll");

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
        calibContainer.removeAllViews();
        calibContainer.addView(calibWebView, lp);
        calibContainer.setVisibility(View.VISIBLE);
        mainLayout.setVisibility(View.GONE);

        calibWebView.loadUrl("file:///android_asset/eyetracker.html");
    }

    private class CalibBridge {

        @android.webkit.JavascriptInterface
        public void onCalibrationDone() {
            runOnUiThread(() -> {
                // Start the overlay service
                startOverlayService();
                // Hide calibration, show main
                calibContainer.setVisibility(View.GONE);
                mainLayout.setVisibility(View.VISIBLE);
                // Minimize app
                moveTaskToBack(true);
                updateStatuses();
            });
        }

        @android.webkit.JavascriptInterface
        public void launchOverlay() {
            runOnUiThread(() -> {
                startOverlayService();
                calibContainer.setVisibility(View.GONE);
                mainLayout.setVisibility(View.VISIBLE);
                moveTaskToBack(true);
                updateStatuses();
            });
        }

        @android.webkit.JavascriptInterface
        public void onCalibrationStart() {}

        @android.webkit.JavascriptInterface
        public void log(String msg) {
            android.util.Log.d("EyeScroll", msg);
        }

        @android.webkit.JavascriptInterface
        public void stopService() {
            runOnUiThread(() -> {
                calibContainer.setVisibility(View.GONE);
                mainLayout.setVisibility(View.VISIBLE);
                updateStatuses();
            });
        }

        @android.webkit.JavascriptInterface
        public void onGesture(int code) {
            // Ignore gestures during calibration
        }
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
