package com.xcheng.autobundle.simple;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import autobundle.AutoBundle;
import autobundle.annotation.IntValue;
import autobundle.annotation.ParcelableValue;

public class MainActivity extends AppCompatActivity {
    //    @BindBundle("12112112122")
//    private List<String> list;
    //    @BindBundle("1212")
//    Map<String, String> map;
//    @BindBundle("12121212")
    @IntValue("121")
    List[] array;
    @Nullable
    @IntValue("123213")
    List[] array2;
    //@Nullable
    @ParcelableValue(value = "array", desc = "123213")
    List[] array3;

//    @BindBundle(value = "12112122",name = "1212")
//    Map<String, ?> data2;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Bundle bundle = AutoBundle.create(BundleFactory.class)
                .login("12", "1212");

    }

    public static void main(String[] args) {
        List<?> strs = new ArrayList<>();
        if (strs instanceof Serializable) {

        }
    }

}
