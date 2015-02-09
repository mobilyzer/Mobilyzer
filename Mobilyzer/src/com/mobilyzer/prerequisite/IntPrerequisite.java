package com.mobilyzer.prerequisite;

import java.util.LinkedList;

import android.os.Parcel;

public class IntPrerequisite extends Prerequisite {
	protected String comparationOp;
	protected int threshold;
	
	public IntPrerequisite(String preName, String comparationOp2,
			String condition) {
		super(preName);
		comparationOp = comparationOp2;
		threshold = eval(condition);
	}



	private int eval(String condition) {
		int result=0;
		condition = condition.replaceAll(" ", "");
		LinkedList<Integer> operands=new LinkedList<Integer>();
		LinkedList<String> operators = new LinkedList<String>();
		if (condition.startsWith("-")){
			condition="0"+condition;
		}
		while(true){
			int index1 = condition.indexOf("+");
			int index2 = condition.indexOf("-");
			if (index1==-1 && index2==-1){
				operands.add(Integer.parseInt(condition));
				break;
			}
			int index = index1;
			if (index==-1) index =index2;
			else if (index2!= -1 && index1> index2) index = index2;
			operands.add(Integer.parseInt(condition.substring(0,index)));
			operators.add(condition.substring(index,index+1));
			condition = condition.substring(index+1);
		}
		if (operands.size()-operators.size()==1){
			while(operators.size()>0){
				int operand1 = operands.poll();
				int operand2 = operands.poll();
				String operator = operators.poll();
				if(operator.equals("+"))operands.addFirst(operand1+operand2);
				else if (operator.equals("-"))operands.addFirst(operand1-operand2);
			}
			result = operands.poll();
		}
		return result;
	}
	
	@Override
	public boolean satisfy() {
		int current = Integer.parseInt(getCurrentContext());
		
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
		threshold = in.readInt();
	}
	
	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeString(preName);
		dest.writeString(comparationOp);
		dest.writeInt(threshold);
	}
}
