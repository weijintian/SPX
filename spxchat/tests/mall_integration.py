"""Exercise the real PHP API against a disposable MariaDB instance. No production config/data."""
import json
import http.cookiejar
import os
import pathlib
import secrets
import re
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import urllib.parse

source = pathlib.Path(__file__).resolve().parents[1]
temp = pathlib.Path(tempfile.mkdtemp(prefix="spx-mall-test-"))
php = pathlib.Path(os.environ.get('SPX_TEST_PHP', str(pathlib.Path(tempfile.gettempdir()) / "spx-mall-validation-php" / "php.exe")))
bin_dir = pathlib.Path(os.environ.get('SPX_TEST_MARIADB_BIN', str(pathlib.Path(tempfile.gettempdir()) / "spx-mall-validation-mariadb" / "mariadb-11.4.5-winx64" / "bin")))


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


db_port, api_port = port(), port()
app = temp / "app"
app.mkdir()
for entry in source.iterdir():
    if entry.suffix in (".php", ".js", ".css") or entry.name.endswith(".sql"):
        if entry.name.startswith("config"):
            continue
        shutil.copy2(entry, app / entry.name)
shutil.copytree(source / "i18n", app / "i18n")
shutil.copytree(source / "spxchat-systemadmin", app / "spxchat-systemadmin")
config = "<?php return " + "['db'=>['host'=>'127.0.0.1','port'=>" + str(db_port) + ", 'name'=>'spx_mall_test','charset'=>'utf8mb4','user'=>'root','password'=>''], 'app'=>['debug'=>true,'public_base_url'=>'http://127.0.0.1:" + str(api_port) + "','token_ttl'=>86400,'websocket_event_host'=>'127.0.0.1','websocket_event_port'=>"+str(port())+"]];"
config = config.replace(";", "", 1)[:-1] + ", 'mall'=>['private_media_dir'=>'" + (temp / "private-media").as_posix() + "']];"
(app / "config.php").write_text(config, encoding="utf-8")
# Isolated fixture only: app is a fresh copy under tempfile.mkdtemp(), and
# production config files are excluded above. No API key or network is needed.
price_cache = temp / "mall-test-prices.json"
def market_price(value):
    price_cache.write_text(json.dumps({"cached_at":int(time.time()),"snapshot":{"symbol":"SPX001","current_price_usd":value,"updated_at":int(time.time())}}),encoding="utf-8")
market_price("1")
(app / "config.ave.php").write_text("<?php return ['api_key'=>'isolated-test-placeholder','cache_file'=>'"+price_cache.as_posix()+"','cache_ttl'=>86400];",encoding="utf-8")
data_dir = temp / "database"
subprocess.run([str(bin_dir / "mariadb-install-db.exe"), "--datadir=" + str(data_dir), "--port=" + str(db_port)], check=True, stdout=subprocess.DEVNULL)
db_log = (temp / "database.log").open("w", encoding="utf-8")
server = subprocess.Popen([str(bin_dir / "mariadbd.exe"), "--datadir=" + str(data_dir), "--port=" + str(db_port), "--bind-address=127.0.0.1", "--console"], stdout=db_log, stderr=subprocess.STDOUT)
php_args = [str(php), "-n", "-d", "max_execution_time=120", "-d", "extension_dir=" + str(php.parent / "ext"), "-d", "extension=pdo_mysql", "-d", "extension=mbstring", "-d", "extension=fileinfo", "-d", "extension=curl"]
sql_fixture = temp / 'fixture-query.php'
sql_fixture.write_text("<?php $c=require '"+(app/'config.php').as_posix()+"'; $d=$c['db']; $p=new PDO('mysql:host=127.0.0.1;port='.$d['port'].';dbname=spx_mall_test;charset=utf8mb4','root','',[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]); $sql=json_decode(stream_get_contents(STDIN),true,512,JSON_THROW_ON_ERROR); if(preg_match('/^\\s*(SELECT|SHOW)\\b/i',$sql)){$q=$p->query($sql);echo json_encode($q->fetchAll(PDO::FETCH_NUM),JSON_THROW_ON_ERROR|JSON_UNESCAPED_UNICODE);}else{$p->exec($sql);echo '[]';}",encoding='utf-8')
api = None
checks = 0
quote_cache = {}
admin_browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
admin_url = "http://127.0.0.1:" + str(api_port) + "/spxchat-systemadmin/"
admin_csrf = None


def wait_port(number):
    for _ in range(100):
        try:
            with socket.create_connection(("127.0.0.1", number), timeout=0.2):
                return
        except OSError:
            time.sleep(0.1)
    raise RuntimeError("Local test service did not start: " + str(number))


def request(action, token=None, body=None, status=200):
    if action == 'orders.redeem' and body is not None and 'quote_id' not in body:
        if status >= 400:
            return request('checkout.quote', token, body, 422)
        key = body.get('client_request_id')
        if key and key in quote_cache:
            quote_id = quote_cache[key]
        else:
            quote_id = request('checkout.quote', token, body)['quote_id']
            if key: quote_cache[key] = quote_id
        body = {'quote_id':quote_id,'client_request_id':key or secrets.token_hex(16)}
    headers = {"Accept-Language": "zh-CN"}
    if token:
        headers["Authorization"] = "Bearer " + token
    payload = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body).encode()
    req = urllib.request.Request("http://127.0.0.1:" + str(api_port) + "/api.php?action=" + action, data=payload, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=150 if action == 'health' else 30)
    except urllib.error.HTTPError as error:
        response = error
    text = response.read().decode()
    if response.code != status:
        raise AssertionError(action + " expected " + str(status) + ", got " + str(response.code) + ": " + text)
    return json.loads(text)["data"]


def sql(statement, raw=False):
    if os.environ.get('SPX_TEST_SQL_PDO') == '1':
        output = subprocess.run(php_args + [str(sql_fixture)], input=json.dumps(statement), capture_output=True, encoding='utf-8', check=True)
        rows=json.loads(output.stdout)
        def value(cell):
            text='NULL' if cell is None else str(cell)
            return text if raw else text.replace('\\','\\\\').replace('\t','\\t').replace('\n','\\n').replace('\r','\\r')
        return '\n'.join('\t'.join(value(cell) for cell in row) for row in rows).strip()
    output = subprocess.run([str(bin_dir / "mariadb.exe"), "--host=127.0.0.1", "--port=" + str(db_port), "--user=root", "--skip-ssl", "--batch", "--skip-column-names"] + (["--raw"] if raw else []) + ["--execute=" + statement, "spx_mall_test"], capture_output=True, encoding="utf-8")
    if output.returncode:
        raise RuntimeError(output.stderr)
    return output.stdout.strip()


def check(condition, label):
    global checks
    assert condition, label
    checks += 1
    print("PASS " + label, flush=True)


def admin_post(action, **fields):
    body = dict(fields, action=action, csrf=admin_csrf, section="merchants", reason="Isolated integration test")
    return admin_browser.open(admin_url + "admin.php", urllib.parse.urlencode(body).encode(), timeout=15).read().decode()


