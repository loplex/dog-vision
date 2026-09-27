# Installs a desktop window's MSI, upgrades it to a later one and removes it, checking each step.
#
# The MSIs are tools/package_msi_on_windows.ps1's: -Msi of -Version, and -UpgradeMsi of the later
# -UpgradeVersion, of the product -Name, dog-vision, the default, or dog-vision-swing. Each check
# says whether it held, and every check runs, so that one failing does not hide the others; the
# script fails if any did. msiexec's logs go to -LogDir.
#
# -BuiltUnderWine takes MSIs of tools/package_msi_on_linux.sh instead, whose JDK 17's jpackage
# gives dog-vision-cli shortcuts too: the checks then hold them there.
#
# It installs for every user and changes the machine, so it is for one that is thrown away after,
# such as a CI runner, run as an administrator in PowerShell 7 (pwsh). WIX names WiX Toolset 3,
# whose smoke.exe validates the MSIs.
param(
    [Parameter(Mandatory)] [string]$Msi,
    [Parameter(Mandatory)] [string]$Version,
    [Parameter(Mandatory)] [string]$UpgradeMsi,
    [Parameter(Mandatory)] [string]$UpgradeVersion,
    [Parameter(Mandatory)] [string]$LogDir,
    [ValidateSet("dog-vision", "dog-vision-swing")] [string]$Name = "dog-vision",
    [switch]$BuiltUnderWine
)

$ErrorActionPreference = "Stop"

# msiexec runs apart from this script, so it is given whole paths.
$Msi = (Resolve-Path $Msi).Path
$UpgradeMsi = (Resolve-Path $UpgradeMsi).Path
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$LogDir = (Resolve-Path $LogDir).Path

$failures = [System.Collections.Generic.List[string]]::new()

function Test-Check([string]$What, [bool]$Holds) {
    if ($Holds) {
        Write-Host "ok: $What"
    } else {
        Write-Host "FAILED: $What"
        $failures.Add($What)
    }
}

$installDir = Join-Path $env:ProgramFiles $Name
$startMenu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\$Name"
$desktop = Join-Path $env:PUBLIC "Desktop"

# msiexec with [Arguments], waited for; 3010 is success that asks for a restart.
function Invoke-Msiexec([string]$Log, [string[]]$Arguments) {
    $all = $Arguments + @("/qn", "/l*v", "`"$(Join-Path $LogDir $Log)`"")
    $process = Start-Process "msiexec.exe" -ArgumentList $all -Wait -PassThru
    return $process.ExitCode -in @(0, 3010)
}

# The products of the name in the list of installed programs, as Windows Installer registers them.
function Get-Installed {
    @(
        "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    ) | ForEach-Object { Get-ItemProperty $_ -ErrorAction SilentlyContinue } |
        Where-Object { $_.PSObject.Properties["DisplayName"] -and $_.DisplayName -eq $Name }
}

# The MSI against Windows Installer's rules (ICE), less ICE27, which jpackage's own run of light.exe
# leaves out too.
function Test-Ice([string]$Package) {
    & (Join-Path $env:WIX "bin\smoke.exe") -nologo -sice:ICE27 $Package
    Test-Check "$(Split-Path -Leaf $Package) passes ICE validation" ($LASTEXITCODE -eq 0)
}

# The installed product: one, of [Expected] version, with both launchers.
function Test-Installed([string]$Expected) {
    $installed = @(Get-Installed)
    Test-Check "one $Name is installed" ($installed.Count -eq 1)
    Test-Check "the installed version is $Expected" (@($installed | Where-Object DisplayVersion -eq $Expected).Count -eq 1)
    foreach ($launcher in @("$Name.exe", "dog-vision-cli.exe")) {
        Test-Check "$launcher is installed" (Test-Path (Join-Path $installDir $launcher))
    }
}


# The first version.

Test-Ice $Msi
Test-Check "the MSI of $Version installs" (Invoke-Msiexec "install-$Version.log" @("/i", "`"$Msi`""))
Test-Installed $Version

# The command line converts a photo, red on the left and blue on the right, with the runtime in the MSI.
$work = Join-Path ([System.IO.Path]::GetTempPath()) "dog-vision-msi-test"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
$converted = New-Item -ItemType Directory -Path (Join-Path $work "converted")
$photo = Join-Path $work "photo.png"
$png = "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAECAIAAAA8r+mnAAAAFElEQVR4nGP4z8AAR0jM/wzUkwAAOn4f4QhCQp4AAAAASUVORK5CYII="
[System.IO.File]::WriteAllBytes($photo, [System.Convert]::FromBase64String($png))
& (Join-Path $installDir "dog-vision-cli.exe") --species cat --output-dir $converted.FullName $photo
Test-Check "dog-vision-cli.exe converts a photo" (
    $LASTEXITCODE -eq 0 -and @(Get-ChildItem $converted.FullName -Filter "*.png").Count -eq 1
)

# The window's launcher has a shortcut in the Start menu and on the desktop; the command line alone,
# which only prints its usage when started from one, has none, but in an MSI built under Wine.
Write-Host "Start menu: $(@(Get-ChildItem $startMenu -ErrorAction SilentlyContinue).Name -join ', ')"
Write-Host "Desktop: $(@(Get-ChildItem $desktop -Filter 'dog-vision*' -ErrorAction SilentlyContinue).Name -join ', ')"
Test-Check "$Name has a Start menu shortcut" (Test-Path (Join-Path $startMenu "$Name.lnk"))
Test-Check "$Name has a desktop shortcut" (Test-Path (Join-Path $desktop "$Name.lnk"))
$cliShortcuts = if ($BuiltUnderWine) { "has" } else { "has no" }
Test-Check "dog-vision-cli $cliShortcuts Start menu shortcut" (
    (Test-Path (Join-Path $startMenu "dog-vision-cli.lnk")) -eq [bool]$BuiltUnderWine
)
Test-Check "dog-vision-cli $cliShortcuts desktop shortcut" (
    (Test-Path (Join-Path $desktop "dog-vision-cli.lnk")) -eq [bool]$BuiltUnderWine
)


# The later version, over it.

Test-Ice $UpgradeMsi
Test-Check "the MSI of $UpgradeVersion installs over $Version" (
    Invoke-Msiexec "install-$UpgradeVersion.log" @("/i", "`"$UpgradeMsi`"")
)
Test-Installed $UpgradeVersion


# Removing it.

Test-Check "the MSI of $UpgradeVersion uninstalls" (
    Invoke-Msiexec "uninstall-$UpgradeVersion.log" @("/x", "`"$UpgradeMsi`"")
)
Test-Check "no $Name is installed after" (@(Get-Installed).Count -eq 0)
Test-Check "the installation folder is gone" (-not (Test-Path $installDir))
Test-Check "the Start menu folder is gone" (-not (Test-Path $startMenu))


if ($failures.Count -gt 0) {
    Write-Host "$($failures.Count) check(s) failed:"
    $failures | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host "Every check held."
