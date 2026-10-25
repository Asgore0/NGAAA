package cn.ngapure.app;import android.content.Context;
public final class NativeCommunity {
 enum Code {LOGIN_REQUIRED,CHALLENGE_REQUIRED,PERMISSION_DENIED,RATE_LIMIT,NETWORK,SERVER,INVALID_RESPONSE,UNCONFIRMED}
 static final class CommunityException extends Exception {private static final long serialVersionUID=1L;final Code code;CommunityException(Code code){this.code=code;}}
 final NgaStore.CheckInSession session;private NativeCommunity(NgaStore.CheckInSession session){this.session=session;}static NativeCommunity exactForumFavorites(Context app,NgaStore.CheckInSession session){return new NativeCommunity(session);}
}
