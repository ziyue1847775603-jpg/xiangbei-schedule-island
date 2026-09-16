package com.xiangbei.scheduleisland;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQUEST_OVERLAY = 41;
    private static final int REQUEST_NOTIFICATIONS = 42;
    private static final int REQUEST_IMPORT = 43;

    private TextView permissionStatus;
    private TextView dataStatus;
    private boolean startAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { ScheduleRepository.ensureSeed(this); }
        catch (IOException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        if (startAfterPermission && Settings.canDrawOverlays(this)) requestNotificationsThenStart();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 30, 39));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView eyebrow = text("XIANGBEI · MOBILE", 12, Color.rgb(139, 191, 177), true);
        root.addView(eyebrow);

        TextView title = text("向北课表岛", 32, Color.WHITE, true);
        title.setPadding(0, dp(8), 0, 0);
        root.addView(title);

        TextView description = text("Android / HarmonyOS 4.x APK 版\n在其他应用上方显示当前课程与下一节课", 15,
                Color.rgb(177, 207, 199), false);
        description.setLineSpacing(0, 1.18f);
        description.setPadding(0, dp(8), 0, dp(22));
        root.addView(description);

        permissionStatus = statusCard(root);
        dataStatus = statusCard(root);

        Button start = button("开启悬浮课表岛", true);
        start.setOnClickListener(v -> beginStartFlow());
        root.addView(start, margin(-1, 52, 0, 18, 0, 0));

        Button importButton = button("导入课表 CSV", false);
        importButton.setOnClickListener(v -> chooseScheduleFile());
        root.addView(importButton, margin(-1, 52, 0, 12, 0, 0));

        Button permissionButton = button("打开悬浮窗权限设置", false);
        permissionButton.setOnClickListener(v -> openOverlaySettings());
        root.addView(permissionButton, margin(-1, 52, 0, 12, 0, 0));

        Button recentsWatcher = button("开启最近任务自动隐藏", false);
        recentsWatcher.setOnClickListener(v -> {
            Toast.makeText(this, "请在已下载/已安装的服务中开启“课表岛最近任务自动隐藏”",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        root.addView(recentsWatcher, margin(-1, 52, 0, 12, 0, 0));

        Button resetPosition = button("重置悬浮岛位置", false);
        resetPosition.setOnClickListener(v -> {
            boolean enabled = getSharedPreferences("island", MODE_PRIVATE)
                    .getBoolean("enabled", false);
            getSharedPreferences("island", MODE_PRIVATE).edit()
                    .remove("x").remove("y").putInt("position_schema", 1).apply();
            if (enabled) {
                Intent service = new Intent(this, IslandService.class)
                        .setAction(IslandService.ACTION_RESET_POSITION);
                startForegroundService(service);
            }
            Toast.makeText(this, enabled ? "悬浮岛已回到屏幕顶部" : "位置已重置，下次开启后生效",
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(resetPosition, margin(-1, 52, 0, 12, 0, 0));

        Button stop = button("停止并隐藏课表岛", false);
        stop.setOnClickListener(v -> {
            startAfterPermission = false;
            getSharedPreferences("island", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            Intent intent = new Intent(this, IslandService.class).setAction(IslandService.ACTION_STOP);
            startService(intent);
            Toast.makeText(this, "课表岛已停止", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop, margin(-1, 52, 0, 20, 0, 0));

        TextView note = text("使用方法\n1. 授予“显示在其他应用上层”权限\n2. 导入电脑端的课表_按日期.csv\n3. 开启课表岛；拖动顶部可调整位置\n4. 点岛展开，长按岛打开快捷菜单\n5. 在快捷菜单中可最小化、回到今天或关闭\n6. 开启“最近任务自动隐藏”后，点方块键时岛会暂时消失\n\n自动隐藏服务只观察窗口名称，不读取屏幕内容。华为/鸿蒙设备还应在系统管家中允许自启动和后台运行。",
                13, Color.rgb(137, 177, 167), false);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(dp(16), dp(16), dp(16), dp(16));
        note.setBackground(round(Color.rgb(13, 55, 67), 20, Color.rgb(42, 103, 113)));
        root.addView(note, margin(-1, -2, 0, 8, 0, 0));

        root.setFocusableInTouchMode(true);
        root.requestFocus();
        scroll.post(() -> scroll.scrollTo(0, 0));
        refreshState();
        return scroll;
    }

    private TextView statusCard(LinearLayout parent) {
        TextView view = text("", 14, Color.WHITE, false);
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        view.setBackground(round(Color.rgb(13, 55, 67), 18, Color.rgb(42, 103, 113)));
        parent.addView(view, margin(-1, -2, 0, 10, 0, 0));
        return view;
    }

    private void refreshState() {
        if (permissionStatus == null) return;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notifications = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean recentsWatcher = isRecentsWatcherEnabled();
        permissionStatus.setText((overlay ? "●" : "○") + " 悬浮窗权限  " + (overlay ? "已允许" : "未允许") +
                "\n" + (notifications ? "●" : "○") + " 通知权限  " + (notifications ? "已允许" : "未允许") +
                "\n" + (recentsWatcher ? "●" : "○") + " 最近任务自动隐藏  " +
                (recentsWatcher ? "已开启" : "未开启"));
        try {
            List<ScheduleItem> items = ScheduleRepository.load(this);
            dataStatus.setText("● 课表数据  已载入 " + items.size() + " 节\n" +
                    ScheduleRepository.scheduleFile(this).getName());
        } catch (IOException e) {
            dataStatus.setText("○ 课表数据  " + e.getMessage());
        }
    }

    private void beginStartFlow() {
        startAfterPermission = true;
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings();
            return;
        }
        requestNotificationsThenStart();
    }

    private void requestNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        startIslandNow();
    }

    private void startIslandNow() {
        startAfterPermission = false;
        getSharedPreferences("island", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        Intent intent = new Intent(this, IslandService.class).setAction(IslandService.ACTION_START);
        startForegroundService(intent);
        Toast.makeText(this, "课表岛已开启", Toast.LENGTH_SHORT).show();
        refreshState();
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQUEST_OVERLAY);
    }

    private boolean isRecentsWatcherEnabled() {
        AccessibilityManager manager = (AccessibilityManager)
                getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null &&
                    getPackageName().equals(info.getResolveInfo().serviceInfo.packageName) &&
                    RecentsAccessibilityService.class.getName().equals(
                            info.getResolveInfo().serviceInfo.name)) return true;
        }
        return false;
    }

    private void chooseScheduleFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/comma-separated-values", "text/plain"});
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                int count = ScheduleRepository.importFromUri(this, data.getData());
                boolean enabled = getSharedPreferences("island", MODE_PRIVATE)
                        .getBoolean("enabled", false);
                if (enabled && Settings.canDrawOverlays(this)) {
                    Intent service = new Intent(this, IslandService.class).setAction(IslandService.ACTION_RELOAD);
                    startForegroundService(service);
                }
                Toast.makeText(this, "已导入 " + count + " 节课程", Toast.LENGTH_LONG).show();
            } catch (IOException e) {
                Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            refreshState();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS && startAfterPermission) startIslandNow();
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.rgb(6, 30, 37) : Color.WHITE);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(round(primary ? Color.rgb(156, 255, 73) : Color.rgb(18, 70, 83),
                20, primary ? Color.rgb(196, 255, 148) : Color.rgb(52, 116, 127)));
        return button;
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    private GradientDrawable round(int color, float radiusDp, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams margin(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                width < 0 ? width : dp(width), height < 0 ? height : dp(height));
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
