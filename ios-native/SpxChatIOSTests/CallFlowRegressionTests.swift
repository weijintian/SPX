import XCTest
@testable import SpxChatIOS

final class CallFlowRegressionTests: XCTestCase {
    private let id = "12345678-1234-1234-1234-123456789abc"
    private func outgoing(video: Bool = false) -> ActiveCall {
        ActiveCall(id: id, peer: "spx_20", name: "测试联系人", avatar: "", outgoing: true, video: video, room: 9001)
    }

    func testAudioAndVideoBeginMatchCurrentRoomWhenSDKOmitsUserData() {
        for video in [false, true] {
            let call = outgoing(video: video)
            XCTAssertTrue(call.matchesSDK("sdk-current", sessionID: "", room: 9001, retired: []))
            XCTAssertFalse(call.matchesSDK("sdk-old", sessionID: "", room: 9000, retired: []))
            XCTAssertFalse(call.matchesSDK("", sessionID: id, room: 9001, retired: []))
        }
    }

    func testRetiredAttemptCannotConnectOrEndRetryOfSameBusinessSession() {
        var call = outgoing()
        call.room = 9002
        let retired: Set<String> = ["sdk-offline"]
        XCTAssertFalse(call.matchesSDK("sdk-offline", sessionID: id, room: 9001, retired: retired))
        XCTAssertFalse(call.matchesSDK("sdk-offline", sessionID: id, room: 9002, retired: retired))
        XCTAssertTrue(call.matchesSDK("sdk-retry", sessionID: "", room: 9002, retired: retired))
    }

    func testBoundSDKIDRejectsLateCallbackEvenWithSameRoom() {
        var call = outgoing(); call.sdkID = "sdk-connected"
        XCTAssertTrue(call.matchesSDK("sdk-connected", sessionID: "", room: 0, retired: []))
        XCTAssertFalse(call.matchesSDK("sdk-old", sessionID: id, room: 9001, retired: []))
    }

    func testNewCallCannotBeEndedByPreviousSession() {
        let call = outgoing()
        XCTAssertFalse(call.matchesSDK("sdk-previous", sessionID: "another-session", room: 9000, retired: []))
    }

    func testOfflineRetryUsesOriginalDeadlineForAudioAndVideo() {
        let now = Date(timeIntervalSince1970: 1_000)
        for video in [false, true] {
            let call = outgoing(video: video)
            XCTAssertTrue(call.canRetryOffline(reason: 4, deadline: now.addingTimeInterval(1), now: now))
            XCTAssertFalse(call.canRetryOffline(reason: 4, deadline: now, now: now))
            XCTAssertFalse(call.canRetryOffline(reason: 4, deadline: now.addingTimeInterval(-1), now: now))
            XCTAssertFalse(call.canRetryOffline(reason: 4, deadline: nil, now: now))
        }
    }

    func testDeclinedBusyCanceledAndTimeoutCallsDoNotRetry() {
        let call = outgoing(), deadline = Date().addingTimeInterval(30)
        let reasons: [UInt] = [0, 1, 2, 3, 5, 6, 7, 8, 9]
        for reason in reasons {
            XCTAssertFalse(call.canRetryOffline(reason: reason, deadline: deadline))
        }
    }

    func testConnectedAndIncomingCallsDoNotRedialOnOfflineCallback() {
        var connected = outgoing(); connected.connected = true
        let deadline = Date().addingTimeInterval(30)
        XCTAssertFalse(connected.canRetryOffline(reason: 4, deadline: deadline))
        let incoming = ActiveCall(id: id, peer: "spx_10", name: "来电联系人", avatar: "", outgoing: false, video: true, room: 9001, sdkID: "sdk-incoming")
        XCTAssertFalse(incoming.canRetryOffline(reason: 4, deadline: deadline))
        XCTAssertFalse(incoming.matchesSDK("sdk-other", sessionID: id, room: 9001, retired: []))
    }
}
