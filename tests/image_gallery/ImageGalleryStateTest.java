package cn.ngapure.app;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

public final class ImageGalleryStateTest {
    private static int assertions;
    private static final String A="https://img.nga.cn/attachments/mon_202610/23/a.png", B="https://img.nga.cn/attachments/mon_202610/23/b.png", C="https://img.nga.cn/attachments/mon_202610/23/c.png";
    private static void check(boolean pass,String message){assertions++;if(!pass)throw new AssertionError(message);}
    private interface Run {void run()throws Exception;}
    private static void rejects(Run work,String message)throws Exception{boolean bad=false;try{work.run();}catch(IllegalArgumentException expected){bad=true;}check(bad,message);}
    public static void main(String[] args)throws Exception{
        ArrayList<String> input=new ArrayList<>(Arrays.asList(A,B,C));
        ImageGalleryState state=ImageGalleryState.create(input,2,3,false);input.clear();
        check(state.urls.size()==3&&state.index==2&&state.currentUrl().equals(C),"clicked original third index retained independently");
        check(state.counterLabel().equals("3 / 3")&&state.limitLabel().isEmpty(),"exact original image count and current ordinal");
        boolean immutable=false;try{state.urls.add(A);}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"immutable original attachments");
        float[] view={3,.25f,.75f};ImageGalleryState zoomed=state.withViewport(view);view[0]=1;
        check(zoomed.viewport()[0]==3,"viewport input copied");view=zoomed.viewport();view[1]=1;
        check(zoomed.viewport()[1]==.25f,"viewport output copied");
        ImageGalleryState back=ImageGalleryState.decode(zoomed.encode());
        check(back!=null&&back.index==2&&back.urls.equals(state.urls)&&back.viewport()[0]==3,"current index and anchored zoom survive bounded JSON roundtrip");
        check(back.withIndex(0).viewport()==null,"new image starts fitted rather than previous image viewport");
        check(back.withIndex(2)==back,"same page retains viewport");
        for(int i=0;i<100;i++){int n=i%3;ImageGalleryState selected=back.withIndex(n);check(selected.index==n&&selected.currentUrl().equals(Arrays.asList(A,B,C).get(n)),"rapid selection maintains URL/index identity");}
        ImageGalleryState limited=ImageGalleryState.create(Arrays.asList(A,B,C),1,91,true);
        check(limited.counterLabel().equals("2 / 3")&&limited.limitLabel().contains("前 3")&&limited.limitLabel().contains("91"),"bounded browsing counter does not fake access to withheld images");
        check(ImageGalleryState.decode(limited.encode()).sourceTotal==91,"source total survives restoration");
        ImageGalleryState unknown=ImageGalleryState.create(Arrays.asList(A),0,70,false,true);
        check(unknown.limitLabel().contains("至少")&&ImageGalleryState.decode(unknown.encode()).totalKnown==false,"incomplete source is explicitly a lower bound");
        rejects(()->ImageGalleryState.create(Collections.emptyList(),0,0,false),"empty galleries rejected");
        rejects(()->ImageGalleryState.create(Arrays.asList(A,B),2,2,false),"out of range selected image rejected");
        rejects(()->ImageGalleryState.create(Arrays.asList(A,A),0,2,false),"ambiguous duplicate image indices rejected");
        rejects(()->ImageGalleryState.create(Arrays.asList("https://evil.test/a.png"),0,1,false),"external URL rejected");
        rejects(()->ImageGalleryState.create(Arrays.asList("http://img.nga.cn/attachments/a.png"),0,1,false),"state requires already canonical URLs");
        rejects(()->ImageGalleryState.create(Arrays.asList(A,B),0,1,false),"total cannot be less than visible image list");
        rejects(()->ImageGalleryState.create(Arrays.asList(A,B),0,9,false),"truncation cannot be hidden");
        rejects(()->ImageGalleryState.create(Arrays.asList(A),0,100001,true),"unbounded total rejected");
        List<String> many=new ArrayList<>();for(int i=0;i<65;i++)many.add("https://img.nga.cn/attachments/mon_202610/23/p"+i+".png");
        rejects(()->ImageGalleryState.create(many,0,65,false),"gallery bounded to 64 URLs");
        check(ImageGalleryState.decode(null)==null&&ImageGalleryState.decode("")==null,"missing saved gallery ignored");
        check(ImageGalleryState.decode(String.join("",Collections.nCopies(96*1024+1,"x")))==null,"oversize Binder string rejected before parsing");
        for(String malformed:new String[]{"{}","[]","{\"v\":2}","{\"v\":1,\"urls\":null}","not json"})check(ImageGalleryState.decode(malformed)==null,"malformed state rejected");
        for(Object bad:new Object[]{"2",2.5,Boolean.TRUE,JSONObject.NULL}){JSONObject json=new JSONObject(state.encode());json.put("index",bad);check(ImageGalleryState.decode(json.toString())==null,"coerced selected index rejected");}
        for(String field:new String[]{"known","limited"}){JSONObject json=new JSONObject(state.encode());json.put(field,"false");check(ImageGalleryState.decode(json.toString())==null,"coerced completeness flag rejected");}
        for(float[] bad:new float[][]{{Float.NaN,0,0},{Float.POSITIVE_INFINITY,0,0},{0,0,0},{7,0,0},{2,-1,0},{2,0,2},{2,0}})check(state.withViewport(bad).viewport()==null,"invalid viewport discarded");
        System.out.println("ImageGalleryStateTest: "+assertions+" assertions PASS");
    }
}
