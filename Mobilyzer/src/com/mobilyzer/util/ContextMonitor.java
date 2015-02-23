package com.mobilyzer.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.DetectedActivity;
import com.mobilyzer.Config;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementScheduler;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.UpdateIntent;
import com.mobilyzer.api.API;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.measurements.PingTask;
import com.mobilyzer.prerequisite.IntPrerequisite;
import com.mobilyzer.prerequisite.Prerequisite;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.RemoteException;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

public class ContextMonitor implements  GooglePlayServicesClient.ConnectionCallbacks, GooglePlayServicesClient.OnConnectionFailedListener{
	private static ContextMonitor singletonContextMonitor = null;
	private static PhoneUtils phoneUtils = null;

	private Messenger schedulerMessenger = null;
	private AlarmManager alarmManager = null;
	private PendingIntent timeIntent = null;
	private PendingIntent activityTimeIntent = null;
	ActivityRecognitionClient activityRecognitionClient = null;
	
	String currentActivity = "UNKNOWN";
	long activityUpdateTime = 0;
	//The map between prerequisite type and its corresponding listen queue
	static private HashMap<String, LinkedList<MeasurementTask>> preTypeToListenListMap;
	static{
		preTypeToListenListMap= new HashMap<String, LinkedList<MeasurementTask>>();
		preTypeToListenListMap.put(Prerequisite.PRE_TYPE_CALL, new LinkedList<MeasurementTask>());
		preTypeToListenListMap.put(Prerequisite.PRE_TYPE_NETWORK, new LinkedList<MeasurementTask>());
		preTypeToListenListMap.put(Prerequisite.PRE_TYPE_RESULTS, new LinkedList<MeasurementTask>());
		preTypeToListenListMap.put(Prerequisite.PRE_TYPE_TIME, new LinkedList<MeasurementTask>());
		preTypeToListenListMap.put(Prerequisite.PRE_TYPE_SCREEN, new LinkedList<MeasurementTask>());
		preTypeToListenListMap.put(Prerequisite.PRE_TYPE_ACTIVITY, new LinkedList<MeasurementTask>());
		preTypeToListenListMap.put(Prerequisite.PRE_TYPE_LOCATION, new LinkedList<MeasurementTask>());
	}
	//All tasks registered at contextMonitor
	private volatile LinkedList<MeasurementTask> allTaskList = new LinkedList<MeasurementTask>();

	//The map between a task and the listen queue it registers
	private HashMap<MeasurementTask, LinkedList<LinkedList<MeasurementTask>>> taskToListenListsMap = new HashMap<MeasurementTask, LinkedList<LinkedList<MeasurementTask>>>();

