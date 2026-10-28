#requires -Version 7.2
# Pure/mocked tests: never dot-source the runner or invoke a real Docker/Java service.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$runner = Join-Path $root 'tools/checkout-browser.ps1'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($runner,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Runner PowerShell syntax invalid' }
foreach ($name in @('Assert-V2WorkspaceFacts','Get-OwnerHash','Get-OwnedDirectory','Assert-OwnedState','Read-OwnedState','Write-OwnedStopRequest','Test-LauncherAlive','Assert-LocalDocker','Remove-OwnedContainer')) {
    $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
    if (-not $definition) { throw "Missing function $name" }
    Invoke-Expression $definition.Extent.Text
}
function Must-Reject([scriptblock]$Body) {
    $rejected=$false
    try { & $Body | Out-Null } catch { $rejected=$true }
    if (-not $rejected) { throw 'Unsafe input was accepted' }
}
$run='a'*32; $owner=Get-OwnerHash $root; $container='c'*64; $uuid='11111111-1111-1111-1111-111111111111'
$state=[pscustomobject]@{runId=$run; owner=$owner; pid=7; started=1; phase='READY'; containerId=$container; serverUuid=$uuid; database="checkout_browser_$run"; webPort=9085; dbPort=49000}
Assert-OwnedState $root $run $state
Must-Reject { Get-OwnedDirectory $root '../escape' }
Must-Reject { Get-OwnedDirectory $root ('A'*32) }
$state.owner='foreign'; Must-Reject { Assert-OwnedState $root $run $state }; $state.owner=$owner
$state.webPort=8083; Must-Reject { Assert-OwnedState $root $run $state }; $state.webPort=9085
$global:FakeDockerCalls=[Collections.Generic.List[string]]::new()
$global:FakeDockerContainer=$container; $global:FakeDockerUuid=$uuid; $global:FakeDockerMissing=$false
$global:FakeDockerInfo=[pscustomobject]@{Id=$container; Config=[pscustomobject]@{Image='mysql:8.4.3'; Labels=[pscustomobject]@{'com.3dl.checkout.browser.run'=$run; 'com.3dl.checkout.browser.owner'=$owner}};
    HostConfig=[pscustomobject]@{Tmpfs=[pscustomobject]@{'/var/lib/mysql'='rw,size=1g'}; PortBindings=[pscustomobject]@{'3306/tcp'=@([pscustomobject]@{HostIp='127.0.0.1';HostPort='0'})}};
    Mounts=@(); State=[pscustomobject]@{Running=$true}}
function global:docker {
    $global:LASTEXITCODE=0
    $command=' '+($args -join ' ')+' '
    $global:FakeDockerCalls.Add($command)
    if ($command.Contains(' context inspect ')) { return 'npipe:////./pipe/dockerDesktopLinuxEngine' }
    if ($command.Contains(' version ')) { return '28.5.2' }
    if ($command.Contains(' ps ')) { if (-not $global:FakeDockerMissing) { return $global:FakeDockerContainer }; return }
    if ($command.Contains(' inspect ')) { return ($global:FakeDockerInfo | ConvertTo-Json -Depth 12 -Compress) }
    if ($command.Contains(' exec ')) { return "[auto]`nserver-uuid=$global:FakeDockerUuid" }
    if ($command.Contains(' stop ') -or $command.Contains(' rm ')) { return $global:FakeDockerContainer }
    throw 'Unrecognized mocked Docker operation'
}
function No-Removal {
    if (@($global:FakeDockerCalls | Where-Object { $_ -match ' (stop|rm) ' }).Count) { throw 'Attempted removal of unowned resources' }
}
try {
    $global:FakeDockerInfo.Config.Labels.'com.3dl.checkout.browser.run'='foreign'
    Must-Reject { Remove-OwnedContainer $root $run $state }; No-Removal
    $global:FakeDockerInfo.Config.Labels.'com.3dl.checkout.browser.run'=$run
    $global:FakeDockerInfo.HostConfig.PortBindings.'3306/tcp'[0].HostIp='0.0.0.0'
    Must-Reject { Remove-OwnedContainer $root $run $state }; No-Removal
    $global:FakeDockerInfo.HostConfig.PortBindings.'3306/tcp'[0].HostIp='127.0.0.1'
    $global:FakeDockerInfo.Mounts=@([pscustomobject]@{Type='volume'})
    Must-Reject { Remove-OwnedContainer $root $run $state }; No-Removal
    $global:FakeDockerInfo.Mounts=@()
    $global:FakeDockerUuid='22222222-2222-2222-2222-222222222222'
    Must-Reject { Remove-OwnedContainer $root $run $state }; No-Removal
    $global:FakeDockerUuid=$uuid
    Remove-OwnedContainer $root $run $state
    if (@($global:FakeDockerCalls | Where-Object { $_ -match ' stop ' }).Count -ne 1) { throw 'Owned container was not selected exactly once' }
    $global:FakeDockerCalls.Clear(); $global:FakeDockerMissing=$true
    Remove-OwnedContainer $root $run $state; No-Removal
    $global:FakeDockerMissing=$false; $global:FakeDockerInfo.State.Running=$false; $state.serverUuid=$uuid
    Remove-OwnedContainer $root $run $state
    if (@($global:FakeDockerCalls | Where-Object { $_ -match ' rm --volumes ' }).Count -ne 1) { throw 'Created-only owned recovery failed' }
} finally { Remove-Item Function:\docker }
Write-Output 'PASS: PowerShell parser; RunId/state guards; foreign run, public binding, persistent volume and UUID rejection; owned/missing/created-only mocked cleanup. No real services invoked.'

