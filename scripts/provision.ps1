<#
.SYNOPSIS
  Pushes the endpoint URL and ingest token into the HC Sync app over USB, so you never type the token on the phone.

.EXAMPLE
  .\scripts\provision.ps1 -Url https://health.example.com/ingest -TokenFile C:\path\to\ingest_token.txt

.NOTES
  Needs adb and a debug build of the app (run-as only works for debuggable apps). The token is read from a file,
  sent through adb's stdin, and never printed. The app imports the file once and deletes it.
#>
param(
    [Parameter(Mandatory = $true)][string]$Url,
    [Parameter(Mandatory = $true)][string]$TokenFile,
    [int]$DaysBack = 30,
    [string]$Package = "dev.hcsync",
    [string]$Adb = ""
)

$ErrorActionPreference = "Stop"
if (-not $Adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) {
        $Adb = $cmd.Source
    } else {
        $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk")) | Where-Object { $_ }
        $Adb = ($roots | ForEach-Object { Join-Path $_ "platform-tools\adb.exe" } | Where-Object { Test-Path $_ } | Select-Object -First 1)
    }
}
if (-not $Adb -or -not (Test-Path $Adb)) { throw "adb not found. Install Android platform-tools, set ANDROID_HOME, or pass -Adb <path to adb.exe>." }
if ($Url -notmatch '^https://') { throw "Url must start with https://" }

$token = (Get-Content -Raw -Path $TokenFile).Trim()
if ($token.Length -lt 16) { throw "Token file looks empty or too short." }

$json = @{ url = $Url; token = $token; days_back = $DaysBack } | ConvertTo-Json -Compress

# Stop the app so it re-reads the file on next launch, then write it into the app's private storage.
& $Adb shell am force-stop $Package | Out-Null
$json | & $Adb shell "run-as $Package sh -c 'mkdir -p files && cat > files/config.json'"
if ($LASTEXITCODE -ne 0) { throw "adb run-as failed. Is the phone connected and the debug build installed?" }

$present = (& $Adb shell "run-as $Package ls files") -join " "
if ($present -notmatch "config.json") { throw "Provisioning file was not written." }
Remove-Variable token, json
Write-Host "Done. Open HC Sync on the phone; it imports the settings on launch (token shows only its last 4 characters)."
