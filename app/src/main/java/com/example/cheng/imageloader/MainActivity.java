package com.example.cheng.imageloader;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

public class MainActivity extends AppCompatActivity {

    private static final String IMAGE_URL = "https://timgsa.baidu.com/timg?image&quality=80&size=b9999_10000&sec=1519665434967&di=47dca9a537caaa4638c876c6e51c4678&imgtype=0&src=http%3A%2F%2Fpic.58pic.com%2F58pic%2F15%2F14%2F14%2F18e58PICMwt_1024.jpg";
    private ImageView mImg;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mImg = findViewById(R.id.imageView);

        ImageLoader.build(this).loadBitmapAsync(IMAGE_URL,mImg,100,100);
    }
}
