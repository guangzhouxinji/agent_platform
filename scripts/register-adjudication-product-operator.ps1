param()
$ErrorActionPreference='Stop';$workspace=Split-Path -Parent (Split-Path -Parent $PSCommandPath);$e=Join-Path $workspace 'docs/evidence/adjudication-llm-materials-20261004/subject-initialization-01'
. (Join-Path $PSScriptRoot 'lib/adjudication-product-http.ps1')
if(Test-Path -LiteralPath "$root/operator-registration.json"){throw 'Preserve registered subject; controlled continuation required'}
try {
 $kid=AdjSend $maintenance 'POST' '/v2/identity/keys/rotate' $null $null $null 200
 $registered=AdjSend $maintenance 'POST' '/v2/identity/subjects' @{loginName='adj-case-operator-20261004'} $null $null 200
 if(-not $registered.subjectId -or -not $registered.oneTimeActivation){throw 'Current normal registration did not issue a one-time activation'}
 $password=AdjSecret "$root/operator.credential.xml"
 AdjSend $member 'POST' "/v2/identity/subjects/$($registered.subjectId)/activate" @{activation=$registered.oneTimeActivation;password=$password} $null $null 200|Out-Null
 @{subjectId=$registered.subjectId;loginName='adj-case-operator-20261004';organization='project-adjudication';nature='PROJECT_OWNER_DELEGATED_PRODUCT_OPERATION_NOT_REAL_PERSON_ID_VERIFICATION';professionalReviewPermission=$false}|ConvertTo-Json|Set-Content "$root/operator-registration.json" -Encoding utf8
 AdjSend $maintenance 'POST' '/v2/identity/clients' @{clientId='adjudication-case-workspace';name='裁决案件工作台';redirectUri="$base/app/adjudication"} 'adj-client-register-20261004-01' $null 200|Out-Null
 $token=AdjLogin 'adj-case-operator-20261004' $password
 $grants=@();foreach($action in @('CREATE','READ','WRITE','MODEL_EXECUTE')){
  $scope=@{issuer=$base;subject=$registered.subjectId;organization='project-adjudication';domain='ADJUDICATION';taskScope='adjudication-materials-case-01';dataScope='adjudication-materials-case-01-materials';resourceType='ADJ_CASE';resourceId='adjudication-materials-case-01';resourceVersion=1;action=$action;purpose='CASE_AUXILIARY'}
  $receipt=AdjSend $maintenance 'POST' '/v2/adjudication/grants' @{scope=$scope;expiresAt='2026-11-01T16:00:00Z';expectedEpoch=0} "adj-grant-$action-20261004-01" $null 200
  $grants+=@{action=$action;scope=$scope;receipt=$receipt}
 }
 $grants|ConvertTo-Json -Depth 12|Set-Content "$root/case-grants.json" -Encoding utf8
 @{subjectId=$registered.subjectId;issuer=$base;organization='project-adjudication';actualTlsMaintenance=$true;normalRegistrationAndOneTimeActivation=$true;normalOAuthPkce=$true;grants=$grants;humanReviewGranted=$false;modelCallSent=$false}|ConvertTo-Json -Depth 12|Set-Content "$e/current-subject-permission.json" -Encoding utf8
 $trace|ConvertTo-Json -Depth 8|Set-Content "$e/subject-maintenance-http.json" -Encoding utf8
 Write-Output 'NORMAL_ADJ_SUBJECT_PKCE_AND_EXACT_CASE_GRANTS_COMPLETED_NO_MODEL_CALL'
}finally{$token=$null;$password=$null;$registered=$null;$maintenance.Dispose();$member.Dispose()}
