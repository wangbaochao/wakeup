package com.lskj.wakeup.test;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.iflytek.cloud.ErrorCode;
import com.iflytek.cloud.GrammarListener;
import com.iflytek.cloud.RecognizerResult;
import com.iflytek.cloud.SpeechConstant;
import com.iflytek.cloud.SpeechError;
import com.iflytek.cloud.SpeechEvent;
import com.iflytek.cloud.SpeechRecognizer;
import com.iflytek.cloud.VoiceWakeuper;
import com.iflytek.cloud.WakeuperListener;
import com.iflytek.cloud.WakeuperResult;
import com.iflytek.cloud.util.ResourceUtil;
import com.lskj.wakeup.biz.AsrResultModel;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Ge Xiaodong
 * @time 2019/9/25 16:06
 * @description 讯飞语音唤醒+识别
 */
public class WakeUpAndRecognitionUtil {

    private static final String TAG = "IflytekWakeUp";
    // 命令词识别内容
    // 天气
    private String[] matchWeather = new String[]{"天气", "气温"};
    // 日期
    private String[] matchDate = new String[]{"几号", "周几", "星期几", "日子"};
    // 限号
    private String[] matchLimit = new String[]{"限行", "限号"};
    // 温湿度
    private String[] matchTemp = new String[]{"温度", "湿度", "温湿度"};

    public static final int TYPE_WEATHER = 1;
    public static final int TYPE_DATE = 2;
    public static final int TYPE_LIMIT = 3;
    public static final int TYPE_TEMP = 4;
    // 匹配结果最低置信度(得分)
    private static final int RECOGNITION_SCORE = 30;

    private Context mContext;
    // 唤醒的阈值，就相当于门限值，当用户输入的语音的置信度大于这一个值的时候，才被认定为成功唤醒。
    private int curThresh = 1000;

    // 语音唤醒对象
    private VoiceWakeuper mIvw;
    // 语音识别对象
    private SpeechRecognizer mAsr;

    // 本地语法id
    private String mLocalGrammarID;
    // 本地语法文件
    private String mLocalGrammar;
    // 本地语法构建路径
    private String grmPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/msc/lskj";

    // 唤醒结果
    private String resultString;
    private List<AsrResultModel> asrResultList;

    public WakeUpAndRecognitionUtil(Context context) {
        this.mContext = context;
        asrResultList = new ArrayList<>();
        // 初始化唤醒对象
        mIvw = VoiceWakeuper.createWakeuper(mContext, null);
        // 初始化识别对象---唤醒+识别,用来构建语法
        mAsr = SpeechRecognizer.createRecognizer(mContext, null);
        // 初始化语法文件
        mLocalGrammar = readFile("wake.bnf", "utf-8");

        initAsrAndStartWakeuper();
    }

