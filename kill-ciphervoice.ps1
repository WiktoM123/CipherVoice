[CmdletBinding(SupportsShouldProcess = $true)]
param()

$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$knownJars = @(
    [IO.Path]::Combine($projectRoot, 'client\target\CipherVoice.jar')
    [IO.Path]::Combine($projectRoot, 'server\target\server-0.1.0-SNAPSHOT.jar')
)
$knownLauncher = [IO.Path]::Combine($projectRoot, 'dist\CipherVoice\CipherVoice.exe')

function Test-CipherVoiceProcess($candidate) {
    if ([string]::Equals($candidate.ExecutablePath, $knownLauncher,
            [StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    if ($candidate.Name -notin @('java.exe', 'javaw.exe') -or
            $candidate.CommandLine -notmatch '(?i)(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))') {
        return $false
    }
    $jarArgument = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
    # A relative path does not identify the working directory of another process.
    if (![IO.Path]::IsPathRooted($jarArgument)) {
        return $false
    }
    try {
        return $knownJars -contains [IO.Path]::GetFullPath($jarArgument)
    } catch {
        return $false
    }
}

$matched = 0
$failed = $false
foreach ($candidate in Get-CimInstance Win32_Process -ErrorAction Stop) {
    if (!(Test-CipherVoiceProcess $candidate)) {
        continue
    }
    $matched++
    if ($PSCmdlet.ShouldProcess("$($candidate.Name), PID $($candidate.ProcessId)", 'Zatrzymaj CipherVoice')) {
        try {
            $current = Get-CimInstance Win32_Process -Filter "ProcessId = $($candidate.ProcessId)" -ErrorAction Stop
            if ($null -ne $current -and (Test-CipherVoiceProcess $current)) {
                Stop-Process -Id $current.ProcessId -Force -ErrorAction Stop
                Write-Host "Zatrzymano $($current.Name), PID $($current.ProcessId)."
            }
        } catch {
            $failed = $true
            Write-Warning "Nie udalo sie zatrzymac PID $($candidate.ProcessId): $($_.Exception.Message)"
        }
    }
}
if ($matched -eq 0) {
    Write-Host 'Nie znaleziono procesow uruchomionych skryptami tej kopii CipherVoice ani jej plikiem EXE.'
}
if ($failed) {
    exit 1
}
