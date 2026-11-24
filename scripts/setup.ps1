<#
.SYNOPSIS
  MaterialTV: Fire TV setup and undo, run from a Windows computer.

.DESCRIPTION
  Amazon blocks apps on a Fire TV from using that TV's own ADB (Fire OS 7.6.6.9 and newer),
  so this script does from your computer what MaterialTV's built-in setup does on the TV.
  It follows src/app/m3home/Setup.java step for step and shares its change log
  (/sdcard/Android/data/app.m3home/files/setup/ledger.json), so Undo works from either side.

  Works in Windows PowerShell 5.1 and PowerShell 7. Needs no admin rights and no modules.
  Downloads only from dl.google.com, github.com, raw.githubusercontent.com and pkgs.tailscale.com.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File setup.ps1 -Tv 192.168.1.50

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File setup.ps1 -Tv 192.168.1.50 -Undo

.EXAMPLE
  irm https://raw.githubusercontent.com/Brick-Bread/MaterialTV/main/scripts/setup.ps1 | iex
  (set $env:M3_TV = '192.168.1.50' and/or $env:M3_UNDO = '1' first, since iex can't take parameters)
#>
param(
    [string]$Tv = '',
    [switch]$Undo,
    [switch]$Yes,
    [switch]$DryRun   # hidden: print every command that would change the TV, run none of them
)

# Everything lives in one hashtable so a run through `irm | iex` doesn't leave stale state behind.
$M3 = @{
    Repo        = 'Brick-Bread/MaterialTV'
    Self        = 'app.m3home'
    Guard       = 'app.m3home/app.m3home.HomeGuard'
    Dream       = 'app.m3home/app.m3home.Screensaver'
    Listener    = 'app.m3home/app.m3home.MediaListener'
    Tailscale   = 'com.tailscale.ipn'
    YouTube     = 'com.amazon.firetv.youtube'
    SmartTube   = @('org.smarttube.stable', 'com.teamsmart.videomanager.tv')
    SmartTubeUrl = 'https://github.com/yuliskov/SmartTube/releases/download/32.56s/SmartTube_stable_32.56_universal.apk'
    SmartTubeSha = '4a35b90940612e048e4e9cb9e10379f7db1e84f30375994129f10a571ee04ce5'
    LedgerDir   = '/sdcard/Android/data/app.m3home/files/setup'
    LedgerPath  = '/sdcard/Android/data/app.m3home/files/setup/ledger.json'
    ListNames   = @('packages.txt', 'protected.txt', 'settings.txt', 'never-disable.txt', 'alexa.txt', 'settings.restore.txt')
    FromFile    = [bool]$PSCommandPath -and ($MyInvocation.MyCommand.Path -eq $PSCommandPath)
    ScriptDir   = $PSScriptRoot
    Adb = $null; Dev = $null; Work = $null; Lists = @{}
    Ledger = $null; LedgerFound = $false
    Summary = New-Object System.Collections.Generic.List[string]
    Yes = $false; DryRun = $false
}
$M3.RawLists  = "https://raw.githubusercontent.com/$($M3.Repo)/main/assets/setup"
$M3.ApkUrl    = "https://github.com/$($M3.Repo)/releases/latest/download/m3home.apk"
$M3.ApiLatest = "https://api.github.com/repos/$($M3.Repo)/releases/latest"

function Test-Truthy([string]$v) { return @('1', 'true', 'yes', 'y') -contains "$v".Trim().ToLower() }

# ---- output -------------------------------------------------------------------------------

function Write-Step([string]$s) { Write-Host $s -ForegroundColor Magenta }
function Write-Info([string]$s) { Write-Host "  $s" }
function Write-Ok([string]$s) { Write-Host "  $s" -ForegroundColor Green }
function Write-Warn([string]$s) { Write-Host '  Warning: ' -ForegroundColor Yellow -NoNewline; Write-Host $s }
function Write-Fail([string]$s) { Write-Host 'Error: ' -ForegroundColor Red -NoNewline; Write-Host $s }
function Write-Dry([string]$s) { Write-Host "  [dry-run] $s" -ForegroundColor DarkGray }
function Add-Note([string]$s) { $M3.Summary.Add($s) }

# Stops the run; caught at the bottom so `irm | iex` never closes the user's window.
function Stop-Setup([string]$msg) { throw (New-Object System.Exception ("M3STOP:" + $msg)) }

function Read-Answer([string]$prompt) {
    $a = Read-Host -Prompt $prompt
    if ($null -eq $a) { return '' }
    return "$a".Trim()
}

function Confirm-Choice([string]$question, [bool]$default) {
    $hint = if ($default) { '[Y/n]' } else { '[y/N]' }
    if ($M3.Yes) {
        Write-Host ("{0} {1} {2}" -f $question, $hint, $(if ($default) { 'yes' } else { 'no' }))
        return $default
    }
    while ($true) {
        $a = (Read-Answer "$question $hint").ToLower()
        if ($a -eq '') { return $default }
        if ($a -eq 'y' -or $a -eq 'yes') { return $true }
        if ($a -eq 'n' -or $a -eq 'no') { return $false }
        Write-Host '  Please answer y or n.'
    }
}

# ---- downloads ----------------------------------------------------------------------------

function Get-Download([string]$url, [string]$outFile) {
    try {
        Invoke-WebRequest -Uri $url -OutFile $outFile -UseBasicParsing -UserAgent 'MaterialTV-Setup' -TimeoutSec 300 -ErrorAction Stop
        return $true
    } catch { return $false }
}

function Get-Text([string]$url) {
    try {
        $r = Invoke-WebRequest -Uri $url -UseBasicParsing -UserAgent 'MaterialTV-Setup' -TimeoutSec 30 -ErrorAction Stop
        if ($r.Content -is [byte[]]) { return [Text.Encoding]::UTF8.GetString($r.Content) }
        return [string]$r.Content
    } catch { return $null }
}

function Get-Sha256([string]$path) { return (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower() }

function Get-FirstToken([string]$text) {
    if ($null -eq $text) { return '' }
    $t = ($text.Trim() -split '\s+')[0]
    if ($null -eq $t) { return '' }
    return $t.ToLower()
}

# ---- adb ----------------------------------------------------------------------------------

function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { $M3.Adb = $cmd.Source; if (-not $M3.Adb) { $M3.Adb = $cmd.Path }; return }
    $onWindows = ($PSVersionTable.PSVersion.Major -lt 6) -or $IsWindows
    if ($onWindows) {
        $plat = 'windows'; $exe = 'adb.exe'
        $base = $env:LOCALAPPDATA; if (-not $base) { $base = $env:TEMP }
        $cache = Join-Path $base 'MaterialTV'
    } else {
        $exe = 'adb'
        if ($IsMacOS) { $plat = 'darwin'; $cache = Join-Path $HOME 'Library/Caches/materialtv' }
        else { $plat = 'linux'; $cache = Join-Path $HOME '.cache/materialtv' }
    }
    $adb = Join-Path (Join-Path $cache 'platform-tools') $exe
    if (Test-Path -LiteralPath $adb) { $M3.Adb = $adb; return }
    Write-Step 'Getting adb...'
    Write-Info "adb isn't installed, so downloading Google's platform-tools from dl.google.com."
    $zip = Join-Path $M3.Work 'platform-tools.zip'
    if (-not (Get-Download "https://dl.google.com/android/repository/platform-tools-latest-$plat.zip" $zip)) {
        Stop-Setup "Couldn't download platform-tools. Check your internet connection."
    }
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    $old = Join-Path $cache 'platform-tools'
    if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force }
    try { Expand-Archive -LiteralPath $zip -DestinationPath $cache -Force -ErrorAction Stop }
    catch { Stop-Setup "Couldn't unpack platform-tools: $($_.Exception.Message)" }
    if (-not $onWindows) { & chmod +x $adb 2>$null }
    $M3.Adb = $adb
    Write-Ok "adb is ready (kept in $cache for next time)."
}

