<#
.SYNOPSIS
  Stops the local development stack.

.PARAMETER Purge
  Also delete the PostgreSQL volume, so the next start migrates a fresh
  database from V1. Use this to reset local data.
#>
[CmdletBinding()]
param([switch] $Purge)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

Push-Location $repo
try {
    if ($Purge) {
        Write-Host '==> Stopping PostgreSQL and deleting its volume' -ForegroundColor Cyan
        docker compose down -v
        Write-Host '    Local database erased. The next backend start migrates from empty.' -ForegroundColor Green
    } else {
        Write-Host '==> Stopping PostgreSQL (data kept)' -ForegroundColor Cyan
        docker compose down
    }
} finally {
    Pop-Location
}

Write-Host @"

Stop the backend and frontend with Ctrl+C in their own terminals.
Uploaded files remain in local-data/storage; delete that directory to clear them.
"@
