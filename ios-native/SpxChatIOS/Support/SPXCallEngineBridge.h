#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN
typedef void (^SPXCallSuccess)(void);
typedef void (^SPXCallFailure)(int code, NSString *message);

/// Objective-C selectors are kept here so SDK Swift-importer differences cannot
/// silently prevent the optional incoming-call observer from being called.
@interface SPXCallEngineBridge : NSObject
@property(nonatomic, copy, nullable) void (^eventHandler)(NSDictionary<NSString *, id> *event);
- (void)initializeWithAppID:(int)appID userID:(NSString *)userID userSig:(NSString *)userSig
                   success:(SPXCallSuccess)success failure:(SPXCallFailure)failure
    NS_SWIFT_NAME(initialize(appID:userID:userSig:success:failure:));
- (BOOL)isLoggedInAs:(NSString *)userID NS_SWIFT_NAME(isLoggedIn(as:));
- (void)callUser:(NSString *)userID video:(BOOL)video sessionID:(NSString *)sessionID
           room:(uint32_t)room timeout:(int)timeout success:(SPXCallSuccess)success failure:(SPXCallFailure)failure
    NS_SWIFT_NAME(call(userID:video:sessionID:room:timeout:success:failure:));
- (void)acceptWithSuccess:(SPXCallSuccess)success failure:(SPXCallFailure)failure NS_SWIFT_NAME(accept(success:failure:));
- (void)terminateRejecting:(BOOL)reject success:(SPXCallSuccess)success failure:(SPXCallFailure)failure
    NS_SWIFT_NAME(terminate(reject:success:failure:));
- (void)openMicrophoneWithSuccess:(SPXCallSuccess)success failure:(SPXCallFailure)failure NS_SWIFT_NAME(openMicrophone(success:failure:));
- (void)closeMicrophone;
- (void)setSpeaker:(BOOL)speaker;
- (void)openCameraInView:(UIView *)view front:(BOOL)front success:(SPXCallSuccess)success failure:(SPXCallFailure)failure
    NS_SWIFT_NAME(openCamera(in:front:success:failure:));
- (void)closeCamera;
- (void)switchCameraToFront:(BOOL)front NS_SWIFT_NAME(switchCamera(front:));
- (void)showRemoteUser:(NSString *)userID inView:(UIView *)view NS_SWIFT_NAME(showRemote(userID:in:));
- (void)stopRemoteUser:(NSString *)userID NS_SWIFT_NAME(stopRemote(userID:));
- (void)invalidate;
@end
NS_ASSUME_NONNULL_END
