package com.practice.serialportpractice;

import android.serialport.SerialPort;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class SerialPortHelper {
    public static final String TAG = SerialPortHelper.class.getSimpleName();
    private static ExecutorService executorService = Executors.newSingleThreadExecutor();
    private String serialPort = "";
    private int baudrate = 0;
    private SerialPort mSerialPort;
    private InputStream mFileInputStream;
    private ReadThread readThread;
    private WriteThread writeThread;
    private OutputStream mFileOutputStream;
    //READ
    private byte[] buffer = new byte[1024];
    private ByteBuf readPool = Unpooled.buffer();
    boolean stopRead = false;
    boolean skipReadWait = false;
    ReentrantLock readLock = new ReentrantLock();
    Condition readCondition = readLock.newCondition();
    //WRITE
    boolean stopWrite = false;
    ArrayDeque<Cmd> cmdQueue = new ArrayDeque<>();
    ReentrantLock writeLock = new ReentrantLock();
    Condition writeCondition = writeLock.newCondition();
    ReentrantLock sleepLock = new ReentrantLock();
    Condition sleepCondition = sleepLock.newCondition();
    private Cmd cmdToSend = null;
    private byte[] bytesToSend = null;

    public SerialPortHelper(String serialPort, int baudrate) {
        this.serialPort = serialPort;
        this.baudrate = baudrate;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    Callback callback;

    private void stopWrite() {
        sleepLock.lock();
        stopWrite = true;
        sleepCondition.signalAll();
        sleepLock.unlock();
        sendCmd(new Cmd.Builder().data(Unpooled.buffer()).clearQueue(true).interval(0).extra("").build());
    }

    private void stopRead() {
        readLock.lock();
        stopRead = true;
        readCondition.signalAll();
        readLock.unlock();
    }

    private boolean opened = false;

    public void sendCmd(Cmd cmd) {
        if (cmd == null) return;
        writeLock.lock();
        if (cmd.isClearQueue()) {
            cmdQueue.clear();
        }
        cmdQueue.add(cmd);
        writeCondition.signalAll();
        writeLock.unlock();
    }


    class ReadThread extends Thread {

        @Override
        public void run() {
            super.run();
            try {
                while (true) {
                    readPool.discardReadBytes();
                    while (!stopRead) {
                        int readLength = 0;
                        if (mFileInputStream.available() > 0) {
                            readLength = mFileInputStream.read(buffer);
                        }
                        if (readLength > 0) {
                            Log.e(TAG, "SerialPortHelper: <<<" + Utils.bytesToHex(buffer, 0, readLength));
                            readPool.writeBytes(buffer, 0, readLength);
                            break;//读到数据了，将数据回调出去
                        } else {
                            readLock.lock();
                            if (!stopRead && !skipReadWait) {
                                readCondition.await(10, TimeUnit.MILLISECONDS);
                            }
                            skipReadWait = false;
                            readLock.unlock();
                        }
                    }
                    if (stopRead) break;

                    try {
                        callback.receiveData(readPool);
                    } catch (Exception e) {

                    }


                }
            } catch (Exception e) {

            }
            try {
                readLock.unlock();
            } catch (Exception e) {

            }

        }

    }

    class WriteThread extends Thread {
        @Override
        public void run() {
            super.run();
            try {
                while (true) {
                    writeLock.lock();
                    if (cmdQueue.isEmpty()) {
                        writeCondition.await();
                        writeLock.unlock();
                        continue;
                    } else {
                        cmdToSend = cmdQueue.poll();
                    }
                    writeLock.unlock();
                    if (stopWrite) {
                        break;
                    }


                    try {
                        if (cmdToSend != null && cmdToSend.getData() != null && cmdToSend.getData().readableBytes() > 0) {
                            readLock.lock();
                            bytesToSend = new byte[cmdToSend.getData().readableBytes()];
                            cmdToSend.getData().readBytes(bytesToSend);
                            mFileOutputStream.write(bytesToSend);
                            skipReadWait = true;
                            readCondition.signalAll();
                            readLock.unlock();
                            try {
                                callback.haveSendCmd(cmdToSend);
                            } catch (Exception e) {

                            }
                            Log.e(TAG, "weigher: >>>" + Utils.bytesToHex(bytesToSend));
                        }
                    } catch (Exception e) {

                    }

                    if (cmdToSend.getInterval() > 0) {
                        sleepLock.lock();
                        if (!stopWrite) {
                            sleepCondition.await(cmdToSend.getInterval(), TimeUnit.MILLISECONDS);
                        }
                        sleepLock.unlock();
                    }
                }
            } catch (Exception e) {

            }
            try {
                readLock.unlock();
            } catch (Exception e) {

            }

            try {
                writeLock.unlock();
            } catch (Exception e) {

            }
            try {
                sleepLock.unlock();
            } catch (Exception e) {

            }
        }

    }


    public void open() {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                if (opened) return;
                try {
                    _open();
                    opened = true;
                    try {
                        callback.openSuccess();
                    } catch (Exception e) {

                    }
                } catch (Exception e) {
                    opened = false;
                    _close();
                    try {
                        callback.openFailed("串口打开失败:" + Log.getStackTraceString(e));
                    } catch (Exception ex) {

                    }
                }
            }
        });
    }

    public void close() {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                if (!opened) return;
                opened = false;
                _close();
                try {
                    callback.closed();
                } catch (Exception e) {

                }
            }
        });
    }


    private void _open() throws Exception {
        mSerialPort = new SerialPort(new File(serialPort), baudrate);
        mFileInputStream = mSerialPort.getInputStream();
        mFileOutputStream = mSerialPort.getOutputStream();
        readThread = new ReadThread();
        writeThread = new WriteThread();
        readThread.start();
        writeThread.start();
    }


    private void _close() {
        try {
            stopRead();
        } catch (Exception e) {

        }
        try {
            stopWrite();
        } catch (Exception e) {

        }
        try {
            mSerialPort.tryClose();
        } catch (Exception e) {

        }
        try {
            readThread.join();
        } catch (Exception e) {

        }
        try {
            writeThread.join();
        } catch (Exception e) {

        }
        Log.e(TAG, "release: finish");
    }

    public static class Cmd {
        ByteBuf data;
        String extra;
        long interval;
        boolean clearQueue;

        public Cmd() {
        }

        private Cmd(Builder builder) {
            setData(builder.data);
            setExtra(builder.extra);
            setInterval(builder.interval);
            setClearQueue(builder.clearQueue);
        }

        public ByteBuf getData() {
            return data;
        }

        public void setData(ByteBuf data) {
            this.data = data;
        }

        public String getExtra() {
            return extra;
        }

        public void setExtra(String extra) {
            this.extra = extra;
        }

        public long getInterval() {
            return interval;
        }

        public void setInterval(long interval) {
            this.interval = interval;
        }

        public boolean isClearQueue() {
            return clearQueue;
        }

        public void setClearQueue(boolean clearQueue) {
            this.clearQueue = clearQueue;
        }

        public static final class Builder {
            private ByteBuf data;
            private String extra;
            private long interval;
            private boolean clearQueue;

            public Builder() {
            }

            public Builder data(ByteBuf val) {
                data = val;
                return this;
            }

            public Builder extra(String val) {
                extra = val;
                return this;
            }

            public Builder interval(long val) {
                interval = val;
                return this;
            }

            public Builder clearQueue(boolean val) {
                clearQueue = val;
                return this;
            }

            public Cmd build() {
                return new Cmd(this);
            }
        }
    }


    public interface Callback {

        void haveSendCmd(Cmd cmd);

        void openSuccess();

        void openFailed(String msg);

        void receiveData(ByteBuf readPool);

        void closed();
    }


    public static class Utils {
        private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

        public static String byteToHex(byte value) {
            return bytesToHex(new byte[]{value});
        }

        public static String bytesToHex(byte[] bytes, int offset, int length) {
            char[] hexChars = new char[length * 2];
            for (int j = 0; j < length; j++) {
                int v = bytes[j + offset] & 0xFF;
                hexChars[j * 2] = HEX_ARRAY[v >>> 4];
                hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
            }
            return new String(hexChars);
        }

        public static String bytesToHex(byte[] bytes) {
            return bytesToHex(bytes, 0, bytes.length);
        }
    }
}
