# Tries the command line's zip for Windows on a Windows machine:
# it unpacks the zip into a folder whose name has a space,
# checks that it holds the launcher, LICENSE, THIRD-PARTY-LICENSES.txt and the runtime's legal folder,
# puts it on the PATH, and from another folder runs dog-vision-cli with --help,
# on a photo, which it converts, and with an option it does not know, which exits with 2.
#
# -Zip is the zip, as tools/package_cli_zip_on_windows.ps1 or tools/package_cli_zip_on_linux.sh builds it.
param(
    [Parameter(Mandatory)] [string]$Zip
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path ([System.IO.Path]::GetTempPath()) "dog vision cli"
if (Test-Path $work) {
    Remove-Item -Recurse -Force $work
}
Expand-Archive $Zip $work
$launcher = Join-Path $work "dog-vision-cli\dog-vision-cli.exe"
foreach ($file in "dog-vision-cli.exe", "LICENSE", "THIRD-PARTY-LICENSES.txt", "runtime\legal\java.base") {
    if (-not (Test-Path (Join-Path $work "dog-vision-cli\$file"))) {
        throw "The zip has no dog-vision-cli\$file"
    }
}
$photo = Join-Path $work "test photo.jpg"
Copy-Item (Join-Path $root "tools\test_photo.jpg") $photo
$env:PATH = "$(Split-Path -Parent $launcher);$env:PATH"
Set-Location ([System.IO.Path]::GetTempPath())

dog-vision-cli --help
if ($LASTEXITCODE -ne 0) {
    throw "--help exited with $LASTEXITCODE"
}
dog-vision-cli $photo
if ($LASTEXITCODE -ne 0) {
    throw "The conversion exited with $LASTEXITCODE"
}
if (-not (Test-Path (Join-Path $work "test photo.dog.png"))) {
    throw "The conversion wrote no test photo.dog.png"
}
dog-vision-cli --no-such-option
if ($LASTEXITCODE -ne 2) {
    throw "An unknown option exited with $LASTEXITCODE, not 2"
}
Write-Host "The zip's dog-vision-cli runs from the PATH"
# The unknown option's 2 is still in $LASTEXITCODE, which GitHub Actions' pwsh exits with after the script:
# every failure above has thrown.
exit 0
