package cn.ngapure.app;

import java.io.IOException;
import java.io.OutputStream;

/** Original-image export metadata comes from the bytes, never the filename or URL suffix. */
public final class NativeImageExport {
    private NativeImageExport() { }
    public static final class Image {
        public final String extension,mime;
        private final byte[] bytes;
        private Image(byte[] bytes,String extension,String mime){this.bytes=bytes;this.extension=extension;this.mime=mime;}
        public int size(){return bytes.length;}
        public void writeTo(OutputStream stream)throws IOException{if(stream==null)throw new IOException("无法打开保存位置");stream.write(bytes);}
    }
    public static Image inspect(byte[] bytes)throws IOException{
        if(bytes==null||bytes.length<10||bytes.length>20*1024*1024)throw new IOException("原图为空或超过20MB");
        if(bytes.length>=24&&at(bytes,0,new int[]{137,80,78,71,13,10,26,10})&&word(bytes,12,"IHDR"))return new Image(bytes,"png","image/png");
        if((bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255)return new Image(bytes,"jpg","image/jpeg");
        if(word(bytes,0,"GIF87a")||word(bytes,0,"GIF89a"))return new Image(bytes,"gif","image/gif");
        if(bytes.length>=16&&word(bytes,0,"RIFF")&&word(bytes,8,"WEBP"))return new Image(bytes,"webp","image/webp");
        if(bytes.length>=26&&word(bytes,0,"BM"))return new Image(bytes,"bmp","image/bmp");
        if(bytes.length>=20&&word(bytes,4,"ftyp")){
            long size=((long)(bytes[0]&255)<<24)|((long)(bytes[1]&255)<<16)|((long)(bytes[2]&255)<<8)|(bytes[3]&255);
            if(size>=20&&size<=bytes.length&&size<=4096){
                if(word(bytes,8,"avif")||word(bytes,8,"avis"))return new Image(bytes,"avif","image/avif");
                for(int p=16;p+4<=size;p+=4)if(word(bytes,p,"avif")||word(bytes,p,"avis"))return new Image(bytes,"avif","image/avif");
            }
        }
        throw new IOException("服务器未返回支持的原始图片，未写入文件");
    }
    private static boolean word(byte[] bytes,int start,String word){if(start<0||start+word.length()>bytes.length)return false;for(int i=0;i<word.length();i++)if((bytes[start+i]&255)!=word.charAt(i))return false;return true;}
    private static boolean at(byte[] bytes,int start,int[] signature){if(start+signature.length>bytes.length)return false;for(int i=0;i<signature.length;i++)if((bytes[start+i]&255)!=signature[i])return false;return true;}
}
