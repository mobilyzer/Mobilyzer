/*
 * Copyright 2012 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.mobilyzer.measurements;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.InvalidClassException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementResult.TaskProgress;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.PLTExecutorService;
import com.mobilyzer.UpdateIntent;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.MeasurementJsonConvertor;
import com.mobilyzer.util.PhoneUtils;


/**
 * Measures the Page Load Time
 */
public class PageLoadTimeTask extends MeasurementTask {
	// Type name for internal use
	public static final String TYPE = "pageloadtime";
	// Human readable name for the task
	public static final String DESCRIPTOR = "Page Load Time";


	private long duration;
	private long startTimeFilter; //used in intent filter

	private volatile ArrayList<String> navigationTimingResults; 
	private volatile ArrayList<String> resourceTimingResults;

	
	private long dataConsumed;

	/**
	 * The description of PageLoadTime measurement
	 */
	public static class PageLoadTimeDesc extends MeasurementDesc {
		public String url;
		public boolean spdyTest;


		public PageLoadTimeDesc(String key, Date startTime, Date endTime, double intervalSec,
				long count, long priority, int contextIntervalSec, Map<String, String> params) {
			super(PageLoadTimeTask.TYPE, key, startTime, endTime, intervalSec, count, priority,
					contextIntervalSec, params);
			initializeParams(params);
			if (this.url == null) {
				throw new InvalidParameterException("PageLoadTimeTask cannot be created"
						+ " due to null url string");
			}
		}

		/*
		 * @see com.google.wireless.speed.speedometer.MeasurementDesc#getType()
		 */
		@Override
		public String getType() {
			return PageLoadTimeTask.TYPE;
		}

		@Override
		protected void initializeParams(Map<String, String> params) {
			if (params == null) {
				return;
			}

			this.url = params.get("url");

			if(params.get("spdy").equals("True")){
				this.spdyTest=true;  
			}else{
				this.spdyTest=false;
			}


		}

		protected PageLoadTimeDesc(Parcel in) {
			super(in);
			url = in.readString();
			spdyTest=in.readByte() != 0; 

		}

