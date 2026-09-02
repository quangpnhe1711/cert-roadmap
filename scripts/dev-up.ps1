<#
.SYNOPSIS
  Brings the local development stack to the point where the app can be started.

.DESCRIPTION
  Checks prerequisites, makes sure a JDK 21 is available to Maven, starts
  PostgreSQL, and generates the sample deck so there is something to upload.

  It deliberately does not start the backend or the frontend. Those belong in
  your own terminals where you can read their logs and restart them; a script
  that owns them just hides the output you need.

.PARAMETER SkipJdk
  Do not touch the JDK or the Maven toolchain configuration.

.EXAMPLE
  ./scripts/dev-up.ps1
#>
[CmdletBinding()]
param(
    [switch] $SkipJdk
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $repo 'tools'
$localData = Join-Path $repo 'local-data'
$sampleDeck = Join-Path $localData 'sample-aif-c01.pdf'

function Write-Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "    $text" -ForegroundColor Green }
function Write-Warn($text) { Write-Host "    $text" -ForegroundColor Yellow }

# ---------------------------------------------------------------- prerequisites

Write-Step 'Checking prerequisites'

foreach ($tool in @('docker', 'node', 'npm')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool is required and was not found on PATH."
    }
}
Write-Ok "docker $((docker --version) -replace 'Docker version ', '')"
Write-Ok "node $(node --version)"

try {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw }
} catch {
    throw 'The Docker daemon is not running. Start Docker Desktop and try again.'
}

# ------------------------------------------------------------------- JDK 21

if (-not $SkipJdk) {
    Write-Step 'Checking for a JDK 21'

    $jdk = Get-ChildItem -Path $toolsDir -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
           Select-Object -First 1

    if (-not $jdk) {
        # The build targets Java 21 and that is not negotiable, but Maven itself
        # can run on any JDK. Rather than asking for a system-wide upgrade, the
        # repository carries its own JDK and points a Maven toolchain at it.
        Write-Warn 'No JDK 21 in tools/. Downloading Temurin 21 (about 190 MB).'
        New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
        $archive = Join-Path $env:TEMP 'temurin-21.zip'
        $url = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse'
        Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing
        Expand-Archive -Path $archive -DestinationPath $toolsDir -Force
        Remove-Item $archive -Force
        $jdk = Get-ChildItem -Path $toolsDir -Directory -Filter 'jdk-21*' | Select-Object -First 1
        if (-not $jdk) { throw 'Download finished but no jdk-21* directory appeared in tools/.' }
    }

    Write-Ok "JDK 21 at $($jdk.FullName)"

    $toolchains = Join-Path $env:USERPROFILE '.m2\toolchains.xml'
    $entry = @"
    <toolchain>
      <type>jdk</type>
      <provides>
        <version>21</version>
        <vendor>temurin</vendor>
      </provides>
      <configuration>
        <jdkHome>$($jdk.FullName)</jdkHome>
      </configuration>
    </toolchain>
"@

    if (-not (Test-Path $toolchains)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $toolchains) | Out-Null
        @"
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
$entry
</toolchains>
"@ | Set-Content -Path $toolchains -Encoding UTF8
        Write-Ok "Wrote $toolchains"
    } elseif ((Get-Content $toolchains -Raw) -match '<version>\s*21\s*</version>') {
        Write-Ok "$toolchains already declares a JDK 21"
    } else {
        # Never rewrite a file someone else's projects depend on.
        Write-Warn "$toolchains exists but declares no JDK 21. Add this inside <toolchains>:"
        Write-Host $entry
    }
}

# ------------------------------------------------------------------ database

Write-Step 'Starting PostgreSQL on localhost:5434'

Push-Location $repo
try {
    docker compose up -d postgres | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed.' }

    $deadline = (Get-Date).AddSeconds(90)
    do {
        $state = (docker inspect -f '{{.State.Health.Status}}' certcopilot-postgres 2>$null)
        if ($state -eq 'healthy') { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    if ($state -ne 'healthy') { throw "PostgreSQL did not become healthy (last state: $state)." }
    Write-Ok 'PostgreSQL is healthy. Flyway migrates it on backend startup.'
} finally {
    Pop-Location
}

# ------------------------------------------------------------- sample material

Write-Step 'Checking for the sample deck'

if (Test-Path $sampleDeck) {
    Write-Ok "Sample deck already present: $sampleDeck"
} else {
    Write-Warn 'Generating a synthetic AIF-C01-shaped deck (title, dense, and diagram slides).'
    New-Item -ItemType Directory -Force -Path $localData | Out-Null
    Push-Location (Join-Path $repo 'backend')
    try {
        & ./mvnw -q test "-Dtest=DumpDeckTest" "-Ddump.deck=$sampleDeck"
        if ($LASTEXITCODE -ne 0) { throw 'Generating the sample deck failed.' }
    } finally {
        Pop-Location
    }
    Write-Ok "Wrote $sampleDeck"
}

# ------------------------------------------------------------------ next steps

Write-Host @"

Ready. Start the two processes in their own terminals:

  cd backend
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

  cd frontend
  npm install
  npm run dev

Then open http://localhost:5173 and register an account.
Upload local-data/sample-aif-c01.pdf when onboarding asks for material.

This build runs on the deterministic fake AI adapter. Lessons and quizzes are
fixture output and say so in the UI; they are not a measure of content quality.

Stop everything with ./scripts/dev-down.ps1
"@ -ForegroundColor White
