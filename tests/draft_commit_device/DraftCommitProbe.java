package cn.ngapure.draftfixturev196;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.system.Os;
import cn.ngapure.app.NativeDrafts;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Android preferences and denied app-owned disk writes; no mocks, accounts, sockets or UI. */
public final class DraftCommitProbe extends Instrumentation {
    private final JSONArray checks=new JSONArray();
    private boolean authorized,journalOnly;private int assertions;private File directory;
    private void check(boolean value,String name)throws Exception{assertions++;checks.put(new JSONObject().put("check",name).put("passed",value));if(!value)throw new IllegalStateException(name);}
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("offlineDraftCommit"));journalOnly=args!=null&&"true".equals(args.getString("threadJournalOnly"));start();}
    private interface Work{void run()throws Exception;}
    private void denied(Work action)throws Exception{Os.chmod(directory.getAbsolutePath(),0500);try{action.run();}finally{Os.chmod(directory.getAbsolutePath(),0700);}}
    private void mustFail(Work action,String name)throws Exception{boolean failed=false;try{action.run();}catch(IllegalStateException expected){failed=true;}check(failed,name);}
    private static NativeDrafts.Draft submission(NativeDrafts store,String tid,String body)throws Exception{
        Method method=NativeDrafts.class.getDeclaredMethod("saveForSubmission",String.class,String.class,String.class,String.class,String.class,String.class,java.util.List.class);method.setAccessible(true);
        try{return (NativeDrafts.Draft)method.invoke(store,"reply","7",tid,"","",body,Collections.emptyList());}
        catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw error;}
    }
    private static boolean rejected(NativeDrafts store,NativeDrafts.Draft expected)throws Exception{
        Method method=NativeDrafts.class.getDeclaredMethod("rejected",NativeDrafts.Draft.class);method.setAccessible(true);
        try{return (Boolean)method.invoke(store,expected);}
        catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw error;}
    }
    private static Object journal(SharedPreferences preferences)throws Exception{
        Class<?> type=Class.forName("cn.ngapure.app.NativeThreadActions$PersistentJournal");Constructor<?> constructor=type.getDeclaredConstructor(SharedPreferences.class);constructor.setAccessible(true);return constructor.newInstance(preferences);
    }
    private static boolean entry(Object journal,String action,String key)throws Exception{Method method=journal.getClass().getDeclaredMethod(action,String.class);method.setAccessible(true);return (Boolean)method.invoke(journal,key);}
    private void journalChecks(SharedPreferences preferences)throws Exception{
        check(preferences.edit().clear().commit(),"OnlyOwnSyntheticJournalStartsEmpty");Object journal=journal(preferences);
        denied(()->{
            check(!entry(journal,"put","synthetic-pending"),"RealDiskFailureDoesNotSaveActionSendPermission");
            check(!entry(journal,"contains","synthetic-pending"),"FailedJournalPreparationCannotInventUnknownRequest");
        });
        check(preferences.edit().putString("unrelated","flush").commit(),"UnrelatedJournalWriteCanFlushAfterDiskRecovers");
        check(!preferences.contains("synthetic-pending"),"UnrelatedFlushCannotPersistUnstartedActionMarker");
        check(entry(journal,"put","synthetic-pending")&&entry(journal,"contains","synthetic-pending"),"NewExplicitSavedPreparationCreatesRealMarker");
        Map<String,?> before=new HashMap<>(preferences.getAll());
        denied(()->{
            check(!entry(journal,"remove","synthetic-pending"),"RealFailedAcknowledgementCleanupReportsUncertainty");
            check(preferences.getAll().equals(before),"FailedJournalCleanupRestoresPendingMarkerExactly");
        });
        check(entry(journal,"remove","synthetic-pending")&&!entry(journal,"contains","synthetic-pending"),"WritableAcknowledgementCanRetireItsPendingMarker");
    }
    @Override public void onStart(){boolean passed=false,cleanup=false;String failure="NONE";SharedPreferences raw=null,storage=null;try{
        check(authorized,"ExplicitOfflineDraftCommitAuthorization");Context context=getTargetContext();
        check(android.os.Process.myUid()==context.getApplicationInfo().uid,"HelperUsesItsOwnUid");
        String[] permissions=context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_PERMISSIONS).requestedPermissions;boolean noInternet=true;
        if(permissions!=null)for(String permission:permissions)if("android.permission.INTERNET".equals(permission))noInternet=false;
        check(noInternet,"NoInternetPermission");
        directory=new File(context.getApplicationInfo().dataDir,"shared_prefs");
        raw=context.getSharedPreferences("synthetic_android_commit",Context.MODE_PRIVATE);
        check(raw.edit().putString("draft:raw","old").commit(),"CreateSyntheticPreferencesOnRealDisk");
        SharedPreferences systemPreferences=raw;
        denied(()->{
            check(!systemPreferences.edit().putString("draft:raw","changed-in-live-map").commit(),"RealAndroidCommitFailsWhenItsOwnDirectoryCannotBeWritten");
            check("changed-in-live-map".equals(systemPreferences.getString("draft:raw","")),"SystemSharedPreferencesUpdatesMemoryBeforeFailedDiskCommit");
        });
        check(raw.edit().clear().commit(),"RawControlRestoresWritableDirectory");

        storage=context.getSharedPreferences(journalOnly?"synthetic_thread_action_journal":"nga_native_drafts",Context.MODE_PRIVATE);
        if(journalOnly){journalChecks(storage);passed=true;}else{
        check(storage.edit().clear().commit(),"IsolatedProductionDraftStoreStartsEmpty");
        NativeDrafts store=new NativeDrafts(context);int[] sendPermissions={0};
        denied(()->mustFail(()->{submission(store,"1","Never sent: denied save");sendPermissions[0]++;},"DeniedSubmissionSaveReturnsNoPermission"));
        check(sendPermissions[0]==0&&store.get("reply","7","1","")==null,"FailedSaveDoesNotInventPendingPublishingMarker");
        check(new NativeDrafts(context).get("reply","7","1","")==null,"ReplacementEditorSeesOldEditableState");
        check(storage.edit().putString("unrelated-test-value","flush").commit(),"LaterUnrelatedWriteSucceeds");
        check(!storage.contains("draft:reply:7:1:"),"LaterFlushCannotPersistUnsentMarker");
        NativeDrafts.Draft real=submission(store,"1","Explicitly saved synthetic pending");
        check(real.submissionUnconfirmed,"SuccessfulExplicitSaveCreatesPendingMarker");
        mustFail(()->submission(store,"1","No duplicate"),"GenuinePendingSubmissionCannotBeRepeated");
        Map<String,?> pendingBefore=new HashMap<>(storage.getAll());
        denied(()->mustFail(()->rejected(store,real),"DeniedSettlementFailsWithoutUnlocking"));
        check(storage.getAll().equals(pendingBefore)&&store.get("reply","7","1","").submissionUnconfirmed,"RealPendingMarkerSurvivesFailedSettlementExactly");
        check(rejected(store,real)&&!store.get("reply","7","1","").submissionUnconfirmed,"WritableDefiniteRejectionSettlesExactVersion");

        check(storage.edit().clear().commit(),"ResetOnlyOwnSyntheticDraftsBeforeQuotaCase");
        for(int i=1;i<=20;i++)store.save("reply","7",Integer.toString(i),"","Title "+i,"Synthetic old body "+i);
        // apply() writes are queued; this barrier ensures quota failure is the only denied write.
        check(storage.edit().putString("unrelated-test-value","quota-barrier").commit(),"AllSyntheticQuotaDraftsReachDiskBeforeFailure");
        Map<String,?> quotaBefore=new HashMap<>(storage.getAll());
        denied(()->mustFail(()->submission(store,"21","Denied quota candidate"),"RealDiskFailureRejectsQuotaTransaction"));
        check(storage.getAll().equals(quotaBefore),"RejectedQuotaTransactionRestoresAllOldDraftBytes");
        check(store.getAll().size()==20&&store.get("reply","7","21","")==null,"FailedQuotaWriteRetainsEveryOldDraftAndNoNewSlot");
        check(submission(store,"21","New explicit writable confirmation").submissionUnconfirmed&&store.getAll().size()==20,"LaterExplicitConfirmationAppliesQuotaNormally");
        passed=true;}
    }catch(Throwable error){failure=error instanceof IllegalStateException&&error.getMessage()!=null?error.getMessage():error.getClass().getSimpleName();}
    finally{
        try{if(directory!=null)Os.chmod(directory.getAbsolutePath(),0700);boolean a=raw==null||raw.edit().clear().commit(),b=storage==null||storage.edit().clear().commit();cleanup=a&&b;}catch(Exception ignored){cleanup=false;}
        JSONObject report=new JSONObject();try{report.put("schema",1).put("purpose",journalOnly?"OWN_UID_REAL_ANDROID_ACTION_JOURNAL_COMMIT_FAILURE":"OWN_UID_REAL_ANDROID_DRAFT_COMMIT_FAILURE").put("passed",passed&&cleanup).put("failure",failure).put("assertions",assertions).put("checks",checks).put("cleanupPassed",cleanup).put("realSharedPreferences",true).put("diskFailureMethod","temporary chmod 0500 on helper-only shared_prefs; 0700 restored in finally").put("ownUid",true).put("internetPermission",false).put("realAccountReads",0).put("realCommunityWrites",0).put("realNetworkRequests",0);}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("safe_draft_commit_report",report.toString());finish(passed&&cleanup?0:1,result);
    }}
}
