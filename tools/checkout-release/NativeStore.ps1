#requires -Version 7.2
param([ValidateSet('Init','Validate','Read','Write','Exists','Mutex')][string]$Action,[string]$Root,[string]$Name)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
try {
 if(-not $IsWindows){throw 'Windows NTFS required'}
 $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
 $rootPath=[IO.Path]::GetFullPath($Root)
 $profilePath=[IO.Path]::GetFullPath([Environment]::GetFolderPath('UserProfile')).TrimEnd('\')+'\'
 if(-not $rootPath.StartsWith($profilePath,[StringComparison]::OrdinalIgnoreCase)){throw 'Store must be inside operator profile'}
 $drive=[IO.DriveInfo]::new([IO.Path]::GetPathRoot($rootPath));if($drive.DriveType -ne 'Fixed' -or $drive.DriveFormat -cne 'NTFS'){throw 'Local fixed NTFS required'}
 $cursor=$rootPath
 while($cursor){if((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Reparse point forbidden'};$cursor=[IO.Path]::GetDirectoryName($cursor)}
 function Private-Acl($path){
  $item=Get-Item -LiteralPath $path
  if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Redirected store object'}
  $acl=Get-Acl -LiteralPath $path
  if(-not $acl.AreAccessRulesProtected -or $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $sid.Value){throw 'Unprotected store owner/inheritance'}
  $rules=@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
  if($rules.Count -ne 1 -or $rules[0].IdentityReference.Value -cne $sid.Value -or $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne 'FullControl'){throw 'Store DACL must grant only owner full control'}
 }
 function Write-New($file,[string]$text){
  $acl=[Security.AccessControl.FileSecurity]::new();$acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
  $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow'))
  $stream=[IO.FileSystemAclExtensions]::Create([IO.FileInfo]::new($file),[IO.FileMode]::CreateNew,[Security.AccessControl.FileSystemRights]::FullControl,[IO.FileShare]::None,4096,[IO.FileOptions]::WriteThrough,$acl)
  try{$bytes=[Text.Encoding]::UTF8.GetBytes($text);$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)}finally{$stream.Dispose()}
  Private-Acl $file
 }
 $nodeBytes=[Text.Encoding]::UTF8.GetBytes((Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Cryptography' -Name MachineGuid).MachineGuid)
 $node=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($nodeBytes)).ToLowerInvariant()
 if($Action -eq 'Init'){
  if(Test-Path -LiteralPath $rootPath){throw 'Store already exists; no automatic repair'}
  $parent=[IO.Path]::GetDirectoryName($rootPath);if(-not (Test-Path -LiteralPath $parent)){throw 'Store parent must already exist'}
  $acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
  $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
  [IO.FileSystemAclExtensions]::CreateDirectory($acl,$rootPath) | Out-Null
  Private-Acl $rootPath
  $policy=[ordered]@{version=1;storeId=[guid]::NewGuid().ToString('N');ownerSid=$sid.Value;nodeId=$node}
  Write-New (Join-Path $rootPath 'store.json') ($policy | ConvertTo-Json -Compress)
 }
 Private-Acl $rootPath;Private-Acl (Join-Path $rootPath 'store.json')
 $policyText=[IO.File]::ReadAllText((Join-Path $rootPath 'store.json'));$policy=$policyText | ConvertFrom-Json
 if($policy.version -ne 1 -or $policy.storeId -cnotmatch '^[a-f0-9]{32}$' -or $policy.ownerSid -cne $sid.Value -or $policy.nodeId -cne $node){throw 'Store policy identity mismatch'}
 if($Action -in @('Init','Validate')){[Console]::Write($policyText);exit 0}
 if($Action -eq 'Mutex'){
  if($Name -cnotmatch '^[a-f0-9]{64}$'){throw 'Invalid mutex name'}
  $mutex=[Threading.Mutex]::new($false,('Global\3DLCheckoutV2-'+$Name));$held=$false
  try{try{$held=$mutex.WaitOne(0)}catch [Threading.AbandonedMutexException]{$held=$true};if(-not $held){throw 'Destination executor already active'}
   [Console]::WriteLine('LOCKED');[Console]::Out.Flush();[Console]::In.ReadLine() | Out-Null
  }finally{if($held){$mutex.ReleaseMutex()};$mutex.Dispose()};exit 0
 }
 if($Name -cnotmatch '^(plan|approval|used|start|success|failure|verified)-[a-f0-9]{64}\.json$'){throw 'Invalid record name'}
 $file=Join-Path $rootPath $Name
 if($Action -eq 'Exists'){if(Test-Path -LiteralPath $file){Private-Acl $file;[Console]::Write('true')}else{[Console]::Write('false')};exit 0}
 if($Action -eq 'Read'){Private-Acl $file;[Console]::Write([IO.File]::ReadAllText($file));exit 0}
 if($Action -eq 'Write'){$text=[Console]::In.ReadToEnd();if($text.Length -gt 65536){throw 'Oversized store record'};Write-New $file $text;[Console]::Write('CREATED');exit 0}
}catch{[Console]::Error.WriteLine('Protected NTFS store operation refused');exit 1}
