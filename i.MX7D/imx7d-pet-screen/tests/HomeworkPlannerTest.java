package com.zhb.petscreen;
import java.util.*;
public class HomeworkPlannerTest {
 static int count;static void check(boolean x){count++;if(!x)throw new AssertionError("case "+count);}
 public static void main(String[] args){
  String p=HomeworkPlanner.plan(Arrays.asList("数学","语文"),Arrays.asList(30,20),1080,60);
  check(p.contains("18:00–18:25 数学"));check(p.contains("18:25–18:30 休息"));check(p.contains("18:30–18:35 数学"));check(p.contains("18:35–18:55 语文"));check(p.contains("预计完成：18:55"));check(p.contains("剩余 5 分钟"));
  check(HomeworkPlanner.plan(Arrays.asList("语文"),Arrays.asList(25),1080,25).contains("休息 0 分钟"));
  check(HomeworkPlanner.plan(Arrays.asList("数学"),Arrays.asList(30),1430,15).contains("次日 00:25"));
  check(HomeworkPlanner.plan(Arrays.asList("数学"),Arrays.asList(30),1080,15).contains("超过可用时间 20 分钟"));
  try{HomeworkPlanner.plan(Arrays.asList(""),Arrays.asList(1),0,15);throw new AssertionError();}catch(IllegalArgumentException e){count++;}
  try{HomeworkPlanner.plan(Arrays.asList("数学"),Arrays.asList(0),0,15);throw new AssertionError();}catch(IllegalArgumentException e){count++;}
  System.out.println("PASS "+count+" planner assertions");
 }
}
