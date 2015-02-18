package com.mobilyzer.prerequisite;

import java.security.InvalidParameterException;
import java.util.LinkedList;

import android.os.Parcel;

public class DoublePrerequisite extends Prerequisite {

	protected String comparationOp;
	protected double threshold;
	
	public DoublePrerequisite(String preName, String comparationOp2,
			String condition) throws InvalidParameterException{
		super(preName);
		comparationOp = comparationOp2;
		threshold = eval(condition);
	}



	private double eval(String condition) throws InvalidParameterException {
		double result=0;
		try{
		condition = condition.replaceAll(" ", "");
		if (condition.startsWith("-")){
			condition="0"+condition;
		}
		LinkedList<Double> operands=new LinkedList<Double>();
		LinkedList<String> operators = new LinkedList<String>();
		while(true){
			int index1 = condition.indexOf("+");
			int index2 = condition.indexOf("-");
			if (index1==-1 && index2==-1){
				operands.add(Double.parseDouble(condition));
				break;
			}
			int index = index1;
			if (index==-1) index =index2;
			else if (index2!= -1 && index1> index2) index = index2;
			operands.add(Double.parseDouble(condition.substring(0,index)));
			operators.add(condition.substring(index,index+1));
			condition = condition.substring(index+1);
		}
		if (operands.size()-operators.size()==1){
			while(operators.size()>0){
				double operand1 = operands.poll();
				double operand2 = operands.poll();
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
		Double current = Double.parseDouble(getCurrentContext());
		
		if (current - threshold < 0.0001 && threshold - current < 0.0001 && comparationOp.contains("="))
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

	protected DoublePrerequisite(Parcel in) {
		super(in.readString());
		comparationOp = in.readString();
		threshold = in.readDouble();
	}
	
	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeString(preName);
		dest.writeString(comparationOp);
		dest.writeDouble(threshold);
	}

}
