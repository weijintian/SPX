package com.spx.chat;

import android.content.Context;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateException;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class SSLHelper {
    private static SSLContext sslContext;
    private static X509TrustManager trustManager;

    private SSLHelper() { }

    public static synchronized void init(Context context) {
        if (sslContext != null) return;
        try (InputStream input = context.getResources().openRawResource(R.raw.ip_crt)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Certificate certificate = factory.generateCertificate(input);
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("spx-server", certificate);
            TrustManagerFactory customFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            customFactory.init(keyStore);
            X509TrustManager customTrustManager = (X509TrustManager) customFactory.getTrustManagers()[0];
            TrustManagerFactory systemFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            systemFactory.init((KeyStore) null);
            X509TrustManager systemTrustManager = (X509TrustManager) systemFactory.getTrustManagers()[0];
            trustManager = new CombinedTrustManager(customTrustManager, systemTrustManager);
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new javax.net.ssl.TrustManager[]{trustManager}, null);
        } catch (Exception exception) {
            throw new IllegalStateException("加载自签证书失败", exception);
        }
    }

    public static SSLContext getSSLContext() {
        if (sslContext == null) throw new IllegalStateException("SSLHelper 未初始化");
        return sslContext;
    }

    private static final class CombinedTrustManager implements X509TrustManager {
        private final X509TrustManager custom;
        private final X509TrustManager system;

        CombinedTrustManager(X509TrustManager custom, X509TrustManager system) {
            this.custom = custom;
            this.system = system;
        }

        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
            try { custom.checkClientTrusted(chain, authType); }
            catch (CertificateException ignored) { system.checkClientTrusted(chain, authType); }
        }

        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
            try { custom.checkServerTrusted(chain, authType); }
            catch (CertificateException ignored) { system.checkServerTrusted(chain, authType); }
        }

        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            java.security.cert.X509Certificate[] customIssuers = custom.getAcceptedIssuers();
            java.security.cert.X509Certificate[] systemIssuers = system.getAcceptedIssuers();
            java.security.cert.X509Certificate[] result = Arrays.copyOf(customIssuers, customIssuers.length + systemIssuers.length);
            System.arraycopy(systemIssuers, 0, result, customIssuers.length, systemIssuers.length);
            return result;
        }
    }
}
