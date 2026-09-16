package com.xiangbei.scheduleisland;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ScheduleRepository {
    private static final String FILE_NAME = "schedule.csv";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private ScheduleRepository() {}

    static File scheduleFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    static void ensureSeed(Context context) throws IOException {
        File destination = scheduleFile(context);
        if (destination.exists()) return;
        try (InputStream input = context.getAssets().open("schedule.example.csv");
             OutputStream output = new FileOutputStream(destination)) {
            copy(input, output);
        }
    }

    static List<ScheduleItem> load(Context context) throws IOException {
        ensureSeed(context);
        try (InputStream input = new FileInputStream(scheduleFile(context))) {
            return parse(readText(input));
        }
    }

    static int importFromUri(Context context, Uri uri) throws IOException {
        File temp = new File(context.getFilesDir(), "schedule.importing.csv");
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(temp)) {
            if (input == null) throw new IOException("无法读取所选文件");
            copy(input, output);
        }

        List<ScheduleItem> parsed;
        try (InputStream input = new FileInputStream(temp)) {
            parsed = parse(readText(input));
        }
        if (parsed.isEmpty()) {
            temp.delete();
            throw new IOException("文件中没有可识别的课程记录");
        }

        File destination = scheduleFile(context);
        File backup = new File(context.getFilesDir(), "schedule.backup.csv");
        if (destination.exists()) {
            try (InputStream input = new FileInputStream(destination);
                 OutputStream output = new FileOutputStream(backup)) {
                copy(input, output);
            }
        }
        try (InputStream input = new FileInputStream(temp);
             OutputStream output = new FileOutputStream(destination, false)) {
            copy(input, output);
        }
        temp.delete();
        return parsed.size();
    }

    static List<ScheduleItem> parse(String text) throws IOException {
        List<List<String>> rows = parseCsv(text);
        if (rows.size() < 2) return Collections.emptyList();

        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < rows.get(0).size(); i++) {
            String name = rows.get(0).get(i).replace("\uFEFF", "").trim();
            columns.put(name, i);
        }
        String[] required = {"日期", "开始", "结束", "课程", "教师", "地点", "节次", "第几周"};
        for (String name : required) {
            if (!columns.containsKey(name)) throw new IOException("课表缺少字段：" + name);
        }

        Map<String, MutableItem> merged = new LinkedHashMap<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            try {
                LocalDate date = LocalDate.parse(value(row, columns, "日期"), DATE);
                LocalTime start = LocalTime.parse(value(row, columns, "开始"));
                LocalTime end = LocalTime.parse(value(row, columns, "结束"));
                String course = value(row, columns, "课程").trim();
                if (course.isEmpty()) continue;
                String key = date + "|" + start + "|" + end + "|" + course;
                MutableItem item = merged.get(key);
                if (item == null) {
                    int week = parseInt(value(row, columns, "第几周"));
                    item = new MutableItem(date, start, end, course,
                            value(row, columns, "节次"), week);
                    merged.put(key, item);
                }
                item.addTeacher(value(row, columns, "教师"));
                item.addLocation(value(row, columns, "地点"));
            } catch (DateTimeParseException | IndexOutOfBoundsException ignored) {
                // Ignore malformed data rows while keeping valid courses usable.
            }
        }

        List<ScheduleItem> result = new ArrayList<>();
        for (MutableItem item : merged.values()) result.add(item.freeze());
        result.sort(Comparator.comparing(ScheduleItem::startDateTime));
        return result;
    }

    static List<ScheduleItem> forDate(List<ScheduleItem> items, LocalDate date) {
        List<ScheduleItem> result = new ArrayList<>();
        for (ScheduleItem item : items) if (item.date.equals(date)) result.add(item);
        result.sort(Comparator.comparing(i -> i.start));
        return result;
    }

    private static String value(List<String> row, Map<String, Integer> columns, String name) {
        int index = columns.get(name);
        return index < row.size() ? row.get(index).trim() : "";
    }

    private static int parseInt(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try { return Integer.parseInt(digits); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String readText(InputStream input) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        return result.toString();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else quoted = false;
                } else field.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                if (!isBlank(row)) rows.add(row);
                row = new ArrayList<>();
            } else field.append(c);
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            if (!isBlank(row)) rows.add(row);
        }
        return rows;
    }

    private static boolean isBlank(List<String> row) {
        for (String value : row) if (!value.trim().isEmpty()) return false;
        return true;
    }

    private static final class MutableItem {
        final LocalDate date;
        final LocalTime start;
        final LocalTime end;
        final String course;
        final String nodes;
        final int week;
        final Set<String> teachers = new LinkedHashSet<>();
        final Set<String> locations = new LinkedHashSet<>();

        MutableItem(LocalDate date, LocalTime start, LocalTime end, String course,
                    String nodes, int week) {
            this.date = date;
            this.start = start;
            this.end = end;
            this.course = course;
            this.nodes = nodes;
            this.week = week;
        }

        void addTeacher(String value) { if (!value.trim().isEmpty()) teachers.add(value.trim()); }
        void addLocation(String value) { if (!value.trim().isEmpty()) locations.add(value.trim()); }

        ScheduleItem freeze() {
            return new ScheduleItem(date, start, end, course,
                    String.join(" / ", teachers), String.join(" / ", locations), nodes, week);
        }
    }
}
