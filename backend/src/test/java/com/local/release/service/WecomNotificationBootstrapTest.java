package com.local.release.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WecomNotificationBootstrapTest {
  @TempDir Path directory;
  final WecomNotificationService service=mock(WecomNotificationService.class);
  @Test void absentBootstrapNeverInitializesOrSends(){new WecomNotificationBootstrap(service,new ObjectMapper(),directory.resolve("absent.json").toString()).initialize();verifyNoInteractions(service);}
  @Test void successfulImportDeletesPlaintextAndExistingConfigAlsoConsumesFileWithoutResending()throws Exception{
    Path file=directory.resolve("bootstrap.json");
    String content="{\"enabled\":true,\"webhook\":\"placeholder-never-used\",\"groupName\":\"group\",\"publicBaseUrl\":\"http://127.0.0.1\",\"environments\":[\"test\",\"prod\"],\"notifySuccess\":true,\"notifyFailure\":true,\"testNotification\":true}";
    Files.writeString(file,content);when(service.initializeIfAbsent(any(),eq(true))).thenReturn(true);
    new WecomNotificationBootstrap(service,new ObjectMapper(),file.toString()).initialize();assertThat(file).doesNotExist();
    Files.writeString(file,content);when(service.initializeIfAbsent(any(),eq(true))).thenReturn(false);
    new WecomNotificationBootstrap(service,new ObjectMapper(),file.toString()).initialize();assertThat(file).doesNotExist();
    verify(service,times(2)).initializeIfAbsent(any(),eq(true));verify(service,never()).enqueueTest();
  }
  @Test void failedImportRetainsFileForCorrectionAndDoesNotSend()throws Exception{
    Path file=directory.resolve("bootstrap.json");Files.writeString(file,"not-json");new WecomNotificationBootstrap(service,new ObjectMapper(),file.toString()).initialize();assertThat(file).exists();verifyNoInteractions(service);
  }
}
