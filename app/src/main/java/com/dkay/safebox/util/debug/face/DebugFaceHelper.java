package com.dkay.safebox.util.debug.face;

import android.graphics.Rect;
import android.hardware.Camera;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceFeature;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.ImageQualitySimilar;
import com.arcsoft.face.LivenessInfo;
import com.arcsoft.face.MaskInfo;
import com.arcsoft.face.enums.ExtractType;
import com.dkay.safebox.facedb.entity.FaceEntity;
import com.dkay.safebox.faceserver.FaceServer;
import com.dkay.safebox.model.CompareResult;
import com.dkay.safebox.util.FaceRectTransformer;
import com.dkay.safebox.util.debug.DebugInfoCallback;
import com.dkay.safebox.util.debug.DebugInfoDumper;
import com.dkay.safebox.util.debug.DumpConfig;
import com.dkay.safebox.util.debug.model.DebugRecognizeInfo;
import com.dkay.safebox.util.face.IDualCameraFaceInfoTransformer;
import com.dkay.safebox.util.face.RecognizeCallback;
import com.dkay.safebox.util.face.constants.LivenessType;
import com.dkay.safebox.util.face.constants.RequestFeatureStatus;
import com.dkay.safebox.util.face.constants.RequestLivenessStatus;
import com.dkay.safebox.util.face.facefilter.FaceMoveFilter;
import com.dkay.safebox.util.face.facefilter.FaceRecognizeAreaFilter;
import com.dkay.safebox.util.face.facefilter.FaceRecognizeFilter;
import com.dkay.safebox.util.face.facefilter.FaceSizeFilter;
import com.dkay.safebox.util.face.model.FacePreviewInfo;
import com.dkay.safebox.util.face.model.RecognizeConfiguration;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;


/**
 * ?????????????????????(for debug)????????????
 */
public class DebugFaceHelper implements DebugFaceListener {
    private static final String TAG = "FaceHelper";

    /**
     * ?????????????????????
     */
    private RecognizeCallback recognizeCallback;


    /**
     * ????????????????????????????????????
     */
    private ConcurrentHashMap<Integer, DebugRecognizeInfo> recognizeInfoMap = new ConcurrentHashMap<>();

    private CompositeDisposable getFeatureDelayedDisposables = new CompositeDisposable();
    private CompositeDisposable delayFaceTaskCompositeDisposable = new CompositeDisposable();
    /**
     * ?????????????????????IR????????????
     */
    private IDualCameraFaceInfoTransformer dualCameraFaceInfoTransformer;

    /**
     * ???????????????????????????
     */
    private static final int ERROR_BUSY = -1;
    /**
     * ????????????????????????
     */
    private static final int ERROR_FR_ENGINE_IS_NULL = -2;
    /**
     * ????????????????????????
     */
    private static final int ERROR_FL_ENGINE_IS_NULL = -3;
    /**
     * ??????????????????
     */
    private FaceEngine ftEngine;
    /**
     * ??????????????????
     */
    private FaceEngine frEngine;
    /**
     * ??????????????????
     */
    private FaceEngine flEngine;

    private Camera.Size previewSize;

    private List<FaceInfo> faceInfoList = new CopyOnWriteArrayList<>();
    private List<MaskInfo> maskInfoList = new CopyOnWriteArrayList<>();
    /**
     * ?????????????????????
     */
    private ExecutorService frExecutor;
    /**
     * ?????????????????????
     */
    private ExecutorService flExecutor;
    /**
     * ????????????????????????
     */
    private LinkedBlockingQueue<Runnable> frThreadQueue;
    /**
     * ????????????????????????
     */
    private LinkedBlockingQueue<Runnable> flThreadQueue;

