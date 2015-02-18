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
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

public class ContextMonitor {
	private static ContextMonitor singletonContextMonitor = null;
	private static PhoneUtils phoneUtils = null;
	private static HandlerThread contextMonitorThread = null;
	
	private Messenger schedulerMessenger = null;
	private Handler contextHandler = null;
	
	//The map between prerequisite type and its corresponding listen queue
	static private HashMap<String, LinkedList<MeasurementTask>> preTypeToListenListMap;
	static{
		preTypeToListenListMap= new HashMap<String, LinkedList<MeasurementTask>>();
		preTypeToListenListMap.put(Prerequisite.PRE_TYPE_MOVEMENT, new LinkedList<MeasurementTask>());
		preTypeToListenListMap.put(Prerequisite.PRE_TYPE_NETWORK, new LinkedList<MeasurementTask>());
		preTypeToListenListMap.put(Prerequisite.PRE_TYPE_RESULTS, new LinkedList<MeasurementTask>());
	}
	//All tasks registered at contextMonitor
	private volatile LinkedList<MeasurementTask> allTaskList = new LinkedList<MeasurementTask>();
	
	//The map between a task and the listen queue it registers
	private HashMap<String, LinkedList<LinkedList<MeasurementTask>>> taskToListenListsMap = new HashMap<String, LinkedList<LinkedList<MeasurementTask>>>();
	
	private ContextMonitor(){
		//start the context_monitor_thread if necessary
		if (contextMonitorThread==null){
			contextMonitorThread = new HandlerThread("context_monitor_thread");
			contextMonitorThread.start();
			Looper loop=contextMonitorThread.getLooper();
			contextHandler = new ContextHandler(contextMonitorThread.getLooper());
		}
	}
	
	//return the global contextMonitor
	public static synchronized ContextMonitor getContextMonitor() {
	    if (singletonContextMonitor == null) {
	    	singletonContextMonitor = new ContextMonitor();
	    	phoneUtils = PhoneUtils.getPhoneUtils();
	    }
	    return singletonContextMonitor;
	}
	
	//set the messager for communicating with MeasurementScheduler
	public void setSchedulerMessenger(Messenger msger){
		schedulerMessenger = msger;
	}
	
	//get the handler for this context monitor
	public Handler getContextHandler() {
		return contextHandler;
	}
	
	
	//API for registering a measurement task to the context monitor
	public void registerMeasurementTask(MeasurementTask task){
		if (contextHandler!=null){
			Bundle b = new Bundle();
			b.putString("type", "REGISTER_TASK");
			b.putParcelable(UpdateIntent.MEASUREMENT_TASK_PAYLOAD,task);
			Message msg = contextHandler.obtainMessage();
			msg.setData(b);
			msg.sendToTarget();
		}
	}

	//API for reporting measurement result to the context monitor
	public void updateMeasurementResultContext(Parcelable[] results){
		if (contextHandler!=null){
			Bundle b = new Bundle();
			b.putString("type","UPDATE_MEASUREMENT_RESULT");
			b.putParcelableArray("results", results);
			Message msg = contextHandler.obtainMessage();
			msg.setData(b);
			msg.sendToTarget();
		}
	}
	
	//get the corresponding listen queue for a specifc preName
	private LinkedList<MeasurementTask> getListenList(String preName){
		if(preTypeToListenListMap.containsKey(preName))
			return preTypeToListenListMap.get(preName);
		return null;
	}
	
	//unregister a task from context monitor
	private void doUnregisterTask(MeasurementTask task){
		if (taskToListenListsMap.containsKey(task)){
			for (LinkedList<MeasurementTask> listenList: taskToListenListsMap.get(task)){
				for (Iterator<MeasurementTask> iter = listenList.iterator(); iter.hasNext();){
					MeasurementTask taskI = iter.next();
					if(taskI==task) iter.remove();
				}
			}
		}
		if (allTaskList.contains(task))
			allTaskList.remove(task);
	}
	
