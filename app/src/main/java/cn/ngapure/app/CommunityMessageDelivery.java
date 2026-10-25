package cn.ngapure.app;

/** Completes a durable PM attempt before any optional, lifecycle-bound UI callback. */
final class CommunityMessageDelivery {
    interface Sender { NativeCommunity.MutationResult send() throws Exception; }

    static final class Result {
        final NativeCommunity.MutationResult acknowledgement;
        final Exception error;
        /** Whether this exact attempt's pending lock was durably retired. */
        final boolean snapshotCleared;
        /** Keep the editor locked when delivery or local recovery state is unresolved. */
        final boolean uncertain;

        private Result(NativeCommunity.MutationResult acknowledgement,Exception error,
                boolean snapshotCleared,boolean uncertain) {
            this.acknowledgement=acknowledgement;this.error=error;
            this.snapshotCleared=snapshotCleared;this.uncertain=uncertain;
        }
    }

    private CommunityMessageDelivery() {}

    static Result run(NativeMessageDrafts drafts,NativeMessageDrafts.Draft pending,Sender sender) {
        if(drafts==null||pending==null||!pending.pending||sender==null)
            return unknown(new IllegalStateException("缺少已保存的待核对私信快照，本次没有发送"));
        final NativeCommunity.MutationResult acknowledgement;
        try {
            acknowledgement=sender.send();
            if(acknowledgement==null||!acknowledgement.confirmed)
                return unknown(new NativeCommunity.CommunityException(NativeCommunity.Code.UNCONFIRMED,0));
        } catch(Exception error) {
            if(!definitelyRejected(error))return unknown(error);
            try {
                boolean cleared=drafts.rejected(pending);
                return new Result(null,error,cleared,!cleared);
            } catch(Exception cleanupFailure) {
                return new Result(null,cleanupFailure,false,true);
            }
        }
        try {
            boolean cleared=drafts.confirmed(pending);
            return new Result(acknowledgement,null,cleared,!cleared);
        } catch(Exception cleanupFailure) {
            // The server acknowledgement remains valid even if local recovery cleanup failed.
            return new Result(acknowledgement,cleanupFailure,false,true);
        }
    }

    /** Unexpected worker failures must never be interpreted as permission to resend. */
    static Result unknown(Exception error) {
        return new Result(null,error==null
                ?new NativeCommunity.CommunityException(NativeCommunity.Code.UNCONFIRMED,0):error,false,true);
    }

    private static boolean definitelyRejected(Exception error) {
        if(error instanceof IllegalArgumentException)return true;
        if(!(error instanceof NativeCommunity.CommunityException))return false;
        NativeCommunity.Code code=((NativeCommunity.CommunityException)error).code;
        return code==NativeCommunity.Code.LOGIN_REQUIRED||code==NativeCommunity.Code.CHALLENGE_REQUIRED
                ||code==NativeCommunity.Code.PERMISSION_DENIED||code==NativeCommunity.Code.RATE_LIMIT
                ||code==NativeCommunity.Code.UNSUPPORTED;
    }
}
