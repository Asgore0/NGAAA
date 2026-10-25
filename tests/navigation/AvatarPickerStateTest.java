package cn.ngapure.app;

/** Pending avatar state must survive recreation without accepting a different account session. */
public final class AvatarPickerStateTest {
    private static int checks;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static String digest(char value){StringBuilder out=new StringBuilder();for(int i=0;i<64;i++)out.append(value);return out.toString();}
    public static void main(String[] args){
        String first=digest('a'),second=digest('b');AvatarPickerState request=AvatarPickerState.begin(first);
        ok(request!=null,"valid scope can arm picker");ok(request.matches(first,true),"same logged-in session accepted");ok(!request.matches(second,true),"different exact-session digest rejected");ok(!request.matches(first,false),"logged-out session rejected");ok(!request.matches(null,true),"missing current scope rejected");
        AvatarPickerState restored=AvatarPickerState.decode(request.encode());ok(restored!=null&&restored.matches(first,true),"purpose and digest survive recreation");ok(restored.encode().length==2&&restored.encode()[0].equals("avatar"),"schema contains purpose and digest only");
        for(Object malformed:new Object[]{null,"avatar",new Object[]{"avatar",first},new String[0],new String[]{"avatar"},new String[]{"avatar",first,"uri"},new String[]{"upload",first},new String[]{null,first},new String[]{"avatar",null}})ok(AvatarPickerState.decode(malformed)==null,"wrong type, purpose or shape rejected");
        for(String invalid:new String[]{"",first.substring(1),first+"a",digest('A'),digest('g'),first.substring(1)+"\n","https://example.test/", "session-cookie"}){ok(AvatarPickerState.begin(invalid)==null,"invalid digest cannot arm request");ok(AvatarPickerState.decode(new String[]{"avatar",invalid})==null,"invalid digest cannot restore request");}
        String[] encoded=request.encode();encoded[1]=second;ok(request.matches(first,true),"external array cannot mutate pending scope");
        preview(first,second);
        System.out.println("AvatarPickerState: "+checks+" exact-session restoration assertions PASS; no Android, network or device");
    }
    private static void preview(String first,String second){
        String uri="content://com.android.providers.media.documents/document/image%3A42";float[] input={2.25f,.13f,-.2f};
        AvatarPickerState.Crop original=AvatarPickerState.crop(first,uri,input);ok(original!=null,"bounded local preview can be captured");
        AvatarPickerState.Crop restored=AvatarPickerState.decodeCrop(original.encode());ok(restored!=null&&restored.matches(first,true),"preview restores only exact logged-in session");
        ok(!restored.matches(second,true)&&!restored.matches(first,false),"preview rejects session change and logout");ok(restored.uri.equals(uri),"local document identity retained");
        float[] transform=restored.transform();ok(transform[0]==2.25f&&transform[1]==.13f&&transform[2]==-.2f,"image-relative crop parameters retained");
        input[0]=6;transform[1]=0;String[] state=original.encode();state[2]="content://other/image";ok(original.zoom==2.25f&&original.x==.13f&&original.uri.equals(uri),"caller buffers cannot mutate captured preview");
        for(Object invalid:new Object[]{null,"avatarPreview",new Object[]{"avatarPreview",first,uri,"1","0","0"},new String[0],new String[]{"avatarPreview",first,uri,"1","0"},new String[]{"avatar",first,uri,"1","0","0"},new String[]{"upload",first,uri,"1","0","0"},new String[]{"avatarPreview",first,uri,"1","0","0","extra"}})ok(AvatarPickerState.decodeCrop(invalid)==null,"wrong preview type, purpose or shape rejected");
        for(String invalid:new String[]{"",null,"content:invalid","content:///image","file:///private/image.png","https://example.test/image","CONTENT://media/image","content://user@media/image","content://media:80/image","content://media/image#fragment","content://media/\nimage","content://media/%"})ok(AvatarPickerState.crop(first,invalid,new float[]{1,0,0})==null,"non-document or malformed local identity rejected");
        StringBuilder oversized=new StringBuilder("content://media/");while(oversized.length()<4097)oversized.append('x');ok(AvatarPickerState.crop(first,oversized.toString(),new float[]{1,0,0})==null,"oversized URI rejected");
        for(float[] invalid:new float[][]{null,new float[0],new float[]{1,0},new float[]{1,0,0,0},new float[]{0,0,0},new float[]{6.01f,0,0},new float[]{1,.51f,0},new float[]{1,0,-.51f},new float[]{Float.NaN,0,0},new float[]{1,Float.POSITIVE_INFINITY,0},new float[]{1,0,Float.NEGATIVE_INFINITY}})ok(AvatarPickerState.crop(first,uri,invalid)==null,"invalid normalized preview geometry rejected");
        for(String number:new String[]{"NaN","Infinity","-Infinity","","not-a-number",null})ok(AvatarPickerState.decodeCrop(new String[]{"avatarPreview",first,uri,number,"0","0"})==null,"malformed serialized geometry rejected");
        ok(AvatarPickerState.crop(second,uri,new float[]{6,.5f,-.5f})!=null,"bounded geometry endpoints accepted");ok(AvatarPickerState.crop("cookie",uri,new float[]{1,0,0})==null,"raw credentials cannot become preview scope");
        ok(AvatarPickerState.decode(original.encode())==null&&AvatarPickerState.decodeCrop(requestState(first))==null,"picker and preview schemas cannot be confused");
    }
    private static String[] requestState(String scope){return AvatarPickerState.begin(scope).encode();}
}
