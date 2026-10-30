$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$testClasses = Join-Path $projectPath '.local/test-classes'
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
$sourcePath = Join-Path $projectPath 'launcher/src/io/github/tvhome/universal'

# org.json is a TEST-only dependency: the APK uses the org.json bundled with Android.
$libDir = Join-Path $projectPath '.local/toolchain/lib'
$jsonVersion = '20240303'
$jsonJar = Join-Path $libDir "json-$jsonVersion.jar"
New-Item -ItemType Directory -Force -Path $libDir | Out-Null
if (-not (Test-Path -LiteralPath $jsonJar)) {
    $jsonUrl = "https://repo1.maven.org/maven2/org/json/json/$jsonVersion/json-$jsonVersion.jar"
    Write-Host "Downloading org.json (test dependency only): $jsonUrl"
    Invoke-WebRequest -Uri $jsonUrl -OutFile $jsonJar
}

# android.jar gives AppVisibility its SharedPreferences type; it is never executed in these tests.
$androidJar = Join-Path $env:ANDROID_HOME 'platforms/android-34/android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { $androidJar = Join-Path $env:ANDROID_SDK_ROOT 'platforms/android-34/android.jar' }

$testSources = @(
    'AppOrder.java','AppMovePolicy.java','GreetingText.java','HomeOverridePolicy.java',
    'CarouselPolicy.java','WatchTimerPolicy.java','AppVisibility.java','FootballData.java','WeatherModel.java'
) | ForEach-Object { Join-Path $sourcePath $_ }
$testSources += Get-ChildItem -LiteralPath (Join-Path $projectPath 'tests') -Filter '*.java' | Select-Object -ExpandProperty FullName

$classpath = "$jsonJar;$androidJar"
& javac -encoding UTF-8 --release 11 -cp $classpath -d $testClasses @testSources
if ($LASTEXITCODE -ne 0) { throw 'Host test compilation failed' }

$runtimeClasspath = "$testClasses;$jsonJar;$androidJar"
foreach ($testClass in @('LogicTest','CarouselTest','TimerTest','AppVisibilityTest','FootballTest','WeatherTest')) {
    & java -cp $runtimeClasspath "io.github.tvhome.universal.$testClass"
    if ($LASTEXITCODE -ne 0) { throw "Failed: $testClass" }
}
Write-Host 'All host tests passed.'
