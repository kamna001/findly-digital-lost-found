$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$classes = Join-Path $root "build\classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source = Join-Path $root "src\main\java\com\foundly\LostFoundServer.java"
javac -encoding UTF-8 -d $classes $source
if ($LASTEXITCODE -ne 0) {
    throw "Java compilation failed."
}
java -cp $classes com.foundly.LostFoundServer
