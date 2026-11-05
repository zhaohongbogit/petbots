package com.zhb.petscreen;
import java.util.*;
import java.util.regex.*;
/** Explicit local commands only. Model output is never passed to this dispatcher. */
final class OfflineCommands {
 static String shortReply(String text){
  switch(text.replaceAll("[\\s，。！!?？]","")){
   case "你好":case "小七你好":case "你好小七":case "你在吗":return "我在，我是小七。你可以打字让我制定时间计划、查询设备状态或展示表情。";
   case "你叫什么名字":case "你是谁":return "我是小七，通过局域网在i.MX7上处理你的文字任务。";
   case "谢谢":case "谢谢小七":return "不客气。需要安排下一件事时，可以把任务和预计分钟数发给我。";
   case "再见":return "再见，需要时再来找我。";
   default:return null;
  }
 }
 static boolean isPlan(String text){return text.matches("^(请)?(帮我)?(安排|制定|规划|做)?(一下)?(时间)?计划[：:].*")||text.matches("^(请)?(帮我)?(安排|制定|规划|做)?(一下)?(时间)?计划$");}
 static String plan(String text,int now){
  String body=text.replaceFirst("^(请)?(帮我)?(安排|制定|规划|做)?(一下)?(时间)?计划[：:]?","").trim();
  if(body.isEmpty())return "请写明任务和预计时长，例如：计划：写报告30分钟，复盘20分钟；开始19:00；可用120分钟。这里只生成计划，不创建日历或提醒。";
  int start=now,available=120;ArrayList<String> titles=new ArrayList<>();ArrayList<Integer> durations=new ArrayList<>();
  for(String raw:body.split("[，,；;\\n]")){
   String part=raw.trim();if(part.isEmpty())continue;
   Matcher time=Pattern.compile("开始\\s*([0-9]{1,2}):([0-9]{2})").matcher(part);
   Matcher span=Pattern.compile("可用\\s*([0-9]{1,3})分钟").matcher(part);
   Matcher task=Pattern.compile("(.{1,60}?)\\s*([0-9]{1,3})分钟").matcher(part);
   if(time.matches()){int h=Integer.parseInt(time.group(1)),m=Integer.parseInt(time.group(2));if(h>23||m>59)throw new IllegalArgumentException();start=h*60+m;}
   else if(span.matches())available=Integer.parseInt(span.group(1));
   else if(task.matches()){titles.add(task.group(1).trim());durations.add(Integer.parseInt(task.group(2)));}
   else throw new IllegalArgumentException();
  }
  return HomeworkPlanner.plan(titles,durations,start,available).replace("作业时间计划","时间计划").replace("作业 ","任务 ").replace("删减作业","删减任务")+"\n这里只生成计划，未创建日历、提醒或外部任务。";
 }
 static Integer volume(String t){Matcher m=Pattern.compile("(请)?(把)?(媒体)?音量(调到|设为|设置为|调至)([0-9]{1,3})(%)?").matcher(t.replaceAll("[\\s，。！]",""));if(!m.matches())return null;int n=Integer.parseInt(m.group(5));if(n<0||n>100)throw new IllegalArgumentException();return n;}
}