    public void initAsrAndStartWakeuper() {
        mAsr = SpeechRecognizer.getRecognizer();
        if (null != mAsr) {
            mAsr.setParameter(SpeechConstant.PARAMS, null);
            mAsr.setParameter(SpeechConstant.TEXT_ENCODING, "utf-8");
            // 设置引擎类型
            mAsr.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_LOCAL);
            // 设置语法构建路径
            mAsr.setParameter(ResourceUtil.GRM_BUILD_PATH, grmPath);
            // 设置资源路径
            mAsr.setParameter(ResourceUtil.ASR_RES_PATH, getResourcePath());
            int ret = mAsr.buildGrammar("bnf", mLocalGrammar, grammarListener);
            if (ret != ErrorCode.SUCCESS) {
                Log.e(TAG, "语法构建失败,错误码：" + ret + ",请点击网址https://www.xfyun.cn/document/error-code查询解决方案");
            }
        }
    }

    private GrammarListener grammarListener = new GrammarListener() {
        @Override
        public void onBuildFinish(String grammarId, SpeechError error) {
            if (error == null) {
                mLocalGrammarID = grammarId;
                Log.d(TAG, "语法构建成功：" + grammarId);

                startWakeuper();
            } else {
                Log.d(TAG, "语法构建失败,错误码：" + error.getErrorCode() + ",请点击网址https://www.xfyun.cn/document/error-code查询解决方案");
            }
        }
    };

    /**
     * 开启唤醒功能
     */
    public void startWakeuper() {
        //非空判断，防止因空指针使程序崩溃
        mIvw = VoiceWakeuper.getWakeuper();
        if (mIvw != null) {
            resultString = "";
            asrResultList.clear();

            // 清空参数
            mIvw.setParameter(SpeechConstant.PARAMS, null);
            // 设置唤醒模式------------唤醒+识别
            mIvw.setParameter(SpeechConstant.IVW_SST, "oneshot");
            //设置识别引擎，只影响唤醒后的识别（唤醒本身只有离线类型）
            mIvw.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_LOCAL);
            String resPath = ResourceUtil.generateResourcePath(mContext, ResourceUtil.RESOURCE_TYPE.assets, "ivw/5db263d2.jet");

            // 唤醒门限值，根据资源携带的唤醒词个数按照“id:门限;id:门限”的格式传入
            mIvw.setParameter(SpeechConstant.IVW_THRESHOLD, "0:" + curThresh);
            // 设置持续进行唤醒  单独做唤醒时使用
            mIvw.setParameter(SpeechConstant.KEEP_ALIVE, "1");
            // 设置闭环优化网络模式
            mIvw.setParameter(SpeechConstant.IVW_NET_MODE, "0");
            // 设置唤醒资源路径
            mIvw.setParameter(SpeechConstant.IVW_RES_PATH, resPath);
            // 设置唤醒录音保存路径，保存最近一分钟的音频
            mIvw.setParameter(SpeechConstant.IVW_AUDIO_PATH, Environment.getExternalStorageDirectory().getPath() + "/msc/ivw.wav");
            mIvw.setParameter(SpeechConstant.AUDIO_FORMAT, "wav");
            // 如有需要，设置 NOTIFY_RECORD_DATA 以实时通过 onEvent 返回录音音频流字节
            //mIvw.setParameter( SpeechConstant.NOTIFY_RECORD_DATA, "1" );
            if (!TextUtils.isEmpty(mLocalGrammarID)) {
                // 设置本地识别资源
                mIvw.setParameter(ResourceUtil.ASR_RES_PATH, getResourcePath());
                // 设置语法构建路径
                mIvw.setParameter(ResourceUtil.GRM_BUILD_PATH, grmPath);
                // 设置本地识别使用语法id
                mIvw.setParameter(SpeechConstant.LOCAL_GRAMMAR, mLocalGrammarID);
                // 启动唤醒
                mIvw.startListening(mWakeuperListener);
            } else {
                Log.d(TAG, "请先构建语法");
            }
        }
    }

    /**
     * 销毁唤醒功能
     */
    public void destroyWakeuper() {
        // 销毁合成对象
        mIvw = VoiceWakeuper.getWakeuper();
        if (mIvw != null) {
            mIvw.destroy();
        }
    }

    /**
     * 停止唤醒
     */
    public void stopWakeuper() {
        mIvw.stopListening();
    }

    /**
     * 唤醒词监听类
     *
     * @author Administrator
     */
    private WakeuperListener mWakeuperListener = new WakeuperListener() {


        //开始说话
        @Override
        public void onBeginOfSpeech() {
            Log.d(TAG, "开始说话");
        }

        //错误码返回
        @Override
        public void onError(SpeechError arg0) {

        }

        @Override
        public void onEvent(int eventType, int isLast, int arg2, Bundle obj) {
            Log.d(TAG, "eventType:" + eventType + ", arg1:" + isLast + ", arg2:" + arg2);
            // 识别结果
            if (SpeechEvent.EVENT_IVW_RESULT == eventType) {
                RecognizerResult reslut = ((RecognizerResult) obj.get(SpeechEvent.KEY_EVENT_IVW_RESULT));
                assert reslut != null;
                int asrType = parseGrammarResult(reslut.getResultString());

                // 识别出询问天气
                if (asrType == TYPE_WEATHER) {
                    Log.d(TAG, "询问了天气");

                    EventBus.getDefault().post(new EventMsg(TYPE_WEATHER));
                } else if (asrType == TYPE_DATE) {
                    Log.d(TAG, "询问了日期");

                    EventBus.getDefault().post(new EventMsg(TYPE_DATE));
                } else if (asrType == TYPE_LIMIT) {
                    Log.d(TAG, "询问了限号");

                    EventBus.getDefault().post(new EventMsg(TYPE_LIMIT));
                } else if (asrType == TYPE_TEMP) {
                    Log.d(TAG, "询问了温湿度");

                    EventBus.getDefault().post(new EventMsg(TYPE_TEMP));
                }else {
                    if (isLast == 1) {
                        new Handler().postDelayed(() -> startWakeuper(), 1000);
                    }
                }
            }

        }

        @Override
        public void onVolumeChanged(int i) {

        }

        @SuppressLint("ResourceType")
        @Override
        public void onResult(WakeuperResult result) {

            try {
                String text = result.getResultString();
                JSONObject object;
                object = new JSONObject(text);
                StringBuffer buffer = new StringBuffer();
                buffer.append("【RAW】 " + text);
                buffer.append("\n");
                buffer.append("【操作类型】" + object.optString("sst"));
                buffer.append("\n");
                buffer.append("【唤醒词id】" + object.optString("id"));
                buffer.append("\n");
                buffer.append("【得分】" + object.optString("score"));
                buffer.append("\n");
                buffer.append("【前端点】" + object.optString("bos"));
                buffer.append("\n");
                buffer.append("【尾端点】" + object.optString("eos"));
                resultString = buffer.toString();

                uploadRecognition(text);
                // 播放音频
//                MediaPlayUtil.startRawPlay(mContext, R.raw.wakeup);
                // 亮屏
//                DeviceUtil.getInstance().unlockScreen();

                Log.d(TAG, resultString);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    };

    /**
     * 上传识别的结果
     *
     * @param text
     */
    private void uploadRecognition(String text) {
//        RecognitionUpload recognition = new RecognitionUpload();
//        recognition.setWord(text);
//        BusinessReqBody reqBody = new BusinessReqBody();
//        reqBody.setBusinessType(TCPBizType.SoftwareBizType.TYPE_SPEECH_RECOGNITION);
//        reqBody.setToken(DyxcApplication.getInstance().getTcpToken());
//        reqBody.setRequestJson(JSON.toJSONString(recognition));
//
//        DyxcApplication.getInstance().packageAndSendTCPMsg(TCPBizType.TYPE_TCP_MSG_BIZ_REQ, JSON.toJSONString(reqBody));
    }

    /**
     * 读取asset目录下文件。
     *
     * @return content
     */
    public String readFile(String file, String code) {
        int len = 0;
        byte[] buf = null;
        String result = "";
        try {
            InputStream in = mContext.getAssets().open(file);
            len = in.available();
            buf = new byte[len];
            in.read(buf, 0, len);

            result = new String(buf, code);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    // 获取识别资源路径
    private String getResourcePath() {
        // 识别通用资源
        return ResourceUtil.generateResourcePath(mContext, ResourceUtil.RESOURCE_TYPE.assets, "asr/common.jet");
    }

    public int parseGrammarResult(String json) {
        try {
            JSONTokener tokener = new JSONTokener(json);
            JSONObject joResult = new JSONObject(tokener);

            JSONArray words = joResult.getJSONArray("ws");
            for (int i = 0; i < words.length(); i++) {
                JSONArray items = words.getJSONObject(i).getJSONArray("cw");
                for (int j = 0; j < items.length(); j++) {
                    JSONObject obj = items.getJSONObject(j);
                    if (obj.getString("w").contains("nomatch")) {
                        Log.e(TAG, "没有匹配结果.");
                        return -1;
                    } else {
                        AsrResultModel resultModel = new AsrResultModel();
                        resultModel.setResultText(obj.getString("w"));
                        resultModel.setResultScore(obj.getInt("sc"));
                        asrResultList.add(resultModel);
                    }
                }
            }
            if (matchText(matchWeather)) {
                Log.d(TAG, "天气");
                return TYPE_WEATHER;
            } else if (matchText(matchDate)) {
                Log.d(TAG, "日期");
                return TYPE_DATE;
            } else if (matchText(matchLimit)) {
                Log.d(TAG, "限号");
                return TYPE_LIMIT;
            } else if (matchText(matchTemp)) {
                Log.d(TAG, "温湿度");
                return TYPE_TEMP;
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "没有匹配结果.");
            return -1;
        }
        return -1;
    }

    private boolean matchText(String[] matchWeather) {
        if (asrResultList.size() > 0) {
            for (AsrResultModel resultModel : asrResultList) {
                for (String s : matchWeather) {
                    if (resultModel.getResultText().contains(s) && resultModel.getResultScore() >= RECOGNITION_SCORE) {
                        return true;
                    }
                }
            }

        }
        return false;
    }
}
