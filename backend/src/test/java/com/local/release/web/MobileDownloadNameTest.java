package com.local.release.web;
import com.local.release.domain.MobileArtifact;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class MobileDownloadNameTest {
  @Test void extensionComesFromImmutableArtifactAndOldConstructorKeepsAab(){
    var aab=new MobileArtifact(12L,8L,5L,"com.example.app","1.0",9,"a".repeat(64),10,"b".repeat(64),"c".repeat(40),"3.35.4","/private/bundle.aab");
    var apk=new MobileArtifact(13L,9L,5L,"com.example.app","1.0",9,"a".repeat(64),10,"b".repeat(64),"c".repeat(40),"3.35.4","/private/app.apk",null,null,"APK");
    assertThat(MobileReleaseController.downloadName(aab)).isEqualTo("app-12.aab");assertThat(MobileReleaseController.downloadName(apk)).isEqualTo("app-13.apk");
  }
}
