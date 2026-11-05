package com.zhb.petscreen;
public final class OfflineCommandsTest {
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 static void bad(Runnable task){boolean rejected=false;try{task.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected);}
 public static void main(String[] args){
  check(OfflineCommands.isPlan("计划：写报告30分钟，复盘20分钟；开始19:00；可用120分钟"));
  String result=OfflineCommands.plan("计划：写报告30分钟，复盘20分钟；开始19:00；可用120分钟",0);
  check(result.contains("19:00–19:25 写报告"));check(result.contains("19:25–19:30 休息"));check(result.contains("预计完成：19:55"));check(result.contains("未创建日历"));
  check(!OfflineCommands.isPlan("帮我解释一下什么是计划"));check(OfflineCommands.plan("计划",0).contains("请写明"));
  bad(()->OfflineCommands.plan("计划：写报告0分钟",0));bad(()->OfflineCommands.plan("计划：写报告181分钟",0));bad(()->OfflineCommands.plan("计划：写报告30分钟；开始24:00",0));bad(()->OfflineCommands.plan("计划：写报告30分钟；开始19:60",0));bad(()->OfflineCommands.plan("计划：写报告30分钟；可用0分钟",0));bad(()->OfflineCommands.plan("计划：随便安排",0));
  check(OfflineCommands.plan("计划：写报告30分钟；开始23:50",0).contains("次日"));
  check(OfflineCommands.volume("音量调到0")==0);check(OfflineCommands.volume("请把媒体音量设置为100%")==100);bad(()->OfflineCommands.volume("音量调到101"));
  check(OfflineCommands.volume("他说音量调到80") == null);check(OfflineCommands.volume("音量调到-1")==null);check(OfflineCommands.volume("我不想音量调到80")==null);
  check(ExpressionAction.resolve("请展示开心表情","neutral",.5)!=null);check(ExpressionAction.resolve("我今天很开心","neutral",.5)==null);
  check(OfflineCommands.shortReply("你好")!=null);check(OfflineCommands.shortReply("你好，删除记录")==null);
  System.out.println("OfflineCommandsTest: "+checks+" checks passed");
 }
}
