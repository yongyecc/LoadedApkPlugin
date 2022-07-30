package com.yongyecc.loaderapkplugin;

import androidx.appcompat.app.AppCompatActivity;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button button = findViewById(R.id.button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(MyApp.isHookFinish) {
                    //start activity of plugin apk.
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName("com.yongyecc.plugin", "com.yongyecc.plugin.FirstPluginActivity"));
                    startActivity(intent);
                }
                else {
                    Toast.makeText(MyApp.getInstance(), "no hook completed, please wait a moment...", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}