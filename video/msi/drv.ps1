# Input driver for the video capture on the Windows laptop. Runs in the desktop session (scheduled task /IT).
# Reads one command per line from the file given as the first argument and logs every step with
# milliseconds since start to out\<name>.log, so the edit can put a drawn cursor on the same path.
#   wait <ms> | move <x> <y> <ms> | click | rclick | down | up | keys <SendKeys> | clip <text>
#   shot <name> | rec <file> <seconds> | focus <title part> | mark <label> | park
param($script)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @'
using System; using System.Runtime.InteropServices;
public class U {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, IntPtr e);
  public struct POINT { public int X; public int Y; }
}
'@
[U]::SetProcessDPIAware() | Out-Null
$dir = "C:\Users\USER\shortstop-test"
$name = [IO.Path]::GetFileNameWithoutExtension($script)
$log = "$dir\out\$name.log"
Set-Content $log ""
$sw = [Diagnostics.Stopwatch]::StartNew()
function L($s) { Add-Content $log ("{0} {1}" -f $sw.ElapsedMilliseconds, $s) }
function Pos { $p = New-Object U+POINT; [U]::GetCursorPos([ref]$p) | Out-Null; $p }
function Ease($t) { if ($t -lt .5) { 4 * $t * $t * $t } else { 1 - [Math]::Pow(-2 * $t + 2, 3) / 2 } }
foreach ($line in Get-Content $script) {
  if (-not $line.Trim() -or $line.StartsWith("#")) { continue }
  $cmd, $rest = $line -split " ", 2
  switch ($cmd) {
    "wait" { Start-Sleep -Milliseconds ([int]$rest) }
    "move" {
      $a = $rest -split " "; $x1 = [int]$a[0]; $y1 = [int]$a[1]; $ms = [int]$a[2]
      $p = Pos; $x0 = $p.X; $y0 = $p.Y
      # A gentle arc: the control point sits off the straight line.
      $cx = ($x0 + $x1) / 2 - ($y1 - $y0) * 0.12; $cy = ($y0 + $y1) / 2 + ($x1 - $x0) * 0.12
      $t0 = $sw.ElapsedMilliseconds
      do {
        $t = [Math]::Min([double]1, ($sw.ElapsedMilliseconds - $t0) / [double]$ms); $e = Ease $t
        $x = (1 - $e) * (1 - $e) * $x0 + 2 * (1 - $e) * $e * $cx + $e * $e * $x1
        $y = (1 - $e) * (1 - $e) * $y0 + 2 * (1 - $e) * $e * $cy + $e * $e * $y1
        [U]::SetCursorPos([int]$x, [int]$y) | Out-Null
        L ("pos {0} {1}" -f [int]$x, [int]$y)
        Start-Sleep -Milliseconds 8
      } while ($t -lt 1)
    }
    "click" { $p = Pos; L "click $($p.X) $($p.Y)"; [U]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 60; [U]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero) }
    "rclick" { $p = Pos; L "rclick $($p.X) $($p.Y)"; [U]::mouse_event(8, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 60; [U]::mouse_event(16, 0, 0, 0, [IntPtr]::Zero) }
    "keys" { L "keys $rest"; [System.Windows.Forms.SendKeys]::SendWait($rest) }
    "clip" { Set-Clipboard -Value ($rest -replace '\\n', "`n"); L "clip" }
    "focus" { (New-Object -ComObject WScript.Shell).AppActivate($rest) | Out-Null; L "focus $rest" }
    "mark" { L "mark $rest" }
    "park" { [U]::SetCursorPos(3000, -500) | Out-Null; L "park" }
    "shot" {
      $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
      $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
      [System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen($b.Left, $b.Top, 0, 0, $bmp.Size)
      $bmp.Save("$dir\out\$rest.png"); $bmp.Dispose(); L "shot $rest"
    }
    "rec" {
      $a = $rest -split " "
      $fa = "-y -loglevel error -f gdigrab -framerate 30 -offset_x 0 -offset_y 0 -video_size 1920x1080 -draw_mouse 0 -i desktop -t $($a[1]) -c:v libx264 -preset ultrafast -crf 14 -pix_fmt yuv420p $dir\out\$($a[0])"
      Start-Process -WindowStyle Hidden -FilePath "$dir\tools\ffmpeg.exe" -ArgumentList $fa
      L "rec $($a[0])"
    }
  }
}
L "end"
