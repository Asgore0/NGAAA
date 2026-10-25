package cn.ngapure.communityview;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import cn.ngapure.app.NativeCommunity;
import cn.ngapure.app.NativeCommunityView;
import cn.ngapure.app.NativeGlassDialog;
import cn.ngapure.app.NativeLoginView;
import cn.ngapure.app.NativeMessageDrafts;
import cn.ngapure.app.NgaStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Exact production classes, own UID, local synthetic read completion and no Internet permission. */
public final class CommunityViewProbe extends Instrumentation {
    private Activity activity;
    private boolean authorized,observeBaseline;
    private int assertions,reads,mutations;
    private String stage="guard";
    private final JSONObject report=new JSONObject();
    private final List<NativeCommunityView> owners=new ArrayList<>();
    private static final class Queue extends AbstractExecutorService {
        final List<Runnable> tasks=new ArrayList<>();boolean stopped;
        @Override public void execute(Runnable action){if(stopped)throw new java.util.concurrent.RejectedExecutionException();tasks.add(action);}
        @Override public void shutdown(){stopped=true;}
        @Override public List<Runnable> shutdownNow(){stopped=true;List<Runnable> pending=new ArrayList<>(tasks);tasks.clear();return pending;}
        @Override public boolean isShutdown(){return stopped;}
        @Override public boolean isTerminated(){return stopped&&tasks.isEmpty();}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
        void completeNext(){if(tasks.isEmpty())throw new AssertionError("SYNTHETIC_READ_REQUIRED");tasks.remove(0).run();}
    }
    private static final class Fixture {
        final NativeCommunityView owner;final Queue queue;final NgaStore store;final String[] opened;
        Fixture(NativeCommunityView owner,Queue queue,NgaStore store,String[] opened){this.owner=owner;this.queue=queue;this.store=store;this.opened=opened;}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticPrivateMessagesOnly"));observeBaseline=args!=null&&"true".equals(args.getString("observeBaseline"));start();}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private static Object field(Object target,String name)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);return member.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);member.set(target,value);}
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args);}
    private void check(String label,boolean value)throws Exception{report.put(label,value);assertions++;if(!value)throw new AssertionError(label);}
    private NativeCommunity.Response json(String value){return new NativeCommunity.Response(200,value.getBytes(StandardCharsets.UTF_8),"application/json; charset=UTF-8");}
    private Fixture prepare()throws Exception {
        return main(()->{
            for(NativeCommunityView previous:owners)previous.close();
            NgaStore store=new NgaStore(activity);store.setMotionEnabled(true);store.setCookie("ngaPassportUid=70001; ngaPassportCid=synthetic-only");
            activity.getSharedPreferences("nga_native_message_drafts",Activity.MODE_PRIVATE).edit().clear().commit();
            NativeCommunity api=new NativeCommunity(new NativeCommunity.Session(){public String host(){return "bbs.nga.cn";}public String cookie(){return "ngaPassportUid=70001; ngaPassportCid=synthetic-only";}},(request,cookie)->{
                if(request.mutation){mutations++;throw new AssertionError("COMMUNITY_MUTATION_FORBIDDEN");}reads++;
                if(request.parameters.containsKey("username"))return json("{\"data\":{\"0\":{\"uid\":\"70002\",\"username\":\"Synthetic recipient\",\"posts\":1}}}");
                if("read".equals(request.parameters.get("act"))){String page=request.parameters.get("page");if(page==null)page="1";return json("{\"data\":{\"0\":{\"currentPage\":"+page+",\"rowsPerPage\":20,\"nextPage\":"+(Integer.parseInt(page)+1)+",\"mid\":88001,\"subject\":\"Synthetic conversation\",\"allmsgs\":{\"0\":{\"id\":90001,\"content\":\"Synthetic read-only body\",\"from\":70002,\"from_username\":\"Fixture\",\"time\":1760000000}}}}}");}
                return json("{\"data\":{\"0\":{\"currentPage\":1,\"rowsPerPage\":20}}}");
            });
            final String[] opened={"","",""};
            NativeCommunityView owner=new NativeCommunityView(activity,store,"inbox","",1,new NativeCommunityView.Listener(){public void navigate(String kind,String id,String title,int page){}public void topic(String tid,String title){opened[0]=tid;opened[1]="";opened[2]=title;}public void topic(String tid,String pid,String title){opened[0]=tid;opened[1]=pid;opened[2]=title;}public void user(String uid){}public void login(){throw new AssertionError("SYNTHETIC_LOGIN_REQUIRED");}});
            // The constructor's disposable read cannot connect: this helper has no INTERNET.
            // Invalidate it before replacing the worker/transport with a controlled local queue.
            ((ExecutorService)field(owner,"io")).shutdownNow();set(owner,"generation",((Integer)field(owner,"generation"))+1);set(owner,"pendingUiTasks",0);
            Queue queue=new Queue();set(owner,"io",queue);set(owner,"api",api);owners.add(owner);activity.setContentView(owner);return new Fixture(owner,queue,store,opened);
        });
    }
    private List<NativeGlassDialog> dialogs(Fixture value)throws Exception {
        List<?> raw=(List<?>)field(value.owner,"dialogs");List<NativeGlassDialog> result=new ArrayList<>();for(Object object:raw)if(object instanceof NativeGlassDialog)result.add((NativeGlassDialog)object);return result;
    }
    private NativeGlassDialog last(Fixture value)throws Exception{List<NativeGlassDialog> values=dialogs(value);return values.get(values.size()-1);}
    private static TextView text(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup){ViewGroup parent=(ViewGroup)view;for(int i=0;i<parent.getChildCount();i++){TextView found=text(parent.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private static EditText input(View view,String hint){if(view instanceof EditText&&hint.contentEquals(((EditText)view).getHint()))return (EditText)view;if(view instanceof ViewGroup){ViewGroup parent=(ViewGroup)view;for(int i=0;i<parent.getChildCount();i++){EditText found=input(parent.getChildAt(i),hint);if(found!=null)return found;}}return null;}
    private NativeGlassDialog editor(Fixture value)throws Exception{return main(()->{invoke(value.owner,"compose",new Class<?>[]{NativeCommunity.MessagePage.class},(Object)null);return last(value);});}
    private View decor(NativeGlassDialog dialog){return dialog.getWindow().getDecorView();}
    private boolean cancelThenDiscard(Fixture value,NativeGlassDialog editor)throws Exception{return main(()->{
        input(decor(editor),"标题").setText("Synthetic draft title");input(decor(editor),"私信正文").setText("Synthetic draft body");((Runnable)field(value.owner,"saveOpenEditor")).run();
        editor.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();report.put("editorClosingStillShowing",editor.isClosing()&&editor.isShowing());text(decor(editor),"清除本机草稿").callOnClick();return dialogs(value).size()>1;
    });}
    private boolean cancelThenLookupCompletion(Fixture value,NativeGlassDialog editor)throws Exception{return main(()->{
        input(decor(editor),"输入完整用户名查找收件人").setText("Synthetic recipient");text(decor(editor),"查找用户").performClick();
        if(value.queue.tasks.size()!=1)throw new AssertionError("QUEUED_LOOKUP_REQUIRED");editor.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        // Completing a controlled local read in the same main-loop turn makes the
        // exit-animation boundary deterministic, without network timing assumptions.
        value.queue.completeNext();return dialogs(value).size()>1;
    });}
    private void observe()throws Exception {
        stage="observeDiscardDuringExit";Fixture discard=prepare();NativeGlassDialog discardEditor=editor(discard);boolean staleDiscard=cancelThenDiscard(discard,discardEditor);main(()->{discard.owner.close();return null;});
        stage="observeLookupDuringExit";Fixture lookup=prepare();NativeGlassDialog lookupEditor=editor(lookup);boolean staleLookup=cancelThenLookupCompletion(lookup,lookupEditor);main(()->{lookup.owner.close();return null;});
        report.put("cancelThenDiscardReopenedDialog",staleDiscard).put("cancelThenLookupCompletionReopenedDialog",staleLookup).put("expectedRegressionObserved",staleDiscard&&staleLookup).put("completed",true).put("observationOnly",true);
    }
    private void strict()throws Exception {
        stage="discardDuringExit";Fixture discard=prepare();NativeGlassDialog discardEditor=editor(discard);check("cancelExitRejectsDraftDiscardEntry",!cancelThenDiscard(discard,discardEditor));main(()->{discard.owner.close();return null;});
        stage="lookupDuringExit";Fixture lookup=prepare();NativeGlassDialog lookupEditor=editor(lookup);check("lateLookupCannotOpenRecipientAfterCancel",!cancelThenLookupCompletion(lookup,lookupEditor));main(()->{lookup.owner.close();return null;});
        stage="closingLookupEntry";Fixture closing=prepare();NativeGlassDialog closingEditor=editor(closing);main(()->{input(decor(closingEditor),"输入完整用户名查找收件人").setText("Synthetic recipient");closingEditor.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();text(decor(closingEditor),"查找用户").callOnClick();return null;});check("closingEditorRejectsLookupEntry",closing.queue.tasks.isEmpty());
        stage="closedLookupEntry";Fixture ended=prepare();NativeGlassDialog endedEditor=editor(ended);main(()->{input(decor(endedEditor),"输入完整用户名查找收件人").setText("Synthetic recipient");endedEditor.dismiss();text(decor(endedEditor),"查找用户").callOnClick();return null;});check("closedEditorRejectsLookupEntry",ended.queue.tasks.isEmpty());
        stage="liveLookup";Fixture live=prepare();NativeGlassDialog liveEditor=editor(live);main(()->{input(decor(liveEditor),"输入完整用户名查找收件人").setText("Synthetic recipient");text(decor(liveEditor),"查找用户").performClick();live.queue.completeNext();return null;});check("liveLookupStillOpensConfirmation",main(()->dialogs(live).size()==2));NativeGlassDialog found=main(()->last(live));main(()->{found.getButton(DialogInterface.BUTTON_POSITIVE).performClick();return null;});check("liveRecipientConfirmationPopulatesNativeInput",main(()->"70002".contentEquals(input(decor(liveEditor),"收件人 UID，多个用英文逗号分隔").getText())));
        stage="lookupAfterParentExit";Fixture orphan=prepare();NativeGlassDialog orphanEditor=editor(orphan);main(()->{input(decor(orphanEditor),"输入完整用户名查找收件人").setText("Synthetic recipient");text(decor(orphanEditor),"查找用户").performClick();orphan.queue.completeNext();return null;});NativeGlassDialog orphanFound=main(()->last(orphan));main(()->{orphanEditor.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();orphanFound.getButton(DialogInterface.BUTTON_POSITIVE).performClick();return null;});check("closingEditorRejectsNestedRecipientMutation",main(()->!orphanFound.isShowing()&&input(decor(orphanEditor),"收件人 UID，多个用英文逗号分隔").length()==0));
        stage="lookupAfterAccountChange";Fixture changed=prepare();NativeGlassDialog changedEditor=editor(changed);main(()->{input(decor(changedEditor),"输入完整用户名查找收件人").setText("Synthetic recipient");text(decor(changedEditor),"查找用户").performClick();changed.store.setCookie("ngaPassportUid=70003; ngaPassportCid=synthetic-other");changed.queue.completeNext();return null;});check("changedAccountRejectsLateRecipientPresentation",main(()->dialogs(changed).size()==1));main(()->{changed.owner.close();return null;});
        stage="readOnlyClosedWindow";Fixture readOnly=prepare();NativeGlassDialog retiredReview=main(()->{invoke(readOnly.owner,"verifyConversation",new Class<?>[]{String.class,int.class},"88001",1);NativeGlassDialog review=last(readOnly);review.dismiss();readOnly.queue.completeNext();return review;});check("closedReadOnlyWindowDoesNotRenderLateContent",main(()->dialogs(readOnly).isEmpty()&&text(decor(retiredReview),"只读核对，不会重发或解锁原提交。") == null));
        stage="readOnlyPageReplacement";Fixture pager=prepare();main(()->{invoke(pager.owner,"verifyConversation",new Class<?>[]{String.class,int.class},"88001",1);pager.queue.completeNext();return null;});NativeGlassDialog first=main(()->last(pager));TextView next=main(()->text(decor(first),"下一页"));check("syntheticReadOnlyConversationHasNextPage",next!=null);main(()->{next.performClick();return null;});check("readOnlyNextPageReplacesPreviousWindow",main(()->!first.isShowing()&&dialogs(pager).size()==1));main(()->{pager.queue.completeNext();return null;});check("replacementPageRendersInSingleWindow",main(()->dialogs(pager).size()==1&&text(decor(last(pager)),"上一页")!=null));
        stage="userTouchTarget";Fixture touch=prepare();LinearLayout row=main(()->(LinearLayout)invoke(touch.owner,"personRow",new Class<?>[]{String.class,String.class,String.class,String.class,int.class},"","Synthetic user","70002","Local detail",30));check("userRowOwnsAccessibleClick",main(()->row.isClickable()&&row.isFocusable()&&row.getMinimumHeight()>=Math.round(48*activity.getResources().getDisplayMetrics().density)));check("smallAvatarDoesNotBecomeSeparateTouchTarget",main(()->!row.getChildAt(0).isClickable()&&!row.getChildAt(0).isFocusable()));check("smallUsernameDoesNotBecomeSeparateTouchTarget",main(()->!((ViewGroup)row.getChildAt(1)).getChildAt(0).isClickable()));
        stage="reminderReplyLocator";Fixture reminders=prepare();NativeCommunity.Reminder item=new NativeCommunity.Reminder();item.tid="91001";item.pid="92001";item.title="Synthetic reminder";NativeCommunity.ReminderPage reminderPage=new NativeCommunity.ReminderPage();reminderPage.items.add(item);main(()->{invoke(reminders.owner,"reminders",new Class<?>[]{NativeCommunity.ReminderPage.class},reminderPage);text(reminders.owner,"查看相关主题").performClick();return null;});check("nativeReminderOpensExactReplyLocator","91001".equals(reminders.opened[0])&&"92001".equals(reminders.opened[1]));item.pid="1&pid=2";main(()->{invoke(reminders.owner,"reminders",new Class<?>[]{NativeCommunity.ReminderPage.class},reminderPage);text(reminders.owner,"查看相关主题").performClick();return null;});check("malformedReminderLocatorCannotBecomeNavigation","91001".equals(reminders.opened[0])&&reminders.opened[1].isEmpty());
        stage="loginMethodOnlyRestoration";java.lang.reflect.Constructor<NativeLoginView> loginConstructor=NativeLoginView.class.getConstructor(Activity.class,NgaStore.class,Runnable.class,int.class);Method selected=NativeLoginView.class.getMethod("selectedMethod"),restore=NativeLoginView.class.getMethod("restoreSelectedMethod",int.class);NativeLoginView login=main(()->loginConstructor.newInstance(activity,reminders.store,(Runnable)()->{},2));Object credential=main(()->field(login,"credentialView"));check("smsMethodRestoresWithoutStartingQrAuthorization",main(()->Integer.valueOf(2).equals(selected.invoke(login))&&field(login,"attempt")==null));main(()->{((EditText)field(credential,"account")).setText("synthetic-phone");((EditText)field(credential,"secret")).setText("123456");login.close();return null;});check("closingLoginClearsSensitiveFields",main(()->((EditText)field(credential,"account")).length()==0&&((EditText)field(credential,"secret")).length()==0));NativeLoginView recreated=main(()->loginConstructor.newInstance(activity,reminders.store,(Runnable)()->{},2));Object freshCredential=main(()->field(recreated,"credentialView"));check("recreatedMethodRetainsNoFormValues",main(()->Integer.valueOf(2).equals(selected.invoke(recreated))&&((EditText)field(freshCredential,"account")).length()==0&&((EditText)field(freshCredential,"secret")).length()==0));main(()->{restore.invoke(recreated,9);return null;});check("invalidRestoreMethodIsIgnored",main(()->Integer.valueOf(2).equals(selected.invoke(recreated))));main(()->{recreated.close();return null;});
        check("noCommunityMutationExecuted",mutations==0);report.put("completed",true).put("passed",true).put("assertions",assertions);
    }
    @Override public void onStart(){try {
        if(!authorized||!"cn.ngapure.communityview".equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_SYNTHETIC_REQUIRED");
        activity=startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),CommunityViewActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();if(observeBaseline)observe();else strict();
    }catch(Exception|AssertionError failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName()).put("assertions",assertions);}catch(Exception ignored){}
    }finally{try{main(()->{for(NativeCommunityView owner:owners)owner.close();if(activity!=null)activity.finish();return null;});report.put("ownUidOnly",true).put("internetPermission",false).put("syntheticReadCompletions",reads).put("communityMutationCount",mutations).put("realClientStarted",false).put("realAccountAccessed",false).put("localDraftOnly",true);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_community_view_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
