package com.mobilyzer.prerequisite;

import java.security.InvalidParameterException;
import java.util.LinkedList;

import android.os.Parcel;

public class IntPrerequisite extends Prerequisite {
	protected String comparationOp;
	protected long threshold;
	
	public IntPrerequisite(String preName, String comparationOp2,
			String condition) throws InvalidParameterException{
		super(preName);
		comparationOp = comparationOp2;
		threshold = eval(condition);
	}


	public long getThreshold(){
		return threshold;
	}
	
	private long eval(String condition) throws InvalidParameterException {
		long result=0;
		try{
			condition = condition.replaceAll(" ", "");
			LinkedList<Long> operands=new LinkedList<Long>();
			LinkedList<String> operators = new LinkedList<String>();
			if (condition.startsWith("-")){
				condition="0"+condition;
			}
			while(true){
				int index1 = condition.indexOf("+");
				int index2 = condition.indexOf("-");
				if (index1==-1 && index2==-1){
					operands.add(Long.parseLong(condition));
					break;
				}
				int index = index1;
				if (index==-1) index =index2;
				else if (index2!= -1 && index1> index2) index = index2;
				operands.add(Long.parseLong(condition.substring(0,index)));
				operators.add(condition.substring(index,index+1));
				condition = condition.substring(index+1);
			}
			if (operands.size()-operators.size()==1){
				while(operators.size()>0){
					long operand1 = operands.poll();
					long operand2 = operands.poll();
					String operator = operators.poll();
					if(operator.equals("+"))operands.addFirst(operand1+operand2);
					else if (operator.equals("-"))operands.addFirst(operand1-operand2);
				}
				result = operands.poll();
			}
		}catch (Exception e){
			throw new InvalidParameterException("fail to parse "+result+"in precondition");
		}
		return result;
	}
	
	@Override
	public boolean satisfy() {
		String currentContext = getCurrentContext();
		if (currentContext==null || currentContext=="null")
			return false;
		long current = Long.parseLong(currentContext);
		
		if (current == threshold  && comparationOp.contains("="))
			return true;
		if (current < threshold && comparationOp.contains("<"))
			return true;
		if (current > threshold && comparationOp.contains(">"))
			return true;
		
		return false;
	}



	@Override
	public String toString()
	{
		return preName+comparationOp+threshold;
	}



	@Override
	public int describeContents() {
		// TODO Auto-generated method stub
		return 0;
	}

	protected IntPrerequisite(Parcel in) {
		super(in.readString());
		comparationOp = in.readString();
		threshold = in.readLong();
	}
	
	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeString(preName);
		dest.writeString(comparationOp);
		dest.writeLong(threshold);
	}
}