# Process inspection only: all native process/CIM calls are mocked.
$script:fakeProcessCalls=0
$script:fakeProcessMode='gone'
$script:fakeStarted=[DateTimeOffset]::FromUnixTimeMilliseconds(1000).UtcDateTime
$state.started=1000
$directory=Get-OwnedDirectory $root $run
function Get-Process {
    $script:fakeProcessCalls++
    if ($script:fakeProcessMode -eq 'gone' -or
        ($script:fakeProcessMode -in @('exits-before-cim','null-command-exited','exits-before-starttime') -and $script:fakeProcessCalls -gt 1)) { return }
    if ($script:fakeProcessMode -eq 'exits-before-starttime') {
        $vanished=[pscustomobject]@{}
        $vanished | Add-Member ScriptProperty StartTime { throw [InvalidOperationException]::new('Process exited') }
        return $vanished
    }
    return [pscustomobject]@{StartTime=$script:fakeStarted}
}
function Get-CimInstance {
    switch ($script:fakeProcessMode) {
        'exits-before-cim' { return }
        'unattestable-live' { return }
        { $_ -in @('null-command-exited','null-command-live') } { return [pscustomobject]@{CommandLine=$null} }
        'foreign-command' { return [pscustomobject]@{CommandLine='java unrelated-application'} }
        default { return [pscustomobject]@{CommandLine="java com.TreDL.ecommerce.tools.CheckoutBrowserLauncher serve $run $directory"} }
    }
}
try {
    foreach ($mode in @('gone','exits-before-cim','null-command-exited','exits-before-starttime')) {
        $script:fakeProcessMode=$mode; $script:fakeProcessCalls=0
        if (Test-LauncherAlive $state $directory) { throw "Terminated process reported alive: $mode" }
    }
    foreach ($mode in @('unattestable-live','foreign-command','null-command-live')) {
        $script:fakeProcessMode=$mode; $script:fakeProcessCalls=0
        Must-Reject { Test-LauncherAlive $state $directory }
    }
    $script:fakeProcessMode='owned-live'; $script:fakeProcessCalls=0
    if (-not (Test-LauncherAlive $state $directory)) { throw 'Owned live launcher not recognized' }
    $state.started=5000
    Must-Reject { Test-LauncherAlive $state $directory }
} finally { Remove-Item Function:\Get-Process; Remove-Item Function:\Get-CimInstance }
Write-Output 'PASS: 9 mocked process lifecycle cases: absent, exit before CIM, null command after exit, exit during StartTime, unattestable live PID, foreign command, null command on live PID, owned live launcher, reused PID. No real process stopped.'

# Branch-independent workspace identity: pure facts, never invokes Git or services.
$blob='d'*40
Assert-V2WorkspaceFacts $root $root $blob $blob
Must-Reject { Assert-V2WorkspaceFacts $root ([IO.Path]::GetDirectoryName($root)) $blob $blob }
Must-Reject { Assert-V2WorkspaceFacts $root $root '' $blob }
Must-Reject { Assert-V2WorkspaceFacts $root $root $blob ('e'*40) }
Write-Output 'PASS: 4 workspace/V1 checks; Git root match, tracked schema and immutable V1; no branch-name dependency.'