# Runs adb and returns its output (stdout and stderr) as one string with LF line ends.
function Invoke-Adb([string[]]$ArgList) {
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $M3.Adb @ArgList 2>&1 | ForEach-Object { "$_" }
    } finally { $ErrorActionPreference = $eap }
    $M3.LastExit = $LASTEXITCODE
    if ($null -eq $out) { return '' }
    return ((@($out) -join "`n") -replace "`r", '')
}

# Read-only command on the TV.
function Invoke-TvQuery([string]$cmd) { return Invoke-Adb @('-s', $M3.Dev, 'shell', $cmd) }

# Command that changes the TV (printed, not run, in dry-run mode).
function Invoke-TvChange([string]$cmd) {
    if ($M3.DryRun) { Write-Dry "adb -s $($M3.Dev) shell $cmd"; return '' }
    return Invoke-TvQuery $cmd
}

function Get-Clean([string]$s) {
    if ($null -eq $s) { return '' }
    $s = $s.Trim()
    if ($s -eq 'null') { return '' }
    return $s
}

function Get-TvSetting([string]$nsKey) { return Get-Clean (Invoke-TvQuery "settings get $nsKey") }

function Get-Packages([string]$cmd) {
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($l in ((Invoke-TvQuery $cmd) -split "`n")) {
        $l = $l.Trim()
        if ($l.StartsWith('package:')) { [void]$set.Add(($l.Substring(8) -split '\s+')[0]) }
    }
    return , $set
}

function Get-Uid([string]$pkg) {
    $r = Invoke-TvQuery "dumpsys package $pkg | grep -m1 -E 'userId=|appId='"
    $m = [regex]::Match($r, '(userId|appId)=(\d+)')
    if ($m.Success) { return $m.Groups[2].Value }
    return $null
}

# "ns key" value; empty or "null" means it was unset -> settings delete.
function Restore-Setting([string]$nsKey, [string]$value) {
    if ([string]::IsNullOrEmpty($value) -or $value -eq 'null') { [void](Invoke-TvChange "settings delete $nsKey") }
    else { [void](Invoke-TvChange ("settings put $nsKey '" + ($value -replace "['""]", '') + "'")) }
}

function Connect-Tv {
    Write-Step "Connecting to the TV at $($M3.Dev)..."
    $out = Invoke-Adb @('connect', $M3.Dev)
    Write-Info $out.Trim()
    $start = Get-Date; $limit = 45; $told = $false
    while ($true) {
        $st = (Invoke-Adb @('-s', $M3.Dev, 'get-state')).Trim()
        if ($st -eq 'device') { break }
        if (("$st $out") -match 'unauthorized') {
            $limit = 180
            if (-not $told) {
                $told = $true
                Write-Host ''
                Write-Host '  Look at the TV now. ' -ForegroundColor White -NoNewline
                Write-Host 'It asks "Allow USB debugging?" for this computer.'
                Write-Info 'Tick "Always allow from this computer", then select Allow.'
                Write-Info 'Waiting for you to accept it...'
            }
        }
        if (((Get-Date) - $start).TotalSeconds -ge $limit) {
            Write-Host ''
            Stop-Setup ("Couldn't connect to the TV at $($M3.Dev).`n" +
                "  Check that:`n" +
                "  - the TV is on and on the same network as this computer, and the IP is right`n" +
                "    (Settings > My Fire TV > About > Network).`n" +
                "  - ADB debugging is on: Settings > My Fire TV > Developer options > ADB debugging.`n" +
                "    If Developer options is missing: Settings > My Fire TV > About, then select`n" +
                "    the device name 7 times.`n" +
                "  - you accepted ""Allow USB debugging?"" on the TV.")
        }
        Start-Sleep -Seconds 3
        if (-not $told) { $out = Invoke-Adb @('connect', $M3.Dev) }
    }
    Write-Ok 'Connected.'
}

