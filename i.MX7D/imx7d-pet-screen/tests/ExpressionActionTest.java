package com.zhb.petscreen;
import java.util.*;
public class ExpressionActionTest {
 static int count;
 static void check(boolean value){count++;if(!value)throw new AssertionError("case "+count);}
 static ExpressionAction one(String text){List<ExpressionAction> a=ExpressionAction.resolve(text,"angry",.65);check(a!=null&&a.size()==1);return a.get(0);}
 public static void main(String[] args){
  check(one("你表现出来很生气").name.equals("angry"));check(one("你表现出来很生气").intensity==.95);
  check(one("再生气一点").intensity==.9);check(one("不要生气，笑一个").name.equals("happy"));
  check(one("恢复平静").name.equals("neutral"));check(one("笑一个").name.equals("happy"));
  check(one("抠鼻屎表情").name.equals("nose_pick_666"));check(one("请展示抠鼻屎666").durationMs==12000);
  check(one("吃棒棒糖").name.equals("eat_lollipop"));check(one("做一个吃棒棒糖的表情").durationMs==12000);
  List<ExpressionAction> seq=ExpressionAction.resolve("先哭，再笑","neutral",.5);check(seq.size()==2&&seq.get(0).name.equals("sad")&&seq.get(1).name.equals("happy"));
  for(String t:new String[]{"我今天很生气","不要表现生气","如果你生气会怎样","他说笑一个","我想知道生气的原因","你能不能别生气","讲个故事"})check(ExpressionAction.resolve(t,"neutral",.5)==null);
  for(double value:new double[]{-1,1.1,Double.NaN,Double.POSITIVE_INFINITY}){try{new ExpressionAction("angry",value,1000);throw new AssertionError();}catch(IllegalArgumentException expected){count++;}}
  try{new ExpressionAction("shell",.5,1000);throw new AssertionError();}catch(IllegalArgumentException expected){count++;}
  try{new ExpressionAction("happy",.5,31000);throw new AssertionError();}catch(IllegalArgumentException expected){count++;}
  check(ExpressionAction.isSelfReport("我今天很生气，你能陪我聊聊吗？"));
  check(!ExpressionAction.isSelfReport("我今天很生气，请你表现得很生气"));
  System.out.println("PASS "+count+" assertions");
 }
}
