package com.local.release.web;
import com.local.release.miniprogram.MiniProgramSettings;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/api/features")
public class FeatureController {
  private final MiniProgramSettings mini;
  public FeatureController(MiniProgramSettings mini){this.mini=mini;}
  @GetMapping public Map<String,Boolean> features(){return Map.of("miniprograms",mini.enabled(),"miniprogramUpload",mini.uploadEnabled());}
}
