package com.xiangbei.scheduleisland;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;
import java.util.Locale;

/**
 * Watches only top-level window transitions so the overlay can stay out of the
 * system Recents screen. It does not request or inspect view-tree content.
 */
public class RecentsAccessibilityService extends AccessibilityService {
    private static final String TAG = "XiangbeiRecents";
    private boolean recentsVisible;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;

        String signature = eventSignature(event);
        boolean looksLikeRecents = containsRecentsToken(signature);
        Log.d(TAG, "window=" + signature + " recents=" + looksLikeRecents);

        if (looksLikeRecents) {
            if (!recentsVisible) {
                recentsVisible = true;
                notifyIsland(true);
            }
            return;
        }

        // A state change with a named package/class means Recents was replaced
        // by Home or an app. Empty TYPE_WINDOWS_CHANGED events are ignored.
        if (recentsVisible && !TextUtils.isEmpty(event.getPackageName()) &&
                !TextUtils.isEmpty(event.getClassName())) {
            recentsVisible = false;
            notifyIsland(false);
        }
    }

    @Override
    public void onInterrupt() {
        // No continuous operation to interrupt.
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "最近任务自动隐藏已启用");
    }

    private void notifyIsland(boolean visible) {
        if (!getSharedPreferences("island", MODE_PRIVATE).getBoolean("enabled", false)) return;
        Intent intent = new Intent(this, IslandService.class)
                .setAction(IslandService.ACTION_RECENTS_VISIBILITY)
                .putExtra(IslandService.EXTRA_RECENTS_VISIBLE, visible);
        startForegroundService(intent);
    }

    private String eventSignature(AccessibilityEvent event) {
        StringBuilder value = new StringBuilder();
        if (event.getPackageName() != null) value.append(event.getPackageName()).append(' ');
        if (event.getClassName() != null) value.append(event.getClassName()).append(' ');
        if (event.getContentDescription() != null) {
            value.append(event.getContentDescription()).append(' ');
        }
        List<CharSequence> text = event.getText();
        if (text != null) {
            for (CharSequence item : text) value.append(item).append(' ');
        }
        return value.toString().trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsRecentsToken(String value) {
        return value.contains("recents") || value.contains("recentapps") ||
                value.contains("overview") || value.contains("quickstep") ||
                value.contains("最近任务") || value.contains("近期任务") ||
                value.contains("最近使用");
    }
}
