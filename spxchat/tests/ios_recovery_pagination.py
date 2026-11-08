"""Isolated API checks. Requires PHP CLI and local root MySQL socket; never uses production DB."""
import concurrent.futures, signal, json, os, pathlib, secrets, shutil, socket, subprocess, tempfile, time, urllib.request, urllib.error
root=pathlib.Path(__file__).resolve().parents[1]
base=pathlib.Path(tempfile.mkdtemp(prefix='spx-ios-api-'))
database='spx_ios_test_'+secrets.token_hex(6)
api=None
checks=[]
def php(code,args=()):
    p=subprocess.run(['php','-r',code,*args],capture_output=True,text=True,check=True)
    return p.stdout
connect="$p=new PDO('mysql:unix_socket=/var/run/mysqld/mysqld.sock;charset=utf8mb4','root','',[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC]);"
def sql(q):
    return php(connect+'$p->exec("USE '+database+'");$s=$p->query($argv[1]);echo json_encode($s->columnCount()?$s->fetchAll():[]);',[q])
def check(x,label):
    assert x,label
    checks.append(label)
def request(action,token=None,body=None,status=200):
    h={'Accept-Language':'zh-CN'}
    if token:h['Authorization']='Bearer '+token
    data=None
    if body is not None:h['Content-Type']='application/json';data=json.dumps(body).encode()
    req=urllib.request.Request(url+'?action='+action,headers=h,data=data)
    try:r=urllib.request.urlopen(req,timeout=60)
    except urllib.error.HTTPError as e:r=e
    raw=r.read().decode();assert r.status in (status if isinstance(status,tuple) else (status,)),(action,r.status,raw[:500])
    return json.loads(raw)['data']
