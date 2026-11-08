<?php
declare(strict_types=1);
if (PHP_SAPI !== 'cli') { http_response_code(404); exit; }
require_once __DIR__.'/db.php';
require_once __DIR__.'/mall.php';
require_once __DIR__.'/ws-event.php';
require_once __DIR__.'/chat_automation.php';
require_once __DIR__.'/chat_groups.php';
require_once __DIR__.'/chat_red_packets.php';
try { $result=chatRunDue(db());$result['red_packet_expirations']=redPacketSweep(db(),200);echo json_encode($result,JSON_UNESCAPED_UNICODE).PHP_EOL; }
catch(Throwable $e) { error_log('Chat schedule worker: '.$e->getMessage()); exit(1); }
