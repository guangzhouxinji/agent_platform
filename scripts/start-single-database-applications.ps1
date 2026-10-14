param([Parameter(Mandatory)][string]$Configuration,[switch]$ValidateOnly,[string]$ResumeCreatedContainer,[string]$RecoverFailedApplication,[string]$ResumeFailedContainer,[switch]$PrepareEvidenceDirectory,[switch]$PublishLocalTls,[string]$StopLocalTls)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/single-database-final-deployment.ps1')
try {
    $config=Read-FinalDeploymentJson $Configuration
    if($PublishLocalTls -or $StopLocalTls){
        if($ResumeCreatedContainer -or $RecoverFailedApplication -or $ResumeFailedContainer -or $PrepareEvidenceDirectory -or ($PublishLocalTls -and $StopLocalTls) -or ($StopLocalTls -and $ValidateOnly)){throw 'FINAL_RECOVERY_MODE_CONFLICT'}
        if($config.schema -ceq 'SINGLE_DATABASE_ENF_LOCAL_TLS_PUBLICATION/v1'){. (Join-Path $PSScriptRoot 'lib/single-database-enforcement-tls-publish44.ps1')}elseif($config.schema -ceq 'SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v3'){. (Join-Path $PSScriptRoot 'lib/single-database-adjudication-tls-publish42.ps1')}elseif($config.schema -ceq 'SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v2'){. (Join-Path $PSScriptRoot 'lib/single-database-adjudication-tls-publish41.ps1')}elseif($config.schema -ceq 'SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v1'){. (Join-Path $PSScriptRoot 'lib/single-database-adjudication-tls-publish.ps1')}else{. (Join-Path $PSScriptRoot 'lib/single-database-local-tls-publish.ps1')}
        $sha=(Get-FileHash -LiteralPath $Configuration -Algorithm SHA256).Hash.ToLowerInvariant()
        if($StopLocalTls){if($config.schema -ceq 'SINGLE_DATABASE_ENF_LOCAL_TLS_PUBLICATION/v1'){Stop-FinalEnf44LocalTlsPublisher $config $sha $StopLocalTls | ConvertTo-Json -Depth 12}elseif($config.schema -ceq 'SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v3'){Stop-FinalAdj42LocalTlsPublisher $config $sha $StopLocalTls | ConvertTo-Json -Depth 12}elseif($config.schema -ceq 'SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v2'){Stop-FinalAdj41LocalTlsPublisher $config $sha $StopLocalTls | ConvertTo-Json -Depth 12}elseif($config.schema -ceq 'SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v1'){Stop-FinalAdjLocalTlsPublisher $config $sha $StopLocalTls | ConvertTo-Json -Depth 12}else{Stop-FinalLocalTlsPublisher $config $sha $StopLocalTls | ConvertTo-Json -Depth 12}}
        else{if($config.schema -ceq 'SINGLE_DATABASE_ENF_LOCAL_TLS_PUBLICATION/v1'){Invoke-FinalEnf44LocalTlsPublisher $config $sha -ValidateOnly:$ValidateOnly | ConvertTo-Json -Depth 12}elseif($config.schema -ceq 'SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v3'){Invoke-FinalAdj42LocalTlsPublisher $config $sha -ValidateOnly:$ValidateOnly | ConvertTo-Json -Depth 12}elseif($config.schema -ceq 'SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v2'){Invoke-FinalAdj41LocalTlsPublisher $config $sha -ValidateOnly:$ValidateOnly | ConvertTo-Json -Depth 12}elseif($config.schema -ceq 'SINGLE_DATABASE_ADJ_LOCAL_TLS_PUBLICATION/v1'){Invoke-FinalAdjLocalTlsPublisher $config $sha -ValidateOnly:$ValidateOnly | ConvertTo-Json -Depth 12}else{Invoke-FinalLocalTlsPublisher $config $sha -ValidateOnly:$ValidateOnly | ConvertTo-Json -Depth 12}}
    }
    elseif($PrepareEvidenceDirectory -or $ResumeFailedContainer){
        if($ValidateOnly -or $ResumeCreatedContainer -or $RecoverFailedApplication -or ($PrepareEvidenceDirectory -and $ResumeFailedContainer)){throw 'FINAL_RECOVERY_MODE_CONFLICT'}
        . (Join-Path $PSScriptRoot 'lib/single-database-failed-recovery.ps1')
        if($PrepareEvidenceDirectory){Initialize-FinalConsultationEvidenceDirectory $config | ConvertTo-Json -Depth 12}
        else{Resume-FinalFailedApplication $config $ResumeFailedContainer | ConvertTo-Json -Depth 12}
    }
    elseif($RecoverFailedApplication){
        if($ValidateOnly -or $ResumeCreatedContainer){throw 'FINAL_RECOVERY_MODE_CONFLICT'}
        . (Join-Path $PSScriptRoot 'lib/single-database-failed-recovery.ps1')
        Invoke-FinalFailedApplicationRecovery $config (Read-FinalDeploymentJson $RecoverFailedApplication) | ConvertTo-Json -Depth 12
    }
    elseif($ResumeCreatedContainer -and -not $ValidateOnly){Resume-FinalCreatedApplication -Config $config -Id $ResumeCreatedContainer | ConvertTo-Json -Depth 12}
    else {Invoke-FinalApplications -Config $config -ValidateOnly:$ValidateOnly | ConvertTo-Json -Depth 12}
} catch {
    Write-Error 'FINAL_APPLICATION_DEPLOYMENT_REFUSED; inspect approved references and preserved resources' -ErrorAction Continue
    exit 1
}


