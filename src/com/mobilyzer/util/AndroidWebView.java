package com.mobilyzer.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;


import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.mobilyzer.UpdateIntent;
import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.internal.Util;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Handler;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class AndroidWebView extends WebView {
	public static enum WebViewProtocol{
		SPDY, HTTP
	}
	
	private static String USER_AGENT="Mozilla/5.0 (Linux; U; Android 4.3; en-us; SCH-I535 Build/JSS15J) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30";
	
	OkHttpClient client;
	boolean spdyTest;
	long startTimeFilter;
	String url;
	Context context;
	long pageStartLoading;
	WebViewProtocol protocol;
//	private volatile ArrayList<String> objsTimings;
//	private volatile int totalByte;
	public AndroidWebView(Context context, boolean spdyTest, WebViewProtocol protocol, long startTimeFilter, String url) {
		super(context);
		this.spdyTest=spdyTest;
		this.context=context;
		this.url=url;
		this.protocol=protocol;
		
		
		
		this.startTimeFilter=startTimeFilter;
		clearCache(true);
		getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
		getSettings().setAppCacheEnabled(false);
		getSettings().setJavaScriptEnabled(true);
		getSettings().setUserAgentString(USER_AGENT);
		context.deleteDatabase("webview.db");
		context.deleteDatabase("webviewCache.db");
		clearHistory();
		
		client= new OkHttpClient();
		if(client.getConnectionPool()!=null && client.getConnectionPool().getConnectionCount()!=0){
			client.getConnectionPool().evictAll();
		}
		
		client.setConnectionPool(ConnectionPool.getDefault());
//		client.getCache().delete();
		
		
		if(spdyTest){
			if(protocol.equals(WebViewProtocol.HTTP)){
				client.setProtocols(Util.immutableList(Protocol.HTTP_1_1));
			}else{
				client.setProtocols(Util.immutableList(Protocol.SPDY_3, Protocol.HTTP_1_1));
			}
		}else{
			client.setProtocols(Util.immutableList(Protocol.HTTP_1_1));
		}
		
		
		TrustManager localTrustmanager = new X509TrustManager() {

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return null;
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain,
					String authType) throws CertificateException {
			}

			@Override
			public void checkClientTrusted(X509Certificate[] chain,
					String authType) throws CertificateException {

			}
		};

		// Create SSLContext and set the socket factory as default
		try {
			SSLContext sslc = SSLContext.getInstance("SSL");
			sslc.init(null, new TrustManager[] { localTrustmanager },
					new SecureRandom());
			client.setSslSocketFactory(sslc.getSocketFactory());
			HttpsURLConnection.setDefaultSSLSocketFactory(sslc.getSocketFactory());
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (KeyManagementException e) {
			e.printStackTrace();
		}

		
		setWebViewClient(new WebViewClient(){
		    
		  
		  @Override
		  public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
		    handler.proceed();
		  }
		  
			@Override
			public void onPageStarted(WebView view, String url, Bitmap favicon) {
				Logger.d("ashkan_plt: Page started: "+url);
				super.onPageStarted(view, url, favicon);
			}  
			
		      @Override
		      public void onPageFinished(WebView view, String url) {
		    	Logger.d("ashkan_plt: Page finished: "+url);
		      
		    	
		    	new Handler(){
		    		private WebView view;
		    		
		    		
		    		public void init(WebView v){
		    			view=v;
		    			postDelayed(new Runnable() {
							@Override
							public void run() {

							  String js_code = "javascript:(\n function() { \n";
							  js_code += "            var result='';\n";
							  js_code += "            for(var prop in performance.timing){\n";
							  js_code += "              if(performance.timing.hasOwnProperty(prop)){\n";
							  js_code += "                  result=prop+':'+performance.timing[prop]+'|'+result}}\n";
							  js_code += "            console.log('mobilyzer_navigation'+result);\n";
							  js_code += "            perfObj=window.performance.getEntriesByType('resource');\n";
							  js_code += "            all_res='';\n";
							  js_code += "            for(var prop in perfObj){if(perfObj.hasOwnProperty(prop)){\n";
							  js_code += "              res='';\n";
							  js_code += "              for(var index in perfObj[prop]){res=index+':'+perfObj[prop][index]+'|'+res;}\n";
							  if(AndroidWebView.this.spdyTest){
							    if(AndroidWebView.this.protocol.equals(WebViewProtocol.HTTP)){
							      js_code += "                  all_res=all_res+'mobilyzer_resource|http|'+res;}}\n";
							    }else{
							      js_code += "                  all_res=all_res+'mobilyzer_resource|spdy|'+res;}}\n";
							    }
							  }else{
							    js_code += "                  all_res=all_res+'mobilyzer_resource|http|'+res;}}\n";
							  }
							  js_code += "            console.log(all_res);\n";
							  js_code += "    })()\n";
							  view.loadUrl(js_code);
							}
		    			}, 20000);
		    		}
		    		
		    	}.init(view);
		        super.onPageFinished(view, url);
		      }
		      
		      
		      
		      @Override
		      public WebResourceResponse shouldInterceptRequest(WebView view, String urlStr) {
		    	  
		    	  long relStartTime;
		    	  if(urlStr.equals(AndroidWebView.this.url)){
		    		  pageStartLoading=System.currentTimeMillis();
		    		  relStartTime=0;
		    	  }else{
		    		  relStartTime=System.currentTimeMillis()-pageStartLoading;
		    	  }
		    	  Logger.d("ashkan_plt: shouldInterceptRequest: "+urlStr+" "+relStartTime);
		    	  
		    	  
		    	  if (urlStr == null || urlStr.trim().equals("") || !(urlStr.startsWith("http") && !urlStr.startsWith("www"))|| urlStr.contains("|")){
		    		  return super.shouldInterceptRequest(view, urlStr);
		    	  }
		    	  
		    	  
	              return new MyWebResourceResponse(urlStr);
//		    	  return super.shouldInterceptRequest(view, urlStr);
		      }
		      
		    });
		
		setWebChromeClient(new WebChromeClient(){
		      @Override
		      public void onConsoleMessage(String message, int lineNumber, String sourceID) {
		    	  
		    	  Logger.d("onConsoleMessage: "+message);
		          Intent newintent = new Intent();
		          newintent.setAction((UpdateIntent.PLT_MEASUREMENT_ACTION)+AndroidWebView.this.startTimeFilter);
		          if(message.startsWith("mobilyzer_navigation")){
		        	  
		        	  if(AndroidWebView.this.spdyTest){
		        		  if(AndroidWebView.this.protocol.equals(WebViewProtocol.HTTP)){
		        			  message=message.replace("mobilyzer_navigation", "mobilyzer_navigation|http");	
		        		  }else{
		        			  message=message.replace("mobilyzer_navigation", "mobilyzer_navigation|spdy");  
		        		  }
		        	  }else{
		        		  message=message.replace("mobilyzer_navigation", "mobilyzer_navigation|http");
		        	  }
		        	  newintent.putExtra(UpdateIntent.PLT_TASK_PAYLOAD_RESULT_NAV, message);
//		        	  newintent.putExtra(UpdateIntent.PLT_TASK_PAYLOAD_BYTE_USED, totalByte);//TODO
		              PhoneUtils.getGlobalContext().sendBroadcast(newintent);
		              Logger.d("ashkan_plt: onConsoleMessage: Broadcasting mobilyzer_navigation result "+message.length());
		            
		            if(AndroidWebView.this.spdyTest && AndroidWebView.this.protocol.equals(WebViewProtocol.HTTP)){
		            	AndroidWebView spdyWebView=new AndroidWebView(AndroidWebView.this.context, true, WebViewProtocol.SPDY ,AndroidWebView.this.startTimeFilter, AndroidWebView.this.url);
	            		spdyWebView.loadUrl();
		            }
		            
		            AndroidWebView.this.destroyDrawingCache();
//		            AndroidWebView.this.destroy();
		            
		            
		          }else if(message.startsWith("mobilyzer_resource")){
                    newintent.putExtra(UpdateIntent.PLT_TASK_PAYLOAD_RESULT_RES, message);
                    PhoneUtils.getGlobalContext().sendBroadcast(newintent);
                    Logger.d("ashkan_plt: onConsoleMessage: Broadcasting mobilyzer_resource result "+message.length());
		          } 
		      }
		    });
	}
	
	   class MyWebResourceResponse extends WebResourceResponse{
	        

	        private String url; 
	        public MyWebResourceResponse(String url){
	            super("", "", null);
	            this.url=url;
	        }
	        public MyWebResourceResponse(String mimeType, String encoding,
	                InputStream data) {
	            super(mimeType, encoding, data);
	        }
	        @Override
	        public InputStream getData() {
	            return new MyInputStream(url);

	            
	        }
	        
	    }
	    
	    class MyInputStream extends InputStream{
	        private String url;
	        private boolean initialized;
	        private InputStream is;
	        public MyInputStream(String url) {
	            this.url=url;
	            initialized=false;
	        }
	        @Override
	        public int read() throws IOException {
	            if(!initialized){
	                try {
	                    
	                    Request request = new Request.Builder()
	                    .url(url)
	                    .header("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.3; en-us; SCH-I535 Build/JSS15J) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30")
	                    .build();


	                    long startTime=System.currentTimeMillis();
	                    Response response = client.newCall(request).execute();
	                    Logger.d("ashkan_plt: HTTP: "+client.getConnectionPool().getHttpConnectionCount()+" SPDY: "+client.getConnectionPool().getSpdyConnectionCount());
	                    long endTime=System.currentTimeMillis();
	                    is= response.body().byteStream();
	                     
	                   Logger.d("ashkan_plt: load time for "+url+":\t "+(endTime-startTime));
	                } catch (IOException e) {
	                    e.printStackTrace();
	                }
	                initialized=true;
	            }
	            return is.read();
	        }
	        
	    }

	
	public void loadUrl() {
		super.loadUrl(this.url);
	}

}
