import Foundation
import Security

/// Same explicit server certificate as Android. Hostname and validity are still evaluated.
final class ServerTrustDelegate: NSObject, URLSessionDelegate {
    static let shared = ServerTrustDelegate()
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              challenge.protectionSpace.host == "70.39.201.191",
              let trust = challenge.protectionSpace.serverTrust,
              let file = Bundle.main.url(forResource: "spx-server", withExtension: "cer"),
              let bytes = try? Data(contentsOf: file),
              let certificate = SecCertificateCreateWithData(nil, bytes as CFData) else {
            completionHandler(.performDefaultHandling, nil); return
        }
        SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, challenge.protectionSpace.host as CFString))
        SecTrustSetAnchorCertificates(trust, [certificate] as CFArray)
        SecTrustSetAnchorCertificatesOnly(trust, false)
        var error: CFError?
        if SecTrustEvaluateWithError(trust, &error) { completionHandler(.useCredential, URLCredential(trust: trust)) }
        else { completionHandler(.cancelAuthenticationChallenge, nil) }
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