function Test-Device {
    $manu = Get-Clean (Invoke-TvQuery 'getprop ro.product.manufacturer')
    $sdkText = Get-Clean (Invoke-TvQuery 'getprop ro.build.version.sdk')
    $model = Get-Clean (Invoke-TvQuery 'getprop ro.product.model')
    $fos = Get-Clean (Invoke-TvQuery 'getprop ro.build.version.name')
    $sdk = 0; [void][int]::TryParse($sdkText, [ref]$sdk)
    $fosText = if ($fos) { ", $fos" } else { '' }
    Write-Info ("TV: {0} {1}{2} (Android SDK {3})." -f $(if ($manu) { $manu } else { 'unknown' }), $(if ($model) { $model } else { 'unknown' }), $fosText, $(if ($sdkText) { $sdkText } else { '?' }))
    $odd = $false
    if ($manu -ne 'Amazon') { Write-Warn "This doesn't look like an Amazon Fire TV. MaterialTV and these lists are made for Fire OS."; $odd = $true }
    if ($sdk -lt 28) { Write-Warn 'This TV runs an Android older than 9 (Fire OS 7). MaterialTV needs Fire OS 7 or newer.'; $odd = $true }
    if ($odd -and -not $M3.Yes) {
        if (-not (Confirm-Choice 'Continue anyway?' $false)) { Stop-Setup 'Stopped.' }
    }
}

# ---- versions and MaterialTV install (Updater.java) ---------------------------------------------

function Test-Newer([string]$a, [string]$b) {
    $x = $a -split '\.'; $y = $b -split '\.'
    $n = [Math]::Max($x.Count, $y.Count)
    for ($i = 0; $i -lt $n; $i++) {
        $p = 0; $q = 0
        if ($i -lt $x.Count) { [void][int]::TryParse(($x[$i] -replace '[^0-9]', ''), [ref]$p) }
        if ($i -lt $y.Count) { [void][int]::TryParse(($y[$i] -replace '[^0-9]', ''), [ref]$q) }
        if ($p -ne $q) { return ($p -gt $q) }
    }
    return $false
}

function Get-LatestVersion {
    $body = Get-Text $M3.ApiLatest
    if (-not $body) { return '' }
    try { return ([string](ConvertFrom-Json $body).tag_name) -replace '^[vV]', '' } catch { return '' }
}

function Install-Apk([string]$file) {
    if ($M3.DryRun) { Write-Dry "adb -s $($M3.Dev) install -r $file"; return 'Success' }
    return Invoke-Adb @('-s', $M3.Dev, 'install', '-r', $file)
}

function Install-M3Home {
    if ($M3.DryRun) {
        Write-Dry "download $($M3.ApkUrl) (+ .sha256), check it, adb -s $($M3.Dev) install -r m3home.apk"
        return $true
    }
    $apk = Join-Path $M3.Work 'm3home.apk'
    Write-Info 'Downloading MaterialTV from GitHub...'
    if (-not (Get-Download $M3.ApkUrl $apk)) { Write-Fail "Couldn't download MaterialTV from GitHub."; return $false }
    $want = Get-FirstToken (Get-Text ($M3.ApkUrl + '.sha256'))
    if ($want.Length -ne 64 -or $want -ne (Get-Sha256 $apk)) {
        Remove-Item -LiteralPath $apk -Force
        Write-Fail "The MaterialTV download didn't match its checksum, so it wasn't installed."
        return $false
    }
    Write-Info 'Checksum OK. Installing...'
    $r = Install-Apk $apk
    Remove-Item -LiteralPath $apk -Force -ErrorAction SilentlyContinue
    if ($r -match 'Success') { Write-Ok 'MaterialTV installed.'; return $true }
    Write-Fail "MaterialTV didn't install: $r"
    if ($r -match 'UPDATE_INCOMPATIBLE|signatures') {
        Write-Host '  The MaterialTV on the TV is signed with a different key (a self-built copy?).'
        Write-Host '  Uninstall it first, or keep using your own build.'
    }
    return $false
}

function Confirm-M3Home {
    Write-Step 'MaterialTV app...'
    $path = (Invoke-TvQuery "pm path $($M3.Self)") -split "`n" | Where-Object { $_ -like 'package:*' }
    $latest = Get-LatestVersion
    if (-not $path) {
        Write-Info ("MaterialTV isn't on the TV yet. Installing the latest release{0} from GitHub." -f $(if ($latest) { " ($latest)" } else { '' }))
        if (-not (Install-M3Home)) { return $false }
        Add-Note ("Installed MaterialTV{0}." -f $(if ($latest) { " $latest" } else { '' }))
        return $true
    }
    $m = [regex]::Match((Invoke-TvQuery "dumpsys package $($M3.Self) | grep versionName"), 'versionName=(\S+)')
    $cur = if ($m.Success) { $m.Groups[1].Value } else { '' }
    $curText = if ($cur) { $cur } else { '?' }
    if (-not $latest) {
        Write-Ok "MaterialTV $curText is installed. (Couldn't check GitHub for a newer version.)"
    } elseif (Test-Newer $latest $(if ($cur) { $cur } else { '0' })) {
        Write-Info "MaterialTV $curText is installed; version $latest is out."
        if (Confirm-Choice "  Update MaterialTV to $latest?" $true) {
            if (Install-M3Home) { Add-Note "Updated MaterialTV to $latest." } else { Write-Warn "Carrying on with MaterialTV $curText." }
        }
    } else {
        Write-Ok "MaterialTV $curText is installed and up to date."
    }
    return $true
}

