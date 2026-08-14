<#!
.SYNOPSIS
Starts the MoneyBags microservices locally, beginning with Eureka.

.DESCRIPTION
Each service is launched in a background Maven process. Output and errors are
written to the logs directory beside this script.
#>

[CmdletBinding()]
param(
    [int]$EurekaStartupTimeoutSeconds = 90,
    [ValidateSet('demo', 'default')]
    [string]$CustomerProfile = 'default'
)

$ErrorActionPreference = 'Stop'

# Some Windows IDE sessions expose both PATH and Path. Start-Process rejects
# that duplicate case-insensitive key, so retain one canonical process value.
$pathValue = [Environment]::GetEnvironmentVariable('Path', 'Process')
[Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
[Environment]::SetEnvironmentVariable('Path', $pathValue, 'Process')

$projectRoot = $PSScriptRoot
$logDirectory = Join-Path $projectRoot 'logs'
$pidDirectory = Join-Path $projectRoot '.moneybags-pids'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $pidDirectory -Force | Out-Null

function Test-PortListening {
    param([int]$Port)

    return [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port -contains $Port
}

function Start-MoneyBagsService {
    param(
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [int]$Port,
        [string]$Profile
    )

    if (Test-PortListening -Port $Port) {
        Write-Host "$Name is already listening on port $Port; skipping." -ForegroundColor Yellow
        return
    }

    $serviceDirectory = Join-Path $projectRoot $Name
    $pomFile = Join-Path $serviceDirectory 'pom.xml'
    if (-not (Test-Path $pomFile)) {
        throw "Cannot find $pomFile"
    }

    $standardOutput = Join-Path $logDirectory "$Name.out.log"
    $standardError = Join-Path $logDirectory "$Name.err.log"
    $mavenArguments = @('-f', $pomFile, 'spring-boot:run')
    if ($Profile -and $Profile -ne 'default') {
        $mavenArguments += "-Dspring-boot.run.profiles=$Profile"
    }

    $process = Start-Process -FilePath 'mvn.cmd' `
        -ArgumentList $mavenArguments `
        -WorkingDirectory $serviceDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput $standardOutput `
        -RedirectStandardError $standardError `
        -PassThru

    [pscustomobject]@{
        Name = $Name
        ProcessId = $process.Id
        ProcessName = $process.ProcessName
        StartedAtUtc = $process.StartTime.ToUniversalTime().ToString('o')
        Port = $Port
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $pidDirectory "$Name.json") -Encoding UTF8

    Write-Host "Started $Name (PID $($process.Id), port $Port)."
}

function Wait-ForPort {
    param(
        [Parameter(Mandatory)] [int]$Port,
        [Parameter(Mandatory)] [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortListening -Port $Port) {
            Write-Host "Eureka is listening on port $Port." -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    }

    throw "Eureka did not start on port $Port within $TimeoutSeconds seconds. Check logs\\eureka-server.err.log."
}

Start-MoneyBagsService -Name 'eureka-server' -Port 8080
Wait-ForPort -Port 8080 -TimeoutSeconds $EurekaStartupTimeoutSeconds

@(
    @{ Name = 'customer-service'; Port = 8082; Profile = $CustomerProfile },
    @{ Name = 'transaction-service'; Port = 8084 },
    @{ Name = 'ledger-service'; Port = 8085 },
    @{ Name = 'statement-reporting-service'; Port = 8086 },
    @{ Name = 'api-gateway'; Port = 8090 }
) | ForEach-Object {
    Start-MoneyBagsService -Name $_.Name -Port $_.Port -Profile $_.Profile
}

Write-Host "MoneyBags startup requested. Logs: $logDirectory" -ForegroundColor Green
