package com.dkay.safebox.api;

import android.os.Bundle;
import android.view.View;


/**
 * 功能块代码接口
 *
 * @author CSH
 */
public interface IFunction {

    /**
     * 初始化数据和find控件
     */
    void initData();

    /**
     * 获取到功能块的视图用于显示
     **/
    View getView();

    /**
     * 获得上一级ViewID
     *
     * @return 返回上一级View的TypeID
     * 返回0表示顶级
     * 返回 -1表示返回之前打开页面，未知父级
     */
    int getParentType();

    /**
     * 获得该功能能块的类型
     **/
    int getFunctionType();

    /**
     * 释放资源，停止任务线程
     **/
    void release();

    /**
     * 用来设置点击其他activity后返回的数据操作。。相当于onActivityResult
     */
    void callResult(Bundle data);

    /**
     * 预留方法
     */
    void spare(Bundle data);


    /**
     * 是否隐藏顶部
     */
    boolean isShowHead();


}
