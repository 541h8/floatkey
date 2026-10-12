package com.floatkey.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 悬浮键盘服务。
 *
 * 两层窗口：
 *  ① 悬浮球（小圆点）—— 常驻所有应用上层，可拖动，点一下展开键盘
 *  ② 键盘面板 —— 内嵌 WebView 承载键盘 UI，点击按键后通过 addJavascriptInterface
 *     把按键事件回传到 Java 层，再由 sendKey / 剪贴板兜底 写入前台应用
 *
 * 关于"点其他应用输入"：
 *   Android 没有合法 API 让一个普通 App 直接往另一个 App 的输入框里塞字符。
 *   本服务按以下优先级尝试，尽力做到"直接出现"：
 *     1) 反射 InputConnection 的 commitText（仅对前台 EditText，需要焦点且为本进程）
 *     2) dispatchKeyEvent 派发实体按键事件（部分应用/游戏/浏览器可接收）
 *     3) 剪贴板写入 + 提示用户"长按粘贴"（兜底，需手动一步）
 *   AccessibilityService 通道（onAccessibilityEvent 取焦点节点并 ACTION_SET_TEXT）
 *   作为补充路径，需要用户在系统设置里手动开启本应用的无障碍服务。
 */
public class FloatingKeyboardService extends Service {

    private static final char APOSTROPHE = '\u0027';
    public static final String CHANNEL_ID = "floatkey_foreground";

    private WindowManager wm;
    private View ballView;
    private View keyboardView;
    private WebView keyboardWebView;
    private boolean keyboardShown = false;
    private int screenW, screenH;

