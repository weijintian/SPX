"""Real HTTP/PDO call-state tests. Uses disposable users/database and a fake SDK key."""
import base64
import hashlib
import hmac
import json
import secrets
import uuid
import zlib


def run(ctx):
    request, sql, check = (ctx[k] for k in ('request', 'sql', 'check'))
    tokens = [secrets.token_hex(32) for _ in range(3)]
    for uid, token in zip([201, 202, 203], tokens):
        sql("INSERT INTO users(id,username,nickname,spx_id,password_hash) VALUES(%d,'call%d','Caller %d','spx%d','test');" % (uid, uid, uid, 900000+uid))
        sql("INSERT INTO user_tokens(user_id,token,expires_at) VALUES(%d,'%s',DATE_ADD(NOW(),INTERVAL 1 DAY))" % (uid, token))
    alice, bob, other = tokens
    request('calls.credentials', None, None, 401)
    cred = request('calls.credentials', alice)
    check(cred['user_id'] == 'spx_201' and 'key' not in cred and cred['sdk_app_id'] == 1400000001, 'credentials belong only to authenticated user and omit SDK secret')
    decoded = json.loads(zlib.decompress(base64.b64decode(cred['user_sig'].translate(str.maketrans('*-_', '+/=')))))
    text = ''.join('%s:%s\n' % (k, decoded[k]) for k in ('TLS.identifier','TLS.sdkappid','TLS.time','TLS.expire'))
    signature = base64.b64encode(hmac.new(b'isolated-call-test-key-0123456789abcdef', text.encode(), hashlib.sha256).digest()).decode()
    check(decoded['TLS.identifier'] == 'spx_201' and decoded['TLS.expire'] == 3600 and hmac.compare_digest(signature, decoded['TLS.sig']), 'UserSig round trip validates Tencent TLS v2 HMAC and one-hour expiry')
    da, db, dc = [str(uuid.uuid4()) for _ in range(3)]
    def present(token, device, ready=True):
        return request('calls.presence', token, {'device_id':device, 'ready':ready})
    present(alice, da); present(bob, db); present(other, dc)
    request('calls.presence', alice, {'device_id':'bad', 'ready':True}, 422)
    request('contacts.add', alice, {'spx_id':'spx900202'}, 201)
    chat = request('chats.create', alice, {'peer_user_id':202}, 201)['chat_id']
    def prepare(sid=None, media='audio', token=alice, device=da, chat_id=chat, status=200):
        return request('calls.prepare', token, {'session_id':sid or str(uuid.uuid4()), 'device_id':device, 'chat_id':chat_id,'media':media}, status)
    def update(sid, event, token=alice, device=da, status=200):
        # The receiving client validates/claims the invitation before updating it.
        if token==bob and status==200:
            view(sid,token,device)
        return request('calls.update', token, {'session_id':sid,'device_id':device,'event':event}, status)
    def view(sid, token=alice, device=da, status=200):
        return request('calls.session&session_id='+sid+'&device_id='+device, token, None, status)
    prepare(token=other, device=dc, status=404)
    prepare(sid='-'*36, status=422)
    prepare(media='group', status=422)
    prepare(device=str(uuid.uuid4()), status=409)
    check(True, 'invalid media, identifiers, foreign chat and unready caller are rejected')
    sid = str(uuid.uuid4()); call = prepare(sid)
    check(call['peer_user_id']=='spx_202' and call['state']=='ringing', 'mutual friends create foreground one-to-one call')
    check(prepare(sid)['session_id']==sid and sql("SELECT COUNT(*) FROM chat_calls WHERE id='%s'" % sid)=='1', 'prepare retries are idempotent')
    prepare(status=409); prepare(sid, media='video', status=409)
    view(sid, other, dc, 403); view(sid, alice, da, 200); view(sid, bob, da, 409)
    update(sid, 'connected', other, dc, 403); update(sid, 'connected', bob, da, 409)
    view(sid,bob,db)
    update(sid, 'rejected', alice, da, 403); update(sid, 'canceled', bob, db, 403)
    check(view(sid,bob,db)['peer_id']==201, 'callee can view only its own device session; outsider cannot read or modify')
    request('messages.send', alice, {'chat_id':chat,'client_message_id':'call-'+hashlib.sha256(sid.encode()).hexdigest()[:30], 'content':'forged call record'}, 422)
    check(True, 'human messages cannot preempt server call record identifiers')
    update(sid,'connected',bob,db)
    sql("UPDATE chat_calls SET connected_at=DATE_SUB(NOW(),INTERVAL 17 SECOND) WHERE id='%s'" % sid)
    ended = update(sid,'ended')
    check(ended['state']=='ended' and ended['duration_seconds']>=17, 'connected duration uses server clock')
    update(sid,'ended',bob,db); update(sid,'rejected')
    check(sql("SELECT COUNT(*) FROM messages WHERE message_origin='call'")=='2' and sql("SELECT record_written FROM chat_calls WHERE id='%s'" % sid)=='1', 'duplicate terminal callbacks produce exactly one record per participant')
    record_id=int(sql("SELECT id FROM messages WHERE user_id=201 AND message_origin='call' LIMIT 1"))
    request('messages.edit',alice,{'chat_id':chat,'message_id':record_id,'content':'fake result'},403)
    check(True, 'call records cannot be edited into forged messages')
    sid=prepare(media='video')['session_id']; update(sid,'sdk_rejected',alice,da)
    check(view(sid)['state']=='rejected','SDK rejection callback may finish either participant session')
    sid=prepare(media='video')['session_id']; update(sid,'rejected',bob,db)
    check(view(sid)['state']=='rejected','incoming rejection persists correct state')
    sid=prepare()['session_id']; update(sid,'canceled')
    check(view(sid,bob,db)['state']=='canceled','caller cancellation is visible to callee')
    sid=prepare()['session_id']; update(sid,'sdk_canceled',bob,db)
    check(view(sid)['state']=='canceled','SDK remote cancellation is persisted by the incoming device')
    sid=prepare()['session_id']
    sql("UPDATE chat_calls SET created_at=DATE_SUB(NOW(),INTERVAL 70 SECOND) WHERE id='%s'" % sid)
    check(view(sid)['state']=='timeout','unanswered calls expire and release busy slot')
    sid=prepare()['session_id'];update(sid,'connected',bob,db)
    sql("UPDATE chat_calls SET caller_seen=DATE_SUB(NOW(),INTERVAL 50 SECOND) WHERE id='%s'" % sid)
    check(view(sid)['state']=='failed','lost client heartbeat terminates stale connected call')
    present(bob,db,False)
    sid=prepare()['session_id']
    check(view(sid)['state']=='ringing' and not view(sid)['peer_ready'],'offline friend can be called and remains waiting')
    view(sid,bob,db,409); update(sid,'connected',bob,db,409)
    check(update(sid,'heartbeat')['state']=='ringing','offline recipient cannot claim the session before device readiness')
    prepare(status=409)
    present(bob,db)
    check(update(sid,'heartbeat')['peer_ready'],'recipient coming online is exposed to waiting caller without a new call')
    view(sid,bob,da,409)
    check(view(sid,bob,db)['state']=='ringing','fresh authenticated recipient device claims an offline call')
    present(bob,dc)
    view(sid,bob,dc,409);update(sid,'rejected',bob,dc,409)
    present(bob,db)
    check(view(sid,bob,db)['state']=='ringing','another device cannot take over a claimed call')
    update(sid,'connected',bob,db);update(sid,'ended')
    present(bob,db,False)
    sid=prepare()['session_id'];update(sid,'canceled');update(sid,'canceled')
    check(view(sid)['state']=='canceled' and sql("SELECT COUNT(*) FROM messages WHERE client_message_id='call-%s'" % hashlib.sha256(sid.encode()).hexdigest()[:30])=='2','offline cancellation releases busy slot and writes each record once')
    sid=prepare(media='video')['session_id']
    sql("UPDATE chat_calls SET created_at=DATE_SUB(NOW(),INTERVAL 70 SECOND) WHERE id='%s'" % sid)
    check(view(sid)['state']=='timeout','offline video call times out without extending its waiting window')
    present(bob,db)
    sid=prepare()['session_id']
    sql('DELETE FROM messages WHERE chat_id='+str(chat))
    sql('DELETE FROM chats WHERE id='+str(chat))
    update(sid,'canceled')
    check(sql("SELECT COUNT(*) FROM messages WHERE user_id=201 AND message_origin='call'")=='1','deleting chat during a call cannot lose its terminal record')
    request('calls.unknown',alice,None,405)
