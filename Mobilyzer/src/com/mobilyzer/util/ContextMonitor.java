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

import com.mobilyzer.Config;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementScheduler;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.UpdateIntent;
import com.mobilyzer.api.API;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.measurements.PingTask;
import com.mobilyzer.prerequisite.Prerequisite;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

public class ContextMonitor {
	private static ContextMonitor singletonContextMonitor = null;
	private static Messenger schedulerMessenger = null;
	private static Handler contextHandler = null;
	private static PhoneUtils phoneUtils = null;
	private ContextMonitor(){
	}
	
	public static synchronized ContextMonitor getContextMonitor() {
	    if (singletonContextMonitor == null) {
	    	singletonContextMonitor = new ContextMonitor();
	    	phoneUtils = PhoneUtils.getPhoneUtils();
	    }
	    return singletonContextMonitor;
	}
	
	public void setSchedulerMessenger(Messenger msger){
		schedulerMessenger = msger;
	}
	
	public void setContextHandler(Handler handler) {
		contextHandler = handler;
	}
	
	private String resultType ="";
	private double pingRTT= 0;
	public String getContext(String contextName){
		if(contextName.equals(Prerequisite.MOVE_COUNT))
			return Long.toString(stepCount);
		else if(contextName.equals(Prerequisite.MOVE_STATUS))
			return Boolean.toString(isWalking);
		else if(contextName.equals(Prerequisite.CELLULAR_RSSI))
			return Integer.toString(phoneUtils.getCurrentRssi());
		else if(contextName.equals(Prerequisite.NETWORK_TYPE))
			return phoneUtils.getNetwork();
		else if(contextName.equals(Prerequisite.RESULT_TYPE))
			return resultType;
		else if(contextName.equals(Prerequisite.PING_AVGRTT))
			return Double.toString(pingRTT);
		return "";
	}
	
	static private HashMap<String, LinkedList<MeasurementTask>> preNameToListenListMap;
	static{
		preNameToListenListMap= new HashMap<String, LinkedList<MeasurementTask>>();
		preNameToListenListMap.put(Prerequisite.MOVE_STATUS, new LinkedList<MeasurementTask>());
		preNameToListenListMap.put(Prerequisite.MOVE_COUNT, new LinkedList<MeasurementTask>());
		preNameToListenListMap.put(Prerequisite.CELLULAR_RSSI, new LinkedList<MeasurementTask>());
		preNameToListenListMap.put(Prerequisite.NETWORK_TYPE, new LinkedList<MeasurementTask>());
		preNameToListenListMap.put(Prerequisite.RESULT_TYPE, new LinkedList<MeasurementTask>());
		preNameToListenListMap.put(Prerequisite.PING_AVGRTT, new LinkedList<MeasurementTask>());
	}
	private volatile LinkedList<MeasurementTask> allTaskList = new LinkedList<MeasurementTask>();
	
	private LinkedList<MeasurementTask> getListenList(String preName){
		if(preNameToListenListMap.containsKey(preName))
			return preNameToListenListMap.get(preName);
		return null;
	}
	