try:
    php(connect+'$p->exec("CREATE DATABASE '+database+' CHARACTER SET utf8mb4");')
    for f in root.iterdir():
        if f.is_file() and f.suffix in ('.php','.sql') and not f.name.startswith('config'):shutil.copy2(f,base/f.name)
    shutil.copytree(root/'i18n',base/'i18n')
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    url='http://127.0.0.1:'+str(port)+'/api.php'
    config="<?php return ['db'=>['host'=>'localhost','port'=>3306,'name'=>'"+database+"','user'=>'root','password'=>'','charset'=>'utf8mb4'],'app'=>['debug'=>true,'public_base_url'=>'http://127.0.0.1:"+str(port)+"','token_ttl'=>86400],'mall'=>['private_media_dir'=>'"+str(base/'private')+"']];"
    (base/'config.php').write_text(config)
    log=(base/'test.log').open('w')
    api=subprocess.Popen(['php','-S','127.0.0.1:'+str(port),'-t',str(base)],stdout=log,stderr=log,env=dict(os.environ,PHP_CLI_SERVER_WORKERS='4'),start_new_session=True)
    time.sleep(1)
    request('health')
    tokens=[secrets.token_hex(32),secrets.token_hex(32)]
    for i in range(2):
        uid=i+10001
        sql("INSERT INTO users(id,username,nickname,spx_id,password_hash,bsc_wallet_address) VALUES ("+str(uid)+",'test"+str(uid)+"','Test','test"+str(uid)+"','unusable','0x"+str(uid).zfill(40)+"')")
        sql("INSERT INTO user_tokens(user_id,token,expires_at) VALUES ("+str(uid)+",'"+tokens[i]+"',DATE_ADD(NOW(),INTERVAL 1 DAY))")
        sql("INSERT INTO wallet_assets(user_id,symbol,chain,balance,stakeable_balance) VALUES ("+str(uid)+",'SPX001','BSC',1000000,1000000)")
    body={'symbol':'SPX001','chain':'BSC','amount':'100','address':'0x'+str(10001).zfill(40),'client_request_id':'withdraw-test-001'}
    one=request('wallet.withdraw',tokens[0],body,201)
    two=request('wallet.withdraw',tokens[0],body,200)
    check(one['transaction_id']==two['transaction_id'],'withdraw replay returns original transaction')
    check(len(json.loads(sql("SELECT id FROM wallet_transactions WHERE user_id=10001 AND type='withdraw'")))==1,'replay creates exactly one withdrawal')
    check(float(json.loads(sql('SELECT balance FROM wallet_assets WHERE user_id=10001'))[0]['balance'])==999900,'replay debits exactly once')
    check(request('financial.request&operation=wallet.withdraw&client_request_id=withdraw-test-001',tokens[0])['exists'],'request lookup finds committed result')
    check(not request('financial.request&operation=wallet.withdraw&client_request_id=withdraw-test-001',tokens[1])['exists'],'request lookup isolated by account')
    request('wallet.withdraw',tokens[0],dict(body,amount='200'),409)
    check(True,'same ID cannot change amount')
    check(request('financial.resolve',tokens[0],dict(body,operation='wallet.withdraw'))['transaction_id']==one['transaction_id'],'resolution preserves committed result')
    cancelled=dict(body,client_request_id='withdraw-cancelled')
    check(request('financial.resolve',tokens[0],dict(cancelled,operation='wallet.withdraw'))['cancelled'],'resolution closes uncommitted request')
    check(request('wallet.withdraw',tokens[0],cancelled,200)['cancelled'],'delayed original cannot debit after resolution')
    bad=dict(body,client_request_id='withdraw-insufficient',amount='9999999')
    request('wallet.withdraw',tokens[0],bad,409)
    check(not request('financial.request&operation=wallet.withdraw&client_request_id=withdraw-insufficient',tokens[0])['exists'],'failed debit rolls back reservation')
    check(request('financial.resolve',tokens[0],dict(bad,operation='wallet.withdraw'))['cancelled'],'rejected original can be safely closed')
    # Legacy contract: no ID still follows existing pending-withdrawal protection.
    legacy={k:v for k,v in body.items() if k!='client_request_id'}
    request('wallet.withdraw',tokens[0],legacy,409)
    check(True,'legacy Android withdrawal contract retained')

    stake={'plan_id':json.loads(sql('SELECT id FROM staking_plans WHERE status=1 LIMIT 1'))[0]['id'],'amount':'100000','client_request_id':'staking-repeat-001'}
    s1=request('staking.create',tokens[1],stake,201);s2=request('staking.create',tokens[1],stake)
    check(s1['id']==s2['id'],'staking replay preserves original record')
    check(len(json.loads(sql('SELECT id FROM staking_records WHERE user_id=10002')))==1,'staking retries create exactly one record')
    check(float(json.loads(sql('SELECT balance FROM wallet_assets WHERE user_id=10002'))[0]['balance'])==900000,'staking debit occurs exactly once')
    sc=dict(stake,client_request_id='staking-cancel-001')
    check(request('financial.resolve',tokens[1],dict(sc,operation='staking.create'))['cancelled'],'staking unresolved request closes safely')
    check(request('staking.create',tokens[1],sc)['cancelled'],'delayed staking request cannot debit')
    concurrent_body=dict(body,address='0x'+str(10002).zfill(40),client_request_id='withdraw-concurrent')
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        results=list(executor.map(lambda _:request('wallet.withdraw',tokens[1],concurrent_body,(200,201)),range(2)))
    check(results[0]['transaction_id']==results[1]['transaction_id'],'concurrent withdrawal retries share one result')
    check(float(json.loads(sql('SELECT balance FROM wallet_assets WHERE user_id=10002'))[0]['balance'])==899900,'concurrent retries debit only once')
    # Resolver races against an original transaction; either original wins or cancellation wins.
    race=dict(stake,client_request_id='staking-resolve-race')
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        start=executor.submit(request,'staking.create',tokens[1],race,(200,201))
        resolve=executor.submit(request,'financial.resolve',tokens[1],dict(race,operation='staking.create'))
        original,resolved=start.result(),resolve.result()
    check(original==resolved,'resolution serializes against in-flight debit')
    expected=899900 if resolved.get('cancelled') else 799900
    check(float(json.loads(sql('SELECT balance FROM wallet_assets WHERE user_id=10002'))[0]['balance'])==expected,'resolve race final balance matches one durable result')

    for n in range(65):
        sql("INSERT INTO wallet_transactions(user_id,symbol,chain,type,amount,status) VALUES (10001,'USDT','BSC','deposit',1,'confirmed')")
    p1=request('wallet.transactions&symbol=USDT&page=1&page_size=30',tokens[0]);p2=request('wallet.transactions&symbol=USDT&page=2&page_size=30',tokens[0]);p3=request('wallet.transactions&symbol=USDT&page=3&page_size=30',tokens[0])
    check([len(p1),len(p2),len(p3)]==[30,30,5],'wallet pages include older records')
    check(not ({x['id'] for x in p1}&{x['id'] for x in p2}),'wallet pages do not overlap')
    check(all(x['symbol']=='USDT' for x in p1+p2+p3),'wallet symbol filtering on server')
    check(request('wallet.transactions&symbol=USDT&page=1&page_size=30',tokens[1])==[],'wallet pages isolated by account')
    request('wallet.transactions&symbol=INVALID',tokens[0],status=422)
    check(True,'invalid wallet filters rejected')
    # Favoriting an older product must not depend on the first sixty rows.
    for n in range(65):
        sql("INSERT INTO products(name,description,spx_price,stock,status) VALUES ('fixture"+str(n)+"','test',1,1,1)")
        sql("INSERT INTO mall_favorites(user_id,product_id) SELECT 10001,id FROM products WHERE name='fixture"+str(n)+"'")
    pid=json.loads(sql("SELECT id FROM products WHERE name='fixture0'"))[0]['id']
    check(request('product.favorite.status&product_id='+str(pid),tokens[0])['enabled'],'older favorite independent status correct')
    check(not request('product.favorite.status&product_id='+str(pid),tokens[1])['enabled'],'favorite status isolated by account')
    fp=[request('favorites&page='+str(n)+'&page_size=30',tokens[0]) for n in [1,2,3]]
    check([len(x) for x in fp]==[30,30,5],'favorites pages expose more than sixty')
    request('redpackets.mine&filter=sent&page=1&page_size=30',tokens[0])
    request('redpackets.mine&filter=received&page=1&page_size=30',tokens[0])
    request('redpackets.mine&filter=INVALID',tokens[0],status=422)
    check(True,'red packet filters accepted and invalid filter rejected')
    print(json.dumps({'passed':len(checks),'checks':checks},ensure_ascii=False))
finally:
    if api:os.killpg(api.pid,signal.SIGTERM);api.wait(timeout=10)
    php(connect+'$p->exec("DROP DATABASE IF EXISTS '+database+'");')
    shutil.rmtree(base)
