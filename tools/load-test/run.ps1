param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Arguments
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$localRepo = Join-Path $root ".m2\repository"
$classpathFile = Join-Path $PSScriptRoot "classpath.txt"
$outputDirectory = Join-Path $PSScriptRoot "out"
$javacPath = (Get-Command javac -ErrorAction Stop).Source
$javaPath = Join-Path (Split-Path $javacPath) "java.exe"

Push-Location $root
try {
    mvn "-Dmaven.repo.local=$localRepo" -q -DskipTests compile
    mvn "-Dmaven.repo.local=$localRepo" -q dependency:build-classpath "-Dmdep.outputFile=$classpathFile"

    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $generatedSources = Join-Path $root "target\generated-sources\protobuf\java"
    $applicationClasses = Join-Path $root "target\classes"
    $runtimeClasspath = "$applicationClasses;$generatedSources;$(Get-Content $classpathFile -Raw)"

    & $javacPath --release 17 -cp $runtimeClasspath -d $outputDirectory "$PSScriptRoot\LoadTestClient.java"
    & $javaPath "-cp" "$outputDirectory;$runtimeClasspath" com.example.im.load.LoadTestClient @Arguments
}
finally {
    Pop-Location
}
