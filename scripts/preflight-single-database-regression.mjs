import {resolve} from 'node:path';
import {readFile} from 'node:fs/promises';
import {preflightRegressionLaunch,assertRegressionEnvironment} from './lib/single-database-regression-launch.mjs';
try {
 const [mode,file]=process.argv.slice(2);
 if(mode==='--environment') {
  const c=JSON.parse(await readFile(resolve('deployment/single-database/regression-launch-contract.json'),'utf8'));
  console.log(JSON.stringify(assertRegressionEnvironment(c,process.env)));
 } else if(mode==='--request'&&file)console.log(JSON.stringify(await preflightRegressionLaunch(resolve('.'),resolve(file))));
 else throw new Error('CURRENT_REQUEST_REQUIRED_NO_BARE_MAVEN_LAUNCH');
} catch(e) { console.error(JSON.stringify({ready:false,code:/^[A-Z0-9_]+$/.test(e.message)?e.message:'REGRESSION_REQUEST_OR_METADATA_UNAVAILABLE'}));process.exitCode=1; }
