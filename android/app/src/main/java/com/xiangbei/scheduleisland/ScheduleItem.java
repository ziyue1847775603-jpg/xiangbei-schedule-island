package com.xiangbei.scheduleisland;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

final class ScheduleItem {
    final LocalDate date;
    final LocalTime start;
    final LocalTime end;
    final String course;
    final String teacher;
    final String location;
    final String nodes;
    final int week;

    ScheduleItem(LocalDate date, LocalTime start, LocalTime end, String course,
                 String teacher, String location, String nodes, int week) {
        this.date = date;
        this.start = start;
        this.end = end;
        this.course = course;
        this.teacher = teacher;
        this.location = location;
        this.nodes = nodes;
        this.week = week;
    }

    LocalDateTime startDateTime() {
        return LocalDateTime.of(date, start);
    }

    LocalDateTime endDateTime() {
        return LocalDateTime.of(date, end);
    }
}
