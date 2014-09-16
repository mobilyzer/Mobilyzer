/* Copyright 2012 Google Inc.
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

package com.mobilyzer;


import java.util.HashSet;
import java.util.LinkedList;

import android.os.Parcel;
import android.os.Parcelable;



/**
 * POJO class containing dynamic information about the device
 * @see DeviceInfo
 */
public class DeviceProperty implements Parcelable {

  public String deviceId;
  public String appVersion;
  public long timestamp;
  public String osVersion;
  public String ipConnectivity;
  public String dnResolvability;
  public GeoLocation location;
  public String locationType;
  public String networkType;
  public String carrier;
  public int batteryLevel;
  public boolean isBatteryCharging;
  public String cellInfo;
  public int rssi;
  // version of Mobilyzer
  public String mobilyzerVersion;
  // the set of apps on the device that are using Mobilyzer.
  public LinkedList<String> hostApps;
  // the app which requests this measurement
  public String requestApp;
  
  public String registrationId;

  public DeviceProperty(String deviceId, String appVersion, long timeStamp, 
      String osVersion, String ipConnectivity, String dnResolvability, 
      double longtitude, double latitude, String locationType, 
      String networkType, String carrier, int batteryLevel, boolean isCharging,
      String cellInfo, int rssi,
      String mobilyzerVersion, HashSet<String> hostApps, String requestApp) {
    super();
    this.deviceId = deviceId;
    this.appVersion = appVersion;
    this.timestamp = timeStamp;
    this.osVersion = osVersion;
    this.ipConnectivity = ipConnectivity;
    this.dnResolvability = dnResolvability;
    this.location = new GeoLocation(longtitude, latitude);
    this.locationType = locationType;
    this.networkType = networkType;
    this.carrier = carrier;
    this.batteryLevel = batteryLevel;
    this.isBatteryCharging = isCharging;
    this.cellInfo = cellInfo;
    this.rssi = rssi;
    this.mobilyzerVersion = mobilyzerVersion;
    this.hostApps = new LinkedList<String>();
    for ( String hostApp : hostApps ) {
      this.hostApps.add(hostApp);
    }
    this.requestApp = requestApp;
  }

  private DeviceProperty(Parcel in) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    deviceId = in.readString();
    appVersion = in.readString();
    timestamp = in.readLong();
    osVersion = in.readString();
    ipConnectivity = in.readString();
    dnResolvability = in.readString();
    location = in.readParcelable(loader);
    locationType = in.readString();
    networkType = in.readString();
    carrier = in.readString();
    batteryLevel = in.readInt();
    isBatteryCharging = in.readByte() != 0;
    cellInfo = in.readString();
    rssi = in.readInt();
    mobilyzerVersion = in.readString();
    hostApps = new LinkedList<String>();
    in.readList(hostApps, loader);
    requestApp = in.readString();
  }
  
  public static final Parcelable.Creator<DeviceProperty> CREATOR
      = new Parcelable.Creator<DeviceProperty>() {
    public DeviceProperty createFromParcel(Parcel in) {
      return new DeviceProperty(in);
    }

    public DeviceProperty[] newArray(int size) {
      return new DeviceProperty[size];
    }
  };
  
  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(deviceId);
    dest.writeString(appVersion);
    dest.writeLong(timestamp);
    dest.writeString(osVersion);
    dest.writeString(ipConnectivity);
    dest.writeString(dnResolvability);
    dest.writeParcelable(location, flags);
    dest.writeString(locationType);
    dest.writeString(networkType);
    dest.writeString(carrier);
    dest.writeInt(batteryLevel);
    dest.writeByte((byte) (isBatteryCharging ? 1 : 0));
    dest.writeString(cellInfo);
    dest.writeInt(rssi);
    dest.writeString(mobilyzerVersion);
    dest.writeList(hostApps);
    dest.writeString(requestApp);
  }
  
  public void setRegistrationId(String regid){//TODO temporarily fix
    this.registrationId=regid;
  }

  
}

class GeoLocation implements Parcelable {
  private double longitude;
  private double latitude;
  
  public GeoLocation(double longtitude, double latitude) {
    this.longitude = longtitude;
    this.latitude = latitude;
  }


  private GeoLocation(Parcel in) {
    longitude = in.readDouble();
    latitude = in.readDouble();
  }
  
  public static final Parcelable.Creator<GeoLocation> CREATOR
      = new Parcelable.Creator<GeoLocation>() {
    public GeoLocation createFromParcel(Parcel in) {
      return new GeoLocation(in);
    }

    public GeoLocation[] newArray(int size) {
      return new GeoLocation[size];
    }
  };
  
  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeDouble(longitude);
    dest.writeDouble(latitude);
  }
  
}
