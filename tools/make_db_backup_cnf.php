<?php
declare(strict_types=1);
// 生成 mysqldump 用的凭据文件（避免密码出现在 ps / 日志里）
$config = require '/var/www/html/spxchat/config.php';
$d = $config['db'];
$lines = [
    '[client]',
    'user=' . $d['user'],
    'password=' . $d['password'],
    'host=' . $d['host'],
    'port=' . $d['port'],
    'protocol=TCP',
];
$path = '/etc/spxchat/db-backup.cnf';
file_put_contents($path, implode("\n", $lines) . "\n");
chmod($path, 0640);
chown($path, 'root');
chgrp($path, 'www-data');
$envPath = '/etc/spxchat/backup.env';
file_put_contents($envPath, 'DB_NAME=' . $d['name'] . "\n");
chmod($envPath, 0640);
chown($envPath, 'root');
chgrp($envPath, 'www-data');
printf("已写入 %s（%s）与 %s（库名 %s）\n", $path, substr(sprintf('%o', fileperms($path)), -4), $envPath, $d['name']);
