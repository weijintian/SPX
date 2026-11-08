"""Local PHP/session/WebSocket regression. SQLite validates flow, not MySQL row locking."""
import argparse
import json
import pathlib
import shutil
import socket
import sqlite3
import struct
import subprocess
import tempfile
import time


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


parser = argparse.ArgumentParser()
parser.add_argument("--php", required=True)
args = parser.parse_args()
php = pathlib.Path(args.php).resolve()
source = pathlib.Path(__file__).resolve().parents[1]
checks = 0


def check(condition, label):
    global checks
    if not condition:
        raise AssertionError(label)
    checks += 1
    print("PASS " + label, flush=True)


def exact(sock, size):
    result = b""
    while len(result) < size:
        chunk = sock.recv(size - len(result))
        if not chunk:
            raise RuntimeError("Unexpected WebSocket close")
        result += chunk
    return result


def send_frame(sock, payload, opcode=1):
    mask = b"test"
    size = len(payload)
    header = bytes([0x80 | opcode, 0x80 | size]) if size < 126 else bytes([0x80 | opcode, 0x80 | 126]) + struct.pack("!H", size)
    sock.sendall(header + mask + bytes(value ^ mask[index % 4] for index, value in enumerate(payload)))


def receive(sock):
    first, second = exact(sock, 2)
    size = second & 127
    if size == 126:
        size = struct.unpack("!H", exact(sock, 2))[0]
    elif size == 127:
        size = struct.unpack("!Q", exact(sock, 8))[0]
    payload = exact(sock, size)
    return first & 15, payload


