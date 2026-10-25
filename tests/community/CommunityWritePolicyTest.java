package cn.ngapure.app;

/** Delivery contract checks on the same gate used by the real native editor. */
public final class CommunityWritePolicyTest {
    private static int checks;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void illegal(Runnable action,String label){try{action.run();throw new AssertionError(label);}catch(IllegalStateException expected){checks++;}}
    public static void main(String[] args) {
        CommunityWritePolicy draft=new CommunityWritePolicy();ok(draft.canSubmit(),"new draft ready");
        ok(draft.begin(),"first explicit submit admitted");ok(!draft.begin()&&!draft.canSubmit(),"double tap is single flight");
        draft.failed(false);ok(draft.canSubmit(),"definite rejection permits correction");ok(draft.begin(),"new confirmed attempt admitted");
        draft.failed(true);ok(draft.uncertain()&&!draft.canSubmit(),"unknown delivery locks original draft");
        for(int i=0;i<6;i++)ok(!draft.begin(),"edits or repeated clicks cannot re-admit uncertain draft");
        illegal(()->draft.failed(false),"late rejection cannot unlock uncertain outcome");
        illegal(draft::confirmed,"late acknowledgement cannot silently reset locked draft");
        ok(draft.uncertain()&&!draft.canSubmit(),"read verification leaves delivery lock unchanged");
        CommunityWritePolicy accepted=new CommunityWritePolicy();ok(accepted.begin(),"separate draft begins");accepted.confirmed();
        ok(!accepted.canSubmit()&&!accepted.begin(),"acknowledged draft cannot send twice");
        illegal(()->accepted.failed(false),"stale failure cannot reopen acknowledged draft");
        ok(draft.uncertain(),"separate draft never modifies retained uncertain draft");
        CommunityWritePolicy restored=new CommunityWritePolicy();restored.restoreUncertain();
        ok(restored.uncertain()&&!restored.canSubmit()&&!restored.begin(),"persisted pending restores locked");
        illegal(restored::restoreUncertain,"restore cannot reset an existing gate");
        illegal(()->restored.failed(false),"read verification cannot reopen restored pending gate");
        System.out.println("CommunityWritePolicy: "+checks+" delivery assertions PASS");
    }
}
