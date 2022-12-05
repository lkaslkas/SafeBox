package com.dkay.safebox.function;

import android.os.Bundle;
import android.view.View;

import com.dkay.safebox.R;
import com.dkay.safebox.activity.MainActivity;
import com.dkay.safebox.api.IFunction;


public class testFunction implements IFunction {
    MainActivity mMain;
    private View mView;

    public testFunction(MainActivity main) {

        mMain = main;
        mView = View.inflate(mMain, R.layout.function_home, null);
        initData();
    }


    @Override
    public void initData() {

    }


    @Override
    public View getView() {
        return mView;
    }

    @Override
    public int getParentType() {
        return 0;
    }

    @Override
    public int getFunctionType() {
        return MainActivity.FUNCTION_TYPE_HOME;
    }

    @Override
    public void release() {

    }

    @Override
    public void callResult(Bundle data) {

    }

    @Override
    public void spare(Bundle data) {
    }

    @Override
    public boolean isShowHead() {
        return false;
    }



}
