package android.content;
public class Context { public static final String JOB_SCHEDULER_SERVICE="jobs"; public final android.app.job.JobScheduler jobs=new android.app.job.JobScheduler(); public Context getApplicationContext(){return this;} public Object getSystemService(String name){return JOB_SCHEDULER_SERVICE.equals(name)?jobs:null;} }
