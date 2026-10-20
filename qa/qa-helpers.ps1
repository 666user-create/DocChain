# DocChain 视觉验收辅助函数
# 用法: . .\qa\qa-helpers.ps1; Shot 'a.png'; Click 100 200; (FindAndActivate 'DocChain')
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;

public class QA {
    [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint x, uint y, uint d, UIntPtr e);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();

    public delegate bool EnumProc(IntPtr h, IntPtr p);
    static readonly StringBuilder buf = new StringBuilder(256);
    static string needle = "";
    static IntPtr hit = IntPtr.Zero;
    static readonly EnumProc keepAlive;   // 防止委托被 GC

    static QA() { keepAlive = Match; }

    [DllImport("user32.dll")] public static extern void keybd_event(byte v, byte s, uint f, UIntPtr e);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();

    // idle-timer reset: bare Ctrl tap, no shortcut side effects
    public static void TapCtrl() {
        keybd_event(0x11, 0, 0, UIntPtr.Zero);
        System.Threading.Thread.Sleep(60);
        keybd_event(0x11, 0, 2, UIntPtr.Zero);
    }

    static bool Match(IntPtr h, IntPtr p) {
        buf.Length = 0;
        GetWindowText(h, buf, 256);
        if (buf.ToString().IndexOf(needle, StringComparison.OrdinalIgnoreCase) >= 0) { hit = h; return false; }
        return true;
    }

    public static IntPtr FindWindowByTitle(string part) {
        needle = part; hit = IntPtr.Zero;
        EnumWindows(keepAlive, IntPtr.Zero);
        return hit;
    }

    public static bool Activate(IntPtr h) {
        if (h == IntPtr.Zero) return false;
        ShowWindow(h, 9);                       // SW_RESTORE
        IntPtr fg = GetForegroundWindow();
        uint t = GetCurrentThreadId();
        uint pidTmp;
        uint target = GetWindowThreadProcessId(h, out pidTmp);
        GetWindowThreadProcessId(fg, out pidTmp);
        AttachThreadInput(t, target, true);
        bool ok = SetForegroundWindow(h);
        AttachThreadInput(t, target, false);
        return ok;
    }

    public static string ForegroundTitle() {
        IntPtr h = GetForegroundWindow();
        buf.Length = 0;
        GetWindowText(h, buf, 256);
        return buf.ToString();
    }
}
'@

# 立即声明 DPI 感知：之后坐标与物理分辨率(1920x1080)一致，与 1920 截图 1:1 对应
[QA]::SetProcessDPIAware() | Out-Null

function Shot([string]$path) {
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

function Click([int]$x, [int]$y) {
    [QA]::SetCursorPos($x, $y) | Out-Null
    Start-Sleep -Milliseconds 150
    [QA]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 70
    [QA]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 300
}

function RightClick([int]$x, [int]$y) {
    [QA]::SetCursorPos($x, $y) | Out-Null
    Start-Sleep -Milliseconds 150
    [QA]::mouse_event(0x0008, 0, 0, 0, [UIntPtr]::Zero)   # right down
    Start-Sleep -Milliseconds 70
    [QA]::mouse_event(0x0010, 0, 0, 0, [UIntPtr]::Zero)   # right up
    Start-Sleep -Milliseconds 500
}

function ForegroundTitle() { return [QA]::ForegroundTitle() }

function FindAndActivate([string]$title) {
    $h = [QA]::FindWindowByTitle($title)
    if ($h -eq [IntPtr]::Zero) { return $false }
    [QA]::Activate($h) | Out-Null
    Start-Sleep -Milliseconds 500
    return $true
}
