package com.gongjiaozaixian.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Message;
import android.util.Log;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Date;

/**
 * Created by surpass on 2017/10/23.
 * 注意：Sent方法一定不能在主线程调用，因为它用到了mSocket对象，网络请求是禁止在主线程操作的
 * 否则会出现 NetworkOnMainThreadException异常
 * http://blog.csdn.net/mad1989/article/details/25964495
 */
class SocketStauts {
    //连接成功 发送成功 接收成功 连接失败 套接字错误 套接字发送失败
    public static int SocketConnectting = 0, SocketConnected = 1, SocketSended = 2, SocketReseived = 3, SocketConnectFaild = -1, SocketErr = -2, SocketSendFaild = -3;
}

public class SocketUtil extends Thread {

    String TAG = "SocketUtil";
    String hostIp = "";//主机IP地址
    int hostSocketPort = 80;//主机端口

    Context mContext;

    //网络检查线程（无网络时检测  由发送动作触发）
    Thread NetworkCheckThread;
    //心跳线程（实例话本类时被创建  调用本类的start时 同时启动此线程）
    Thread HeartThread;
    //服务器未开启supersocket会引发io异常，不能使用方法递归，否则会出现崩溃，只能用线程重复连接
    Thread SocketConnectWhenIOErrThread;

    //网络连接状态管理类（每次发送都会调用）
    ConnectivityManager connectivityManager;
    NetworkInfo mobNetInfo;
    NetworkInfo wifiNetInfo;

    final int ConnectTimeOut = 3000;
    Date ReceiveDataTime;//发送方法会根据当前时间和这个时间做对比，如果超过3秒，则判断服务器断开连接，心跳的关键参数之一
    int HeartTime = ConnectTimeOut;//心跳发送时间间隔  它的值必须要大于ConnectTimeOut 否则还未连接上就会判断 为服务器无心跳
    public Socket mSocket = null;//=======客户端套接字
    PrintWriter printWriter;//===========Socket发送对象
    BufferedReader bufferedReader;//=====Socket接收对象
    InputStreamReader mInputStreamReader;
    OutputStreamWriter mOutputStreamWriter;

    String HeartPackage = "0";

    boolean isRun = true;//===============是否正在运行 用于接收 socket数据  和 退出Socket的标记
    boolean isConnecttingServer = false;//=====是否正在连接服务器
    public boolean Connected = false;//=======判断是否和服务器建立 连接

    public Handler mHandler;//=============主线程处理器 （调用此类的组件）


    public SocketUtil(Context context) {
        mContext = context;
    }