	private ContextMonitor(){
		phoneUtils = PhoneUtils.getPhoneUtils();
		activityRecognitionClient = new ActivityRecognitionClient(PhoneUtils.getGlobalContext(), this, this);
		activityRecognitionClient.connect();
		alarmManager = (AlarmManager) PhoneUtils.getGlobalContext().getSystemService(Context.ALARM_SERVICE);
		IntentFilter filter = new IntentFilter();
		filter.addAction("mobilyzer.util.ContextMonitor.checkTimeQueue");
		filter.addAction("mobilyzer.util.ContextMonitor.checkActivityQueue");
		BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (intent.getAction().equals("mobilyzer.util.ContextMonitor.checkTimeQueue")) {
					recheckListenList(getListenList(Prerequisite.PRE_TYPE_TIME));
				}else if(intent.getAction().equals("mobilyzer.util.ContextMonitor.checkActivityQueue")){
					recheckListenList(getListenList(Prerequisite.PRE_TYPE_ACTIVITY));
				}
			}
		};
		PhoneUtils.getGlobalContext().registerReceiver(broadcastReceiver, filter);
	}

	//return the global contextMonitor
	public static synchronized ContextMonitor getContextMonitor() {
		if (singletonContextMonitor == null) {
			singletonContextMonitor = new ContextMonitor();
		}
		return singletonContextMonitor;
	}

	//set the messager for communicating with MeasurementScheduler
	public void setSchedulerMessenger(Messenger msger){
		schedulerMessenger = msger;
	}


	//API for registering a measurement task to the context monitor
	public synchronized void registerMeasurementTask(MeasurementTask measurementTask){
		if(measurementTask.isPrereqSatisied()){
			submitTaskToScheduler(measurementTask);
			return;
		}
		allTaskList.add(measurementTask);
		Log.i("xsc","Register task to context: "+measurementTask);

		LinkedList<LinkedList<MeasurementTask>> listenLists = new LinkedList<LinkedList<MeasurementTask>>();
		for (ArrayList<Prerequisite> preGroup: measurementTask.getPrerequisiteGroups()){
			for (Prerequisite pre: preGroup){
				if (pre.satisfy())continue;
				LinkedList<MeasurementTask> listenList = getListenList(pre.getType());
				if(!listenList.contains(measurementTask))
				{
					listenList.add(measurementTask);
					listenLists.add(listenList);
					if (pre.getName()==Prerequisite.TIME_TIME){
						IntPrerequisite intPrerequisite = (IntPrerequisite)pre;
						long satisfyTime = intPrerequisite.getThreshold()+100;
						timeIntent = PendingIntent.getBroadcast(PhoneUtils.getGlobalContext(), (int)(satisfyTime%Integer.MAX_VALUE), 
								new Intent("mobilyzer.util.ContextMonitor.checkTimeQueue"),
								PendingIntent.FLAG_UPDATE_CURRENT);
						alarmManager.set(AlarmManager.RTC_WAKEUP, satisfyTime, timeIntent);
					}else if(pre.getName()==Prerequisite.ACTIVITY_LASTTIME){
						IntPrerequisite intPrerequisite = (IntPrerequisite)pre;
						long satisfyTime = intPrerequisite.getThreshold()+activityUpdateTime+100;
						timeIntent = PendingIntent.getBroadcast(PhoneUtils.getGlobalContext(), (int)(satisfyTime%Integer.MAX_VALUE), 
								new Intent("mobilyzer.util.ContextMonitor.checkActivityQueue"),
								PendingIntent.FLAG_UPDATE_CURRENT);
						alarmManager.set(AlarmManager.RTC_WAKEUP, satisfyTime, timeIntent);
					}
				}
				break;
			}
		}
		if(listenLists.size()>0)
			taskToListenListsMap.put(measurementTask, listenLists);
		Log.i("xsc","New measurement registered: "+measurementTask);
		Log.i("xsc","Current registered task number: "+allTaskList.size());
	}


	//get the corresponding listen queue for a specifc preName
	private LinkedList<MeasurementTask> getListenList(String preName){
		if(preTypeToListenListMap.containsKey(preName))
			return preTypeToListenListMap.get(preName);
		return null;
	}

	//unregister a task from context monitor
	private void unregisterTask(MeasurementTask task){
		Log.i("xsc","Unregistertask");
		if (taskToListenListsMap.containsKey(task)){
			Log.i("xsc","Listen list num:"+taskToListenListsMap.get(task).size());
			for (LinkedList<MeasurementTask> listenList: taskToListenListsMap.get(task)){
				for (Iterator<MeasurementTask> iter = listenList.iterator(); iter.hasNext();){
					MeasurementTask taskI = iter.next();
					if(taskI==task) iter.remove();
				}
				Log.i("xsc","Listen list size: "+listenList.size());
			}
		}
		taskToListenListsMap.remove(task);
		if (allTaskList.contains(task))
			allTaskList.remove(task);
	}



	//submit the task back to the measurementScheduler (when the prerequisites are satisfied)
	private void submitTaskToScheduler(MeasurementTask task){
		unregisterTask(task);
		Message msg = Message.obtain(null, Config.MSG_SUBMIT_TASK);
		Bundle data = new Bundle();
		data.putParcelable(UpdateIntent.MEASUREMENT_TASK_PAYLOAD, task);
		msg.setData(data);  
		try {
			schedulerMessenger.send(msg);
		} catch (RemoteException e) {
			String err = "remote scheduler failed!";
			Logger.e(err);
		}
		Log.i("xsc","Submit Task: "+task.toString());
		Log.i("xsc","Current registered task number: "+allTaskList.size());
	}

	//Recheck tasks on a listenList to see whether they are satisfied
	private synchronized void recheckListenList(LinkedList<MeasurementTask> listenList){
		Log.i("xsc","enter recheck");
		for (Iterator<MeasurementTask> iter = listenList.iterator(); iter.hasNext();){
			MeasurementTask task = iter.next();
			if(task.isPrereqSatisied()) submitTaskToScheduler(task);
		}
		Log.i("xsc","go out of recheck");
	}



	//get current context value for contextName
	public String getContext(String contextName){
		if(contextName.equals(Prerequisite.NETWORK_TYPE))
			return phoneUtils.getNetwork();
		if(contextName.equals(Prerequisite.NETWORK_WIFI_RSSI))
			return Integer.toString(phoneUtils.getCurrentRssi());
		if(contextName.equals(Prerequisite.NETWORK_WIFI_SSID))
			return phoneUtils.getWifiCarrierName();
		if(contextName.equals(Prerequisite.NETWORK_WIFI_RSSID))
			return phoneUtils.getWifiBSSID();
		if(contextName.equals(Prerequisite.NETWORK_CELLULAR_RSSI))
			return Integer.toString(phoneUtils.getCurrentRssi());
		if(contextName.equals(Prerequisite.SCREEN_STATUS))
			return "null";
		if(contextName.equals(Prerequisite.CALL_STATUS))
			return phoneUtils.getCallState();
		if(contextName.equals(Prerequisite.RESULT_TYPE))
			return resultType;
		if(contextName.equals(Prerequisite.PING_AVGRTT))
			return Double.toString(pingRTT);
		if(contextName.equals(Prerequisite.LOCATION_COORDINATE)){
			Location location = phoneUtils.getLocation();
			if (location == null)
				return null;
			return "("+location.getLatitude()+","+location.getLongitude()+")";
		}
		if(contextName.equals(Prerequisite.LOCATION_ALTITUDE)){
			Location location = phoneUtils.getLocation();
			if (location == null)
				return null;
			return Double.toString(location.getAltitude());
		}
		if(contextName.equals(Prerequisite.LOCATION_LATITUDE)){
			Location location = phoneUtils.getLocation();
			if (location == null)
				return null;
			return Double.toString(location.getLatitude());
		}
		if(contextName.equals(Prerequisite.LOCATION_LONGITUDE)){
			Location location = phoneUtils.getLocation();
			if (location == null)
				return null;
			return Double.toString(location.getLongitude());
		}
		if(contextName.equals(Prerequisite.LOCATION_SPEED)){
			Location location = phoneUtils.getLocation();
			if (location == null)
				return null;
			return Double.toString(location.getSpeed());
		}
		if(contextName.equals(Prerequisite.ACTIVITY_TYPE))
			return getActivity();
		if(contextName.equals(Prerequisite.ACTIVITY_LASTTIME))
			return Long.toString(System.currentTimeMillis()-activityUpdateTime);
		return "";
	}

	/**
	 * The following part is related to change in network conditions
	 */

	public void updateCellularRssiContext(){
		recheckListenList(getListenList(Prerequisite.PRE_TYPE_NETWORK));
	}
	
	public void updateWifiRssiContext(){
		recheckListenList(getListenList(Prerequisite.PRE_TYPE_NETWORK));
	}

	public void updateNetworkTypeContext(){
		recheckListenList(getListenList(Prerequisite.PRE_TYPE_NETWORK));
	}

	/**
	 * This part is related to call state.
	 */
	public void updateCallStateContext(){
		recheckListenList(getListenList(Prerequisite.PRE_TYPE_CALL));
	}
	
	/**
	 * This part is related to locations.
	 */
	public void updateLocationContext(){
		recheckListenList(getListenList(Prerequisite.PRE_TYPE_LOCATION));
	}
	
	/***
	 *** The following part is related to measurement results.
	 ***/
	private String resultType ="";
	private double pingRTT= 0;

	//API for reporting measurement result to the context monitor
	public void updateMeasurementResultContext(MeasurementResult[] results){
		if (results.length>0){
			MeasurementResult result = results[0];
			resultType = result.getType();
			if (resultType.equals(PingTask.TYPE)){
				pingRTT = Double.parseDouble(result.getValues().get("mean_rtt_ms"));
			}
			recheckListenList(getListenList(Prerequisite.PRE_TYPE_RESULTS));
		}
	}

	/**
	 * The following part is related to activity detection
	 */
	
	@Override
	public void onConnected(Bundle connectionHint) {
		Intent intent = new Intent(PhoneUtils.getGlobalContext(), ActivityIntentService.class);
	    PendingIntent callbackIntent = PendingIntent.getService(PhoneUtils.getGlobalContext(), 0, intent,
	             PendingIntent.FLAG_UPDATE_CURRENT);
	    activityRecognitionClient.requestActivityUpdates(1000, callbackIntent);
		LocalBroadcastManager.getInstance(PhoneUtils.getGlobalContext()).registerReceiver(
				new BroadcastReceiver(){
					 @Override
					  public void onReceive(Context context, Intent intent) {
						 int activityType = intent.getIntExtra("activityType", DetectedActivity.UNKNOWN);
						 String newActivity = currentActivity;
						 if (activityType == DetectedActivity.IN_VEHICLE){
							 newActivity = "IN_VEHICLE";
						 }else if (activityType == DetectedActivity.ON_BICYCLE){
							 newActivity = "ON_BICYCLE";
						 }else if (activityType == DetectedActivity.ON_FOOT){
							 newActivity = "ON_FOOT";
						 }else if (activityType == DetectedActivity.STILL){
							 newActivity = "STILL";
						 }else if (activityType == DetectedActivity.TILTING){
							 newActivity = "TILTING";
						 }else{
							 newActivity = "UNKNOWN";
						 }
						 if (!newActivity.equals(currentActivity)){
							 updateActivityContext(newActivity);
						 }
					  }
				}, new IntentFilter(ActivityIntentService.ACTION_ACTIVITY_UPDATE));
	}

	public String getActivity(){
		return currentActivity;
	}
	
	public void updateActivityContext(String newActivity){
		Log.i("xsc",newActivity);
		currentActivity = newActivity;
		activityUpdateTime = System.currentTimeMillis();
		recheckListenList(getListenList(Prerequisite.PRE_TYPE_ACTIVITY));
	}
	
	@Override
	public void onDisconnected() {
		activityRecognitionClient = null;
	}

	@Override
	public void onConnectionFailed(ConnectionResult arg0) {
		activityRecognitionClient = null;
	}



