package com.example.android.camera2basic;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Base64;
import android.util.Log;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.apache.commons.io.IOUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.entity.StringEntity;


public class AsyncRequestHandler
{
    public double edgeStdDev = 0;
    public double cloudStdDev = 0;
    private int N = 10;
    private int cn = 0;
    private int en = 0;
    private long[] cloudStdArr = new long[100];
    private long[] edgeStdArr = new long[100];

    private boolean cloudBusy = false;
    private boolean edgeBusy = false;

    public boolean cloudNewData = false;
    public boolean edgeNewData = false;

    private long cloudLatency = 0;
    private long edgeLatency = 0;

    private Rect cloudRect = new Rect(0,0,0,0);
    private Rect edgeRect = new Rect(0, 0, 0,0);

    private static String cloudAPIEndpoint = "https://104.42.217.135:8000";
    private static String edgeAPIEndpoint = "https://37.50.143.103:8000";

    private static AsyncHttpClient httpClient = new AsyncHttpClient(true, 8000,800);

    private Semaphore cloudMutex;
    private Semaphore edgeMutex;

    public AsyncRequestHandler(Semaphore cloudM, Semaphore edgeM)
    {
        cloudMutex = cloudM;
        edgeMutex = edgeM;
    }

    private double average(long[] arr, int len)
    {
        double sum = 0;
        for (int i = 0; i < len; i++) {
            sum += arr[i];
        }
        return sum /= len;
    }

    private double stdDev(long[] arr, int len)
    {
        double avg = average(arr, len);
        double sum = 0;

        for (int i = 0; i < len; i++) {
            sum += Math.pow(arr[i] - avg, 2);
        }

        return Math.sqrt(sum/len);
    }

    private static void get(String url, RequestParams params,
                           AsyncHttpResponseHandler responseHandler)
    {
        httpClient.get(getAbsoluteUrl(url), params, responseHandler);
    }

    private static void post(String url, StringEntity params,
                            AsyncHttpResponseHandler responseHandler)
    {
        httpClient.post(null, url, params,
                "application/json", responseHandler);
    }

    private static String getAbsoluteUrl(String relativeUrl)
    {
        return cloudAPIEndpoint + relativeUrl;
    }

    private void sendToEdge(Bitmap bitmap)
    {
        edgeBusy = true;
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        byte[] bytes = byteStream.toByteArray();
        String encoded = Base64.encodeToString(bytes, Base64.DEFAULT);
        JSONObject params = new JSONObject();

        try {
            params.put("frame", encoded);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        StringEntity entity = null;
        try {
            entity = new StringEntity(encoded);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        final long startTime = System.nanoTime();

        post(edgeAPIEndpoint + "/detect/", entity, new AsyncHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
                int intArr[] = new int[responseBody.length / 3];
                int offset = 0;
                for (int i = 0; i < intArr.length; i++) {
                    intArr[i] = (((responseBody[2 + offset]) & 0xFF) |
                            ((responseBody[1 + offset] & 0xFF) << 8) | ((responseBody[0 + offset] & 0xFF) << 16)) & 0xFFFFFF;
                    offset += 3;
                    Log.d("Read from Server", Integer.toString(intArr[i]));
                }

                try {
                    edgeMutex.acquire();
                    try {
                        if (Array.getLength(intArr) == 0) {
                            edgeRect.set(0, 0, 0, 0);
                        } else {
                            edgeRect.set(intArr[0], intArr[1], intArr[2], intArr[3]);
                        }
                    } finally {
                        edgeMutex.release();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                long endTime = System.nanoTime();

                edgeLatency = endTime - startTime;
                edgeStdArr[en] = edgeLatency;
                en += 1;
                en %= N;
                edgeStdDev = stdDev(edgeStdArr, N);

                edgeNewData = true;
                edgeBusy = false;

            }

            @Override
            public void onFailure(int statusCode, Header[] headers, byte[] responseBody,
                                  Throwable error) {
                long endTime = System.nanoTime();

                try {
                    edgeMutex.acquire();
                    try {
                        edgeRect.set(0, 0, 0, 0);
                    } finally {
                        edgeMutex.release();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                edgeLatency = endTime - startTime;
                edgeStdArr[en] = edgeLatency;
                en += 1;
                en %= N;
                edgeStdDev = stdDev(edgeStdArr, N);
                edgeBusy = false;
                error.printStackTrace();
            }
        });
    }

    private void sendToCloud(Bitmap bitmap)
    {
        cloudBusy = true;

        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        byte[] bytes = byteStream.toByteArray();
        String encoded = Base64.encodeToString(bytes, Base64.DEFAULT);

        JSONObject params = new JSONObject();

        try {
            params.put("frame", encoded);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        StringEntity entity = null;
        try {
            entity = new StringEntity(encoded);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        final long startTime = System.nanoTime();

        post(cloudAPIEndpoint + "/detect/", entity, new AsyncHttpResponseHandler()
            {
                @Override
                public void onSuccess(int statusCode, Header[] headers, byte[] responseBody)
                {
                    int intArr[] = new int[responseBody.length / 3];
                    int offset = 0;
                    for(int i = 0; i < intArr.length; i++) {
                        intArr[i] = (((responseBody[2 + offset]) & 0xFF) |
                                ((responseBody[1 + offset] & 0xFF) << 8) | ((responseBody[offset] & 0xFF) << 16)) & 0xFFFFFF;
                        offset += 3;
                        Log.d("Whole array", Arrays.toString(intArr));
                    }

                    try {
                        cloudMutex.acquire();
                        try {
                            if (Array.getLength(intArr) == 0) {
                                cloudRect.set(0, 0, 0, 0);
                            } else {
                                cloudRect.set(intArr[0], intArr[1], intArr[2], intArr[3]);
                            }
                        } finally {
                            cloudMutex.release();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    long endTime = System.nanoTime();
                    cloudLatency = endTime - startTime;
                    cloudStdArr[cn] = cloudLatency;
                    cn += 1;
                    cn %= N;
                    cloudStdDev = stdDev(cloudStdArr, N);
                    cloudNewData = true;
                    cloudBusy = false;
                }

                @Override
                public void onFailure(int statusCode, Header[] headers, byte[] responseBody,
                                      Throwable error)
                {
                    long endTime = System.nanoTime();

                    try {
                        cloudMutex.acquire();
                        try {
                            cloudRect.set(0, 0, 0, 0);
                        } finally {
                            cloudMutex.release();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    cloudLatency = endTime - startTime;
                    cloudStdArr[cn] = cloudLatency;
                    cn += 1;
                    cn %= N;
                    cloudStdDev = stdDev(cloudStdArr, N);
                    cloudBusy = false;
                    error.printStackTrace();
                }
        });
    }

    public void sendImage(Bitmap image)
    {
        if (!cloudBusy) {
            sendToCloud(image);
        }

        if (!edgeBusy) {
            sendToEdge(image);
        }
    }

    public Rect getCloudRect()
    {
        return cloudRect;
    }

    public Rect getEdgeRect()
    {
        return edgeRect;
    }

    public long getCloudLatency()
    {
        return cloudLatency;
    }

    public long getEdgeLatency()
    {
        return edgeLatency;
    }

    public boolean getCloudBusy() { return cloudBusy; }

    public boolean getEdgeBusy() { return edgeBusy; }
}
