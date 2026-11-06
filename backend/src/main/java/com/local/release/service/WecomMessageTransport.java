package com.local.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Never includes the endpoint, response body or exception message in an error. */
@Component
public class WecomMessageTransport {
  private final ObjectMapper json;
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
    .followRedirects(HttpClient.Redirect.NEVER).build();
  public WecomMessageTransport(ObjectMapper json){this.json=json;}
  public record Delivery(boolean success,boolean retryable,String error){}
  public static String validateWebhook(String value){
    String input=value==null?"":value.trim();
    try{
      URI uri=URI.create(input);
      if(!"https".equals(uri.getScheme())||!"qyapi.weixin.qq.com".equals(uri.getHost())
          ||uri.getPort()!=-1||uri.getUserInfo()!=null||uri.getFragment()!=null
          ||!"/cgi-bin/webhook/send".equals(uri.getRawPath())
          ||uri.getRawQuery()==null||!uri.getRawQuery().matches("key=[A-Za-z0-9_-]{16,128}"))throw new IllegalArgumentException();
      return input;
    }catch(Exception ignored){throw new IllegalArgumentException("请输入企业微信群机器人的完整 HTTPS Webhook 地址");}
  }
  public Delivery send(String webhook,String payload){
    try{
      HttpRequest request=HttpRequest.newBuilder(URI.create(validateWebhook(webhook)))
        .timeout(Duration.ofSeconds(6)).header("Content-Type","application/json; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(payload,StandardCharsets.UTF_8)).build();
      // The endpoint is fixed to the official API; no redirects or configurable hosts.
      HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return parseResponse(response.statusCode(),response.body());
    }catch(InterruptedException error){Thread.currentThread().interrupt();return new Delivery(false,true,"发送被平台停止中断");}
    catch(java.net.http.HttpTimeoutException error){return new Delivery(false,true,"企业微信请求超时");}
    catch(Exception error){return new Delivery(false,true,"无法连接企业微信或响应无效，请检查网络与机器人配置");}
  }
  Delivery parseResponse(int status,String text){
    if(status!=200)return new Delivery(false,status==429||status>=500,"企业微信 HTTP 响应 "+status);
    try{
      if(text==null||text.length()>16384)return new Delivery(false,false,"企业微信响应格式异常");
      var body=json.readTree(text);
      if(body==null||!body.isObject()||!body.has("errcode")||!body.get("errcode").isIntegralNumber()||!body.get("errcode").canConvertToInt())return new Delivery(false,false,"企业微信响应格式异常");
      int code=body.get("errcode").intValue();
      return code==0?new Delivery(true,false,null):new Delivery(false,code==-1||code==45009,"企业微信拒绝通知（错误码 "+code+"），请检查机器人配置");
    }catch(Exception error){return new Delivery(false,false,"企业微信响应格式异常");}
  }
}
