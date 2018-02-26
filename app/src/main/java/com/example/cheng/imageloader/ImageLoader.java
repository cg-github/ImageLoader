package com.example.cheng.imageloader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

/**
 * Created by cheng on 2018/2/26.
 */

public class ImageLoader {
    private static final String TAG = "ImageLoader";
    private static final String IMAGE_CACHE_NAME = "bitmap";

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;

    private static final int DISK_CACHE_SIZE = 1024 * 1024 * 50;
    private static final int DISK_CACHE_INDEX = 0;
    private static final int MESSAGE_RESULT = 1;


    private boolean mIsDiskCacheCreated = false;

    private Context mContext;
    private ImageResizer mImageResizer;
    private LruCache<String, Bitmap> mMemCache;
    private DiskLruCache mDiskLruCache;
    private Handler mHandler= new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what){
                case MESSAGE_RESULT:
                    LoaderResult result = (LoaderResult) msg.obj;
                    ImageView imageView = result.imageView;
                    String uri = result.uri;
                    Bitmap bitmap = result.bitmap;
                    imageView.setImageBitmap(bitmap);
                    break;
            }
        }
    };

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);
        @Override
        public Thread newThread(@NonNull Runnable r) {
            return new Thread(r, "ImageLoader#"+ mCount.getAndIncrement());
        }
    };
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS,
            new LinkedBlockingDeque<Runnable>(), sThreadFactory
    );


    private ImageLoader(Context context){
        mContext = context.getApplicationContext();
        mImageResizer = new ImageResizer();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemCache = new LruCache<String, Bitmap>(cacheSize){
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };
        File diskCacheDir = getDiskCacheDir(mContext, IMAGE_CACHE_NAME);
        if (!diskCacheDir.exists()){
            diskCacheDir.mkdirs();
        }
        if (diskCacheDir.getUsableSpace() > DISK_CACHE_SIZE){
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Build a new instance of ImageLoader
     * @param context
     * @return
     */
    public static ImageLoader build(Context context){
        return new ImageLoader(context);
    }

    /**
     * load bitmap Synchronized, don't run in UI thread
     * @param uri the url of image
     * @param reqWidth target width of bitmap
     * @param reqHeight target height of bitmap
     * @return
     */
    public Bitmap loadBitmap(String uri, int reqWidth, int reqHeight){
        //get bitmap from memoryCache
        Bitmap bitmap = loadBitmapFromMemoryCache(uri);
        if (bitmap != null){
            return bitmap;
        }

        //get bitmap form DiskCache
        try {
            bitmap = loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
            if (bitmap != null){
                return bitmap;
            } else {
                bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
            }
        } catch (IOException e){
            e.printStackTrace();
        }

        //get bitmap from url directly
        if (bitmap == null && !mIsDiskCacheCreated){
            Log.w(TAG, "DiskCache is not created!");
            bitmap = downloadBitmapFromUrl(uri);
        }

        return bitmap;
    }

    /**
     * load bitmap async with original dimensions
     * @param uri the url of image
     * @param imageView imageView to show the bitmap
     */
    public void loadBitmapAsync(String uri, ImageView imageView){
        loadBitmapAsync(uri, imageView, 0, 0);
    }

    /**
     * load bitmap async specify dimensions
     * @param uri the url of image
     * @param imageView imageView to show the bitmap
     * @param reqWidth target width of bitmap
     * @param reqHeight target height of bitmap
     */
    public void loadBitmapAsync(final String uri, final ImageView imageView, final int reqWidth, final int reqHeight){
        final Bitmap bitmap = loadBitmapFromMemoryCache(uri);
        if (bitmap != null){
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap resultBitmap = loadBitmap(uri, reqWidth, reqHeight);
                LoaderResult loaderResult = new LoaderResult(imageView, uri, resultBitmap);
                mHandler.obtainMessage(MESSAGE_RESULT,loaderResult).sendToTarget();
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    private void addBitmapToMemoryCache(String key, Bitmap bitmap){
        if (mMemCache.get(key) == null){
            mMemCache.put(key, bitmap);
        }
    }
    private Bitmap getBitmapFromMemoryCache(String key){
        return mMemCache.get(key);
    }

    private Bitmap loadBitmapFromMemoryCache(String uri){
        String key = hashUrlGetKey(uri);
        return getBitmapFromMemoryCache(key);
    }

    private Bitmap loadBitmapFromDiskCache(String uri, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()){
            Log.w(TAG,"load bitmap from UI Thread,it's not recommend!");
        }
        if (mDiskLruCache == null){
            return null;
        }
        Bitmap bitmap = null;
        String key = hashUrlGetKey(uri);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        try {
            if (snapshot != null){
                FileInputStream in = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
                FileDescriptor fileDescriptor = in.getFD();
                bitmap = mImageResizer.decodeSampleBitmapFromFileDescriptor(fileDescriptor, reqWidth, reqHeight);
                if (bitmap != null){
                    addBitmapToMemoryCache(key, bitmap);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bitmap;
    }

    private Bitmap loadBitmapFromHttp(String uri, int reqWidth, int reqHeight) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("can not visit network in UI thread!");
        }
        if (mDiskLruCache == null){
            return null;
        }

        String key = hashUrlGetKey(uri);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null){
            OutputStream out = editor.newOutputStream(DISK_CACHE_INDEX);
            if( downloadUrlToStream(uri, out)){
                editor.commit();
            } else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
    }

    private boolean downloadUrlToStream(String urlString, OutputStream outputStream){
        HttpsURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;

        try {
            URL url = new URL(urlString);
            urlConnection = (HttpsURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream());
            out = new BufferedOutputStream(outputStream);

            int b;
            while((b = in.read()) != -1){
                out.write(b);
            }
            return true;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null){
                urlConnection.disconnect();
            }
            try {
                if (in != null){
                    in.close();
                }
                if (out != null){
                    out.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private Bitmap downloadBitmapFromUrl(String urlString){
        Bitmap bitmap = null;
        HttpsURLConnection urlConnection = null;
        BufferedInputStream in = null;

        try {
            URL url = new URL(urlString);
            urlConnection = (HttpsURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream());
            bitmap = BitmapFactory.decodeStream(in);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null){
                urlConnection.disconnect();
            }
            if (in != null){
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return bitmap;
    }

    private String hashUrlGetKey(String uri) {
        String key;
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(uri.getBytes());
            key = byteToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            key = String.valueOf(uri.hashCode());
        }
        return key;
    }

    private String byteToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++ ){
            String hex = Integer.toHexString(0xff & bytes[i]);
            if (hex.length() == 1){
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }


    private File getDiskCacheDir(Context context, String name){
        boolean externalStorageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (externalStorageAvailable){
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + name);
    }

    private static class LoaderResult{
        public ImageView imageView;
        public String uri;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap){
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }
}
