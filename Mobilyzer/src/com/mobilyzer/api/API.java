/* Copyright 2013 RobustNet Lab, University of Michigan. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mobilyzer.api;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.Bundle;

import com.mobilyzer.Config;
import com.mobilyzer.MeasurementScheduler.DataUsageProfile;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.UpdateIntent;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.measurements.DnsLookupTask;
import com.mobilyzer.measurements.HttpTask;
import com.mobilyzer.measurements.PageLoadTimeTask;
import com.mobilyzer.measurements.PageLoadTimeTask.PageLoadTimeDesc;
import com.mobilyzer.measurements.ParallelTask;
import com.mobilyzer.measurements.PingTask;
import com.mobilyzer.measurements.SequentialTask;
import com.mobilyzer.measurements.TCPThroughputTask;
import com.mobilyzer.measurements.TracerouteTask;
import com.mobilyzer.measurements.UDPBurstTask;
import com.mobilyzer.measurements.DnsLookupTask.DnsLookupDesc;
import com.mobilyzer.measurements.HttpTask.HttpDesc;
import com.mobilyzer.measurements.ParallelTask.ParallelDesc;
import com.mobilyzer.measurements.PingTask.PingDesc;
import com.mobilyzer.measurements.SequentialTask.SequentialDesc;
import com.mobilyzer.measurements.TCPThroughputTask.TCPThroughputDesc;
import com.mobilyzer.measurements.TracerouteTask.TracerouteDesc;
import com.mobilyzer.measurements.UDPBurstTask.UDPBurstDesc;
import com.mobilyzer.util.Logger;

/**
 * @author jackjia,Hongyi Yao (hyyao@umich.edu)
 * The user API for Mobiperf library.
 * Use singleton design pattern to ensure there only exist one instance of API
 * User: create and add task => Scheduler: run task, send finish intent =>
 * User: register BroadcastReceiver for userResultAction and serverResultAction
 */
public final class API {
  public enum TaskType {
    DNSLOOKUP, HTTP, PING, TRACEROUTE, TCPTHROUGHPUT, UDPBURST,
    PARALLEL, SEQUENTIAL, INVALID, PLT
  }

  /**
   * Action name of different type of result for broadcast receiver.
   * userResultAction is not a constant value. We append the clientKey to 
   * UpdateIntent.USER_RESULT_ACTION so that only the user who submit the task
   * can get the result   
   */
  public String userResultAction;
  public static final String SERVER_RESULT_ACTION =
      UpdateIntent.SERVER_RESULT_ACTION;
  public String batteryThresholdAction;
  public String checkinIntervalAction;
  public String taskStatusAction;
  public String dataUsageAction;
  public String authAccountAction;
  
  public final static int USER_PRIORITY = MeasurementTask.USER_PRIORITY;
  public final static int INVALID_PRIORITY = MeasurementTask.INVALID_PRIORITY;
  
  private Context applicationContext;
  
  private boolean isBound = false;
  private boolean isBindingToService = false;
  Messenger mSchedulerMessenger = null;
  
  private String clientKey;
  
  /**
   * Singleton api object for the entire application
   */
  private static API apiObject;
  private Queue<Message> pendingMsg;
  /**
   * Make constructor private for singleton design
   * @param parent Context when the object is created
   * @param clientKey User-defined unique key for this application
   */
  private API(Context parent, String clientKey) {
    Logger.d("API: constructor is called...");
    this.applicationContext = parent.getApplicationContext();
    this.clientKey = clientKey;
    this.pendingMsg = new LinkedList<Message>();

    this.userResultAction = UpdateIntent.USER_RESULT_ACTION + "." + clientKey;
    
    this.batteryThresholdAction = UpdateIntent.BATTERY_THRESHOLD_ACTION + "."
        + clientKey;
    this.checkinIntervalAction = UpdateIntent.CHECKIN_INTERVAL_ACTION + "."
        + clientKey;
    this.taskStatusAction = UpdateIntent.TASK_STATUS_ACTION + "." + clientKey;
    this.dataUsageAction = UpdateIntent.DATA_USAGE_ACTION + "." + clientKey;
    this.authAccountAction = UpdateIntent.AUTH_ACCOUNT_ACTION + "." + clientKey;
    startAndBindService();
  }