    public void CreateHeartThreadAndStart() {
        if (HeartThread == null) {
            //===================================心跳线程
            HeartThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isRun) {
                        try {
                            Thread.sleep(HeartTime);
                            Send(HeartPackage);
                            Log.d(TAG, "[SocketUtil]发送心跳");
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }

                    }
                }
            });
        }
        HeartThread.start();
        //这里要把心跳加1秒，因为线程执行时间并会有几十毫秒误差
        HeartTime = ConnectTimeOut;
        HeartTime = HeartTime + 1000;
    }

    //连接socket服务器
    //1.调用线程start方法时调用此方法
    //1.连接出现IO错误时  重新调用此方法
    //1.定时发送方法调用此方法  改成心跳线程调用此方法
    public void ConnectToServer() {

        //必须开启一个新线程来连接，防止主线程调用
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Connected = false;

                Log.d(TAG, "[SocketUtil]偿试连接到服务器（Socket连接）");
                if (!NetworkIsConnected()) {
                    CreateAndStartNetWorkConnetFaile();
                    return;
                }

                if (isConnecttingServer) {
                    Log.d(TAG, "[SocketUtil]正在连接服务器，无法再创建连接!");
                    return;
                }

                isConnecttingServer = true;
                mSocket = null;


                mSocket = new Socket();
                // synchronized (mSocket) {
                if (isRun) {
                    if (mHandler != null) {
                        Message msg_s = mHandler.obtainMessage();
                        msg_s.what = SocketStauts.SocketConnectting;
                        mHandler.sendMessage(msg_s);
                    }
                }
                SocketAddress socAddress = new InetSocketAddress(hostIp, hostSocketPort);
                try {
                    mSocket.connect(socAddress, ConnectTimeOut);//设置连接超时时间  此方法会阻塞进程 如果连接失败 下面的代码无法执行
                    ReceiveDataTime = new Date(System.currentTimeMillis());
                    StopSocketConnectWhenIOErrThread();
                    Connected = true;
                    mSocket.setKeepAlive(true);
                    //mSocket.setSoTimeout(timeout);// 设置阻塞时间
                    InputStream is = mSocket.getInputStream();
                    OutputStream os = mSocket.getOutputStream();
                    mInputStreamReader = new InputStreamReader(is);
                    mOutputStreamWriter = new OutputStreamWriter(os);

                    bufferedReader = new BufferedReader(mInputStreamReader);
                    printWriter = new PrintWriter(new BufferedWriter(mOutputStreamWriter), true);

                    //=========连接成功后发送通知给主线程
                    if (isRun) {
                        if (mHandler != null) {
                            Message msg_e = mHandler.obtainMessage();
                            msg_e.what = SocketStauts.SocketConnected;
                            mHandler.sendMessage(msg_e);
                        }
                        isConnecttingServer = false;
                    }
                }
                //连接超时会调用此对象
                catch (IOException e) {
                    isConnecttingServer = false;

                    if (isRun) {
                        if (mHandler != null) {
                            Message msg_e = mHandler.obtainMessage();
                            msg_e.what = SocketStauts.SocketConnectFaild;
                            msg_e.obj = e.toString();
                            mHandler.sendMessage(msg_e);
                        }
                        isConnecttingServer = false;
                    }

                    Log.d(TAG, "[SocketUtil]" + e);
                    CreateAndStartSocketConnectWhenIOErrThread();
                    return;
                }

            }
        };
        r.run();

    }

    //实时接受数据
    @Override
    public void run() {

        CreateHeartThreadAndStart();
        ConnectToServer();


        String line = "";
        while (isRun) {
            try {
                if (mSocket != null && bufferedReader != null) {
                    while ((line = bufferedReader.readLine()) != null && isRun) {
                        Log.d(TAG, "[SocketUtil]接收消息内容(isRun:" + isRun + ")：" + line);

                        //更新接收时间
                        ReceiveDataTime = new Date(System.currentTimeMillis());
                        if (line.equals("1")) {
                            //Log.d(TAG, "收到的消息：" + line);

                        }
                        //非心跳发回来的数据，为JSON格式的字符串
                        else {
                            if (isRun) {
                                //收到服务器发来的消息
                                if (mHandler != null) {
                                    Message msg = mHandler.obtainMessage();
                                    msg.what = SocketStauts.SocketReseived;
                                    msg.obj = line;
                                    mHandler.sendMessage(msg);// 结果返回给UI处理
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    //判断网络是否连接
    public boolean NetworkIsConnected() {

        connectivityManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);

        mobNetInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);


        wifiNetInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        if (mobNetInfo.isConnected() || wifiNetInfo.isConnected()) {
            return true;
        } else {
            return false;
        }

    }

    //当连接失败出现IO错误时  启动线程重复连接  直到连接成功
    public void CreateAndStartSocketConnectWhenIOErrThread() {
        if (SocketConnectWhenIOErrThread == null) {
            SocketConnectWhenIOErrThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isRun) {
                        try {
                            Thread.sleep(300);
                            if (!isConnecttingServer) {
                                ConnectToServer();
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
            SocketConnectWhenIOErrThread.start();
        }
    }

    //创建并启动网络检测线程
    public void CreateAndStartNetWorkConnetFaile() {
        if (NetworkCheckThread != null) {
            NetworkCheckThread.interrupt();
            NetworkCheckThread = null;
        }

        //启动线程，检测网络，当网络通的时候，再调用连接方法
        NetworkCheckThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRun) {
                    try {
                        Thread.sleep(1000);
                        if (NetworkIsConnected()) {
                            Log.d(TAG, "[SocketUtil]网络通了，进行Socket连接");
                            ConnectToServer();
                            break;
                        } else {
                            Log.d(TAG, "[SocketUtil]网络不通，无法进行Socket连接");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        NetworkCheckThread.start();
    }

    //停止IO错误处理线程
    public void StopSocketConnectWhenIOErrThread() {
        if (SocketConnectWhenIOErrThread != null) {
            SocketConnectWhenIOErrThread.interrupt();
            SocketConnectWhenIOErrThread = null;
        }
    }

    //发送数据,由外部类调用
    public String Send(String mess) {
        String res = "";
        if (!isRun) {
            res = "抱歉~连接已停止运行，请联系客服处理！错误码:0";
            return res;
        }
        //=========================未连接服务器  发送消息
        if (!Connected) {
            res = "抱歉~无法与服务器建立连接，请稍后再试！错误码:1";
            if (mHandler != null && !mess.equals(HeartPackage)) {
                Message msg = mHandler.obtainMessage();
                msg.obj = res;
                msg.what = SocketStauts.SocketSendFaild;
                mHandler.sendMessage(msg);// 结果返回给UI处理
            }
            return res;
        }

        //=========================服务器正在连接  发送消息
        if (isConnecttingServer && !mess.equals(HeartPackage)) {
            res = "抱歉~数据提交失败！正在与服务器创建连接，请稍后再试！错误码:2";
            if (mHandler != null) {
                Message msg = mHandler.obtainMessage();
                msg.obj = res;
                msg.what = SocketStauts.SocketSendFaild;
                mHandler.sendMessage(msg);// 结果返回给UI处理
            }
            return res;
        }

        if (!NetworkIsConnected() && !mess.equals(HeartPackage)) {
            res = "网络连接失败，请检查您的网络后重试，谢谢！错误码:3";
            if (mHandler != null) {
                Message msg = mHandler.obtainMessage();
                msg.obj = res;
                msg.what = SocketStauts.SocketSendFaild;
                mHandler.sendMessage(msg);// 结果返回给UI处理
            }
            CreateAndStartNetWorkConnetFaile();
            return res;
        }

        //计算最近一次接收数据的时间和当前时间差，如果差大于3秒，则判断为失联
        Date now = new Date(System.currentTimeMillis());
        long ediff = now.getTime() - ReceiveDataTime.getTime();
        if (ediff > HeartTime + 1000) {
            res = "您已和服务器断开了连接，请稍后重试！";
            if (mHandler != null) {
                Message msg = mHandler.obtainMessage();
                msg.obj = res;
                msg.what = SocketStauts.SocketSendFaild;
                mHandler.sendMessage(msg);// 结果返回给UI处理
            }

            ConnectToServer();

            return res;
        }

        try {


            printWriter.println(mess + "\r\n");//换行符很重要，否则发送不出去
            printWriter.flush();
            if (mHandler != null) {
                Message msg = mHandler.obtainMessage();
                msg.what = SocketStauts.SocketSended;
                msg.obj = "200";
                mHandler.sendMessage(msg);// 结果返回给UI处理
            }

            return "200";

        } catch (Exception e) {
            if (mHandler != null) {
                Message msg = mHandler.obtainMessage();
                msg.what = SocketStauts.SocketSendFaild;
                msg.obj = "发送失败！原因：" + e.toString();
                mHandler.sendMessage(msg);// 结果返回给UI处理
            }
            e.printStackTrace();

            return "发送失败！原因：" + e.toString();
        }
    }

    //关闭连接
    public void close() {
        Log.d(TAG, "[SocketUtil]停止socket 线程 1.1");
        if (mHandler != null) {
            mHandler.removeCallbacks(this);
        }
        Log.d(TAG, "[SocketUtil]停止socket 线程 1.2");
        if (NetworkCheckThread != null) {
            NetworkCheckThread.interrupt();
            NetworkCheckThread = null;
        }
        Log.d(TAG, "[SocketUtil]停止socket 线程 1.3");
        if (HeartThread != null) {
            HeartThread.interrupt();
            HeartThread = null;
        }
        Log.d(TAG, "[SocketUtil]停止socket 线程 1.4");
        if (SocketConnectWhenIOErrThread != null) {
            SocketConnectWhenIOErrThread.interrupt();
            SocketConnectWhenIOErrThread = null;
        }
        Log.d(TAG, "[SocketUtil]停止socket 线程 1.5");
        try {
            isRun = false;
            Log.d(TAG, "[SocketUtil]关闭socket");
            if (mSocket != null) {

                //bufferedReader.close();
                Log.d(TAG, "[SocketUtil]停止socket 线程 1.5.1");
                //printWriter.close();
                Log.d(TAG, "[SocketUtil]停止socket 线程 1.5.2");
                mSocket.close();
                Log.d(TAG, "[SocketUtil]停止socket 线程 1.5.3");
            }
            Log.d(TAG, "[SocketUtil]停止socket 线程 1.6");

        } catch (Exception e) {
            Log.d(TAG, "[SocketUtil]close err");
            e.printStackTrace();
            Log.d(TAG, "[SocketUtil]停止socket 线程 1.7");
        }

        Log.d(TAG, "[SocketUtil]停止socket 线程 1.8");
        this.interrupt();
    }
}