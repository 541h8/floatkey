package com.floatkey.app;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.KeyEvent;
import android.util.Log;

/**
 * 辅助功能通道：在系统设置里手动开启后，
 * 可读取前台应用里聚焦的 EditText 节点，直接写入字符。
 *
 * 这是 Android 上唯一能"跨应用直接输入"的合法路径，
 * 但需要用户授权（设置 → 辅助功能 → 本应用 → 开启）。
 */
public class KeyboardAccessibilityService extends AccessibilityService {

    public static final String ACTION_TYPE = "com.floatkey.app.ACTION_TYPE";
    public static final String EXTRA_TEXT = "com.floatkey.app.EXTRA_TEXT";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TYPE.equals(intent.getAction())) {
            String text = intent.getStringExtra(EXTRA_TEXT);
            if (text != null && !text.isEmpty()) {
                typeIntoFocused(text);
            }
        }
        return START_NOT_STICKY;
    }

    private void typeIntoFocused(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo target = findFocusedEditable(root);
        if (target == null) target = findEditable(root);
        if (target != null) {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isFocused()
                && node.isEditable()
                && node.isVisibleToUser()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findFocusedEditable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }
}
