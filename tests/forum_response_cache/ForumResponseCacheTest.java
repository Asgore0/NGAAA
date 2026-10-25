package cn.ngapure.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Real cache/JSON/JCE; only clock, directory and AndroidKeyStore key retrieval are injected. */
public final class ForumResponseCacheTest {
    private static final String SCOPE=repeat('a',64),OTHER=repeat('b',64);
    private static final SecretKey KEY=new SecretKeySpec(new byte[32],"AES");
    private static File root;private static int checks;
    private static final class Clock implements ForumResponseCache.Clock {
        long elapsed=50000,wall=1800000000000L;String boot="boot-a";
        public long elapsed(){return elapsed;}public long wall(){return wall;}public String boot(){return boot;}
        void advance(long ms){elapsed+=ms;wall+=ms;}
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable run){tasks.add(run);}void drain(){while(!tasks.isEmpty())tasks.remove().run();}
    }
    private static final class Fixture {
        final String identity=UUID.randomUUID().toString();final File directory=new File(root,identity);final Clock clock=new Clock();final Queue queue=new Queue();
        final ForumResponseCache cache=new ForumResponseCache(identity,directory,()->KEY,clock,queue);
        ForumResponseCache restarted(){return new ForumResponseCache(UUID.randomUUID().toString(),directory,()->KEY,clock,queue);}
        boolean put(String route,String parent,NgaApi.ForumPage page){return cache.putIfCurrent(SCOPE,route,parent,page,cache.capture());}
        ForumResponseCache.Entry load(){return cache.load(SCOPE,"123","",1);}
    }
    private static String repeat(char c,int count){char[] value=new char[count];Arrays.fill(value,c);return new String(value);}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    private static NgaApi.ForumPage page(String route){
        NgaApi.ForumPage p=new NgaApi.ForumPage();p.key=route;p.fid=route.startsWith("stid:")?"":route;p.stid=route.startsWith("stid:")?route.substring(5):"";p.name="Synthetic private board";
        NgaApi.Topic t=new NgaApi.Topic();t.tid="456";t.title="Synthetic fixture title 😅";t.author="Fixture User";t.authorId="789";t.time="10-25 12:30";t.forum=p.name;t.fid=p.fid;t.image="https://example.invalid/image.png";t.avatar="https://example.invalid/avatar.png";t.replies=7;t.votes.likesKnown=true;t.votes.likes=8;t.votes.dislikesKnown=true;t.votes.dislikes=3;t.votes.scoreKnown=true;t.votes.score=5;p.topics.add(t);
        NgaApi.Forum child=new NgaApi.Forum();child.key="321";child.fid="321";child.parentFid="123";child.name="Child fixture";child.category="category";child.description="description";child.icon="https://example.invalid/icon.png";p.subForums.add(child);
        p.requestNanos=999;p.requestCount=1;p.responseBytes=77;return p;
    }
    private static File[] data(File dir){File[] files=dir.listFiles((d,n)->n.endsWith(".bin"));return files==null?new File[0]:files;}
    private static void basic()throws Exception{
        Fixture f=new Fixture();check(!f.directory.exists(),"constructor performs no disk work");check(f.load()==null&&!f.directory.exists(),"cold miss does not create a directory or generation");
        Files.createDirectories(f.directory.toPath());check(f.load()==null&&f.directory.list().length==0,"missing marker read does not create or prune");
        File invalidMarker=new File(f.directory,"generation");Files.write(invalidMarker.toPath(),"invalid-marker".getBytes(StandardCharsets.US_ASCII));check(f.load()==null&&new String(Files.readAllBytes(invalidMarker.toPath()),StandardCharsets.US_ASCII).equals("invalid-marker"),"invalid marker read does not repair or write");
        NgaApi.ForumPage p=page("123");check(f.put("123","",p),"write first page");
        ForumResponseCache.Entry hit=f.load();check(hit!=null&&hit.source.equals("memory")&&hit.ageMs==0,"memory source and initial age");
        check(hit.page.topics.get(0).title.equals(p.topics.get(0).title)&&hit.page.topics.get(0).votes.dislikes==3&&hit.page.subForums.get(0).parentFid.equals("123"),"canonical forum topic votes child fields retained");
        check(hit.page.requestNanos==0&&hit.page.requestCount==0&&hit.page.responseBytes==0,"cached copy has no inherited network timings");
        p.topics.get(0).title="mutated input";p.subForums.get(0).name="mutated child";p.topics.get(0).votes.likes=999;
        hit.page.topics.get(0).title="mutated output";hit.page.topics.get(0).votes.dislikes=999;hit.page.subForums.clear();
        hit=f.load();check(hit.page.topics.get(0).title.equals("Synthetic fixture title 😅")&&hit.page.topics.get(0).votes.likes==8&&hit.page.topics.get(0).votes.dislikes==3&&hit.page.subForums.size()==1,"input and output deeply isolated");
        byte[] bytes=Files.readAllBytes(data(f.directory)[0].toPath());String fileText=new String(bytes,StandardCharsets.ISO_8859_1);check(!fileText.contains("Synthetic")&&!fileText.contains("Fixture User")&&!fileText.contains(SCOPE),"encrypted files contain no fixture content or scope");
        check(data(f.directory)[0].getName().matches("[a-f0-9]{32}-[a-f0-9]{64}\\.bin"),"opaque generation and digest filename");
        f.clock.advance(1000);f.cache.trimMemory();hit=f.load();check(hit!=null&&hit.source.equals("disk")&&hit.ageMs==1000,"trim reads disk preserving original age");
        hit=f.restarted().load(SCOPE,"123","",1);check(hit!=null&&hit.source.equals("disk")&&hit.ageMs==1000,"fresh process state reads encrypted disk");
        check(f.cache.load(OTHER,"123","",1)==null,"exact session scope isolated");check(f.cache.load(SCOPE,"124","",1)==null,"route isolated");check(f.cache.load(SCOPE,"123","999",1)==null,"parent isolated");
        check(f.cache.load(SCOPE,"123","",2)==null,"page two never cached");NgaApi.ForumPage second=page("123");second.page=2;check(!f.put("123","",second),"page two writes rejected");
        check(f.cache.load("raw-cookie","123","",1)==null&&!f.cache.putIfCurrent("raw-cookie","123","",page("123"),f.cache.capture()),"credentials cannot be used as cache scope");
        check(f.cache.load(SCOPE,"../123","",1)==null&&f.cache.load(SCOPE,"123","../",1)==null,"invalid route and parent rejected");
        check(!f.put("124","",page("123")),"mismatched payload route rejected");
        check(f.put("stid:777","123",page("stid:777"))&&f.cache.load(SCOPE,"stid:777","123",1)!=null&&f.cache.load(SCOPE,"777","123",1)==null,"collection and ordinary board distinct");
    }
    private static void expiry()throws Exception{
        Fixture f=new Fixture();f.put("123","",page("123"));f.clock.advance(29000);check(f.load().ageMs==29000,"29 second memory hit");f.clock.advance(999);check(f.load().ageMs==29999,"TTL last millisecond hit");f.clock.advance(1);check(f.load()==null&&f.restarted().load(SCOPE,"123","",1)==null,"30 second boundary expires memory and disk without sliding");
        Fixture backward=new Fixture();backward.put("123","",page("123"));backward.clock.elapsed--;check(backward.load()==null,"monotonic rollback rejected");
        Fixture wall=new Fixture();wall.put("123","",page("123"));wall.cache.trimMemory();wall.clock.wall--;check(wall.load()==null,"disk wall-clock rollback rejected");
        Fixture future=new Fixture();future.put("123","",page("123"));future.cache.trimMemory();future.clock.wall+=30000;check(future.load()==null,"disk forward wall expiry rejected");
        Fixture reboot=new Fixture();reboot.put("123","",page("123"));reboot.cache.trimMemory();reboot.clock.boot="boot-b";check(reboot.load()==null,"boot mismatch rejected even if timestamps appear fresh");
        Fixture unavailable=new Fixture();unavailable.clock.boot="";check(unavailable.put("123","",page("123"))&&unavailable.load()!=null&&data(unavailable.directory).length==0,"no boot identity keeps memory only");
        Fixture queued=new Fixture();queued.cache.storeAsync(SCOPE,"123","",page("123"),queued.cache.capture());queued.clock.advance(5000);queued.queue.drain();check(queued.load().ageMs==5000,"async queue delay does not renew loaded time");
        Fixture stale=new Fixture();stale.cache.storeAsync(SCOPE,"123","",page("123"),stale.cache.capture());stale.clock.advance(30000);stale.queue.drain();check(stale.load()==null&&data(stale.directory).length==0,"expired queued snapshot is not written");
    }
    private static void epochs()throws Exception{
        Fixture f=new Fixture();ForumResponseCache.Ticket old=f.cache.capture();f.put("123","",page("123"));byte[] marker=Files.readAllBytes(new File(f.directory,"generation").toPath());
        ForumResponseCache sibling=new ForumResponseCache(f.identity,f.directory,()->KEY,f.clock,f.queue);f.cache.invalidate();
        check(f.load()==null&&sibling.load(SCOPE,"123","",1)==null,"pending invalidation blocks old disk reads across instances");
        check(Arrays.equals(marker,Files.readAllBytes(new File(f.directory,"generation").toPath())),"invalidate itself performs no disk operation");
        check(!sibling.putIfCurrent(SCOPE,"123","",page("123"),old),"old instance ticket rejected");
        check(f.put("123","",page("123"))&&f.load().source.equals("memory"),"new epoch can retain memory while disk barrier pending");
        check(Arrays.equals(marker,Files.readAllBytes(new File(f.directory,"generation").toPath())),"pending write cannot touch disk");
        f.queue.drain();check(data(f.directory).length==0&&!Arrays.equals(marker,Files.readAllBytes(new File(f.directory,"generation").toPath())),"worker retires old generation");
        f.cache.trimMemory();check(f.load()==null&&f.restarted().load(SCOPE,"123","",1)==null,"retired disk not restored after memory trim or restart");
        Fixture other=new Fixture();check(!other.cache.putIfCurrent(SCOPE,"123","",page("123"),f.cache.capture()),"ticket belongs to one shared cache identity");
        f.put("123","",page("123"));for(int i=0;i<100;i++)f.cache.invalidate();check(f.queue.tasks.size()==1,"100 rapid invalidations coalesce into one disk rotation");f.queue.drain();check(f.load()==null&&f.put("123","",page("123")),"coalesced invalidations end at newest epoch");
        Fixture queued=new Fixture();queued.cache.storeAsync(SCOPE,"123","",page("123"),queued.cache.capture());queued.cache.invalidate();queued.queue.drain();check(queued.load()==null,"queued old network completion cannot resurrect after invalidate");
    }
    private static void limits()throws Exception{
        Fixture f=new Fixture();for(int i=0;i<7;i++)check(f.put(String.valueOf(100+i),"",page(String.valueOf(100+i))),"bounded small write "+i);
        check(data(f.directory).length==6,"disk count bounded at six without over-evicting all");
        Fixture large=new Fixture();for(int i=0;i<6;i++){NgaApi.ForumPage p=page(String.valueOf(200+i));p.topics.get(0).title=repeat('x',235000);check(large.put(p.key,"",p),"large allowed entry "+i);}
        long bytes=0;for(File file:data(large.directory)){bytes+=file.length();check(file.length()<=ForumResponseCache.MAX_ENTRY_BYTES,"single entry bounded");}check(bytes<=ForumResponseCache.MAX_TOTAL_BYTES&&data(large.directory).length==4,"aggregate disk payload bounded at one MiB");
        check(large.cache.load(SCOPE,"200","",1)==null&&large.cache.load(SCOPE,"205","",1)!=null,"aggregate memory eviction retains newest snapshot");
        NgaApi.ForumPage huge=page("123");huge.topics.get(0).title=repeat('q',ForumResponseCache.MAX_ENTRY_BYTES);Fixture reject=new Fixture();check(!reject.put("123","",huge)&&reject.load()==null&&data(reject.directory).length==0,"oversized entry rejected entirely");
        NgaApi.ForumPage many=page("123");for(int i=0;i<128;i++)many.topics.add(new NgaApi.Topic());check(!reject.put("123","",many),"too many topics rejected");
        NgaApi.ForumPage invalid=page("123");invalid.topics.get(0).title=null;check(!reject.put("123","",invalid),"malformed null canonical value rejected");
    }
    private static void corruption()throws Exception{
        Fixture f=new Fixture();f.put("123","",page("123"));File source=data(f.directory)[0];byte[] envelope=Files.readAllBytes(source.toPath());envelope[envelope.length-1]^=1;Files.write(source.toPath(),envelope);f.cache.trimMemory();check(f.load()==null,"GCM authentication rejects modified ciphertext");
        Fixture swap=new Fixture();swap.put("123","",page("123"));File first=data(swap.directory)[0];byte[] firstBytes=Files.readAllBytes(first.toPath());swap.put("124","",page("124"));File second=null;for(File file:data(swap.directory))if(!file.equals(first))second=file;Files.write(second.toPath(),firstBytes);swap.cache.trimMemory();check(swap.cache.load(SCOPE,"124","",1)==null,"AAD prevents route filename substitution");
        Fixture wrong=new Fixture();wrong.put("123","",page("123"));ForumResponseCache otherKey=new ForumResponseCache(UUID.randomUUID().toString(),wrong.directory,()->new SecretKeySpec(new byte[16],"AES"),wrong.clock,wrong.queue);check(otherKey.load(SCOPE,"123","",1)==null,"different key cannot read ciphertext");
        Fixture absent=new Fixture();ForumResponseCache noKey=new ForumResponseCache(UUID.randomUUID().toString(),absent.directory,()->{throw new Exception("key unavailable");},absent.clock,absent.queue);check(noKey.putIfCurrent(SCOPE,"123","",page("123"),noKey.capture())&&noKey.load(SCOPE,"123","",1)!=null,"Keystore failure retains safe memory result");noKey.trimMemory();check(noKey.load(SCOPE,"123","",1)==null,"Keystore failure disk miss");
        Fixture file=new Fixture();Files.createDirectories(file.directory.getParentFile().toPath());Files.write(file.directory.toPath(),new byte[]{1});check(file.put("123","",page("123"))&&file.load()!=null,"unwritable cache directory retains memory");file.cache.invalidate();file.queue.drain();check(file.load()==null,"failed invalidation disk remains disabled");
    }
    private static void concurrency()throws Exception{
        File dir=new File(root,UUID.randomUUID().toString());Clock clock=new Clock();Queue queue=new Queue();CountDownLatch keyEntered=new CountDownLatch(1),release=new CountDownLatch(1);
        ForumResponseCache cache=new ForumResponseCache(UUID.randomUUID().toString(),dir,()->{keyEntered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new Exception("timeout");return KEY;},clock,queue);
        ForumResponseCache.Ticket old=cache.capture();Thread writer=new Thread(()->cache.putIfCurrent(SCOPE,"123","",page("123"),old));writer.start();check(keyEntered.await(2,TimeUnit.SECONDS),"writer paused in real encryption key retrieval");
        long start=System.nanoTime();cache.invalidate();cache.capture();cache.trimMemory();long duration=System.nanoTime()-start;check(duration<100000000L,"UI epoch operations do not wait for encryption or storage");release.countDown();writer.join(3000);check(!writer.isAlive(),"writer completed after release");queue.drain();check(cache.load(SCOPE,"123","",1)==null&&data(dir).length==0,"inflight encrypted write cannot repopulate retired epoch");
    }
    public static void main(String[] args)throws Exception{root=new File(args[0],UUID.randomUUID().toString());Files.createDirectories(root.toPath());basic();expiry();epochs();limits();corruption();concurrency();System.out.println("ForumResponseCache: "+checks+" checks passed");}
}
