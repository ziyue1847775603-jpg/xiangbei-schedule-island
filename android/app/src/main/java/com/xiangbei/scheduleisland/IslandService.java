package com.xiangbei.scheduleisland;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class IslandService extends Service {
    public static final String ACTION_START = "com.xiangbei.scheduleisland.START";
    public static final String ACTION_STOP = "com.xiangbei.scheduleisland.STOP";
    public static final String ACTION_RELOAD = "com.xiangbei.scheduleisland.RELOAD";
    public static final String ACTION_RESET_POSITION = "com.xiangbei.scheduleisland.RESET_POSITION";
    public static final String ACTION_SHOW = "com.xiangbei.scheduleisland.SHOW";
    public static final String ACTION_RECENTS_VISIBILITY =
            "com.xiangbei.scheduleisland.RECENTS_VISIBILITY";
    public static final String EXTRA_RECENTS_VISIBLE = "recents_visible";

    private static final String CHANNEL_ID = "schedule_island";
    private static final int NOTIFICATION_ID = 214;
    private static final Locale CHINESE = Locale.SIMPLIFIED_CHINESE;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateHeadline();
            handler.postDelayed(this, 30_000L);
        }
    };

    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private View islandView;
    private TextView dateBadge;
    private TextView headline;
    private TextView subline;
    private TextView rightStatus;
    private List<ScheduleItem> allItems = new ArrayList<>();
    private boolean expanded;
    private boolean minimized;
    private boolean menuVisible;
    private boolean hiddenForRecents;
    private LocalDate selectedDate = LocalDate.now();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        restoreUiState();
        reloadData();
        if (Settings.canDrawOverlays(this)) showOverlay();
        handler.post(ticker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_RECENTS_VISIBILITY.equals(action)) {
            boolean recentsVisible = intent.getBooleanExtra(EXTRA_RECENTS_VISIBLE, false);
            setHiddenForRecents(recentsVisible);
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            saveUiState();
            getSharedPreferences("island", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW.equals(action)) {
            hiddenForRecents = false;
            if (islandView == null && Settings.canDrawOverlays(this)) showOverlay();
            return START_STICKY;
        }
        if (ACTION_RESET_POSITION.equals(action)) {
            getSharedPreferences("island", MODE_PRIVATE).edit()
                    .remove("x").remove("y").putInt("position_schema", 1).apply();
            if (windowParams != null) {
                int width = desiredWindowWidth();
                windowParams.x = Math.max(dp(10), (screenWidth() - width) / 2);
                windowParams.y = dp(8);
            }
            saveUiState();
            renderOverlay();
            return START_STICKY;
        }
        if (ACTION_RELOAD.equals(action)) {
            reloadData();
            renderOverlay();
        } else if (islandView == null && Settings.canDrawOverlays(this)) {
            hiddenForRecents = false;
            showOverlay();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        saveUiState();
        handler.removeCallbacksAndMessages(null);
        if (islandView != null) {
            try { windowManager.removeView(islandView); }
            catch (RuntimeException ignored) {}
            islandView = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        renderOverlay();
    }

    private void reloadData() {
        try { allItems = ScheduleRepository.load(this); }
        catch (IOException e) {
            allItems = new ArrayList<>();
            Toast.makeText(this, "课表读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showOverlay() {
        if (!Settings.canDrawOverlays(this) || hiddenForRecents) return;
        SharedPreferences preferences = getSharedPreferences("island", MODE_PRIVATE);
        if (preferences.getInt("position_schema", 0) < 1) {
            preferences.edit().remove("x").remove("y").putInt("position_schema", 1).apply();
        }
        int width = desiredWindowWidth();
        windowParams = new WindowManager.LayoutParams(
                width,
                minimized ? dp(64) : WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = preferences
                .getInt("x", Math.max(dp(10), (screenWidth() - width) / 2));
        windowParams.y = preferences
                .getInt("y", dp(8));
        if (Build.VERSION.SDK_INT >= 28) {
            windowParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        }
        islandView = buildIsland();
        windowManager.addView(islandView, windowParams);
    }

    private void renderOverlay() {
        saveUiState();
        if (windowManager == null || !Settings.canDrawOverlays(this) || hiddenForRecents) return;
        int oldX = windowParams == null ? dp(10) : windowParams.x;
        int oldY = windowParams == null ? dp(8) : windowParams.y;
        if (islandView != null) {
            try { windowManager.removeView(islandView); }
            catch (RuntimeException ignored) {}
            islandView = null;
        }
        int width = desiredWindowWidth();
        if (windowParams == null) {
            windowParams = new WindowManager.LayoutParams(width, -2,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            windowParams.gravity = Gravity.TOP | Gravity.START;
        }
        windowParams.width = width;
        windowParams.height = minimized ? dp(64) : WindowManager.LayoutParams.WRAP_CONTENT;
        windowParams.x = Math.max(0, Math.min(oldX, screenWidth() - width));
        int maxY = Math.max(0, screenHeight() - estimatedWindowHeight());
        windowParams.y = Math.max(0, Math.min(oldY, maxY));
        islandView = buildIsland();
        windowManager.addView(islandView, windowParams);
    }

    private View buildIsland() {
        dateBadge = null;
        headline = null;
        subline = null;
        rightStatus = null;
        if (minimized) return buildMinimizedIsland();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), (expanded || menuVisible) ? dp(12) : dp(10));
        root.setElevation(dp(18));
        root.setBackground(glassBackground());

        View header = buildHeader();
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(58)));

        if (expanded || menuVisible) {
            View divider = new View(this);
            divider.setBackgroundColor(Color.argb(90, 153, 226, 229));
            root.addView(divider, margins(-1, dp(1), dp(2), dp(10), dp(2), dp(8)));
            root.addView(menuVisible ? buildContextMenu() : buildExpandedContent(),
                    new LinearLayout.LayoutParams(-1, -2));
        }
        updateHeadline();
        return root;
    }

    private View buildMinimizedIsland() {
        FrameLayout bubble = new FrameLayout(this);
        bubble.setMinimumWidth(dp(64));
        bubble.setMinimumHeight(dp(64));
        bubble.setPadding(dp(6), dp(6), dp(6), dp(6));
        bubble.setElevation(dp(18));
        bubble.setBackground(glassBackground());
        bubble.setContentDescription("课表岛已最小化，轻触恢复，长按打开菜单");

        TextView compactDate = text(LocalDate.now().getDayOfMonth() + "\n课",
                15, Color.WHITE, true);
        compactDate.setGravity(Gravity.CENTER);
        compactDate.setLineSpacing(0, 0.88f);
        bubble.addView(compactDate, new FrameLayout.LayoutParams(-1, -1));

        View dot = new View(this);
        dot.setBackground(round(Color.rgb(156, 255, 73), 6, Color.WHITE));
        FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(10), dp(10),
                Gravity.END | Gravity.TOP);
        dotParams.setMargins(0, dp(1), dp(1), 0);
        bubble.addView(dot, dotParams);
        attachDragAndToggle(bubble);
        return bubble;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        dateBadge = text("", 13, Color.WHITE, true);
        dateBadge.setGravity(Gravity.CENTER);
        dateBadge.setLineSpacing(0, 0.9f);
        dateBadge.setBackground(round(Color.rgb(78, 151, 137), 23, Color.rgb(142, 216, 202)));
        row.addView(dateBadge, margins(dp(48), dp(48), 0, 0, dp(10), 0));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_VERTICAL);
        headline = text("正在读取课表", 15, Color.WHITE, true);
        headline.setSingleLine(true);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        center.addView(headline);
        subline = text("", 11, Color.rgb(157, 193, 183), false);
        subline.setSingleLine(true);
        subline.setEllipsize(TextUtils.TruncateAt.END);
        subline.setPadding(0, dp(4), 0, 0);
        center.addView(subline);
        row.addView(center, new LinearLayout.LayoutParams(0, -1, 1f));

        rightStatus = text("", 11, Color.WHITE, true);
        rightStatus.setGravity(Gravity.CENTER);
        rightStatus.setMinWidth(dp(62));
        rightStatus.setPadding(dp(8), dp(6), dp(8), dp(6));
        rightStatus.setBackground(round(Color.argb(95, 185, 229, 219), 16,
                Color.argb(80, 214, 247, 240)));
        row.addView(rightStatus, margins(-2, dp(42), dp(8), 0, 0, 0));
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.addView(row, new FrameLayout.LayoutParams(-1, -1));
        View touchLayer = new View(this);
        touchLayer.setClickable(true);
        touchLayer.setContentDescription("拖动或展开课表岛");
        attachDragAndToggle(touchLayer);
        wrapper.addView(touchLayer, new FrameLayout.LayoutParams(-1, -1));
        return wrapper;
    }

    private View buildExpandedContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView previous = miniButton("‹");
        TextView title = text(weekTitle(selectedDate), 18, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        TextView today = actionButton("今天");
        TextView next = miniButton("›");
        previous.setOnClickListener(v -> {
            selectedDate = selectedDate.minusWeeks(1);
            renderOverlay();
        });
        today.setOnClickListener(v -> returnToToday());
        next.setOnClickListener(v -> {
            selectedDate = selectedDate.plusWeeks(1);
            renderOverlay();
        });
        nav.addView(previous, new LinearLayout.LayoutParams(dp(42), dp(38)));
        nav.addView(title, new LinearLayout.LayoutParams(0, dp(38), 1f));
        nav.addView(today, margins(dp(50), dp(34), dp(4), dp(2), dp(4), dp(2)));
        nav.addView(next, new LinearLayout.LayoutParams(dp(42), dp(38)));
        content.addView(nav);

        LinearLayout days = new LinearLayout(this);
        days.setOrientation(LinearLayout.HORIZONTAL);
        LocalDate monday = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String[] labels = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < 7; i++) {
            final LocalDate date = monday.plusDays(i);
            TextView day = text(labels[i] + "\n" + date.getDayOfMonth(), 12,
                    date.equals(selectedDate) ? Color.WHITE : Color.rgb(170, 203, 195), true);
            day.setGravity(Gravity.CENTER);
            day.setLineSpacing(0, 0.9f);
            int background = date.equals(selectedDate) ? Color.rgb(74, 143, 99) : Color.argb(110, 83, 135, 144);
            day.setBackground(round(background, 15, date.equals(selectedDate)
                    ? Color.rgb(145, 220, 120) : Color.argb(70, 196, 228, 223)));
            day.setOnClickListener(v -> {
                selectedDate = date;
                renderOverlay();
            });
            days.addView(day, margins(0, dp(54), i == 0 ? 0 : dp(3), dp(6), i == 6 ? 0 : dp(3), dp(8), 1f));
        }
        content.addView(days);

        TextView dayTitle = text(selectedDate.format(DateTimeFormatter.ofPattern("M月d日 EEEE", CHINESE)),
                14, Color.rgb(209, 235, 227), true);
        dayTitle.setPadding(dp(4), dp(2), 0, dp(8));
        content.addView(dayTitle);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));

        List<ScheduleItem> dayItems = ScheduleRepository.forDate(allItems, selectedDate);
        if (dayItems.isEmpty()) {
            TextView empty = text("⌁\n今天没有课", 17, Color.WHITE, true);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(dp(6), 1f);
            empty.setBackground(round(Color.argb(105, 87, 132, 143), 22,
                    Color.argb(85, 174, 218, 214)));
            list.addView(empty, new LinearLayout.LayoutParams(-1, dp(150)));
        } else {
            for (ScheduleItem item : dayItems) list.addView(courseCard(item));
        }
        content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(292)));

        TextView hint = text("轻触课程查看详情 · 轻触顶部收起", 10,
                Color.rgb(115, 161, 151), false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(9), 0, 0);
        content.addView(hint);
        return content;
    }

    private View buildContextMenu() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);

        TextView title = text("快捷操作", 13, Color.rgb(157, 193, 183), true);
        title.setPadding(dp(8), dp(2), dp(8), dp(8));
        menu.addView(title);

        menu.addView(menuItem("—  最小化为小圆钮", () -> minimizeIsland(), false));
        menu.addView(menuItem("◎  回到今天", this::returnToToday, false));
        menu.addView(menuItem(expanded ? "⌃  收起课程列表" : "⌄  展开课程列表", () -> {
            menuVisible = false;
            expanded = !expanded;
            renderOverlay();
        }, false));
        menu.addView(menuItem("↻  刷新课表数据", () -> {
            reloadData();
            menuVisible = false;
            renderOverlay();
        }, false));
        menu.addView(menuItem("⌖  重置到屏幕顶部", () -> {
            menuVisible = false;
            int width = normalWindowWidth();
            if (windowParams != null) {
                windowParams.x = Math.max(dp(10), (screenWidth() - width) / 2);
                windowParams.y = dp(8);
            }
            renderOverlay();
        }, false));
        menu.addView(menuItem("□  打开主应用", () -> {
            menuVisible = false;
            saveUiState();
            Intent open = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(open);
            renderOverlay();
        }, false));
        menu.addView(menuItem("×  关闭课表岛", () -> {
            menuVisible = false;
            saveUiState();
            getSharedPreferences("island", MODE_PRIVATE).edit()
                    .putBoolean("enabled", false).apply();
            stopSelf();
        }, true));
        return menu;
    }

    private View menuItem(String label, Runnable action, boolean destructive) {
        TextView item = text(label, 14,
                destructive ? Color.rgb(255, 178, 168) : Color.WHITE, true);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), 0, dp(14), 0);
        item.setBackground(round(destructive
                        ? Color.argb(90, 120, 47, 47)
                        : Color.argb(88, 76, 126, 130),
                14, destructive
                        ? Color.argb(100, 255, 150, 138)
                        : Color.argb(65, 190, 224, 216)));
        item.setOnClickListener(v -> action.run());
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(0, 0, 0, dp(7));
        wrapper.addView(item, new LinearLayout.LayoutParams(-1, dp(43)));
        return wrapper;
    }

    private View courseCard(ScheduleItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(round(Color.argb(205, 75, 100, 95), 18,
                Color.argb(95, 191, 224, 215)));

        View accent = new View(this);
        accent.setBackground(round(courseColor(item.course), 3, courseColor(item.course)));
        row.addView(accent, new LinearLayout.LayoutParams(dp(5), dp(54)));

        LinearLayout time = new LinearLayout(this);
        time.setOrientation(LinearLayout.VERTICAL);
        time.setGravity(Gravity.CENTER_VERTICAL);
        TextView start = text(formatTime(item.start), 15, Color.WHITE, true);
        TextView end = text(formatTime(item.end), 11, Color.rgb(164, 196, 186), false);
        time.addView(start);
        time.addView(end);
        row.addView(time, margins(dp(64), -1, dp(12), 0, dp(8), 0));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(item.course, 15, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(name);
        TextView place = text(shortLocation(item.location), 11, Color.rgb(182, 209, 201), false);
        place.setSingleLine(true);
        place.setEllipsize(TextUtils.TruncateAt.END);
        place.setPadding(0, dp(5), 0, 0);
        details.addView(place);
        row.addView(details, new LinearLayout.LayoutParams(0, -1, 1f));
        row.setOnClickListener(v -> showCourseDetail(item));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(0, 0, 0, dp(8));
        wrapper.addView(row, new LinearLayout.LayoutParams(-1, dp(78)));
        return wrapper;
    }

    private void showCourseDetail(ScheduleItem item) {
        if (!(islandView instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) islandView;
        while (root.getChildCount() > 2) root.removeViewAt(2);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(4), dp(8), dp(8));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        TextView back = text("‹  返回当天课表", 12, Color.rgb(170, 220, 206), true);
        back.setPadding(0, dp(6), 0, dp(10));
        back.setOnClickListener(v -> renderOverlay());
        content.addView(back);

        TextView title = text(item.course, 21, Color.WHITE, true);
        title.setPadding(0, 0, 0, dp(12));
        content.addView(title);
        content.addView(detailRow("日期", item.date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", CHINESE))));
        content.addView(detailRow("时间", formatTime(item.start) + " – " + formatTime(item.end)));
        content.addView(detailRow("节次", blank(item.nodes)));
        content.addView(detailRow("教学周", item.week > 0 ? "第 " + item.week + " 周" : "—"));
        content.addView(detailRow("教师", blank(item.teacher)));
        content.addView(detailRow("地点", blank(item.location)));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(330)));
    }

    private View detailRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(7), 0, dp(7));
        TextView key = text(label, 12, Color.rgb(132, 180, 168), true);
        TextView content = text(value, 13, Color.WHITE, false);
        content.setGravity(Gravity.END);
        row.addView(key, new LinearLayout.LayoutParams(dp(64), -2));
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private void updateHeadline() {
        if (headline == null) return;
        LocalDateTime now = LocalDateTime.now();
        ScheduleItem current = null;
        ScheduleItem next = null;
        for (ScheduleItem item : allItems) {
            if (!now.isBefore(item.startDateTime()) && now.isBefore(item.endDateTime())) {
                current = item;
                break;
            }
            if (item.startDateTime().isAfter(now)) {
                next = item;
                break;
            }
        }

        LocalDate today = now.toLocalDate();
        dateBadge.setText(today.getDayOfMonth() + "\n" + weekday(today));
        if (current != null) {
            headline.setText("● 正在上课 · " + current.course);
            subline.setText(formatTime(current.start) + "–" + formatTime(current.end) + " · " + shortLocation(current.location));
            long minutes = Math.max(1, Duration.between(now, current.endDateTime()).toMinutes());
            rightStatus.setText(minutes + " 分\n下课");
        } else if (next != null) {
            headline.setText("● 下一节 · " + next.course);
            subline.setText(next.date.format(DateTimeFormatter.ofPattern("M月d日 E", CHINESE)) + " " +
                    formatTime(next.start) + " · " + shortLocation(next.location));
            long minutes = Duration.between(now, next.startDateTime()).toMinutes();
            String until = minutes < 60 ? Math.max(1, minutes) + " 分" :
                    minutes < 1440 ? (minutes / 60) + " 小时" : (minutes / 1440) + " 天";
            rightStatus.setText(formatTime(next.start) + "\n" + until);
        } else if (allItems.isEmpty()) {
            headline.setText("没有读到课表数据");
            subline.setText("打开向北课表岛导入 CSV");
            rightStatus.setText("—\n待导入");
        } else {
            headline.setText("本学期课程已结束");
            subline.setText("课表中的课程都已完成");
            rightStatus.setText("✓\n完成");
        }
    }

    private void attachDragAndToggle(View handle) {
        final float[] down = new float[2];
        final int[] origin = new int[2];
        final boolean[] moved = {false};
        final boolean[] longPressed = {false};
        final Runnable[] longPressAction = new Runnable[1];
        handle.setOnClickListener(view -> {
            if (minimized) {
                minimized = false;
            } else if (menuVisible) {
                menuVisible = false;
            } else {
                expanded = !expanded;
            }
            renderOverlay();
        });
        handle.setOnContextClickListener(view -> {
            openContextMenu(view);
            return true;
        });
        handle.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    origin[0] = windowParams.x;
                    origin[1] = windowParams.y;
                    moved[0] = false;
                    longPressed[0] = false;
                    longPressAction[0] = () -> {
                        if (!moved[0]) {
                            longPressed[0] = true;
                            openContextMenu(view);
                        }
                    };
                    view.postDelayed(longPressAction[0], ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        moved[0] = true;
                        view.removeCallbacks(longPressAction[0]);
                    }
                    if (moved[0] && !longPressed[0] && islandView != null) {
                        windowParams.x = Math.max(0, Math.min(origin[0] + Math.round(dx), screenWidth() - windowParams.width));
                        int maxY = Math.max(0, screenHeight() - estimatedWindowHeight());
                        windowParams.y = Math.max(0, Math.min(origin[1] + Math.round(dy), maxY));
                        windowManager.updateViewLayout(islandView, windowParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.removeCallbacks(longPressAction[0]);
                    if (!moved[0] && !longPressed[0] && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        view.performClick();
                    } else if (moved[0]) {
                        saveUiState();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private void openContextMenu(View source) {
        source.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        minimized = false;
        menuVisible = true;
        renderOverlay();
    }

    private void minimizeIsland() {
        menuVisible = false;
        int center = windowParams == null ? screenWidth() / 2
                : windowParams.x + Math.max(1, windowParams.width) / 2;
        minimized = true;
        if (windowParams != null) {
            windowParams.x = center >= screenWidth() / 2
                    ? screenWidth() - dp(64) - dp(8)
                    : dp(8);
        }
        renderOverlay();
    }

    private void returnToToday() {
        selectedDate = LocalDate.now();
        minimized = false;
        menuVisible = false;
        expanded = true;
        renderOverlay();
    }

    private void setHiddenForRecents(boolean hidden) {
        hiddenForRecents = hidden;
        if (hidden) {
            saveUiState();
            if (islandView != null) {
                try { windowManager.removeView(islandView); }
                catch (RuntimeException ignored) {}
                islandView = null;
            }
        } else if (islandView == null && Settings.canDrawOverlays(this)) {
            showOverlay();
        }
    }

    private void restoreUiState() {
        SharedPreferences preferences = getSharedPreferences("island", MODE_PRIVATE);
        expanded = preferences.getBoolean("expanded", false);
        minimized = preferences.getBoolean("minimized", false);
        String storedDate = preferences.getString("selected_date", null);
        if (storedDate != null) {
            try { selectedDate = LocalDate.parse(storedDate); }
            catch (RuntimeException ignored) { selectedDate = LocalDate.now(); }
        }
    }

    private void saveUiState() {
        SharedPreferences.Editor editor = getSharedPreferences("island", MODE_PRIVATE).edit()
                .putBoolean("expanded", expanded)
                .putBoolean("minimized", minimized)
                .putString("selected_date", selectedDate.toString());
        if (windowParams != null) {
            editor.putInt("x", windowParams.x).putInt("y", windowParams.y);
        }
        editor.apply();
    }

    private int normalWindowWidth() {
        return Math.min(screenWidth() - dp(20), dp(410));
    }

    private int desiredWindowWidth() {
        return minimized ? dp(64) : normalWindowWidth();
    }

    private int estimatedWindowHeight() {
        if (minimized) return dp(64);
        if (menuVisible) return dp(425);
        return dp(expanded ? 570 : 86);
    }

    private Notification createNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, IslandService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent show = new Intent(this, IslandService.class).setAction(ACTION_SHOW);
        PendingIntent showPending = PendingIntent.getService(this, 2, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        return builder.setSmallIcon(com.xiangbei.scheduleisland.R.drawable.ic_notification)
                .setContentTitle("向北课表岛正在显示")
                .setContentText("长按岛可最小化；最近任务中会自动隐藏")
                .setContentIntent(openPending)
                .addAction(new Notification.Action.Builder(null, "显示", showPending).build())
                .addAction(new Notification.Action.Builder(null, "停止", stopPending).build())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "课表岛运行状态",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持课表悬浮岛运行所需的常驻通知");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private GradientDrawable glassBackground() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(247, 18, 79, 93), Color.argb(248, 5, 43, 56)});
        drawable.setCornerRadius(dp(minimized ? 32 : expanded || menuVisible ? 28 : 29));
        drawable.setStroke(dp(1), Color.argb(170, 103, 195, 204));
        return drawable;
    }

    private GradientDrawable round(int color, float radiusDp, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private TextView miniButton(String value) {
        TextView view = text(value, 24, Color.rgb(202, 234, 226), false);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(Color.argb(100, 141, 190, 184), 19,
                Color.argb(80, 215, 241, 236)));
        return view;
    }

    private TextView actionButton(String value) {
        TextView view = text(value, 12, Color.rgb(224, 242, 236), true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(Color.argb(105, 96, 157, 153), 16,
                Color.argb(90, 215, 241, 236)));
        return view;
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom, float weight) {
        LinearLayout.LayoutParams params = margins(width, height, left, top, right, bottom);
        params.weight = weight;
        return params;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private int screenWidth() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics.widthPixels;
    }

    private int screenHeight() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics.heightPixels;
    }

    private String weekTitle(LocalDate date) {
        List<ScheduleItem> items = ScheduleRepository.forDate(allItems, date);
        if (!items.isEmpty() && items.get(0).week > 0) return "第 " + items.get(0).week + " 周";
        if (!allItems.isEmpty()) {
            ScheduleItem first = allItems.stream().min(Comparator.comparing(i -> i.date)).orElse(null);
            if (first != null && first.week > 0) {
                LocalDate firstMonday = first.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate targetMonday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                long offset = java.time.temporal.ChronoUnit.WEEKS.between(firstMonday, targetMonday);
                int week = first.week + (int) offset;
                if (week > 0) return "第 " + week + " 周";
            }
        }
        return "周视图";
    }

    private String weekday(LocalDate date) {
        return "周" + date.getDayOfWeek().getDisplayName(TextStyle.SHORT, CHINESE).replace("周", "");
    }

    private String formatTime(LocalTime time) {
        return time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private String shortLocation(String value) {
        if (value == null || value.trim().isEmpty()) return "地点待定";
        String text = value.replace("前卫-", "")
                .replace("（医学优先）", "")
                .replace("（三四教班 预防 放射 护理 康复）", "")
                .replace("（医学优先 基础医学院生命科学优先）", "");
        return text.length() > 30 ? text.substring(0, 29) + "…" : text;
    }

    private String blank(String value) {
        return value == null || value.trim().isEmpty() ? "—" : value;
    }

    private int courseColor(String name) {
        int[] palette = {
                Color.rgb(157, 255, 75), Color.rgb(78, 210, 216), Color.rgb(255, 179, 81),
                Color.rgb(117, 164, 255), Color.rgb(234, 112, 188), Color.rgb(129, 224, 156)
        };
        return palette[Math.floorMod(name.hashCode(), palette.length)];
    }
}
