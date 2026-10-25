package cn.ngapure.draftfixturev196;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import cn.ngapure.app.NativeDrafts;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual Android commit(false) after thread interruption, isolated storage and no network. */
public final class InterruptedCommitProbe extends Instrumentation {
    private final JSONArray checks=new JSONArray();private boolean authorized;private int assertions;
    private void check(boolean value,String name)throws Exception{assertions++;checks.put(new JSONObject().put("check",name).put("passed",value));if(!value)throw new IllegalStateException(name);}
    private interface Work{void run()throws Exception;}
    private void interrupted(Work action)throws Exception{
        AtomicReference<Throwable> failure=new AtomicReference<>();Thread worker=new Thread(()->{
            try{Thread.currentThread().interrupt();action.run();}
            catch(Throwable error){failure.set(error);}
            finally{Thread.interrupted();}
        },"SyntheticInterruptedCommit");
        worker.start();worker.join(10000);
        if(worker.isAlive()){worker.interrupt();worker.join(2000);throw new IllegalStateException("InterruptedCommitWorkerMustFinish");}
        Throwable error=failure.get();if(error instanceof Exception)throw (Exception)error;if(error!=null)throw new IllegalStateException("InterruptedCommitWorkerFailed",error);
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("offlineInterruptedCommit"));start();}
    private static NativeDrafts.Draft submission(NativeDrafts store,String body)throws Exception{
        Method method=NativeDrafts.class.getDeclaredMethod("saveForSubmission",String.class,String.class,String.class,String.class,String.class,String.class,java.util.List.class);method.setAccessible(true);
        try{return (NativeDrafts.Draft)method.invoke(store,"reply","7","1","","",body,Collections.emptyList());}
        catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw error;}
    }
    private static Object journal(SharedPreferences preferences)throws Exception{
        Class<?> type=Class.forName("cn.ngapure.app.NativeThreadActions$PersistentJournal");Constructor<?> constructor=type.getDeclaredConstructor(SharedPreferences.class);constructor.setAccessible(true);return constructor.newInstance(preferences);
    }
    private static boolean entry(Object journal,String action,String key)throws Exception{Method method=journal.getClass().getDeclaredMethod(action,String.class);method.setAccessible(true);return (Boolean)method.invoke(journal,key);}
    @Override public void onStart(){boolean passed=false,cleanup=false;String failure="NONE";SharedPreferences raw=null,drafts=null,actions=null;try{
        check(authorized,"ExplicitInterruptedCommitProbeAuthorization");Context context=getTargetContext();
        check(android.os.Process.myUid()==context.getApplicationInfo().uid,"HelperUsesItsOwnUid");String[] permissions=context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_PERMISSIONS).requestedPermissions;boolean noInternet=true;
        if(permissions!=null)for(String permission:permissions)if("android.permission.INTERNET".equals(permission))noInternet=false;check(noInternet,"NoInternetPermission");
        raw=context.getSharedPreferences("synthetic_interrupt_control",Context.MODE_PRIVATE);check(raw.edit().clear().putString("value","old").commit(),"ActualPreferencesLoadedBeforeInterrupt");
        SharedPreferences control=raw;
        interrupted(()->{
            check(Thread.currentThread().isInterrupted(),"ControlThreadIsInterruptedBeforeCommit");
            check(!control.edit().putString("value","changed-before-false").commit(),"ActualAndroidCommitReturnsFalseForInterruptedCaller");
            check("changed-before-false".equals(control.getString("value","")),"InterruptedFalseCommitHasAlreadyChangedLivePreferences");
            check(!Thread.currentThread().isInterrupted(),"FrameworkAwaitConsumesTheInterruptBeforeRollback");
        });
        drafts=context.getSharedPreferences("nga_native_drafts",Context.MODE_PRIVATE);check(drafts.edit().clear().commit(),"OnlyOwnProductionDraftStoreReset");NativeDrafts store=new NativeDrafts(context);
        store.save("reply","7","1","","Existing title","Original editable content");check(drafts.edit().putBoolean("unrelated",true).commit(),"ExistingEditableDraftReachesDiskBeforeInterruption");
        int[] sendPermissions={0};
        interrupted(()->{boolean refused=false;try{submission(store,"Interrupted attempted content");sendPermissions[0]++;}catch(IllegalStateException expected){refused=true;}check(refused,"InterruptedSubmissionNeverGrantsSendPermission");});
        NativeDrafts.Draft recovered=store.get("reply","7","1","");
        check(sendPermissions[0]==0&&recovered!=null&&!recovered.submissionUnconfirmed&&recovered.content.equals("Original editable content"),"InterruptedDraftCommitRestoresExactEditableRecoveryState");
        check(new NativeDrafts(context).get("reply","7","1","").updatedAt==recovered.updatedAt,"ReplacementEditorSeesSameRestoredVersion");
        check(submission(store,"Later explicit successful synthetic confirmation").submissionUnconfirmed,"NewNonInterruptedConfirmationCreatesRealPendingMarker");

        actions=context.getSharedPreferences("synthetic_interrupt_journal",Context.MODE_PRIVATE);check(actions.edit().clear().commit(),"OnlyOwnJournalLoadedBeforeInterruption");Object journal=journal(actions);
        interrupted(()->check(!entry(journal,"put","synthetic-action"),"InterruptedJournalPreparationReportsNoSavedPermission"));
        check(!entry(journal,"contains","synthetic-action"),"InterruptedJournalPreparationDoesNotInventUnknownRequest");
        check(entry(journal,"put","synthetic-action"),"NewExplicitJournalPreparationCanSucceed");
        interrupted(()->check(!entry(journal,"remove","synthetic-action"),"InterruptedAcknowledgementCleanupReportsUncertainty"));
        check(entry(journal,"contains","synthetic-action"),"InterruptedCleanupRetainsRealPendingGuard");
        check(entry(journal,"remove","synthetic-action")&&!entry(journal,"contains","synthetic-action"),"SubsequentSuccessfulSettlementRetiresExactGuard");passed=true;
    }catch(Throwable error){failure=error instanceof IllegalStateException&&error.getMessage()!=null?error.getMessage():error.getClass().getSimpleName();}
    finally{
        try{boolean a=raw==null||raw.edit().clear().commit(),b=drafts==null||drafts.edit().clear().commit(),c=actions==null||actions.edit().clear().commit();cleanup=a&&b&&c;}catch(Exception ignored){cleanup=false;}
        JSONObject report=new JSONObject();try{report.put("schema",1).put("purpose","OWN_UID_REAL_ANDROID_INTERRUPTED_COMMIT_FAILURE").put("passed",passed&&cleanup).put("failure",failure).put("assertions",assertions).put("checks",checks).put("cleanupPassed",cleanup).put("realSharedPreferences",true).put("diskFailureMethod","pre-interrupted caller of actual Android SharedPreferences.commit").put("ownUid",true).put("internetPermission",false).put("realAccountReads",0).put("realCommunityWrites",0).put("realNetworkRequests",0);}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("safe_interrupted_commit_report",report.toString());finish(passed&&cleanup?0:1,result);
    }}
}