  /**
   * Actual method to get the singleton API object
   * @param parent Context which the object lies in
   * @param clientKey User-defined unique key for this application
   * @return Singleton API object
   */
  public static API getAPI(Context parent, String clientKey) {
    Logger.d("API: Get API Singeton object...");
    if ( apiObject == null ) {
      Logger.d("API: API object not initialized...");
      apiObject = new API(parent, clientKey);
    }
    else {
      // Safeguard to avoid using unbound API object 
      apiObject.startAndBindService();
    }
    return apiObject;
  }
  
  @Override
  public Object clone() throws CloneNotSupportedException {
    // Prevent the singleton object to be copied
    throw new CloneNotSupportedException();
  }
  
  
  /** Defines callbacks for binding and unbinding scheduler*/
  private ServiceConnection serviceConn = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
      Logger.d("API -> onServiceConnected called");
      // We've bound to the scheduler's messenger and get Messenger instance
      mSchedulerMessenger = new Messenger(service);
      isBound = true;
      isBindingToService = false;
      
      Logger.i("Register client key");
      Message msg = Message.obtain(null, Config.MSG_REGISTER_CLIENTKEY);
      Bundle data = new Bundle();
      data.putString(UpdateIntent.CLIENTKEY_PAYLOAD, clientKey);
      msg.setData(data);
      try {
        sendMessage(msg);
      } catch (MeasurementError e) {
        Logger.e("Register clientKey failed", e);
      }
      
