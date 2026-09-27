# Builds the desktop window's MSI for Windows on x86-64 on Windows.
#
# The same MSI as tools/package_msi_on_linux.sh builds through Wine, from the JAR that
# `:desktop:windowsUberJar` assembles, but with the JDK this runs on: its jlink links the runtime
# and its jpackage makes the MSI through WiX Toolset 3, whose light.exe validates it (ICE) here.
# It is written to desktop/build/compose/binaries/main/msi/<version>.
#
# Needs:
# - PowerShell 7 (pwsh), which reads this file as UTF-8, as the vendor's name needs.
# - A JDK 25 as JAVA_HOME, as the Linux packages' runtime is.
# - WiX Toolset 3.14's candle.exe and light.exe on the PATH, or its installation's WIX variable.
#
# -AppVersion gives the MSI another version than desktop/build.gradle.kts's packageVersion, as a
# test of an upgrade needs a later one; the application in it stays the same.
param(
    [string]$AppVersion
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$desktop = Join-Path $root "desktop"
$packaging = Join-Path $desktop "packaging"

# Every later version's MSI replaces the one installed, as Windows Installer tells versions of one
# product apart by it: never change it. package_msi_on_linux.sh gives the same.
$upgradeUuid = "602aa86b-3230-4786-8460-ba08bca42e45"

# The runtime's modules, the Linux packages' too.
$modules = "java.base,java.desktop,java.logging,jdk.crypto.ec,java.instrument,jdk.unsupported"

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
$jdkBin = Join-Path $env:JAVA_HOME "bin"


# What jpackage takes in: the JAR and a runtime.

$gradle = Get-Content (Join-Path $desktop "build.gradle.kts") -Raw
if ($gradle -notmatch 'packageVersion = "([^"]+)"') {
    throw "desktop/build.gradle.kts has no packageVersion"
}
$packageVersion = $Matches[1]
if (-not $AppVersion) {
    $AppVersion = $packageVersion
}
Invoke-Checked (Join-Path $root "gradlew.bat") @("--quiet", ":desktop:windowsUberJar")
$jar = Join-Path $desktop "build\compose\jars\dog-vision-windows-x64-$packageVersion.jar"

$staging = Join-Path $desktop "build\windows-msi\$AppVersion"
if (Test-Path $staging) {
    Remove-Item -Recurse -Force $staging
}

# jpackage takes every file in --input into the application, so the JAR goes there alone.
$inputDir = Join-Path $staging "input"
New-Item -ItemType Directory -Path $inputDir | Out-Null
Copy-Item $jar $inputDir

# The options jpackage links a runtime with itself.
$runtime = Join-Path $staging "runtime"
Invoke-Checked (Join-Path $jdkBin "jlink.exe") @(
    "--add-modules", $modules,
    "--strip-native-commands", "--strip-debug", "--no-man-pages", "--no-header-files",
    "--output", $runtime
)

$output = Join-Path $desktop "build\compose\binaries\main\msi\$AppVersion"
if (Test-Path $output) {
    Remove-Item -Recurse -Force $output
}


# jpackage, with package_msi_on_linux.sh's options.

$arguments = @(
    "--type", "msi",
    "--name", "dog-vision",
    "--app-version", $AppVersion,
    "--vendor", "Martin Lopatář",
    "--description", "How a dog or a cat sees a photo, a video or the camera",
    "--license-file", (Join-Path $root "LICENSE"),
    "--icon", (Join-Path $packaging "dog-vision.ico"),
    "--input", $inputDir,
    "--main-jar", (Split-Path -Leaf $jar),
    "--main-class", "cz.loplex.dogvision.desktop.MainKt",
    "--java-options", "-Dcompose.application.configure.swing.globals=true",
    "--runtime-image", $runtime,
    "--add-launcher", "dog-vision-cli=$(Join-Path $packaging "dog-vision-cli.properties")",
    "--resource-dir", (Join-Path $packaging "windows"),
    "--win-menu",
    "--win-menu-group", "dog-vision",
    "--win-shortcut",
    "--win-dir-chooser",
    "--win-upgrade-uuid", $upgradeUuid,
    "--temp", (Join-Path $staging "temp"),
    "--dest", $output,
    # What light.exe says where it fails, which jpackage prints only then.
    "--verbose"
)

# From a file in UTF-8, which jpackage reads as its default charset: Java reads its command line in
# the system's ANSI code page, which on an English Windows, 1252, has no ř for the vendor's name.
# Each argument in quotes, inside which a backslash escapes the next character.
$argumentFile = Join-Path $staging "jpackage-arguments"
$quoted = $arguments | ForEach-Object { '"' + ($_ -replace '\\', '\\' -replace '"', '\"') + '"' }
[System.IO.File]::WriteAllLines($argumentFile, [string[]]$quoted, [System.Text.UTF8Encoding]::new($false))
Invoke-Checked (Join-Path $jdkBin "jpackage.exe") @("@$argumentFile")

Join-Path $output "dog-vision-$AppVersion.msi"
