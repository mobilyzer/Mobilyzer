package com.mobilyzer.prerequisite;

import android.os.Parcel;

public class StringPrerequisite extends Prerequisite {
	protected String comparationOp;
	protected String prio;

	public StringPrerequisite(String preName, String comparationOp, String prio) {
		super(preName);
		this.comparationOp = comparationOp;
		this.prio = prio;
	}
	
	@Override
	public boolean satisfy() {
		String current = getCurrentContext();
	
		if(comparationOp.equals("="))
			return current.equalsIgnoreCase(prio);
		return !current.equalsIgnoreCase(prio);
	}
	
	
	@Override
	public String toString()
	{
		return preName+comparationOp+prio;
	}

	@Override
	public int describeContents() {
		// TODO Auto-generated method stub
		return 0;
	}

	protected StringPrerequisite(Parcel in) {
		super(in.readString());
		comparationOp = in.readString();
		prio = in.readString();
	}
	
	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeString(preName);
		dest.writeString(comparationOp);
		dest.writeString(prio);
	}
}
