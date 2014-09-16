package com.mobilyzer;



import com.mobilyzer.util.AndroidWebView;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.AndroidWebView.WebViewProtocol;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class PLTExecutorService extends Service {
	boolean spdyTest;
	
	@Override
	public void onCreate() {
		super.onCreate();
	}
	
	@Override
	  public int onStartCommand(Intent intent, int flags, int startId) {
		boolean spdy=intent.getBooleanExtra(UpdateIntent.PLT_TASK_PAYLOAD_TEST_TYPE,false);
		spdyTest=spdy;
		String url=intent.getStringExtra(UpdateIntent.PLT_TASK_PAYLOAD_URL);
		long startTimeFilter=intent.getLongExtra(UpdateIntent.PLT_TASK_PAYLOAD_STARTTIME,0);
		Logger.d("ashkan_plt: PLTExecutorService is started "+spdy+" "+url);
		if(spdy){
			
//			AndroidWebView spdyWebView=new AndroidWebView(this, false, startTimeFilter, url);
			AndroidWebView webView=new AndroidWebView(this, true, WebViewProtocol.HTTP,startTimeFilter, url);
			webView.loadUrl();
//			try {
//				Thread.sleep(50000);
//			} catch (InterruptedException e) {
//				e.printStackTrace();
//			}
			
//			spdyWebView.destroy();
//			AndroidWebView httpWebView=new AndroidWebView(this, false, startTimeFilter);
//			httpWebView.loadUrl(url);
			
		}else{
			AndroidWebView webView=new AndroidWebView(this, false, WebViewProtocol.HTTP,startTimeFilter, url);
			webView.loadUrl();
//			Intent newintent = new Intent(this, CrosswalkActivity.class);
//			newintent.putExtra(UpdateIntent.PLT_TASK_PAYLOAD_URL, url);
//			newintent.putExtra(UpdateIntent.PLT_TASK_PAYLOAD_STARTTIME, startTimeFilter);
//			newintent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK );
//			startActivity(newintent);
			
//			ChromiumWebView chWebview = new ChromiumWebView(this);
//			chWebview.getAwContents().clearCache(true);
//			chWebview.getAwContents().getSettings().setJavaScriptEnabled(true);
//			chWebview.getAwContents().loadUrl(new LoadUrlParams(url));
		}
		
	    return Service.START_NOT_STICKY;
	  }
	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub
		return null;
	}

	
	@Override
	public void onDestroy() {
		Logger.d("ashkan_plt: PLTExecutorService: onDestroy");
//		if(!spdyTest){
//			Intent closeIntent=new Intent(UpdateIntent.PLT_MEASUREMENT_ACTION);
//			closeIntent.putExtra(UpdateIntent.PLT_TASK_PAYLOAD_CLOSE_ACTIVITY, "True");
//			sendBroadcast(closeIntent);
//			
//		}
		// TODO Auto-generated method stub
		super.onDestroy();
	}
}
