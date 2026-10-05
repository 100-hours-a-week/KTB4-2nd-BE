"""Private-stack correctness/failure experiments, never capacity evidence."""
import hashlib
import csv
import http.cookiejar
import time
from concurrent.futures import ThreadPoolExecutor
import run

FAULTS=['multi-device','ttl','redis-outage','mysql-outage','redis-restart','redis-memory','csrf']

def execute(args,session,case,inputs,containers,strict=True):
    import capacity
    started=time.monotonic()
    def counters():
        stages={}
        with (case/'server-requests.csv').open() as csvfile:
            for row in csv.DictReader(csvfile):
                if row['phase']!='fault':continue
                key=row['stage'];stages[key]=stages.get(key,0)+int(row['calls'])
        return stages
    before_counts=counters()
    f=inputs[0];base=session['base'];env=session['env'];kind=args.fault;observations=[]
    def request(path='/users/me',method='GET',second=False):
        cookie=f['second_cookie'] if second else f['cookie']
        token=f['second_refresh_token'] if second else f['refresh_token']
        if method=='POST':cookie+='; refreshToken='+token
        request_started=time.monotonic()
        status=capacity.http_request(base,path,f,http.cookiejar.CookieJar(),method,cookie,timeout=45,phase='fault')[0]
        observations.append(dict(path=path,method=method,status=status,elapsed_seconds=time.monotonic()-request_started))
        return status
    def redis(*commands):return run.command(['docker','exec',containers['redis'],'redis-cli',*map(str,commands)]).strip()
    result=dict(fault=kind,variant=args.variant,capacity_evidence=False,redis_persistence=args.redis_persistence)
    if kind=='multi-device':
        def rotate(second):
            jar=http.cookiejar.CookieJar()
            cookie=f['second_cookie'] if second else f['cookie']
            token=f['second_refresh_token'] if second else f['refresh_token']
            status,_=capacity.http_request(base,'/auth/token/refresh',f,jar,'POST',cookie+'; refreshToken='+token,timeout=45,phase='fault')
            return status,{c.name:c.value for c in jar}
        with ThreadPoolExecutor(max_workers=2) as pool:rotated=list(pool.map(rotate,[False,True]))
        statuses=[status for status,_ in rotated];access=[];refresh=[];old=[]
        for second,(status,cookies) in enumerate(rotated):
            if status!=200:continue
            context=f['cookie'].split('CSRF_CONTEXT=')[1].split(';')[0]
            new_cookie='accessToken='+cookies['accessToken']+'; CSRF_CONTEXT='+context
            access.append(capacity.http_request(base,'/users/me',f,http.cookiejar.CookieJar(),cookie=new_cookie,timeout=45,phase='fault')[0])
            refresh.append(capacity.http_request(base,'/auth/token/refresh',f,http.cookiejar.CookieJar(),'POST',new_cookie+'; refreshToken='+cookies['refreshToken'],timeout=45,phase='fault')[0])
            old.append(request('/auth/token/refresh','POST',bool(second)))
        result.update(statuses=statuses,new_access=access,new_refresh=refresh,old_refresh=old,
                      passed=statuses==[200,200] and access==[200,200] and refresh==[200,200] and old==[401,401])
    elif kind=='csrf':
        if args.variant != 'B2':
            raise ValueError('CSRF Redis fault requires B2')
        context=f['cookie'].split('CSRF_CONTEXT=')[1].split(';')[0]
        key='yeodam:performance:authperf:capacity:csrf:'+hashlib.sha256(context.encode()).hexdigest()
        path='/trips/'+str(f['trip_id'])+'/favorite'
        before=int(redis('PTTL',key))
        valid=request(path,'POST')
        after=int(redis('PTTL',key))
        invalid=capacity.http_request(base,path,dict(f,csrf='invalid'),http.cookiejar.CookieJar(),'POST',f['cookie'],phase='fault')[0]
        missing=capacity.http_request(base,path,dict(f,csrf=''),http.cookiejar.CookieJar(),'POST',f['cookie'],phase='fault')[0]
        redis('PEXPIRE',key,1000)
        time.sleep(1.2)
        expired_exists=int(redis('EXISTS',key))
        expired=request(path,'POST')
        result.update(valid_status=valid,invalid_status=invalid,missing_status=missing,
                      ttl_before_ms=before,ttl_after_read_ms=after,expired_key_exists=expired_exists,
                      expired_status=expired,passed=valid==200 and invalid==403 and missing==403
                      and 0<after<=before and expired_exists==0 and expired==403)
    elif kind=='ttl':
        if args.variant!='A':
            prefix='yeodam:performance:authperf:capacity:'
            deadline=int(redis('TIME').splitlines()[0])*1000+3000
            for sid,token in [(f['sid'],f['refresh_token']),(f['second_sid'],f['second_refresh_token'])]:
                redis('HSET',prefix+'session:'+sid,'expiresAt',deadline)
                redis('PEXPIRE',prefix+'session:'+sid,3000)
                redis('PEXPIRE',prefix+'refresh:'+hashlib.sha256(token.encode()).hexdigest(),3000)
            redis('PEXPIRE',prefix+'user-sessions:'+str(f['user_id']),3000)
        else:
            sql="update login_sessions set expires_at=date_add(now(),interval 3 second) where user_id="+str(int(f['user_id']))
            run.command(['docker','exec',containers['mysql'],'mysql','-uroot','-proot','auth_performance','-e',sql])
        time.sleep(4)
        def rdb_rows():
            sql='select count(*) from login_sessions where user_id='+str(int(f['user_id']))
            return int(run.command(['docker','exec',containers['mysql'],'mysql','-uroot','-proot','-N','auth_performance','-e',sql]).strip())
        if args.variant=='A':result['rows_before_expired_lookup']=rdb_rows()
        statuses=[request(),request('/auth/token/refresh','POST')]
        result.update(statuses=statuses,passed=statuses==[401,401])
        if args.variant!='A':
            result['remaining_session_keys']=int(redis('EXISTS',prefix+'session:'+f['sid'],prefix+'session:'+f['second_sid']))
            result['remaining_index_keys']=int(redis('EXISTS',prefix+'refresh:'+hashlib.sha256(f['refresh_token'].encode()).hexdigest(),prefix+'user-sessions:'+str(f['user_id'])))
            result['passed']=result['passed'] and result['remaining_session_keys']==0 and result['remaining_index_keys']==0
        else:
            result['rows_after_expired_lookup']=rdb_rows()
            result['note']='Physical rows before/after expired lookup; no background cleanup assumption'
    elif kind=='redis-memory':
        previous=redis('CONFIG','GET','maxmemory').splitlines()[-1]
        try:
            redis('CONFIG','SET','maxmemory',1)
            statuses=[request(),request('/auth/token/refresh','POST')]
            result.update(statuses=statuses,passed=statuses==[200,503],scope='Artificial lowered limit; not normal working-set capacity')
        finally:redis('CONFIG','SET','maxmemory',previous)
        result['old_token_after_restore']=request('/auth/token/refresh','POST')
        result['passed']=result['passed'] and result['old_token_after_restore']==200
    elif kind=='redis-outage':
        run.command(['docker','pause',containers['redis']])
        try:
            statuses=[request(),request('/auth/token/refresh','POST')]
            result.update(statuses=statuses,passed=statuses==[503,503])
        finally:run.command(['docker','unpause',containers['redis']])
        result['recovery_status']=request()
        result['passed']=result['passed'] and result['recovery_status']==200
    elif kind=='mysql-outage':
        capacity.compose(env,'stop','-t','1','auth-mysql')
        try:
            statuses=[request(),request('/auth/token/refresh','POST')]
            result.update(statuses=statuses,passed=statuses==[503,503])
        finally:capacity.compose(env,'up','-d','--wait','auth-mysql')
        result['recovery_status']=request()
        result['passed']=result['passed'] and result['recovery_status']==200
    elif kind=='redis-restart':
        old_cookie=f['cookie'];old_token=f['refresh_token']
        jar=http.cookiejar.CookieJar()
        status,_=capacity.http_request(base,'/auth/token/refresh',f,jar,'POST',old_cookie+'; refreshToken='+old_token,timeout=45,phase='fault')
        if status!=200:raise RuntimeError('Pre-restart rotation failed')
        cookies={c.name:c.value for c in jar}
        context=old_cookie.split('CSRF_CONTEXT=')[1].split(';')[0]
        f=dict(f,cookie='accessToken='+cookies['accessToken']+'; CSRF_CONTEXT='+context,refresh_token=cookies['refreshToken'])
        logged_out=inputs[1]
        result['logout_before_restart']=capacity.http_request(base,'/auth/logout',logged_out,http.cookiejar.CookieJar(),'POST',logged_out['cookie'],timeout=45,phase='fault')[0]
        if result['logout_before_restart']!=204:raise RuntimeError('Pre-restart logout failed')
        before=int(redis('DBSIZE'))
        result['csrf_key_before_restart']=int(redis('EXISTS','yeodam:performance:authperf:capacity:csrf:'+hashlib.sha256(context.encode()).hexdigest()))
        capacity.compose(env,'restart','auth-redis')
        deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            try:
                if redis('PING')=='PONG':break
            except Exception:time.sleep(.2)
        result['csrf_key_after_restart_before_requests']=int(redis('EXISTS','yeodam:performance:authperf:capacity:csrf:'+hashlib.sha256(context.encode()).hexdigest()))
        statuses=[request(),request('/auth/token/refresh','POST')]
        expected=[200,200] if args.redis_persistence=='aof' else [401,403] if args.variant=='B2' else [401,401]
        result['logged_out_session_after_restart']=capacity.http_request(base,'/users/me',logged_out,http.cookiejar.CookieJar(),cookie=logged_out['cookie'],timeout=45,phase='fault')[0]
        result['old_token_after_restart']=capacity.http_request(base,'/auth/token/refresh',f,http.cookiejar.CookieJar(),'POST',old_cookie+'; refreshToken='+old_token,timeout=45,phase='fault')[0]
        result.update(before_keys=before,after_keys=int(redis('DBSIZE')),statuses=statuses,passed=statuses==expected,
                      scope='Graceful restart only; not crash durability or HA')
        result['passed']=result['passed'] and result['logged_out_session_after_restart']==401 and result['old_token_after_restart']==(403 if args.variant=='B2' and args.redis_persistence=='off' else 401)
    time.sleep(.3)
    after_counts=counters()
    result['server_stage_calls']={key:value-before_counts.get(key,0) for key,value in after_counts.items()}
    result['elapsed_seconds']=time.monotonic()-started
    result['observations']=observations
    run.save(case/('fault-'+kind+'.json'),result)
    if strict and not result['passed']:raise RuntimeError('Fault expectation failed; inspect fault-'+kind+'.json')
    return result
