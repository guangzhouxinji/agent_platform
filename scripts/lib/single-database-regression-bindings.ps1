# Pure scope helper. No credentials are loaded here; final Action is invoked in this process.
function Invoke-SingleDatabaseRegressionBindings {
 param([Parameter(Mandatory)]$Bindings,[Parameter(Mandatory)][scriptblock]$Action)
 $restore=@{}
 foreach($p in $Bindings.PSObject.Properties){$restore[$p.Name]=[Environment]::GetEnvironmentVariable($p.Name,'Process')}
 try {
  foreach($p in $Bindings.PSObject.Properties){[Environment]::SetEnvironmentVariable($p.Name,[string]$p.Value,'Process')}
  & $Action
 } finally {foreach($key in $restore.Keys){[Environment]::SetEnvironmentVariable($key,$restore[$key],'Process')}}
}
