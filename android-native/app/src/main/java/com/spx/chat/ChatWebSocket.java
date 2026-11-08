package com.spx.chat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocketFactory;

public final class ChatWebSocket {
    public interface Listener {
        void onConnected();
        void onMessage(JSONObject message);
        void onDisconnected();
    }

    private final URI uri;
    private final String token;
    private final Listener listener;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final SecureRandom random = new SecureRandom();
    private volatile Socket socket;
    private volatile OutputStream output;
    private volatile long lastFrameAt, connectedAt;

    public ChatWebSocket(String url, String token, Listener listener) throws Exception {
        this.uri = URI.create(url);
        this.token = token;
        this.listener = listener;
    }

    public void connect() {
        new Thread(this::run, "spx-websocket").start();
    }

    public void close() {
        stopped.set(true);
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }

    private void run() {
        long retryDelay = 1000;
        while (!stopped.get()) {
            try {
                connectOnce();
                retryDelay = 1000;
            } catch (Exception ignored) {
            } finally {
                output = null;
                try { if (socket != null) socket.close(); } catch (Exception ignored) { }
                socket = null;
            }
            if (stopped.get()) break;
            if(connectedAt>0&&System.currentTimeMillis()-connectedAt>30000)retryDelay=1000;connectedAt=0;
            listener.onDisconnected();
            try { Thread.sleep(retryDelay+random.nextInt(500)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            retryDelay = Math.min(retryDelay * 2, 30000);
        }
    }

    static void verifyPeer(String host,javax.net.ssl.SSLSession session) throws javax.net.ssl.SSLPeerUnverifiedException {
        if(!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host,session))
            throw new javax.net.ssl.SSLPeerUnverifiedException("WebSocket 服务器身份不匹配");
    }

    private void connectOnce() throws Exception {
        String scheme = uri.getScheme();
        boolean secure = "wss".equalsIgnoreCase(scheme);
        int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
        socket = new Socket();
        if (stopped.get()) { socket.close(); return; }
        socket.connect(new InetSocketAddress(uri.getHost(), port), 10000);
        socket.setSoTimeout(10000);
        if (secure) {
            javax.net.ssl.SSLSocket tls=(javax.net.ssl.SSLSocket)SSLHelper.getSSLContext().getSocketFactory().createSocket(socket,uri.getHost(),port,true);
            socket=tls;
            javax.net.ssl.SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);
            if(stopped.get()){tls.close();return;}tls.startHandshake();
            verifyPeer(uri.getHost(),tls.getSession());
        }
        InputStream input = socket.getInputStream();
        output = socket.getOutputStream();
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String request = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        String headers = readHeaders(input);
        String expectedAccept = android.util.Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)),
                android.util.Base64.NO_WRAP);
        String normalizedHeaders = headers.toLowerCase(Locale.US);
        if (!headers.startsWith("HTTP/1.1 101") || !normalizedHeaders.contains(("sec-websocket-accept: " + expectedAccept).toLowerCase(Locale.US))) {
            throw new IllegalStateException("WebSocket 握手失败");
        }
        JSONObject auth = new JSONObject();
        auth.put("type", "auth");
        auth.put("token", token);
        sendFrame(1, auth.toString().getBytes(StandardCharsets.UTF_8));
        DataInputStream data = new DataInputStream(input);
        while (!stopped.get()) {
            int first = data.readUnsignedByte();
            int second = data.readUnsignedByte();
            int opcode = first & 15;
            long length = second & 127;
            if (length == 126) length = data.readUnsignedShort();
            else if (length == 127) length = data.readLong();
            boolean masked = (second & 128) != 0;
            byte[] mask = masked ? new byte[4] : null;
            if (masked) data.readFully(mask);
            if (length < 0 || length > 16 * 1024 * 1024) throw new IllegalStateException("WebSocket 消息过大");
            byte[] payload = new byte[(int) length];
            data.readFully(payload);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            lastFrameAt=System.currentTimeMillis();
            if (opcode == 8) break;
            if (opcode == 10) continue;
            if (opcode == 9) { sendFrame(10, payload); continue; }
            if (opcode == 1) {
                JSONObject message = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                if ("ready".equals(message.optString("type"))) {
                    socket.setSoTimeout(0);
                    connectedAt=lastFrameAt=System.currentTimeMillis();
                    startHeartbeat(socket);
                    listener.onConnected();
                }
                else listener.onMessage(message);
            }
        }
        try { socket.close(); } catch (Exception ignored) { }
    }

    private void startHeartbeat(final Socket connection){
        Thread heartbeat=new Thread(()->{while(!stopped.get()&&socket==connection&&!connection.isClosed()){
            try{Thread.sleep(20000);if(stopped.get()||socket!=connection||connection.isClosed())return;
                if(System.currentTimeMillis()-lastFrameAt>60000){connection.close();return;}
                sendFrame(9,new byte[0]);
            }catch(Exception e){try{connection.close();}catch(Exception ignored){}return;}
        }},"spx-chat-heartbeat");heartbeat.setDaemon(true);heartbeat.start();
    }

    private String readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (bytes.size() < 16384) {
            int value = input.read();
            if (value < 0) throw new IllegalStateException("WebSocket 握手连接已关闭");
            bytes.write(value);
            if ((state == 0 && value == '\r') || (state == 2 && value == '\r')) state++;
            else if ((state == 1 && value == '\n') || (state == 3 && value == '\n')) state++;
            else state = value == '\r' ? 1 : 0;
            if (state == 4) break;
        }
        return bytes.toString("US-ASCII");
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws Exception {
        OutputStream stream = output;
        if (stream == null) return;
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        int length = payload.length;
        if (length < 126) frame.write(0x80 | length);
        else if (length <= 65535) { frame.write(0x80 | 126); frame.write((length >>> 8) & 255); frame.write(length & 255); }
        else {
            frame.write(0x80 | 127);
            long longLength = length;
            for (int i = 7; i >= 0; i--) frame.write((int) ((longLength >>> (8 * i)) & 255));
        }
        byte[] mask = new byte[4]; random.nextBytes(mask); frame.write(mask);
        for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i % 4]);
        stream.write(frame.toByteArray());
        stream.flush();
    }
}
