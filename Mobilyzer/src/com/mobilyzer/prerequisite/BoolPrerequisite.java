package com.mobilyzer.prerequisite;


import android.os.Parcel;

public class BoolPrerequisite extends Prerequisite {
	protected String comparationOp;
	protected boolean preFlag;
	
	public BoolPrerequisite(String preName, String comparationOp,
			String condition) {
		super(preName);
		this.comparationOp = comparationOp.trim();
		if (condition.toLowerCase().contains("t"))preFlag = true;
		else preFlag = false;
	}


	@Override
	public boolean satisfy() {
		String currentContext = getCurrentContext();
		if (currentContext==null || currentContext=="null")
			return false;
		boolean current = Boolean.parseBoolean(currentContext);
		if (current==preFlag && comparationOp.equals("="))
			return true;
		if (current!=preFlag && !comparationOp.equals("="))
			return true;
		return false;
	}
	
	@Override
	public String toString()
	{
		return preName+comparationOp+preFlag;
	}

	@Override
	public int describeContents() {
		// TODO Auto-generated method stub
		return 0;
	}

	protected BoolPrerequisite(Parcel in) {
		super(in.readString());
		comparationOp = in.readString();
		preFlag = in.readByte() != 0;
	}
	
	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeString(preName);
		dest.writeString(comparationOp);
		dest.writeByte((byte) (preFlag ? 1 : 0));  
	}
}
