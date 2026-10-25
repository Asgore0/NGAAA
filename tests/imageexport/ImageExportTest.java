package cn.ngapure.app;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
public final class ImageExportTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static byte[] word(byte[] bytes,int start,String text){byte[] value=text.getBytes(StandardCharsets.US_ASCII);System.arraycopy(value,0,bytes,start,value.length);return bytes;}
    private static void rejected(byte[] bytes)throws Exception{boolean failed=false;try{NativeImageExport.inspect(bytes);}catch(IOException expected){failed=true;}check(failed);}
    private static void format(byte[] bytes,String extension,String mime)throws Exception{NativeImageExport.Image image=NativeImageExport.inspect(bytes);check(image.extension.equals(extension));check(image.mime.equals(mime));check(image.size()==bytes.length);ByteArrayOutputStream out=new ByteArrayOutputStream();image.writeTo(out);check(Arrays.equals(bytes,out.toByteArray()));}
    public static void main(String[] args)throws Exception{
        byte[] png=new byte[32];int[] signature={137,80,78,71,13,10,26,10};for(int i=0;i<signature.length;i++)png[i]=(byte)signature[i];word(png,12,"IHDR");format(png,"png","image/png");
        byte[] jpg=new byte[32];jpg[0]=(byte)255;jpg[1]=(byte)216;jpg[2]=(byte)255;format(jpg,"jpg","image/jpeg");
        format(word(new byte[32],0,"GIF89a"),"gif","image/gif");format(word(new byte[32],0,"GIF87a"),"gif","image/gif");
        format(word(word(new byte[32],0,"RIFF"),8,"WEBP"),"webp","image/webp");format(word(new byte[32],0,"BM"),"bmp","image/bmp");
        byte[] avif=word(word(new byte[32],4,"ftyp"),8,"avif");avif[3]=24;format(avif,"avif","image/avif");
        byte[] compat=word(word(word(new byte[32],4,"ftyp"),8,"mif1"),16,"avif");compat[3]=24;format(compat,"avif","image/avif");
        rejected(null);rejected(new byte[0]);rejected("<html>NOT AN IMAGE</html>".getBytes(StandardCharsets.UTF_8));rejected(word(new byte[32],0,"filename.jpg"));rejected(word(new byte[32],0,"WEBP"));rejected(word(new byte[32],0,"GIF00a"));
        byte[] truncated=Arrays.copyOf(png,16);rejected(truncated);byte[] oversized=new byte[20*1024*1024+1];System.arraycopy(png,0,oversized,0,png.length);rejected(oversized);
        byte[] wrongBrand=word(word(new byte[32],4,"ftyp"),8,"heic");wrongBrand[3]=24;rejected(wrongBrand);
        byte[] beyond=word(word(new byte[32],4,"ftyp"),28,"avif");beyond[3]=24;rejected(beyond);
        boolean nullOut=false;try{NativeImageExport.inspect(png).writeTo(null);}catch(IOException expected){nullOut=true;}check(nullOut);
        System.out.println("NativeImageExport: "+checks+" format/original-byte checks PASS");
    }
}
