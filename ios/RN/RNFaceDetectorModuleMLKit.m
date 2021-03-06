#import "RNFaceDetectorModuleMLKit.h"
#if __has_include(<FirebaseMLVision/FirebaseMLVision.h>)
#import "RNFileSystem.h"
#import "RNImageUtils.h"
#import <React/RCTLog.h>
static const NSString *kModeOptionName = @"mode";
static const NSString *kDetectLandmarksOptionName = @"detectLandmarks";
static const NSString *kRunClassificationsOptionName = @"runClassifications";

@implementation RNFaceDetectorModuleMLKit

static NSFileManager *fileManager = nil;
static NSDictionary *defaultDetectorOptions = nil;

- (instancetype)init
{
    self = [super init];
    if (self) {
        fileManager = [NSFileManager defaultManager];
    }
    return self;
}
        // --------------------------------------  <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<  check this
        // expose the face detector module to react bridge
RCT_EXPORT_MODULE(RNFaceDetector);

@synthesize bridge = _bridge;

- (void)setBridge:(RCTBridge *)bridge
{
    _bridge = bridge;
}

+ (BOOL)requiresMainQueueSetup
{
    return NO;
}

- (NSDictionary *)constantsToExport
{
    return [FaceDetectorManagerMlkit constants];
}
        // --------------------------------------  <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<  check this
        // expose the background task detectFaces to react bridge
RCT_EXPORT_METHOD(detectFaces:(nonnull NSDictionary *)options
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
            // --------------------------------------  <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<  check this
        // todo: check where the options uri come from 
// ================================================  <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
        // RCTLogInfo(@"FaceDetectorModuleMlkit > exportMethod detectFaces: options = %@",options);  //only warn or error get response from react log.
// ================================================  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^  
    NSString *uri = options[@"uri"];
    if (uri == nil) {
        reject(@"E_FACE_DETECTION_FAILED", @"You must define a URI.", nil);
        return;
    }
    // convert uri into native system path
    NSURL *url = [NSURL URLWithString:uri];
    NSString *path = [url.path stringByStandardizingPath];
    // ================================================  <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
        // RCTLogInfo(@"FaceDetectorModuleMlkit > exportMethod detectFaces: path = %@",path);  //only warn or error get response from react log.
// ================================================  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^  
    @try {
        if (![fileManager fileExistsAtPath:path]) {
            reject(@"E_FACE_DETECTION_FAILED", [NSString stringWithFormat:@"The file does not exist. Given path: `%@`.", path], nil);
            return;
        }
    // ================================================  <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
        // RCTLogInfo(@"FaceDetectorModuleMlkit > exportMethod detectFaces: file existed at the given path");  //only warn or error get response from react log.
// ================================================  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^         
        FIRVisionFaceDetectorOptions *newOptions = [[FIRVisionFaceDetectorOptions alloc] init];
        if (options[kDetectLandmarksOptionName]) {
            newOptions.landmarkMode = [options[kDetectLandmarksOptionName] integerValue];
        }
        
        if (options[kModeOptionName]) {
            newOptions.performanceMode = [options[kModeOptionName] integerValue];
        }
        
        if (options[kRunClassificationsOptionName]) {
            newOptions.classificationMode = [options[kRunClassificationsOptionName] integerValue];
        }
        // get image from path...
        UIImage *image = [[UIImage alloc] initWithContentsOfFile:path];
        UIImage *rotatedImage = [RNImageUtils forceUpOrientation:image];
                // --------------------------------------  <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<  check this
        // inject the facedetector class to bridge and run
        Class faceDetectorManagerClassMlkit = NSClassFromString(@"FaceDetectorManagerMlkit");
        id faceDetector = [[faceDetectorManagerClassMlkit alloc] init];
            // ================================================  <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
        // RCTLogInfo(@"FaceDetectorModuleMlkit > exportMethod detectFaces: init faceDetector, findFacesInFrame");  //only warn or error get response from react log.
// ================================================  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^  
        // detect face in frame, 
        // todo: why scale 1x1, why rotate image
        [faceDetector findFacesInFrame:rotatedImage scaleX:1 scaleY:1 completed:^(NSArray * faces) {
            // todo: resolve faces and image info, where will it go? to react bridge?
            resolve(@{
                        @"faces" : faces,
                        @"image" : @{
                                @"uri" : options[@"uri"],
                                @"width" : @(image.size.width),
                                @"height" : @(image.size.height),
                                @"orientation" : @([self exifOrientationFor:image.imageOrientation])
                                }
                        });
            }];
    } @catch (NSException *exception) {
        reject(@"E_FACE_DETECTION_FAILED", [exception description], nil);
    }
}


// https://gist.github.com/steipete/4666527
- (int)exifOrientationFor:(UIImageOrientation)orientation
{
    switch (orientation) {
        case UIImageOrientationUp:
            return 1;
        case UIImageOrientationDown:
            return 3;
        case UIImageOrientationLeft:
            return 8;
        case UIImageOrientationRight:
            return 6;
        case UIImageOrientationUpMirrored:
            return 2;
        case UIImageOrientationDownMirrored:
            return 4;
        case UIImageOrientationLeftMirrored:
            return 5;
        case UIImageOrientationRightMirrored:
            return 7;
    }
}

@end
        // --------------------------------------  <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<  check this
        // todo: why need this else of implementation
#else
@implementation RNFaceDetectorModuleMLKit

@synthesize bridge = _bridge;

- (void)setBridge:(RCTBridge *)bridge
{
    _bridge = bridge;
}

+ (BOOL)requiresMainQueueSetup
{
    return NO;
}

- (NSDictionary *)constantsToExport
{
    return @{};
}
        // --------------------------------------  <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<  check this
        // todo: implement a custom face detector
RCT_EXPORT_MODULE(RNFaceDetector);

@end
#endif
