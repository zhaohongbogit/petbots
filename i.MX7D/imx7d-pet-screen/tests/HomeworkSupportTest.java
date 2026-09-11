package com.zhb.petscreen;
import org.json.*;
public class HomeworkSupportTest {
 public static void main(String[] a)throws Exception{
  JSONObject t=new JSONObject().put("title","数学第12页").put("minutes",15).put("uncertain",false);
  JSONObject doc=new JSONObject().put("tasks",new JSONArray().put(t)).put("notes","耗时待核对");
  if(!HomeworkSupport.draft(doc).contains("预估 15 分钟"))throw new AssertionError();int count=1;
  for(Object bad:new Object[]{0,-1,181,1.5,"15"}){t.put("minutes",bad);try{HomeworkSupport.draft(doc);throw new AssertionError();}catch(Exception expected){count++;}}
  t.put("minutes",15).put("uncertain",true);if(!HomeworkSupport.draft(doc).contains("待确认"))throw new AssertionError();count++;
  JSONObject plan=new JSONObject().put("tasks",new JSONArray().put(t)).put("start_minutes",1080).put("available_minutes",30);
  if(!HomeworkSupport.plan(plan).contains("18:15"))throw new AssertionError();count++;
  plan.put("start_minutes",1440);try{HomeworkSupport.plan(plan);throw new AssertionError();}catch(Exception expected){count++;}
  System.out.println("PASS "+count+" schema assertions");
 }
}
