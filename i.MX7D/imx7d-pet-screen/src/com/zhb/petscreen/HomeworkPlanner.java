package com.zhb.petscreen;
import java.util.*;
final class HomeworkPlanner {
 static String clock(int value){int day=value/1440;return (day==1?"次日 ":day>1?"第"+(day+1)+"天 ":"")+String.format(Locale.ROOT,"%02d:%02d",(value%1440)/60,value%60);}
 static String plan(List<String> titles,List<Integer> minutes,int start,int available){
  if(titles.isEmpty()||titles.size()>20||titles.size()!=minutes.size()||start<0||start>=1440||available<15||available>720)throw new IllegalArgumentException();
  StringBuilder out=new StringBuilder("作业时间计划（耗时为估计，可调整）\n");int now=start,focus=0,work=0;
  for(int i=0;i<titles.size();i++){
   String title=titles.get(i).trim();int remaining=minutes.get(i);
   if(title.isEmpty()||title.length()>200||remaining<1||remaining>180)throw new IllegalArgumentException();
   work+=remaining;
   while(remaining>0){
    if(focus==25){out.append(clock(now)).append("–").append(clock(now+5)).append(" 休息 5 分钟\n");now+=5;focus=0;}
    int block=Math.min(remaining,25-focus);
    out.append(clock(now)).append("–").append(clock(now+block)).append(" ").append(title).append("（").append(block).append("分钟）\n");now+=block;focus+=block;remaining-=block;
   }
  }
  int total=now-start;
  out.append("\n预计完成：").append(clock(now)).append("\n作业 ").append(work).append(" 分钟 + 休息 ").append(total-work).append(" 分钟 = ").append(total).append(" 分钟。\n");
  if(total>available)out.append("超过可用时间 ").append(total-available).append(" 分钟。请调整任务范围或可用时间；未擅自删减作业。");
  else out.append("可用时间剩余 ").append(available-total).append(" 分钟，可用于检查或缓冲。");
  return out.toString();
 }
}
