package com.dkay.safebox.function;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import com.dkay.safebox.R;
import com.dkay.safebox.activity.HomeActivity;
import com.dkay.safebox.activity.MainActivity;
import com.dkay.safebox.api.IFunction;


public class HomeFunction implements IFunction {
    MainActivity mMain;
    private View mView;
    private LinearLayout ll_open;

    public HomeFunction(MainActivity main) {

        mMain = main;
        mView = View.inflate(mMain, R.layout.function_home, null);
        ll_open = mView.findViewById(R.id.ll_open);
        ll_open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mMain.startActivity(new Intent(mMain, HomeActivity.class));
            }
        });
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
