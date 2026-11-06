package com.local.release.web;

import com.local.release.config.SecurityConfig;
import com.local.release.security.BearerTokenFilter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(BrandingLogoTest.Config.class)
@WebAppConfiguration
class BrandingLogoTest {
  @TempDir static Path dataDir;
  private static final String PUBLIC = "/api/branding/logo";
  private static final String SETTINGS = "/api/settings/branding/logo";
  @Autowired WebApplicationContext context;
  private MockMvc mvc;

  @Configuration @EnableWebMvc @EnableWebSecurity
  @Import({SecurityConfig.class, ApiExceptionHandler.class})
  static class Config {
    @Bean BrandingLogoController branding() { return new BrandingLogoController(dataDir.toString()); }
    @Bean BearerTokenFilter tokens() { return new BearerTokenFilter(null, null); }
  }

  @BeforeEach void setup() throws Exception {
    context.getBean(BrandingLogoController.class).clear();
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test void adminCanReplaceAndRemoveThePublicLogo() throws Exception {
    mvc.perform(get(PUBLIC)).andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
    byte[] first = png(2);
    byte[] padded = Arrays.copyOf(first, first.length + 100);
    mvc.perform(put(SETTINGS).with(user("admin").roles("ADMIN")).contentType("image/png").content(padded))
        .andExpect(status().isNoContent());
    byte[] returned = mvc.perform(get(PUBLIC)).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
        .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsByteArray();
    assertThat(returned).hasSizeLessThan(padded.length);
    assertThat(ImageIO.read(new ByteArrayInputStream(returned)).getWidth()).isEqualTo(2);
    mvc.perform(put(SETTINGS).with(user("admin").roles("ADMIN")).contentType("image/png").content(png(3)))
        .andExpect(status().isNoContent());
    byte[] persisted = new BrandingLogoController(dataDir.toString()).get().getBody();
    assertThat(ImageIO.read(new ByteArrayInputStream(persisted)).getWidth()).isEqualTo(3);
    for (int i = 0; i < 2; i++) {
      mvc.perform(delete(SETTINGS).with(user("admin").roles("ADMIN"))).andExpect(status().isNoContent());
    }
    mvc.perform(get(PUBLIC)).andExpect(status().isNoContent());
  }

  @Test void invalidUploadsPreserveThePreviousLogo() throws Exception {
    var admin = user("admin").roles("ADMIN");
    mvc.perform(put(SETTINGS).with(admin).contentType("image/png").content(png(1))).andExpect(status().isNoContent());
    byte[] original = mvc.perform(get(PUBLIC)).andReturn().getResponse().getContentAsByteArray();
    mvc.perform(put(SETTINGS).with(admin).contentType("image/svg+xml").content("<svg/>")).andExpect(status().isUnsupportedMediaType());
    mvc.perform(put(SETTINGS).with(admin).contentType("image/png").content("<svg/>")).andExpect(status().isBadRequest());
    mvc.perform(put(SETTINGS).with(admin).contentType("image/png").content(png(1025))).andExpect(status().isBadRequest());
    mvc.perform(put(SETTINGS).with(admin).contentType("image/png").content(new byte[256 * 1024 + 1])).andExpect(status().is(413));
    mvc.perform(get(PUBLIC)).andExpect(content().bytes(original));
  }

  @Test void onlyAdminsCanChangeTheLogo() throws Exception {
    mvc.perform(put(SETTINGS).contentType("image/png").content(png(1))).andExpect(status().is4xxClientError());
    mvc.perform(delete(SETTINGS)).andExpect(status().is4xxClientError());
    mvc.perform(put(SETTINGS).with(user("viewer")).contentType("image/png").content(png(1))).andExpect(status().isForbidden());
    mvc.perform(delete(SETTINGS).with(user("viewer"))).andExpect(status().isForbidden());
    mvc.perform(get(PUBLIC)).andExpect(status().isNoContent());
  }

  private byte[] png(int width) throws Exception {
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, 1, BufferedImage.TYPE_INT_ARGB), "png", output);
    return output.toByteArray();
  }
}
