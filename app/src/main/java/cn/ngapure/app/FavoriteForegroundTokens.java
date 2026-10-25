package cn.ngapure.app;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Activity lifecycle ownership only; contains no account, context, credential or work callback. */
final class FavoriteForegroundTokens {
    static final class Ticket {
        final Object token;
        private final Object generation;
        private Ticket(Object token,Object generation){this.token=token;this.generation=generation;}
    }
    private final Map<Object,Object> active=new IdentityHashMap<>();
    synchronized void foreground(Object token){
        if(token==null)throw new IllegalArgumentException("Missing foreground token");
        active.put(token,new Object());
    }
    synchronized void background(Object token){active.remove(token);}
    synchronized List<Ticket> snapshot(){
        List<Ticket> result=new ArrayList<>();
        for(Map.Entry<Object,Object> entry:active.entrySet())result.add(new Ticket(entry.getKey(),entry.getValue()));
        return result;
    }
    synchronized List<Object> current(List<Ticket> tickets){
        List<Object> result=new ArrayList<>();
        for(Ticket ticket:tickets)if(active.get(ticket.token)==ticket.generation)result.add(ticket.token);
        return result;
    }
}
