<?php
declare(strict_types=1);
require dirname(__DIR__) . '/financial_requests.php';

// No network or production database. This PDO double checks reservation state transitions,
// not MySQL row locking or concurrent transaction isolation.
final class RecoveryTestPDO extends PDO {
    public array $rows = [];
    private bool $active = false;
    private array $before = [];
    public function __construct() {}
    public function inTransaction(): bool { return $this->active; }
    public function beginTransaction(): bool { $this->before=$this->rows; $this->active=true; return true; }
    public function commit(): bool { $this->active=false; return true; }
    public function rollBack(): bool { $this->rows=$this->before; $this->active=false; return true; }
    public function prepare(string $query, array $options=[]): PDOStatement|false { return new RecoveryTestStatement($this,$query); }
}
final class RecoveryTestStatement extends PDOStatement {
    private mixed $result = false;
    public function __construct(private RecoveryTestPDO $db, private string $sql) {}
    public function execute(?array $params=null): bool {
        $p=$params ?? [];
        if (str_starts_with($this->sql,'INSERT INTO financial_requests')) {
            $key=json_encode(array_slice($p,0,3));
            $this->db->rows[$key] ??= ['payload_hash'=>$p[3],'result_json'=>null];
        } elseif (str_starts_with($this->sql,'UPDATE financial_requests')) {
            $key=json_encode(array_slice($p,1,3)); $this->db->rows[$key]['result_json']=$p[0];
        } elseif (str_starts_with($this->sql,'SELECT payload_hash')) {
            $this->result=$this->db->rows[json_encode($p)] ?? false;
        } elseif (str_starts_with($this->sql,'SELECT result_json')) {
            $this->result=$this->db->rows[json_encode($p)]['result_json'] ?? false;
        } else { throw new RuntimeException('Unexpected test query'); }
        return true;
    }
    public function fetch(int $mode=PDO::FETCH_DEFAULT,int $cursorOrientation=PDO::FETCH_ORI_NEXT,int $cursorOffset=0): mixed { return $this->result; }
    public function fetchColumn(int $column=0): mixed { return $this->result; }
}
$count=0;
function check(bool $value,string $label): void { global $count; if(!$value)throw new RuntimeException($label);$count++; }
function rejects(callable $operation,int $code): void {
    try { $operation(); } catch(DomainException $e) { check($e->getCode()===$code,'Wrong rejection code');return; }
    throw new RuntimeException('Expected rejection');
}
$db=new RecoveryTestPDO();
$action='merchant.product.save';$id='product-request-123';
$a=['name'=>'Test','images'=>['a','b'],'variants'=>[['name'=>'One','id'=>'v1','attributes'=>['size'=>'M','color'=>'blue']]]];
$b=['variants'=>[['attributes'=>['color'=>'blue','size'=>'M'],'id'=>'v1','name'=>'One']],'images'=>['a','b'],'name'=>'Test'];
$p=financialPayload($action,$a); $q=financialPayload($action,$b);
check($p===$q,'JSON dictionary order must not change the payload');
check(financialPayload($action,$a+['operation'=>$action,'client_request_id'=>$id])===$p,'Recovery transport keys must not enter payload hash');
check(financialPayload($action,array_replace($a,['images'=>['b','a']]))!==$p,'Photo order must remain significant');
check(financialRequestBegin($db,7,$action,'',$p)===null,'Legacy request must not require reservation');
try { financialRequestBegin($db,7,$action,$id,$p); throw new RuntimeException('Transaction required'); }
catch(LogicException $e) { check(true,'Guard transaction'); }
$db->beginTransaction();
check(financialRequestBegin($db,7,$action,$id,$p)===null,'First request reserves only');
financialRequestFinish($db,7,$action,$id,['id'=>81]);$db->commit();
$db->beginTransaction();
check(financialRequestBegin($db,7,$action,$id,$q)['id']===81,'Retry must return existing product');
$db->commit();
$result=financialRequestResolve($db,7,$action,$b+['client_request_id'=>$id]);
check($result['id']===81,'Response-loss recovery must retain committed product');
rejects(fn()=>financialRequestResolve($db,7,$action,array_replace($a,['name'=>'Changed','client_request_id'=>$id])),409);
check(!$db->inTransaction(),'Payload mismatch must roll back');
$cancel='cancel-product-123';
check(financialRequestResolve($db,7,$action,$a+['client_request_id'=>$cancel])['cancelled']===true,'Unknown request must create cancellation tombstone');
$db->beginTransaction();
check(financialRequestBegin($db,7,$action,$cancel,$p)['cancelled']===true,'Delayed original cannot bypass cancellation');
$db->commit();
check(financialRequestResult($db,8,$action,$id)['exists']===false,'Recovery must be account scoped');
rejects(fn()=>financialRequestID(['client_request_id'=>'bad']),422);
echo json_encode(['passed'=>$count,'production_writes'=>false,'mysql_locking_tested'=>false],JSON_PRETTY_PRINT).PHP_EOL;
