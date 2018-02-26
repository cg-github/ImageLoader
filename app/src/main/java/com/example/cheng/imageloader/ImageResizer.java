package com.example.cheng.imageloader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.FileDescriptor;

/**
 * Created by cheng on 2018/2/9.
 */

public class ImageResizer {

    private static final String TAG = "ImageResizer";

    public ImageResizer(){
    };

    public Bitmap decodeSampleBitmapFromResource(Resources resources, int resId, int reqWidth, int reqHeight){
        //decode with inJustDecodeBounds=true to get true dimensions of Bitmap.
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(resources, resId, options);

        //calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        //truly decode bitmap with inSampleSize
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(resources, resId, options);
    }

    public Bitmap decodeSampleBitmapFromFileDescriptor(FileDescriptor fd, int reqWidth, int reqHeight){
        //decode with inJustDecodeBounds=true to get true dimensions of Bitmap.
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd, null, options);

        //calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        //truly decode bitmap with inSampleSize
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFileDescriptor(fd, null, options);

    }
    public int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight){
        if (reqHeight == 0 || reqWidth == 0){
            return 1;
        }

        //origin width and height
        int width = options.outWidth;
        int height = options.outHeight;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth){
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth ){
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}
