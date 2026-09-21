param(
    [Parameter(Mandatory = $true)]
    [int]$HostDataPort,

    [string]$DeviceSerial = "",

    [int]$DevicePort = 18080
)

$adbArgs = @()
if ($DeviceSerial) {
    $adbArgs += @("-s", $DeviceSerial)
}

$adbArgs += @("reverse", "tcp:$DevicePort", "tcp:$HostDataPort")

& adb @adbArgs
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "SpongeTube Media Lab data plane: http://localhost:$DevicePort/"
Write-Host "Control port is intentionally not reversed."