# ---- setup lists ----------------------------------------------------------------------------

function Get-Lists {
    $localDir = $null
    if ($M3.ScriptDir) {
        $d = Join-Path (Split-Path -Parent $M3.ScriptDir) 'assets\setup'
        if (Test-Path -LiteralPath $d) { $localDir = (Resolve-Path -LiteralPath $d).Path }
    }
    $remote = $false; $local = $false
    foreach ($name in $M3.ListNames) {
        $text = Get-Text "$($M3.RawLists)/$name"
        if ($text) { $remote = $true }
        elseif ($localDir -and (Test-Path -LiteralPath (Join-Path $localDir $name))) {
            $text = [IO.File]::ReadAllText((Join-Path $localDir $name)); $local = $true
        } else {
            Stop-Setup "Couldn't get the setup list $name from GitHub, and there's no local copy next to this script."
        }
        $M3.Lists[$name] = $text
    }
    if ($local -and -not $remote) { Write-Info "Using the setup lists in $localDir." }
    elseif ($local) { Write-Info 'Using the setup lists from GitHub (some from the local copy).' }
    else { Write-Info 'Using the setup lists from GitHub.' }
}

# One package per line (settings lists: the whole "ns key value" line). Like Setup.list.
function Get-List([string]$name) {
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($l in ($M3.Lists[$name] -split "`n")) {
        $h = $l.IndexOf('#'); if ($h -ge 0) { $l = $l.Substring(0, $h) }
        $l = $l.Trim()
        if ($l -eq '') { continue }
        if ($name.StartsWith('settings')) { $out.Add($l) } else { $out.Add(($l -split '\s+')[0]) }
    }
    return , $out
}

# ---- change log (ledger.json) ---------------------------------------------------------------

function ConvertTo-LedgerValue($v) {
    if ($null -eq $v) { return '' }
    if ($v -is [System.Array]) {
        $l = New-Object System.Collections.Generic.List[string]
        foreach ($x in $v) { $l.Add([string]$x) }
        return , $l
    }
    if ($v -is [System.Management.Automation.PSCustomObject]) {
        $o = [ordered]@{}
        foreach ($p in $v.PSObject.Properties) { $o[$p.Name] = $(if ($null -eq $p.Value) { '' } elseif ($p.Value -is [bool]) { $p.Value } else { [string]$p.Value }) }
        return $o
    }
    return $v
}

function Read-Ledger {
    $M3.Ledger = [ordered]@{}
    $M3.LedgerFound = $false
    $raw = (Invoke-TvQuery "cat $($M3.LedgerPath) 2>/dev/null").Trim()
    if (-not $raw.StartsWith('{')) { return }
    try { $obj = ConvertFrom-Json $raw } catch { Write-Warn "The change log on the TV couldn't be read; starting a new one."; return }
    foreach ($p in $obj.PSObject.Properties) { $M3.Ledger[$p.Name] = ConvertTo-LedgerValue $p.Value }
    $M3.LedgerFound = $true
}

# Keeps the first "before" value only, so re-running setup can't overwrite the original.
function Add-LedgerValue([string]$key, [string]$value) {
    if (-not $M3.Ledger.Contains($key)) { $M3.Ledger[$key] = $(if ($null -eq $value) { '' } else { $value }) }
}

function Get-LedgerArray([string]$key) {
    if (-not $M3.Ledger.Contains($key) -or -not ($M3.Ledger[$key] -is [System.Collections.Generic.List[string]])) {
        $M3.Ledger[$key] = New-Object System.Collections.Generic.List[string]
    }
    return , $M3.Ledger[$key]
}

function Add-LedgerItem([string]$key, [string]$value) {
    $a = Get-LedgerArray $key
    if (-not $a.Contains($value)) { $a.Add($value) }
}

function Get-LedgerObject([string]$key) {
    if (-not $M3.Ledger.Contains($key) -or -not ($M3.Ledger[$key] -is [System.Collections.Specialized.OrderedDictionary])) {
        $M3.Ledger[$key] = [ordered]@{}
    }
    return $M3.Ledger[$key]
}

function Get-LedgerString([string]$key) {
    if ($M3.Ledger.Contains($key) -and $null -ne $M3.Ledger[$key]) { return [string]$M3.Ledger[$key] }
    return ''
}

function ConvertTo-JsonString([string]$s) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    foreach ($c in $s.ToCharArray()) {
        switch ($c) {
            '"' { [void]$sb.Append('\"') }
            '\' { [void]$sb.Append('\\') }
            "`n" { [void]$sb.Append('\n') }
            "`r" { [void]$sb.Append('\r') }
            "`t" { [void]$sb.Append('\t') }
            default {
                if ([int]$c -lt 32) { [void]$sb.Append(('\u{0:x4}' -f [int]$c)) } else { [void]$sb.Append($c) }
            }
        }
    }
    [void]$sb.Append('"')
    return $sb.ToString()
}