    private FaceRectTransformer rgbFaceRectTransformer;
    private FaceRectTransformer irFaceRectTransformer;
    /**
     * ?????????????????????????????????View???????????????????????????????????????
     */
    private Rect recognizeArea = new Rect(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

    private List<FaceRecognizeFilter> faceRecognizeFilterList = new ArrayList<>();
    /**
     * ????????????????????????????????????App????????????????????????
     */
    private int trackedFaceCount = 0;
    /**
     * ??????????????????????????????faceId
     */
    private int currentMaxFaceId = 0;

    /**
     * ??????????????????
     */
    private RecognizeConfiguration recognizeConfiguration;

    private List<Integer> currentTrackIdList = new ArrayList<>();
    private List<FacePreviewInfo> facePreviewInfoList = new ArrayList<>();

    private DebugInfoCallback errorCallback;
    private DumpConfig errorDumpConfig = new DumpConfig();
    private boolean needUpdateFaceData;
    private Disposable timerDisposable;

    public void setErrorDumpConfig(DumpConfig errorDumpConfig) {
        this.errorDumpConfig = errorDumpConfig;
    }

    public void setErrorCallback(DebugInfoCallback errorCallback) {
        this.errorCallback = errorCallback;
    }

    private DebugFaceHelper(Builder builder) {
        needUpdateFaceData = builder.needUpdateFaceData;
        ftEngine = builder.ftEngine;
        trackedFaceCount = builder.trackedFaceCount;
        previewSize = builder.previewSize;
        frEngine = builder.frEngine;
        flEngine = builder.flEngine;
        recognizeCallback = builder.recognizeCallback;
        recognizeConfiguration = builder.recognizeConfiguration;
        dualCameraFaceInfoTransformer = builder.dualCameraFaceInfoTransformer;

        /*
         * fr ??????????????????
         */
        int frQueueSize = recognizeConfiguration.getMaxDetectFaces();
        if (builder.frQueueSize > 0) {
            frQueueSize = builder.frQueueSize;
        } else {
            Log.e(TAG, "frThread num must > 0, now using default value:" + frQueueSize);
        }
        frThreadQueue = new LinkedBlockingQueue<>(frQueueSize);
        frExecutor = new ThreadPoolExecutor(1, frQueueSize, 0, TimeUnit.MILLISECONDS, frThreadQueue, r -> {
            Thread t = new Thread(r);
            t.setName("frThread-" + t.getId());
            return t;
        });

        /*
         * fl ??????????????????
         */
        int flQueueSize = recognizeConfiguration.getMaxDetectFaces();
        if (builder.flQueueSize > 0) {
            flQueueSize = builder.flQueueSize;
        } else {
            Log.e(TAG, "flThread num must > 0, now using default value:" + flQueueSize);
        }
        flThreadQueue = new LinkedBlockingQueue<>(flQueueSize);
        flExecutor = new ThreadPoolExecutor(1, flQueueSize, 0, TimeUnit.MILLISECONDS, flThreadQueue, r -> {
            Thread t = new Thread(r);
            t.setName("flThread-" + t.getId());
            return t;
        });
        if (previewSize == null) {
            throw new RuntimeException("previewSize must be specified!");
        }
        if (recognizeConfiguration.isEnableFaceSizeLimit()) {
            faceRecognizeFilterList.add(new FaceSizeFilter(recognizeConfiguration.getFaceSizeLimit(), recognizeConfiguration.getFaceSizeLimit()));
        }
        if (recognizeConfiguration.isEnableFaceMoveLimit()) {
            faceRecognizeFilterList.add(new FaceMoveFilter(recognizeConfiguration.getFaceMoveLimit()));
        }
        if (recognizeConfiguration.isEnableFaceAreaLimit()) {
            faceRecognizeFilterList.add(new FaceRecognizeAreaFilter(recognizeArea));
        }
    }

    /**
     * ??????????????????????????????
     *
     * @param nv21            ????????????
     * @param facePreviewInfo ????????????
     * @param width           ????????????
     * @param height          ????????????
     * @param format          ????????????
     */
    public void requestFaceFeature(byte[] nv21, FacePreviewInfo facePreviewInfo, int width, int height, int format) {
        if (frEngine != null && frThreadQueue.remainingCapacity() > 0) {
            frExecutor.execute(new FaceRecognizeRunnable(nv21, facePreviewInfo, width, height, format));
        } else {
            onFaceFeatureInfoGet(nv21, null, facePreviewInfo.getTrackId(), facePreviewInfo.getFaceInfoRgb(), -1, -1, ERROR_BUSY);
        }
    }

    /**
     * ??????????????????????????????????????????????????????????????????????????????
     *
     * @param nv21         NV21?????????????????????
     * @param faceInfo     ????????????
     * @param width        ????????????
     * @param height       ????????????
     * @param format       ????????????
     * @param livenessType ??????????????????
     * @param waitLock
     */
    public void requestFaceLiveness(byte[] nv21, FacePreviewInfo faceInfo, int width, int height, int format, LivenessType livenessType, Object waitLock) {
        if (flEngine != null && flThreadQueue.remainingCapacity() > 0) {
            flExecutor.execute(new FaceLivenessDetectRunnable(nv21, faceInfo, width, height, format, livenessType, waitLock));
        } else {
            onFaceLivenessInfoGet(nv21, null, faceInfo.getTrackId(), faceInfo.getFaceInfoRgb(), 0, ERROR_BUSY, livenessType);
        }
    }

    /**
     * ????????????
     */
    public void release() {
        if (getFeatureDelayedDisposables != null) {
            getFeatureDelayedDisposables.clear();
        }
        if (!frExecutor.isShutdown()) {
            frExecutor.shutdownNow();
            frThreadQueue.clear();
        }
        if (!flExecutor.isShutdown()) {
            flExecutor.shutdownNow();
            flThreadQueue.clear();
        }
        if (faceInfoList != null) {
            faceInfoList.clear();
        }
        if (frThreadQueue != null) {
            frThreadQueue.clear();
            frThreadQueue = null;
        }
        if (flThreadQueue != null) {
            flThreadQueue.clear();
            flThreadQueue = null;
        }
        faceInfoList = null;
    }

    /**
     * ???????????????
     *
     * @param rgbNv21     ??????????????????????????????NV21??????
     * @param irNv21      ???????????????????????????NV21??????
     * @param doRecognize ??????????????????
     * @return ????????????????????????????????????????????????trackId???trackId??????????????????faceId????????????????????????????????????
     */
    public List<FacePreviewInfo> onPreviewFrame(@NonNull byte[] rgbNv21, @Nullable byte[] irNv21, boolean doRecognize) {
        if (ftEngine != null) {
            faceInfoList.clear();
            maskInfoList.clear();
            facePreviewInfoList.clear();
            long ftStartTime = System.currentTimeMillis();
            int code = ftEngine.detectFaces(rgbNv21, previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21, faceInfoList);
            long ftTime = System.currentTimeMillis() - ftStartTime;
            if (code != ErrorInfo.MOK) {
                onFail(new Exception("detectFaces failed,code is " + code));
                return facePreviewInfoList;
            }
            if (errorCallback != null && errorDumpConfig.isDumpFaceTrackError() && faceInfoList.isEmpty()) {
                errorCallback.onNormalErrorOccurred(DebugInfoDumper.ERROR_TYPE_FACE_TRACK, rgbNv21,
                        DebugInfoDumper.getFaceTrackErrorFileName(previewSize.width, previewSize.height, code));
            }

            if (recognizeConfiguration.isKeepMaxFace()) {
                keepMaxFace(faceInfoList);
            }
            refreshTrackId(faceInfoList);
            if (faceInfoList.isEmpty()) {
                return facePreviewInfoList;
            }
            long maskStartTime = System.currentTimeMillis();
            code = ftEngine.process(rgbNv21, previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21, faceInfoList,
                    FaceEngine.ASF_MASK_DETECT);
            long maskTime = System.currentTimeMillis() - maskStartTime;
            if (code == ErrorInfo.MOK) {
                code = ftEngine.getMask(maskInfoList);
                if (code != ErrorInfo.MOK) {
                    onFail(new Exception("getMask failed,code is " + code));
                    return facePreviewInfoList;
                }
            } else {
                onFail(new Exception("process mask failed,code is " + code));
                return facePreviewInfoList;
            }
            for (int i = 0; i < faceInfoList.size(); i++) {
                FacePreviewInfo facePreviewInfo = new FacePreviewInfo(faceInfoList.get(i), currentTrackIdList.get(i));
                if (!maskInfoList.isEmpty()) {
                    MaskInfo maskInfo = maskInfoList.get(i);
                    facePreviewInfo.setMask(maskInfo.getMask());
                }
                if (rgbFaceRectTransformer != null && recognizeArea != null) {
                    Rect rect = rgbFaceRectTransformer.adjustRect(faceInfoList.get(i).getRect());
                    facePreviewInfo.setRgbTransformedRect(rect);
                }
                if (irFaceRectTransformer != null) {
                    FaceInfo faceInfo = faceInfoList.get(i);
                    if (dualCameraFaceInfoTransformer != null) {
                        faceInfo = dualCameraFaceInfoTransformer.transformFaceInfo(faceInfo);
                    }
                    facePreviewInfo.setFaceInfoIr(faceInfo);
                    facePreviewInfo.setIrTransformedRect(irFaceRectTransformer.adjustRect(faceInfo.getRect()));
                }
                facePreviewInfoList.add(facePreviewInfo);
            }
            clearLeftFace(facePreviewInfoList);
            if (doRecognize) {
                doRecognize(rgbNv21, irNv21, facePreviewInfoList, ftTime, maskTime);
            }
        } else {
            facePreviewInfoList.clear();
        }
        return facePreviewInfoList;
    }

    public void setRgbFaceRectTransformer(FaceRectTransformer rgbFaceRectTransformer) {
        this.rgbFaceRectTransformer = rgbFaceRectTransformer;
    }

    public void setIrFaceRectTransformer(FaceRectTransformer irFaceRectTransformer) {
        this.irFaceRectTransformer = irFaceRectTransformer;
    }

    /**
     * ???????????????????????????
     *
     * @param facePreviewInfoList ?????????trackId??????
     */
    private void clearLeftFace(List<FacePreviewInfo> facePreviewInfoList) {
        if (facePreviewInfoList == null || facePreviewInfoList.size() == 0) {
            if (getFeatureDelayedDisposables != null) {
                getFeatureDelayedDisposables.clear();
            }
        }
        Enumeration<Integer> keys = recognizeInfoMap.keys();
        while (keys.hasMoreElements()) {
            int key = keys.nextElement();
            boolean contained = false;
            for (FacePreviewInfo facePreviewInfo : facePreviewInfoList) {
                if (facePreviewInfo.getTrackId() == key) {
                    contained = true;
                    break;
                }
            }
            if (!contained) {
                DebugRecognizeInfo recognizeInfo = recognizeInfoMap.remove(key);
                if (recognizeInfo != null) {
                    recognizeCallback.onNoticeChanged("");
                    // ???????????????????????????????????????????????????????????????????????????
                    synchronized (recognizeInfo.getWaitLock()) {
                        recognizeInfo.getWaitLock().notifyAll();
                    }
                }
            }
        }
    }

    private void doRecognize(byte[] rgbNv21, byte[] irNv21, List<FacePreviewInfo> facePreviewInfoList, long ftTime, long maskTime) {
        if (facePreviewInfoList != null && !facePreviewInfoList.isEmpty() && previewSize != null) {
            for (FaceRecognizeFilter faceRecognizeFilter : faceRecognizeFilterList) {
                faceRecognizeFilter.filter(facePreviewInfoList);
            }
            for (int i = 0; i < facePreviewInfoList.size(); i++) {
                FacePreviewInfo facePreviewInfo = facePreviewInfoList.get(i);
                if (!facePreviewInfo.isQualityPass()) {
                    continue;
                }
                //??????mask??????MaskInfo.UNKNOWN?????????
                if (facePreviewInfo.getMask() == MaskInfo.UNKNOWN) {
                    continue;
                }
                DebugRecognizeInfo recognizeInfo = getRecognizeInfo(recognizeInfoMap, facePreviewInfo.getTrackId());
                recognizeInfo.setFtCost(ftTime);
                recognizeInfo.setMaskCost(maskTime);
                int status = recognizeInfo.getRecognizeStatus();
                /*
                 * ????????????????????????????????????????????????????????????????????????????????????????????????ANALYZING???????????????????????????ALIVE???NOT_ALIVE??????????????????????????????
                 */
                if (recognizeConfiguration.isEnableLiveness() && status != RequestFeatureStatus.SUCCEED) {
                    int liveness = recognizeInfo.getLiveness();
                    if (liveness != LivenessInfo.ALIVE && liveness != LivenessInfo.NOT_ALIVE && liveness != RequestLivenessStatus.ANALYZING
                            || status == RequestFeatureStatus.FAILED) {
                        changeLiveness(facePreviewInfo.getTrackId(), RequestLivenessStatus.ANALYZING);
                        requestFaceLiveness(
                                irNv21 == null ? rgbNv21 : irNv21,
                                facePreviewInfo,
                                previewSize.width,
                                previewSize.height,
                                FaceEngine.CP_PAF_NV21,
                                irNv21 == null ? LivenessType.RGB : LivenessType.IR,
                                recognizeInfo.getWaitLock()
                        );
                    }
                }
                /*
                 * ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                 * ??????????????????????????????????????????{@link DebugFaceListener#onFaceFeatureInfoGet(FaceFeature, Integer, Integer)}?????????
                 */
                if (status == RequestFeatureStatus.TO_RETRY) {
                    changeRecognizeStatus(facePreviewInfo.getTrackId(), RequestFeatureStatus.SEARCHING);
                    recognizeInfo.setEnterTime(System.currentTimeMillis());
                    requestFaceFeature(
                            rgbNv21, facePreviewInfo,
                            previewSize.width,
                            previewSize.height,
                            FaceEngine.CP_PAF_NV21
                    );
                }
            }
        }
    }

    @Override
    public void onFail(Exception e) {
        Log.e(TAG, "onFail:" + e.getMessage());
    }

    /**
     * ????????????????????????????????????????????????????????????
     *
     * @param recognizeInfoMap ?????????????????????map
     * @param trackId          ??????????????????
     * @return ????????????
     */
    private DebugRecognizeInfo getRecognizeInfo(Map<Integer, DebugRecognizeInfo> recognizeInfoMap, int trackId) {
        DebugRecognizeInfo recognizeInfo = recognizeInfoMap.get(trackId);
        if (recognizeInfo == null) {
            recognizeInfo = new DebugRecognizeInfo();
            recognizeInfoMap.put(trackId, recognizeInfo);
        }
        return recognizeInfo;
    }

    @Override
    public void onFaceFeatureInfoGet(byte[] nv21, @Nullable FaceFeature faceFeature, Integer trackId, FaceInfo faceInfo,
                                     long frCost, long fqCost, Integer errorCode) {
        //FR??????
        DebugRecognizeInfo recognizeInfo = getRecognizeInfo(recognizeInfoMap, trackId);
        if (faceFeature != null) {
            //??????????????????????????????????????????
            if (!recognizeConfiguration.isEnableLiveness()) {
                searchFace(nv21, faceFeature, trackId, faceInfo);
            }
            //?????????????????????????????????
            else if (recognizeInfo.getLiveness() == LivenessInfo.ALIVE) {
                searchFace(nv21, faceFeature, trackId, faceInfo);
            }
            //???????????????????????????????????????????????????
            else {
                synchronized (recognizeInfo.getWaitLock()) {
                    try {
                        recognizeInfo.getWaitLock().wait();
                        if (recognizeInfoMap.containsKey(trackId)) {
                            onFaceFeatureInfoGet(nv21, faceFeature, trackId, faceInfo, frCost, fqCost, errorCode);
                        }
                    } catch (InterruptedException e) {
                        Log.e(TAG, "onFaceFeatureInfoGet: ????????????????????????????????????????????????????????????????????????????????????");
                        e.printStackTrace();
                    }
                }
            }
        }
        //????????????????????????????????????????????????UI????????????name?????????"ExtractCode:${errorCode}"??????????????????
        else {
            // ERROR_CALLBACK: ????????????????????????
            if (errorCallback != null && errorDumpConfig.isDumpExtractError() && errorCode != null) {
                errorCallback.onNormalErrorOccurred(DebugInfoDumper.ERROR_TYPE_FEATURE_EXTRACT, nv21,
                        DebugInfoDumper.getExtractFailedFileName(previewSize.width, previewSize.height, trackId, errorCode, faceInfo, frCost, fqCost));
            }

            if (recognizeInfo.increaseAndGetExtractErrorRetryCount() > recognizeConfiguration.getExtractRetryCount()) {
                // ??????????????????????????????????????????????????????????????????????????????
                savePerformanceInfo(trackId, 0, RequestFeatureStatus.FAILED);
                recognizeInfo.setExtractErrorRetryCount(0);
                retryRecognizeDelayed(trackId);
            } else {
                savePerformanceInfo(trackId, 0, RequestFeatureStatus.TO_RETRY);
                changeRecognizeStatus(trackId, RequestFeatureStatus.TO_RETRY);
            }
        }
    }

    /**
     * ?????? {@link RecognizeConfiguration#getRecognizeFailedRetryInterval()}??????????????????????????????
     *
     * @param trackId ??????ID
     */
    private void retryRecognizeDelayed(final Integer trackId) {
        changeRecognizeStatus(trackId, RequestFeatureStatus.FAILED);
        Observable.timer(recognizeConfiguration.getRecognizeFailedRetryInterval(), TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Long>() {
                    Disposable disposable;

                    @Override
                    public void onSubscribe(Disposable d) {
                        disposable = d;
                        delayFaceTaskCompositeDisposable.add(disposable);
                    }

                    @Override
                    public void onNext(Long aLong) {

                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }

                    @Override
                    public void onComplete() {
                        // ????????????????????????????????????FAILED????????????????????????????????????????????????
                        changeRecognizeStatus(trackId, RequestFeatureStatus.TO_RETRY);
                        getRecognizeInfo(recognizeInfoMap, trackId).setExtractErrorRetryCount(0);
                        getRecognizeInfo(recognizeInfoMap, trackId).setLivenessErrorRetryCount(0);
                        delayFaceTaskCompositeDisposable.remove(disposable);
                    }
                });
    }

    @Override
    public void onFaceLivenessInfoGet(byte[] nv21, @Nullable LivenessInfo livenessInfo, Integer trackId, FaceInfo faceInfo, long cost, Integer errorCode, LivenessType livenessType) {

        // ERROR_CALLBACK: ????????????????????????
        if (errorCallback != null && errorDumpConfig.isDumpLivenessDetectResult() && errorCode != null) {
            int liveness = livenessInfo == null ? LivenessInfo.UNKNOWN : livenessInfo.getLiveness();
            errorCallback.onNormalErrorOccurred(DebugInfoDumper.ERROR_TYPE_FACE_LIVENESS, nv21,
                    DebugInfoDumper.getFaceLivenessFileName(previewSize.width, previewSize.height, trackId, errorCode, faceInfo, livenessType.ordinal(), liveness, cost));
        }

        DebugRecognizeInfo recognizeInfo = getRecognizeInfo(recognizeInfoMap, trackId);
        if (livenessInfo != null) {
            int liveness = livenessInfo.getLiveness();
            changeLiveness(trackId, liveness);
            // ??????????????????
            if (liveness == LivenessInfo.NOT_ALIVE) {
                noticeCurrentStatus("?????????????????????");
                // ?????? FAIL_RETRY_INTERVAL ??????????????????????????????UNKNOWN????????????????????????????????????????????????
                retryLivenessDetectDelayed(trackId);
            }
            if (liveness == LivenessInfo.ALIVE) {
                Log.i(TAG, "fl success");
            }
            if (liveness != LivenessInfo.ALIVE && liveness != LivenessInfo.NOT_ALIVE) {
                onFail(new Exception("fl failed liveness is " + liveness));
            }
        } else {
            // ?????????????????????????????????????????????????????????0????????????????????????????????????????????????????????????????????????????????????
            if (recognizeInfo.increaseAndGetLivenessErrorRetryCount() > recognizeConfiguration.getLivenessRetryCount()) {
                recognizeInfo.setLivenessErrorRetryCount(0);
                retryLivenessDetectDelayed(trackId);
            } else {
                changeLiveness(trackId, LivenessInfo.UNKNOWN);
            }
        }
    }

    /**
     * ?????? {@link RecognizeConfiguration#getLivenessFailedRetryInterval()}??????????????????????????????
     *
     * @param trackId ??????ID
     */
    private void retryLivenessDetectDelayed(final Integer trackId) {
        Observable.timer(recognizeConfiguration.getLivenessFailedRetryInterval(), TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Long>() {
                    Disposable disposable;

                    @Override
                    public void onSubscribe(Disposable d) {
                        disposable = d;
                        delayFaceTaskCompositeDisposable.add(disposable);
                    }

                    @Override
                    public void onNext(Long aLong) {

                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }

                    @Override
                    public void onComplete() {
                        // ????????????????????????UNKNOWN????????????????????????????????????????????????
                        changeLiveness(trackId, LivenessInfo.UNKNOWN);
                        delayFaceTaskCompositeDisposable.remove(disposable);
                    }
                });
    }

    private void noticeCurrentStatus(String notice) {
        if (recognizeCallback != null) {
            recognizeCallback.onNoticeChanged(notice);
        }
        if (timerDisposable != null && !timerDisposable.isDisposed()) {
            timerDisposable.dispose();
        }
        timerDisposable = Observable.timer(1500, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(aLong -> {
                    if (recognizeCallback != null) {
                        recognizeCallback.onNoticeChanged("");
                    }
                });
    }

    private void searchFace(final byte[] nv21, final FaceFeature faceFeature, final Integer trackId, FaceInfo faceInfo) {
        CompareResult compareResult = FaceServer.getInstance().searchFaceFeature(faceFeature, frEngine);
        if (compareResult == null || compareResult.getFaceEntity() == null) {
            savePerformanceInfo(trackId, 0, RequestFeatureStatus.FAILED);
            getRecognizeInfo(recognizeInfoMap, trackId).setExtractErrorRetryCount(0);
            retryRecognizeDelayed(trackId);
            return;
        }
        compareResult.setTrackId(trackId);
        boolean pass = compareResult.getSimilar() > recognizeConfiguration.getSimilarThreshold();
        recognizeCallback.onRecognized(compareResult, getRecognizeInfo(recognizeInfoMap, trackId).getLiveness(), pass);
        if (pass) {
            savePerformanceInfo(trackId, compareResult.getCost(), RequestFeatureStatus.SUCCEED);
            setName(trackId, "????????????");
            noticeCurrentStatus("????????????");
            changeRecognizeStatus(trackId, RequestFeatureStatus.SUCCEED);
        } else {
            // ERROR_CALLBACK: ?????????????????????
            if (errorCallback != null && errorDumpConfig.isDumpCompareFailedError()) {
                FaceEntity faceEntity = compareResult.getFaceEntity();
                String recognizeFeatureFileName = trackId + "-" + System.currentTimeMillis();
                String registerFeatureFileName = faceEntity.getFaceId() + "-" + faceEntity.getUserName();
                String fileName = DebugInfoDumper.getCompareFailedFileName(previewSize.width, previewSize.height, trackId,
                        compareResult.getCompareCode(), faceInfo, compareResult.getSimilar(),
                        recognizeFeatureFileName, registerFeatureFileName);
                errorCallback.onCompareFailed(nv21, fileName, recognizeFeatureFileName, registerFeatureFileName, faceFeature.getFeatureData(), faceEntity);
            }
            noticeCurrentStatus("???????????????");

            savePerformanceInfo(trackId, compareResult.getCost(), RequestFeatureStatus.FAILED);
            getRecognizeInfo(recognizeInfoMap, trackId).setExtractErrorRetryCount(0);
            retryRecognizeDelayed(trackId);
        }
    }

    /**
     * ????????????????????????
     */
    public class FaceRecognizeRunnable implements Runnable {
        private FaceInfo faceInfo;
        private int width;
        private int height;
        private int format;
        private Integer trackId;
        private byte[] nv21Data;
        private int isMask;

        private FaceRecognizeRunnable(byte[] nv21Data, FacePreviewInfo facePreviewInfo, int width, int height, int format) {
            if (nv21Data == null) {
                return;
            }
            this.nv21Data = nv21Data;
            this.faceInfo = new FaceInfo(facePreviewInfo.getFaceInfoRgb());
            this.width = width;
            this.height = height;
            this.format = format;
            this.trackId = facePreviewInfo.getTrackId();
            this.isMask = facePreviewInfo.getMask();
        }

        @Override
        public void run() {
            if (nv21Data != null) {
                if (frEngine != null) {
                    if (recognizeConfiguration.isEnableImageQuality()) {
                        /*
                         * ????????????????????????
                         */
                        ImageQualitySimilar qualitySimilar = new ImageQualitySimilar();
                        long iqStartTime = System.currentTimeMillis();
                        int iqCode;
                        synchronized (frEngine) {
                            iqCode = frEngine.imageQualityDetect(nv21Data, width, height, format, faceInfo, isMask, qualitySimilar);
                        }
                        long fqCost = System.currentTimeMillis() - iqStartTime;
                        DebugRecognizeInfo recognizeInfo = getRecognizeInfo(recognizeInfoMap, trackId);
                        recognizeInfo.setFrQualityCost(fqCost);
                        if (iqCode == ErrorInfo.MOK) {
                            float quality = qualitySimilar.getScore();
                            float destQuality = isMask == MaskInfo.WORN ? recognizeConfiguration.getImageQualityMaskRecognizeThreshold() :
                                    recognizeConfiguration.getImageQualityNoMaskRecognizeThreshold();
                            if (quality >= destQuality) {
                                extractFace(fqCost);
                            } else {
                                onFaceFail(iqCode, "fr imageQuality score invalid", -1, fqCost);
                            }
                        } else {
                            onFaceFail(iqCode, "fr imageQuality failed errorCode is " + iqCode, -1, fqCost);
                        }
                    } else {
                        extractFace(-1);
                    }
                } else {
                    onFaceFail(ERROR_FR_ENGINE_IS_NULL, "fr failed errorCode is null", -1, -1);
                }
            }
            nv21Data = null;
        }

        private void extractFace(long fqCost) {
            FaceFeature faceFeature = new FaceFeature();
            long frStartTime = System.currentTimeMillis();
            int frCode;
            synchronized (frEngine) {
                /*
                 * ??????????????????????????????????????????ExtractType?????????ExtractType.RECOGNIZE???????????????mask????????????????????????????????????isMask
                 */
                frCode = frEngine.extractFaceFeature(nv21Data, width, height, format, faceInfo, ExtractType.RECOGNIZE, isMask, faceFeature);
            }
            long frTime = System.currentTimeMillis() - frStartTime;
            DebugRecognizeInfo recognizeInfo = getRecognizeInfo(recognizeInfoMap, trackId);
            recognizeInfo.setExtractCost(frTime);
            if (frCode == ErrorInfo.MOK) {
                recognizeInfo.increaseAndGetExtractErrorRetryCount();
                onFaceFeatureInfoGet(nv21Data, faceFeature, trackId, faceInfo, frTime, fqCost, frCode);
            } else {
                onFaceFail(frCode, "fr failed errorCode is " + frCode, frTime, fqCost);
            }
        }

        private void onFaceFail(int frCode, String errorMsg, long frCost, long fqCost) {
            onFail(new Exception(errorMsg));
            onFaceFeatureInfoGet(nv21Data, null, trackId, faceInfo, frCost, fqCost, frCode);
        }
    }

    /**
     * ?????????????????????
     */
    public class FaceLivenessDetectRunnable implements Runnable {
        private FaceInfo faceInfo;
        private int width;
        private int height;
        private int format;
        private Integer trackId;
        private byte[] nv21Data;
        private LivenessType livenessType;
        private Object waitLock;

        private FaceLivenessDetectRunnable(byte[] nv21Data, FacePreviewInfo faceInfo, int width, int height, int format, LivenessType livenessType, Object waitLock) {
            if (nv21Data == null) {
                return;
            }
            this.nv21Data = nv21Data;
            this.faceInfo = new FaceInfo(faceInfo.getFaceInfoRgb());
            this.width = width;
            this.height = height;
            this.format = format;
            this.trackId = faceInfo.getTrackId();
            this.livenessType = livenessType;
            this.waitLock = waitLock;
        }

        @Override
        public void run() {
            if (nv21Data != null) {
                if (flEngine != null) {
                    processLiveness();
                } else {
                    onProcessFail(0, ERROR_FL_ENGINE_IS_NULL, "fl failed ,frEngine is null");
                }
            }
            nv21Data = null;
        }

        private void processLiveness() {
            List<LivenessInfo> livenessInfoList = new ArrayList<>();
            int flCode = -1;
            long flCost = 0;
            synchronized (flEngine) {
                if (livenessType == LivenessType.RGB) {
                    long start = System.currentTimeMillis();
                    flCode = flEngine.process(nv21Data, width, height, format, Arrays.asList(faceInfo), FaceEngine.ASF_LIVENESS);
                    flCost = System.currentTimeMillis() - start;
                    DebugRecognizeInfo recognizeInfo = getRecognizeInfo(recognizeInfoMap, trackId);
                    recognizeInfo.setLivenessCost(flCost);
                } else {
                    if (dualCameraFaceInfoTransformer != null) {
                        faceInfo = dualCameraFaceInfoTransformer.transformFaceInfo(faceInfo);
                    }
                    List<FaceInfo> faceInfoList = new ArrayList<>();
                    int fdCode = flEngine.detectFaces(nv21Data, width, height, format, faceInfoList);
                    boolean isFaceExists = isFaceExists(faceInfoList, faceInfo);
                    if (fdCode == ErrorInfo.MOK && isFaceExists) {
                        if (needUpdateFaceData) {
                            /*
                             * ???IR?????????????????????????????????IR?????????????????????upadateFaceData???????????????????????????FaceInfo?????????????????????????????????
                             */
                            flCode = flEngine.updateFaceData(nv21Data, previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21,
                                    new ArrayList<>(Collections.singletonList(faceInfo)));
                            if (flCode == ErrorInfo.MOK) {
                                long start = System.currentTimeMillis();
                                flCode = flEngine.processIr(nv21Data, width, height, format, Arrays.asList(faceInfo), FaceEngine.ASF_IR_LIVENESS);
                                flCost = System.currentTimeMillis() - start;
                                DebugRecognizeInfo recognizeInfo = getRecognizeInfo(recognizeInfoMap, trackId);
                                recognizeInfo.setLivenessCost(flCost);
                            }
                        } else {
                            long start = System.currentTimeMillis();
                            flCode = flEngine.processIr(nv21Data, width, height, format, Arrays.asList(faceInfo), FaceEngine.ASF_IR_LIVENESS);
                            flCost = System.currentTimeMillis() - start;
                            DebugRecognizeInfo recognizeInfo = getRecognizeInfo(recognizeInfoMap, trackId);
                            recognizeInfo.setLivenessCost(flCost);
                        }
                    }
                }
            }
            if (flCode == ErrorInfo.MOK) {
                if (livenessType == LivenessType.RGB) {
                    flCode = flEngine.getLiveness(livenessInfoList);
                } else {
                    flCode = flEngine.getIrLiveness(livenessInfoList);
                }
            }

            if (flCode == ErrorInfo.MOK && !livenessInfoList.isEmpty()) {
                getRecognizeInfo(recognizeInfoMap, trackId).increaseAndGetLivenessErrorRetryCount();
                onFaceLivenessInfoGet(nv21Data, livenessInfoList.get(0), trackId, faceInfo, flCost, flCode, livenessType);
                if (livenessInfoList.get(0).getLiveness() == LivenessInfo.ALIVE) {
                    synchronized (waitLock) {
                        waitLock.notifyAll();
                    }
                }
            } else {
                onProcessFail(flCost, flCode, "fl failed flCode is " + flCode);
            }
        }

        private void onProcessFail(long cost, int code, String msg) {
            onFail(new Exception(msg));
            onFaceLivenessInfoGet(nv21Data, null, trackId, faceInfo, cost, code, livenessType);
        }
    }

    private void savePerformanceInfo(int trackId, long compareCost, int status) {
        DebugRecognizeInfo recognizeInfo = getRecognizeInfo(recognizeInfoMap, trackId);
        recognizeInfo.setCompareCost(compareCost);
        recognizeInfo.setTotalCost(System.currentTimeMillis() - recognizeInfo.getEnterTime() + recognizeInfo.getFtCost() + recognizeInfo.getMaskCost());
        recognizeInfo.setTrackId(trackId);
        recognizeInfo.setStatus(status);
        if (errorCallback != null && errorDumpConfig.isDumpPerformanceInfo()) {
            String performanceInfo = recognizeInfo.performanceDaraToJsonString();
            recognizeInfo.resetCost();
            Log.i(TAG, "performanceInfo:" + performanceInfo);
            errorCallback.onSavePerformanceInfo(performanceInfo);
        }
    }

    /**
     * ???????????????????????????????????????faceInfo?????????????????????faceInfo??????
     *
     * @param faceInfoList ??????????????????
     * @param faceInfo     ????????????
     * @return ??????????????????????????????????????????????????????????????????
     */
    private static boolean isFaceExists(List<FaceInfo> faceInfoList, FaceInfo faceInfo) {
        if (faceInfoList == null || faceInfoList.isEmpty() || faceInfo == null) {
            return false;
        }
        for (FaceInfo info : faceInfoList) {
            if (Rect.intersects(faceInfo.getRect(), info.getRect())) {
                return true;
            }
        }
        return false;
    }

    /**
     * ??????trackId
     *
     * @param ftFaceList ?????????????????????
     */
    private void refreshTrackId(List<FaceInfo> ftFaceList) {
        currentTrackIdList.clear();

        for (FaceInfo faceInfo : ftFaceList) {
            currentTrackIdList.add(faceInfo.getFaceId() + trackedFaceCount);
        }
        if (ftFaceList.size() > 0) {
            currentMaxFaceId = ftFaceList.get(ftFaceList.size() - 1).getFaceId();
        }
    }

    /**
     * ?????????????????????trackID,????????????????????????
     *
     * @return ??????trackId
     */
    public int getTrackedFaceCount() {
        // ????????????????????????0?????????????????????+1
        return trackedFaceCount + currentMaxFaceId + 1;
    }

    /**
     * ???????????????????????????
     *
     * @param trackId ?????????trackId
     * @param name    trackId???????????????
     */
    public void setName(int trackId, String name) {
        DebugRecognizeInfo recognizeInfo = recognizeInfoMap.get(trackId);
        if (recognizeInfo != null) {
            recognizeInfo.setName(name);
        }
    }


    /**
     * ???????????????????????????IR????????????
     *
     * @param transformer ????????????
     */
    public void setDualCameraFaceInfoTransformer(IDualCameraFaceInfoTransformer transformer) {
        this.dualCameraFaceInfoTransformer = transformer;
    }


    public String getName(int trackId) {
        DebugRecognizeInfo recognizeInfo = recognizeInfoMap.get(trackId);
        return recognizeInfo == null ? null : recognizeInfo.getName();
    }


    /**
     * ?????????????????????????????????View???
     *
     * @param recognizeArea ???????????????
     */
    public void setRecognizeArea(Rect recognizeArea) {
        if (recognizeArea != null) {
            this.recognizeArea.set(recognizeArea);
        }
    }

    @IntDef(value = {
            RequestFeatureStatus.FAILED,
            RequestFeatureStatus.SEARCHING,
            RequestFeatureStatus.SUCCEED,
            RequestFeatureStatus.TO_RETRY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestFaceFeatureStatus {
    }

    @IntDef(value = {
            LivenessInfo.ALIVE,
            LivenessInfo.NOT_ALIVE,
            LivenessInfo.UNKNOWN,
            LivenessInfo.FACE_NUM_MORE_THAN_ONE,
            LivenessInfo.FACE_TOO_SMALL,
            LivenessInfo.FACE_ANGLE_TOO_LARGE,
            LivenessInfo.FACE_BEYOND_BOUNDARY,
            RequestLivenessStatus.ANALYZING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestFaceLivenessStatus {
    }

    /**
     * ???????????????????????????
     *
     * @param trackId   ??????VIDEO????????????????????????????????????????????????
     * @param newStatus ???????????????????????????{@link RequestFeatureStatus}????????????
     */
    public void changeRecognizeStatus(int trackId, @RequestFaceFeatureStatus int newStatus) {
        getRecognizeInfo(recognizeInfoMap, trackId).setRecognizeStatus(newStatus);
    }

    /**
     * ??????????????????????????????????????????
     *
     * @param trackId     ??????VIDEO????????????????????????????????????????????????
     * @param newLiveness ????????????????????????????????????
     */
    public void changeLiveness(int trackId, @RequestFaceLivenessStatus int newLiveness) {
        getRecognizeInfo(recognizeInfoMap, trackId).setLiveness(newLiveness);
    }

    /**
     * ????????????????????????????????????
     *
     * @param trackId ??????VIDEO????????????????????????????????????????????????
     * @return ??????????????????????????????
     */
    public Integer getLiveness(int trackId) {
        return getRecognizeInfo(recognizeInfoMap, trackId).getLiveness();
    }

    /**
     * ????????????????????????
     *
     * @param trackId ??????VIDEO????????????????????????????????????????????????
     * @return ??????????????????
     */
    public Integer getRecognizeStatus(int trackId) {
        return getRecognizeInfo(recognizeInfoMap, trackId).getRecognizeStatus();
    }


    public static final class Builder {
        private FaceEngine ftEngine;
        private FaceEngine frEngine;
        private FaceEngine flEngine;
        private Camera.Size previewSize;
        private RecognizeConfiguration recognizeConfiguration;
        private RecognizeCallback recognizeCallback;
        private IDualCameraFaceInfoTransformer dualCameraFaceInfoTransformer;
        private int frQueueSize;
        private int flQueueSize;
        private int trackedFaceCount;
        private boolean needUpdateFaceData;

        public Builder() {
        }


        public Builder recognizeConfiguration(RecognizeConfiguration val) {
            recognizeConfiguration = val;
            return this;
        }

        public Builder dualCameraFaceInfoTransformer(IDualCameraFaceInfoTransformer val) {
            dualCameraFaceInfoTransformer = val;
            return this;
        }

        public Builder recognizeCallback(RecognizeCallback val) {
            recognizeCallback = val;
            return this;
        }

        public Builder ftEngine(FaceEngine val) {
            ftEngine = val;
            return this;
        }

        public Builder frEngine(FaceEngine val) {
            frEngine = val;
            return this;
        }

        public Builder flEngine(FaceEngine val) {
            flEngine = val;
            return this;
        }


        public Builder previewSize(Camera.Size val) {
            previewSize = val;
            return this;
        }


        public Builder frQueueSize(int val) {
            frQueueSize = val;
            return this;
        }

        public Builder flQueueSize(int val) {
            flQueueSize = val;
            return this;
        }

        public Builder trackedFaceCount(int val) {
            trackedFaceCount = val;
            return this;
        }

        public Builder needUpdateFaceData(boolean val) {
            needUpdateFaceData = val;
            return this;
        }

        public DebugFaceHelper build() {
            return new DebugFaceHelper(this);
        }
    }

    /**
     * ?????????????????????
     *
     * @param ftFaceList ?????????????????????????????????????????????
     */
    private static void keepMaxFace(List<FaceInfo> ftFaceList) {
        if (ftFaceList == null || ftFaceList.size() <= 1) {
            return;
        }
        FaceInfo maxFaceInfo = ftFaceList.get(0);
        for (FaceInfo faceInfo : ftFaceList) {
            if (faceInfo.getRect().width() > maxFaceInfo.getRect().width()) {
                maxFaceInfo = faceInfo;
            }
        }
        ftFaceList.clear();
        ftFaceList.add(maxFaceInfo);
    }

}
