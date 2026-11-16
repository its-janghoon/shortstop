# Shortstop check on a Remote Test Lab phone, in one run (about a minute).
# Usage (Windows PowerShell 5.1 or 7):  powershell -ExecutionPolicy Bypass -File C:\Users\USER\shortstop-test\rtl-check.ps1
# Needs adb (PATH, the Android SDK, or C:\Users\USER\shortstop-test\platform-tools) and exactly one device in "device" state.
# It does not care which local port the Remote Test Lab tunnel uses: it takes the serial adb reports.

$ErrorActionPreference = 'Continue'
$Dir = 'C:\Users\USER\shortstop-test'
$Apk = Join-Path $Dir 'shortstop-debug.apk'
$Out = Join-Path $Dir 'out'
$Pkg = 'io.github.itsjanghoon.shortstop'
$Examples = @('Parcel fee text', 'Bank account locked', 'Real delivery notice', 'Contract to paste into AI')
$Verdicts = @('SCAM.', 'CAREFUL.', 'SAFE.')
$ShareText = 'Acme Bank: unusual sign-in, your account is locked. Verify now at acrnebank.com/secure or call 555-201-3344 within 24 hours'
$clock = [Diagnostics.Stopwatch]::StartNew()
New-Item -ItemType Directory -Force -Path $Out | Out-Null

function Say($msg) { Write-Host ('[{0,5:N1}s] {1}' -f $clock.Elapsed.TotalSeconds, $msg) }

# --- adb and the device -----------------------------------------------------------------------------
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", (Join-Path $Dir 'platform-tools\adb.exe'))) {
    if (-not $adb -and (Test-Path $c)) { $adb = $c }
}
if (-not $adb) { Write-Error 'adb not found'; exit 1 }

$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($devices.Count -ne 1) {
    Write-Error ("Need exactly one device in 'device' state, found {0}:`n{1}" -f $devices.Count, ((& $adb devices) -join "`n"))
    exit 1
}
$serial = $devices[0]
Say "device $serial"
function A { & $adb -s $serial @args }

# --- screen on, unlocked, no animations (uiautomator needs an idle screen) ----------------------------
A shell svc power stayon true | Out-Null
A shell input keyevent KEYCODE_WAKEUP | Out-Null
A shell wm dismiss-keyguard | Out-Null
A shell input keyevent 82 | Out-Null
$oldScale = @{}
foreach ($k in @('animator_duration_scale', 'transition_animation_scale', 'window_animation_scale')) {
    $oldScale[$k] = ((A shell settings get global $k) -join '').Trim()
    A shell settings put global $k 0 | Out-Null
}
$size = ((A shell wm size) -join ' ') -replace '.*?(\d+)x(\d+).*', '$1 $2'
$w, $h = $size -split ' ' | ForEach-Object { [int]$_ }
if (-not $w) { $w = 1080; $h = 2340 }
Say "screen ${w}x$h"

# --- install and launch -------------------------------------------------------------------------------
Say 'installing'
A install -r -g $Apk | Select-Object -Last 1 | ForEach-Object { Say $_ }
A logcat -c | Out-Null
A shell am force-stop $Pkg | Out-Null
A shell am start -W -n "$Pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 2

Say 'crash report from earlier runs (ShortstopCrash and files/last-crash.txt):'
A logcat -d -v brief 'ShortstopCrash:V' '*:S' | Select-Object -First 60 | ForEach-Object { Write-Host "  $_" }
A shell run-as $Pkg cat files/last-crash.txt 2>$null | Select-Object -First 60 | ForEach-Object { Write-Host "  crash-file: $_" }