	private void submitTask(MeasurementTask task){
		allTaskList.remove(task);
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
	
	
	public void registerMeasurementTask(MeasurementTask task){
		Bundle b = new Bundle();
		b.putString("type", "REGISTER_TASK");
		b.putParcelable(UpdateIntent.MEASUREMENT_TASK_PAYLOAD,task);
		Message msg = contextHandler.obtainMessage();
		msg.setData(b);
		msg.sendToTarget();
	}
	
	public void updateMeasurementResultContext(Parcelable[] results){
		Bundle b = new Bundle();
		b.putString("type","UPDATE_MEASUREMENT_RESULT");
		b.putParcelableArray("results", results);
		Message msg = contextHandler.obtainMessage();
		msg.setData(b);
		msg.sendToTarget();
	}
	
	private void updateMeasurementResultContext(Bundle b){
		Parcelable[] parcels = b.getParcelableArray("results");
		MeasurementResult[] results = null;
		if ( parcels != null ) {
	          results = new MeasurementResult[parcels.length];
	          for ( int i = 0; i < results.length; i++ ) {
	            results[i] = (MeasurementResult) parcels[i];
	            Log.i("xsc","Measurement Result: "+results[i]);
	          }
		}
		MeasurementResult result = results[0];
		resultType = result.getType();
		onContextChanged(Prerequisite.RESULT_TYPE);
		if (resultType.equals(PingTask.TYPE)){
			pingRTT = Double.parseDouble(result.getValues().get("mean_rtt_ms"));
			onContextChanged(Prerequisite.PING_AVGRTT);
		}
	}
	
	private void registerMeasurementTask(Bundle b){
		MeasurementTask measurementTask = (MeasurementTask)
		          b.getParcelable(UpdateIntent.MEASUREMENT_TASK_PAYLOAD);
		allTaskList.add(measurementTask);
		Log.i("xsc","Register task to context: "+measurementTask);
		if(measurementTask.isPrereqSatisied()) submitTask(measurementTask);
		for (Prerequisite pre: measurementTask.getPrerequisites()){
			if (pre.satisfy())continue;
			getListenList(pre.getName()).add(measurementTask);
			break;
		}
		Log.i("xsc","New measurement registered: "+measurementTask);
		Log.i("xsc","Current registered task number: "+allTaskList.size());
	}
	
	private void onContextChanged(String preName){
		LinkedList<MeasurementTask> listenList = getListenList(preName);
		for (Iterator<MeasurementTask> iter = listenList.iterator(); iter.hasNext();){
			MeasurementTask task = iter.next();
			boolean isSatisfied = true;
			for (Prerequisite pre: task.getPrerequisites()){
				Log.i("xsc","Check pre: "+pre);
				if(pre.satisfy())continue;
				isSatisfied = false;
				if(!pre.getName().equals(preName)){
					iter.remove();
					getListenList(pre.getName()).add(task);
					break;
				}
			}
			if (isSatisfied){
				iter.remove();
				submitTask(task);
			}
		}
	}
	
	public class ContextHandler extends Handler{
		public ContextHandler(){
			
		}
		public ContextHandler(Looper looper){
			super(looper);
		}
		
		@Override
		public void handleMessage(Message msg){
			Bundle b = msg.getData();
			String msgType = b.getString("type");
			if(msgType.equals(PhoneUtils.MOVEMENT_SENSOR_CHANGED)){
				processAccelData(b.getLong("time"),b.getFloat("x"),b.getFloat("y"),b.getFloat("z"));
			}else if(msgType.equals(PhoneUtils.CELLULAR_RSSI_CHANGED)){
				onContextChanged(Prerequisite.CELLULAR_RSSI);
				Log.i("xsc","rssi changed "+phoneUtils.getCurrentRssi());
			}else if(msgType.equals("UPDATE_MEASUREMENT_RESULT")){
				updateMeasurementResultContext(b);
			}else if(msgType.equals("REGISTER_TASK")){
				registerMeasurementTask(b);
			}
		}
	}
	
	
	
	//for walking detection
	private boolean movementStatus = false;
	private long stepCount = 0;
	private float gravity = 9.8f;
	private boolean isWalking = false;
	private float temGravity = 9.8f;
	private Deque<Accel> maxPoints = new ArrayDeque<Accel>();
	private Deque<Accel> minPoints = new ArrayDeque<Accel>();
	private Accel lastPoint = null;
	private boolean upDir = true;
	private float maxThrottle = gravity + 1;
	private float minThrottle = gravity - 1;
	private Queue<Accel> pendingData = new ConcurrentLinkedQueue<Accel>();
	class Accel {
		long timestamp;
		float x, y, z;
		float amplitude;

		public Accel(long timestamp, float x, float y, float z) {
			this.timestamp = timestamp;
			this.x = x;
			this.y = y;
			this.z = z;
			amplitude =  (float) Math.sqrt(x * x + y * y + z * z);
		}

		float getAmplitude() {
			return amplitude;
		}

		@Override
		public String toString() {
			return timestamp + ": " + x + "\t" + y + "\t" + z;
		}
	};
	
	private void processAccelData(long timeStamp, float x, float y, float z){
		if(pendingData.size()<30){
			pendingData.add(new Accel(timeStamp, x, y, z));
		}else{
			boolean lastMovementStatus = movementStatus;
			long lastStepCount = stepCount;
			Accel accel;
			while((accel = pendingData.poll())!=null){
				temGravity = 0.8f * temGravity + 0.2f * accel.amplitude;
				maxThrottle = gravity + 1;
				minThrottle = gravity - 1;
				
				if (lastPoint != null) {
					if (upDir) {
						if (accel.getAmplitude() < lastPoint.getAmplitude()) {
							upDir = false;
							if (lastPoint.getAmplitude() >= maxThrottle) {
								if (maxPoints.isEmpty()) {
									maxPoints.addLast(lastPoint);
								} else {
									if (minPoints.isEmpty() || minPoints.getLast().timestamp < maxPoints.getLast().timestamp) {
										if (maxPoints.getLast().amplitude < lastPoint.amplitude) {
											maxPoints.pollLast();
											maxPoints.addLast(lastPoint);
										}
									} else {
										if (maxPoints.size() == 3) {
											maxPoints.pollFirst();
										}
										maxPoints.addLast(lastPoint);
									}
								}
							}
						}
					} else {
						if (accel.getAmplitude() > lastPoint.getAmplitude()) {
							upDir = true;
							if (lastPoint.getAmplitude() <= minThrottle) {
								if (minPoints.isEmpty()) {
									minPoints.addLast(lastPoint);
								} else {
									if (maxPoints.isEmpty()
											|| maxPoints.getLast().timestamp < minPoints.getLast().timestamp) {
										if (minPoints.getLast().amplitude > lastPoint.amplitude) {
											minPoints.pollLast();
											minPoints.addLast(lastPoint);
										}
									} else {
										if (minPoints.size() == 3) {
											minPoints.pollFirst();
										}
										minPoints.addLast(lastPoint);
									}
								}
							}
						}
					}
				}

				if (lastPoint == maxPoints.peekLast()
						&& maxPoints.size() == 3
						&& maxPoints.peekLast().timestamp
								- maxPoints.peekFirst().timestamp <= 3000000000l
						&& !isWalking)
					isWalking = true;

				if (isWalking
						&& accel.timestamp - maxPoints.peekLast().timestamp >= 3000000000l)
					isWalking = false;
				if (isWalking && lastPoint == maxPoints.peekLast())
					stepCount++;

				if (!isWalking
						&& maxPoints.peekLast() != null
						&& (accel.timestamp - maxPoints.peekLast().timestamp) / 1000000l >= 8000l)
					gravity = temGravity;

				lastPoint = accel;
				movementStatus = isWalking;
			}
			if (lastMovementStatus != movementStatus)
				onContextChanged("movement.status");
			if (lastStepCount != stepCount)
				onContextChanged("movement.count");
			Log.i("xsc","Current stepCount: "+Long.toString(stepCount));
		}
	}
	
}
