package org.reactnative.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.os.Build;
import androidx.core.content.ContextCompat;

import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.os.AsyncTask;
import com.facebook.react.bridge.*;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.bridge.ReactContext;
import com.google.android.cameraview.CameraView;
import com.google.android.gms.vision.face.Face;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import org.reactnative.barcodedetector.RNBarcodeDetector;
import org.reactnative.camera.tasks.*;
import org.reactnative.camera.tasks.FaceVerifierAsyncTask;
import org.reactnative.camera.utils.ImageDimensions;
import org.reactnative.camera.utils.RNFileUtils;
import org.reactnative.facedetector.RNFaceDetector;
import org.reactnative.frame.RNFrame;
import org.reactnative.frame.RNFrameFactory;
import org.tensorflow.lite.Interpreter;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RNCameraView extends CameraView implements
        LifecycleEventListener,
        BarCodeScannerAsyncTaskDelegate,
        FaceDetectorAsyncTaskDelegate,
        FaceVerifierAsyncTaskDelegate,
        BarcodeDetectorAsyncTaskDelegate,
        TextRecognizerAsyncTaskDelegate,
        PictureSavedDelegate {
  private ThemedReactContext mThemedReactContext;
  private Queue<Promise> mPictureTakenPromises = new ConcurrentLinkedQueue<>();
  private Map<Promise, ReadableMap> mPictureTakenOptions = new ConcurrentHashMap<>();
  private Map<Promise, File> mPictureTakenDirectories = new ConcurrentHashMap<>();
  private Promise mVideoRecordedPromise;
  private List<String> mBarCodeTypes = null;
  private boolean mDetectedImageInEvent = false;

  private ScaleGestureDetector mScaleGestureDetector;
  private GestureDetector mGestureDetector;


  private boolean mIsPaused = false;
  private boolean mIsNew = true;
  private boolean invertImageData = false;
  private Boolean mIsRecording = false;
  private Boolean mIsRecordingInterrupted = false;
  private boolean mUseNativeZoom=false;

  // Concurrency lock for scanners to avoid flooding the runtime
  public volatile boolean barCodeScannerTaskLock = false;
  public volatile boolean faceDetectorTaskLock = false;
  // =============<<<<<<<<<<<<<<<<< check here
  public volatile boolean faceVerifierTaskLock = false;
  public volatile boolean googleBarcodeDetectorTaskLock = false;
  public volatile boolean textRecognizerTaskLock = false;
  public volatile boolean takingPictureLock = false;

  // Scanning-related properties
  private MultiFormatReader mMultiFormatReader;
  private RNFaceDetector mFaceDetector;
  // =============<<<<<<<<<<<<<<<<< check here
  private Interpreter mFaceVerifier;
  private RNBarcodeDetector mGoogleBarcodeDetector;
  private boolean mShouldDetectFaces = false;
  // =============<<<<<<<<<<<<<<<<< check here
  private boolean mShouldVerifyFaces = false;
  private boolean mShouldGoogleDetectBarcodes = false;
  private boolean mShouldScanBarCodes = false;
  private boolean mShouldRecognizeText = false;
  private boolean mShouldDetectTouches = false;
  private int mFaceDetectorMode = RNFaceDetector.FAST_MODE;
  private int mFaceDetectionLandmarks = RNFaceDetector.NO_LANDMARKS;
  private int mFaceDetectionClassifications = RNFaceDetector.NO_CLASSIFICATIONS;
  private int mGoogleVisionBarCodeType = RNBarcodeDetector.ALL_FORMATS;
  private int mGoogleVisionBarCodeMode = RNBarcodeDetector.NORMAL_MODE;
  private boolean mTrackingEnabled = true;
  private int mPaddingX;
  private int mPaddingY;
  private HashMap<String,Float> mLastFace;

  // Limit Android Scan Area
  private boolean mLimitScanArea = false;
  private float mScanAreaX = 0.0f;
  private float mScanAreaY = 0.0f;
  private float mScanAreaWidth = 0.0f;
  private float mScanAreaHeight = 0.0f;
  private int mCameraViewWidth = 0;
  private int mCameraViewHeight = 0;

  private long mLastFinish = 0;
  private String userImageName;
  private String userImageDir;


  public RNCameraView(ThemedReactContext themedReactContext) {
    super(themedReactContext, true);
    mThemedReactContext = themedReactContext;
    themedReactContext.addLifecycleEventListener(this);
    mLastFace=new HashMap<>();
//    MyModelModule myModelModule = new MyModelModule((ReactContext) this.getContext());

    addCallback(new Callback() {
      @Override
      public void onCameraOpened(CameraView cameraView) {
        RNCameraViewHelper.emitCameraReadyEvent(cameraView);
      }

      @Override
      public void onMountError(CameraView cameraView) {
        RNCameraViewHelper.emitMountErrorEvent(cameraView, "Camera view threw an error - component could not be rendered.");
      }

      // =============<<<<<<<<<<<<<<<<< check here
      @Override
      public void onPictureTaken(CameraView cameraView, final byte[] data, int deviceOrientation) {
//        Log.i("Debug","RNCameraView onPictureTaken ...");
        Promise promise = mPictureTakenPromises.poll();
        ReadableMap options = mPictureTakenOptions.remove(promise);
        if (options.hasKey("fastMode") && options.getBoolean("fastMode")) {
          promise.resolve(null);
        }
        final File cacheDirectory = mPictureTakenDirectories.remove(promise);
//        Log.i("Debug","RNCameraView onPictureTaken  call resolvetakenpictureasynctask");
        if(Build.VERSION.SDK_INT >= 11/*HONEYCOMB*/) {
//          Log.i("Debug","RNCameraView onPictureTaken sdk>11 call resolvetakenpictureasynctask");
          new ResolveTakenPictureAsyncTask(data, promise, options, cacheDirectory, deviceOrientation, RNCameraView.this)
                  .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
          new ResolveTakenPictureAsyncTask(data, promise, options, cacheDirectory, deviceOrientation, RNCameraView.this)
                  .execute();
        }
//        Log.i("Debug","RNCameraView onPictureTaken emitpicturetakenevent");
        RNCameraViewHelper.emitPictureTakenEvent(cameraView);
      }

      @Override
      public void onRecordingStart(CameraView cameraView, String path, int videoOrientation, int deviceOrientation) {
        WritableMap result = Arguments.createMap();
        result.putInt("videoOrientation", videoOrientation);
        result.putInt("deviceOrientation", deviceOrientation);
        result.putString("uri", RNFileUtils.uriFromFile(new File(path)).toString());
        RNCameraViewHelper.emitRecordingStartEvent(cameraView, result);
      }

      @Override
      public void onRecordingEnd(CameraView cameraView) {
        RNCameraViewHelper.emitRecordingEndEvent(cameraView);
      }

      @Override
      public void onVideoRecorded(CameraView cameraView, String path, int videoOrientation, int deviceOrientation) {
        if (mVideoRecordedPromise != null) {
          if (path != null) {
            WritableMap result = Arguments.createMap();
            result.putBoolean("isRecordingInterrupted", mIsRecordingInterrupted);
            result.putInt("videoOrientation", videoOrientation);
            result.putInt("deviceOrientation", deviceOrientation);
            result.putString("uri", RNFileUtils.uriFromFile(new File(path)).toString());
            mVideoRecordedPromise.resolve(result);
          } else {
            mVideoRecordedPromise.reject("E_RECORDING", "Couldn't stop recording - there is none in progress");
          }
          mIsRecording = false;
          mIsRecordingInterrupted = false;
          mVideoRecordedPromise = null;
        }
      }

      // =============<<<<<<<<<<<<<<<<< check here
      //  todo: similar to output capture of ios
      @Override
      public void onFramePreview(CameraView cameraView, byte[] data, int width, int height, int rotation) {
        int correctRotation = RNCameraViewHelper.getCorrectCameraRotation(rotation, getFacing(), getCameraOrientation());
        boolean willCallBarCodeTask = mShouldScanBarCodes && !barCodeScannerTaskLock && cameraView instanceof BarCodeScannerAsyncTaskDelegate;
        boolean willCallFaceTask = mShouldDetectFaces && !faceDetectorTaskLock && cameraView instanceof FaceDetectorAsyncTaskDelegate;
        boolean willCallGoogleBarcodeTask = mShouldGoogleDetectBarcodes && !googleBarcodeDetectorTaskLock && cameraView instanceof BarcodeDetectorAsyncTaskDelegate;
        boolean willCallTextTask = mShouldRecognizeText && !textRecognizerTaskLock && cameraView instanceof TextRecognizerAsyncTaskDelegate;
        // Log.i("Debug",String.format("RNCameraView onFramePreview run"));
        if (!willCallBarCodeTask && !willCallFaceTask && !willCallGoogleBarcodeTask && !willCallTextTask) {
          // Log.i("Debug",String.format("RNCameraView nothing"));
          return;
        }

        if (data.length < (1.5 * width * height)) {
          return;
        }

        if (willCallBarCodeTask) {
          barCodeScannerTaskLock = true;
          BarCodeScannerAsyncTaskDelegate delegate = (BarCodeScannerAsyncTaskDelegate) cameraView;
          new BarCodeScannerAsyncTask(delegate, mMultiFormatReader, data, width, height, mLimitScanArea, mScanAreaX, mScanAreaY, mScanAreaWidth, mScanAreaHeight, mCameraViewWidth, mCameraViewHeight, getAspectRatio().toFloat()).execute();
        }

        if (willCallFaceTask && !takingPictureLock) {
          Calendar calendar = Calendar.getInstance();
//          calendar.setTime(new Date());
          long mTimeNow = calendar.getTimeInMillis();
          // Log.i("Debug", String.format("RNCameraView onFramePreview lastcallfinished: %d ; now = %d",
//                                mLastFinish,mTimeNow));
          if(mTimeNow - mLastFinish > 500) {
            faceDetectorTaskLock = true;
            byte[] dataCopy = data.clone();


            // =============<<<<<<<<<<<<<<<<< check here
            // Log.i("Debug", String.format("RNCameraView onFramePreview detectface start"));
            FaceDetectorAsyncTaskDelegate delegate = (FaceDetectorAsyncTaskDelegate) cameraView;
            new FaceDetectorAsyncTask(delegate, mFaceDetector,
                    dataCopy, width, height, correctRotation,
                    getResources().getDisplayMetrics().density,
                    getFacing(), getWidth(), getHeight(),
                    mPaddingX, mPaddingY).execute();
//            todo: add face verification here. or in the asynctask
            boolean willCallFaceVerifyTask = true;
//            Log.i("Debug","RNCameraView check interpreter :  "+mFaceVerifier.toString());
            Log.i("Debug","RNCameraView mshouldverifyfaces = "+mShouldVerifyFaces);
            if(willCallFaceVerifyTask && mShouldVerifyFaces && mFaceVerifier != null && !faceVerifierTaskLock){
              Log.i("Debug","RNCameraView lastFaceDetected: "+mLastFace.get("faceID"));
              Log.i("Debug",String.format("RNCameraView x= %.2f ; y= %.2f: ",
                      mLastFace.get("x"),mLastFace.get("y")));
              Log.i("Debug",String.format("RNCameraView w= %.2f ; h= %.2f: ",
                      mLastFace.get("width"),mLastFace.get("height")));

              faceVerifierTaskLock = true;
              String userImageFile =  mThemedReactContext.getFilesDir().getAbsolutePath()+
                      "/"+userImageDir+"/"+userImageName+".png";
              Log.i("Debug","RNCameraView userImageFile: "+userImageFile);
              File file = new File(userImageFile);
              if(!file.exists()){
                Log.i("Debug","RNCameraView userImageFile missed");
                faceVerifierTaskLock = false;
              }else {
                  Log.i("Debug","will run verifytask... ");
                  byte[] d = convertYuvToJpeg(dataCopy,cameraView);
                  if (d != null) {
//                    Log.i("Debug","byte converted lenth="+d.length);
//                    Bitmap bitmap = BitmapFactory.decodeByteArray(d,0,d.length);
//
//                    if(mLastFace != null && mLastFace.get("faceID") != null){
//                      int largerEdge = mLastFace.get("width") > mLastFace.get("height") ?
//                              mLastFace.get("width").intValue() :
//                              mLastFace.get("height").intValue();
//                      bitmap = ImageUtils.cutFace(bitmap,
//                              mLastFace.get("x").intValue(), mLastFace.get("y").intValue(),
//                              largerEdge,largerEdge);
//                    }

//                    Log.i("Debug","bitmap converted lenth="+bitmap.getByteCount());
                  }else {
                    Log.i("Debug","byte converted error lenth="+d);
                  }
//                  String dest_fake2 = "/User/obama.jpg";
//                  String dest_fake1 = "/User/taylor.jpg";
//                  String userImagePath = mThemedReactContext.getFilesDir().getAbsolutePath()+dest_fake2;
//                  String user0ImagePath = mThemedReactContext.getFilesDir().getAbsolutePath()+dest_fake1;
                  FaceVerifierAsyncTaskDelegate delegate1 = (FaceVerifierAsyncTaskDelegate) cameraView;
//                  new FaceVerifierAsyncTask(delegate1, mFaceVerifier,
//                          userImageFile,user0ImagePath,
//                          d, width, height, correctRotation,
//                          getResources().getDisplayMetrics().density,
//                          getFacing(), getWidth(), getHeight(),
//                          mPaddingX, mPaddingY).execute();
                new FaceVerifierAsyncTask(delegate1, mFaceVerifier,
                        userImageFile,
                        d, mLastFace, width, height, correctRotation,
                        getResources().getDisplayMetrics().density,
                        getFacing(), getWidth(), getHeight(),
                        mPaddingX, mPaddingY).execute();
              }
            }
          }
        }

        if (willCallGoogleBarcodeTask) {
          googleBarcodeDetectorTaskLock = true;
          if (mGoogleVisionBarCodeMode == RNBarcodeDetector.NORMAL_MODE) {
            invertImageData = false;
          } else if (mGoogleVisionBarCodeMode == RNBarcodeDetector.ALTERNATE_MODE) {
            invertImageData = !invertImageData;
          } else if (mGoogleVisionBarCodeMode == RNBarcodeDetector.INVERTED_MODE) {
            invertImageData = true;
          }
          if (invertImageData) {
            for (int y = 0; y < data.length; y++) {
              data[y] = (byte) ~data[y];
            }
          }
          BarcodeDetectorAsyncTaskDelegate delegate = (BarcodeDetectorAsyncTaskDelegate) cameraView;
          new BarcodeDetectorAsyncTask(delegate, mGoogleBarcodeDetector, data, width, height,
                  correctRotation, getResources().getDisplayMetrics().density, getFacing(),
                  getWidth(), getHeight(), mPaddingX, mPaddingY).execute();
        }

        if (willCallTextTask) {
          textRecognizerTaskLock = true;
          TextRecognizerAsyncTaskDelegate delegate = (TextRecognizerAsyncTaskDelegate) cameraView;
          new TextRecognizerAsyncTask(delegate, mThemedReactContext, data, width, height, correctRotation, getResources().getDisplayMetrics().density, getFacing(), getWidth(), getHeight(), mPaddingX, mPaddingY).execute();
        }
      }
    });
  }
  public byte[] convertYuvToJpeg(byte[] data, CameraView camera) {
    try {
      YuvImage image = new YuvImage(data, ImageFormat.NV21,
              camera.getPreviewSize().getWidth(),
              camera.getPreviewSize().getHeight(),
              null);
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      Rect rect = new Rect(0, 0,
              camera.getPreviewSize().getWidth(),
              camera.getPreviewSize().getHeight());
      //set quality
      int quality = 100;
      image.compressToJpeg(rect, quality, baos);
      return baos.toByteArray();
    } catch (Exception e) {
    }
    return null;
  }

  // =============<<<<<<<<<<<<<<<<< check here
  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    View preview = getView();
    if (null == preview) {
      return;
    }
    float width = right - left;
    float height = bottom - top;
    float ratio = getAspectRatio().toFloat();
    int orientation = getResources().getConfiguration().orientation;
    int correctHeight;
    int correctWidth;
    this.setBackgroundColor(Color.BLACK);
    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
      if (ratio * height < width) {
        correctHeight = (int) (width / ratio);
        correctWidth = (int) width;
      } else {
        correctWidth = (int) (height * ratio);
        correctHeight = (int) height;
      }
    } else {
      if (ratio * width > height) {
        correctHeight = (int) (width * ratio);
        correctWidth = (int) width;
      } else {
        correctWidth = (int) (height / ratio);
        correctHeight = (int) height;
      }
    }
    int paddingX = (int) ((width - correctWidth) / 2);
    int paddingY = (int) ((height - correctHeight) / 2);
    mPaddingX = paddingX;
    mPaddingY = paddingY;
    preview.layout(paddingX, paddingY, correctWidth + paddingX, correctHeight + paddingY);
  }

  @SuppressLint("all")
  @Override
  public void requestLayout() {
    // React handles this for us, so we don't need to call super.requestLayout();
  }

  public void setBarCodeTypes(List<String> barCodeTypes) {
    mBarCodeTypes = barCodeTypes;
    initBarcodeReader();
  }

  public void setDetectedImageInEvent(boolean detectedImageInEvent) {
    this.mDetectedImageInEvent = detectedImageInEvent;
  }

  // =============<<<<<<<<<<<<<<<<< check here
  public void takePicture(final ReadableMap options, final Promise promise, final File cacheDirectory, final String fileName) {
    takingPictureLock = true;
    mBgHandler.post(new Runnable() {
      @Override
      public void run() {
        mPictureTakenPromises.add(promise);
        mPictureTakenOptions.put(promise, options);
        mPictureTakenDirectories.put(promise, cacheDirectory);

        try {
          // =============<<<<<<<<<<<<<<<<< check here
//          Log.i("Debug","RNCameraView takePicture call: super.takePicure");
          RNCameraView.super.takePicture(options);
        } catch (Exception e) {
          takingPictureLock = false;
          mPictureTakenPromises.remove(promise);
          mPictureTakenOptions.remove(promise);
          mPictureTakenDirectories.remove(promise);
          Log.i("Debug","CameraView takePicture error: "+e.getMessage());
          promise.reject("E_TAKE_PICTURE_FAILED", e.getMessage());
        }
      }
    });
  }

  @Override
  public void onPictureSaved(WritableMap response) {
    takingPictureLock = false;
    RNCameraViewHelper.emitPictureSavedEvent(this, response);
  }

  public void record(final ReadableMap options, final Promise promise, final File cacheDirectory) {
    mBgHandler.post(new Runnable() {
      @Override
      public void run() {
        try {
          String path = options.hasKey("path") ? options.getString("path") : RNFileUtils.getOutputFilePath(cacheDirectory, ".mp4");
          int maxDuration = options.hasKey("maxDuration") ? options.getInt("maxDuration") : -1;
          int maxFileSize = options.hasKey("maxFileSize") ? options.getInt("maxFileSize") : -1;
          int fps = options.hasKey("fps") ? options.getInt("fps") : -1;

          CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
          if (options.hasKey("quality")) {
            profile = RNCameraViewHelper.getCamcorderProfile(options.getInt("quality"));
          }
          if (options.hasKey("videoBitrate")) {
            profile.videoBitRate = options.getInt("videoBitrate");
          }

          boolean recordAudio = true;
          if (options.hasKey("mute")) {
            recordAudio = !options.getBoolean("mute");
          }

          int orientation = Constants.ORIENTATION_AUTO;
          if (options.hasKey("orientation")) {
            orientation = options.getInt("orientation");
          }

          if (RNCameraView.super.record(path, maxDuration * 1000, maxFileSize, recordAudio, profile, orientation, fps)) {
            mIsRecording = true;
            mVideoRecordedPromise = promise;
          } else {
            promise.reject("E_RECORDING_FAILED", "Starting video recording failed. Another recording might be in progress.");
          }
        } catch (IOException e) {
          promise.reject("E_RECORDING_FAILED", "Starting video recording failed - could not create video file.");
        }
      }
    });
  }

  /**
   * Initialize the barcode decoder.
   * Supports all iOS codes except [code138, code39mod43, itf14]
   * Additionally supports [codabar, code128, maxicode, rss14, rssexpanded, upc_a, upc_ean]
   */
  private void initBarcodeReader() {
    mMultiFormatReader = new MultiFormatReader();
    EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    EnumSet<BarcodeFormat> decodeFormats = EnumSet.noneOf(BarcodeFormat.class);

    if (mBarCodeTypes != null) {
      for (String code : mBarCodeTypes) {
        String formatString = (String) CameraModule.VALID_BARCODE_TYPES.get(code);
        if (formatString != null) {
          decodeFormats.add(BarcodeFormat.valueOf(formatString));
        }
      }
    }

    hints.put(DecodeHintType.POSSIBLE_FORMATS, decodeFormats);
    mMultiFormatReader.setHints(hints);
  }

  public void setShouldScanBarCodes(boolean shouldScanBarCodes) {
    if (shouldScanBarCodes && mMultiFormatReader == null) {
      initBarcodeReader();
    }
    this.mShouldScanBarCodes = shouldScanBarCodes;
    setScanning(mShouldDetectFaces || mShouldGoogleDetectBarcodes || mShouldScanBarCodes || mShouldRecognizeText);
  }

  public void onBarCodeRead(Result barCode, int width, int height, byte[] imageData) {
    String barCodeType = barCode.getBarcodeFormat().toString();
    if (!mShouldScanBarCodes || !mBarCodeTypes.contains(barCodeType)) {
      return;
    }

    final byte[] compressedImage;
    if (mDetectedImageInEvent) {
      try {
//        todo: check this
        // https://stackoverflow.com/a/32793908/122441
        final YuvImage yuvImage = new YuvImage(imageData, ImageFormat.NV21, width, height, null);
        final ByteArrayOutputStream imageStream = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, imageStream);
        compressedImage = imageStream.toByteArray();
      } catch (Exception e) {
        throw new RuntimeException(String.format("Error decoding imageData from NV21 format (%d bytes)", imageData.length), e);
      }
    } else {
      compressedImage = null;
    }

    RNCameraViewHelper.emitBarCodeReadEvent(this, barCode, width, height, compressedImage);
  }

  public void onBarCodeScanningTaskCompleted() {
    barCodeScannerTaskLock = false;
    if(mMultiFormatReader != null) {
      mMultiFormatReader.reset();
    }
  }

  // Limit Scan Area
  public void setRectOfInterest(float x, float y, float width, float height) {
    this.mLimitScanArea = true;
    this.mScanAreaX = x;
    this.mScanAreaY = y;
    this.mScanAreaWidth = width;
    this.mScanAreaHeight = height;
  }
  public void setCameraViewDimensions(int width, int height) {
    this.mCameraViewWidth = width;
    this.mCameraViewHeight = height;
  }


  public void setShouldDetectTouches(boolean shouldDetectTouches) {
    if(!mShouldDetectTouches && shouldDetectTouches){
      mGestureDetector=new GestureDetector(mThemedReactContext,onGestureListener);
    }else{
      mGestureDetector=null;
    }
    this.mShouldDetectTouches = shouldDetectTouches;
  }

  public void setUseNativeZoom(boolean useNativeZoom){
    if(!mUseNativeZoom && useNativeZoom){
      mScaleGestureDetector = new ScaleGestureDetector(mThemedReactContext,onScaleGestureListener);
    }else{
      mScaleGestureDetector=null;
    }
    mUseNativeZoom=useNativeZoom;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if(mUseNativeZoom) {
      mScaleGestureDetector.onTouchEvent(event);
    }
    if(mShouldDetectTouches){
      mGestureDetector.onTouchEvent(event);
    }
    return true;
  }

  // =============<<<<<<<<<<<<<<<<< check here
  /**
   * Initial setup of the face detector
   */
  private void setupFaceDetector() {
    mFaceDetector = new RNFaceDetector(mThemedReactContext);
    mFaceDetector.setMode(mFaceDetectorMode);
    mFaceDetector.setLandmarkType(mFaceDetectionLandmarks);
    mFaceDetector.setClassificationType(mFaceDetectionClassifications);
    mFaceDetector.setTracking(mTrackingEnabled);
  }
  // =============<<<<<<<<<<<<<<<<< check here
  /**
   * Initial setup of the face verifier
   */
  private void setupFaceVerifier() {
    mFaceVerifier =  MyModelModule.getInterpreter();
  }
  public void setFaceDetectionLandmarks(int landmarks) {
    mFaceDetectionLandmarks = landmarks;
    if (mFaceDetector != null) {
      mFaceDetector.setLandmarkType(landmarks);
    }
  }

  public void setFaceDetectionClassifications(int classifications) {
    mFaceDetectionClassifications = classifications;
    if (mFaceDetector != null) {
      mFaceDetector.setClassificationType(classifications);
    }
  }

  public void setFaceDetectionMode(int mode) {
    mFaceDetectorMode = mode;
    if (mFaceDetector != null) {
      mFaceDetector.setMode(mode);
    }
  }

  public void setTracking(boolean trackingEnabled) {
    mTrackingEnabled = trackingEnabled;
    if (mFaceDetector != null) {
      mFaceDetector.setTracking(trackingEnabled);
    }
  }
  // =============<<<<<<<<<<<<<<<<< check here
  public void setShouldDetectFaces(boolean shouldDetectFaces) {
    if (shouldDetectFaces && mFaceDetector == null) {
      setupFaceDetector();
    }
    if(mFaceVerifier == null){
      setupFaceVerifier();
    }
    this.mShouldDetectFaces = shouldDetectFaces;
    setScanning(mShouldDetectFaces || mShouldGoogleDetectBarcodes || mShouldScanBarCodes || mShouldRecognizeText);
  }