# --- ui helpers ---------------------------------------------------------------------------------------
$dumpLocal = Join-Path $Out 'ui.xml'
function Dump {
    A shell uiautomator dump /sdcard/ss-ui.xml | Out-Null
    Remove-Item $dumpLocal -ErrorAction SilentlyContinue
    A pull /sdcard/ss-ui.xml $dumpLocal 2>$null | Out-Null
    if (-not (Test-Path $dumpLocal)) { return $null }
    try { return [xml](Get-Content -Raw -Encoding UTF8 $dumpLocal) } catch { return $null }
}
function Find($xml, $text) {
    if (-not $xml) { return $null }
    $n = $xml.SelectNodes('//node') | Where-Object { $_.text -eq $text -or $_.'content-desc' -eq $text } | Select-Object -First 1
    if (-not $n) { return $null }
    if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        return @{ x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2); y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2) }
    }
    return $null
}
function Swipe($fromY, $toY) { A shell input swipe ([int]($w / 2)) ([int]$fromY) ([int]($w / 2)) ([int]$toY) 250 | Out-Null }
function ToTop { 1..3 | ForEach-Object { Swipe ($h * 0.3) ($h * 0.85) } }
# Tap the element with this text, scrolling down until it shows. True if it was tapped.
function Tap($text, [switch]$fromTop) {
    if ($fromTop) { ToTop }
    for ($i = 0; $i -lt 6; $i++) {
        $p = Find (Dump) $text
        if ($p) { A shell input tap $p.x $p.y | Out-Null; return $true }
        Swipe ($h * 0.75) ($h * 0.35)
    }
    Say "NOT FOUND: '$text'"
    return $false
}
# Wait until a verdict word is on screen. Returns it, or $null after $max seconds.
function WaitVerdict($max = 25) {
    $t = [Diagnostics.Stopwatch]::StartNew()
    while ($t.Elapsed.TotalSeconds -lt $max) {
        $x = Dump
        foreach ($v in $Verdicts) { if (Find $x $v) { return $v } }
        Start-Sleep -Milliseconds 500
    }
    return $null
}
function Shot($name) {
    A shell screencap -p /sdcard/ss-shot.png | Out-Null
    $f = Join-Path $Out "a-$name.png"
    A pull /sdcard/ss-shot.png $f 2>$null | Out-Null
    Say "  saved $f"
}

# --- the four examples --------------------------------------------------------------------------------
foreach ($e in $Examples) {
    $name = ($e.ToLower() -replace '[^a-z0-9]+', '-').Trim('-')
    Say "example: $e"
    if (-not (Tap $e -fromTop)) { Shot "$name-notfound"; continue }
    $v = WaitVerdict 25
    if ($v) { Say "  verdict $v" } else { Say '  NO VERDICT within 25 s' }
    Start-Sleep -Milliseconds 700
    Shot $name
    if (-not (Tap 'Check another')) { A shell input keyevent KEYCODE_BACK | Out-Null }
    Start-Sleep -Milliseconds 500
}

# --- share a text into the running app ----------------------------------------------------------------
Say 'share intent'
A shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'$ShareText'" -n "$Pkg/.MainActivity" | Out-Null
$v = WaitVerdict 25
if ($v) { Say "  verdict $v" } else { Say '  NO VERDICT within 25 s' }
Start-Sleep -Milliseconds 700
Shot 'share'

# --- logs ---------------------------------------------------------------------------------------------
Write-Host ''
Say '=== logcat -b crash ==='
A logcat -d -b crash | ForEach-Object { Write-Host "  $_" }
Say '=== ShortstopCrash ==='
A logcat -d -v brief 'ShortstopCrash:V' '*:S' | ForEach-Object { Write-Host "  $_" }
A shell run-as $Pkg cat files/last-crash.txt 2>$null | ForEach-Object { Write-Host "  crash-file: $_" }
Say '=== AppCheck | FirebaseAI | GenAi ==='
A logcat -d -v brief | Select-String -Pattern 'AppCheck|FirebaseAI|GenAi' | Select-Object -Last 80 | ForEach-Object { Write-Host "  $($_.Line)" }

foreach ($k in $oldScale.Keys) {
    $v = $oldScale[$k]
    if ($v -and $v -ne 'null') { A shell settings put global $k $v | Out-Null } else { A shell settings put global $k 1 | Out-Null }
}
Say "done in $([int]$clock.Elapsed.TotalSeconds) s; screenshots in $Out"
