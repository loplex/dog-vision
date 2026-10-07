# Installs the MSI, upgrades it to a later one and removes it, checking each step:
# LICENSE, THIRD-PARTY-LICENSES.txt and the runtime's legal folder,
# the windows' launchers with their shortcuts in the Start menu and on the desktop,
# the command line's with none but the installation folder on the system's PATH,
# and the web page with a shortcut in the Start menu to its index.html,
# without its script's source map, which is not installed by default.
# The MSI also removes the folder ffmpeg is downloaded into, %ProgramData%\<the app's name>,
# which the script makes before the upgrade, as a window would, and which has to stay over that.
#
# Then it installs one part alone, the Feature SwingGui, by ADDLOCAL naming it without its parent DogVision,
# which Windows Installer installs with it, into a folder of its own (INSTALLDIR);
# adds the Compose window, which is to go into that folder too;
# upgrades it, which is to keep both windows there and the command line and the web page out,
# as the MSI migrates the Features installed and takes the folder from the registry; and removes it.
#
# The MSIs are tools/package_msi_on_windows.ps1's or tools/package_msi_on_linux.sh's: -Msi of -Version,
# and -UpgradeMsi of the later -UpgradeVersion.
# Each check says whether it held, and every check runs, so that one failing does not hide the others;
# the script fails if any did.
# msiexec's logs go to -LogDir.
#
# It installs for every user and changes the machine,
# so it is for one that is thrown away after, such as a CI runner, run as an administrator in PowerShell 7 (pwsh).
# WIX names WiX Toolset 3, whose smoke.exe validates the MSIs.
param(
    [Parameter(Mandatory)] [string]$Msi,
    [Parameter(Mandatory)] [string]$Version,
    [Parameter(Mandatory)] [string]$UpgradeMsi,
    [Parameter(Mandatory)] [string]$UpgradeVersion,
    [Parameter(Mandatory)] [string]$LogDir
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

# The product's name, as packaging/windows/dog-vision.wxs gives it: the app's English name,
# which the list of installed programs and the Start menu's group show, and which begins each shortcut's name.
$root = Split-Path -Parent $PSScriptRoot
$strings = Get-Content (Join-Path $root "texts\strings\values\strings.xml") -Raw
if ($strings -notmatch '<string name="app_name">([^<]+)</string>') {
    throw "texts\strings\values\strings.xml has no app_name"
}
$product = $Matches[1]

$installDir = Join-Path $env:ProgramFiles "dog-vision"
$startMenu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\$product"
$desktop = Join-Path $env:PUBLIC "Desktop"
# The windows' shortcuts, each in the Start menu's group and on the desktop,
# and the web page's, in the Start menu's group alone.
$composeShortcut = "$product (Kotlin Compose).lnk"
$swingShortcut = "$product (Java Swing).lnk"
$shortcuts = @($composeShortcut, $swingShortcut)
$webShortcut = "$product (web).lnk"
# Each part's launcher, or the web page's index.html.
$launchers = @("dog-vision-compose.exe", "dog-vision-swing.exe", "dog-vision-cli.exe", "web\index.html")
# Where a window downloads ffmpeg, which the MSI removes with the product, but not over an upgrade.
$data = Join-Path $env:ProgramData $product

# msiexec with [Arguments], waited for; 3010 is success that asks for a restart.
function Invoke-Msiexec([string]$Log, [string[]]$Arguments) {
    $all = $Arguments + @("/qn", "/l*v", "`"$(Join-Path $LogDir $Log)`"")
    $process = Start-Process "msiexec.exe" -ArgumentList $all -Wait -PassThru
    return $process.ExitCode -in @(0, 3010)
}

# The products of the product's name in the list of installed programs, as Windows Installer registers them.
function Get-Installed {
    @(
        "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*"
    ) | ForEach-Object { Get-ItemProperty $_ -ErrorAction SilentlyContinue } |
        Where-Object { $_.PSObject.Properties["DisplayName"] -and $_.DisplayName -eq $product }
}

# The MSI against Windows Installer's rules (ICE).
function Test-Ice([string]$Package) {
    & (Join-Path $env:WIX "bin\smoke.exe") -nologo $Package
    Test-Check "$(Split-Path -Leaf $Package) passes ICE validation" ($LASTEXITCODE -eq 0)
}

# How often the installation folder is on the system's PATH, as the registry holds it for the processes started after.
function Get-OnPath {
    $entries = [Environment]::GetEnvironmentVariable("Path", "Machine") -split ";"
    @($entries | Where-Object { $_.TrimEnd("\") -eq $installDir }).Count
}

# The installed product: one, of [Expected] version, with the runtime and the launchers [Parts] alone,
# and its folder on the PATH once where the command line is among them, else not at all.
function Test-Installed([string]$Expected, [string[]]$Parts = $launchers) {
    $installed = @(Get-Installed)
    Test-Check "one $product is installed" ($installed.Count -eq 1)
    Test-Check "the installed version is $Expected" (@($installed | Where-Object DisplayVersion -eq $Expected).Count -eq 1)
    Test-Check "the runtime is installed" (Test-Path (Join-Path $installDir "runtime\lib\modules"))
    foreach ($file in "LICENSE", "THIRD-PARTY-LICENSES.txt", "runtime\legal\java.base") {
        Test-Check "$file is installed" (Test-Path (Join-Path $installDir $file))
    }
    foreach ($launcher in $launchers) {
        $present = Test-Path (Join-Path $installDir $launcher)
        if ($launcher -in $Parts) {
            Test-Check "$launcher is installed" $present
        } else {
            Test-Check "$launcher is not installed" (-not $present)
        }
    }
    $onPath = if ("dog-vision-cli.exe" -in $Parts) { 1 } else { 0 }
    Test-Check "the installation folder is on the PATH $onPath time(s)" ((Get-OnPath) -eq $onPath)
}

# Nothing of the product is left: not listed, its folders and shortcuts gone, the PATH as it was.
function Test-Removed {
    Test-Check "no $product is installed after" (@(Get-Installed).Count -eq 0)
    Test-Check "the installation folder is gone" (-not (Test-Path $installDir))
    Test-Check "the Start menu folder is gone" (-not (Test-Path $startMenu))
    foreach ($shortcut in $shortcuts) {
        Test-Check "the desktop's $shortcut is gone" (-not (Test-Path (Join-Path $desktop $shortcut)))
    }
    Test-Check "the folder ffmpeg is downloaded into is gone" (-not (Test-Path $data))
    Test-Check "the installation folder is not on the PATH" ((Get-OnPath) -eq 0)
    Test-Check "the PATH is as it was before, separators included" (
        [Environment]::GetEnvironmentVariable("Path", "Machine") -eq $pathBefore
    )
}


# The first version.

# The system's PATH as it was before, which removing the product is to leave as it was.
$pathBefore = [Environment]::GetEnvironmentVariable("Path", "Machine")
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

# Each window has a shortcut in the Start menu and on the desktop;
# the command line, which started from one would only say that the window is the desktop app's, has none.
Write-Host "Start menu: $(@(Get-ChildItem $startMenu -ErrorAction SilentlyContinue).Name -join ', ')"
Write-Host "Desktop: $(@(Get-ChildItem $desktop -Filter "$product*" -ErrorAction SilentlyContinue).Name -join ', ')"
foreach ($shortcut in $shortcuts) {
    Test-Check "the Start menu has $shortcut" (Test-Path (Join-Path $startMenu $shortcut))
    Test-Check "the desktop has $shortcut" (Test-Path (Join-Path $desktop $shortcut))
}
Test-Check "the Start menu has $webShortcut, to the installed index.html" (
    (Test-Path (Join-Path $startMenu $webShortcut)) -and
        (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $startMenu $webShortcut)).TargetPath -eq
        (Join-Path $installDir "web\index.html")
)
Test-Check "the desktop has no $webShortcut" (-not (Test-Path (Join-Path $desktop $webShortcut)))
Test-Check "the web page's source map is not installed by default" (
    -not (Test-Path (Join-Path $installDir "web\dog-vision.js.map"))
)
Test-Check "the Start menu's group holds the windows' shortcuts and the web page's alone" (
    @(Get-ChildItem $startMenu -ErrorAction SilentlyContinue).Count -eq $shortcuts.Count + 1
)

# dog-vision-cli runs by its name alone, from the PATH a process started after the installation has,
# from the registry, as this script's own is the one it started with.
$env:PATH = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
    [Environment]::GetEnvironmentVariable("Path", "User")
$found = Get-Command "dog-vision-cli" -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
Test-Check "dog-vision-cli on the PATH is the installed one" (
    $found -and $found.Source -eq (Join-Path $installDir "dog-vision-cli.exe")
)
if ($found) {
    dog-vision-cli --help | Out-Null
}
Test-Check "dog-vision-cli runs from the PATH" ($found -and $LASTEXITCODE -eq 0)


# The later version, over it, with a file in the folder a window downloads ffmpeg into, as if it had.

New-Item -ItemType Directory -Force -Path (Join-Path $data "ffmpeg\bin") | Out-Null
Set-Content (Join-Path $data "ffmpeg\bin\ffmpeg.exe") "downloaded"
Test-Ice $UpgradeMsi
Test-Check "the MSI of $UpgradeVersion installs over $Version" (
    Invoke-Msiexec "install-$UpgradeVersion.log" @("/i", "`"$UpgradeMsi`"")
)
Test-Installed $UpgradeVersion
Test-Check "the folder ffmpeg is downloaded into stays over the upgrade" (
    Test-Path (Join-Path $data "ffmpeg\bin\ffmpeg.exe")
)


# Removing it.

Test-Check "the MSI of $UpgradeVersion uninstalls" (
    Invoke-Msiexec "uninstall-$UpgradeVersion.log" @("/x", "`"$UpgradeMsi`"")
)
Test-Removed


# The Swing window alone, named without its parent, into a folder of its own;
# the Compose window added later, into the same folder; both kept there over the upgrade, without the command line.
# INSTALLDIR is given without its trailing backslash, which would escape the quote after it.

$defaultDir = $installDir
$installDir = Join-Path $env:SystemDrive "DogVisionTest"
$windows = @("dog-vision-compose.exe", "dog-vision-swing.exe")
Test-Check "the MSI of $Version installs ADDLOCAL=SwingGui into $installDir" (
    Invoke-Msiexec "install-swing-$Version.log" @(
        "/i", "`"$Msi`"", "ADDLOCAL=SwingGui", "INSTALLDIR=`"$installDir`""
    )
)
Test-Installed $Version @("dog-vision-swing.exe")
foreach ($place in @($startMenu, $desktop)) {
    Test-Check "$place has $swingShortcut alone of the windows' shortcuts" (
        (Test-Path (Join-Path $place $swingShortcut)) -and
            -not (Test-Path (Join-Path $place $composeShortcut))
    )
}
Test-Check "the MSI of $Version adds ADDLOCAL=ComposeGui" (
    Invoke-Msiexec "add-compose-$Version.log" @("/i", "`"$Msi`"", "ADDLOCAL=ComposeGui")
)
Test-Installed $Version $windows
Test-Check "nothing is installed into $defaultDir" (-not (Test-Path $defaultDir))
Test-Check "the MSI of $UpgradeVersion installs over the two windows" (
    Invoke-Msiexec "install-windows-$UpgradeVersion.log" @("/i", "`"$UpgradeMsi`"")
)
Test-Installed $UpgradeVersion $windows
Test-Check "nothing is installed into $defaultDir after the upgrade" (-not (Test-Path $defaultDir))
Test-Check "the MSI of $UpgradeVersion uninstalls the two windows" (
    Invoke-Msiexec "uninstall-windows-$UpgradeVersion.log" @("/x", "`"$UpgradeMsi`"")
)
Test-Removed
$installDir = $defaultDir

if ($failures.Count -gt 0) {
    Write-Host "$($failures.Count) check(s) failed:"
    $failures | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host "Every check held."
