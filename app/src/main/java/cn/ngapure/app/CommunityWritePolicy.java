package cn.ngapure.app;

/** A single draft's delivery gate. An uncertain send cannot be retried by editing or callbacks. */
final class CommunityWritePolicy {
    private enum State { EDITING, IN_FLIGHT, CONFIRMED, UNCERTAIN }
    private State state=State.EDITING;

    boolean canSubmit(){return state==State.EDITING;}
    boolean uncertain(){return state==State.UNCERTAIN;}
    void restoreUncertain(){if(!canSubmit())throw new IllegalStateException("Draft already active");state=State.UNCERTAIN;}
    boolean begin(){if(!canSubmit())return false;state=State.IN_FLIGHT;return true;}
    void confirmed(){requireFlight();state=State.CONFIRMED;}
    void failed(boolean deliveryUnknown){requireFlight();state=deliveryUnknown?State.UNCERTAIN:State.EDITING;}
    private void requireFlight(){if(state!=State.IN_FLIGHT)throw new IllegalStateException("No pending draft submission");}
}
