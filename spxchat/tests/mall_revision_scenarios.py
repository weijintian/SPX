"""Capacity, legacy group identity, deletion and decimal settlement regressions.

Runs only inside mall_integration.py's disposable database and PHP workers.
"""
import json
import secrets
import subprocess
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier


def run(g):
    request, sql, check, admin = (g[k] for k in ('request', 'sql', 'check', 'admin_post'))
    owner, member = 70, 71
    token, member_token = secrets.token_hex(32), secrets.token_hex(32)
    for uid, credential in [(owner, token), (member, member_token)]:
        sql(f"INSERT INTO users(id,username,nickname,spx_id,password_hash) VALUES({uid},'revision{uid}','Revision {uid}','spx9070{uid}','test'); INSERT INTO user_tokens(user_id,token,expires_at) VALUES({uid},'{credential}',DATE_ADD(NOW(),INTERVAL 1 DAY)); INSERT INTO wallet_assets(user_id,symbol,chain,balance,stakeable_balance) VALUES({uid},'SPX001','BSC',2000000,1000000)")
    doc = g['upload'](g['valid_png'], 'image/png', 200, 'merchant.qualification.upload', token)['id']
    profile = dict(g['profile'], name='Revision Shop', qualification_images=[doc])
    request('merchant.apply', token, profile)
    mid = int(request('merchant.me', token)['merchant']['id'])
    admin('mall.merchant.review', merchant_id=mid, decision='approve')
    request('merchant.deposit.pay', token, {'level': 'basic'})
    member_doc = g['upload'](g['valid_png'], 'image/png', 200, 'merchant.qualification.upload', member_token)['id']
    request('merchant.apply', member_token, dict(g['profile'], name='Administrator Shop', qualification_images=[member_doc]))
    sql(f"INSERT INTO chat_groups(owner_user_id,name,group_code) VALUES({owner},'Legacy owner group','REV070')")
    gid = int(sql("SELECT id FROM chat_groups WHERE group_code='REV070'"))
    sql(f"INSERT INTO chat_groups(owner_user_id,name,group_code) VALUES({owner},'Missing owner group','REV071')")
    missing_gid = int(sql("SELECT id FROM chat_groups WHERE group_code='REV071'"))
    sql(f"INSERT INTO chat_group_members(group_id,user_id,role) VALUES({gid},{owner},'member'),({gid},{member},'owner'),({missing_gid},{member},'admin')")
    sql("DELETE FROM app_settings WHERE `key`='runtime_schema_fingerprint'")
    request('health')
    check(sql(f"SELECT role FROM chat_group_members WHERE group_id={gid} AND user_id={owner}") == 'owner', 'legacy creator member role is repaired to owner')
    check(sql(f"SELECT role FROM chat_group_members WHERE group_id={gid} AND user_id={member}") == 'member', 'stale owner is demoted without guessing ownership')
    check(sql(f"SELECT role FROM chat_group_members WHERE group_id={missing_gid} AND user_id={owner}") == 'owner', 'missing creator membership is restored')
    check(sql(f"SELECT role FROM chat_group_members WHERE group_id={missing_gid} AND user_id={member}") == 'admin', 'repair preserves valid administrators')
    check({x['group_code'] for x in request('merchant.groups', token)} == {'REV070', 'REV071'}, 'old managed groups appear in consultation picker')
    check(request('merchant.groups', member_token)[0]['group_code'] == 'REV071', 'administrator can select group but ordinary member cannot')
    cap = lambda: request('merchant.me', token)['product_capacity']
    check(cap() == {'listed': 0, 'limit': 6, 'remaining': 6}, 'basic merchant initially has six listing slots')
    product = dict(g['product'], consultation_group_code='REV070', variants=[], stock=50)
    ids = [request('merchant.product.save', token, dict(product, name=f'Capacity basic {i}'))['id'] for i in range(6)]
    check(cap() == {'listed': 6, 'limit': 6, 'remaining': 0}, 'six basic listings fill the capacity exactly')
    request('merchant.product.save', token, dict(product, name='Overflow basic'), 422)
    original = next(x for x in request('merchant.products', token) if int(x['id']) == ids[0])
    check(original['consultation_group_code'] == 'REV070', 'legacy consultation group is retained in merchant product editor')
    check(request(f'product&id={ids[0]}', g['buyer'])['consultation_group']['group_code'] == 'REV070', 'legacy consultation group is decorated on public products')
    request('merchant.product.save', token, dict(product, id=ids[0], version=original['version'], name='Edited at capacity'))
    check(cap()['listed'] == 6, 'editing an already listed product is allowed at capacity')
    # Admin-configured limits apply without a new client build.
    save_limits = lambda basic, gold: admin('settings.mall_limits.save', mall_basic_product_limit=basic, mall_gold_product_limit=gold)
    settings_page = g['admin_browser'].open(g['admin_url']+'admin.php?section=settings').read().decode()
    check('name="mall_basic_product_limit"' in settings_page and 'name="mall_gold_product_limit"' in settings_page, 'backend settings renders both editable listing limits')
    save_limits(7, 23)
    check(cap() == {'listed': 6, 'limit': 7, 'remaining': 1}, 'admin increasing basic limit immediately exposes an extra slot')
    extra = request('merchant.product.save', token, dict(product, name='Configured seventh slot'))['id']
    check(cap()['listed'] == 7 and cap()['remaining'] == 0, 'configured basic limit actually permits publishing a seventh product')
    # Re-running the migration must preserve edits, including when its marker is absent.
    sql("DELETE FROM mall_schema_versions WHERE version=8; DELETE FROM app_settings WHERE `key`='runtime_schema_fingerprint'")
    request('health')
    check(cap()['limit'] == 7, 'schema initialization preserves existing admin limits')
    save_limits(4, 23)
    check(cap() == {'listed': 7, 'limit': 4, 'remaining': 0}, 'lower limit retains existing products without a negative remaining count')
    request('merchant.product.save', token, dict(product, name='Reduced limit overflow'), 422)
    fresh = next(x for x in request('merchant.products', token) if int(x['id']) == ids[0])
    request('merchant.product.save', token, dict(product, id=ids[0], version=fresh['version'], name='Edited above new limit'))
    check(cap()['listed'] == 7, 'existing listed product remains editable above reduced limit')
    configured = sql("SELECT `value` FROM app_settings WHERE `key`='mall_product_limits'")
    for bad in ['-1', '1.5', '', '1000001', 'text']:
        save_limits(9, bad)
        check(sql("SELECT `value` FROM app_settings WHERE `key`='mall_product_limits'") == configured, 'invalid admin limit is rejected atomically: '+repr(bad))
    sql("UPDATE admin_users SET role='operator' WHERE id=1")
    try:
        save_limits(100, 100)
        check(sql("SELECT `value` FROM app_settings WHERE `key`='mall_product_limits'") == configured, 'operator cannot change listing limits')
    finally:
        sql("UPDATE admin_users SET role='superadmin' WHERE id=1")
    save_limits(0, 23)
    check(cap()['limit'] == 0 and cap()['remaining'] == 0, 'zero limit pauses new basic listings')
    request('merchant.product.save', token, dict(product, name='Zero limit overflow'), 422)
    request('merchant.product.offline', token, {'id': extra})
    request('merchant.product.delete', token, {'id': extra})
    save_limits(6, 20)
    request('merchant.product.delete', token, {'id': ids[0]}, 422)
    request('merchant.product.offline', token, {'id': ids[0]})
    check(cap()['remaining'] == 1, 'offlining immediately releases one slot')
    offline = next(x for x in request('merchant.products', token) if int(x['id']) == ids[0])
    request('merchant.product.save', token, dict(product, id=ids[0], version=offline['version'], name='Relisted basic'))
    check(cap()['remaining'] == 0, 'relisting uses the released slot')
    request('merchant.product.offline', token, {'id': ids[0]})
    request('merchant.product.delete', member_token, {'id': ids[0]}, 422)
    request('merchant.product.delete', token, {'id': ids[0]})
    request('merchant.product.delete', token, {'id': ids[0]})
    check(len(request('merchant.products', token)) == 5 and cap()['remaining'] == 1, 'offline deletion is idempotent and releases capacity')
    request('merchant.product.save', token, dict(product, id=ids[0], version=offline['version']), 422)
    request('merchant.draft.save', token, {'payload': dict(product, id=ids[0])}, 422)
    request('checkout.quote', g['buyer'], {'product_id': ids[0], 'quantity': 1, 'address_id': g['second']}, 422)
    # Two real PHP workers contend for the last slot. Exactly one may publish.
    second_port = g['port']()
    log = (g['temp']/'php-capacity.log').open('w', encoding='utf-8')
    worker = subprocess.Popen(g['php_args']+['-S', f'127.0.0.1:{second_port}', '-t', str(g['app'])], stdout=log, stderr=subprocess.STDOUT)
    try:
        g['wait_port'](second_port)
        barrier = Barrier(2)
        def publish(endpoint):
            body = dict(product, name=f'Concurrent capacity {endpoint}')
            req = urllib.request.Request(f'http://127.0.0.1:{endpoint}/api.php?action=merchant.product.save', data=json.dumps(body).encode(), headers={'Authorization': 'Bearer '+token, 'Content-Type': 'application/json'})
            barrier.wait(timeout=10)
            try: response = urllib.request.urlopen(req, timeout=30)
            except urllib.error.HTTPError as error: response = error
            response.read()
            return response.code
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(publish, endpoint) for endpoint in [g['api_port'], second_port]]
            codes = [future.result() for future in futures]
        check(sorted(codes) == [200, 422] and cap()['listed'] == 6, 'concurrent publishes cannot exceed the final basic slot')
    finally:
        worker.terminate(); worker.wait(timeout=10); log.close()
    sql(f"INSERT INTO products(merchant_id,name,description,image_url,spx_price,stock,product_type,status,review_status) VALUES({mid},'Pending cap approval','Review','https://example.com/a.png',10,1,'physical',0,'pending')")
    pending = int(sql("SELECT id FROM products WHERE name='Pending cap approval'"))
    admin('mall.product.review', product_id=pending, decision='approve')
    check(sql(f'SELECT status FROM products WHERE id={pending}') == '0', 'admin approval cannot bypass full capacity')
    request('merchant.deposit.pay', token, {'level': 'gold'})
    check(cap() == {'listed': 6, 'limit': 20, 'remaining': 14}, 'gold upgrade expands capacity to twenty')
    for i in range(14):
        request('merchant.product.save', token, dict(product, name=f'Capacity gold {i}'))
    check(cap() == {'listed': 20, 'limit': 20, 'remaining': 0}, 'twenty gold listings fill capacity exactly')
    request('merchant.product.save', token, dict(product, name='Overflow gold'), 422)
    save_limits(6, 21)
    check(cap()['limit'] == 21 and cap()['remaining'] == 1, 'gold capacity also reads the admin-configured value')
    gold_extra = request('merchant.product.save', token, dict(product, name='Configured gold extra'))['id']
    check(cap()['listed'] == 21, 'configured gold slot can be filled beyond the original default')
    request('merchant.product.offline', token, {'id': gold_extra})
    request('merchant.product.delete', token, {'id': gold_extra})
    save_limits(6, 20)
    request('merchant.product.offline', token, {'id': ids[1]})
    admin('mall.product.review', product_id=pending, decision='approve')
    check(cap()['listed'] == 20 and sql(f'SELECT status FROM products WHERE id={pending}') == '1', 'admin approval consumes a genuinely free slot')
    # Preserve all order history when its product is deleted; settle the net once.
    request('merchant.product.offline', token, {'id': pending})
    paid_product = request('merchant.product.save', token, dict(product, name='Fee precision', shipping_fee='0.12345678'))['id']
    purchase = request('orders.redeem', g['buyer'], {'product_id': paid_product, 'quantity': 1, 'address_id': g['second']}, 201)['order_id']
    check(sql(f"SELECT CONCAT(total_spx,':',settlement_fee,':',merchant_net_amount) FROM orders WHERE id={purchase}") == '10.12345678:1.01234567:9.11111111', 'fee includes shipping and truncates precisely at eight decimals')
    request('product.favorite', g['buyer'], {'product_id': paid_product, 'enabled': True})
    request('merchant.product.offline', token, {'id': paid_product})
    request('merchant.product.delete', token, {'id': paid_product})
    check(request(f'order&id={purchase}', g['buyer'])['name'] == 'Fee precision', 'deleting product preserves buyer order snapshot')
    check(not any(int(x['id']) == paid_product for x in request('favorites', g['buyer'])), 'deleted product is removed from favorites')
    check(sql(f'SELECT COUNT(*) FROM products WHERE id={paid_product}') == '1', 'soft deletion retains product media and historical row')
    request('merchant.order.ship', token, {'order_id': purchase, 'carrier': 'Test', 'tracking_number': 'REV123'})
    request('orders.receive', g['buyer'], {'order_id': purchase})
    sql(f'UPDATE orders SET received_at=DATE_SUB(NOW(),INTERVAL 8 DAY) WHERE id={purchase}')
    before = sql(f"SELECT balance FROM wallet_assets WHERE user_id={owner} AND symbol='SPX001'")
    admin('mall.order.settle', order_id=purchase)
    admin('mall.order.settle', order_id=purchase)
    check(sql(f"SELECT balance-CAST('{before}' AS DECIMAL(36,18))=9.11111111 FROM wallet_assets WHERE user_id={owner} AND symbol='SPX001'") == '1', 'merchant receives exactly ninety percent once')
    check(sql(f'SELECT COUNT(*)=1 AND MIN(gross_amount=fee_amount+net_amount)=1 FROM mall_settlement_ledger WHERE order_id={purchase}') == '1', 'unique settlement ledger balances gross fee and net')
    check(sql(f'SELECT settlement_fee_rate FROM orders WHERE id={purchase}') == '0.1000', 'order stores immutable ten percent fee terms')
    # Ownership transfer must update both authoritative identity and membership.
    request('groups.transfer', token, {'group_id': gid, 'owner_user_id': member})
    check(sql(f"SELECT CONCAT(user_id,':',role) FROM chat_group_members WHERE group_id={gid} AND role='owner'") == f'{member}:owner', 'group transfer leaves exactly one authoritative owner')
    check('REV070' not in {x['group_code'] for x in request('merchant.groups', token)}, 'former owner no longer appears as manager after transfer')