def upload(content,mime,status,action="merchant.media.upload",auth=None):
    boundary="spx"+secrets.token_hex(10)
    raw=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.png\"\r\nContent-Type: "+mime+"\r\n\r\n").encode()+content+("\r\n--"+boundary+"--\r\n").encode()
    req=urllib.request.Request("http://127.0.0.1:"+str(api_port)+"/api.php?action="+action,data=raw,headers={"Authorization":"Bearer "+(seller if auth is None else auth),"Content-Type":"multipart/form-data; boundary="+boundary})
    try: res=urllib.request.urlopen(req,timeout=15)
    except urllib.error.HTTPError as e: res=e
    result=res.read().decode();assert res.code==status,result
    return json.loads(result)["data"]

def image_response(url,auth=None,browser=None):
    headers={"Authorization":"Bearer "+auth} if auth else {}
    req=urllib.request.Request(url,headers=headers)
    try: res=(browser or urllib.request).open(req,timeout=15) if browser else urllib.request.urlopen(req,timeout=15)
    except urllib.error.HTTPError as e: res=e
    return res.code,res.headers,res.read()

try:
    wait_port(db_port)
    php_log = (temp / "php.log").open("w", encoding="utf-8")
    api_env = dict(os.environ)
    api_env['SPX_APNS_ENABLED'] = '0'  # Never enqueue or send production push notifications from fixtures.
    if '--calls-only' in __import__('sys').argv:
        # Test-only credentials. Never read the local production Tencent key.
        api_env['TENCENT_CALL_SDK_APP_ID'] = '1400000001'
        api_env['TENCENT_CALL_SDK_SECRET_KEY'] = 'isolated-call-test-key-0123456789abcdef'
    api = subprocess.Popen(php_args + ["-S", "127.0.0.1:" + str(api_port), "-t", str(app)], stdout=php_log, stderr=subprocess.STDOUT, env=api_env)
    wait_port(api_port)
    check(request("health")["service"] == "spxchat", "fresh schema installs")
    if '--account-groups-only' in __import__('sys').argv:
        import account_group_scenarios
        account_group_scenarios.run(globals())
        print("SUCCESS " + str(checks) + " isolated account and group checks", flush=True)
        raise SystemExit(0)
    if '--calls-only' in __import__('sys').argv:
        import call_scenarios
        call_scenarios.run(globals())
        print("SUCCESS " + str(checks) + " isolated call checks", flush=True)
        raise SystemExit(0)
    if '--red-packets-only' in __import__('sys').argv:
        import red_packet_scenarios
        red_packet_scenarios.run(globals())
        print("SUCCESS " + str(checks) + " isolated red packet checks", flush=True)
        raise SystemExit(0)
    if '--chat-only' in __import__('sys').argv:
        import chat_scenarios
        chat_scenarios.run(globals())
        print("SUCCESS " + str(checks) + " isolated chat checks", flush=True)
        raise SystemExit(0)
    sql("INSERT INTO users (id,username,nickname,spx_id,password_hash) VALUES (1,'seller','Seller','spx100001','test'),(2,'buyer','Buyer','spx100002','test'),(3,'outsider','Outsider','spx100003','test');")
    tokens = [secrets.token_hex(32) for _ in range(3)]
    for uid, token in enumerate(tokens, 1):
        sql("INSERT INTO user_tokens (user_id,token,expires_at) VALUES (" + str(uid) + ",'" + token + "',DATE_ADD(NOW(),INTERVAL 1 DAY)); INSERT INTO wallet_assets (user_id,symbol,chain,balance,stakeable_balance) VALUES (" + str(uid) + ",'SPX001','BSC',2000000,1500000);")
    seller, buyer, outsider = tokens
    admin_password = "Test_" + secrets.token_hex(10)
    password_hash = subprocess.run(php_args + ["-r", "echo password_hash($argv[1], PASSWORD_DEFAULT);", admin_password], check=True, capture_output=True, encoding="utf-8").stdout
    sql("INSERT INTO admin_users (id,username,password_hash,role) VALUES (1,'mall_test_admin','" + password_hash + "','superadmin')")
    login_html = admin_browser.open(admin_url + "login.php").read().decode()
    login_csrf = re.search(r'name="csrf" value="([a-f0-9]+)"', login_html).group(1)
    admin_browser.open(admin_url + "login.php", urllib.parse.urlencode({"csrf": login_csrf, "username": "mall_test_admin", "password": admin_password}).encode()).read()
    dashboard = admin_browser.open(admin_url + "admin.php?section=profile").read().decode()
    admin_csrf = re.search(r'name="csrf" value="([a-f0-9]+)"', dashboard).group(1)
    profile = {"name": "Test Shop", "category": "Other", "contact_name": "Seller", "contact_phone": "123456", "level": "basic", "address": "Test street", "latitude": "39.9", "longitude": "116.4", "coordinate_system": "gcj02"}
    import base64
    valid_png=base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j1ioAAAAASUVORK5CYII=")
    upload(valid_png,"image/png",401,action="merchant.logo.upload",auth="")
    logo=upload(valid_png,"image/png",200,action="merchant.logo.upload")["url"]
    doc=upload(valid_png,"image/png",200,action="merchant.qualification.upload")["id"]
    check(logo.endswith(".png") and doc.startswith("1_"),"shop photos can be uploaded before merchant approval")
    upload(b"<?php echo 1;","image/png",422,action="merchant.qualification.upload")
    check((temp/"private-media/qualifications"/doc).is_file() and not (app/"uploads"/doc).exists(),"qualification stored outside public web root; disguised script rejected")
    photo_url="http://127.0.0.1:"+str(api_port)+"/api.php?action=merchant.qualification.image&id="+doc
    code,headers,raw=image_response(photo_url,seller)
    check(code==200 and raw==valid_png and "no-store" in headers.get("Cache-Control",""),"owner can preview private qualification without persistent caching")
    check(image_response(photo_url,outsider)[0]==404 and image_response(photo_url)[0]==401,"qualification rejects other accounts and anonymous access")
    request("merchant.apply",buyer,dict(profile,qualification_images=[doc]),422)
    check(sql("SELECT COUNT(*) FROM merchants")=="0","applicant cannot attach another user's qualification")
    profile.update(logo_url=logo,qualification_images=[doc])
    request("merchant.apply", seller, profile)
    shop = request("merchant.me", seller)["merchant"]
    check(shop["status"] == "pending", "application awaits review")
    check(shop["qualification_images"]==[doc] and shop["logo_url"]==logo,"submitted photos retained for owner and review")
    review=admin_browser.open(admin_url+"admin.php?section=merchants").read().decode()
    check("qualification_image="+doc in review,"admin review shows submitted qualification thumbnail")
    admin_photo=admin_url+"admin.php?merchant_id="+str(shop["id"])+"&qualification_image="+doc
    check(image_response(admin_photo,browser=admin_browser)[2]==valid_png,"authenticated administrator can inspect qualification image")
    check(image_response(admin_url+"admin.php?merchant_id=999&qualification_image="+doc,browser=admin_browser)[0]==404,"admin image endpoint validates merchant membership")
    request("merchant.deposit.pay", seller, {"level": "basic"}, 422)
    check(sql("SELECT balance FROM wallet_assets WHERE user_id=1 AND symbol='SPX001'") == "2000000.000000000000000000", "unreviewed shop cannot debit deposit")
    admin_post("mall.merchant.review", merchant_id=1, decision="approve")
    check(sql("SELECT status FROM merchants WHERE id=1") == "awaiting_deposit", "admin review unlocks deposit stage")
    request('merchant.product.save',seller,{'name':'Unfunded product','description':'Must not publish','cny_price':'1','stock':1,'images':['https://example.com/p.png'],'consultation_group_code':'ABC123'},422)
    check(sql('SELECT COUNT(*) FROM products WHERE merchant_id=1')=='0','approved merchant cannot publish before funding deposit')
    check(upload(valid_png,'image/png',200)['url'].endswith('.png'),'approved merchant can prepare draft photos before funding')
    request("merchant.deposit.pay", seller, {"level": "basic"})
    request("merchant.deposit.pay", seller, {"level": "basic"})
    check(sql("SELECT COUNT(*) FROM merchant_deposit_ledger") == "1", "deposit retry is idempotent")
    request("merchant.deposit.pay", seller, {"level": "gold"})
    check(sql("SELECT balance FROM wallet_assets WHERE user_id=1 AND symbol='SPX001'") == "1000000.000000000000000000", "gold upgrade charges only 900000 difference")
    check(sql("SELECT deposit_stakeable FROM merchants WHERE id=1") == "500000.000000000000000000", "deposit preserves stakeable source split")
    request("merchant.deposit.pay", seller, {"level": "basic"}, 422)
    check(len(request("shops&level=gold")) == 1, "funded gold shop is public")
    check("qualification_images" not in request("shops&level=gold")[0] and "qualification_images" not in request("shop&id=1")["shop"],"public shop endpoints do not disclose qualification ids")
    request("merchant.profile.save",seller,dict(profile,qualification_images=[]),422)
    request("merchant.profile.save",seller,profile)
    check(request("merchant.me",seller)["merchant"]["qualification_images"]==[doc],"approved qualification cannot be replaced without review")
    check(sql("SELECT GROUP_CONCAT(version ORDER BY version) FROM mall_schema_versions")=="1,2,3,4,5,6,7,8","merchant media, workspace, CNY, settlement and capacity settings migrations installed once")
    warm_script=temp/"warm-schema.php"
    warm_script.write_text("<?php $cfg=require "+repr(str(app/"config.php").replace("\\","/"))+"; $d=$cfg['db']; $p=new PDO('mysql:host=127.0.0.1;port='+$d['port']+';dbname=spx_mall_test','root','');",encoding="utf-8")
    warm_script.write_text("<?php $cfg=require '"+(app/"config.php").as_posix()+"'; $d=$cfg['db']; $p=new PDO('mysql:host=127.0.0.1;port=' . $d['port'] . ';dbname=spx_mall_test','root',''); require '"+(app/"db.php").as_posix()+"'; $before=$p->query(\"SHOW SESSION STATUS WHERE Variable_name IN ('Com_alter_table','Com_create_table')\")->fetchAll(PDO::FETCH_KEY_PAIR); installSchema($p); $after=$p->query(\"SHOW SESSION STATUS WHERE Variable_name IN ('Com_alter_table','Com_create_table')\")->fetchAll(PDO::FETCH_KEY_PAIR); echo json_encode([$before,$after]);",encoding="utf-8")
    counters=json.loads(subprocess.check_output(php_args+[str(warm_script)],text=True))
    check(counters[0]==counters[1],"warm schema initialization performs zero CREATE or ALTER operations")
    sql("INSERT INTO chat_groups(id,owner_user_id,name,group_code) VALUES (1,1,'Product consultation','ABC123'),(2,3,'Foreign group','XYZ987'); INSERT INTO chat_group_members(group_id,user_id,role) VALUES (1,1,'owner'),(2,3,'owner');")
    check(request('merchant.groups',seller)[0]['group_code']=='ABC123','seller can select managed consultation groups')
    product = {"consultation_group_code":"ABC123", "name": "Test item", "description": "Detail", "category": "Other", "cny_price": "67", "stock": 10, "product_type": "physical", "images": ["https://example.com/product.jpg"], "detail_images": [], "variants": [{"id": "red", "name": "Red", "cny_price": "67", "stock": 5}, {"id": "blue", "name": "Blue", "cny_price": "80.4", "stock": 5}]}
    request('merchant.product.save',seller,dict(product,consultation_group_code=''),422)
    request('merchant.product.save',seller,dict(product,consultation_group_code='XYZ987'),422)
    check(sql('SELECT COUNT(*) FROM products WHERE merchant_id=1')=='0','publishing requires a merchant-managed consultation group')
    pid = request("merchant.product.save", seller, product)["id"]
    check(request('product&id='+str(pid))['consultation_group']['group_code']=='ABC123','product detail returns verified consultation group')
    request('groups.join',buyer,{'group_code':'ABC123'},201)
    request('groups.join',buyer,{'group_code':'ABC123'})
    check(sql('SELECT COUNT(*) FROM chat_group_members WHERE group_id=1 AND user_id=2')=='1','buyer joins consultation group once')
    draft=request('merchant.draft.save',seller,{'payload':dict(product,name='Draft test')})
    request('merchant.draft.save',seller,{'id':draft['id'],'version':0,'payload':product},422)
    check(request('merchant.draft&id='+str(draft['id']),seller)['payload']['name']=='Draft test','draft saving retains product data and prevents stale overwrite')
    request('merchant.draft.delete',seller,{'id':draft['id']})
    check(request('merchant.performance',seller)['orders_30_days']==0,'quality ranking query works on fresh store')
    merchant_browser=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    merchant_url='http://127.0.0.1:'+str(api_port)+'/merchant.php'
    login_html=merchant_browser.open(merchant_url).read().decode()
    merchant_csrf=re.search(r'name="csrf" value="([a-f0-9]+)"',login_html).group(1)
    sql("UPDATE users SET password_hash='"+password_hash+"' WHERE id IN (1,3)")
    bad_login=merchant_browser.open(merchant_url,urllib.parse.urlencode({'csrf':merchant_csrf,'username':'seller','password':'invalid'}).encode()).read().decode()
    check('merchant.js' not in bad_login,'merchant portal rejects incorrect password')
    portal=merchant_browser.open(merchant_url,urllib.parse.urlencode({'csrf':merchant_csrf,'username':'seller','password':admin_password}).encode()).read().decode()
    merchant_csrf=re.search(r'name="csrf-token" content="([a-f0-9]+)"',portal).group(1)
    if 'merchant.js' not in portal: (temp/'merchant-login-failure.html').write_text(portal,encoding='utf-8')
    check('merchant.js' in portal and seller not in portal,'merchant portal authenticates app account without exposing API token')
    def portal_api(action,body=None,csrf_value=None,browser=None):
        data=json.dumps(body).encode() if body is not None else None
        headers={'Content-Type':'application/json','X-CSRF-Token':csrf_value or ''}
        req=urllib.request.Request('http://127.0.0.1:'+str(api_port)+'/merchant-api.php?action='+action,data=data,headers=headers)
        try:r=(browser or merchant_browser).open(req,timeout=15)
        except urllib.error.HTTPError as e:r=e
        return r.code,json.loads(r.read().decode())
    check(portal_api('merchant.products')[0]==200,'merchant portal reads own products through protected session')
    seller_email='shop.'+'x'*55+'@example.com'
    sql("UPDATE users SET email='"+seller_email+"' WHERE id=1")
    def email_portal_login(identifier, password, csrf_ok=True):
        browser=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        html=browser.open(merchant_url).read().decode()
        csrf=re.search(r'name="csrf" value="([a-f0-9]+)"',html).group(1)
        html=browser.open(merchant_url,urllib.parse.urlencode({'csrf':csrf if csrf_ok else 'invalid','username':identifier,'password':password}).encode()).read().decode()
        return browser,html
    email_browser,email_html=email_portal_login('  '+seller_email.upper()+'  ',admin_password)
    check('merchant.js' in email_html,'merchant portal accepts trimmed case-insensitive email longer than 64 characters')
    code,email_shop=portal_api('merchant.me',browser=email_browser)
    check(code==200 and email_shop['data']['merchant']['id']==shop['id'],'email login resolves the same existing shop as app username login')
    check('Bearer ' not in email_html and password_hash not in email_html,'email login exposes neither API bearer token nor password hash')
    check('merchant.js' not in email_portal_login(seller_email,'invalid')[1],'email login rejects wrong password')
    check('merchant.js' not in email_portal_login(seller_email,admin_password,False)[1],'email login still requires valid CSRF')
    check('merchant.js' not in email_portal_login('missing@example.com',admin_password)[1],'unknown email cannot log in')
    sql('UPDATE users SET status=0 WHERE id=1')
    check('merchant.js' not in email_portal_login(seller_email,admin_password)[1],'disabled account cannot log in by email')
    check(portal_api('merchant.me',browser=email_browser)[0]==401,'disabling account also invalidates existing email session')
    sql('UPDATE users SET status=1 WHERE id=1')
    # The email sign-in revokes the earlier token under single-device login.
    # Re-authenticate before testing CSRF, rather than testing an expired session.
    merchant_browser,portal=email_portal_login('seller',admin_password)
    merchant_csrf=re.search(r'name="csrf-token" content="([a-f0-9]+)"',portal).group(1)
    check(portal_api('merchant.product.offline',{'id':pid})[0]==403 and portal_api('mall.order.refund',{'order_id':1},merchant_csrf)[0]==403,'merchant portal enforces CSRF and denies platform admin actions')
    saved=portal_api('merchant.draft.save',{'payload':product},merchant_csrf)
    check(saved[0]==200,'merchant portal writes with valid CSRF token')
    portal_api('merchant.draft.delete',{'id':saved[1]['data']['id']},merchant_csrf)
    alien_browser=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    alien_html=alien_browser.open(merchant_url).read().decode()
    alien_csrf=re.search(r'name="csrf" value="([a-f0-9]+)"',alien_html).group(1)
    alien_html=alien_browser.open(merchant_url,urllib.parse.urlencode({'csrf':alien_csrf,'username':'outsider','password':admin_password}).encode()).read().decode()
    alien_csrf=re.search(r'name="csrf-token" content="([a-f0-9]+)"',alien_html).group(1)
    check(portal_api('merchant.product.save',dict(product,id=pid),alien_csrf,alien_browser)[0]==422,'merchant portal prevents editing another merchant product')
    sql("UPDATE users SET password_hash='changed' WHERE id=3")
    check(portal_api('merchant.products',browser=alien_browser)[0]==401,'password changes invalidate merchant browser session')
    if os.environ.get('SPX_BROWSER_CHECK')=='1':
        runtime=pathlib.Path(os.environ['USERPROFILE'])/'.cache/codex-runtimes/codex-primary-runtime/dependencies/node'
        root=source.parent
        browser_env=dict(os.environ);browser_env['NODE_PATH']=str(runtime/'node_modules')
        browser_check=subprocess.run([str(runtime/'bin/node.exe'),str(root/'spxchat/tests/merchant_browser_check.cjs')],input=json.dumps({'url':merchant_url,'password':admin_password,'output':str(root/'.spx-release-build')}),env=browser_env,text=True,encoding='utf-8',capture_output=True,timeout=100)
        print(browser_check.stdout,flush=True)
        if browser_check.returncode: raise AssertionError(browser_check.stderr)
        check(True,'merchant portal verified in real browser')
    # Resume App-side cases with fresh tokens after browser login revocations.
    sql("UPDATE users SET password_hash='"+password_hash+"' WHERE id=3")
    seller=request('auth.login',body={'username':'seller','password':admin_password})['token']
    outsider=request('auth.login',body={'username':'outsider','password':admin_password})['token']
    check(len(request("products")) == 1, "valid merchant product auto-approved")
    check(request("products")[0]["images"]==product["images"],"batched catalog query retains product image order")
    check(len(request("products")) == 1, "approved product visible")
    share_url = "http://127.0.0.1:" + str(api_port) + "/product-share.php?id=" + str(pid)
    share_status, share_headers, share_bytes = image_response(share_url)
    share_html = share_bytes.decode()
    check(share_status == 200 and 'spx://product?id=' + str(pid) in share_html and 'Test item' in share_html, "public share page links to exact product without login")
    check(doc not in share_html and "qualification_images" not in share_html and "private-media" not in share_html, "product sharing never exposes private merchant qualifications")
    sql("UPDATE products SET name='<script>alert(1)</script>',description='<img src=x onerror=alert(1)>' WHERE id=" + str(pid))
    escaped_html = image_response(share_url)[2].decode()
    check('<script>alert(1)</script>' not in escaped_html and '&lt;script&gt;' in escaped_html and '<img src=x onerror=alert(1)>' not in escaped_html, "share page escapes merchant HTML and script content")
    sql("UPDATE products SET name='Test item',description='Detail',status=0 WHERE id=" + str(pid))
    unavailable_status, _, unavailable_bytes = image_response(share_url)
    check(unavailable_status == 404 and 'spx://product' not in unavailable_bytes.decode(), "offline products cannot be opened through a public share")
    sql("UPDATE products SET status=1 WHERE id=" + str(pid))
    invalid_status, _, _ = image_response("http://127.0.0.1:" + str(api_port) + "/product-share.php?id=invalid")
    check(invalid_status == 404, "invalid product share identifiers return a clean unavailable page")
    request("merchant.product.save", outsider, dict(product, id=pid), 422)
    check(sql("SELECT name FROM products WHERE id=" + str(pid)) == "Test item", "other user cannot mutate shop product")
    sql("INSERT INTO user_addresses (id,user_id,name,phone,province,city,district,detail) VALUES (1,2,'Buyer','123','P','C','D','Street')")
    purchase = {"product_id": pid, "quantity": 1, "variant_id": "red", "address_id": 1, "client_request_id": secrets.token_hex(16)}
    order = request("orders.redeem", buyer, purchase, 201)["order_id"]
    check(request("orders.redeem", buyer, purchase, 201)["order_id"] == order, "purchase retry returns same order without another charge")
    check(sql("SELECT merchant_id FROM orders WHERE id=" + str(order)) == "1", "order retains merchant ownership")
    check(sql("SELECT balance FROM wallet_assets WHERE user_id=1 AND symbol='SPX001'") == "1000000.000000000000000000", "order payment remains held")
    request("merchant.order.ship", outsider, {"order_id": order, "carrier": "Test", "tracking_number": "123"}, 422)
    request("orders.cancel", buyer, {"order_id": order})
    request("orders.cancel", buyer, {"order_id": order}, 409)
    check(sql("SELECT stock FROM products WHERE id=" + str(pid)) == "10", "cancel restores SKU stock once")
    check(sql("SELECT settlement_status FROM orders WHERE id=" + str(order)) == "refunded", "cancel excludes order from settlement")
    order = request("orders.redeem", buyer, {"product_id": pid, "quantity": 1, "variant_id": "red", "address_id": 1}, 201)["order_id"]
    request("orders.after_sale", buyer, {"order_id": order, "reason": "Damaged"})
    request('merchant.order.reply',seller,{'order_id':order,'reply':'Please supply photos'})
    check(sql('SELECT merchant_service_reply IS NOT NULL AND service_replied_at IS NOT NULL FROM orders WHERE id='+str(order))=='1','merchant reply records response time')
    check(request('order&id='+str(order),buyer)['merchant_service_reply']=='Please supply photos','buyer receives merchant after-sale reply')
    check(float(request('merchant.performance',seller)['exposure_score'])<12,'complaints lower exposure even for gold store')
    request("merchant.order.ship", seller, {"order_id": order, "carrier": "Test", "tracking_number": "123"}, 422)
    check(sql("SELECT after_sale_status FROM orders WHERE id=" + str(order)) == "requested", "after-sale blocks fulfillment")
    admin_post("mall.order.after_sale.reject", order_id=order)
    request("merchant.order.ship", seller, {"order_id": order, "carrier": "Test", "tracking_number": "123"})
    request("orders.receive", buyer, {"order_id": order})
    request("orders.receive", buyer, {"order_id": order}, 422)
    check(sql("SELECT received_at IS NOT NULL FROM orders WHERE id=" + str(order)) == "1", "buyer completion starts after-sale window")
    admin_post("mall.order.settle", order_id=order)
    check(sql("SELECT settlement_status FROM orders WHERE id=" + str(order)) == "held", "settlement blocked during after-sale window")
    request("orders.after_sale", buyer, {"order_id": order, "reason": "Refund after delivery"})
    sql("UPDATE orders SET received_at=DATE_SUB(NOW(),INTERVAL 8 DAY) WHERE id=" + str(order))
    admin_post("mall.order.settle", order_id=order)
    check(sql("SELECT settlement_status FROM orders WHERE id=" + str(order)) == "held", "unresolved after-sale blocks settlement")
    admin_post("mall.order.refund", order_id=order)
    admin_post("mall.order.refund", order_id=order)
    check(sql("SELECT COUNT(*) FROM wallet_transactions WHERE user_id=2 AND type='refund'") == "2", "admin refund returns payment once")
    settled_order = request("orders.redeem", buyer, {"product_id": pid, "quantity": 1, "variant_id": "red", "address_id": 1}, 201)["order_id"]
    request("merchant.order.ship", seller, {"order_id": settled_order, "carrier": "Test", "tracking_number": "456"})
    request("orders.receive", buyer, {"order_id": settled_order})
    sql("UPDATE orders SET received_at=DATE_SUB(NOW(),INTERVAL 8 DAY) WHERE id=" + str(settled_order))
    sql("UPDATE admin_users SET role='operator' WHERE id=1")
    admin_post("mall.order.settle", order_id=settled_order)
    check(sql("SELECT settlement_status FROM orders WHERE id=" + str(settled_order)) == "held", "operator cannot settle funds")
    sql("UPDATE admin_users SET role='superadmin' WHERE id=1")
    admin_post("mall.order.settle", order_id=settled_order)
    admin_post("mall.order.settle", order_id=settled_order)
    check(sql("SELECT balance FROM wallet_assets WHERE user_id=1 AND symbol='SPX001'") == "1000009.000000000000000000", "finance settlement credits merchant net after 10 percent fee once")
    local = dict(product, name="Local item", product_type="local", variants=[], stock=5)
    lid = request("merchant.product.save", seller, local)["id"]
    local_order = request("orders.redeem", buyer, {"product_id": lid, "quantity": 1}, 201)["order_id"]
    seller_order = next(o for o in request("merchant.orders", seller) if int(o["id"]) == local_order)
    check("redeem_code" not in seller_order, "seller cannot retrieve buyer redemption code")
    request("merchant.order.redeem", seller, {"order_id": local_order, "redeem_code": "WRONG"}, 422)
    buyer_order = request("order&id=" + str(local_order), buyer)
    request("merchant.order.redeem", seller, {"order_id": local_order, "redeem_code": buyer_order["redeem_code"]})
    request("merchant.order.redeem", seller, {"order_id": local_order, "redeem_code": buyer_order["redeem_code"]}, 422)
    check(sql("SELECT status FROM orders WHERE id=" + str(local_order)) == "delivered", "local code redeems once")
    check(sql("SELECT attempts FROM api_rate_limits WHERE scope_key LIKE 'mall_redeem:1:%'") == "3", "failed redemption attempts remain rate limited after rollback")
    sql("UPDATE orders SET received_at=DATE_SUB(NOW(),INTERVAL 8 DAY) WHERE id=" + str(local_order))
    admin_post("mall.order.settle", order_id=local_order)

    # Financial and stale-edit regressions verified against live isolated endpoints.
    sql("UPDATE wallet_assets SET balance=100,stakeable_balance=100 WHERE user_id=2 AND symbol='SPX001'")
    r = request("orders.redeem",buyer,{"product_id":pid,"quantity":1,"variant_id":"red","address_id":1},201)["order_id"]
    request("orders.cancel",buyer,{"order_id":r})
    check(sql("SELECT balance=100 AND stakeable_balance=100 FROM wallet_assets WHERE user_id=2 AND symbol='SPX001'")=="1","refund restores original stakeable split")
    sql("UPDATE wallet_assets SET balance=2000000,stakeable_balance=1500000 WHERE user_id=2 AND symbol='SPX001'")
    stale=dict(product,name="Stale edit regression")
    sid=request("merchant.product.save",seller,stale)["id"]
    before=request("product&id="+str(sid))
    r=request("orders.redeem",buyer,{"product_id":sid,"quantity":1,"variant_id":"red","address_id":1},201)["order_id"]
    request("merchant.product.save",seller,dict(stale,id=sid,version=before["version"],description="Changed description"))
    check(sql("SELECT stock FROM products WHERE id="+str(sid))=="9","metadata edit preserves purchased stock")
    request("orders.cancel",buyer,{"order_id":r})
    check(sql("SELECT stock FROM products WHERE id="+str(sid))=="10","cancel restores exact original inventory")
    q=request("checkout.quote",buyer,{"product_id":sid,"quantity":1,"variant_id":"red","address_id":1})
    current=request("product&id="+str(sid))
    changed=dict(stale,id=sid,version=current["version"],variants=[dict(stale["variants"][0],cny_price="168"),stale["variants"][1]])
    request("merchant.product.save",seller,changed)
    money=sql("SELECT balance FROM wallet_assets WHERE user_id=2 AND symbol='SPX001'")
    request("orders.redeem",buyer,{"quote_id":q["quote_id"],"client_request_id":secrets.token_hex(16)},422)
    check(sql("SELECT balance FROM wallet_assets WHERE user_id=2 AND symbol='SPX001'")==money,"price changes blocked without charging buyer")
    q=request("checkout.quote",buyer,{"product_id":sid,"quantity":1,"variant_id":"blue","address_id":1})
    request("checkout.resolve",buyer,{"quote_id":q["quote_id"]})
    request("orders.redeem",buyer,{"quote_id":q["quote_id"],"client_request_id":secrets.token_hex(16)},422)
    check(sql("SELECT balance FROM wallet_assets WHERE user_id=2 AND symbol='SPX001'")==money,"payment recovery voids late unpaid submissions")
    current=request("product&id="+str(sid))
    q=request("checkout.quote",buyer,{"product_id":sid,"quantity":1,"variant_id":"blue","address_id":1})
    payload={"quote_id":q["quote_id"],"client_request_id":secrets.token_hex(16)}
    r=request("orders.redeem",buyer,payload,201)["order_id"]
    again=request("orders.redeem",buyer,dict(payload,client_request_id=secrets.token_hex(16)),201)["order_id"]
    check(r==again,"one quote cannot charge twice even with different request IDs")
    request("merchant.order.ship",seller,{"order_id":r,"carrier":"Test","tracking_number":"123"})
    request("orders.receive",buyer,{"order_id":r})
    current=request("product&id="+str(sid))
    request("merchant.product.save",seller,dict(changed,version=current["version"],variants=[changed["variants"][0]]))
    stock=sql("SELECT stock FROM products WHERE id="+str(sid))
    admin_post("mall.order.refund",order_id=r)
    check(sql("SELECT stock FROM products WHERE id="+str(sid))==stock,"delivered refund does not resurrect removed or unreturned stock")
    check(request("order&id="+str(r),buyer)["image_url"]=="https://example.com/product.jpg","order retains immutable product image")
    current=request("product&id="+str(sid))
    request("merchant.inventory.adjust",seller,{"product_id":sid,"variant_id":"red","inventory_version":current["inventory_version"],"delta":2,"reason":"Restock"})
    request("merchant.inventory.adjust",seller,{"product_id":sid,"variant_id":"red","inventory_version":current["inventory_version"],"delta":2,"reason":"Stale restock"},422)
    check(sql("SELECT stock FROM products WHERE id="+str(sid))==str(int(stock)+2),"inventory adjustments use version protection")
    request("merchant.product.save",seller,dict(stale,id=sid,version=1),422)
    request("merchant.product.save",seller,dict(stale,name="No image",images=[]),422)
    check(len(request("products&type=physical"))>=2 and request("products&type=digital")==[],"server filters product types across pages")
    check(len(request("orders&page_size=1",buyer))==1,"buyer orders are paginated")

    # Fixed CNY pricing and market changes, against the isolated cache above.
    from decimal import Decimal
    dynamic_id=request("merchant.product.save",seller,dict(product,name="Fixed CNY dynamic SPX",cny_price="168",variants=[],stock=3))["id"]
    market_price("0.001")
    q_low=request("checkout.quote",buyer,{"product_id":dynamic_id,"quantity":1,"address_id":1})
    check(Decimal(q_low["unit_price"])==Decimal("25074.62686567") and Decimal(q_low["price_cny"])==Decimal("168"),"168 CNY converts with fixed 6.7 USD/CNY")
    market_price("0.002")
    q_high=request("checkout.quote",buyer,{"product_id":dynamic_id,"quantity":1,"address_id":1})
    check(Decimal(q_high["unit_price"])==Decimal("12537.31343283") and q_high["pricing"]["usd_cny"]=="6.7","rising SPX price reduces quantity without changing CNY")
    before_cash=Decimal(sql("SELECT balance FROM wallet_assets WHERE user_id=2 AND symbol='SPX001'"))
    locked_id=request("orders.redeem",buyer,{"quote_id":q_low["quote_id"],"client_request_id":secrets.token_hex(16)},201)["order_id"]
    check(before_cash-Decimal(sql("SELECT balance FROM wallet_assets WHERE user_id=2 AND symbol='SPX001'"))==Decimal(q_low["total_spx"]),"market changes preserve confirmed quote debit")
    request("orders.cancel",buyer,{"order_id":locked_id})
    check(Decimal(sql("SELECT balance FROM wallet_assets WHERE user_id=2 AND symbol='SPX001'"))==before_cash,"refund returns paid tokens despite changed market")
    market_price("0")
    request("checkout.quote",buyer,{"product_id":dynamic_id,"quantity":1,"address_id":1},422)
    market_price("1")

    # Complete order lifecycle and boundary checks for commerce v2.
    from decimal import Decimal
    address = {"name":"Receiver", "phone":"13800138000", "province":"P", "city":"C", "district":"D", "detail":"Building 1", "is_default":True}
    address_id = request("wallet.address.save", buyer, address, 201)["id"]
    request("wallet.address.update", outsider, dict(address,id=address_id),422)
    request("wallet.address.delete", outsider, {"id":address_id},422)
    check(sql("SELECT COUNT(*) FROM user_addresses WHERE user_id=2 AND is_default=1")=="1", "address writes maintain one default and enforce ownership")
    q=request("checkout.quote",buyer,{"product_id":pid,"quantity":1,"variant_id":"red","address_id":address_id})
    request("wallet.address.update",buyer,dict(address,id=address_id,detail="Building 2"))
    request("orders.redeem",buyer,{"quote_id":q["quote_id"],"client_request_id":secrets.token_hex(16)},422)
    check(sql("SELECT order_id IS NULL FROM mall_checkout_quotes WHERE id='"+q["quote_id"]+"'")=="1","changed delivery address requires a fresh confirmation")
    second=request("wallet.address.save",buyer,dict(address,detail="Backup",is_default=False),201)["id"]
    request("wallet.address.delete",buyer,{"id":address_id})
    check(sql("SELECT COUNT(*) FROM user_addresses WHERE user_id=2 AND is_default=1")=="1","deleting default selects a remaining address")
    fee_product=dict(product,name="Shipping and return",stock=4,variants=[],cny_price="67",shipping_fee="2.00000001")
    fid=request("merchant.product.save",seller,fee_product)["id"]
    q=request("checkout.quote",buyer,{"product_id":fid,"quantity":3,"address_id":second,"buyer_note":"Leave at door"})
    check(Decimal(q["total_spx"])==Decimal(q["subtotal"])+Decimal(q["shipping_fee"]),"checkout uses exact decimal price times quantity plus per-order shipping")
    cash=Decimal(sql("SELECT balance FROM wallet_assets WHERE user_id=2 AND symbol='SPX001'"))
    r=request("orders.redeem",buyer,{"quote_id":q["quote_id"],"client_request_id":secrets.token_hex(16)},201)["order_id"]
    check(cash-Decimal(sql("SELECT balance FROM wallet_assets WHERE user_id=2 AND symbol='SPX001'"))==Decimal(q["total_spx"]),"wallet debit equals the confirmed quote exactly")
    request("merchant.order.ship",seller,{"order_id":r,"carrier":"Test Express","tracking_number":"OUT123"})
    request("orders.after_sale",buyer,{"order_id":r,"reason":"Return needed","type":"return_refund","images":["https://example.com/proof.jpg"]})
    request("orders.return",buyer,{"order_id":r,"carrier":"Return Express","tracking_number":"BACK123"},422)
    admin_post("mall.order.return.approve",order_id=r,return_address="Return desk 13800138000 Warehouse 1")
    o=request("order&id="+str(r),buyer)
    check(o["after_sale_status"]=="return_approved" and "Warehouse 1" in o["return_address"],"return approval supplies the buyer a real return address")
    request("orders.receive",buyer,{"order_id":r},422)
    request("orders.return",buyer,{"order_id":r,"carrier":"Return Express","tracking_number":"BACK123"})
    request("orders.return",buyer,{"order_id":r,"carrier":"Return Express","tracking_number":"DUPLICATE"},422)
    admin_post("mall.order.return.approve",order_id=r,return_address="Wrong reset")
    o=request("order&id="+str(r),buyer)
    check(o["after_sale_status"]=="return_shipped" and o["return_tracking"]=="BACK123","shipped returns cannot be reset or overwritten by duplicate actions")
    admin_post("mall.order.settle",order_id=r)
    check(sql("SELECT settlement_status FROM orders WHERE id="+str(r))=="held","open returned order cannot be settled")
    admin_post("mall.order.refund",order_id=r)
    check(Decimal(sql("SELECT balance FROM wallet_assets WHERE user_id=2 AND symbol='SPX001'"))==cash and sql("SELECT stock FROM products WHERE id="+str(fid))=="1","returned order refunds shipping and price without premature restocking")
    o=request("order&id="+str(r),buyer)
    check(len(o["events"])>=5 and o["buyer_note"]=="Leave at door" and len(o["after_sale_images"])==1,"receipt retains note, evidence and lifecycle timeline")
    digital=dict(product,name="Tiny decimal digital",product_type="digital",stock=3,variants=[],cny_price="0.00000007",shipping_fee="50")
    did=request("merchant.product.save",seller,digital)["id"]
    q=request("checkout.quote",buyer,{"product_id":did,"quantity":2})
    check(Decimal(q["total_spx"])==Decimal(q["subtotal"]) and Decimal(q["shipping_fee"])==0,"digital goods have no shipping fee and retain eight decimal places")
    r=request("orders.redeem",buyer,{"quote_id":q["quote_id"],"client_request_id":secrets.token_hex(16)},201)["order_id"]
    request("merchant.order.deliver",seller,{"order_id":r,"fulfillment_note":"License ABC123"})
    request("orders.receive",buyer,{"order_id":r})
    check(request("order&id="+str(r),buyer)["fulfillment_note"]=="License ABC123","digital delivery content survives buyer confirmation")
    request('product.review',outsider,{'order_id':r,'product_id':did,'rating':5,'content':'Forbidden'},422)
    request('product.review',buyer,{'order_id':r,'product_id':did,'rating':5,'content':'Delivered correctly'})
    request('product.review',buyer,{'order_id':r,'product_id':did,'rating':1,'content':'Duplicate'},422)
    check(request('product.reviews&product_id='+str(did),buyer)[0]['rating']==5,'completed order buyer can review exactly once')
    tiny_admin=admin_browser.open(admin_url+'admin.php?section=merchants').read().decode()
    check('0.00000001' in tiny_admin,'platform mall admin preserves eight decimal prices')
    check(sql('SELECT COUNT(*) FROM wallet_transactions WHERE mall_order_id='+str(r)+" AND type='redeem'")=='1','mall debit ledger carries exact order reference')
    admin_post("mall.order.refund",order_id=r)
    r=request("orders.redeem",buyer,{"product_id":lid,"quantity":1},201)["order_id"]
    o=request("order&id="+str(r),buyer)
    sql("UPDATE orders SET service_expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id="+str(r))
    request("merchant.order.redeem",seller,{"order_id":r,"redeem_code":o["redeem_code"]},422)
    request("orders.cancel",buyer,{"order_id":r})
    check(sql("SELECT status FROM orders WHERE id="+str(r))=="refunded","expired local voucher cannot be redeemed and remains refundable")
    r=request("orders.redeem",buyer,{"product_id":pid,"quantity":1,"variant_id":"blue","address_id":second},201)["order_id"]
    current=request("product&id="+str(pid))
    request("merchant.product.save",seller,dict(product,id=pid,version=current["version"],variants=[product["variants"][0]]))
    active_stock=sql("SELECT stock FROM products WHERE id="+str(pid))
    request("orders.cancel",buyer,{"order_id":r})
    check(sql("SELECT stock FROM products WHERE id="+str(pid))==active_stock and len(request("product&id="+str(pid))["variants"])==1,"pending cancellation restores disabled SKU without exposing it or inflating active stock")
    admin_post("mall.product.review",product_id=fid,decision="offline")
    version=int(sql("SELECT version FROM products WHERE id="+str(fid)))
    request("merchant.product.save",seller,dict(fee_product,id=fid,version=version),422)
    check(sql("SELECT status FROM products WHERE id="+str(fid))=="0","platform product restriction cannot be bypassed by auto approval")
    q=request("checkout.quote",buyer,{"product_id":did,"quantity":1})
    request("orders.redeem",outsider,{"quote_id":q["quote_id"],"client_request_id":secrets.token_hex(16)},422)
    sql("UPDATE mall_checkout_quotes SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id='"+q["quote_id"]+"'")
    request("orders.redeem",buyer,{"quote_id":q["quote_id"],"client_request_id":secrets.token_hex(16)},422)
    check(sql("SELECT order_id IS NULL FROM mall_checkout_quotes WHERE id='"+q["quote_id"]+"'")=="1","foreign and expired quotes cannot create orders")
    first_page=request("merchant.products&page_size=1&page=1",seller)
    next_page=request("merchant.products&page_size=1&page=2",seller)
    check(len(first_page)==1 and len(next_page)==1 and first_page[0]["id"]!=next_page[0]["id"],"merchant pagination returns distinct subsequent products")
    upload(b"<?php echo 1;", "image/png",422)
    import base64
    valid_png=base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j1ioAAAAASUVORK5CYII=")
    uploaded=upload(valid_png,"image/png",200)
    check(uploaded["url"].endswith(".png") and "/uploads/mall/" in uploaded["url"],"image upload validates actual image content and stores randomized file name")
    # Platform products use the same receipts, refunds and after-sale lifecycle.
    sql("INSERT INTO products(name,description,image_url,spx_price,stock,product_type,status) VALUES ('Platform legacy','Legacy compatibility','https://example.com/platform.jpg',10,3,'physical',1)")
    platform_id=int(sql("SELECT id FROM products WHERE name='Platform legacy'"))
    check(request("product&id="+str(platform_id))["cny_price"]=="", "legacy product missing CNY is an empty string, never a literal null price")
    sql("UPDATE wallet_assets SET balance=100,stakeable_balance=100 WHERE user_id=2 AND symbol='SPX001'")
    r=request("orders.redeem",buyer,{"product_id":platform_id,"quantity":1,"address_id":second},201)["order_id"]
    admin_post("order.process",order_id=r,decision="cancel")
    check(sql("SELECT balance=100 AND stakeable_balance=100 FROM wallet_assets WHERE user_id=2 AND symbol='SPX001'")=="1","legacy admin cancellation restores platform order balance and stakeable split")
    r=request("orders.redeem",buyer,{"product_id":platform_id,"quantity":1,"address_id":second},201)["order_id"]
    admin_post("order.ship",order_id=r,carrier="Platform Express",tracking_number="PLAT123")
    request("orders.receive",buyer,{"order_id":r})
    request("orders.after_sale",buyer,{"order_id":r,"reason":"Platform item return"})
    admin_post("mall.order.refund",order_id=r)
    check(request("order&id="+str(r),buyer)["status"]=="refunded","platform shipment supports buyer receipt and shared after-sale refunds")
    sql("UPDATE wallet_assets SET balance=2000000,stakeable_balance=1500000 WHERE user_id=2 AND symbol='SPX001'")
    sql("UPDATE products SET status=0 WHERE id="+str(platform_id))

    # Two independent PHP workers share the disposable DB to exercise row locks.
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier
    second_port=port()
    second_log=(temp/"php-concurrent.log").open("w",encoding="utf-8")
    second_api=subprocess.Popen(php_args+["-S","127.0.0.1:"+str(second_port),"-t",str(app)],stdout=second_log,stderr=subprocess.STDOUT)
    try:
        wait_port(second_port)
        def raw_pay(endpoint_port,token,payload,barrier):
            barrier.wait(timeout=10)
            req=urllib.request.Request("http://127.0.0.1:"+str(endpoint_port)+"/api.php?action=orders.redeem",data=json.dumps(payload).encode(),headers={"Authorization":"Bearer "+token,"Content-Type":"application/json"})
            try: res=urllib.request.urlopen(req,timeout=30)
            except urllib.error.HTTPError as e: res=e
            return res.code,json.loads(res.read().decode())
        cpid=request("merchant.product.save",seller,dict(product,name="Concurrency",variants=[],stock=1))["id"]
        q=request("checkout.quote",buyer,{"product_id":cpid,"quantity":1,"address_id":second})
        barrier=Barrier(2)
        with ThreadPoolExecutor(2) as pool:
            jobs=[pool.submit(raw_pay,endpoint,buyer,{"quote_id":q["quote_id"],"client_request_id":secrets.token_hex(16)},barrier) for endpoint in [api_port,second_port]]
            answers=[job.result() for job in jobs]
        check(all(code==201 for code,_ in answers) and answers[0][1]["data"]["order_id"]==answers[1][1]["data"]["order_id"],"concurrent payment submissions share one order and one debit")
        request("orders.cancel",buyer,{"order_id":answers[0][1]["data"]["order_id"]})
        aid=request("wallet.address.save",outsider,address,201)["id"]
        q1=request("checkout.quote",buyer,{"product_id":cpid,"quantity":1,"address_id":second})
        q2=request("checkout.quote",outsider,{"product_id":cpid,"quantity":1,"address_id":aid})
        barrier=Barrier(2)
        with ThreadPoolExecutor(2) as pool:
            one=pool.submit(raw_pay,api_port,buyer,{"quote_id":q1["quote_id"],"client_request_id":secrets.token_hex(16)},barrier)
            two=pool.submit(raw_pay,second_port,outsider,{"quote_id":q2["quote_id"],"client_request_id":secrets.token_hex(16)},barrier)
            answers=[one.result(),two.result()]
        check(sorted(code for code,_ in answers)==[201,422] and sql("SELECT stock FROM products WHERE id="+str(cpid))=="0","concurrent buyers cannot oversell the last unit")
        winner=0 if answers[0][0]==201 else 1
        request("orders.cancel",[buyer,outsider][winner],{"order_id":answers[winner][1]["data"]["order_id"]})
        check(sql("SELECT stock FROM products WHERE id="+str(cpid))=="1","winning concurrent purchase restores inventory exactly on cancellation")
    finally:
        second_api.terminate();second_api.wait(timeout=10);second_log.close()

    admin_post("mall.merchant.review", merchant_id=1, decision="suspend")
    request("merchant.close", seller, {}, 422)
    check(sql("SELECT status FROM merchants WHERE id=1") == "suspended", "suspended merchant cannot bypass financial hold by closing")
    admin_post("mall.merchant.review", merchant_id=1, decision="resume")
    blocking=request('orders.redeem',buyer,{'product_id':pid,'quantity':1,'variant_id':'red','address_id':second},201)['order_id']
    settling=request('orders.redeem',buyer,{'product_id':pid,'quantity':1,'variant_id':'red','address_id':second},201)['order_id']
    request('merchant.order.ship',seller,{'order_id':settling,'carrier':'Closing Express','tracking_number':'CLOSE123'})
    request('orders.receive',buyer,{'order_id':settling})
    sql('UPDATE orders SET received_at=DATE_SUB(NOW(),INTERVAL 8 DAY) WHERE id='+str(settling))
    request("merchant.close", seller, {})
    check(sql('SELECT status FROM merchants WHERE id=1')=='closing','unfinished orders defer deposit return')
    admin_post('mall.deposit.release',merchant_id=1)
    check(sql('SELECT status FROM merchants WHERE id=1')=='closing','admin cannot release deposit with unfinished orders')
    request('orders.cancel',buyer,{'order_id':blocking})
    check(sql('SELECT status FROM merchants WHERE id=1')=='closing','cancelled order cannot release deposit while another settlement is held')
    admin_post('mall.order.settle',order_id=settling)
    check(sql('SELECT status FROM merchants WHERE id=1')=='closed','last settlement automatically releases closing deposit')
    check(request('merchant.me',seller)['merchant']['status']=='closed','all orders finished returns deposit without additional waiting')
    check(request("products") == [], "closing shop immediately stops public sales")
    request("orders.redeem", buyer, {"product_id": pid, "quantity": 1, "variant_id": "red", "address_id": 1}, 404)
    admin_post("mall.deposit.release", merchant_id=1)
    check(sql("SELECT status FROM merchants WHERE id=1") == "closed", "repeat release retains closed state")
    admin_post("mall.deposit.release", merchant_id=1)
    admin_post("mall.deposit.release", merchant_id=1)
    check(sql("SELECT balance FROM wallet_assets WHERE user_id=1 AND symbol='SPX001'") == "2000027.000000000000000000", "closure returns deposit once after all net settlements")
    check(sql("SELECT stakeable_balance FROM wallet_assets WHERE user_id=1 AND symbol='SPX001'") == "1500000.000000000000000000", "deposit return restores original stakeable split")
    outsider_doc=upload(valid_png,'image/png',200,'merchant.qualification.upload',outsider)['id']
    request('merchant.apply',outsider,dict(profile,name='Empty shop',qualification_images=[outsider_doc]))
    empty_id=int(sql('SELECT id FROM merchants WHERE owner_user_id=3'))
    admin_post('mall.merchant.review',merchant_id=empty_id,decision='approve')
    request('merchant.deposit.pay',outsider,{'level':'basic'})
    request('merchant.close',outsider,{})
    request('merchant.close',outsider,{})
    check(sql('SELECT status FROM merchants WHERE id='+str(empty_id))=='closed' and sql("SELECT balance FROM wallet_assets WHERE user_id=3 AND symbol='SPX001'")=='2000000.000000000000000000','shop with no orders immediately refunds deposit once')
    import mall_revision_scenarios
    mall_revision_scenarios.run(globals())
    import chat_scenarios
    chat_scenarios.run(globals())
    import red_packet_scenarios
    red_packet_scenarios.run(globals())
    print("SUCCESS " + str(checks) + " integration checks", flush=True)
finally:
    if api:
        api.terminate()
        api.wait(timeout=10)
    server.terminate()
    server.wait(timeout=10)
    print("Isolated test logs: " + str(temp), flush=True)
