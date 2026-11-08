"""Security and membership tests against mall_integration's disposable local API/DB."""
import hashlib
import json
import secrets
import subprocess
import uuid
from decimal import Decimal


def run(ctx):
    request, sql, check = ctx['request'], ctx['sql'], ctx['check']
    def hashed(value):
        return subprocess.run(ctx['php_args'] + ['-r', 'echo password_hash($argv[1], PASSWORD_DEFAULT);', value], check=True, capture_output=True, encoding='utf-8').stdout
    login = '654321'
    login_hash = hashed(login)
    tokens = [secrets.token_hex(32) for _ in range(4)]
    for uid, token in zip(range(301, 305), tokens):
        sql("INSERT INTO users(id,username,nickname,spx_id,password_hash,email,email_verified_at) VALUES (%d,'secure%d','User %d','spx%d','%s','user%d@example.test',NOW()); INSERT INTO user_tokens(user_id,token,expires_at) VALUES (%d,'%s',DATE_ADD(NOW(),INTERVAL 1 DAY)); INSERT INTO wallet_assets(user_id,symbol,chain,balance,stakeable_balance) VALUES (%d,'SPX001','BSC',100,50);" % (uid,uid,uid,uid,login_hash,uid,uid,token,uid))
    owner, member, admin, outsider = tokens
    sql("INSERT INTO chat_groups(id,owner_user_id,name,group_code) VALUES (9301,301,'Default welcome','9OQF7L'); INSERT INTO chat_group_members(group_id,user_id,role) VALUES (9301,301,'owner'),(9301,302,'member'),(9301,303,'admin');")
    def packet(pin, token=owner, status=201):
        return request('redpackets.create',token,dict(request_id=str(uuid.uuid4()),scope='group',target_id=9301,mode='ordinary',count=1,amount='1',greeting='Test',payment_password=pin),status)
    def set_pin(pin, status=200, token=owner, confirmation=None):
        return request('payment.password.set', token, {'password':pin,'confirmation':pin if confirmation is None else confirmation}, status)
    check(request('payment.password.status',owner)['configured'] is False, 'existing accounts start without an implicit payment password')
    packet('123456',status=409)
    set_pin('12345',422);set_pin('１２３４５６',422);set_pin('123456',422,confirmation='123457');set_pin(login,422)
    check(sql('SELECT COUNT(*) FROM user_payment_passwords WHERE user_id=301') == '0', 'invalid, mismatching and login-equivalent payment passwords are rejected')
    set_pin('123456')
    check(request('payment.password.status',owner)['configured'] and sql('SELECT password_hash FROM users WHERE id=301')==login_hash, 'first setup requires no email code and preserves login password')
    check(sql('SELECT password_hash FROM user_payment_passwords WHERE user_id=301') != '123456', 'payment credential is hashed')
    set_pin('111111',409)
    for attempt in range(4):packet('999999',status=403)
    packet('999999',status=429);packet('123456',status=429)
    check(Decimal(sql("SELECT balance FROM wallet_assets WHERE user_id=301 AND symbol='SPX001' AND chain='BSC'"))==100 and sql('SELECT COUNT(*) FROM chat_red_packets')=='0','five failures lock payment and never debit the wallet')
    sql('UPDATE user_payment_passwords SET locked_until=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE user_id=301')
    packet('999999',status=403)
    check(sql('SELECT failed_attempts FROM user_payment_passwords WHERE user_id=301')=='1','expired lock starts a fresh attempt budget')
    sent=packet('123456')['packet']
    check(sent['can_claim'] and sql('SELECT failed_attempts FROM user_payment_passwords WHERE user_id=301')=='0','correct payment password resets failures and permits a real send')
    legacy=dict(request_id=str(uuid.uuid4()),scope='group',target_id=9301,amount='1',password=login)
    request('redpackets.create',owner,legacy,422)
    check(sql('SELECT COUNT(*) FROM chat_red_packets')=='1','legacy login-password field cannot authorize a new red packet')
    def verify_code(value,purpose='payment_password_change',email='user301@example.test',expired=False):
        sql("INSERT INTO email_verification_codes(email,purpose,code_hash,expires_at) VALUES ('%s','%s','%s',DATE_%s(NOW(),INTERVAL 5 MINUTE))"%(email,purpose,hashlib.sha256(value.encode()).hexdigest(),'SUB' if expired else 'ADD'))
    def change(value='234567',code='246810',status=200,token=owner):
        return request('payment.password.change.email',token,{'password':value,'confirmation':value,'code':code},status)
    change(status=422)
    verify_code('246810',purpose='password_change');change(status=422)
    verify_code('246810',expired=True);change(status=422)
    verify_code('246810',email='user302@example.test');change(status=422)
    verify_code('246810');change(code='111111',status=422)
    check(sql("SELECT attempts FROM email_verification_codes WHERE email='user301@example.test' AND purpose='payment_password_change' ORDER BY id DESC LIMIT 1")=='1','wrong verification increments attempts without changing payment password')
    change();change(value='345678',status=422)
    packet('123456',status=403);packet('234567')
    check(sql("SELECT COUNT(*) FROM email_verification_codes WHERE email='user301@example.test' AND purpose='payment_password_change' AND used_at IS NOT NULL")=='1','email code is purpose/account-bound and consumed once')
    set_pin('112233',token=member)
    sql("UPDATE users SET email_verified_at=NULL WHERE id=302")
    request('payment.password.email.code',member,{},409);change(value='223344',token=member,status=409)
    check(True,'unverified email cannot authorize password changes')
    sql("UPDATE users SET email_verified_at=NOW() WHERE id=302")
    # Stub SMTP only inside the disposable copy; no production code/config or real mailbox is used.
    smtp=ctx['app']/'smtp.php';original=smtp.read_text(encoding='utf-8')
    smtp.write_text("<?php function smtpSend(string $to,string $subject,string $html): void { file_put_contents(__DIR__.'/mail.json',json_encode(compact('to','subject','html'))); }",encoding='utf-8')
    config=ctx['app']/'config.php';original_config=config.read_text(encoding='utf-8')
    config.write_text(original_config.replace("'token_ttl'=>86400", "'token_ttl'=>86400,'verification_resend_seconds'=>60,'verification_code_ttl'=>300"),encoding='utf-8')
    sql("DELETE FROM email_verification_codes WHERE email='user302@example.test' AND purpose='payment_password_change'")
    try:
        result=request('payment.password.email.code',member,{})
        mail=json.loads((ctx['app']/'mail.json').read_text(encoding='utf-8'))
        check(result['email']=='user302@example.test' and mail['to']==result['email'] and '支付密码' in mail['subject'],'verification email targets only the signed-in account binding')
        request('payment.password.email.code',member,{},429)
        check(True,'email resend cooldown rejects immediate repeats')
    finally:
        smtp.write_text(original,encoding='utf-8');config.write_text(original_config,encoding='utf-8')
    # Explicit, visible packet states and authorization errors.
    pid=sent['id'];request('redpackets.claim',member,{'id':pid})
    done=request('redpackets.detail&id='+pid,admin)
    check(done['status']=='completed' and '已领完' in done['claim_disabled_reason'],'completed packet reports its actual terminal state')
    exp=packet('234567')['packet']['id'];sql("UPDATE chat_red_packets SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id='%s'"%exp)
    check('已过期' in request('redpackets.detail&id='+exp,admin)['claim_disabled_reason'],'expired packet reports expiry instead of a loading failure')
    request('redpackets.detail&id='+pid,outsider,None,403)
    request('redpackets.detail&id='+'a'*32,owner,None,404)
    check(True,'ineligible account and missing packet remain distinct HTTP errors')
    # Ban, invitation bypass, and permission hierarchy.
    request('contacts.add',owner,{'spx_id':'spx302'},201)
    request('groups.members.remove',member,{'group_id':9301,'user_id':303,'ban':True},403)
    request('groups.members.remove',admin,{'group_id':9301,'user_id':301,'ban':True},409)
    request('groups.members.remove',owner,{'group_id':9301,'user_id':302,'ban':'invalid'},422)
    check(sql('SELECT COUNT(*) FROM chat_group_members WHERE group_id=9301 AND user_id=302')=='1','invalid or unauthorized removal cannot change membership')
    request('groups.members.remove',admin,{'group_id':9301,'user_id':302,'ban':True})
    check(sql('SELECT COUNT(*) FROM chat_group_bans WHERE group_id=9301 AND user_id=302')=='1','authorized blacklist removal persists ban atomically')
    request('groups.join',member,{'group_code':'9OQF7L'},403)
    request('groups.members.add',owner,{'group_id':9301,'user_ids':[302]},403)
    check(sql('SELECT COUNT(*) FROM chat_group_members WHERE group_id=9301 AND user_id=302')=='0','group-code, QR and admin invitations cannot re-admit banned account')
    # Register against the real route; seed only a verification email fixture.
    def register(name):
        email=name+'@example.test';value='135790';verify_code(value,purpose='register',email=email)
        return request('auth.register',body=dict(email=email,code=value,username=name,password='Register_123'),status=201)
    new=register('newcomer');uid=int(new['user']['id']);newtoken=new['token']
    check(sql('SELECT COUNT(*) FROM chat_group_members WHERE group_id=9301 AND user_id=%d'%uid)=='1' and any(g['group_code']=='9OQF7L' for g in request('groups',newtoken)), 'new registration joins 9OQF7L and sees it in group list immediately')
    check(sql("SELECT COUNT(*) FROM chat_group_messages WHERE group_id=9301 AND sender_id=%d AND message_origin='system'"%uid)=='1','registration generates exactly one system welcome')
    again=request('auth.login',body={'username':'newcomer','password':'Register_123'})['token']
    check(sql("SELECT COUNT(*) FROM chat_group_messages WHERE group_id=9301 AND sender_id=%d AND message_origin='system'"%uid)=='1','subsequent login does not duplicate registration admission')
    request('groups.members.remove',owner,{'group_id':9301,'user_id':uid,'ban':True})
    again=request('auth.login',body={'username':'newcomer','password':'Register_123'})['token']
    request('groups.join',again,{'group_code':'9OQF7L'},403)
    check(sql('SELECT COUNT(*) FROM chat_group_members WHERE group_id=9301 AND user_id=%d'%uid)=='0','login auto-admission cannot bypass blacklist')
    # Temporary group unavailability never blocks registration. Only pending admissions retry.
    sql("UPDATE chat_groups SET group_code='TMP001' WHERE id=9301")
    pending=register('queuednew');queued=int(pending['user']['id'])
    check(sql('SELECT state FROM registration_group_admissions WHERE user_id=%d'%queued)=='pending','missing default group queues admission without breaking registration')
    sql("UPDATE chat_groups SET group_code='9OQF7L' WHERE id=9301")
    joined=request('auth.login',body={'username':'queuednew','password':'Register_123'})['token']
    check(sql('SELECT state FROM registration_group_admissions WHERE user_id=%d'%queued)=='joined' and sql('SELECT COUNT(*) FROM chat_group_members WHERE group_id=9301 AND user_id=%d'%queued)=='1','pending admission retries once after group recovery')
    request('groups.leave',joined,{'group_id':9301})
    request('auth.login',body={'username':'queuednew','password':'Register_123'})
    check(sql('SELECT COUNT(*) FROM chat_group_members WHERE group_id=9301 AND user_id=%d'%queued)=='0','user who leaves is not forced back on next login')