# Hand-written so both PowerShell versions produce the same compact JSON as the app.
function ConvertTo-LedgerJson {
    $parts = New-Object System.Collections.Generic.List[string]
    foreach ($k in @($M3.Ledger.Keys)) {
        $v = $M3.Ledger[$k]
        if ($v -is [bool]) { $j = $(if ($v) { 'true' } else { 'false' }) }
        elseif ($v -is [System.Collections.Generic.List[string]]) { $j = '[' + ((@($v) | ForEach-Object { ConvertTo-JsonString $_ }) -join ',') + ']' }
        elseif ($v -is [System.Collections.Specialized.OrderedDictionary]) {
            $j = '{' + ((@($v.Keys) | ForEach-Object {
                $mv = $v[$_]
                (ConvertTo-JsonString ([string]$_)) + ':' + $(if ($mv -is [bool]) { $(if ($mv) { 'true' } else { 'false' }) } else { ConvertTo-JsonString ([string]$mv) })
            }) -join ',') + '}'
        }
        elseif ($v -is [int] -or $v -is [long] -or $v -is [double] -or $v -is [decimal]) { $j = [string]$v }
        else { $j = ConvertTo-JsonString ([string]$v) }
        $parts.Add((ConvertTo-JsonString ([string]$k)) + ':' + $j)
    }
    return '{' + ($parts -join ',') + '}'
}

function Save-Ledger {
    $json = ConvertTo-LedgerJson
    if ($M3.DryRun) {
        Write-Dry "adb -s $($M3.Dev) shell mkdir -p $($M3.LedgerDir)"
        Write-Dry "adb -s $($M3.Dev) push ledger.json $($M3.LedgerPath)"
        Write-Dry "ledger.json would be: $json"
        return $true
    }
    $file = Join-Path $M3.Work 'ledger.json'
    [IO.File]::WriteAllText($file, $json + "`n", (New-Object System.Text.UTF8Encoding($false)))
    [void](Invoke-TvQuery "mkdir -p $($M3.LedgerDir)")
    $r = Invoke-Adb @('-s', $M3.Dev, 'push', $file, $M3.LedgerPath)
    if ($M3.LastExit -eq 0) { Write-Info 'Saved the change log on the TV, so Undo can put everything back.'; return $true }
    Write-Warn "Couldn't save the change log on the TV ($($M3.LedgerPath)): $r"
    Write-Info "Undo will fall back to dumbtv's restore lists."
    return $false
}

# ---- setup steps (Setup.java) ---------------------------------------------------------------

function Invoke-Permissions {
    Write-Step 'MaterialTV permissions...'
    $a11y = Get-TvSetting 'secure enabled_accessibility_services'
    Add-LedgerValue 'a11y_before' $a11y
    if (-not $a11y.Contains($M3.Guard)) {
        $new = if ($a11y -eq '') { $M3.Guard } else { "$($a11y):$($M3.Guard)" }
        [void](Invoke-TvChange "settings put secure enabled_accessibility_services '$new'")
    }
    [void](Invoke-TvChange 'settings put secure accessibility_enabled 1')
    [void](Invoke-TvChange "pm grant $($M3.Self) android.permission.WRITE_SECURE_SETTINGS")
    [void](Invoke-TvChange "cmd notification allow_listener $($M3.Listener)")
    [void](Invoke-TvChange "appops set $($M3.Self) REQUEST_INSTALL_PACKAGES allow")
    [void](Invoke-TvChange "dumpsys deviceidle whitelist +$($M3.Self)")
    $ss = Get-TvSetting 'secure screensaver_components'
    Add-LedgerValue 'screensaver_before' $ss
    [void](Invoke-TvChange "settings put secure screensaver_components $($M3.Dream)")
    [void](Invoke-TvChange 'settings put secure screensaver_enabled 1')
    $M3.Ledger['permissions'] = $true
    Write-Ok 'Home button, screensaver, now playing and updates are set up.'
    Add-Note 'MaterialTV permissions granted, MaterialTV is the screensaver.'
}

function Invoke-StayOn([string]$current) {
    Write-Step 'Screensaver timeout...'
    Add-LedgerValue 'stay_on_before' $current
    [void](Invoke-TvChange 'settings put global stay_on_while_plugged_in 0')
    Write-Ok 'The TV no longer stays awake while plugged in, so the screensaver can start.'
    Add-Note "Turned off ""stay awake while plugged in"" (was $current)."
}

function Invoke-Debloat([bool]$keepAlexa) {
    Write-Step 'Debloat and privacy (dumbtv lists)...'
    $installed = Get-Packages 'pm list packages'
    $disabled = Get-Packages 'pm list packages -d'
    $never = Get-List 'never-disable.txt'
    $targets = New-Object System.Collections.Generic.List[string]
    $targets.AddRange((Get-List 'packages.txt'))
    if (-not $keepAlexa) { $targets.AddRange((Get-List 'alexa.txt')) }
    [void](Get-LedgerArray 'disabled')
    $count = 0
    foreach ($p in $targets) {
        if ($never.Contains($p) -or -not $installed.Contains($p) -or $disabled.Contains($p)) { continue }
        $r = Invoke-TvChange "pm disable-user --user 0 $p"
        if ($M3.DryRun -or $r -match 'disabled') { Add-LedgerItem 'disabled' $p; $count++ }
    }
    Write-Ok "Switched off $count Amazon ad and tracking apps."

    $bg = Get-Clean (Invoke-TvQuery 'cmd netpolicy get restrict-background')
    Add-LedgerValue 'data_saver_before' $(if ($bg -match 'enabled') { 'true' } else { 'false' })
    [void](Invoke-TvChange 'cmd netpolicy set restrict-background true')
    [void](Get-LedgerArray 'blacklisted_uids'); [void](Get-LedgerArray 'background_off')
    $quiet = 0
    foreach ($p in (Get-List 'protected.txt')) {
        if (-not $installed.Contains($p)) { continue }
        [void](Invoke-TvChange "appops set $p RUN_IN_BACKGROUND ignore; appops set $p RUN_ANY_IN_BACKGROUND ignore; am force-stop $p")
        Add-LedgerItem 'background_off' $p; $quiet++
        $uid = Get-Uid $p
        if ($uid -and [int64]$uid -ge 10000) {
            [void](Invoke-TvChange "cmd netpolicy add restrict-background-blacklist $uid")
            Add-LedgerItem 'blacklisted_uids' $uid
        }
    }
    Write-Ok "Limited $quiet apps Amazon won't let you switch off."

    $before = Get-LedgerObject 'settings_before'
    $n = 0
    foreach ($line in (Get-List 'settings.txt')) {
        $f = $line -split '\s+'
        if ($f.Count -lt 3) { continue }
        $key = "$($f[0]) $($f[1])"
        if (-not $before.Contains($key)) { $before[$key] = Get-TvSetting $key }
        [void](Invoke-TvChange "settings put $key $($f[2])")
        $n++
    }
    Write-Ok "Applied $n privacy settings."
    Add-Note "Debloat: $count apps switched off, $quiet protected apps limited, $n privacy settings."
}

