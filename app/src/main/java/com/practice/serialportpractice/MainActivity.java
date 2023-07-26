package com.practice.serialportpractice;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import io.netty.buffer.ByteBuf;

public class MainActivity extends AppCompatActivity {
    SerialPortHelper serialPortHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        serialPortHelper = new SerialPortHelper("/dev/ttyS9", 115200);
        serialPortHelper.setCallback(new SerialPortHelper.Callback() {
            @Override
            public void haveSendCmd(SerialPortHelper.Cmd cmd) {

            }

            @Override
            public void openSuccess() {

            }

            @Override
            public void openFailed(String msg) {

            }

            @Override
            public void receiveData(ByteBuf readPool) {

            }

            @Override
            public void closed() {

            }
        });
        serialPortHelper.open();
        serialPortHelper.sendCmd(new SerialPortHelper.Cmd.Builder().build());
    }

    @Override
    protected void onDestroy() {
        serialPortHelper.close();
        super.onDestroy();
    }
}