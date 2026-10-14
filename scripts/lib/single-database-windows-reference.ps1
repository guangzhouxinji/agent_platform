# Bind the approved logical reference to the same physical original; never copy a private file.
function Get-FinalHandleWindowsPath([string]$Path,[bool]$Directory) {
    if(-not $IsWindows){throw 'FINAL_WINDOWS_HANDLE_REQUIRED'}
    if(-not ('FinalWindowsReferenceHandle' -as [type])) {
        Add-Type -TypeDefinition @"
using System;using System.Text;using System.Runtime.InteropServices;using Microsoft.Win32.SafeHandles;
public static class FinalWindowsReferenceHandle {
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]
 static extern SafeFileHandle CreateFile(string p,uint access,uint share,IntPtr sa,uint creation,uint flags,IntPtr template);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]
 static extern uint GetFinalPathNameByHandle(SafeFileHandle h,StringBuilder p,uint size,uint flags);
 public static string Resolve(string path,bool directory) {
  using(var h=CreateFile(path,directory?0u:0x80000000u,directory?7u:1u,IntPtr.Zero,3,directory?0x02000000u:0u,IntPtr.Zero)) {
   if(h.IsInvalid)throw new InvalidOperationException("FINAL_REFERENCE_HANDLE_UNAVAILABLE");
   var b=new StringBuilder(32768);uint n=GetFinalPathNameByHandle(h,b,(uint)b.Capacity,0);
   if(n==0||n>=b.Capacity)throw new InvalidOperationException("FINAL_REFERENCE_HANDLE_UNAVAILABLE");
   return b.ToString();
  }
 }
}
"@
    }
    try {$resolved=[FinalWindowsReferenceHandle]::Resolve($Path,$Directory)}
    catch {throw 'FINAL_REFERENCE_HANDLE_UNAVAILABLE'}
    if(-not $resolved.StartsWith('\\?\',[StringComparison]::Ordinal)){throw 'FINAL_REFERENCE_PHYSICAL_PATH_REQUIRED'}
    return Get-FinalWindowsReferencePath ($resolved.Substring(4))
}
function Assert-FinalPhysicalReferencePaths([string]$Logical,[string]$Root,[string]$Physical,[string]$PhysicalRoot) {
    $Logical=Get-FinalWindowsReferencePath $Logical;$Root=Get-FinalWindowsReferencePath $Root
    $Physical=Get-FinalWindowsReferencePath $Physical;$PhysicalRoot=Get-FinalWindowsReferencePath $PhysicalRoot
    if(-not $Logical.StartsWith($Root+'/',[StringComparison]::Ordinal)){throw 'FINAL_REFERENCE_PHYSICAL_SCOPE_REQUIRED'}
    if($PhysicalRoot -cne $Root) {
        $local=Get-FinalWindowsReferencePath ([Environment]::GetFolderPath('LocalApplicationData'))
        $prefix=$local+'/AgentPlatform/'
        if(-not $Root.StartsWith($prefix,[StringComparison]::Ordinal)){throw 'FINAL_REFERENCE_PHYSICAL_SCOPE_REQUIRED'}
        # This exact package/root equivalence is evidence-backed in technical D 6.16.1, not arbitrary relocation.
        $expected=$local+'/Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/AgentPlatform/'+$Root.Substring($prefix.Length)
        if($PhysicalRoot -cne $expected){throw 'FINAL_REFERENCE_PHYSICAL_SCOPE_REQUIRED'}
    }
    if($Physical -cne $PhysicalRoot+$Logical.Substring($Root.Length)){throw 'FINAL_REFERENCE_PHYSICAL_SCOPE_REQUIRED'}
}
function Get-FinalPhysicalWindowsReference($Reference,[string]$SecretRoot) {
    Assert-FinalFile $Reference
    $logical=Get-FinalWindowsReferencePath ([string]$Reference.path)
    $root=Get-FinalWindowsReferencePath $SecretRoot
    if(-not $logical.StartsWith($root+'/',[StringComparison]::Ordinal)){throw 'FINAL_REFERENCE_PHYSICAL_SCOPE_REQUIRED'}
    $physical=Get-FinalHandleWindowsPath $logical $false
    $relative=$logical.Substring($root.Length)
    if(-not $physical.EndsWith($relative,[StringComparison]::Ordinal)){throw 'FINAL_REFERENCE_PHYSICAL_SCOPE_REQUIRED'}
    $physicalRoot=$physical.Substring(0,$physical.Length-$relative.Length)
    Assert-FinalPhysicalReferencePaths $logical $root $physical $physicalRoot
    # Logical directories may already exist as shadow directories; verify the approved physical root itself.
    if((Get-FinalHandleWindowsPath $physicalRoot $true) -cne $physicalRoot){throw 'FINAL_REFERENCE_PHYSICAL_SCOPE_REQUIRED'}
    Assert-FinalFile @{path=$physical;sha256=$Reference.sha256}
    return $physical
}