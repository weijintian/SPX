<?php
declare(strict_types=1);
require_once dirname(__DIR__) . '/mall_valuation.php';
function check(bool $ok, string $label): void { if (!$ok) throw new RuntimeException($label); }
foreach (['6.7','6.70','7','0.000001','100','100.000000'] as $rate) check(mallValidUsdtCny($rate), 'valid rate ' . $rate);
foreach (['0','-1','100.000001','101','1e1','NaN','6.1234567','', ' 6.7', '6.7<script>'] as $rate) check(!mallValidUsdtCny($rate), 'invalid rate ' . $rate);
$pricing = ['spx_usd' => '0.001', 'usd_cny' => '6.7'];
check(mallCnyToSpx('168', $pricing) === '25074.62686567', 'fixed CNY conversion');
$pricing['spx_usd'] = '0.002';
check(mallCnyToSpx('168', $pricing) === '12537.31343283', 'higher market price lowers token amount');
$pricing['spx_usd'] = '0.000000000000000001';
check(mallCnyToSpx('0.00000001', $pricing) === '1492537313.43283582', 'tiny market price retains precision');
foreach ([['168', '0', '6.7'], ['168', '-1', '6.7'], ['168', '0.001', '7'], ['0', '0.001', '6.7'], ['-1', '0.001', '6.7']] as [$cny, $usd, $fx]) {
    $rejected = false;
    try { mallCnyToSpx($cny, ['spx_usd' => $usd, 'usd_cny' => $fx]); }
    catch (InvalidArgumentException $e) { $rejected = true; }
    check($rejected, 'invalid price or FX rejected');
}
echo "Mall pricing validation passed: 24 cases\n";
