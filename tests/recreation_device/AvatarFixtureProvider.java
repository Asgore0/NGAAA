package cn.ngapure.recreationprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Public test fixture only: serves one generated image, never arbitrary files or account content. */
public final class AvatarFixtureProvider extends ContentProvider {
    private static int reads;
    private boolean allowed(Uri uri){return uri!=null&&"/avatar.png".equals(uri.getPath());}
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return allowed(uri)?"image/png":null;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){if(!allowed(uri))return null;MatrixCursor result=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});result.addRow(new Object[]{"synthetic-avatar.png",1024});return result;}
    @Override public Bundle call(String method,String argument,Bundle extras){Bundle result=new Bundle();if("readCount".equals(method))result.putInt("reads",reads);return result;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(!allowed(uri)||!"r".equals(mode))throw new FileNotFoundException("fixture only");
        File file=new File(getContext().getCacheDir(),"synthetic-avatar-v2.png");
        if(!file.isFile())try{Bitmap bitmap=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888);for(int y=0;y<160;y++)for(int x=0;x<240;x++)bitmap.setPixel(x,y,0xff000000|((x*255/239)<<16)|((y*255/159)<<8)|(x+y)%256);try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}}catch(IOException failure){throw new FileNotFoundException("fixture unavailable");}
        reads++;return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("read only fixture");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){throw new UnsupportedOperationException("read only fixture");}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs){throw new UnsupportedOperationException("read only fixture");}
}
