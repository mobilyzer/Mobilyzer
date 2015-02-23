package com.mobilyzer.prerequisite;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.os.Parcelable;

import com.mobilyzer.util.ContextMonitor;
import com.mobilyzer.util.PhoneUtils;

public abstract class Prerequisite implements Parcelable{
	//type of the prerequisite: boolean or numeric or string
	protected String preType;

	//name of the prerequisite: networkType, signalStrength...
	protected String preName;

	public Prerequisite(String preName){
		this.preName=preName;
	}
	
	private static HashMap<String, Class> preNameToType;
	
	static public final String NETWORK_TYPE = "network.type";
	static public final String NETWORK_WIFI_RSSI = "network.wifi.rssi";
	static public final String NETWORK_WIFI_SSID = "network.wifi.ssid";
	static public final String NETWORK_WIFI_RSSID = "network.wifi.rssid";
	static public final String NETWORK_CELLULAR_RSSI = "network.cellular.rssi";
	static public final String SCREEN_STATUS = "screen.status";
	static public final String CALL_STATUS = "call.status";
//	static public final String MOVE_COUNT = "movement.count";
//	static public final String MOVE_STATUS = "movement.status";
	static public final String ACTIVITY_TYPE = "activity.type";
	static public final String ACTIVITY_LASTTIME = "activity.lasttime";
	static public final String RESULT_TYPE = "result.type";
	static public final String PING_AVGRTT = "result.ping.avgrtt";
	static public final String LOCATION_COORDINATE = "location.coordinate";
	static public final String LOCATION_LONGITUDE = "location.longitude";
	static public final String LOCATION_LATITUDE = "location.latitude";
	static public final String LOCATION_ALTITUDE = "location.alitude";
	static public final String LOCATION_SPEED = "location.speed";
	static public final String TIME_TIME = "time.time";
	static public final String PRE_TYPE_NETWORK = "network";
//	static public final String PRE_TYPE_MOVEMENT = "movement";
	static public final String PRE_TYPE_ACTIVITY = "activity";
	static public final String PRE_TYPE_RESULTS = "result";
	static public final String PRE_TYPE_LOCATION = "location";
	static public final String PRE_TYPE_CALL = "call";
	static public final String PRE_TYPE_SCREEN = "screen";
	static public final String PRE_TYPE_TIME = "time";
	
	static {
		preNameToType = new HashMap<String, Class>();
		preNameToType.put(NETWORK_TYPE, StringPrerequisite.class);
		preNameToType.put(NETWORK_WIFI_RSSI, DoublePrerequisite.class);
		preNameToType.put(NETWORK_WIFI_SSID, StringPrerequisite.class);
		preNameToType.put(NETWORK_WIFI_RSSID, StringPrerequisite.class);
		preNameToType.put(NETWORK_CELLULAR_RSSI, DoublePrerequisite.class);
		preNameToType.put(SCREEN_STATUS, StringPrerequisite.class);
		preNameToType.put(CALL_STATUS, StringPrerequisite.class);
		preNameToType.put(ACTIVITY_TYPE, StringPrerequisite.class);
		preNameToType.put(ACTIVITY_LASTTIME, IntPrerequisite.class);
		preNameToType.put(RESULT_TYPE, StringPrerequisite.class);
		preNameToType.put(PING_AVGRTT, DoublePrerequisite.class);
		preNameToType.put(LOCATION_COORDINATE, LocationPrerequisite.class);
		preNameToType.put(LOCATION_LONGITUDE, DoublePrerequisite.class);
		preNameToType.put(LOCATION_LATITUDE, DoublePrerequisite.class);
		preNameToType.put(LOCATION_ALTITUDE, DoublePrerequisite.class);
		preNameToType.put(LOCATION_SPEED, DoublePrerequisite.class);
		preNameToType.put(TIME_TIME, IntPrerequisite.class);
	}
	public static ArrayList<ArrayList<Prerequisite>> makePrerequisiteGroupsFromString(String s) throws InvalidParameterException{
		ArrayList<ArrayList<Prerequisite>> results = new ArrayList<ArrayList<Prerequisite>>();
		if (s==null || s.trim().length()==0)
			return results;
		//prerequisite groups are connected by "|"
		try{
			for (String sPreGroup: s.split("\\|")){
		
			ArrayList<Prerequisite> preGroup = new ArrayList<Prerequisite>();
			//prerequisites in a group are connected by "&"
			for (String sPre: sPreGroup.split("&"))
			{
				sPre = sPre.trim();
				if (sPre.length()==0)continue;
				Prerequisite pre = makePrerequisiteFromString(sPre);
				if (pre==null){
					results=null;
					break;
				}else{
					preGroup.add(makePrerequisiteFromString(sPre));
				}
			}
			results.add(preGroup);
		}
		}catch (InvalidParameterException e){
			throw e;
		}
		return results;
	}
	
	public static Prerequisite makePrerequisiteFromString(String s) throws InvalidParameterException{
		Prerequisite result = null;
		Pattern pattern = Pattern.compile("[<>=]+");
		Matcher match = pattern.matcher(s);
		if (match.find()) {
			String preName = s.substring(0,match.start());
			String comparationOp = s.substring(match.start(),match.end());
			String condition = s.substring(match.end());
			if (condition.contains("$last"))
				condition = condition.replace("$last", ContextMonitor.getContextMonitor().getContext(preName));
			if(preNameToType.containsKey(preName)){
				if(preNameToType.get(preName)==StringPrerequisite.class){
					result = new StringPrerequisite(preName, comparationOp, condition);
				}else if(preNameToType.get(preName)==DoublePrerequisite.class){
					result = new DoublePrerequisite(preName, comparationOp, condition);
				}else if (preNameToType.get(preName)==IntPrerequisite.class){
					result = new IntPrerequisite(preName, comparationOp, condition);
				}else if (preNameToType.get(preName)==BoolPrerequisite.class){
					result = new BoolPrerequisite(preName, comparationOp, condition);
				}else if (preNameToType.get(preName)==LocationPrerequisite.class){
					String[] splits = condition.split("+");
					if (splits.length<2)
						throw new InvalidParameterException("Fail to parse location prerequisite "+condition);
					result = new LocationPrerequisite(preName, comparationOp, splits[0], splits[1]);
				}else
					throw new InvalidParameterException("Unknown prerequisite type");
			}
		}
		return result;
	}
	public String getName(){
		return preName;
	}
	
	public String getCurrentContext(){
		return ContextMonitor.getContextMonitor().getContext(preName);
	}
	
	public String getType(){
		if (preName!=null)
		{
			return preName.split("\\.")[0];
		}
		return preName;
	}
	
	abstract public boolean satisfy();
	
	@Override
	public String toString()
	{
		return preName;
	}
}