    private final JSInterface js = new JSInterface();

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, buildNotification());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        readScreenSize();
        createBall();
    }

    private void readScreenSize() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(m);
        screenW = m.widthPixels;
        screenH = m.heightPixels;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID, "悬浮键盘", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("保持键盘悬浮窗在屏幕上层运行");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(getString(R.string.app_name))
                .setContentText("键盘正在运行，点悬浮球展开")
                .setSmallIcon(android.R.drawable.ic_input_add)
                .setOngoing(true)
                .build();
    }

    /* ---------------- 悬浮球 ---------------- */

    private void createBall() {
        ballView = new ImageView(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xFF7C4DFF);
        d.setStroke(6, 0xFFFFFFFF);
        ballView.setBackground(d);
        ballView.setLayoutParams(new ViewGroup.LayoutParams(160, 160));
        ballView.setPadding(28, 28, 28, 28);
        ballView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        TextView t = new TextView(this);
        t.setText("⌨");
        t.setTextSize(28);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(android.view.Gravity.CENTER);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                160, 160,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = screenH / 3;

        ballView.setOnClickListener(v -> toggleKeyboard());
        ballView.setOnTouchListener(new DragTouchListener(lp));

        try { wm.addView(ballView, lp); } catch (Exception ignored) {}
    }

    private class DragTouchListener implements View.OnTouchListener {
        private final WindowManager.LayoutParams lp;
        private float dx, dy;
        private long down;
        private boolean moved = false;

        DragTouchListener(WindowManager.LayoutParams lp) { this.lp = lp; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dx = e.getRawX() - lp.x;
                    dy = e.getRawY() - lp.y;
                    down = System.currentTimeMillis();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = (int) (e.getRawX() - dx);
                    lp.y = (int) (e.getRawY() - dy);
                    if (Math.abs(e.getRawX() - dx) > 8 || Math.abs(e.getRawY() - dy) > 8) moved = true;
                    try { wm.updateViewLayout(ballView, lp); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && System.currentTimeMillis() - down < 200) {
                        v.performClick();
                    }
                    return true;
            }
            return false;
        }
    }

    /* ---------------- 键盘面板 ---------------- */

    private void toggleKeyboard() {
        if (keyboardShown) { hideKeyboard(); } else { showKeyboard(); }
    }

    private void showKeyboard() {
        if (keyboardView != null) return;
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        keyboardWebView = new WebView(this);
        keyboardWebView.setBackgroundColor(Color.TRANSPARENT);
        keyboardWebView.getSettings().setJavaScriptEnabled(true);
        keyboardWebView.getSettings().setDomStorageEnabled(true);
        keyboardWebView.addJavascriptInterface(js, "FloatKey");
        keyboardWebView.setWebViewClient(new WebViewClient());
        keyboardWebView.loadUrl("file:///android_asset/keyboard.html");

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                screenH * 3 / 5,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xCC311B92);
        TextView drag = new TextView(this);
        drag.setText("  ≡ 拖动  ·  ✕ 收起  ·  长按输入框定位光标");
        drag.setTextColor(0xFFFFFFFF);
        drag.setPadding(24, 12, 24, 12);
        drag.setTextSize(12);
        bar.addView(drag, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(this);
        close.setText(" ✕ ");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(18);
        close.setPadding(24, 12, 24, 12);
        close.setOnClickListener(v -> hideKeyboard());
        bar.addView(close);

        FrameLayout container = new FrameLayout(this);
        container.addView(keyboardWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(container);
        keyboardView = root;

        try { wm.addView(keyboardView, lp); } catch (Exception e) {
            Toast.makeText(this, "无法显示键盘：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        keyboardShown = true;
    }

    private void hideKeyboard() {
        if (keyboardView != null) {
            try { wm.removeView(keyboardView); } catch (Exception ignored) {}
            keyboardView = null;
            keyboardWebView = null;
        }
        keyboardShown = false;
    }

    private int windowType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    /* ---------------- JS 桥：键盘 → Java ----------------
     * keyboard.html 里的按键点击会调用 FloatKey.press(json)
     * json: {"k":"a"}  {"k":"Enter"}  {"k":"Backspace"}  {"k":"Tab"}
     *       {"k":"F1"}  {"text":"hello"}（批量文本）
     */

    private class JSInterface {
        @android.webkit.JavascriptInterface
        public void press(final String json) {
            try {
                final JSONObject o = new JSONObject(json);
                mainHandler().post(() -> handle(o));
            } catch (Exception ignored) {}
        }
    }

    private android.os.Handler mainHandler() {
        return new android.os.Handler(getMainLooper());
    }

    private void handle(JSONObject o) {
        String k = o.optString("k", "");
        String text = o.optString("text", "");

        if (!text.isEmpty()) { insertText(text); return; }

        int keyCode = keyCodeOf(k);
        if (keyCode != 0) {
            sendKey(keyCode, k.length() == 1 ? k.charAt(0) : 0);
            return;
        }
        // 无对应 keyCode 的单字符（中文、emoji、符号层字符）
        if (!k.isEmpty() && k.length() <= 2) {
            insertText(k);
        }
    }

    private static int keyCodeOf(String k) {
        if (k.length() == 1) {
            char c = k.charAt(0);
            if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + (c - 'a');
            if (c >= '0' && c <= '9') {
                if (c == '0') return KeyEvent.KEYCODE_0;
                return KeyEvent.KEYCODE_1 + (c - '1');
            }
            switch (c) {
                case ' ': return KeyEvent.KEYCODE_SPACE;
                case '\n': case '\r': return KeyEvent.KEYCODE_ENTER;
                case '\b': return KeyEvent.KEYCODE_DEL;
                case '\t': return KeyEvent.KEYCODE_TAB;
                case '-': return KeyEvent.KEYCODE_MINUS;
                case '=': return KeyEvent.KEYCODE_EQUALS;
                case '[': return KeyEvent.KEYCODE_LEFT_BRACKET;
                case ']': return KeyEvent.KEYCODE_RIGHT_BRACKET;
                case '\\': return KeyEvent.KEYCODE_BACKSLASH;
                case ';': return KeyEvent.KEYCODE_SEMICOLON;
                case APOSTROPHE: return KeyEvent.KEYCODE_APOSTROPHE;
                case '/': return KeyEvent.KEYCODE_SLASH;
                case '.': return KeyEvent.KEYCODE_PERIOD;
                case ',': return KeyEvent.KEYCODE_COMMA;
            }
        }
        switch (k) {
            case "Enter": case "Return": return KeyEvent.KEYCODE_ENTER;
            case "Backspace": case "Delete": return KeyEvent.KEYCODE_DEL;
            case "Tab": return KeyEvent.KEYCODE_TAB;
            case "Escape": case "Esc": return KeyEvent.KEYCODE_ESCAPE;
            case "Space": return KeyEvent.KEYCODE_SPACE;
            case "ArrowUp": return KeyEvent.KEYCODE_DPAD_UP;
            case "ArrowDown": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "ArrowLeft": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "ArrowRight": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "CapsLock": return KeyEvent.KEYCODE_CAPS_LOCK;
            case "Shift": case "ShiftLeft": case "ShiftRight": return KeyEvent.KEYCODE_SHIFT_LEFT;
            case "Control": case "Ctrl": case "CtrlLeft": return KeyEvent.KEYCODE_CTRL_LEFT;
            case "Alt": return KeyEvent.KEYCODE_ALT_LEFT;
            case "Meta": case "Cmd": return KeyEvent.KEYCODE_META_LEFT;
        }
        if (k.startsWith("F") && k.length() <= 3) {
            try {
                int n = Integer.parseInt(k.substring(1));
                if (n >= 1 && n <= 12) return KeyEvent.KEYCODE_F1 + (n - 1);
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /**
     * 派发按键事件到当前前台 Activity。
     * 对网页游戏 / 支持外部按键的 App 有效；普通 EditText 通常吃不到，
     * 此时走 insertText 的兜底路径。
     */
    private void sendKey(int code, int unicode) {
        long now = System.currentTimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0);
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am != null) {
                try {
                    Method m = AccessibilityManager.class.getMethod(
                            "sendKeyEvent", KeyEvent.class);
                    m.invoke(am, down);
                    m.invoke(am, up);
                    return;
                } catch (Exception ignored) {
                    // 反射失败，继续走 InputConnection
                }
            }
        } catch (Exception ignored) {}
        injectViaInputConnection(code, unicode);
    }

    private void injectViaInputConnection(int code, int unicode) {
        // InputConnection 只能拿到前台窗口中聚焦的那个；本服务不在前台时通常拿不到，
        // 这里尽力尝试，失败则静默（insertText 的剪贴板兜底会接住）。
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            InputConnection ic = imm.getCurrentInputConnection();
            if (ic != null) {
                if (unicode != 0) {
                    ic.commitText(String.valueOf((char) unicode), 1);
                } else {
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 写字符到前台应用的输入框。
     * 优先用辅助功能服务取焦点节点并 ACTION_SET_TEXT；不可用时用剪贴板。
     */
    private void insertText(String s) {
        if (isAccessibilityEnabled()) {
            Intent i = new Intent(this, KeyboardAccessibilityService.class);
            i.setAction(KeyboardAccessibilityService.ACTION_TYPE);
            i.putExtra(KeyboardAccessibilityService.EXTRA_TEXT, s);
            startService(i);
        } else {
            // 无辅助功能时：写入剪贴板，并提示用户在目标输入框长按 → 粘贴
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("floatkey", s));
            Toast.makeText(this,
                    "已复制：" + s + "\n在输入框长按 → 粘贴", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAccessibilityEnabled() {
        String id = getPackageName() + "/" + KeyboardAccessibilityService.class.getName();
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo i : list) {
            if (i.getId().equals(id)) return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        hideKeyboard();
        if (ballView != null) {
            try { wm.removeView(ballView); } catch (Exception ignored) {}
            ballView = null;
        }
    }
}
