# Builds the command line's zip for Windows on x86-64 on Windows:
# dog-vision-cli, to unpack and run anywhere without installing it.
#
# jpackage makes its app image with dog-vision-cli.exe, a native launcher that runs in a console, of the arguments
# that :packaging's windowsCliJpackage task writes, with the JARs that cli's windowsLauncher task writes
# and the runtime that :packaging's windowsCliRuntime task links from Temurin's jmods for Windows,
# and this then copies the launcher's .cfg that :packaging's windowsCliLauncherConfigs task writes,
# with the classpath in its order, and the licences it writes beside it, LICENSE and THIRD-PARTY-LICENSES.txt;
# the zip holds that image.
# It is written to tools\build\zip, which git ignores.
# tools/package_cli_zip_on_linux.sh builds the same zip through Wine.
#
# Needs:
# - PowerShell 7 (pwsh), which reads this file as UTF-8.
# - A JDK 25 as JAVA_HOME, whose jpackage makes the app image.
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$cliFiles = Join-Path $root "packaging\build\windows\cli"

# Runs a program and stops the script where it fails, as $ErrorActionPreference does not for them.
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with $LASTEXITCODE"
    }
}

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME names no JDK"
}


# What jpackage takes in: the arguments, with the JARs and the runtime they name.

$properties = Get-Content (Join-Path $root "gradle.properties") -Raw
if ($properties -notmatch '(?m)^appVersion=(.+?)\r?$') {
    throw "gradle.properties has no appVersion"
}
$version = $Matches[1]
Invoke-Checked (Join-Path $root "gradlew.bat") @("--quiet", ":packaging:windowsCliJpackage")
$jpackageFiles = Join-Path $cliFiles "jpackage"

$staging = Join-Path $root "tools\build\staging\cli-zip"
if (Test-Path $staging) {
    Remove-Item -Recurse -Force $staging
}


# jpackage, and the zip of its image.

# The file of arguments, which jpackage reads in its default charset, UTF-8 from JDK 18 on,
# as windowsCliJpackage writes it: Java reads its command line in the system's ANSI code page,
# which on an English Windows, 1252, has no ř for the vendor's name.
$image = Join-Path $staging "image"
Invoke-Checked (Join-Path $env:JAVA_HOME "bin\jpackage.exe") @(
    "@$(Join-Path $jpackageFiles "arguments")",
    "--type", "app-image",
    "--dest", $image
)

$app = Join-Path $image "dog-vision-cli"
# The launcher with its classpath in its order;
# the licence, and what the zip holds that is not this project's own, in its folder.
Invoke-Checked (Join-Path $root "gradlew.bat") @("--quiet", ":packaging:windowsCliLauncherConfigs", "-PwindowsAppImage=$app")
Copy-Item (Join-Path $cliFiles "launchers\*.cfg") (Join-Path $app "app")
foreach ($file in "LICENSE", "THIRD-PARTY-LICENSES.txt") {
    Copy-Item (Join-Path $cliFiles "launchers\$file") $app
}
$output = Join-Path $root "tools\build\zip"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$zip = Join-Path $output "dog-vision-cli-$version-windows-x64.zip"
if (Test-Path $zip) {
    Remove-Item -Force $zip
}
# The image's folder at the zip's root, so that it unpacks into a folder of its own.
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($app, $zip, [System.IO.Compression.CompressionLevel]::Optimal, $true)

$zip
