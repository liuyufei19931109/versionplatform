package com.local.release.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class WecomMessageTransportTest {
  final WecomMessageTransport transport=new WecomMessageTransport(new ObjectMapper());
  @Test void rejectsAlternateHostsCredentialsPortsRedirectPathsAndExtraQueryParameters(){
    for(String value:List.of("http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=0000000000000000","https://qyapi.weixin.qq.com.evil.example/cgi-bin/webhook/send?key=0000000000000000","https://user:password@qyapi.weixin.qq.com/cgi-bin/webhook/send?key=0000000000000000","https://qyapi.weixin.qq.com:8443/cgi-bin/webhook/send?key=0000000000000000","https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=0000000000000000&url=http://localhost","https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=0000000000000000#secret"))assertThatThrownBy(()->WecomMessageTransport.validateWebhook(value)).hasMessageNotContaining("0000000000000000");
  }
  @Test void onlyExplicitIntegerZeroMeansSuccessAndErrorsDoNotEchoBody(){
    assertThat(transport.parseResponse(200,"{\"errcode\":0}").success()).isTrue();
    for(String body:List.of("{}","null","[]","{\"errcode\":0.5}","{\"errcode\":\"0\"}","invalid","{\"errcode\":40001,\"errmsg\":\"secret value\"}")){
      var result=transport.parseResponse(200,body);assertThat(result.success()).isFalse();assertThat(result.error()).doesNotContain("secret value");
    }
    assertThat(transport.parseResponse(302,"{}").success()).isFalse();assertThat(transport.parseResponse(429,"{}").retryable()).isTrue();assertThat(transport.parseResponse(200,"{\"errcode\":45009}").retryable()).isTrue();
  }
  @Test void detailBaseUrlRejectsCredentialsPathsQueryAndNonHttpSchemes(){
    for(String value:List.of("javascript:alert(1)","https://user:secret@example.com","http://example.com/path","https://example.com/?token=private","https://example.com/#secret"))assertThatThrownBy(()->WecomNotificationService.validateBaseUrl(value,true)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
    assertThat(WecomNotificationService.validateBaseUrl("https://release.example.com/",true)).isEqualTo("https://release.example.com");
  }
}
