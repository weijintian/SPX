import Foundation

struct APIEnvelope: Decodable { let code: JSON; let message: String?; let data: JSON? }
enum APIError: LocalizedError {
    case invalidURL, unauthorized, staleSession, server(Int,String), invalidResponse
    var errorDescription: String? {
        switch self {
        case .invalidURL: return "服务器地址无效，请在设置检查"
        case .unauthorized: return "登录已过期，请重新登录"
        case .staleSession: return "账号已切换，请重试"
        case .server(_, let message): return message
        case .invalidResponse: return "服务器返回了无法识别的数据"
        }
    }
    var retryable: Bool { if case .server(let c,_) = self { return c == 409 || c == 429 || c >= 500 }; return false }
    var definitiveRejection: Bool {
        if case .unauthorized = self { return true }
        if case .server(let status, _) = self { return [400, 403, 404, 409, 422, 429].contains(status) }
        return false
    }
}
extension Notification.Name { static let spxUnauthorized = Notification.Name("spx.unauthorized") }
actor APIClient {
    static let shared = APIClient()
    private var token: String?
    private var generation = UUID()
    private let session: URLSession
    init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 300
        session = URLSession(configuration:configuration, delegate: ServerTrustDelegate.shared, delegateQueue: nil)
    }
    func setToken(_ value:String?) { token = value; generation = UUID() }
    func sessionIdentity() -> UUID { generation }
    func endpoint() throws -> URL {
        let configured = UserDefaults.standard.string(forKey:"spx.apiURL") ?? "https://70.39.201.191/spxchat/api.php"
        guard let url = URL(string:configured), url.scheme == "https", url.host != nil else { throw APIError.invalidURL }
        return url
    }
    func requestURL(_ action:String, query:[String:String] = [:]) throws -> URL {
        var c = URLComponents(url:try endpoint(),resolvingAgainstBaseURL:false)!
        c.queryItems = [URLQueryItem(name:"action",value:action)] + query.sorted(by:{$0.key < $1.key}).map{URLQueryItem(name:$0.key,value:$0.value)}
        guard let url = c.url else {throw APIError.invalidURL}; return url
    }
    func request(_ action:String, method:String = "GET", query:[String:String] = [:], body:[String:JSON]? = nil) async throws -> JSON {
        var request = URLRequest(url:try requestURL(action,query:query))
        request.httpMethod = method
        request.setValue("application/json",forHTTPHeaderField:"Accept")
        request.setValue(UserDefaults.standard.string(forKey:"spx.language") ?? Locale.preferredLanguages.first ?? "zh-CN",forHTTPHeaderField:"Accept-Language")
        if let token {request.setValue("Bearer \(token)",forHTTPHeaderField:"Authorization")}
        if let body {request.httpBody = try JSONEncoder().encode(body);request.setValue("application/json",forHTTPHeaderField:"Content-Type")}
        let epoch = generation
        let (data,response) = try await session.data(for:request)
        try Task.checkCancellation()
        guard epoch == generation else {throw APIError.staleSession}
        return try decode(data,response:response)
    }
    func get(_ action:String, _ query:[String:String] = [:]) async throws -> JSON {try await request(action,query:query)}
    @discardableResult func post(_ action:String, _ body:[String:JSON] = [:]) async throws -> JSON {try await request(action,method:"POST",body:body)}
    func data(_ action: String, query: [String: String] = [:]) async throws -> Data {
        let epoch = generation
        var request = URLRequest(url: try requestURL(action, query: query))
        request.cachePolicy = .reloadIgnoringLocalCacheData
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard epoch == generation else { throw APIError.staleSession }
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        if http.statusCode == 401 {
            NotificationCenter.default.post(name: .spxUnauthorized, object: token)
            throw APIError.unauthorized
        }
        guard (200..<300).contains(http.statusCode) else { throw APIError.server(http.statusCode, "图片加载失败（\(http.statusCode)）") }
        return data
    }
    func decode(_ data:Data,response:URLResponse) throws -> JSON {
        guard let http = response as? HTTPURLResponse else {throw APIError.invalidResponse}
        if http.statusCode == 401, let token {
            let message = (try? JSONDecoder().decode(APIEnvelope.self,from:data))?.message ?? "登录已失效，请重新登录"
            NotificationCenter.default.post(name:.spxUnauthorized,object:token,userInfo:["message":message])
            throw APIError.unauthorized
        }
        guard let envelope = try? JSONDecoder().decode(APIEnvelope.self,from:data) else {throw APIError.server(http.statusCode,"服务器暂时不可用（\(http.statusCode)）")}
        guard (200..<300).contains(http.statusCode), envelope.code.string == "0" else {throw APIError.server(http.statusCode,envelope.message ?? "操作未完成")}
        return envelope.data ?? .null
    }
    func uploadRequest(_ action:String,boundary:String) throws -> URLRequest {
        var r = URLRequest(url:try requestURL(action));r.httpMethod = "POST";r.timeoutInterval = 180
        r.setValue("multipart/form-data; boundary=\(boundary)",forHTTPHeaderField:"Content-Type")
        if let token {r.setValue("Bearer \(token)",forHTTPHeaderField:"Authorization")};return r
    }
    func backgroundUploadRequest(_ action: String, boundary: String, userID: Int) throws -> URLRequest {
        try requireUploadOwner(userID)
        return try uploadRequest(action, boundary: boundary)
    }
    private func requireUploadOwner(_ userID: Int) throws {
        guard userID > 0, Int(Keychain.read("lastUserID") ?? "0") == userID else { throw APIError.staleSession }
        if token == nil { token = Keychain.read("token") }
        guard let token, token == Keychain.read("token") else { throw APIError.unauthorized }
    }
    func uploadStatus(userID: Int, uploadID: String, totalChunks: Int, totalBytes: Int) async throws -> JSON {
        try requireUploadOwner(userID)
        let result = try await get("upload.message.status", ["upload_id": uploadID,
                                                              "total_chunks": String(totalChunks),
                                                              "total_bytes": String(totalBytes)])
        try requireUploadOwner(userID)
        return result
    }
    func upload(_ action: String, body: Data, boundary: String, expectedSession: UUID? = nil) async throws -> JSON {
        if let expectedSession, expectedSession != generation { throw APIError.staleSession }
        let epoch = generation
        let request = try uploadRequest(action, boundary: boundary)
        let (data, response) = try await session.upload(for: request, from: body)
        try Task.checkCancellation()
        guard epoch == generation else { throw APIError.staleSession }
        return try decode(data, response: response)
    }
}

