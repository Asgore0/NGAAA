package cn.ngapure.app;

import java.net.URI;

/** Small exact-session state; never retains callbacks, image bytes or credentials. */
final class AvatarPickerState {
    private final String scope;
    private AvatarPickerState(String scope){this.scope=scope;}
    static AvatarPickerState begin(String scope){return valid(scope)?new AvatarPickerState(scope):null;}
    static AvatarPickerState decode(Object raw){
        if(!(raw instanceof String[]))return null;String[] values=(String[])raw;
        return values.length==2&&"avatar".equals(values[0])?begin(values[1]):null;
    }
    String[] encode(){return new String[]{"avatar",scope};}
    boolean matches(String currentScope,boolean loggedIn){return loggedIn&&scope.equals(currentScope);}
    private static boolean valid(String scope){return scope!=null&&scope.matches("[0-9a-f]{64}");}

    /** A local preview can be decoded again after recreation. This never represents an upload. */
    static final class Crop {
        final String scope,uri;
        final float zoom,x,y;
        private Crop(String scope,String uri,float zoom,float x,float y){this.scope=scope;this.uri=uri;this.zoom=zoom;this.x=x;this.y=y;}
        boolean matches(String currentScope,boolean loggedIn){return loggedIn&&scope.equals(currentScope);}
        float[] transform(){return new float[]{zoom,x,y};}
        String[] encode(){return new String[]{"avatarPreview",scope,uri,Float.toString(zoom),Float.toString(x),Float.toString(y)};}
    }
    static Crop crop(String scope,String uri,float[] transform){
        if(!valid(scope)||!contentUri(uri)||transform==null||transform.length!=3)return null;
        float zoom=transform[0],x=transform[1],y=transform[2];
        if(!finite(zoom)||!finite(x)||!finite(y)||zoom<1||zoom>6||x<-.5f||x>.5f||y<-.5f||y>.5f)return null;
        return new Crop(scope,uri,zoom,x,y);
    }
    static Crop decodeCrop(Object raw){
        if(!(raw instanceof String[])||((String[])raw).length!=6)return null;String[] values=(String[])raw;
        if(!"avatarPreview".equals(values[0]))return null;
        try{return crop(values[1],values[2],new float[]{Float.parseFloat(values[3]),Float.parseFloat(values[4]),Float.parseFloat(values[5])});}
        catch(RuntimeException malformed){return null;}
    }
    private static boolean finite(float value){return !Float.isNaN(value)&&!Float.isInfinite(value);}
    private static boolean contentUri(String value){
        if(value==null||value.length()>4096||value.matches("(?s).*[\\x00-\\x1f\\x7f].*"))return false;
        try{URI uri=new URI(value);return "content".equals(uri.getScheme())&&uri.getRawAuthority()!=null&&!uri.getRawAuthority().isEmpty()&&uri.getRawUserInfo()==null&&uri.getPort()==-1&&uri.getRawFragment()==null;}
        catch(Exception malformed){return false;}
    }
}