	//register a task to context monitor
	private void doRegisterMeasurementTask(MeasurementTask measurementTask){
		if(measurementTask.isPrereqSatisied()){
			doSubmitTask(measurementTask);
			return;
		}
		allTaskList.add(measurementTask);
		Log.i("xsc","Register task to context: "+measurementTask);
		
		for (ArrayList<Prerequisite> preGroup: measurementTask.getPrerequisiteGroups()){
			for (Prerequisite pre: preGroup){
				if (pre.satisfy())continue;
				getListenList(pre.getType()).add(measurementTask);
				break;
			}
		}
		Log.i("xsc","New measurement registered: "+measurementTask);
		Log.i("xsc","Current registered task number: "+allTaskList.size());
	}
	
	//submit the task back to the measurementScheduler (when the prerequisites are satisfied)
	private void doSubmitTask(MeasurementTask task){
		doUnregisterTask(task);
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
	private void doRecheckListenList(LinkedList<MeasurementTask> listenList){
		for (Iterator<MeasurementTask> iter = listenList.iterator(); iter.hasNext();){
			MeasurementTask task = iter.next();
			if(task.isPrereqSatisied()) doSubmitTask(task);
		}
	}
	
	
	
	//get current context value for contextName
	public String getContext(String contextName){
		if(contextName.equals(Prerequisite.MOVE_COUNT))
			return Long.toString(stepCount);
		else if(contextName.equals(Prerequisite.MOVE_STATUS))
			return Boolean.toString(isWalking);
		else if(contextName.equals(Prerequisite.CELLULAR_RSSI))
			return Integer.toString(phoneUtils.getCurrentRssi());
		else if(contextName.equals(Prerequisite.WIFI_RSSI))
			return Integer.toString(phoneUtils.getWifiRSSI());
		else if(contextName.equals(Prerequisite.NETWORK_TYPE))
			return phoneUtils.getNetwork();
		else if(contextName.equals(Prerequisite.RESULT_TYPE))
			return resultType;
		else if(contextName.equals(Prerequisite.PING_AVGRTT))
			return Double.toString(pingRTT);
		return "";
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
			Log.i("xsc",msgType);
			if(msgType.equals(PhoneUtils.MOVEMENT_SENSOR_CHANGED)){
				if(processAccelData(b.getLong("time"),b.getFloat("x"),b.getFloat("y"),b.getFloat("z")))
					doRecheckListenList(getListenList(Prerequisite.PRE_TYPE_MOVEMENT));
				return;
			}
			
			if(msgType.equals(PhoneUtils.CELLULAR_RSSI_CHANGED)){
				doRecheckListenList(getListenList(Prerequisite.PRE_TYPE_NETWORK));
				Log.i("xsc","rssi changed "+phoneUtils.getCurrentRssi());
				return;
			}
			
			if(msgType.equals("UPDATE_MEASUREMENT_RESULT")){
				Parcelable[] parcels = b.getParcelableArray("results");
				MeasurementResult[] results = null;
				if ( parcels != null ) {
			          results = new MeasurementResult[parcels.length];
			          for ( int i = 0; i < results.length; i++ ) {
			            results[i] = (MeasurementResult) parcels[i];
			            Log.i("xsc","Measurement Result: "+results[i]);
			          }
				}
				if (results.length>0){
					MeasurementResult result = results[0];
					resultType = result.getType();
					if (resultType.equals(PingTask.TYPE)){
						pingRTT = Double.parseDouble(result.getValues().get("mean_rtt_ms"));
					}
	
					doRecheckListenList(getListenList(Prerequisite.PRE_TYPE_RESULTS));
				}
				return;
			}
			
			if(msgType.equals("REGISTER_TASK")){
				MeasurementTask measurementTask = (MeasurementTask)
				          b.getParcelable(UpdateIntent.MEASUREMENT_TASK_PAYLOAD);
				registerMeasurementTask(measurementTask);
				return;
			}
		}
	}





	/***
	*** The following part is related to measurement results.
	***/
	private String resultType ="";
	private double pingRTT= 0;
	
	
	
	
	
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
	
	//process accelerometer, if the movement status changed, return true
	private boolean processAccelData(long timeStamp, float x, float y, float z){
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
			Log.i("xsc","Current stepCount: "+Long.toString(stepCount));
			return (lastMovementStatus != movementStatus || lastStepCount != stepCount);
		}
		return false;
	}
	
}
