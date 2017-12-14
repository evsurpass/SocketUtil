# SocketUtil
You Can Use this Class To Connect Server Socket so easy

step 1

setting ip and port

step 2

Create Class like this

public class MyApplication extends Application
 
insert the code to Oncreate

mSocketUtil = new SocketUtil(mContext);
mSocketUtil.start();

step 3

you can use mSocketUtil in your Activity like This

mSocketUtil = ((MyApplication) getApplication()).mSocketUtil;
mSocketUtil.mHandler = socketHandler;//you must declare handler 
String res = mSocketUtil.Send("Login");



