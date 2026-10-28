#requires -Version 7.2
# Static checks only: never invokes the release wrapper, JDBC, Spring or Docker.
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$count=0
function Check([bool]$Condition,[string]$Description){
 if(-not $Condition){throw "Static release check failed: $Description"}
 $script:count++
}
foreach($path in @('tools/checkout-release.ps1','tools/checkout-release/NativeStore.ps1','tools/checkout-release/release-static-tests.ps1')){
 $tokens=$null;$errors=$null
 [Management.Automation.Language.Parser]::ParseFile((Join-Path $root $path),[ref]$tokens,[ref]$errors) | Out-Null
 Check ($errors.Count -eq 0) "PowerShell parse $path"
}
$native=Get-Content (Join-Path $root 'tools/checkout-release/NativeStore.ps1') -Raw
foreach($invariant in @('CreateNew','WriteThrough','Flush($true)','SetAccessRuleProtection($true,$false)','AreAccessRulesProtected','ReparsePoint','MachineGuid','Global\3DLCheckoutV2-','WaitOne(0)')){
 Check ($native.Contains($invariant)) "NTFS/mutex invariant $invariant"
}
$wrapper=Get-Content (Join-Path $root 'tools/checkout-release.ps1') -Raw
Check ($wrapper.Contains('CHECKOUT_V2_PLAN_STORE')) 'explicit store'
Check ($wrapper.Contains('PlanFingerprint')) 'explicit fingerprint'
Check ($wrapper.Contains('Only dependency JARs accepted')) 'classpath jars only'
Check (-not $wrapper.Contains('target/test-classes')) 'no test classes'
Check (-not $wrapper.Contains('src/test/resources')) 'no test resources'
Check (-not $wrapper.Contains('ConfirmNewDatabase')) 'obsolete approval bypass removed'
$cli=Get-Content (Join-Path $root 'tools/checkout-release/CheckoutMigration.java') -Raw
foreach($invariant in @('cleanDisabled(true)','baselineOnMigrate(false)','createSchemas(false)','target("1")','outOfOrder(false)','resourceProvider(provider)','approved=snapshot.clone()','application-test.properties')){
 Check ($cli.Contains($invariant)) "launcher invariant $invariant"
}
Check (-not ($wrapper+$cli+$native -match '(?i)docker\s+(system|volume)\s+prune|\.clean\s*\(|\.repair\s*\(|DROP\s+DATABASE|TRUNCATE\s+TABLE')) 'no destructive command'
$flow=Get-Content (Join-Path $root 'src/main/java/com/TreDL/ecommerce/release/MigrationWorkflow.java') -Raw
Check ($flow.Contains('requireRecord("approval",id,id)')) 'approval required'
Check ($flow.IndexOf('store.record("used",id,id)') -lt $flow.IndexOf('runner.migrate(')) 'durable consumption precedes migration'
Check ($flow.Contains('store.record("failure",resource,id)')) 'persistent failure'
Check ($flow.Contains('seen.migrationAccount()') -and $flow.Contains('seen.runtimeAccount()') -and $flow.Contains('p.effectiveHost()')) 'authenticated destination binding'
$lease=Get-Content (Join-Path $root 'src/main/java/com/TreDL/ecommerce/release/MigrationLease.java') -Raw
Check ($lease.Contains('IS_USED_LOCK(?)') -and $lease.Contains('CONNECTION_ID()') -and $lease.Contains('failed.set(true)')) 'lease session and irreversible failure'
Check (-not $lease.Contains('KILL CONNECTION')) 'no production session termination'
$policy=Get-Content (Join-Path $root 'src/main/java/com/TreDL/ecommerce/release/ReleaseDatabase.java') -Raw
Check ($policy.Contains('@@mandatory_roles') -and $policy.Contains('Global, foreign, column or activatable role grants forbidden')) 'global and role grants rejected'
Check ($policy.Contains('History must be read-only')) 'history read only'
$properties=Get-Content (Join-Path $root 'src/main/resources/application-checkout-release.properties') -Raw
foreach($invariant in @('spring.jpa.hibernate.ddl-auto=validate','spring.flyway.enabled=false','app.data-initializer.enabled=false','server.address=127.0.0.1','server.servlet.session.cookie.secure=true')){
 Check ($properties.Contains($invariant)) "runtime $invariant"
}
Write-Output "PASS: $count release static checks; no service or container started."
