#requires -Version 7.2
[CmdletBinding()]
param(
    [ValidateSet('Start','Status','Stop')][string]$Action = 'Status',
    [string]$RunId,
    [ValidateSet(9085)][int]$Port = 9085
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path

function Get-OwnerHash([string]$Root) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Root.ToLowerInvariant())
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}
function Get-OwnedDirectory([string]$Root, [string]$Id) {
    if ($Id -cnotmatch '^[a-f0-9]{32}$') { throw 'Invalid RunId' }
    $path = [IO.Path]::GetFullPath((Join-Path $Root "target/checkout-browser/$Id"))
    foreach ($part in @((Join-Path $Root 'target'),(Join-Path $Root 'target/checkout-browser'),$path)) {
        if ((Test-Path -LiteralPath $part) -and ((Get-Item -LiteralPath $part).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Reparse points are forbidden for browser runtime state'
        }
    }
    return $path
}
function Assert-OwnedState([string]$Root,[string]$Id,$State) {
    $keys = @('runId','owner','pid','started','phase','containerId','serverUuid','database','webPort','dbPort')
    if (@($State.PSObject.Properties.Name | Where-Object { $_ -notin $keys }).Count -or @($State.PSObject.Properties).Count -ne $keys.Count) { throw 'Unexpected state properties' }
    if ($State.runId -cne $Id -or $State.owner -cne (Get-OwnerHash $Root) -or $State.webPort -ne 9085 -or
        $State.database -cne "checkout_browser_$Id" -or [long]$State.pid -le 0 -or [long]$State.started -le 0 -or
        $State.containerId -cnotmatch '^(?:[a-f0-9]{64})?$' -or $State.serverUuid -cnotmatch '^(?:[a-f0-9-]{36})?$' -or
        $State.phase -notin @('STARTING','READY','FAILED','STOPPING','STOPPED','CLEANUP_REQUIRED')) { throw 'Unowned runtime state' }
}
function Read-OwnedState([string]$Root,[string]$Id) {
    $directory = Get-OwnedDirectory $Root $Id
    $file = Join-Path $directory 'state.json'
    if ((Get-Item -LiteralPath $file).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Redirected state forbidden' }
    $state = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
    Assert-OwnedState $Root $Id $state
    return $state
}
function Write-OwnedStopRequest([string]$Root,[string]$Id) {
    $directory = Get-OwnedDirectory $Root $Id
    $file = Join-Path $directory 'stop.request'
    if (Test-Path -LiteralPath $file) {
        if (((Get-Item -LiteralPath $file).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            (Get-Content -LiteralPath $file -Raw) -cne $Id) { throw 'Unowned stop request' }
        return
    }
    $stream = [IO.File]::Open($file,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $bytes = [Text.Encoding]::UTF8.GetBytes($Id); $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
}
function Test-LauncherAlive($State,[string]$Directory) {
    $process = Get-Process -Id $State.pid -ErrorAction SilentlyContinue
    if (-not $process) { return $false }
    try {
        $started = [DateTimeOffset]::new($process.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
        if ([Math]::Abs($started - [long]$State.started) -gt 1500) { throw 'PID identity changed; cleanup refused' }
        $identity = Get-CimInstance Win32_Process -Filter "ProcessId=$($State.pid)"
        # The process may exit between Get-Process and the CIM query. Never read
        # CommandLine on null, and never treat an unattestable live PID as stopped.
        if (-not $identity -or -not $identity.CommandLine) {
            if (-not (Get-Process -Id $State.pid -ErrorAction SilentlyContinue)) { return $false }
            throw 'Launcher identity could not be attested'
        }
        $command = $identity.CommandLine
        if (-not $command -or -not $command.Contains('com.TreDL.ecommerce.tools.CheckoutBrowserLauncher') -or
            -not $command.Contains('serve') -or -not $command.Contains([string]$State.runId) -or -not $command.Contains($Directory)) {
            throw 'Launcher identity could not be attested'
        }
        return $true
    } catch {
        # StartTime/CIM may also fail if the original process exited mid-check.
        # A live or reused PID still fails closed; no process is ever killed here.
        if (-not (Get-Process -Id $State.pid -ErrorAction SilentlyContinue)) { return $false }
        throw
    }
}
function Assert-LocalDocker {
    $endpoint = & docker --context desktop-linux context inspect desktop-linux --format '{{.Endpoints.docker.Host}}'
    if ($LASTEXITCODE -ne 0 -or $endpoint.Trim() -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') { throw 'Local Docker Desktop Linux context required' }
    & docker --context desktop-linux version --format '{{.Server.Version}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Local daemon unavailable; no configuration will be changed' }
}
function Remove-OwnedContainer([string]$Root,[string]$Id,$State) {
    Assert-LocalDocker
    $owner = Get-OwnerHash $Root
    $matches = @(& docker --context desktop-linux ps -aq --no-trunc --filter "label=com.3dl.checkout.browser.run=$Id" --filter "label=com.3dl.checkout.browser.owner=$owner")
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect owned resources' }
    $matches = @($matches | Where-Object { $_ })
    if ($matches.Count -gt 1) { throw 'Ambiguous resources; refusing cleanup' }
    if ($matches.Count -eq 0) { return }
    $containerId = $matches[0]
    if ($containerId -cnotmatch '^[a-f0-9]{64}$' -or ($State.containerId -and $State.containerId -cne $containerId)) { throw 'Container identity mismatch' }
    $inspection = & docker --context desktop-linux inspect $containerId
    if ($LASTEXITCODE -ne 0) { throw 'Cannot attest owned container' }
    $info = @($inspection | ConvertFrom-Json)[0]
    $ports = @($info.HostConfig.PortBindings.'3306/tcp')
    if ($info.Id -cne $containerId -or $info.Config.Image -cne 'mysql:8.4.3' -or
        $info.Config.Labels.'com.3dl.checkout.browser.run' -cne $Id -or $info.Config.Labels.'com.3dl.checkout.browser.owner' -cne $owner -or
        $info.HostConfig.Tmpfs.'/var/lib/mysql' -cne 'rw,size=1g' -or @($info.Mounts | Where-Object Type -ne 'tmpfs').Count -gt 0 -or
        $ports.Count -ne 1 -or $ports[0].HostIp -cne '127.0.0.1') { throw 'Resource ownership/storage/binding mismatch' }
    if ($State.serverUuid -and $info.State.Running) {
        $identity = & docker --context desktop-linux exec $containerId cat /var/lib/mysql/auto.cnf
        if ($LASTEXITCODE -ne 0 -or ($identity -join "`n") -notmatch ('(?m)^server-uuid\s*=\s*' + [Regex]::Escape($State.serverUuid) + '\s*$')) { throw 'Server identity mismatch' }
    }
    if ($info.State.Running) {
        & docker --context desktop-linux stop --time 15 $containerId | Out-Null
    } else {
        & docker --context desktop-linux rm --volumes $containerId | Out-Null
    }
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop owned container; no other cleanup attempted' }
}
function Initialize-BrowserRuntime([string]$Root,[string]$Directory) {
    $dependencies = Join-Path $Directory 'dependencies.txt'
    & (Join-Path $Root 'mvnw.cmd') -B '-DincludeScope=test' "-Dmdep.outputFile=$dependencies" compile dependency:build-classpath *> (Join-Path $Directory 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Compilation/dependency resolution failed; see build.log' }
    $jars = @((Get-Content -LiteralPath $dependencies -Raw).Trim().Split([IO.Path]::PathSeparator))
    foreach ($jar in $jars) {
        if (-not $jar.EndsWith('.jar') -or -not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Only dependency JARs are permitted' }
    }
    $main = Join-Path $Directory 'main'
    $launcher = Join-Path $Directory 'launcher'
    New-Item -ItemType Directory -Path $main,$launcher | Out-Null
    $compiled = Join-Path $Root 'target/classes'
    foreach ($file in Get-ChildItem -LiteralPath $compiled -Recurse -File -Filter '*.class') {
        $relative = [IO.Path]::GetRelativePath($compiled,$file.FullName)
        $outer = ($relative -replace '\$[^\/]*\.class$','.class') -replace '\.class$','.java'
        if (-not (Test-Path -LiteralPath (Join-Path (Join-Path $Root 'src/main/java') $outer))) { continue }
        $destination = Join-Path $main $relative
        New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($destination)) | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $destination
    }
    $resources = Join-Path $Root 'src/main/resources'
    foreach ($file in Get-ChildItem -LiteralPath $resources -Recurse -File) {
        $destination = Join-Path $main ([IO.Path]::GetRelativePath($resources,$file.FullName))
        New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($destination)) | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $destination
    }
    $compileClasspath = (@($main) + $jars) -join [IO.Path]::PathSeparator
    & javac --release 17 -proc:none -encoding UTF-8 -cp $compileClasspath -d $launcher (Join-Path $Root 'tools/checkout-browser/CheckoutBrowserLauncher.java')
    if ($LASTEXITCODE -ne 0) { throw 'Launcher compilation failed' }
    $runtimeClasspath = (@($launcher,$main) + $jars) -join [IO.Path]::PathSeparator
    $checks = & java -cp $runtimeClasspath com.TreDL.ecommerce.tools.CheckoutBrowserLauncher check $Root (Join-Path $Root 'tools/checkout-browser/browser-checkout.properties')
    if ($LASTEXITCODE -ne 0) { throw 'Launcher static checks failed' }
    Write-Verbose ($checks -join "`n")
    return $runtimeClasspath
}

if ($Action -eq 'Start') {
    if ($RunId) { throw 'Start always creates a fresh RunId' }
    if ((& git -C $repoRoot branch --show-current) -ne 'feature/checkout-v2-integration') { throw 'Unexpected worktree branch' }
    Assert-LocalDocker
    $RunId = [guid]::NewGuid().ToString('N')
    $directory = Get-OwnedDirectory $repoRoot $RunId
    if (Test-Path -LiteralPath $directory) { throw 'Run directory already exists' }
    New-Item -ItemType Directory -Path $directory | Out-Null
    Write-Output "RunId=$RunId"
    $classpath = Initialize-BrowserRuntime $repoRoot $directory
    $start = [Diagnostics.ProcessStartInfo]::new((Get-Command java).Source)
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true; $start.WorkingDirectory = $repoRoot
    foreach ($argument in @('-cp',$classpath,'com.TreDL.ecommerce.tools.CheckoutBrowserLauncher','serve',$repoRoot,$RunId,(Join-Path $repoRoot 'tools/checkout-browser/browser-checkout.properties'))) { $start.ArgumentList.Add($argument) }
    foreach ($name in @($start.Environment.Keys)) {
        if ($name -match '^(SPRING_|CHECKOUT_|DB_|MYSQL_|DOCKER_|TESTCONTAINERS_|LOGGING_)|^(JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS|CLASSPATH)$') { $start.Environment.Remove($name) | Out-Null }
    }
    $key = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $start.Environment['CHECKOUT_QUOTE_SECRET_BASE64'] = [Convert]::ToBase64String($key)
    [Array]::Clear($key,0,$key.Length)
    $process = $null
    try {
        $process = [Diagnostics.Process]::Start($start)
        $start.Environment.Remove('CHECKOUT_QUOTE_SECRET_BASE64') | Out-Null
        $deadline = [DateTime]::UtcNow.AddSeconds(240)
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($process.HasExited) { throw 'Launcher exited before READY; use Status/Stop with this RunId' }
            if (Test-Path -LiteralPath (Join-Path $directory 'state.json')) {
                $state = Read-OwnedState $repoRoot $RunId
                if ($state.phase -eq 'READY') { Write-Output 'READY http://127.0.0.1:9085'; return }
                if ($state.phase -in @('FAILED','STOPPED','CLEANUP_REQUIRED')) { throw "Launcher phase=$($state.phase); use Stop with this RunId" }
            }
            Start-Sleep -Milliseconds 300
        }
        throw 'Startup timed out; stop requested, inspect this RunId'
    } catch {
        if ($process -and -not $process.HasExited) { Write-OwnedStopRequest $repoRoot $RunId }
        throw
    } finally { $start.Environment.Remove('CHECKOUT_QUOTE_SECRET_BASE64') | Out-Null }
} else {
    $directory = Get-OwnedDirectory $repoRoot $RunId
    $state = Read-OwnedState $repoRoot $RunId
    $alive = Test-LauncherAlive $state $directory
    if ($Action -eq 'Status') {
        [pscustomobject]@{ RunId=$RunId; Phase=$state.phase; LauncherAlive=$alive; Url='http://127.0.0.1:9085'; ContainerId=$state.containerId }
        return
    }
    if ($alive) {
        Write-OwnedStopRequest $repoRoot $RunId
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        do { Start-Sleep -Milliseconds 300; $alive = Test-LauncherAlive $state $directory } while ($alive -and [DateTime]::UtcNow -lt $deadline)
        if ($alive) { throw 'Launcher has not exited; database preserved. Retry Stop after inspecting this RunId.' }
    }
    $state = Read-OwnedState $repoRoot $RunId
    Remove-OwnedContainer $repoRoot $RunId $state
    $state.phase = 'STOPPED'
    $stateFile = Join-Path $directory 'state.json'
    $temporary = Join-Path $directory ([guid]::NewGuid().ToString('N') + '.state.tmp')
    [IO.File]::WriteAllText($temporary,($state | ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
    [IO.File]::Move($temporary,$stateFile,$true)
    Write-Output "STOPPED RunId=$RunId (only owned resources)"
}
