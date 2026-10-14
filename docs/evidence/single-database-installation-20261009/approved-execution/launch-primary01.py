import json,subprocess,sys,time
request=json.loads(sys.stdin.readline())
with open(sys.argv[1],'xb',buffering=0) as out:
    p=subprocess.Popen(['docker','start','-ai','agent-platform-single-final-primary-20261009'],stdin=subprocess.PIPE,stdout=out,stderr=subprocess.STDOUT)
    p.stdin.write((json.dumps(request)+'\n').encode());p.stdin.flush();p.stdin.close()
    del request
    time.sleep(3)
    print(json.dumps({'attachedClientPid':p.pid,'clientExit':p.poll(),'credentialValuesPrinted':False}))
