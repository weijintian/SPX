import Foundation
import Security
import OSLog

/// Pins the active server leaf certificate while URLSession evaluates hostname and validity.
final class ServerTrustDelegate: NSObject, URLSessionDelegate {
    static let shared = ServerTrustDelegate()
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "com.spx.chat.ios", category: "ServerTrust")

    private func bundledCertificate() -> SecCertificate? {
        guard let file = Bundle.main.url(forResource: "spx-server", withExtension: "cer"),
              let bytes = try? Data(contentsOf: file) else { return nil }
        if let certificate = SecCertificateCreateWithData(nil, bytes as CFData) { return certificate }
        guard let pem = String(data: bytes, encoding: .utf8) else { return nil }
        let body = pem
            .replacingOccurrences(of: "-----BEGIN CERTIFICATE-----", with: "")
            .replacingOccurrences(of: "-----END CERTIFICATE-----", with: "")
            .components(separatedBy: .whitespacesAndNewlines)
            .joined()
        guard let der = Data(base64Encoded: body) else { return nil }
        return SecCertificateCreateWithData(nil, der as CFData)
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              challenge.protectionSpace.host == "70.39.201.191",
              let trust = challenge.protectionSpace.serverTrust,
              let certificate = bundledCertificate() else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        let status = SecTrustSetAnchorCertificates(trust, [certificate] as CFArray)
        guard status == errSecSuccess else {
            logger.error("Custom anchor configuration failed: \\(status)")
            completionHandler(.performDefaultHandling, nil)
            return
        }

        SecTrustSetAnchorCertificatesOnly(trust, true)
        var error: CFError?
        if SecTrustEvaluateWithError(trust, &error) {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            let reason = error.map { String(describing: $0) } ?? "unknown trust failure"
            logger.error("Server certificate evaluation failed: \\(reason, privacy: .public)")
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
            return "连接已取消，请重新点击登录（网络错误 \\(code)）"
        case URLError.serverCertificateHasBadDate.rawValue, URLError.serverCertificateNotYetValid.rawValue:
            return "服务器证书日期校验失败，请检查手机日期与时间是否自动设置（网络错误 \\(code)）"
        case URLError.serverCertificateUntrusted.rawValue, URLError.serverCertificateHasUnknownRoot.rawValue,
             URLError.secureConnectionFailed.rawValue, URLError.clientCertificateRejected.rawValue,
             URLError.clientCertificateRequired.rawValue:
            return "无法验证服务器的安全连接，请联系管理员检查证书配置（网络错误 \\(code)）"
        case URLError.appTransportSecurityRequiresSecureConnection.rawValue:
            return "服务器连接未满足 iOS 安全要求，请联系管理员（网络错误 \\(code)）"
        case URLError.notConnectedToInternet.rawValue, URLError.dataNotAllowed.rawValue:
            return "网络不可用，请允许 App 使用无线局域网与蜂窝数据后重试（网络错误 \\(code)）"
        case URLError.timedOut.rawValue:
            return "连接服务器超时，请检查网络后重试（网络错误 \\(code)）"
        case URLError.cannotFindHost.rawValue, URLError.cannotConnectToHost.rawValue, URLError.dnsLookupFailed.rawValue:
            return "暂时无法连接服务器，请稍后重试（网络错误 \\(code)）"
        case URLError.networkConnectionLost.rawValue:
            return "网络连接中断，请重试（网络错误 \\(code)）"
        default:
            return "网络请求失败，请重试（网络错误 \\(code)）"
        }
    }
}