with tempfile.TemporaryDirectory(prefix="spx-session-regression-") as folder:
    app = pathlib.Path(folder)
    for name in ["auth.php", "ws-event.php", "ws-server.php", "chat_names.php", "chat_calls.php", "chat_delivery.php", "chat_content.php", "chat_replies.php"]:
        shutil.copy2(source / name, app / name)
    # Restrict the test copy to loopback; production source remains unchanged.
    ws_source = (app / "ws-server.php").read_text(encoding="utf-8").replace("tcp://0.0.0.0:", "tcp://127.0.0.1:")
    (app / "ws-server.php").write_text(ws_source, encoding="utf-8")
    public_port, event_port = free_port(), free_port()
    (app / "config.php").write_text("<?php return ['app'=>['token_ttl'=>86400,'websocket_public_port'=>" + str(public_port) + ",'websocket_event_port'=>" + str(event_port) + ",'websocket_event_host'=>'127.0.0.1']];", encoding="utf-8")
    (app / "db.php").write_text(r'''<?php
class FixturePDO extends PDO {
    public function __construct() {
        parent::__construct('sqlite:' . __DIR__ . '/fixture.db', null, null, [PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC]);
        $this->sqliteCreateFunction('NOW', static fn()=>date('Y-m-d H:i:s'));
        $this->exec('PRAGMA busy_timeout=3000');
    }
    public function prepare(string $query, array $options=[]): PDOStatement|false {
        $query=str_replace([' FOR UPDATE','INSERT IGNORE INTO'],['','INSERT OR IGNORE INTO'],$query);
        return parent::prepare($query,$options);
    }
    public function query(string $query, ?int $fetchMode=null, mixed ...$args): PDOStatement|false {
        if (preg_match("/SHOW TABLES LIKE '([a-z_]+)'/",$query,$match)) $query="SELECT name FROM sqlite_master WHERE type='table' AND name='".$match[1]."'";
        return $fetchMode===null ? parent::query($query) : parent::query($query,$fetchMode,...$args);
    }
}
function db(): PDO { static $pdo; return $pdo ??= new FixturePDO(); }
''', encoding="utf-8")
    (app / "session.php").write_text(r'''<?php
require_once __DIR__.'/db.php';
require_once __DIR__.'/auth.php';
require_once __DIR__.'/ws-event.php';
require_once __DIR__.'/chat_calls.php';
function jsonResponse($data,$message='',int $status=200): never { echo json_encode(['status'=>$status]);exit; }
try {
    if ($argv[1]==='login') echo json_encode(['token'=>issueToken((int)$argv[2])]);
    else { $_SERVER['HTTP_AUTHORIZATION']='Bearer '.$argv[2]; $user=currentUser(); echo json_encode(['status'=>200,'id'=>(int)$user['id']]); }
} catch (Throwable $error) { echo json_encode(['failed'=>true]); exit(1); }
''', encoding="utf-8")
    database = sqlite3.connect(app / "fixture.db")
    database.executescript('''
    CREATE TABLE users(id INTEGER PRIMARY KEY,nickname TEXT,status INTEGER,password_hash TEXT,avatar_url TEXT);
    INSERT INTO users VALUES(1,'A',1,'test',''),(2,'B',1,'test','');
    CREATE TABLE user_tokens(user_id INTEGER,token TEXT UNIQUE,expires_at TEXT);
    CREATE TABLE user_presence(user_id INTEGER PRIMARY KEY,is_online INTEGER);
    INSERT INTO user_presence VALUES(1,1),(2,1);
    CREATE TABLE wallet_assets(user_id INTEGER,symbol TEXT,chain TEXT,balance NUMERIC,UNIQUE(user_id,symbol,chain));
    CREATE TABLE chats(id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER,peer_user_id INTEGER,title TEXT,last_message TEXT,updated_at TEXT,unread_count INTEGER DEFAULT 0);
    INSERT INTO chats(id,user_id,peer_user_id,title) VALUES(1,1,2,'B'),(2,2,1,'A');
    CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat_id INTEGER,user_id INTEGER,sender_id INTEGER,client_message_id TEXT,content TEXT,message_type TEXT,message_origin TEXT,created_at TEXT DEFAULT CURRENT_TIMESTAMP);
    ''')
    database.commit()
    command = [str(php), "-n", "-d", "extension_dir=" + str(php.parent / "ext"), "-d", "extension=pdo_sqlite"]

    def action(mode, value):
        result = subprocess.run(command + [str(app / "session.php"), mode, str(value)], cwd=app, capture_output=True, encoding="utf-8", timeout=15)
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError:
            raise RuntimeError("Fixture PHP returned a non-JSON result") from None

    def connect(token):
        sock = socket.create_connection(("127.0.0.1", public_port), timeout=6)
        sock.sendall(b"GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n")
        headers = b""
        while b"\r\n\r\n" not in headers:
            headers += sock.recv(1024)
        if not headers.startswith(b"HTTP/1.1 101"):
            raise RuntimeError("Fixture WebSocket handshake failed")
        send_frame(sock, json.dumps({"type":"auth","token":token}).encode())
        return sock, json.loads(receive(sock)[1])

    def internal_event(uid):
        with socket.create_connection(("127.0.0.1", event_port), timeout=3) as sock:
            sock.sendall((json.dumps({"type":"auth.session_changed","user_id":uid}) + "\n").encode())

    log = (app / "websocket.log").open("w", encoding="utf-8")
    server = subprocess.Popen(command + [str(app / "ws-server.php")], cwd=app, stdout=log, stderr=subprocess.STDOUT)
    sockets = []
    try:
        for _ in range(100):
            try:
                with socket.create_connection(("127.0.0.1", public_port), timeout=0.1):
                    break
            except OSError:
                time.sleep(0.05)
        first, other = action("login", 1)["token"], action("login", 2)["token"]
        check(action("me", first) == {"status":200,"id":1}, "initial login works before lazy call tables exist")
        old_socket, ready = connect(first); sockets.append(old_socket)
        other_socket, other_ready = connect(other); sockets.append(other_socket)
        check(ready["type"] == "ready" and other_ready["type"] == "ready", "both accounts authenticate WebSockets")
        database.executescript('''
        CREATE TABLE chat_call_presence(user_id INTEGER PRIMARY KEY,device_id TEXT,last_seen TEXT);
        INSERT INTO chat_call_presence VALUES(1,'old-device',CURRENT_TIMESTAMP),(2,'peer-device',CURRENT_TIMESTAMP);
        CREATE TABLE chat_calls(id TEXT PRIMARY KEY,caller_id INTEGER,callee_id INTEGER,caller_chat_id INTEGER,media TEXT,state TEXT,created_at TEXT,connected_at TEXT,ended_at TEXT,duration_seconds INTEGER DEFAULT 0,caller_seen TEXT,callee_seen TEXT,caller_device TEXT,callee_device TEXT,sdk_call_id TEXT,record_written INTEGER DEFAULT 0);
        INSERT INTO chat_calls(id,caller_id,callee_id,caller_chat_id,media,state,created_at,caller_seen,caller_device,callee_device) VALUES('fixture-call',1,2,1,'audio','ringing',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'old-device','peer-device');
        ''')
        database.commit()
        second = action("login", 1)["token"]
        check(second != first and database.execute("SELECT COUNT(*) FROM user_tokens WHERE user_id=1").fetchone()[0] == 1, "second login leaves one valid token")
        check(action("me", first)["status"] == 401, "old login API token rejected")
        check(action("me", second)["status"] == 200, "latest login API token accepted")
        check(action("me", other)["status"] == 200, "other account remains logged in")
        check(json.loads(receive(old_socket)[1])["type"] == "auth.revoked", "existing old WebSocket receives forced logout")
        check(database.execute("SELECT COUNT(*) FROM chat_call_presence WHERE user_id=1").fetchone()[0] == 0, "old device call presence removed")
        check(database.execute("SELECT state,record_written FROM chat_calls WHERE id='fixture-call'").fetchone() == ("failed",1), "old call finalized with call record")
        check(database.execute("SELECT COUNT(*) FROM messages WHERE message_origin='call'").fetchone()[0] == 2, "call result stored once for each participant")
        new_socket, ready = connect(second); sockets.append(new_socket)
        check(ready["type"] == "ready", "latest login establishes new WebSocket")
        internal_event(1); time.sleep(0.1)
        send_frame(new_socket, b"probe", opcode=9)
        check(receive(new_socket) == (10,b"probe"), "delayed login event does not kick latest device")
        invalid, revoked = connect(first); sockets.append(invalid)
        check(revoked["type"] == "auth.revoked", "revoked token cannot reconnect")
        database.execute("CREATE TRIGGER reject_login BEFORE INSERT ON user_tokens WHEN NEW.user_id=1 BEGIN SELECT RAISE(ABORT,'fixture failure'); END")
        database.commit()
        check(action("login",1).get("failed") is True, "injected login insert failure surfaced")
        check(action("me",second)["status"] == 200, "failed rotation rolls back to previous valid token")
        database.execute("DROP TRIGGER reject_login"); database.commit()
        # No event delivery: periodic token validation must still evict a revoked socket.
        database.execute("DELETE FROM user_tokens WHERE user_id=1"); database.commit()
        check(json.loads(receive(new_socket)[1])["type"] == "auth.revoked", "periodic validation handles missing logout event")
        print(f"SUCCESS {checks} isolated PHP/WebSocket/session checks (SQLite; MySQL concurrency not covered)", flush=True)
    finally:
        for sock in sockets:
            sock.close()
        server.terminate()
        server.wait(timeout=10)
        log.close()
        database.close()
