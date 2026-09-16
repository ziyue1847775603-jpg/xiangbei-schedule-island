package com.xiangbei.scheduleisland;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScheduleRepositoryTest {
    @Test
    public void parsesQuotedFieldsAndMergesDuplicateRooms() throws Exception {
        String csv = "\uFEFF日期,星期,开始,结束,课程,教师,地点,节次,第几周\n" +
                "2026-09-21,周一,13:30,17:10,示例课程B,\"教师甲,教师乙\",实验平台130,5-8,3\n" +
                "2026-09-21,周一,13:30,17:10,示例课程B,\"教师甲,教师乙\",实验平台132,5-8,3\n";

        List<ScheduleItem> items = ScheduleRepository.parse(csv);

        assertEquals(1, items.size());
        assertEquals("示例课程B", items.get(0).course);
        assertEquals("教师甲,教师乙", items.get(0).teacher);
        assertEquals("实验平台130 / 实验平台132", items.get(0).location);
        assertEquals(3, items.get(0).week);
    }

    @Test
    public void skipsMalformedRowsAndKeepsValidCourse() throws Exception {
        String csv = "日期,星期,开始,结束,课程,教师,地点,节次,第几周\n" +
                "not-a-date,周一,08:00,09:40,坏记录,甲,101,1-2,1\n" +
                "2026-09-14,周一,08:00,09:40,示例课程,示例教师,教学楼-101,1-2节,第1周\n";

        List<ScheduleItem> items = ScheduleRepository.parse(csv);

        assertEquals(1, items.size());
        assertEquals("示例课程", items.get(0).course);
        assertEquals(1, items.get(0).week);
        assertTrue(items.get(0).nodes.contains("1-2"));
    }
}
