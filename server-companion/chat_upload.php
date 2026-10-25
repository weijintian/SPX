<?php
declare(strict_types=1);

const CHAT_UPLOAD_MAX_BYTES = 1073741824;
const CHAT_UPLOAD_CHUNK_BYTES = 1048576;

function chatWriteUploadCheckpoint(string $path, array $value): void
{
    $json = json_encode($value, JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
    $temporary = tempnam(dirname($path), 'checkpoint_');
    if ($temporary === false) throw new RuntimeException('无法保存上传任务');
    try {
        if (file_put_contents($temporary, $json, LOCK_EX) !== strlen($json) || !rename($temporary, $path)) {
            throw new RuntimeException('无法保存上传任务');
        }
    } finally {
        if (is_file($temporary)) @unlink($temporary);
    }
}

function chatUploadFormat(string $path): array
{
    $mime = (new finfo(FILEINFO_MIME_TYPE))->file($path);
    $formats = [
        'image/jpeg'=>['jpg','image'], 'image/png'=>['png','image'],
        'image/webp'=>['webp','image'], 'image/gif'=>['gif','image'],
        'image/heic'=>['heic','image'], 'image/heif'=>['heif','image'],
        'video/mp4'=>['mp4','video'], 'video/quicktime'=>['mov','video'],
        'video/webm'=>['webm','video'], 'video/x-matroska'=>['mkv','video'],
        'video/3gpp'=>['3gp','voice'], 'audio/3gpp'=>['3gp','voice'],
        'audio/amr'=>['amr','voice'], 'audio/mp4'=>['m4a','voice'],
        'audio/x-m4a'=>['m4a','voice'], 'audio/aac'=>['aac','voice'],
        'audio/mpeg'=>['mp3','voice'], 'audio/ogg'=>['ogg','voice'],
        'audio/wav'=>['wav','voice'], 'audio/x-wav'=>['wav','voice'],
        'application/pdf'=>['pdf','file'], 'text/plain'=>['txt','file'],
        'text/csv'=>['csv','file'], 'application/rtf'=>['rtf','file'], 'text/rtf'=>['rtf','file'],
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document'=>['docx','file'],
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'=>['xlsx','file'],
        'application/vnd.openxmlformats-officedocument.presentationml.presentation'=>['pptx','file'],
        'application/vnd.ms-excel'=>['xls','file'], 'application/msword'=>['doc','file'],
        'application/vnd.ms-powerpoint'=>['ppt','file'], 'application/zip'=>['zip','file'],
        'application/x-zip-compressed'=>['zip','file'], 'application/x-7z-compressed'=>['7z','file'],
        'application/vnd.rar'=>['rar','file'], 'application/x-rar'=>['rar','file'],
        'application/x-rar-compressed'=>['rar','file'],
    ];
    if (!is_string($mime) || !isset($formats[$mime])) throw new DomainException('文件格式暂不支持，请选择常用图片、视频、音频或文档', 422);
    [$extension, $type] = $formats[$mime];
    // Office archives can be reported as ZIP by libmagic. Preserve a verified extension.
    if ($extension === 'zip' && class_exists(ZipArchive::class)) {
        $zip = new ZipArchive();
        if ($zip->open($path) === true) {
            try {
                if ($zip->locateName('[Content_Types].xml') !== false) {
                    foreach (['docx'=>'word/document.xml','xlsx'=>'xl/workbook.xml','pptx'=>'ppt/presentation.xml'] as $ext=>$entry) {
                        if ($zip->locateName($entry) !== false) { $extension = $ext; break; }
                    }
                }
            } finally { $zip->close(); }
        }
    }
    return [$extension, $type, $mime];
}

function chatSaveUpload(string $source, int $uid, string $directory, string $baseUrl): array
{
    $size = filesize($source);
    if ($size === false || $size < 1 || $size > CHAT_UPLOAD_MAX_BYTES) throw new DomainException('附件不能为空且不能超过 1 GB', 413);
    [$extension, $type, $mime] = chatUploadFormat($source);
    if (!is_dir($directory) && !mkdir($directory, 0755, true) && !is_dir($directory)) throw new RuntimeException('附件存储空间不可用');
    $filename = 'message_' . $uid . '_' . bin2hex(random_bytes(16)) . '.' . $extension;
    $destination = $directory . '/' . $filename;
    if (is_uploaded_file($source)) $saved = move_uploaded_file($source, $destination);
    else $saved = @rename($source, $destination);
    if (!$saved && !is_uploaded_file($source)) {
        $staged = $destination . '.tmp';
        $input = @fopen($source, 'rb'); $output = @fopen($staged, 'xb');
        if ($input !== false && $output !== false) {
            try { $saved = stream_copy_to_stream($input, $output) === $size && fflush($output); }
            finally { fclose($input); fclose($output); }
            if ($saved) $saved = @rename($staged, $destination);
        } else {
            if (is_resource($input)) fclose($input);
            if (is_resource($output)) fclose($output);
        }
        if (!$saved) @unlink($staged);
    }
    if (!$saved) throw new RuntimeException('附件保存失败');
    @chmod($destination, 0644);
    return ['url'=>rtrim($baseUrl, '/') . '/uploads/messages/' . $filename, 'message_type'=>$type, 'mime'=>$mime, 'size'=>$size];
}

/** Filesystem work is serialized per account, including retries and expired-upload cleanup. */
function chatReceiveChunk(int $uid, array $post, array $file, string $directory, string $tempBase, string $baseUrl): array
{
    $id = strtolower((string)($post['upload_id'] ?? ''));
    $index = filter_var($post['chunk_index'] ?? null, FILTER_VALIDATE_INT);
    $total = filter_var($post['total_chunks'] ?? null, FILTER_VALIDATE_INT);
    $expected = isset($post['total_bytes']) ? filter_var($post['total_bytes'], FILTER_VALIDATE_INT) : null;
    if ($uid < 1 || !preg_match('/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/D', $id) || $index === false || $total === false || $total < 1 || $total > 1024 || $index < 0 || $index >= $total) throw new DomainException('分块上传参数无效', 422);
    if ($expected !== null && ($expected === false || $expected < 1 || $expected > CHAT_UPLOAD_MAX_BYTES || (int)ceil($expected / CHAT_UPLOAD_CHUNK_BYTES) !== $total)) throw new DomainException('附件大小无效', 422);
    $source = (string)($file['tmp_name'] ?? '');
    if (($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK || !is_uploaded_file($source)) throw new DomainException('上传分块无效，请重试', 422);
    $size = filesize($source);
    if ($size === false || $size < 1 || $size > CHAT_UPLOAD_CHUNK_BYTES || ($index < $total - 1 && $size !== CHAT_UPLOAD_CHUNK_BYTES)) throw new DomainException('上传分块大小无效', 422);
    if ($expected !== null && $size !== min(CHAT_UPLOAD_CHUNK_BYTES, $expected - $index * CHAT_UPLOAD_CHUNK_BYTES)) throw new DomainException('上传分块与附件大小不一致', 422);
    $accountRoot = $tempBase . '/' . $uid;
    if (!is_dir($accountRoot) && !mkdir($accountRoot, 0700, true) && !is_dir($accountRoot)) throw new RuntimeException('上传暂存空间不可用');
    $lock = fopen($accountRoot . '/.lock', 'c');
    if ($lock === false) throw new RuntimeException('无法锁定上传任务');
    if (!flock($lock, LOCK_EX | LOCK_NB)) { fclose($lock); throw new DomainException('附件正在处理中，请稍后重试', 409); }
    $assembled = null;
    try {
        $active = 0; $stored = 0;
        foreach (glob($accountRoot . '/*', GLOB_ONLYDIR) ?: [] as $old) {
            if (is_link($old)) continue;
            $last = @filemtime($old . '/meta.json') ?: filemtime($old);
            if ($last !== false && $last < time() - 86400) {
                foreach (glob($old . '/*') ?: [] as $item) if (is_file($item) && !is_link($item)) @unlink($item);
                @rmdir($old); continue;
            }
            if (!is_file($old . '/result.json')) $active++;
            foreach (glob($old . '/*.part') ?: [] as $part) $stored += (int)filesize($part);
        }
        $folder = $accountRoot . '/' . $id;
        if (!is_dir($folder)) {
            if ($active >= 4) throw new DomainException('未完成的附件过多，请完成或稍后重试', 429);
            if (!mkdir($folder, 0700)) throw new RuntimeException('无法创建上传任务');
        }
        $metaPath = $folder . '/meta.json';
        if (is_file($metaPath)) {
            $meta = json_decode((string)file_get_contents($metaPath), true);
            if (!is_array($meta) || ($meta['total'] ?? null) !== $total || ($meta['bytes'] ?? null) !== $expected) throw new DomainException('分块上传参数不一致，请重新选择附件', 409);
        } else {
            chatWriteUploadCheckpoint($metaPath, ['total'=>$total,'bytes'=>$expected]);
        }
        touch($metaPath);
        $resultPath = $folder . '/result.json';
        if (is_file($resultPath)) {
            $saved = json_decode((string)file_get_contents($resultPath), true);
            if (is_array($saved) && isset($saved['url'])) return $saved;
            throw new RuntimeException('上传任务记录损坏，请重新选择附件');
        }
        $partPath = $folder . '/' . $index . '.part';
        if (is_file($partPath)) {
            if (hash_file('sha256', $partPath) !== hash_file('sha256', $source)) throw new DomainException('附件内容已变化，请重新选择附件', 409);
        } else {
            if ($stored + $size > 2 * CHAT_UPLOAD_MAX_BYTES) throw new DomainException('上传暂存空间已满，请稍后重试', 429);
            if (!move_uploaded_file($source, $partPath)) throw new RuntimeException('上传分块保存失败');
        }
        $received = 0; $bytes = 0;
        for ($i = 0; $i < $total; $i++) {
            $part = $folder . '/' . $i . '.part';
            if (is_file($part)) { $received++; $bytes += (int)filesize($part); }
        }
        if ($bytes > CHAT_UPLOAD_MAX_BYTES) throw new DomainException('附件不能超过 1 GB', 413);
        if ($received !== $total) return ['received'=>$received,'total'=>$total,'upload_id'=>$id,'chunk_index'=>$index];
        if ($expected !== null && $bytes !== $expected) throw new DomainException('附件大小不一致，请重新选择附件', 422);
        $assembled = tempnam($folder, 'assembled_');
        if ($assembled === false) throw new RuntimeException('无法合并附件');
        $output = fopen($assembled, 'wb');
        if ($output === false) throw new RuntimeException('无法写入附件');
        try {
            for ($i = 0; $i < $total; $i++) {
                $part = fopen($folder . '/' . $i . '.part', 'rb');
                if ($part === false) throw new RuntimeException('无法读取上传分块');
                try { if (stream_copy_to_stream($part, $output) !== filesize($folder . '/' . $i . '.part')) throw new RuntimeException('附件合并失败'); }
                finally { fclose($part); }
            }
        } finally { fclose($output); }
        $result = chatSaveUpload($assembled, $uid, $directory, $baseUrl);
        try {
            chatWriteUploadCheckpoint($resultPath, $result);
        } catch (Throwable $error) {
            @unlink($directory . '/' . basename($result['url']));
            throw new RuntimeException('无法保存上传结果，请重试', 0, $error);
        }
        foreach (glob($folder . '/*.part') ?: [] as $part) @unlink($part);
        return $result;
    } finally {
        if (is_string($assembled) && is_file($assembled)) @unlink($assembled);
        flock($lock, LOCK_UN); fclose($lock);
    }
}

/** Return the server's durable checkpoint for one authenticated upload. */
function chatUploadStatus(int $uid, array $query, string $tempBase): array
{
    $id = strtolower((string)($query['upload_id'] ?? ''));
    $total = filter_var($query['total_chunks'] ?? null, FILTER_VALIDATE_INT);
    $expected = filter_var($query['total_bytes'] ?? null, FILTER_VALIDATE_INT);
    if ($uid < 1 || !preg_match('/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/D', $id)
        || $total === false || $total < 1 || $total > 1024
        || $expected === false || $expected < 1 || $expected > CHAT_UPLOAD_MAX_BYTES
        || (int)ceil($expected / CHAT_UPLOAD_CHUNK_BYTES) !== $total) {
        throw new DomainException('分块上传参数无效', 422);
    }

    $accountRoot = $tempBase . '/' . $uid;
    $folder = $accountRoot . '/' . $id;
    if (is_link($folder)) throw new DomainException('上传任务无效，请重新选择附件', 409);
    if (!is_dir($folder)) return ['received_chunks'=>[], 'received'=>0, 'total'=>$total, 'result'=>null];
    $lock = fopen($accountRoot . '/.lock', 'c');
    if ($lock === false) throw new RuntimeException('无法锁定上传任务');
    if (!flock($lock, LOCK_EX | LOCK_NB)) { fclose($lock); throw new DomainException('附件正在处理中，请稍后重试', 409); }
    try {
        clearstatcache();
        if (!is_dir($folder)) return ['received_chunks'=>[], 'received'=>0, 'total'=>$total, 'result'=>null];
        $metaPath = $folder . '/meta.json';
        if (!is_file($metaPath) || is_link($metaPath)) throw new DomainException('上传任务记录损坏，请重新选择附件', 409);
        $meta = json_decode((string)file_get_contents($metaPath), true);
        if (!is_array($meta) || ($meta['total'] ?? null) !== $total || ($meta['bytes'] ?? null) !== $expected) {
            throw new DomainException('分块上传参数不一致，请重新选择附件', 409);
        }
        $resultPath = $folder . '/result.json';
        if (is_file($resultPath) && !is_link($resultPath)) {
            $saved = json_decode((string)file_get_contents($resultPath), true);
            if (!is_array($saved) || empty($saved['url'])) throw new DomainException('上传任务记录损坏，请重新选择附件', 409);
            touch($metaPath);
            return ['received_chunks'=>[], 'received'=>$total, 'total'=>$total, 'result'=>$saved];
        }
        $last = filemtime($metaPath);
        if ($last !== false && $last < time() - 86400) {
            return ['received_chunks'=>[], 'received'=>0, 'total'=>$total, 'result'=>null];
        }
        $received = [];
        for ($index = 0; $index < $total; $index++) {
            $part = $folder . '/' . $index . '.part';
            if (!is_file($part) || is_link($part)) continue;
            $size = filesize($part);
            $expectedPart = min(CHAT_UPLOAD_CHUNK_BYTES, $expected - $index * CHAT_UPLOAD_CHUNK_BYTES);
            if ($size === false || $size !== $expectedPart) throw new DomainException('已保存的上传分块不完整，请重新上传该附件', 409);
            $received[] = $index;
        }
        touch($metaPath);
        return ['received_chunks'=>$received, 'received'=>count($received), 'total'=>$total, 'result'=>null];
    } finally {
        flock($lock, LOCK_UN); fclose($lock);
    }
}
