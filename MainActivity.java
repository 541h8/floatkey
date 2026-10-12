package com.floatkey.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_ACCESSIBILITY = 1002;
    private static final int REQ_NOTIFICATION = 1003;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        AppOpsManager aom = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        String pkg = getPackageName();
        int mode = aom.checkOpNoThrow(
                "android:system_alert_window", android.os.Process.myUid(), pkg);
        boolean overlayGranted = (mode == AppOpsManager.MODE_ALLOWED)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this));

        Intent service = new Intent(this, FloatingKeyboardService.class);
        if (overlayGranted) {
            startServiceAndFinish(service);
        } else {
            askOverlay(service);
        }
    }

    private void startServiceAndFinish(Intent service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        Toast.makeText(this, "键盘已启动，悬浮球在屏幕左侧", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }

    private void askOverlay(final Intent service) {
        new AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("这款键盘以悬浮窗形式显示在其他应用上层，请在下一页把「" +
                        getString(R.string.app_name) + "」的权限打开，然后返回即可。")
                .setPositiveButton("去开启", (d, w) -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(i, REQ_OVERLAY);
                    } catch (ActivityNotFoundException e) {
                        startActivityForResult(
                                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
                    }
                })
                .setNegativeButton("算了", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startServiceAndFinish(new Intent(this, FloatingKeyboardService.class));
            } else {
                Toast.makeText(this, "未授权悬浮窗，无法显示键盘", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}