      Logger.i("Send pending message");
      while ( (msg = pendingMsg.poll()) != null ) {
        try {
          sendMessage(msg);
        } catch (MeasurementError e) {
          Logger.e("Send pending message failed", e);
        }
      }
    }
    
    @Override
    public void onServiceDisconnected(ComponentName arg0) {
      // This callback is never called until the active scheduler is uninstalled
      Logger.d("API -> onServiceDisconnected called");
      mSchedulerMessenger = null;
      isBound = false;
      // Start and bind to another scheduler (probably bind to the one in itself)
      Logger.e("API -> startAndBind again");
      startAndBindService();
    }
  };
  
  /**
   * Bind to scheduler, automatically called when the API is initialized
   */
  public void startAndBindService() {
    Logger.e("API-> startAndBindService() called "+isBindingToService+" "+isBound);
    if (!isBindingToService && !isBound) {
      Logger.e("API-> bind() called 2");
      // Bind to the scheduler service if it is not bounded
      Intent intent = new Intent("com.mobilyzer.MeasurementScheduler");
      intent.putExtra(UpdateIntent.CLIENTKEY_PAYLOAD, clientKey);
      intent.putExtra(UpdateIntent.VERSION_PAYLOAD, Config.version);
      /**
       * Start and bind to service if it is not bounded.
       * Notice that we don't use BIND_AUTO_CREATE flag since it will prevent
       * the scheduler to kill itself when stopSelf is called
       */
      applicationContext.startService(intent);
      applicationContext.bindService(intent, serviceConn, 0);
      isBindingToService = true;
    }
  }
  
  /**
   * Unbind from scheduler, called in activity's onDestroy callback function
   */
  public void unbind() {
    Logger.e("API-> unbind called");
    if (isBound) {
      Logger.e("API-> unbind called 2");
      // Register client key
      Message msg = Message.obtain(null, Config.MSG_UNREGISTER_CLIENTKEY);
      Bundle data = new Bundle();
      data.putString(UpdateIntent.CLIENTKEY_PAYLOAD, clientKey);
      msg.setData(data);
      try {
        sendMessage(msg);
      } catch (MeasurementError e) {
        Logger.e("Unregister clientKey failed", e);
      }
      // Unbind service
      applicationContext.unbindService(serviceConn);
      isBound = false;
    }
  }

  /**
   * Create a new MeasurementTask based on those parameters. Then submit it to
   * scheduler by addTask or put into task list of parallel or sequential task
   * @param taskType Type of measurement (ping, dns, traceroute, etc.) for this
   *        measurement task.
   * @param startTime Earliest time that measurements can be taken using this 
   *        Task descriptor. The current time will be used in place of a null
   *        startTime parameter. Measurements with a startTime more than 24 
   *        hours from now will NOT be run.
   * @param endTime Latest time that measurements can be taken using this Task
   *        descriptor. Tasks with an endTime before startTime will be canceled.
   *        Corresponding to the 24-hour rule in startTime, tasks with endTime
   *        later than 24 hours from now will be assigned a new endTime that
   *        ends 24 hours from now.
   * @param intervalSec Minimum number of seconds to elapse between consecutive
   *        measurements taken with this description.
   * @param count Maximum number of times that a measurement should be taken
   *        with this description. A count of 0 means to continue the 
   *        measurement indefinitely (until end_time).
   * @param priority Two level of priority: USER_PRIORITY for user task and
   *        INVALID_PRIORITY for server task
   * @param contextIntervalSec interval between the context collection (in sec)
   * @param params Measurement parameters.
   * @return Measurement task filled with those parameters
   * @throws MeasurementError taskType is not valid
   */
  public MeasurementTask createTask( TaskType taskType, Date startTime
    , Date endTime, double intervalSec, long count, long priority
    , int contextIntervalSec, Map<String, String> params)
        throws MeasurementError {
    MeasurementTask task = null;    
    switch ( taskType ) {
      case DNSLOOKUP:
        task = new DnsLookupTask(new DnsLookupDesc(clientKey, startTime, endTime
          , intervalSec, count, priority, contextIntervalSec, params));
        break;
      case HTTP:
        task = new HttpTask(new HttpDesc(clientKey, startTime, endTime
          , intervalSec, count, priority, contextIntervalSec, params));
        break;
      case PING:
        task = new PingTask(new PingDesc(clientKey, startTime, endTime
          , intervalSec, count, priority, contextIntervalSec, params));
        break;
      case TRACEROUTE:
        task = new TracerouteTask(new TracerouteDesc(clientKey, startTime, endTime
          , intervalSec, count, priority, contextIntervalSec, params));
        break;
      case TCPTHROUGHPUT:
        task = new TCPThroughputTask(new TCPThroughputDesc(clientKey, startTime
          , endTime, intervalSec, count, priority, contextIntervalSec, params));
        break;
      case UDPBURST:
        task = new UDPBurstTask(new UDPBurstDesc(clientKey, startTime, endTime
          , intervalSec, count, priority, contextIntervalSec, params));
        break;
//      case PLT:
//        task = new PageLoadTimeTask(new PageLoadTimeDesc(clientKey, startTime, endTime
//          , intervalSec, count, priority, contextIntervalSec, params));
//        break;
      default:
        throw new MeasurementError("Undefined measurement type. Candidate: " +
            "DNSLOOKUP, HTTP, PING, TRACEROUTE, TCPTHROUGHPUT, UDPBURST");
    }
    return task;
  }

  /**
   * Create a parallel or sequential task based on the manner. An ArrayList of
   * MeasurementTask must be provided as the real tasks to be executed
   * @param manner Determine whether tasks in task list will be executed
   *        parallelly or sequentially (back-to-back)
   * @param startTime Earliest time that measurements can be taken using this 
   *        Task descriptor. The current time will be used in place of a null
   *        startTime parameter. Measurements with a startTime more than 24 
   *        hours from now will NOT be run.
   * @param endTime Latest time that measurements can be taken using this Task
   *        descriptor. Tasks with an endTime before startTime will be canceled.
   *        Corresponding to the 24-hour rule in startTime, tasks with endTime
   *        later than 24 hours from now will be assigned a new endTime that
   *        ends 24 hours from now.
   * @param intervalSec Minimum number of seconds to elapse between consecutive
   *        measurements taken with this description.
   * @param count Maximum number of times that a measurement should be taken
   *        with this description. A count of 0 means to continue the 
   *        measurement indefinitely (until end_time).
   * @param priority Two level of priority: USER_PRIORITY for user task and
   *        INVALID_PRIORITY for server task
   * @param contextIntervalSec interval between the context collection (in sec)
   * @param params Measurement parameters.
   * @param taskList tasks to be executed 
   * @return The parallel or sequential task filled with those parameters
   * @throws MeasurementError manner is not valid
   */
  public MeasurementTask composeTasks(TaskType manner, Date startTime,
    Date endTime, double intervalSec, long count, long priority,
    int contextIntervalSec, Map<String, String> params,
    ArrayList<MeasurementTask> taskList) throws MeasurementError {
    MeasurementTask task = null;
    switch ( manner ) {
      case PARALLEL:
        task = new ParallelTask(new ParallelDesc(clientKey, startTime, endTime
          , intervalSec, count, priority, contextIntervalSec, params), taskList);
        break;
      case SEQUENTIAL:
        task = new SequentialTask(new SequentialDesc(clientKey, startTime, endTime
          , intervalSec, count, priority, contextIntervalSec, params), taskList);
        break;
      default:
        throw new MeasurementError("Undefined measurement composing type. " + 
            " Candidate: PARALLEL, SEQUENTIAL");
    }
    return task;
  }

  /**
   * Get available messenger after binding to scheduler
   * @return the messenger if bound, null otherwise
   */
  private Messenger getScheduler() {
    if (isBound) {
      Logger.e("API -> get available messenger");
      return mSchedulerMessenger;
    } else {
      Logger.e("API -> have not bound to a scheduler!");
      return null;
    }
  }
  
  /**
   * Helper method for sending messages to the scheduler
   * @param msg message to be sent
   * @throws MeasurementError
   */
  private void sendMessage(Message msg) throws MeasurementError {
    Messenger messenger = getScheduler();
    if ( messenger != null ) {
      // Append client key to every msg sent from API
      Bundle data = msg.getData();
      if (data == null) {
        data = new Bundle();
        msg.setData(data);
      }
      data.putString(UpdateIntent.CLIENTKEY_PAYLOAD, clientKey);
      
      try {
        messenger.send(msg);
      } catch (RemoteException e) {
        String err = "remote scheduler failed!";
        Logger.e(err);
        throw new MeasurementError(err);
      }   
    }
    else {
      String err = "API didn't bind to a scheduler. Message will be temporarily" +
      		" queued and sent after scheduler bound";
      Logger.e(err);
      this.pendingMsg.offer(msg);
    }
  }
 
  /**
   * Submit task to the scheduler.
   * Works in async way. The result will be returned in a intent whose action is
   * USER_RESULT_ACTION + clientKey or SERVER_RESULT_ACTION
   * @param task the task to be exectued, created by createTask(..)
   *        or composeTask(..)
   * @throws MeasurementError
   */
  public void submitTask ( MeasurementTask task )
      throws MeasurementError {
    Logger.d("API->submitTask called");
    if ( task != null ) {
//      // Hongyi: for delay measurement
//      task.getDescription().parameters.put("ts_api_send",
//        String.valueOf(System.currentTimeMillis()));

      Logger.i("API: Adding new " + task.getType() + " task " + task.getTaskId());
      Message msg = Message.obtain(null, Config.MSG_SUBMIT_TASK);
      Bundle data = new Bundle();
      data.putParcelable(UpdateIntent.MEASUREMENT_TASK_PAYLOAD, task);
      msg.setData(data);  
      sendMessage(msg);
    }
    else {
      String err = "submitTask: task is null";
      Logger.e(err);
      throw new MeasurementError(err);
    }
  }

  /**
   * Cancel the task submitted to the scheduler
   * @param localId task to be cancelled. Got by MeasurementTask.getTaskId() 
   * @return true for succeed, false for fail
   * @throws InvalidParameterException
   */
  public void cancelTask(String taskId) throws MeasurementError{
    Logger.d("API->cancelTask called");
    if ( taskId != null ) {
      Message msg = Message.obtain(null, Config.MSG_CANCEL_TASK);
      Bundle data = new Bundle();
      Logger.i("API: try to cancel task " + taskId);
      data.putString(UpdateIntent.TASKID_PAYLOAD, taskId);
      msg.setData(data);  
      sendMessage(msg);
    }
    else {
      String err = "cancelTask: taskId is null";
      Logger.e(err);
      throw new MeasurementError(err);
    }
  }
  
  /**
   * Set authenticate account for uploading results. Anonymous by default 
   * @param account
   * @throws MeasurementError
   */
  public void setAuthenticateAccount(String account) throws MeasurementError {
    Logger.d("API->setAuthenticateAccount called");
    Message msg = Message.obtain(null, Config.MSG_SET_AUTH_ACCOUNT);
    Bundle data = new Bundle();
    data.putString(UpdateIntent.AUTH_ACCOUNT_PAYLOAD, account);
    msg.setData(data);
    sendMessage(msg);
  }
  
  /**
   * Get current authenticate account used by scheduler
   * @throws MeasurementError
   */
  public void getAuthenticateAccount() throws MeasurementError {
    Logger.d("API->getAuthenticateAccount called");
    Message msg = Message.obtain(null, Config.MSG_GET_AUTH_ACCOUNT);
    sendMessage(msg);
  }
  /**
   * Set battery threshold of the scheduler. Only a threshold larger than the
   * current one will be accepted. 
   * @param threshold new battery threshold, must stay between 0 and 100
   * @throws MeasurementError
   */
  public void setBatteryThreshold(int threshold) throws MeasurementError {
    Logger.d("API->setBatteryThreshold called");
    if ( threshold > 100 || threshold <= 0 ) {
      String err = "Battery threshold should stay between 0 and 100";
      Logger.e(err);
      throw new MeasurementError(err);
    }
    Message msg = Message.obtain(null, Config.MSG_SET_BATTERY_THRESHOLD);
    Bundle data = new Bundle();
    data.putInt(UpdateIntent.BATTERY_THRESHOLD_PAYLOAD, threshold);
    msg.setData(data);
    sendMessage(msg);
  }
  
  /**
   * Get current battery threshold of the scheduler.
   * Async call. Receive api.batteryThresholdAction to get the result 
   * @throws MeasurementError
   */
  public void getBatteryThreshold() throws MeasurementError {
    Logger.d("API->getBatteryThreshold called");
    Message msg = Message.obtain(null, Config.MSG_GET_BATTERY_THRESHOLD);
    sendMessage(msg);
  }
  
  /**
   * Set checkin interval of the scheduler. Only an interval larger than the
   * current one will be accepted. 
   * @param interval new checkin interval, should be greater than min interval
   * @throws MeasurementError
   */
  public void setCheckinInterval(long interval) throws MeasurementError {
    Logger.d("API->setCheckinInterval called");
    if ( interval < Config.MIN_CHECKIN_INTERVAL_SEC ) {
      String err = "Checkin interval should be greater than "
          + Config.MIN_CHECKIN_INTERVAL_SEC;
      Logger.e(err);
      throw new MeasurementError(err);
    }
    Message msg = Message.obtain(null, Config.MSG_SET_CHECKIN_INTERVAL);
    Bundle data = new Bundle();
    data.putLong(UpdateIntent.CHECKIN_INTERVAL_PAYLOAD, interval);
    msg.setData(data);
    sendMessage(msg);
  }
  
  /**
   * Get current checkin interval of the scheduler.
   * Async call. Receive api.checkinIntervalAction to get the result 
   * @throws MeasurementError
   */
  public void getCheckinInterval() throws MeasurementError {
    Logger.d("API->getCheckinInterval called");
    Message msg = Message.obtain(null, Config.MSG_GET_CHECKIN_INTERVAL);
    sendMessage(msg);
  }
  
  /**
   * Get current status of that task.
   * Async call. Receive api.taskStatusAction to get the result 
   * @param taskId the id of the target task
   * @throws MeasurementError
   */
  public void getTaskStatus(String taskId) throws MeasurementError {
    Message msg = Message.obtain(null, Config.MSG_GET_TASK_STATUS);
    Bundle data = new Bundle();
    data.putString(UpdateIntent.TASKID_PAYLOAD, taskId);
    msg.setData(data);
    Logger.d("Attempt getting task status");
    sendMessage(msg);
  }

  /**
   * Set data usage profile of the scheduler. Only an profile more conventional
   * than the current one will be accepted. 
   * @param profile new data usage profile
   * @throws MeasurementError
   */
  public void setDataUsage(DataUsageProfile profile) throws MeasurementError {
    if ( profile == DataUsageProfile.NOTASSIGNED ) {
      String err = "Data usage profile should be valid";
      Logger.e(err);
      throw new MeasurementError(err);
    }
    Message msg = Message.obtain(null, Config.MSG_SET_DATA_USAGE);
    Bundle data = new Bundle();
    data.putSerializable(UpdateIntent.DATA_USAGE_PAYLOAD, profile);
    msg.setData(data);
    Logger.d("Attempt setting data usage to " + profile);
    sendMessage(msg);
    
  }
  
  /**
   * Get current data usage of the scheduler.
   * Async call. Receive api.dataUsageAction to get the result 
   * @throws MeasurementError
   */
  public void getDataUsage() throws MeasurementError {
    Message msg = Message.obtain(null, Config.MSG_GET_DATA_USAGE);
    Logger.d("Attempt getting data usage");
    sendMessage(msg);
  }
  
  /** Gets the currently available measurement descriptions*/
  public static Set<String> getMeasurementNames() {
    return MeasurementTask.getMeasurementNames();
  }
  
  /** Get the type of a measurement based on its name. Type is for JSON interface only
   * where as measurement name is a readable string for the UI */
  public static String getTypeForMeasurementName(String name) {
    return MeasurementTask.getTypeForMeasurementName(name);
  }
}