//    RNCameraViewHelper.emitFacesDetectedEvent(this, data);


  public void onFacesDetected(WritableArray data) {
    if (!mShouldDetectFaces) {
      return;
    }
//    todo: save face to cut....
    RNCameraViewHelper.emitFacesDetectedEvent(this, data);
  }

  public void saveFaceDetected(HashMap<String, Float> face) {
    if (face != null){
      mLastFace = face;
    }
  }



  public void onFaceDetectionError(RNFaceDetector faceDetector) {
    if (!mShouldDetectFaces) {
      return;
    }
    faceDetectorTaskLock = false;
    RNCameraViewHelper.emitFaceDetectionErrorEvent(this, faceDetector);

  }

  @Override
  public void onFaceDetectingTaskCompleted() {
//    try {
//      Log.i("Debug", "RNCameraView facedetectcompleted, sleep0.5" );
//      Thread.sleep(500);
//      faceDetectorTaskLock = false;
//      Log.i("Debug", "RNCameraView facedetectcompleted done" );
//    } catch (InterruptedException e) {
//      e.printStackTrace();
//    }
    faceDetectorTaskLock = false;
    Calendar calendar = Calendar.getInstance();
//    calendar.setTime(new Date());
//    mLastFinish = calendar.get(Calendar.MILLISECOND);
    mLastFinish = calendar.getTimeInMillis();
    // Log.i("Debug", String.format("RNCameraView lastcallfinished = %d",mLastFinish));
  }

  /**
   * Initial setup of the barcode detector
   */
  private void setupBarcodeDetector() {
    mGoogleBarcodeDetector = new RNBarcodeDetector(mThemedReactContext);
    mGoogleBarcodeDetector.setBarcodeType(mGoogleVisionBarCodeType);
  }

  public void setShouldGoogleDetectBarcodes(boolean shouldDetectBarcodes) {
    if (shouldDetectBarcodes && mGoogleBarcodeDetector == null) {
      setupBarcodeDetector();
    }
    this.mShouldGoogleDetectBarcodes = shouldDetectBarcodes;
    setScanning(mShouldDetectFaces || mShouldGoogleDetectBarcodes || mShouldScanBarCodes || mShouldRecognizeText);
  }

  public void setGoogleVisionBarcodeType(int barcodeType) {
    mGoogleVisionBarCodeType = barcodeType;
    if (mGoogleBarcodeDetector != null) {
      mGoogleBarcodeDetector.setBarcodeType(barcodeType);
    }
  }

  public void setGoogleVisionBarcodeMode(int barcodeMode) {
    mGoogleVisionBarCodeMode = barcodeMode;
  }

  public void onBarcodesDetected(WritableArray barcodesDetected, int width, int height, byte[] imageData) {
    if (!mShouldGoogleDetectBarcodes) {
      return;
    }

    // See discussion in https://github.com/react-native-community/react-native-camera/issues/2786
    final byte[] compressedImage;
    if (mDetectedImageInEvent) {
      try {
        // https://stackoverflow.com/a/32793908/122441
//        check this
//        todo: convert byte image data into image bitmap?
        final YuvImage yuvImage = new YuvImage(imageData, ImageFormat.NV21, width, height, null);
        final ByteArrayOutputStream imageStream = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, imageStream);
        compressedImage = imageStream.toByteArray();
      } catch (Exception e) {
        throw new RuntimeException(String.format("Error decoding imageData from NV21 format (%d bytes)", imageData.length), e);
      }
    } else {
      compressedImage = null;
    }

    RNCameraViewHelper.emitBarcodesDetectedEvent(this, barcodesDetected, compressedImage);
  }

  public void onBarcodeDetectionError(RNBarcodeDetector barcodeDetector) {
    if (!mShouldGoogleDetectBarcodes) {
      return;
    }

    RNCameraViewHelper.emitBarcodeDetectionErrorEvent(this, barcodeDetector);
  }

  @Override
  public void onBarcodeDetectingTaskCompleted() {
    googleBarcodeDetectorTaskLock = false;
  }

  /**
   *
   * Text recognition
   */

  public void setShouldRecognizeText(boolean shouldRecognizeText) {
    this.mShouldRecognizeText = shouldRecognizeText;
    setScanning(mShouldDetectFaces || mShouldGoogleDetectBarcodes || mShouldScanBarCodes || mShouldRecognizeText);
  }

  public void onTextRecognized(WritableArray serializedData) {
    if (!mShouldRecognizeText) {
      return;
    }

    RNCameraViewHelper.emitTextRecognizedEvent(this, serializedData);
  }

  @Override
  public void onTextRecognizerTaskCompleted() {
    textRecognizerTaskLock = false;
  }

  /**
   *
   * End Text Recognition */

  @Override
  public void onHostResume() {
    if (hasCameraPermissions()) {
      mBgHandler.post(new Runnable() {
        @Override
        public void run() {
          if ((mIsPaused && !isCameraOpened()) || mIsNew) {
            mIsPaused = false;
            mIsNew = false;
            start();
          }
        }
      });
    } else {
      RNCameraViewHelper.emitMountErrorEvent(this, "Camera permissions not granted - component could not be rendered.");
    }
  }

  @Override
  public void onHostPause() {
    if (mIsRecording) {
      mIsRecordingInterrupted = true;
    }
    if (!mIsPaused && isCameraOpened()) {
      mIsPaused = true;
      stop();
    }
  }

  @Override
  public void onHostDestroy() {
    if (mFaceDetector != null) {
      mFaceDetector.release();
    }
    if (mGoogleBarcodeDetector != null) {
      mGoogleBarcodeDetector.release();
    }
    mMultiFormatReader = null;
    mThemedReactContext.removeLifecycleEventListener(this);

    // camera release can be quite expensive. Run in on bg handler
    // and cleanup last once everything has finished
    mBgHandler.post(new Runnable() {
      @Override
      public void run() {
        stop();
        cleanup();
      }
    });
  }
  private void onZoom(float scale){
    float currentZoom=getZoom();
    float nextZoom=currentZoom+(scale-1.0f);

    if(nextZoom > currentZoom){
      setZoom(Math.min(nextZoom,1.0f));
    }else{
      setZoom(Math.max(nextZoom,0.0f));
    }

  }

  private boolean hasCameraPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      int result = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA);
      return result == PackageManager.PERMISSION_GRANTED;
    } else {
      return true;
    }
  }
  private int scalePosition(float raw){
    Resources resources = getResources();
    Configuration config = resources.getConfiguration();
    DisplayMetrics dm = resources.getDisplayMetrics();
    return (int)(raw/ dm.density);
  }
  private GestureDetector.SimpleOnGestureListener onGestureListener = new GestureDetector.SimpleOnGestureListener(){
    @Override
    public boolean onSingleTapUp(MotionEvent e) {
      RNCameraViewHelper.emitTouchEvent(RNCameraView.this,false,scalePosition(e.getX()),scalePosition(e.getY()));
      return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
      RNCameraViewHelper.emitTouchEvent(RNCameraView.this,true,scalePosition(e.getX()),scalePosition(e.getY()));
      return true;
    }
  };
  private ScaleGestureDetector.OnScaleGestureListener onScaleGestureListener = new ScaleGestureDetector.OnScaleGestureListener() {

    @Override
    public boolean onScale(ScaleGestureDetector scaleGestureDetector) {
      onZoom(scaleGestureDetector.getScaleFactor());
      return true;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector scaleGestureDetector) {
      onZoom(scaleGestureDetector.getScaleFactor());
      return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector scaleGestureDetector) {
    }

  };

  // =============<<<<<<<<<<<<<<<<< check here
  public void setShouldVerifyFaces(boolean faceVerifierEnabled) {
    mShouldVerifyFaces=faceVerifierEnabled;
  }
  // =============<<<<<<<<<<<<<<<<< check here
  @Override
  public void onFaceVerified(float result) {
    if (!mShouldVerifyFaces) {
      return;
    }

    Log.i("Debug","RNCameraView onFaceVerified "+String.valueOf(result));
//    RNCameraViewHelper.emitFaceVerifiedEvent(this, result );
    RNCameraViewHelper.emitFaceVerifiedEvent(this, result );
  }

  @Override
  public void onFaceVerificationError( ) {
    if (!mShouldDetectFaces) {
      return;
    }
    faceVerifierTaskLock = false;
    RNCameraViewHelper.emitFaceVerificationErrorEvent(this);

  }

  @Override
  public void onFaceVerificationTaskCompleted() {
//    unlock the thread.
    faceVerifierTaskLock = false;
  }

  public void setUserImageName(String userImageName) {
    this.userImageName = userImageName;
  }

  public String getUserImageName() {
    return userImageName;
  }

  public void setUserImageDir(String userImageDir) {
    this.userImageDir = userImageDir;
  }
  public String getUserImageDir() {
    return this.userImageDir ;
  }
}
