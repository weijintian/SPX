<?php
declare(strict_types=1);

function installMallWorkspaceSchema(PDO $pdo): void
{
    if ($pdo->query("SELECT version FROM mall_schema_versions WHERE version=4")->fetchColumn()) return;
    if ((int)$pdo->query("SELECT GET_LOCK('spx_mall_workspace_v4',30)")->fetchColumn() !== 1) throw new RuntimeException('商城升级中');
    try {
        if ($pdo->query("SELECT version FROM mall_schema_versions WHERE version=4")->fetchColumn()) return;
        foreach ([
            'products'=>['consultation_group_id'=>'BIGINT UNSIGNED NULL'],
            'orders'=>['merchant_service_reply'=>'VARCHAR(1000) NULL','service_replied_at'=>'DATETIME NULL','after_sale_requested_at'=>'DATETIME NULL'],
            'wallet_transactions'=>['mall_order_id'=>'BIGINT UNSIGNED NULL','mall_merchant_id'=>'BIGINT UNSIGNED NULL'],
        ] as $table=>$columns) {
            $existing=$pdo->query("SHOW COLUMNS FROM $table")->fetchAll(PDO::FETCH_COLUMN);
            foreach ($columns as $name=>$type) if (!in_array($name,$existing,true)) $pdo->exec("ALTER TABLE $table ADD COLUMN $name $type");
        }
        $pdo->exec("CREATE TABLE IF NOT EXISTS mall_product_drafts (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,merchant_id BIGINT UNSIGNED NOT NULL,product_id BIGINT UNSIGNED NULL,title VARCHAR(150) NOT NULL,payload MEDIUMTEXT NOT NULL,version INT NOT NULL DEFAULT 1,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,INDEX(merchant_id,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        $pdo->exec("CREATE TABLE IF NOT EXISTS mall_favorites (user_id BIGINT UNSIGNED NOT NULL,product_id BIGINT UNSIGNED NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(user_id,product_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        $pdo->exec("CREATE TABLE IF NOT EXISTS mall_reviews (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,order_id BIGINT UNSIGNED NOT NULL UNIQUE,user_id BIGINT UNSIGNED NOT NULL,product_id BIGINT UNSIGNED NOT NULL,rating TINYINT NOT NULL,content VARCHAR(1000) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,INDEX(product_id,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        $pdo->exec("CREATE TABLE IF NOT EXISTS mall_product_reports (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,user_id BIGINT UNSIGNED NOT NULL,product_id BIGINT UNSIGNED NOT NULL,reason VARCHAR(1000) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'pending',reply VARCHAR(1000) NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,INDEX(status,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        $pdo->exec("INSERT IGNORE INTO mall_schema_versions(version) VALUES(4)");
    } finally { $pdo->query("SELECT RELEASE_LOCK('spx_mall_workspace_v4')"); }
}

function mallConsultationGroup(PDO $pdo, int $uid, string $code): int
{
    if (!preg_match('/^[A-Z0-9]{6}$/', $code)) throw new InvalidArgumentException('请填写六位商品咨询群号，可在群详情复制');
    $q=$pdo->prepare("SELECT g.id FROM chat_groups g JOIN chat_group_members gm ON gm.group_id=g.id AND gm.user_id=? WHERE g.group_code=? AND (g.owner_user_id=gm.user_id OR gm.role='admin')");
    $q->execute([$uid,$code]); $id=$q->fetchColumn();
    if (!$id) throw new InvalidArgumentException('咨询群不存在，或你不是该群的群主/管理员');
    return (int)$id;
}

function installMallWorkspaceServiceSchema(PDO $pdo): void
{
    if ($pdo->query('SELECT version FROM mall_schema_versions WHERE version=5')->fetchColumn()) return;
    if ((int)$pdo->query("SELECT GET_LOCK('spx_mall_service_v5',30)")->fetchColumn()!==1) throw new RuntimeException('商城升级中');
    try {
        if ($pdo->query('SELECT version FROM mall_schema_versions WHERE version=5')->fetchColumn()) return;
        $columns=$pdo->query('SHOW COLUMNS FROM orders')->fetchAll(PDO::FETCH_COLUMN);
        if (!in_array('after_sale_requested_at',$columns,true)) $pdo->exec('ALTER TABLE orders ADD COLUMN after_sale_requested_at DATETIME NULL');
        $indexes=$pdo->query('SHOW INDEX FROM orders')->fetchAll(PDO::FETCH_ASSOC);
        if (!in_array('idx_mall_quality',array_column($indexes,'Key_name'),true)) $pdo->exec('ALTER TABLE orders ADD INDEX idx_mall_quality(merchant_id,created_at)');
        $pdo->exec('INSERT IGNORE INTO mall_schema_versions(version) VALUES(5)');
    } finally {$pdo->query("SELECT RELEASE_LOCK('spx_mall_service_v5')");}
}

function mallClosingBlockers(PDO $pdo, int $mid): int
{
    $q=$pdo->prepare("SELECT COUNT(*) FROM orders WHERE merchant_id=? AND (after_sale_status IN ('requested','return_approved','return_shipped') OR (status NOT IN ('cancelled','refunded') AND (status NOT IN ('delivered','completed') OR settlement_status<>'settled')))");
    $q->execute([$mid]); return (int)$q->fetchColumn();
}

/** Lock the owner user and merchant before calling. No extra closing waiting period. */
function mallReleaseClosedDeposit(PDO $pdo, array $m): bool
{
    if ($m['status']==='closed') return true;
    if ($m['status']!=='closing' || mallClosingBlockers($pdo,(int)$m['id'])>0) return false;
    if (mallCompareAmounts((string)$m['deposit_frozen'],'0')>0) {
        walletCreditSplit($pdo,(int)$m['owner_user_id'],'SPX001','BSC',(string)$m['deposit_frozen'],(string)$m['deposit_stakeable']);
        $pdo->prepare("INSERT INTO wallet_transactions(user_id,symbol,chain,type,amount,stakeable_amount,status,mall_merchant_id) VALUES(?,'SPX001','BSC','merchant_deposit_return',?,?,'completed',?)")->execute([$m['owner_user_id'],$m['deposit_frozen'],$m['deposit_stakeable'],$m['id']]);
        $pdo->prepare("INSERT INTO merchant_deposit_ledger(merchant_id,type,amount,stakeable_amount,balance_after,reason) VALUES(?,'release',?,?,0,'所有订单及售后结束，退还保证金')")->execute([$m['id'],$m['deposit_frozen'],$m['deposit_stakeable']]);
    }
    $pdo->prepare("UPDATE merchants SET status='closed',deposit_frozen=0,deposit_stakeable=0 WHERE id=?")->execute([$m['id']]);
    mallAudit($pdo,$m,'deposit.release',null,['amount'=>(string)$m['deposit_frozen'],'rule'=>'all_orders_finished']);
    return true;
}

function mallRequestClosure(PDO $pdo,array $m): bool
{
    if ($m['status']==='closed') return true;
    if (!in_array($m['status'],['active','awaiting_deposit','closing'],true)) throw new InvalidArgumentException('当前店铺不能申请关闭，请先联系平台处理冻结问题');
    if ($m['status']!=='closing') {
        $pdo->prepare("UPDATE merchants SET status='closing',close_requested_at=NOW() WHERE id=?")->execute([$m['id']]);
        $pdo->prepare('UPDATE products SET status=0,version=version+1 WHERE merchant_id=?')->execute([$m['id']]);
        mallAudit($pdo,$m,'close.request');$m['status']='closing';
    }
    return mallReleaseClosedDeposit($pdo,$m);
}

function mallTryFinishClosure(PDO $pdo, int $uid): void
{
    $q=$pdo->prepare("SELECT id FROM merchants WHERE owner_user_id=? AND status='closing'");$q->execute([$uid]);
    if (!$q->fetchColumn()) return;
    $pdo->beginTransaction();
    try {
        $q=$pdo->prepare("SELECT id FROM users WHERE id=? FOR UPDATE");$q->execute([$uid]);
        $m=mallOwnMerchant($pdo,$uid,true);mallReleaseClosedDeposit($pdo,$m);$pdo->commit();
    } catch(Throwable $e) {if($pdo->inTransaction())$pdo->rollBack();throw $e;}
}

function mallQualityJoin(bool $productQuality=true): string
{
    return " LEFT JOIN (SELECT merchant_id,COUNT(*) AS total_count,
        SUM(status IN ('delivered','completed')) AS finished_count,
        SUM(after_sale_requested_at IS NOT NULL) AS complaint_count,
        SUM(status='pending' AND order_type<>'local' AND NOW()>DATE_ADD(created_at,INTERVAL COALESCE(JSON_UNQUOTE(JSON_EXTRACT(product_snapshot,'$.delivery_days')),3) DAY)) AS overdue_count,
        SUM(shipped_at IS NOT NULL AND shipped_at<=DATE_ADD(created_at,INTERVAL COALESCE(JSON_UNQUOTE(JSON_EXTRACT(product_snapshot,'$.delivery_days')),3) DAY)) AS ontime_count,
        SUM(shipped_at IS NOT NULL) AS shipped_count,
        SUM(after_sale_requested_at IS NOT NULL) AS service_total,
        SUM(service_replied_at IS NOT NULL AND service_replied_at<=DATE_ADD(after_sale_requested_at,INTERVAL 24 HOUR)) AS service_count
        FROM orders WHERE merchant_id IS NOT NULL AND status<>'cancelled' AND created_at>=DATE_SUB(NOW(),INTERVAL 30 DAY) GROUP BY merchant_id) mq ON mq.merchant_id=m.id " . ($productQuality ? "LEFT JOIN (SELECT product_id,AVG(rating) AS rating,COUNT(*) AS reviews FROM mall_reviews GROUP BY product_id) rq ON rq.product_id=p.id " : '');
}
function mallQualityBoost(bool $productQuality=true): string
{
    return "(IF(m.level='gold',12,0)+IF(COALESCE(mq.total_count,0)>=5,8*mq.finished_count/mq.total_count,0)
        +IF(COALESCE(mq.shipped_count,0)>=5,5*mq.ontime_count/mq.shipped_count,0)
        +IF(COALESCE(mq.service_total,0)>0,4*mq.service_count/mq.service_total,0)
        ".($productQuality ? "+IF(COALESCE(rq.reviews,0)>=3,(rq.rating-3)*4,0)" : '')."
        -IF(COALESCE(mq.total_count,0)>0,40*mq.complaint_count/mq.total_count+30*mq.overdue_count/mq.total_count,0))";
}

function mallWorkspaceApi(string $action,string $method,array $body): void
{
    $reads=['merchant.groups','merchant.drafts','merchant.draft','merchant.finance','merchant.audit','merchant.performance','merchant.dashboard','merchant.settlements','merchant.redemptions','product.reviews','product.favorite.status','favorites'];
    $writes=['merchant.draft.save','merchant.draft.delete','merchant.products.offline','merchant.order.reply','product.favorite','product.report','product.review'];
    if(!in_array($action,array_merge($reads,$writes),true))return;
    if(($method==='GET'&&!in_array($action,$reads,true))||($method==='POST'&&!in_array($action,$writes,true))||!in_array($method,['GET','POST'],true))jsonResponse(null,'请求方式无效',405);
    $u=currentUser();$uid=(int)$u['id'];$pdo=db();
    try {
        if($action==='product.reviews'){
            $q=$pdo->prepare("SELECT r.rating,r.content,r.created_at,u.nickname FROM mall_reviews r JOIN users u ON u.id=r.user_id WHERE r.product_id=? ORDER BY r.id DESC LIMIT 30");$q->execute([(int)($_GET['product_id']??0)]);jsonResponse($q->fetchAll());
        }
        if($action==='product.favorite.status'){
            $q=$pdo->prepare('SELECT 1 FROM mall_favorites WHERE user_id=? AND product_id=?');$q->execute([$uid,(int)($_GET['product_id']??0)]);
            jsonResponse(['enabled'=>(bool)$q->fetchColumn()]);
        }
        if($action==='favorites'){
            [$size,$offset]=(isset($_GET['page'])||isset($_GET['page_size']))?mallPage():[60,0];
            $q=$pdo->prepare(mallProductSelect()." JOIN mall_favorites f ON f.product_id=p.id AND f.user_id=? WHERE ".mallVisibleWhere()." ORDER BY f.created_at DESC,p.id DESC LIMIT $size OFFSET $offset");$q->execute([$uid]);
            jsonResponse(array_map(static fn($p)=>mallDecorateProduct($pdo,$p,false),$q->fetchAll()));
        }
        if(str_starts_with($action,'product.')){
            enforceApiRateLimit('mall_product_'.$uid,60);
            $pid=(int)($body['product_id']??0);
            $q=$pdo->prepare("SELECT id FROM products WHERE id=?".($action==='product.review'?'':' AND deleted_at IS NULL'));$q->execute([$pid]);if(!$q->fetchColumn())throw new InvalidArgumentException('商品不存在或已删除');
            if($action==='product.favorite'){
                if(!empty($body['enabled']))$pdo->prepare("INSERT IGNORE INTO mall_favorites(user_id,product_id) VALUES(?,?)")->execute([$uid,$pid]);
                else $pdo->prepare("DELETE FROM mall_favorites WHERE user_id=? AND product_id=?")->execute([$uid,$pid]);
            }elseif($action==='product.report'){
                $pdo->prepare("INSERT INTO mall_product_reports(user_id,product_id,reason) VALUES(?,?,?)")->execute([$uid,$pid,mallText($body,'reason',1000,true)]);
            }else{
                $rating=filter_var($body['rating']??null,FILTER_VALIDATE_INT);if($rating===false||$rating<1||$rating>5)throw new InvalidArgumentException('评分须为 1—5');
                $q=$pdo->prepare("SELECT id FROM orders WHERE id=? AND user_id=? AND product_id=? AND status IN ('delivered','completed')");$q->execute([(int)($body['order_id']??0),$uid,$pid]);$oid=$q->fetchColumn();if(!$oid)throw new InvalidArgumentException('仅完成订单的买家可评价');
                $q=$pdo->prepare("SELECT id FROM mall_reviews WHERE order_id=?");$q->execute([$oid]);if($q->fetchColumn())throw new InvalidArgumentException('该订单已评价');
                $pdo->prepare("INSERT INTO mall_reviews(order_id,user_id,product_id,rating,content) VALUES(?,?,?,?,?)")->execute([$oid,$uid,$pid,$rating,mallText($body,'content',1000,true)]);
            }jsonResponse(null,'操作成功');
        }
        $m=mallOwnMerchant($pdo,$uid);
        if($action==='merchant.groups'){
            [$size,$offset]=(isset($_GET['page'])||isset($_GET['page_size']))?mallPage():[100,0];
            $q=$pdo->prepare("SELECT g.id,g.name,g.group_code FROM chat_groups g JOIN chat_group_members gm ON gm.group_id=g.id WHERE gm.user_id=? AND (g.owner_user_id=gm.user_id OR gm.role='admin') ORDER BY g.id DESC LIMIT $size OFFSET $offset");$q->execute([$uid]);jsonResponse($q->fetchAll());
        }
        [$size,$offset]=mallPage();
        if($action==='merchant.dashboard'){
            $mid=(int)$m['id'];
            $q=$pdo->prepare("SELECT COUNT(*) AS orders,COALESCE(SUM(total_spx),0) AS amount FROM orders WHERE merchant_id=? AND created_at>=CURDATE() AND status NOT IN ('cancelled','refunded')");
            $q->execute([$mid]);$today=$q->fetch(PDO::FETCH_ASSOC);
            $q=$pdo->prepare("SELECT COUNT(*) FROM orders WHERE merchant_id=? AND status='pending' AND order_type IN ('physical','digital')");$q->execute([$mid]);$pendingShip=(int)$q->fetchColumn();
            $q=$pdo->prepare("SELECT COUNT(*) FROM orders WHERE merchant_id=? AND status='pending' AND order_type='local'");$q->execute([$mid]);$pendingRedeem=(int)$q->fetchColumn();
            $q=$pdo->prepare("SELECT COALESCE(SUM(merchant_net_amount),0) AS net,COUNT(*) AS total FROM orders WHERE merchant_id=? AND settlement_status='held' AND status='delivered'");
            $q->execute([$mid]);$held=$q->fetch(PDO::FETCH_ASSOC);
            $q=$pdo->prepare("SELECT COALESCE(SUM(net_amount),0) AS net,COUNT(*) AS total FROM mall_settlement_ledger WHERE merchant_id=?");
            $q->execute([$mid]);$settled=$q->fetch(PDO::FETCH_ASSOC);
            $q=$pdo->prepare("SELECT DATE(created_at) AS d,COUNT(*) AS orders,COALESCE(SUM(total_spx),0) AS amount FROM orders WHERE merchant_id=? AND created_at>=DATE_SUB(CURDATE(),INTERVAL 6 DAY) AND status NOT IN ('cancelled','refunded') GROUP BY DATE(created_at) ORDER BY d");
            $q->execute([$mid]);$daily=$q->fetchAll(PDO::FETCH_ASSOC);
            $q=$pdo->prepare("SELECT COUNT(*) FROM orders WHERE merchant_id=? AND after_sale_status IN ('requested','return_approved','return_shipped')");$q->execute([$mid]);$afterSale=(int)$q->fetchColumn();
            jsonResponse(['today_orders'=>(int)$today['orders'],'today_amount'=>(string)$today['amount'],
                'pending_ship'=>$pendingShip,'pending_redeem'=>$pendingRedeem,'after_sale'=>$afterSale,
                'pending_net'=>(string)$held['net'],'pending_count'=>(int)$held['total'],
                'settled_net'=>(string)$settled['net'],'settled_count'=>(int)$settled['total'],
                'daily_7d'=>$daily]);
        }
        if($action==='merchant.settlements'){
            $mid=(int)$m['id'];$state=(string)($_GET['status'] ?? 'all');
            $rows=[];
            if($state==='all'||$state==='pending'){
                $q=$pdo->prepare("SELECT o.id AS order_id,o.total_spx AS gross,o.settlement_fee AS fee,o.merchant_net_amount AS net,o.settlement_fee_rate AS fee_rate,o.order_type,o.received_at,o.service_expires_at,p.name AS product_name FROM orders o LEFT JOIN products p ON p.id=o.product_id WHERE o.merchant_id=? AND o.settlement_status='held' AND o.status='delivered' ORDER BY o.id DESC LIMIT 100");
                $q->execute([$mid]);
                foreach($q->fetchAll(PDO::FETCH_ASSOC) as $row){$row['state']='pending';$row['time']=$row['received_at'];$rows[]=$row;}
            }
            if($state==='all'||$state==='settled'){
                $q=$pdo->prepare("SELECT l.order_id,l.gross_amount AS gross,l.fee_amount AS fee,l.net_amount AS net,l.fee_rate,o.order_type,o.received_at,l.created_at,p.name AS product_name FROM mall_settlement_ledger l LEFT JOIN orders o ON o.id=l.order_id LEFT JOIN products p ON p.id=o.product_id WHERE l.merchant_id=? ORDER BY l.created_at DESC LIMIT 100");
                $q->execute([$mid]);
                foreach($q->fetchAll(PDO::FETCH_ASSOC) as $row){$row['state']='settled';$row['time']=$row['created_at'];$rows[]=$row;}
            }
            usort($rows,static fn($a,$b)=>strcmp((string)$b['time'],(string)$a['time']));
            jsonResponse(array_slice($rows,0,100));
        }
        if($action==='merchant.redemptions'){
            $mid=(int)$m['id'];
            $q=$pdo->prepare("SELECT o.id AS order_id,o.redeem_code,o.total_spx,o.received_at,o.settlement_status,o.status,p.name AS product_name,u.nickname AS buyer FROM orders o LEFT JOIN products p ON p.id=o.product_id LEFT JOIN users u ON u.id=o.user_id WHERE o.merchant_id=? AND o.order_type='local' AND o.status IN ('delivered') AND o.received_at IS NOT NULL ORDER BY o.received_at DESC LIMIT 100");
            $q->execute([$mid]);
            jsonResponse($q->fetchAll(PDO::FETCH_ASSOC));
        }
        if($action==='merchant.drafts'||$action==='merchant.draft'){
            $sql="SELECT * FROM mall_product_drafts WHERE merchant_id=?";$params=[$m['id']];
            if($action==='merchant.draft'){$sql.=" AND id=?";$params[]=(int)($_GET['id']??0);}
            $q=$pdo->prepare($sql." ORDER BY id DESC LIMIT $size OFFSET $offset");$q->execute($params);$rows=$q->fetchAll();
            foreach($rows as &$row)$row['payload']=json_decode($row['payload'],true);unset($row);
            if($action==='merchant.draft'){if(!$rows)throw new InvalidArgumentException('草稿不存在');jsonResponse($rows[0]);}jsonResponse($rows);
        }
        if($action==='merchant.finance'){
            $q=$pdo->prepare("SELECT t.id,t.type,t.symbol,t.amount,t.stakeable_amount,t.status,t.mall_order_id,t.mall_merchant_id,t.created_at,o.total_spx AS gross_amount,o.settlement_fee AS fee_amount,o.merchant_net_amount AS net_amount,o.settlement_fee_rate AS fee_rate FROM wallet_transactions t LEFT JOIN orders o ON o.id=t.mall_order_id WHERE t.user_id=? AND (t.mall_merchant_id=? OR t.type IN ('merchant_deposit','merchant_deposit_return','merchant_settlement')) ORDER BY t.id DESC LIMIT $size OFFSET $offset");$q->execute([$uid,$m['id']]);jsonResponse($q->fetchAll());
        }
        if($action==='merchant.audit'){
            $q=$pdo->prepare("SELECT id,action,target_id,metadata,created_at FROM merchant_audit_logs WHERE merchant_id=? ORDER BY id DESC LIMIT $size OFFSET $offset");$q->execute([$m['id']]);jsonResponse($q->fetchAll());
        }
        if($action==='merchant.performance'){
            $q=$pdo->prepare("SELECT COALESCE(mq.total_count,0) AS orders_30_days,COALESCE(mq.complaint_count,0) AS after_sale_count,COALESCE(mq.overdue_count,0) AS overdue_count,COALESCE(mq.ontime_count,0) AS ontime_count,COALESCE(mq.shipped_count,0) AS shipped_count,COALESCE(mq.service_total,0) AS service_requests,COALESCE(mq.service_count,0) AS timely_replies,".mallQualityBoost(false)." AS exposure_score,m.business_hours FROM merchants m ".mallQualityJoin(false)." WHERE m.id=? LIMIT 1");$q->execute([$m['id']]);jsonResponse($q->fetch());
        }
        $pdo->beginTransaction();$m=mallOwnMerchant($pdo,$uid,true);
        if($action==='merchant.draft.save'){
            if(!in_array($m['status'],['active','awaiting_deposit'],true))throw new InvalidArgumentException('当前店铺不能保存草稿');
            $payload=$body['payload']??null;if(!is_array($payload))throw new InvalidArgumentException('草稿格式无效');
            $productId=(int)($payload['id']??0);
            if($productId>0){$q=$pdo->prepare('SELECT id FROM products WHERE id=? AND merchant_id=? AND deleted_at IS NULL FOR UPDATE');$q->execute([$productId,$m['id']]);if(!$q->fetchColumn())throw new InvalidArgumentException('商品已删除或无权保存草稿');}
            $json=json_encode($payload,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);if(strlen($json)>120000)throw new InvalidArgumentException('草稿过大');
            $title=mb_substr(mallText($payload,'name',150)?:'未命名商品',0,150);$id=(int)($body['id']??0);
            if($id){$q=$pdo->prepare("SELECT version FROM mall_product_drafts WHERE id=? AND merchant_id=? FOR UPDATE");$q->execute([$id,$m['id']]);$version=$q->fetchColumn();if(!$version||(int)$version!==(int)($body['version']??0))throw new InvalidArgumentException('草稿已变化，请刷新');
                $pdo->prepare("UPDATE mall_product_drafts SET title=?,payload=?,version=version+1 WHERE id=? AND merchant_id=?")->execute([$title,$json,$id,$m['id']]);
            }else{$pdo->prepare("INSERT INTO mall_product_drafts(merchant_id,product_id,title,payload) VALUES(?,?,?,?)")->execute([$m['id'],((int)($payload['id']??0))?:null,$title,$json]);$id=(int)$pdo->lastInsertId();}
            mallAudit($pdo,$m,'draft.save',$id);$pdo->commit();jsonResponse(['id'=>$id,'version'=>isset($version)?(int)$version+1:1],'草稿已保存');
        }elseif($action==='merchant.draft.delete'){
            $pdo->prepare("DELETE FROM mall_product_drafts WHERE id=? AND merchant_id=?")->execute([(int)($body['id']??0),$m['id']]);
        }elseif($action==='merchant.products.offline'){
            $ids=$body['ids']??[];if(!is_array($ids)||!$ids||count($ids)>50)throw new InvalidArgumentException('一次选择 1—50 件商品');
            $ids=array_values(array_unique(array_map('intval',$ids)));$marks=implode(',',array_fill(0,count($ids),'?'));
            $q=$pdo->prepare("SELECT id FROM products WHERE merchant_id=? AND deleted_at IS NULL AND id IN ($marks) FOR UPDATE");$q->execute(array_merge([$m['id']],$ids));if(count($q->fetchAll())!==count($ids))throw new InvalidArgumentException('部分商品不存在或不属于你的店铺');
            $pdo->prepare("UPDATE products SET status=0,version=version+1 WHERE merchant_id=? AND id IN ($marks)")->execute(array_merge([$m['id']],$ids));mallAudit($pdo,$m,'products.offline',null,['ids'=>$ids]);
        }else{
            $q=$pdo->prepare("SELECT * FROM orders WHERE id=? AND merchant_id=? FOR UPDATE");$q->execute([(int)($body['order_id']??0),$m['id']]);$o=$q->fetch();if(!$o)throw new InvalidArgumentException('订单不存在');
            if(!in_array($o['after_sale_status'],['requested','return_approved','return_shipped'],true))throw new InvalidArgumentException('没有待回复售后');
            $reply=mallText($body,'reply',1000,true);$pdo->prepare("UPDATE orders SET merchant_service_reply=?,service_replied_at=COALESCE(service_replied_at,NOW()) WHERE id=?")->execute([$reply,$o['id']]);
            mallEvent($pdo,(int)$o['id'],'merchant_reply',mb_substr('商家回复：'.$reply,0,500));mallAudit($pdo,$m,'order.reply',(int)$o['id']);
        }
        $pdo->commit();jsonResponse(null,'操作成功');
    }catch(InvalidArgumentException $e){if($pdo->inTransaction())$pdo->rollBack();jsonResponse(null,$e->getMessage(),422);}
}
