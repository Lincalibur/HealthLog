<#
Genius F1 helper - PowerShell version (Windows PowerShell 5.1, or `pwsh` on Linux).
On Linux, run.sh is the simpler option; this exists if you prefer PowerShell.

  ./run.ps1                  interactive menu
  ./run.ps1 setup
  ./run.ps1 report [ADDR] [--quick]
  ./run.ps1 scan | find | info | hr | sync | listen [ADDR]
  ./run.ps1 raw ADDR HEX
#>
param(
    [Parameter(Position = 0)][string]$Action = "",
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest = @()
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$OnLinux = ($PSVersionTable.PSEdition -eq "Core") -and $IsLinux
if ($OnLinux) {
    $Venv = ".venv-linux"; $Py = Join-Path $Venv "bin/python"; $SysPy = "python3"
} else {
    $Venv = ".venv"; $Py = Join-Path $Venv "Scripts\python.exe"; $SysPy = "python"
}

function Test-System {
    Write-Host "== Checking system"
    if (-not (Get-Command $SysPy -ErrorAction SilentlyContinue)) {
        Write-Host "  [FAIL] $SysPy not found"; return $false
    }
    Write-Host "  [ OK ] $(& $SysPy --version 2>&1)"
    if ($OnLinux) {
        # Reuse the bash checks so both launchers report the same thing.
        & bash ./run.sh check
        return ($LASTEXITCODE -eq 0)
    }
    $bt = Get-PnpDevice -Class Bluetooth -ErrorAction SilentlyContinue | Where-Object Status -eq "OK"
    if ($bt) { Write-Host "  [ OK ] Bluetooth adapter present" }
    else { Write-Host "  [FAIL] no working Bluetooth adapter (Device Manager > Bluetooth)"; return $false }
    return $true
}

function Invoke-Setup {
    if (-not (Test-System)) { Write-Host "  [WARN] fix the items above (continuing with Python setup)" }
    Write-Host "== Python environment ($Venv)"
    if (-not (Test-Path $Py)) {
        & $SysPy -m venv $Venv
        if ($LASTEXITCODE -ne 0) { throw "venv creation failed (Linux: sudo apt install python3-venv)" }
    }
    & $Py -m pip install -q --upgrade pip
    & $Py -m pip install -q -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw "pip install failed" }
    Write-Host "Setup done. Next: ./run.ps1 report"
}

function Confirm-Ready {
    $ready = $false
    if (Test-Path $Py) { & $Py -c "import bleak" 2>$null; $ready = ($LASTEXITCODE -eq 0) }
    if (-not $ready) { Write-Host "First run - setting up."; Invoke-Setup }
}

if ($Action -eq "") {
    Write-Host "Genius F1 tools"
    Write-Host "  1) setup   2) report   3) report --quick   4) scan   5) find"
    Write-Host "  6) info    7) hr       8) sync             9) listen q) quit"
    $c = Read-Host "Choice"
    $map = @{ "1" = @("setup"); "2" = @("report"); "3" = @("report", "--quick"); "4" = @("scan");
              "5" = @("find"); "6" = @("info"); "7" = @("hr"); "8" = @("sync"); "9" = @("listen") }
    if (-not $map.ContainsKey($c)) { exit 0 }
    $Action = $map[$c][0]; $Rest = @($map[$c] | Select-Object -Skip 1)
}

switch ($Action) {
    "setup"  { Invoke-Setup }
    "report" { Confirm-Ready; & $Py f1_report.py @Rest }
    { $_ -in "scan", "find", "info", "hr", "spo2", "bp", "steps", "notify", "sync", "listen", "raw", "reset" } {
        Confirm-Ready; & $Py genius_f1.py $Action @Rest
    }
    default  { Write-Host "Unknown action '$Action'. Use: setup, report, scan, find, info, hr, spo2, bp, steps, notify, sync, listen, raw, reset" }
}
exit $LASTEXITCODE