function Invoke-SmartTube {
    Write-Step 'SmartTube...'
    $installed = Get-Packages 'pm list packages'
    $had = $false
    foreach ($p in $M3.SmartTube) { if ($installed.Contains($p)) { $had = $true } }
    if (-not $had) {
        $apk = Join-Path $M3.Work 'smarttube.apk'
        if ($M3.DryRun) {
            Write-Dry "download $($M3.SmartTubeUrl), check SHA-256 $($M3.SmartTubeSha)"
        } else {
            Write-Info 'Downloading SmartTube from GitHub...'
            if (-not (Get-Download $M3.SmartTubeUrl $apk)) { Write-Fail "Couldn't download SmartTube."; return $false }
            if ((Get-Sha256 $apk) -ne $M3.SmartTubeSha) { Remove-Item -LiteralPath $apk -Force; Write-Fail "SmartTube download didn't match its checksum."; return $false }
        }
        $r = Install-Apk $apk
        Remove-Item -LiteralPath $apk -Force -ErrorAction SilentlyContinue
        if ($r -notmatch 'Success') { Write-Fail "SmartTube didn't install: $r"; return $false }
        Add-LedgerItem 'installed' $M3.SmartTube[0]
        Write-Ok 'Installed SmartTube.'
        Add-Note 'Installed SmartTube.'
    } else {
        Write-Ok 'SmartTube was already installed.'
    }
    if ($installed.Contains($M3.YouTube) -and -not (Get-Packages 'pm list packages -d').Contains($M3.YouTube)) {
        [void](Invoke-TvChange "pm disable-user --user 0 $($M3.YouTube)")
        Add-LedgerItem 'disabled' $M3.YouTube
        Write-Ok 'Switched off the Amazon YouTube app.'
        Add-Note 'Switched off the Amazon YouTube app.'
    }
    return $true
}

function Invoke-Tailscale {
    Write-Step 'Tailscale...'
    $ts = $M3.Tailscale
    if (-not (Get-Packages 'pm list packages').Contains($ts)) {
        $page = Get-Text 'https://pkgs.tailscale.com/stable/'
        $m = [regex]::Match($(if ($page) { $page } else { '' }), 'tailscale-android-universal-[0-9.]+\.apk')
        if (-not $m.Success) { Write-Fail "Couldn't find Tailscale's Android download."; return $false }
        $name = $m.Value
        $url = "https://pkgs.tailscale.com/stable/$name"
        $want = Get-FirstToken (Get-Text "$url.sha256")
        $apk = Join-Path $M3.Work 'tailscale.apk'
        if ($M3.DryRun) {
            Write-Dry "download $url, check SHA-256 $(if ($want) { $want } else { '?' })"
        } else {
            Write-Info "Downloading $($name -replace '\.apk$', '') from pkgs.tailscale.com..."
            if (-not (Get-Download $url $apk)) { Write-Fail "Couldn't download Tailscale."; return $false }
            if ($want.Length -ne 64 -or $want -ne (Get-Sha256 $apk)) { Remove-Item -LiteralPath $apk -Force; Write-Fail "Tailscale download didn't match its checksum."; return $false }
        }
        $r = Install-Apk $apk
        Remove-Item -LiteralPath $apk -Force -ErrorAction SilentlyContinue
        if ($r -notmatch 'Success') { Write-Fail "Tailscale didn't install: $r"; return $false }
        Add-LedgerItem 'installed' $ts
        Write-Ok "Installed $($name -replace '\.apk$', '')."
        Add-Note "Installed $($name -replace '\.apk$', '')."
    } else {
        Write-Ok 'Tailscale was already installed.'
    }
    Add-LedgerValue 'vpn_before' (Get-TvSetting 'secure always_on_vpn_app')
    [void](Invoke-TvChange "appops set $ts ACTIVATE_VPN allow; settings put secure always_on_vpn_app $ts; dumpsys deviceidle whitelist +$ts")
    Write-Ok 'Tailscale is the always-on VPN. Open Tailscale once on the TV to sign in.'
    Add-Note 'Tailscale is the always-on VPN (open it once on the TV to sign in).'
    return $true
}

