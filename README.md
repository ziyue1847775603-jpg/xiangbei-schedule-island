# 向北课表岛

Windows WPF 桌面课表灵动岛，直接读取桌面 `向北课表/课表_按日期.csv` 的真实课程数据。

仓库同时包含 `android/` 下的原生 Android / HarmonyOS 4.x APK 版本；移动端支持长按快捷菜单、
圆钮最小化、回到今天、状态恢复及最近任务自动隐藏。移动端说明见 `android/README.md`。

- 顶部常驻、拖动定位、单击展开/收起
- 自动显示正在上课、下一节、倒计时与地点
- 展开后显示周视图和当天课程
- 左键点击任意课程卡片可查看日期、时间、节次、教学周、教师和地点
- 合并同课程同时间的多个实验室记录
- 监听 CSV 文件变更并自动刷新
- 右键切换置顶、开机启动、手动刷新和退出

## 运行

直接运行 `release/向北课表岛.exe`。单击顶部灵动岛展开或收起，展开后左键点击课程卡片查看详情。

程序优先读取桌面 `向北课表/课表_按日期.csv`，其次读取程序目录中的 `schedule.csv`。真实课表不会提交到仓库；`schedule.example.csv` 提供了字段格式示例。

## 构建

在 Windows PowerShell 中运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

构建结果位于 `release/向北课表岛.exe`，并嵌入 `assets/xiangbei-schedule-island.ico`。构建脚本会优先复制本地 `schedule.csv`；如果不存在，则使用不含个人信息的 `schedule.example.csv`。
