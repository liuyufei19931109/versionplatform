package com.local.release.web;
import com.local.release.domain.Artifact; import com.local.release.repo.ArtifactRepository; import org.springframework.web.bind.annotation.*; import java.util.List;
import com.local.release.repo.MobileArtifactRepository;
@RestController @RequestMapping({"/api/artifacts","/api/apps/artifacts"})
public class ArtifactController {
  private final ArtifactRepository repo;private final MobileArtifactRepository mobile;public ArtifactController(ArtifactRepository r,MobileArtifactRepository mobile){repo=r;this.mobile=mobile;}
  @GetMapping public List<Artifact> list(@RequestParam(required=false) Long serviceId){return serviceId==null?repo.findAll():repo.findByServiceIdOrderByCreatedAtDesc(serviceId);}
  @DeleteMapping("/{id}") public void delete(@PathVariable Long id){if(mobile.existsByArtifactId(id))throw new IllegalArgumentException("App 安装包用于发布追溯和外部拉取，本版本暂不支持删除");repo.deleteById(id);}
}