//	/***
//	 *** The following part is related to walking detection.
//	 ***/
//
//	private SensorManager mSensorManager = null;
//	private Sensor mAccel = null;
//	private MovementListener movementListener = null;
//	
//	private boolean movementStatus = false;
//	private long stepCount = 0;
//	private float gravity = 9.8f;
//	private boolean isWalking = false;
//	private float temGravity = 9.8f;
//	private Deque<Accel> maxPoints = new ArrayDeque<Accel>();
//	private Deque<Accel> minPoints = new ArrayDeque<Accel>();
//	private Accel lastPoint = null;
//	private boolean upDir = true;
//	private float maxThrottle = gravity + 1;
//	private float minThrottle = gravity - 1;
//	private Queue<Accel> pendingData = new ConcurrentLinkedQueue<Accel>();
//	class Accel {
//		long timestamp;
//		float x, y, z;
//		float amplitude;
//
//		public Accel(long timestamp, float x, float y, float z) {
//			this.timestamp = timestamp;
//			this.x = x;
//			this.y = y;
//			this.z = z;
//			amplitude =  (float) Math.sqrt(x * x + y * y + z * z);
//		}
//
//		float getAmplitude() {
//			return amplitude;
//		}
//
//		@Override
//		public String toString() {
//			return timestamp + ": " + x + "\t" + y + "\t" + z;
//		}
//	};
//
//
//	
//	public void startWalkingDectection() {
//		mSensorManager = (SensorManager) PhoneUtils.getGlobalContext().getSystemService(Context.SENSOR_SERVICE);
//		mAccel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
//		if (mAccel != null){
//			movementListener = new MovementListener();
//			mSensorManager.registerListener(movementListener, mAccel, SensorManager.SENSOR_DELAY_NORMAL);
//		}
//	}
//
//	public void stopWalkingDectection(){
//		if (movementListener != null){
//			mSensorManager.unregisterListener(movementListener);
//			movementListener = null;
//		}
//	}
//	
//	public void updateMovingContext(){
//		recheckListenList(getListenList(Prerequisite.PRE_TYPE_MOVEMENT));
//	}
//	
//	private class MovementListener implements SensorEventListener {
//		@Override
//		public void onSensorChanged(SensorEvent event) {
//			if(processAccelData(event.timestamp, event.values[0], event.values[1], event.values[2])){
//				updateMovingContext();
//			}
//		}
//
//		@Override
//		public void onAccuracyChanged(Sensor sensor, int accuracy) {
//
//		}
//		
//		//process accelerometer, if the movement status changed, return true
//		private boolean processAccelData(long timeStamp, float x, float y, float z){
//			if(pendingData.size()<30){
//				pendingData.add(new Accel(timeStamp, x, y, z));
//			}else{
//				boolean lastMovementStatus = movementStatus;
//				long lastStepCount = stepCount;
//				Accel accel;
//				while((accel = pendingData.poll())!=null){
//					temGravity = 0.8f * temGravity + 0.2f * accel.amplitude;
//					maxThrottle = gravity + 1;
//					minThrottle = gravity - 1;
//
//					if (lastPoint != null) {
//						if (upDir) {
//							if (accel.getAmplitude() < lastPoint.getAmplitude()) {
//								upDir = false;
//								if (lastPoint.getAmplitude() >= maxThrottle) {
//									if (maxPoints.isEmpty()) {
//										maxPoints.addLast(lastPoint);
//									} else {
//										if (minPoints.isEmpty() || minPoints.getLast().timestamp < maxPoints.getLast().timestamp) {
//											if (maxPoints.getLast().amplitude < lastPoint.amplitude) {
//												maxPoints.pollLast();
//												maxPoints.addLast(lastPoint);
//											}
//										} else {
//											if (maxPoints.size() == 3) {
//												maxPoints.pollFirst();
//											}
//											maxPoints.addLast(lastPoint);
//										}
//									}
//								}
//							}
//						} else {
//							if (accel.getAmplitude() > lastPoint.getAmplitude()) {
//								upDir = true;
//								if (lastPoint.getAmplitude() <= minThrottle) {
//									if (minPoints.isEmpty()) {
//										minPoints.addLast(lastPoint);
//									} else {
//										if (maxPoints.isEmpty()
//												|| maxPoints.getLast().timestamp < minPoints.getLast().timestamp) {
//											if (minPoints.getLast().amplitude > lastPoint.amplitude) {
//												minPoints.pollLast();
//												minPoints.addLast(lastPoint);
//											}
//										} else {
//											if (minPoints.size() == 3) {
//												minPoints.pollFirst();
//											}
//											minPoints.addLast(lastPoint);
//										}
//									}
//								}
//							}
//						}
//					}
//
//					if (lastPoint == maxPoints.peekLast()
//							&& maxPoints.size() == 3
//							&& maxPoints.peekLast().timestamp
//							- maxPoints.peekFirst().timestamp <= 3000000000l
//							&& !isWalking)
//						isWalking = true;
//
//					if (isWalking
//							&& accel.timestamp - maxPoints.peekLast().timestamp >= 3000000000l)
//						isWalking = false;
//					if (isWalking && lastPoint == maxPoints.peekLast())
//						stepCount++;
//
//					if (!isWalking
//							&& maxPoints.peekLast() != null
//							&& (accel.timestamp - maxPoints.peekLast().timestamp) / 1000000l >= 8000l)
//						gravity = temGravity;
//
//					lastPoint = accel;
//					movementStatus = isWalking;
//				}			
//				Log.i("xsc","Current stepCount: "+Long.toString(stepCount));
//				return (lastMovementStatus != movementStatus || lastStepCount != stepCount);
//			}
//			return false;
//		}
//
//	}

	
	
}
