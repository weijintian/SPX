package com.spx.chat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.camera.CameraSettings;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.util.Collections;

/**
 * 实时扫码页。
 *
 * 与原来的 IntentIntegrator 方式相比：
 *  - 焦点模式显式设为 CONTINUOUS：摄像头持续自动对焦，不会「只对一次」之后一直虚焦；
 *  - decodeContinuous：每一帧都送去解码，二维码进入取景框就会被识别，不需要按快门；
 *  - 手电筒开关，暗光环境也能扫；
 *  - 识别成功后震动一下并返回结果。
 */
public class QrScanActivity extends Activity implements BarcodeCallback {
    public static final String EXTRA_SCAN_RESULT = "SCAN_RESULT";

    private static final int CAMERA_PERMISSION_REQUEST = 0x5A11;

    private DecoratedBarcodeView barcodeView;
    private TextView torchButton;
    private boolean started = false;
    private boolean scanned = false;
    private boolean torchEnabled = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        barcodeView = new DecoratedBarcodeView(this);
        CameraSettings settings = new CameraSettings();
        // 持续对焦：手机移动、远近变化都会重新合焦
        settings.setFocusMode(CameraSettings.FocusMode.CONTINUOUS);
        settings.setRequestedCameraId(0);
        barcodeView.setCameraSettings(settings);
        barcodeView.setDecoderFactory(new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE)));
        barcodeView.setStatusText("把二维码放进框内 · 自动识别，无需按快门");
        root.addView(barcodeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextColor(Color.WHITE);
        close.setTextSize(16);
        close.setPadding(dp(18), dp(10), dp(18), dp(10));
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.gravity = Gravity.TOP | Gravity.START;
        closeParams.setMargins(dp(10), dp(36), 0, 0);
        root.addView(close, closeParams);

        torchButton = new TextView(this);
        torchButton.setText("手电筒 关");
        torchButton.setTextColor(Color.WHITE);
        torchButton.setTextSize(15);
        torchButton.setPadding(dp(22), dp(12), dp(22), dp(12));
        torchButton.setBackgroundColor(0x66000000);
        torchButton.setOnClickListener(v -> toggleTorch());
        FrameLayout.LayoutParams torchParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        torchParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        torchParams.setMargins(0, 0, 0, dp(56));
        root.addView(torchButton, torchParams);

        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toggleTorch() {
        torchEnabled = !torchEnabled;
        if (torchEnabled) barcodeView.setTorchOn(); else barcodeView.setTorchOff();
        torchButton.setText(torchEnabled ? "手电筒 开" : "手电筒 关");
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startScanning() {
        if (started || isFinishing()) return;
        started = true;
        barcodeView.resume();
        barcodeView.decodeContinuous(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission()) {
            startScanning();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startScanning();
        } else {
            android.widget.Toast.makeText(this, "需要相机权限才能扫码", android.widget.Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (barcodeView != null) barcodeView.pause();
        started = false;
    }

    @Override
    public void barcodeResult(BarcodeResult result) {
        if (scanned || result == null) return;
        String text = result.getText();
        if (text == null || text.trim().isEmpty()) return;
        scanned = true;
        if (barcodeView != null) barcodeView.pause();
        vibrate();
        Intent data = new Intent();
        data.putExtra(EXTRA_SCAN_RESULT, text.trim());
        setResult(RESULT_OK, data);
        finish();
    }

    private void vibrate() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(60);
            }
        } catch (Exception ignored) { }
    }
}
