package com.mobilyzer.prerequisite;

import java.security.InvalidParameterException;

import android.location.Location;
import android.os.Parcel;

public class LocationPrerequisite extends Prerequisite {
	String preLocation;
	float preLongitude;
	float preLatitude;
	
	String comparationOp;
	float distanceThreshold;
	
	public LocationPrerequisite(String preName,	String comparationOp, 
			String preLocation, String distance) throws InvalidParameterException{
		super(preName);
		this.preLocation = preLocation;
		float [] fprelocation = parseLocation(preLocation);
		preLatitude = fprelocation[0];
		preLongitude = fprelocation[1];
		this.comparationOp = comparationOp;
		try{
			distanceThreshold = Float.parseFloat(distance.trim());
		}catch (Exception e){
			throw new InvalidParameterException("fail to parse float number"+distance+"in precondition");
		}
	}

	private float[] parseLocation(String location){
		location = location.trim();
		location = location.replace("(", "").replace(")", "");
		String [] splits = location.split(",");
		if (splits.length<2){
			throw new InvalidParameterException("fail to parse "+location+"in precondition");
		}
		float[] result = new float[2];
		try{
			result[0]=Float.parseFloat(splits[0]);
			result[1]=Float.parseFloat(splits[1]);
		}catch (Exception e){
			throw new InvalidParameterException("fail to parse "+location+"in precondition");
		}
		return result;
	}
	
	@Override
	public boolean satisfy() {
		String currentLocationS = getCurrentContext();
		if (currentLocationS == null || currentLocationS == "null")
			return false;
		try{
			float [] currentLocationArray = parseLocation(currentLocationS);
			float currentLatitude = currentLocationArray[0];
			float currentLongtitude = currentLocationArray[1];
			float distance[] = new float[3];
			Location.distanceBetween(currentLatitude, currentLongtitude, preLatitude, preLongitude, distance);
			if (distance[0]>distanceThreshold && comparationOp.contains(">"))
				return true;
			if (distance[0]<distanceThreshold && comparationOp.contains("<"))
				return true;
			if (distance[0]==distanceThreshold && comparationOp.contains("="))
				return true;
			return false;
		}catch (Exception e){
			
		}
		return false;
	}
	
	@Override
	public int describeContents() {
		// TODO Auto-generated method stub
		return 0;
	}
	
	protected LocationPrerequisite(Parcel in) {
		super(in.readString());
		preLocation = in.readString();
		comparationOp = in.readString();
		distanceThreshold = in.readFloat();
		float [] fprelocation = parseLocation(preLocation);
		preLatitude = fprelocation[0];
		preLongitude = fprelocation[1];
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeString(preName);
		dest.writeString(preLocation);
		dest.writeString(comparationOp);	
		dest.writeFloat(distanceThreshold);
	}
}