function Invoke-Setup {
    if (-not (Confirm-M3Home)) { Stop-Setup "MaterialTV isn't installed, so setup can't continue." }
    Get-Lists
    Read-Ledger
    if ($M3.LedgerFound) { Write-Info 'Found the change log from an earlier setup; it will be kept and added to.' }

    $stayOn = Get-TvSetting 'global stay_on_while_plugged_in'
    Write-Host ''
    Write-Step 'Choose what to set up'
    $perm = Confirm-Choice '  MaterialTV permissions (Home button, screensaver, now playing, updates)?' $true
    $debloat = Confirm-Choice '  Debloat and privacy (switch off Amazon ads and tracking, dumbtv lists)?' $true
    $keepAlexa = $true
    if ($debloat) { $keepAlexa = Confirm-Choice '  Keep Alexa and the voice remote?' $true }
    $smartTube = Confirm-Choice '  Install SmartTube instead of the Amazon YouTube app?' $false
    $tailscale = Confirm-Choice '  Install Tailscale and make it the always-on VPN?' $false
    $stay = $false
    if ($stayOn -ne '' -and $stayOn -ne '0') {
        Write-Info 'The TV is set to stay awake while plugged in, which stops any screensaver.'
        $stay = Confirm-Choice '  Turn that off?' $true
    }
    if (-not ($perm -or $debloat -or $smartTube -or $tailscale -or $stay)) {
        Write-Host ''; Write-Info 'Nothing chosen, nothing changed.'; return 0
    }
    if (-not $M3.Yes) {
        Write-Host ''
        if (-not (Confirm-Choice 'Start now?' $true)) { Write-Info 'Cancelled. Nothing changed.'; return 0 }
    }
    Write-Host ''

    $ok = $true
    try {
        if ($perm) { Invoke-Permissions }
        if ($stay) { Invoke-StayOn $stayOn }
        if ($debloat) { Invoke-Debloat $keepAlexa }
        if ($ok -and $smartTube) { $ok = Invoke-SmartTube }
        if ($ok -and $tailscale) { $ok = Invoke-Tailscale }
    } catch {
        Write-Fail $_.Exception.Message
        $ok = $false
    } finally {
        if (-not (Save-Ledger)) { $ok = $false }
    }
    Write-Host ''
    if ($ok) { Write-Step 'Setup finished.' } else { Write-Fail 'Setup stopped early. What ran so far is in the change log.' }
    Write-Summary
    if ($ok) { Write-Host 'To put everything back later: run this script with -Undo (or use Settings > Fire TV setup > Undo in MaterialTV).' }
    if ($M3.DryRun) { Write-Host '(Dry run: nothing on the TV was changed.)' -ForegroundColor Yellow }
    return $(if ($ok) { 0 } else { 1 })
}

function Write-Summary {
    if ($M3.Summary.Count -eq 0) { return }
    Write-Host 'Summary:'
    foreach ($s in $M3.Summary) { Write-Host "  - $s" }
}

# ---- undo (Setup.undo) ----------------------------------------------------------------------

