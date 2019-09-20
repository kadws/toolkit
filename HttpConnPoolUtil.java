package com.uroad.jstxb.util;

import com.alibaba.fastjson.JSONObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HttpConnPoolUtil {
    private static Logger mLogger = LoggerFactory.getLogger(HttpConnPoolUtil.class);

    //设置建立连接超时为2s
    private static final int CONNECT_TIMEOUT = 5*1000;
    //等待数据超时4s
    private static final int SOCKET_TIMEOUT = 10*1000;
    //连接数
    private static final int MAX_COUNT = 80;
    //路由最大并发数
    private static final int MAX_PRE_ROUTE = 60;
    //2s检查一次
    private final static long monitorInterval = 2;

    private static CloseableHttpClient httpClient;
    private static PoolingHttpClientConnectionManager manager;
    private static ScheduledExecutorService monitorExecutor;

    static{
        //通用请求超时设置
        RequestConfig requestConfig = RequestConfig.custom().setConnectionRequestTimeout(CONNECT_TIMEOUT)
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setSocketTimeout(SOCKET_TIMEOUT).build();
        //设置poolingHttpClientConnectionManager
        ConnectionSocketFactory plainSocketFactory = PlainConnectionSocketFactory.getSocketFactory();
        LayeredConnectionSocketFactory sslSocketFactory = SSLConnectionSocketFactory.getSocketFactory();
        Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http",plainSocketFactory)
                .register("https",sslSocketFactory)
                .build();
        manager = new PoolingHttpClientConnectionManager(registry);
        manager.setMaxTotal(MAX_COUNT);
        manager.setDefaultMaxPerRoute(MAX_PRE_ROUTE);
        //尽量不要设置失败重试次数
        httpClient = HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(requestConfig)
                .build();

        //开启监控线程，对异常和空闲线程进行关闭
        monitorExecutor = Executors.newScheduledThreadPool(1);
        monitorExecutor.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                //关闭异常连接
                manager.closeExpiredConnections();
                //关闭5s空闲的连接
                manager.closeIdleConnections(5,TimeUnit.SECONDS);
            }
        },1,monitorInterval,TimeUnit.SECONDS);
    }

    /**
     * 设置post参数
     * @param httpPost
     * @param params
     * @throws UnsupportedEncodingException
     */
    private static void setPostParams(HttpPost httpPost, Map<String,String> params) {
        List<NameValuePair> nvps = new ArrayList<NameValuePair>();
        for(Map.Entry<String,String> entry:params.entrySet()){
            nvps.add(new BasicNameValuePair(entry.getKey(),entry.getValue()));
        }
        try {
            httpPost.setEntity(new UrlEncodedFormEntity(nvps,"UTF-8"));
        } catch (UnsupportedEncodingException e) {
            mLogger.error("[HttpConnPoolUtil][setPostParams] exception",e);
        }
    }

    private static String invoke(HttpUriRequest request){
        CloseableHttpResponse response = null;
        HttpEntity entity = null;
        try {
            response = httpClient.execute(request);
            if(response.getStatusLine().getStatusCode() == HttpStatus.SC_OK){
                entity = response.getEntity();
                return EntityUtils.toString(entity,"UTF-8");
            }
        } catch (IOException e) {
            mLogger.error("[HttpConnnPoolUtil][invoke][method:"+request.getMethod()+",URI:"+request.getURI()+"]is request exception",e);
        } finally {
            if(entity != null){
                try {
                    EntityUtils.consume(entity);
                } catch (IOException e) {
                    mLogger.error("[HttpConnPoolUtil][invoke][method:"+request.getMethod()+",URI:"+request.getURI()+"]is closed exception",e);
                }
            }
            if(response != null){
                try {
                    response.close();
                } catch (IOException e) {
                    mLogger.error("[HttpConnPoolUtil][invoke][method:"+request.getMethod()+",URI:"+request.getURI()+"]is closed exception",e);
                }
            }
        }
        return null;
    }

    public static String httpFormPost(String url,Map<String,String> params){
        mLogger.info("post->req:url：{},param：{}",url,JSONObject.toJSON(params));
        HttpPost httpPost = new HttpPost(url);
        Long startTs = System.currentTimeMillis();
        setPostParams(httpPost,params);
        String result = invoke(httpPost);
        Long endTs = System.currentTimeMillis();
        Long methodCallTime = endTs - startTs;
        if(methodCallTime > 5000){
            mLogger.warn("url:{},call time {} ms",url,methodCallTime);
            mLogger.info("所有存活线程="+Thread.getAllStackTraces().size());
        }
        mLogger.info("post->rps{}",result);
        return result;
    }

    public static String httpJsonPost(String url,String param){
        mLogger.info("post->req:url：{},param：{}",url,param);
        HttpPost httpPost = new HttpPost(url);
        Long startTs = System.currentTimeMillis();
        StringEntity s = new StringEntity(param,"UTF-8");
        s.setContentType("application/json");
        httpPost.setEntity(s);
        String result = invoke(httpPost);
        Long endTs = System.currentTimeMillis();
        Long methodCallTime = endTs - startTs;
        if(methodCallTime > 5000){
            mLogger.warn("url:{},call time {} ms",url,methodCallTime);
            mLogger.info("所有存活线程="+Thread.getAllStackTraces().size());
        }
        mLogger.info("post->rps：{}",result);
        return result;
    }

    public static String httpGet(String url){
        mLogger.info("get->req:url：{}",url);
        Long startTs = System.currentTimeMillis();
        HttpGet httpGet = new HttpGet(url);
        String result = invoke(httpGet);
        Long endTs = System.currentTimeMillis();
        Long methodCallTime = endTs - startTs;
        mLogger.info("get->rps：{}",result);
        if(methodCallTime > 5000){
            mLogger.warn("url:{},call time ：{} ms",url,methodCallTime);
        }
        return result;
    }
}
