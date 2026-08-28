$ErrorActionPreference = 'Continue'

$projectDirectory = 'D:\Desktop\company-projects\huanbao-ai-assistant'
$npmCommand = 'C:\Program Files\nodejs\npm.cmd'
$stdoutLog = Join-Path $env:TEMP 'huanbao-ai-assistant-vite.out.log'
$stderrLog = Join-Path $env:TEMP 'huanbao-ai-assistant-vite.err.log'

function Test-FrontendAlreadyRunning {
    $listener = Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { return $false }

    $processInfo = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $listener.OwningProcess)
    return [bool]($processInfo.CommandLine -like '*huanbao-ai-assistant*')
}

while ($true) {
    if (Test-FrontendAlreadyRunning) {
        Start-Sleep -Seconds 5
        continue
    }

    $viteProcess = $null

    try {
        $viteProcess = Start-Process `
            -FilePath $npmCommand `
            -ArgumentList @('run', 'dev', '--', '--host', '0.0.0.0') `
            -WorkingDirectory $projectDirectory `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdoutLog `
            -RedirectStandardError $stderrLog `
            -PassThru

        $viteProcess.WaitForExit()
    } catch {
        $_ | Out-File -FilePath $stderrLog -Append -Encoding utf8
    }

    Start-Sleep -Seconds 2
}
