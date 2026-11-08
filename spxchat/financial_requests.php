<?php
declare(strict_types=1);

// Request reservation and business result commit in the same database transaction.
function financialRequestID(array $body): string
{
    $id = trim((string)($body['client_request_id'] ?? ''));
    if ($id !== '' && !preg_match('/^[a-zA-Z0-9_-]{8,64}$/D', $id)) throw new DomainException('请求编号格式无效', 422);
    return $id;
}
function financialAmount(string $amount): string
{
    return str_contains($amount, '.') ? (rtrim(rtrim($amount, '0'), '.') ?: '0') : $amount;
}
function financialRequestBegin(PDO $pdo, int $uid, string $action, string $id, array $payload): ?array
{
    if ($id === '') return null; // Legacy Android clients retain their current contract.
    if (!$pdo->inTransaction()) throw new LogicException('Financial request requires a transaction');
    ksort($payload);
    $hash = hash('sha256', json_encode($payload, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES));
    $pdo->prepare('INSERT INTO financial_requests(user_id,action,request_id,payload_hash) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id')->execute([$uid,$action,$id,$hash]);
    $q=$pdo->prepare('SELECT payload_hash,result_json FROM financial_requests WHERE user_id=? AND action=? AND request_id=? FOR UPDATE');
    $q->execute([$uid,$action,$id]); $row=$q->fetch();
    if (!$row || !hash_equals($row['payload_hash'],$hash)) throw new DomainException('同一请求编号不能更改提交内容',409);
    return $row['result_json'] === null ? null : json_decode($row['result_json'],true,512,JSON_THROW_ON_ERROR);
}
function financialRequestFinish(PDO $pdo,int $uid,string $action,string $id,array $result): array
{
    if ($id === '') return $result;
    $result['client_request_id']=$id;
    $pdo->prepare('UPDATE financial_requests SET result_json=? WHERE user_id=? AND action=? AND request_id=?')->execute([json_encode($result,JSON_THROW_ON_ERROR),$uid,$action,$id]);
    return $result;
}
function financialRequestResult(PDO $pdo,int $uid,string $action,string $id): array
{
    if (!in_array($action,['wallet.withdraw','staking.create','merchant.product.save'],true) || $id==='') throw new DomainException('请求编号或类型无效',422);
    financialRequestID(['client_request_id'=>$id]);
    $q=$pdo->prepare('SELECT result_json FROM financial_requests WHERE user_id=? AND action=? AND request_id=?');
    $q->execute([$uid,$action,$id]); $value=$q->fetchColumn();
    // A missing result never authorizes a new ID: the client retries only this ID.
    return $value ? ['exists'=>true,'result'=>json_decode($value,true,512,JSON_THROW_ON_ERROR)] : ['exists'=>false];
}

function financialPayload(string $action,array $body): array
{
    if($action==='wallet.withdraw') return ['symbol'=>strtoupper(trim((string)($body['symbol']??''))),'chain'=>strtoupper(trim((string)($body['chain']??'BSC'))),'amount'=>financialAmount(trim((string)($body['amount']??''))),'address'=>strtolower(trim((string)($body['address']??'')))];
    if($action==='staking.create') return ['plan_id'=>(int)($body['plan_id']??0),'amount'=>trim((string)($body['amount']??''))];
    if($action==='merchant.product.save') {
        // Only persisted product fields participate; ignore transport and recovery parameters.
        $fields=['id','version','name','description','cny_price','stock','product_type','category','detail_text','images','detail_images','video_url','parameters','service_terms','delivery_note','variants','shipping_fee','delivery_days','service_valid_days','appointment_required','consultation_group_code'];
        $payload=array_intersect_key($body,array_flip($fields));
        return financialCanonicalProductPayload($payload);
    }
    throw new DomainException('申请类型无效',422);
}
// Serialize resolution with the original transaction. If no operation committed,
// persist a tombstone so a delayed original request cannot create a debit later.
function financialRequestResolve(PDO $pdo,int $uid,string $action,array $body): array
{
    $id=financialRequestID($body);
    if($id==='') throw new DomainException('缺少请求编号',422);
    $payload=financialPayload($action,$body);
    $pdo->beginTransaction();
    try {
        $result=financialRequestBegin($pdo,$uid,$action,$id,$payload);
        if($result===null) $result=financialRequestFinish($pdo,$uid,$action,$id,['cancelled'=>true]);
        $pdo->commit(); return $result;
    } catch(Throwable $e) { if($pdo->inTransaction())$pdo->rollBack();throw $e; }
}

/** Stable nested ordering is required when JSON dictionaries are encoded after an App restart. */
function financialCanonicalProductPayload(array $payload): array
{
    if (!array_is_list($payload)) ksort($payload);
    foreach ($payload as $key => $value) if (is_array($value)) $payload[$key]=financialCanonicalProductPayload($value);
    return $payload;
}
