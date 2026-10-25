package cn.ngapure.app;
import java.util.List;
public final class FavoriteForegroundTokensTest {
 private static int checks;private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
 public static void main(String[] args){
  FavoriteForegroundTokens tokens=new FavoriteForegroundTokens();Object a=new String("same"),b=new String("same");
  check(tokens.snapshot().isEmpty(),"initially empty");tokens.foreground(a);List<FavoriteForegroundTokens.Ticket> first=tokens.snapshot();check(tokens.current(first).get(0)==a,"active generation valid");
  tokens.foreground(b);check(tokens.snapshot().size()==2,"equal-but-distinct activity tokens remain independent");List<FavoriteForegroundTokens.Ticket> both=tokens.snapshot();
  tokens.background(a);check(tokens.current(first).isEmpty(),"stopped ticket invalid");check(tokens.current(both).size()==1&&tokens.current(both).get(0)==b,"old stop cannot hide replacement activity");
  tokens.foreground(a);check(tokens.current(first).isEmpty(),"same token resume cannot revive older generation");check(tokens.current(both).size()==1,"old batch excludes resumed generation");
  List<FavoriteForegroundTokens.Ticket> resumed=tokens.snapshot();tokens.foreground(a);check(tokens.current(resumed).size()==1,"repeated start invalidates only its earlier ticket");
  check(tokens.current(tokens.snapshot()).size()==2,"new batch retains all current activity intents");tokens.background(a);tokens.background(b);check(tokens.snapshot().isEmpty(),"all stops release token references");
  tokens.background(new Object());check(tokens.snapshot().isEmpty(),"unknown stop is harmless");
  try{tokens.foreground(null);throw new AssertionError("null accepted");}catch(IllegalArgumentException expected){checks++;}
  System.out.println("FavoriteForegroundTokens: "+checks+" assertions passed; pure Java lifecycle identities");
 }
}
