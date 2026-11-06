package com.local.release.miniprogram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class MiniProgramConfigurationTest {
  static MiniProgramConfiguration config(boolean enabled){return new MiniProgramConfiguration("示例小程序","example-mini","wx0000000000000001","git@github.com:example-org/example-mini.git","feature/demo","",1,true,enabled);}
  @Test void rejectsSecretBearingUrlsAndTraversal(){
    for(String repo:new String[]{"https://user:secret@github.com/team/repo.git","ext::bad","file:///etc/passwd","--upload-pack=bad"})
      assertThatThrownBy(()->MiniProgramConfiguration.repository(repo)).isInstanceOf(IllegalArgumentException.class);
    for(String branch:new String[]{"../master","--main","release;cmd","a.lock","x//y"})assertThatThrownBy(()->MiniProgramConfiguration.branch(branch)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new MiniProgramConfiguration("n","code","wx0000000000000001","https://github.com/team/repo","master","../secret",1,true,true)).isInstanceOf(IllegalArgumentException.class);
    assertThat(config(false).gitRepo()).isEqualTo("git@github.com:example-org/example-mini.git");
  }
  @Test void moduleAndUploadAreBothOffByDefault(){MiniProgramSettings settings=new MiniProgramSettings();assertThat(settings.enabled()).isFalse();assertThat(settings.uploadEnabled()).isFalse();assertThatThrownBy(settings::requireEnabled).hasMessageContaining("尚未启用");}
  @Test void keysAreSelectedByAppIdRatherThanAUserSuppliedPath(@TempDir Path root)throws Exception{
    MiniProgramSettings settings=new MiniProgramSettings();settings.keyRoot=root.toString();
    assertThatThrownBy(()->settings.key(config(false).appId())).hasMessageContaining("代码上传密钥");
    Path key=root.resolve("private."+config(false).appId()+".key");Files.writeString(key,"FAKE");assertThat(settings.key(config(false).appId())).isEqualTo(key.toRealPath());
  }
}
