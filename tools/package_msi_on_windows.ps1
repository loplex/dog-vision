# Builds a desktop window's MSI for Windows on x86-64 on Windows: with -Window compose, the default,
# dog-vision's, the Compose window's; with -Window swing, dog-vision-swing's.
#
# The same MSI as tools/package_msi_on_linux.sh builds through Wine, from the JAR that the window's
# windowsUberJar task assembles, but with the JDK this runs on: its jlink links the runtime and its
# jpackage makes the MSI through WiX Toolset 3, whose light.exe validates it (ICE) here. It is
# written to desktop/build/compose/binaries/main/msi/<version> or swing/build/packages/msi/<version>.
#
# Needs:
# - PowerShell 7 (pwsh), which reads this file as UTF-8, as the vendor's name needs.
# - A JDK 25 as JAVA_HOME, as the tar.gz's runtime is.
# - WiX Toolset 3.14's candle.exe and light.exe on the PATH, or its installation's WIX variable.
#
# -AppVersion gives the MSI another version than gradle.properties' appVersion, as a
# test of an upgrade needs a later one; the application in it stays the same.
param(
    [string]$AppVersion,
    [ValidateSet("compose", "swing")] [string]$Window = "compose"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
# The icons and dog-vision-cli's properties, which both windows' MSIs take.
$packaging = Join-Path $root "desktop\packaging"

# What each window's MSI is made of, as package_msi_on_linux.sh gives it, which says why the upgrade
# codes must never change and must differ, and where the modules and the Java option come from.
if ($Window -eq "compose") {
    $name = "dog-vision"
    $module = Join-Path $root "desktop"
    $jarTask = ":desktop:windowsUberJar"
    $jarDir = Join-Path $module "build\compose\jars"
    $outputDir = Join-Path $module "build\compose\binaries\main\msi"
    $mainClass = "cz.loplex.dogvision.desktop.MainKt"
    $description = "How a dog or a cat sees a photo, a video or the camera"
    $upgradeUuid = "602aa86b-3230-4786-8460-ba08bca42e45"
    $modules = "java.base,java.desktop,java.logging,jdk.crypto.ec,java.instrument,jdk.unsupported"
    $javaOptions = @("--java-options", "-Dcompose.application.configure.swing.globals=true")
} else {
    $name = "dog-vision-swing"
    $module = Join-Path $root "swing"
    $jarTask = ":swing:windowsUberJar"
    $jarDir = Join-Path $module "build\jars"
    $outputDir = Join-Path $module "build\packages\msi"
    $mainClass = "cz.loplex.dogvision.swing.MainKt"
    $description = "How a dog or a cat sees a photo, a video or the camera, in Swing"
    $upgradeUuid = "acf6164b-f4f9-4430-b4f0-939242f187fb"
    $modules = "java.base,java.desktop,java.instrument,jdk.unsupported"
    $javaOptions = @()
}

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

$properties = Get-Content (Join-Path $root "gradle.properties") -Raw
if ($properties -notmatch '(?m)^appVersion=(.+?)\r?$') {
    throw "gradle.properties has no appVersion"
}
$packageVersion = $Matches[1]
if (-not $AppVersion) {
    $AppVersion = $packageVersion
}
Invoke-Checked (Join-Path $root "gradlew.bat") @("--quiet", $jarTask)
$jar = Join-Path $jarDir "$name-windows-x64-$packageVersion.jar"

$staging = Join-Path $module "build\windows-msi\$AppVersion"
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

# The resource directory, less its code page file, which jpackage from JDK 25 on hands light.exe
# after its own English strings, whose code page, 1252, light.exe then takes: in its place, those
# strings of this JDK's jpackage with the code page 1250, which replace jpackage's by their name.
$resources = Join-Path $staging "resources"
Copy-Item -Recurse (Join-Path $packaging "windows") $resources
Remove-Item (Join-Path $resources "MsiInstallerCodepage_en.wxl")
$extracted = Join-Path $staging "jimage"
Invoke-Checked (Join-Path $jdkBin "jimage.exe") @(
    "extract", "--dir", $extracted, "--include", "regex:.*/MsiInstallerStrings_en\.wxl",
    (Join-Path $env:JAVA_HOME "lib\modules")
)
$strings = Get-ChildItem -Recurse -File $extracted | Select-Object -First 1
if (-not $strings) {
    throw "This JDK's jpackage has no MsiInstallerStrings_en.wxl"
}
$text = [System.IO.File]::ReadAllText($strings.FullName)
if ($text -notmatch 'Codepage="1252"') {
    throw "jpackage's MsiInstallerStrings_en.wxl names no code page 1252"
}
$text = $text -replace 'Codepage="1252"', 'Codepage="1250"'
[System.IO.File]::WriteAllText((Join-Path $resources "MsiInstallerStrings_en.wxl"), $text)

$output = Join-Path $outputDir $AppVersion
if (Test-Path $output) {
    Remove-Item -Recurse -Force $output
}


# jpackage, with package_msi_on_linux.sh's options.

$arguments = @(
    "--type", "msi",
    "--name", $name,
    "--app-version", $AppVersion,
    "--vendor", "Martin Lopatář",
    "--description", $description,
    "--license-file", (Join-Path $root "LICENSE"),
    "--icon", (Join-Path $packaging "dog-vision.ico"),
    "--input", $inputDir,
    "--main-jar", (Split-Path -Leaf $jar),
    "--main-class", $mainClass
) + $javaOptions + @(
    "--runtime-image", $runtime,
    "--add-launcher", "dog-vision-cli=$(Join-Path $packaging "dog-vision-cli.properties")",
    "--resource-dir", $resources,
    "--win-menu",
    "--win-menu-group", $name,
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

Join-Path $output "$name-$AppVersion.msi"
