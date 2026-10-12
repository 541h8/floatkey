package com.floatkey.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;

public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("悬浮键盘 · 设置");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 40);
        root.addView(title);

        String[][] items = {
                {"① 悬浮窗权限（必需）", "让键盘显示在其他应用上层。已授权则跳过。"},
                {"② 辅助功能（推荐开启）", "开启后才能把字直接打进别的 App 输入框，否则只能用复制粘贴。"},
                {"③ 通知权限（可选）", "Android 13+ 需授权，用于常驻前台保活键盘。"},
        };
        for (String[] it : items) {
            TextView h = new TextView(this);
            h.setText(it[0]);
            h.setTextSize(16);
            h.setPadding(0, 24, 0, 8);
            root.addView(h);
            TextView b = new TextView(this);
            b.setText(it[1]);
            b.setTextSize(13);
            b.setTextColor(0xFF666666);
            root.addView(b);
        }

        Button btnOverlay = new Button(this);
        btnOverlay.setText("开启悬浮窗权限");
        btnOverlay.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        root.addView(btnOverlay);

        Button btnAcc = new Button(this);
        btnAcc.setText("开启辅助功能（跨应用输入）");
        btnAcc.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(btnAcc);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Button btnNotif = new Button(this);
            btnNotif.setText("开启通知权限");
            btnNotif.setOnClickListener(v ->
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
            root.addView(btnNotif);
        }

        Button start = new Button(this);
        start.setText("完成，启动键盘");
        start.setOnClickListener(v -> {
            startService(new Intent(this, FloatingKeyboardService.class));
            finish();
        });
        root.addView(start);

        setContentView(sv);
    }
}
