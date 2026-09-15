using Microsoft.Win32;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Shapes;
using System.Windows.Threading;

[assembly: AssemblyTitle("向北课表岛")]
[assembly: AssemblyProduct("向北课表岛")]
[assembly: AssemblyDescription("液态玻璃桌面课表灵动岛")]
[assembly: AssemblyCompany("Xiangbei")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

namespace Xiangbei.LiquidIsland
{
    internal sealed class ScheduleEntry
    {
        public DateTime Date;
        public TimeSpan Start;
        public TimeSpan End;
        public string Course = "";
        public string Teacher = "";
        public string Location = "";
        public string Nodes = "";
        public int Week;
        public string Color = "#7CCACA";

        public DateTime StartsAt { get { return Date.Date + Start; } }
        public DateTime EndsAt { get { return Date.Date + End; } }
    }

    internal static class ScheduleLoader
    {
        private static readonly Dictionary<string, string> Colors = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            { "人工智能引论D", "#48A4ED" },
            { "系统解剖学B", "#628BFF" },
            { "习近平新时代中国特色社会主义思想概论", "#FFB55C" },
            { "无机化学L", "#F35F7D" },
            { "无机化学实验A", "#E88B65" },
            { "学科英语素养", "#E79A68" },
            { "体育Ⅰ", "#4EE5C6" },
            { "医用大学物理A", "#62D4E6" },
            { "国家安全教育", "#EF8DA3" },
            { "微积分D", "#FF6D66" },
            { "形势与政策", "#54E4A6" },
            { "生命奇迹", "#70A5FF" },
            { "临床医学导论", "#FF8062" },
            { "恋爱心理学", "#BD9BFF" },
            { "军事理论", "#72CED6" },
            { "蓝染创意实践", "#4B9DEA" }
        };

        public static List<ScheduleEntry> Load(string path)
        {
            if (!File.Exists(path)) throw new FileNotFoundException("没有找到课表数据", path);
            string text = File.ReadAllText(path, new UTF8Encoding(false, true));
            List<List<string>> rows = ParseCsv(text);
            if (rows.Count < 2) return new List<ScheduleEntry>();

            Dictionary<string, int> header = rows[0]
                .Select((value, index) => new { value, index })
                .ToDictionary(x => x.value.Trim(), x => x.index, StringComparer.Ordinal);

            string[] required = { "日期", "开始", "结束", "课程", "教师", "地点", "节次", "第几周" };
            foreach (string key in required)
                if (!header.ContainsKey(key)) throw new InvalidDataException("课表缺少字段：" + key);

            List<ScheduleEntry> parsed = new List<ScheduleEntry>();
            for (int i = 1; i < rows.Count; i++)
            {
                List<string> row = rows[i];
                if (row.Count < rows[0].Count) continue;
                DateTime date;
                TimeSpan start;
                TimeSpan end;
                int week;
                if (!DateTime.TryParseExact(row[header["日期"]], "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out date)) continue;
                if (!TimeSpan.TryParse(row[header["开始"]], CultureInfo.InvariantCulture, out start)) continue;
                if (!TimeSpan.TryParse(row[header["结束"]], CultureInfo.InvariantCulture, out end)) continue;
                int.TryParse(row[header["第几周"]], out week);
                string course = row[header["课程"]].Trim();
                parsed.Add(new ScheduleEntry
                {
                    Date = date,
                    Start = start,
                    End = end,
                    Course = course,
                    Teacher = row[header["教师"]].Trim(),
                    Location = row[header["地点"]].Trim(),
                    Nodes = row[header["节次"]].Trim(),
                    Week = week,
                    Color = Colors.ContainsKey(course) ? Colors[course] : ColorFromName(course)
                });
            }

            // 同一门课同一时段可能列出多个实验室；岛内合并为一节，保留全部地点和教师。
            return parsed
                .GroupBy(x => x.Date.ToString("yyyyMMdd") + "|" + x.Start + "|" + x.End + "|" + x.Course, StringComparer.Ordinal)
                .Select(g =>
                {
                    ScheduleEntry first = g.First();
                    first.Location = JoinDistinct(g.Select(x => x.Location));
                    first.Teacher = JoinDistinct(g.Select(x => x.Teacher));
                    return first;
                })
                .OrderBy(x => x.Date).ThenBy(x => x.Start).ThenBy(x => x.Course, StringComparer.Ordinal)
                .ToList();
        }

        private static string JoinDistinct(IEnumerable<string> values)
        {
            string[] items = values.Where(x => !string.IsNullOrWhiteSpace(x)).Distinct(StringComparer.Ordinal).ToArray();
            return string.Join(" / ", items);
        }

        private static string ColorFromName(string value)
        {
            int hash = 17;
            foreach (char c in value) hash = unchecked(hash * 31 + c);
            string[] palette = { "#66D7C1", "#68A9FF", "#C19BFF", "#FF8F72", "#F0B85A", "#70D68B" };
            return palette[(hash & 0x7fffffff) % palette.Length];
        }

        private static List<List<string>> ParseCsv(string text)
        {
            List<List<string>> rows = new List<List<string>>();
            List<string> row = new List<string>();
            StringBuilder field = new StringBuilder();
            bool quoted = false;
            for (int i = 0; i < text.Length; i++)
            {
                char c = text[i];
                if (quoted)
                {
                    if (c == '"')
                    {
                        if (i + 1 < text.Length && text[i + 1] == '"') { field.Append('"'); i++; }
                        else quoted = false;
                    }
                    else field.Append(c);
                    continue;
                }
                if (c == '"') quoted = true;
                else if (c == ',') { row.Add(field.ToString()); field.Clear(); }
                else if (c == '\r') { }
                else if (c == '\n')
                {
                    row.Add(field.ToString()); field.Clear();
                    if (row.Any(x => x.Length > 0)) rows.Add(row);
                    row = new List<string>();
                }
                else field.Append(c);
            }
            row.Add(field.ToString());
            if (row.Any(x => x.Length > 0)) rows.Add(row);
            return rows;
        }
    }

    internal sealed class LiquidIslandWindow : Window
    {
        private const double IslandWidth = 426;
        private const double IslandBarHeight = 68;
        private const double CollapsedHeight = 96;
        private readonly Border _glass;
        private readonly Grid _root;
        private readonly Grid _header;
        private readonly Grid _expanded;
        private readonly TextBlock _dateNumber;
        private readonly TextBlock _dateMeta;
        private readonly TextBlock _headline;
        private readonly TextBlock _subline;
        private readonly TextBlock _statusMain;
        private readonly TextBlock _statusSmall;
        private readonly Ellipse _pulse;
        private readonly TextBlock _weekTitle;
        private readonly TextBlock _dayTitle;
        private readonly TextBlock _emptyTitle;
        private readonly TextBlock _emptySubtitle;
        private readonly StackPanel _weekStrip;
        private readonly StackPanel _events;
        private readonly Border _emptyState;
        private readonly TextBlock _dataStatus;
        private readonly DispatcherTimer _clock;
        private List<ScheduleEntry> _all = new List<ScheduleEntry>();
        private DateTime _selectedDate = DateTime.Today;
        private DateTime _weekAnchor = StartOfWeek(DateTime.Today);
        private DateTime _termStart = DateTime.MinValue;
        private bool _expandedState;
        private int _heightAnimationVersion;
        private readonly bool _expandAtLaunch;
        private readonly bool _jumpToNextAtLaunch;
        private string _dataPath = "";
        private FileSystemWatcher _watcher;

        public LiquidIslandWindow(bool expandAtLaunch = false, bool jumpToNextAtLaunch = false)
        {
            _expandAtLaunch = expandAtLaunch;
            _jumpToNextAtLaunch = jumpToNextAtLaunch;
            Title = "向北课表岛";
            Width = IslandWidth;
            Height = CollapsedHeight;
            MinWidth = IslandWidth;
            MaxWidth = IslandWidth;
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            AllowsTransparency = true;
            Background = Brushes.Transparent;
            ShowInTaskbar = false;
            Topmost = true;
            UseLayoutRounding = true;
            SnapsToDevicePixels = true;

            _root = new Grid { ClipToBounds = true };
            _root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(IslandBarHeight) });
            _root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            _glass = new Border
            {
                CornerRadius = new CornerRadius(34),
                BorderThickness = new Thickness(1),
                BorderBrush = new LinearGradientBrush(
                    Color.FromArgb(172, 236, 255, 255),
                    Color.FromArgb(72, 72, 225, 255), 115),
                Background = BuildGlassBrush(),
                Effect = new DropShadowEffect
                {
                    BlurRadius = 24,
                    ShadowDepth = 7,
                    Opacity = .30,
                    Color = Color.FromRgb(0, 18, 28)
                },
                Child = _root
            };
            Content = new Grid { Margin = new Thickness(14), Children = { _glass } };

            AddLiquidHighlights(_root);

            _header = new Grid { Margin = new Thickness(14, 0, 10, 0), Cursor = Cursors.Hand, Background = Brushes.Transparent };
            _header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(46) });
            _header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(10) });
            _header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            _header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(82) });
            _header.MouseLeftButtonDown += HeaderMouseDown;
            Grid.SetRow(_header, 0);
            _root.Children.Add(_header);

            Border dateBadge = new Border
            {
                Width = 43,
                Height = 43,
                CornerRadius = new CornerRadius(21.5),
                VerticalAlignment = VerticalAlignment.Center,
                Background = new LinearGradientBrush(Color.FromArgb(138, 207, 255, 237), Color.FromArgb(54, 255, 255, 255), 140),
                BorderBrush = new SolidColorBrush(Color.FromArgb(138, 255, 255, 255)),
                BorderThickness = new Thickness(1)
            };
            StackPanel dateStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            _dateNumber = Text("14", 17, FontWeights.SemiBold, Brushes.White);
            _dateNumber.TextAlignment = TextAlignment.Center;
            _dateMeta = Text("周一", 9, FontWeights.Medium, Brush("#C9E0D5"));
            _dateMeta.TextAlignment = TextAlignment.Center;
            dateStack.Children.Add(_dateNumber);
            dateStack.Children.Add(_dateMeta);
            dateBadge.Child = dateStack;
            _header.Children.Add(dateBadge);

            StackPanel center = new StackPanel { VerticalAlignment = VerticalAlignment.Center, ClipToBounds = true };
            Grid.SetColumn(center, 2);
            StackPanel topLine = new StackPanel { Orientation = Orientation.Horizontal };
            _pulse = new Ellipse { Width = 7, Height = 7, Fill = Brush("#B8FF62"), Margin = new Thickness(0, 0, 7, 0), VerticalAlignment = VerticalAlignment.Center };
            _pulse.Effect = new DropShadowEffect { BlurRadius = 10, ShadowDepth = 0, Color = Color.FromRgb(184, 255, 98), Opacity = .9 };
            _headline = Text("正在读取课表…", 14, FontWeights.SemiBold, Brushes.White);
            _headline.TextTrimming = TextTrimming.CharacterEllipsis;
            _headline.MaxWidth = 172;
            topLine.Children.Add(_pulse);
            topLine.Children.Add(_headline);
            _subline = Text("桌面灵动岛", 10.5, FontWeights.Normal, Brush("#DBEAE2"));
            _subline.Margin = new Thickness(14, 3, 0, 0);
            _subline.TextTrimming = TextTrimming.CharacterEllipsis;
            _subline.MaxWidth = 188;
            center.Children.Add(topLine);
            center.Children.Add(_subline);
            _header.Children.Add(center);

            Border statusCapsule = new Border
            {
                CornerRadius = new CornerRadius(15),
                Padding = new Thickness(7, 5, 7, 5),
                VerticalAlignment = VerticalAlignment.Center,
                Background = new SolidColorBrush(Color.FromArgb(70, 224, 255, 255)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(105, 255, 255, 255)),
                BorderThickness = new Thickness(1)
            };
            Grid.SetColumn(statusCapsule, 3);
            StackPanel statusStack = new StackPanel();
            _statusMain = Text("--:--", 11.5, FontWeights.SemiBold, Brush("#EDFFF5"));
            _statusMain.TextAlignment = TextAlignment.Center;
            _statusSmall = Text("点击展开", 8.5, FontWeights.Normal, Brush("#C8DDD2"));
            _statusSmall.Margin = new Thickness(0, 1, 0, 0);
            _statusSmall.TextAlignment = TextAlignment.Center;
            statusStack.Children.Add(_statusMain);
            statusStack.Children.Add(_statusSmall);
            statusCapsule.Child = statusStack;
            _header.Children.Add(statusCapsule);

            _expanded = new Grid { Margin = new Thickness(14, 0, 14, 12), Opacity = 0, Visibility = Visibility.Collapsed };
            _expanded.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1) });
            _expanded.RowDefinitions.Add(new RowDefinition { Height = new GridLength(44) });
            // 54px cells + 7px top margin + 8px bottom margin need 69px.
            // Leave a little extra room so DPI rounding cannot overlap the day title.
            _expanded.RowDefinitions.Add(new RowDefinition { Height = new GridLength(72) });
            _expanded.RowDefinitions.Add(new RowDefinition { Height = new GridLength(38) });
            _expanded.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            _expanded.RowDefinitions.Add(new RowDefinition { Height = new GridLength(32) });
            Grid.SetRow(_expanded, 1);
            _root.Children.Add(_expanded);

            Border separator = new Border { Height = 1, Background = new LinearGradientBrush(Colors.Transparent, Color.FromArgb(78, 255, 255, 255), 0), Opacity = .75 };
            Grid.SetRow(separator, 0);
            _expanded.Children.Add(separator);

            Grid weekHeader = new Grid { Margin = new Thickness(1, 8, 1, 0) };
            weekHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            weekHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            _weekTitle = Text("第 2 周", 16, FontWeights.SemiBold, Brushes.White);
            _weekTitle.VerticalAlignment = VerticalAlignment.Center;
            weekHeader.Children.Add(_weekTitle);
            StackPanel nav = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            nav.Children.Add(MiniButton("‹", delegate { _weekAnchor = _weekAnchor.AddDays(-7); _selectedDate = _weekAnchor; RefreshExpanded(); }));
            nav.Children.Add(MiniButton("本周", delegate { _weekAnchor = StartOfWeek(DateTime.Today); _selectedDate = DateTime.Today; RefreshExpanded(); }, 50));
            nav.Children.Add(MiniButton("›", delegate { _weekAnchor = _weekAnchor.AddDays(7); _selectedDate = _weekAnchor; RefreshExpanded(); }));
            Grid.SetColumn(nav, 1);
            weekHeader.Children.Add(nav);
            Grid.SetRow(weekHeader, 1);
            _expanded.Children.Add(weekHeader);

            _weekStrip = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Stretch, Margin = new Thickness(0, 7, 0, 8) };
            Grid.SetRow(_weekStrip, 2);
            _expanded.Children.Add(_weekStrip);

            Grid dayHeader = new Grid { Margin = new Thickness(1, 3, 1, 8) };
            dayHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            dayHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            _dayTitle = Text("今天", 13.5, FontWeights.SemiBold, Brush("#EAF8EE"));
            dayHeader.Children.Add(_dayTitle);
            TextBlock hint = Text("单击顶部可收起", 10.5, FontWeights.Normal, Brush("#789482"));
            Grid.SetColumn(hint, 1);
            hint.VerticalAlignment = VerticalAlignment.Center;
            dayHeader.Children.Add(hint);
            Grid.SetRow(dayHeader, 3);
            _expanded.Children.Add(dayHeader);

            Grid listHost = new Grid();
            _events = new StackPanel();
            ScrollViewer scroll = new ScrollViewer
            {
                VerticalScrollBarVisibility = ScrollBarVisibility.Hidden,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
                Content = _events
            };
            listHost.Children.Add(scroll);
            _emptyState = new Border
            {
                Height = 96,
                VerticalAlignment = VerticalAlignment.Top,
                CornerRadius = new CornerRadius(22),
                Background = new SolidColorBrush(Color.FromArgb(39, 226, 255, 255)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(67, 235, 255, 255)),
                BorderThickness = new Thickness(1),
                Padding = new Thickness(20),
                Margin = new Thickness(0, 3, 0, 8),
                Visibility = Visibility.Collapsed
            };
            StackPanel emptyStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            TextBlock leaf = Text("⌁", 28, FontWeights.Light, Brush("#B8FF62"));
            leaf.TextAlignment = TextAlignment.Center;
            _emptyTitle = Text("今天没有课", 15, FontWeights.SemiBold, Brushes.White);
            _emptyTitle.TextAlignment = TextAlignment.Center;
            _emptyTitle.Margin = new Thickness(0, 7, 0, 0);
            _emptySubtitle = Text("留一点时间给自己。", 11, FontWeights.Normal, Brush("#BED2C6"));
            _emptySubtitle.TextAlignment = TextAlignment.Center;
            _emptySubtitle.Margin = new Thickness(0, 5, 0, 0);
            _emptySubtitle.TextWrapping = TextWrapping.Wrap;
            emptyStack.Children.Add(leaf);
            emptyStack.Children.Add(_emptyTitle);
            emptyStack.Children.Add(_emptySubtitle);
            _emptyState.Child = emptyStack;
            listHost.Children.Add(_emptyState);
            Grid.SetRow(listHost, 4);
            _expanded.Children.Add(listHost);

            Grid footer = new Grid { Margin = new Thickness(1, 8, 1, 0) };
            footer.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            footer.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            _dataStatus = Text("正在读取数据", 10, FontWeights.Normal, Brush("#A9C1B3"));
            _dataStatus.VerticalAlignment = VerticalAlignment.Center;
            footer.Children.Add(_dataStatus);
            TextBlock signature = Text("XIANGBEI  ·  LIQUID ISLAND", 9.5, FontWeights.SemiBold, Brush("#6E8C78"));
            signature.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(signature, 1);
            footer.Children.Add(signature);
            Grid.SetRow(footer, 5);
            _expanded.Children.Add(footer);

            ContextMenu = BuildContextMenu();
            Loaded += OnLoaded;
            Closed += delegate { if (_watcher != null) _watcher.Dispose(); Application.Current.Shutdown(); };
            KeyDown += OnKeyDown;

            _clock = new DispatcherTimer { Interval = TimeSpan.FromSeconds(20) };
            _clock.Tick += delegate { RefreshHeader(); if (_expandedState) RefreshExpanded(false); };
            _clock.Start();
        }

        private static Brush BuildGlassBrush()
        {
            LinearGradientBrush brush = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
            brush.GradientStops.Add(new GradientStop(Color.FromArgb(210, 31, 94, 110), 0));
            brush.GradientStops.Add(new GradientStop(Color.FromArgb(202, 12, 61, 80), .46));
            brush.GradientStops.Add(new GradientStop(Color.FromArgb(216, 7, 38, 58), 1));
            return brush;
        }

        private static void AddLiquidHighlights(Grid root)
        {
            Canvas light = new Canvas { IsHitTestVisible = false, Opacity = .8 };
            Ellipse glow = new Ellipse
            {
                Width = 220,
                Height = 135,
                Fill = new RadialGradientBrush(Color.FromArgb(75, 87, 227, 255), Colors.Transparent),
                Effect = new BlurEffect { Radius = 34 }
            };
            Canvas.SetRight(glow, -62);
            Canvas.SetTop(glow, -66);
            light.Children.Add(glow);
            Ellipse water = new Ellipse
            {
                Width = 180,
                Height = 75,
                Fill = new RadialGradientBrush(Color.FromArgb(54, 132, 255, 205), Colors.Transparent),
                Effect = new BlurEffect { Radius = 28 }
            };
            Canvas.SetLeft(water, -70);
            Canvas.SetBottom(water, -23);
            light.Children.Add(water);
            root.Children.Add(light);
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            Rect area = SystemParameters.WorkArea;
            Left = area.Right - Width - 24;
            Top = area.Top + 18;
            Dispatcher.BeginInvoke(new Action(delegate
            {
                LoadData();
                if (_jumpToNextAtLaunch)
                {
                    ScheduleEntry next = _all.FirstOrDefault(x => x.StartsAt > DateTime.Now);
                    if (next != null)
                    {
                        _selectedDate = next.Date.Date;
                        _weekAnchor = StartOfWeek(_selectedDate);
                        RefreshExpanded();
                    }
                }
                if (_expandAtLaunch && !_expandedState) ToggleExpanded();
            }), DispatcherPriority.ApplicationIdle);
        }

        private void LoadData()
        {
            try
            {
                _dataPath = FindDataPath();
                _all = ScheduleLoader.Load(_dataPath);
                _termStart = InferTermStart(_all);
                SetupWatcher();
                _dataStatus.Text = _all.Count + " 节 · " + System.IO.Path.GetFileName(_dataPath) + " · 自动刷新";
            }
            catch (Exception ex)
            {
                _all = new List<ScheduleEntry>();
                _dataStatus.Text = "读取失败：" + ex.Message;
            }
            _selectedDate = DateTime.Today;
            _weekAnchor = StartOfWeek(_selectedDate);
            RefreshHeader();
            RefreshExpanded();
        }

        private static string FindDataPath()
        {
            string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            string desktopPath = System.IO.Path.Combine(desktop, "向北课表", "课表_按日期.csv");
            if (File.Exists(desktopPath)) return desktopPath;
            string basePath = System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "课表_按日期.csv");
            if (File.Exists(basePath)) return basePath;
            string portablePath = System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "schedule.csv");
            if (File.Exists(portablePath)) return portablePath;
            return basePath;
        }

        private void SetupWatcher()
        {
            if (_watcher != null) _watcher.Dispose();
            string directory = System.IO.Path.GetDirectoryName(_dataPath);
            if (string.IsNullOrEmpty(directory) || !Directory.Exists(directory)) return;
            _watcher = new FileSystemWatcher(directory, System.IO.Path.GetFileName(_dataPath));
            _watcher.NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.Size | NotifyFilters.FileName;
            _watcher.Changed += WatcherChanged;
            _watcher.Renamed += WatcherChanged;
            _watcher.EnableRaisingEvents = true;
        }

        private void WatcherChanged(object sender, FileSystemEventArgs e)
        {
            Dispatcher.BeginInvoke(new Action(delegate
            {
                try
                {
                    _all = ScheduleLoader.Load(_dataPath);
                    _termStart = InferTermStart(_all);
                    _dataStatus.Text = _all.Count + " 节 · 已同步 " + DateTime.Now.ToString("HH:mm:ss");
                    RefreshHeader();
                    RefreshExpanded();
                }
                catch { }
            }), DispatcherPriority.Background);
        }

        private void RefreshHeader()
        {
            DateTime now = DateTime.Now;
            _dateNumber.Text = now.Day.ToString(CultureInfo.InvariantCulture);
            _dateMeta.Text = ChineseWeekday(now.DayOfWeek);

            ScheduleEntry current = _all.FirstOrDefault(x => x.StartsAt <= now && x.EndsAt > now);
            ScheduleEntry next = _all.FirstOrDefault(x => x.StartsAt > now);
            if (current != null)
            {
                _pulse.Fill = Brush("#B8FF62");
                _headline.Text = "正在上课 · " + current.Course;
                _subline.Text = "至 " + FormatTime(current.End) + " · " + ShortLocation(current.Location);
                _statusMain.Text = "进行中";
                _statusSmall.Text = "还剩 " + FriendlyDuration(current.EndsAt - now);
            }
            else if (next != null)
            {
                _pulse.Fill = Brush("#7CEFD0");
                _headline.Text = (next.Date.Date == now.Date ? "下节 · " : "下一节 · ") + next.Course;
                _subline.Text = next.Date.ToString("M月d日") + " " + ChineseWeekday(next.Date.DayOfWeek) + " " + FormatTime(next.Start) + " · " + ShortLocation(next.Location);
                _statusMain.Text = next.Date.Date == now.Date ? FormatTime(next.Start) : next.Date.ToString("M/d");
                _statusSmall.Text = FriendlyUntil(next.StartsAt - now);
            }
            else
            {
                _pulse.Fill = Brush("#789482");
                _headline.Text = _all.Count == 0 ? "没有读到课表数据" : "本学期课程已结束";
                _subline.Text = _all.Count == 0 ? "请保留同目录下的课表_按日期.csv" : "所有课程都已走完";
                _statusMain.Text = now.ToString("HH:mm");
                _statusSmall.Text = _expandedState ? "点击收起" : "点击展开";
            }
        }

        private void RefreshExpanded(bool rebuildWeek = true, bool resize = true)
        {
            int week = WeekNumber(_weekAnchor);
            _weekTitle.Text = week > 0 ? "第 " + week + " 周" : _weekAnchor.ToString("yyyy · MM");
            _dayTitle.Text = DayTitle(_selectedDate);
            if (rebuildWeek) BuildWeekStrip();
            BuildEventCards();
            if (_expandedState && resize && Math.Abs(Height - DesiredExpandedHeight()) > 2)
                AnimateHeight(DesiredExpandedHeight(), 220);
        }

        private void BuildWeekStrip()
        {
            _weekStrip.Children.Clear();
            string[] shortNames = { "一", "二", "三", "四", "五", "六", "日" };
            for (int i = 0; i < 7; i++)
            {
                DateTime date = _weekAnchor.AddDays(i);
                bool selected = date == _selectedDate.Date;
                bool today = date == DateTime.Today;
                int count = _all.Count(x => x.Date.Date == date);
                Border cell = new Border
                {
                    Width = 43,
                    Height = 54,
                    Margin = new Thickness(i == 0 ? 0 : 4, 0, 0, 0),
                    CornerRadius = new CornerRadius(16),
                    Background = selected
                        ? new LinearGradientBrush(Color.FromArgb(102, 184, 255, 98), Color.FromArgb(54, 78, 229, 198), 135)
                        : new SolidColorBrush(Color.FromArgb(40, 225, 255, 255)),
                    BorderBrush = selected
                        ? new SolidColorBrush(Color.FromArgb(120, 204, 255, 151))
                        : new SolidColorBrush(Color.FromArgb(55, 239, 255, 255)),
                    BorderThickness = new Thickness(1),
                    Cursor = Cursors.Hand,
                    Tag = date
                };
                StackPanel stack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                TextBlock dow = Text(shortNames[i], 10.5, FontWeights.Medium, selected ? Brush("#F0FFF7") : Brush("#B7CDC0"));
                dow.TextAlignment = TextAlignment.Center;
                TextBlock day = Text(date.Day.ToString(CultureInfo.InvariantCulture), 16, today ? FontWeights.Bold : FontWeights.SemiBold, selected ? Brushes.White : Brush("#D2DFD6"));
                day.TextAlignment = TextAlignment.Center;
                day.Margin = new Thickness(0, 4, 0, 3);
                Ellipse dot = new Ellipse
                {
                    Width = count > 0 ? Math.Min(18, 4 + count * 2) : 3,
                    Height = 3,
                    Fill = count > 0 ? Brush("#B8FF62") : new SolidColorBrush(Color.FromArgb(32, 255, 255, 255)),
                    HorizontalAlignment = HorizontalAlignment.Center
                };
                stack.Children.Add(dow);
                stack.Children.Add(day);
                stack.Children.Add(dot);
                cell.Child = stack;
                cell.MouseLeftButtonUp += delegate(object sender, MouseButtonEventArgs e)
                {
                    _selectedDate = (DateTime)((Border)sender).Tag;
                    RefreshExpanded();
                    e.Handled = true;
                };
                _weekStrip.Children.Add(cell);
            }
        }

        private void BuildEventCards()
        {
            _events.Children.Clear();
            List<ScheduleEntry> day = _all.Where(x => x.Date.Date == _selectedDate.Date).OrderBy(x => x.Start).ToList();
            _emptyState.Visibility = day.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            if (day.Count == 0)
            {
                ScheduleEntry next = _all.FirstOrDefault(x => x.StartsAt > _selectedDate.Date.AddDays(1));
                _emptyTitle.Text = _selectedDate.Date == DateTime.Today ? "今天没有课" : "这天没有课";
                _emptySubtitle.Text = next == null
                    ? "留一点时间给自己。"
                    : "下一节：" + next.Date.ToString("M月d日") + " " + ChineseWeekday(next.Date.DayOfWeek) + " · " + next.Course;
                return;
            }

            DateTime now = DateTime.Now;
            foreach (ScheduleEntry item in day)
            {
                bool live = item.StartsAt <= now && item.EndsAt > now;
                bool next = !live && item.StartsAt > now && item == _all.FirstOrDefault(x => x.StartsAt > now);
                _events.Children.Add(EventCard(item, live, next));
            }
        }

        private Border EventCard(ScheduleEntry item, bool live, bool next)
        {
            Color accent = (Color)ColorConverter.ConvertFromString(item.Color);
            Border card = new Border
            {
                CornerRadius = new CornerRadius(19),
                Margin = new Thickness(0, 0, 0, 7),
                Padding = new Thickness(0),
                Background = new LinearGradientBrush(
                    Color.FromArgb(live ? (byte)86 : (byte)58, accent.R, accent.G, accent.B),
                    Color.FromArgb(34, 235, 255, 255), 100),
                BorderBrush = new SolidColorBrush(Color.FromArgb(live || next ? (byte)105 : (byte)34, accent.R, accent.G, accent.B)),
                BorderThickness = new Thickness(1)
            };

            Grid grid = new Grid { MinHeight = 72 };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(5) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(76) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            Border stripe = new Border { Background = new SolidColorBrush(accent), CornerRadius = new CornerRadius(19, 0, 0, 19) };
            grid.Children.Add(stripe);

            StackPanel time = new StackPanel { Margin = new Thickness(12, 13, 8, 10) };
            Grid.SetColumn(time, 1);
            TextBlock start = Text(FormatTime(item.Start), 15, FontWeights.SemiBold, Brushes.White);
            TextBlock end = Text(FormatTime(item.End), 10.5, FontWeights.Normal, Brush("#90A896"));
            end.Margin = new Thickness(0, 3, 0, 0);
            time.Children.Add(start);
            time.Children.Add(end);
            grid.Children.Add(time);

            StackPanel info = new StackPanel { Margin = new Thickness(0, 12, 10, 10) };
            Grid.SetColumn(info, 2);
            TextBlock title = Text(item.Course, 14, FontWeights.SemiBold, Brushes.White);
            title.TextTrimming = TextTrimming.CharacterEllipsis;
            TextBlock place = Text("⌖ " + ShortLocation(item.Location), 10.5, FontWeights.Normal, Brush("#D8E7DE"));
            place.Margin = new Thickness(0, 5, 0, 0);
            place.TextTrimming = TextTrimming.CharacterEllipsis;
            TextBlock teacher = Text("♙ " + item.Teacher, 10, FontWeights.Normal, Brush("#ADC5B7"));
            teacher.Margin = new Thickness(0, 3, 0, 0);
            teacher.TextTrimming = TextTrimming.CharacterEllipsis;
            info.Children.Add(title);
            info.Children.Add(place);
            info.Children.Add(teacher);
            grid.Children.Add(info);

            Border badge = new Border
            {
                CornerRadius = new CornerRadius(12),
                Background = new SolidColorBrush(Color.FromArgb(28, 255, 255, 255)),
                Padding = new Thickness(8, 4, 8, 4),
                Margin = new Thickness(0, 13, 12, 0),
                VerticalAlignment = VerticalAlignment.Top,
                Child = Text((live ? "进行中" : next ? "下一节" : item.Nodes + " 节"), 9.5, FontWeights.Medium, live || next ? new SolidColorBrush(accent) : Brush("#91A697"))
            };
            Grid.SetColumn(badge, 3);
            grid.Children.Add(badge);
            card.Child = grid;
            card.Cursor = Cursors.Hand;
            card.ToolTip = "左键查看课程详情";
            card.MouseLeftButtonUp += delegate(object sender, MouseButtonEventArgs e)
            {
                ShowCourseDetails(item);
                e.Handled = true;
            };
            return card;
        }

        private void ShowCourseDetails(ScheduleEntry item)
        {
            Color accent = (Color)ColorConverter.ConvertFromString(item.Color);
            Window dialog = new Window
            {
                Title = "课程详情",
                Width = 372,
                SizeToContent = SizeToContent.Height,
                WindowStyle = WindowStyle.None,
                ResizeMode = ResizeMode.NoResize,
                AllowsTransparency = true,
                Background = Brushes.Transparent,
                ShowInTaskbar = false,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                Owner = this,
                Topmost = Topmost
            };

            Border shell = new Border
            {
                CornerRadius = new CornerRadius(24),
                BorderThickness = new Thickness(1),
                BorderBrush = new SolidColorBrush(Color.FromArgb(178, accent.R, accent.G, accent.B)),
                Background = new LinearGradientBrush(
                    Color.FromArgb(248, 24, 78, 94),
                    Color.FromArgb(250, 7, 35, 52), 135),
                Padding = new Thickness(20, 17, 20, 18),
                Margin = new Thickness(12),
                Effect = new DropShadowEffect
                {
                    BlurRadius = 26,
                    ShadowDepth = 7,
                    Opacity = .34,
                    Color = Color.FromRgb(0, 12, 20)
                }
            };

            StackPanel content = new StackPanel();
            Grid heading = new Grid();
            heading.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            heading.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            StackPanel title = new StackPanel();
            title.Children.Add(Text("课程详情", 11, FontWeights.Medium, Brush("#A9C7BA")));
            TextBlock courseName = Text(item.Course, 20, FontWeights.Bold, Brushes.White);
            courseName.Margin = new Thickness(0, 4, 10, 0);
            courseName.TextWrapping = TextWrapping.Wrap;
            title.Children.Add(courseName);
            heading.Children.Add(title);

            Border close = MiniButton("×", delegate { dialog.Close(); }, 32);
            close.Margin = new Thickness(8, 0, 0, 0);
            Grid.SetColumn(close, 1);
            heading.Children.Add(close);
            content.Children.Add(heading);

            Border divider = new Border
            {
                Height = 1,
                Margin = new Thickness(0, 15, 0, 12),
                Background = new LinearGradientBrush(
                    Color.FromArgb(105, accent.R, accent.G, accent.B),
                    Color.FromArgb(25, 255, 255, 255), 0)
            };
            content.Children.Add(divider);

            string date = item.Date.ToString("yyyy年M月d日") + " · " + ChineseWeekday(item.Date.DayOfWeek);
            string time = item.Start.ToString(@"hh\:mm") + " – " + item.End.ToString(@"hh\:mm");
            content.Children.Add(DetailRow("日期", date));
            content.Children.Add(DetailRow("时间", time));
            content.Children.Add(DetailRow("节次", string.IsNullOrWhiteSpace(item.Nodes) ? "—" : item.Nodes));
            content.Children.Add(DetailRow("教学周", item.Week > 0 ? "第 " + item.Week + " 周" : "—"));
            content.Children.Add(DetailRow("教师", string.IsNullOrWhiteSpace(item.Teacher) ? "—" : item.Teacher));
            content.Children.Add(DetailRow("地点", string.IsNullOrWhiteSpace(item.Location) ? "—" : item.Location));

            TextBlock source = Text("数据来源 · " + System.IO.Path.GetFileName(_dataPath), 9.5, FontWeights.Normal, Brush("#78988B"));
            source.Margin = new Thickness(0, 12, 0, 0);
            content.Children.Add(source);

            shell.Child = content;
            dialog.Content = shell;
            dialog.KeyDown += delegate(object sender, KeyEventArgs e)
            {
                if (e.Key == Key.Escape) dialog.Close();
            };
            dialog.ShowDialog();
        }

        private static Grid DetailRow(string label, string value)
        {
            Grid row = new Grid { Margin = new Thickness(0, 4, 0, 4) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(62) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            TextBlock key = Text(label, 11, FontWeights.Medium, Brush("#93B1A4"));
            TextBlock detail = Text(value, 12, FontWeights.Medium, Brush("#EDF8F2"));
            detail.TextWrapping = TextWrapping.Wrap;
            Grid.SetColumn(detail, 1);
            row.Children.Add(key);
            row.Children.Add(detail);
            return row;
        }

        private void ToggleExpanded()
        {
            _expandedState = !_expandedState;
            if (_expandedState)
            {
                _expanded.Visibility = Visibility.Visible;
                _glass.CornerRadius = new CornerRadius(28);
                RefreshExpanded(true, false);
            }
            RefreshHeader();
            AnimateHeight(_expandedState ? DesiredExpandedHeight() : CollapsedHeight, _expandedState ? 340 : 260);
            DoubleAnimation fade = new DoubleAnimation
            {
                To = _expandedState ? 1 : 0,
                Duration = TimeSpan.FromMilliseconds(_expandedState ? 280 : 150),
                EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
            };
            fade.Completed += delegate
            {
                if (!_expandedState)
                {
                    _expanded.Visibility = Visibility.Collapsed;
                    _glass.CornerRadius = new CornerRadius(34);
                }
            };
            _expanded.BeginAnimation(OpacityProperty, fade);
        }

        private double DesiredExpandedHeight()
        {
            int count = _all.Count(x => x.Date.Date == _selectedDate.Date);
            double contentHeight = count == 0 ? 408 : Math.Min(618, 346 + Math.Min(count, 4) * 68);
            Rect area = SystemParameters.WorkArea;
            double maximumVisibleHeight = Math.Max(CollapsedHeight, area.Bottom - Top - 12);

            // If the island was dragged too close to the bottom edge, move it up
            // instead of allowing the week strip and course cards to be clipped.
            if (contentHeight > maximumVisibleHeight)
            {
                Top = Math.Max(area.Top + 12, area.Bottom - contentHeight - 12);
                maximumVisibleHeight = Math.Max(CollapsedHeight, area.Bottom - Top - 12);
            }

            return Math.Min(contentHeight, maximumVisibleHeight);
        }

        private void AnimateHeight(double target, int milliseconds)
        {
            int version = ++_heightAnimationVersion;
            double start = ActualHeight > 0 ? ActualHeight : Height;

            // Clear a previous animation and make its current visual value the
            // new base value. This prevents Height from snapping back to 96px.
            BeginAnimation(HeightProperty, null);
            Height = start;

            DoubleAnimation height = new DoubleAnimation
            {
                From = start,
                To = target,
                Duration = TimeSpan.FromMilliseconds(milliseconds),
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
                FillBehavior = FillBehavior.Stop
            };
            height.Completed += delegate
            {
                if (version != _heightAnimationVersion) return;
                BeginAnimation(HeightProperty, null);
                Height = target;
            };
            BeginAnimation(HeightProperty, height, HandoffBehavior.SnapshotAndReplace);
        }

        private void HeaderMouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton != MouseButton.Left) return;
            double beforeLeft = Left;
            double beforeTop = Top;
            try { DragMove(); } catch (InvalidOperationException) { }
            if (Math.Abs(Left - beforeLeft) < 2 && Math.Abs(Top - beforeTop) < 2) ToggleExpanded();
            e.Handled = true;
        }

        private ContextMenu BuildContextMenu()
        {
            ContextMenu menu = new ContextMenu
            {
                Background = Brush("#F0151D17"),
                Foreground = Brushes.White,
                BorderBrush = Brush("#506C8A72"),
                BorderThickness = new Thickness(1),
                Padding = new Thickness(5)
            };
            MenuItem toggle = new MenuItem { Header = "展开 / 收起" };
            toggle.Click += delegate { ToggleExpanded(); };
            MenuItem reload = new MenuItem { Header = "重新载入课表" };
            reload.Click += delegate { LoadData(); };
            MenuItem top = new MenuItem { Header = "始终置顶", IsCheckable = true, IsChecked = true };
            top.Click += delegate { Topmost = top.IsChecked; };
            MenuItem startup = new MenuItem { Header = "开机自动启动", IsCheckable = true, IsChecked = IsStartupEnabled() };
            startup.Click += delegate { SetStartup(startup.IsChecked); };
            MenuItem exit = new MenuItem { Header = "退出课表岛" };
            exit.Click += delegate { Close(); };
            menu.Items.Add(toggle);
            menu.Items.Add(reload);
            menu.Items.Add(new Separator());
            menu.Items.Add(top);
            menu.Items.Add(startup);
            menu.Items.Add(new Separator());
            menu.Items.Add(exit);
            return menu;
        }

        private void OnKeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Escape && _expandedState) ToggleExpanded();
            else if (e.Key == Key.R && Keyboard.Modifiers == ModifierKeys.Control) LoadData();
        }

        private static Border MiniButton(string label, Action action, double width = 34)
        {
            Border button = new Border
            {
                Width = width,
                Height = 29,
                CornerRadius = new CornerRadius(14.5),
                Margin = new Thickness(4, 0, 0, 0),
                Background = new SolidColorBrush(Color.FromArgb(48, 228, 255, 255)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(64, 243, 255, 255)),
                BorderThickness = new Thickness(1),
                Cursor = Cursors.Hand,
                Child = Text(label, label.Length > 1 ? 10.5 : 18, FontWeights.Medium, Brush("#C9DBCF"))
            };
            ((TextBlock)button.Child).TextAlignment = TextAlignment.Center;
            ((TextBlock)button.Child).VerticalAlignment = VerticalAlignment.Center;
            button.MouseLeftButtonUp += delegate(object sender, MouseButtonEventArgs e) { action(); e.Handled = true; };
            return button;
        }

        private static TextBlock Text(string value, double size, FontWeight weight, Brush foreground)
        {
            return new TextBlock
            {
                Text = value,
                FontFamily = new FontFamily("Microsoft YaHei UI, Segoe UI"),
                FontSize = size,
                FontWeight = weight,
                Foreground = foreground,
                VerticalAlignment = VerticalAlignment.Center
            };
        }

        private static SolidColorBrush Brush(string value)
        {
            return new SolidColorBrush((Color)ColorConverter.ConvertFromString(value));
        }

        private static string ShortLocation(string value)
        {
            if (string.IsNullOrWhiteSpace(value)) return "地点待定";
            string text = value.Replace("前卫-", "").Replace("（医学优先）", "").Replace("（三四教班 预防 放射 护理 康复）", "").Replace("（医学优先 基础医学院生命科学优先）", "").Replace("(地学优先)", "").Replace("（地学优先）", "");
            string[] locations = text.Split(new[] { " / " }, StringSplitOptions.RemoveEmptyEntries);
            if (locations.Length > 2) text = locations[0] + " / " + locations[1] + " 等";
            return text.Length > 31 ? text.Substring(0, 30) + "…" : text;
        }

        private static string FormatTime(TimeSpan time) { return DateTime.Today.Add(time).ToString("HH:mm"); }

        private static string ChineseWeekday(DayOfWeek day)
        {
            string[] names = { "周日", "周一", "周二", "周三", "周四", "周五", "周六" };
            return names[(int)day];
        }

        private static string FriendlyDuration(TimeSpan span)
        {
            if (span.TotalMinutes < 1) return "不到 1 分钟";
            if (span.TotalHours < 1) return ((int)Math.Ceiling(span.TotalMinutes)) + " 分钟";
            return ((int)span.TotalHours) + " 小时 " + span.Minutes + " 分";
        }

        private static string FriendlyUntil(TimeSpan span)
        {
            if (span.TotalMinutes < 1) return "马上开始";
            if (span.TotalHours < 1) return "还有 " + ((int)Math.Ceiling(span.TotalMinutes)) + " 分钟";
            if (span.TotalDays < 1) return "还有 " + ((int)span.TotalHours) + " 小时";
            int days = (int)Math.Floor(span.TotalDays);
            return days == 1 ? "明天" : "还有 " + days + " 天";
        }

        private static DateTime StartOfWeek(DateTime value)
        {
            int offset = ((int)value.DayOfWeek + 6) % 7;
            return value.Date.AddDays(-offset);
        }

        private static DateTime InferTermStart(List<ScheduleEntry> entries)
        {
            List<DateTime> candidates = entries.Where(x => x.Week > 0)
                .Select(x => StartOfWeek(x.Date).AddDays(-7 * (x.Week - 1)))
                .ToList();
            if (candidates.Count == 0) return DateTime.MinValue;
            return candidates.GroupBy(x => x).OrderByDescending(g => g.Count()).First().Key;
        }

        private int WeekNumber(DateTime date)
        {
            if (_termStart == DateTime.MinValue) return 0;
            return (int)Math.Floor((StartOfWeek(date) - _termStart).TotalDays / 7) + 1;
        }

        private static string DayTitle(DateTime date)
        {
            if (date.Date == DateTime.Today) return "今天 · " + date.ToString("M月d日") + " " + ChineseWeekday(date.DayOfWeek);
            if (date.Date == DateTime.Today.AddDays(1)) return "明天 · " + date.ToString("M月d日") + " " + ChineseWeekday(date.DayOfWeek);
            return date.ToString("M月d日") + " · " + ChineseWeekday(date.DayOfWeek);
        }

        private static bool IsStartupEnabled()
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", false))
                    return key != null && key.GetValue("XiangbeiLiquidIsland") != null;
            }
            catch { return false; }
        }

        private static void SetStartup(bool enabled)
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true))
                {
                    if (key == null) return;
                    if (enabled) key.SetValue("XiangbeiLiquidIsland", "\"" + Process.GetCurrentProcess().MainModule.FileName + "\"");
                    else key.DeleteValue("XiangbeiLiquidIsland", false);
                }
            }
            catch { }
        }

        private void OnSourceInitialized(object sender, EventArgs e)
        {
            IntPtr hwnd = new WindowInteropHelper(this).Handle;
            try
            {
                AccentPolicy accent = new AccentPolicy
                {
                    AccentState = 4,
                    AccentFlags = 2,
                    GradientColor = unchecked((int)0x8F573E1B),
                    AnimationId = 0
                };
                int size = Marshal.SizeOf(accent);
                IntPtr memory = Marshal.AllocHGlobal(size);
                Marshal.StructureToPtr(accent, memory, false);
                WindowCompositionAttributeData data = new WindowCompositionAttributeData
                {
                    Attribute = 19,
                    Data = memory,
                    SizeOfData = size
                };
                SetWindowCompositionAttribute(hwnd, ref data);
                Marshal.FreeHGlobal(memory);

                int preference = 2;
                DwmSetWindowAttribute(hwnd, 33, ref preference, Marshal.SizeOf(typeof(int)));
            }
            catch { }
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct AccentPolicy
        {
            public int AccentState;
            public int AccentFlags;
            public int GradientColor;
            public int AnimationId;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct WindowCompositionAttributeData
        {
            public int Attribute;
            public IntPtr Data;
            public int SizeOfData;
        }

        [DllImport("user32.dll")]
        private static extern int SetWindowCompositionAttribute(IntPtr hwnd, ref WindowCompositionAttributeData data);

        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
    }

    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            try
            {
                Application app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
                string[] args = Environment.GetCommandLineArgs();
                bool expanded = args.Any(x => string.Equals(x, "--expanded", StringComparison.OrdinalIgnoreCase));
                bool next = args.Any(x => string.Equals(x, "--next", StringComparison.OrdinalIgnoreCase));
                LiquidIslandWindow window = new LiquidIslandWindow(expanded, next);
                app.MainWindow = window;
                window.Show();
                app.Run();
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.ToString(), "向北课表岛启动失败", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }
    }
}
