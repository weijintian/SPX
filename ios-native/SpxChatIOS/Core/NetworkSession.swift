import Foundation
import Security
import OSLog

/// Pins the active server leaf certificate while URLSession evaluates hostname and validity.
private enum ServerCertificatePin {
    static let leafDER = Data(base64Encoded: "MIIDgDCCAmigAwIBAgIQZdIbhko8YKx3HUVdCKJnDzANBgkqhkiG9w0BAQsFADBWMQswCQYDVQQGEwJDTjEOMAwGA1UECAwFU3RhdGUxDTALBgNVBAcMBENpdHkxEDAOBgNVBAoMB1NQWENoYXQxFjAUBgNVBAMMDTcwLjM5LjIwMS4xOTEwHhcNMjYxMDIzMDUxOTU0WhcNMjcxMTI0MDUxOTU0WjAYMRYwFAYDVQQDDA03MC4zOS4yMDEuMTkxMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAn6sVF1bkJGNRq7okhoI/g9Itub6mix8aZfSHx7XsvcXjec2uZT/NLnoGsV2qiPSZwPs5K87WRj3UWHpSS8YSlggY370KtJrM4Tu1x1eM4RI257WrmNjTBX1Unaa9qBFdSzTYLFQmaT7IbH0CMVIJoOm1HqEbdumnQmw2LqrHGbCw2PfoNzWWx5cxSqPv3Re9rwL2typZwaXCQuB5loS1XsjM32qYw2wcseD9aP/nKtES2elJs1SSH0EXNUTlswnRSpk5JQg+ttiO3Gea/6/CyZXecKXHsTjgoW0wk3xM/kymwLlAmDgl2ezxQk0kUMrEap7NLqdA+BextTqAgReijQIDAQABo4GHMIGEMAwGA1UdEwEB/wQCMAAwDgYDVR0PAQH/BAQDAgWgMBMGA1UdJQQMMAoGCCsGAQUFBwMBMA8GA1UdEQQIMAaHBEYnyb8wHQYDVR0OBBYEFNlQQQA8jNu/kC6a5UlVWXHRp+YLMB8GA1UdIwQYMBaAFNlQQQA8jNu/kC6a5UlVWXHRp+YLMA0GCSqGSIb3DQEBCwUAA4IBAQAMA+wGrOrfy7dtnS8pGJtReh4yWeTdtyBcrvJ6lazxwIQco0h3GTEF73bqNligJWpDYLnAhzoGK3unFBvBlx1jv9SNI8vWPf5aPpCv2IODM22vcauCjYFj4eTRBMHnGKNLhFlF0065qOuuDkrKbaG55hQiLEiRnsaqOLDu+DxFXan1cpo+a3aHKn87ODfkYYkkBlYbUDuuUE4QkheDHBtLisL4dr83ZQf8oZlXietLSdrtjsNDfGq5L9+isliB/nyTOBtSlmw3Vtb4SUqqBzO5XsGBp0V4yrt1GknFnD9ZsLJwBAwQezOT2aNlGwo9HA7XFJRr/vjWUh7bBu25SwV")!
}


final class ServerTrustDelegate: NSObject, URLSessionDelegate {
    static let shared = ServerTrustDelegate()
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "com.spx.chat.ios", category: "ServerTrust")
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              challenge.protectionSpace.host == "70.39.201.191",
              let trust = challenge.protectionSpace.serverTrust,
              let file = Bundle.main.url(forResource: "spx-server", withExtension: "cer"),
              let bytes = try? Data(contentsOf: file),
              let certificate = SecCertificateCreateWithData(nil, bytes as CFData) else {
            completionHandler(.performDefaultHandling, nil); return
        }
        guard let leafPin = SecCertificateCreateWithData(nil, ServerCertificatePin.leafDER as CFData) else {
            completionHandler(.performDefaultHandling, nil); return
        }
        let status = SecTrustSetAnchorCertificates(trust, [leafPin] as CFArray)
        guard status == errSecSuccess else {
            logger.error("Custom anchor configuration failed: \(status)")
            completionHandler(.performDefaultHandling, nil); return
        }
        SecTrustSetAnchorCertificatesOnly(trust, true)
        var error: CFError?
        if SecTrustEvaluateWithError(trust, &error) { completionHandler(.useCredential, URLCredential(trust: trust)) }
        else {
            // Preserve URLSession's TLS error instead of replacing it with URLError.cancelled.
            // Default handling still rejects untrusted servers.
            let reason = error.map { String(describing: $0) } ?? "unknown trust failure"
            logger.error("Server certificate evaluation failed: \(reason, privacy: .public)")
            completionHandler(.performDefaultHandling, nil)
        }
    }
}

enum NetworkSession {
    static let shared = make()
    static func make() -> URLSession {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 300
        return URLSession(configuration: config, delegate: ServerTrustDelegate.shared, delegateQueue: nil)
    }
}

/// Keep transport failures distinct from API/account errors on authentication screens.
enum AuthenticationErrorMessage {
    static func describe(_ error: Error) -> String {
        if error is CancellationError { return "操作已取消，请重试" }
        let failure = error as NSError
        guard failure.domain == NSURLErrorDomain else { return error.localizedDescription }
        let code = failure.code
        switch code {
        case URLError.cancelled.rawValue:
            return "连接已取消，请重新点击登录（网络错误 \(code)）"
        case URLError.serverCertificateHasBadDate.rawValue, URLError.serverCertificateNotYetValid.rawValue:
            return "服务器证书日期校验失败，请检查手机日期与时间是否自动设置（网络错误 \(code)）"
        case URLError.serverCertificateUntrusted.rawValue, URLError.serverCertificateHasUnknownRoot.rawValue,
             URLError.secureConnectionFailed.rawValue, URLError.clientCertificateRejected.rawValue,
             URLError.clientCertificateRequired.rawValue:
            return "无法验证服务器的安全连接，请联系管理员检查证书配置（网络错误 \(code)）"
        case URLError.appTransportSecurityRequiresSecureConnection.rawValue:
            return "服务器连接未满足 iOS 安全要求，请联系管理员（网络错误 \(code)）"
        case URLError.notConnectedToInternet.rawValue, URLError.dataNotAllowed.rawValue:
            return "网络不可用，请允许 App 使用无线局域网与蜂窝数据后重试（网络错误 \(code)）"
        case URLError.timedOut.rawValue:
            return "连接服务器超时，请检查网络后重试（网络错误 \(code)）"
        case URLError.cannotFindHost.rawValue, URLError.cannotConnectToHost.rawValue, URLError.dnsLookupFailed.rawValue:
            return "暂时无法连接服务器，请稍后重试（网络错误 \(code)）"
        case URLError.networkConnectionLost.rawValue:
            return "网络连接中断，请重试（网络错误 \(code)）"
        default:
            return "网络请求失败，请重试（网络错误 \(code)）"
        }
    }
}