function Invoke-Undo {
    Get-Lists
    Read-Ledger
    $logged = $M3.LedgerFound
    if ($logged) { Write-Info 'Found the change log from setup; undo will put back exactly what it changed.' }
    else { Write-Info "No change log on the TV, so undo uses dumbtv's restore lists." }

    Write-Host ''
    Write-Step 'Choose what to undo'
    $uDebloat = Confirm-Choice '  Undo debloat and privacy (apps back on, settings restored)?' $true
    $uSmart = Confirm-Choice "  Remove SmartTube (if setup installed it) and bring back Amazon's YouTube app?" $true
    $uTail = Confirm-Choice '  Remove Tailscale (if setup installed it) and clear the always-on VPN?' $true
    $uSelf = Confirm-Choice "  Uninstall MaterialTV too (Amazon's home screen comes back)?" $false
    if (-not ($uDebloat -or $uSmart -or $uTail -or $uSelf)) { Write-Host ''; Write-Info 'Nothing chosen, nothing changed.'; return 0 }
    if (-not $M3.Yes) {
        Write-Host ''
        if (-not (Confirm-Choice 'Undo these now?' $true)) { Write-Info 'Cancelled. Nothing changed.'; return 0 }
    }
    Write-Host ''

    $installedByUs = Get-LedgerArray 'installed'
    if ($uDebloat) {
        Write-Step 'Undoing debloat and privacy...'
        $disabled = Get-Packages 'pm list packages -d'
        $toEnable = New-Object System.Collections.Generic.List[string]
        foreach ($p in (Get-LedgerArray 'disabled')) { $toEnable.Add($p) }
        if (-not $logged) {
            $toEnable.AddRange((Get-List 'packages.txt'))
            $toEnable.AddRange((Get-List 'alexa.txt'))
        }
        $n = 0
        foreach ($p in $toEnable) {
            if ($p -eq $M3.YouTube -and -not $uSmart) { continue }
            if ($disabled.Contains($p)) { [void](Invoke-TvChange "pm enable --user 0 $p"); $n++ }
        }
        Write-Ok "Switched $n apps back on."
        foreach ($p in (Get-List 'protected.txt')) {
            [void](Invoke-TvChange "appops set $p RUN_IN_BACKGROUND allow; appops set $p RUN_ANY_IN_BACKGROUND allow")
            $uid = Get-Uid $p
            if ($uid) { [void](Invoke-TvChange "cmd netpolicy remove restrict-background-blacklist $uid") }
        }
        $ds = if ((Get-LedgerString 'data_saver_before') -eq 'true') { 'true' } else { 'false' }
        [void](Invoke-TvChange "cmd netpolicy set restrict-background $ds")
        $s = 0
        $before = Get-LedgerObject 'settings_before'
        if ($before.Count -gt 0) {
            foreach ($k in @($before.Keys)) { Restore-Setting ([string]$k) ([string]$before[$k]); $s++ }
        } else {
            foreach ($line in (Get-List 'settings.restore.txt')) {
                $f = $line -split '\s+'
                if ($f.Count -ge 3) { [void](Invoke-TvChange "settings put $($f[0]) $($f[1]) $($f[2])"); $s++ }
            }
        }
        Write-Ok "Restored $s settings and background limits."
        Add-Note "Undid debloat: $n apps back on, $s settings restored."
    }
    if (($uDebloat -or $uSelf) -and $M3.Ledger.Contains('stay_on_before')) {
        $stay = Get-LedgerString 'stay_on_before'
        Restore-Setting 'global stay_on_while_plugged_in' $stay
        Write-Ok ("Put ""stay awake while plugged in"" back to {0}." -f $(if ($stay) { $stay } else { 'the default' }))
    }
    if ($uSmart) {
        $all = Get-Packages 'pm list packages'
        foreach ($p in $M3.SmartTube) {
            if ($all.Contains($p) -and (-not $logged -or $installedByUs.Contains($p))) {
                [void](Invoke-TvChange "pm uninstall $p")
                Write-Step 'Removed SmartTube.'
                Add-Note 'Removed SmartTube.'
            }
        }
        [void](Invoke-TvChange "pm enable --user 0 $($M3.YouTube)")
    }
    if ($uTail) {
        Restore-Setting 'secure always_on_vpn_app' (Get-LedgerString 'vpn_before')
        if ((Get-Packages 'pm list packages').Contains($M3.Tailscale) -and (-not $logged -or $installedByUs.Contains($M3.Tailscale))) {
            Write-Step "Removed Tailscale's always-on VPN; uninstalling Tailscale..."
            [void](Invoke-TvChange "pm uninstall $($M3.Tailscale)")
            Add-Note 'Removed Tailscale.'
        } else {
            Write-Step 'Tailscale is no longer the always-on VPN.'
            Add-Note 'Tailscale is no longer the always-on VPN.'
        }
    }
    if ($uSelf) {
        Write-Step 'Removing MaterialTV and its permissions...'
        $ss = Get-LedgerString 'screensaver_before'
        if ($ss -eq '' -or $ss.Contains($M3.Self)) { $ss = Get-TvSetting 'secure screensaver_default_component' }
        Restore-Setting 'secure screensaver_components' $ss
        $a11y = Get-TvSetting 'secure enabled_accessibility_services'
        $g = $M3.Guard
        $rest = $a11y.Replace(":$g", '').Replace("$($g):", '').Replace($g, '')
        Restore-Setting 'secure enabled_accessibility_services' $rest
        if ($rest -eq '') { [void](Invoke-TvChange 'settings put secure accessibility_enabled 0') }
        [void](Invoke-TvChange "cmd notification disallow_listener $($M3.Listener); dumpsys deviceidle whitelist -$($M3.Self)")
        [void](Invoke-TvChange "rm -f $($M3.LedgerPath)")
        Write-Step "Uninstalling MaterialTV. Amazon's home screen comes back. Goodbye!"
        [void](Invoke-TvChange "pm uninstall $($M3.Self)")
        Add-Note 'Uninstalled MaterialTV (its change log went with it).'
    } else {
        [void](Invoke-TvChange "rm -f $($M3.LedgerPath)")
        Write-Step 'Undo finished.'
        if ($logged) { Add-Note 'Removed the change log from the TV.' }
    }
    Write-Summary
    if ($M3.DryRun) { Write-Host '(Dry run: nothing on the TV was changed.)' -ForegroundColor Yellow }
    return 0
}

# ---- main ---------------------------------------------------------------------------------

function Invoke-Main {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'   # the progress bar makes Invoke-WebRequest very slow on 5.1
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    } catch { }

    $tvArg = $Tv; if (-not $tvArg) { $tvArg = $env:M3_TV }
    $isUndo = $Undo.IsPresent -or (Test-Truthy $env:M3_UNDO)
    $M3.Yes = $Yes.IsPresent -or (Test-Truthy $env:M3_YES)
    $M3.DryRun = $DryRun.IsPresent -or (Test-Truthy $env:M3_DRY_RUN)
    $M3.Summary.Clear()

    Write-Host ("MaterialTV - Fire TV {0}" -f $(if ($isUndo) { 'undo' } else { 'setup' })) -ForegroundColor White
    if ($M3.DryRun) { Write-Host 'Dry run: commands that would change the TV are printed, not run.' -ForegroundColor Yellow }

    $M3.Work = Join-Path ([IO.Path]::GetTempPath()) ('m3home-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $M3.Work | Out-Null
    try {
        if (-not $tvArg) {
            Write-Host "Enter your Fire TV's IP address."
            Write-Host '  Find it on the TV in Settings > My Fire TV > About > Network,'
            Write-Host "  or on MaterialTV's setup screen. Example: 192.168.1.50"
            $tvArg = Read-Answer 'Fire TV IP'
        }
        $tvArg = ($tvArg -replace '\s', '')
        if (-not $tvArg) { Stop-Setup 'No IP address given.' }
        $M3.Dev = if ($tvArg.Contains(':')) { $tvArg } else { "$($tvArg):5555" }

        Find-Adb
        Connect-Tv
        Test-Device
        Write-Host ''
        if ($isUndo) { return (Invoke-Undo) } else { return (Invoke-Setup) }
    } catch {
        $msg = $_.Exception.Message
        if ($msg.StartsWith('M3STOP:')) { Write-Fail $msg.Substring(7) }
        else { Write-Fail "$msg"; Write-Host "  at $($_.InvocationInfo.PositionMessage)" -ForegroundColor DarkGray }
        return 1
    } finally {
        Remove-Item -LiteralPath $M3.Work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$code = Invoke-Main
$code = [int](@($code) | Select-Object -Last 1)
if ($M3.FromFile) { exit $code }
$global:LASTEXITCODE = $code
