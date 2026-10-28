#requires -Version 7.2
[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('InitStore','Preflight','Approve','Migrate','Verify')][string]$Action,[string]$PlanFingerprint)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if(-not $IsWindows){throw 'Qualified Windows executor required'}
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if(-not $env:CHECKOUT_V2_PLAN_STORE){throw 'Protected CHECKOUT_V2_PLAN_STORE required'}
if($Action -in @('Approve','Migrate','Verify') -and $PlanFingerprint -cnotmatch '^[a-f0-9]{64}$'){throw 'Explicit trusted-channel plan fingerprint required'}
$helper=Join-Path $root 'tools/checkout-release/NativeStore.ps1'
if($Action -eq 'InitStore'){& $helper -Action Init -Root $env:CHECKOUT_V2_PLAN_STORE | Out-Null;if($LASTEXITCODE -ne 0){throw 'Private store initialization failed'};Write-Output 'STORE INITIALIZED';return}
& $helper -Action Validate -Root $env:CHECKOUT_V2_PLAN_STORE | Out-Null
if($LASTEXITCODE -ne 0){throw 'Unprotected or changed store'}
$build=Join-Path $root ('target/release-migration/'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $build | Out-Null
$dependencies=Join-Path $build 'dependencies.txt'
& (Join-Path $root 'mvnw.cmd') -B '-DincludeScope=test' "-Dmdep.outputFile=$dependencies" compile dependency:build-classpath *> (Join-Path $build 'build.log')
if($LASTEXITCODE -ne 0){throw 'Tool build failed'}
$jars=@((Get-Content $dependencies -Raw).Trim().Split([IO.Path]::PathSeparator))
foreach($jar in $jars){if(-not $jar.EndsWith('.jar') -or -not (Test-Path -LiteralPath $jar -PathType Leaf)){throw 'Only dependency JARs accepted'}}
if(@($jars | Where-Object {$_ -match 'flyway-(core|mysql)-' -and $_ -notmatch 'flyway-(core|mysql)-10\.20\.1\.jar$'}).Count){throw 'Pinned Flyway version required'}
$classes=Join-Path $build 'launcher';New-Item -ItemType Directory -Path $classes | Out-Null
$classpath=(@((Join-Path $root 'target/classes'))+$jars) -join [IO.Path]::PathSeparator
& javac --release 17 -proc:none -encoding UTF-8 -cp $classpath -d $classes (Join-Path $root 'tools/checkout-release/CheckoutMigration.java')
if($LASTEXITCODE -ne 0){throw 'Launcher compilation failed'}
$runtime=(@($classes,(Join-Path $root 'target/classes'))+$jars) -join [IO.Path]::PathSeparator
$arguments=@('-cp',$runtime,'com.TreDL.ecommerce.tools.CheckoutMigration',$Action,(Join-Path $root 'src/main/resources/db/checkout-v2/V1__create_checkout_v2.sql'),$env:CHECKOUT_V2_PLAN_STORE,$helper)
if($PlanFingerprint){$arguments+=$PlanFingerprint}
& java @arguments
if($LASTEXITCODE -ne 0){throw 'Release command refused; preserve store and database for manual review'}
