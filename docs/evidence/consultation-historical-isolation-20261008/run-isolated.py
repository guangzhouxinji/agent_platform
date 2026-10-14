"""Independent javac/Jupiter, no Maven/shared target writes; only own evidence outputs."""
from pathlib import Path
import json, subprocess, hashlib, sys, shutil
root=Path(__file__).resolve().parents[3]; evidence=Path(__file__).resolve().parent
attempt=sys.argv[1] if len(sys.argv)>1 else 'attempt-01'
output=evidence/attempt
if output.exists(): raise SystemExit('Never overwrite prior attempts')
output.mkdir(); classes=output/'classes'; classes.mkdir()
old=(root/'docs/evidence/consultation-historical-isolation-20261007/integration-test-repair-01/javac.args').read_text(encoding='utf-8-sig').splitlines()
cp=old[old.index('-classpath')+1].strip('"')
# Only jars + frozen dependency build; no old product isolation/test classes can shadow current source.
paths=[x for x in cp.split(';') if x.lower().endswith('.jar') or '/build-05/classes' in x.replace('\\','/')]
cp=';'.join([classes.as_posix()]+paths)
source_roots=sorted({str(p.parent) for p in (root/'backend').rglob('src/main/java')})
# rglob returns the java directory itself; preserve all module roots, not src/main.
source_roots=sorted(str(p) for p in (root/'backend').rglob('java') if str(p).replace('\\','/').endswith('/src/main/java'))
package=root/'backend/apps/platform-api/src/test/java/cn/xinji/agentplatform/security/product'
args=['--release','21','-parameters','-proc:none','-encoding','UTF-8','-Xprefer:source','-verbose',
      '-classpath',cp,'-sourcepath',';'.join(source_roots),'-d',classes.as_posix(),
      (package/'ProductHistoricalResultIsolationJdbcTest.java').as_posix(),
      (package/'ProductHistoricalIndirectConsumptionTest.java').as_posix()]
shutil.copy2(package/'ProductHistoricalIndirectConsumptionTest.java',output/'ProductHistoricalIndirectConsumptionTest.java')
argfile=output/'javac.args'; argfile.write_text('\n'.join('"'+x.replace('\\','/')+'"' for x in args),encoding='utf-8')
(output/'classpath-reference.txt').write_text(cp,encoding='utf-8')
jdk=Path('D:/software/jdk-21/bin')
with (output/'javac.log').open('w',encoding='utf-8') as log:
    compiled=subprocess.run([str(jdk/'javac.exe'),'@'+str(argfile)],cwd=root,stdout=log,stderr=subprocess.STDOUT)
print('compile exit',compiled.returncode,flush=True)
result={'compileExit':compiled.returncode,'classCount':len(list(classes.rglob('*.class'))),'testClass':'ProductHistoricalIndirectConsumptionTest','fullSuiteRun':False,'sharedBuildOutputWritten':False}
if compiled.returncode==0:
    (output/'tmp').mkdir()
    javaargs=['-Djava.io.tmpdir='+str(output/'tmp'),'-Disolation.indirect.evidence.dir='+str(output/'scenario-evidence'),'-classpath',cp,'cn.xinji.agentplatform.security.product.ProductHistoricalIndirectConsumptionTest']
    jfile=output/'java.args'; jfile.write_text('\n'.join('"'+x.replace('\\','/')+'"' for x in javaargs),encoding='utf-8')
    with (output/'junit.log').open('w',encoding='utf-8') as log:
        tested=subprocess.run([str(jdk/'java.exe'),'@'+str(jfile)],cwd=root,stdout=log,stderr=subprocess.STDOUT)
    result['testExit']=tested.returncode
    print('test exit',tested.returncode,flush=True)
(output/'execution-receipt.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
sys.exit(result.get('testExit',compiled.returncode))