		public static final Parcelable.Creator<PageLoadTimeDesc> CREATOR =
				new Parcelable.Creator<PageLoadTimeDesc>() {
			public PageLoadTimeDesc createFromParcel(Parcel in) {
				return new PageLoadTimeDesc(in);
			}

			public PageLoadTimeDesc[] newArray(int size) {
				return new PageLoadTimeDesc[size];
			}
		};

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			super.writeToParcel(dest, flags);
			dest.writeString(url);
			dest.writeByte((byte) (spdyTest ? 1 : 0));
		}
	}

	public PageLoadTimeTask(MeasurementDesc desc) {
		super(new PageLoadTimeDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec,
				desc.count, desc.priority, desc.contextIntervalSec, desc.parameters));

		navigationTimingResults=new ArrayList<String>();
		resourceTimingResults= new ArrayList<String>();
		startTimeFilter=System.currentTimeMillis();

		dataConsumed=0;

		 this.duration=1000*60*4;
	}



	protected PageLoadTimeTask(Parcel in) {
		super(in);
		duration = in.readLong();
	}

	public static final Parcelable.Creator<PageLoadTimeTask> CREATOR =
			new Parcelable.Creator<PageLoadTimeTask>() {
		public PageLoadTimeTask createFromParcel(Parcel in) {
			return new PageLoadTimeTask(in);
		}

		public PageLoadTimeTask[] newArray(int size) {
			return new PageLoadTimeTask[size];
		}
	};

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		super.writeToParcel(dest, flags);
		dest.writeLong(duration);
	}

	/**
	 * Returns a copy of the PLTTask
	 */
	 @Override
	 public MeasurementTask clone() {
		 MeasurementDesc desc = this.measurementDesc;
		 PageLoadTimeDesc newDesc =
				 new PageLoadTimeDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec, desc.count,
						 desc.priority, desc.contextIntervalSec, desc.parameters);
		 return new PageLoadTimeTask(newDesc);
	 }


	 public synchronized ArrayList<String> getNavigationTimingResults(){
		 return navigationTimingResults;
	 }

	 public synchronized ArrayList<String> getResourceTimingResults(){
		 return resourceTimingResults;
	 }


	 public synchronized boolean isDone(){
		 if(((PageLoadTimeDesc)getDescription()).spdyTest){
			 if(resourceTimingResults.size()>2 && navigationTimingResults.size()==2){
				 return true;
			 }
		 }else{
			 if(resourceTimingResults.size()>2 && navigationTimingResults.size()==1){
				 return true;
			 }
		 }

		 return false;
	 }


	 @Override
	 public MeasurementResult[] call() throws MeasurementError {
		 MeasurementResult[] mrArray = new MeasurementResult[1];
		 PageLoadTimeDesc taskDesc = (PageLoadTimeDesc) this.measurementDesc;


		 Intent newintent = new Intent(PhoneUtils.getGlobalContext(), PLTExecutorService.class);
		 newintent.putExtra(UpdateIntent.PLT_TASK_PAYLOAD_URL, taskDesc.url);
		 newintent.putExtra(UpdateIntent.PLT_TASK_PAYLOAD_TEST_TYPE, taskDesc.spdyTest);
		 newintent.putExtra(UpdateIntent.PLT_TASK_PAYLOAD_STARTTIME, startTimeFilter);
		 PhoneUtils.getGlobalContext().startService(newintent);
		 Logger.d("ashkan_plt: PLT Test, Sending broadcast to start PLTExecutorService");

		 IntentFilter filter = new IntentFilter();
		 filter.addAction((UpdateIntent.PLT_MEASUREMENT_ACTION)+startTimeFilter);
		 BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
			 @Override
			 public void onReceive(Context context, Intent intent) {

				 if (intent.getAction().equals((UpdateIntent.PLT_MEASUREMENT_ACTION)+startTimeFilter)) {
					 Logger.d("ashkan_plt: PageLoadTimeTask: "+intent.getAction() + " RECEIVED");
					 if (intent.hasExtra(UpdateIntent.PLT_TASK_PAYLOAD_RESULT_NAV)){
						 String navigationStr=intent.getStringExtra(UpdateIntent.PLT_TASK_PAYLOAD_RESULT_NAV).substring(20);
						 navigationTimingResults.add(navigationStr);
						 if(intent.hasExtra(UpdateIntent.PLT_TASK_PAYLOAD_BYTE_USED)){
							 dataConsumed+=intent.getIntExtra(UpdateIntent.PLT_TASK_PAYLOAD_BYTE_USED, 0);
							 Logger.d("ashkan_plt: total data consumed: "+dataConsumed*2);
						 }
						 
						 Logger.d("ashkan_plt: >>>>navigationTimingResults: "+navigationTimingResults.size());
					 }else if(intent.hasExtra(UpdateIntent.PLT_TASK_PAYLOAD_RESULT_RES)){
						 String resrourcesStr=intent.getStringExtra(UpdateIntent.PLT_TASK_PAYLOAD_RESULT_RES).substring(18);
					        String[] resourcesArray=resrourcesStr.split("mobilyzer_resource");
					        for (String res: resourcesArray){
					        	if(res.length()<3){
					        		continue;
					        	}
					        	resourceTimingResults.add(res);
					        }
					        Logger.d("ashkan_plt: >>>>resourceTimingResults: "+resrourcesStr.length()+" "+resourceTimingResults.size());

						 
					 }

				 }
			 }
		 };
		 PhoneUtils.getGlobalContext().registerReceiver(broadcastReceiver, filter);



		 for(int i=0;i<60*3;i++){
			 if(isDone()){
				 break;
			 }
			 try {
				 Thread.sleep(1000);
			 } catch (InterruptedException e) {
				 e.printStackTrace();
			 }
		 }
		 Logger.e("ashkan_plt: PLTTask isDone");
		 PhoneUtils.getGlobalContext().unregisterReceiver(broadcastReceiver);
		 
		 if(isDone()){

			 
			 Logger.i("ashkan_plt: Successfully measured PLT");
			 PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
			 MeasurementResult result = new MeasurementResult(
					 phoneUtils.getDeviceInfo().deviceId,
					 phoneUtils.getDeviceProperty(this.getKey()),
					 PageLoadTimeTask.TYPE, System.currentTimeMillis() * 1000,
					 TaskProgress.COMPLETED, this.measurementDesc);

			 if(taskDesc.spdyTest){
				 result.addResult("navigationTimingResults_0", getNavigationTimingResults().get(0));
				 result.addResult("navigationTimingResults_1", getNavigationTimingResults().get(1));
				 int res_index=0;
				 for(String resResults: getResourceTimingResults()){
					 result.addResult("resource_"+res_index, resResults);
					 res_index++;
				 }
			 }else{
				 result.addResult("navigationTimingResults_0", getNavigationTimingResults().get(0));
				 int res_index=0;
				 for(String resResults: getResourceTimingResults()){
					 result.addResult("resource_"+res_index, resResults);
					 res_index++;
				 }  
			 }
			 Logger.i(MeasurementJsonConvertor.toJsonString(result));
			 mrArray[0]=result;
		 }else{
			 Logger.i("ashkan_plt: Not all the data is collected for this  PLT measurement");
			 PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
			 MeasurementResult result = new MeasurementResult(
					 phoneUtils.getDeviceInfo().deviceId,
					 phoneUtils.getDeviceProperty(this.getKey()),
					 PageLoadTimeTask.TYPE, System.currentTimeMillis() * 1000,
					 TaskProgress.FAILED, this.measurementDesc);
			 int nav_index=0;
			 for(String navResults: getNavigationTimingResults()){
				 result.addResult("navigationTimingResults_"+nav_index, navResults);
				 nav_index++;
			 }
			 int res_index=0;
			 for(String resResults: getResourceTimingResults()){
				 result.addResult("resource_"+res_index, resResults);
				 res_index++;
			 }
			 Logger.i(MeasurementJsonConvertor.toJsonString(result));
			 mrArray[0]=result;
		 }

		 PhoneUtils.getGlobalContext().stopService(new Intent(PhoneUtils.getGlobalContext(), PLTExecutorService.class));
		 return mrArray;

	 }

	 @SuppressWarnings("rawtypes")
	 public static Class getDescClass() throws InvalidClassException {
		 return PageLoadTimeDesc.class;
	 }

	 @Override
	 public String getType() {
		 return PageLoadTimeTask.TYPE;
	 }

	 @Override
	 public String getDescriptor() {
		 return DESCRIPTOR;
	 }

	 @Override
	 public String toString() {
		 return null;
	 }

	 @Override
	 public boolean stop() {
		 // There is nothing we need to do to stop the PLT measurement
		 return false;
	 }

	 @Override
	 public long getDuration() {
		 return this.duration;
	 }


	 @Override
	 public void setDuration(long newDuration) {
		 if (newDuration < 0) {
			 this.duration = 0;
		 } else {
			 this.duration = newDuration;
		 }
	 }

	 /**
	  * Since it is hard to get the amount of data sent directly, use a fixed value. The data consumed
	  * is usually small, and the fixed value is a conservative estimate.
	  * 
	  * TODO find a better way to get this value
	  */
	 @Override
	 public long getDataConsumed() {
		 return 2*dataConsumed;
	 }
}
