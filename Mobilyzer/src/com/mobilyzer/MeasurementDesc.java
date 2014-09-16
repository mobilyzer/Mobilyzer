package com.mobilyzer;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * MeasurementDesc and all its subclasses are POJO classes that encode a measurement and enable easy
 * (de)serialization. On the other hand {@link MeasurementTask} contains runtime specific
 * information for task execution.
 * 
 * @see MeasurementTask
 */
public abstract class MeasurementDesc implements Parcelable {

  public String type;
  public String key;
  public Date startTime;
  public Date endTime;
  public double intervalSec;
  public long count;
  public long priority;
  public Map<String, String> parameters;
  public int contextIntervalSec;

  /**
   * @param type Type of measurement (ping, dns, traceroute, etc.) that should execute this
   *        measurement task.
   * @param startTime Earliest time that measurements can be taken using this Task descriptor. The
   *        current time will be used in place of a null startTime parameter. Measurements with a
   *        startTime more than 24 hours from now will NOT be run.
   * @param endTime Latest time that measurements can be taken using this Task descriptor. Tasks
   *        with an endTime before startTime will be canceled. Corresponding to the 24-hour rule in
   *        startTime, tasks with endTime later than 24 hours from now will be assigned a new
   *        endTime that ends 24 hours from now.
   * @param intervalSec Minimum number of seconds to elapse between consecutive measurements taken
   *        with this description.
   * @param count Maximum number of times that a measurement should be taken with this description.
   *        A count of 0 means to continue the measurement indefinitely (until end_time).
   * @param priority Larger values represent higher priorities.
   * @param contextIntervalSec interval between the context collection
   * @param params Measurement parameters.
   */
  protected MeasurementDesc(String type, String key, Date startTime, Date endTime,
      double intervalSec, long count, long priority, int contextIntervalSec,
      Map<String, String> params) {
    super();
    this.type = type;
    this.key = key;
    if (startTime == null) {
      this.startTime = Calendar.getInstance().getTime();
    } else {
      this.startTime = new Date(startTime.getTime());
    }
    long now = System.currentTimeMillis();
    if (endTime == null || endTime.getTime() - now > Config.TASK_EXPIRATION_MSEC) {

      this.endTime = new Date(now + Config.TASK_EXPIRATION_MSEC);
    } else {
      this.endTime = endTime;
    }
    if (intervalSec <= 0) {
      this.intervalSec = Config.DEFAULT_SYSTEM_MEASUREMENT_INTERVAL_SEC;
    } else {
      this.intervalSec = intervalSec;
    }
    this.count = count;
    this.priority = priority;
    this.parameters = params;

    if (contextIntervalSec <= 0) {
      this.contextIntervalSec = Config.DEFAULT_CONTEXT_INTERVAL_SEC;
    } else {
      this.contextIntervalSec = contextIntervalSec;
    }
  }

  /** Return the type of the measurement (DNS, Ping, Traceroute, etc.) */
  public abstract String getType();

  /** Subclass override this method to initialize measurement specific parameters */
  protected abstract void initializeParams(Map<String, String> params);

  @Override
  public boolean equals(Object o) {

    MeasurementDesc another = (MeasurementDesc) o;
    if (this.type.equals(another.type) && this.key.equals(another.key)
        && this.startTime.equals(another.startTime) && this.endTime.equals(another.endTime)
        && this.intervalSec == another.intervalSec && this.count == another.count
        && this.priority == another.priority
        && this.contextIntervalSec == another.contextIntervalSec
//        && this.parameters.equals(another.parameters)
        ) {
      for (String key : this.parameters.keySet()) {
        if (!this.parameters.get(key).equals(another.parameters.get(key))) {
          return false;
        }
      }

      return true;
    }
    return false;

  }
  
  @Override
  public String toString() {
    String result=type+","+key+","+intervalSec+","+count+","+priority+","+contextIntervalSec+",";
    Object [] keys=parameters.keySet().toArray();
    Arrays.sort(keys);
    for(Object k : keys){
      result+=parameters.get(k)+",";
    }
    return result;
  }

  /**
   * Necessary functions for Parcelable
   * @param in Parcel object containing measurement descriptor
   */
  protected MeasurementDesc(Parcel in) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    type = in.readString();
    key = in.readString();
    startTime = (Date) in.readSerializable();
    endTime = (Date) in.readSerializable();
    intervalSec = in.readDouble();
    count = in.readLong();
    priority = in.readLong();
    contextIntervalSec = in.readInt();
    parameters = in.readHashMap(loader);
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(type);
    dest.writeString(key);
    dest.writeSerializable(startTime);
    dest.writeSerializable(endTime);
    dest.writeDouble(intervalSec);
    dest.writeLong(count);
    dest.writeLong(priority);
    dest.writeInt(contextIntervalSec);
    dest.writeMap(parameters);
  }
}
