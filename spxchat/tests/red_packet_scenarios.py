"""Real packet / wallet API checks, executed only in mall_integration's disposable DB."""
import json
import secrets
import subprocess
import uuid
import urllib.request
import urllib.error
from decimal import Decimal
from concurrent.futures import ThreadPoolExecutor

def run(ctx):
    request, sql, check = ctx['request'], ctx['sql'], ctx['check']
    password='Packet_'+secrets.token_hex(8)
    hashed=subprocess.run(ctx['php_args']+['-r','echo password_hash($argv[1], PASSWORD_DEFAULT);',password],check=True,capture_output=True,encoding='utf-8').stdout
    tokens=[secrets.token_hex(32) for _ in range(4)]
    for uid,token in zip(range(201,205),tokens):
        sql("INSERT INTO users(id,username,nickname,spx_id,password_hash) VALUES (%d,'packet%d','Packet %d','spx%d','%s'); INSERT INTO user_tokens(user_id,token,expires_at) VALUES (%d,'%s',DATE_ADD(NOW(),INTERVAL 1 DAY)); INSERT INTO wallet_assets(user_id,symbol,chain,balance,stakeable_balance) VALUES (%d,'SPX001','BSC',100,60);"%(uid,uid,uid,910000+uid,hashed,uid,token,uid))
    alice,bob,carol,outsider=tokens
    payment='258369'
    for token in tokens:
        request('payment.password.set',token,{'password':payment,'confirmation':payment})
    request('contacts.add',alice,{'spx_id':'spx910202'},201)
    chat=request('chats.create',alice,{'peer_user_id':202},201)['chat_id']
    sql("INSERT INTO chat_groups(id,owner_user_id,name,group_code) VALUES (9901,201,'Packet group','RPK901'); INSERT INTO chat_group_members(group_id,user_id,role) VALUES (9901,201,'owner'),(9901,202,'member'),(9901,203,'member');")
    def balance(uid,column='balance'):
        value=sql("SELECT %s FROM wallet_assets WHERE user_id=%d AND symbol='SPX001' AND chain='BSC'"%(column,uid))
        try:return Decimal(value)
        except Exception as error:raise AssertionError('Unexpected isolated wallet fixture value: '+repr(value)) from error
    def create(amount='3',count=1,mode='ordinary',scope='chat',target=chat,**more):
        body=dict(request_id=str(uuid.uuid4()),scope=scope,target_id=target,mode=mode,count=count,amount=amount,greeting='恭喜发财',payment_password=payment)
        body.update(more)
        return body
    def detail(pid,token=alice):return request('redpackets.detail&id='+pid,token)
    def claim(pid,token):return request('redpackets.claim',token,{'id':pid})
    def escrow():return Decimal(sql('SELECT COALESCE(SUM(remaining_units),0) FROM chat_red_packets'))/Decimal(1000000)
    def conserved():return sum(balance(i) for i in range(201,205))+escrow()==Decimal(400)
    def notices(pid,group=False):
        table='chat_group_messages' if group else 'messages'
        fields="'id',id,'sender_id',sender_id,'content',content,'media_url',media_url,'message_type',message_type,'message_origin',message_origin"
        if not group:fields+=",'user_id',user_id,'chat_id',chat_id"
        return [json.loads(line) for line in sql("SELECT JSON_OBJECT(%s) FROM %s WHERE message_origin='system' AND media_url LIKE 'spxredpacket:claim:%%\"packet_id\":\"%s\"%%' ORDER BY id"%(fields,table,pid),raw=True).splitlines() if line]
    request('redpackets.create',alice,None,405)
    bad=create(payment_password='654321');request('redpackets.create',alice,bad,403)
    check(balance(201)==100,'wrong password and method do not debit wallet')
    for amount in ['0','-1','1e3','0.0000001','1000000001','01','NaN']:
        request('redpackets.create',alice,create(amount=amount),422)
    request('redpackets.create',alice,create(amount=1),422)
    request('redpackets.create',alice,create(count=2),422)
    request('redpackets.create',alice,create(scope='group',target=9901,count=4),422)
    request('redpackets.create',alice,create(scope='group',target=9901,count=3,amount='0.000004'),422)
    request('redpackets.create',alice,create(amount='101'),409)
    check(balance(201)==100 and escrow()==0,'invalid precision totals counts and insufficient balance leave no escrow')
    request('messages.send',alice,{'chat_id':chat,'message_type':'red_packet','content':'{}'},422)
    request('chat.schedule.create',alice,{'scope':'chat','target_id':chat,'message_type':'red_packet','content':'{}','send_at_epoch':int(__import__('time').time())+90},422)
    check(True,'ordinary send and scheduled send cannot forge monetary cards')
    body=create(amount='3.123456');sent=request('redpackets.create',alice,body,201);pid=sent['packet']['id']
    check(balance(201)==Decimal('96.876544') and sent['message']['message_type']=='red_packet' and conserved(),'private send atomically debits SPX001 and creates a real message')
    again=request('redpackets.create',alice,body)
    check(again['duplicate'] and again['packet']['id']==pid and balance(201)==Decimal('96.876544'),'repeated request does not debit twice')
    request('redpackets.create',alice,dict(body,amount='4'),409)
    recovered=request('redpackets.request&request_id='+body['request_id'],alice)
    check(recovered['exists'] and recovered['packet']['id']==pid,'lost send response can be recovered by persistent request ID')
    check(request('redpackets.request&request_id='+body['request_id'],bob)['exists'] is False,'request recovery is scoped to its sender')
    peerchat=int(sql('SELECT id FROM chats WHERE user_id=202 AND peer_user_id=201 LIMIT 1'))
    rows=request('messages&chat_id='+str(peerchat),bob)
    check(rows[-1]['message_type']=='red_packet' and json.loads(rows[-1]['content'])['packet_id']==pid,'recipient receives authoritative red packet card in history')
    request('messages.delete',alice,{'chat_id':chat,'message_id':sent['message']['id']},403)
    check(True,'sent monetary card cannot be withdrawn as an ordinary message')
    request('redpackets.detail&id='+pid,outsider,None,403)
    request('redpackets.claim',outsider,{'id':pid},403)
    request('redpackets.claim',alice,{'id':pid},403)
    check(not notices(pid),'failed and ineligible claims create no chat receipt')
    check('claims' not in detail(pid,bob) and 'my_amount' in detail(pid,bob),'unopened packet hides allocation and claimant amounts')
    private_unread=sql('SELECT GROUP_CONCAT(unread_count ORDER BY id) FROM chats WHERE id IN (%d,%d)'%(chat,peerchat))
    got=claim(pid,bob);first=balance(202);twice=claim(pid,bob)
    check(Decimal(got['my_amount'])==Decimal('3.123456') and first==balance(202) and twice['my_amount']==got['my_amount'],'private claim credits available balance exactly once')
    check(got['status']=='completed' and conserved(),'private packet has a conserved completed lifecycle')
    receipt=got.get('claim_notice');private=notices(pid)
    check(len(private)==2 and {x['user_id'] for x in private}=={201,202} and receipt['chat_id']==peerchat and receipt['user_id']==202,'one private claim creates one receipt per intended existing conversation')
    metadata=json.loads(receipt['media_url'].removeprefix('spxredpacket:claim:'))
    check(metadata==dict(packet_id=pid,receiver_id=202,sender_id=201,receiver_name='Packet 202',sender_name='Packet 201') and receipt['message_origin']=='system' and receipt['message_type']=='text','receipt identity comes from server with no public amount or password')
    check('claim_notice' not in twice and len(notices(pid))==2 and 'claim_notice' not in detail(pid,bob),'repeated claim and viewing details never generate extra receipt')
    check(sql('SELECT GROUP_CONCAT(unread_count ORDER BY id) FROM chats WHERE id IN (%d,%d)'%(chat,peerchat))==private_unread,'private receipt creates no extra unread badge')
    push_script=ctx['temp']/'receipt-push-check.php'
    push_script.write_text("<?php require '"+(ctx['app']/'push_notifications.php').as_posix()+"'; $e=json_decode(stream_get_contents(STDIN),true,512,JSON_THROW_ON_ERROR); $quiet=pushEventRoute($e)===null; $e['message']['message_origin']='human'; echo json_encode([$quiet,pushEventRoute($e)!==null]);",encoding='utf-8')
    route=json.loads(subprocess.run(ctx['php_args']+[str(push_script)],input=json.dumps(dict(type='message',user_id=201,chat_id=chat,message=receipt)),text=True,encoding='utf-8',capture_output=True,check=True).stdout)
    check(route==[True,True],'receipt push is silent while ordinary message push remains routable')
    for owner,cid in [(alice,chat),(bob,peerchat)]:
        history=request('messages&chat_id='+str(cid),owner)
        check(any(x.get('media_url')==receipt['media_url'] and x.get('message_origin')=='system' for x in history),'private receipt persists in message history')
    ordinary=request('redpackets.create',alice,create(scope='group',target=9901,count=3,amount='6'),201)['packet']['id']
    amounts=[Decimal(claim(ordinary,t)['my_amount']) for t in [alice,bob,carol]]
    check(amounts==[Decimal(2)]*3 and detail(ordinary)['status']=='completed' and conserved(),'ordinary group packet splits equally and conserves total')
    group_receipts=notices(ordinary,True)
    check(len(group_receipts)==3 and {x['sender_id'] for x in group_receipts}=={201,202,203} and all('红包' in x['content'] for x in group_receipts),'group and sender-own claims persist separate readable system receipts')
    random=request('redpackets.create',alice,create(scope='group',target=9901,count=3,amount='0.000007',mode='random'),201)['packet']['id']
    amounts=[Decimal(claim(random,t)['my_amount']) for t in [carol,alice,bob]]
    check(all(x>0 for x in amounts) and sum(amounts)==Decimal('0.000007') and conserved(),'lucky packet assigns positive random amounts with exact final remainder')
    late=request('redpackets.create',alice,create(scope='group',target=9901,count=1,amount='1',mode='random'),201)['packet']['id']
    sql("INSERT INTO chat_group_members(group_id,user_id,role) VALUES (9901,204,'member')")
    request('redpackets.claim',outsider,{'id':late},403)
    request('redpackets.detail&id='+late,outsider,None,403)
    sql('DELETE FROM chat_group_members WHERE group_id=9901 AND user_id=203')
    request('redpackets.claim',carol,{'id':late},403)
    sql("INSERT INTO chat_group_members(group_id,user_id,role) VALUES (9901,203,'member')")
    sql('UPDATE chat_group_members SET mute=1 WHERE group_id=9901 AND user_id=202')
    claim(late,bob)
    check(len(notices(late,True))==1 and notices(late,True)[0]['sender_id']==202,'muted claimant still produces one server receipt')
    request('redpackets.create',bob,create(scope='group',target=9901),403)
    sql('UPDATE chat_group_members SET mute=0 WHERE group_id=9901 AND user_id=202')
    check(conserved(),'late joiners and removed members cannot claim; muted members may receive but cannot send')
    exp=request('redpackets.create',alice,create(scope='group',target=9901,count=3,amount='3'),201)['packet']['id']
    claim(exp,bob);before=balance(201)
    sql("UPDATE chat_red_packets SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id='%s'"%exp)
    expired=detail(exp);claim(exp,carol);detail(exp)
    check(expired['status']=='expired' and Decimal(expired['refund_amount'])==2 and balance(201)==before+2 and conserved(),'partly claimed packet refunds only remainder exactly once after expiration')
    check(len(notices(exp,True))==1,'expired unclaimed attempts never produce a receipt')
    auto=request('redpackets.create',alice,create(amount='1'),201)['packet']['id'];before=balance(201)
    sql("UPDATE chat_red_packets SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id='%s'"%auto)
    request('wallet',alice);request('wallet',alice)
    check(balance(201)==before+1 and detail(auto)['status']=='expired','wallet refresh settles due refunds without duplicate credit')
    # Move a stakeable source through packets, then ensure expiry restores the exact source split.
    source_before=balance(201,'stakeable_balance');large=request('redpackets.create',alice,create(amount='80'),201)['packet']['id']
    check(balance(201,'stakeable_balance')<source_before,'sending uses existing wallet source split rather than minting eligible balance')
    sql("UPDATE chat_red_packets SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id='%s'"%large);detail(large)
    check(balance(201,'stakeable_balance')==source_before and conserved(),'expiration restores original stakeable and available amounts: before='+str(source_before)+' after='+str(balance(201,'stakeable_balance'))+' conserved='+str(conserved()))
    # Two independent PHP processes contend for the last packet under the same database lock.
    from socket import socket
    with socket() as sock:sock.bind(('127.0.0.1',0));second_port=sock.getsockname()[1]
    logfile=(ctx['temp']/'red-packet-concurrency.log').open('w',encoding='utf-8')
    second=subprocess.Popen(ctx['php_args']+['-S','127.0.0.1:'+str(second_port),'-t',str(ctx['app'])],stdout=logfile,stderr=subprocess.STDOUT)
    try:
        ctx['wait_port'](second_port)
        def raw_claim(port,token,pid):
            req=urllib.request.Request('http://127.0.0.1:'+str(port)+'/api.php?action=redpackets.claim',data=json.dumps({'id':pid}).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json','Accept-Language':'zh-CN'})
            with urllib.request.urlopen(req,timeout=20) as res:return json.loads(res.read())['data']
        race=request('redpackets.create',alice,create(scope='group',target=9901,count=1,amount='2',mode='random'),201)['packet']['id']
        with ThreadPoolExecutor(2) as pool:answers=list(pool.map(lambda x:raw_claim(*x),[(ctx['api_port'],bob,race),(second_port,carol,race)]))
        check(sum(x.get('my_amount') is not None for x in answers)==1 and sql("SELECT COUNT(*) FROM chat_red_packet_claims WHERE packet_id='%s'"%race)=='1' and conserved(),'concurrent last-packet claims cannot overpay or overdraw')
        check(len(notices(race,True))==1,'concurrent last-packet contention creates only the winning receipt')
        repeat=request('redpackets.create',alice,create(amount='1'),201)['packet']['id'];before=balance(202)
        with ThreadPoolExecutor(2) as pool:answers=list(pool.map(lambda x:raw_claim(*x),[(ctx['api_port'],bob,repeat),(second_port,bob,repeat)]))
        check(balance(202)==before+1 and all(Decimal(x['my_amount'])==1 for x in answers) and conserved(),'concurrent duplicate claim by same user credits only once')
        check(len(notices(repeat))==2 and sum('claim_notice' in x for x in answers)==1,'concurrent duplicate claim creates one receipt in each private history')
    finally:
        second.terminate();second.wait(timeout=10);logfile.close()
    # Force receipt persistence to fail inside the same transaction: money must roll back too.
    rollback=request('redpackets.create',alice,create(scope='group',target=9901,amount='1'),201)['packet']['id']
    before=balance(202)
    sql("CREATE TRIGGER packet_receipt_failure BEFORE INSERT ON chat_group_messages FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated receipt failure'")
    try:request('redpackets.claim',bob,{'id':rollback},500)
    finally:sql('DROP TRIGGER packet_receipt_failure')
    check(balance(202)==before and sql("SELECT COUNT(*) FROM chat_red_packet_claims WHERE packet_id='%s'"%rollback)=='0' and not notices(rollback,True) and detail(rollback)['claimed_count']==0,'receipt failure rolls back claim, wallet credit, counters and events together')
    claim(rollback,bob)
    check(balance(202)==before+1 and len(notices(rollback,True))==1 and conserved(),'retry after rollback succeeds exactly once')
    # A duplicate peer chat must not receive a receipt for the original conversation.
    sql("INSERT INTO chats(user_id,peer_user_id,title) VALUES (202,201,'duplicate fixture')")
    duplicate_chat=int(sql('SELECT MAX(id) FROM chats WHERE user_id=202 AND peer_user_id=201'))
    private_id=request('redpackets.create',alice,create(amount='1'),201)['packet']['id'];claim(private_id,bob)
    check(len(notices(private_id))==2 and all(x['chat_id']!=duplicate_chat for x in notices(private_id)),'private receipt uses the conversation that holds the original monetary card')
    sql('UPDATE users SET nickname=REPEAT(CONCAT(CHAR(34),CHAR(92)),32) WHERE id IN (201,202)')
    long_names=request('redpackets.create',alice,create(scope='group',target=9901,amount='1'),201)['packet']['id'];claim(long_names,bob)
    escaped=notices(long_names,True)[0]['media_url']
    check(len(escaped)<=500 and json.loads(escaped.removeprefix('spxredpacket:claim:'))['receiver_name']=='"\\'*32,'max-length escaped nicknames fit the existing metadata column')
    sql('UPDATE users SET nickname=CONCAT(CHAR(10),CHAR(9)) WHERE id IN (201,202)')
    control_names=request('redpackets.create',alice,create(scope='group',target=9901,amount='1'),201)['packet']['id'];claim(control_names,bob)
    safe=notices(control_names,True)[0]
    check('\n' not in safe['content'] and json.loads(safe['media_url'].removeprefix('spxredpacket:claim:'))['receiver_name']=='好友','control-only names become a safe readable fallback')
    sql("UPDATE users SET nickname=CONCAT('Packet ',id) WHERE id IN (201,202)")
    # The received amount is the same SPX001 balance used by existing withdrawal flow.
    address='0x1111111111111111111111111111111111111111'
    request('wallet.bind',bob,{'address':address,'password':password},201)
    # This test exercises cancellation, so explicitly use manual review in this disposable DB.
    # Production auto-approval defaults are deliberately not changed by the feature.
    sql("INSERT INTO app_settings (`key`,`value`) VALUES ('withdraw_auto_policy','{\"enabled\":\"0\"}') ON DUPLICATE KEY UPDATE `value`=VALUES(`value`)")
    before=balance(202);withdraw=request('wallet.withdraw',bob,{'symbol':'SPX001','chain':'BSC','amount':'1','address':address},201)
    check(balance(202)==before-1 and withdraw['status']=='pending','received SPX001 follows existing bound-address withdrawal workflow')
    request('wallet.withdraw.cancel',bob,{'transaction_id':withdraw['transaction_id']})
    check(balance(202)==before and conserved(),'cancelling test withdrawal restores balance without changing packet claims')
    history=request('redpackets.mine',bob)
    check(any(x['id']==pid and Decimal(x['my_amount'])==Decimal('3.123456') for x in history),'packet history remains available independently of chat messages')
    check(sql("SELECT COUNT(*) FROM wallet_transactions WHERE user_id IN (201,202,203,204) AND type IN ('red_packet_send','red_packet_receive','red_packet_refund')")!='0','packet send receipt and refund produce auditable wallet transactions')
    check(sql('SELECT COUNT(*) FROM wallet_assets WHERE user_id BETWEEN 201 AND 204 AND (balance<0 OR stakeable_balance<0 OR stakeable_balance>balance)')=='0','wallet balances and stakeable subsets remain valid after all scenarios')
