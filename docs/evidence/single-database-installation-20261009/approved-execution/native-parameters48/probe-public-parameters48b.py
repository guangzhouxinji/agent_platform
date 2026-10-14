from pathlib import Path
import subprocess,json,datetime
here=Path(__file__).parent;target='f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f'
records=[]
for length in [8,9,32,44,48,49,64]:
 key=('Aa1'*22)[:length]
 args=['docker','exec',target,'/usr/bin/timeout','--kill-after=2s','10s','/opt/dmdbms/bin/dexp','PARAMETER_PROBE/NotARealPassword123@127.0.0.1:1','SCHEMAS=(PARAMETER_PROBE)','DIRECTORY=/tmp','FILE=parameter-probe48b-'+str(length)+'.dmp','ENCRYPT=Y','ENCRYPT_PASSWORD='+key,'NOLOGFILE=Y','DROP=N','PARALLEL=1','TABLE_PARALLEL=1','FILESIZE=128M','FILENUM=1']
 run=subprocess.run(args,input=b'',stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
 if len(run.stdout)+len(run.stderr)>16384:raise RuntimeError('BOUNDED_PUBLIC_PROBE_OUTPUT_REQUIRED')
 (here/('synthetic-key48b-'+str(length)+'.stdout')).write_bytes(run.stdout);(here/('synthetic-key48b-'+str(length)+'.stderr')).write_bytes(run.stderr)
 text=run.stdout.decode('gb18030','replace');row={'syntheticKeyLength':length,'exitCode':run.returncode,'outputBytes':len(run.stdout),'capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'realCredentialUsed':False,'endpoint':'127.0.0.1:1','productIdentityCreated':False};records.append(row);print(json.dumps(row),text[:400],flush=True)
 if run.returncode in [0,124,137]:raise RuntimeError('UNEXPECTED_PROBE_RESULT_NO_CONTINUATION')
(here/'public-parameter-probe48b.json').write_text(json.dumps({'records':records,'sourceDatabaseCredentialUsed':False,'businessExportClaimed':False},indent=2)+'\n',encoding='utf-8')
