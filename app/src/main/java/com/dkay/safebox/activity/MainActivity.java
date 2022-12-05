package com.dkay.safebox.activity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.dkay.safebox.R;
import com.dkay.safebox.api.IFunction;
import com.dkay.safebox.function.HomeFunction;
import com.google.gson.Gson;

import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Stack;

public class MainActivity extends AppCompatActivity implements View.OnClickListener{

    public static MainActivity ME;
    // 这个是用户切换显示其他View的容器
    private FrameLayout mViewContainer;
    // 控制所有View切换的栈
    private final Stack<IFunction> mFunctions = new Stack<>();
    // 保存当前显示的功能
    public IFunction mCurentFunction;
    public static MyHandler handler;
    private Gson gson;

    public static Bundle savedInstanceState;

    public static final int FUNCTION_TYPE_HOME = 101;
    public static final int FUNCTION_TYPE_SETTING = 102;

    public static SharedPreferences pref;

    /*********/


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.savedInstanceState = savedInstanceState;
        requestWindowFeature(Window.FEATURE_NO_TITLE);// 隐藏标题
        setContentView(R.layout.activity_main);
        pref = getSharedPreferences("con",MODE_PRIVATE);
        handler = new MyHandler();
        ME = this;
        gson = new Gson();
        find();
        skipTo(FUNCTION_TYPE_HOME);

    }

    private void find() {
        mViewContainer = findViewById(R.id.floContainer);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 222: {
                if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                } else {
                }
                return;
            }
        }
    }




    class MyHandler extends Handler {
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {

            }
        }

    }



    @Override
    public void onClick(View view) {
        switch (view.getId()){

        }
    }

    public View inflate(int resId) {
        return getLayoutInflater().inflate(resId, null);
    }

    /**
     * 跳转到某个界面
     */
    public void skipTo(int viewID) {
        skipTo(viewID, null);
    }

    /**
     * 跳转到某个界面
     */
    public void skipTo(int viewID, Bundle data) {
        // 如果当前的界面是要跳转的到的界面的话，不刷新
        if (mCurentFunction != null && mCurentFunction.getFunctionType() == viewID)
            return;
        if (mCurentFunction != null)
            mCurentFunction.release();
        switch (viewID) {
            case FUNCTION_TYPE_HOME:
                mCurentFunction = new HomeFunction(ME);
                updateView();
                break;

        }
    }


    /**
     * 向主界面显示内容
     */

    public void updateView() {
        if (mCurentFunction == null) try {
            throw new Exception("Current Function is null!");
        } catch (Exception e) {
            e.printStackTrace();
        }
        mViewContainer.removeAllViews();
        mViewContainer.addView(mCurentFunction.getView());

    }

    /**
     * 用于跳转后可以返回到父VIEW
     */
    public void openFunction(int viewID, Bundle data) {
        //如果当前的界面是要跳转的到的界面的话，不刷新
        if (mCurentFunction != null && mCurentFunction.getFunctionType() == viewID )
            return;
        mFunctions.push(mCurentFunction);
        skipTo(viewID, data);
    }

    public void onBackPressed() {
        back();
    }

    public void setTitle(CharSequence text) {
        //设置标题
    }

    /**
     * 返回方法
     */
    public void back() {
        back(null);
    }

    /**
     * 返回方法
     */
    float curr = 0;

    public void back(Bundle data) {
        if (mCurentFunction != null) {
            int typeID = mCurentFunction.getParentType();
            if (typeID != 0) {
                mCurentFunction.release();
                if (mFunctions.isEmpty()) {
                    skipTo(typeID, null);
                } else {
                    IFunction tmpFunc = mFunctions.pop();
                    if (tmpFunc.getFunctionType() == typeID) {
                        mCurentFunction = tmpFunc;
                        updateView();
                    } else {
                        mFunctions.clear();
                        if (data != null) skipTo(typeID, data);
                        else skipTo(typeID, null);
                    }
                }
                return;
            }
        }
        if (System.currentTimeMillis() - curr < 2000) {
            moveTaskToBack(true);
            finish();
            curr = 0;
        } else {
            curr = System.currentTimeMillis();
//            MyToast.makeText(getApplicationContext(), getString(R.string.zai_an_yi_ci_fan_hui_zhuo_mian), Toast.LENGTH_SHORT).show();
        }
    }

    public static final int ACTIVITY_ID_VIDEOCONFIG = 1;




    //字符串转byte数组
    public static byte[] hexToByteArray(String inHex) {
        int hexlen = inHex.length();
        byte[] result;
        if (hexlen % 2 == 1) {
            //奇数
            hexlen++;
            result = new byte[(hexlen / 2)];
            inHex = "0" + inHex;
        } else {
            //偶数
            result = new byte[(hexlen / 2)];
        }
        int j = 0;
        for (int i = 0; i < hexlen; i += 2) {
            result[j] = hexToByte(inHex.substring(i, i + 2));
            j++;
        }
        return result;
    }

    public static byte hexToByte(String inHex) {
        return (byte) Integer.parseInt(inHex, 16);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCurentFunction.release();
    }

    @Override
    protected void onResume() {
        super.onResume();

    }


    @Override
    protected void onPause() {
        super.onPause();
    }


}