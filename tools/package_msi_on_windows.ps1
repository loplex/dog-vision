# Builds the MSI for Windows on x86-64 on Windows,
# into tools\build\msi, which git ignores, as tools\build\msi\dog-vision-0.1.0.msi:
# the same MSI as tools/package_msi_on_linux.sh builds through Wine,
# but with the jpackage of the JDK this runs on, and with light.exe's validation (ICE), which fails under Wine.
#
# jpackage makes the app image, into tools\build\app-image\dog-vision-compose,
# of the arguments that :packaging's windowsJpackage task writes: the Compose window's dog-vision-compose.exe,
# the Swing window's dog-vision-swing.exe and the command line's dog-vision-cli.exe,
# with their JARs and the runtime that its windowsRuntime task links.
# jpackage puts every JAR on each launcher's classpath,
# so this then copies the launchers' .cfg that the windowsLauncherConfigs task writes, each with its own classpath.
# WiX Toolset 3's candle.exe and light.exe make the MSI of what the windowsWix task writes of the image:
# packaging\windows\dog-vision.wxs, which says what the MSI does, and the image's files.
#
# Needs:
# - PowerShell 7 (pwsh), which reads this file as UTF-8.
# - A JDK 25 as JAVA_HOME, for its jpackage.
# - WiX Toolset 3.14's candle.exe and light.exe on the PATH, or its installation's WIX variable.
#
# -AppVersion gives the MSI another version than gradle.properties' appVersion,
# as a test of an upgrade needs a later one; the application in it stays the same.
param(
    [string]$AppVersion
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$packaging = Join-Path $root "packaging"

# Runs a program and stops the script where it fails, as $ErrorActionPreference does not for them.
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with $LASTEXITCODE"
    }
}

if (-not (Get-Command "light.exe" -ErrorAction SilentlyContinue)) {
    if (-not $env:WIX) {
        throw "WiX Toolset 3 is neither on the PATH nor named by WIX"
    }
    $env:PATH = "$(Join-Path $env:WIX "bin");$env:PATH"
}
if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME names no JDK"
}
$gradlew = Join-Path $root "gradlew.bat"


# The app image, of the arguments that windowsJpackage writes,
# which jpackage reads in its default charset, UTF-8 from JDK 18 on:
# Java reads its command line in the system's ANSI code page,
# which on an English Windows, 1252, has no ř for the vendor's name.

$properties = Get-Content (Join-Path $root "gradle.properties") -Raw
if ($properties -notmatch '(?m)^appVersion=(.+?)\r?$') {
    throw "gradle.properties has no appVersion"
}
$gradleOptions = @("--quiet")
if ($AppVersion) {
    $gradleOptions += "-PwindowsAppVersion=$AppVersion"
} else {
    $AppVersion = $Matches[1]
}
Invoke-Checked $gradlew ($gradleOptions + ":packaging:windowsJpackage")

$images = Join-Path $root "tools\build\app-image"
$image = Join-Path $images "dog-vision-compose"
# jpackage refuses an image's folder that is there already.
if (Test-Path $image) {
    Remove-Item -Recurse -Force $image
}
Invoke-Checked (Join-Path $env:JAVA_HOME "bin\jpackage.exe") @(
    "@$(Join-Path $packaging "build\windows\jpackage\arguments")",
    "--type", "app-image",
    "--dest", $images
)

# Each launcher with its own classpath, in its order;
# the licence, and what the image holds that is not this project's own, in its folder.
Invoke-Checked $gradlew ($gradleOptions + @(":packaging:windowsLauncherConfigs", "-PwindowsAppImage=$image"))
Copy-Item (Join-Path $packaging "build\windows\launchers\*.cfg") (Join-Path $image "app")
foreach ($file in "LICENSE", "THIRD-PARTY-LICENSES.txt") {
    Copy-Item (Join-Path $packaging "build\windows\launchers\$file") $image
}


# candle.exe and light.exe over what windowsWix writes.
# light.exe takes the MSI's code page from the first localization it is handed,
# so codepage.wxl comes before WiX's own English strings, which -cultures takes.

Invoke-Checked $gradlew ($gradleOptions + @(":packaging:windowsWix", "-PwindowsAppImage=$image"))
$source = Join-Path $packaging "build\windows\wix"

$staging = Join-Path $root "tools\build\staging\dog-vision-msi"
if (Test-Path $staging) {
    Remove-Item -Recurse -Force $staging
}
New-Item -ItemType Directory -Force -Path $staging | Out-Null
$output = Join-Path $root "tools\build\msi"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$msi = Join-Path $output "dog-vision-$AppVersion.msi"
if (Test-Path $msi) {
    Remove-Item -Force $msi
}

$extensions = @("-ext", "WixUtilExtension", "-ext", "WixUIExtension")
Invoke-Checked "candle.exe" (@("-nologo", "-arch", "x64") + $extensions + @(
    "-out", "$staging\",
    (Join-Path $source "dog-vision.wxs"),
    (Join-Path $source "files.wxs")
))
Invoke-Checked "light.exe" (@("-nologo", "-spdb") + $extensions + @(
    "-loc", (Join-Path $source "codepage.wxl"),
    "-cultures:en-us",
    "-out", $msi,
    (Join-Path $staging "dog-vision.wixobj"),
    (Join-Path $staging "files.wixobj")
))

$msi
