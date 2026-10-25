#import "SPXCallEngineBridge.h"
#import <RTCRoomEngine/TUICallEngine.h>
#import <RTCRoomEngine/TUIRoomEngine.h>

@interface SPXCallEngineBridge () <TUICallObserver>
@property(nonatomic, strong) TUICallEngine *engine;
@property(nonatomic, assign) BOOL invalidated;
@property(nonatomic, assign) BOOL initializing;
@end

// RoomEngine is a process-wide singleton. Never destroy it while its native
// initialization is still running, nor let a new account reuse that instance.
static SPXCallEngineBridge *SPXRetiringBridge;

@implementation SPXCallEngineBridge
- (void)initializeWithAppID:(int)appID userID:(NSString *)userID userSig:(NSString *)userSig
                   success:(SPXCallSuccess)success failure:(SPXCallFailure)failure {
    if ((SPXRetiringBridge && SPXRetiringBridge != self) || self.initializing || self.invalidated) {
        failure(409, @"上一次通话连接正在清理，请稍后重试");
        return;
    }
    if (!self.engine) {
        self.engine = [TUICallEngine createInstance];
        // Register before init: Android may invite as soon as this account logs in.
        [self.engine addObserver:self];
    }
    self.initializing = YES;
    [self.engine init:appID userId:userID userSig:userSig succ:^{
        dispatch_async(dispatch_get_main_queue(), ^{
            self.initializing = NO;
            if (self.invalidated) { [self destroyRetiredEngine]; return; }
            success();
        });
    } fail:^(int code, NSString *message) {
        dispatch_async(dispatch_get_main_queue(), ^{
            self.initializing = NO;
            if (self.invalidated) [self destroyRetiredEngine];
            failure(code, message ?: @"通话登录失败");
        });
    }];
}
- (BOOL)isLoggedInAs:(NSString *)userID {
    return !self.invalidated && self.engine && [[TUIRoomEngine getSelfInfo].userId isEqualToString:userID];
}
- (void)callUser:(NSString *)userID video:(BOOL)video sessionID:(NSString *)sessionID
           room:(uint32_t)room timeout:(int)timeout success:(SPXCallSuccess)success failure:(SPXCallFailure)failure {
    TUICallParams *params = [[TUICallParams alloc] init];
    params.userData = sessionID;
    params.timeout = MAX(1, MIN(60, timeout));
    TUIRoomId *roomID = [[TUIRoomId alloc] init];
    roomID.intRoomId = room;
    params.roomId = roomID;
    // Deliberately match the existing Android 1:1 selector and invitation format.
    [self.engine call:userID callMediaType:video ? TUICallMediaTypeVideo : TUICallMediaTypeAudio
               params:params succ:success fail:^(int code, NSString *message) { failure(code, message ?: @"呼叫失败"); }];
}
- (void)acceptWithSuccess:(SPXCallSuccess)success failure:(SPXCallFailure)failure {
    [self.engine accept:success fail:^(int code, NSString *message) { failure(code, message ?: @"接听失败"); }];
}
- (void)terminateRejecting:(BOOL)reject success:(SPXCallSuccess)success failure:(SPXCallFailure)failure {
    if (!self.engine) { success(); return; }
    if (self.initializing) { failure(409, @"通话连接仍在初始化"); return; }
    TUICallFail fail = ^(int code, NSString *message) { failure(code, message ?: @"通话结束失败"); };
    if (reject) [self.engine reject:success fail:fail];
    else [self.engine hangup:success fail:fail];
}
- (void)openMicrophoneWithSuccess:(SPXCallSuccess)success failure:(SPXCallFailure)failure {
    [self.engine openMicrophone:success fail:^(int code, NSString *message) { failure(code, message ?: @"麦克风开启失败"); }];
}
- (void)closeMicrophone { [self.engine closeMicrophone]; }
- (void)setSpeaker:(BOOL)speaker {
    [self.engine selectAudioPlaybackDevice:speaker ? TUIAudioPlaybackDeviceSpeakerphone : TUIAudioPlaybackDeviceEarpiece];
}
- (void)openCameraInView:(UIView *)view front:(BOOL)front success:(SPXCallSuccess)success failure:(SPXCallFailure)failure {
    [self.engine openCamera:front ? TUICameraFront : TUICameraBack videoView:view succ:success
                      fail:^(int code, NSString *message) { failure(code, message ?: @"摄像头开启失败"); }];
}
- (void)closeCamera { [self.engine closeCamera]; }
- (void)switchCameraToFront:(BOOL)front { [self.engine switchCamera:front ? TUICameraFront : TUICameraBack]; }
- (void)showRemoteUser:(NSString *)userID inView:(UIView *)view {
    [self.engine startRemoteView:userID videoView:view onPlaying:^(NSString *user) {}
                      onLoading:^(NSString *user) {}
                        onError:^(NSString *user, int code, NSString *message) {
        [self emit:@{@"kind": @"videoError", @"peer": user, @"code": @(code), @"message": message ?: @""}];
    }];
}
- (void)stopRemoteUser:(NSString *)userID { [self.engine stopRemoteView:userID]; }
- (void)invalidate {
    self.invalidated = YES;
    self.eventHandler = nil;
    if (self.initializing) { SPXRetiringBridge = self; return; }
    [self destroyRetiredEngine];
}
- (void)destroyRetiredEngine {
    if (self.engine) {
        [self.engine removeObserver:self];
        [TUICallEngine destroyInstance];
        self.engine = nil;
    }
    if (SPXRetiringBridge == self) SPXRetiringBridge = nil;
}
- (void)emit:(NSDictionary *)event {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!self.invalidated && self.eventHandler) self.eventHandler(event);
    });
}
- (NSDictionary *)event:(NSString *)kind callID:(NSString *)callID media:(TUICallMediaType)media
                  info:(TUICallObserverExtraInfo *)info {
    return @{@"kind": kind, @"sdk_id": callID ?: @"", @"session_id": info.userData ?: @"",
             @"room": @(info.roomId.intRoomId), @"video": @(media == TUICallMediaTypeVideo),
             @"role": @(info.role)};
}
- (void)onCallReceived:(NSString *)callID callerId:(NSString *)caller calleeIdList:(NSArray<NSString *> *)callees
             mediaType:(TUICallMediaType)media info:(TUICallObserverExtraInfo *)info {
    NSMutableDictionary *event = [[self event:@"received" callID:callID media:media info:info] mutableCopy];
    event[@"peer"] = caller;
    event[@"callees"] = callees;
    [self emit:event];
}
- (void)onCallBegin:(NSString *)callID mediaType:(TUICallMediaType)media info:(TUICallObserverExtraInfo *)info {
    [self emit:[self event:@"begin" callID:callID media:media info:info]];
}
- (void)onCallEnd:(NSString *)callID mediaType:(TUICallMediaType)media reason:(TUICallEndReason)reason
           userId:(NSString *)userID totalTime:(float)totalTime info:(TUICallObserverExtraInfo *)info {
    [self ended:callID media:media reason:reason info:info];
}
- (void)onCallNotConnected:(NSString *)callID mediaType:(TUICallMediaType)media reason:(TUICallEndReason)reason
                    userId:(NSString *)userID info:(TUICallObserverExtraInfo *)info {
    [self ended:callID media:media reason:reason info:info];
}
- (void)ended:(NSString *)callID media:(TUICallMediaType)media reason:(TUICallEndReason)reason info:(TUICallObserverExtraInfo *)info {
    NSMutableDictionary *event = [[self event:@"end" callID:callID media:media info:info] mutableCopy];
    event[@"reason"] = @(reason);
    [self emit:event];
}
- (void)onUserVideoAvailable:(NSString *)userID isVideoAvailable:(BOOL)available {
    [self emit:@{@"kind": @"video", @"peer": userID, @"available": @(available)}];
}
- (void)onError:(int)code message:(NSString *)message {
    [self emit:@{@"kind": @"error", @"code": @(code), @"message": message ?: @""}];
}
- (void)onUserSigExpired { [self emit:@{@"kind": @"expired"}]; }
- (void)onKickedOffline { [self emit:@{@"kind": @"kicked"}]; }
@end
